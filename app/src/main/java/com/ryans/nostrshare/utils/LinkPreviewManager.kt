package com.ryans.nostrshare.utils

import com.ryans.nostrshare.NostrShareApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

data class LinkMetadata(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null
)

object LinkPreviewManager {
    private val cache = ConcurrentHashMap<String, LinkMetadata>()
    private val mediaExtensionPattern = ".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|mp4|mov|webm|zip|pdf|exe|dmg|iso|apk)$".toRegex(RegexOption.IGNORE_CASE)

    suspend fun fetchMetadata(url: String): LinkMetadata? = withContext(Dispatchers.IO) {
        if (mediaExtensionPattern.matches(url.substringBefore("?"))) return@withContext null
        cache[url]?.let { return@withContext it }

        try {
            val client = NostrShareApp.getInstance().client
            
            // Single GET request: More robust than HEAD + multiple GETs
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                
                // 1. Check Headers from the same response
                val contentType = response.header("Content-Type") ?: ""
                val isHtml = contentType.contains("text/html", ignoreCase = true) || 
                             contentType.contains("application/xhtml+xml", ignoreCase = true)
                
                if (!isHtml) return@use null

                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                if (contentLength > 15 * 1024 * 1024) return@use null // 15MB limit

                // 2. Read Body: Request up to 512KB to ensure we find metadata tags
                val body = response.body ?: return@use null
                val source = body.source()
                source.request(512 * 1024) 
                
                val doc = Jsoup.parse(source.buffer.clone().inputStream(), null, url)
                
                val title = doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
                    ?: doc.select("meta[name=twitter:title]").attr("content").takeIf { it.isNotBlank() }
                    ?: doc.title().takeIf { it.isNotBlank() }
                
                val description = doc.select("meta[property=og:description]").attr("content").takeIf { it.isNotBlank() }
                    ?: doc.select("meta[name=twitter:description]").attr("content").takeIf { it.isNotBlank() }
                    ?: doc.select("meta[name=description]").attr("content").takeIf { it.isNotBlank() }
                
                val image = doc.select("meta[property=og:image]").attr("content").takeIf { it.isNotBlank() }
                    ?: doc.select("meta[name=twitter:image]").attr("content").takeIf { it.isNotBlank() }
                
                val siteName = doc.select("meta[property=og:site_name]").attr("content").takeIf { it.isNotBlank() }

                val metadata = LinkMetadata(
                    url = url,
                    title = title,
                    description = description,
                    imageUrl = image,
                    siteName = siteName
                )
                
                cache[url] = metadata
                metadata
            }
        } catch (e: Exception) {
            android.util.Log.e("LinkPreviewManager", "Error fetching metadata: ${e.message}")
            null
        }
    }
}
