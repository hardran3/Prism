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
    val lastEdited: Long = System.currentTimeMillis(),
    val scheduledAt: Long? = null,
    val signedJson: String? = null,
    val isScheduled: Boolean = false,
    val isAutoSave: Boolean = false,
    val isCompleted: Boolean = false,
    val publishError: String? = null
)
