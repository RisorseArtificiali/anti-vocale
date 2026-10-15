package com.antivocale.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LogEntity::class], version = 15, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun logDao(): LogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN sourcePackageName TEXT DEFAULT NULL")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN isPartial INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE logs ADD COLUMN failedChunkCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN modelName TEXT DEFAULT NULL")
            }
        }

        /** TASK-276 AC3: the raw ASR text kept alongside the polished result. */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN rawTranscript TEXT DEFAULT NULL")
            }
        }

        /** TASK-121.4: the AI summary kept alongside the delivered transcript. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN summary TEXT DEFAULT NULL")
            }
        }

        /** TASK-494: why an attended summary attempt produced none. */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN summarySkipReason TEXT DEFAULT NULL")
            }
        }

        /** GH #92: the JSON-serialized timed cues of a transcription. */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN segments TEXT DEFAULT NULL")
            }
        }

        /** TASK-570: structured failure diagnostics on the ERROR row. */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN failureContext TEXT DEFAULT NULL")
            }
        }

        /** TASK-512: processing context of successful runs. */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN processingContext TEXT DEFAULT NULL")
            }
        }

        /** TASK-546: the language facts of a run (reported + pinned). */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN detectedLanguage TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE logs ADD COLUMN languagePin TEXT DEFAULT NULL")
            }
        }

        /** GH #43: the superseded fast first-pass transcript of two-pass runs. */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN firstPassTranscript TEXT DEFAULT NULL")
            }
        }

        /** TASK-736: the voice note's sender label. */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN senderName TEXT DEFAULT NULL")
            }
        }

        /** TASK-820 (GH #128): the auto-save outcome, row-scoped. */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN savedFolderName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE logs ADD COLUMN savedFileName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE logs ADD COLUMN saveFailureReason TEXT DEFAULT NULL")
            }
        }

        /** TASK-842 (GH #157): the duplicate guard's content-hash column + lookup index. */
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN contentHash TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_logs_contentHash ON logs(contentHash)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "anti_vocale_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
