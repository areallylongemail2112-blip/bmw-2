package com.bmw.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CodingDao {

    @Query("SELECT COUNT(*) FROM modules")
    suspend fun moduleCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModules(modules: List<ModuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCodings(codings: List<CodingEntity>)

    @Query("DELETE FROM modules")
    suspend fun deleteAllModules()

    @Query("DELETE FROM codings")
    suspend fun deleteAllCodings()

    @Query("SELECT * FROM modules ORDER BY name")
    suspend fun getModules(): List<ModuleEntity>

    @Query("SELECT * FROM modules WHERE id = :id")
    suspend fun getModule(id: String): ModuleEntity?

    @Query("SELECT * FROM codings WHERE moduleId = :moduleId ORDER BY name")
    suspend fun getCodingsForModule(moduleId: String): List<CodingEntity>

    @Query("SELECT * FROM codings WHERE id = :id")
    suspend fun getCoding(id: String): CodingEntity?

    @Query("SELECT COUNT(*) FROM codings WHERE moduleId = :moduleId")
    suspend fun codingCountForModule(moduleId: String): Int

    // --- values ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertValue(value: CodingValueEntity)

    @Query("SELECT value FROM coding_values WHERE codingId = :codingId")
    suspend fun getValue(codingId: String): String?

    @Query("SELECT * FROM coding_values")
    suspend fun getAllValues(): List<CodingValueEntity>

    @Query("DELETE FROM coding_values")
    suspend fun clearValues()

    // --- backups ---

    @Insert
    suspend fun insertBackup(backup: CodingBackupEntity): Long

    @Query("SELECT * FROM coding_backups ORDER BY createdAt DESC")
    suspend fun getBackups(): List<CodingBackupEntity>

    @Query("SELECT * FROM coding_backups WHERE id = :id")
    suspend fun getBackup(id: Long): CodingBackupEntity?

    /** Most recent snapshot of one block on one source, used to skip duplicate backups. */
    @Query(
        "SELECT * FROM coding_backups WHERE moduleId = :moduleId AND dataIdentifier = :did " +
            "AND source = :source ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun latestBackupForBlock(moduleId: String, did: Int, source: String): CodingBackupEntity?

    @Query("DELETE FROM coding_backups WHERE id = :id")
    suspend fun deleteBackup(id: Long)
}
