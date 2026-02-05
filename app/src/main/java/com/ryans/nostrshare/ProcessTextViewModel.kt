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
import org.json.JSONObject

class ProcessTextViewModel : ViewModel() {
    var quoteContent by mutableStateOf("")
    var sourceUrl by mutableStateOf("")
    var isPublishing by mutableStateOf(false)
    var publishStatus by mutableStateOf("")
    var pubkey by mutableStateOf<String?>(null)
    var npub by mutableStateOf<String?>(null) // Keep original npub for signer intent
    var signerPackageName by mutableStateOf<String?>(null)
    var userProfile by mutableStateOf<UserProfile?>(null)

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
    }

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
    
    fun logout() {
        pubkey = null
        npub = null
        signerPackageName = null
        userProfile = null
        
        prefs.edit().clear().apply()
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
    
    // Multi-server upload tracking
    var uploadSuccessCount by mutableStateOf(0)
    var uploadTotalCount by mutableStateOf(0)
    var uploadServerResults by mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) // (serverUrl, success)

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
        
        // Auto-switch mode
        if (mimeType.startsWith("video/") || mimeType.startsWith("image/")) {
            setKind(PostKind.MEDIA)
        }

        startUpload(context)
    }
    
    // Pending upload state for multi-server
    private var pendingServers: List<String> = emptyList()
    private var processedMediaUri: android.net.Uri? = null // Processed/optimized media URI
    
    fun startUpload(context: Context) {
        val uri = mediaUri ?: return
        val pk = pubkey
        
        if (pk == null) {
            uploadStatus = "Please login (click avatar) to upload."
            return
        }
        uploadStatus = "Preparing upload..."
        isUploading = true
        
        viewModelScope.launch {
            try {
                // 0. Process image if optimization is enabled
                val uploadUri = if (settingsRepository.isOptimizeMediaEnabled()) {
                    uploadStatus = "Optimizing media..."
                    val processed = ImageProcessor.processImage(context, uri, mediaMimeType)
                    if (processed != null) {
                        processedMediaUri = processed
                        // Update MIME type to JPEG since we converted
                        mediaMimeType = "image/jpeg"
                        processed
                    } else {
                        // Processing not applicable (video, gif, etc) - use original
                        processedMediaUri = null
                        uri
                    }
                } else {
                    processedMediaUri = null
                    uri
                }
                
                // 1. Hash and Size (use processed URI)
                uploadStatus = "Calculating hash..."
                val (hash, size) = blossomClient.hashFile(context, uploadUri)
                uploadedMediaHash = hash
                uploadedMediaSize = size
                
                // 2. Get ALL servers
                val servers = settingsRepository.getBlossomServers()
                if (servers.isEmpty()) {
                    pendingServers = listOf("https://blossom.primal.net")
                } else {
                    pendingServers = servers.toList()
                }
                
                // Initialize tracking
                uploadTotalCount = pendingServers.size
                uploadSuccessCount = 0
                uploadServerResults = emptyList()
                
                // Use first server for auth (all servers should accept same auth)
                pendingAuthServerUrl = pendingServers.first()
                
                // For Upload, operation is "upload" (default)
                val authEventJson = blossomClient.createAuthEventJson(hash, size, pk, "upload")
                
                // 3. Ask Activity to Sign

                currentSigningPurpose = SigningPurpose.UPLOAD_AUTH
                _eventToSign.value = authEventJson
                
            } catch (e: Exception) {
                uploadStatus = "Error preparing: ${e.message}"
                isUploading = false
            }
        }
    }
    
    fun deleteMedia() {
        val hash = uploadedMediaHash ?: return
        val pk = pubkey ?: return
        val server = pendingAuthServerUrl ?: return // Assume same server we uploaded to?
        // If we lost pendingAuthServerUrl (e.g. app restart), we might not be able to delete correctly without parsing URL or guessing.
        // For current session deletion, pendingAuthServerUrl should be fine.
        // Ideally we should track which server the current uploaded URL belongs to. 
        // For now, let's use pendingAuthServerUrl or try to derive.
        
        viewModelScope.launch {
            try {
                 uploadStatus = "Preparing delete..."
                 val authEventJson = blossomClient.createAuthEventJson(hash, null, pk, "delete")
                 currentSigningPurpose = SigningPurpose.DELETE_AUTH
                 _eventToSign.value = authEventJson
            } catch (e: Exception) {
                uploadStatus = "Delete prepare failed: ${e.message}"
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
        val hash = uploadedMediaHash ?: return
        val server = pendingAuthServerUrl ?: return
        
        viewModelScope.launch {
            try {
                isDeleting = true
                uploadStatus = "Deleting..."
                val success = blossomClient.delete(hash, signedAuthEvent, server)
                if (success) {
                    uploadStatus = "Media deleted."
                    uploadedMediaUrl = null
                    uploadedMediaHash = null
                    uploadedMediaSize = null
                    mediaUri = null
                } else {
                    uploadStatus = "Delete failed on server."
                }
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
            
            // Upload to ALL servers in parallel
            val results = mutableListOf<Pair<String, Boolean>>()
            var firstSuccessUrl: String? = null
            
            // Use coroutineScope for structured concurrency with async
            kotlinx.coroutines.coroutineScope {
                val jobs = servers.map { server: String ->
                    async {
                        try {
                            val url = blossomClient.upload(
                                NostrShareApp.getInstance(),
                                uri,
                                signedAuthEvent,
                                server,
                                mediaMimeType
                            )
                            synchronized(results) {
                                results.add(server to true)
                                if (firstSuccessUrl == null) {
                                    firstSuccessUrl = url
                                }
                            }
                            true
                        } catch (e: Exception) {
                            synchronized(results) {
                                results.add(server to false)
                            }
                            false
                        }
                    }
                }
                
                // Wait for all uploads to complete
                jobs.awaitAll()
            }
            
            // Update state
            uploadServerResults = results.toList()
            uploadSuccessCount = results.count { it.second }
            
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

    fun setKind(kind: PostKind) {
        val oldKind = postKind
        postKind = kind
        
        // Intelligent URL handling
        val url = uploadedMediaUrl
        if (url != null) {
            if (kind == PostKind.NOTE) {
                // Switching TO Note: Add URL if missing
                if (!quoteContent.contains(url)) {
                     val prefix = if (quoteContent.isNotBlank()) "\n\n" else ""
                     quoteContent += "$prefix$url"
                }
            } else if (oldKind == PostKind.NOTE && kind == PostKind.MEDIA) {
                // Switching FROM Note TO Media: Remove URL if present at end
                if (quoteContent.endsWith(url)) {
                    quoteContent = quoteContent.removeSuffix(url).trim()
                }
            }
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
        val effectiveKind = if (settingsRepository.isAlwaysUseKind1()) {
            PostKind.NOTE
        } else {
            postKind
        }
        
        when (effectiveKind) {
             PostKind.NOTE -> {
                 event.put("kind", 1)
                 var content = quoteContent.trim()
                 // Append URL if from highlight/source
                 if (sourceUrl.isNotBlank()) content += "\n\n$sourceUrl"
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
                 val title = "My $label" // MVP title
                 tags.put(org.json.JSONArray().put("title").put(title))
                 
                 if (uploadedMediaUrl != null) {
                     val imeta = org.json.JSONArray()
                     imeta.put("imeta")
                     imeta.put("url $uploadedMediaUrl")
                     if (mediaMimeType != null) imeta.put("m $mediaMimeType")
                     if (uploadedMediaHash != null) imeta.put("x $uploadedMediaHash")
                     tags.put(imeta)
                     
                     // Also add "url" tag for backward compat? 
                     // NIP-68 says imeta.
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