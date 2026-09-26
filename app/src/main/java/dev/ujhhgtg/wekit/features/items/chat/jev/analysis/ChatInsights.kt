package dev.ujhhgtg.wekit.features.items.chat.jev.analysis

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.jev.core.Mood
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ContextMessage
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore

/**
 * 潜语卡片「更强大」的那一半：**纯计算**的会话洞察。
 *
 * 设计约束（都很硬）：
 *  - **不碰 UI、不碰资源、不碰数据库**：全部输入都是内存里已有的东西
 *    （本条 [Mood] + 本屏可见行的说话人/文本 + [MoodStore] 的走势样本），
 *    所以它可以在主线程上被调用，也可以在 card 的 Row 里缓存一次复用。
 *  - **只产出 res id 与数字**，文案留到渲染时用 [dev.ujhhgtg.wekit.features.items.chat.jev.core.JevText]
 *    取 —— 后台线程取资源既慢又不安全，而且这样三语文案天然一致。
 *  - 每条规则都要**说得出依据**（数值、条数、占比），不产生无法解释的结论。
 *
 * 五项扩展：
 *  1. [Balance]  互动均衡：本屏双方发言占比 + 连续发言（谁在等回应）；
 *  2. [Trend]    情绪趋势小结：近 N 条均值 / 波动 / 走向；
 *  3. [Topic]    话题标签：关键词字典命中，最多 4 个；
 *  4. [Level]    建议分级：推进 / 稳妥 / 观察 / 暂缓；
 *  5. [Risk]     风险提示：单向、负面、低置信度三类，带具体数字。
 */
object ChatInsights {

    /** 本屏（同一会话可见行）的互动概览，按时间从旧到新统计。 */
    data class Balance(
        val self: Int,
        val other: Int,
        /** 末尾连续由对方发言的条数（>0 表示球在你这）。 */
        val otherRun: Int,
        /** 末尾连续由自己发言的条数。 */
        val selfRun: Int,
    ) {
        val total: Int get() = self + other
        val selfPercent: Int get() = if (total == 0) 0 else (self * 100) / total
        val otherPercent: Int get() = if (total == 0) 0 else 100 - selfPercent
    }

    /** 情绪趋势小结。[direction]：1 转好、-1 转差、0 平稳。 */
    data class Trend(val samples: Int, val mean: Double, val swing: Double, val direction: Int)

    /** 建议强度四档。 */
    enum class Level { ADVANCE, STEADY, WATCH, HOLD }

    /** 风险：0 = 低、1 = 中、2 = 高；[notes] 是逐条依据（res id + 格式化参数）。 */
    data class Risk(val level: Int, val notes: List<Pair<Int, Array<Any>>>)

    /** 一条消息的全部洞察结果。[topics] / [levelDesc] 是 res id，渲染时再取文案。 */
    data class Insight(
        val topics: List<Int> = emptyList(),
        val balance: Balance? = null,
        val trend: Trend? = null,
        val level: Level? = null,
        val levelDesc: Int = 0,
        val risk: Risk? = null,
    ) {
        /** 没有任何一条洞察有内容时，卡片就少画一整块，不要留空白标题。 */
        val isEmpty: Boolean
            get() = topics.isEmpty() && balance == null && trend == null && level == null
    }

    /**
     * 本屏素材：在**绑定的那一刻**由扫描器一次性算好（见 `YanwaiScanner.screenOf`）。
     *
     * 刻意做成不可变的值对象：渲染路径只读它，不再回头去扫屏幕上的 View。
     * [speakerFlags] 是同一会话可见行的「是否是我发的」，从旧到新（含非文本行，
     * 图片/表情也代表「这一轮是谁在说」）；[texts] 是前文 + 本条原文（从旧到新），
     * 供话题标签提取。
     */
    data class Screen(
        val speakerFlags: List<Boolean> = emptyList(),
        val texts: List<String> = emptyList(),
        /**
         * 本次遍历顺带产出的前文（受 [ModulePrefs.contextLimit] 截断，旧 → 新，不含本条）。
         *
         * 放在这里是为了**一次本屏遍历同时喂两处**：模型输入要的 [ContextMessage] 和
         * 话题标签要的纯文本。第 16 轮之前前文是另一次 `findBoundViews` 全表扫描算的，
         * 每次绑定都要多走一遍本屏 View。
         */
        val context: List<ContextMessage> = emptyList(),
    )

