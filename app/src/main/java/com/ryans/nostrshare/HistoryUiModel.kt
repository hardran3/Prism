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
    val publishedEventId: String? = null
)

fun Draft.toUiModel(isRemote: Boolean): HistoryUiModel {
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
        publishedEventId = publishedEventId
    )
}
