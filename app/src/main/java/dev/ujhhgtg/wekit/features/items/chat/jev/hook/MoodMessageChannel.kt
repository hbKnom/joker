package dev.ujhhgtg.wekit.features.items.chat.jev.hook

import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.JevProtocol
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.AnalysisInput
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.Mood
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.features.items.chat.jev.core.dominantName
import dev.ujhhgtg.wekit.features.items.system.servers.WeChatService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 展示通道 2：把解读结论作为系统消息插回原会话。
 *
 * **这个通道默认关闭，而且不该是主要展示方式** —— 聊天里更好的位置是消息旁边的
 * 决策分析卡（[YanwaiBubble]）。用户实机反馈很明确：截图里一屏几十条居中的
 * 【潜语 · 平静】+「情绪：…」把聊天刷得没法看。因此第 15 轮的处置是：
 *
 *  1. **默认关**（[ModulePrefs.displayMessage]），并且升级时一次性把老配置也关掉
 *     （[ModulePrefs.migrateInsertOffOnce]）；
 *  2. 即使打开，也要过五道闸门才允许真的插一条：
 *     作用域 → 只插对方的话 → 新鲜度窗口（默认 60s，可配置）→ 逐会话最小间隔 →
 *     全局突发上限；
 *  3. 去重键改成「会话 + 消息 id」（[MoodStore.insertKeyOf]），与上下文无关 ——
 *     以前复用分析键，上下文一变就换 key，同一条消息会被反复回插。
 *
 * 它仍然是 [SignalAnalyzer] 的一个**订阅者**：与气泡卡共用同一次请求、同一份结果、
 * 同一个作用域与开关，不重复打模型。
 */
object MoodMessageChannel : SignalAnalyzer.SettleListener {

    private const val TAG = "MoodMessageChannel"

    /** 同一条会话两次回插之间的最小间隔：再吵的对话也不会变成一串系统消息。 */
    private const val MIN_INTERVAL_PER_TALKER_MS = 45_000L

    /** 全局突发窗口与上限：任何情况下这段时间里最多插这么多条。 */
    private const val BURST_WINDOW_MS = 10 * 60_000L
    private const val BURST_LIMIT = 4

    @Volatile
    private var installed = false

    /** 会话 -> 上次回插时刻。 */
    private val lastInsertAt = ConcurrentHashMap<String, Long>()

    /** 全局回插时刻（用于突发上限），长度不会超过 [BURST_LIMIT]。 */
    private val recentInserts = ConcurrentLinkedDeque<Long>()

    fun install() {
        if (installed) return
        installed = true
        SignalAnalyzer.addListener(this)
        // 升级即关：上游老实现默认会往会话里插系统消息，用户明确要求去掉这个提醒。
        runCatching { ModulePrefs.migrateInsertOffOnce() }
        MoodLog.i(
            "$TAG 已挂载（回插=${ModulePrefs.displayMessage}，窗口=${ModulePrefs.insertFreshSeconds}s，" +
                "范围=${ModulePrefs.scopeSummary()}）",
        )
    }

    fun uninstall() {
        installed = false
        SignalAnalyzer.removeListener(this)
    }

    /**
     * 会话里能看到的那段文本；与气泡卡同一份结论。
     *
     * 第 14 轮改成**照着结构化字段拼**（情绪横条 / 建议），不再直接把模型那段多行正文
     * 原样贴进来 —— 原来会出现「【潜语解读 · 情绪概率】」「【潜语解读 · 下一步动作】」
     * 这种把「段落名当成情绪」的标题，以及同一份情绪百分比在两行里重复。
     * 现在的固定形状：
     *
     * ```
     * 【潜语 · 平静】
     * 情绪：平静 59% · 不确定 32% · 开心 9%
     * 建议：接住对方那句话，回应自己的感受。
     * ```
     */
    fun format(mood: Mood): String {
        val bars = mood.bars.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") { "${it.name} ${it.percent}%" }
            ?.let { "情绪：$it" }
        // 卡片正文里的「事件 / 判读」等补充信息（去掉与上面重复的情绪行与建议行）
        val extra = mood.detail.lines()
            .filterNot {
                it.startsWith(JevProtocol.header) || it.startsWith("情绪：") || it.startsWith("建议：")
            }
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotBlank() }
        val advice = mood.advice?.takeIf { it.isNotBlank() }?.let { "建议：$it" }
        return listOfNotNull("【潜语 · ${mood.dominantName()}】", bars, extra, advice).joinToString("\n")
    }

    override fun onSettled(input: AnalysisInput, mood: Mood?, reason: String?) {
        if (!installed || mood == null) return
        if (!ModulePrefs.displayMessage) return
        if (!ModulePrefs.inScope(input.talker)) return
        // 自己的话不外插：不然用户会觉得"我自己发的消息也来一条解读"
        if (input.isSelf) return

        val now = System.currentTimeMillis()
        val created = createdAtMs(input)
        val window = ModulePrefs.insertFreshSeconds * 1000L
        if (created > 0 && now - created > window) {
            // 打开历史会话时本屏十几条老消息会一起被分析 —— 这里全部跳过，不插
            return
        }
        val last = lastInsertAt[input.talker]
        if (last != null && now - last < MIN_INTERVAL_PER_TALKER_MS) return
        pruneBurst(now)
        if (recentInserts.size >= BURST_LIMIT) {
            // 固定文案：ModulePrefs.report 内部会按内容折叠重复行，不会刷屏
            ModulePrefs.report("回插已限流：短时间内插入过多，本次跳过")
            return
        }
        // 与上下文无关的稳定去重键：同一条消息（哪怕上下文变了、重试了）只插一次
        if (!MoodStore.markInserted(MoodStore.insertKeyOf(input.talker, input.messageId, input.text))) return

        when (val result = WeChatService.insertSystemMessage(input.talker, format(mood), now)) {
            is WeChatService.Result.Success -> {
                lastInsertAt[input.talker] = now
                recentInserts.addLast(now)
                ModulePrefs.report("已回插解读：${mood.dominant ?: mood.label}")
            }

            is WeChatService.Result.Error -> MoodLog.e("回插解读失败：${result.message}")
        }
    }

    private fun pruneBurst(now: Long) {
        while (true) {
            val head = recentInserts.peekFirst() ?: return
            if (now - head <= BURST_WINDOW_MS) return
            recentInserts.pollFirst()
        }
    }

    /** 微信的 createTime 是秒；这里是秒/毫秒都认（读不到时为 0）。 */
    private fun createdAtMs(input: AnalysisInput): Long =
        if (input.createdAt in 1..9_999_999_999L) input.createdAt * 1000 else input.createdAt
}