    /** 卡片最多展示几个话题标签：再多就把一行挤爆了。 */
    const val MAX_TOPICS = 4

    /** 趋势小结最少要几条样本才给结论（少于这个数只能说「样本不足」，不如不说）。 */
    private const val MIN_TREND_SAMPLES = 3

    /**
     * 生成一条消息的洞察。
     *
     * @param mood 本条的分析结果
     * @param screen 本屏素材（谁在说话 + 前文/本条原文），绑定那一刻算好
     * @param trendScores 同一会话最近几段的情绪强度（旧 → 新），来自 [MoodStore.recentScores]
     */
    fun build(mood: Mood, screen: Screen, trendScores: List<Double>): Insight {
        val balance = balance(screen.speakerFlags)
        val trend = trend(trendScores)
        val level = level(mood, balance)
        val risk = risk(mood, balance)
        return Insight(
            topics = topics(screen.texts),
            balance = balance,
            trend = trend,
            level = level,
            levelDesc = levelDesc(level),
            risk = risk,
        )
    }

    // ------------------------------------------------------------------ 互动均衡

    fun balance(speakerFlags: List<Boolean>): Balance? {
        if (speakerFlags.isEmpty()) return null
        val self = speakerFlags.count { it }
        val other = speakerFlags.size - self
        var otherRun = 0
        for (i in speakerFlags.indices.reversed()) {
            if (speakerFlags[i]) break
            otherRun++
        }
        var selfRun = 0
        for (i in speakerFlags.indices.reversed()) {
            if (!speakerFlags[i]) break
            selfRun++
        }
        return Balance(self, other, otherRun, selfRun)
    }

    // ------------------------------------------------------------------ 趋势小结

    fun trend(scores: List<Double>): Trend? {
        if (scores.size < MIN_TREND_SAMPLES) return null
        val usable = scores.filter { it.isFinite() }
        if (usable.size < MIN_TREND_SAMPLES) return null
        val mean = usable.average()
        val swing = (usable.max() - usable.min()) / 2.0
        // 走向 = 最新一句相对之前几句均值的变化；阈值取 0.08，避免把噪声当趋势。
        val previous = usable.dropLast(1).average()
        val delta = usable.last() - previous
        val direction = when {
            delta > 0.08 -> 1
            delta < -0.08 -> -1
            else -> 0
        }
        return Trend(usable.size, mean, swing, direction)
    }

    // ------------------------------------------------------------------ 话题标签

    /**
     * 话题字典：res id + 关键词（中英各一份，关键词命中即算）。
     *
     * 刻意做成**关键词字典**而不是再打一次模型：零成本、零延迟、离线可用，
     * 而且是「可解释」的 —— 用户看得见是哪几个词命中。命中多个取前 [MAX_TOPICS] 个。
     */
    private val TOPIC_RULES: List<Pair<Int, List<String>>> = listOf(
        R.string.jev_topic_meet to listOf("见面", "见个面", "约", "出来", "有空吗", "碰头", "meet"),
        R.string.jev_topic_meal to listOf("吃饭", "吃个", "饭", "外卖", "奶茶", "咖啡", "餐厅", "dinner", "lunch"),
        R.string.jev_topic_time to listOf("几点", "时间", "明天", "后天", "今天", "晚上", "周末", "上午", "下午", "time", "tomorrow"),
        R.string.jev_topic_place to listOf("地方", "在哪", "地址", "位置", "家里", "公司楼下", "place", "where"),
        R.string.jev_topic_work to listOf("工作", "上班", "加班", "老板", "同事", "项目", "会议", "出差", "work", "busy"),
        R.string.jev_topic_study to listOf("学习", "考试", "论文", "作业", "上课", "答辩", "study", "exam"),
        R.string.jev_topic_feeling to listOf("开心", "难过", "生气", "烦", "累", "心情", "高兴", "委屈", "焦虑", "sad", "happy", "tired"),
        R.string.jev_topic_care to listOf("注意身体", "早点休息", "吃饭没", "照顾好", "多喝水", "别熬夜", "take care"),
        R.string.jev_topic_apology to listOf("抱歉", "对不起", "不好意思", "原谅", "sorry", "apolog"),
        R.string.jev_topic_money to listOf("钱", "工资", "花销", "借", "还你", "付款", "账单", "money", "pay"),
        R.string.jev_topic_family to listOf("爸", "妈", "父母", "家里", "孩子", "家人", "family", "mom", "dad"),
        R.string.jev_topic_friend to listOf("朋友", "哥们", "闺蜜", "同学", "friend"),
        R.string.jev_topic_travel to listOf("旅行", "旅游", "机票", "高铁", "出发", "行程", "酒店", "trip", "flight"),
        R.string.jev_topic_fun to listOf("电影", "游戏", "追剧", "综艺", "音乐", "演唱会", "球", "唱歌", "movie", "game"),
        R.string.jev_topic_health to listOf("医院", "感冒", "吃药", "不舒服", "体检", "睡", "健康", "sick", "doctor"),
        R.string.jev_topic_gift to listOf("礼物", "送你", "生日", "节日", "红包", "纪念日", "gift", "birthday"),
    )

