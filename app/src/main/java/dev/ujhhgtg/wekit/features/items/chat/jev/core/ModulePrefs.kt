package dev.ujhhgtg.wekit.features.items.chat.jev.core

import dev.ujhhgtg.wekit.preferences.HotPrefs
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * 言外设置的持久化后端。
 *
 * 上游 wechatmood 是独立 APK，微信进程要通过 ContentProvider 跨进程读设置，
 * 因此有一整套 SettingsProvider / SettingsSync / SettingsSession 桥。
 * 本模块是 Xposed 模块，设置与聊天 hook 同处微信进程，直接走 [WePrefs] 即可，
 * 桥接层全部去掉；上游 [ApiProfiles] 依赖的 channel_{id}_{key,endpoint,model} 键位保持原样。
 *
 * ## 第 16 轮：热路径全部改走 [HotPrefs]（卡顿根因之一，且是最大的一处）
 *
 * [WePrefs] 的存储是 SQLite，**每一次 getBoolean / getString 都是一次真正的 db.query**
 * （建数组、编译语句、开游标、拿全局锁）。而本功能的读点全在热路径上：
 *
 * ```
 * 一次 bind / 一次兜底节拍 / 一次结果回填 =
 *   enabled + displayBubble + scopeAll + scopeTalkers + analyzeSelf + contextLimit
 *   + canAnalyze(enabled + apiKey) + showTrend + 4 个扩展开关 ≈ 12 次 SQLite 查询
 * ```
 *
 * 一屏 20 行、滚动时 30+ bind/s ⇒ **每秒 300+ 次主线程 SQLite 查询**（还没算每次的
 * `arrayOf(key)` 与游标分配）。实测表现就是滑动掉帧、决策卡出现时 UI 线程被
 * `MicroMsg.ThreadWatchDog [dumpAsync][From:ui]` 抓到。
 *
 * 改后：同一批读取全部命中 [HotPrefs] 的 1 秒 TTL 内存缓存（本进程写入立即失效，
 * 所以「设置页保存 → 立刻生效」的语义不变），**每次 bind 的 SQLite 查询从约 12 次降到 0 次**。
 *
 * [uiRevision] 是配套的：卡片渲染指纹只需要读这一个 volatile int 就知道
 * 「配置有没有变」，不必在渲染前把上面那一串偏好重新读一遍做比较。
 */
object ModulePrefs {

    private const val TAG = "YanwaiPrefs"

    // 与上游同名，ApiProfiles 直接引用
    const val KEY_ENABLED = "yanwai_enabled"
    const val KEY_EXPLORE = "yanwai_explore_mode"
    const val KEY_SHOW_BADGE = "yanwai_show_badge"
    const val KEY_API_KEY = "yanwai_api_key"
    const val KEY_API_BASE = "yanwai_api_base"
    const val KEY_API_PROVIDER = "yanwai_api_provider"
    const val KEY_API_MODEL = "yanwai_api_model"
    // 合并「言外潜台词」与「Jev 聊天决策」后新增（键名沿用 yanwai_ 前缀，老用户配置不丢）
    const val KEY_DISPLAY_MESSAGE = "yanwai_display_message"
    const val KEY_SCOPE_ALL = "yanwai_scope_all"
    const val KEY_SCOPE_TALKERS = "yanwai_scope_talkers"
    const val KEY_SCOPE_TALKER_NAMES = "yanwai_scope_talker_names"
    /** 上下文条数（0-20，默认 10，与上游 [MessagePolicy.MAX_CONTEXT_MESSAGES] 一致）。 */
    const val KEY_CONTEXT_LIMIT = "yanwai_context_limit"
    /** 是否连自己发的消息一起分析（**第 16 轮起默认开**：用户要求「每条文本消息都要被分析」）。 */
    const val KEY_ANALYZE_SELF = "yanwai_analyze_self"
    /** 分析卡是否默认展开完整解读（默认收起，只留主情绪 + 一条建议）。 */
    const val KEY_CARD_EXPANDED = "yanwai_card_expanded"
    /** 分析卡是否显示「与前几句对比」。 */
    const val KEY_SHOW_TREND = "yanwai_show_trend"
    /**
     * 「回插会话」的新鲜度窗口（秒）。只回插最近这么久之内发生的消息 ——
     * 打开历史会话时本屏十几条老消息会被一起分析，窗口太大就会瞬间插出十几条系统消息。
     */
    const val KEY_INSERT_FRESH_SECONDS = "yanwai_insert_fresh_seconds"
    /** 一次性迁移标记：把第 14 轮之前默认可能开着的「回插会话」强制关一次。 */
    const val KEY_INSERT_OFF_MIGRATED = "yanwai_insert_off_migrated"

    // ------------------------------------------------------------------ 第 16 轮新增的上限/扩展键

