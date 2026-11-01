package com.example.`data`.local

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.example.`data`.local.dao.CommandHistoryDao
import com.example.`data`.local.dao.CommandHistoryDao_Impl
import com.example.`data`.local.dao.FailedCommandLogDao
import com.example.`data`.local.dao.FailedCommandLogDao_Impl
import com.example.`data`.local.dao.PersonalVocabularyDao
import com.example.`data`.local.dao.PersonalVocabularyDao_Impl
import com.example.`data`.local.dao.RoutineDao
import com.example.`data`.local.dao.RoutineDao_Impl
import com.example.`data`.local.dao.UserMemoryDao
import com.example.`data`.local.dao.UserMemoryDao_Impl
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL", "RestrictedApi"])
public class OmniVoiceDatabase_Impl : OmniVoiceDatabase() {
  private val _commandHistoryDao: Lazy<CommandHistoryDao> = lazy {
    CommandHistoryDao_Impl(this)
  }

  private val _routineDao: Lazy<RoutineDao> = lazy {
    RoutineDao_Impl(this)
  }

  private val _personalVocabularyDao: Lazy<PersonalVocabularyDao> = lazy {
    PersonalVocabularyDao_Impl(this)
  }

  private val _userMemoryDao: Lazy<UserMemoryDao> = lazy {
    UserMemoryDao_Impl(this)
  }

