package com.ryans.nostrshare

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel : ViewModel() {
    private val prefs by lazy { 
        NostrShareApp.getInstance().getSharedPreferences("nostr_share_prefs", Context.MODE_PRIVATE) 
    }
    private val settingsRepository by lazy { SettingsRepository(NostrShareApp.getInstance()) }
    private val relayManager by lazy { RelayManager(NostrShareApp.getInstance().client, settingsRepository) }

    var pubkey by mutableStateOf<String?>(null)
    var npub by mutableStateOf<String?>(null)
    var signerPackageName by mutableStateOf<String?>(null)

    private val _eventToSign = MutableStateFlow<String?>(null)
    val eventToSign: StateFlow<String?> = _eventToSign.asStateFlow()

    var isPublishing by mutableStateOf(false)
    var publishStatus by mutableStateOf("")

    init {
        pubkey = prefs.getString("pubkey", null)
        npub = prefs.getString("npub", null)
        signerPackageName = prefs.getString("signer_package", null)
    }

    fun publishBlossomList(servers: List<String>) {
        val pk = pubkey ?: return
        
        viewModelScope.launch {
            try {
                isPublishing = true
                publishStatus = "Preparing Kind 10063..."
                
                val eventJson = relayManager.createBlossomServerListEventJson(pk, servers)
                _eventToSign.value = eventJson
            } catch (e: Exception) {
                publishStatus = "Error: ${e.message}"
                isPublishing = false
            }
        }
    }

    fun onEventSigned(signedEventJson: String) {
        _eventToSign.value = null
        finalizePublish(signedEventJson)
    }

    private fun finalizePublish(signedEventJson: String) {
        viewModelScope.launch {
            try {
                publishStatus = "Fetching relays..."
                val pk = pubkey ?: return@launch
                val relays = relayManager.fetchRelayList(pk)
                
                publishStatus = "Publishing to ${relays.size} relays..."
                val results = relayManager.publishEvent(signedEventJson, relays)
                
                val successCount = results.count { it.value }
                publishStatus = "Published to $successCount/${relays.size} relays."
                
                if (successCount == 0) {
                    publishStatus = "Failed to publish to any relays."
                }
            } catch (e: Exception) {
                publishStatus = "Publish failed: ${e.message}"
            } finally {
                isPublishing = false
            }
        }
    }
}
