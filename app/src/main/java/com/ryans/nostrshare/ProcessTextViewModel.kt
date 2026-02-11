package com.ryans.nostrshare

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.Uri
import java.io.File
import org.json.JSONObject
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.ryans.nostrshare.data.Draft

enum class OnboardingStep {
    WELCOME,
    SYNCING,
    SERVER_SELECTION
}

class ProcessTextViewModel : ViewModel() {
    var quoteContent by mutableStateOf("")
    var sourceUrl by mutableStateOf("")
    var mediaTitle by mutableStateOf("") // User-defined title for media
    var isPublishing by mutableStateOf(false)
    var publishStatus by mutableStateOf("")
    var pubkey by mutableStateOf<String?>(null)
    var npub by mutableStateOf<String?>(null) // Keep original npub for signer intent
    var signerPackageName by mutableStateOf<String?>(null)
    var userProfile by mutableStateOf<UserProfile?>(null)
    
    // Highlight Metadata (NIP-84)
    var highlightEventId by mutableStateOf<String?>(null)
    var highlightAuthor by mutableStateOf<String?>(null)
    var highlightKind by mutableStateOf<Int?>(null)
    var highlightIdentifier by mutableStateOf<String?>(null)
    var highlightRelays = mutableStateListOf<String>()
    
    // Onboarding State
    var isOnboarded by mutableStateOf(false)
    var currentOnboardingStep by mutableStateOf(OnboardingStep.WELCOME)
    var isSyncingServers by mutableStateOf(false)

    // Configured Blossom servers for the current session
    var blossomServers by mutableStateOf<List<BlossomServer>>(emptyList())
    
    fun toggleBlossomServer(url: String) {
        blossomServers = blossomServers.map {
            if (it.url == url) it.copy(enabled = !it.enabled) else it
        }
    }

    // User Profile Cache (pubkey -> profile)
    var usernameCache = mutableStateMapOf<String, UserProfile>()
    
    // Follow List for prioritization
    var followedPubkeys by mutableStateOf<Set<String>>(emptySet())

    private val prefs by lazy { 
        NostrShareApp.getInstance().getSharedPreferences("nostr_share_prefs", Context.MODE_PRIVATE) 
    }
    val settingsRepository by lazy { SettingsRepository(NostrShareApp.getInstance()) }
    private val relayManager by lazy { RelayManager(NostrShareApp.getInstance().client, settingsRepository) }
    private val draftDao by lazy { NostrShareApp.getInstance().database.draftDao() }

