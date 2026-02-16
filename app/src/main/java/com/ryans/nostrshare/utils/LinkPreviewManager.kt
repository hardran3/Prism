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

    suspend fun fetchMetadata(url: String): LinkMetadata? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext it }

        try {
            val client = NostrShareApp.getInstance().client
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null
            
            val doc = Jsoup.parse(html)
            val title = doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
                ?: doc.title().takeIf { it.isNotBlank() }
            
            val description = doc.select("meta[property=og:description]").attr("content").takeIf { it.isNotBlank() }
                ?: doc.select("meta[name=description]").attr("content").takeIf { it.isNotBlank() }
            
            val image = doc.select("meta[property=og:image]").attr("content").takeIf { it.isNotBlank() }
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
        } catch (e: Exception) {
            null
        }
    }
}
