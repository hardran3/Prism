package com.ryans.nostrshare.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE isScheduled = 0 AND isAutoSave = 0 AND isRemoteCache = 0 AND pubkey = :pubkey ORDER BY lastEdited DESC")
    fun getAllDrafts(pubkey: String?): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE isScheduled = 1 AND isCompleted = 0 AND isRemoteCache = 0 AND pubkey = :pubkey ORDER BY isOfflineRetry DESC, scheduledAt ASC")
    fun getAllScheduled(pubkey: String?): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE isScheduled = 1 AND isCompleted = 1 AND pubkey = :pubkey ORDER BY scheduledAt DESC")
    fun getScheduledHistory(pubkey: String?): Flow<List<Draft>>
    
    @Query("SELECT * FROM drafts WHERE isRemoteCache = 1 AND isScheduled = 0 AND pubkey = :pubkey ORDER BY actualPublishedAt DESC")
    fun getRemoteHistory(pubkey: String): Flow<List<Draft>>

    @Query("""
        SELECT * FROM drafts 
        WHERE pubkey = :pubkey 
        AND (
            (isScheduled = 1 AND isCompleted = 1) 
            OR (:includeRemote = 1 AND isRemoteCache = 1 AND isScheduled = 0)
        )
        ORDER BY CASE WHEN isRemoteCache = 1 THEN IFNULL(actualPublishedAt, lastEdited) ELSE IFNULL(scheduledAt, lastEdited) END DESC
    """)
    fun getUnifiedHistory(pubkey: String, includeRemote: Int): Flow<List<Draft>>

    @Query("SELECT publishedEventId FROM drafts WHERE isRemoteCache = 1 AND pubkey = :pubkey AND publishedEventId IS NOT NULL")
    suspend fun getAllRemoteIds(pubkey: String): List<String>

    @Query("SELECT * FROM drafts WHERE isRemoteCache = 1 AND pubkey = :pubkey ORDER BY actualPublishedAt DESC")
    suspend fun getRemoteHistoryList(pubkey: String): List<Draft>

    @Query("SELECT MAX(actualPublishedAt) FROM drafts WHERE isRemoteCache = 1 AND pubkey = :pubkey")
    suspend fun getMaxRemoteTimestamp(pubkey: String): Long?

    @Query("SELECT MIN(actualPublishedAt) FROM drafts WHERE isRemoteCache = 1 AND pubkey = :pubkey")
    suspend fun getMinRemoteTimestamp(pubkey: String): Long?

    @Query("DELETE FROM drafts WHERE isRemoteCache = 1 AND pubkey = :pubkey")
    suspend fun deleteRemoteHistory(pubkey: String)

    @Query("SELECT * FROM drafts WHERE isScheduled = 1 AND isCompleted = 0")
    suspend fun getScheduledDrafts(): List<Draft>

    @Query("SELECT COUNT(*) FROM drafts WHERE isScheduled = 1 AND isCompleted = 0")
    suspend fun getScheduledCount(): Int

    @Query("DELETE FROM drafts WHERE isScheduled = 1 AND isCompleted = 1 AND pubkey = :pubkey")
    suspend fun deleteCompletedScheduled(pubkey: String)

    @Query("SELECT * FROM drafts WHERE isAutoSave = 1 AND pubkey = :pubkey LIMIT 1")
    suspend fun getAutoSaveDraft(pubkey: String?): Draft?

    @Query("DELETE FROM drafts WHERE isAutoSave = 1 AND pubkey = :pubkey")
    suspend fun deleteAutoSaveDraft(pubkey: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: Draft): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDrafts(drafts: List<Draft>)

    @Transaction
    suspend fun syncRemoteNotes(remoteNotes: List<Draft>, syncPubkey: String) {
        for (remote in remoteNotes) {
            val eventId = remote.publishedEventId ?: continue
            val existing = getDraftByEventId(eventId, syncPubkey)
            if (existing != null) {
                // Update remote fields while preserving local flags
                updateRemoteMetadata(
                    id = existing.id,
                    pubkey = syncPubkey,
                    actualPublishedAt = remote.actualPublishedAt ?: existing.actualPublishedAt,
                    previewTitle = remote.previewTitle ?: existing.previewTitle,
                    previewDescription = remote.previewDescription ?: existing.previewDescription,
                    previewImageUrl = remote.previewImageUrl ?: existing.previewImageUrl,
                    previewSiteName = remote.previewSiteName ?: existing.previewSiteName,
                    mediaJson = if (existing.mediaJson == "[]" || existing.mediaJson.isBlank()) remote.mediaJson else existing.mediaJson
                )
            } else {
                insertDraft(remote)
            }
        }
    }

    @Query("DELETE FROM drafts WHERE pubkey IS NULL")
    suspend fun deleteOrphanNotes()

    @Query("SELECT * FROM drafts WHERE publishedEventId = :eventId AND pubkey = :pubkey LIMIT 1")
    suspend fun getDraftByEventId(eventId: String, pubkey: String): Draft?

    @Query("""
        UPDATE drafts SET 
            actualPublishedAt = :actualPublishedAt,
            previewTitle = :previewTitle,
            previewDescription = :previewDescription,
            previewImageUrl = :previewImageUrl,
            previewSiteName = :previewSiteName,
            mediaJson = :mediaJson,
            isRemoteCache = 1
        WHERE id = :id AND pubkey = :pubkey
    """)
    suspend fun updateRemoteMetadata(
        id: Int, 
        pubkey: String,
        actualPublishedAt: Long?, 
        previewTitle: String?, 
        previewDescription: String?, 
        previewImageUrl: String?, 
        previewSiteName: String?,
        mediaJson: String
    )

    @Delete
    suspend fun deleteDraft(draft: Draft)

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun getDraftById(id: Int): Draft?

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT pubkey, COUNT(*) as count FROM drafts WHERE isScheduled = 1 AND isCompleted = 0 GROUP BY pubkey")
    suspend fun getScheduledCountByPubkey(): List<PubkeyCount>

    @Query("SELECT DISTINCT pubkey FROM drafts WHERE pubkey IS NOT NULL")
    suspend fun getAllPubkeys(): List<String>
}

data class PubkeyCount(
    val pubkey: String?,
    val count: Int
)
