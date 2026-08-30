package com.bmw.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ModuleEntity::class,
        CodingEntity::class,
        CodingValueEntity::class,
        CodingBackupEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun codingDao(): CodingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 adds the coding-block backup table; existing definitions/values are kept. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `coding_backups` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`moduleId` TEXT NOT NULL, " +
                        "`moduleName` TEXT NOT NULL, " +
                        "`diagAddress` INTEGER NOT NULL, " +
                        "`dataIdentifier` INTEGER NOT NULL, " +
                        "`blockHex` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`connectionLabel` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /** v3 tracks value provenance and tags backups with VIN / I-level. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `coding_values` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'DEFAULT'"
                )
                db.execSQL("ALTER TABLE `coding_backups` ADD COLUMN `vin` TEXT")
                db.execSQL("ALTER TABLE `coding_backups` ADD COLUMN `iLevel` TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bmw_assistant.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
