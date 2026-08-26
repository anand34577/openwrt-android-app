package com.openwrtmgr.app.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RouterProfileDao {
    @Query("SELECT * FROM router_profiles ORDER BY lastConnectedEpochMillis DESC")
    fun observeAll(): Flow<List<RouterProfileEntity>>

    @Query("SELECT * FROM router_profiles WHERE id = :id")
    suspend fun getById(id: Long): RouterProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: RouterProfileEntity): Long

    @Update
    suspend fun update(profile: RouterProfileEntity)

    @Delete
    suspend fun delete(profile: RouterProfileEntity)
}
