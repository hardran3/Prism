package com.ryans.nostrshare

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RelayManager(private val client: OkHttpClient) {
    private val bootstrapRelays = listOf("wss://relay.damus.io", "wss://nos.lol")

    suspend fun fetchRelayList(pubkey: String): List<String> = withContext(Dispatchers.IO) {
        val relayList = mutableSetOf<String>()
        val latch = CountDownLatch(1) // Wait for at least one valid response or timeout

        // Simple implementation: try one by one or race them. For simplicity, race first success.
        for (url in bootstrapRelays) {
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    filter.put("kinds", JSONArray().put(10002))
                    filter.put("authors", JSONArray().put(pubkey))
                    filter.put("limit", 1)
                    val req = JSONArray()
                    req.put("REQ")
                    req.put(subId)
                    req.put(filter)
                    webSocket.send(req.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONArray(text)
                        val type = json.optString(0)
                        if (type == "EVENT" && json.optString(1) == subId) {
                            val event = json.getJSONObject(2)
                            val tags = event.optJSONArray("tags")
                            if (tags != null) {
                                for (i in 0 until tags.length()) {
                                    val tag = tags.getJSONArray(i)
                                    if (tag.length() > 1 && tag.getString(0) == "r") {
                                        val relayUrl = tag.getString(1)
                                        val marker = if (tag.length() > 2) tag.getString(2) else ""
                                        // Add if write or unspecified (read/write)
                                        if (marker == "write" || marker == "") {
                                            relayList.add(relayUrl)
                                        }
                                    }
                                }
                            }
                            webSocket.close(1000, "Done")
                            latch.countDown()
                        } else if (type == "EOSE" && json.optString(1) == subId) {
                           webSocket.close(1000, "EOSE")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                     // Fail silently for now
                }
            }
            client.newWebSocket(request, listener)
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // Timeout
        }
        
        if (relayList.isEmpty()) {
            return@withContext bootstrapRelays 
        }
        return@withContext relayList.toList()
    }

    suspend fun publishEvent(signedEventJson: String, relays: List<String>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Boolean>()
        val latch = CountDownLatch(relays.size)
        
        for (url in relays) {
            val request = Request.Builder().url(url).build()
             val listener = object : WebSocketListener() {
                 override fun onOpen(webSocket: WebSocket, response: Response) {
                     val msg = JSONArray()
                     msg.put("EVENT")
                     msg.put(JSONObject(signedEventJson))
                     webSocket.send(msg.toString())
                 }

                 override fun onMessage(webSocket: WebSocket, text: String) {
                     try {
                         val json = JSONArray(text)
                         if (json.optString(0) == "OK") {
                             val success = json.optBoolean(2)
                             results[url] = success
                             webSocket.close(1000, "Done")
                             latch.countDown()
                         }
                     } catch (e: Exception) {
                        // ignore
                     }
                 }

                 override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                     results[url] = false
                     latch.countDown()
                 }
                 
                 override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                     if (results[url] == null) {
                        results[url] = false
                        latch.countDown()
                     }
                 }
             }
             client.newWebSocket(request, listener)
        }

        try {
            latch.await(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // Timeout
        }
        
        return@withContext results
    }
    suspend fun fetchUserProfile(pubkey: String): UserProfile? = withContext(Dispatchers.IO) {
        val latch = CountDownLatch(1)
        var profile: UserProfile? = null

        for (url in bootstrapRelays) {
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    filter.put("kinds", JSONArray().put(0)) // Kind 0: Metadata
                    filter.put("authors", JSONArray().put(pubkey))
                    filter.put("limit", 1)
                    val req = JSONArray()
                    req.put("REQ")
                    req.put(subId)
                    req.put(filter)
                    webSocket.send(req.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONArray(text)
                        val type = json.optString(0)
                        if (type == "EVENT" && json.optString(1) == subId) {
                            val event = json.getJSONObject(2)
                            val contentStr = event.optString("content")
                            if (contentStr.isNotEmpty()) {
                                val content = JSONObject(contentStr)
                                profile = UserProfile(
                                    name = content.optString("name", "").ifEmpty { content.optString("display_name") },
                                    pictureUrl = content.optString("picture")
                                )
                            }
                            webSocket.close(1000, "Done")
                            latch.countDown()
                        } else if (type == "EOSE" && json.optString(1) == subId) {
                           webSocket.close(1000, "EOSE")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                     // Fail silently
                }
            }
            client.newWebSocket(request, listener)
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) { }
        
        return@withContext profile
    }
}

data class UserProfile(
    val name: String?,
    val pictureUrl: String?
)
