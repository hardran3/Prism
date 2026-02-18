package com.ryans.nostrshare.data

import android.content.Context
import androidx.room.*

@Database(entities = [Draft::class], version = 9, exportSchema = false)
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

        fun getDatabase(context: Context): DraftDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DraftDatabase::class.java,
                    "prism_database"
                )
                .addMigrations(MIGRATION_8_9)
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
