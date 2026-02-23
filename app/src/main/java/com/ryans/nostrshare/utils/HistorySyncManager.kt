package com.ryans.nostrshare.utils

import android.util.Log
import com.ryans.nostrshare.NostrShareApp
import com.ryans.nostrshare.data.Draft
import com.ryans.nostrshare.data.DraftDao
import com.ryans.nostrshare.RelayManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

object HistorySyncManager {
    private const val TAG = "HistorySyncManager"
    
    private val scope = NostrShareApp.getInstance().applicationScope
    private var activeJob: Job? = null
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()
    
    private val _discoveryCount = MutableStateFlow(0)
    val discoveryCount = _discoveryCount.asStateFlow()
    
    private val _currentRelay = MutableStateFlow<String?>(null)
    val currentRelay = _currentRelay.asStateFlow()

    private val _activePubkey = MutableStateFlow<String?>(null)
    val activePubkey = _activePubkey.asStateFlow()

    private var lastDeltaSyncTime = 0L

    fun reset() {
        activeJob?.cancel()
        _isSyncing.value = false
        _discoveryCount.value = 0
        _currentRelay.value = null
        _activePubkey.value = null
        lastDeltaSyncTime = 0L
    }

    fun startFullSync(pubkey: String, relayManager: RelayManager, draftDao: DraftDao) {
        if (activeJob?.isActive == true) return
        
        activeJob = scope.launch {
            try {
                _activePubkey.value = pubkey
                _isSyncing.value = true
                _discoveryCount.value = 0
                
                Log.d(TAG, "Starting Full Sync for $pubkey")
                
                withContext(Dispatchers.IO) {
                    draftDao.deleteRemoteHistory(pubkey)
                }
                
                performSync(pubkey, relayManager, draftDao, until = System.currentTimeMillis() / 1000)
                
                Log.d(TAG, "Full Sync Completed")
            } catch (e: Exception) {
                if (e is CancellationException) Log.d(TAG, "Sync Cancelled")
                else Log.e(TAG, "Sync Failed", e)
            } finally {
                cleanup()
            }
        }
    }

    fun startDeltaSync(pubkey: String, relayManager: RelayManager, draftDao: DraftDao, force: Boolean = false) {
        if (activeJob?.isActive == true) return
        
        val now = System.currentTimeMillis()
        if (!force && now - lastDeltaSyncTime < 30000) { 
            Log.d(TAG, "Delta sync on cooldown, skipping")
            return
        }
        lastDeltaSyncTime = now

        activeJob = scope.launch {
            try {
                _activePubkey.value = pubkey
                _isSyncing.value = true
                _discoveryCount.value = 0
                
                val lastTimestamp = withContext(Dispatchers.IO) {
                    draftDao.getMaxRemoteTimestamp(pubkey)
                } ?: (System.currentTimeMillis() - 86400000) 
                
                Log.d(TAG, "Starting Parallel Delta Sync for $pubkey since $lastTimestamp")
                
                performLatestRefresh(pubkey, relayManager, draftDao, since = lastTimestamp / 1000 + 1)
                
            } catch (e: Exception) {
                if (e is CancellationException) Log.d(TAG, "Sync Cancelled")
                else Log.e(TAG, "Sync Failed", e)
            } finally {
                cleanup()
            }
        }
    }

    fun startPaginationSync(pubkey: String, relayManager: RelayManager, draftDao: DraftDao, onResult: (Int) -> Unit = {}) {
        if (activeJob?.isActive == true) return
        
        activeJob = scope.launch {
            try {
                _activePubkey.value = pubkey
                _isSyncing.value = true
                _discoveryCount.value = 0
                
                val oldestTimestamp = withContext(Dispatchers.IO) {
                    draftDao.getMinRemoteTimestamp(pubkey)
                }
                
                if (oldestTimestamp == null) {
                    performSync(pubkey, relayManager, draftDao, until = System.currentTimeMillis() / 1000)
                } else {
                    val untilTs = oldestTimestamp / 1000 - 1
                    performSync(pubkey, relayManager, draftDao, until = untilTs)
                }
                onResult(_discoveryCount.value)
            } catch (e: Exception) {
                if (e is CancellationException) Log.d(TAG, "Sync Cancelled")
                else Log.e(TAG, "Sync Failed", e)
                onResult(-1)
            } finally {
                cleanup()
            }
        }
    }

