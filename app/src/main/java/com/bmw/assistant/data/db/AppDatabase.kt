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
    version = 5,
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

        /**
         * v5 stamps each backup with the VIN it was read from, so a snapshot can never be
         * restored into a different car. Migrated rather than wiped: a backup is the only copy
         * of a module's original coding bytes, and losing one loses the way back.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `coding_backups` ADD COLUMN `vin` TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bmw_assistant.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_4_5)
                    // Older sideloads (and the product-improvements experiment) used schemas 2–4
                    // with no migration path. Definitions are always re-seeded from JSON, so a
                    // wipe is safer than a crash on launch.
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { instance = it }
            }
    }
}
