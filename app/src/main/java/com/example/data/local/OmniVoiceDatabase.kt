package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.CommandHistoryDao
import com.example.data.local.dao.FailedCommandLogDao
import com.example.data.local.dao.PersonalVocabularyDao
import com.example.data.local.dao.RoutineDao
import com.example.data.local.dao.UserMemoryDao
import com.example.data.local.entity.CommandHistory
import com.example.data.local.entity.FailedCommandLog
import com.example.data.local.entity.PersonalVocabulary
import com.example.data.local.entity.Routine
import com.example.data.local.entity.RoutineConverters
import com.example.data.local.entity.RoutineToolCall
import com.example.data.local.entity.UserMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main Room Database for OmniVoice, providing structured local persistence for:
 * - Command execution history (`command_history`)
 * - Multi-tool voice automation routines (`routines`)
 * - Personal taught vocabulary (`personal_vocabulary`)
 * - Long-term user memory, contact nicknames & conditional rules (`user_memory`)
 * - Self-learning failed command log (`failed_command_log`)
 */
@Database(
    entities = [
        CommandHistory::class,
        Routine::class,
        PersonalVocabulary::class,
        UserMemory::class,
        FailedCommandLog::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(RoutineConverters::class)
abstract class OmniVoiceDatabase : RoomDatabase() {

    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun routineDao(): RoutineDao
    abstract fun personalVocabularyDao(): PersonalVocabularyDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun failedCommandLogDao(): FailedCommandLogDao

    companion object {
        @Volatile
        private var INSTANCE: OmniVoiceDatabase? = null

        private const val DATABASE_NAME = "omnivoice_database.db"

        fun getDatabase(context: Context): OmniVoiceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OmniVoiceDatabase::class.java,
                    DATABASE_NAME
                )
                    .addCallback(DatabaseCallback(context.applicationContext))
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    val database = getDatabase(context)
                    val routineDao = database.routineDao()
                    if (routineDao.count() == 0) {
                        routineDao.insertAll(getDefaultPrePopulatedRoutines())
                    }
                    val vocabDao = database.personalVocabularyDao()
                    if (vocabDao.count() == 0) {
                        vocabDao.insertAll(getDefaultPersonalVocabulary())
                    }
                    val memoryDao = database.userMemoryDao()
                    if (memoryDao.count() == 0) {
                        memoryDao.insertAll(getDefaultUserMemories())
                    }
                }
            }
        }

        fun getDefaultPersonalVocabulary(): List<PersonalVocabulary> {
            return listOf(
                PersonalVocabulary(
                    spokenPhrase = "batti jalao",
                    canonicalMeaning = "turn on flashlight",
                    urduScript = "بتی جلاؤ",
                    category = "custom_command",
                    mappedAction = "toggle_flashlight",
                    mappedTarget = "on"
                ),
                PersonalVocabulary(
                    spokenPhrase = "batti bujhao",
                    canonicalMeaning = "turn off flashlight",
                    urduScript = "بتی بجھاؤ",
                    category = "custom_command",
                    mappedAction = "toggle_flashlight",
                    mappedTarget = "off"
                ),
                PersonalVocabulary(
                    spokenPhrase = "ammi jaan",
                    canonicalMeaning = "Mom",
                    urduScript = "امی جان",
                    category = "contact_nickname",
                    mappedAction = "contact_alias",
                    mappedTarget = "Mom"
                ),
                PersonalVocabulary(
                    spokenPhrase = "abbu jaan",
                    canonicalMeaning = "Dad",
                    urduScript = "ابو جان",
                    category = "contact_nickname",
                    mappedAction = "contact_alias",
                    mappedTarget = "Dad"
                ),
                PersonalVocabulary(
                    spokenPhrase = "chachu",
                    canonicalMeaning = "Uncle Tariq",
                    urduScript = "چاچو",
                    category = "contact_nickname",
                    mappedAction = "contact_alias",
                    mappedTarget = "Uncle Tariq"
                )
            )
        }

        fun getDefaultUserMemories(): List<UserMemory> {
            return listOf(
                UserMemory(
                    memoryKey = "user_name",
                    memoryValue = "Zain",
                    memoryType = "profile"
                ),
                UserMemory(
                    memoryKey = "politeness_style",
                    memoryValue = "aap (respectful Urdu/Roman Urdu)",
                    memoryType = "preference"
                ),
                UserMemory(
                    memoryKey = "nickname:ammi",
                    memoryValue = "Mom (+923001112233)",
                    memoryType = "contact_nickname"
                ),
                UserMemory(
                    memoryKey = "nickname:abbu",
                    memoryValue = "Dad (+923004445566)",
                    memoryType = "contact_nickname"
                ),
                UserMemory(
                    memoryKey = "nickname:ali",
                    memoryValue = "Ali Khan (+923219876543)",
                    memoryType = "contact_nickname"
                ),
                UserMemory(
                    memoryKey = "nickname:ahmed",
                    memoryValue = "Ahmed Raza (+923335556677)",
                    memoryType = "contact_nickname"
                ),
                UserMemory(
                    memoryKey = "rule:incoming_call_ali",
                    memoryValue = "If Ali calls -> end_call & send_sms('I will call you later')",
                    memoryType = "conditional_rule"
                )
            )
        }

        fun getDefaultPrePopulatedRoutines(): List<Routine> {
            return listOf(
                Routine(
                    routineKey = "good_night",
                    triggerPhrase = "good night",
                    description = "Sets alarm for 7:00 AM, turns off flashlight, mutes volume, and locks device.",
                    toolCalls = listOf(
                        RoutineToolCall(
                            toolName = "set_alarm",
                            target = "07:00",
                            parameters = mapOf("hour" to "7", "minute" to "0")
                        ),
                        RoutineToolCall(
                            toolName = "toggle_flashlight",
                            target = "off",
                            parameters = mapOf("state" to "off")
                        ),
                        RoutineToolCall(
                            toolName = "adjust_volume",
                            target = "mute",
                            parameters = mapOf("direction" to "mute")
                        ),
                        RoutineToolCall(
                            toolName = "lock_device",
                            target = "screen"
                        )
                    ),
                    confirmationMessage = "Good night. Alarm set for 7 AM, audio muted, and device locked."
                ),
                Routine(
                    routineKey = "shab_ba_khair",
                    triggerPhrase = "shab bakhair",
                    description = "Subah 7 baje ka alarm lagata hai, phone silent karta hai aur screen lock karta hai.",
                    toolCalls = listOf(
                        RoutineToolCall(
                            toolName = "set_alarm",
                            target = "07:00",
                            parameters = mapOf("hour" to "7", "minute" to "0")
                        ),
                        RoutineToolCall(
                            toolName = "adjust_volume",
                            target = "mute",
                            parameters = mapOf("direction" to "mute")
                        ),
                        RoutineToolCall(
                            toolName = "lock_device",
                            target = "screen"
                        )
                    ),
                    confirmationMessage = "Shab bakhair! Subah 7 baje ka alarm lag gaya aur phone silent ho gaya."
                ),
                Routine(
                    routineKey = "good_morning",
                    triggerPhrase = "good morning",
                    description = "Reads unread notifications, tells date/time, and sets volume up.",
                    toolCalls = listOf(
                        RoutineToolCall(
                            toolName = "general_chat",
                            target = "date"
                        ),
                        RoutineToolCall(
                            toolName = "adjust_volume",
                            target = "up",
                            parameters = mapOf("direction" to "up")
                        ),
                        RoutineToolCall(
                            toolName = "read_notifications",
                            target = "all"
                        )
                    ),
                    confirmationMessage = "Good morning! Here is your daily briefing."
                ),
                Routine(
                    routineKey = "find_phone",
                    triggerPhrase = "where are you",
                    description = "Rings device at maximum volume and turns on flashlight to locate phone.",
                    toolCalls = listOf(
                        RoutineToolCall(
                            toolName = "find_phone",
                            target = "alert"
                        ),
                        RoutineToolCall(
                            toolName = "toggle_flashlight",
                            target = "on",
                            parameters = mapOf("state" to "on")
                        )
                    ),
                    confirmationMessage = "I am right here! Playing location alarm now."
                ),
                Routine(
                    routineKey = "emergency_lock",
                    triggerPhrase = "lock down",
                    description = "Immediately locks phone screen and secures system.",
                    toolCalls = listOf(
                        RoutineToolCall(
                            toolName = "lock_device",
                            target = "screen"
                        )
                    ),
                    confirmationMessage = "Lockdown initiated. Device secured."
                )
            )
        }
    }
}
