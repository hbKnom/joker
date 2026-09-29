package dev.joker.features.items.chat.jev.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import dev.joker.R
import dev.joker.features.api.core.models.MessageInfo
import dev.joker.features.api.ui.WeChatMessageViewApi
import dev.joker.utils.HookParam
import dev.joker.features.items.chat.jev.analysis.ChatInsights
import dev.joker.features.items.chat.jev.analysis.SignalAnalyzer
import dev.joker.features.items.chat.jev.core.AnalysisInput
import dev.joker.features.items.chat.jev.core.ContextMessage
import dev.joker.features.items.chat.jev.core.JevText
import dev.joker.features.items.chat.jev.core.ModulePrefs
import dev.joker.features.items.chat.jev.core.Mood
import dev.joker.features.items.chat.jev.core.MoodLog
import dev.joker.features.items.chat.jev.core.MoodStore
import dev.joker.features.items.chat.jev.core.MessageMetadata
import dev.joker.features.items.chat.jev.core.MessagePolicy

/**
 * 潜语扫描器（合并「言外潜台词」+「Jev 聊天决策」后的唯一入口）。
 *
 * 上游 wechatmood 是独立模块，只能自己 DexKit 搜 `MicroMsg.MvvmChattingItem` 的
 * `[onBindView]` 绑定点、再每 700ms 轮询 ListView/Adapter 才能知道「哪条消息现在在屏幕上」。
 * 本模块跑在 Joker 里，[WeChatMessageViewApi] 已经把这件事做完了：它 hook 同一个绑定点，
 * 维护 `View -> MessageInfo` 绑定表，并在绑定/解绑/回收时回调。因此这里：
 *
 *  - 去掉 DexKit 与 Adapter 搜索，绑定关系直接由 API 给出；
 *  - 去掉 ListView 遍历，可见行通过 [WeChatMessageViewApi.findBoundViews] 获取。
 *
 * ## 第 15 轮：节拍从「结果通道」降级成「兜底」
 *
 * 用户实测「决策分析造成卡顿」的直接原因是这里：600ms 节拍会对**屏幕里每一行**重新
 * 扫描一遍，每扫一次都可能触发渲染。现在改成：
 *
 *  1. **结果靠推送，不靠轮询**：实现 [SignalAnalyzer.SettleListener]，成功/失败落地后
 *     直接把结论回填到对应那张卡上，卡片不再等下一拍（既更快又不闪）。
 *  2. **节拍只盯「还没结清的那几行」**（[awaiting]，通常只有个位数），不再遍历整屏；
 *     而且只在真的有未结清行时才继续排下一拍 —— 空闲时一个定时器都不留。
 *  3. **分析输入按行缓存一次**（[Row] + [inputs]）：反射取文本、收集前文（要排序整屏
 *     可见行）只在绑定/重绑时做，节拍里不再重复做。
 *  4. **看门狗区分「排队中」与「在跑」**：排队要占限速预算，用宽松上限；已经在跑用严格
 *     上限。以前一律 60s，一屏十几条排到队尾的消息会被误判成「分析超时」。
 *  5. **补扫**（[rescan]）：功能是开启时才装上钩子，此前已经绑好的行不会再有回调 ——
 *     以前这些行永远不分析（用户实测「选定的聊天有些行没有结果」）。现在开启/保存配置后
 *     主动补扫一次可见行并提交。
 *
 * ## 第 16 轮：彻底修卡顿 + 每条文本消息都不放过
 *
 * 卡顿这边（量化见下），本文件改了两处、都不改变行为语义：
 *
 *  1. **本屏可见行快照带 TTL**（[screenSnapshot]）：每次绑定都要「列出这个会话的所有
 *     已绑定行 + 按 top 排序」才能取前文。一屏 N 行、滚动时每秒几十次绑定，
 *     这条路径是 O(N log N) × 绑定次数 ≈ 每屏 20 次整表扫描 + 20 次排序。
 *     现在 250ms 内复用一份快照：快速滑动时从「每次绑定都扫+排」降到「每 250ms 一次」，
 *     **一屏绑定 20 次的代价从 20 次扫描/排序降到 1 次**。
 *  2. **一次扫描同时产出三样东西**（前文、双方发言序列、话题素材）：以前前文和
 *     「谁在说话」要各来一遍。顺带把绑定路径里的偏好读取全部换成
 *     [dev.joker.preferences.HotPrefs] 内存命中（见 [ModulePrefs] 的注释，
 *     每次绑定少掉约 12 次 SQLite 查询）。
 *
 * 覆盖完整性这边：
 *
 *  3. **队列上限不再是「丢」，而是「等」**（[capacityWaiting]）：队列满时这一条不提交，
 *     但登记下来，队列一有空位下一拍自动补投；卡片期间明确显示
 *     「分析队列已满（N 条排队中）」。上一版没有上限（堆到几百条、排到十几分钟），
 *     再上一版则是直接静默丢弃 —— 两种都不是用户要的。
 *  4. **自己发的消息默认一起分析**（[ModulePrefs.analyzeSelf] 默认已翻转为开）：
 *     「被选定会话的每一条文本消息都要被分析」。
 *  5. **超长消息给出可操作提示**：文案里带上当前上限（[MessagePolicy.maxCharacters]，
 *     可在设置里改），不再是一句固定的「超过 2000 字」。
 *
 * 硬约束（不变）：每条已经提交的分析都必须在有限时间内变成「有结果」或「可见失败」，
 * 绝不允许「既没结果也没失败、节拍却一直空转」。
 */
