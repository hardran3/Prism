package com.ryans.nostrshare.utils

import android.util.Log
import com.ryans.nostrshare.RelayManager
import com.ryans.nostrshare.data.DraftDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

object DeepScanManager {
    private const val TAG = "DeepScanManager"

    enum class OverallPhase {
        IDLE,
        RELAY_DISCOVERY,
        HEALTH_CHECK,
        SYNCING,
        COMPLETED,
        ERROR
    }

    data class DeepScanProgress(
        val phase: OverallPhase = OverallPhase.IDLE,
        val relayDiscoveryProgress: Float = 0f,
        val healthCheckProgress: Float = 0f,
        val syncProgress: Float = 0f,
        val newNotesFound: Int = 0,
        val currentActivity: String? = null,
        val error: String? = null
    )

    private val _progressState = MutableStateFlow(DeepScanProgress())
    val progressState = _progressState.asStateFlow()

    private var scanJob: Job? = null

    fun stopScan() {
        scanJob?.cancel()
        _progressState.value = DeepScanProgress(phase = OverallPhase.IDLE)
        HistorySyncManager.stopSync()
    }

    fun startDeepScan(
        pubkey: String,
        relayManager: RelayManager,
        draftDao: DraftDao,
        scope: CoroutineScope,
        isDeep: Boolean = true
    ) {
        if (scanJob?.isActive == true) return

        scanJob = scope.launch {
            try {
                if (!isDeep) {
                    performOutboxOnlyScan(pubkey, relayManager, draftDao, scope)
                    return@launch
                }

                _progressState.value = DeepScanProgress(phase = OverallPhase.RELAY_DISCOVERY, currentActivity = "Discovering follows...")
                val follows = relayManager.fetchContactList(pubkey)
                if (follows.isEmpty()) {
                    performOutboxOnlyScan(pubkey, relayManager, draftDao, scope)
                    return@launch
                }

                val relayFrequencyMap = mutableMapOf<String, Int>()
                val followsList = follows.toList()
                val chunks = followsList.chunked(50)

                chunks.forEachIndexed { index, chunk ->
                    ensureActive()
                    val progress = (index + 1).toFloat() / chunks.size.toFloat()
                    _progressState.value = _progressState.value.copy(
                        relayDiscoveryProgress = progress,
                        currentActivity = "Harvesting relays for ${follows.size} followers..."
                    )
                    val discovered = relayManager.fetchRelayListsBatch(chunk)
                    discovered.forEach { (_, relays) ->
                        relays.forEach { url ->
                            val cleanUrl = cleanRelayUrl(url)
                            if (cleanUrl.isNotBlank()) {
                                synchronized(relayFrequencyMap) {
                                    relayFrequencyMap[cleanUrl] = relayFrequencyMap.getOrDefault(cleanUrl, 0) + 1
                                }
                            }
                        }
                    }
                }

                _progressState.value = _progressState.value.copy(phase = OverallPhase.HEALTH_CHECK, relayDiscoveryProgress = 1f)
                val rankedRelays = relayFrequencyMap.toList().sortedByDescending { it.second }.map { it.first }
                
                val activeSet = mutableListOf<String>()
                val userOutboxRelays = relayManager.fetchRelayList(pubkey, isRead = true)
                activeSet.addAll(userOutboxRelays)

                val rankedRelaysToCheck = rankedRelays.filterNot { it in userOutboxRelays }
                var healthyFoundCount = 0
                val goal = 25

                for (url in rankedRelaysToCheck) {
                    ensureActive()
                    if (healthyFoundCount >= goal) break

                    if (relayManager.checkRelayHealth(url)) {
                        healthyFoundCount++
                        activeSet.add(url)
                    }
                    
                    val progress = healthyFoundCount.toFloat() / goal.toFloat()
                    _progressState.value = _progressState.value.copy(
                        healthCheckProgress = progress,
                        currentActivity = "Finding healthy relays... ($healthyFoundCount / $goal)"
                    )
                }

                _progressState.value = _progressState.value.copy(healthCheckProgress = 1f)
                
                val finalActiveSet = activeSet.distinct()
                if (finalActiveSet.isEmpty()) {
                    _progressState.value = DeepScanProgress(phase = OverallPhase.ERROR, error = "Could not find any healthy relays.")
                    return@launch
                }

                _progressState.value = _progressState.value.copy(phase = OverallPhase.SYNCING, healthCheckProgress = 1f)
                val notesFound = AtomicInteger(0)
                val kinds = listOf(1, 6, 16, 20, 22, 9802, 30023)
                val existingIds = draftDao.getAllRemoteIds(pubkey).toSet()
                
                relayManager.fetchHistoryFromRelays(
                    pubkey,
                    kinds,
                    relays = finalActiveSet,
                    until = System.currentTimeMillis() / 1000,
                    onProgress = { url, current, total ->
                        _progressState.value = _progressState.value.copy(
                            syncProgress = current.toFloat() / total.toFloat(),
                            currentActivity = "Syncing ${url.take(40)}... ($current/$total)"
                        )
                    }
                ) { note ->
                    val noteId = note.optString("id")
                    val noteAuthor = note.optString("pubkey")
                    if (noteAuthor == pubkey && !HistorySyncManager.isReply(note) && !existingIds.contains(noteId)) {
                        val currentCount = notesFound.incrementAndGet()
                        _progressState.value = _progressState.value.copy(newNotesFound = currentCount)
                        val processed = HistorySyncManager.processRemoteNote(note, pubkey)
                        scope.launch(Dispatchers.IO) {
                            draftDao.insertDraft(processed)
                        }
                    }
                }

                _progressState.value = _progressState.value.copy(phase = OverallPhase.COMPLETED, syncProgress = 1f)
                delay(2000)
                _progressState.value = DeepScanProgress(phase = OverallPhase.IDLE)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Deep Scan Failed", e)
                    _progressState.value = DeepScanProgress(phase = OverallPhase.ERROR, error = e.message ?: "Unknown error")
                }
            }
        }
    }

    private suspend fun performOutboxOnlyScan(pubkey: String, relayManager: RelayManager, draftDao: DraftDao, scope: CoroutineScope) {
        _progressState.value = DeepScanProgress(phase = OverallPhase.SYNCING, currentActivity = "Fetching from your outbox relays...")
        val notesFound = AtomicInteger(0)
        
        val kinds = listOf(1, 6, 16, 20, 22, 9802, 30023)
        val existingIds = draftDao.getAllRemoteIds(pubkey).toSet()
        val outboxRelays = relayManager.fetchRelayList(pubkey, isRead = true)

        relayManager.fetchHistoryFromRelays(
            pubkey,
            kinds,
            relays = outboxRelays,
            until = System.currentTimeMillis() / 1000,
            onProgress = { url, current, total ->
                _progressState.value = _progressState.value.copy(
                    syncProgress = current.toFloat() / total.toFloat(),
                    currentActivity = "Syncing ${url.take(40)}... ($current/$total)"
                )
            }
        ) { note ->
            val noteId = note.optString("id")
            val noteAuthor = note.optString("pubkey")
            if (noteAuthor == pubkey && !HistorySyncManager.isReply(note) && !existingIds.contains(noteId)) {
                val currentCount = notesFound.incrementAndGet()
                _progressState.value = _progressState.value.copy(newNotesFound = currentCount)
                val processed = HistorySyncManager.processRemoteNote(note, pubkey)
                scope.launch(Dispatchers.IO) {
                    draftDao.insertDraft(processed)
                }
            }
        }

        _progressState.value = _progressState.value.copy(phase = OverallPhase.COMPLETED, syncProgress = 1f)
        delay(2000)
        _progressState.value = DeepScanProgress(phase = OverallPhase.IDLE)
    }

    private fun cleanRelayUrl(url: String): String {
        return try {
            val trimmed = url.trim().removeSuffix("/")
            if (trimmed.startsWith("wss://") || trimmed.startsWith("ws://")) {
                val uri = java.net.URI(trimmed)
                val scheme = uri.scheme ?: "wss"
                val host = uri.host ?: return ""
                val port = if (uri.port != -1 && ((scheme == "wss" && uri.port != 443) || (scheme == "ws" && uri.port != 80))) ":${uri.port}" else ""
                "$scheme://$host$port/"
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
