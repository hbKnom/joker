package dev.ujhhgtg.wekit.utils

import android.util.Log
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.div
import kotlin.math.min

object WeLogger {

    private const val TAG = BuildConfig.TAG

    private const val CHUNK_SIZE = 4000
    private const val MAX_CHUNKS = 200
    private const val QUEUE_CAPACITY = 2048
    private const val RESERVED_IMPORTANT_CAPACITY = 128
    private const val BATCH_SIZE = 64
    private const val FLUSH_TIMEOUT_MILLIS = 3000L

    /**
     * Per-day rotated log cap. A single daily file may otherwise grow unbounded
     * (a cloned WeChat with host-log redirection can produce tens of MB in hours),
     * blowing up storage and making the in-app log viewer unable to render.
     * When [MAX_FILE_BYTES] is exceeded we rotate to `wekit-<date>.<seq>.log`
     * siblings and keep the newest [MAX_ROTATED_FILES] files.
     */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024   // 4 MiB per file
    private const val MAX_ROTATED_FILES = 6               // keep the newest 6 files per day

    /**
     * `File.length()` 是一次 stat 系统调用；宿主日志被重定向时每秒可能产生几百条记录，
     * 每条都查一次文件大小纯属浪费。这里按条数抽样检查：上限最多被超出
     * [SIZE_CHECK_INTERVAL] 条记录的量级（几十 KB），对存储与日志查看器没有影响。
     */
    private const val SIZE_CHECK_INTERVAL = 64

    /** 「详细日志」开关的缓存有效期，见 [verboseEnabled]。 */
    private const val VERBOSE_TTL_MILLIS = 1000L

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private sealed interface WriteTask {
        data class Record(
            val level: String,
            val tag: String?,
            val msg: String,
            val throwable: Throwable?,
            val timestamp: LocalDateTime,
        ) : WriteTask

        class Flush(val completed: CountDownLatch) : WriteTask
    }

    /**
     * A bounded queue keeps logging fire-and-forget even if storage is temporarily slow. A
     * dropped-record counter is emitted by the writer once it catches up, so loss is visible.
     */
    private val writeQueue = ArrayBlockingQueue<WriteTask>(QUEUE_CAPACITY, true)
    private val droppedRecords = AtomicLong()
    private val writerThread = Thread(::runWriter, "WeKit-Logger").apply {
        isDaemon = true
        start()
    }

    private var writer: FileWriter? = null
    private var currentLogDate: LocalDate? = null
    // Name of the file `writer` is appending to (e.g. wekit-2026-09-07.log or a .N sibling).
    private var currentLogFile: File? = null

    // ========== File Logging Internals ==========

    /** 距上次真正 stat 文件大小已写入的记录数，见 [SIZE_CHECK_INTERVAL]。 */
    private var recordsSinceSizeCheck = 0

    /** 「详细日志」开关的缓存值/时间戳，见 [verboseEnabled]。 */
    private var verboseCachedAt = 0L
    private var verboseCached = false

    /**
     * 「详细日志」开关（设置里的 `verbose_log`）的缓存读。
     *
     * 为什么需要缓存：它在**每一条消息 bind、每一次渲染**里被判断（高频率日志的门闩），
     * 而 `WePrefs` 的读是一次真正的 SQLite 查询 —— 拿它做热路径门闩，门闩本身就成了瓶颈。
     * 这里按 [VERBOSE_TTL_MILLIS] 缓存，开关变更最多晚一秒生效，对诊断无影响。
     */
    val verboseEnabled: Boolean
        get() {
            val now = System.currentTimeMillis()
            if (now - verboseCachedAt < VERBOSE_TTL_MILLIS) return verboseCached
            verboseCached = try {
                WePrefs.getBoolOrDef(Preferences.VERBOSE_LOG, false)
            } catch (_: Throwable) {
                false
            }
            verboseCachedAt = now
            return verboseCached
        }

    private fun getOrRotateWriter(logDate: LocalDate): FileWriter? {
        if (writer != null && currentLogDate == logDate && currentLogFile != null) {
            if (recordsSinceSizeCheck < SIZE_CHECK_INTERVAL) {
                recordsSinceSizeCheck++
                return writer
            }
            recordsSinceSizeCheck = 0
            if (currentLogFile!!.length() < MAX_FILE_BYTES) {
                return writer
            }
        }

        // A writer for the same date exists but the current file exceeded the cap:
        // rotate it (rename to a numbered sibling) and start a fresh file. Also cover
        // the date-change case by closing any previous writer.
        if (currentLogFile != null && currentLogDate == logDate && currentLogFile!!.length() >= MAX_FILE_BYTES) {
            runCatching { writer?.flush() }
            rotateOversized(logDate)
            writer?.runCatching { close() }
            writer = null
        } else {
            writer?.runCatching { close() }
            writer = null
            currentLogDate = null
        }

        val logsDir = runCatching {
            (KnownPaths.moduleData / "logs").createDirsSafe()
        }.getOrNull() ?: return null

        // Clean up logs older than 3 days during rotation/initialization
        deleteOldLogs(logsDir)

        val logPath = logsDir.resolve("wekit-${dateFmt.format(logDate)}.log")

        return runCatching {
            FileWriter(logPath.toFile(), true).also {
                writer = it
                currentLogDate = logDate
                currentLogFile = logPath.toFile()
            }
        }.getOrNull()
    }