object YanwaiScanner : WeChatMessageViewApi.IMessageViewLifecycleListener,
    WeChatMessageViewApi.ICreateViewListener, SignalAnalyzer.SettleListener {

    private const val TAG = "YanwaiScanner"

    /**
     * 兜底节拍间隔。结果主要靠 [SignalAnalyzer.SettleListener] 推送回填，
     * 这一拍只负责「排队提示刷新 + 看门狗 + 布局未就绪的卡片重试」，1s 足够。
     */
    private const val TICK_MS = 1000L

    /** 已经在跑的单个分析：超过这个时间没有结论就结清（分析侧自身上限是 70s）。 */
    private const val RUNNING_WATCHDOG_MS = 90_000L

    /** 还排在队里的分析：限速 + 排队有客观等待，给足余量，避免误杀排在队尾的消息。 */
    private const val QUEUED_WATCHDOG_MS = 180_000L

    /** 兜底节拍跑满这么多拍，就顺手补扫一次可见行（抓「没有绑定回调」的漏网消息）。 */
    private const val RESCAN_EVERY_TICKS = 10

    /** 行还没测量好时卡片挂不上，最多重试这么多拍再放弃（避免无限重试）。 */
    private const val MAX_DEFER_ATTEMPTS = 12

    /**
     * 本屏可见行快照的有效期。
     *
     * 250ms 足够覆盖「一次滑动里连续触发的十几个 onBindView」，又短到不会让
     * 「前文/双方发言」明显过期。取前文本来就有 [ModulePrefs.contextLimit] 条的截断，
     * 差几行不影响结论。
     */
    private const val SCREEN_SNAPSHOT_TTL_MS = 250L

    /**
     * 本屏快照最多保留这么久（超过就整体丢弃）。
     *
     * 快照里装的是**宿主行 View 的强引用**（还有它们的 [MessageInfo]）。以前的清理条件是
     * 「写了 8 个会话以上」，也就是「只看过一两个会话」时那几个会话的行视图会被一直拎着 ——
     * 那是一整棵聊天行视图树（连带它的 Context）。现在改成按时间淘汰：最多 2 秒。
     */
    private const val SCREEN_SNAPSHOT_KEEP_MS = 2_000L

    /** 安装后第二次补扫的延迟（见 [install]）。 */
    private const val INSTALL_RESCAN_DELAY_MS = 800L

    /**
     * [submitIfNeeded] 的结果。
     *
     * 必须是三态而不是布尔：**「队列已满」与其它「这次不提交」在补投路径上完全不同** ——
     * 队列满要登记到 [capacityWaiting] 等空位；而冷却中 / 额度用尽只需要等下一轮重扫。
     * 早先返回布尔时，补投循环里「移除等待登记 → 再次被拒」的那一行会同时从三个待办集合里
     * 消失（不在 awaiting、不在 capacityWaiting、不在 deferred）—— 卡片永久停在
     * 「正在分析…」，而且**这一条永远不会被分析**，正是用户说的「有的消息没有结果」。
     */
    private enum class SubmitOutcome {
        /** 已经提交 / 已有结论 / 正在跑：这一行不需要再做什么。 */
        DONE,

        /** 队列满：这一条这次没排上，必须登记等空位（不能丢）。 */
        QUEUE_FULL,

        /** 其它原因这次不提交（失败冷却 / 自动重投额度用尽 / 未配置）：等下一轮重扫或手动重试。 */
        BLOCKED,
    }

    /**
     * 失败消息自动重扫的延迟。
     *
     * 「每一条文本消息都要被分析」的兜底：失败（超时 / 网络抖 / 刚启动时还没配好 Key）
     * 之后不再要求用户滚动一下或手点重试 —— 等这么久自动重扫一次，
     * 重扫会把可见行重新走一遍 `handle` → `submitIfNeeded`。
     *
     * **必须大于 [SignalAnalyzer] 的失败冷却**：冷却期内重扫会被 `submit` 挡回来，
     * 等于白扫一次（旧值 20s < 冷却 30s，正好踩在这个坑上）。第 22 轮起这个延迟
     * **直接由分析器那边的冷却常量推出来**（冷 + 5s）—— 以后谁改了冷却，
     * 这里的「必须大于冷却」不会因为忘了同步而被破坏。
     */
    private val RETRY_SWEEP_DELAY_MS = SignalAnalyzer.FAIL_COOLDOWN_MS + 5_000L

    /**
     * 一轮自动重扫里的退避上限（35s、70s、105s… 最多排到这里）。
     *
     * 注意它只是「单轮退避的封顶」，不是「重试次数的封顶」—— 真正的次数上限在
     * [SignalAnalyzer.tryConsumeAutoRetry]（每条消息 6 次）。这里封顶是为了不让
     * 同一轮退避越排越久（否则等冷却的行会被拖到几十分钟之后才重扫一次）。
     */
    private const val MAX_RETRY_SWEEPS = 6

    private val main = Handler(Looper.getMainLooper())
    private var installed = false
    private var tickScheduled = false
    private var retrySweepScheduled = false
    private var retrySweepAttempts = 0

    /**
     * 本轮重扫里「失败过、但这次仍没能重新提交」的可见行数（[retrySweep] 用）。
     * 只在主线程读写。
     */
    private var retryBlockedRows = 0

    /**
     * 本轮重扫里「自动重投额度已用尽」的可见行数。
     *
     * 与 [retryBlockedRows] 分开：额度用尽的行再排多少轮重扫也不会自己好，
     * 只保留卡片上的手动重试入口 —— 混在一起会让重扫无限空转。
     */
    private var retryExhaustedRows = 0

    /** 正在等待结果的行（主线程私有），避免对同一行重复 show。 */
    private val awaiting = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /**
     * 因为队列已满而**还没提交**的行。
     *
     * 这是「不丢消息」的关键：不提交 ≠ 放弃 —— 队列一有空位，下一拍就自动补投，
     * 期间卡片显示「队列已满，会在有空位时自动开始」。
     */
    private val capacityWaiting = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /** 已经画好内容、但因为行还没测量而没能挂上视图的行，等布局就绪后重试。 */
    private val deferred = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    private val deferredAttempts = java.util.WeakHashMap<View, Int>()

    /**
     * 每行**算好一次**的分析输入 + 跳过原因，节拍与推送里直接复用。
     *
     * 这是「开启潜语后滑动明显变卡」的主要来源：原来每 400~600ms 会对屏幕里每一行重新
     * 算一遍输入 —— 反射取文本 + 收集前文（要遍历并排序本屏所有已绑定的行），
     * 20 行的屏幕就是每拍 20 次反射 + 几百次比较。现在只在绑定/重绑时算一次。
     */
    private val inputs = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Row>())

    /**
     * 一行消息的分析输入、跳过原因，以及扩展洞察需要的「本屏素材」。
     *
     * [screen] 里装着本屏同一会话的「谁在说话」序列与原文，用于互动均衡 / 话题标签；
     * 在绑定那一刻算好，节拍与渲染都不再重新扫屏幕。
     */
    private class Row(
        val input: AnalysisInput,
        val note: String?,
        val screen: ChatInsights.Screen,
        /**
         * 生成这份输入时那条消息的**对象身份**（[MessageInfo.instance]）。
         *
         * msgId > 0 时归属判定只看 msgId；但本地暂态消息的 msgId 是 0，此时只有对象身份
         * 能把两条不同的消息分开 —— 少了它，两条 msgId 都是 0 的消息会被当成同一条，
         * 缓存/回填就会把 A 的结论挂到 B 上（卡片串台）。
         */
        val owner: Any,
    )

    /** key -> 提交时刻（elapsedRealtime）。用于看门狗判定，与系统时间跳变无关。 */
    private val submittedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 已经记过的异常签名：同一类绘制异常只落一行日志，不刷屏。 */
    private val loggedSignatures = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // ------------------------------------------------------------------ 本屏快照（主线程私有）

    /** 会话 -> 本屏已绑定行（按纵向顺序），带生成时刻。只在主线程读写。 */
    private val snapshotAt = HashMap<String, Long>()
    private val snapshotRows = HashMap<String, List<Pair<View, MessageInfo>>>()

    /** 最近一次写入快照的时刻（用于按时间淘汰快照里强引用的宿主行 View）。 */
    private var snapshotNewestAt = 0L

    /**
     * 取本屏快照（同一会话、按纵向顺序），250ms 内复用。
     *
     * 主线程专用：`view.top` 是 View 的字段，非主线程读它既没意义也不安全。
     * 所有调用点（[handle] / [tick] / [fill]）都已经被 [onMain] 或 Handler 包住。
     */
    private fun screenSnapshot(talker: String): List<Pair<View, MessageInfo>> {
        val now = SystemClock.elapsedRealtime()
        // 按时间淘汰：快照持的是宿主行 View 的强引用，长时间不清等于把整棵行视图树
        // （连同 Activity 上下文）拎在手里 —— 这是「对宿主 View 的持有」最隐蔽的一处。
        if (snapshotNewestAt != 0L && now - snapshotNewestAt > SCREEN_SNAPSHOT_KEEP_MS) {
            snapshotAt.clear()
            snapshotRows.clear()
            snapshotNewestAt = 0L
        }
        val at = snapshotAt[talker]
        if (at != null && now - at <= SCREEN_SNAPSHOT_TTL_MS) {
            snapshotRows[talker]?.let { return it }
        }
        val fresh = WeChatMessageViewApi.findBoundViews { it.talker == talker }
            .sortedBy { (view, _) -> view.top }
        if (snapshotAt.size > 8) {
            snapshotAt.clear()
            snapshotRows.clear()
        }
        snapshotAt[talker] = now
        snapshotRows[talker] = fresh
        snapshotNewestAt = now
        return fresh
    }

    private fun invalidateScreenSnapshot() {
        snapshotAt.clear()
        snapshotRows.clear()
        snapshotNewestAt = 0L
    }

    private var tickCount = 0

    // ------------------------------------------------------------------ 兜底节拍

    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            if (!installed) return
            // 主线程 Handler 里漏出去的异常 = 进程闪退。这一拍里跑的东西最多（队列补投、
            // 看门狗、周期重扫、气泡 prune、宿主 View 读取），任何一个意外都不许把宿主带走。
            try {
                tickBody()
            } catch (t: Throwable) {
                logOnce("tick:${t.javaClass.simpleName}", "兜底节拍异常（已忽略）：${t.message}")
            }
        }
    }

    /** 兜底节拍的实际逻辑；异常兜底在 [tick] 里。 */
    private fun tickBody() {
        if (!ModulePrefs.enabled) {
                awaiting.clear()
                capacityWaiting.clear()
                deferred.clear()
                deferredAttempts.clear()
                return
            }
            val now = SystemClock.elapsedRealtime()
            var keep = false
            // 队列空出位置后，把「等空位」的行补投出去（不丢消息的关键路径）。
            if (capacityWaiting.isNotEmpty() && !SignalAnalyzer.atCapacity()) {
                for (view in capacityWaiting.toList()) {
                    val row = rowFor(view)
                    if (row == null) {
                        capacityWaiting.remove(view)
                        continue
                    }
                    capacityWaiting.remove(view)
                    // 补投又被拒（空位瞬间被别处占掉/又满了）→ 重新登记等下一拍。
                    // 「移出等待集合之后就没人管」正是「有的消息永远停在正在分析、这一条
                    // 再也不会被分析」的根因；队列满只代表「这次没排上」，不代表可以丢。
                    if (row.note == null &&
                        submitIfNeeded(row.input.key, row.input) == SubmitOutcome.QUEUE_FULL
                    ) {
                        capacityWaiting.add(view)
                    }
                }
            }
            // 【Round45】手指还在滑动时，这一拍**只做队列维护、不碰任何卡片渲染**。
            // Round43 已经把「周期补扫」挡在滑动之外，但等待中的卡片仍会在滑动帧里
            // 被反复 showCard 重建 —— 行被滑动手势反复换绑，此刻重建就是用户看到的
            // 「上下滑动聊天记录，分析卡片容器会自动刷新 / 串来串去」，同时把测量与绘制
            // 全压在主线程手指上（卡顿）。队列补投（上面那段）已经跑完，所以「每条被选定的
            // 消息都要被分析」这条不受影响：停住后的下一拍立刻把所有卡片补齐。
            if (YanwaiBubble.anyListScrolling()) {
                scheduleTick()
                tickCount++
                return
            }
            for (view in awaiting.toList()) {
                val row = rowFor(view)
                if (row == null) {
                    awaiting.remove(view)
                    continue
                }
                val key = row.input.key
                if (row.note != null || isSettled(key)) {
                    awaiting.remove(view)
                    submittedAt.remove(key)
                    if (!showCard(view, row)) deferred.add(view)
                    continue
                }
                var submitted = submittedAt[key]
                if (submitted == null && MoodStore.isPending(key)) {
                    submitted = now
                    submittedAt[key] = now
                }
                if (submitted == null) {
                    // 既没提交过、也不在队列里：现在不该有卡（未配置 Key / 命中失败冷却）
                    awaiting.remove(view)
                    continue
                }
                val started = SignalAnalyzer.startedAt(key)
                val waited = if (started != null) now - started else now - submitted
                val limit = if (started != null) RUNNING_WATCHDOG_MS else QUEUED_WATCHDOG_MS
                if (waited > limit) {
                    // 结清会回调 onSettled 立刻回填；这一拍继续跑一次做确认
                    SignalAnalyzer.clearStuck(key)
                    keep = true
                    continue
                }
                keep = true
                // 排队中的卡片要跟着队列缩短更新「前面还有几条」
                showCard(view, row)
            }
            for (view in capacityWaiting.toList()) {
                val row = rowFor(view)
                if (row == null) {
                    capacityWaiting.remove(view)
                    continue
                }
                // 等空位的卡片也要刷新「队列已满（N 条）」这一行
                showCard(view, row)
                keep = true
            }
            keep = retryDeferred() || keep
            // prune 的返回值必须接进 keep：它表示「还有卡片没画出来」（落点/宽度/滚动推迟）。
            // 旧实现把返回值丢掉了 —— 于是「滚动中被推迟排版的那张卡」可能在节拍停下后
            // 谁也不管，一直停在旧画面上。接进来就等于「没画完就一直重试」。
            keep = YanwaiBubble.prune() || keep
            if (keep) {
                scheduleTick()
                tickCount++
                // 有未结清的行时，顺手周期性补扫一次（抓没有绑定回调的漏网消息）。
                // 【Round43】滚动中不补扫：滑动帧里整屏重扫是纯浪费（行不停换绑、
                // 快照下一帧就过期），而由此触发的卡片重建/重排全压在手指上 ——
                // 用户反馈的「上下滑动聊天记录会自动刷新」就出在这。停住再扫。
                if (tickCount % RESCAN_EVERY_TICKS == 0 && !YanwaiBubble.anyListScrolling()) rescan()
            }
    }

    /** 重试「内容已就绪但行还没测量」的卡片；返回是否还需要继续跑节拍。 */
    private fun retryDeferred(): Boolean {
        if (deferred.isEmpty()) return false
        var keep = false
        for (view in deferred.toList()) {
            val row = rowFor(view)
            if (row == null) {
                deferred.remove(view)
                deferredAttempts.remove(view)
                continue
            }
            if (showCard(view, row)) {
                deferred.remove(view)
                deferredAttempts.remove(view)
                continue
            }
            val attempts = (deferredAttempts[view] ?: 0) + 1
            deferredAttempts[view] = attempts
            if (attempts >= MAX_DEFER_ATTEMPTS) {
                deferred.remove(view)
                deferredAttempts.remove(view)
                logOnce("defer", "卡片挂载失败：行布局一直未就绪")
            } else {
                keep = true
            }
        }
        return keep
    }

    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        main.postDelayed(tick, TICK_MS)
    }

    /** 只在主线程改 View 相关状态；绑定回调理论上就在主线程，这里防御一次。 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === main.looper) block() else main.post(block)
    }

    private fun logOnce(signature: String, message: String) {
        if (!loggedSignatures.add(signature)) return
        MoodLog.w(message)
    }

    // ------------------------------------------------------------------ 生命周期

    fun install() {
        if (installed) return
        installed = true
        WeChatMessageViewApi.addLifecycleListener(this)
        WeChatMessageViewApi.addListener(this)
        SignalAnalyzer.addListener(this)
        // 回插通道一次性关闭迁移（用户明确要求默认不要再往会话里插系统消息）
        runCatching { ModulePrefs.migrateInsertOffOnce() }
        MoodLog.i(
            "$TAG 已挂载（气泡=${ModulePrefs.displayBubble}，回插会话=${ModulePrefs.displayMessage}，" +
                "范围=${ModulePrefs.scopeSummary()}，分析自己=${ModulePrefs.analyzeSelf}）"
        )
        // 装上钩子时屏幕上可能已经有绑好的消息，补扫一次
        onMain { rescan() }
        // 再补一次：装钩子那一刻宿主可能正处在「布局/绑定进行中」（绑定表还没写完），
        // 只扫一次容易漏掉「已经在屏幕上、但绑定回调早于我们安装」的那几条。
        // 每次安装只多扫一遍，代价可忽略，却是「每条文本消息都要被分析」的一道保险。
        main.removeCallbacks(rescanAgain)
        main.postDelayed(rescanAgain, INSTALL_RESCAN_DELAY_MS)
    }

    /**
     * 安装后的一次性补扫（见 [install]）。
     *
     * 兜底 try/catch：它跑在 Handler 消息里，漏异常出去就是进程闪退。
     */
    private val rescanAgain = Runnable {
        if (!installed) return@Runnable
        try {
            rescan()
        } catch (t: Throwable) {
            logOnce("rescan-again:${t.javaClass.simpleName}", "安装后补扫异常（已忽略）：${t.message}")
        }
    }

    fun uninstall() {
        installed = false
        main.removeCallbacks(tick)
        main.removeCallbacks(retrySweep)
        main.removeCallbacks(rescanAgain)
        retrySweepScheduled = false
        tickScheduled = false
        awaiting.clear()
        capacityWaiting.clear()
        deferred.clear()
        deferredAttempts.clear()
        submittedAt.clear()
        inputs.clear()
        invalidateScreenSnapshot()
        runCatching {
            WeChatMessageViewApi.removeLifecycleListener(this)
            WeChatMessageViewApi.removeListener(this)
            SignalAnalyzer.removeListener(this)
        }
        YanwaiBubble.clearAll()
        MoodLog.i("$TAG 已卸载")
    }

    /** 设置页改完配置后调用：补扫可见行 + 起一拍兜底。 */
    fun refresh() {
        main.removeCallbacks(tick)
        tickScheduled = false
        if (!installed) return
        onMain {
            invalidateScreenSnapshot()
            rescan()
            if (awaiting.isNotEmpty() || capacityWaiting.isNotEmpty() || deferred.isNotEmpty()) {
                scheduleTick()
            }
        }
    }

    /**
     * 补扫当前所有可见行：建立/刷新分析输入，并对「还没有任何进度」的文本消息提交分析。
     *
     * 这是「选定的聊天每条文本消息都要被分析」的兜底：功能开启、补填 API Key、
     * 换作用范围之后，已经绑定在屏幕上的行不会再收到绑定回调，只有这里会把它们捞回来。
     */
    fun rescan() {
        if (!installed) return
        if (!ModulePrefs.enabled) return
        // 兜底：它会被 install / refresh（设置页主线程，直接调用）/ 节拍 / 重扫调用，
        // 任何一处漏出异常都可能崩宿主 —— 只在 verbose 门闩下记一行。
        try {
            invalidateScreenSnapshot()
            var bound = 0
            for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
                bound++
                handle(view, message)
            }
            if (bound > 0 && (awaiting.isNotEmpty() || capacityWaiting.isNotEmpty() || deferred.isNotEmpty())) {
                scheduleTick()
            }
        } catch (t: Throwable) {
            logOnce("rescan:${t.javaClass.simpleName}", "补扫异常（已忽略）：${t.message}")
        }
    }

    override fun onCreateView(param: HookParam, view: View) {
        if (!installed) return
        val message = WeChatMessageViewApi.getBoundMessage(view) ?: return
        onMain { handle(view, message) }
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        if (!installed) return
        onMain { handle(view, message) }
    }

    override fun onMessageViewDetached(view: View, message: MessageInfo, rebound: Boolean) {
        // 重绑（rebound = true）就是「这一行换了消息」：旧卡片的内容与它向宿主容器预留的高度
        // 都属于**上一条**消息，必须当场摘掉，否则在宿主列表把它当新消息画出来的那一帧里，
        // 用户看到的就是「别的消息下面挂着这条消息的卡片」。
        //
        // 为什么这样不会闪：宿主是先派发 Detached(rebound=true) 再派发 Attached/onCreateView，
        // 两者在**同一个 onBindView 调用栈、同一帧**内完成 —— 摘掉之后紧接着的 handle()
        // 会按新的消息标识把卡片重建出来，用户看不到中间态。
        onMain { forget(view) }
    }

    override fun onMessageViewRecycled(view: View, message: MessageInfo) {
        onMain { forget(view) }
    }

    /** 分析结论落地（成功或失败）：立刻把对应那张卡刷新出来，不等下一拍。 */
    override fun onSettled(input: AnalysisInput, mood: Mood?, reason: String?) {
        if (!installed) return
        main.post {
            if (!installed) return@post
            // Handler 消息里漏出去的异常同样是闪退（这里要读宿主 View、要重排卡片）。
            try {
                submittedAt.remove(input.key)
                fill(input.key)
                // 同一条消息的其它键也一起填上（见 [fillIdentity]）。
                if (mood != null) fillIdentity(input, mood)
                // 这一次没结论（失败/超时/未配置）：安排一次延迟重扫，让这一条有机会自动重来。
                if (mood == null) scheduleRetrySweep()
            } catch (t: Throwable) {
                logOnce("settled:${t.javaClass.simpleName}", "结论回填异常（已忽略）：${t.message}")
            }
        }
    }

    /** 失败后的自动重扫（同一时刻只排一次），见 [RETRY_SWEEP_DELAY_MS]。 */
    private fun scheduleRetrySweep(delayMs: Long = RETRY_SWEEP_DELAY_MS) {
        if (retrySweepScheduled) return
        retrySweepScheduled = true
        main.postDelayed(retrySweep, delayMs)
    }

    private val retrySweep = Runnable {
        retrySweepScheduled = false
        if (!installed || !ModulePrefs.enabled) return@Runnable
        try {
            retrySweepBody()
        } catch (t: Throwable) {
            logOnce("sweep:${t.javaClass.simpleName}", "自动重扫异常（已忽略）：${t.message}")
        }
    }

    /** 失败后的自动重扫逻辑；异常兜底在 [retrySweep] 里。 */
    private fun retrySweepBody() {
        retryBlockedRows = 0
        retryExhaustedRows = 0
        rescan()
        // 重扫之后还有「失败过、但只是还在冷却里」的行 → 继续排（退避 35s / 70s / …）。
        //
        // 旧实现在次数到顶时把计数清零并**直接停机**，于是刚过完额度的那条失败消息
        // 在下一轮里谁也不管，要等下一条消息失败才顺带被重扫一次 —— 这就是
        // 「有的消息永远分析不出来」的残留。现在分成两类：还在冷却的行继续排，
        // 额度真的用尽的行走手动重试（卡片上有点击入口），且不再空转。
        if (retryBlockedRows > 0 && ModulePrefs.canAnalyze) {
            retrySweepAttempts = (retrySweepAttempts + 1).coerceAtMost(MAX_RETRY_SWEEPS)
            scheduleRetrySweep(RETRY_SWEEP_DELAY_MS * retrySweepAttempts)
        } else {
            retrySweepAttempts = 0
            if (retryExhaustedRows > 0) {
                logOnce("retry-exhausted", "有 $retryExhaustedRows 行自动重投额度用尽，等待手动重试")
            }
        }
    }

    // ------------------------------------------------------------------ 主逻辑

    /**
     * 行处理入口（宿主绑定回调 + 补扫都走这里）。
     *
     * **[onMain] 在主线程是「直接调用」而不是 post** —— 也就是说这里其实跑在宿主的
     * `onBindView` / View 挂载派发调用栈里。漏一个异常出去，崩的是宿主自己。
     * 所以最外层必须有兜底：异常只记一行（同一签名只记一次），绝不往外抛。
     */
    private fun handle(view: View, message: MessageInfo) {
        try {
            handleBound(view, message)
        } catch (t: Throwable) {
            logOnce("handle:${t.javaClass.simpleName}", "行处理异常（已忽略）：${t.message}")
        }
    }

    private fun handleBound(view: View, message: MessageInfo) {
        if (!ModulePrefs.enabled) {
            forget(view)
            return
        }
        // 绑定已经换人（异步回调期间列表又滚了一格）：这一行现在属于**别的消息**。
        // 以前这里只是 return，把上一条消息的卡片留在这一行上 —— 用户看到的就是
        // 「卡片先挂到别的消息上，过一会才规范」。现在当场摘掉：紧接着新绑定的那次
        // 回调会把新消息的卡片挂上，中间不留任何错挂窗口。
        val actual = WeChatMessageViewApi.getBoundMessage(view)
        if (actual != null && !sameMessage(actual, message)) {
            forget(view)
            return
        }
        val row = rowOf(view, message) ?: run {
            forget(view)
            return
        }
        val input = row.input
        // 不在作用域的聊天：一张卡都不要留（否则会永远显示「正在分析…」——
        // SignalAnalyzer.submit 会拒收，却没人告诉卡片「这次不会分析」）。
        if (!ModulePrefs.inScope(input.talker)) {
            forget(view)
            return
        }
        val key = input.key
        if (row.note == null) {
            // 「队列满」必须登记等空位：否则这一行会同时从 awaiting / capacityWaiting /
            // deferred 里消失，卡片永久停在「正在分析…」而这一条永远不会被分析。
            if (submitIfNeeded(key, input) == SubmitOutcome.QUEUE_FULL) capacityWaiting.add(view)
        }
        val settled = isSettled(key)
        if (!settled && (submittedAt.containsKey(key) || MoodStore.isPending(key))) awaiting.add(view)
        if (row.note == null && !settled && capacityWaiting.contains(view)) {
            // 还在等队列空位：由节拍负责补投与刷新
        }
        if (!showCard(view, row)) deferred.add(view)
        if (awaiting.isNotEmpty() || capacityWaiting.isNotEmpty() || deferred.isNotEmpty()) scheduleTick()
    }

    /**
     * 提交一次即可（claim 去重）：气泡通道与「回插会话」通道共用这一份请求与结果。
     *
     * 「没有任何进度」时才提交 —— 有结果、有失败（冷却中）、正在排队/在跑的都不重复提交。
     * 这条路径也是「功能开启前就已经绑好的行」被补扫时唯一会走到的提交入口。
     *
     * 队列满时**不提交也不放弃**：登记到 [capacityWaiting]，队列一有空位由节拍自动补投。
     */
    private fun submitIfNeeded(key: String, input: AnalysisInput): SubmitOutcome {
        if (submittedAt.containsKey(key) || MoodStore.isPending(key)) return SubmitOutcome.DONE
        if (MoodStore.get(key) != null) return SubmitOutcome.DONE
        // 闸门（失败冷却 / 自动重投额度 / 队列容量）之前先试一次复用：
        // SignalAnalyzer.submit() 里的复用短路排在这些闸门**之后**，只要队列恰好满着就永远
        // 走不到 —— 表现就是「同一条消息换个上下文键后卡片一直加载不过来」。复用命中时
        // 不发请求、不排队，直接算这一条已结清。
        if (SignalAnalyzer.reuseExisting(input) != null) {
            submittedAt.putIfAbsent(key, SystemClock.elapsedRealtime())
            return SubmitOutcome.DONE
        }
        // 失败过的消息**允许**自动重投，但要有冷却与次数上限（都在 SignalAnalyzer 里）。
        // 旧实现这里无条件 return，等于「失败一次就永久不再分析这一条」，与用户要求的
        // 「被选定的聊天每一条文本消息都要被分析」直接冲突。
        if (SignalAnalyzer.failure(key) != null) {
            // 先看冷却：还在 30s 冷却里就不该消耗重投额度（否则额度被白等掉）
            if (SignalAnalyzer.coolingDown(key)) {
                retryBlockedRows++
                return SubmitOutcome.BLOCKED
            }
            when (SignalAnalyzer.tryConsumeAutoRetry(key)) {
                SignalAnalyzer.RetryVerdict.EXHAUSTED -> {
                    retryExhaustedRows++
                    return SubmitOutcome.BLOCKED
                }

                SignalAnalyzer.RetryVerdict.COOLING -> {
                    retryBlockedRows++
                    return SubmitOutcome.BLOCKED
                }

                SignalAnalyzer.RetryVerdict.READY -> Unit
            }
        }
        if (SignalAnalyzer.atCapacity()) {
            retryBlockedRows++
            // 队列满 ≠ 这一条没救：调用方必须把它登记到 capacityWaiting（见 SubmitOutcome 注释）。
            return SubmitOutcome.QUEUE_FULL
        }
        if (SignalAnalyzer.submit(input) != null) {
            submittedAt.putIfAbsent(key, SystemClock.elapsedRealtime())
            return SubmitOutcome.DONE
        }
        // submit 被拒（仍在冷却/未配置/超限）：还算「有活可干」，让重扫多排一轮
        if (SignalAnalyzer.failure(key) != null) retryBlockedRows++
        return SubmitOutcome.BLOCKED
    }

    private fun isSettled(key: String): Boolean =
        MoodStore.get(key) != null || SignalAnalyzer.failure(key) != null

    /** 画/刷新卡片；返回 true 表示这张卡已经就绪。 */
    private fun showCard(view: View, row: Row): Boolean {
        if (!ModulePrefs.displayBubble) return true
        if (row.note == null && !isSettled(row.input.key) && SignalAnalyzer.atCapacity() &&
            !MoodStore.isPending(row.input.key) && !submittedAt.containsKey(row.input.key)
        ) {
            // 队列满、这一条还没排上：登记下来，等空位自动补投（下一拍开始就会一直刷新它）
            capacityWaiting.add(view)
        }
        val capacityPending = capacityWaiting.contains(view)
        return runCatching { YanwaiBubble.show(view, row.input, row.note, row.screen, capacityPending) }
            .getOrElse {
                logOnce("bubble", "气泡绘制失败：${it.javaClass.simpleName} ${it.message}")
                true
            }
    }

    /**
     * 把某个 key 的结论填到它对应的卡片上。
     *
     * 先看 [awaiting]（通常个位数），找不到再从整屏缓存里捞一次 ——
     * 保证「分析完了但卡片没更新」这种情况不会因为没被登记而丢掉。
     */
    private fun fill(key: String) {
        val targets = LinkedHashMap<View, Row>()
        for (view in awaiting.toList()) {
            val row = rowFor(view) ?: continue
            if (row.input.key == key) targets[view] = row
        }
        if (targets.isEmpty()) {
            for ((view, row) in allRows()) {
                if (row.input.key == key) targets[view] = row
            }
        }
        if (targets.isEmpty()) return
        awaiting.removeAll(targets.keys)
        capacityWaiting.removeAll(targets.keys)
        deferred.removeAll(targets.keys)
        for ((view, row) in targets) {
            if (!showCard(view, row)) deferred.add(view)
        }
    }

    /**
     * 把一份结论广播给**同一条消息**的其它结论键。
     *
     * 为什么需要：显示用的结论键 [AnalysisInput.key] 把上下文一起哈希了，所以同一条消息在
     * 「旁边来了新消息 / 滚动导致可见行变化」之后会得到一个全新的键，而排队中的那次请求
     * 是**旧键**。旧实现只回填落地那个键，卡片（此刻挂在新键上）就一直等一个永远不会有人
     * 回答的键 —— 用户看到的就是「分析好了却还显示正在分析，过一会才规范」。
     * 按消息身份（会话 + msgId）广播一次，两个键同时有结论，卡片当场就是最终样子。
     */
    private fun fillIdentity(settled: AnalysisInput, mood: Mood) {
        val targets = LinkedHashMap<View, Row>()
        for ((view, row) in allRows()) {
            if (row.input.key == settled.key) continue
            if (row.input.identity != settled.identity) continue
            targets[view] = row
        }
        if (targets.isEmpty()) return
        for ((view, row) in targets) {
            MoodStore.complete(row.input.key, mood)
            awaiting.remove(view)
            if (!showCard(view, row)) deferred.add(view)
        }
    }

    private fun forget(view: View) {
        awaiting.remove(view)
        capacityWaiting.remove(view)
        deferred.remove(view)
        deferredAttempts.remove(view)
        val row = inputs.remove(view)
        row?.let { submittedAt.remove(it.input.key) }
        YanwaiBubble.clear(view)
    }

    /** 取当前绑定行（缓存优先）。 */
    private fun rowFor(view: View): Row? =
        WeChatMessageViewApi.getBoundMessage(view)?.let { rowOf(view, it) }

    /**
     * 取这一行的分析输入（带缓存）。
     *
     * 缓存命中条件是「同一个 View 仍然绑着**同一条消息**」—— 重绑（滚出滚入）后
     * [WeChatMessageViewApi] 会重新走一次 handle()，所以这里只需要防御性地校验一次。
     */
    private fun rowOf(view: View, message: MessageInfo): Row? {
        val cached = inputs[view]
        if (cached != null && rowMatches(cached, message)) return cached
        val fresh = buildRow(view, message) ?: run {
            inputs.remove(view)
            return null
        }
        inputs[view] = fresh
        return fresh
    }

    /**
     * 缓存里的这一行是否仍然属于这条消息。
     *
     * **按消息唯一标识判定**：msgId > 0 用 msgId（同一台设备上唯一且稳定），
     * 否则退回消息对象身份。绝不用行位置、下标、时间或文本近似 —— 那些在滚动复用下都会串。
     */
    private fun rowMatches(row: Row, message: MessageInfo): Boolean {
        if (row.input.talker != message.talker) return false
        val id = messageIdOf(message)
        return if (id > 0L) row.input.messageId == id else row.owner === message.instance
    }

    /** 两条 [MessageInfo] 是不是同一条消息（同上：msgId 优先，暂态消息退回对象身份）。 */
    private fun sameMessage(a: MessageInfo, b: MessageInfo): Boolean {
        if (a.talker != b.talker) return false
        val idA = messageIdOf(a)
        val idB = messageIdOf(b)
        return if (idA > 0L && idB > 0L) idA == idB else a.instance === b.instance
    }

    /**
     * 安全取消息的本地 msgId：字段取不到（宿主版本差异）时按「没有稳定标识」处理。
     *
     * 这里绝不能抛：它跑在宿主的 onBindView / 绘制路径里，抛出去就是把微信打崩。
     */
    private fun messageIdOf(message: MessageInfo): Long =
        try {
            message.id
        } catch (t: Throwable) {
            0L
        }

    /**
     * 当前绑定仍然有效的行缓存。
     *
     * View 被复用（换绑到别的消息）之后，[inputs] 里的旧条目要等到下一拍 handle/forget
     * 才会被清掉；中间这段时间如果拿它去回填结论，就会把上一条消息的卡片画到新消息下面。
     * 所以这里逐条按**当前绑定**校验，校验不过的直接丢掉、绝不参与回填。
     */
    private fun allRows(): List<Pair<View, Row>> =
        synchronized(inputs) { inputs.entries.map { it.key to it.value } }
            .mapNotNull { (view, row) ->
                val bound = WeChatMessageViewApi.getBoundMessage(view) ?: return@mapNotNull null
                if (!rowMatches(row, bound)) return@mapNotNull null
                view to row
            }

    /**
     * View + MessageInfo -> 分析输入。
     *
     * 非文本消息返回 null（不画卡）；文本过长时返回带 [Row.note] 的行 ——
     * 以前这种消息既不提交也不画卡，用户看到的是「这一条什么都没有」，
     * 现在明确写清「本条内容过长，未分析」，而且文案里带上当前上限（可配置）。
     *
     * 「也分析我发的消息」默认**开**（[ModulePrefs.analyzeSelf]）：用户要求被选定会话的
     * 每一条文本消息都要被分析，自己发的也算。
     */
    private fun buildRow(view: View, message: MessageInfo): Row? = runCatching {
        val text = MessageMetadata.analyzeText(message, ModulePrefs.analyzeSelf) ?: return@runCatching null
        val talker = message.talker
        if (talker.isBlank()) return@runCatching null
        val screen = screenOf(view, message, text)
        val input = AnalysisInput(
            text = text,
            talker = talker,
            context = screen.context,
            messageId = messageIdOf(message),
            speaker = MessageMetadata.speaker(message),
            createdAt = runCatching { message.createTime }.getOrDefault(0L),
            // 归属键在拿不到 msgId 时要靠原始内容区分消息（清洗后的 text 会把超长/图片消息压成同串）
            rawContent = runCatching { message.content }.getOrDefault(""),
        )
        val note = if (MessagePolicy.textOrNull(text) == null) {
            JevText.get(R.string.jev_note_too_long, MessagePolicy.maxCharacters)
        } else {
            null
        }
        Row(input, note, screen, message.instance)
    }.getOrElse {
        // 反射取文本/前文失败（宿主版本差异）只放弃这一条，绝不把异常抛进宿主的 onBindView：
        // 那属于「滑动聊天记录闪退」。同一类异常只记一行，避免刷屏与同步磁盘写。
        logOnce("build:${it.javaClass.simpleName}", "构造分析输入失败：${it.javaClass.simpleName} ${it.message}")
        null
    }

    /**
     * 一次遍历本屏已绑定行，同时产出三样东西：
     *  - 目标消息**之前**的前文（受 [ModulePrefs.contextLimit] 限制）；
     *  - 本屏同一会话的「谁在说话」序列（互动均衡用，含非文本行——图片/表情也代表发言）；
     *  - 本屏同一会话的原文（话题标签用，只取文本次）。
     *
     * 只从**当前已绑定**的可见行里取，与上游一致（不读数据库、不扫历史）。
     * 命中不了 limit 条时有多少给多少，不做补造。
     *
     * 注意：这里用行在列表里的纵向顺序（而不是 getLocationOnScreen），
     * 既避免在非主线程碰 View 的屏幕坐标，也不受状态栏/输入法偏移影响。
     *
     * 第 16 轮：本屏行列表来自带 TTL 的 [screenSnapshot]，不再每次绑定都重扫整表。
     */
    private fun screenOf(target: View, message: MessageInfo, selfText: String): ChatInsights.Screen {
        val limit = ModulePrefs.contextLimit
        val isSelf = message.isSend == 1
        val ordered = screenSnapshot(message.talker)
        val flags = ArrayList<Boolean>(ordered.size)
        var targetIndex = -1
        for ((index, entry) in ordered.withIndex()) {
            flags += entry.second.isSend == 1
            if (entry.first === target) targetIndex = index
        }
        if (limit <= 0 || targetIndex <= 0) {
            return ChatInsights.Screen(flags, listOf(selfText), emptyList(), selfText, isSelf)
        }
        val context = ordered.subList(maxOf(0, targetIndex - limit), targetIndex)
            .mapNotNull { (_, previous) ->
                val previousText = MessageMetadata.plainText(previous) ?: return@mapNotNull null
                ContextMessage(MessageMetadata.speaker(previous), previousText)
            }
        // 话题素材 = 前文 + 本条正文（从旧到新）。刻意不含目标行之后的消息：
        // 那些是「还没发生」的话，拿它们给这一条贴标签是错的。
        return ChatInsights.Screen(flags, context.map { it.text } + selfText, context, selfText, isSelf)
    }

    /**
     * 气泡点击「分析失败，点击重试」：清掉这条的失败与冷却，**真正重新提交一次**。
     *
     * 之前这里只调 refresh()（仅跑节拍），卡片会停在「正在分析…」而永远没有新请求 ——
     * 点击重试等于没反应，属于必须修掉的逻辑错误。
     */
    fun retryRow(view: View) {
        if (!installed) return
        main.post {
            // 卡片点击进来的主线程消息：这里的任何异常都是闪退，兜住。
            try {
                val message = WeChatMessageViewApi.getBoundMessage(view) ?: return@post
                val row = rowOf(view, message) ?: return@post
                if (row.note != null) return@post
                SignalAnalyzer.retryFailure(row.input.key)
                MoodStore.release(row.input.key)
                submittedAt.remove(row.input.key)
                capacityWaiting.remove(view)
                deferred.remove(view)
                deferredAttempts.remove(view)
                // 失败/超时的行重新真的提交一次：结果要么回填，要么再给一条可见失败。
                handle(view, message)
            } catch (t: Throwable) {
                logOnce("retry:${t.javaClass.simpleName}", "手动重试异常（已忽略）：${t.message}")
            }
        }
    }

    /**
     * 「重新分析本屏」/「重试全部失败」：清空结果、失败与进度后，对当前所有可见行重新提交。
     * 是一次真实的全量重算（会消耗请求额度），因此只在设置页里由用户主动触发。
     */
    fun reanalyzeVisible() {
        if (!installed) return
        onMain {
            submittedAt.clear()
            awaiting.clear()
            capacityWaiting.clear()
            deferred.clear()
            deferredAttempts.clear()
            inputs.clear()
            invalidateScreenSnapshot()
            SignalAnalyzer.clearResults()
            YanwaiBubble.clearAll()
            var count = 0
            for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
                if (rowOf(view, message) == null) continue
                count++
                handle(view, message)
            }
            MoodLog.i("已对 $count 行可见消息重新提交分析")
        }
    }

    /** 「重试全部失败」：只重新提交**还没有结果**的可见行（成功的保持不动，省额度）。 */
    fun reanalyseFailed() {
        if (!installed) return
        onMain {
            SignalAnalyzer.retryAllFailures()
            var count = 0
            for ((view, message) in WeChatMessageViewApi.findBoundViews { true }) {
                val row = rowOf(view, message) ?: continue
                if (row.note != null) continue
                if (MoodStore.get(row.input.key) != null) continue
                submittedAt.remove(row.input.key)
                MoodStore.release(row.input.key)
                count++
                handle(view, message)
            }
            MoodLog.i("已重新提交 $count 行失败的可见消息")
        }
    }

    /** 供设置页/看门狗使用：清空计时器（例如用户手动关闭功能后重新打开）。 */
    fun forgetProgress() {
        submittedAt.clear()
        inputs.clear()
        awaiting.clear()
        capacityWaiting.clear()
        deferred.clear()
        deferredAttempts.clear()
        invalidateScreenSnapshot()
    }

    /** 正在等队列空位的条数（设置页与诊断用）。 */
    val capacityWaitingCount: Int get() = capacityWaiting.size
}
