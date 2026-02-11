package com.ryans.nostrshare

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject

class BlossomClient(private val client: OkHttpClient) {

    suspend fun hashFile(context: Context, uri: Uri): Pair<String, Long> = withContext(Dispatchers.IO) {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalBytes: Long = 0

        inputStream.use { stream ->
            if (stream == null) throw Exception("Cannot open file")
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                totalBytes += bytesRead.toLong()
            }
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        Pair(hash, totalBytes)
    }

    fun createAuthEventJson(
        hash: String, 
        size: Long?, 
        pubkey: String, 
        operation: String, 
        serverUrl: String? = null,
        fileName: String? = null,
        mimeType: String? = null
    ): String {
        // Basic Auth Event Template (Kind 24242)
        val event = JSONObject()
        event.put("kind", 24242)
        event.put("created_at", System.currentTimeMillis() / 1000)
        event.put("pubkey", pubkey)
        
        // Amethyst uses "Uploading $fileName" or "Deleting $hash"
        val displayFileName = fileName ?: hash.take(8)
        val content = if (operation == "delete") "Deleting $hash" else "Uploading $displayFileName"
        event.put("content", content)
        
        val tags = org.json.JSONArray()
        // Amethyst Tag Order: t, expiration, size, x
        val authAction = if (operation == "mirror") "upload" else operation
        tags.put(org.json.JSONArray().put("t").put(authAction))
        
        val exp = (System.currentTimeMillis() / 1000) + 3600
        tags.put(org.json.JSONArray().put("expiration").put(exp.toString()))
        
        if (size != null) tags.put(org.json.JSONArray().put("size").put(size.toString()))
        tags.put(org.json.JSONArray().put("x").put(hash))
        
        // Optional tags used by other clients
        if (serverUrl != null) tags.put(org.json.JSONArray().put("server").put(serverUrl.replace(Regex("/$"), "")))
        if (fileName != null) tags.put(org.json.JSONArray().put("name").put(fileName))
        if (mimeType != null) tags.put(org.json.JSONArray().put("type").put(mimeType))
        
        event.put("tags", tags)
        
        return event.toString()
    }

    data class UploadResult(val url: String, val serverHash: String?)

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    suspend fun upload(context: Context, data: ByteArray, signedAuthEvent: String, server: String, mimeType: String?): UploadResult = withContext(Dispatchers.IO) {
        try {
            val mediaType = (mimeType ?: "application/octet-stream").toMediaTypeOrNull()
            
            // Clean up the signed event and base64 encode it
            val cleanedAuth = signedAuthEvent.trim()
            val authHeader = "Nostr " + android.util.Base64.encodeToString(cleanedAuth.toByteArray(), android.util.Base64.NO_WRAP)

            var lastError = ""
            val cleanServer = server.removeSuffix("/")

            // Strategy 1: PUT /upload (Amethyst Alignment)
            try {
                val requestBody = data.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$cleanServer/upload")
                    .put(requestBody)
                    .header("Authorization", authHeader)
                    .header("Content-Type", mimeType ?: "application/octet-stream")
                    .header("Content-Length", data.size.toString())
                    .header("User-Agent", "Prism/1.0") 
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(responseBody)
                    val urlRes = json.optString("url")
                    val serverHash = json.optString("sha256").takeIf { it.isNotBlank() }
                        ?: extractHashFromUrl(urlRes)
                    return@withContext UploadResult(urlRes, serverHash)
                } else {
                     val body = response.body?.string() ?: "No body"
                     lastError = "PUT /upload failed (${response.code}): $body"
                     response.close()
                }
            } catch (e: Exception) {
                lastError = "PUT /upload error: ${e.message}"
            }

            // Strategy 2: POST /upload (Fallback)
            try {
                val requestBody = data.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$cleanServer/upload")
                    .post(requestBody)
                    .header("Authorization", authHeader)
                    .header("Content-Type", mimeType ?: "application/octet-stream")
                    .header("Content-Length", data.size.toString())
                    .header("User-Agent", "Prism/1.0")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(responseBody)
                    val urlRes = json.optString("url")
                    val serverHash = json.optString("sha256").takeIf { it.isNotBlank() }
                        ?: extractHashFromUrl(urlRes)
                    return@withContext UploadResult(urlRes, serverHash)
                } else {
                     val body = response.body?.string() ?: "No body"
                     lastError += " | POST /upload failed (${response.code}): $body"
                     response.close()
                }
            } catch (e: Exception) {
                lastError += " | POST /upload error: ${e.message}"
            }

