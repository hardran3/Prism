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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.compose.runtime.snapshotFlow
import android.net.Uri
import java.io.File
import org.json.JSONObject
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.ryans.nostrshare.data.Draft

enum class OnboardingStep {
    WELCOME,
    SYNCING,
    SERVER_SELECTION,
    SCHEDULING_CONFIG
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
    var originalEventJson by mutableStateOf<String?>(null)
    private var savedContentBuffer by mutableStateOf("")
    
    // Onboarding State
    var isOnboarded by mutableStateOf(false)
    var isSchedulingEnabled by mutableStateOf(false)
    var currentOnboardingStep by mutableStateOf(OnboardingStep.WELCOME)
    var isSyncingServers by mutableStateOf(false)

    // Cached GUI data (Web Preview)
    var previewTitle by mutableStateOf<String?>(null)
    var previewDescription by mutableStateOf<String?>(null)
    var previewImageUrl by mutableStateOf<String?>(null)
    var previewSiteName by mutableStateOf<String?>(null)

    // Configured Blossom servers for the current session
    var blossomServers by mutableStateOf<List<BlossomServer>>(emptyList())
    
    fun toggleBlossomServer(url: String) {
        blossomServers = blossomServers.map {
            if (it.url == url) it.copy(enabled = !it.enabled) else it
        }
    }

    // Active Accounts
    var knownAccounts = mutableStateListOf<Account>()

    // User Profile Cache (pubkey -> profile)
    var usernameCache = mutableStateMapOf<String, UserProfile>()
    
    // Follow List for prioritization
    var followedPubkeys by mutableStateOf<Set<String>>(emptySet())

    private val prefs by lazy { 
        NostrShareApp.getInstance().getSharedPreferences("nostr_share_prefs", Context.MODE_PRIVATE) 
    }
    val settingsRepository by lazy { SettingsRepository(NostrShareApp.getInstance()) }
    val relayManager by lazy { RelayManager(NostrShareApp.getInstance().client, settingsRepository) }
    private val draftDao by lazy { NostrShareApp.getInstance().database.draftDao() }

    @OptIn(ExperimentalCoroutinesApi::class)
    val drafts = snapshotFlow { pubkey }
        .flatMapLatest { pk ->
            draftDao.getAllDrafts(pk)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val allScheduled = snapshotFlow { pubkey }
        .flatMapLatest { pk ->
            draftDao.getAllScheduled(pk)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val scheduledHistory = snapshotFlow { pubkey }
        .flatMapLatest { pk ->
            draftDao.getScheduledHistory(pk)
        }

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
        val onboardedFromRepo = settingsRepository.isOnboarded()
        isOnboarded = onboardedFromRepo
        isSchedulingEnabled = settingsRepository.isSchedulingEnabled()

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
            
            // Refresh profile in background only if already fully onboarded
            if (isOnboarded) {
                refreshUserProfile()
            }
        }

        // Initialize Known Accounts
        knownAccounts.addAll(settingsRepository.getKnownAccounts())
        
        // Initialize Blossom servers
        blossomServers = settingsRepository.getBlossomServers(pubkey)
        
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

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = NostrShareApp.getInstance().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

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

        // Update Known Accounts
        val existingIndex = knownAccounts.indexOfFirst { it.pubkey == hexKey }
        val newAccount = Account(hexKey, npubKey, pkgName, userProfile?.name, userProfile?.pictureUrl)
        if (existingIndex >= 0) {
            knownAccounts[existingIndex] = newAccount
        } else {
            knownAccounts.add(newAccount)
        }
        settingsRepository.setKnownAccounts(knownAccounts.toList())
            
        refreshUserProfile()
    }

