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

    private val relayManager by lazy { RelayManager(NostrShareApp.getInstance().client) }
    private val prefs by lazy { 
        NostrShareApp.getInstance().getSharedPreferences("nostr_share_prefs", Context.MODE_PRIVATE) 
    }

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

    fun onSignedEventReceived(signedEventJson: String) {
        isPublishing = true
        publishStatus = "Fetching relay list..."
        viewModelScope.launch {
            try {
                // Use IO dispatcher
                val relays = withContext(Dispatchers.IO) {
                    try {
                        val fetched = relayManager.fetchRelayList(pubkey!!)
                        if (fetched.isEmpty()) {
                            // Fallback if no Kind 10002 found
                            listOf("wss://relay.damus.io", "wss://nos.lol")
                        } else {
                            fetched
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        listOf("wss://relay.damus.io", "wss://nos.lol")
                    }
                }
                
                publishStatus = "Broadcasting to ${relays.size} relays...\n${relays.take(3).joinToString("\n")}"
                
                // Launch publishing in a separate scope or keep here? 
                // We need to keep it here to await results.
                val results = withContext(Dispatchers.IO) {
                     relayManager.publishEvent(signedEventJson, relays)
                }
                
                val successCount = results.count { entry -> entry.value }
                publishStatus = if (successCount > 0) {
                    "Success! Published to $successCount / ${results.size} relays."
                } else {
                    "Failed to publish to any of ${results.size} relays."
                }
                
                delay(2000)
                isPublishing = false
            } catch (e: Exception) {
                publishStatus = "Error: ${e.message}"
                delay(3000)
                isPublishing = false
            }
        }
    }

    var isHighlightMode by mutableStateOf(true) // Default to highlight, Activity can override

    fun prepareEventJson(): String {
        if (isHighlightMode) {
            // NIP-84: Kind 9802 (Highlight)
            // .content = The highlighted text
            // .tags = [ ["r", "source_url"] ]
            val event = JSONObject()
            event.put("kind", 9802)
            event.put("content", quoteContent.trim())
            event.put("created_at", System.currentTimeMillis() / 1000)
            
            val tags = org.json.JSONArray()
            
            // Add Source URL "r" tag
            if (sourceUrl.isNotBlank()) {
                val rTag = org.json.JSONArray()
                rTag.put("r")
                rTag.put(sourceUrl.trim())
                tags.put(rTag)
            }
            
            // Add "alt" tag for clients that don't support NIP-84 yet
            val altTag = org.json.JSONArray()
            altTag.put("alt")
            altTag.put("Highlight: \"${quoteContent.take(50)}...\"")
            tags.put(altTag)

            event.put("tags", tags)
            event.put("pubkey", pubkey ?: "")
            
            // Re-adding id and sig (empty) for strict parsers
            event.put("id", "")
            event.put("sig", "")
            
            return event.toString()
        } else {
            // Kind 1: Text Note
            // .content = "Text\n\nSource URL"
            val event = JSONObject()
            event.put("kind", 1)
            
            var fullContent = quoteContent.trim()
            if (sourceUrl.isNotBlank()) {
                fullContent += "\n\n${sourceUrl.trim()}"
            }
            
            event.put("content", fullContent)
            event.put("created_at", System.currentTimeMillis() / 1000)
            event.put("tags", org.json.JSONArray()) // No special tags for basic note
            event.put("pubkey", pubkey ?: "")
            event.put("id", "")
            event.put("sig", "")
            
            return event.toString()
        }
    }

}