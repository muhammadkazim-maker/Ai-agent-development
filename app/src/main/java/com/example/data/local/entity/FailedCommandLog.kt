package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the Self-Learning Failed Command Log.
 * Records unrecognized, ambiguous, or corrected voice commands so the user can
 * review them and promote them to the personal vocabulary dictionary.
 */
@Entity(tableName = "failed_command_log")
data class FailedCommandLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "raw_utterance")
    val rawUtterance: String,

    @ColumnInfo(name = "normalized_utterance")
    val normalizedUtterance: String,

    @ColumnInfo(name = "detected_language")
    val detectedLanguage: String,

    @ColumnInfo(name = "failure_reason")
    val failureReason: String,

    @ColumnInfo(name = "suggested_correction")
    val suggestedCorrection: String = "",

    @ColumnInfo(name = "resolved")
    val resolved: Boolean = false,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