    /** 单条消息字数上限（超过则跳过并明确标注，不再静默丢弃）。 */
    const val KEY_MAX_CHARS = "yanwai_max_chars"
    /** 待分析队列上限：超限的消息**排队等空位**（不会丢），卡片显示「队列已满」。 */
    const val KEY_QUEUE_CAP = "yanwai_queue_cap"
    /** 请求之间的最小间隔（毫秒），用于摊平突发、避开限流；命中 429 会自动加倍。 */
    const val KEY_REQUEST_INTERVAL = "yanwai_request_interval_ms"
    /** 扩展：卡内情绪趋势小结（均值/波动/走向）。 */
    const val KEY_CARD_TREND = "yanwai_card_trend"
    /** 扩展：互动均衡（本屏双方发言占比）。 */
    const val KEY_CARD_BALANCE = "yanwai_card_balance"
    /** 扩展：话题标签。 */
    const val KEY_CARD_TOPICS = "yanwai_card_topics"
    /** 扩展：建议分级与风险提示。 */
    const val KEY_CARD_LEVEL = "yanwai_card_level"

    const val DEFAULT_INSERT_FRESH_SECONDS = 60
    const val MIN_INSERT_FRESH_SECONDS = 10
    const val MAX_INSERT_FRESH_SECONDS = 1800

    const val DEFAULT_MAX_CHARS = 2000
    const val MIN_MAX_CHARS = 200
    const val MAX_MAX_CHARS = 8000

    const val DEFAULT_QUEUE_CAP = 60
    const val MIN_QUEUE_CAP = 5
    const val MAX_QUEUE_CAP = 500

    const val DEFAULT_REQUEST_INTERVAL_MS = 700
    const val MIN_REQUEST_INTERVAL_MS = 0
    const val MAX_REQUEST_INTERVAL_MS = 5000

    const val MAX_CONTEXT_LIMIT = 20

    /**
     * 配置版本号：任何会改变卡片外观/行为的 setter 都 +1。
     *
     * 卡片渲染时只读这一个 volatile int，就能判断「要不要重画」——
     * 不必在每次绑定/每拍把十几个偏好挨个读出来比一遍（那正是热路径开销的来源）。
     */
    private val revisions = AtomicInteger()
    val uiRevision: Int get() = revisions.get()

    /** 分析范围专用版本号：范围变化时要主动清掉越界的卡片，与外观重画分开处理。 */
    private val scopeRevisions = AtomicInteger()
    val scopeRevision: Int get() = scopeRevisions.get()

    // ------------------------------------------------------------------ 展示通道与范围

    /**
     * 展示通道 1：在消息下方挂分析卡。
     *
     * 与老键 [KEY_SHOW_BADGE] 是同一个开关（合并前叫「显示徽标」），默认**开** ——
     * 合并后的功能主展示就是这张卡，默认关掉等于装了没反应。
     */
    val displayBubble get() = HotPrefs.bool(KEY_SHOW_BADGE, true)

    /**
     * 展示通道 2：把结论作为系统消息插回会话。
     *
     * **默认关**。即使开着，[dev.ujhhgtg.wekit.features.items.chat.jev.hook.MoodMessageChannel]
     * 也还有新鲜度窗口、逐会话最小间隔与突发上限三道闸门。
     */
    val displayMessage get() = HotPrefs.bool(KEY_DISPLAY_MESSAGE, false)

    /** 是否对所有会话生效（默认 true）。 */
    val scopeAll get() = HotPrefs.bool(KEY_SCOPE_ALL, true)

    /** 当前选定的会话（wxId）。 */
    val scopeTalkers: Set<String> get() = scopeTalkers()

    /** 写回分析范围。[names] 只用于会话列表的可读展示（[scopeSummary]）。 */
    fun setScope(all: Boolean, talkers: Set<String>, names: Map<String, String>) {
        val ordered = talkers.toList()
        putBool(KEY_SCOPE_ALL, all)
        putString(KEY_SCOPE_TALKERS, ordered.joinToString("\n"))
        putString(KEY_SCOPE_TALKER_NAMES, ordered.joinToString("\n") { names[it] ?: it })
        scopeRevisions.incrementAndGet()
    }

    /**
     * 会话名单缓存：原始串没变就复用上一次解析出的 Set。
     *
     * [HotPrefs] 已经把「读串」变成内存命中，但每次 `split('\n')` 仍会新建数组与字符串。
     * [inScope] 在每次绑定/每次提交/每次渲染都会走到，所以这里再缓存一层解析结果。
     */
    @Volatile
    private var talkerRaw: String? = null
    @Volatile
    private var talkerParsed: Set<String> = emptySet()

