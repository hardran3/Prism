package com.ryans.nostrshare.data

import androidx.room.*

@Entity(tableName = "drafts")
data class Draft(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val sourceUrl: String,
    val kind: Int,
    val mediaJson: String, // Serialized list of MediaUploadState
    val mediaTitle: String,
    val highlightEventId: String? = null,
    val highlightAuthor: String? = null,
    val highlightKind: Int? = null,
    val highlightIdentifier: String? = null,
    val highlightRelaysJson: String? = null, // Serialized List<String>
    val originalEventJson: String? = null,
    val lastEdited: Long = System.currentTimeMillis(),
    val pubkey: String? = null,
    val scheduledAt: Long? = null,
    val signedJson: String? = null,
    val isScheduled: Boolean = false,
    val isAutoSave: Boolean = false,
    val isCompleted: Boolean = false,
    val publishError: String? = null,
    val publishedEventId: String? = null,
    val actualPublishedAt: Long? = null,
    val isOfflineRetry: Boolean = false,
    val savedContentBuffer: String? = null,
    val previewTitle: String? = null,
    val previewDescription: String? = null,
    val previewImageUrl: String? = null,
    val previewSiteName: String? = null,
    val highlightAuthorName: String? = null,
    val highlightAuthorAvatarUrl: String? = null,
    val isRemoteCache: Boolean = false
) {
    @Ignore var isRemote: Boolean = false
    val isQuote: Boolean get() = kind == 1 && 
        (com.ryans.nostrshare.NostrUtils.hasQuoteLink(content) || com.ryans.nostrshare.NostrUtils.hasQuoteLink(sourceUrl))
}
