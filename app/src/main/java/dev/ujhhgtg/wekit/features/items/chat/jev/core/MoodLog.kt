package dev.ujhhgtg.wekit.features.items.chat.jev.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Each process keeps its own journal; exporting in WeChat never needs the module bridge. */
object MoodLog {
    private const val TAG = "WeChatMood"
    private var journal: DiagnosticJournal? = null
    private var early = ""
    private val secrets = mutableSetOf<String>()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    var frameworkSink: ((String) -> Unit)? = null

    /**
     * 「详细日志」门闩：诊断性的 INFO 只在用户打开详细日志时才落盘。
     *
     * 每写一行都要同步落一次磁盘，而潜语的热路径（每次 bind / 每次绘制 / 每拍重试）
     * 到处都可能想记一行 —— 这些诊断必须挂在门闩后面，否则日志本身就是卡顿源。
     * 异常（W/E）不受门闩约束：它们已经被「同类只记一行」去重，属于必须留下的证据。
     */
    val verbose: Boolean
        get() = runCatching { dev.ujhhgtg.wekit.utils.WeLogger.verboseEnabled }.getOrDefault(false)

    @Synchronized fun protect(secret: String) { if (secret.isNotBlank()) secrets.add(secret) }
    @Synchronized fun sanitize(text: String) = DiagnosticText.sanitize(text, secrets)

    @Synchronized fun init(context: Context) {
        if (journal != null) return
        journal = DiagnosticJournal(File(context.filesDir, "mood.log"))
        if (early.isNotBlank()) journal?.append(early)
        early = ""
        i("PROCESS_START package=${context.packageName} uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} module=${dev.ujhhgtg.wekit.BuildConfig.VERSION_NAME} android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
    }

    fun i(message: String) = write("I", message)
    fun w(message: String) = write("W", message)
    fun e(message: String, throwable: Throwable? = null) =
        write("E", if (throwable == null) message else DiagnosticText.failure(message, throwable))
    fun dump(title: String, body: String) = write("D", "===== $title =====\n$body\n===== /$title =====")

    @Synchronized private fun write(level: String, message: String) {
        val safe = sanitize(message).take(32 * 1024)
        val line = "${format.format(Date())} [$level] $safe\n"
        runCatching { Log.println(if (level == "E") Log.ERROR else if (level == "W") Log.WARN else Log.INFO, TAG, safe) }
        journal?.append(line) ?: run { early = (early + line).takeLast(128 * 1024) }
        val lifecycleEvent = listOf("PROCESS_START", "ENVIRONMENT", "BRIDGE_RECOVERED", "SYNC_RECEIVED", "SWITCH_SAVED")
            .any(message::startsWith)
        if (level == "E" || level == "W" || lifecycleEvent) {
            runCatching { frameworkSink?.invoke("$TAG $line") }
        }
    }

    @Synchronized fun read(): String = sanitize(buildString {
        journal?.diskFailure?.let { appendLine("[LOG_DISK_FAILURE] $it；以下保留内存日志，请在关闭微信前导出。") }
        append(journal?.read() ?: early)
    })
}
