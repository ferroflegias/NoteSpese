package com.expensepereport.app.data.room

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SpesaDao {
    @Query("SELECT * FROM spese ORDER BY data DESC, id DESC")
    fun getAllSpeseFlow(): Flow<List<SpesaEntity>>

    @Query("SELECT * FROM spese ORDER BY data DESC, id DESC")
    suspend fun getAllSpese(): List<SpesaEntity>

    @Query("SELECT * FROM spese WHERE data >= :startDate AND data < :endDate ORDER BY data ASC, id ASC")
    suspend fun getSpeseForRange(startDate: String, endDate: String): List<SpesaEntity>

    @Query("SELECT * FROM spese WHERE id = :id LIMIT 1")
    suspend fun getSpesaById(id: Long): SpesaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(spesa: SpesaEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(spese: List<SpesaEntity>)

    @Update
    suspend fun update(spesa: SpesaEntity)

    @Query("DELETE FROM spese WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM spese")
    suspend fun deleteAll()
}
