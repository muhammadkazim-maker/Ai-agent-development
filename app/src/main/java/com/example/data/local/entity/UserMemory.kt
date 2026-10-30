package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for OmniVoice Long-Term Memory:
 * Stores user's name, contact nicknames, preferences, conditional rules
 * (e.g., "If Ali calls, reject it and send him 'I'll call you later'"),
 * and contextual references ("him", "that number", "same as yesterday").
 */
@Entity(
    tableName = "user_memory",
    indices = [Index(value = ["memory_key"], unique = true)]
)
data class UserMemory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "memory_key")
    val memoryKey: String,

    @ColumnInfo(name = "memory_value")
    val memoryValue: String,

    @ColumnInfo(name = "memory_type")
    val memoryType: String = "preference", // "profile", "contact_nickname", "preference", "conditional_rule", "context"

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
