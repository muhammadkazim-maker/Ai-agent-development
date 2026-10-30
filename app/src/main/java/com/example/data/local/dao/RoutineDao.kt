package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.Routine
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for custom [Routine] entities that group multiple tool calls
 * into a single spoken voice command trigger.
 */
@Dao
interface RoutineDao {

    @Query("SELECT * FROM routines ORDER BY created_at DESC")
    fun getAllRoutines(): Flow<List<Routine>>

    @Query("SELECT * FROM routines WHERE is_enabled = 1 ORDER BY created_at DESC")
    fun getEnabledRoutines(): Flow<List<Routine>>

    @Query("SELECT * FROM routines ORDER BY created_at ASC")
    suspend fun getAllRoutinesSnapshot(): List<Routine>

    @Query("SELECT * FROM routines WHERE id = :id LIMIT 1")
    suspend fun getRoutineById(id: Long): Routine?

    @Query("SELECT * FROM routines WHERE LOWER(TRIM(trigger_phrase)) = LOWER(TRIM(:triggerPhrase)) LIMIT 1")
    suspend fun getRoutineByTrigger(triggerPhrase: String): Routine?

    /**
     * Finds the best matching enabled routine whose trigger phrase matches or is contained
     * within the spoken input, prioritizing longer/more specific trigger phrases first.
     */
    @Query(
        """
        SELECT * FROM routines 
        WHERE is_enabled = 1 
          AND (
              LOWER(TRIM(trigger_phrase)) = LOWER(TRIM(:spokenText)) 
              OR LOWER(:spokenText) LIKE '%' || LOWER(TRIM(trigger_phrase)) || '%'
          )
        ORDER BY LENGTH(trigger_phrase) DESC 
        LIMIT 1
        """
    )
    suspend fun findMatchingEnabledRoutine(spokenText: String): Routine?

    @Query("SELECT * FROM routines WHERE routine_key = :key LIMIT 1")
    suspend fun getRoutineByKey(key: String): Routine?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(routine: Routine): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(routines: List<Routine>)

    @Update
    suspend fun update(routine: Routine)

    @Delete
    suspend fun delete(routine: Routine)

    @Query("UPDATE routines SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE routines SET is_enabled = :enabled WHERE routine_key = :routineKey")
    suspend fun setEnabledByKey(routineKey: String, enabled: Boolean)

    @Query(
        """
        UPDATE routines 
        SET execution_count = execution_count + 1, 
            last_executed_at = :timestamp 
        WHERE routine_key = :routineKey
        """
    )
    suspend fun recordExecution(routineKey: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM routines WHERE routine_key = :routineKey")
    suspend fun deleteByKey(routineKey: String)

    @Query("DELETE FROM routines")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM routines")
    suspend fun count(): Int
}