    private fun scopeTalkers(): Set<String> {
        val raw = HotPrefs.string(KEY_SCOPE_TALKERS, "")
        if (raw == talkerRaw) return talkerParsed
        val parsed = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        talkerRaw = raw
        talkerParsed = parsed
        return parsed
    }

    /**
     * 是否分析这个会话。
     *
     * 「全部聊天」为默认；即使选了「仅选定聊天」，只要列表是空的也放行 ——
     * 避免用户把开关拨到「仅选定」却还没选会话时，功能看起来像坏了。
     */
    fun inScope(talker: String?): Boolean {
        if (scopeAll) return true
        val list = scopeTalkers()
        if (list.isEmpty()) return true
        if (talker.isNullOrBlank()) return false
        return list.contains(talker)
    }

    fun setDisplayMessage(value: Boolean) = putBool(KEY_DISPLAY_MESSAGE, value)

    // ------------------------------------------------------------------ 分析口径

    /** 上下文条数：夹在 0..20；0 表示不带前文（只分析这一句）。 */
    val contextLimit get() = HotPrefs.int(KEY_CONTEXT_LIMIT, MessagePolicy.MAX_CONTEXT_MESSAGES)
        .coerceIn(0, MAX_CONTEXT_LIMIT)

    fun setContextLimit(value: Int) = putInt(KEY_CONTEXT_LIMIT, value.coerceIn(0, MAX_CONTEXT_LIMIT))

    /** 是否连自己发的消息一起分析。**默认开**（要求：被选定会话的每条文本消息都要被分析）。 */
    val analyzeSelf get() = HotPrefs.bool(KEY_ANALYZE_SELF, true)

    fun setAnalyzeSelf(value: Boolean) = putBool(KEY_ANALYZE_SELF, value)

