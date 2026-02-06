package com.ryans.nostrshare

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

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

    fun createAuthEventJson(hash: String, size: Long?, pubkey: String, operation: String): String {
        // Basic Auth Event Template (Kind 24242)
        val event = JSONObject()
        event.put("kind", 24242)
        event.put("created_at", System.currentTimeMillis() / 1000)
        event.put("pubkey", pubkey)
        event.put("content", "Authorization for $operation of $hash")
        
        val tags = org.json.JSONArray()
        tags.put(org.json.JSONArray().put("t").put(operation))
        tags.put(org.json.JSONArray().put("x").put(hash))
        if (size != null) tags.put(org.json.JSONArray().put("size").put(size.toString()))
        tags.put(org.json.JSONArray().put("expiration").put((System.currentTimeMillis() / 1000) + 3600)) // 1 hour exp
        
        event.put("tags", tags)
        
        // Note: This JSON still needs to be signed by the external signer
        return event.toString()
    }

    suspend fun upload(context: Context, uri: Uri, signedAuthEvent: String, server: String, mimeType: String?): String = withContext(Dispatchers.IO) {
        // 1. Create temporary file from URI (needed for OkHttp RequestBody)
        val file = File(context.cacheDir, "upload_temp_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        try {
            val mediaType = (mimeType ?: "application/octet-stream").toMediaTypeOrNull()
            // Auth header
            val authHeader = "Nostr " + android.util.Base64.encodeToString(signedAuthEvent.toByteArray(), android.util.Base64.NO_WRAP)

            // Strategy 1: POST /upload (Primal & others)
            try {
                val requestBody = file.asRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$server/upload")
                    .post(requestBody)
                    .header("Authorization", authHeader)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(responseBody)
                    return@withContext json.optString("url")
                } else {
                     response.close() // Close and try fallback
                }
            } catch (e: Exception) {
                // Ignore and try fallback
            }

            // Strategy 2: PUT /media (Standard Blossom)
            val requestBody2 = file.asRequestBody(mediaType)
            val request2 = Request.Builder()
                .url("$server/media")
                .put(requestBody2)
                .header("Authorization", authHeader)
                .build()

            val response2 = client.newCall(request2).execute()
            if (!response2.isSuccessful) {
                 // Try one last thing: PUT /<sha256> (Wait, we need the hash for that, let's stick to /media for now as per spec)
                 throw Exception("Upload failed on both POST /upload and PUT /media. Code: ${response2.code}")
            }
            
            val responseBody = response2.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(responseBody)
            return@withContext json.optString("url")

        } finally {
            file.delete()
        }
    }

    suspend fun delete(hash: String, signedAuthEvent: String, server: String): Boolean = withContext(Dispatchers.IO) {
        val authHeader = "Nostr " + android.util.Base64.encodeToString(signedAuthEvent.toByteArray(), android.util.Base64.NO_WRAP)
        
        val request = Request.Builder()
            .url("$server/media/$hash")
            .delete()
            .header("Authorization", authHeader)
            .build()
            
        val response = client.newCall(request).execute()
        return@withContext response.isSuccessful
    }
}
