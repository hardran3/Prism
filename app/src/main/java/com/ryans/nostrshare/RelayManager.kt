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
import android.util.Log

class RelayManager(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {
    private val bootstrapRelays = listOf("wss://relay.damus.io", "wss://nos.lol")
    private val indexerRelays = listOf(
        "wss://purplepag.es", 
        "wss://indexer.coracle.social", 
        "wss://user.kindpag.es",
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://nostr.mom"
    )

    suspend fun fetchRelayList(pubkey: String): List<String> = withContext(Dispatchers.IO) {
        val relayList = mutableSetOf<String>()
        val latch = CountDownLatch(1) // Wait for at least one valid response or timeout

        if (settingsRepository.isBlastrEnabled()) {
            relayList.add("wss://sendit.nosflare.com/")
        }

        // Try indexers first as they are most reliable forKind 10002
        val targetRelays = indexerRelays + bootstrapRelays
        
        for (url in targetRelays) {
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
                                            synchronized(relayList) {
                                                relayList.add(relayUrl)
                                            }
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
        } catch (_: InterruptedException) {
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
                     Log.d("RelayManager", "PublishEvent: WebSocket opened for $url")
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
                             synchronized(results) {
                                 results[url] = success
                             }
                             Log.d("RelayManager", "PublishEvent: Received OK from $url, success: $success")
                             webSocket.close(1000, "Done")
                             latch.countDown()
                         } else {
                             Log.d("RelayManager", "PublishEvent: Received non-OK message from $url: $text")
                         }
                     } catch (_: Exception) {
                        Log.e("RelayManager", "PublishEvent: Error parsing message from $url: $text")
                     }
                 }

                 override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                     Log.e("RelayManager", "PublishEvent: WebSocket failure for $url: ${t.message}", t)
                     synchronized(results) {
                         results[url] = false
                     }
                     webSocket.close(1000, "Failure") // Explicitly close on failure
                     latch.countDown()
                 }
                 
                 override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                     Log.d("RelayManager", "PublishEvent: WebSocket closing for $url with code $code, reason: $reason")
                     synchronized(results) {
                         if (results[url] == null) {
                            results[url] = false
                            latch.countDown()
                         }
                     }
                 }
             } // Correctly close the WebSocketListener object here
             client.newWebSocket(request, listener)
        }

        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // Timeout
        }
        
        return@withContext results
    }

    suspend fun fetchUserProfile(pubkey: String): UserProfile? = withContext(Dispatchers.IO) {
        var profile: UserProfile? = null
        
        // Use indexers for metadata lookups
        val targetRelays = indexerRelays + bootstrapRelays
        val latch = CountDownLatch(targetRelays.size)

        for (url in targetRelays) {
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
                            try {
                                val content = JSONObject(event.optString("content"))
                                synchronized(this@RelayManager) {
                                    profile = UserProfile(
                                        name = content.optString("name").ifEmpty { content.optString("display_name") },
                                        pictureUrl = content.optString("picture"),
                                        lud16 = content.optString("lud16")
                                    )
                                }
                            } catch (_: Exception) {}
                            webSocket.close(1000, "Done")
                            latch.countDown()
                        } else if (type == "EOSE" && json.optString(1) == subId) {
                            webSocket.close(1000, "EOSE")
                            latch.countDown()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    webSocket.close(1000, "Failure") // Explicitly close on failure
                    latch.countDown()
                }
            }
            client.newWebSocket(request, listener)
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { }
        
        return@withContext profile
    }
    
    suspend fun fetchUserProfiles(
        pubkeys: List<String>,
        onProfileFound: ((String, UserProfile) -> Unit)? = null
    ): Map<String, UserProfile> = withContext(Dispatchers.IO) {
        if (pubkeys.isEmpty()) return@withContext emptyMap()
        
        val results = mutableMapOf<String, UserProfile>()
        
        // Chunk pubkeys into batches of 50 to avoid relay filter limits
        val chunks = pubkeys.chunked(50)
        
        for (chunk in chunks) {
            val latch = CountDownLatch(indexerRelays.size)
            
            for (url in indexerRelays) {
                val request = Request.Builder().url(url).build()
                val listener = object : WebSocketListener() {
                    val subId = UUID.randomUUID().toString()
                    
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        try {
                            val filter = JSONObject()
                            filter.put("kinds", JSONArray().put(0)) 
                            val authors = JSONArray()
                            chunk.forEach { authors.put(it) }
                            filter.put("authors", authors)
                            
                            val req = JSONArray()
                            req.put("REQ")
                            req.put(subId)
                            req.put(filter)
                            webSocket.send(req.toString())
                        } catch (_: Exception) {
                            webSocket.close(1000, "Error")
                            latch.countDown()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val json = JSONArray(text)
                            val type = json.optString(0)
                            if (type == "EVENT" && json.optString(1) == subId) {
                                val event = json.getJSONObject(2)
                                val pk = event.optString("pubkey")
                                try {
                                    val content = JSONObject(event.optString("content"))
                                    val profile = UserProfile(
                                        name = content.optString("name").ifEmpty { content.optString("display_name") },
                                        pictureUrl = content.optString("picture"),
                                        lud16 = content.optString("lud16")
                                    )
                                    synchronized(results) { 
                                        if (!results.containsKey(pk)) {
                                            results[pk] = profile
                                            onProfileFound?.invoke(pk, profile)
                                        }
                                    }
                                } catch (_: Exception) {}
                            } else if (type == "EOSE" && json.optString(1) == subId) {
                                webSocket.close(1000, "EOSE")
                                latch.countDown()
                            }
                        } catch (e: Exception) { }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        latch.countDown()
                    }
                }
                client.newWebSocket(request, listener)
            }
            
            try {
                // Wait up to 3 seconds per chunk
                latch.await(3, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }
        
        return@withContext results
    }

    suspend fun fetchBlossomServerList(pubkey: String): List<String> = withContext(Dispatchers.IO) {
        val serverList = mutableSetOf<String>()
        val latch = CountDownLatch(1)
        
        val targetRelays = indexerRelays + bootstrapRelays

        for (url in targetRelays) {
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    filter.put("kinds", JSONArray().put(10063)) // Kind 10063: Blossom Server List
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
                                    if (tag.length() > 1 && tag.getString(0) == "server") {
                                        synchronized(serverList) {
                                            serverList.add(tag.getString(1))
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
                     // Fail silently
                }
            }
            client.newWebSocket(request, listener)
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) { }
        
        return@withContext serverList.toList()
    }

    suspend fun fetchContactList(pubkey: String, relays: List<String> = emptyList()): Set<String> = withContext(Dispatchers.IO) {
        val follows = mutableSetOf<String>()
        val latch = CountDownLatch(1)
        val targetRelays = if (relays.isNotEmpty()) relays else (indexerRelays + bootstrapRelays)

        for (url in targetRelays) {
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    filter.put("kinds", JSONArray().put(3)) // Kind 3: Contacts
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
                                    if (tag.length() > 1 && tag.getString(0) == "p") {
                                        synchronized(follows) {
                                            follows.add(tag.getString(1))
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
                     latch.countDown()
                }
            }
            client.newWebSocket(request, listener)
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { }
        
        return@withContext follows
    }

    suspend fun fetchEvent(eventId: String, relays: List<String> = emptyList()): JSONObject? = withContext(Dispatchers.IO) {
        val relayList = if (relays.isNotEmpty()) relays else bootstrapRelays
        val latch = CountDownLatch(1)
        var result: JSONObject? = null

        for (url in relayList) {
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    filter.put("ids", JSONArray().put(eventId))
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
                            synchronized(this@RelayManager) { // Synchronize on the outer class instance
                                result = json.getJSONObject(2)
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
        
        return@withContext result
    }

    suspend fun fetchAddress(kind: Int, pubkey: String, identifier: String, relays: List<String> = emptyList()): JSONObject? = withContext(Dispatchers.IO) {
        val relayList = if (relays.isNotEmpty()) relays else bootstrapRelays
        val latch = CountDownLatch(1)
        var result: JSONObject? = null

        for (url in relayList) {
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    filter.put("kinds", JSONArray().put(kind))
                    filter.put("authors", JSONArray().put(pubkey))
                    val dTag = JSONArray().put(identifier)
                    filter.put("#d", dTag)
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
                            synchronized(this@RelayManager) { // Synchronize on the outer class instance
                                result = json.getJSONObject(2)
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
                }
            }
            client.newWebSocket(request, listener)
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) { }
        
        return@withContext result
    }
    
    suspend fun searchUsers(query: String): List<Pair<String, UserProfile>> = withContext(Dispatchers.IO) {
        val searchRelays = listOf(
            "wss://relay.noswhere.com",
            "wss://nostr.wine",
            "wss://search.nos.today"
        )
        val results = mutableMapOf<String, UserProfile>()
        val latch = CountDownLatch(searchRelays.size)

        for (url in searchRelays) {
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    filter.put("kinds", JSONArray().put(0)) // Kind 0: Metadata
                    filter.put("search", query)
                    filter.put("limit", 15)
                    
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
                            val pubkey = event.optString("pubkey")
                            val contentStr = event.optString("content")
                            if (contentStr.isNotEmpty() && pubkey.isNotEmpty()) {
                                try {
                                    val content = JSONObject(contentStr)
                                    val profile = UserProfile(
                                        name = content.optString("name", "").ifEmpty { content.optString("display_name") },
                                        pictureUrl = content.optString("picture"),
                                        lud16 = content.optString("lud16")
                                    )
                                    synchronized(results) {
                                        results[pubkey] = profile
                                    }
                                } catch (_: Exception) {}
                            }
                        } else if (type == "EOSE" && json.optString(1) == subId) {
                           webSocket.close(1000, "EOSE")
                           latch.countDown()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                     latch.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (latch.count > 0) {
                        latch.countDown()
                    }
                }
            }
            client.newWebSocket(request, listener)
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) { }
        
        return@withContext results.toList()
    }

    fun createBlossomServerListEventJson(pubkey: String, servers: List<String>): String {
        val event = JSONObject()
        event.put("kind", 10063)
        event.put("created_at", System.currentTimeMillis() / 1000)
        event.put("pubkey", pubkey)
        event.put("content", "")
        
        val tags = JSONArray()
        servers.forEach { server ->
            val tag = JSONArray()
            tag.put("server")
            tag.put(server)
            tags.put(tag)
        }
        event.put("tags", tags)
        event.put("id", "")
        event.put("sig", "")
        
        return event.toString()
    }
}


