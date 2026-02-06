package com.ryans.nostrshare

import android.content.Context
import android.content.SharedPreferences

data class BlossomServer(val url: String, val enabled: Boolean)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nostr_share_settings", Context.MODE_PRIVATE)

    fun isAlwaysUseKind1(): Boolean = prefs.getBoolean("always_kind_1", false)
    fun setAlwaysUseKind1(enabled: Boolean) = prefs.edit().putBoolean("always_kind_1", enabled).apply()

    fun isOptimizeMediaEnabled(): Boolean = prefs.getBoolean("optimize_media", true)
    fun setOptimizeMediaEnabled(enabled: Boolean) = prefs.edit().putBoolean("optimize_media", enabled).apply()

    fun isBlastrEnabled(): Boolean = prefs.getBoolean("blastr_enabled", false)
    fun setBlastrEnabled(enabled: Boolean) = prefs.edit().putBoolean("blastr_enabled", enabled).apply()

    fun getBlossomServers(): List<BlossomServer> {
        val jsonString = prefs.getString("blossom_servers_json", null)
        if (jsonString == null) {
             // Defaults
             return listOf(
                 BlossomServer("https://blossom.primal.net", true),
                 BlossomServer("https://blossom.band", true)
             )
        }
        
        val list = mutableListOf<BlossomServer>()
        try {
            val array = org.json.JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(BlossomServer(obj.getString("url"), obj.getBoolean("enabled")))
            }
        } catch (e: Exception) {
            return listOf(
                 BlossomServer("https://blossom.primal.net", true),
                 BlossomServer("https://blossom.band", true)
             )
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
