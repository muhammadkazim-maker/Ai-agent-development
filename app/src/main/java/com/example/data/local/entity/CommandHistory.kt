package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing an executed voice command audit log entry.
 */
@Entity(tableName = "command_history")
data class CommandHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "raw_speech")
    val rawSpeech: String,

    @ColumnInfo(name = "action_type")
    val actionType: String,

    @ColumnInfo(name = "target")
    val target: String = "",

    @ColumnInfo(name = "is_locked_mode")
    val isLockedMode: Boolean = false,

    @ColumnInfo(name = "verification_passed")
    val verificationPassed: Boolean = true,

    @ColumnInfo(name = "response_summary")
    val responseSummary: String = "",

    @ColumnInfo(name = "execution_status")
    val executionStatus: String = "SUCCESS" // "SUCCESS", "FAILED", "BLOCKED", "CANCELLED"
)
