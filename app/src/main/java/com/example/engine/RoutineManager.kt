package com.example.engine

import android.content.Context
import android.util.Log
import com.example.data.local.OmniVoiceDatabase
import com.example.data.local.OmniVoiceRepository
import com.example.data.local.entity.Routine
import com.example.data.local.entity.RoutineToolCall
import com.example.data.model.VoiceAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VoiceRoutine(
    val id: String,
    val dbId: Long = 0L,
    val triggerPhrase: String,
    val description: String,
    val actions: List<VoiceAction>,
    val confirmationMessage: String,
    val isEnabled: Boolean = true,
    val executionCount: Int = 0,
    val lastExecutedAt: Long? = null
) {
    fun toEntity(): Routine {
        val toolCalls = actions.map { RoutineToolCall.fromVoiceAction(it) }
        return Routine(
            id = dbId,
            routineKey = id,
            triggerPhrase = triggerPhrase.lowercase().trim(),
            description = description,
            toolCalls = toolCalls,
            confirmationMessage = confirmationMessage,
            isEnabled = isEnabled,
            executionCount = executionCount,
            lastExecutedAt = lastExecutedAt
        )
    }

    companion object {
        fun fromEntity(entity: Routine): VoiceRoutine {
            val actions = entity.resolvedToolCalls().map { it.toVoiceAction() }
            return VoiceRoutine(
                id = entity.routineKey,
                dbId = entity.id,
                triggerPhrase = entity.triggerPhrase,
                description = entity.description,
                actions = actions,
                confirmationMessage = entity.confirmationMessage,
                isEnabled = entity.isEnabled,
                executionCount = entity.executionCount,
                lastExecutedAt = entity.lastExecutedAt
            )
        }
    }
}

class RoutineManager(
    private val context: Context,
    private val repository: OmniVoiceRepository? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolvedRepository: OmniVoiceRepository by lazy {
        repository ?: run {
            val db = OmniVoiceDatabase.getDatabase(context)
            OmniVoiceRepository(db.commandHistoryDao(), db.routineDao())
        }
    }

    private val _routines = MutableStateFlow<List<VoiceRoutine>>(getDefaultRoutines())
    val routines: StateFlow<List<VoiceRoutine>> = _routines.asStateFlow()

    init {
        syncWithRoomDatabase()
    }

    private fun syncWithRoomDatabase() {
        scope.launch {
            try {
                if (resolvedRepository.getRoutineCount() == 0) {
                    resolvedRepository.saveAllRoutines(
                        OmniVoiceDatabase.getDefaultPrePopulatedRoutines()
                    )
                }
                resolvedRepository.allRoutines.collect { entities ->
                    if (entities.isNotEmpty()) {
                        _routines.value = entities.map { VoiceRoutine.fromEntity(it) }
                    }
                }
            } catch (e: Exception) {
                Log.w("RoutineManager", "Falling back to in-memory routines", e)
            }
        }
    }

    companion object {
        fun getDefaultRoutines(): List<VoiceRoutine> {
            return OmniVoiceDatabase.getDefaultPrePopulatedRoutines().map {
                VoiceRoutine.fromEntity(it)
            }
        }
    }

    fun findMatchingRoutine(speech: String): VoiceRoutine? {
        val lower = speech.lowercase().trim()
        return _routines.value
            .filter { it.isEnabled }
            .sortedByDescending { it.triggerPhrase.length }
            .find { routine ->
                val trigger = routine.triggerPhrase.lowercase().trim()
                trigger.isNotEmpty() && (lower == trigger || lower.contains(trigger))
            }
    }

    suspend fun findMatchingRoutineFromDb(speech: String): VoiceRoutine? {
        return try {
            resolvedRepository.findMatchingEnabledRoutine(speech)?.let {
                VoiceRoutine.fromEntity(it)
            } ?: findMatchingRoutine(speech)
        } catch (_: Exception) {
            findMatchingRoutine(speech)
        }
    }

    fun toggleRoutine(id: String) {
        val current = _routines.value.find { it.id == id } ?: return
        val newEnabled = !current.isEnabled
        _routines.value = _routines.value.map {
            if (it.id == id) it.copy(isEnabled = newEnabled) else it
        }
        scope.launch {
            try {
                resolvedRepository.setRoutineEnabledByKey(id, newEnabled)
            } catch (e: Exception) {
                Log.w("RoutineManager", "Failed to update routine enabled state in Room", e)
            }
        }
    }

    fun addCustomRoutine(routine: VoiceRoutine) {
        _routines.value = listOf(routine) + _routines.value.filterNot { it.id == routine.id }
        scope.launch {
            try {
                resolvedRepository.saveRoutine(routine.toEntity())
            } catch (e: Exception) {
                Log.w("RoutineManager", "Failed to save custom routine to Room", e)
            }
        }
    }

    fun deleteRoutine(id: String) {
        _routines.value = _routines.value.filterNot { it.id == id }
        scope.launch {
            try {
                resolvedRepository.deleteRoutineByKey(id)
            } catch (e: Exception) {
                Log.w("RoutineManager", "Failed to delete routine from Room", e)
            }
        }
    }

    fun recordRoutineExecution(id: String) {
        val now = System.currentTimeMillis()
        _routines.value = _routines.value.map {
            if (it.id == id) {
                it.copy(executionCount = it.executionCount + 1, lastExecutedAt = now)
            } else {
                it
            }
        }
        scope.launch {
            try {
                resolvedRepository.recordRoutineExecution(id, now)
            } catch (e: Exception) {
                Log.w("RoutineManager", "Failed to record routine execution in Room", e)
            }
        }
    }
}
