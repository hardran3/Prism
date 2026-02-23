package com.ryans.nostrshare.ui

import com.ryans.nostrshare.MediaUploadState
import com.ryans.nostrshare.utils.LinkMetadata
import org.json.JSONObject

sealed class ContentSegment {
    data class Text(val text: String) : ContentSegment()
    data class MediaGroup(val items: List<MediaUploadState>) : ContentSegment()
    data class LinkPreview(val meta: LinkMetadata, val originalText: String) : ContentSegment()
    data class NostrPreview(val event: JSONObject) : ContentSegment()
    data class NostrLink(val bech32: String) : ContentSegment()
}
