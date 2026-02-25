package com.ryans.nostrshare.data

import android.content.Context
import androidx.room.*

@Database(entities = [Draft::class], version = 13, exportSchema = false)
abstract class DraftDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao

    companion object {
        @Volatile
        private var INSTANCE: DraftDatabase? = null

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE drafts ADD COLUMN savedContentBuffer TEXT")
                database.execSQL("ALTER TABLE drafts ADD COLUMN previewTitle TEXT")
                database.execSQL("ALTER TABLE drafts ADD COLUMN previewDescription TEXT")
                database.execSQL("ALTER TABLE drafts ADD COLUMN previewImageUrl TEXT")
                database.execSQL("ALTER TABLE drafts ADD COLUMN previewSiteName TEXT")
                database.execSQL("ALTER TABLE drafts ADD COLUMN highlightAuthorName TEXT")
                database.execSQL("ALTER TABLE drafts ADD COLUMN highlightAuthorAvatarUrl TEXT")
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE drafts ADD COLUMN isRemoteCache INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Remove duplicates before creating unique index to prevent migration failure
                database.execSQL("""
                    DELETE FROM drafts 
                    WHERE publishedEventId IS NOT NULL 
                    AND id NOT IN (
                        SELECT MAX(id) 
                        FROM drafts 
                        WHERE publishedEventId IS NOT NULL 
                        GROUP BY publishedEventId
                    )
                """.trimIndent())
                
                // 2. Create the unique index
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_drafts_publishedEventId ON drafts (publishedEventId)")
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE drafts ADD COLUMN articleTitle TEXT")
                database.execSQL("ALTER TABLE drafts ADD COLUMN articleSummary TEXT")
                database.execSQL("ALTER TABLE drafts ADD COLUMN articleIdentifier TEXT")
            }
        }

        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_drafts_pubkey ON drafts (pubkey)")
            }
        }

        fun getDatabase(context: Context): DraftDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DraftDatabase::class.java,
                    "prism_database.db"
                )
                .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
