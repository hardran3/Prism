package com.ryans.nostrshare.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE isScheduled = 0 AND isAutoSave = 0 ORDER BY lastEdited DESC")
    fun getAllDrafts(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE isScheduled = 1 AND isCompleted = 0 ORDER BY scheduledAt ASC")
    fun getAllScheduled(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE isScheduled = 1 AND isCompleted = 1 ORDER BY scheduledAt DESC")
    fun getScheduledHistory(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE isScheduled = 1 AND isCompleted = 0")
    suspend fun getScheduledDrafts(): List<Draft>

    @Query("SELECT COUNT(*) FROM drafts WHERE isScheduled = 1 AND isCompleted = 0")
    fun getScheduledCount(): Int

    @Query("DELETE FROM drafts WHERE isScheduled = 1 AND isCompleted = 1")
    suspend fun deleteCompletedScheduled()

    @Query("SELECT * FROM drafts WHERE isAutoSave = 1 LIMIT 1")
    suspend fun getAutoSaveDraft(): Draft?

    @Query("DELETE FROM drafts WHERE isAutoSave = 1")
    suspend fun deleteAutoSaveDraft()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: Draft): Long

    @Delete
    suspend fun deleteDraft(draft: Draft)

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun getDraftById(id: Int): Draft?

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteById(id: Int)
}
