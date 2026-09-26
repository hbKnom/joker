package dev.ujhhgtg.wekit.features.items.chat.jev.analysis

import dev.ujhhgtg.wekit.features.items.chat.jev.core.*
import kotlinx.coroutines.*

/**
 * 潜语分析调度：认领去重 + 限速 + 超时 + 可见失败 + 流水记账。
 *
 * 这里刻意不引入队列积压：同一句话只认领一次（[MoodStore.claim]），失败后进冷却窗口，
 * 超时/无响应由 [clearStuck] 兜底结清 —— 保证界面上每一条提交过的分析最终都会变成
 * 「有结果」或「可见失败」，不会永远停在「正在分析…」。
 *
 * 第 14 轮补的两件事，直接对着用户实测的「有的行出结果、有的行一直不显示」：
 *  1. **最新优先**：待分析队列按消息时间倒序（[queue]），用户正在看的那几句先出结论；
 *  2. **3 个 worker 并行**（[WORKERS]）：原来只有 2 个槽位，一屏十几条要排队几十秒。
 */
object SignalAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = JevHttpClient()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failureMessages = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 完成计数：只用来给日志限流（前 3 条 + 每 25 条记一次）。 */
    private val completed = java.util.concurrent.atomic.AtomicInteger()

    /**
     * 待分析队列：**最新的一句话先分析**。
     *
     * 用户实测的「有的行出结果很快、有的行一直不显示」根因就在这里 —— 进入会话时本屏
     * 十几条消息一起提交，原来按「屏幕从上到下」（= 从旧到新）的顺序发请求，
     * 用户正在看的最新几条排在最后，要等前面十几条跑完（每条两轮请求）才轮到。
     * 现在按消息时间倒序排队，并且放 3 个 worker 并行，眼前的消息先出结论。
     */
    private val queue = java.util.concurrent.PriorityBlockingQueue<AnalysisInput>(
        8,
        compareByDescending { createdAtKey(it) },
    )

    private val workersStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 单条消息的硬超时（兜底）。真正保证"不会一直转圈"的是扫描器一侧的看门狗。 */
    private const val TIMEOUT_MS = 70_000L

    /** 起跑时刻（elapsedRealtime）：看门狗据此区分「排在队里」和「已经在跑」。 */
    private val startTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 在跑/在排队的输入：看门狗结清时要拿它回调展示层，否则只能结清一个没有身份的状态。 */
    private val pendingInputs = java.util.concurrent.ConcurrentHashMap<String, AnalysisInput>()

    /** 「失败过」的消息上一次被允许自动重投的时刻（[tryConsumeAutoRetry] 用）。 */
    private val autoRetryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 「失败过」的消息**已经用掉多少次**自动重投额度。
     *
     * 只有上限没有下限是以前的老毛病：`mayRetryFailed` 每 [AUTO_RETRY_MIN_GAP_MS] 就放行一次，
     * 一条永远失败的消息（网络断了、服务挂了）会被无限重投，一直烧流量与电量。
     * 现在每条消息的自动重投次数封顶到 [MAX_AUTO_RETRIES] 次，用尽之后只剩两条路：
     * 用户点卡片手动重试，或设置页的「重试全部失败」。
     */
    private val autoRetries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 同一条失败消息两次自动重投之间的最小间隔（按次数指数放大，见 [autoRetryGapMs]）。 */
    private const val AUTO_RETRY_MIN_GAP_MS = 15_000L

    /** 单次自动重投间隔的上限，别让退避到几十分钟才试一次。 */
    private const val AUTO_RETRY_MAX_GAP_MS = 120_000L

    /** 每条消息自动重投的次数上限（之后只保留手动重试入口）。 */
    private const val MAX_AUTO_RETRIES = 6

    /**
     * 失败后的冷却：这段时间内同一条消息不重复打模型，但失败原因必须一直可见（可手动重试）。
     *
     * **公开**是有意的：扫描器的自动重扫延迟必须**严格大于**这个值，否则重扫会被
     * [submit] 的冷却挡回来、等于白扫一次（第 17 轮踩过：重扫 20s < 冷却 30s）。
     * 现在扫描器直接用它加一点余量算出重扫延迟，两边不可能再错开。
     */
    const val FAIL_COOLDOWN_MS = 30_000L

    /** 第 n 次自动重投的等待间隔：15s、30s、60s、120s… 封顶 [AUTO_RETRY_MAX_GAP_MS]。 */
    private fun autoRetryGapMs(attempts: Int): Long =
        (AUTO_RETRY_MIN_GAP_MS shl attempts.coerceIn(0, 3)).coerceAtMost(AUTO_RETRY_MAX_GAP_MS)

    /**
     * 结论落地（成功 **或** 失败）的回调接口。
     *
     * 展示层（气泡卡、回插通道）靠它**即时回填**：以前只有「分析成功」一个回调，
     * 气泡只能靠 600ms 轮询去猜结果到没到 —— 一屏十几行每秒重新渲染一遍，
     * 既卡又慢半拍。现在成功/失败都推给订阅者，节拍退化成兜底。
     */
    interface SettleListener {
        /** [mood] 为 null 表示这次没有结论（[reason] 是可见的失败原因）。 */
        fun onSettled(input: AnalysisInput, mood: Mood?, reason: String?)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SettleListener>()

    fun addListener(listener: SettleListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: SettleListener) {
        listeners.remove(listener)
    }

    private fun notifySettled(input: AnalysisInput, mood: Mood?, reason: String?) {
        listeners.forEach { listener ->
            runCatching { listener.onSettled(input, mood, reason) }
                .onFailure { MoodLog.e("结论回调失败：${it.javaClass.simpleName} ${it.message}") }
        }
    }

    fun failure(key: String): String? = failureMessages[key]

    /**
     * 自动重投的判定结果。调用方（扫描器的 [dev.ujhhgtg.wekit.features.items.chat.jev.hook.YanwaiScanner]）
     * 靠它区分「再等一会就好」和「这一条已经没额度了」，从而决定还要不要继续排重扫。
     */
    enum class RetryVerdict {
        /** 允许重投（本次调用**已经消耗**一次该消息的重投额度）。 */
        READY,

        /** 还在间隔里：等下一轮重扫即可，不消耗额度。 */
        COOLING,

        /** 自动重投额度已用尽：只保留卡片上的手动重试入口。 */
        EXHAUSTED,
    }

    /**
     * 「这条失败过的消息现在能不能再自动重投一次」——**会消耗一次额度**。
     *
     * 第 17 轮新增（用户要求「被选定的聊天每一条文本消息都要被分析」）：旧实现里
     * 扫描器的提交入口一看到 [failure] 非空就永久放弃 —— 于是**只要失败过一次
     * （超时、网络抖、当时还没配好 Key），这一条就再也不会自动重试**。
     *
     * 本轮补上另外半边：自动重投要有**冷却 + 次数上限**（[autoRetryGapMs] /
     * [MAX_AUTO_RETRIES]），否则一条永远失败的消息会被无限重投。
     * 真正的配额判断仍在 [submit] 里：未配置、超出字数上限、仍在 [FAIL_COOLDOWN_MS]
     * 冷却内的消息即使被重投也会被挡下并立刻返回 null。
     */
    fun tryConsumeAutoRetry(key: String): RetryVerdict {
        val attempts = autoRetries[key] ?: 0
        if (attempts >= MAX_AUTO_RETRIES) return RetryVerdict.EXHAUSTED
        val now = System.currentTimeMillis()
        val last = autoRetryAt[key] ?: Long.MIN_VALUE
        if (last != Long.MIN_VALUE && now - last < autoRetryGapMs(attempts)) return RetryVerdict.COOLING
        autoRetryAt[key] = now
        val next = attempts + 1
        autoRetries[key] = next
        if (next >= MAX_AUTO_RETRIES) {
            // 恰好用尽：记一行（每条消息只会出现一次），用户看到的是卡片上的手动重试入口
            MoodLog.w("自动重投额度已用尽（$MAX_AUTO_RETRIES 次）：${key.take(8)}，改由手动重试")
        }
        return RetryVerdict.READY
    }

    /**
     * 兼容旧调用点的布尔版（会消耗额度，见 [tryConsumeAutoRetry]）。
     */
    fun mayRetryFailed(key: String): Boolean = tryConsumeAutoRetry(key) == RetryVerdict.READY

    /**
     * 这一条是否还在 [FAIL_COOLDOWN_MS] 失败冷却里。
     *
     * 扫描器据此把「还在冷却」与「额度用尽」分开：前者等下一轮重扫就行，
     * 后者再排也是白排 —— 而且**冷却检查必须先于额度消耗**，否则冷却是白等的。
     */
    fun coolingDown(key: String): Boolean =
        System.currentTimeMillis() - (failures[key] ?: 0L) < FAIL_COOLDOWN_MS

    /** 已发出的请求数（含重试），设置页显示运行状态用。 */
    val requestCount: Int get() = client.requestCount

    /** 队列积压（还没开跑的分析条数），设置页与卡片的排队提示用。 */
    val queuedDepth: Int get() = queue.size

    /** 在跑 + 在排队的总数（[pendingInputs]），看门狗与设置页展示用。 */
    val pendingDepth: Int get() = pendingInputs.size

    /** 当前生效的请求间隔（毫秒，含自适应倍率），设置页展示用。 */
    val currentIntervalMs: Long get() = client.currentIntervalMs

    /**
     * 队列是否已达上限。
     *
     * 第 16 轮新增：上限**可配置**（[ModulePrefs.KEY_QUEUE_CAP]），而且达到上限时
     * **不丢消息** —— 扫描器会把这一条登记到「等空位」集合，队列一有位置就自动补投，
     * 期间卡片明确显示「分析队列已满（N 条排队中），本条会在有空位时自动开始分析」。
     * 上一版没有任何上限：用户连续翻几屏会话，队列能堆到几百条，
     * 每条都要两轮请求，后面的消息要等十几分钟才轮到（看起来就是「分析不出来」）。
     */
    fun atCapacity(): Boolean = queue.size >= ModulePrefs.queueCap

    /** 是否已经在跑（不是「排在队里」）。看门狗据此选用宽松/严格的等待上限。 */
    fun startedAt(key: String): Long? = startTimes[key]

    /**
     * 看门狗结清：某条消息既没结果也没失败、却已经超出等待上限时调用。
     *
     * 目的只有一个 —— 让界面**永远**能给出一个可解释的状态（失败 + 可重试），
     * 而不是无限「正在分析…」。释放认领后同一句话可以立刻重试。
     */
    fun clearStuck(key: String) {
        val wasPending = MoodStore.isPending(key)
        MoodStore.release(key)
        // 看门狗结清是**可重试**的失败：让扫描器稍后自动重投，不用用户手点。
        autoRetryAt[key] = System.currentTimeMillis()
        failures.remove(key)
        startTimes.remove(key)
        val input = pendingInputs.remove(key)
        val reason = if (wasPending) "分析超时（模型无响应），点击此卡重试" else "分析已中断，点击此卡重试"
        failureMessages[key] = reason
        MoodStore.markFailed()
        ModulePrefs.report("看门狗结清：${key.take(8)} pending=$wasPending")
        if (input != null) notifySettled(input, null, reason)
    }

    fun retryFailure(key: String) {
        failures.remove(key)
        failureMessages.remove(key)
        // 手动重试：额度也一并清零，用户主动点一次不该被判「你已经试过 6 次了」
        autoRetries.remove(key)
        autoRetryAt.remove(key)
    }

    /** 「重试全部失败」：清掉所有冷却与失败标记，交给调用方重新提交可见行。 */
    fun retryAllFailures(): Int {
        val count = failureMessages.size
        failures.clear()
        failureMessages.clear()
        autoRetryAt.clear()
        autoRetries.clear()
        return count
    }

    fun clearResults() {
        queue.clear()
        failures.clear()
        failureMessages.clear()
        autoRetryAt.clear()
        autoRetries.clear()
        pendingInputs.clear()
        startTimes.clear()
        MoodStore.clearResults()
    }

    /**
     * 提交一条分析。
     *
     * 返回值语义（调用方据此决定要不要挂「正在分析」状态）：
     * - 非 null：这条消息**正在被分析**（本次新入队，或已经在队列/在跑）；
     * - null：这条**不会**产生新结论 —— 未配置 / 不在范围 / 内容超限 / 仍在失败冷却期。
     *
     * 冷却期以前返回的是 key，调用方会挂上「正在分析」并等 60s 看门狗来结清，
     * 结果用户看到的是「明明刚失败过，怎么又转了一圈说超时」。现在直接返回 null，
     * 卡片立刻显示上次的失败原因与重试入口。
     */
    fun submit(input: AnalysisInput, stillVisible: () -> Boolean = { true }): String? {
        if (!ModulePrefs.canAnalyze) return null
        if (!ModulePrefs.inScope(input.talker)) return null
        if (MessagePolicy.textOrNull(input.text) == null) return null
        val key = input.key
        if (System.currentTimeMillis() - (failures[key] ?: 0L) < FAIL_COOLDOWN_MS) return null
        if (!MoodStore.claim(key)) return key
        failureMessages.remove(key)
        pendingInputs[key] = input
        ensureWorkers()
        queue.offer(input)
        return key
    }

    /** 3 个常驻 worker；只在第一次提交时启动，进程内可复用。 */
    private fun ensureWorkers() {
        if (!workersStarted.compareAndSet(false, true)) return
        repeat(WORKERS) {
            scope.launch {
                while (true) {
                    // 阻塞取队列（worker 线程就是 Dispatchers.IO 的线程，阻塞在这里不占主线程）。
                    val input = queue.take()
                    startTimes[input.key] = android.os.SystemClock.elapsedRealtime()
                    try {
                        perform(input)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MoodLog.e("分析任务异常：${e.message}")
                    } finally {
                        // 跑完/异常都要把起跑标记摘掉，否则看门狗会一直以为它还在跑
                        startTimes.remove(input.key)
                    }
                }
            }
        }
    }

    /** 真正跑一条分析：结果必然落到「成功」或「可见失败」，不允许静默丢弃。 */
    private suspend fun perform(input: AnalysisInput) {
        val key = input.key
        try {
            ModulePrefs.reload()
            if (!ModulePrefs.canAnalyze) {
                // 第 16 轮：这里以前是静默 release —— 用户看到的就是「这一条永远在转圈」，
                // 要等 180 秒看门狗才变成失败。现在立刻给出可见状态，而且**不进冷却**：
                // 用户去设置页填好 Key 回来，这一条下一拍就会自动重投。
                abort(key, input, "尚未配置模型渠道或 API Key，配置后本卡会自动重新分析")
                return
            }
            // 刻意不再用「这一行还在不在屏幕上」当作继续条件：
            // 一旦开始就必然留下结果或可见失败。以前消息一转出屏幕就静默 release，
            // 气泡会永远停在「正在分析…」（用户实测的主要症状之一）。
            val mood = withTimeoutOrNull(TIMEOUT_MS) { analyze(input) }
            if (mood == null) {
                fail(key, input, "分析超时（模型无响应），点击此卡重试", report = "分析超时：${key.take(8)}")
                return
            }
            succeed(key, input, mood)
        } catch (e: CancellationException) {
            MoodStore.release(key)
            throw e
        } catch (e: Exception) {
            fail(key, input, e.message ?: "分析失败，请稍后重试", report = "分析失败：${e.message}")
        }
    }

    /**
     * 「这一条这次不会出结论」——可见、可自动重投、**不进失败冷却**。
     *
     * 与 [fail] 的区别只在冷却：配置类原因（没填 Key、内容超限、被上层放弃）不应该让
     * 用户在修好配置之后还要干等 30 秒，所以这里只写失败文案 + 释放认领。
     */
    private fun abort(key: String, input: AnalysisInput, reason: String) {
        failures.remove(key)
        autoRetryAt[key] = System.currentTimeMillis()
        // 配置类原因（没填 Key）不消耗自动重投额度：用户在设置里补好之后
        // 这一条还要能自己接着跑，不能因为「还没配好时试了几次」就被判额度用尽。
        autoRetries.remove(key)
        failureMessages[key] = reason
        MoodStore.release(key)
        startTimes.remove(key)
        pendingInputs.remove(key)
        MoodStore.markFailed()
        notifySettled(input, null, reason)
    }

    private fun succeed(key: String, input: AnalysisInput, mood: Mood) {
        MoodStore.complete(key, mood)
        failures.remove(key)
        autoRetryAt.remove(key)
        autoRetries.remove(key)
        failureMessages.remove(key)
        startTimes.remove(key)
        pendingInputs.remove(key)
        MoodStore.markCompleted()
        MoodStore.recordScore(input.talker, mood.score)
        MoodStore.record(
            MoodStore.Entry(
                key = key,
                label = mood.label,
                talker = input.talker,
                at = System.currentTimeMillis(),
                ok = true,
                note = buildString {
                    append("情绪 ")
                    append((mood.score * 100).toInt().coerceIn(-100, 100))
                    append("%")
                    mood.advice?.let { append("；建议：").append(it.take(24)) }
                },
            ),
        )
        // 日志/回传都限流：一屏消息分析完就是十几行同形状的「潜语分析完成，已缓存 N 条」，
        // 这是用户点名的日志刷屏源之一。前 3 条照记（方便排查启动状态），之后每 25 条记一次。
        val done = completed.incrementAndGet()
        if (done <= 3 || done % 25 == 0) {
            MoodLog.i("潜语解读完成：${mood.dominant ?: mood.label}（累计 $done 条，缓存 ${MoodStore.size()} 条）")
            ModulePrefs.report("潜语分析完成，已缓存 ${MoodStore.size()} 条")
        }
        notifySettled(input, mood, null)
    }

    private fun fail(key: String, input: AnalysisInput, reason: String, report: String) {
        failures[key] = System.currentTimeMillis()
        autoRetryAt[key] = System.currentTimeMillis()
        failureMessages[key] = reason
        MoodStore.release(key)
        MoodStore.markFailed()
        startTimes.remove(key)
        pendingInputs.remove(key)
        MoodStore.record(
            MoodStore.Entry(
                key = key,
                label = "分析失败",
                talker = input.talker,
                at = System.currentTimeMillis(),
                ok = false,
                note = reason,
            ),
        )
        MoodLog.e("分析失败：$reason")
        ModulePrefs.report(report)
        notifySettled(input, null, reason)
    }

    suspend fun requestMood(text: String, context: List<ContextMessage> = emptyList(),
        speaker: String = "对方"): Mood = analyze(AnalysisInput(text, "sample", context, speaker = speaker))

    /**
     * 设置页的「检测连接」：真的打一次接口并校验协议。
     *
     * 三种结果分开报，用户才能对症：接口不通（Key/网络/额度）、
     * 通但模型不按 Jev 协议回答（渠道或模型不对）、完全正常。
     */
    fun testConnection(callback: (Boolean, String) -> Unit) {
        scope.launch {
            val outcome = runCatching {
                val settings = ModulePrefs.apiSettings()
                check(settings.isConfigured) { "请先在下方填写并保存 API Key" }
                val body = client.exchange(JevProtocol.payload("在吗？", settings.model), settings)
                runCatching { JevProtocol.parseProfile(body) }.fold(
                    onSuccess = { true to "连接正常：${settings.provider.label} · ${settings.model}（已用请求 ${client.requestCount} 次）" },
                    onFailure = { false to "接口可连通，但返回内容不符合 Jev 协议，请换渠道或模型（已用请求 ${client.requestCount} 次）" },
                )
            }.getOrElse { false to (it.message ?: "检测失败，请稍后重试") }
            withContext(Dispatchers.Main) { runCatching { callback(outcome.first, outcome.second) } }
        }
    }

    private suspend fun analyze(input: AnalysisInput, shouldContinue: () -> Boolean = { true }): Mood = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        // Keep both rounds on the same endpoint and credential, even if settings change mid-request.
        val settings = ModulePrefs.apiSettings()
        check(settings.isConfigured) { "请先在潜语设置中填写并保存 API Key" }
        try {
            ChatAnalysis.analyze(input, settings.model, { client.exchange(it, settings) }) {
                job.ensureActive()
                shouldContinue()
            }
        } catch (e: org.json.JSONException) {
            throw IllegalStateException("模型返回不完整，本次不显示判断")
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("模型返回不完整，本次不显示判断")
        }
    }

    /** 时间未知（createTime 读不到）的消息按「最新」处理，避免它们被永久排在队尾。 */
    private fun createdAtKey(input: AnalysisInput): Long =
        if (input.createdAt > 0) input.createdAt else Long.MAX_VALUE

    /** 并行 worker 数：3 条同时跑，配合限速把一屏消息的等待时间压到秒级。 */
    private const val WORKERS = 3
}
