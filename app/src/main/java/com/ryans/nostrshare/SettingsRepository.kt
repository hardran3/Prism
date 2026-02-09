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
        val globalPrefs = appContext.getSharedPreferences("nostr_share_prefs", Context.MODE_PRIVATE)
        return globalPrefs.getString("pubkey", null) != null
    }

    fun isAlwaysUseKind1(): Boolean = prefs.getBoolean("always_kind_1", false)
    fun setAlwaysUseKind1(enabled: Boolean) = prefs.edit().putBoolean("always_kind_1", enabled).apply()

    fun isOptimizeMediaEnabled(): Boolean = getCompressionLevel() != COMPRESSION_NONE
    fun setOptimizeMediaEnabled(enabled: Boolean) = setCompressionLevel(if (enabled) COMPRESSION_MEDIUM else COMPRESSION_NONE)

    fun getCompressionLevel(): Int = prefs.getInt("compression_level", COMPRESSION_MEDIUM)
    fun setCompressionLevel(level: Int) = prefs.edit().putInt("compression_level", level).apply()

    fun isBlastrEnabled(): Boolean = prefs.getBoolean("blastr_enabled", false)
    fun setBlastrEnabled(enabled: Boolean) = prefs.edit().putBoolean("blastr_enabled", enabled).apply()

    fun isHapticEnabled(): Boolean = prefs.getBoolean("haptic_enabled", true)
    fun setHapticEnabled(enabled: Boolean) = prefs.edit().putBoolean("haptic_enabled", enabled).apply()

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
}
