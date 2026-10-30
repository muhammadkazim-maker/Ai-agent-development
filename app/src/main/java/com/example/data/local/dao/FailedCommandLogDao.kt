package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.FailedCommandLog
import kotlinx.coroutines.flow.Flow

@Dao
interface FailedCommandLogDao {

    @Query("SELECT * FROM failed_command_log ORDER BY timestamp DESC")
    fun getAllFailedLogs(): Flow<List<FailedCommandLog>>

    @Query("SELECT * FROM failed_command_log WHERE resolved = 0 ORDER BY timestamp DESC")
    fun getUnresolvedFailedLogs(): Flow<List<FailedCommandLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FailedCommandLog): Long

    @Query("UPDATE failed_command_log SET resolved = 1, suggested_correction = :correction WHERE id = :id")
    suspend fun markResolved(id: Long, correction: String)

    @Query("DELETE FROM failed_command_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM failed_command_log")
    suspend fun clearAll()
}
