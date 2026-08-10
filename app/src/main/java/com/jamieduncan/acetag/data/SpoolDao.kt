package com.jamieduncan.acetag.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SpoolDao {
    @Insert
    suspend fun insert(spool: SpoolEntity): Long

    @Update
    suspend fun update(spool: SpoolEntity)

    @Query("DELETE FROM spools WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM spools WHERE usedUpAt IS NULL ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<SpoolEntity>>

    @Query("SELECT * FROM spools WHERE usedUpAt IS NOT NULL ORDER BY usedUpAt DESC")
    fun observeUsedUp(): Flow<List<SpoolEntity>>

    @Query("SELECT * FROM spools ORDER BY createdAt DESC")
    suspend fun getAll(): List<SpoolEntity>

    @Query("SELECT * FROM spools WHERE id = :id")
    suspend fun getById(id: Long): SpoolEntity?

    @Query("SELECT * FROM spools WHERE tagUidA = :uid OR tagUidB = :uid LIMIT 1")
    suspend fun findByTagUid(uid: String): SpoolEntity?

    @Query(
        """
        SELECT * FROM spools
        WHERE usedUpAt IS NULL AND tagUidB IS NULL
          AND type = :type AND manufacturer = :manufacturer AND color = :color
          AND nozzleMin = :nozzleMin AND nozzleMax = :nozzleMax
          AND bedMin = :bedMin AND bedMax = :bedMax
          AND diameterMm = :diameterMm AND weightG = :weightG
        """,
    )
    suspend fun findMatchingWithOpenSlot(
        type: String,
        manufacturer: String,
        color: String,
        nozzleMin: Int,
        nozzleMax: Int,
        bedMin: Int,
        bedMax: Int,
        diameterMm: Double,
        weightG: Int,
    ): List<SpoolEntity>
}
