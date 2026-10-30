package com.example.data.local

import com.example.data.local.dao.CommandHistoryDao
import com.example.data.local.dao.FailedCommandLogDao
import com.example.data.local.dao.PersonalVocabularyDao
import com.example.data.local.dao.RoutineDao
import com.example.data.local.dao.UserMemoryDao
import com.example.data.local.entity.CommandHistory
import com.example.data.local.entity.FailedCommandLog
import com.example.data.local.entity.PersonalVocabulary
import com.example.data.local.entity.Routine
import com.example.data.local.entity.RoutineToolCall
import com.example.data.local.entity.UserMemory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Repository implementation for OmniVoice local persistence layer.
 * Abstracts Room DAOs for CommandHistory, Routine, PersonalVocabulary,
 * UserMemory, and FailedCommandLog from ViewModels and background engines.
 */
class OmniVoiceRepository(
    private val commandHistoryDao: CommandHistoryDao,
    private val routineDao: RoutineDao,
    private val personalVocabularyDao: PersonalVocabularyDao? = null,
    private val userMemoryDao: UserMemoryDao? = null,
    private val failedCommandLogDao: FailedCommandLogDao? = null
) {

    // Command History Streams
    val allHistory: Flow<List<CommandHistory>> = commandHistoryDao.getAllHistory()
    val recentHistory: Flow<List<CommandHistory>> = commandHistoryDao.getRecentHistory(50)

    suspend fun recordCommand(entry: CommandHistory): Long {
        return commandHistoryDao.insert(entry)
    }

    suspend fun deleteHistory(id: Long) {
        commandHistoryDao.deleteById(id)
    }

    suspend fun clearAllHistory() {
        commandHistoryDao.clearAll()
    }

    suspend fun getHistoryCount(): Int {
        return commandHistoryDao.count()
    }

    // Routines Streams
    val allRoutines: Flow<List<Routine>> = routineDao.getAllRoutines()
    val enabledRoutines: Flow<List<Routine>> = routineDao.getEnabledRoutines()

    suspend fun getAllRoutinesSnapshot(): List<Routine> {
        return routineDao.getAllRoutinesSnapshot()
    }

    suspend fun getRoutineById(id: Long): Routine? {
        return routineDao.getRoutineById(id)
    }

    suspend fun findRoutineByTrigger(triggerPhrase: String): Routine? {
        return routineDao.getRoutineByTrigger(triggerPhrase)
    }

    suspend fun findMatchingEnabledRoutine(spokenText: String): Routine? {
        return routineDao.findMatchingEnabledRoutine(spokenText)
    }

    suspend fun findRoutineByKey(key: String): Routine? {
        return routineDao.getRoutineByKey(key)
    }

    suspend fun saveRoutine(routine: Routine): Long {
        return routineDao.insert(routine)
    }

    suspend fun createCustomRoutine(
        triggerPhrase: String,
        description: String,
        toolCalls: List<RoutineToolCall>,
        confirmationMessage: String,
        routineKey: String = "custom_${System.currentTimeMillis()}"
    ): Long {
        val routine = Routine(
            routineKey = routineKey,
            triggerPhrase = triggerPhrase.lowercase().trim(),
            description = description.ifBlank { "Custom multi-tool voice routine" },
            toolCalls = toolCalls,
            confirmationMessage = confirmationMessage.ifBlank {
                "Custom routine '$triggerPhrase' executed."
            }
        )
        return routineDao.insert(routine)
    }

    suspend fun saveAllRoutines(routines: List<Routine>) {
        routineDao.insertAll(routines)
    }

    suspend fun updateRoutine(routine: Routine) {
        routineDao.update(routine)
    }

    suspend fun setRoutineEnabled(id: Long, enabled: Boolean) {
        routineDao.setEnabled(id, enabled)
    }

    suspend fun setRoutineEnabledByKey(routineKey: String, enabled: Boolean) {
        routineDao.setEnabledByKey(routineKey, enabled)
    }

    suspend fun recordRoutineExecution(
        routineKey: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        routineDao.recordExecution(routineKey, timestamp)
    }

    suspend fun deleteRoutine(id: Long) {
        routineDao.deleteById(id)
    }

    suspend fun deleteRoutineByKey(routineKey: String) {
        routineDao.deleteByKey(routineKey)
    }

    suspend fun getRoutineCount(): Int {
        return routineDao.count()
    }

    // Personal Vocabulary ("Teach Me")
    val allPersonalVocabulary: Flow<List<PersonalVocabulary>> =
        personalVocabularyDao?.getAllVocabulary() ?: emptyFlow()

    suspend fun getPersonalVocabularySnapshot(): List<PersonalVocabulary> {
        return personalVocabularyDao?.getAllVocabularySnapshot() ?: emptyList()
    }

    suspend fun teachPersonalWord(
        spokenPhrase: String,
        canonicalMeaning: String,
        urduScript: String = "",
        category: String = "custom_command",
        mappedAction: String = "",
        mappedTarget: String = ""
    ): Long {
        val dao = personalVocabularyDao ?: return -1L
        return dao.insert(
            PersonalVocabulary(
                spokenPhrase = spokenPhrase.trim(),
                canonicalMeaning = canonicalMeaning.trim(),
                urduScript = urduScript.trim(),
                category = category,
                mappedAction = mappedAction,
                mappedTarget = mappedTarget
            )
        )
    }

    suspend fun deletePersonalVocabulary(id: Long) {
        personalVocabularyDao?.deleteById(id)
    }

    // Long-Term User Memory
    val allUserMemories: Flow<List<UserMemory>> =
        userMemoryDao?.getAllMemories() ?: emptyFlow()

    suspend fun getUserMemoriesSnapshot(): List<UserMemory> {
        return userMemoryDao?.getAllMemoriesSnapshot() ?: emptyList()
    }

    suspend fun saveUserMemory(key: String, value: String, type: String = "preference"): Long {
        val dao = userMemoryDao ?: return -1L
        val existing = dao.getMemoryByKey(key)
        return dao.upsert(
            UserMemory(
                id = existing?.id ?: 0L,
                memoryKey = key.trim(),
                memoryValue = value.trim(),
                memoryType = type,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteUserMemory(id: Long) {
        userMemoryDao?.deleteById(id)
    }

    // Self-Learning Failed Command Log
    val allFailedLogs: Flow<List<FailedCommandLog>> =
        failedCommandLogDao?.getAllFailedLogs() ?: emptyFlow()

    val unresolvedFailedLogs: Flow<List<FailedCommandLog>> =
        failedCommandLogDao?.getUnresolvedFailedLogs() ?: emptyFlow()

    suspend fun logFailedCommand(
        rawUtterance: String,
        normalizedUtterance: String,
        detectedLanguage: String,
        failureReason: String,
        suggestedCorrection: String = ""
    ): Long {
        val dao = failedCommandLogDao ?: return -1L
        return dao.insert(
            FailedCommandLog(
                rawUtterance = rawUtterance,
                normalizedUtterance = normalizedUtterance,
                detectedLanguage = detectedLanguage,
                failureReason = failureReason,
                suggestedCorrection = suggestedCorrection
            )
        )
    }

    suspend fun promoteFailedCommandToVocabulary(
        logId: Long,
        spokenPhrase: String,
        canonicalMeaning: String,
        category: String = "custom_command"
    ) {
        teachPersonalWord(
            spokenPhrase = spokenPhrase,
            canonicalMeaning = canonicalMeaning,
            category = category
        )
        failedCommandLogDao?.markResolved(logId, canonicalMeaning)
    }

    suspend fun deleteFailedLog(id: Long) {
        failedCommandLogDao?.deleteById(id)
    }
}
