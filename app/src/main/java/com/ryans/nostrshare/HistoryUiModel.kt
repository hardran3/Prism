package com.ryans.nostrshare

import com.ryans.nostrshare.data.Draft
import com.ryans.nostrshare.nip55.PostKind
import com.ryans.nostrshare.ui.ContentSegment
import com.ryans.nostrshare.utils.LinkMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Lightweight UI model to prevent OOM and lag
data class HistoryUiModel(
    val id: String,
    val localId: Int,
    val contentSnippet: String,
    val timestamp: Long,
    val pubkey: String?,
    val isRemote: Boolean,
    val isScheduled: Boolean,
    val isCompleted: Boolean,
    val isSuccess: Boolean,
    val isOfflineRetry: Boolean,
    val publishError: String?,
    val kind: Int,
    val isQuote: Boolean,
    val actualPublishedAt: Long?,
    val scheduledAt: Long?,
    val sourceUrl: String?,
    val previewTitle: String?,
    val previewImageUrl: String?,
    val previewDescription: String?,
    val previewSiteName: String?,
    val mediaJson: String?,
    val originalEventJson: String?,
    val articleTitle: String? = null,
    val articleSummary: String? = null,
    val articleIdentifier: String? = null,
    val publishedEventId: String? = null,
    val mediaItems: List<MediaUploadState> = emptyList(),
    val nostrEvent: JSONObject? = null,
    val targetLink: String? = null,
    val linkMetadata: LinkMetadata? = null,
    val hashtags: Set<String> = emptySet()
)

fun Draft.toUiModel(isRemote: Boolean): HistoryUiModel {
    val effectiveKind = getEffectivePostKind()
    
    // 1. Parse JSON exactly once
    val rootJson = originalEventJson?.let { 
        try { JSONObject(it) } catch (_: Exception) { null } 
    }

    // 2. Extract Hashtags
    val tagsSet = mutableSetOf<String>()
    rootJson?.optJSONArray("tags")?.let { tagsArr ->
        for (i in 0 until tagsArr.length()) {
            val t = tagsArr.optJSONArray(i)
            if (t != null && t.length() >= 2 && t.getString(0) == "t") {
                tagsSet.add(t.getString(1).lowercase())
            }
        }
    }
    if (tagsSet.isEmpty()) {
        val hashtagRegex = "#([a-zA-Z0-9_]+)".toRegex()
        hashtagRegex.findAll(content).forEach { match ->
            tagsSet.add(match.groupValues[1].lowercase())
        }
    }

    // 3. Resolve Media
    val parsedMedia = if (!mediaJson.isNullOrBlank()) {
        try {
            val array = JSONArray(mediaJson)
            val items = mutableListOf<MediaUploadState>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val uriStr = obj.optString("uri").takeIf { it.isNotBlank() } ?: continue
                items.add(MediaUploadState(
                    id = obj.optString("id", UUID.randomUUID().toString()), 
                    uri = android.net.Uri.parse(uriStr), 
                    mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() }
                ).apply { 
                    uploadedUrl = obj.optString("uploadedUrl").takeIf { it != "null" && it.isNotBlank() }
                    hash = obj.optString("hash").takeIf { it != "null" && it.isNotBlank() }
                    size = obj.optLong("size", 0L) 
                })
            }
            items
        } catch (_: Exception) { emptyList() }
    } else emptyList()

    // 4. Resolve Repost/Quote Targets
    var targetJson: JSONObject? = null
    var detectedLink: String? = null

    when {
        effectiveKind == PostKind.REPOST -> {
            targetJson = rootJson
            if (targetJson == null && content.trim().startsWith("{")) {
                targetJson = try { JSONObject(content) } catch (_: Exception) { null }
            }
            if (sourceUrl.isNotBlank()) {
                detectedLink = sourceUrl.removePrefix("nostr:")
            }
        }
        effectiveKind == PostKind.QUOTE -> {
            val regex = "(nostr:)?(nevent1|note1|naddr1)[a-z0-9]+".toRegex(RegexOption.IGNORE_CASE)
            val match = regex.find(content) ?: sourceUrl.let { regex.find(it) }
            detectedLink = match?.value?.removePrefix("nostr:")
            targetJson = rootJson
        }
        else -> {
            targetJson = rootJson
        }
    }

    // 5. Suppress Self-Previews
    if (publishedEventId != null) {
        if (targetJson?.optString("id") == publishedEventId) targetJson = null
        val entity = detectedLink?.let { com.ryans.nostrshare.NostrUtils.findNostrEntity(it) }
        if (entity?.id == publishedEventId) detectedLink = null
    }

    // 6. Pre-build Link Metadata
    val linkMeta = if (!previewTitle.isNullOrBlank() || !previewImageUrl.isNullOrBlank()) {
        LinkMetadata(
            url = sourceUrl,
            title = previewTitle,
            description = previewDescription,
            imageUrl = previewImageUrl,
            siteName = previewSiteName
        )
    } else null
    
    return HistoryUiModel(
        id = if (isRemote) (publishedEventId ?: id.toString()) else "local_$id",
        localId = id,
        contentSnippet = content,
        timestamp = if (isRemote) (actualPublishedAt ?: lastEdited) else (scheduledAt ?: lastEdited),
        pubkey = pubkey,
        isRemote = isRemote,
        isScheduled = isScheduled,
        isCompleted = isCompleted,
        isSuccess = isCompleted && publishError == null,
        isOfflineRetry = isOfflineRetry,
        publishError = publishError,
        kind = kind,
        isQuote = isQuote,
        actualPublishedAt = actualPublishedAt,
        scheduledAt = scheduledAt,
        sourceUrl = sourceUrl,
        previewTitle = previewTitle,
        previewImageUrl = previewImageUrl,
        previewDescription = previewDescription,
        previewSiteName = previewSiteName,
        mediaJson = mediaJson,
        originalEventJson = originalEventJson,
        articleTitle = articleTitle,
        articleSummary = articleSummary,
        articleIdentifier = articleIdentifier,
        publishedEventId = publishedEventId,
        mediaItems = parsedMedia,
        nostrEvent = targetJson,
        targetLink = detectedLink,
        linkMetadata = linkMeta,
        hashtags = tagsSet
    )
}
