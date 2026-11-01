package com.example.`data`.local.dao

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.example.`data`.local.entity.CommandHistory
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL", "RestrictedApi"])
public class CommandHistoryDao_Impl(
  __db: RoomDatabase,
) : CommandHistoryDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfCommandHistory: EntityInsertAdapter<CommandHistory>
  init {
    this.__db = __db
    this.__insertAdapterOfCommandHistory = object : EntityInsertAdapter<CommandHistory>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `command_history` (`id`,`timestamp`,`raw_speech`,`action_type`,`target`,`is_locked_mode`,`verification_passed`,`response_summary`,`execution_status`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: CommandHistory) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.timestamp)
        statement.bindText(3, entity.rawSpeech)
        statement.bindText(4, entity.actionType)
        statement.bindText(5, entity.target)
        val _tmp: Int = if (entity.isLockedMode) 1 else 0
        statement.bindLong(6, _tmp.toLong())
        val _tmp_1: Int = if (entity.verificationPassed) 1 else 0
        statement.bindLong(7, _tmp_1.toLong())
        statement.bindText(8, entity.responseSummary)
        statement.bindText(9, entity.executionStatus)
      }
    }
  }

  public override suspend fun insert(entry: CommandHistory): Long = performSuspending(__db, false,
      true) { _connection ->
    val _result: Long = __insertAdapterOfCommandHistory.insertAndReturnId(_connection, entry)
    _result
  }

  public override fun getAllHistory(): Flow<List<CommandHistory>> {
    val _sql: String = "SELECT * FROM command_history ORDER BY timestamp DESC"
    return createFlow(__db, false, arrayOf("command_history")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfRawSpeech: Int = getColumnIndexOrThrow(_stmt, "raw_speech")
        val _columnIndexOfActionType: Int = getColumnIndexOrThrow(_stmt, "action_type")
        val _columnIndexOfTarget: Int = getColumnIndexOrThrow(_stmt, "target")
        val _columnIndexOfIsLockedMode: Int = getColumnIndexOrThrow(_stmt, "is_locked_mode")
        val _columnIndexOfVerificationPassed: Int = getColumnIndexOrThrow(_stmt,
            "verification_passed")
        val _columnIndexOfResponseSummary: Int = getColumnIndexOrThrow(_stmt, "response_summary")
        val _columnIndexOfExecutionStatus: Int = getColumnIndexOrThrow(_stmt, "execution_status")
        val _result: MutableList<CommandHistory> = mutableListOf()
        while (_stmt.step()) {
          val _item: CommandHistory
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpRawSpeech: String
          _tmpRawSpeech = _stmt.getText(_columnIndexOfRawSpeech)
          val _tmpActionType: String
          _tmpActionType = _stmt.getText(_columnIndexOfActionType)
          val _tmpTarget: String
          _tmpTarget = _stmt.getText(_columnIndexOfTarget)
          val _tmpIsLockedMode: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLockedMode).toInt()
          _tmpIsLockedMode = _tmp != 0
          val _tmpVerificationPassed: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfVerificationPassed).toInt()
          _tmpVerificationPassed = _tmp_1 != 0
          val _tmpResponseSummary: String
          _tmpResponseSummary = _stmt.getText(_columnIndexOfResponseSummary)
          val _tmpExecutionStatus: String
          _tmpExecutionStatus = _stmt.getText(_columnIndexOfExecutionStatus)
          _item =
              CommandHistory(_tmpId,_tmpTimestamp,_tmpRawSpeech,_tmpActionType,_tmpTarget,_tmpIsLockedMode,_tmpVerificationPassed,_tmpResponseSummary,_tmpExecutionStatus)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getRecentHistory(limit: Int): Flow<List<CommandHistory>> {
    val _sql: String = "SELECT * FROM command_history ORDER BY timestamp DESC LIMIT ?"
    return createFlow(__db, false, arrayOf("command_history")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfRawSpeech: Int = getColumnIndexOrThrow(_stmt, "raw_speech")
        val _columnIndexOfActionType: Int = getColumnIndexOrThrow(_stmt, "action_type")
        val _columnIndexOfTarget: Int = getColumnIndexOrThrow(_stmt, "target")
        val _columnIndexOfIsLockedMode: Int = getColumnIndexOrThrow(_stmt, "is_locked_mode")
        val _columnIndexOfVerificationPassed: Int = getColumnIndexOrThrow(_stmt,
            "verification_passed")
        val _columnIndexOfResponseSummary: Int = getColumnIndexOrThrow(_stmt, "response_summary")
        val _columnIndexOfExecutionStatus: Int = getColumnIndexOrThrow(_stmt, "execution_status")
        val _result: MutableList<CommandHistory> = mutableListOf()
        while (_stmt.step()) {
          val _item: CommandHistory
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpRawSpeech: String
          _tmpRawSpeech = _stmt.getText(_columnIndexOfRawSpeech)
          val _tmpActionType: String
          _tmpActionType = _stmt.getText(_columnIndexOfActionType)
          val _tmpTarget: String
          _tmpTarget = _stmt.getText(_columnIndexOfTarget)
          val _tmpIsLockedMode: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLockedMode).toInt()
          _tmpIsLockedMode = _tmp != 0
          val _tmpVerificationPassed: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfVerificationPassed).toInt()
          _tmpVerificationPassed = _tmp_1 != 0
          val _tmpResponseSummary: String
          _tmpResponseSummary = _stmt.getText(_columnIndexOfResponseSummary)
          val _tmpExecutionStatus: String
          _tmpExecutionStatus = _stmt.getText(_columnIndexOfExecutionStatus)
          _item =
              CommandHistory(_tmpId,_tmpTimestamp,_tmpRawSpeech,_tmpActionType,_tmpTarget,_tmpIsLockedMode,_tmpVerificationPassed,_tmpResponseSummary,_tmpExecutionStatus)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun count(): Int {
    val _sql: String = "SELECT COUNT(*) FROM command_history"
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

  public override suspend fun deleteById(id: Long) {
    val _sql: String = "DELETE FROM command_history WHERE id = ?"
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

  public override suspend fun clearAll() {
    val _sql: String = "DELETE FROM command_history"
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
