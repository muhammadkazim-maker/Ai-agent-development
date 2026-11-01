package com.example.`data`.local.dao

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.example.`data`.local.entity.Routine
import com.example.`data`.local.entity.RoutineConverters
import com.example.`data`.local.entity.RoutineToolCall
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL", "RestrictedApi"])
public class RoutineDao_Impl(
  __db: RoomDatabase,
) : RoutineDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfRoutine: EntityInsertAdapter<Routine>

  private val __routineConverters: RoutineConverters = RoutineConverters()

  private val __deleteAdapterOfRoutine: EntityDeleteOrUpdateAdapter<Routine>

  private val __updateAdapterOfRoutine: EntityDeleteOrUpdateAdapter<Routine>
  init {
    this.__db = __db
    this.__insertAdapterOfRoutine = object : EntityInsertAdapter<Routine>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `routines` (`id`,`routine_key`,`trigger_phrase`,`description`,`tool_calls`,`actions_payload`,`confirmation_message`,`is_enabled`,`execution_count`,`last_executed_at`,`created_at`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: Routine) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.routineKey)
        statement.bindText(3, entity.triggerPhrase)
        statement.bindText(4, entity.description)
        val _tmp: String = __routineConverters.fromToolCallList(entity.toolCalls)
        statement.bindText(5, _tmp)
        statement.bindText(6, entity.actionsPayload)
        statement.bindText(7, entity.confirmationMessage)
        val _tmp_1: Int = if (entity.isEnabled) 1 else 0
        statement.bindLong(8, _tmp_1.toLong())
        statement.bindLong(9, entity.executionCount.toLong())
        val _tmpLastExecutedAt: Long? = entity.lastExecutedAt
        if (_tmpLastExecutedAt == null) {
          statement.bindNull(10)
        } else {
          statement.bindLong(10, _tmpLastExecutedAt)
        }
        statement.bindLong(11, entity.createdAt)
      }
    }
    this.__deleteAdapterOfRoutine = object : EntityDeleteOrUpdateAdapter<Routine>() {
      protected override fun createQuery(): String = "DELETE FROM `routines` WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: Routine) {
        statement.bindLong(1, entity.id)
      }
    }
    this.__updateAdapterOfRoutine = object : EntityDeleteOrUpdateAdapter<Routine>() {
      protected override fun createQuery(): String =
          "UPDATE OR ABORT `routines` SET `id` = ?,`routine_key` = ?,`trigger_phrase` = ?,`description` = ?,`tool_calls` = ?,`actions_payload` = ?,`confirmation_message` = ?,`is_enabled` = ?,`execution_count` = ?,`last_executed_at` = ?,`created_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: Routine) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.routineKey)
        statement.bindText(3, entity.triggerPhrase)
        statement.bindText(4, entity.description)
        val _tmp: String = __routineConverters.fromToolCallList(entity.toolCalls)
        statement.bindText(5, _tmp)
        statement.bindText(6, entity.actionsPayload)
        statement.bindText(7, entity.confirmationMessage)
        val _tmp_1: Int = if (entity.isEnabled) 1 else 0
        statement.bindLong(8, _tmp_1.toLong())
        statement.bindLong(9, entity.executionCount.toLong())
        val _tmpLastExecutedAt: Long? = entity.lastExecutedAt
        if (_tmpLastExecutedAt == null) {
          statement.bindNull(10)
        } else {
          statement.bindLong(10, _tmpLastExecutedAt)
        }
        statement.bindLong(11, entity.createdAt)
        statement.bindLong(12, entity.id)
      }
    }
  }

  public override suspend fun insert(routine: Routine): Long = performSuspending(__db, false, true)
      { _connection ->
    val _result: Long = __insertAdapterOfRoutine.insertAndReturnId(_connection, routine)
    _result
  }

  public override suspend fun insertAll(routines: List<Routine>): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfRoutine.insert(_connection, routines)
  }

  public override suspend fun delete(routine: Routine): Unit = performSuspending(__db, false, true)
      { _connection ->
    __deleteAdapterOfRoutine.handle(_connection, routine)
  }

  public override suspend fun update(routine: Routine): Unit = performSuspending(__db, false, true)
      { _connection ->
    __updateAdapterOfRoutine.handle(_connection, routine)
  }

  public override fun getAllRoutines(): Flow<List<Routine>> {
    val _sql: String = "SELECT * FROM routines ORDER BY created_at DESC"
    return createFlow(__db, false, arrayOf("routines")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfRoutineKey: Int = getColumnIndexOrThrow(_stmt, "routine_key")
        val _columnIndexOfTriggerPhrase: Int = getColumnIndexOrThrow(_stmt, "trigger_phrase")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfToolCalls: Int = getColumnIndexOrThrow(_stmt, "tool_calls")
        val _columnIndexOfActionsPayload: Int = getColumnIndexOrThrow(_stmt, "actions_payload")
        val _columnIndexOfConfirmationMessage: Int = getColumnIndexOrThrow(_stmt,
            "confirmation_message")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfExecutionCount: Int = getColumnIndexOrThrow(_stmt, "execution_count")
        val _columnIndexOfLastExecutedAt: Int = getColumnIndexOrThrow(_stmt, "last_executed_at")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: MutableList<Routine> = mutableListOf()
        while (_stmt.step()) {
          val _item: Routine
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpRoutineKey: String
          _tmpRoutineKey = _stmt.getText(_columnIndexOfRoutineKey)
          val _tmpTriggerPhrase: String
          _tmpTriggerPhrase = _stmt.getText(_columnIndexOfTriggerPhrase)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpToolCalls: List<RoutineToolCall>
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfToolCalls)
          _tmpToolCalls = __routineConverters.toToolCallList(_tmp)
          val _tmpActionsPayload: String
          _tmpActionsPayload = _stmt.getText(_columnIndexOfActionsPayload)
          val _tmpConfirmationMessage: String
          _tmpConfirmationMessage = _stmt.getText(_columnIndexOfConfirmationMessage)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpExecutionCount: Int
          _tmpExecutionCount = _stmt.getLong(_columnIndexOfExecutionCount).toInt()
          val _tmpLastExecutedAt: Long?
          if (_stmt.isNull(_columnIndexOfLastExecutedAt)) {
            _tmpLastExecutedAt = null
          } else {
            _tmpLastExecutedAt = _stmt.getLong(_columnIndexOfLastExecutedAt)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item =
              Routine(_tmpId,_tmpRoutineKey,_tmpTriggerPhrase,_tmpDescription,_tmpToolCalls,_tmpActionsPayload,_tmpConfirmationMessage,_tmpIsEnabled,_tmpExecutionCount,_tmpLastExecutedAt,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getEnabledRoutines(): Flow<List<Routine>> {
    val _sql: String = "SELECT * FROM routines WHERE is_enabled = 1 ORDER BY created_at DESC"
    return createFlow(__db, false, arrayOf("routines")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfRoutineKey: Int = getColumnIndexOrThrow(_stmt, "routine_key")
        val _columnIndexOfTriggerPhrase: Int = getColumnIndexOrThrow(_stmt, "trigger_phrase")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfToolCalls: Int = getColumnIndexOrThrow(_stmt, "tool_calls")
        val _columnIndexOfActionsPayload: Int = getColumnIndexOrThrow(_stmt, "actions_payload")
        val _columnIndexOfConfirmationMessage: Int = getColumnIndexOrThrow(_stmt,
            "confirmation_message")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfExecutionCount: Int = getColumnIndexOrThrow(_stmt, "execution_count")
        val _columnIndexOfLastExecutedAt: Int = getColumnIndexOrThrow(_stmt, "last_executed_at")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: MutableList<Routine> = mutableListOf()
        while (_stmt.step()) {
          val _item: Routine
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpRoutineKey: String
          _tmpRoutineKey = _stmt.getText(_columnIndexOfRoutineKey)
          val _tmpTriggerPhrase: String
          _tmpTriggerPhrase = _stmt.getText(_columnIndexOfTriggerPhrase)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpToolCalls: List<RoutineToolCall>
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfToolCalls)
          _tmpToolCalls = __routineConverters.toToolCallList(_tmp)
          val _tmpActionsPayload: String
          _tmpActionsPayload = _stmt.getText(_columnIndexOfActionsPayload)
          val _tmpConfirmationMessage: String
          _tmpConfirmationMessage = _stmt.getText(_columnIndexOfConfirmationMessage)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpExecutionCount: Int
          _tmpExecutionCount = _stmt.getLong(_columnIndexOfExecutionCount).toInt()
          val _tmpLastExecutedAt: Long?
          if (_stmt.isNull(_columnIndexOfLastExecutedAt)) {
            _tmpLastExecutedAt = null
          } else {
            _tmpLastExecutedAt = _stmt.getLong(_columnIndexOfLastExecutedAt)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item =
              Routine(_tmpId,_tmpRoutineKey,_tmpTriggerPhrase,_tmpDescription,_tmpToolCalls,_tmpActionsPayload,_tmpConfirmationMessage,_tmpIsEnabled,_tmpExecutionCount,_tmpLastExecutedAt,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllRoutinesSnapshot(): List<Routine> {
    val _sql: String = "SELECT * FROM routines ORDER BY created_at ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfRoutineKey: Int = getColumnIndexOrThrow(_stmt, "routine_key")
        val _columnIndexOfTriggerPhrase: Int = getColumnIndexOrThrow(_stmt, "trigger_phrase")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfToolCalls: Int = getColumnIndexOrThrow(_stmt, "tool_calls")
        val _columnIndexOfActionsPayload: Int = getColumnIndexOrThrow(_stmt, "actions_payload")
        val _columnIndexOfConfirmationMessage: Int = getColumnIndexOrThrow(_stmt,
            "confirmation_message")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfExecutionCount: Int = getColumnIndexOrThrow(_stmt, "execution_count")
        val _columnIndexOfLastExecutedAt: Int = getColumnIndexOrThrow(_stmt, "last_executed_at")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: MutableList<Routine> = mutableListOf()
        while (_stmt.step()) {
          val _item: Routine
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpRoutineKey: String
          _tmpRoutineKey = _stmt.getText(_columnIndexOfRoutineKey)
          val _tmpTriggerPhrase: String
          _tmpTriggerPhrase = _stmt.getText(_columnIndexOfTriggerPhrase)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpToolCalls: List<RoutineToolCall>
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfToolCalls)
          _tmpToolCalls = __routineConverters.toToolCallList(_tmp)
          val _tmpActionsPayload: String
          _tmpActionsPayload = _stmt.getText(_columnIndexOfActionsPayload)
          val _tmpConfirmationMessage: String
          _tmpConfirmationMessage = _stmt.getText(_columnIndexOfConfirmationMessage)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpExecutionCount: Int
          _tmpExecutionCount = _stmt.getLong(_columnIndexOfExecutionCount).toInt()
          val _tmpLastExecutedAt: Long?
          if (_stmt.isNull(_columnIndexOfLastExecutedAt)) {
            _tmpLastExecutedAt = null
          } else {
            _tmpLastExecutedAt = _stmt.getLong(_columnIndexOfLastExecutedAt)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item =
              Routine(_tmpId,_tmpRoutineKey,_tmpTriggerPhrase,_tmpDescription,_tmpToolCalls,_tmpActionsPayload,_tmpConfirmationMessage,_tmpIsEnabled,_tmpExecutionCount,_tmpLastExecutedAt,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRoutineById(id: Long): Routine? {
    val _sql: String = "SELECT * FROM routines WHERE id = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfRoutineKey: Int = getColumnIndexOrThrow(_stmt, "routine_key")
        val _columnIndexOfTriggerPhrase: Int = getColumnIndexOrThrow(_stmt, "trigger_phrase")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfToolCalls: Int = getColumnIndexOrThrow(_stmt, "tool_calls")
        val _columnIndexOfActionsPayload: Int = getColumnIndexOrThrow(_stmt, "actions_payload")
        val _columnIndexOfConfirmationMessage: Int = getColumnIndexOrThrow(_stmt,
            "confirmation_message")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfExecutionCount: Int = getColumnIndexOrThrow(_stmt, "execution_count")
        val _columnIndexOfLastExecutedAt: Int = getColumnIndexOrThrow(_stmt, "last_executed_at")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: Routine?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpRoutineKey: String
          _tmpRoutineKey = _stmt.getText(_columnIndexOfRoutineKey)
          val _tmpTriggerPhrase: String
          _tmpTriggerPhrase = _stmt.getText(_columnIndexOfTriggerPhrase)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpToolCalls: List<RoutineToolCall>
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfToolCalls)
          _tmpToolCalls = __routineConverters.toToolCallList(_tmp)
          val _tmpActionsPayload: String
          _tmpActionsPayload = _stmt.getText(_columnIndexOfActionsPayload)
          val _tmpConfirmationMessage: String
          _tmpConfirmationMessage = _stmt.getText(_columnIndexOfConfirmationMessage)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpExecutionCount: Int
          _tmpExecutionCount = _stmt.getLong(_columnIndexOfExecutionCount).toInt()
          val _tmpLastExecutedAt: Long?
          if (_stmt.isNull(_columnIndexOfLastExecutedAt)) {
            _tmpLastExecutedAt = null
          } else {
            _tmpLastExecutedAt = _stmt.getLong(_columnIndexOfLastExecutedAt)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _result =
              Routine(_tmpId,_tmpRoutineKey,_tmpTriggerPhrase,_tmpDescription,_tmpToolCalls,_tmpActionsPayload,_tmpConfirmationMessage,_tmpIsEnabled,_tmpExecutionCount,_tmpLastExecutedAt,_tmpCreatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRoutineByTrigger(triggerPhrase: String): Routine? {
    val _sql: String =
        "SELECT * FROM routines WHERE LOWER(TRIM(trigger_phrase)) = LOWER(TRIM(?)) LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, triggerPhrase)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfRoutineKey: Int = getColumnIndexOrThrow(_stmt, "routine_key")
        val _columnIndexOfTriggerPhrase: Int = getColumnIndexOrThrow(_stmt, "trigger_phrase")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfToolCalls: Int = getColumnIndexOrThrow(_stmt, "tool_calls")
        val _columnIndexOfActionsPayload: Int = getColumnIndexOrThrow(_stmt, "actions_payload")
        val _columnIndexOfConfirmationMessage: Int = getColumnIndexOrThrow(_stmt,
            "confirmation_message")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfExecutionCount: Int = getColumnIndexOrThrow(_stmt, "execution_count")
        val _columnIndexOfLastExecutedAt: Int = getColumnIndexOrThrow(_stmt, "last_executed_at")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: Routine?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpRoutineKey: String
          _tmpRoutineKey = _stmt.getText(_columnIndexOfRoutineKey)
          val _tmpTriggerPhrase: String
          _tmpTriggerPhrase = _stmt.getText(_columnIndexOfTriggerPhrase)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpToolCalls: List<RoutineToolCall>
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfToolCalls)
          _tmpToolCalls = __routineConverters.toToolCallList(_tmp)
          val _tmpActionsPayload: String
          _tmpActionsPayload = _stmt.getText(_columnIndexOfActionsPayload)
          val _tmpConfirmationMessage: String
          _tmpConfirmationMessage = _stmt.getText(_columnIndexOfConfirmationMessage)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpExecutionCount: Int
          _tmpExecutionCount = _stmt.getLong(_columnIndexOfExecutionCount).toInt()
          val _tmpLastExecutedAt: Long?
          if (_stmt.isNull(_columnIndexOfLastExecutedAt)) {
            _tmpLastExecutedAt = null
          } else {
            _tmpLastExecutedAt = _stmt.getLong(_columnIndexOfLastExecutedAt)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _result =
              Routine(_tmpId,_tmpRoutineKey,_tmpTriggerPhrase,_tmpDescription,_tmpToolCalls,_tmpActionsPayload,_tmpConfirmationMessage,_tmpIsEnabled,_tmpExecutionCount,_tmpLastExecutedAt,_tmpCreatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun findMatchingEnabledRoutine(spokenText: String): Routine? {
    val _sql: String = """
        |
        |        SELECT * FROM routines 
        |        WHERE is_enabled = 1 
        |          AND (
        |              LOWER(TRIM(trigger_phrase)) = LOWER(TRIM(?)) 
        |              OR LOWER(?) LIKE '%' || LOWER(TRIM(trigger_phrase)) || '%'
        |          )
        |        ORDER BY LENGTH(trigger_phrase) DESC 
        |        LIMIT 1
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, spokenText)
        _argIndex = 2
        _stmt.bindText(_argIndex, spokenText)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfRoutineKey: Int = getColumnIndexOrThrow(_stmt, "routine_key")
        val _columnIndexOfTriggerPhrase: Int = getColumnIndexOrThrow(_stmt, "trigger_phrase")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfToolCalls: Int = getColumnIndexOrThrow(_stmt, "tool_calls")
        val _columnIndexOfActionsPayload: Int = getColumnIndexOrThrow(_stmt, "actions_payload")
        val _columnIndexOfConfirmationMessage: Int = getColumnIndexOrThrow(_stmt,
            "confirmation_message")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfExecutionCount: Int = getColumnIndexOrThrow(_stmt, "execution_count")
        val _columnIndexOfLastExecutedAt: Int = getColumnIndexOrThrow(_stmt, "last_executed_at")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: Routine?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpRoutineKey: String
          _tmpRoutineKey = _stmt.getText(_columnIndexOfRoutineKey)
          val _tmpTriggerPhrase: String
          _tmpTriggerPhrase = _stmt.getText(_columnIndexOfTriggerPhrase)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpToolCalls: List<RoutineToolCall>
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfToolCalls)
          _tmpToolCalls = __routineConverters.toToolCallList(_tmp)
          val _tmpActionsPayload: String
          _tmpActionsPayload = _stmt.getText(_columnIndexOfActionsPayload)
          val _tmpConfirmationMessage: String
          _tmpConfirmationMessage = _stmt.getText(_columnIndexOfConfirmationMessage)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpExecutionCount: Int
          _tmpExecutionCount = _stmt.getLong(_columnIndexOfExecutionCount).toInt()
          val _tmpLastExecutedAt: Long?
          if (_stmt.isNull(_columnIndexOfLastExecutedAt)) {
            _tmpLastExecutedAt = null
          } else {
            _tmpLastExecutedAt = _stmt.getLong(_columnIndexOfLastExecutedAt)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _result =
              Routine(_tmpId,_tmpRoutineKey,_tmpTriggerPhrase,_tmpDescription,_tmpToolCalls,_tmpActionsPayload,_tmpConfirmationMessage,_tmpIsEnabled,_tmpExecutionCount,_tmpLastExecutedAt,_tmpCreatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRoutineByKey(key: String): Routine? {
    val _sql: String = "SELECT * FROM routines WHERE routine_key = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, key)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfRoutineKey: Int = getColumnIndexOrThrow(_stmt, "routine_key")
        val _columnIndexOfTriggerPhrase: Int = getColumnIndexOrThrow(_stmt, "trigger_phrase")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfToolCalls: Int = getColumnIndexOrThrow(_stmt, "tool_calls")
        val _columnIndexOfActionsPayload: Int = getColumnIndexOrThrow(_stmt, "actions_payload")
        val _columnIndexOfConfirmationMessage: Int = getColumnIndexOrThrow(_stmt,
            "confirmation_message")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfExecutionCount: Int = getColumnIndexOrThrow(_stmt, "execution_count")
        val _columnIndexOfLastExecutedAt: Int = getColumnIndexOrThrow(_stmt, "last_executed_at")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: Routine?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpRoutineKey: String
          _tmpRoutineKey = _stmt.getText(_columnIndexOfRoutineKey)
          val _tmpTriggerPhrase: String
          _tmpTriggerPhrase = _stmt.getText(_columnIndexOfTriggerPhrase)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpToolCalls: List<RoutineToolCall>
          val _tmp: String
          _tmp = _stmt.getText(_columnIndexOfToolCalls)
          _tmpToolCalls = __routineConverters.toToolCallList(_tmp)
          val _tmpActionsPayload: String
          _tmpActionsPayload = _stmt.getText(_columnIndexOfActionsPayload)
          val _tmpConfirmationMessage: String
          _tmpConfirmationMessage = _stmt.getText(_columnIndexOfConfirmationMessage)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpExecutionCount: Int
          _tmpExecutionCount = _stmt.getLong(_columnIndexOfExecutionCount).toInt()
          val _tmpLastExecutedAt: Long?
          if (_stmt.isNull(_columnIndexOfLastExecutedAt)) {
            _tmpLastExecutedAt = null
          } else {
            _tmpLastExecutedAt = _stmt.getLong(_columnIndexOfLastExecutedAt)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _result =
              Routine(_tmpId,_tmpRoutineKey,_tmpTriggerPhrase,_tmpDescription,_tmpToolCalls,_tmpActionsPayload,_tmpConfirmationMessage,_tmpIsEnabled,_tmpExecutionCount,_tmpLastExecutedAt,_tmpCreatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun count(): Int {
    val _sql: String = "SELECT COUNT(*) FROM routines"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun setEnabled(id: Long, enabled: Boolean) {
    val _sql: String = "UPDATE routines SET is_enabled = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (enabled) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _argIndex = 2
        _stmt.bindLong(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun setEnabledByKey(routineKey: String, enabled: Boolean) {
    val _sql: String = "UPDATE routines SET is_enabled = ? WHERE routine_key = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (enabled) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _argIndex = 2
        _stmt.bindText(_argIndex, routineKey)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun recordExecution(routineKey: String, timestamp: Long) {
    val _sql: String = """
        |
        |        UPDATE routines 
        |        SET execution_count = execution_count + 1, 
        |            last_executed_at = ? 
        |        WHERE routine_key = ?
        |        
        """.trimMargin()
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, timestamp)
        _argIndex = 2
        _stmt.bindText(_argIndex, routineKey)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteById(id: Long) {
    val _sql: String = "DELETE FROM routines WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteByKey(routineKey: String) {
    val _sql: String = "DELETE FROM routines WHERE routine_key = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, routineKey)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clearAll() {
    val _sql: String = "DELETE FROM routines"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
