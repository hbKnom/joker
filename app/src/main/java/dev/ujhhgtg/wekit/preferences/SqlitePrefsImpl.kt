package dev.ujhhgtg.wekit.preferences

import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * SQLite-backed [WePrefs].
 *
 * Upstream 09-19 replaced the MMKV key/value store with a single SQLite database
 * (`wekit.sqlite`) so module settings, structured data and the agent database share one file.
 * This keeps the exact [WePrefs] contract, so every existing feature keeps working; values are
 * persisted into a `prefs` table instead of MMKV.
 *
 * WAL is enabled because the module is loaded in several WeChat processes at once.
 */
class SqlitePrefsImpl(@Suppress("UNUSED_PARAMETER") name: String) : WePrefs() {

    private val lock = Any()

    private val db: SQLiteDatabase by lazy {
        KnownPaths.moduleData.toFile().mkdirs()
        val file = KnownPaths.moduleData.resolve(DB_FILE_NAME).toFile()
        SQLiteDatabase.openOrCreateDatabase(file, null).also { database ->
            runCatching { database.enableWriteAheadLogging() }
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE (" +
                    "key TEXT PRIMARY KEY NOT NULL, " +
                    "type INTEGER NOT NULL, " +
                    "value BLOB)",
            )
        }
    }

    private fun readRaw(key: String): Pair<Int, ByteArray?>? = synchronized(lock) {
        runCatching {
            db.query(
                TABLE,
                arrayOf("type", "value"),
                "key = ?",
                arrayOf(key),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow("type"))
                    val bytes = if (cursor.isNull(cursor.getColumnIndexOrThrow("value"))) {
                        null
                    } else {
                        cursor.getBlob(cursor.getColumnIndexOrThrow("value"))
                    }
                    type to bytes
                } else null
            }
        }.onFailure { WeLogger.e(TAG, "prefs read failed for $key", it) }.getOrNull()
    }

    private fun writeRaw(key: String, type: Int, value: ByteArray?) = synchronized(lock) {
        runCatching {
            val values = ContentValues().apply {
                put("key", key)
                put("type", type)
                if (value == null) putNull("value") else put("value", value)
            }
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }.onFailure { WeLogger.e(TAG, "prefs write failed for $key", it) }
        Unit
    }

    private fun decode(type: Int, bytes: ByteArray?): Any? = when (type) {
        TYPE_NULL -> null
        TYPE_BOOL -> if (bytes == null) null else bytes.isNotEmpty() && bytes[0].toInt() != 0
        TYPE_INT -> bytes?.let { String(it, Charsets.UTF_8).toIntOrNull() }
        TYPE_LONG -> bytes?.let { String(it, Charsets.UTF_8).toLongOrNull() }
        TYPE_FLOAT -> bytes?.let { String(it, Charsets.UTF_8).toFloatOrNull() }
        TYPE_STRING -> bytes?.toString(Charsets.UTF_8)
        // 空集合会被存成零长度 blob，而 "".split(SEP) == [""]：不滤空串就会读回 {""}，
        // 让「清空生效聊天 / 关掉全部特性」变成永远匹配不上任何人的死状态（点歌、TTS 播报、分析项都中招）。
        TYPE_STRING_SET -> bytes?.toString(Charsets.UTF_8)?.split(SEP)?.filter { it.isNotEmpty() }?.toSet()
        TYPE_BYTES -> bytes
        TYPE_SERIALIZABLE -> bytes?.let { raw ->
            runCatching { ObjectInputStream(ByteArrayInputStream(raw)).readObject() }
                .onFailure { WeLogger.e(TAG, "failed when getting Serializable object", it) }
                .getOrNull()
        }

        else -> null
    }

    private fun encode(value: Any): Pair<Int, ByteArray?> = when (value) {
        is Float, is Double -> TYPE_FLOAT to value.toFloat().toString().toByteArray(Charsets.UTF_8)
        is Long -> TYPE_LONG to value.toString().toByteArray(Charsets.UTF_8)
        is Int -> TYPE_INT to value.toString().toByteArray(Charsets.UTF_8)
        is Boolean -> TYPE_BOOL to byteArrayOf(if (value) 1 else 0)
        is String -> TYPE_STRING to value.toByteArray(Charsets.UTF_8)
        is Set<*> -> TYPE_STRING_SET to
            value.filterIsInstance<String>().joinToString(SEP).toByteArray(Charsets.UTF_8)

        is ByteArray -> TYPE_BYTES to value
        is Array<*> if value.isArrayOf<String>() -> TYPE_STRING_SET to
            value.filterIsInstance<String>().joinToString(SEP).toByteArray(Charsets.UTF_8)

        is Serializable -> {
            val out = ByteArrayOutputStream()
            ObjectOutputStream(out).writeObject(value)
            TYPE_SERIALIZABLE to out.toByteArray()
        }

        else -> throw IllegalArgumentException("unsupported type ${value::class}")
    }

    override fun getAll(): Map<String, *> = synchronized(lock) {
        val map = HashMap<String, Any?>()
        runCatching {
            db.query(TABLE, arrayOf("key", "type", "value"), null, null, null, null, null)
                .use { cursor: Cursor ->
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(cursor.getColumnIndexOrThrow("key")) ?: continue
                        val type = cursor.getInt(cursor.getColumnIndexOrThrow("type"))
                        val bytes = if (cursor.isNull(cursor.getColumnIndexOrThrow("value"))) {
                            null
                        } else {
                            cursor.getBlob(cursor.getColumnIndexOrThrow("value"))
                        }
                        map[key] = decode(type, bytes)
                    }
                }
        }.onFailure { WeLogger.e(TAG, "prefs getAll failed", it) }
        map
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null) return defValue
        val raw = readRaw(key) ?: return defValue
        return if (raw.first == TYPE_STRING) decode(TYPE_STRING, raw.second) as? String ?: defValue
        else defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val raw = readRaw(key) ?: return defValues
        if (raw.first != TYPE_STRING_SET) return defValues
        return decode(TYPE_STRING_SET, raw.second) as? Set<String> ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int =
        (readRaw(key)?.let { decode(it.first, it.second) } as? Number)?.toInt() ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (readRaw(key)?.let { decode(it.first, it.second) } as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (readRaw(key)?.let { decode(it.first, it.second) } as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        readRaw(key)?.let { decode(it.first, it.second) } as? Boolean ?: defValue

    override fun contains(key: String): Boolean = readRaw(key) != null

    override fun getObject(key: String): Any? = readRaw(key)?.let { decode(it.first, it.second) }

    override fun putObject(key: String, obj: Any): WePrefs {
        val (type, bytes) = encode(obj)
        writeRaw(key, type, bytes)
        return this
    }

    override fun putString(key: String, value: String?): WePrefs {
        writeRaw(key, if (value == null) TYPE_NULL else TYPE_STRING, value?.toByteArray(Charsets.UTF_8))
        return this
    }

    override fun putStringSet(key: String, values: Set<String>?): WePrefs {
        writeRaw(
            key,
            if (values == null) TYPE_NULL else TYPE_STRING_SET,
            values?.joinToString(SEP)?.toByteArray(Charsets.UTF_8),
        )
        return this
    }

    override fun putInt(key: String, value: Int): WePrefs {
        writeRaw(key, TYPE_INT, value.toString().toByteArray(Charsets.UTF_8))
        return this
    }

    override fun putLong(key: String, value: Long): WePrefs {
        writeRaw(key, TYPE_LONG, value.toString().toByteArray(Charsets.UTF_8))
        return this
    }

    override fun putFloat(key: String, value: Float): WePrefs {
        writeRaw(key, TYPE_FLOAT, value.toString().toByteArray(Charsets.UTF_8))
        return this
    }

    override fun putBoolean(key: String, value: Boolean): WePrefs {
        writeRaw(key, TYPE_BOOL, byteArrayOf(if (value) 1 else 0))
        return this
    }

    override fun getBytesOrDefault(key: String, defValue: ByteArray): ByteArray =
        getBytes(key, defValue) ?: defValue

    override fun getBytes(key: String, defValue: ByteArray?): ByteArray? {
        val raw = readRaw(key) ?: return defValue
        return if (raw.first == TYPE_BYTES) raw.second ?: defValue else defValue
    }

    override fun putBytes(key: String, value: ByteArray) {
        writeRaw(key, TYPE_BYTES, value)
    }

    override fun remove(key: String): WePrefs {
        synchronized(lock) {
            runCatching { db.delete(TABLE, "key = ?", arrayOf(key)) }
                .onFailure { WeLogger.e(TAG, "prefs remove failed for $key", it) }
        }
        return this
    }

    override fun clear(): WePrefs {
        synchronized(lock) {
            runCatching { db.delete(TABLE, null, null) }
                .onFailure { WeLogger.e(TAG, "prefs clear failed", it) }
        }
        return this
    }

    override fun save(): Unit { commit() }

    override fun commit(): Boolean = true

    override fun apply() = Unit

    override val isReadOnly: Boolean = false
    override val isPersistent: Boolean = true

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    /**
     * Writes a consistent copy of the prefs database into [target] (used by the full-backup
     * exporter). Uses `VACUUM INTO` so the copy is transactional without closing the live DB.
     */
    fun snapshotInto(target: java.io.File) {
        synchronized(lock) {
            target.parentFile?.mkdirs()
            runCatching {
                db.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath))
            }.recoverCatching {
                // Older SQLite without VACUUM INTO: checkpoint, then copy the file bytes.
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                java.io.File(db.path).copyTo(target, overwrite = true)
            }.getOrElse {
                WeLogger.e(TAG, "failed to snapshot prefs database", it)
            }
        }
    }

    companion object {
        const val DB_FILE_NAME = "wekit.sqlite"
        const val TABLE = "prefs"

        private const val TAG = "SqlitePrefsImpl"
        private const val SEP = "\u0000"

        // Type tags mirror MmkvPrefsImpl's encoding so a dump stays comparable.
        private const val TYPE_NULL = 0x80 + 1
        private const val TYPE_BOOL = 0x80 + 2
        private const val TYPE_INT = 0x80 + 4
        private const val TYPE_LONG = 0x80 + 6
        private const val TYPE_FLOAT = 0x80 + 7
        private const val TYPE_STRING = 0x80 + 31
        private const val TYPE_STRING_SET = 0x80 + 32
        private const val TYPE_BYTES = 0x80 + 33
        private const val TYPE_SERIALIZABLE = 0x80 + 41
    }
}