    val drafts = draftDao.getAllDrafts()
    val allScheduled = draftDao.getAllScheduled()
    val scheduledHistory = draftDao.getScheduledHistory()
    var isUploading by mutableStateOf(false)
    var isDeleting by mutableStateOf(false)
    var uploadStatus by mutableStateOf("")
    var uploadServerResults by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())
    var deleteServerResults by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())

    // Legacy/Compat variables (Will be deprecated as multi-media matures)
    var mediaUri by mutableStateOf<Uri?>(null)
    var mediaMimeType by mutableStateOf<String?>(null)
    var uploadedMediaUrl by mutableStateOf<String?>(null)
    var uploadedMediaHash by mutableStateOf<String?>(null)
    var uploadedMediaSize by mutableStateOf<Long?>(null)

    init {
        // Load persisted session
        val savedPubkey = prefs.getString("pubkey", null)
        if (savedPubkey != null) {
            pubkey = savedPubkey
            npub = prefs.getString("npub", null)
            signerPackageName = prefs.getString("signer_package", null)
            
            val savedName = prefs.getString("user_name", null)
            val savedPic = prefs.getString("user_pic", null)
            if (savedName != null || savedPic != null) {
                userProfile = UserProfile(savedName, savedPic)
            }
            
            // Refresh profile in background
            refreshUserProfile()
        }
        
        // Initialize Blossom servers
        blossomServers = settingsRepository.getBlossomServers()
        
        val onboardedFromRepo = settingsRepository.isOnboarded()
        // We consider someone onboarded if they have a pubkey AND at least some (non-factory-default) servers OR if they've explicitly finished it.
        // For now, let's just stick to pubkey check but hide main UI until servers are ready.
        isOnboarded = onboardedFromRepo && blossomServers.size > 2
        
        if (!isOnboarded) {
            currentOnboardingStep = OnboardingStep.WELCOME
        }

        // Load caches
        followedPubkeys = settingsRepository.getFollowedPubkeys()
        settingsRepository.getUsernameCache().forEach { (pk, profile) ->
            usernameCache[pk] = profile
        }
    }

    fun isHapticEnabled(): Boolean = settingsRepository.isHapticEnabled()

    fun login(hexKey: String, npubKey: String?, pkgName: String?) {
        pubkey = hexKey
        npub = npubKey
        signerPackageName = pkgName
        currentOnboardingStep = OnboardingStep.SYNCING
        isSyncingServers = true
        
        prefs.edit()
            .putString("pubkey", hexKey)
            .putString("npub", npubKey)
            .putString("signer_package", pkgName)
            .apply()
            
        refreshUserProfile()
    }
    

    private fun refreshUserProfile() {
        val pk = pubkey ?: return
        viewModelScope.launch {
            try {
                val profile = relayManager.fetchUserProfile(pk)
                if (profile != null) {
                    userProfile = profile
                    prefs.edit()
                        .putString("user_name", profile.name)
                        .putString("user_pic", profile.pictureUrl)
                        .apply()
                }
                
                // Also Sync Blossom Servers
                syncBlossomServers()
                
                // Fetch home relays first to get a better contact list
                val userRelays = relayManager.fetchRelayList(pk)
                
                // And fetch follows using home relays if found
                val follows = relayManager.fetchContactList(pk, userRelays)
                followedPubkeys = follows
                settingsRepository.setFollowedPubkeys(follows)
                
                // Pre-cache profiles for followers (Kind 0)
                if (follows.isNotEmpty()) {
                    val followsList = follows.toList()
                    relayManager.fetchUserProfiles(followsList) { p, profile ->
                        usernameCache[p] = profile
                    }
                    // Persist the full updated cache at the end
                    settingsRepository.setUsernameCache(usernameCache.toMap())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun syncBlossomServers() {
        val pk = pubkey ?: return
        viewModelScope.launch {
            try {
                isSyncingServers = true
                val discoveredUrls = relayManager.fetchBlossomServerList(pk)
                if (discoveredUrls.isNotEmpty()) {
                    val currentServers = settingsRepository.getBlossomServers()
                    val existingUrls = currentServers.map { it.url }.toSet()
                    
                    val newServers = currentServers.toMutableList()
                    discoveredUrls.forEach { url ->
                        val cleanUrl = url.trim().removeSuffix("/")
                        if (!existingUrls.contains(cleanUrl) && !existingUrls.contains("$cleanUrl/")) {
                            newServers.add(BlossomServer(cleanUrl, true))
                        }
                    }
                    
                    if (newServers.size > currentServers.size) {
                        blossomServers = newServers
                        // If we found servers via 10063, we can likely just finish onboarding
                        finishOnboarding(newServers)
                    } else if (discoveredUrls.isNotEmpty()) {
                        // Found list but no changes needed? Just finish.
                        finishOnboarding(currentServers)
                    } else {
                        // No 10063 found
                        currentOnboardingStep = OnboardingStep.SERVER_SELECTION
                    }
                } else {
                    // No 10063 found
                    currentOnboardingStep = OnboardingStep.SERVER_SELECTION
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSyncingServers = false
            }
        }
    }

    fun finishOnboarding(servers: List<BlossomServer>) {
        settingsRepository.setBlossomServers(servers)
        blossomServers = servers
        isOnboarded = true
    }

    fun getFallBackServers(): List<BlossomServer> {
        return settingsRepository.fallBackBlossomServers.map { BlossomServer(it, true) }
    }

    fun updateQuote(newQuote: String) {
        val cleanedQuote = UrlUtils.cleanText(newQuote)
        quoteContent = cleanedQuote

        // Auto-enrich if empty source and we found a nostr link
        if (sourceUrl.isBlank() && PostKind.HIGHLIGHT == postKind) {
             val entity = NostrUtils.findNostrEntity(cleanedQuote)
             if (entity != null && entity.type != "npub") {
                 updateSource(entity.bech32)
             }
        }
    }

    fun updateSource(newSource: String) {
        val cleanedSource = if (newSource.startsWith("http")) UrlUtils.cleanUrl(newSource) else newSource
        val oldSource = sourceUrl
        sourceUrl = cleanedSource
        
        // Ensure URL is visible in text body if in NOTE mode
        if (postKind == PostKind.NOTE && cleanedSource.isNotBlank() && !quoteContent.contains(cleanedSource)) {
             val prefix = if (quoteContent.isNotBlank()) "\n\n" else ""
             quoteContent += "$prefix$cleanedSource"
        }

        // Auto-extract Nostr Highlights
        if (cleanedSource.isNotBlank() && cleanedSource != oldSource) {
            val entity = NostrUtils.findNostrEntity(cleanedSource)
            if (entity != null && (entity.type == "nevent" || entity.type == "note" || entity.type == "naddr" || entity.type == "nprofile")) {
                viewModelScope.launch {
                    try {
                        // Switch to Highlight mode automatically
                        if (postKind != PostKind.HIGHLIGHT) {
                             setKind(PostKind.HIGHLIGHT)
                        }

                        if (entity.type == "nprofile") {
                             // Just resolve username for nprofile
                            val profile = relayManager.fetchUserProfile(entity.id)
                            if (profile != null) {
                                userProfile = profile
                                usernameCache[entity.id] = profile
                            }
                            return@launch
                        }

                        val event = if (entity.type == "naddr") {
                            relayManager.fetchAddress(entity.kind!!, entity.author!!, entity.id, entity.relays)
                        } else {
                            relayManager.fetchEvent(entity.id, entity.relays)
                        }
                        
                        if (event != null) {
                            if (entity.type == "naddr") {
                                val tags = event.optJSONArray("tags")
                                var title: String? = null
                                if (tags != null) {
                                    for (i in 0 until tags.length()) {
                                        val tag = tags.optJSONArray(i)
                                        if (tag != null && tag.length() >= 2 && tag.optString(0) == "title") {
                                            title = tag.getString(1)
                                            break
                                        }
                                    }
                                }
                                if (title != null) {
                                    quoteContent = title
                                }
                            } else {
                                val content = event.optString("content")
                                if (content.isNotBlank() && (quoteContent.isBlank() || quoteContent == oldSource)) {
                                    quoteContent = content
                                }
                            }

                            val authorPubkey = event.optString("pubkey")
                            if (authorPubkey.isNotEmpty()) {
                                val profile = relayManager.fetchUserProfile(authorPubkey)
                                profile?.name ?: authorPubkey.take(8)
                                sourceUrl = "nostr:${entity.bech32}"
                                
                                // Store NIP-84 Metadata
                                highlightEventId = event.optString("id")
                                highlightAuthor = authorPubkey
                                highlightKind = event.optInt("kind", 1)
                                highlightIdentifier = if (entity.type == "naddr") entity.id else null
                                highlightRelays.clear()
                                highlightRelays.addAll(entity.relays)
                                
                                // If author was in entity but not in event (unlikely if fetch worked), use it as fallback
                                if (highlightAuthor == null) highlightAuthor = entity.author
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    var publishSuccess by mutableStateOf<Boolean?>(null) // null = idle/publishing, true = success, false = failed

    private val blossomClient by lazy { BlossomClient(NostrShareApp.getInstance().client) }
    
    // Media State
    var mediaItems = mutableStateListOf<MediaUploadState>()
    var processedMediaUris = mutableMapOf<String, Uri>() // id -> processedUri
    
    // New UX State
    var showSharingDialog by mutableStateOf(false)
    var isBatchUploading by mutableStateOf(false)
    var batchUploadStatus by mutableStateOf("")
    var batchCompressionLevel by mutableStateOf<Int?>(null) // null = use global setting

    val batchProgress: Float
        get() {
            if (mediaItems.isEmpty()) return 0f
            val uploaded = mediaItems.count { it.uploadedUrl != null }
            return uploaded.toFloat() / mediaItems.size.toFloat()
        }
    
    // Dynamic Kind Selector
    var availableKinds by mutableStateOf(listOf(PostKind.NOTE, PostKind.HIGHLIGHT))
    
    // Signing Flow
    enum class SigningPurpose {
        POST,
        UPLOAD_AUTH,
        DELETE_AUTH,
        BATCH_UPLOAD_AUTH,
        SERVER_LIST,
        SCHEDULE
    }
    var currentSigningPurpose = SigningPurpose.POST
    var currentScheduleTime: Long? = null

    fun prepareScheduling(timestamp: Long) {
        currentScheduleTime = timestamp
        currentSigningPurpose = SigningPurpose.SCHEDULE
        
        // Generate event JSON with the FUTURE timestamp
        val eventJson = prepareEventJson(createdAt = timestamp / 1000)
        _eventToSign.value = eventJson
    }

    fun onScheduledEventSigned(signedJson: String) {
        val timestamp = currentScheduleTime ?: return
        currentScheduleTime = null
        
        viewModelScope.launch {
            val mediaJson = serializeMediaItems(mediaItems)
            val draft = Draft(
                content = quoteContent,
                sourceUrl = sourceUrl,
                kind = postKind.kind,
                mediaJson = mediaJson,
                mediaTitle = mediaTitle,
                pubkey = pubkey, // Save pubkey for scheduling
                isScheduled = true,
                scheduledAt = timestamp,
                signedJson = signedJson,
                isAutoSave = false
            )
            val id = draftDao.insertDraft(draft)
            com.ryans.nostrshare.utils.SchedulerUtils.enqueueScheduledWork(
                NostrShareApp.getInstance(),
                draft.copy(id = id.toInt())
            )
            
            // Clear editor after scheduling
            clearContent()
        }
    }


    fun cancelScheduledNote(draft: Draft) {
        viewModelScope.launch {
            draftDao.deleteDraft(draft)
            
            val context = NostrShareApp.getInstance()
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            
            val intent = android.content.Intent(context, com.ryans.nostrshare.receivers.ScheduleReceiver::class.java).apply {
                action = com.ryans.nostrshare.receivers.ScheduleReceiver.ACTION_PUBLISH_SCHEDULED
                putExtra(com.ryans.nostrshare.receivers.ScheduleReceiver.EXTRA_DRAFT_ID, draft.id)
            }
            
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                draft.id,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_NO_CREATE
            )
            
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
            
            // Update notification
            com.ryans.nostrshare.utils.NotificationHelper.updateScheduledNotification(context)
        }
    }

    fun verifyScheduledNotes(context: Context) {
        com.ryans.nostrshare.utils.SchedulerUtils.verifyAllScheduledNotes(context)
    }

    fun clearScheduledHistory() {
        viewModelScope.launch {
            draftDao.deleteCompletedScheduled()
        }
    }
    var pendingAuthServerUrl: String? = null
    var pendingAuthItemId: String? = null // To track which item we are authenticating for

    // User Search State
    var userSearchQuery by mutableStateOf("")
    var userSearchResults = mutableStateListOf<Pair<String, UserProfile>>()
    var isSearchingUsers by mutableStateOf(false)
    var showUserSearchDialog by mutableStateOf(false)
    
    fun onMediaSelected(context: Context, uris: List<Uri>) {
        uris.forEach { uri ->
            val mimeType = context.contentResolver.getType(uri) ?: "image/*"
            val item = MediaUploadState(
                id = java.util.UUID.randomUUID().toString(),
                uri = uri,
                mimeType = mimeType
            )
            mediaItems.add(item)
            
            // Auto-switch mode
            if (mimeType.startsWith("video/") || mimeType.startsWith("image/")) {
                availableKinds = listOf(PostKind.MEDIA, PostKind.NOTE)
                if (!settingsRepository.isAlwaysUseKind1()) {
                    setKind(PostKind.MEDIA)
                }
            }
            
            prepareMedia(context, item)
        }
        
        blossomServers = settingsRepository.getBlossomServers()
        showSharingDialog = true
    }
    
    private fun prepareMedia(context: Context, item: MediaUploadState) {
        item.status = "Processing..."
        item.isProcessing = true
        
        viewModelScope.launch {
            try {
                // 0. Process image (Always strip EXIF, optionally compress)
                val isImage = item.mimeType?.startsWith("image/") == true
                var processedUri: Uri? = null
                if (isImage) {
                    item.status = "Optimizing..."
                    val level = batchCompressionLevel ?: settingsRepository.getCompressionLevel()
                    val result = ImageProcessor.processImage(context, item.uri, item.mimeType, level)
                    
                    if (result != null) {
                        processedUri = result.uri
                    }
                }
                
                // 2. Localize if not already processed
                val stableUri = processedUri ?: run {
                    item.status = "Localizing..."
                    val tempSource = File.createTempFile("blossom_source_${item.id}_", ".tmp", context.cacheDir)
                    context.contentResolver.openInputStream(item.uri).use { input ->
                        tempSource.outputStream().use { output ->
                            input?.copyTo(output)
                        }
                    }
                    Uri.fromFile(tempSource)
                }
                processedMediaUris[item.id] = stableUri
                
                item.status = "Hashing..."
                val hashResult = blossomClient.hashFile(context, stableUri)
                item.hash = hashResult.first
                item.size = hashResult.second
                
                item.status = "Ready"
                item.isProcessing = false
            } catch (e: Exception) {
                item.status = "Error: ${e.message}"
                item.isProcessing = false
            }
        }
    }

    fun updateBatchCompressionLevel(context: Context, level: Int) {
        if (batchCompressionLevel == level) return
        batchCompressionLevel = level
        
        // Re-process all images that aren't currently being uploaded or already finished
        mediaItems.forEach { item ->
             if (item.uploadedUrl == null && !item.isUploading) {
                 prepareMedia(context, item)
             }
        }
    }
    fun onHighlightShared() {
        if (!settingsRepository.isAlwaysUseKind1()) {
            setKind(PostKind.HIGHLIGHT)
        }
    }
    
    // Pending upload state for multi-server
    private var pendingServers: List<String> = emptyList()
    private var processedMediaUri: Uri? = null // Processed/optimized media URI
    
    private var lastProcessedWithOptimize: Boolean? = null

    fun initiateUploadAuth(context: Context? = null, item: MediaUploadState) {
        val pk = pubkey ?: return
        
        val stableUri = processedMediaUris[item.id]
        if (stableUri == null) {
            item.status = "File not localized."
            return
        }
        
        val hash = item.hash
        val size = item.size
        if (hash == null || size == 0L) {
             item.status = "Hash/Size missing."
             return
        }
        
        item.status = "Preparing auth..."
        item.isUploading = true
        
        viewModelScope.launch {
            try {
                // Use latest servers
                val servers = blossomServers.filter { it.enabled }.map { it.url }
                val targetServers = if (servers.isEmpty()) listOf("https://blossom.primal.net") else servers
                item.pendingServers = targetServers
                
                // Use first server for auth
                val authServer = targetServers.firstOrNull() ?: "https://blossom.primal.net"
                pendingAuthServerUrl = authServer
                pendingAuthItemId = item.id
                
                // For Upload, operation is "upload" (default)
                val fileName = stableUri.lastPathSegment
                val authEventJson = blossomClient.createAuthEventJson(hash, size, pk, "upload", fileName = fileName, mimeType = item.mimeType)
                
                // 3. Ask Activity to Sign
                currentSigningPurpose = SigningPurpose.UPLOAD_AUTH
                _eventToSign.value = authEventJson
                
            } catch (e: Exception) {
                item.status = "Auth Error: ${e.message}"
                item.isUploading = false
            }
        }
    }

    fun initiateBatchUpload(context: Context) {
        pubkey ?: return
        val itemsToUpload = mediaItems.filter { it.uploadedUrl == null && !it.isUploading && !it.isProcessing }
        if (itemsToUpload.isEmpty()) {
            isBatchUploading = false
            return
        }

        isBatchUploading = true
        batchUploadStatus = "Starting sequential upload..."
        
        // Start the first one - it will trigger subsequent ones in finalizeUpload
        val first = itemsToUpload.first()
        initiateUploadAuth(context, first)
    }

    fun resetBatchState() {
        isBatchUploading = false
        batchUploadStatus = ""
        mediaItems.forEach { item ->
            if (item.isUploading && item.uploadedUrl == null) {
                item.isUploading = false
                item.status = "Ready"
            }
        }
    }

    private var pendingBatchItemIds: List<String> = emptyList()
    private val _batchEventsToSign = MutableStateFlow<List<String>>(emptyList())
    val batchEventsToSign: StateFlow<List<String>> = _batchEventsToSign.asStateFlow()

    fun onBatchEventsSigned(signedEvents: List<String>) {
        val ids = pendingBatchItemIds
        if (signedEvents.size != ids.size) {
             batchUploadStatus = "Signature mismatch"
             isBatchUploading = false
             return
        }

        signedEvents.forEachIndexed { index, signedEvent ->
            val itemId = ids[index]
            val item = mediaItems.find { it.id == itemId }
            if (item != null) {
                finalizeUpload(item, signedEvent)
            }
        }
        
        _batchEventsToSign.value = emptyList()
        pendingBatchItemIds = emptyList()
        // isBatchUploading will be set to false when all items finish (we need a way to track that)
        // Or we just let it be true until we navigate away? 
        // For now, let's monitor the items.
    }
    
    fun retryFailedUploads(context: Context) {
        val failedServers = uploadServerResults.filter { !it.second }.map { it.first }
        if (failedServers.isEmpty()) return

        val pk = pubkey ?: return
        val hash = uploadedMediaHash ?: return
        val size = uploadedMediaSize ?: return

        isUploading = true
        uploadStatus = "Retrying ${failedServers.size} failed uploads..."

        viewModelScope.launch {
            try {
                pendingServers = failedServers
                pendingAuthServerUrl = pendingServers.firstOrNull()

                val fileName = processedMediaUri?.lastPathSegment
                val authEventJson = blossomClient.createAuthEventJson(hash, size, pk, "upload", fileName = fileName, mimeType = mediaMimeType)
                currentSigningPurpose = SigningPurpose.UPLOAD_AUTH
                _eventToSign.value = authEventJson
            } catch (e: Exception) {
                uploadStatus = "Retry prepare failed: ${e.message}"
                isUploading = false
            }
        }
    }

    // Store the hash that will be used for the current delete operation
    private var pendingDeleteHash: String? = null

    fun deleteMedia(item: MediaUploadState) {
        val localHash = item.hash ?: return
        val pk = pubkey ?: return
        
        viewModelScope.launch {
            try {
                 item.status = "Preparing delete..."
                 item.isProcessing = true
                 
                 val servers = blossomServers.filter { it.enabled }.map { it.url }
                 val targetServers = if (servers.isNotEmpty()) servers else listOf("https://blossom.primal.net")
                 item.pendingServers = targetServers
                 
                 pendingAuthServerUrl = targetServers.firstOrNull()
                 pendingAuthItemId = item.id
                 
                 // Use server-reported hash from the first server, fall back to local hash
                 val hashForAuth = targetServers.firstNotNullOfOrNull { item.serverHashes.value[it] } ?: localHash
                 item.hash = hashForAuth // Temporary until finalized
                 
                 val authEventJson = blossomClient.createAuthEventJson(hashForAuth, null, pk, "delete")
                 currentSigningPurpose = SigningPurpose.DELETE_AUTH
                 _eventToSign.value = authEventJson
            } catch (e: Exception) {
                item.status = "Delete Error: ${e.message}"
                item.isProcessing = false
            }
        }
    }
                


    // LiveData/Flow to communicate with Activity for signing
    private val _eventToSign = MutableStateFlow<String?>(null)
    val eventToSign: StateFlow<String?> = _eventToSign.asStateFlow()
    
    fun requestSignature(json: String) {
        _eventToSign.value = json
    }
    
    fun onEventSigned(signedEventJson: String) {
        val itemId = pendingAuthItemId
        val item = mediaItems.find { it.id == itemId }
        
        when (currentSigningPurpose) {
            SigningPurpose.POST -> publishPost(signedEventJson)
            SigningPurpose.UPLOAD_AUTH -> {
                if (item != null) finalizeUpload(item, signedEventJson)
            }
            SigningPurpose.DELETE_AUTH -> {
                if (item != null) finalizeDelete(item, signedEventJson)
            }
            SigningPurpose.SERVER_LIST -> finalizeBlossomServerListPublish(signedEventJson)
            SigningPurpose.SCHEDULE -> onScheduledEventSigned(signedEventJson)
            SigningPurpose.BATCH_UPLOAD_AUTH -> { }
        }
        _eventToSign.value = null // Reset
        pendingAuthItemId = null
    }

    private fun finalizeDelete(item: MediaUploadState, signedAuthEvent: String) {
        val hashToDelete = item.hash ?: return
        val servers = item.pendingServers
        
        viewModelScope.launch {
            try {
                isDeleting = true
                val urlToRemove = item.uploadedUrl
                uploadStatus = if (urlToRemove != null) "Deleting $urlToRemove..." else "Deleting from ${servers.size} servers..."
                
                val results = mutableListOf<Pair<String, Boolean>>()
                
                kotlinx.coroutines.coroutineScope {
                    val jobs = servers.map { server ->
                         async {
                             try {
                                 // Use the hash that matches the signed auth event
                                 val success = blossomClient.delete(hashToDelete, signedAuthEvent, server)
                                 synchronized(results) { results.add(server to success) }
                             } catch (_: Exception) {
                                 synchronized(results) { results.add(server to false) }
                             }
                         }
                    }
                    jobs.awaitAll()
                }
                
                deleteServerResults = results.toList()
                val successCount = results.count { it.second }
                
                item.status = "Deleted from $successCount/${servers.size} servers."
                
                // Remove item from list if fully deleted? Or just mark it.
                // For now, let's keep it in list but clear URLs.
                if (uploadedMediaUrl == item.uploadedUrl) {
                    uploadedMediaUrl = null
                    uploadedMediaHash = null
                    uploadedMediaSize = null
                }
                
                item.uploadedUrl = null
                item.hash = null
                item.size = 0L
                processedMediaUris.remove(item.id)
                mediaItems.remove(item)

                // Remove from quoteContent if in NOTE mode
                if (postKind == PostKind.NOTE && urlToRemove != null) {
                    var content = quoteContent.trim()
                    if (content.endsWith(urlToRemove)) {
                        content = content.removeSuffix(urlToRemove).trim()
                    } else if (content.contains(urlToRemove)) {
                        content = content.replace(urlToRemove, "").replace("\n\n\n", "\n\n").trim()
                    }
                    quoteContent = content
                }
                
            } catch (e: Exception) {
                item.status = "Delete error: ${e.message}"
            } finally {
                item.isProcessing = false
            }
        }
    }

    private fun finalizeUpload(item: MediaUploadState, signedAuthEvent: String) {
        val uri = processedMediaUris[item.id] ?: item.uri
        val servers = item.pendingServers
        if (servers.isEmpty()) return
        
        viewModelScope.launch {
            item.status = "Uploading to ${servers.size} server(s)..."
            item.isUploading = true
            
            // Read file into memory ONCE to guarantee bit-perfect consistency
            val fileBytes = try {
                NostrShareApp.getInstance().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                item.status = "Failed to read media: ${e.message}"
                item.isUploading = false
                null
            } ?: return@launch

            // Upload to ALL servers in parallel
            val serverResults = mutableListOf<Pair<String, Boolean>>()
            val successfulUrls = mutableMapOf<String, String>()
            val serverHashes = mutableMapOf<String, String?>()
            
            kotlinx.coroutines.coroutineScope {
                val jobs = servers.map { server: String ->
                    async {
                        try {
                            val result = blossomClient.upload(
                                NostrShareApp.getInstance(),
                                fileBytes,
                                signedAuthEvent,
                                server,
                                item.mimeType
                            )
                            synchronized(serverResults) {
                                serverResults.add(server to true)
                                successfulUrls[server] = result.url
                                serverHashes[server] = result.serverHash
                            }
                            true
                        } catch (e: Exception) {
                            synchronized(serverResults) {
                                serverResults.add(server to false) 
                            }
                            false
                        }
                    }
                }
                jobs.awaitAll()
            }
            
            // Priority Check: Pick the first successful URL according to the original order
            val firstSuccessUrl = servers.firstNotNullOfOrNull { successfulUrls[it] }
            
            // Update item state
            item.serverResults.value = serverResults
            item.serverHashes.value = serverHashes.filterValues { it != null }.mapValues { it.value!! }
            
            if (firstSuccessUrl != null) {
                item.uploadedUrl = firstSuccessUrl
                val successCount = serverResults.count { it.second }
                item.status = "Uploaded to $successCount/${servers.size} servers"
                
                // Sync legacy variables for top item if it's the first one
                if (mediaItems.firstOrNull()?.id == item.id) {
                    uploadedMediaUrl = item.uploadedUrl
                    uploadedMediaHash = item.hash
                    uploadedMediaSize = item.size
                }

                // If in NOTE mode, append URL to content now
                if (postKind == PostKind.NOTE) {
                     val prefix = if (quoteContent.isNotBlank()) "\n\n" else ""
                     quoteContent += "$prefix$firstSuccessUrl"
                }
            } else {
                item.status = "All uploads failed"
            }
            
            item.isUploading = false
            
            // If we were batch uploading, check if all items are now finished
            if (isBatchUploading) {
                val stillUploading = mediaItems.any { it.isUploading }
                if (!stillUploading) {
                    val nextItem = mediaItems.find { it.uploadedUrl == null && !it.isUploading && !it.isProcessing }
                    if (nextItem != null) {
                         batchUploadStatus = "Uploading next item..."
                         initiateUploadAuth(null, nextItem)
                    } else {
                        isBatchUploading = false
                        batchUploadStatus = "Batch Complete"
                        showSharingDialog = false // Auto-dismiss
                    }
                }
            }
        }
    }
    enum class PostKind(val kind: Int, val label: String) {
        NOTE(1, "Note"), 
        HIGHLIGHT(9802, "Highlight"),
        MEDIA(0, "Media"), // Kind will be determined dynamically (20 or 22)
        FILE_METADATA(1063, "File Meta")
    }
    
    var postKind by mutableStateOf(PostKind.NOTE)

    // Draft State
    var showDraftPrompt by mutableStateOf(false)
    var currentDraftId: Int? = null
    var showDatePicker by mutableStateOf(false)
    var isDraftMonitoringActive by mutableStateOf(false)

    fun clearContent() {
        quoteContent = ""
        sourceUrl = ""
        mediaUri = null
        mediaMimeType = null
        uploadedMediaUrl = null
        uploadedMediaHash = null
        uploadedMediaSize = null
        postKind = PostKind.NOTE
        mediaItems.clear()
        mediaTitle = ""
        
        val draftIdToDelete = currentDraftId
        currentDraftId = null
        
        viewModelScope.launch {
            draftDao.deleteAutoSaveDraft()
            if (draftIdToDelete != null) {
                draftDao.deleteById(draftIdToDelete)
            }
        }
    }
    fun saveDraft() {
        if (!isDraftMonitoringActive) return
        
        // Don't save if empty
        if (quoteContent.isBlank() && sourceUrl.isBlank() && mediaItems.isEmpty()) {
             viewModelScope.launch { draftDao.deleteAutoSaveDraft() }
             return
        }

        viewModelScope.launch {
            val mediaJson = serializeMediaItems(mediaItems)
            val highlightRelaysJson = if (highlightRelays.isNotEmpty()) {
                org.json.JSONArray(highlightRelays).toString()
            } else {
                null
            }
            val draft = Draft(
                content = quoteContent,
                sourceUrl = sourceUrl,
                kind = postKind.kind,
                mediaJson = mediaJson,
                mediaTitle = mediaTitle,
                highlightEventId = highlightEventId,
                highlightAuthor = highlightAuthor,
                highlightKind = highlightKind,
                highlightIdentifier = highlightIdentifier,
                highlightRelaysJson = highlightRelaysJson,
                isAutoSave = true
            )
            // Use a specific ID if we want to update the SAME auto-save slot
            // Actually insertDraft with REPLACE is fine if we manage the ID or just delete old one
            draftDao.deleteAutoSaveDraft()
            draftDao.insertDraft(draft)
        }
    }

    fun saveManualDraft() {
        viewModelScope.launch {
            val mediaJson = serializeMediaItems(mediaItems)
            val highlightRelaysJson = if (highlightRelays.isNotEmpty()) {
                org.json.JSONArray(highlightRelays).toString()
            } else {
                null
            }
            val draft = Draft(
                id = currentDraftId ?: 0,
                content = quoteContent,
                sourceUrl = sourceUrl,
                kind = postKind.kind,
                mediaJson = mediaJson,
                mediaTitle = mediaTitle,
                highlightEventId = highlightEventId,
                highlightAuthor = highlightAuthor,
                highlightKind = highlightKind,
                highlightIdentifier = highlightIdentifier,
                highlightRelaysJson = highlightRelaysJson,
                isAutoSave = false
            )
            val newId = draftDao.insertDraft(draft)
            if (currentDraftId == null) {
                currentDraftId = newId.toInt()
            }
        }
    }

    fun discardDraft() {
        showDraftPrompt = false
        val draftIdToDelete = currentDraftId
        currentDraftId = null
        viewModelScope.launch {
            draftDao.deleteAutoSaveDraft()
            if (draftIdToDelete != null) {
                draftDao.deleteById(draftIdToDelete)
            }
        }
    }

    fun deleteDraft(id: Int) {
        viewModelScope.launch {
            draftDao.deleteById(id)
        }
    }

    fun checkDraft() {
        viewModelScope.launch {
            val autoDraft = draftDao.getAutoSaveDraft()
            if (autoDraft != null) {
                // Determine if we should show prompt
                // Only if CURRENT content is empty
                if (quoteContent.isBlank() && sourceUrl.isBlank() && mediaItems.isEmpty()) {
                     showDraftPrompt = true
                }
            }
        }
    }

    fun applyDraft() {
        viewModelScope.launch {
            val autoDraft = draftDao.getAutoSaveDraft()
            autoDraft?.let { loadDraft(it) }
            showDraftPrompt = false
        }
    }

    fun loadDraft(draft: Draft) {
        quoteContent = draft.content
        sourceUrl = draft.sourceUrl
        mediaTitle = draft.mediaTitle
        currentDraftId = draft.id
        
        highlightEventId = draft.highlightEventId
        highlightAuthor = draft.highlightAuthor
        highlightKind = draft.highlightKind
        highlightIdentifier = draft.highlightIdentifier
        try {
            draft.highlightRelaysJson?.let { jsonString ->
                if (jsonString.isNotEmpty()) {
                    val jsonArray = org.json.JSONArray(jsonString)
                    highlightRelays.clear()
                    for (i in 0 until jsonArray.length()) {
                        highlightRelays.add(jsonArray.getString(i))
                    }
                } else {
                    highlightRelays.clear()
                }
            } ?: highlightRelays.clear()
        } catch (e: Exception) {
            // Log the error for debugging, but don't crash the app
            android.util.Log.e("ProcessTextViewModel", "Error parsing highlightRelaysJson: ${e.message}")
            highlightRelays.clear()
        }
        
        // Use PostKind values for restoration to avoid being limited by current session's availableKinds
        postKind = PostKind.values().find { it.kind == draft.kind } ?: PostKind.NOTE
        
        // Deserialize media
        deserializeMediaItems(draft.mediaJson)
    }

    private fun serializeMediaItems(items: List<MediaUploadState>): String {
        val array = org.json.JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("uri", item.uri.toString())
            obj.put("uploadedUrl", item.uploadedUrl)
            obj.put("mimeType", item.mimeType)
            obj.put("hash", item.hash)
            obj.put("size", item.size)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeMediaItems(json: String) {
        try {
            val array = org.json.JSONArray(json)
            mediaItems.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val item = MediaUploadState(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    uri = Uri.parse(obj.getString("uri")),
                    mimeType = obj.optString("mimeType", null)
                ).apply {
                    uploadedUrl = obj.optString("uploadedUrl", null).takeIf { it != "null" && it != "" }
                    hash = obj.optString("hash", null).takeIf { it != "null" && it != "" }
                    size = obj.optLong("size", 0L)
                }
                mediaItems.add(item)
            }
        } catch (_: Exception) {}
    }

    fun setKind(kind: PostKind) {
        val oldKind = postKind
        postKind = kind
        
        val sUrl = sourceUrl
        
        // 1. Cleanup Phase: If leaving NOTE, remove auto-added URLs
        if (oldKind == PostKind.NOTE && kind != PostKind.NOTE) {
            var content = quoteContent.trim()
            
            // Remove all media URLs from the end in reverse order
            mediaItems.reversed().forEach { item ->
                val mUrl = item.uploadedUrl
                if (mUrl != null && content.endsWith(mUrl)) {
                    content = content.removeSuffix(mUrl).trim()
                }
            }
            
            // Remove Source URL if at end
            if (sUrl.isNotBlank() && content.endsWith(sUrl)) {
                 content = content.removeSuffix(sUrl).trim()
            }
            
            // Re-check media URLs (in case of interleaved order)
            mediaItems.reversed().forEach { item ->
                val mUrl = item.uploadedUrl
                if (mUrl != null && content.endsWith(mUrl)) {
                    content = content.removeSuffix(mUrl).trim()
                }
            }
            
            quoteContent = content
        }
        
        // 2. Setup Phase: If entering NOTE, append URLs
        if (kind == PostKind.NOTE) {
            var content = quoteContent
            
            // Append Source URL
            if (sUrl.isNotBlank() && !content.contains(sUrl)) {
                 val prefix = if (content.isNotBlank()) "\n\n" else ""
                 content += "$prefix$sUrl"
            }
            
            // Append ALL successful media URLs
            mediaItems.forEach { item ->
                val mUrl = item.uploadedUrl
                if (mUrl != null && !content.contains(mUrl)) {
                    val prefix = if (content.isNotBlank()) "\n\n" else ""
                    content += "$prefix$mUrl"
                }
            }
            
            quoteContent = content
        }
    }

    fun prepareEventJson(createdAt: Long? = null): String {
        val event = JSONObject()
        event.put("created_at", createdAt ?: (System.currentTimeMillis() / 1000))
        event.put("pubkey", pubkey ?: "")
        event.put("id", "")
        event.put("sig", "")
        
        val tags = org.json.JSONArray()
        
        // Check if we should force Kind 1 for all posts
        // Use selected kind (Setting only affects defaults)
        val effectiveKind = postKind
        
        when (effectiveKind) {
             PostKind.NOTE -> {
                 event.put("kind", 1)
                 var content = quoteContent.trim()
                 // Append URL if from highlight/source and NOT already in content
                 if (sourceUrl.isNotBlank() && !content.contains(sourceUrl)) {
                     content += "\n\n$sourceUrl"
                 }
                 // Append all uploaded media URLs
                 mediaItems.filter { it.uploadedUrl != null }.forEach { item ->
                    val url = item.uploadedUrl!!
                    if (!content.contains(url)) {
                        val prefix = if (content.isNotBlank()) "\n\n" else ""
                        content += "$prefix$url"
                    }
                 }
                 
                 event.put("content", content)
                 event.put("tags", tags)
             }
             PostKind.HIGHLIGHT -> {
                 event.put("kind", 9802)
                 event.put("content", quoteContent.trim())
                 
                 // NIP-84 Compliance
                 val firstHighlightRelay = highlightRelays.firstOrNull()
                 if (highlightEventId != null) {
                     val eTag = org.json.JSONArray().put("e").put(highlightEventId)
                     firstHighlightRelay?.let { eTag.put(it) }
                     tags.put(eTag)
                 }
                 if (highlightAuthor != null) {
                     val pTag = org.json.JSONArray().put("p").put(highlightAuthor)
                     firstHighlightRelay?.let { pTag.put(it) }
                     tags.put(pTag)
                 }
                 if (highlightKind != null) {
                     tags.put(org.json.JSONArray().put("k").put(highlightKind.toString()))
                 }
                 if (highlightIdentifier != null && highlightAuthor != null && highlightKind != null) {
                     tags.put(org.json.JSONArray().put("a").put("${highlightKind}:${highlightAuthor}:${highlightIdentifier}"))
                 }

                 if (sourceUrl.isNotBlank()) {
                     tags.put(org.json.JSONArray().put("r").put(sourceUrl))
                 }
                 val altText = if (highlightKind == 1) "A Short Note" else "Highlight: \"${quoteContent.take(50)}...\""
                 tags.put(org.json.JSONArray().put("alt").put(altText))
                 event.put("tags", tags)
             }
             PostKind.MEDIA -> {
                 // Determine kind based on first item
                 val firstMime = mediaItems.firstOrNull()?.mimeType ?: "image/"
                 val kind = if (firstMime.startsWith("image/")) 20 else 22
                 event.put("kind", kind)
                 
                 // content is description
                 event.put("content", quoteContent.trim())
                 
                 val label = if (kind == 20) "Image" else "Video"
                 val title = if (mediaTitle.isNotBlank()) mediaTitle else "My $label"
                 tags.put(org.json.JSONArray().put("title").put(title))
                 
                 mediaItems.filter { it.uploadedUrl != null }.forEach { item ->
                     val primaryUploadedUrl = item.uploadedUrl!!
                     
                     // Extract file extension from the primary URL
                     primaryUploadedUrl.substringAfterLast('.', "")
                     val filename = primaryUploadedUrl.substringAfterLast('/')

                     // 1. Construct imeta tag
                     val imeta = org.json.JSONArray()
                     imeta.put("imeta")
                     imeta.put("url $primaryUploadedUrl") // Primary URL
                     item.mimeType?.let { imeta.put("m $it") }
                     item.hash?.let { imeta.put("x $it") }
                     if (item.size > 0) imeta.put("size ${item.size}")
                     
                     // Get base URL of the primary uploaded URL
                     val primaryBaseUrl = Uri.parse(primaryUploadedUrl).let { "${it.scheme}://${it.host}" }

                     // Add fallback URLs to imeta
                     item.serverResults.value.filter { it.second }.forEach { (baseUrl, _) ->
                         if (baseUrl != primaryBaseUrl) {
                             // Construct fallback URL using base URL, hash, and extension
                             val fallbackUrl = "$baseUrl/$filename" // filename already contains hash and extension
                             imeta.put("fallback $fallbackUrl")
                         }
                     }
                     
                     tags.put(imeta)
                     
                     // 2. Add top-level tags as per NIP-68/71
                     item.mimeType?.let { tags.put(org.json.JSONArray().put("m").put(it)) }
                     item.hash?.let { tags.put(org.json.JSONArray().put("x").put(it)) }
                     
                     // Add top-level 'url' tags for each successful base URL
                     item.serverResults.value.filter { it.second }.forEach { (baseUrl, _) ->
                         tags.put(org.json.JSONArray().put("url").put(baseUrl))
                     }
                 }
                 event.put("tags", tags)
             }
             PostKind.FILE_METADATA -> {
                 // Single event fallback if called for 1063 directly (unlikely due to bulk logic)
                 val firstItem = mediaItems.firstOrNull { it.uploadedUrl != null }
                 if (firstItem != null) {
                     event.put("kind", 1063)
                     event.put("content", quoteContent.trim())
                     tags.put(org.json.JSONArray().put("url").put(firstItem.uploadedUrl))
                     firstItem.mimeType?.let { tags.put(org.json.JSONArray().put("m").put(it)) }
                     firstItem.hash?.let { tags.put(org.json.JSONArray().put("x").put(it)) }
                     event.put("tags", tags)
                 }
             }
        }
        return event.toString()

    }

    fun prepareBulkFileMetadataEvents(): List<String> {
        val events = mutableListOf<String>()
        mediaItems.filter { it.uploadedUrl != null }.forEach { item ->
            val event = JSONObject()
            event.put("created_at", System.currentTimeMillis() / 1000)
            event.put("pubkey", pubkey ?: "")
            event.put("kind", 1063)
            event.put("content", quoteContent.trim()) // Optional description
            
            val tags = org.json.JSONArray()
            tags.put(org.json.JSONArray().put("url").put(item.uploadedUrl))
            item.mimeType?.let { tags.put(org.json.JSONArray().put("m").put(it)) }
            item.hash?.let { tags.put(org.json.JSONArray().put("x").put(it)) }
            item.size.takeIf { it > 0 }?.let { tags.put(org.json.JSONArray().put("size").put(it.toString())) }
            
            // Add title if present
            if (mediaTitle.isNotBlank()) tags.put(org.json.JSONArray().put("title").put(mediaTitle))
            
            event.put("tags", tags)
            events.add(event.toString())
        }
        return events
    }

    fun publishPost(signedEventJson: String) {
        publishPosts(listOf(signedEventJson))
    }

    fun publishPosts(signedEventsJson: List<String>) {
        isPublishing = true
        publishSuccess = null
        publishStatus = "Fetching relay list..."
        viewModelScope.launch {
            try {
                val relaysToPublish = withContext(Dispatchers.IO) {
                    val baseRelays = try {
                        val fetched = relayManager.fetchRelayList(pubkey!!)
                        if (fetched.isEmpty()) listOf("wss://relay.damus.io", "wss://nos.lol") else fetched
                    } catch (e: Exception) {
                        listOf("wss://relay.damus.io", "wss://nos.lol")
                    }
                    
                    val combinedRelays = mutableListOf<String>().apply { addAll(baseRelays) }
                    if (settingsRepository.isCitrineRelayEnabled()) {
                        combinedRelays.add("ws://localhost:4869")
                    }
                    combinedRelays.distinct() // Ensure no duplicate relays
                }
                
                publishStatus = "Broadcasting ${signedEventsJson.size} post(s) to ${relaysToPublish.size} relays..."
                val relaySuccessMap = mutableMapOf<String, Boolean>()
                relaysToPublish.forEach { relaySuccessMap[it] = false }
                
                var totalPostSuccess = 0
                signedEventsJson.forEach { signedEvent ->
                    val results = withContext(Dispatchers.IO) {
                         relayManager.publishEvent(signedEvent, relaysToPublish)
                    }
                    if (results.any { it.value }) {
                        totalPostSuccess++
                    }
                    results.forEach { (relay, success) ->
                        if (success) {
                            relaySuccessMap[relay] = true
                        }
                    }
                }
                
                delay(1000)
                
                val successfulRelaysCount = relaySuccessMap.count { it.value }
                if (totalPostSuccess > 0) {
                    publishStatus = "Success! Published to $successfulRelaysCount/${relaysToPublish.size} relays."
                    publishSuccess = true
                    discardDraft()
                } else {
                    publishStatus = "Failed to publish."
                    publishSuccess = false
                }
                isPublishing = false
            } catch (e: Exception) {
                publishStatus = "Error: ${e.message}"
                publishSuccess = false
                isPublishing = false
            }
        }
    }

    fun finalizeBlossomServerListPublish(signedEventJson: String) {
        isPublishing = true
        publishStatus = "Publishing server list to relays..."
        viewModelScope.launch {
            try {
                val hexKey = pubkey ?: return@launch
                val baseRelays = withContext(Dispatchers.IO) {
                    relayManager.fetchRelayList(hexKey)
                }
                
                val combinedRelays = mutableListOf<String>().apply { addAll(baseRelays) }
                if (settingsRepository.isCitrineRelayEnabled()) {
                    combinedRelays.add("ws://localhost:4869")
                }
                
                val results = withContext(Dispatchers.IO) {
                    relayManager.publishEvent(signedEventJson, combinedRelays.distinct())
                }
                val successCount = results.count { it.value }
                if (successCount > 0) {
                    publishStatus = "Server list published to $successCount relays."
                    publishSuccess = true
                } else {
                    publishStatus = "Failed to publish server list."
                    publishSuccess = false
                }
            } catch (e: Exception) {
                publishStatus = "Error: ${e.message}"
                publishSuccess = false
            } finally {
                isPublishing = false
            }
        }
    }

    fun performUserSearch(query: String) {
        userSearchQuery = query
        val cleanQuery = query.trim().removePrefix("@").removePrefix("nostr:")
        
        if (cleanQuery.length < 2) {
            userSearchResults.clear()
            return
        }
        
        // 1. Local Search (Instant)
        val localMatches = mutableListOf<Pair<String, UserProfile>>()
        usernameCache.forEach { (pk, profile) ->
            if (profile.name?.contains(cleanQuery, ignoreCase = true) == true || pk.contains(cleanQuery, ignoreCase = true)) {
                localMatches.add(pk to profile)
            }
        }
        
        // Also check followed pubkeys that might not be in cache
        followedPubkeys.forEach { pk ->
            if (pk.contains(cleanQuery, ignoreCase = true) && !usernameCache.containsKey(pk)) {
                localMatches.add(pk to UserProfile(name = null, pictureUrl = null))
            }
        }

        userSearchResults.clear()
        userSearchResults.addAll(localMatches.sortedByDescending { followedPubkeys.contains(it.first) })
        
        // 2. Relay Search
        isSearchingUsers = true
        viewModelScope.launch {
            try {
                val relayResults = relayManager.searchUsers(cleanQuery)
                
                // Merge and prioritize
                val merged = (localMatches + relayResults).distinctBy { it.first }
                val sortedResults = merged.sortedWith(compareByDescending<Pair<String, UserProfile>> { 
                    followedPubkeys.contains(it.first)
                }.thenBy { it.second.name?.lowercase() ?: "" })
                
                userSearchResults.clear()
                userSearchResults.addAll(sortedResults)
                
                // Update username cache with relay data (includes pics)
                var cacheUpdated = false
                relayResults.forEach { (pk, profile) ->
                    if (profile.name?.isNotEmpty() == true) {
                        usernameCache[pk] = profile
                        cacheUpdated = true
                    }
                }
                if (cacheUpdated) {
                    settingsRepository.setUsernameCache(usernameCache.toMap())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSearchingUsers = false
            }
        }
    }

    fun resolveUsername(npub: String) {
        val entity = NostrUtils.findNostrEntity(npub) ?: return
        if (entity.type != "npub" && entity.type != "nprofile") return
        val pubkey = entity.id
        
        if (usernameCache.containsKey(pubkey) && usernameCache[pubkey]?.name != null) return
        
        viewModelScope.launch {
            try {
                val profile = relayManager.fetchUserProfile(pubkey)
                if (profile != null) {
                    usernameCache[pubkey] = profile
                    settingsRepository.setUsernameCache(usernameCache.toMap())
                }
            } catch (_: Exception) {}
        }
    }
}