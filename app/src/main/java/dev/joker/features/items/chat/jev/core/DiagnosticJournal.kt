package dev.joker.features.items.chat.jev.core

import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * 潜语诊断日志的内存缓冲 + **后台落盘**。
 *
 * ## 第 27 轮：为什么必须把 `appendText` 从调用线程上挪走
 *
 * 老实现是 `@Synchronized fun append(text) { memory += text; file.appendText(text) }` ——
 * **每一次 `MoodLog.i/w/e` 都是一次同步文件写**。而这些调用点全在主线程：
 * bind 路径、绘制路径、每拍重试、看门狗结清……宿主聊天列表滚动一次就能触发几十次写入，
 * 主线程被磁盘 IO 按住，就是用户感知到的「滑动卡顿」。
 *
 * 现在的分工：
 *  - [append]（调用线程）：只做内存拼接 + 入队，**不碰磁盘**，成本是一次字符串拼接 + 一次无锁入队；
 *  - 后台守护线程：从队列里取文本，按需 `appendText` 落盘；
 *  - [read]：一律读内存 —— 「导出日志」看到的永远是最新内容，不必等磁盘。
 *
 * 队列满时丢**最旧**的待落盘文本：内存那份始终完整（导出不缺内容），丢的只是磁盘副本。
 * 这是刻意的取舍 —— 宁可少写几行磁盘日志，也不让 UI 线程等 IO。
 */
class DiagnosticJournal(private val file: File, private val limit: Int = 128 * 1024) {

    var diskFailure: String? = null
        private set

    private var memory = runCatching { if (file.exists()) file.readText().takeLast(limit) else "" }
        .onFailure { diskFailure = it.javaClass.simpleName }.getOrDefault("")

    private val lock = Any()

    /** 待落盘队列；容量按「一次积压的行数」给，正常情况远用不到。 */
    private val pendingWrites = LinkedBlockingQueue<String>(256)

    private val writer = Thread({
        while (true) {
            val text = try {
                pendingWrites.take()
            } catch (_: InterruptedException) {
                return@Thread
            }
            flushToDisk(text)
        }
    }, "JokerMoodJournal").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        // 宿主进程里线程创建失败必须退化成「只记内存」，绝不能影响宿主
        runCatching { start() }
    }

    fun append(text: String) {
        val overflow = synchronized(lock) {
            val over = memory.length + text.length > limit
            memory = (memory + text).takeLast(limit)
            over
        }
        // 内存已经溢出说明磁盘副本落后很多：整体重写一次（后台线程做）
        enqueue(if (overflow) REWRITE else text)
    }

    fun read(): String = synchronized(lock) { memory }

    private fun enqueue(text: String) {
        if (!pendingWrites.offer(text)) {
            pendingWrites.poll()
            pendingWrites.offer(text)
        }
    }

    private fun flushToDisk(text: String) {
        synchronized(lock) {
            runCatching {
                if (text == REWRITE) file.writeText(memory) else file.appendText(text)
                diskFailure = null
            }.onFailure { diskFailure = it.javaClass.simpleName }
        }
    }

    private companion object {
        /** 队列里的「整体重写」哨兵（内存已溢出时用它对齐磁盘）。 */
        const val REWRITE = "\u0000JOKER_JOURNAL_REWRITE"
    }
}
