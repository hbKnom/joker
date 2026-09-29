package dev.joker.features.api.core

import android.annotation.SuppressLint
import android.content.ContentValues
import com.tencent.wcdb.database.SQLiteDatabase
import dev.joker.reflekt.reflekt
import dev.joker.reflekt.utils.toClass
import dev.joker.R
import dev.joker.constants.Preferences
import dev.joker.constants.WeChatVersions
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.HostInfo
import dev.joker.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("DiscouragedApi")
object WeDatabaseListenerApi : ApiFeature() {

    override val technicalId = "数据库监听服务"
    override val nameRes = R.string.feature_we_database_listener_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_database_listener_api_description

    fun interface IInsertListener {
        fun onInsert(table: String, values: ContentValues)
    }

    fun interface IUpdateListener {
        fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int)
    }

    fun interface IQueryListener {
        fun onQuery(sql: String): String?
    }

    private const val TAG = "WeDatabaseListenerApi"

    /** 热路径日志折叠窗口：同签名 2 秒内只落一条。 */
    private const val LOG_SIGNATURE_WINDOW_MILLIS = 2000L

    private val insertListeners = CopyOnWriteArrayList<IInsertListener>()
    private val updateListeners = CopyOnWriteArrayList<IUpdateListener>()
    private val queryListeners = CopyOnWriteArrayList<IQueryListener>()

    fun addListener(listener: Any) {
        if (listener is IInsertListener) {
            insertListeners.add(listener)
        }
        if (listener is IUpdateListener) {
            updateListeners.add(listener)
        }
        if (listener is IQueryListener) {
            queryListeners.add(listener)
        }
    }

    fun removeListener(listener: Any) {
        if (listener is IInsertListener) {
            insertListeners.remove(listener)
        }
        if (listener is IUpdateListener) {
            updateListeners.remove(listener)
        }
        if (listener is IQueryListener) {
            queryListeners.remove(listener)
        }
    }

    override fun onEnable() {
        hookDatabaseInsert()
        hookDatabaseUpdate()
        hookDatabaseQuery()
    }

    override fun onDisable() {
        insertListeners.clear()
        updateListeners.clear()
        queryListeners.clear()
    }

    // ==================== 私有辅助方法 ====================

    private fun formatArgs(args: Array<out Any?>): String {
        return args.mapIndexed { index, arg ->
            "arg[$index](${arg?.javaClass?.simpleName ?: "null"})=$arg"
        }.joinToString(", ")
    }

    /** 同一签名（方法 + 表 + SQL 前缀）在窗口内只落一条日志。 */
    private val logSignatures = HashMap<String, Long>()
    private val logSuppressed = java.util.concurrent.atomic.AtomicLong()

    private fun logWithStack(
        methodName: String,
        table: String,
        args: Array<out Any?>,
        result: Any? = null
    ) {
        // 【2026-09-27 修卡顿】这里原来是 `Preferences.verboseLog` —— 它的 getter
        // **每次读都走一次 SharedPreferences/SQLite 查询**，而本函数挂在宿主 rawQuery 的
        // 热路径上（每打开一个聊天页/每次数据库读都会经过）。改用带缓存的
        // [WeLogger.verboseEnabled]（开关变化后再开微信生效），省掉每次一次 SQLite 读。
        if (!WeLogger.verboseEnabled) return

        // 【第 47 轮修卡顿 + 修日志噪音】本函数挂在宿主 rawQuery / update / insert 的
        // **每一次调用**上，旧实现每次都做两件昂贵的事：
        //   ① `WeLogger.currentStackTrace` —— new 出整个 StackTraceElement 数组再拼字符串；
        //   ② 把上千字符的 ContentValues dump 分块写盘。
        // 实机日志（用户开「详细日志 + 宿主日志转发」抓出来的那份）15 分钟就滚出
        // 3000+ 行，7 个文件打满 28MB —— 既是卡顿来源，也是「日志里到处是错」的观感来源。
        // 现在：同签名（方法+表+SQL 前缀）在 [LOG_SIGNATURE_WINDOW_MILLIS] 内只记一条，
        // 且**只有该签名第一次出现时**才附带调用栈 —— 定位调用方够用，成本恒定。
        val sql = (args.getOrNull(0) as? String)?.take(40)
        val signature = "$methodName|$table|$sql"
        val now = System.currentTimeMillis()
        val last = logSignatures[signature]
        if (last != null && now - last < LOG_SIGNATURE_WINDOW_MILLIS) {
            logSuppressed.incrementAndGet()
            return
        }
        val firstSight = last == null
        if (logSignatures.size > 512) logSignatures.clear()   // 兜底：绝不无界增长
        logSignatures[signature] = now

        val argsInfo = if (firstSight) formatArgs(args).take(600) else formatArgs(args).take(200)
        val resultStr = if (result != null) ", result=$result" else ""
        val stackStr = if (firstSight) ", stack=${WeLogger.currentStackTrace}" else ""
        val skipped = logSuppressed.getAndSet(0)

        WeLogger.logChunkedD(
            TAG,
            "[$methodName] table=$table$resultStr, args=[$argsInfo]$stackStr" +
                if (skipped > 0) " (近窗口内已折叠 $skipped 条同类日志)" else ""
        )
    }

    // ==================== Insert Hook ====================

    private fun hookDatabaseInsert() {
        SQLiteDatabase::class.reflekt()
            .firstMethod {
                name = "insertWithOnConflict"
                parameters(String::class, String::class, ContentValues::class, Int::class)
            }.hookAfter {
                try {
                    if (insertListeners.isEmpty()) return@hookAfter

                    val table = args[0] as String
                    val values = args[2] as ContentValues

                    logWithStack("Insert", table, args, result)
                    insertListeners.forEach { it.onInsert(table, values) }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "Insert dispatch failed", e)
                }
            }
    }

    // ==================== Update Hook ====================

    private fun hookDatabaseUpdate() {
        listOf(
            "com.tencent.wcdb.compat.SQLiteDatabase", "com.tencent.wcdb.database.SQLiteDatabase"
        ).forEach { className ->
            className.toClass().reflekt()
                .firstMethod {
                    name = "updateWithOnConflict"
                    parameters(
                        String::class,
                        ContentValues::class,
                        String::class,
                        Array<String>::class,
                        Int::class
                    )
                }
                .hookBefore {
                    try {
                        if (updateListeners.isEmpty()) return@hookBefore

                        val table = args[0] as String
                        val values = args[1] as ContentValues
                        val whereClause = args[2] as String?

                        @Suppress("UNCHECKED_CAST")
                        val whereArgs = args[3] as Array<String>?
                        val conflictAlgorithm = args[4] as Int

                        logWithStack("Update", table, args)

                        updateListeners.forEach { it.onUpdate(table, values, whereClause, whereArgs, conflictAlgorithm) }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "update dispatch failed", e)
                    }
                }
        }
    }

    // ==================== Query Hook ====================

    private fun hookDatabaseQuery() {
        val isPlay = HostInfo.isHostGooglePlay
        val version = HostInfo.versionCode
        val isNewVersion = !isPlay && version >= WeChatVersions.MM_8_0_43 || isPlay && version >= WeChatVersions.MM_8_0_48_PLAY

        if (isNewVersion) {
            hookNewQueryMethod()
        } else {
            hookOldQueryMethod()
        }
    }

    private fun hookNewQueryMethod() {
        com.tencent.wcdb.compat.SQLiteDatabase::class.reflekt()
            .firstMethod {
                name = "rawQuery"
                parameters(String::class, Array<Any>::class)
            }
            .hookBefore {
                try {
                    if (queryListeners.isEmpty()) return@hookBefore

                    val sql = args[0] as? String ?: return@hookBefore
                    var currentSql = sql

                    logWithStack("rawQuery", "N/A", args)

                    queryListeners.forEach { listener ->
                        listener.onQuery(currentSql)?.let { currentSql = it }
                    }

                    if (currentSql != sql) {
                        args[0] = currentSql
                        if (WeLogger.verboseEnabled)
                            WeLogger.d(
                                TAG,
                                "[rawQuery] SQL modified: $sql -> $currentSql, stack=${WeLogger.currentStackTrace}"
                            )
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "New version query dispatch failed", e)
                }
            }
    }

    private fun hookOldQueryMethod() {
        SQLiteDatabase::class.reflekt().firstMethod {
            name = "rawQueryWithFactory"
            parameterCount = 5
        }.hookBefore {
            try {
                if (queryListeners.isEmpty()) return@hookBefore

                val sql = args[1] as? String ?: return@hookBefore
                var currentSql = sql

                logWithStack(
                    "rawQueryWithFactory",
                    args[3] as? String ?: "N/A",
                    args
                )

                queryListeners.forEach { listener ->
                    listener.onQuery(currentSql)?.let { currentSql = it }
                }

                if (currentSql != sql) {
                    args[1] = currentSql
                    if (WeLogger.verboseEnabled)
                        WeLogger.d(
                            TAG,
                            "[rawQueryWithFactory] SQL modified: $sql -> $currentSql, stack=${WeLogger.currentStackTrace}"
                        )
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "Old version query dispatch failed", e)
            }
        }
    }
}
