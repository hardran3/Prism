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
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import android.util.Log

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class RelayManager(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {
    private val eventCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, JSONObject>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, JSONObject>?) = size > 200
    })
    private val bootstrapRelays = listOf("wss://relay.damus.io", "wss://nos.lol")
    private val indexerRelays = listOf(
        "wss://purplepag.es", 
        "wss://indexer.coracle.social", 
        "wss://user.kindpag.es",
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://nostr.mom"
    )

    suspend fun fetchRelayList(pubkey: String, isRead: Boolean = true): List<String> = withContext(Dispatchers.IO) {
        val relayList = mutableSetOf<String>()
        if (!isRead && settingsRepository.isBlastrEnabled(pubkey)) {
            relayList.add("wss://sendit.nosflare.com/")
        }

        // Try indexers first as they are most reliable forKind 10002
        val targetRelays = indexerRelays + bootstrapRelays
        val latch = CountDownLatch(targetRelays.size)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
        
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
                           latch.countDown()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        latch.countDown()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                     latch.countDown()
                }
            }
            activeSockets.add(client.newWebSocket(request, listener))
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // Timeout
        }
        
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        if (relayList.isEmpty()) {
            return@withContext bootstrapRelays 
        }
        return@withContext relayList.toList()
    }

    suspend fun publishEvent(signedEventJson: String, relays: List<String>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Boolean>()
        val latch = CountDownLatch(relays.size)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
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
             activeSockets.add(client.newWebSocket(request, listener))
        }

        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // Timeout
        }
        
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        return@withContext results
    }

    suspend fun fetchLatestParallel(
        pubkey: String,
        kinds: List<Int>,
        since: Long,
        onNote: (JSONObject) -> Unit
    ) = withContext(Dispatchers.IO) {
        val userRelays = fetchRelayList(pubkey, isRead = true).distinct()
        val latch = CountDownLatch(userRelays.size)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<WebSocket>())
        
        Log.d("RelayManager", "Starting Parallel Fetch from ${userRelays.size} relays since $since")

        for (relayUrl in userRelays) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(relayUrl).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    val kindsArray = JSONArray()
                    kinds.forEach { kindsArray.put(it) }
                    filter.put("authors", JSONArray().put(pubkey))
                    filter.put("kinds", kindsArray)
                    filter.put("since", since)
                    filter.put("limit", 50)
                    
                    val req = JSONArray().put("REQ").put(subId).put(filter)
                    webSocket.send(req.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONArray(text)
                        val type = json.optString(0)
                        if (type == "EVENT" && json.optString(1) == subId) {
                            onNote(json.getJSONObject(2))
                        } else if (type == "EOSE" && json.optString(1) == subId) {
                            webSocket.close(1000, "Done")
                            latch.countDown()
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    latch.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            }
            activeSockets.add(client.newWebSocket(request, listener))
        }

        // Wait up to 8 seconds for the fastest relays to respond
        latch.await(8, TimeUnit.SECONDS)
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
    }

    suspend fun fetchHistoryFromRelays(
        pubkey: String,
        kinds: List<Int>,
        relays: List<String>? = null,
        searchTerm: String? = null,
        since: Long? = null,
        until: Long? = null,
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onNote: (JSONObject) -> Unit
    ) = withContext(Dispatchers.IO) {
        val userRelays = if (relays.isNullOrEmpty()) fetchRelayList(pubkey, isRead = true) else relays
        val targetRelays = if (searchTerm.isNullOrBlank()) {
            userRelays.distinct()
        } else {
            val searchRelays = listOf(
                "wss://relay.noswhere.com",
                "wss://nostr.wine",
                "wss://search.nos.today"
            )
            (userRelays + searchRelays).distinct()
        }

        Log.d("RelayManager", "Starting persistent Citrine-style fetch from ${targetRelays.size} relays.")

        targetRelays.forEachIndexed { index, relayUrl ->
            currentCoroutineContext().ensureActive()
            Log.d("RelayManager", "Syncing relay: $relayUrl")
            onProgress?.invoke(relayUrl, index + 1, targetRelays.size)

            val terminalLatch = CountDownLatch(1)
            var currentUntil = until ?: (System.currentTimeMillis() / 1000)
            
            val request = Request.Builder().url(relayUrl).build()
            val listener = object : WebSocketListener() {
                private val subId = UUID.randomUUID().toString()
                private var eventsInBatch = 0
                private var oldestInBatch = Long.MAX_VALUE

                fun sendNextReq(ws: WebSocket) {
                    eventsInBatch = 0
                    oldestInBatch = Long.MAX_VALUE
                    val filter = JSONObject()
                    filter.put("authors", JSONArray().put(pubkey))
                    val kindsArray = JSONArray()
                    kinds.forEach { kindsArray.put(it) }
                    filter.put("kinds", kindsArray)
                    filter.put("limit", 500)
                    filter.put("until", currentUntil)
                    since?.let { filter.put("since", it) }
                    searchTerm?.takeIf { it.isNotBlank() }?.let { filter.put("search", it) }
                    
                    val req = JSONArray().put("REQ").put(subId).put(filter)
                    ws.send(req.toString())
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sendNextReq(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONArray(text)
                        val type = json.optString(0)
                        if (type == "EVENT" && json.optString(1) == subId) {
                            val event = json.getJSONObject(2)
                            eventsInBatch++
                            val createdAt = event.optLong("created_at")
                            if (createdAt < oldestInBatch) oldestInBatch = createdAt
                            onNote(event)
                        } else if (type == "EOSE" && json.optString(1) == subId) {
                            if (eventsInBatch > 0 && oldestInBatch < currentUntil) {
                                currentUntil = oldestInBatch - 1
                                sendNextReq(webSocket)
                            } else {
                                webSocket.close(1000, "Finished")
                                terminalLatch.countDown()
                            }
                        } else if (type == "CLOSED" && json.optString(1) == subId) {
                            terminalLatch.countDown()
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w("RelayManager", "Failure on $relayUrl: ${t.message}")
                    terminalLatch.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    terminalLatch.countDown()
                }
            }

            val ws = client.newWebSocket(request, listener)
            try {
                // Wait for the relay sync to complete or timeout
                val completed = terminalLatch.await(5, TimeUnit.MINUTES)
                if (!completed) Log.w("RelayManager", "Timed out syncing $relayUrl")
            } finally {
                ws.cancel()
            }
        }
    }

    suspend fun fetchUserProfile(pubkey: String): UserProfile? = withContext(Dispatchers.IO) {
        val result = java.util.concurrent.atomic.AtomicReference<UserProfile?>(null)
        val latch = CountDownLatch(1)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
        val checkedRelays = java.util.Collections.synchronizedSet(HashSet<String>())
        
        fun search(url: String) {
            if (!checkedRelays.add(url)) return
            
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
                            val createdAt = event.optLong("created_at", 0L)
                            try {
                                val content = JSONObject(event.optString("content"))
                                val profile = UserProfile(
                                    name = content.optString("name").ifEmpty { content.optString("display_name") },
                                    pictureUrl = content.optString("picture"),
                                    lud16 = content.optString("lud16"),
                                    createdAt = createdAt
                                )
                                
                                synchronized(result) {
                                    val current = result.get()
                                    if (current == null || createdAt > current.createdAt) {
                                        result.set(profile)
                                    }
                                }
                            } catch (_: Exception) {}
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
            activeSockets.add(client.newWebSocket(request, listener))
        }

        // 1. Race bootstrap + indexers immediately
        val targetRelays = mutableListOf<String>().apply { addAll(indexerRelays + bootstrapRelays) }
        if (settingsRepository.isCitrineRelayEnabled(pubkey)) {
            targetRelays.add("ws://127.0.0.1:4869")
        }
        val uniqueTargetRelays = targetRelays.distinct()
        uniqueTargetRelays.forEach { search(it) }
        
        // 2. Async: Fetch Author NIP-65 Relays and race them too
        launch {
            try {
                val authorRelays = fetchRelayList(pubkey)
                authorRelays.forEach { search(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { }
        
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        return@withContext result.get()
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
            val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
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
                                val createdAt = event.optLong("created_at", 0L)
                                try {
                                    val content = JSONObject(event.optString("content"))
                                    val profile = UserProfile(
                                        name = content.optString("name").ifEmpty { content.optString("display_name") },
                                        pictureUrl = content.optString("picture"),
                                        lud16 = content.optString("lud16"),
                                        createdAt = createdAt
                                    )
                                    synchronized(results) { 
                                        val existing = results[pk]
                                        if (existing == null || createdAt > existing.createdAt) {
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
                activeSockets.add(client.newWebSocket(request, listener))
            }
            
            try {
                // Wait up to 3 seconds per chunk
                latch.await(3, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }
        
        return@withContext results
    }

    suspend fun fetchBlossomServerList(pubkey: String): List<String> = withContext(Dispatchers.IO) {
        val serverList = mutableListOf<String>()
        var latestTimestamp = 0L
        val latch = CountDownLatch(indexerRelays.size + bootstrapRelays.size)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
        val targetRelays = indexerRelays + bootstrapRelays

        for (url in targetRelays) {
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    filter.put("kinds", JSONArray().put(10063)) // Kind 10063: Blossom Server List
                    filter.put("authors", JSONArray().put(pubkey))
                    filter.put("limit", 5) // Get a few latest to be safe
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
                            val createdAt = event.optLong("created_at", 0L)
                            
                            synchronized(this@RelayManager) {
                                if (createdAt > latestTimestamp) {
                                    latestTimestamp = createdAt
                                    serverList.clear()
                                    val tags = event.optJSONArray("tags")
                                    if (tags != null) {
                                        for (i in 0 until tags.length()) {
                                            val tag = tags.getJSONArray(i)
                                            if (tag.length() > 1 && tag.getString(0) == "server") {
                                                serverList.add(tag.getString(1))
                                            }
                                        }
                                    }
                                }
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
            }
            activeSockets.add(client.newWebSocket(request, listener))
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) { }
        
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        return@withContext serverList.toList()
    }

    suspend fun fetchContactList(pubkey: String, relays: List<String> = emptyList()): Set<String> = withContext(Dispatchers.IO) {
        val follows = mutableSetOf<String>()
        val latch = CountDownLatch(1)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
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
            activeSockets.add(client.newWebSocket(request, listener))
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { }
        
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        return@withContext follows
    }

    suspend fun fetchUserNotes(pubkey: String, kinds: List<Int>, relays: List<String> = emptyList(), since: Long? = null, until: Long? = null, limit: Int = 100): List<JSONObject> = withContext(Dispatchers.IO) {
        val notes = mutableListOf<JSONObject>()
        // Use user's outbox relays (kind 10002) if available, fallback to indexers
        val userRelays = if (relays.isEmpty()) fetchRelayList(pubkey) else emptyList()
        val baseRelays = if (relays.isNotEmpty()) relays else if (userRelays.isNotEmpty()) userRelays else (indexerRelays + bootstrapRelays).distinct()
        
        val targetRelays = mutableListOf<String>().apply { addAll(baseRelays) }
        if (settingsRepository.isCitrineRelayEnabled(pubkey) || settingsRepository.isCitrineRelayEnabled()) {
            targetRelays.add("ws://127.0.0.1:4869")
        }
        val uniqueTargetRelays = targetRelays.distinct()
        android.util.Log.d("RelayManager", "Fetching notes from relays: $uniqueTargetRelays")
        
        val latch = CountDownLatch(uniqueTargetRelays.size)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
        
        for (url in uniqueTargetRelays) {
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                val subId = UUID.randomUUID().toString()
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                    if (kinds.isNotEmpty()) {
                        val kindsArray = JSONArray()
                        kinds.forEach { kindsArray.put(it) }
                        filter.put("kinds", kindsArray)
                    }
                    filter.put("authors", JSONArray().put(pubkey))
                    if (since != null) {
                        filter.put("since", since)
                    }
                    if (until != null) {
                        filter.put("until", until)
                    }
                    filter.put("limit", limit)
                    
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
                            
                            synchronized(notes) {
                                // Avoid duplicates
                                if (notes.none { it.optString("id") == event.optString("id") }) {
                                    notes.add(event)
                                }
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
                    android.util.Log.e("RelayManager", "WebSocket failure on ${request.url}: ${t.message}", t)
                    latch.countDown()
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            }
            activeSockets.add(client.newWebSocket(request, listener))
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { }
        
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        return@withContext notes.sortedByDescending { it.optLong("created_at", 0L) }
    }

    suspend fun fetchEvent(eventId: String, relays: List<String> = emptyList(), authorPubkey: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        eventCache[eventId]?.let { return@withContext it }
        
        val result = java.util.concurrent.atomic.AtomicReference<JSONObject?>(null)
        val latch = CountDownLatch(1)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
        val checkedRelays = java.util.Collections.synchronizedSet(HashSet<String>())
        
        // Helper to launch a search on a specific relay
        fun search(url: String) {
            if (!checkedRelays.add(url)) return
            
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
                            val event = json.getJSONObject(2)
                            if (result.compareAndSet(null, event)) {
                                synchronized(this@RelayManager) {
                                    eventCache[eventId] = event
                                }
                                latch.countDown()
                            }
                            webSocket.close(1000, "Done")
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
            activeSockets.add(client.newWebSocket(request, listener))
        }
        
        // 1. Race provided relays + bootstrap + indexers immediately
        val initialRelays = mutableListOf<String>().apply { addAll(relays + bootstrapRelays + indexerRelays) }
        if (settingsRepository.isCitrineRelayEnabled(authorPubkey) || settingsRepository.isCitrineRelayEnabled()) {
             initialRelays.add("ws://127.0.0.1:4869")
         }
        val uniqueInitialRelays = initialRelays.distinct()
        uniqueInitialRelays.forEach { search(it) }
        
        // 2. Async: Fetch Author NIP-65 Relays and race them too
        if (authorPubkey != null) {
            launch {
                try {
                    val authorRelays = fetchRelayList(authorPubkey)
                    authorRelays.forEach { search(it) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) { }
        
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        return@withContext result.get()
    }

    suspend fun fetchAddress(kind: Int, pubkey: String, identifier: String, relays: List<String> = emptyList()): JSONObject? = withContext(Dispatchers.IO) {
        val cacheKey = "$kind:$pubkey:$identifier"
        eventCache[cacheKey]?.let { return@withContext it }
        
        val result = java.util.concurrent.atomic.AtomicReference<JSONObject?>(null)
        val latch = CountDownLatch(1)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
        val checkedRelays = java.util.Collections.synchronizedSet(HashSet<String>())
        
        fun search(url: String) {
            if (!checkedRelays.add(url)) return
            
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
                            val event = json.getJSONObject(2)
                             if (result.compareAndSet(null, event)) {
                                synchronized(this@RelayManager) {
                                    eventCache[cacheKey] = event
                                }
                                latch.countDown()
                            }
                            webSocket.close(1000, "Done")
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
            activeSockets.add(client.newWebSocket(request, listener))
        }
        
        // 1. Race provided relays + bootstrap + indexers
        val initialRelays = (relays + bootstrapRelays + indexerRelays).distinct()
        initialRelays.forEach { search(it) }
        
        // 2. Async: Fetch Author NIP-65 (We always know author for address)
        launch {
            try {
                val authorRelays = fetchRelayList(pubkey)
                authorRelays.forEach { search(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) { }
        
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        return@withContext result.get()
    }
    
    suspend fun searchUsers(query: String): List<Pair<String, UserProfile>> = withContext(Dispatchers.IO) {
        val searchRelays = listOf(
            "wss://relay.noswhere.com",
            "wss://nostr.wine",
            "wss://search.nos.today"
        )
        val results = mutableMapOf<String, UserProfile>()
        val latch = CountDownLatch(searchRelays.size)
        val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
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
                     android.util.Log.e("RelayManager", "WebSocket failure on ${request.url}: ${t.message}", t)
                     latch.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (latch.count > 0) {
                        latch.countDown()
                    }
                }
            }
            activeSockets.add(client.newWebSocket(request, listener))
        }
        
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) { }
        
        activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        return@withContext results.toList()
    }

    suspend fun fetchRelayListsBatch(pubkeys: List<String>): Map<String, List<String>> = withContext(Dispatchers.IO) {
        if (pubkeys.isEmpty()) return@withContext emptyMap()
        val results = mutableMapOf<String, List<String>>()
        val chunks = pubkeys.chunked(50)

        for (chunk in chunks) {
            val latch = CountDownLatch(indexerRelays.size)
            val activeSockets = java.util.Collections.synchronizedList(mutableListOf<okhttp3.WebSocket>())
            for (url in indexerRelays) {
                val request = Request.Builder().url(url).build()
                val listener = object : WebSocketListener() {
                    val subId = UUID.randomUUID().toString()
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        try {
                            val filter = JSONObject()
                            filter.put("kinds", JSONArray().put(10002))
                            val authors = JSONArray()
                            chunk.forEach { authors.put(it) }
                            filter.put("authors", authors)
                            webSocket.send(JSONArray().put("REQ").put(subId).put(filter).toString())
                        } catch (_: Exception) { latch.countDown() }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val json = JSONArray(text)
                            if (json.optString(0) == "EVENT" && json.optString(1) == subId) {
                                val event = json.getJSONObject(2)
                                val pk = event.optString("pubkey")
                                val tags = event.optJSONArray("tags") ?: return
                                val relayUrls = mutableListOf<String>()
                                for (i in 0 until tags.length()) {
                                    val tag = tags.getJSONArray(i)
                                    if (tag.length() > 1 && tag.getString(0) == "r") relayUrls.add(tag.getString(1))
                                }
                                synchronized(results) { results[pk] = relayUrls }
                            } else if (json.optString(0) == "EOSE" && json.optString(1) == subId) {
                                webSocket.close(1000, "Done")
                                latch.countDown()
                            }
                        } catch (_: Exception) {}
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { latch.countDown() }
                }
                activeSockets.add(client.newWebSocket(request, listener))
            }
            try { latch.await(5, TimeUnit.SECONDS) } catch (_: Exception) {}
            activeSockets.forEach { try { it.cancel() } catch (_: Exception) {} }
        }
        return@withContext results
    }

    suspend fun checkRelayHealth(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val httpUrl = url.trim().removeSuffix("/").replace("wss://", "https://").replace("ws://", "http://")
            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/nostr+json")
                .build()
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            try {
                val latch = CountDownLatch(1)
                var success = false
                val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        success = true
                        webSocket.close(1000, "ping")
                        latch.countDown()
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { latch.countDown() }
                })
                latch.await(3, TimeUnit.SECONDS)
                ws.cancel()
                success
            } catch (_: Exception) { false }
        }
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
