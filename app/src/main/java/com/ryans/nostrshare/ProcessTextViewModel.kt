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

    // Configured Blossom servers for the current session
    var blossomServers by mutableStateOf<List<BlossomServer>>(emptyList())
    
    fun toggleBlossomServer(url: String) {
        blossomServers = blossomServers.map {
            if (it.url == url) it.copy(enabled = !it.enabled) else it
        }
    }

    private val prefs by lazy { 
        NostrShareApp.getInstance().getSharedPreferences("nostr_share_prefs", Context.MODE_PRIVATE) 
    }
    private val settingsRepository by lazy { SettingsRepository(NostrShareApp.getInstance()) }
    private val relayManager by lazy { RelayManager(NostrShareApp.getInstance().client, settingsRepository) }

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
    }

    fun isHapticEnabled(): Boolean = settingsRepository.isHapticEnabled()

    fun login(hexKey: String, npubKey: String?, pkgName: String?) {
        pubkey = hexKey
        npub = npubKey
        signerPackageName = pkgName
        
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun syncBlossomServers() {
        val pk = pubkey ?: return
        viewModelScope.launch {
            try {
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
                        settingsRepository.setBlossomServers(newServers)
                        blossomServers = newServers
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateQuote(newQuote: String) {
        quoteContent = newQuote
    }

    fun updateSource(newSource: String) {
        sourceUrl = newSource
        
        // Ensure URL is visible in text body if in NOTE mode
        if (postKind == PostKind.NOTE && newSource.isNotBlank() && !quoteContent.contains(newSource)) {
             val prefix = if (quoteContent.isNotBlank()) "\n\n" else ""
             quoteContent += "$prefix$newSource"
        }
    }

    var publishSuccess by mutableStateOf<Boolean?>(null) // null = idle/publishing, true = success, false = failed

    private val blossomClient by lazy { BlossomClient(NostrShareApp.getInstance().client) }
    
    // Media State
    var mediaUri by mutableStateOf<android.net.Uri?>(null)
    var mediaMimeType by mutableStateOf<String?>(null)
    var uploadedMediaUrl by mutableStateOf<String?>(null)
    var uploadedMediaHash by mutableStateOf<String?>(null)
    var uploadedMediaSize by mutableStateOf<Long?>(null)
    var isUploading by mutableStateOf(false)
    var isDeleting by mutableStateOf(false) // Track delete status
    var uploadStatus by mutableStateOf("")
    
    // New UX State
    var showMediaDialog by mutableStateOf(false)
    var isProcessingMedia by mutableStateOf(false)
    
    // Dynamic Kind Selector
    var availableKinds by mutableStateOf(listOf(PostKind.NOTE, PostKind.HIGHLIGHT)) // Default to Text options
    
    // Multi-server upload tracking
    var uploadSuccessCount by mutableStateOf(0)
    var uploadTotalCount by mutableStateOf(0)
    var uploadServerResults by mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) // (serverUrl, success)
    var uploadServerHashes by mutableStateOf<Map<String, String?>>(emptyMap()) // serverUrl -> server-reported hash
    var deleteServerResults by mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) // (serverUrl, success)
    
    // Signing Flow
    enum class SigningPurpose { POST, UPLOAD_AUTH, DELETE_AUTH }
    var currentSigningPurpose = SigningPurpose.POST
    var pendingAuthServerUrl: String? = null // To track which server we are authenticating for

    fun onMediaSelected(context: Context, uri: android.net.Uri, mimeType: String) {
        mediaUri = uri
        mediaMimeType = mimeType
        uploadedMediaUrl = null
        uploadedMediaHash = null
        uploadedMediaSize = null
        processedMediaUri?.let { old ->
            try {
                if (old.scheme == "file") {
                    File(old.path!!).delete()
                }
            } catch (_: Exception) {}
        }
        processedMediaUri = null
        uploadServerResults = emptyList()
        
        // Refresh servers from repository once when media is selected
        // This allows temporary toggles in the dialog until the upload starts
        blossomServers = settingsRepository.getBlossomServers()
        
        // Auto-switch mode
        if (mimeType.startsWith("video/") || mimeType.startsWith("image/")) {
            availableKinds = listOf(PostKind.MEDIA, PostKind.NOTE) // Media + Note
            
            if (!settingsRepository.isAlwaysUseKind1()) {
                setKind(PostKind.MEDIA)
            }
            // Reset title when new media is selected
            mediaTitle = "" 
        }
        
        // Show dialog immediately
        showMediaDialog = true
        
        // Start background processing (Hash/Compress)
        prepareMedia(context)
    }
    
    private fun prepareMedia(context: Context) {
        uploadStatus = "Processing media..."
        isProcessingMedia = true
        
        viewModelScope.launch {
            try {
                // 0. Process image (Always strip EXIF, optionally compress)
                val isImage = mediaMimeType?.startsWith("image/") == true
                if (isImage) {
                    uploadStatus = "Processing Image..."
                    val shouldCompress = settingsRepository.isOptimizeMediaEnabled()
                    val result = ImageProcessor.processImage(context, mediaUri!!, mediaMimeType, shouldCompress)
                    
                    if (result != null) {
                        processedMediaUri = result.uri
                        mediaMimeType = result.mimeType
                    } else {
                        processedMediaUri = null
                    }
                    lastProcessedWithOptimize = shouldCompress
                }
                
                // 2. Localize if not already processed (ensures stable bytes for cloud URIs)
                val stableUri = processedMediaUri ?: run {
                    uploadStatus = "Localizing media..."
                    val tempSource = File.createTempFile("blossom_source_", ".tmp", context.cacheDir)
                    context.contentResolver.openInputStream(mediaUri!!).use { input ->
                        tempSource.outputStream().use { output ->
                            input?.copyTo(output)
                        }
                    }
                    val localizedUri = Uri.fromFile(tempSource)
                    processedMediaUri = localizedUri
                    localizedUri
                }
                
                // 3. Hash and Size
                uploadStatus = "Calculating Hash..."
                val (hash, size) = blossomClient.hashFile(context, stableUri)
                uploadedMediaHash = hash
                uploadedMediaSize = size
                
                uploadStatus = "Ready to Upload"
                isProcessingMedia = false
                
            } catch (e: Exception) {
                uploadStatus = "Error: ${e.message}"
                isProcessingMedia = false
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
    private var processedMediaUri: android.net.Uri? = null // Processed/optimized media URI
    
    private var lastProcessedWithOptimize: Boolean? = null

    fun initiateUploadAuth(context: Context) {
        val pk = pubkey ?: return
        if (mediaUri == null) return
        
        val shouldCompress = settingsRepository.isOptimizeMediaEnabled()
        
        // Finalize preparation or re-run if settings changed
        if (processedMediaUri == null || lastProcessedWithOptimize != shouldCompress) {
            prepareMedia(context)
            return 
        }
// Removed: blossomServers = settingsRepository.getBlossomServers()
        // This was overwriting temporary toggles. Refresh is now in onMediaSelected.
        
        // Assume preparation is done
        val hash = uploadedMediaHash
        val size = uploadedMediaSize
        if (hash == null || size == null) {
             uploadStatus = "Media preparation not finished. (Hash: ${hash != null}, Size: ${size != null})"
             return
        }
        
        uploadStatus = "Preparing upload auth..."
        isUploading = true
        
        viewModelScope.launch {
            try {
                // Use latest servers
                val servers = blossomServers.filter { it.enabled }.map { it.url }
                if (servers.isEmpty()) {
                    pendingServers = listOf("https://blossom.primal.net")
                } else {
                    pendingServers = servers
                }
                
                // Initialize tracking
                uploadTotalCount = pendingServers.size
                uploadSuccessCount = 0
                uploadServerResults = emptyList()
                
                // Use first server for auth
                pendingAuthServerUrl = pendingServers.firstOrNull() ?: "https://blossom.primal.net"
                
                // For Upload, operation is "upload" (default)
                val fileName = processedMediaUri?.lastPathSegment
                val authEventJson = blossomClient.createAuthEventJson(hash, size, pk, "upload", fileName = fileName, mimeType = mediaMimeType)
                
                // 3. Ask Activity to Sign
                currentSigningPurpose = SigningPurpose.UPLOAD_AUTH
                _eventToSign.value = authEventJson
                
            } catch (e: Exception) {
                uploadStatus = "Error preparing: ${e.message}"
                isUploading = false
            }
        }
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

    fun deleteMedia() {
        val localHash = uploadedMediaHash ?: return
        val pk = pubkey ?: return
        
        viewModelScope.launch {
            try {
                 uploadStatus = "Preparing delete..."
                 isDeleting = true // Set deleting flag early
                 
                 // Use temporary servers from VM state
                 val servers = blossomServers.filter { it.enabled }.map { it.url }
                 if (servers.isNotEmpty()) {
                     pendingServers = servers
                 } else {
                     pendingServers = listOf("https://blossom.primal.net")
                 }
                 pendingAuthServerUrl = pendingServers.firstOrNull()
                 
                 // Use server-reported hash from the first server, fall back to local hash
                 val hashForAuth = pendingServers.firstNotNullOfOrNull { uploadServerHashes[it] } ?: localHash
                 pendingDeleteHash = hashForAuth
                 
                 val authEventJson = blossomClient.createAuthEventJson(hashForAuth, null, pk, "delete")
                 currentSigningPurpose = SigningPurpose.DELETE_AUTH
                 _eventToSign.value = authEventJson
            } catch (e: Exception) {
                uploadStatus = "Delete prepare failed: ${e.message}"
                isDeleting = false
            }
        }
    }
                


    // LiveData/Flow to communicate with Activity for signing
    private val _eventToSign = MutableStateFlow<String?>(null)
    val eventToSign: StateFlow<String?> = _eventToSign.asStateFlow()
    
    fun onEventSigned(signedEventJson: String) {
        when (currentSigningPurpose) {
            SigningPurpose.POST -> publishPost(signedEventJson)
            SigningPurpose.UPLOAD_AUTH -> finalizeUpload(signedEventJson)
            SigningPurpose.DELETE_AUTH -> finalizeDelete(signedEventJson)
        }
        _eventToSign.value = null // Reset
    }

    private fun finalizeDelete(signedAuthEvent: String) {
        val hashToDelete = pendingDeleteHash ?: uploadedMediaHash ?: return
        val servers = pendingServers
        
        viewModelScope.launch {
            try {
                isDeleting = true
                uploadStatus = "Deleting from ${servers.size} servers..."
                
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
                
                uploadStatus = "Deleted from $successCount/${servers.size} servers."
                
                // Clear state regardless of full success
                uploadedMediaUrl = null
                uploadedMediaHash = null
                uploadedMediaSize = null
                mediaUri = null
                processedMediaUri = null
                
            } catch (e: Exception) {
                uploadStatus = "Delete error: ${e.message}"
            } finally {
                isDeleting = false
            }
        }
    }

    private fun finalizeUpload(signedAuthEvent: String) {
        // Use processed URI if available, otherwise original
        val uri = processedMediaUri ?: mediaUri ?: return
        val servers = pendingServers
        if (servers.isEmpty()) return
        
        viewModelScope.launch {
            uploadStatus = "Uploading to ${servers.size} server(s)..."
            
            // Read file into memory ONCE to guarantee bit-perfect consistency
            val fileBytes = try {
                File(uri.path!!).readBytes()
            } catch (e: Exception) {
                uploadStatus = "Failed to read media: ${e.message}"
                isUploading = false
                return@launch
            }

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
                                mediaMimeType
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
            
            // Update state (Merge with existing results if this was a retry)
            val oldResults = uploadServerResults.toMap()
            val mergedResults = blossomServers.filter { it.enabled }.map { server ->
                val url = server.url
                val success = successfulUrls.containsKey(url) || (oldResults[url] ?: false)
                url to success
            }
            
            uploadServerResults = if (mergedResults.isNotEmpty()) mergedResults else serverResults.toList()
            uploadSuccessCount = uploadServerResults.count { it.second }
            
            // Store server-reported hashes for UI display
            val existingHashes = uploadServerHashes.toMutableMap()
            existingHashes.putAll(serverHashes)
            uploadServerHashes = existingHashes
            
            if (firstSuccessUrl != null) {
                uploadedMediaUrl = firstSuccessUrl
                uploadStatus = "Uploaded to $uploadSuccessCount/$uploadTotalCount servers"
                
                // If in NOTE mode, append URL to content now
                if (postKind == PostKind.NOTE) {
                     val prefix = if (quoteContent.isNotBlank()) "\n\n" else ""
                     quoteContent += "$prefix$firstSuccessUrl"
                }
            } else {
                uploadStatus = "All uploads failed"
            }
            
            isUploading = false
        }
    }

    enum class PostKind(val kind: Int, val label: String) {
        NOTE(1, "Note"), 
        HIGHLIGHT(9802, "Highlight"),
        MEDIA(0, "Media") // Kind will be determined dynamically (20 or 22)
    }
    
    var postKind by mutableStateOf(PostKind.NOTE)

    // Draft State
    var showDraftPrompt by mutableStateOf(false)
    var isDraftMonitoringActive by mutableStateOf(false)
    private var pendingDraft: Draft? = null

    data class Draft(
        val content: String,
        val source: String,
        val kind: PostKind,
        val mediaUri: String?,
        val mediaMime: String?,
        val uploadedUrl: String?,
        val uploadedHash: String?,
        val uploadedSize: Long?
    )

    fun checkDraft() {
        // Load draft from prefs
        val content = prefs.getString("draft_content", "") ?: ""
        val source = prefs.getString("draft_source", "") ?: ""
        val kindStr = prefs.getString("draft_kind", "NOTE") ?: "NOTE"
        val mediaUriStr = prefs.getString("draft_media_uri", null)
        val uploadedUrl = prefs.getString("draft_uploaded_url", null)
        
        // If we have substantial content, prompt
        if (content.isNotBlank() || source.isNotBlank() || mediaUriStr != null || uploadedUrl != null) {
             val kind = try { PostKind.valueOf(kindStr) } catch(_: Exception) { PostKind.NOTE }
             
             pendingDraft = Draft(
                 content = content,
                 source = source,
                 kind = kind,
                 mediaUri = mediaUriStr,
                 mediaMime = prefs.getString("draft_media_mime", null),
                 uploadedUrl = uploadedUrl,
                 uploadedHash = prefs.getString("draft_uploaded_hash", null),
                 uploadedSize = if (prefs.contains("draft_uploaded_size")) prefs.getLong("draft_uploaded_size", 0L) else null
             )
             showDraftPrompt = true
        } else {
             // No draft, enable monitoring immediately
             isDraftMonitoringActive = true
        }
    }

    fun applyDraft() {
        pendingDraft?.let { draft ->
            quoteContent = draft.content
            sourceUrl = draft.source
            postKind = draft.kind // Direct assignment to avoid side effects
            
            if (draft.mediaUri != null) {
                mediaUri = android.net.Uri.parse(draft.mediaUri)
                mediaMimeType = draft.mediaMime
            }
            if (draft.uploadedUrl != null) {
                uploadedMediaUrl = draft.uploadedUrl
                uploadedMediaHash = draft.uploadedHash
                uploadedMediaSize = draft.uploadedSize
            }
        }
        pendingDraft = null
        showDraftPrompt = false
        isDraftMonitoringActive = true
    }
    
    fun discardDraft() {
        prefs.edit()
            .remove("draft_content")
            .remove("draft_source")
            .remove("draft_kind")
            .remove("draft_media_uri")
            .remove("draft_media_mime")
            .remove("draft_uploaded_url")
            .remove("draft_uploaded_hash")
            .remove("draft_uploaded_size")
            .apply()
            
        pendingDraft = null
        showDraftPrompt = false
        isDraftMonitoringActive = true
    }

    fun clearContent() {
        quoteContent = ""
        sourceUrl = ""
        mediaUri = null
        mediaMimeType = null
        uploadedMediaUrl = null
        uploadedMediaHash = null
        uploadedMediaSize = null
        postKind = PostKind.NOTE
        discardDraft()
    }

    fun saveDraft() {
        if (!isDraftMonitoringActive) return
        
        // Don't save if empty
        if (quoteContent.isBlank() && sourceUrl.isBlank() && mediaUri == null) {
             discardDraft()
             return
        }

        prefs.edit()
            .putString("draft_content", quoteContent)
            .putString("draft_source", sourceUrl)
            .putString("draft_kind", postKind.name)
            .putString("draft_media_uri", mediaUri?.toString())
            .putString("draft_media_mime", mediaMimeType)
            .putString("draft_uploaded_url", uploadedMediaUrl)
            .putString("draft_uploaded_hash", uploadedMediaHash)
            .putLong("draft_uploaded_size", uploadedMediaSize ?: 0L)
            .apply()
    }

    fun setKind(kind: PostKind) {
        val oldKind = postKind
        postKind = kind
        
        // URLs to manage
        val mediaUrl = uploadedMediaUrl
        val sUrl = sourceUrl
        
        // 1. Cleanup Phase: If leaving NOTE, remove auto-added URLs
        if (oldKind == PostKind.NOTE && kind != PostKind.NOTE) {
            var content = quoteContent.trim() // Trim to ensure endsWith matches
            
            // Remove Media URL if at end
            if (mediaUrl != null && content.endsWith(mediaUrl)) {
                content = content.removeSuffix(mediaUrl).trim()
            }
            
            // Remove Source URL if at end
            if (sUrl.isNotBlank() && content.endsWith(sUrl)) {
                 content = content.removeSuffix(sUrl).trim()
            }
            
            // Re-check Media URL (in case order was different)
            if (mediaUrl != null && content.endsWith(mediaUrl)) {
                content = content.removeSuffix(mediaUrl).trim()
            }
            
            quoteContent = content
        }
        
        // 2. Setup Phase: If entering NOTE, append URLs
        if (kind == PostKind.NOTE) {
            var content = quoteContent
            
            if (sUrl.isNotBlank() && !content.contains(sUrl)) {
                 val prefix = if (content.isNotBlank()) "\n\n" else ""
                 content += "$prefix$sUrl"
            }
            
            if (mediaUrl != null && !content.contains(mediaUrl)) {
                 val prefix = if (content.isNotBlank()) "\n\n" else ""
                 content += "$prefix$mediaUrl"
            }
            
            quoteContent = content
        }
    }

    fun prepareEventJson(): String {
        val event = JSONObject()
        event.put("created_at", System.currentTimeMillis() / 1000)
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
                 // Append media URL if present and not already in content
                 if (uploadedMediaUrl != null && !content.contains(uploadedMediaUrl!!)) {
                     val prefix = if (content.isNotBlank()) "\n\n" else ""
                     content += "$prefix$uploadedMediaUrl"
                 }
                 
                 event.put("content", content)
                 event.put("tags", tags)
             }
             PostKind.HIGHLIGHT -> {
                 event.put("kind", 9802)
                 event.put("content", quoteContent.trim())
                 if (sourceUrl.isNotBlank()) tags.put(org.json.JSONArray().put("r").put(sourceUrl))
                 tags.put(org.json.JSONArray().put("alt").put("Highlight: \"${quoteContent.take(50)}...\""))
                 event.put("tags", tags)
             }
             PostKind.MEDIA -> {
                 // Determine kind
                 val kind = if (mediaMimeType?.startsWith("image/") == true) 20 else 22
                 event.put("kind", kind)
                 
                 // content is description
                 event.put("content", quoteContent.trim())
                 
                 val label = if (kind == 20) "Image" else "Video"
                 val title = if (mediaTitle.isNotBlank()) mediaTitle else "My $label"
                 tags.put(org.json.JSONArray().put("title").put(title))
                 
                 if (uploadedMediaUrl != null) {
                     val imeta = org.json.JSONArray()
                     imeta.put("imeta")
                     imeta.put("url $uploadedMediaUrl")
                     if (mediaMimeType != null) imeta.put("m $mediaMimeType")
                     if (uploadedMediaHash != null) imeta.put("x $uploadedMediaHash")
                     tags.put(imeta)
                     
                     // Add legacy "url" tag for broader compatibility
                     tags.put(org.json.JSONArray().put("url").put(uploadedMediaUrl))
                 }
                 event.put("tags", tags)
             }
        }
        return event.toString()

    }

    private fun publishPost(signedEventJson: String) {
        isPublishing = true
        publishSuccess = null
        publishStatus = "Fetching relay list..."
        viewModelScope.launch {
            try {
                // Use IO dispatcher
                val relays = withContext(Dispatchers.IO) {
                    try {
                        val fetched = relayManager.fetchRelayList(pubkey!!)
                        if (fetched.isEmpty()) {
                            listOf("wss://relay.damus.io", "wss://nos.lol")
                        } else {
                            fetched
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        listOf("wss://relay.damus.io", "wss://nos.lol")
                    }
                }
                
                publishStatus = "Broadcasting to ${relays.size} relays..."
                val results = withContext(Dispatchers.IO) {
                     relayManager.publishEvent(signedEventJson, relays)
                }
                
                val successCount = results.count { entry -> entry.value }
                delay(1000)
                
                if (successCount > 0) {
                    publishStatus = "Success! Published to $successCount relays."
                    publishSuccess = true
                    discardDraft() // Clear draft on success
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

}