    /**
     * When the current daily log exceeds [MAX_FILE_BYTES], rename it to
     * `wekit-<date>.<seq>.log` (next free seq) and prune siblings above
     * [MAX_ROTATED_FILES] so a runaway day cannot eat the disk or the viewer.
     */
    private fun rotateOversized(logDate: LocalDate) {
        val f = currentLogFile ?: return
        runCatching {
            var seq = 1
            val dir = f.parentFile
            while (File(dir, "wekit-${dateFmt.format(logDate)}.$seq.log").exists()) seq++
            val target = File(dir, "wekit-${dateFmt.format(logDate)}.$seq.log")
            if (f.renameTo(target)) {
                // prune oldest siblings of the same day past the cap
                val sameDay = dir.listFiles { _, name ->
                    name.startsWith("wekit-${dateFmt.format(logDate)}.")
                } ?: return@runCatching
                sameDay.sortedByDescending { it.lastModified() }
                    .drop(MAX_ROTATED_FILES)
                    .forEach { it.delete() }
            }
        }
    }

    private fun deleteOldLogs(logsDir: java.nio.file.Path) {
        runCatching {
            val thresholdDate = LocalDate.now().minusDays(3)
            // Match both the base daily file and rotated siblings: wekit-YYYY-MM-DD.log
            // and wekit-YYYY-MM-DD.<seq>.log
            val logFileRegex = Regex("""wekit-(\d{4}-\d{2}-\d{2})(\.\d+)?\.log""")

            logsDir.toFile().listFiles()?.forEach { file ->
                val match = logFileRegex.matchEntire(file.name)
                if (match != null) {
                    val dateStr = match.groupValues[1]
                    val fileDate = runCatching { LocalDate.parse(dateStr, dateFmt) }.getOrNull()

                    // If the log file date is older than 3 days ago, delete it
                    if (fileDate != null && fileDate.isBefore(thresholdDate)) {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun runWriter() {
        val batch = ArrayList<WriteTask>(BATCH_SIZE)

        while (!Thread.currentThread().isInterrupted) {
            val first = try {
                writeQueue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            batch += first
            writeQueue.drainTo(batch, BATCH_SIZE - 1)

            var hasWrites = false
            batch.forEach { task ->
                when (task) {
                    is WriteTask.Record -> {
                        writeDroppedNotice(task.timestamp)
                        val written = writeRecord(task)
                        hasWrites = written || hasWrites
                        if (written && (task.level == "E" || task.level == "W" || task.level == "A")) {
                            flushWriter()
                            hasWrites = false
                        }
                    }

                    is WriteTask.Flush -> {
                        try {
                            writeDroppedNotice(LocalDateTime.now())
                            flushWriter()
                        } finally {
                            task.completed.countDown()
                        }
                        hasWrites = false
                    }
                }
            }

            if (hasWrites) flushWriter()
            batch.clear()
        }
    }

    private fun writeRecord(record: WriteTask.Record): Boolean {
        val w = getOrRotateWriter(record.timestamp.toLocalDate()) ?: return false
        return runCatching {
            w.write(buildString {
                append(timestampFmt.format(record.timestamp))
                append(' ')
                append(record.level)
                append('/')
                append(TAG)
                append(' ')
                append(record.tag)
                append(": ")
                append(record.msg)
                if (record.throwable != null) {
                    append('\n')
                    append(Log.getStackTraceString(record.throwable))
                }
            })
            w.write('\n'.code)
            true
        }.getOrElse {
            Log.e(TAG, "failed to write log file", it)
            false
        }
    }

    private fun writeDroppedNotice(timestamp: LocalDateTime) {
        val count = droppedRecords.getAndSet(0)
        if (count == 0L) return

        writeRecord(
            WriteTask.Record(
                level = "W",
                tag = "WeLogger",
                msg = "dropped $count log record(s) because the async queue was full or reserved for important logs",
                throwable = null,
                timestamp = timestamp,
            )
        )
    }

    private fun flushWriter() {
        writer?.runCatching { flush() }
    }

    private fun enqueue(record: WriteTask.Record) {
        val isImportant = record.level == "E" || record.level == "W" || record.level == "A"
        val hasRoom = isImportant || writeQueue.remainingCapacity() > RESERVED_IMPORTANT_CAPACITY
        if (!hasRoom || !writeQueue.offer(record)) {
            droppedRecords.incrementAndGet()
        }
    }

    /**
     * Wait for all records currently queued, then flush the active writer. This is intentionally
     * blocking because it is used at explicit synchronization points such as crash handling and
     * before the log viewer reads the current file; normal log calls never wait for the writer.
     */
    fun flush() {
        if (Thread.currentThread() === writerThread) {
            writeDroppedNotice(LocalDateTime.now())
            flushWriter()
            return
        }

        val completed = CountDownLatch(1)
        val barrier = WriteTask.Flush(completed)
        val enqueued = try {
            writeQueue.offer(barrier, FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            Log.w(TAG, "timed out while enqueueing log flush barrier")
            return
        }

        val finished = try {
            completed.await(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            Log.w(TAG, "timed out while flushing log queue")
        }
    }

    // ========== File Logging: public accessors (for the log viewer UI) ==========

    /** The directory run logs are written to (`moduleData/logs`), created on first access. */
    val logsDir: java.nio.file.Path?
        get() = runCatching { (KnownPaths.moduleData / "logs").createDirsSafe() }.getOrNull()

    /**
     * All run-log files (`wekit-yyyy-MM-dd.log` and rotated `wekit-yyyy-MM-dd.<seq>.log`
     * siblings), newest first. Flushes the active writer first so the current day's file
     * reflects the latest entries before the UI reads it.
     */
    val allLogFiles: List<java.nio.file.Path>
        get() {
            flush()
            val dir = logsDir ?: return emptyList()
            val regex = Regex("""wekit-\d{4}-\d{2}-\d{2}(\.\d+)?\.log""")
            return runCatching {
                dir.toFile().listFiles()
                    ?.filter { it.isFile && regex.matches(it.name) }
                    ?.sortedByDescending { it.name }
                    ?.map { it.toPath() }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }

    // ========== Tag + String ==========

    fun e(tag: String?, msg: String) {
        Log.e(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("E", tag, msg, null, LocalDateTime.now()))
    }

    fun w(tag: String?, msg: String) {
        Log.w(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("W", tag, msg, null, LocalDateTime.now()))
    }

    fun i(tag: String?, msg: String) {
        Log.i(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("I", tag, msg, null, LocalDateTime.now()))
    }

    fun d(tag: String?, msg: String) {
        Log.d(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("D", tag, msg, null, LocalDateTime.now()))
    }

    fun v(tag: String?, msg: String) {
        Log.v(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("V", tag, msg, null, LocalDateTime.now()))
    }

    // ========== Tag + String + Throwable ==========

    fun e(tag: String?, msg: String, e: Throwable) {
        Log.e(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("E", tag, msg, e, LocalDateTime.now()))
    }

    fun w(tag: String?, msg: String, e: Throwable) {
        Log.w(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("W", tag, msg, e, LocalDateTime.now()))
    }

    fun i(tag: String?, msg: String, e: Throwable) {
        Log.i(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("I", tag, msg, e, LocalDateTime.now()))
    }

    fun d(tag: String?, msg: String, e: Throwable) {
        Log.d(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("D", tag, msg, e, LocalDateTime.now()))
    }

    fun v(tag: String?, msg: String, e: Throwable) {
        Log.v(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("V", tag, msg, e, LocalDateTime.now()))
    }

    // ========== Stack Trace ==========

    val currentStackTrace: String
        get() {
            return Thread.currentThread().stackTrace
                .drop(2) // drop getStackTrace + this function
                .joinToString(separator = "\n") { element ->
                    "at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
                }
        }

    // ========== Chunked ==========

    fun logChunked(priority: Int, tag: String, msg: String) {
        if (msg.length <= CHUNK_SIZE) {
            Log.println(priority, TAG, "$tag: $msg")
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, msg, null, LocalDateTime.now()))
            return
        }

        val len = msg.length
        val chunkCount = (len + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (chunkCount > MAX_CHUNKS) {
            val head = msg.substring(0, CHUNK_SIZE)
            val headMsg = "[chunked] too long ($len chars, $chunkCount chunks). head:\n$head"
            val truncMsg = "[chunked] truncated. consider writing to file for full dump."
            Log.println(priority, TAG, "$tag: $headMsg")
            Log.println(priority, TAG, "$tag: $truncMsg")
            val timestamp = LocalDateTime.now()
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, headMsg, null, timestamp))
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, truncMsg, null, timestamp))
            return
        }

        var i = 0
        var part = 1
        val timestamp = LocalDateTime.now()
        while (i < len) {
            val end = min(i + CHUNK_SIZE, len)
            val chunk = msg.substring(i, end)
            val partMsg = "[part $part/$chunkCount] $chunk"
            Log.println(priority, TAG, "$tag: $partMsg")
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, partMsg, null, timestamp))
            i += CHUNK_SIZE
            part++
        }
    }

    fun logChunkedI(tag: String, msg: String) = logChunked(Log.INFO, tag, msg)
    fun logChunkedD(tag: String, msg: String) = logChunked(Log.DEBUG, tag, msg)

    // ========== Helpers ==========

    private fun Int.toPriorityChar(): String = when (this) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }
}