    private suspend fun performLatestRefresh(
        syncPubkey: String,
        relayManager: RelayManager,
        draftDao: DraftDao,
        since: Long
    ) = coroutineScope {
        val kinds = listOf(1, 6, 16, 20, 22, 9802)
        val existingIds = withContext(Dispatchers.IO) {
            draftDao.getAllRemoteIds(syncPubkey).toSet()
        }

        relayManager.fetchLatestParallel(syncPubkey, kinds, since) { note ->
            val noteId = note.optString("id")
            val noteAuthor = note.optString("pubkey")
            
            // CRITICAL: Only process notes authored by the user we are currently syncing
            // This prevents Account B's sync from hijacking Account A's local notes.
            if (noteAuthor == syncPubkey && !isReply(note) && !existingIds.contains(noteId)) {
                _discoveryCount.value++
                val draft = processRemoteNote(note, syncPubkey)
                
                scope.launch(Dispatchers.IO) {
                    draftDao.syncRemoteNotes(listOf(draft))
                }
            }
        }
    }

    private suspend fun performSync(
        syncPubkey: String,
        relayManager: RelayManager,
        draftDao: DraftDao,
        since: Long? = null,
        until: Long? = null
    ) = coroutineScope {
        val noteChannel = Channel<Draft>(capacity = 1000)
        val kinds = listOf(1, 6, 16, 20, 22, 9802)
        
        val existingIds = withContext(Dispatchers.IO) {
            draftDao.getAllRemoteIds(syncPubkey).toSet()
        }

        val saverJob = launch(Dispatchers.IO) {
            val batch = mutableListOf<Draft>()
            for (draft in noteChannel) {
                batch.add(draft)
                if (batch.size >= 25) {
                    draftDao.syncRemoteNotes(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) draftDao.syncRemoteNotes(batch)
        }

        try {
            relayManager.fetchHistoryFromRelays(
                syncPubkey,
                kinds,
                since = since,
                until = until,
                onProgress = { url, current, total ->
                    _currentRelay.value = url
                    NotificationHelper.showSyncProgressNotification(
                        NostrShareApp.getInstance(),
                        url,
                        _discoveryCount.value,
                        0,
                        isCompleted = false
                    )
                }
            ) { note ->
                val noteId = note.optString("id")
                val noteAuthor = note.optString("pubkey")
                
                // CRITICAL: Only process notes authored by the user we are currently syncing
                if (noteAuthor == syncPubkey && !isReply(note) && !existingIds.contains(noteId)) {
                    _discoveryCount.value++
                    val draft = processRemoteNote(note, syncPubkey)
                    
                    if (_discoveryCount.value % 25 == 0) {
                        NotificationHelper.showSyncProgressNotification(
                            NostrShareApp.getInstance(),
                            _currentRelay.value ?: "",
                            _discoveryCount.value,
                            0,
                            isCompleted = false
                        )
                    }

                    runBlocking {
                        noteChannel.send(draft)
                    }
                }
            }
        } finally {
            noteChannel.close()
            saverJob.join()
        }
    }

    private fun cleanup() {
        _isSyncing.value = false
        _currentRelay.value = null
        NotificationHelper.showSyncProgressNotification(
            NostrShareApp.getInstance(),
            "",
            0,
            0,
            isCompleted = true
        )
    }

    fun stopSync() {
        activeJob?.cancel()
    }

    fun isReply(json: JSONObject): Boolean {
        if (json.optInt("kind") == 1) {
            val tags = json.optJSONArray("tags")
            if (tags != null) {
                for (i in 0 until tags.length()) {
                    val tag = tags.optJSONArray(i)
                    if (tag != null && tag.length() >= 2) {
                        val tagName = tag.optString(0)
                        if (tagName == "e" || tagName == "a") {
                            val marker = if (tag.length() >= 4) tag.optString(3) else ""
                            if (marker == "reply" || marker == "root") {
                                return true
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    fun processRemoteNote(json: JSONObject, currentPk: String): Draft {
        val kind = json.optInt("kind")
        var content = json.optString("content", "")
        val eventId = json.optString("id")
        val createdAt = json.optLong("created_at") * 1000L
        val tagsArray = json.optJSONArray("tags") ?: JSONArray()
        
        var sourceUrl = ""
        var mediaJson = "[]"
        var repostOriginalEventJson: String? = null
        var previewTitle: String? = null
        
        var highlightEventId: String? = null
        var highlightAuthor: String? = null
        var highlightKind: Int? = null
        
        val tags = mutableListOf<List<String>>()
        for (i in 0 until tagsArray.length()) {
            val tag = tagsArray.optJSONArray(i)
            if (tag != null && tag.length() >= 2) {
                val tagList = mutableListOf<String>()
                for (j in 0 until tag.length()) {
                    tagList.add(tag.optString(j))
                }
                tags.add(tagList)
                
                when (tagList[0]) {
                    "e" -> if (highlightEventId == null) highlightEventId = tagList[1]
                    "p" -> if (highlightAuthor == null) highlightAuthor = tagList[1]
                    "k" -> if (highlightKind == null) highlightKind = tagList[1].toIntOrNull()
                }
            }
        }

        val extractedMedia = mutableListOf<Pair<String, String?>>()
        tags.forEach { tag ->
            if (tag.size >= 2) {
                when (tag[0]) {
                    "url", "image", "thumb" -> extractedMedia.add(tag[1] to null)
                    "imeta" -> {
                        val url = tag.find { it.startsWith("url ") }?.removePrefix("url ")
                        val mime = tag.find { it.startsWith("m ") }?.removePrefix("m ")
                        if (url != null) extractedMedia.add(url to mime)
                    }
                }
            }
        }
        
        val uniqueMedia = extractedMedia.filter { it.first.isNotBlank() }.distinctBy { it.first }
        if (uniqueMedia.isNotEmpty()) {
            val mediaArray = JSONArray()
            uniqueMedia.forEach { (url, tagMime) ->
                val mediaObj = JSONObject()
                mediaObj.put("id", UUID.randomUUID().toString())
                mediaObj.put("uri", url)
                mediaObj.put("uploadedUrl", url)
                val detectedMime = tagMime ?: run {
                    val lc = url.lowercase().substringBefore("?")
                    when {
                        lc.endsWith(".mp4") || lc.endsWith(".mov") || lc.endsWith(".webm") || lc.endsWith(".avi") || lc.endsWith(".mkv") -> "video/mp4"
                        lc.endsWith(".gif") -> "image/gif"
                        lc.endsWith(".png") -> "image/png"
                        lc.endsWith(".webp") -> "image/webp"
                        lc.endsWith(".svg") -> "image/svg+xml"
                        kind == 22 -> "video/mp4"
                        else -> "image/jpeg"
                    }
                }
                mediaObj.put("mimeType", detectedMime)
                mediaArray.put(mediaObj)
            }
            mediaJson = mediaArray.toString()
        }

        when (kind) {
            1 -> {
                if (sourceUrl.isBlank()) {
                    sourceUrl = tags.find { it.size >= 2 && (it[0] == "r" || it[0] == "u" || (it[0].length == 1 && it[1].startsWith("http"))) }?.get(1) ?: ""
                }
                highlightEventId = eventId
                highlightAuthor = json.optString("pubkey")
                highlightKind = 1
            }
            6, 16 -> {
                if (content.startsWith("{") && content.contains("\"id\"")) {
                    repostOriginalEventJson = content
                }
            }
            20, 22 -> {
                val uniqueMediaUrls = uniqueMedia.map { it.first }
                if (uniqueMediaUrls.isNotEmpty()) {
                    if (content.isBlank()) {
                        content = tags.find { it.size >= 2 && it[0] == "alt" }?.get(1) ?: ""
                    }
                    if (sourceUrl.isBlank()) sourceUrl = uniqueMediaUrls[0]
                }
            }
            9802 -> {
                sourceUrl = tags.find { it.size >= 2 && (it[0] == "r" || it[0] == "u") }?.get(1) ?: ""
                previewTitle = tags.find { it.size >= 2 && it[0] == "title" }?.get(1)
            }
        }
        
        return Draft(
            content = content,
            sourceUrl = sourceUrl,
            kind = kind,
            mediaJson = mediaJson,
            mediaTitle = "",
            highlightEventId = highlightEventId,
            highlightAuthor = highlightAuthor,
            highlightKind = highlightKind,
            highlightRelaysJson = if (highlightEventId != null) JSONArray(tags.filter { it[0] == "e" && it.size >= 3 }.map { it[2] }.distinct()).toString() else null,
            originalEventJson = repostOriginalEventJson ?: json.toString(),
            lastEdited = createdAt,
            pubkey = currentPk,
            isScheduled = false,
            isCompleted = true,
            isRemoteCache = true,
            publishedEventId = eventId,
            actualPublishedAt = createdAt,
            previewTitle = previewTitle
        )
    }
}
