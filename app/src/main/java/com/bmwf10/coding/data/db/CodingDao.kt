package com.bmwf10.coding.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CodingDao {

    @Query("SELECT COUNT(*) FROM modules")
    suspend fun moduleCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModules(modules: List<ModuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCodings(codings: List<CodingEntity>)

    /** Insert modules + codings atomically so a mid-seed crash cannot leave a half-empty DB. */
    @Transaction
    suspend fun seedDefinitions(modules: List<ModuleEntity>, codings: List<CodingEntity>) {
        insertModules(modules)
        insertCodings(codings)
    }

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
}