    fun topics(texts: List<String>): List<Int> {
        if (texts.isEmpty()) return emptyList()
        // 只扫最近 4 段：再往前跟当前这句的相关性已经很低，扫全文纯属浪费主线程时间。
        val window = texts.takeLast(4).map { it.lowercase() }
        val joined = window.joinToString("\n").let {
            if (it.length > 4000) it.substring(0, 4000) else it
        }
        if (joined.isEmpty()) return emptyList()
        val hits = ArrayList<Int>(MAX_TOPICS)
        for ((res, keywords) in TOPIC_RULES) {
            if (hits.size >= MAX_TOPICS) break
            if (keywords.any { joined.contains(it) }) hits += res
        }
        return hits
    }

    // ------------------------------------------------------------------ 建议分级

    fun level(mood: Mood, balance: Balance?): Level {
        val score = mood.score
        val confidence = mood.confidence
        // 明确负面、或被判高风险 → 暂缓；对方在等回应也倾向先观察。
        return when {
            mood.risk >= 60 || score <= -0.35 -> Level.HOLD
            score >= 0.25 && confidence >= 0.5 && (balance?.otherRun ?: 0) <= 1 -> Level.ADVANCE
            score < 0 || mood.risk >= 35 || (balance?.otherRun ?: 0) >= 3 -> Level.WATCH
            else -> Level.STEADY
        }
    }

    fun levelDesc(level: Level): Int = when (level) {
        Level.ADVANCE -> R.string.jev_level_desc_advance
        Level.STEADY -> R.string.jev_level_desc_steady
        Level.WATCH -> R.string.jev_level_desc_watch
        Level.HOLD -> R.string.jev_level_desc_hold
    }

    fun levelLabel(level: Level): Int = when (level) {
        Level.ADVANCE -> R.string.jev_level_advance
        Level.STEADY -> R.string.jev_level_steady
        Level.WATCH -> R.string.jev_level_watch
        Level.HOLD -> R.string.jev_level_hold
    }

    // ------------------------------------------------------------------ 风险提示

    fun risk(mood: Mood, balance: Balance?): Risk? {
        val notes = ArrayList<Pair<Int, Array<Any>>>(3)
        var level = 0

        if (mood.score <= -0.4 || mood.risk >= 60) {
            level = maxOf(level, if (mood.risk >= 60) 2 else 1)
            notes += R.string.jev_risk_negative to emptyArray()
        }
        val otherRun = balance?.otherRun ?: 0
        if (otherRun >= 3) {
            level = maxOf(level, 1)
            notes += R.string.jev_risk_other_run to arrayOf<Any>(otherRun)
        }
        val selfRun = balance?.selfRun ?: 0
        if (selfRun >= 3) {
            level = maxOf(level, 1)
            notes += R.string.jev_risk_self_run to arrayOf<Any>(selfRun)
        }
        if (mood.confidence in 0.0..0.35) {
            notes += R.string.jev_risk_low_confidence to arrayOf<Any>((mood.confidence * 100).toInt())
        }
        if (notes.isEmpty()) return null
        return Risk(level, notes)
    }
}
