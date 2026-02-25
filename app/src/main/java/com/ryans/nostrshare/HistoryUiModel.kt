package com.ryans.nostrshare

import com.ryans.nostrshare.data.Draft
import com.ryans.nostrshare.nip55.PostKind
import com.ryans.nostrshare.ui.ContentSegment
import org.json.JSONObject

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
    val nostrEvent: JSONObject? = null,
    val targetLink: String? = null,
    val segments: List<ContentSegment> = emptyList()
)

fun Draft.toUiModel(isRemote: Boolean): HistoryUiModel {
    val effectiveKind = getEffectivePostKind()
    
    // Proactive Target Detection
    var targetJson: JSONObject? = null
    var detectedLink: String? = null

    when (effectiveKind) {
        PostKind.REPOST -> {
            // Priority 1: originalEventJson
            targetJson = originalEventJson?.let { try { JSONObject(it) } catch (_: Exception) { null } }
            
            // Priority 2: Parse content if it's JSON (Remote reposts often store target here)
            if (targetJson == null && content.trim().startsWith("{")) {
                targetJson = try { JSONObject(content) } catch (_: Exception) { null }
            }
            
            // Priority 3: sourceUrl pointer
            if (sourceUrl.isNotBlank()) {
                detectedLink = sourceUrl.removePrefix("nostr:")
            }
        }
        PostKind.QUOTE -> {
            // Scan for the first nostr: link to use as the specific quote target
            val regex = "(nostr:)?(nevent1|note1|naddr1)[a-z0-9]+".toRegex(RegexOption.IGNORE_CASE)
            val match = regex.find(content) ?: sourceUrl.let { regex.find(it) }
            detectedLink = match?.value?.removePrefix("nostr:")
            
            // Still check originalEventJson for a cache of the quoted note
            targetJson = originalEventJson?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        }
        else -> {
            // Standard fallback for other types
            targetJson = originalEventJson?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        }
    }

    // CRITICAL: Prevent self-previewing
    val selfId = publishedEventId
    if (selfId != null) {
        if (targetJson?.optString("id") == selfId) {
            targetJson = null
        }
        val entity = detectedLink?.let { com.ryans.nostrshare.NostrUtils.findNostrEntity(it) }
        if (entity?.id == selfId) {
            detectedLink = null
        }
    }
    
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
        nostrEvent = targetJson,
        targetLink = detectedLink
    )
}