            // Strategy 3: PUT /media (Standard Blossom /media endpoint)
            val requestBody2 = data.toRequestBody(mediaType)
            val request2 = Request.Builder()
                .url("$cleanServer/media")
                .put(requestBody2)
                .header("Authorization", authHeader)
                .header("Content-Type", mimeType ?: "application/octet-stream")
                .header("Content-Length", data.size.toString())
                .header("User-Agent", "Prism/1.0")
                .build()

            val response2 = client.newCall(request2).execute()
            if (!response2.isSuccessful) {
                 val body = response2.body?.string() ?: "No body"
                 throw Exception("$lastError | PUT /media failed (${response2.code}): $body")
            }
            
            val responseBody = response2.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(responseBody)
            val url = json.optString("url")
            val serverHash = json.optString("sha256").takeIf { it.isNotBlank() }
                ?: extractHashFromUrl(url)
            return@withContext UploadResult(url, serverHash)

        } catch (e: Exception) {
            throw e
        }
    }

    private fun extractHashFromUrl(url: String): String? {
        // URLs typically look like https://server.com/<hash>.ext or https://server.com/<hash>
        val pathSegment = url.substringAfterLast("/").substringBefore(".")
        // SHA-256 hash is 64 hex characters
        return if (pathSegment.length == 64 && pathSegment.all { it.isLetterOrDigit() }) pathSegment else null
    }

    suspend fun delete(hash: String, signedAuthEvent: String, server: String): Boolean = withContext(Dispatchers.IO) {
        val authHeader = "Nostr " + android.util.Base64.encodeToString(signedAuthEvent.toByteArray(), android.util.Base64.NO_WRAP)
        
        // Strategy 1: DELETE /<hash> (Standard Blossom)
        try {
            val request = Request.Builder()
                .url("$server/$hash")
                .delete()
                .header("Authorization", authHeader)
                .build()
                
            val response = client.newCall(request).execute()
            if (response.isSuccessful) return@withContext true
            response.close()
        } catch (_: Exception) {
            // Fallback
        }

        // Strategy 2: DELETE /media/<hash> (Legacy/Custom)
        val request2 = Request.Builder()
            .url("$server/media/$hash")
            .delete()
            .header("Authorization", authHeader)
            .build()
            
        val response2 = client.newCall(request2).execute()
        return@withContext response2.isSuccessful
    }

    /**
     * Mirror a blob from a source URL to this server (BUD-04).
     * The server will fetch the file from sourceUrl and store it.
     */
    suspend fun mirror(sourceUrl: String, signedAuthEvent: String, server: String): UploadResult = withContext(Dispatchers.IO) {
        val authHeader = "Nostr " + android.util.Base64.encodeToString(signedAuthEvent.toByteArray(), android.util.Base64.NO_WRAP)
        
        // BUD-04: PUT /mirror with URL in request body
        val requestBody = sourceUrl.toRequestBody("text/plain".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("$server/mirror")
            .put(requestBody)
            .header("Authorization", authHeader)
            .build()
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: "No body"
            throw Exception("Mirror failed (${response.code}): $body")
        }
        
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val json = JSONObject(responseBody)
        val url = json.optString("url")
        val serverHash = json.optString("sha256").takeIf { it.isNotBlank() }
            ?: extractHashFromUrl(url)
        return@withContext UploadResult(url, serverHash)
    }
}
