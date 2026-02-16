package com.ryans.nostrshare.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE isScheduled = 0 AND isAutoSave = 0 AND (pubkey = :pubkey OR pubkey IS NULL) ORDER BY lastEdited DESC")
    fun getAllDrafts(pubkey: String?): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE isScheduled = 1 AND isCompleted = 0 AND (pubkey = :pubkey OR pubkey IS NULL) ORDER BY isOfflineRetry DESC, scheduledAt ASC")
    fun getAllScheduled(pubkey: String?): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE isScheduled = 1 AND isCompleted = 1 AND (pubkey = :pubkey OR pubkey IS NULL) ORDER BY scheduledAt DESC")
    fun getScheduledHistory(pubkey: String?): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE isScheduled = 1 AND isCompleted = 0")
    suspend fun getScheduledDrafts(): List<Draft>

    @Query("SELECT COUNT(*) FROM drafts WHERE isScheduled = 1 AND isCompleted = 0")
    suspend fun getScheduledCount(): Int

    @Query("DELETE FROM drafts WHERE isScheduled = 1 AND isCompleted = 1")
    suspend fun deleteCompletedScheduled()

    @Query("SELECT * FROM drafts WHERE isAutoSave = 1 AND (pubkey = :pubkey OR pubkey IS NULL) LIMIT 1")
    suspend fun getAutoSaveDraft(pubkey: String?): Draft?

    @Query("DELETE FROM drafts WHERE isAutoSave = 1 AND (pubkey = :pubkey OR pubkey IS NULL)")
    suspend fun deleteAutoSaveDraft(pubkey: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: Draft): Long

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
