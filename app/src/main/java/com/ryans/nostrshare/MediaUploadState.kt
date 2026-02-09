package com.ryans.nostrshare

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MediaUploadState(
    val id: String,
    val uri: Uri,
    val mimeType: String? = null
) {
    var size: Long by mutableStateOf(0L)
    var hash: String? by mutableStateOf(null)
    var uploadedUrl: String? by mutableStateOf(null)
    var isProcessing: Boolean by mutableStateOf(false)
    var isUploading: Boolean by mutableStateOf(false)
    var progress: Float by mutableStateOf(0f)
    var status: String by mutableStateOf("")
    
    // Per-server results: Pair<ServerURL, Success>
    var serverResults = mutableStateOf<List<Pair<String, Boolean>>>(emptyList())
    // Per-server hashes: Map<ServerURL, Hash>
    var serverHashes = mutableStateOf<Map<String, String>>(emptyMap())
    
    // Tracking servers for the current operation (Upload/Delete)
    var pendingServers: List<String> = emptyList()
}
