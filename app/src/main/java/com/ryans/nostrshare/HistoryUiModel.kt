package com.ryans.nostrshare

import com.ryans.nostrshare.ui.ContentSegment

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
    val segments: List<ContentSegment> = emptyList()
)
