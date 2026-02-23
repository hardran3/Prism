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
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, LinkMetadata>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, LinkMetadata>?) = size > 100
    })
    private val mediaExtensionPattern = ".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|mp4|mov|webm|zip|pdf|exe|dmg|iso|apk)$".toRegex(RegexOption.IGNORE_CASE)

    suspend fun fetchMetadata(url: String): LinkMetadata? = withContext(Dispatchers.IO) {
        if (mediaExtensionPattern.matches(url.substringBefore("?"))) return@withContext null
        cache[url]?.let { return@withContext it }

        try {
            val client = NostrShareApp.getInstance().client
            
            // Single GET request: More robust than HEAD + multiple GETs
            val request = Request.Builder()
                .url(url)
                // Use a Social Media Bot User-Agent to trigger metadata-rich, lighter HTML from YouTube/video platforms
                .header("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
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
                source.request(1024 * 1024) // Request up to 1MB to ensures we find metadata tags on heavy pages
                
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
