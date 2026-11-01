package com.example.`data`.local.dao

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.example.`data`.local.entity.PersonalVocabulary
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
public class PersonalVocabularyDao_Impl(
  __db: RoomDatabase,
) : PersonalVocabularyDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfPersonalVocabulary: EntityInsertAdapter<PersonalVocabulary>
  init {
    this.__db = __db
    this.__insertAdapterOfPersonalVocabulary = object : EntityInsertAdapter<PersonalVocabulary>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `personal_vocabulary` (`id`,`spoken_phrase`,`canonical_meaning`,`urdu_script`,`category`,`mapped_action`,`mapped_target`,`usage_count`,`created_at`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: PersonalVocabulary) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.spokenPhrase)
        statement.bindText(3, entity.canonicalMeaning)
        statement.bindText(4, entity.urduScript)
        statement.bindText(5, entity.category)
        statement.bindText(6, entity.mappedAction)
        statement.bindText(7, entity.mappedTarget)
        statement.bindLong(8, entity.usageCount.toLong())
        statement.bindLong(9, entity.createdAt)
      }
    }
  }

  public override suspend fun insert(entry: PersonalVocabulary): Long = performSuspending(__db,
      false, true) { _connection ->
    val _result: Long = __insertAdapterOfPersonalVocabulary.insertAndReturnId(_connection, entry)
    _result
  }

  public override suspend fun insertAll(entries: List<PersonalVocabulary>): Unit =
      performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfPersonalVocabulary.insert(_connection, entries)
  }

  public override fun getAllVocabulary(): Flow<List<PersonalVocabulary>> {
    val _sql: String = "SELECT * FROM personal_vocabulary ORDER BY created_at DESC"
    return createFlow(__db, false, arrayOf("personal_vocabulary")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfSpokenPhrase: Int = getColumnIndexOrThrow(_stmt, "spoken_phrase")
        val _columnIndexOfCanonicalMeaning: Int = getColumnIndexOrThrow(_stmt, "canonical_meaning")
        val _columnIndexOfUrduScript: Int = getColumnIndexOrThrow(_stmt, "urdu_script")
        val _columnIndexOfCategory: Int = getColumnIndexOrThrow(_stmt, "category")
        val _columnIndexOfMappedAction: Int = getColumnIndexOrThrow(_stmt, "mapped_action")
        val _columnIndexOfMappedTarget: Int = getColumnIndexOrThrow(_stmt, "mapped_target")
        val _columnIndexOfUsageCount: Int = getColumnIndexOrThrow(_stmt, "usage_count")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: MutableList<PersonalVocabulary> = mutableListOf()
        while (_stmt.step()) {
          val _item: PersonalVocabulary
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpSpokenPhrase: String
          _tmpSpokenPhrase = _stmt.getText(_columnIndexOfSpokenPhrase)
          val _tmpCanonicalMeaning: String
          _tmpCanonicalMeaning = _stmt.getText(_columnIndexOfCanonicalMeaning)
          val _tmpUrduScript: String
          _tmpUrduScript = _stmt.getText(_columnIndexOfUrduScript)
          val _tmpCategory: String
          _tmpCategory = _stmt.getText(_columnIndexOfCategory)
          val _tmpMappedAction: String
          _tmpMappedAction = _stmt.getText(_columnIndexOfMappedAction)
          val _tmpMappedTarget: String
          _tmpMappedTarget = _stmt.getText(_columnIndexOfMappedTarget)
          val _tmpUsageCount: Int
          _tmpUsageCount = _stmt.getLong(_columnIndexOfUsageCount).toInt()
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item =
              PersonalVocabulary(_tmpId,_tmpSpokenPhrase,_tmpCanonicalMeaning,_tmpUrduScript,_tmpCategory,_tmpMappedAction,_tmpMappedTarget,_tmpUsageCount,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllVocabularySnapshot(): List<PersonalVocabulary> {
    val _sql: String =
        "SELECT * FROM personal_vocabulary ORDER BY usage_count DESC, created_at DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfSpokenPhrase: Int = getColumnIndexOrThrow(_stmt, "spoken_phrase")
        val _columnIndexOfCanonicalMeaning: Int = getColumnIndexOrThrow(_stmt, "canonical_meaning")
        val _columnIndexOfUrduScript: Int = getColumnIndexOrThrow(_stmt, "urdu_script")
        val _columnIndexOfCategory: Int = getColumnIndexOrThrow(_stmt, "category")
        val _columnIndexOfMappedAction: Int = getColumnIndexOrThrow(_stmt, "mapped_action")
        val _columnIndexOfMappedTarget: Int = getColumnIndexOrThrow(_stmt, "mapped_target")
        val _columnIndexOfUsageCount: Int = getColumnIndexOrThrow(_stmt, "usage_count")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: MutableList<PersonalVocabulary> = mutableListOf()
        while (_stmt.step()) {
          val _item: PersonalVocabulary
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpSpokenPhrase: String
          _tmpSpokenPhrase = _stmt.getText(_columnIndexOfSpokenPhrase)
          val _tmpCanonicalMeaning: String
          _tmpCanonicalMeaning = _stmt.getText(_columnIndexOfCanonicalMeaning)
          val _tmpUrduScript: String
          _tmpUrduScript = _stmt.getText(_columnIndexOfUrduScript)
          val _tmpCategory: String
          _tmpCategory = _stmt.getText(_columnIndexOfCategory)
          val _tmpMappedAction: String
          _tmpMappedAction = _stmt.getText(_columnIndexOfMappedAction)
          val _tmpMappedTarget: String
          _tmpMappedTarget = _stmt.getText(_columnIndexOfMappedTarget)
          val _tmpUsageCount: Int
          _tmpUsageCount = _stmt.getLong(_columnIndexOfUsageCount).toInt()
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item =
              PersonalVocabulary(_tmpId,_tmpSpokenPhrase,_tmpCanonicalMeaning,_tmpUrduScript,_tmpCategory,_tmpMappedAction,_tmpMappedTarget,_tmpUsageCount,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun findByPhrase(phrase: String): PersonalVocabulary? {
    val _sql: String =
        "SELECT * FROM personal_vocabulary WHERE LOWER(TRIM(spoken_phrase)) = LOWER(TRIM(?)) LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, phrase)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfSpokenPhrase: Int = getColumnIndexOrThrow(_stmt, "spoken_phrase")
        val _columnIndexOfCanonicalMeaning: Int = getColumnIndexOrThrow(_stmt, "canonical_meaning")
        val _columnIndexOfUrduScript: Int = getColumnIndexOrThrow(_stmt, "urdu_script")
        val _columnIndexOfCategory: Int = getColumnIndexOrThrow(_stmt, "category")
        val _columnIndexOfMappedAction: Int = getColumnIndexOrThrow(_stmt, "mapped_action")
        val _columnIndexOfMappedTarget: Int = getColumnIndexOrThrow(_stmt, "mapped_target")
        val _columnIndexOfUsageCount: Int = getColumnIndexOrThrow(_stmt, "usage_count")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _result: PersonalVocabulary?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpSpokenPhrase: String
          _tmpSpokenPhrase = _stmt.getText(_columnIndexOfSpokenPhrase)
          val _tmpCanonicalMeaning: String
          _tmpCanonicalMeaning = _stmt.getText(_columnIndexOfCanonicalMeaning)
          val _tmpUrduScript: String
          _tmpUrduScript = _stmt.getText(_columnIndexOfUrduScript)
          val _tmpCategory: String
          _tmpCategory = _stmt.getText(_columnIndexOfCategory)
          val _tmpMappedAction: String
          _tmpMappedAction = _stmt.getText(_columnIndexOfMappedAction)
          val _tmpMappedTarget: String
          _tmpMappedTarget = _stmt.getText(_columnIndexOfMappedTarget)
          val _tmpUsageCount: Int
          _tmpUsageCount = _stmt.getLong(_columnIndexOfUsageCount).toInt()
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _result =
              PersonalVocabulary(_tmpId,_tmpSpokenPhrase,_tmpCanonicalMeaning,_tmpUrduScript,_tmpCategory,_tmpMappedAction,_tmpMappedTarget,_tmpUsageCount,_tmpCreatedAt)
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
    val _sql: String = "SELECT COUNT(*) FROM personal_vocabulary"
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

  public override suspend fun incrementUsage(id: Long) {
    val _sql: String = "UPDATE personal_vocabulary SET usage_count = usage_count + 1 WHERE id = ?"
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

  public override suspend fun deleteById(id: Long) {
    val _sql: String = "DELETE FROM personal_vocabulary WHERE id = ?"
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

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
