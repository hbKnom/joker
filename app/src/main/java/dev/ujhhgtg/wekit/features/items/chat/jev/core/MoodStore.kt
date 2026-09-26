package dev.ujhhgtg.wekit.features.items.chat.jev.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条情绪概率，供分析卡画横条。[percent] 已取整，[highlight] 标记模型选中的主情绪。
 */
data class MoodBar(val name: String, val percent: Int, val highlight: Boolean = false)

/** 一条备选解读（候选卡选项 → 概率），卡片用「标签 概率%」展示。 */
data class MoodOption(val label: String, val percent: Int)

/**
 * 分析结果。
 *
 * [label] 是给界面看的短标签（比如「开心」「生气」「敷衍」），
 * [score] 是情绪强度 -1.0（负面）到 1.0（正面），[raw] 留着排查模型返回。
 *
 * [detail] 是给「回插会话」和复制用的多行纯文本；[bars] / [advice] 是同一份结果的
 * 结构化形态，卡片直接照着画 —— 不再靠解析自己的文本行来上色。
 */
data class Mood(
    val label: String,
    val score: Double,
    val risk: Int,
    val raw: String,
    val detail: String = label,
    /** 结构化情绪概率；为空时卡片只显示文字。 */
    val bars: List<MoodBar> = emptyList(),
    /** 建议的下一步动作（没有推荐时为空）。 */
    val advice: String? = null,
    /**
     * 主情绪标签（比如「平静」）。[label] 在协议里表示**结论段位**
     * （场景名 / 下一步动作 / 情绪概率），不是情绪本身；界面上要显示的
     * 「这条话是什么情绪」用这个字段，取不到时退回 [label]。
     */
    val dominant: String? = null,
    /**
     * 情绪判定的置信度（0..1，模型自报；取不到时为 0）。卡片只在 > 0 时展示，
     * 因为它不是校准过的准确率，只是「模型有多确定」的参考。
     */
    val confidence: Double = 0.0,
    /** 场景名（如「邀约安排」），取不到为 null。卡片元信息行（场景 · 阶段）用。 */
    val sceneLabel: String? = null,
    /** 对话阶段（如「等具体事实或细节」），取不到为 null。 */
    val progressLabel: String? = null,
    /** 候选解读的短标题（如「这句可能在给见面留位置」）。 */
    val readingTitle: String? = null,
    /** 候选解读要回答的问题（如「对方是在试探一起活动的意愿吗？」）。 */
    val readingQuestion: String? = null,
    /** 候选解读的概率（已按概率降序取前几项）。 */
    val readingOptions: List<MoodOption> = emptyList(),
    /**
     * 降级/补注：例如「第二轮未完成（429），只展示第一轮情绪概率」。
     * 卡片会把它显式画出来 —— 用户能看到「为什么这条只有情绪」，而不是以为功能坏了。
     */
    val note: String? = null,
)

/**
 * 分析结果缓存 + 运行流水。
 *
 * 约束决定了它的形状：
 * 1. 同一条消息及上下文不能重复请求模型 —— 用会话、消息身份和完整输入做键。
 * 2. 界面线程要能**立刻**拿到结果，不能等网络 —— 所以是「先占位、后填充」。
 * 3. 微信进程可能被回收 —— 只放内存，不做持久化；丢了大不了重新分析。
 *
 * 合并后新增三样（都是「更强大」的部分，不是装饰）：
 * - [record] 流水账：成功和失败都记，设置页能直接看到「最近解读」与失败原因；
 * - [recordScore] / [trendOf] 走势：同一会话最近几句的情绪走向，卡片上给一个 ↑/↓；
 * - [markInserted]：同一条消息只回插一次系统消息，重试与回填不会刷屏。
 */
object MoodStore {

    /** 一条分析流水（成功与失败都记）。 */
    data class Entry(
        val key: String,
        val label: String,
        val talker: String,
        val at: Long,
        val ok: Boolean,
        val note: String = "",
    )

    private const val JOURNAL_LIMIT = 60
    private const val TREND_SAMPLES = 6

    private val cache = ConcurrentHashMap<String, Mood>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val journal = ConcurrentLinkedDeque<Entry>()
    private val trends = ConcurrentHashMap<String, ArrayDeque<Double>>()
    private val inserted = ConcurrentHashMap.newKeySet<String>()
    private val completed = AtomicInteger()
    private val failed = AtomicInteger()

