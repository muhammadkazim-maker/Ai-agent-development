package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.UserMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface UserMemoryDao {

    @Query("SELECT * FROM user_memory ORDER BY updated_at DESC")
    fun getAllMemories(): Flow<List<UserMemory>>

    @Query("SELECT * FROM user_memory ORDER BY updated_at DESC")
    suspend fun getAllMemoriesSnapshot(): List<UserMemory>

    @Query("SELECT * FROM user_memory WHERE memory_type = :type ORDER BY updated_at DESC")
    suspend fun getMemoriesByType(type: String): List<UserMemory>

    @Query("SELECT * FROM user_memory WHERE LOWER(TRIM(memory_key)) = LOWER(TRIM(:key)) LIMIT 1")
    suspend fun getMemoryByKey(key: String): UserMemory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: UserMemory): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<UserMemory>)

    @Query("DELETE FROM user_memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM user_memory WHERE memory_key = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT COUNT(*) FROM user_memory")
    suspend fun count(): Int
}
