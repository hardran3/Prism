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

    fun isAlwaysUseKind1(): Boolean = prefs.getBoolean("always_kind_1", false)
    fun setAlwaysUseKind1(enabled: Boolean) = prefs.edit().putBoolean("always_kind_1", enabled).apply()

    fun isOptimizeMediaEnabled(): Boolean = getCompressionLevel() != COMPRESSION_NONE
    fun setOptimizeMediaEnabled(enabled: Boolean) = setCompressionLevel(if (enabled) COMPRESSION_MEDIUM else COMPRESSION_NONE)

    fun getCompressionLevel(): Int = prefs.getInt("compression_level", COMPRESSION_MEDIUM)
    fun setCompressionLevel(level: Int) = prefs.edit().putInt("compression_level", level).apply()

    fun isBlastrEnabled(): Boolean = prefs.getBoolean("blastr_enabled", true)
    fun setBlastrEnabled(enabled: Boolean) = prefs.edit().putBoolean("blastr_enabled", enabled).apply()

    fun isHapticEnabled(): Boolean = prefs.getBoolean("haptic_enabled", true)
    fun setHapticEnabled(enabled: Boolean) = prefs.edit().putBoolean("haptic_enabled", enabled).apply()

    fun isCitrineRelayEnabled(): Boolean = prefs.getBoolean("citrine_relay_enabled", false)
    fun setCitrineRelayEnabled(enabled: Boolean) = prefs.edit().putBoolean("citrine_relay_enabled", enabled).apply()

    fun getBlossomServers(): List<BlossomServer> {
        val jsonString = prefs.getString("blossom_servers_json", null)
        if (jsonString == null) {
             // Suggestions for new users only
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
    
    fun setBlossomServers(servers: List<BlossomServer>) {
        val array = org.json.JSONArray()
        servers.forEach { 
            val obj = org.json.JSONObject()
            obj.put("url", it.url)
            obj.put("enabled", it.enabled)
            array.put(obj)
        }
        prefs.edit().putString("blossom_servers_json", array.toString()).apply()
    }
    
    // Helper for VM
    fun getEnabledBlossomServers(): List<String> {
        return getBlossomServers().filter { it.enabled }.map { it.url }
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
