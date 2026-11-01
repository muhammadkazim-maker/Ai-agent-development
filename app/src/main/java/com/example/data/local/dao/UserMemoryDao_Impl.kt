package com.example.`data`.local.dao

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.example.`data`.local.entity.UserMemory
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
public class UserMemoryDao_Impl(
  __db: RoomDatabase,
) : UserMemoryDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfUserMemory: EntityInsertAdapter<UserMemory>
  init {
    this.__db = __db
    this.__insertAdapterOfUserMemory = object : EntityInsertAdapter<UserMemory>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `user_memory` (`id`,`memory_key`,`memory_value`,`memory_type`,`updated_at`) VALUES (nullif(?, 0),?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: UserMemory) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.memoryKey)
        statement.bindText(3, entity.memoryValue)
        statement.bindText(4, entity.memoryType)
        statement.bindLong(5, entity.updatedAt)
      }
    }
  }

  public override suspend fun upsert(memory: UserMemory): Long = performSuspending(__db, false,
      true) { _connection ->
    val _result: Long = __insertAdapterOfUserMemory.insertAndReturnId(_connection, memory)
    _result
  }

  public override suspend fun insertAll(memories: List<UserMemory>): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfUserMemory.insert(_connection, memories)
  }

  public override fun getAllMemories(): Flow<List<UserMemory>> {
    val _sql: String = "SELECT * FROM user_memory ORDER BY updated_at DESC"
    return createFlow(__db, false, arrayOf("user_memory")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfMemoryKey: Int = getColumnIndexOrThrow(_stmt, "memory_key")
        val _columnIndexOfMemoryValue: Int = getColumnIndexOrThrow(_stmt, "memory_value")
        val _columnIndexOfMemoryType: Int = getColumnIndexOrThrow(_stmt, "memory_type")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<UserMemory> = mutableListOf()
        while (_stmt.step()) {
          val _item: UserMemory
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpMemoryKey: String
          _tmpMemoryKey = _stmt.getText(_columnIndexOfMemoryKey)
          val _tmpMemoryValue: String
          _tmpMemoryValue = _stmt.getText(_columnIndexOfMemoryValue)
          val _tmpMemoryType: String
          _tmpMemoryType = _stmt.getText(_columnIndexOfMemoryType)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = UserMemory(_tmpId,_tmpMemoryKey,_tmpMemoryValue,_tmpMemoryType,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllMemoriesSnapshot(): List<UserMemory> {
    val _sql: String = "SELECT * FROM user_memory ORDER BY updated_at DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfMemoryKey: Int = getColumnIndexOrThrow(_stmt, "memory_key")
        val _columnIndexOfMemoryValue: Int = getColumnIndexOrThrow(_stmt, "memory_value")
        val _columnIndexOfMemoryType: Int = getColumnIndexOrThrow(_stmt, "memory_type")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<UserMemory> = mutableListOf()
        while (_stmt.step()) {
          val _item: UserMemory
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpMemoryKey: String
          _tmpMemoryKey = _stmt.getText(_columnIndexOfMemoryKey)
          val _tmpMemoryValue: String
          _tmpMemoryValue = _stmt.getText(_columnIndexOfMemoryValue)
          val _tmpMemoryType: String
          _tmpMemoryType = _stmt.getText(_columnIndexOfMemoryType)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = UserMemory(_tmpId,_tmpMemoryKey,_tmpMemoryValue,_tmpMemoryType,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getMemoriesByType(type: String): List<UserMemory> {
    val _sql: String = "SELECT * FROM user_memory WHERE memory_type = ? ORDER BY updated_at DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, type)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfMemoryKey: Int = getColumnIndexOrThrow(_stmt, "memory_key")
        val _columnIndexOfMemoryValue: Int = getColumnIndexOrThrow(_stmt, "memory_value")
        val _columnIndexOfMemoryType: Int = getColumnIndexOrThrow(_stmt, "memory_type")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<UserMemory> = mutableListOf()
        while (_stmt.step()) {
          val _item: UserMemory
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpMemoryKey: String
          _tmpMemoryKey = _stmt.getText(_columnIndexOfMemoryKey)
          val _tmpMemoryValue: String
          _tmpMemoryValue = _stmt.getText(_columnIndexOfMemoryValue)
          val _tmpMemoryType: String
          _tmpMemoryType = _stmt.getText(_columnIndexOfMemoryType)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = UserMemory(_tmpId,_tmpMemoryKey,_tmpMemoryValue,_tmpMemoryType,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getMemoryByKey(key: String): UserMemory? {
    val _sql: String =
        "SELECT * FROM user_memory WHERE LOWER(TRIM(memory_key)) = LOWER(TRIM(?)) LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, key)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfMemoryKey: Int = getColumnIndexOrThrow(_stmt, "memory_key")
        val _columnIndexOfMemoryValue: Int = getColumnIndexOrThrow(_stmt, "memory_value")
        val _columnIndexOfMemoryType: Int = getColumnIndexOrThrow(_stmt, "memory_type")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: UserMemory?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpMemoryKey: String
          _tmpMemoryKey = _stmt.getText(_columnIndexOfMemoryKey)
          val _tmpMemoryValue: String
          _tmpMemoryValue = _stmt.getText(_columnIndexOfMemoryValue)
          val _tmpMemoryType: String
          _tmpMemoryType = _stmt.getText(_columnIndexOfMemoryType)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = UserMemory(_tmpId,_tmpMemoryKey,_tmpMemoryValue,_tmpMemoryType,_tmpUpdatedAt)
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
    val _sql: String = "SELECT COUNT(*) FROM user_memory"
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
    val _sql: String = "DELETE FROM user_memory WHERE id = ?"
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

  public override suspend fun deleteByKey(key: String) {
    val _sql: String = "DELETE FROM user_memory WHERE memory_key = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, key)
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