  private val _failedCommandLogDao: Lazy<FailedCommandLogDao> = lazy {
    FailedCommandLogDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(3,
        "502f86225d03ff84ff15f6e1797dd3fd", "fe7f2e5ea92052bedd8f445e3becf49f") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `command_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `raw_speech` TEXT NOT NULL, `action_type` TEXT NOT NULL, `target` TEXT NOT NULL, `is_locked_mode` INTEGER NOT NULL, `verification_passed` INTEGER NOT NULL, `response_summary` TEXT NOT NULL, `execution_status` TEXT NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routine_key` TEXT NOT NULL, `trigger_phrase` TEXT NOT NULL, `description` TEXT NOT NULL, `tool_calls` TEXT NOT NULL, `actions_payload` TEXT NOT NULL, `confirmation_message` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `execution_count` INTEGER NOT NULL, `last_executed_at` INTEGER, `created_at` INTEGER NOT NULL)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_routines_routine_key` ON `routines` (`routine_key`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_routines_trigger_phrase` ON `routines` (`trigger_phrase`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `personal_vocabulary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `spoken_phrase` TEXT NOT NULL, `canonical_meaning` TEXT NOT NULL, `urdu_script` TEXT NOT NULL, `category` TEXT NOT NULL, `mapped_action` TEXT NOT NULL, `mapped_target` TEXT NOT NULL, `usage_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_personal_vocabulary_spoken_phrase` ON `personal_vocabulary` (`spoken_phrase`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `user_memory` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `memory_key` TEXT NOT NULL, `memory_value` TEXT NOT NULL, `memory_type` TEXT NOT NULL, `updated_at` INTEGER NOT NULL)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_memory_memory_key` ON `user_memory` (`memory_key`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `failed_command_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `raw_utterance` TEXT NOT NULL, `normalized_utterance` TEXT NOT NULL, `detected_language` TEXT NOT NULL, `failure_reason` TEXT NOT NULL, `suggested_correction` TEXT NOT NULL, `resolved` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '502f86225d03ff84ff15f6e1797dd3fd')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `command_history`")
        connection.execSQL("DROP TABLE IF EXISTS `routines`")
        connection.execSQL("DROP TABLE IF EXISTS `personal_vocabulary`")
        connection.execSQL("DROP TABLE IF EXISTS `user_memory`")
        connection.execSQL("DROP TABLE IF EXISTS `failed_command_log`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection):
          RoomOpenDelegate.ValidationResult {
        val _columnsCommandHistory: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsCommandHistory.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsCommandHistory.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCommandHistory.put("raw_speech", TableInfo.Column("raw_speech", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCommandHistory.put("action_type", TableInfo.Column("action_type", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCommandHistory.put("target", TableInfo.Column("target", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsCommandHistory.put("is_locked_mode", TableInfo.Column("is_locked_mode", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCommandHistory.put("verification_passed", TableInfo.Column("verification_passed",
            "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCommandHistory.put("response_summary", TableInfo.Column("response_summary", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCommandHistory.put("execution_status", TableInfo.Column("execution_status", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysCommandHistory: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesCommandHistory: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoCommandHistory: TableInfo = TableInfo("command_history", _columnsCommandHistory,
            _foreignKeysCommandHistory, _indicesCommandHistory)
        val _existingCommandHistory: TableInfo = read(connection, "command_history")
        if (!_infoCommandHistory.equals(_existingCommandHistory)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |command_history(com.example.data.local.entity.CommandHistory).
              | Expected:
              |""".trimMargin() + _infoCommandHistory + """
              |
              | Found:
              |""".trimMargin() + _existingCommandHistory)
        }
        val _columnsRoutines: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsRoutines.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("routine_key", TableInfo.Column("routine_key", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("trigger_phrase", TableInfo.Column("trigger_phrase", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("description", TableInfo.Column("description", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("tool_calls", TableInfo.Column("tool_calls", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("actions_payload", TableInfo.Column("actions_payload", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("confirmation_message", TableInfo.Column("confirmation_message",
            "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("is_enabled", TableInfo.Column("is_enabled", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("execution_count", TableInfo.Column("execution_count", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("last_executed_at", TableInfo.Column("last_executed_at", "INTEGER",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRoutines.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysRoutines: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesRoutines: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesRoutines.add(TableInfo.Index("index_routines_routine_key", true,
            listOf("routine_key"), listOf("ASC")))
        _indicesRoutines.add(TableInfo.Index("index_routines_trigger_phrase", false,
            listOf("trigger_phrase"), listOf("ASC")))
        val _infoRoutines: TableInfo = TableInfo("routines", _columnsRoutines, _foreignKeysRoutines,
            _indicesRoutines)
        val _existingRoutines: TableInfo = read(connection, "routines")
        if (!_infoRoutines.equals(_existingRoutines)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |routines(com.example.data.local.entity.Routine).
              | Expected:
              |""".trimMargin() + _infoRoutines + """
              |
              | Found:
              |""".trimMargin() + _existingRoutines)
        }
        val _columnsPersonalVocabulary: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPersonalVocabulary.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPersonalVocabulary.put("spoken_phrase", TableInfo.Column("spoken_phrase", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPersonalVocabulary.put("canonical_meaning", TableInfo.Column("canonical_meaning",
            "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPersonalVocabulary.put("urdu_script", TableInfo.Column("urdu_script", "TEXT", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPersonalVocabulary.put("category", TableInfo.Column("category", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPersonalVocabulary.put("mapped_action", TableInfo.Column("mapped_action", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPersonalVocabulary.put("mapped_target", TableInfo.Column("mapped_target", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPersonalVocabulary.put("usage_count", TableInfo.Column("usage_count", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPersonalVocabulary.put("created_at", TableInfo.Column("created_at", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPersonalVocabulary: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesPersonalVocabulary: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesPersonalVocabulary.add(TableInfo.Index("index_personal_vocabulary_spoken_phrase",
            true, listOf("spoken_phrase"), listOf("ASC")))
        val _infoPersonalVocabulary: TableInfo = TableInfo("personal_vocabulary",
            _columnsPersonalVocabulary, _foreignKeysPersonalVocabulary, _indicesPersonalVocabulary)
        val _existingPersonalVocabulary: TableInfo = read(connection, "personal_vocabulary")
        if (!_infoPersonalVocabulary.equals(_existingPersonalVocabulary)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |personal_vocabulary(com.example.data.local.entity.PersonalVocabulary).
              | Expected:
              |""".trimMargin() + _infoPersonalVocabulary + """
              |
              | Found:
              |""".trimMargin() + _existingPersonalVocabulary)
        }
        val _columnsUserMemory: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsUserMemory.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUserMemory.put("memory_key", TableInfo.Column("memory_key", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUserMemory.put("memory_value", TableInfo.Column("memory_value", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserMemory.put("memory_type", TableInfo.Column("memory_type", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUserMemory.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysUserMemory: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesUserMemory: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesUserMemory.add(TableInfo.Index("index_user_memory_memory_key", true,
            listOf("memory_key"), listOf("ASC")))
        val _infoUserMemory: TableInfo = TableInfo("user_memory", _columnsUserMemory,
            _foreignKeysUserMemory, _indicesUserMemory)
        val _existingUserMemory: TableInfo = read(connection, "user_memory")
        if (!_infoUserMemory.equals(_existingUserMemory)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |user_memory(com.example.data.local.entity.UserMemory).
              | Expected:
              |""".trimMargin() + _infoUserMemory + """
              |
              | Found:
              |""".trimMargin() + _existingUserMemory)
        }
        val _columnsFailedCommandLog: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsFailedCommandLog.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsFailedCommandLog.put("raw_utterance", TableInfo.Column("raw_utterance", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsFailedCommandLog.put("normalized_utterance",
            TableInfo.Column("normalized_utterance", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsFailedCommandLog.put("detected_language", TableInfo.Column("detected_language",
            "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsFailedCommandLog.put("failure_reason", TableInfo.Column("failure_reason", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsFailedCommandLog.put("suggested_correction",
            TableInfo.Column("suggested_correction", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsFailedCommandLog.put("resolved", TableInfo.Column("resolved", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsFailedCommandLog.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysFailedCommandLog: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesFailedCommandLog: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoFailedCommandLog: TableInfo = TableInfo("failed_command_log",
            _columnsFailedCommandLog, _foreignKeysFailedCommandLog, _indicesFailedCommandLog)
        val _existingFailedCommandLog: TableInfo = read(connection, "failed_command_log")
        if (!_infoFailedCommandLog.equals(_existingFailedCommandLog)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |failed_command_log(com.example.data.local.entity.FailedCommandLog).
              | Expected:
              |""".trimMargin() + _infoFailedCommandLog + """
              |
              | Found:
              |""".trimMargin() + _existingFailedCommandLog)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "command_history", "routines",
        "personal_vocabulary", "user_memory", "failed_command_log")
  }

  public override fun clearAllTables() {
    super.performClear(false, "command_history", "routines", "personal_vocabulary", "user_memory",
        "failed_command_log")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(CommandHistoryDao::class, CommandHistoryDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(RoutineDao::class, RoutineDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(PersonalVocabularyDao::class,
        PersonalVocabularyDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(UserMemoryDao::class, UserMemoryDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(FailedCommandLogDao::class,
        FailedCommandLogDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override
      fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>):
      List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun commandHistoryDao(): CommandHistoryDao = _commandHistoryDao.value

  public override fun routineDao(): RoutineDao = _routineDao.value

  public override fun personalVocabularyDao(): PersonalVocabularyDao = _personalVocabularyDao.value

  public override fun userMemoryDao(): UserMemoryDao = _userMemoryDao.value

  public override fun failedCommandLogDao(): FailedCommandLogDao = _failedCommandLogDao.value
}
