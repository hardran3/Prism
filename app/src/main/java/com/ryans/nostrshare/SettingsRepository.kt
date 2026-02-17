package com.ryans.nostrshare

import android.content.Context
import android.content.SharedPreferences

data class BlossomServer(val url: String, val enabled: Boolean)

class SettingsRepository(private val appContext: Context) {
    private val prefs: SharedPreferences = appContext.getSharedPreferences("nostr_share_settings", Context.MODE_PRIVATE)
    
    private val onboardDefaults = listOf(
        BlossomServer("https://blossom.primal.net", true),
        BlossomServer("https://blossom.band", true)
    )
    
    companion object {
        const val COMPRESSION_NONE = 0
        const val COMPRESSION_MEDIUM = 1
        const val COMPRESSION_HIGH = 2
    }

    private val defaultServers = emptyList<BlossomServer>()

    val fallBackBlossomServers = listOf(
        "https://blossom.primal.net",
        "https://blossom.band",
        "https://cdn.nostrcheck.me",
        "https://nostr.download",
        "https://blossom.yakihonne.com/",
        "https://files.sovbit.host"
    )

    fun isOnboarded(): Boolean {
        return prefs.getBoolean("onboarded_completed", false)
    }

    fun setOnboarded(onboarded: Boolean) {
        prefs.edit().putBoolean("onboarded_completed", onboarded).apply()
    }

    fun isSchedulingEnabled(): Boolean = prefs.getBoolean("scheduling_enabled", false)
    fun setSchedulingEnabled(enabled: Boolean) = prefs.edit().putBoolean("scheduling_enabled", enabled).apply()

    fun isAlwaysUseKind1(pubkey: String? = null): Boolean {
        val key = if (pubkey != null) "${pubkey}_always_kind_1" else "always_kind_1"
        return if (pubkey != null && !prefs.contains(key)) {
            prefs.getBoolean("always_kind_1", false) // Fallback
        } else {
            prefs.getBoolean(key, false)
        }
    }
    
    fun setAlwaysUseKind1(enabled: Boolean, pubkey: String? = null) {
        val key = if (pubkey != null) "${pubkey}_always_kind_1" else "always_kind_1"
        prefs.edit().putBoolean(key, enabled).apply()
    }

    fun isOptimizeMediaEnabled(): Boolean = getCompressionLevel() != COMPRESSION_NONE
    fun setOptimizeMediaEnabled(enabled: Boolean) = setCompressionLevel(if (enabled) COMPRESSION_MEDIUM else COMPRESSION_NONE)

    fun getCompressionLevel(): Int = prefs.getInt("compression_level", COMPRESSION_MEDIUM)
    fun setCompressionLevel(level: Int) = prefs.edit().putInt("compression_level", level).apply()

    fun isBlastrEnabled(pubkey: String? = null): Boolean {
        val key = if (pubkey != null) "${pubkey}_blastr_enabled" else "blastr_enabled"
        return if (pubkey != null && !prefs.contains(key)) {
            prefs.getBoolean("blastr_enabled", true) // Fallback
        } else {
            prefs.getBoolean(key, true)
        }
    }

    fun setBlastrEnabled(enabled: Boolean, pubkey: String? = null) {
        val key = if (pubkey != null) "${pubkey}_blastr_enabled" else "blastr_enabled"
        prefs.edit().putBoolean(key, enabled).apply()
    }

    fun isHapticEnabled(): Boolean = prefs.getBoolean("haptic_enabled", true)
    fun setHapticEnabled(enabled: Boolean) = prefs.edit().putBoolean("haptic_enabled", enabled).apply()

    fun isCitrineRelayEnabled(pubkey: String? = null): Boolean {
        val key = if (pubkey != null) "${pubkey}_citrine_relay_enabled" else "citrine_relay_enabled"
        return if (pubkey != null && !prefs.contains(key)) {
            prefs.getBoolean("citrine_relay_enabled", false) // Fallback
        } else {
            prefs.getBoolean(key, false)
        }
    }

    fun setCitrineRelayEnabled(enabled: Boolean, pubkey: String? = null) {
        val key = if (pubkey != null) "${pubkey}_citrine_relay_enabled" else "citrine_relay_enabled"
        prefs.edit().putBoolean(key, enabled).apply()
    }

    fun getBlossomServers(pubkey: String? = null): List<BlossomServer> {
        val key = if (pubkey != null) "${pubkey}_blossom_servers_json" else "blossom_servers_json"
        
        // Try precise key first
        var jsonString = prefs.getString(key, null)
        
        // Fallback to global if specific key missing and we have a pubkey
        if (jsonString == null && pubkey != null) {
             jsonString = prefs.getString("blossom_servers_json", null)
        }

        if (jsonString == null) {
             // Suggestions for new users only (if truly nothing found anywhere)
             return if (!isOnboarded()) onboardDefaults else defaultServers
        }
        
        val list = mutableListOf<BlossomServer>()
        try {
            val array = org.json.JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(BlossomServer(obj.getString("url"), obj.getBoolean("enabled")))
            }
        } catch (e: Exception) {
             return if (!isOnboarded()) onboardDefaults else defaultServers
        }
        return list
    }
    
    fun setBlossomServers(servers: List<BlossomServer>, pubkey: String? = null) {
        val key = if (pubkey != null) "${pubkey}_blossom_servers_json" else "blossom_servers_json"
        val array = org.json.JSONArray()
        servers.forEach { 
            val obj = org.json.JSONObject()
            obj.put("url", it.url)
            obj.put("enabled", it.enabled)
            array.put(obj)
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
    
    // Helper for VM
    fun getEnabledBlossomServers(pubkey: String? = null): List<String> {
        return getBlossomServers(pubkey).filter { it.enabled }.map { it.url }
    }

    fun getFollowedPubkeys(): Set<String> {
        return prefs.getStringSet("followed_pubkeys", emptySet()) ?: emptySet()
    }

    fun setFollowedPubkeys(pubkeys: Set<String>) {
        prefs.edit().putStringSet("followed_pubkeys", pubkeys).apply()
    }

    fun getUsernameCache(): Map<String, UserProfile> {
        val jsonString = prefs.getString("username_cache_json_v2", null) ?: return emptyMap()
        val result = mutableMapOf<String, UserProfile>()
        try {
            val obj = org.json.JSONObject(jsonString)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val profileJson = obj.getJSONObject(key)
                result[key] = UserProfile(
                    name = if (profileJson.isNull("name")) null else profileJson.optString("name"),
                    pictureUrl = if (profileJson.isNull("picture")) null else profileJson.optString("picture"),
                    lud16 = if (profileJson.isNull("lud16")) null else profileJson.optString("lud16")
                )
            }
        } catch (_: Exception) {}
        return result
    }

    fun setUsernameCache(cache: Map<String, UserProfile>) {
        val obj = org.json.JSONObject()
        cache.forEach { (pk, profile) ->
            val profileJson = org.json.JSONObject()
            profileJson.put("name", profile.name)
            profileJson.put("picture", profile.pictureUrl)
            profileJson.put("lud16", profile.lud16)
            obj.put(pk, profileJson)
        }
        prefs.edit().putString("username_cache_json_v2", obj.toString()).apply()
    }
}