    /** 单条消息字数上限，夹在 [MIN_MAX_CHARS]..[MAX_MAX_CHARS]（默认 2000）。 */
    val maxChars get() = HotPrefs.int(KEY_MAX_CHARS, DEFAULT_MAX_CHARS)
        .coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)

    fun setMaxChars(value: Int) = putInt(KEY_MAX_CHARS, value.coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS))

    /** 待分析队列上限，夹在 [MIN_QUEUE_CAP]..[MAX_QUEUE_CAP]（默认 60）。 */
    val queueCap get() = HotPrefs.int(KEY_QUEUE_CAP, DEFAULT_QUEUE_CAP)
        .coerceIn(MIN_QUEUE_CAP, MAX_QUEUE_CAP)

    fun setQueueCap(value: Int) = putInt(KEY_QUEUE_CAP, value.coerceIn(MIN_QUEUE_CAP, MAX_QUEUE_CAP))

    /** 请求最小间隔（毫秒），夹在 [MIN_REQUEST_INTERVAL_MS]..[MAX_REQUEST_INTERVAL_MS]。 */
    val requestIntervalMs get() = HotPrefs.int(KEY_REQUEST_INTERVAL, DEFAULT_REQUEST_INTERVAL_MS)
        .coerceIn(MIN_REQUEST_INTERVAL_MS, MAX_REQUEST_INTERVAL_MS)

    fun setRequestInterval(value: Int) = putInt(
        KEY_REQUEST_INTERVAL,
        value.coerceIn(MIN_REQUEST_INTERVAL_MS, MAX_REQUEST_INTERVAL_MS),
    )

    // ------------------------------------------------------------------ 卡片外观与扩展

    /** 分析卡默认展开完整解读（默认收起：一行主情绪 + 一条建议，安静且省高度）。 */
    val cardExpanded get() = HotPrefs.bool(KEY_CARD_EXPANDED, false)

    fun setCardExpanded(value: Boolean) = putBool(KEY_CARD_EXPANDED, value)

    /** 分析卡是否显示「与前几句对比」（默认开）。 */
    val showTrend get() = HotPrefs.bool(KEY_SHOW_TREND, true)

    fun setShowTrend(value: Boolean) = putBool(KEY_SHOW_TREND, value)

    /** 扩展 1：卡内情绪趋势小结（近 N 条均值/波动/走向）。 */
    val showTrendPanel get() = HotPrefs.bool(KEY_CARD_TREND, true)

    fun setShowTrendPanel(value: Boolean) = putBool(KEY_CARD_TREND, value)

    /** 扩展 2：互动均衡（本屏我/对方发言占比 + 连续发言提示）。 */
    val showBalance get() = HotPrefs.bool(KEY_CARD_BALANCE, true)

    fun setShowBalance(value: Boolean) = putBool(KEY_CARD_BALANCE, value)

    /** 扩展 3：话题标签。 */
    val showTopics get() = HotPrefs.bool(KEY_CARD_TOPICS, true)

    fun setShowTopics(value: Boolean) = putBool(KEY_CARD_TOPICS, value)

    /** 扩展 4：建议分级与风险提示。 */
    val showLevel get() = HotPrefs.bool(KEY_CARD_LEVEL, true)

    fun setShowLevel(value: Boolean) = putBool(KEY_CARD_LEVEL, value)

    /** 回插会话的新鲜度窗口（秒），夹在 10..1800。 */
    val insertFreshSeconds get() = HotPrefs.int(KEY_INSERT_FRESH_SECONDS, DEFAULT_INSERT_FRESH_SECONDS)
        .coerceIn(MIN_INSERT_FRESH_SECONDS, MAX_INSERT_FRESH_SECONDS)

    fun setInsertFreshSeconds(value: Int) = putInt(
        KEY_INSERT_FRESH_SECONDS,
        value.coerceIn(MIN_INSERT_FRESH_SECONDS, MAX_INSERT_FRESH_SECONDS),
    )

    /**
     * 一次性把「回插会话」关掉。
     *
     * 上游老实现（合并前的「Jev 聊天决策」）默认就会往会话里插系统消息，用户已经明确要求
     * 「去除这个提醒」。这里只在第一次运行新版时强制关一次，之后就完全由用户自己决定。
     * 幂等。
     */
    fun migrateInsertOffOnce() {
        if (HotPrefs.bool(KEY_INSERT_OFF_MIGRATED, false)) return
        putBool(KEY_DISPLAY_MESSAGE, false)
        putBool(KEY_INSERT_OFF_MIGRATED, true)
    }

    fun scopeSummary(): String {
        if (scopeAll) return "全部聊天"
        val names = HotPrefs.string(KEY_SCOPE_TALKER_NAMES, "").split('\n').filter { it.isNotBlank() }
        return if (names.isEmpty()) "全部聊天" else "已选 ${names.size} 个聊天"
    }

    // ------------------------------------------------------------------ 开关与状态

    private val prefs get() = WePrefs

    /** 与 [HotPrefs] 的 1 秒 TTL 对齐：跨进程写入最多 1 秒可见，这里只留调用点语义。 */
    fun reload(force: Boolean = false) = Unit

    fun init() = Unit

    val enabled get() = HotPrefs.bool(KEY_ENABLED, false)
    val exploreMode get() = HotPrefs.bool(KEY_EXPLORE, false)
    val showBadge get() = HotPrefs.bool(KEY_SHOW_BADGE, true)
    val apiKey get() = HotPrefs.string(KEY_API_KEY, "")
    val canAnalyze get() = enabled && apiKey.isNotBlank()

    fun apiSettings(): ApiSettings = runCatching {
        ApiSettings.fromInput(
            endpoint = prefs.getStringOrDef(KEY_API_BASE, ""),
            apiKey = apiKey,
            providerId = prefs.getStringOrDef(KEY_API_PROVIDER, ""),
            model = prefs.getStringOrDef(KEY_API_MODEL, ""),
        )
    }.getOrElse { ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "") }

    fun setSwitch(key: String, value: Boolean) {
        require(key == KEY_ENABLED || key == KEY_SHOW_BADGE) { "不支持的开关：$key" }
        putBool(key, value)
    }

    // ------------------------------------------------------------------ 写入（统一失效缓存 + 抬版本号）

    /** 写布尔：落库 → 立刻失效该 key 的热缓存 → 抬配置版本号（卡片据此重画）。 */
    private fun putBool(key: String, value: Boolean) {
        runCatching { prefs.putBool(key, value) }
        HotPrefs.invalidate(key)
        revisions.incrementAndGet()
    }

    private fun putInt(key: String, value: Int) {
        runCatching { prefs.putInt(key, value) }
        HotPrefs.invalidate(key)
        revisions.incrementAndGet()
    }

    private fun putString(key: String, value: String) {
        runCatching { prefs.putString(key, value) }
        HotPrefs.invalidate(key)
        revisions.incrementAndGet()
    }

    /** 上游把运行状态回传给独立 App 的设置页；这里只落模块日志。 */
    fun report(status: String) {
        val line = MoodLog.sanitize(status).take(200)
        val now = System.currentTimeMillis()
        synchronized(REPORT_LOCK) {
            // 去重/限流：重复内容只记一次，并折叠计数。
            if (line == lastReport && now - lastReportAt < REPORT_DEDUPE_MS) {
                suppressedReports++
                lastReportAt = now
                return
            }
            val folded = suppressedReports
            suppressedReports = 0
            lastReport = line
            lastReportAt = now
            WeLogger.i(TAG, if (folded > 0) "$line（同样内容已折叠 $folded 条）" else line)
        }
    }

    private val REPORT_LOCK = Any()
    private var lastReport: String? = null
    private var lastReportAt = 0L
    private var suppressedReports = 0
    private const val REPORT_DEDUPE_MS = 10_000L
}
