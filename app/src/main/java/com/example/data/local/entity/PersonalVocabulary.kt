package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the "Teach Me" feature ("yeh word yaad kar lo: ...").
 * Stores user-taught Urdu, Roman Urdu, and mixed phrases, contact nicknames,
 * and custom command synonyms injected into every Gemini prompt and offline matcher.
 */
@Entity(
    tableName = "personal_vocabulary",
    indices = [Index(value = ["spoken_phrase"], unique = true)]
)
data class PersonalVocabulary(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "spoken_phrase")
    val spokenPhrase: String,

    @ColumnInfo(name = "canonical_meaning")
    val canonicalMeaning: String,

    @ColumnInfo(name = "urdu_script")
    val urduScript: String = "",

    @ColumnInfo(name = "category")
    val category: String = "custom_command", // "custom_command", "contact_nickname", "app_alias", "slang"

    @ColumnInfo(name = "mapped_action")
    val mappedAction: String = "",

    @ColumnInfo(name = "mapped_target")
    val mappedTarget: String = "",

    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
