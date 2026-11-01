package com.example.`data`.local.dao

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.example.`data`.local.entity.FailedCommandLog
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
public class FailedCommandLogDao_Impl(
  __db: RoomDatabase,
) : FailedCommandLogDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfFailedCommandLog: EntityInsertAdapter<FailedCommandLog>
  init {
    this.__db = __db
    this.__insertAdapterOfFailedCommandLog = object : EntityInsertAdapter<FailedCommandLog>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `failed_command_log` (`id`,`raw_utterance`,`normalized_utterance`,`detected_language`,`failure_reason`,`suggested_correction`,`resolved`,`timestamp`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: FailedCommandLog) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.rawUtterance)
        statement.bindText(3, entity.normalizedUtterance)
        statement.bindText(4, entity.detectedLanguage)
        statement.bindText(5, entity.failureReason)
        statement.bindText(6, entity.suggestedCorrection)
        val _tmp: Int = if (entity.resolved) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        statement.bindLong(8, entity.timestamp)
      }
    }
  }

  public override suspend fun insert(entry: FailedCommandLog): Long = performSuspending(__db, false,
      true) { _connection ->
    val _result: Long = __insertAdapterOfFailedCommandLog.insertAndReturnId(_connection, entry)
    _result
  }

  public override fun getAllFailedLogs(): Flow<List<FailedCommandLog>> {
    val _sql: String = "SELECT * FROM failed_command_log ORDER BY timestamp DESC"
    return createFlow(__db, false, arrayOf("failed_command_log")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfRawUtterance: Int = getColumnIndexOrThrow(_stmt, "raw_utterance")
        val _columnIndexOfNormalizedUtterance: Int = getColumnIndexOrThrow(_stmt,
            "normalized_utterance")
        val _columnIndexOfDetectedLanguage: Int = getColumnIndexOrThrow(_stmt, "detected_language")
        val _columnIndexOfFailureReason: Int = getColumnIndexOrThrow(_stmt, "failure_reason")
        val _columnIndexOfSuggestedCorrection: Int = getColumnIndexOrThrow(_stmt,
            "suggested_correction")
        val _columnIndexOfResolved: Int = getColumnIndexOrThrow(_stmt, "resolved")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<FailedCommandLog> = mutableListOf()
        while (_stmt.step()) {
          val _item: FailedCommandLog
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpRawUtterance: String
          _tmpRawUtterance = _stmt.getText(_columnIndexOfRawUtterance)
          val _tmpNormalizedUtterance: String
          _tmpNormalizedUtterance = _stmt.getText(_columnIndexOfNormalizedUtterance)
          val _tmpDetectedLanguage: String
          _tmpDetectedLanguage = _stmt.getText(_columnIndexOfDetectedLanguage)
          val _tmpFailureReason: String
          _tmpFailureReason = _stmt.getText(_columnIndexOfFailureReason)
          val _tmpSuggestedCorrection: String
          _tmpSuggestedCorrection = _stmt.getText(_columnIndexOfSuggestedCorrection)
          val _tmpResolved: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfResolved).toInt()
          _tmpResolved = _tmp != 0
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item =
              FailedCommandLog(_tmpId,_tmpRawUtterance,_tmpNormalizedUtterance,_tmpDetectedLanguage,_tmpFailureReason,_tmpSuggestedCorrection,_tmpResolved,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getUnresolvedFailedLogs(): Flow<List<FailedCommandLog>> {
    val _sql: String = "SELECT * FROM failed_command_log WHERE resolved = 0 ORDER BY timestamp DESC"
    return createFlow(__db, false, arrayOf("failed_command_log")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfRawUtterance: Int = getColumnIndexOrThrow(_stmt, "raw_utterance")
        val _columnIndexOfNormalizedUtterance: Int = getColumnIndexOrThrow(_stmt,
            "normalized_utterance")
        val _columnIndexOfDetectedLanguage: Int = getColumnIndexOrThrow(_stmt, "detected_language")
        val _columnIndexOfFailureReason: Int = getColumnIndexOrThrow(_stmt, "failure_reason")
        val _columnIndexOfSuggestedCorrection: Int = getColumnIndexOrThrow(_stmt,
            "suggested_correction")
        val _columnIndexOfResolved: Int = getColumnIndexOrThrow(_stmt, "resolved")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<FailedCommandLog> = mutableListOf()
        while (_stmt.step()) {
          val _item: FailedCommandLog
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpRawUtterance: String
          _tmpRawUtterance = _stmt.getText(_columnIndexOfRawUtterance)
          val _tmpNormalizedUtterance: String
          _tmpNormalizedUtterance = _stmt.getText(_columnIndexOfNormalizedUtterance)
          val _tmpDetectedLanguage: String
          _tmpDetectedLanguage = _stmt.getText(_columnIndexOfDetectedLanguage)
          val _tmpFailureReason: String
          _tmpFailureReason = _stmt.getText(_columnIndexOfFailureReason)
          val _tmpSuggestedCorrection: String
          _tmpSuggestedCorrection = _stmt.getText(_columnIndexOfSuggestedCorrection)
          val _tmpResolved: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfResolved).toInt()
          _tmpResolved = _tmp != 0
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item =
              FailedCommandLog(_tmpId,_tmpRawUtterance,_tmpNormalizedUtterance,_tmpDetectedLanguage,_tmpFailureReason,_tmpSuggestedCorrection,_tmpResolved,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun markResolved(id: Long, correction: String) {
    val _sql: String =
        "UPDATE failed_command_log SET resolved = 1, suggested_correction = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, correction)
        _argIndex = 2
        _stmt.bindLong(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteById(id: Long) {
    val _sql: String = "DELETE FROM failed_command_log WHERE id = ?"
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
    val _sql: String = "DELETE FROM failed_command_log"
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
