package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.PersonalVocabulary
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalVocabularyDao {

    @Query("SELECT * FROM personal_vocabulary ORDER BY created_at DESC")
    fun getAllVocabulary(): Flow<List<PersonalVocabulary>>

    @Query("SELECT * FROM personal_vocabulary ORDER BY usage_count DESC, created_at DESC")
    suspend fun getAllVocabularySnapshot(): List<PersonalVocabulary>

    @Query("SELECT * FROM personal_vocabulary WHERE LOWER(TRIM(spoken_phrase)) = LOWER(TRIM(:phrase)) LIMIT 1")
    suspend fun findByPhrase(phrase: String): PersonalVocabulary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PersonalVocabulary): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<PersonalVocabulary>)

    @Query("UPDATE personal_vocabulary SET usage_count = usage_count + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Long)

    @Query("DELETE FROM personal_vocabulary WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM personal_vocabulary")
    suspend fun count(): Int
}
