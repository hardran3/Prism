package com.ryans.nostrshare.data

import androidx.room.*
import com.ryans.nostrshare.nip55.PostKind

@Entity(
    tableName = "drafts",
    indices = [Index(value = ["publishedEventId"], unique = true)]
)
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
    val isRemoteCache: Boolean = false,
    val articleTitle: String? = null,
    val articleSummary: String? = null,
    val articleIdentifier: String? = null
) {
    @Ignore var isRemote: Boolean = false
    val isQuote: Boolean get() {
        if (kind != 1) return false
        
        // Explicitly set metadata for a quote
        if (highlightEventId != null) return true
        
        // Check if originalEventJson is a quote target (different ID) or just a local cache (same ID)
        if (!originalEventJson.isNullOrBlank()) {
            try {
                val json = org.json.JSONObject(originalEventJson)
                val jsonId = json.optString("id")
                if (jsonId.isNotBlank() && jsonId != publishedEventId) {
                    return true
                }
            } catch (_: Exception) {}
        }
        
        // Fallback to scanning content/source for links
        return com.ryans.nostrshare.NostrUtils.hasQuoteLink(content) || 
               com.ryans.nostrshare.NostrUtils.hasQuoteLink(sourceUrl)
    }

    fun getEffectivePostKind(): PostKind {
        return when {
            kind == 6 || kind == 16 -> PostKind.REPOST
            kind == 9802 -> PostKind.HIGHLIGHT
            kind == 0 -> PostKind.MEDIA
            kind == 30023 -> PostKind.ARTICLE
            kind == 1063 -> PostKind.FILE_METADATA
            isQuote -> PostKind.QUOTE
            else -> PostKind.NOTE
        }
    }
}