    /** Length-prefix every field so different contexts or message identities never share a result. */
    fun keyOf(text: String, talker: String?, context: List<ContextMessage> = emptyList(),
        messageId: Long = 0, speaker: String = "对方"): String {
        val source = buildString {
            fun field(value: String) { append(value.length).append(':').append(value) }
            field(talker.orEmpty())
            field(messageId.toString())
            field(speaker)
            field(text)
            context.forEach { field(it.speaker); field(it.text) }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): Mood? = cache[key]

    // ------------------------------------------------------------------ 结论复用（不重复分析）

    private const val REUSE_LIMIT = 256

    /**
     * 结论的**复用索引**，键带前缀区分两种身份：
     *  - `i:` + 消息身份（会话 + 消息 id）= 同一条消息的结论；
     *  - `c:` + 内容身份（会话 + 说话人 + 文本 + 上下文，不含消息 id）= 相同文本的结论。
     *
     * 为什么必须有它：显示用的 [AnalysisInput.key] 把**上下文**一起哈希了，于是同一条
     * 消息在「滚动重绑 / 新消息到达导致上下文变化」之后会得到一个全新的键 ——
     * 旧实现拿不到旧结论，就**重新打一次模型**（同一句话反复分析、额度白烧、还卡）。
     * 第 22 轮的要求是「每条消息只分析一次、相同文本不重复分析」，所以复用必须按
     * 「消息身份」和「内容身份」来查，而不是按上下文键。
     *
     * 访问序 LRU 限容：只服务于「当前这段会话的反复绑定」，不需要无限历史。
     */
    private val reuse = object : LinkedHashMap<String, Mood>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mood>?) = size > REUSE_LIMIT
    }
    private val reused = AtomicInteger()

    /** 记下一次复用命中（设置页/诊断用）。 */
    fun markReused() { reused.incrementAndGet() }

    /** 复用命中次数（自本次进程启动起算）。 */
    fun reuseHits(): Int = reused.get()

    /** 记下一条结论的两种身份，供后续同消息/同文本直接复用。 */
    fun rememberReuse(input: AnalysisInput, mood: Mood) {
        runCatching {
            synchronized(reuse) {
                reuse["i:${input.identity}"] = mood
                reuse["c:${contentIdentity(input)}"] = mood
            }
        }
    }

    /**
     * 已经有结论时直接返回（不再打模型），没有则 null。
     *
     * 顺序：结论键 → 同一条消息 → 相同文本（相同上下文）。
     */
    fun settledMood(input: AnalysisInput): Mood? {
        cache[input.key]?.let { return it }
        return runCatching {
            synchronized(reuse) {
                reuse["i:${input.identity}"] ?: reuse["c:${contentIdentity(input)}"]
            }
        }.getOrNull()
    }

    private fun contentIdentity(input: AnalysisInput): String =
        keyOf(input.text, input.talker, input.context, 0L, input.speaker)

    /** 尝试认领一次分析任务；已经在跑或已完成返回 false。 */
    fun claim(key: String): Boolean {
        if (cache.containsKey(key)) return false
        return pending.add(key)
    }

    /** 是否已经有请求在跑（看门狗用它区分「还在等」与「认领丢了」）。 */
    fun isPending(key: String): Boolean = pending.contains(key)

    fun complete(key: String, mood: Mood) {
        cache[key] = mood
        pending.remove(key)
    }

    /** 失败也要释放认领，否则这条消息永远不会重试。 */
    fun release(key: String) {
        pending.remove(key)
    }

    fun size(): Int = cache.size

    /** 还在等结果的条数。气泡卡用它告诉用户「确实在排队」，而不是让卡片看起来卡住了。 */
    fun pendingCount(): Int = pending.size

    // ------------------------------------------------------------------ 流水

    /** 记一条流水。同一个 key 只保留最新一条：重试成功后不该还留着旧的失败记录。 */
    fun record(entry: Entry) {
        runCatching {
            journal.removeIf { it.key == entry.key }
            journal.addFirst(entry)
            while (journal.size > JOURNAL_LIMIT) journal.pollLast()
        }
    }

    /** 最近的流水，新的在前。 */
    fun recent(limit: Int = 12): List<Entry> = runCatching { journal.take(limit) }.getOrDefault(emptyList())

    fun markCompleted() { completed.incrementAndGet() }

    fun markFailed() { failed.incrementAndGet() }

    /** 成功数 / 失败数（自本次进程启动起算），设置页用它显示运行状态。 */
    fun stats(): Pair<Int, Int> = completed.get() to failed.get()

    // ------------------------------------------------------------------ 走势

    /**
     * 走势数据的版本号：每次走势被写入/清空都 +1。
     *
     * 为什么需要：卡片指纹（[YanwaiBubble] 里的 fingerprint）靠它感知「走势变了」——
     * 走势是后台分析线程写的，UI 侧拿不到回调，没有版本号就只能靠轮询或干脆不刷新。
     */
    @Volatile
    var trendVersion: Int = 0
        private set

    /**
     * **按会话**分开的走势版本号：卡片指纹只准用这一个（[trendVersionOf]）。
     *
     * 全局 [trendVersion] 是「一屏卡片一起重排」的元凶：任意一条消息出结论它都会 +1，
     * 于是屏幕上**所有**卡片的指纹一起失配 —— 包括别的会话的、早就出结果的 ——
     * 全部重新排版、重新往宿主行预留高度、整屏重绘。这与之前「把全局队列长度放进指纹」
     * 是同一类错误。走势只影响**同一会话**的卡片，所以版本号也必须按会话递增。
     */
    private val trendVersions = ConcurrentHashMap<String, Int>()

    /** 某个会话的走势版本号（卡片指纹用；会话为空时恒为 0）。 */
    fun trendVersionOf(talker: String): Int =
        if (talker.isEmpty()) 0 else trendVersions[talker] ?: 0

    /** 记下这一句的情绪强度，用来给同一会话的下一张卡算走势。 */
    fun recordScore(talker: String, score: Double) {
        if (talker.isBlank() || !score.isFinite()) return
        val deque = trends.getOrPut(talker) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(score)
            while (deque.size > TREND_SAMPLES) deque.removeFirst()
        }
        trendVersion++
        trendVersions[talker] = (trendVersions[talker] ?: 0) + 1
    }

    /**
     * 走势 = 最新一句 − 之前几句的均值。样本不足 2 条返回 null（不硬编一个箭头出来）。
     */
    fun trendOf(talker: String): Double? {
        val deque = trends[talker] ?: return null
        synchronized(deque) {
            if (deque.size < 2) return null
            val values = deque.toList()
            return values.last() - values.dropLast(1).average()
        }
    }

    /** 同一会话最近几段的情绪强度（旧 → 新），卡片用它画一条走势线。 */
    fun recentScores(talker: String, limit: Int = 12): List<Double> {
        val deque = trends[talker] ?: return emptyList()
        synchronized(deque) { return deque.toList().takeLast(limit) }
    }

    // ------------------------------------------------------------------ 回插去重键

    /**
     * 「回插会话」通道的去重键：**必须与上下文无关**。
     *
     * 以前它复用 [AnalysisInput.key] —— 那个键把上下文也算进哈希，于是同一条消息在
     * 上下文变化（新消息到达、滚动重绑）后会得到一个新键，[markInserted] 形同虚设：
     * 同一条消息被反复回插，用户看到的就是「一条接一条的【潜语 · 平静】」。
     * 现在优先用「会话 + 本地消息 id」（同一台设备上稳定不变），拿不到 id 时退回文本哈希。
     */
    fun insertKeyOf(talker: String, messageId: Long, text: String): String =
        if (messageId > 0L) "$talker#$messageId" else "$talker#t${text.hashCode()}"

    // ------------------------------------------------------------------ 回插去重

    /** 标记「这条消息已经回插过」；返回 true 表示是第一次（可以插）。 */
    fun markInserted(key: String): Boolean = inserted.add(key)

    /** 同一条消息是否已经回插过。 */
    fun isInserted(key: String): Boolean = inserted.contains(key)

    // ------------------------------------------------------------------ 清理

    fun clear() {
        cache.clear()
        pending.clear()
        journal.clear()
        trends.clear()
        trendVersion++
        trendVersions.clear()
        inserted.clear()
        synchronized(reuse) { reuse.clear() }
        reused.set(0)
        completed.set(0)
        failed.set(0)
    }

    /** 只清结果缓存，保留流水与统计（设置页的「清空结果，重新分析」用）。 */
    fun clearResults() {
        cache.clear()
        pending.clear()
        synchronized(reuse) { reuse.clear() }
    }
}

/**
 * 界面上该显示的情绪名。
 *
 * [Mood.label] 是**结论段位**（场景名 / 下一步动作 / 情绪概率），拿它当标题会出现
 * 「潜语 · 情绪概率」这种读不出情绪的名字。这里优先用模型选中的主情绪，
 * 其次取概率最高的一条，最后才退回 [Mood.label]。
 */
fun Mood.dominantName(): String =
    dominant ?: bars.firstOrNull { it.highlight }?.name ?: bars.maxByOrNull { it.percent }?.name ?: label