    fun switchUser(hexKey: String) {
        if (pubkey == hexKey) return
        
        val account = knownAccounts.find { it.pubkey == hexKey }
        
        pubkey = hexKey
        npub = account?.npub
        signerPackageName = account?.signerPackage
        userProfile = account?.let { UserProfile(it.name, it.pictureUrl) } ?: usernameCache[hexKey]
        
        // Reload scoped settings for this user
        blossomServers = settingsRepository.getBlossomServers(hexKey)
        
        // Persist as active user
        prefs.edit()
            .putString("pubkey", hexKey)
            .putString("npub", npub)
            .putString("signer_package", signerPackageName)
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
                        
                    // Update Known Accounts list
                    val index = knownAccounts.indexOfFirst { it.pubkey == pk }
                    if (index >= 0) {
                        val updated = knownAccounts[index].copy(name = profile.name, pictureUrl = profile.pictureUrl)
                        knownAccounts[index] = updated
                        settingsRepository.setKnownAccounts(knownAccounts.toList())
                    }
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
                
                // Get existing local state to preserve "enabled" toggles
                val existingLocal = settingsRepository.getBlossomServers(pk).associate { it.url to it.enabled }
                
                if (discoveredUrls.isNotEmpty()) {
                    // Replace local list with truth from relay, but keep enabled state
                    val newServers = discoveredUrls.map { url ->
                        val cleanUrl = url.trim().removeSuffix("/")
                        val isEnabled = existingLocal[cleanUrl] ?: true // Default to true if new
                        BlossomServer(cleanUrl, isEnabled)
                    }
                    
                    blossomServers = newServers
                    saveServersAndContinue(newServers)
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

    fun saveServersAndContinue(servers: List<BlossomServer>) {
        settingsRepository.setBlossomServers(servers, pubkey)
        blossomServers = servers
        currentOnboardingStep = OnboardingStep.SCHEDULING_CONFIG
    }

    fun completeOnboarding() {
        settingsRepository.setOnboarded(true)
        isOnboarded = true
    }

    fun startSchedulingOnboarding() {
        currentOnboardingStep = OnboardingStep.SCHEDULING_CONFIG
        isOnboarded = false
    }

    fun completeSchedulingConfig() {
        settingsRepository.setSchedulingEnabled(true)
        isSchedulingEnabled = true
        completeOnboarding()
    }

    fun skipSchedulingConfig() {
        settingsRepository.setSchedulingEnabled(false)
        isSchedulingEnabled = false
        completeOnboarding()
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
                            relayManager.fetchEvent(entity.id, entity.relays, entity.author)
                        }
                        
                        if (event != null) {
                            originalEventJson = event.toString()
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
                                if (profile != null) {
                                    highlightAuthorName = profile.name
                                    highlightAuthorUrl = profile.pictureUrl
                                } else {
                                    highlightAuthorName = authorPubkey.take(8)
                                    highlightAuthorUrl = null
                                }
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

                                // Add Repost option
                                if (!availableKinds.contains(PostKind.REPOST)) {
                                    availableKinds = availableKinds + PostKind.REPOST
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun fetchLinkPreview(url: String) {
        if (url == previewImageUrl || url == sourceUrl && previewTitle != null) return
        
        // Reset old preview
        previewTitle = null
        previewDescription = null
        previewImageUrl = null
        previewSiteName = null

        viewModelScope.launch {
            try {
                val meta = com.ryans.nostrshare.utils.LinkPreviewManager.fetchMetadata(url)
                if (meta != null) {
                    previewTitle = meta.title
                    previewDescription = meta.description
                    previewImageUrl = meta.imageUrl
                    previewSiteName = meta.siteName
                }
            } catch (e: Exception) {
                android.util.Log.e("ProcessTextViewModel", "Error fetching preview: ${e.message}")
            }
        }
    }

    var publishSuccess by mutableStateOf<Boolean?>(null) // null = idle/publishing, true = success, false = failed

    // Highlight Metadata Display
    var highlightAuthorName by mutableStateOf<String?>(null)
    var highlightAuthorUrl by mutableStateOf<String?>(null)

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
        
        val pk = pubkey
        val pkg = signerPackageName
        if (pk != null && pkg != null) {
            val signed = com.ryans.nostrshare.nip55.Nip55.signEventBackground(NostrShareApp.getInstance(), pkg, eventJson, pk)
            if (signed != null) {
                onEventSigned(signed)
                return
            }
        }

        _eventToSign.value = eventJson
    }

    fun onScheduledEventSigned(signedJson: String) {
        val timestamp = currentScheduleTime ?: return
        currentScheduleTime = null
        
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
                originalEventJson = originalEventJson,
                pubkey = pubkey, // Save pubkey for scheduling
                isScheduled = true,
                scheduledAt = timestamp,
                signedJson = signedJson,
                isAutoSave = false,
                savedContentBuffer = savedContentBuffer,
                previewTitle = previewTitle,
                previewDescription = previewDescription,
                previewImageUrl = previewImageUrl,
                previewSiteName = previewSiteName,
                highlightAuthorName = highlightAuthorName,
                highlightAuthorAvatarUrl = highlightAuthorUrl
            )
            val id = draftDao.insertDraft(draft)
            com.ryans.nostrshare.utils.SchedulerUtils.enqueueScheduledWork(
                NostrShareApp.getInstance(),
                draft.copy(id = id.toInt()),
                forceRefresh = true
            )
            
            com.ryans.nostrshare.utils.NotificationHelper.updateScheduledNotification(NostrShareApp.getInstance())
            
            publishStatus = "Note scheduled!"
            publishSuccess = true
            
            // Clear editor after scheduling
            clearContent()
        }
    }


    fun cancelScheduledNote(draft: Draft) {
        viewModelScope.launch {
            draftDao.deleteDraft(draft)
            
            val context = NostrShareApp.getInstance()
            com.ryans.nostrshare.utils.SchedulerUtils.cancelScheduledWork(context, draft.id)
            
            // Update notification
            com.ryans.nostrshare.utils.NotificationHelper.updateScheduledNotification(context)
        }
    }

    fun unscheduleAndSaveToDrafts(draft: Draft) {
        viewModelScope.launch {
            // 1. Cancel the background worker
            val context = NostrShareApp.getInstance()
            com.ryans.nostrshare.utils.SchedulerUtils.cancelScheduledWork(context, draft.id)
            
            // 2. Update draft state to be a regular draft
            val updatedDraft = draft.copy(
                isScheduled = false,
                scheduledAt = null,
                signedJson = null, // Discard signature as it's for a specific future timestamp
                publishError = null,
                isCompleted = false,
                lastEdited = System.currentTimeMillis()
            )
            
            // 3. Save back to DB
            draftDao.insertDraft(updatedDraft)
            
            // 4. Update notification
            com.ryans.nostrshare.utils.NotificationHelper.updateScheduledNotification(context)
        }
    }

    fun verifyScheduledNotes(context: Context) {
        if (isSchedulingEnabled) {
            com.ryans.nostrshare.utils.SchedulerUtils.verifyAllScheduledNotes(context)
        }
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
        
        blossomServers = settingsRepository.getBlossomServers(pubkey)
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
        if (!settingsRepository.isAlwaysUseKind1(pubkey)) {
            setKind(PostKind.HIGHLIGHT)
        }
    }
    
    // Pending upload state for multi-server
    private var pendingServers: List<String> = emptyList()
    private var processedMediaUri: Uri? = null // Processed/optimized media URI
    
    private var lastProcessedWithOptimize: Boolean? = null

    fun initiateUploadAuth(item: MediaUploadState) {
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
                
                val pkg = signerPackageName
                if (pkg != null) {
                    val signed = com.ryans.nostrshare.nip55.Nip55.signEventBackground(NostrShareApp.getInstance(), pkg, authEventJson, pk)
                    if (signed != null) {
                        onEventSigned(signed)
                        return@launch
                    }
                }

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
        
        val pkg = signerPackageName
        val pk = pubkey
        if (pkg != null && pk != null) {
            val authEvents = mutableListOf<String>()
            itemsToUpload.forEach { item ->
                val stableUri = processedMediaUris[item.id]
                if (stableUri != null) {
                    val authEventJson = blossomClient.createAuthEventJson(
                        item.hash ?: "",
                        item.size,
                        pk,
                        "upload",
                        fileName = stableUri.lastPathSegment,
                        mimeType = item.mimeType
                    )
                    authEvents.add(authEventJson)
                }
            }

            if (authEvents.isNotEmpty()) {
                val signed = com.ryans.nostrshare.nip55.Nip55.signEventsBackground(context, pkg, authEvents, pk)
                if (signed != null) {
                    currentSigningPurpose = SigningPurpose.BATCH_UPLOAD_AUTH
                    onBatchEventsSigned(signed)
                    return
                }
            }
        }

        // Start the first one - it will trigger subsequent ones in finalizeUpload
        val first = itemsToUpload.first()
        initiateUploadAuth(first)
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

                 val pkg = signerPackageName
                 if (pkg != null) {
                     val signed = com.ryans.nostrshare.nip55.Nip55.signEventBackground(NostrShareApp.getInstance(), pkg, authEventJson, pk)
                     if (signed != null) {
                         onEventSigned(signed)
                         return@launch
                     }
                 }

                 _eventToSign.value = authEventJson
            } catch (e: Exception) {
                item.status = "Delete Error: ${e.message}"
                item.isProcessing = false
            }
        }
    }
                


    private val _eventToSign = MutableStateFlow<String?>(null)
    val eventToSign: StateFlow<String?> = _eventToSign.asStateFlow()
    
    fun requestSignature(json: String) {
        val pk = pubkey
        val pkg = signerPackageName
        if (pk != null && pkg != null) {
            val signed = com.ryans.nostrshare.nip55.Nip55.signEventBackground(NostrShareApp.getInstance(), pkg, json, pk)
            if (signed != null) {
                onEventSigned(signed)
                return
            }
        }
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
            
            // Upload to ALL servers in parallel using STREAMING (no huge memory buffers)
            val serverResults = mutableListOf<Pair<String, Boolean>>()
            val successfulUrls = mutableMapOf<String, String>()
            val serverHashes = mutableMapOf<String, String?>()
            
            kotlinx.coroutines.coroutineScope {
                val jobs = servers.map { server: String ->
                    async {
                        try {
                            val result = blossomClient.upload(
                                NostrShareApp.getInstance(),
                                null, // No ByteArray data
                                uri,  // Stream from URI
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
                         initiateUploadAuth(nextItem)
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
        REPOST(6, "Repost"),
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
            draftDao.deleteAutoSaveDraft(pubkey)
            if (draftIdToDelete != null) {
                draftDao.deleteById(draftIdToDelete)
            }
        }
    }
    fun saveDraft() {
        if (!isDraftMonitoringActive) return
        
        // Don't save if empty
        if (quoteContent.isBlank() && sourceUrl.isBlank() && mediaItems.isEmpty()) {
             viewModelScope.launch { draftDao.deleteAutoSaveDraft(pubkey) }
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
                originalEventJson = originalEventJson,
                pubkey = pubkey,
                isAutoSave = true,
                savedContentBuffer = savedContentBuffer,
                previewTitle = previewTitle,
                previewDescription = previewDescription,
                previewImageUrl = previewImageUrl,
                previewSiteName = previewSiteName,
                highlightAuthorName = highlightAuthorName,
                highlightAuthorAvatarUrl = highlightAuthorUrl
            )
            // Use a specific ID if we want to update the SAME auto-save slot
            // Actually insertDraft with REPLACE is fine if we manage the ID or just delete old one
            draftDao.deleteAutoSaveDraft(pubkey)
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
                originalEventJson = originalEventJson,
                pubkey = pubkey,
                isAutoSave = false,
                savedContentBuffer = savedContentBuffer,
                previewTitle = previewTitle,
                previewDescription = previewDescription,
                previewImageUrl = previewImageUrl,
                previewSiteName = previewSiteName,
                highlightAuthorName = highlightAuthorName,
                highlightAuthorAvatarUrl = highlightAuthorUrl
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
            draftDao.deleteAutoSaveDraft(pubkey)
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
            val autoDraft = draftDao.getAutoSaveDraft(pubkey)
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
            val autoDraft = draftDao.getAutoSaveDraft(pubkey)
            autoDraft?.let { loadDraft(it) }
            showDraftPrompt = false
        }
    }

    fun loadDraftById(id: Int) {
        viewModelScope.launch {
            val draft = draftDao.getDraftById(id)
            if (draft != null) {
                loadDraft(draft)
            }
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
        originalEventJson = draft.originalEventJson
        
        // Restore cached GUI info
        savedContentBuffer = draft.savedContentBuffer ?: ""
        previewTitle = draft.previewTitle
        previewDescription = draft.previewDescription
        previewImageUrl = draft.previewImageUrl
        previewSiteName = draft.previewSiteName
        highlightAuthorName = draft.highlightAuthorName
        highlightAuthorUrl = draft.highlightAuthorAvatarUrl
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
        val loadedKind = PostKind.entries.find { it.kind == draft.kind } ?: PostKind.NOTE
        postKind = loadedKind
        
        if (loadedKind == PostKind.REPOST && !availableKinds.contains(PostKind.REPOST)) {
            availableKinds = availableKinds + PostKind.REPOST
        } else if (highlightEventId != null && !availableKinds.contains(PostKind.REPOST)) {
            // Also enable Repost option if we have highlight metadata, even if current mode is HIGHLIGHT
            availableKinds = availableKinds + PostKind.REPOST
        }
        
        // Deserialize media
        deserializeMediaItems(draft.mediaJson)
        
        // Also populate processedMediaUris map so editor shows thumbnails
        mediaItems.forEach { item ->
            processedMediaUris[item.id] = item.uri
        }
    }

    private fun serializeMediaItems(items: List<MediaUploadState>): String {
        val array = org.json.JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("uri", item.uri.toString())
            obj.putOpt("uploadedUrl", item.uploadedUrl)
            obj.putOpt("mimeType", item.mimeType)
            obj.putOpt("hash", item.hash)
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
                    mimeType = obj.optString("mimeType").takeIf { it.isNotEmpty() }
                ).apply {
                    uploadedUrl = obj.optString("uploadedUrl").takeIf { it != "null" && it.isNotEmpty() }
                    hash = obj.optString("hash").takeIf { it != "null" && it.isNotEmpty() }
                    size = obj.optLong("size", 0L)
                }
                mediaItems.add(item)
            }
        } catch (_: Exception) {}
    }

    fun setKind(kind: PostKind) {
        val oldKind = postKind
        postKind = kind
        
        if (kind == PostKind.REPOST) {
            // Save current work and clear for Repost/Quote
            savedContentBuffer = quoteContent
            quoteContent = ""
        } else if (oldKind == PostKind.REPOST) {
            // Restore saved work when leaving Repost/Quote
            quoteContent = savedContentBuffer
        }

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
             PostKind.REPOST -> {
                 if (quoteContent.isBlank()) {
                     // NIP-18 Repost
                     val kind = if (highlightKind == 1) 6 else 16
                     event.put("kind", kind)
                     event.put("content", originalEventJson ?: "")
                     
                     // Tags e and p
                     if (highlightEventId != null) {
                         tags.put(org.json.JSONArray().put("e").put(highlightEventId).put(highlightRelays.firstOrNull() ?: ""))
                     }
                     if (highlightAuthor != null) {
                         tags.put(org.json.JSONArray().put("p").put(highlightAuthor))
                     }
                     if (highlightKind != null) {
                         tags.put(org.json.JSONArray().put("k").put(highlightKind.toString()))
                     }
                 } else {
                     // Quote Post
                     event.put("kind", 1)
                     var content = quoteContent.trim()
                     if (sourceUrl.isNotBlank() && !content.contains(sourceUrl)) {
                         content += "\n\n$sourceUrl"
                     }
                     event.put("content", content)
                     
                     // Optional: Add q tag for better client support
                     if (highlightEventId != null) {
                         val qTag = org.json.JSONArray().put("q").put(highlightEventId)
                         highlightRelays.firstOrNull()?.let { qTag.put(it) }
                         highlightAuthor?.let { qTag.put(it) }
                         tags.put(qTag)
                     }
                 }
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
            tags.put(org.json.JSONArray().put("url").put(item.uploadedUrl!!))
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
                    if (settingsRepository.isCitrineRelayEnabled(pubkey)) {
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
                    // Count as success only if published to at least one non-localhost relay
                    val nonLocalhostSuccess = results.filter { it.key != "ws://localhost:4869" }.any { it.value }
                    if (nonLocalhostSuccess) {
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
                    if (!isNetworkAvailable()) {
                        // Offline retry logic
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
                            originalEventJson = originalEventJson,
                            pubkey = pubkey,
                            isScheduled = true,
                            isOfflineRetry = true,
                            scheduledAt = System.currentTimeMillis(),
                            signedJson = signedEventsJson.firstOrNull(), // Use the first signed event for retry
                            isAutoSave = false
                        )
                        val id = draftDao.insertDraft(draft)
                        com.ryans.nostrshare.utils.SchedulerUtils.enqueueOfflineRetry(
                            NostrShareApp.getInstance(),
                            draft.copy(id = id.toInt())
                        )
                        publishStatus = "Offline. Note will be sent when internet returns."
                        publishSuccess = true // Mark as success to close the editor, but it's actually queued
                        clearContent()
                    } else {
                        publishStatus = "Failed to publish."
                        publishSuccess = false
                    }
                }
                isPublishing = false
            } catch (e: Exception) {
                if (!isNetworkAvailable()) {
                    // Offline retry logic in case of exception
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
                            originalEventJson = originalEventJson,
                            pubkey = pubkey,
                            isScheduled = true,
                            isOfflineRetry = true,
                            scheduledAt = System.currentTimeMillis(),
                            signedJson = signedEventsJson.firstOrNull(),
                            isAutoSave = false
                        )
                        val id = draftDao.insertDraft(draft)
                        com.ryans.nostrshare.utils.SchedulerUtils.enqueueOfflineRetry(
                            NostrShareApp.getInstance(),
                            draft.copy(id = id.toInt())
                        )
                        publishStatus = "Offline. Note will be sent when internet returns."
                        publishSuccess = true
                        clearContent()
                        isPublishing = false
                    }
                } else {
                    publishStatus = "Error: ${e.message}"
                    publishSuccess = false
                    isPublishing = false
                }
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
                if (settingsRepository.isCitrineRelayEnabled(pubkey)) {
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
        val pubkey = if (npub.length == 64 && npub.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            npub
        } else {
            val entity = NostrUtils.findNostrEntity(npub) ?: return
            if (entity.type != "npub" && entity.type != "nprofile") return
            entity.id
        }
        
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