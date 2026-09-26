package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 聊天记录分析 —— 分析引擎
 *
 * 完整复刻 WeKit Java 脚本 v0.3.4 的统计口径：
 * 核心指标 / 内容载体偏好 / 全天活跃频次 / 发言排行 / 高频词 / 情绪指纹 / 废话程度鉴定。
 * 查询使用 WeDatabaseApi（WCDB 主数据库）范围 SQL，分页读取避免一次性 OOM。
 */
object ChatAnalysisEngine {

    const val FEATURE_AI = "AI 总结"
    const val FEATURE_STATS = "本地统计"
    const val FEATURE_RANK = "发言排行"
    val ALL_FEATURES = listOf(FEATURE_AI, FEATURE_STATS, FEATURE_RANK)

    /** 每页查询条数（防止大群全量 OOM） */
    private const val PAGE_SIZE = 1000

    /**
     * 单条消息喂给 AI 的字符上限（超出截断，避免一条长文吃掉整个上下文）。
     *
     * 用户 2026-09-22 反馈「内容文本的上限真的极少」：原值 500 字，一条长消息（比如群里
     * 转发的长文、长公告）几乎只剩开头。默认放到 2000 字，并且改成可以由
     * [ChatAnalysisEngine.analyze] 的 `lineMax` 参数覆盖（设置页可调）。
     */
    const val TRANSCRIPT_LINE_MAX_DEFAULT = 4000

    /**
     * 喂给 AI 的整段对话文本总量的**默认**硬上限。
     *
     * 用户反馈原值 60000 字太少（2026-09-22 二轮反馈「内容文本的上限极少」，再放到 48 万字）、内容不够 AI 容易答错，这里默认放到 240000 字
     * （中文约 1 字 ≈ 0.6~1 token，24 万字约 15~24 万 token，适配 32 万上下文的模型；
     * 小上下文模型请把设置里的「喂给 AI 的文本上限」调小，否则服务端会返回上下文超限）。
     * 真正的硬上限由调用方按设置传入，这里只是兜底默认值。
     */
    const val TRANSCRIPT_MAX_CHARS_DEFAULT = 480_000

    /**
     * 话题 / 沉默的判定阈值：30 分钟。
     *
     * 一个阈值同时干两件事（口径自洽）：
     *  - 间隔 ≥ 30 分钟 → 记一次「沉默」（用于【沉默与主动性】）；
     *  - 同时切开一个「话题段」（用于【话题切换】），于是「话题段数 = 沉默次数 + 1」。
     *
     * 只做整数比较，不产生任何分配，放在主扫描里是 O(1)。
     */
    private const val TOPIC_BREAK_MS = 30L * 60L * 1000L

    /** 消息长度画像里保留的最长摘录条数（定长插入，零额外内存） */
    private const val EXCERPT_N = 3

    /** 摘录正文的最大展示字数（超长只留开头，避免报告里塞进一篇长文） */
    private const val EXCERPT_MAX = 46

    /** 口头禅只扫这个长度以内的正文：长文（转发长帖）会把语气词分布整个拉偏 */
    private const val CLICHE_BODY_MAX = 300

    /**
     * 第 15 轮：秒回阈值（10 秒）。
     *
     * 回复延迟分布的第一档，也是「秒回率」的分子，并用来归因「谁最爱秒回」。
     * 只有整数比较，零分配。
     */
    private const val FAST_REPLY_MS = 10_000L

    /**
     * 第 15 轮：连击被判为「被打断」的最小长度。
     *
     * 2 条以内的换人属于正常你来我往；只有同一个人的连击 ≥3 条时被别人接上，
     * 才算真正的「打断」。不卡这个下限的话「打断次数」就等于「发言轮次」，指标没有信息量。
     */
    private const val INTERRUPT_MIN_STREAK = 3

    /**
     * 第 15 轮：话题词只扫这个长度以内的正文。
     *
     * 与口头禅同一口径：话题词表是固定词表逐词 `contains`，长文（转发长帖）会让单条消息的
     * 扫描代价随正文长度线性上涨，而长文里的话题词分布也不代表聊天习惯。
     */
    private const val TOPIC_BODY_MAX = 300

    // ---------------- 第 16 轮：六个扩展维度的口径常量 ----------------

    /**
     * 第 16 轮：趋势图的分桶上限（= 定长数组长度）。
     *
     * 实际桶数按时间跨度在 12 / 10 / 7 里选（见 [analyze] 里的 `trendBuckets`），
     * 这里只钉死"最多 12 个 int"这个上限 —— 与第 14/15 轮同一套内存纪律：
     * 新维度的状态**不随消息条数增长**，大群扫描的内存与耗时不会因为多六个维度出现阶跃。
     */
    private const val TREND_MAX_BUCKETS = 12

    /** 第 16 轮：细粒度回复间隔档数（5 秒 / 15 秒 / 30 秒 / 1 分 / 3 分 / 10 分 / 30 分） */
    private const val LATENCY_FINE_BANDS = 7

    /**
     * 第 16 轮：深夜口径（23:00 之后、05:00 之前）。
     *
     * 与【昼夜结构】的「深夜 0-5 点」刻意错开一格：那边回答"整体作息偏不偏晚"，
     * 这边回答"深夜聊的话题和白天有什么不同"，含 23 点才符合"睡前那一段"的直觉。
     */
    private const val NIGHT_FROM_HOUR = 23
    private const val NIGHT_TO_HOUR = 5

    /**
     * 第 16 轮：回复速度分档标签与上界（一一对应）。
     *
     * 标签里**不含空格**（分布行的标签列按最后一个空格切分值）也不含全角冒号（会被当成指标行），
     * 且必须含中文：零值的档位没有 █，弹窗与 PNG 两侧的 PLAIN_COUNT 分支靠"标签含中文"才认得出。
     */
    private val LATENCY_FINE_LABELS = listOf("5秒内", "15秒内", "30秒内", "1分内", "3分内", "10分内", "30分内")

    /** 深夜时段判定：只需一次整数比较，热路径零分配 */
    private fun isNightHour(hour: Int): Boolean = hour >= NIGHT_FROM_HOUR || hour < NIGHT_TO_HOUR

    /** 回复间隔 → 细档下标（越界一律归最后一档，绝不返回非法下标） */
    private fun fineBand(gap: Long): Int = when {
        gap <= 5_000L -> 0
        gap <= 15_000L -> 1
        gap <= 30_000L -> 2
        gap <= 60_000L -> 3
        gap <= 180_000L -> 4
        gap <= 600_000L -> 5
        else -> 6
    }

    /**
     * 第 17 轮：长句口径（>20 字）。
     *
     * 与【废话程度鉴定】的「5 字以下 / 5-20 / 20-50」分档上界逐字一致 ——
     * 两边若用不同的分界，报告里会出现"长句占比 30% 但废话率 0%"这种自相矛盾的读数。
     */
    private const val LONG_BODY_MIN = 20

    /**
     * 第 17 轮：提问被算作"有人接"的宽限窗口（10 分钟）。
     *
     * 与【回应速度画像】的 10 分档同源：超过 10 分钟才来的下一句，更像是新话题，
     * 而不是对上一个疑问句的回答。
     */
    private const val ASK_WINDOW_MS = 600_000L

    /** 第 17 轮：复读金句在报告里最多保留的原文字数（超长正文只留开头，避免撞上摘录上限） */
    private const val REPEAT_SAMPLE_MAX = 24

    // ---------------- 第 18 轮：事件 / 节奏 / 关系网络的口径常量 ----------------

    /**
     * 第 18 轮：对话轮次（一轮连续对话）的条数分档（5 档）。
     *
     * 分档标签写法与既有分布行**逐条同规**：标签里不含空格（解析器按最后一个空格切分值）、
     * 不含全角冒号（会被当成指标行）、且至少一个标签含数字（否则整组会被判成环形图）。
     * 这里第 1 档刻意写成「单独一条」而非「1条」——"1条" 与「2~5条」摆在一起时视觉上像两列数字，
     * 而"单独一条"能一眼读出"这一段只有一句话，没有对话"。
     */
    private val ROUND18_ROUND_LABELS = listOf("单独一条", "2~5条", "6~15条", "16~50条", "50条以上")

    /**
     * 第 18 轮：静默间隔谱的分档（7 档，含 [TOPIC_BREAK_MS] 以上的长中断）。
     *
     * 与【互动节奏】的最长冷场 / 【回复延迟分布】的 30 分钟内分档是**互补**关系：
     * 那两处只回答"多快接上"，这里把从"隔一分钟"到"隔几天"的全部静默铺成一张谱，
     * 短档密集 = 实时聊天，长档密集 = 留言板式联络。
     */
    private val ROUND18_SILENCE_LABELS =
        listOf("1分内", "5分内", "30分内", "2时内", "6时内", "1天内", "更久")

    /** 第 18 轮：静默分档的上界（毫秒，最后一项之后一律归末档） */
    private val ROUND18_SILENCE_BOUNDS =
        longArrayOf(60_000L, 300_000L, 1_800_000L, 7_200_000L, 21_600_000L, 86_400_000L)

    /**
     * 第 18 轮：每人说话画像的样本门槛。
     *
     * 只发过一两条消息的人不该出现在"人均字数榜"上：一条 200 字的小作文就能让
     * "人均 200 字" 压过整场聊天都在说话的人。条数不足的直接不参与排名。
     */
    private const val ROUND18_PROFILE_MIN_MSGS = 5

    /**
     * 第 18 轮：撤回者归因时从系统消息正文里最多取多少字符作为昵称。
     *
     * 微信的撤回系统消息形如 `"张三" 撤回了一条消息`（自己撤回则是 `你撤回了一条消息`）；
     * 只做"取引号内内容"这一种最保守的解析，解析不出来就不归因（宁可少算，不要算错）。
     */
    private const val ROUND18_REVOKE_NAME_MAX = 24

    /**
     * 第 18 轮：表情符号种类上限（超出后不再新增键，只累加已有键）。
     *
     * emoji 的码点空间很大，理论上能出现上万种组合；给一个硬上限是为了让这张表
     * **与消息总量解耦**（大群几十万条消息也不会多占内存）。命中上限后仍继续统计
     * [ExtraStats.emojiTotal] 与已出现过的表情，只是不再记录新面孔。
     */
    private const val ROUND18_EMOJI_MAX_KINDS = 240

    /**
     * 第 18 轮：默契搭档（无向对）上限。
     *
     * 群成员 n 人最多有 n(n-1)/2 个组合，500 人大群会到十几万；达到上限后
     * 只累加已出现过的组合（**不新增键**），保证内存与群规模解耦。
     */
    private const val ROUND18_PAIR_MAX = 4096

    /** 第 18 轮：无向对在 map 里的分隔符（用不可能出现在 wxid / 昵称里的控制字符） */
    private const val ROUND18_PAIR_SEP = '\u0001'

    // ---------------- 第 20 轮（维度整合）新增：六个新维度的词表与上限 ----------------
    //
    // 六个新维度（情绪词雷达 / 打字习惯 / 约定与提醒 / 回复速度榜 / 个人作息雷达 / 时段话量画像）
    // 全部复用同一次分页扫描：固定词表 contains + 几个整数计数器 + 24 格定长数组，
    // 没有新增查询、没有第二遍遍历、没有随消息条数增长的内存（词表固定、键数按人规模封顶）。

    /** 正向情绪词（消息级 contains；与负向表不重叠，命中即算一条） */
    private val MOOD_POS = listOf(
        "开心", "高兴", "喜欢", "爱你", "哈哈", "嘿嘿", "太好了", "不错", "厉害", "棒",
        "舒服", "幸福", "谢谢", "感谢", "感动", "惊喜", "期待", "好玩", "可爱", "想你了",
    )

    /** 负向情绪词（消息级 contains；与正向表不重叠） */
    private val MOOD_NEG = listOf(
        "难过", "难受", "生气", "无语", "好烦", "好累", "崩溃", "郁闷", "焦虑", "失望",
        "委屈", "讨厌", "后悔", "孤独", "想哭", "压力大", "睡不着", "心疼", "社死", "麻了",
    )

    /** 约定 / 时间词（消息级 contains）：命中即算一条「约定类消息」 */
    private val APPT_WORDS = listOf(
        "今晚", "明天", "后天", "周末", "几点", "早上", "上午", "中午", "下午", "晚上",
        "下班", "一起", "见面", "出来", "等你", "马上", "稍等", "待会", "记得", "别忘了",
    )

    /** 全角标点集合（打字习惯：一次字符扫描，纯 indexOf 比较，无分配） */
    private val FULL_PUNCT = "，。！？、；：（）【】「」《》…—～"

    /** 半角标点集合（同上） */
    private val HALF_PUNCT = ",.!?;:()[]-"

    /** 每人作息雷达 / 回复速度榜的键数上限：与人规模同阶，群再大也不会无限长 */
    private const val HABIT_MAX_SENDERS = 40

    /** 回复速度榜的人均样本门槛：少于这个次数的人不进榜（避免"只回一次"的偶然值霸榜） */
    private const val REPLY_RANK_MIN_SAMPLES = 3

    // ---------------- 第 18 轮：表情符号码点判定（纯区间比较） ----------------

    /**
     * 是否是一个"表情符号"码点。
     *
     * 只覆盖 Unicode 官方表情集中最常见、也是微信里真正会被当表情用的几个区块：
     *  - `1F300–1FAFF` 杂项符号与图形 / 补充符号与图形 / 扩展-A（😀🚀🦄🫠…）
     *  - `1F000–1F2FF` 麻将牌 / 扑克牌 / 带圈字符补充（🀄🃏🅰️…）
     *  - `2600–27BF`    杂项符号 / 装饰符号（☀☺♥✅✂…）
     *  - `2B00–2BFF`    杂项符号与箭头（⭐⬆…）
     *
     * 刻意**不**收 `2190–21FF`（普通箭头）、`2000–206F`（标点/空格）、`FE00–FE0F`（变体选择符）
     * —— 这些在中文聊天里大量出现却根本不是表情，收进来只会把"表情排行"变成乱码榜。
     */
    private fun isEmojiCodePoint(cp: Int): Boolean = when (cp) {
        in 0x1F300..0x1FAFF -> true
        in 0x1F000..0x1F2FF -> true
        in 0x2600..0x27BF -> true
        in 0x2B00..0x2BFF -> true
        else -> false
    }

    /**
     * 第 18 轮：扫描一条正文里的表情符号（就地累计，不产生中间集合）。
     *
     * 逐码点走一遍（代理对一次跨两步），命中就自增；变体选择符（U+FE0F）与零宽连接符
     * （U+200D）**不单独计数**（它们只是修饰，不是独立表情）。
     * 单条正文的长度上限由调用方（[CLICHE_BODY_MAX] 同级的正文门槛）保证，这里不做二次限制。
     */
    private fun scanEmoji(body: String, ex: ExtraStats) {
        var i = 0
        var hit = false
        val n = body.length
        while (i < n) {
            val cp = Character.codePointAt(body, i)
            val cc = Character.charCount(cp)
            if (isEmojiCodePoint(cp)) {
                hit = true
                ex.emojiTotal++
                val key = String(Character.toChars(cp))
                val cur = ex.emoji[key]
                if (cur != null) {
                    ex.emoji[key] = cur + 1
                } else if (ex.emoji.size < ROUND18_EMOJI_MAX_KINDS) {
                    ex.emoji[key] = 1
                }
            }
            i += cc
        }
        if (hit) ex.emojiMsgsEx++
    }

    /**
     * 第 18 轮：从系统消息正文里认领一次「撤回」的发起人。
     *
     * 只认两种最稳的形态：
     *  - `"张三" 撤回了一条消息` → 归因到「张三」（群聊里最常见，昵称被引号包住）；
     *  - `你撤回了一条消息`     → 归因到「我」（自己撤回，微信用第二人称）。
     *
     * 其它形态（版本差异、多语言）一律不归因：宁可少算一个人，也不要把整句正文当昵称塞进榜里。
     */
    private fun attributeRevoke(content: String, ex: ExtraStats) {
        val q1 = content.indexOf('"')
        val q2 = if (q1 >= 0) content.indexOf('"', q1 + 1) else -1
        if (q1 >= 0 && q2 > q1 + 1) {
            val name = content.substring(q1 + 1, q2).trim()
            if (name.isNotEmpty() && name.length <= ROUND18_REVOKE_NAME_MAX) {
                ex.revokeBy[name] = (ex.revokeBy[name] ?: 0) + 1
                return
            }
        }
        val q3 = content.indexOf('“')
        val q4 = if (q3 >= 0) content.indexOf('”', q3 + 1) else -1
        if (q3 >= 0 && q4 > q3 + 1) {
            val name = content.substring(q3 + 1, q4).trim()
            if (name.isNotEmpty() && name.length <= ROUND18_REVOKE_NAME_MAX) {
                ex.revokeBy[name] = (ex.revokeBy[name] ?: 0) + 1
                return
            }
        }
        if (content.startsWith("你撤回")) ex.revokeBy["我"] = (ex.revokeBy["我"] ?: 0) + 1
    }

    /** 第 18 轮：静默时长 → 分档下标（越界一律归末档，绝不返回非法下标） */
    private fun silenceBand(gap: Long): Int {
        for (i in ROUND18_SILENCE_BOUNDS.indices) {
            if (gap <= ROUND18_SILENCE_BOUNDS[i]) return i
        }
        return ROUND18_SILENCE_LABELS.size - 1
    }

    /** 第 18 轮：轮长（一轮对话的消息条数）→ 分档下标（越界归末档） */
    private fun roundBand(len: Int): Int = when {
        len <= 1 -> 0
        len <= 5 -> 1
        len <= 15 -> 2
        len <= 50 -> 3
        else -> 4
    }

    /**
     * 第 15 轮：话题词表（固定 28 个，**不随消息内容增长**）。
     *
     * 为什么不再跑一遍分词：分词结果会随语料无限膨胀（大群几十万条能产出十几万 token），
     * 而这里要的是「这份聊天在聊什么」的粗粒度雷达 —— 固定词表的统计口径稳定、跨会话可比、
     * 内存恒定。命中判定与【口头禅】一致，是**消息级**（一条消息对同一个词只记一次）。
     */
    private val TOPIC_WORDS = listOf(
        "工作", "加班", "会议", "项目", "代码", "需求", "学习", "考试", "游戏", "吃饭",
        "外卖", "奶茶", "咖啡", "减肥", "运动", "电影", "音乐", "旅行", "天气", "睡觉",
        "熬夜", "摸鱼", "工资", "股票", "房子", "宠物", "生日", "红包",
    )

    /** 星期名（ISO：周一=0 … 周日=6）：热力图左侧标签与峰值时段文案共用同一份 */
    private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 活跃热力的格数：7 天 × 24 小时（定长，与消息条数无关） */
    private const val HEAT_CELLS = 7 * 24

    /**
     * 口头禅词表（**消息级**判定：一条消息命中一次，和【高频词】的 n-gram 词频是两套口径）。
     *
     * 为什么用固定词表而不是再跑一遍分词：单字语气词（嗯/啊/哦）根本进不了 n-gram
     * （[countWords] 最少切 2 字），而这恰恰是口头禅最典型的形态；固定词表还能保证
     * 词表大小恒定，不会为大群多占一个字节的内存。
     */
    private val CLICHES = listOf(
        "哈哈", "嘿嘿", "嘻嘻", "呵呵", "笑死", "救命", "离谱", "绝了", "无语", "好家伙",
        "真的", "就是", "然后", "其实", "感觉", "可能", "不是", "好吧", "emmm", "emm",
        "嗯", "啊", "哦", "唉", "哎", "呀",
    )

    /**
     * 颜文字提示串（同样是消息级判定）。
     * 只做 contains：真正的面孔表达式五花八门，正则匹配既慢又容易漏，
     * 这里取的是"常见的几种打字习惯"，报告里也只声称口径为"常见颜文字"。
     */
    private val KAOMOJI = listOf("^_^", "T_T", "t_t", "Orz", "orz", "OTL", "-_-", ">_<", "QAQ", "￣▽￣")

    /** 数据库未就绪时的提示，由调用方展示 */
    val dbReady: Boolean get() = runCatching { WeDatabaseApi.isReady }.getOrDefault(false)

    // ---------------- 时间范围（与脚本 timeRange 完全一致） ----------------

    fun timeRange(mode: Int, now: Long): Pair<Long, Long> {
        val d = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = d.timeInMillis
        val yesterdayStart = todayStart - 86_400_000L

        val w = d.clone() as Calendar
        w.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        var mondayStart = w.timeInMillis
        if (mondayStart > todayStart) mondayStart -= 7L * 86_400_000L
        val lastMondayStart = mondayStart - 7L * 86_400_000L

        val m = d.clone() as Calendar
        m.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = m.timeInMillis
        val lm = m.clone() as Calendar
        lm.add(Calendar.MONTH, -1)
        val lastMonthStart = lm.timeInMillis

        return when (mode) {
            0 -> todayStart to now
            1 -> yesterdayStart to todayStart
            2 -> mondayStart to now
            3 -> lastMondayStart to mondayStart
            4 -> monthStart to now
            else -> lastMonthStart to monthStart
        }
    }

    // ---------------- 查询：分批读取并实时统计 ----------------

    /**
     * 执行分析。返回统计报告；该时段无纯文本消息时 statsReport 为 null（AI 仍可提示）。
     * 抛出的异常由调用方展示。
     */
    fun analyze(
        talker: String,
        mode: Int,
        maxCount: Int,
        sampleLimit: Int,
        features: Set<String>,
        onProgress: ((Int, Int) -> Unit)? = null,
        lineMax: Int = TRANSCRIPT_LINE_MAX_DEFAULT,
        transcriptMaxChars: Int = TRANSCRIPT_MAX_CHARS_DEFAULT,
    ): AnalyzeResult {
        val now = System.currentTimeMillis()
        val range = timeRange(mode, now)
        val start = range.first
        val end = range.second
        val isGroup = talker.isGroupChatWxId

        val typeCount = mutableMapOf<String, Int>()
        val hourDist = IntArray(24)
        val rank = mutableMapOf<String, Int>()
        var totalAll = 0
        var laugh = 0
        var question = 0
        var exclaim = 0
        var wave = 0
        var speechless = 0
        var lenShort = 0
        var lenMid = 0
        var lenLong = 0
        var lenHuge = 0
        var atMe = 0
        // 第 13 轮扩展的四个维度：按星期分布、消息间隔（互动节奏）、连发长度、最长单条。
        val weekday = IntArray(7)
        var gapSum = 0L
        var gapCount = 0
        var maxGapMs = 0L
        var prevCt = 0L
        var streak = 0
        var maxStreak = 0
        var streakKey = ""
        var longestLen = 0
        var longestFromKey = ""

        // 第 14 轮扩展的六个维度：全部在下面那次分页扫描里就地累计（不再回扫、不再多查一次库）
        val ex = ExtraStats()
        var waitingInitiator = false
        // 第 15 轮：本条消息与上一条的间隔（0 = 首条 或 超过 30 分钟），用于「谁最爱秒回」归因
        var lastReplyGap = 0L

        val textSenders = mutableListOf<String>()
        val textBodies = mutableListOf<String>()

        val myWxid = runCatching { WeApi.selfWxId }.getOrDefault("")
        val myNick = runCatching { WeDatabaseApi.getDisplayName(myWxid) }.getOrDefault("")

        val hc = Calendar.getInstance()
        var offset = 0
        var fetchedTotal = 0

        // ---- 第 16 轮：趋势分桶（只在这里算一次，循环里仅一次整数除法取下标）----
        // 桶数按时间跨度自适应，既保证"每根柱都有区分度"，又保证标签不重复：
        // 今天/昨天（≤2 天）按 2 小时切 12 桶；本周/上周（≤8 天）按天切 7 桶；本月/上月按 ~3 天切 10 桶。
        val rangeSpanMs = (end - start).coerceAtLeast(1L)
        val trendBuckets = when {
            rangeSpanMs <= 2L * 86_400_000L -> 12
            rangeSpanMs <= 8L * 86_400_000L -> 7
            else -> 10
        }
        val bucketMs = (rangeSpanMs / trendBuckets).coerceAtLeast(1L)
        ex.trendBuckets = trendBuckets
        while (true) {
            val page = queryPage(talker, start, end, PAGE_SIZE, offset, maxCount)
            if (page.isEmpty()) break
            for (m in page) {
                val ct = (m["createTime"] as? Number)?.toLong()
                    ?: m["createTime"]?.toString()?.toLongOrNull()
                    ?: 0L
                if (ct <= 0L) continue
                if (ct < start || ct >= end) continue
                totalAll++
                // ---- 第 18 轮：当前这一轮对话的条数（跨 30 分钟中断时由 closeRound 结算）----
                // 放在这里而不是文字消息分支里：轮次衡量的是"这段对话有多长"，
                // 图片/表情/系统消息同样占一轮的名额。
                ex.curRound++

                val type = (m["type"] as? Number)?.toInt()
                    ?: m["type"]?.toString()?.toIntOrNull()
                    ?: 0
                val tn = typeName(type)
                typeCount[tn] = (typeCount[tn] ?: 0) + 1
                // ---- 第 15 轮：引用回复（type 49 且带 <refermsg> 节点）----
                // 只对卡片类消息做一次 contains：不解析 XML、不为它多查一次库、也不留中间结果。
                if (type == 49 && m["content"]?.toString()?.contains("<refermsg>") == true) ex.quoteMsgs++

                hc.timeInMillis = ct
                val hour = hc.get(Calendar.HOUR_OF_DAY)
                hourDist[hour]++
                // Calendar.DAY_OF_WEEK 周日=1，这里折成 ISO 的「周一=0 … 周日=6」
                val dow = (hc.get(Calendar.DAY_OF_WEEK) + 5) % 7
                weekday[dow]++
                // ---- 第 15 轮：活跃热力（周几 × 小时）在同一次扫描里就地累计，零分配 ----
                val heatIdx = dow * 24 + hour
                val heatV = ex.heat[heatIdx] + 1
                ex.heat[heatIdx] = heatV
                if (heatV > ex.heatPeak) {
                    ex.heatPeak = heatV
                    ex.heatPeakIdx = heatIdx
                }
                // ---- 第 16 轮：趋势分桶 / 活跃密度 / 日画像 / 早晚期 ----
                // 六项全是 O(1) 整数运算与比较：没有新的集合、没有新的字符串、没有第二次查库。
                val bIdx = ((ct - start) / bucketMs).toInt().coerceIn(0, trendBuckets - 1)
                val bv = ex.trend[bIdx]
                if (bv < Int.MAX_VALUE) ex.trend[bIdx] = bv + 1
                if (hourDist[hour] == 1) ex.activeHours++
                if (dow < 5) ex.workdayMsgs++ else ex.weekendMsgs++
                val night = isNightHour(hour)
                val minuteOfDay = hour * 60 + hc.get(Calendar.MINUTE)
                if (ex.earliestMinute < 0 || minuteOfDay < ex.earliestMinute) {
                    ex.earliestMinute = minuteOfDay
                }
                if (minuteOfDay > ex.latestMinute) ex.latestMinute = minuteOfDay
                val dayKey = hc.get(Calendar.YEAR) * 1000 + hc.get(Calendar.DAY_OF_YEAR)
                if (dayKey != ex.curDayKey) {
                    // ---- 第 17 轮：连续活跃天数（只在日期切换时算一次，纯整数比较）----
                    if (ex.prevDayKey > 0) {
                        if (isNextDay(ex.prevDayKey, dayKey)) {
                            ex.dayRun++
                        } else {
                            ex.dayBreak++
                            ex.dayRun = 1
                        }
                    } else {
                        ex.dayRun = 1
                    }
                    ex.prevDayKey = dayKey
                    if (ex.dayRun > ex.dayRunMax) ex.dayRunMax = ex.dayRun
                    // 跨天：收尾上一天（把它的活跃跨度并进日画像），再开新的一天
                    closeDay(ex)
                    ex.curDayKey = dayKey
                    ex.curDayFirst = ct
                    ex.curDayCount = 0
                    ex.activeDays++
                    if (dow >= 5) ex.weekendDays++ else ex.workdayDays++
                }
                ex.curDayLast = ct
                ex.curDayCount++
                if (ex.curDayCount > ex.dayMaxCount) {
                    ex.dayMaxCount = ex.curDayCount
                    ex.dayMaxStart = ex.curDayFirst
                }
                lastReplyGap = 0L
                if (prevCt > 0L) {
                    val gap = ct - prevCt
                    if (gap > 0L) {
                        gapSum += gap
                        gapCount++
                        if (gap > maxGapMs) maxGapMs = gap
                        // ---- 第 18 轮：静默间隔谱（**每一段**间隔都分档，不分长短）----
                        // 与下面「30 分钟以内算回复」是两个互补口径：那边只回答"多快接上"，
                        // 这里把从一分钟到几天以上的全部间隔铺成一张谱，回答"多久不说话"。
                        ex.silence[silenceBand(gap)]++
                        // ---- 第 14 轮：回复间隔 / 沉默 / 话题分段（全是整数比较，无分配）----
                        if (gap <= TOPIC_BREAK_MS) {
                            // 真正意义上的「回复」：30 分钟内的你来我往
                            ex.replyGapSum += gap
                            ex.replyGapCount++
                            // ---- 第 15 轮：回复延迟分档 + 最快回复（同一处累计，不多遍历一次）----
                            lastReplyGap = gap
                            when {
                                gap <= FAST_REPLY_MS -> ex.latency[0]++
                                gap <= 60_000L -> ex.latency[1]++
                                gap <= 300_000L -> ex.latency[2]++
                                else -> ex.latency[3]++
                            }
                            if (ex.fastestGapMs == 0L || gap < ex.fastestGapMs) ex.fastestGapMs = gap
                            // ---- 第 16 轮：细粒度 7 档（中位数与"半数回复在多快以内"都靠它）----
                            ex.latencyFine[fineBand(gap)]++
                        } else {
                            // 沉默 ≥30 分钟：记一次沉默、切开一个话题段，并把下一条消息的作者
                            // 记为这一段的「发起人」（pending 标记在下面 rank 统计处消费）
                            ex.silentBreaks++
                            ex.silentSum += gap
                            if (gap > ex.maxGapMs) {
                                ex.maxGapMs = gap
                                ex.maxGapStart = prevCt
                                ex.maxGapEnd = ct
                            }
                            closeTopic(ex, prevCt)
                            // ---- 第 18 轮：≥30 分钟的中断同样意味着「一轮对话」结束 ----
                            // 与 closeTopic 同源同判据（同一个 TOPIC_BREAK_MS），只多记一次轮长分档：
                            // 不新增比较、不新增遍历，纯标量累加。
                            closeRound(ex)
                            ex.topicStart = ct
                            waitingInitiator = true
                        }
                    }
                } else {
                    // 时段内第一条消息 = 第一个话题段的起点（它本身不算「发起」：
                    // 时间范围是我们截出来的，它前面的沉默长度未知，计入会失真）
                    ex.topicStart = ct
                }
                prevCt = ct

                val sent = (m["isSend"] as? Number)?.toLong() == 1L
                    || m["isSend"]?.toString() == "1"
                val content = m["content"]?.toString() ?: ""

                // ---- 第 18 轮：系统消息与撤回事件 ----
                // 系统消息（type 10000）是微信用来播报"谁撤回了一条消息 / 谁加入了群聊 /
                // 群名被改成了什么"的统一载体。这里只做一次 contains + 一次引号解析：
                // 不解析 XML、不查库、不留中间结果。【特殊消息雷达】里的「撤回条数」走的是
                // type 10002（新版微信把撤回单列成一种消息类型），两者口径互补，都保留。
                if (type == 10000) {
                    ex.sysMsgs++
                    if (content.contains("撤回")) {
                        ex.revokeMsgs++
                        attributeRevoke(content, ex)
                    }
                }

                if (type != 10000) {
                    val rankKey = when {
                        sent -> "我"
                        isGroup -> groupSenderFromContent(content).ifEmpty { "群友" }
                        else -> "对方"
                    }
                    rank[rankKey] = (rank[rankKey] ?: 0) + 1
                    // ---- 第 15 轮：秒回归因（≤10 秒就接上话的那个人是谁）----
                    if (lastReplyGap in 1..FAST_REPLY_MS) {
                        ex.fastReply[rankKey] = (ex.fastReply[rankKey] ?: 0) + 1
                    }
                    // ---- 第 20 轮：回复速度榜（按人累计 30 分钟内的响应间隔）----
                    // 与上面的「秒回归因」共用同一次判定结果（lastReplyGap），不新增比较；
                    // 键数按 HABIT_MAX_SENDERS 封顶：已存在的键照常累加，超限的新人不再入表。
                    if (lastReplyGap in 1..TOPIC_BREAK_MS) {
                        val prevSum = ex.replyMsSumBySender[rankKey]
                        if (prevSum != null) {
                            ex.replyMsSumBySender[rankKey] = prevSum + lastReplyGap
                            ex.replyMsCntBySender[rankKey] = (ex.replyMsCntBySender[rankKey] ?: 0) + 1
                        } else if (ex.replyMsSumBySender.size < HABIT_MAX_SENDERS) {
                            ex.replyMsSumBySender[rankKey] = lastReplyGap
                            ex.replyMsCntBySender[rankKey] = 1
                        }
                    }
                    // 沉默 ≥30 分钟后的第一条 = 这一段话题的「发起人」（系统消息不参与）
                    if (waitingInitiator) {
                        ex.initiator[rankKey] = (ex.initiator[rankKey] ?: 0) + 1
                        waitingInitiator = false
                    }
                }

                if (content.contains("哈") || content.contains("笑")) laugh++
                if (content.contains("?") || content.contains("？") || content.endsWith("吗")) question++
                if (content.contains("!") || content.contains("！")) exclaim++
                if (content.contains("~") || content.contains("～")) wave++
                if (content.contains("...") || content.contains("。。。") || content.contains("无语")) speechless++

                val len = content.length
                when {
                    len <= 5 -> lenShort++
                    len <= 20 -> lenMid++
                    len <= 50 -> lenLong++
                    else -> lenHuge++
                }

                if (type == 1) {
                    var senderKey: String
                    var body = content
                    if (sent) {
                        senderKey = "我"
                    } else if (isGroup) {
                        val wx = groupSenderFromContent(body)
                        if (wx.isNotEmpty()) {
                            senderKey = wx
                            body = stripGroupSenderPrefix(body, wx)
                        } else {
                            senderKey = "群友"
                        }
                    } else {
                        senderKey = "对方"
                    }
                    if (body.startsWith("@")) {
                        // ---- 第 15 轮：@ 拆成两种口径（@ 了谁 / 其中 @ 的是不是我）----
                        // 判定条件与原来逐字等价，只是把「有没有 @」这一层单独记下来。
                        ex.atAny++
                        if ((myWxid.isNotEmpty() && body.contains(myWxid)) ||
                            (myNick.isNotEmpty() && body.contains(myNick)) ||
                            body.contains("所有人")
                        ) {
                            atMe++
                            ex.atMeEx++
                        }
                    }
                    if (senderKey == streakKey) {
                        streak++
                    } else {
                        // ---- 第 15 轮：换人 = 一个「回合」结束；长连击被换人才算「打断」----
                        if (streak >= INTERRUPT_MIN_STREAK) ex.interrupts++
                        ex.turns++
                        // ---- 第 16 轮：接话归因（谁的话被接上 / 谁最爱接别人的话）----
                        // 换人发言 = 上一个人的话"被接上"了：双方各记一次，纯 map 自增。
                        // streakKey 为空表示这是本时段第一条文字消息（无前文可接），跳过。
                        if (streakKey.isNotEmpty()) {
                            ex.turnsAttributed++
                            ex.replyFetch[streakKey] = (ex.replyFetch[streakKey] ?: 0) + 1
                            ex.replyGive[senderKey] = (ex.replyGive[senderKey] ?: 0) + 1
                            if (streakKey == "我") ex.myFetched++
                            // ---- 第 18 轮：默契搭档（无向对，双向接话合并成一个键）----
                            // 两个 senderKey 先按字典序排一次，保证 A→B 与 B→A 落在同一个键上，
                            // 于是这张表天然回答"哪两个人最常互相接话"（单向排行回答不了这个问题）。
                            // 键数超过 ROUND18_PAIR_MAX 后只累加已存在的组合，内存与群规模解耦。
                            val pk = if (streakKey <= senderKey) {
                                streakKey + ROUND18_PAIR_SEP + senderKey
                            } else {
                                senderKey + ROUND18_PAIR_SEP + streakKey
                            }
                            val pv = ex.pairs[pk]
                            if (pv != null) {
                                ex.pairs[pk] = pv + 1
                            } else if (ex.pairs.size < ROUND18_PAIR_MAX) {
                                ex.pairs[pk] = 1
                            }
                        }
                        streakKey = senderKey
                        streak = 1
                    }
                    if (streak > maxStreak) {
                        maxStreak = streak
                        ex.streakMax = streak
                        ex.streakMaxKey = senderKey
                    }
                    if (body.length > longestLen) {
                        longestLen = body.length
                        longestFromKey = senderKey
                    }
                    // ---- 第 14 轮：长度 / 标点 / 口头禅 / 摘录（同一次扫描内增量）----
                    ex.lenSum += body.length
                    ex.rankChars[senderKey] = (ex.rankChars[senderKey] ?: 0) + body.length
                    // ---- 第 18 轮：每人说话画像（人均字数的分母 + 最长单条）----
                    // rankChars 是"总字数"，缺了"条数"就算不出人均；这里补上条数与最长单条，
                    // 三者合起来才是"这个人平时说话多长"。
                    ex.rankTexts[senderKey] = (ex.rankTexts[senderKey] ?: 0) + 1
                    if (body.length > (ex.rankLongest[senderKey] ?: 0)) {
                        ex.rankLongest[senderKey] = body.length
                    }
                    // ---- 第 18 轮：每日开场 / 收尾（日期切换时把上一条归为前一天的收尾）----
                    // 判据来自循环上面算好的 dayKey（年 × 1000 + 年内第几天），与【连续活跃】同源。
                    // 只在日期切换的那一条上做两次 map 自增，热路径零额外开销。
                    if (dayKey != ex.senderDayKey) {
                        val prev = ex.lastSenderKey
                        if (prev.isNotEmpty()) {
                            ex.dayCloser[prev] = (ex.dayCloser[prev] ?: 0) + 1
                        }
                        ex.dayOpener[senderKey] = (ex.dayOpener[senderKey] ?: 0) + 1
                        ex.senderDayKey = dayKey
                    }
                    ex.lastSenderKey = senderKey
                    // ---- 第 16 轮：我的文字消息数（被接话率的分母）与深夜/白天文字基数 ----
                    if (senderKey == "我") ex.myTexts++
                    if (night) ex.nightText++ else ex.dayText++
                    // ---- 第 17 轮：昼夜话量 / 复读 / 提问与回应（同一次扫描内增量，零额外遍历）----
                    if (night) {
                        ex.nightChars += body.length
                        if (body.length > LONG_BODY_MIN) ex.nightLong++
                    } else {
                        ex.dayChars += body.length
                        if (body.length > LONG_BODY_MIN) ex.dayLong++
                    }
                    scanRepeat(ex, senderKey, body)
                    scanQuestion(ex, senderKey, body, lastReplyGap)
                    scanPunctuation(body, ex)
                    // ---- 第 20 轮：三个新维度 + 两个按人拆分的作息累计（同一次扫描内就地增量）----
                    // 词表类（情绪词 / 约定词）与口头禅、话题词同一道长度闸门，长正文不整段扫；
                    // 打字习惯是一遍字符循环（纯比较，无分配）；时段话量与个人作息是 O(1) 整数自增。
                    if (body.length <= TOPIC_BODY_MAX) {
                        scanMood(body, ex)
                        scanAppointment(body, ex)
                    }
                    scanTyping(body, ex)
                    ex.hourChars[hour] += body.length
                    ex.hourTextN[hour]++
                    if (body.length > LONG_BODY_MIN) ex.hourLong[hour]++
                    val habit = ex.hourBySender[senderKey]
                    if (habit != null) {
                        habit[hour]++
                    } else if (ex.hourBySender.size < HABIT_MAX_SENDERS) {
                        val fresh = IntArray(24)
                        fresh[hour]++
                        ex.hourBySender[senderKey] = fresh
                    }
                    // ---- 第 18 轮：表情符号逐码点扫描（命中即累加，不建中间集合）----
                    scanEmoji(body, ex)
                    if (body.length <= CLICHE_BODY_MAX) scanCliches(body, ex)
                    if (body.length <= TOPIC_BODY_MAX) scanTopics(body, ex, night)
                    rememberExcerpt(ex, senderKey, body)
                    textSenders.add(senderKey)
                    textBodies.add(body)
                }
            }
            fetchedTotal += page.size
            onProgress?.invoke(fetchedTotal, totalAll)
            offset += page.size
            // 读满上限 或 最后一页不满一页（已读完）
            if ((maxCount > 0 && offset >= maxCount) || page.size < PAGE_SIZE) break
        }
        // 收尾最后一个话题段（段时长 = 段内最后一条 - 段内第一条）
        closeTopic(ex, prevCt)
        // ---- 第 18 轮：收尾最后一轮对话 + 把最后一天的收尾者补记上 ----
        // 日期切换时只会结算"前一天"的收尾，最后一天没有下一次切换，必须在这里补一次。
        closeRound(ex)
        if (ex.lastSenderKey.isNotEmpty()) {
            ex.dayCloser[ex.lastSenderKey] = (ex.dayCloser[ex.lastSenderKey] ?: 0) + 1
        }
        // ---- 第 16 轮：收尾当天 + 一次性生成趋势桶的 x 轴标签（≤12 次，不进入消息循环）----
        closeDay(ex)
        val hourAxis = rangeSpanMs <= 2L * 86_400_000L
        ex.trendLabels = Array(trendBuckets) { i ->
            val at = start + bucketMs * i
            if (hourAxis) hourTickLabel(at) else dayTickLabel(at)
        }

        val textN = textSenders.size
        if (textN == 0) {
            return AnalyzeResult(statsReport = "", totalAll = totalAll, textN = 0)
        }

        // 等距抽样，保留时间分布（脚本 step 语义）
        // sampleLimit <= 0 = 不抽样：该时段的纯文本消息**全部**喂给 AI。
        // 用户 2026-09-22 第二次反馈「条数只有 20000 的上限真的极少」——真正的兜底是下面
        // 的整段字数上限（transcriptMaxChars），条数不该再额外卡一道。
        val limit = if (sampleLimit <= 0) Int.MAX_VALUE else sampleLimit
        val sampledIdx: List<Int> = if (textN > limit) {
            val step = ceil(textN.toDouble() / limit).toInt().coerceAtLeast(1)
            (0 until textN step step).toList()
        } else {
            (0 until textN).toList()
        }

        val nickCache = mutableMapOf<String, String>()
        val sb = StringBuilder()
        val wordMap = mutableMapOf<String, Int>()
        val effectiveLineMax = if (lineMax < 100) 100 else lineMax
        val effectiveMaxChars = if (transcriptMaxChars < 2000) 2000 else transcriptMaxChars
        var included = 0
        for (k in sampledIdx) {
            val key = textSenders[k]
            val rawBody = textBodies[k]
            val dn = speakerDisplayName(key, talker, isGroup, nickCache)
            var body = rawBody
            if (body.length > effectiveLineMax) {
                body = body.substring(0, effectiveLineMax) + "…"
            }
            // 喂给 AI 的对话文本必须有硬上限：抽样后大群仍可能十几万字，整段发出去会被服务端
            // 判上下文超限（就是用户看到的"AI 返回错误"），所以到量就停并注明截断。
            if (sb.length + dn.length + body.length + 8 > effectiveMaxChars) break
            sb.append("[").append(dn).append("]: ").append(body).append("\n")
            countWords(body, wordMap)
            included++
        }
        // 不论有没有截断都注明一次收录情况：用户反馈"看不出上限到底是多少"，
        // 这行会一起进 AI 正文和报告，条数/字数上限一目了然。
        // 正文字数必须在**追加这行之前**取，否则量到的是"正文 + 本行已写的部分"。
        val bodyChars = sb.length
        sb.append("…（本次共读取该时段纯文本 ").append(textN).append(" 条，收录 ")
            .append(included).append(" 条，正文 ").append(bodyChars).append(" 字 / 上限 ")
            .append(effectiveMaxChars).append(" 字）\n")
        val transcript = sb.toString()

        val report = if (features.contains(FEATURE_STATS)) {
            buildLocalReport(
                talker = talker,
                isGroup = isGroup,
                totalAll = totalAll,
                textN = textN,
                typeCount = typeCount,
                hourDist = hourDist,
                rank = rank,
                nickCache = nickCache,
                wordMap = wordMap,
                laugh = laugh,
                question = question,
                exclaim = exclaim,
                wave = wave,
                speechless = speechless,
                lenShort = lenShort,
                lenMid = lenMid,
                lenLong = lenLong,
                lenHuge = lenHuge,
                atMe = atMe,
                weekday = weekday,
                gapSum = gapSum,
                gapCount = gapCount,
                maxGapMs = maxGapMs,
                maxStreak = maxStreak,
                longestLen = longestLen,
                longestFromKey = longestFromKey,
                extra = ex,
                showRank = features.contains(FEATURE_RANK),
            )
        } else {
            ""
        }

        return AnalyzeResult(
            statsReport = report,
            transcript = transcript,
            totalAll = totalAll,
            textN = textN,
        )
    }

    /** 分页查询：LIMIT ? OFFSET ?；maxCount>0 时最后一页按剩余量截断。 */
    private fun queryPage(
        talker: String,
        start: Long,
        end: Long,
        limit: Int,
        offset: Int,
        maxCount: Int,
    ): List<Map<String, Any?>> {
        var effLimit = limit
        if (maxCount > 0) {
            val remain = maxCount - offset
            if (remain <= 0) return emptyList()
            if (remain < effLimit) effLimit = remain
        }
        val sql = "SELECT msgId,msgSvrId,talker,content,createTime,type,isSend FROM message " +
            "WHERE talker=? AND createTime>=? AND createTime<? ORDER BY createTime ASC LIMIT ? OFFSET ?"
        val args = mutableListOf<Any>(talker, start, end, effLimit, offset)
        return runCatching {
            WeDatabaseApi.executeQuery(sql, args.toTypedArray())
        }.getOrDefault(emptyList())
    }

    // ---------------- 第 14 轮扩展维度的累计器（同一次扫描内增量） ----------------

    /**
     * 六个新维度所需的原始量。
     *
     * 为什么收成一个类而不是再散十几个局部变量：`analyze` 的主循环已经有十几个计数器，
     * 这里再加十几行 `var` 会让人分不清「哪些是旧口径、哪些是新口径」；收进一个持有器后，
     * 新维度的所有状态集中在 **同一处**，也便于逐条核对「有没有多做一次遍历」。
     *
     * 内存：全部是定长标量 + 三个「以参与者为键」的小 map（键最多是参会人数，
     * 私聊只有 我/对方 两个键），**不随消息条数增长**；[topBodies] 恒定 ≤ [EXCERPT_N] 条，
     * 且只存已有字符串的引用（[analyze] 里的 textBodies 本来就持有它们）。
     */
    private class ExtraStats {
        /** 纯文本字数总和（平均字数的分子） */
        var lenSum = 0L

        /** 纯文本字符总数（各类「/百字」密度的分母） */
        var charTotal = 0

        // ---- 标点与语气（按字符计数）----
        var qMark = 0
        var eMark = 0
        var ellipsis = 0
        var tilde = 0
        var letterChars = 0

        /** 命中表情符号 / 常见颜文字的消息条数 */
        var emojiMsgs = 0
        var kaoMsgs = 0

        // ---- 口头禅 ----
        var clicheMsgs = 0
        val cliche = mutableMapOf<String, Int>()

        // ---- 沉默与主动性 ----
        /** ≤[TOPIC_BREAK_MS] 的回复间隔之和 / 条数（真正的「回复」间隔，不含长中断） */
        var replyGapSum = 0L
        var replyGapCount = 0

        /** ≥[TOPIC_BREAK_MS] 的沉默次数与累计时长 */
        var silentBreaks = 0
        var silentSum = 0L

        /** 最长沉默的起止时间点（上一条 / 下一条消息的时间） */
        var maxGapMs = 0L
        var maxGapStart = 0L
        var maxGapEnd = 0L

        /** 沉默后第一条消息的发送者计数（谁更常先开口） */
        val initiator = mutableMapOf<String, Int>()

        /** 每个参与者的纯文本字数（互动平衡的「字数比」） */
        val rankChars = mutableMapOf<String, Int>()

        // ---- 话题切换 ----
        var topicStart = 0L
        var maxTopicMs = 0L
        var maxTopicStart = 0L
        var maxTopicEnd = 0L

        /** 最长 [EXCERPT_N] 条摘录（senderKey to body），定长插入 */
        val topBodies = mutableListOf<Pair<String, String>>()

        // ---- 第 15 轮：六个新维度的累计量（同样是定长容器 / 人数规模的小 map）----

        /** 7(ISO 周几) × 24(小时) 的活跃热力矩阵：定长 [HEAT_CELLS] 个 int，不随消息数增长 */
        val heat = IntArray(HEAT_CELLS)

        /** 热力峰值格的计数与下标（下标 = 周几 × 24 + 小时） */
        var heatPeak = 0
        var heatPeakIdx = -1

        /** 回复延迟分档（≤10 秒 / ≤60 秒 / ≤5 分 / ≤30 分），定长 4 档 */
        val latency = IntArray(4)

        /** 最快一次回复的间隔（毫秒），0 = 还没有可用样本 */
        var fastestGapMs = 0L

        /** 秒回（≤10 秒接上话）按发送者的归因计数 */
        val fastReply = mutableMapOf<String, Int>()

        /** 最长连击的条数与归属人 */
        var streakMax = 0
        var streakMaxKey = ""

        /** 「回合」数（换人发言的次数）与「打断」次数（长连击被别人接上） */
        var turns = 0
        var interrupts = 0

        /** @ 的全部次数 / 其中 @ 到我 的次数 */
        var atAny = 0
        var atMeEx = 0

        /** 引用回复（type 49 且含 refermsg 节点）条数 */
        var quoteMsgs = 0

        /** 话题词命中（消息级）与命中任意话题词的消息条数 */
        val topic = mutableMapOf<String, Int>()
        var topicMsgs = 0

        // ---------------- 第 16 轮：六个扩展维度共用的定长状态 ----------------

        /**
         * 趋势分桶计数。数组定长 [TREND_MAX_BUCKETS]，实际用前 [trendBuckets] 个；
         * 桶宽在扫描前算一次（整数除法），循环里只有一次除法取下标。
         */
        val trend = IntArray(TREND_MAX_BUCKETS)

        /** 本次扫描实际使用的桶数（按时间跨度在 7 / 10 / 12 里选；1 表示不画趋势） */
        var trendBuckets = 0

        /** 趋势桶的 x 轴标签（扫描结束后一次性生成，≤12 次，不进入消息循环） */
        var trendLabels: Array<String> = emptyArray()

        /** 细粒度回复间隔直方图（7 档），用于中位数与"半数回复在多快以内" */
        val latencyFine = IntArray(LATENCY_FINE_BANDS)

        /** 谁的话被接上的次数（换人发言 = 前一个人的话被接上）；键是 [rankKey] 口径 */
        val replyFetch = mutableMapOf<String, Int>()

        /** 谁最常接别人的话（换人发言时的发言人） */
        val replyGive = mutableMapOf<String, Int>()

        /** 被归因的换人次数（= 双方都有名字的回合数，用来判样本够不够） */
        var turnsAttributed = 0

        /** 我发出的文字消息条数与"我的话被接上"的次数 → 我的被接话率 */
        var myTexts = 0
        var myFetched = 0

        /** 有消息的天数，以及工作日 / 周末各自的天数（日均要用"该类型的活跃天数"当分母） */
        var activeDays = 0
        var workdayDays = 0
        var weekendDays = 0

        /** 工作日 / 周末各自的消息总量 */
        var workdayMsgs = 0
        var weekendMsgs = 0

        /** 有消息的小时数（hourDist 首次变 1 时累计）与单日峰值条数 */
        var activeHours = 0
        var dayMaxCount = 0

        /** 峰值日的首条消息时间（用来给"最忙的一天"配日期） */
        var dayMaxStart = 0L

        /** 日画像：当前这一天的键（年*1000+年内第几天）/首条/末条，以及已收尾天的跨度累计 */
        var curDayKey = 0
        var curDayFirst = 0L
        var curDayLast = 0L
        var curDayCount = 0
        var daySpanSum = 0L
        var daySpanCount = 0
        var daySpanMax = 0L

        /** 全天最早 / 最晚活动时刻（当天分钟数 0-1439；-1 表示尚无样本） */
        var earliestMinute = -1
        var latestMinute = -1

        /** 话题命中的时段拆分：深夜 / 白天各自的命中条数，以及对应时段的文字消息基数 */
        val topicNight = mutableMapOf<String, Int>()
        val topicDay = mutableMapOf<String, Int>()
        var topicNightMsgs = 0
        var topicDayMsgs = 0
        var nightText = 0
        var dayText = 0

        // ---- 第 17 轮：六个新维度的累计量（同样是标量与"只看上一条"的短状态）----

        /** 复读：正文与上一条逐字相同、且换了人的次数 */
        var repeatMsgs = 0

        /** 复读当前链长与最长链长（连续命中多少次） */
        var repeatChain = 0
        var repeatChainMax = 0

        /** 复读金句：第一条被原样复述过的正文（截断到 [REPEAT_SAMPLE_MAX] 字） */
        var repeatSample = ""

        /** 上一条文字消息的正文与发送者（复读判定只需要"上一条"，不留任何历史） */
        var prevBody = ""
        var prevBodyFrom = ""

        /** 提问与回应：提问条数 / 被回应条数 / 自己追问条数 */
        var qAsks = 0
        var qAnswered = 0
        var qSelfFollow = 0

        /** 被回应提问的等待时长之和与条数（平均等待的分子 / 分母） */
        var waitSum = 0L
        var waitCount = 0

        /** 未决提问的提问人（空串 = 当前没有未决提问；只留一条，零集合） */
        var pendingAskFrom = ""

        /** 连续活跃：上一个「年 × 1000 + 年内第几天」、当前连击、最长连击、断档次数 */
        var prevDayKey = 0
        var dayRun = 0
        var dayRunMax = 0
        var dayBreak = 0

        /** 昼夜话量：深夜 / 白天的纯文字字数与长句（> [LONG_BODY_MIN] 字）条数 */
        var nightChars = 0L
        var dayChars = 0L
        var nightLong = 0
        var dayLong = 0

        // ---------------- 第 18 轮：事件 / 节奏 / 关系网络的累计量 ----------------
        // 与前面七轮同一条纪律：全部是定长数组 + 「以参与者为键」的小 map，
        // 每张表都有硬上限（见 ROUND18_* 常量），**不随消息条数增长**。

        /** 系统消息（type 10000）总数 —— 撤回、入群、改群名都走这个类型 */
        var sysMsgs = 0

        /** 其中正文命中「撤回」二字的条数（撤回事件口径，与 [typeCount] 的 type 10002 互补） */
        var revokeMsgs = 0

        /** 撤回者归因（昵称 → 次数）；解析不出昵称的系统消息不计入 */
        val revokeBy = mutableMapOf<String, Int>()

        /** 对话轮次：总轮数、轮长总和、最长一轮的条数 */
        var roundCount = 0
        var roundLenSum = 0
        var roundMax = 0

        /** 当前这一轮已累计的条数（跨轮时结算并置 1） */
        var curRound = 0

        /** 轮长分布（5 档，见 [ROUND18_ROUND_LABELS]） */
        val roundBands = IntArray(5)

        /** 静默间隔谱（7 档，见 [ROUND18_SILENCE_LABELS]）：覆盖从 1 分钟到一天以上的全部间隔 */
        val silence = IntArray(7)

        /** 每个人的文字消息条数（人均字数的分母；与 [rankChars] 的分子配套） */
        val rankTexts = mutableMapOf<String, Int>()

        /** 每个人最长的一条文字消息字数 */
        val rankLongest = mutableMapOf<String, Int>()

        /** 表情符号出现次数（码点字符串 → 次数）与总个数 */
        val emoji = mutableMapOf<String, Int>()
        var emojiTotal = 0

        /** 含至少一个表情符号的文字消息条数（"表情消息率"的分子） */
        var emojiMsgsEx = 0

        /** 默契搭档：无向对（两个 senderKey 用小分隔符拼成键）→ 互相接话次数 */
        val pairs = mutableMapOf<String, Int>()

        /** 每日开场 / 收尾者的归因计数（每天第一条 / 最后一条消息是谁发的） */
        val dayOpener = mutableMapOf<String, Int>()
        val dayCloser = mutableMapOf<String, Int>()

        /** 开场 / 收尾归因用的短状态：当前已归因到的日期键与上一条文字消息的发送者 */
        var senderDayKey = 0
        var lastSenderKey = ""

        // ---- 第 20 轮新增：六个新维度的累计量（全部定长或按人封顶，与消息条数无关）----

        /** 情绪词雷达：正向 / 负向词频（固定词表，最多 20 个键）与命中消息条数 */
        val moodPos = mutableMapOf<String, Int>()
        val moodNeg = mutableMapOf<String, Int>()
        var moodPosMsgs = 0
        var moodNegMsgs = 0

        /** 打字习惯：无标点 / 有全角标点 / 带空格 / 纯英数 / 单字 的消息条数 */
        var typNoPunct = 0
        var typFullPunct = 0
        var typSpace = 0
        var typPlainAlnum = 0
        var typSingleChar = 0

        /** 约定与提醒：命中时间 / 约定词的消息条数与词频（固定词表） */
        val appt = mutableMapOf<String, Int>()
        var apptMsgs = 0

        /** 时段话量画像：每小时纯文字字数 / 条数 / 长句条数（定长 24） */
        val hourChars = IntArray(24)
        val hourTextN = IntArray(24)
        val hourLong = IntArray(24)

        /** 个人作息雷达：发送者 → 24 格小时直方图（键数上限 HABIT_MAX_SENDERS） */
        val hourBySender = mutableMapOf<String, IntArray>()

        /** 回复速度榜：发送者 → 响应间隔之和 / 次数（键数上限 HABIT_MAX_SENDERS） */
        val replyMsSumBySender = mutableMapOf<String, Long>()
        val replyMsCntBySender = mutableMapOf<String, Int>()
    }

    // ==================================================================
    // 本地统计报告（第 20 轮：维度整合版 —— 25 个维度）
    //
    // 第 20 轮做的是**纯整合 + 扩充**：把第 13~18 轮陆续加进来的 41 个段位合并成 19 个
    // （同类项合一、去掉重复口径），再补 6 个新维度，最终 25 个，每个段位都有独立分析价值。
    //
    //   核心 13 个（始终输出）：
    //     1 核心指标 / 2 内容载体与表情 / 3 活跃时段分布 / 4 活跃热力 / 5 作息与昼夜 /
    //     6 节奏与沉默 / 7 消息长度画像 / 8 情绪与语气 / 9 高频词与口头禅 /
    //     10 话题雷达与时段 / 11 发言与互动平衡 / 12 特殊消息与互动 / 13 每日开场与收尾
    //   进阶 12 个（三个开关各管 4 个，默认全开）：
    //     时间包：14 活跃日历与趋势 / 15 回应速度 / 16 活跃密度与连续 / 17 连击与轮次
    //     关系包：18 接话·提问·默契 / 19 复读与重复 / 20 回复速度榜 / 21 个人作息雷达
    //     语言包：22 情绪词雷达 / 23 打字习惯 / 24 约定与提醒 / 25 时段话量画像
    //
    // 排版铁律（弹窗 UI 与 PNG 导出各有一个**通用**解析器，两边的判据必须同时满足；
    // 这一段与第 14~18 轮逐字相同，整合时一行没改）：
    //  - 「键：值」一行一个指标：键 ≤ 20 字、值 ≤ 18 字、整行 ≤ 40 字，值**以数字开头**；
    //  - 分布行写 `标签 数值 ████`：标签不含空格与全角冒号；零值桶写 `标签 0`（标签必须含中文）；
    //  - 标签里含数字 → 柱状图；不含数字且全正 → 环形图；整行只有 `词×次数` → 标签云；
    //  - 活跃热力固定 7 行 `周X → 24 个数字`（两侧各有一段专用解析）；
    //  - 结论句一律不带全角冒号，免得被误判成指标行。
    //
    // 数据来源：全部来自同一次分页扫描累出来的 [ExtraStats]（定长数组 + 人数规模的小表），
    // 这里只做字符串拼接：**不查库、不遍历消息、不物化全量**。
    // ==================================================================

    private fun buildLocalReport(
        talker: String,
        isGroup: Boolean,
        totalAll: Int,
        textN: Int,
        typeCount: Map<String, Int>,
        hourDist: IntArray,
        rank: Map<String, Int>,
        nickCache: MutableMap<String, String>,
        wordMap: Map<String, Int>,
        laugh: Int,
        question: Int,
        exclaim: Int,
        wave: Int,
        speechless: Int,
        lenShort: Int,
        lenMid: Int,
        lenLong: Int,
        lenHuge: Int,
        atMe: Int,
        weekday: IntArray,
        gapSum: Long,
        gapCount: Int,
        maxGapMs: Long,
        maxStreak: Int,
        longestLen: Int,
        longestFromKey: String,
        extra: ExtraStats,
        showRank: Boolean,
    ): String {
        val r = StringBuilder()
        appendCoreOverview(r, extra, talker, isGroup, totalAll, textN, typeCount, atMe, rank.size, nickCache)
        appendCoreTime(r, extra, totalAll, textN, hourDist, typeCount)
        appendCoreRhythm(
            r = r,
            ex = extra,
            talker = talker,
            isGroup = isGroup,
            textN = textN,
            gapSum = gapSum,
            gapCount = gapCount,
            maxGapMs = maxGapMs,
            maxStreak = maxStreak,
            longestLen = longestLen,
            longestFromKey = longestFromKey,
            nickCache = nickCache,
        )
        appendCoreContent(
            r = r,
            ex = extra,
            talker = talker,
            isGroup = isGroup,
            textN = textN,
            lenShort = lenShort,
            lenMid = lenMid,
            lenLong = lenLong,
            lenHuge = lenHuge,
            laugh = laugh,
            question = question,
            exclaim = exclaim,
            wave = wave,
            speechless = speechless,
            wordMap = wordMap,
            nickCache = nickCache,
        )
        appendCoreInteraction(
            r = r,
            ex = extra,
            talker = talker,
            isGroup = isGroup,
            textN = textN,
            totalAll = totalAll,
            typeCount = typeCount,
            rank = rank,
            nickCache = nickCache,
            showRank = showRank,
        )

        // 三个进阶包各管 4 个维度（键沿用第 16/17/18 轮的三个开关，老用户的设置不会失效）。
        if (ChatAnalysisDimPacks.isEnabled(ChatAnalysisDimPacks.PACK_TIME)) {
            appendTimePackSections(r, extra, talker, isGroup, textN, totalAll, weekday, nickCache)
        }
        if (ChatAnalysisDimPacks.isEnabled(ChatAnalysisDimPacks.PACK_RELATION)) {
            appendRelationPackSections(r, extra, talker, isGroup, textN, nickCache)
        }
        if (ChatAnalysisDimPacks.isEnabled(ChatAnalysisDimPacks.PACK_LANGUAGE)) {
            appendLanguagePackSections(r, extra, textN, wordMap)
        }

        return r.toString()
    }

    // ---------------- 核心 1~2：核心指标 / 内容载体与表情 ----------------

    /**
     * 核心第 1~2 段。
     *
     * 第 2 段是第 20 轮的合并结果：原【内容载体偏好】（消息类型分布环形图）、
     * 原【媒体与表情构成】（媒体占比口径）与原【表情符号排行】（表情 Top N）
     * 三段讲的是同一件事 —— "这段聊天用什么在说话"，合成一段后
     * 环形图 + KPI + 表情榜刚好是一张完整的"载体画像"。
     */
    private fun appendCoreOverview(
        r: StringBuilder,
        ex: ExtraStats,
        talker: String,
        isGroup: Boolean,
        totalAll: Int,
        textN: Int,
        typeCount: Map<String, Int>,
        atMe: Int,
        speakerCount: Int,
        nickCache: MutableMap<String, String>,
    ) {
        r.append("【核心指标】\n")
        r.append("消息总数：").append(totalAll).append(" 条（纯文本 ").append(textN).append(" 条）\n")
        if (isGroup) r.append("文本发言人数：").append(speakerCount).append("\n")
        else r.append("会话类型：私聊（我 / 对方）\n")
        if (atMe > 0) r.append("被 @ 次数：").append(atMe).append("\n")

        // ── 2) 内容载体与表情 ────────────────────────────────────────
        r.append("\n【内容载体与表情】\n")
        val tk = topKeys(typeCount, 6)
        if (tk.isNotEmpty()) {
            val tMax = typeCount[tk[0]] ?: 1
            for (k in tk) {
                val v = typeCount[k] ?: 0
                r.append(k).append(" ").append(v).append(" ").append(bar(v, tMax, 16)).append("\n")
            }
        }
        if (totalAll > 0) {
            val textCount = typeCount["文字"] ?: 0
            val emojiCount = typeCount["表情"] ?: 0
            val picCount = typeCount["图片"] ?: 0
            val mediaCount = picCount + (typeCount["语音"] ?: 0) + (typeCount["视频"] ?: 0) + emojiCount
            r.append("文字消息占比：").append(pct(textCount, totalAll)).append("%\n")
            r.append("媒体消息占比：").append(pct(mediaCount, totalAll)).append("%\n")
            r.append("表情占比：").append(pct(emojiCount, totalAll)).append("%\n")
            r.append("图片占比：").append(pct(picCount, totalAll)).append("%\n")
            r.append("媒体与文字比 ").append(ratioText(mediaCount, textCount)).append("\n")
            r.append("载体鉴定 ").append(
                when {
                    pct(emojiCount, totalAll) >= 20 -> "表情包是第二语言"
                    pct(mediaCount, totalAll) >= 50 -> "能发图绝不打字"
                    pct(textCount, totalAll) >= 80 -> "纯文字选手"
                    else -> "文字为主，媒体点缀"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有可统计的消息\n")
        }
        // 表情符号排行（第 18 轮的口径原样保留：只统计正文里的 Unicode 表情码点）
        if (textN > 0) {
            r.append("表情总个数：").append(ex.emojiTotal).append(" 个\n")
            r.append("表情消息率：").append(pct(ex.emojiMsgsEx, textN)).append("%\n")
            r.append("表情种类：").append(ex.emoji.size).append(" 种\n")
            if (ex.emojiTotal > 0) {
                r.append("人均表情数：").append(oneDecimal(ex.emojiTotal.toDouble() / textN)).append(" 个\n")
            }
            val ek = topKeys(ex.emoji, 8)
            if (ek.isNotEmpty()) {
                r.append("表情排行 用得最多的表情符号\n")
                val eMax = (ex.emoji[ek[0]] ?: 1).coerceAtLeast(1)
                for (k in ek) {
                    val v = ex.emoji[k] ?: 0
                    if (v <= 0) continue
                    r.append(k).append(' ').append(v).append(' ').append(bar(v, eMax, 16)).append("\n")
                }
                r.append("表情点评 ").append(
                    when {
                        ex.emojiTotal == 0 -> "纯文字聊天 一个表情都没用过"
                        pct(ex.emojiMsgsEx, textN) >= 50 -> "表情是第二语言 一半以上的消息都带表情"
                        ex.emojiTotal >= textN -> "表情比字还多 平均一条消息不止一个"
                        else -> "表情点缀 该用的时候才用"
                    }
                ).append("\n")
            } else {
                r.append("表情排行 没有出现 Unicode 表情符号\n")
            }
        }
    }

    // ---------------- 核心 3~4：活跃时段与热力 / 作息与昼夜 ----------------

    /**
     * 核心第 3~4 段（全是时间维度）。
     *
     * 第 3 段 = 原【全天活跃频次】+ 原【活跃集中度】+ 原【活跃热力】：三段读的是同一份
     * "小时 × 星期"活跃矩阵 —— 频次回答"一天里哪几段忙"、集中度回答"忙得多集中"、
     * 热力回答"哪天的哪个小时最挤"。第 21 轮把热力并进本段，小时维度的两种读法
     * （六段条形 + 7×24 矩阵）落在同一张卡片里对照着看，不再用两张卡片说同一件事；同时删掉了
     * 原【活跃热力】段之前那 24 行「每小时分布」条形 —— 热力矩阵的列读的就是它，属于重复信息。
     * 第 4 段 = 原【昼夜结构】+ 原【作息画像】+ 原【昼夜话量】：三段都是"作息"，
     * 合成一段后先是结构占比、再是作息画像、最后是昼夜话量的三种读法，读起来是一条链。
     */
    private fun appendCoreTime(
        r: StringBuilder,
        ex: ExtraStats,
        totalAll: Int,
        textN: Int,
        hourDist: IntArray,
        // 第 22 轮：2B)「分享物与链接密度」并进这一段讲「聊什么」的地方，用到的
        // typeCount 必须显式传进来 —— 它在上层是构造统计时就地累加好的 map，
        // 不在这里重新扫一遍消息，也不查库。
        typeCount: Map<String, Int>,
    ) {
        var hMax = 0
        var hPeak = 0
        for (h in 0 until 24) {
            if (hourDist[h] > hMax) {
                hMax = hourDist[h]
                hPeak = h
            }
        }
        var deepNight = 0
        var daytime = 0
        var evening = 0
        for (h in 0 until 24) {
            when (h) {
                in 0..5 -> deepNight += hourDist[h]
                in 6..17 -> daytime += hourDist[h]
                else -> evening += hourDist[h]
            }
        }
        // 最忙的三个小时（24 格上跑三趟线性扫描，不排序、不留中间集合）
        val picked = IntArray(3) { -1 }
        for (k in 0 until 3) {
            var best = -1
            for (h in 0 until 24) {
                if (hourDist[h] <= 0) continue
                var dup = false
                for (p in 0 until k) {
                    if (picked[p] == h) dup = true
                }
                if (dup) continue
                if (best < 0 || hourDist[h] > hourDist[best]) best = h
            }
            if (best < 0) break
            picked[k] = best
        }
        var topSum = 0
        val topList = mutableListOf<String>()
        for (p in 0 until 3) {
            val h = picked[p]
            if (h < 0) continue
            topSum += hourDist[h]
            topList.add(h.toString())
        }

        // ── 2B) 分享物与链接密度（第 22 轮新增维度）─────────────────
        // 全部指标都由已有的 typeCount（消息类型计数，在主循环里就地累加）直接推导：
        // 不额外扫描一遍消息、不查库、不猜字段。每一种类型都对应真实存在过的消息条数，
        // 该类型一条都没有时就是 0，不做任何补齐或占位。
        r.append("\n【分享物与链接密度】\n")
        if (totalAll > 0) {
            val dCardLinks = typeCount["卡片/链接"] ?: 0
            val dLocations = typeCount["位置"] ?: 0
            val dTransfers = typeCount["转账"] ?: 0
            val dRedPacks = typeCount["红包"] ?: 0
            val dImages = typeCount["图片"] ?: 0
            val dVoices = typeCount["语音"] ?: 0
            val dVideos = typeCount["视频"] ?: 0
            val dMedia = dImages + dVoices + dVideos
            val dShare = dCardLinks + dLocations + dTransfers + dRedPacks
            r.append("链接与卡片：").append(dCardLinks).append(" 条（")
                .append(pct(dCardLinks, totalAll)).append("%）\n")
            r.append("位置分享：").append(dLocations).append(" 次\n")
            r.append("转账 / 红包：").append(dTransfers).append(" / ").append(dRedPacks)
                .append(" 笔\n")
            r.append("媒体分享：").append(dMedia).append(" 条（图 ").append(dImages)
                .append(" · 语音 ").append(dVoices).append(" · 视频 ").append(dVideos)
                .append("）\n")
            r.append("媒体占全部消息：").append(pct(dMedia, totalAll)).append("%\n")
            r.append("分享物总量：").append(dShare + dMedia).append(" 条（")
                .append(pct(dShare + dMedia, totalAll)).append("%）\n")
            // pct(...) 返回的是 Int 百分数（调用点自己补 "%"），而 shareDensityText
            // 收的是字符串形式的百分数，这里必须显式 toString()。
            r.append("分享密度：").append(shareDensityText(pct(dShare + dMedia, totalAll).toString()))
                .append("\n")
        } else {
            r.append("样本区间内没有消息，暂不出具分享密度画像。\n")
        }

        // ── 3) 活跃时段分布 ─────────────────────────────────────────
        r.append("\n【活跃时段与热力】\n")
        if (totalAll > 0) {
            r.append("最活跃时段：").append(hPeak).append(" 点（").append(hMax).append(" 条）\n")
            r.append("活跃小时数：").append(ex.activeHours).append(" 个\n")
            r.append("冷清小时数：").append(24 - ex.activeHours).append(" 个\n")
            if (topList.isNotEmpty()) {
                r.append("最忙三小时：").append(topList.joinToString(",")).append(" 点\n")
                r.append("三小时占比：").append(pct(topSum, totalAll)).append("%\n")
            }
            val bandNames = listOf("凌晨0-5", "上午6-11", "中午12-13", "下午14-17", "傍晚18-19", "夜晚20-23")
            val bands = listOf(0 to 6, 6 to 12, 12 to 14, 14 to 18, 18 to 20, 20 to 24)
            val bandSum = IntArray(6)
            var bMax = 0
            for (b in 0 until 6) {
                var sum = 0
                for (h in bands[b].first until bands[b].second) sum += hourDist[h]
                bandSum[b] = sum
                if (sum > bMax) bMax = sum
            }
            r.append("六段分布 凌晨 / 上午 / 中午 / 下午 / 傍晚 / 夜晚\n")
            for (b in 0 until 6) {
                r.append(bandNames[b]).append("点 ").append(bandSum[b]).append(" ")
                    .append(bar(bandSum[b], bMax, 16)).append("\n")
            }
            r.append("集中度点评 ").append(
                when {
                    pct(topSum, totalAll) >= 50 -> "越聊越集中 一半的话都挤在三个小时里"
                    ex.activeHours <= 8 -> "窗口很窄 只在固定的几个小时里出现"
                    else -> "分布均匀 一天里随时都可能说话"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有可统计的消息\n")
        }

        // ── 同段续：活跃热力（周几 × 小时）──────────────────────────
        // KPI 的值必须"以数字开头"：排版器会把值拆成「数字 + 单位」两段来画，
        // 值以中文开头时数字会被截出来、前缀会被丢掉，所以星期名一律写在结尾的读法行里。
        if (ex.heatPeakIdx >= 0) {
            r.append("峰值条数：").append(ex.heatPeak).append(" 条\n")
            r.append("峰值小时：").append(ex.heatPeakIdx % 24).append(" 点\n")
        }
        var heatCells = 0
        for (v in ex.heat) if (v > 0) heatCells++
        r.append("热力活跃格：").append(heatCells).append(" / ").append(7 * 24).append(" 格\n")
        // 必须连续 7 行、每行 24 个数字：少一行就不成块，会被两侧解析器退回普通正文行（宁缺勿错）
        for (d in 0 until 7) {
            r.append(DAY_NAMES[d]).append(" →")
            for (h in 0 until 24) r.append(' ').append(ex.heat[d * 24 + h])
            r.append("\n")
        }
        r.append("热力读法 每行一天、每列一小时，颜色越深越活跃")
        if (ex.heatPeakIdx >= 0) r.append("，峰值落在").append(heatLabel(ex.heatPeakIdx))
        r.append("\n")

        // ── 5) 作息与昼夜 ───────────────────────────────────────────
        r.append("\n【作息与昼夜】\n")
        if (totalAll > 0) {
            r.append("深夜 0-5 点：").append(pct(deepNight, totalAll)).append("%\n")
            r.append("白天 6-17 点：").append(pct(daytime, totalAll)).append("%\n")
            r.append("夜晚 18-23 点：").append(pct(evening, totalAll)).append("%\n")
            r.append("时段鉴定 ").append(
                when {
                    pct(deepNight, totalAll) >= 25 -> "夜猫子局，深夜最容易聊出真话"
                    pct(daytime, totalAll) >= 60 -> "白天型作息，聊的都是正事"
                    else -> "分布在正常人类时段"
                }
            ).append("\n")
        }
        if (ex.daySpanCount > 0) {
            r.append("平均活跃时长：").append(humanDuration(ex.daySpanSum / ex.daySpanCount)).append("\n")
            r.append("最长一天跨度：").append(humanDuration(ex.daySpanMax)).append("\n")
        }
        val earliest = clockShort(ex.earliestMinute)
        val latest = clockShort(ex.latestMinute)
        if (earliest.isNotEmpty()) r.append("最早活动：").append(earliest).append("\n")
        if (latest.isNotEmpty()) r.append("最晚活动：").append(latest).append("\n")
        if (earliest.isNotEmpty() && latest.isNotEmpty() && ex.latestMinute > ex.earliestMinute) {
            r.append("活跃窗口：").append(earliest).append(" → ").append(latest).append("\n")
            r.append("窗口宽度：")
                .append(humanDuration((ex.latestMinute - ex.earliestMinute).toLong() * 60_000L))
                .append("\n")
        }
        r.append("作息点评 ").append(
            when {
                ex.latestMinute >= 23 * 60 || (ex.earliestMinute in 0 until 5 * 60) -> "夜猫子作息 深夜还在线上"
                ex.earliestMinute in 0 until 7 * 60 -> "早起型作息 天亮就开始聊"
                ex.daySpanCount > 0 && ex.daySpanSum / ex.daySpanCount >= 12L * 3_600_000L ->
                    "全天候在线 一天能拉满十来个小时"
                else -> "作息正常 集中在白天与晚间"
            }
        ).append("\n")
        if (textN > 0) {
            val nightAvg = if (ex.nightText > 0) ex.nightChars / ex.nightText else 0L
            val dayAvg = if (ex.dayText > 0) ex.dayChars / ex.dayText else 0L
            val diff = if (nightAvg >= dayAvg) nightAvg - dayAvg else dayAvg - nightAvg
            r.append("深夜平均字数：").append(nightAvg).append(" 字\n")
            r.append("白天平均字数：").append(dayAvg).append(" 字\n")
            r.append("深夜长句占比：").append(pct(ex.nightLong, ex.nightText)).append("%\n")
            r.append("白天长句占比：").append(pct(ex.dayLong, ex.dayText)).append("%\n")
            r.append("昼夜字数差：").append(diff).append(" 字\n")
            r.append("话量点评 ").append(
                when {
                    ex.nightText == 0 -> "只在白天说话 深夜被完全跳过"
                    ex.dayText == 0 -> "只在深夜说话 白天一句不冒"
                    nightAvg > 0L && dayAvg > 0L && nightAvg * 2L >= dayAvg * 3L ->
                        "深夜话更长 夜里一句能顶白天几句"
                    nightAvg > 0L && dayAvg > 0L && dayAvg * 2L >= nightAvg * 3L ->
                        "白天话更长 夜里基本是短句收尾"
                    else -> "昼夜字数接近 说话方式没什么两样"
                }
            ).append("\n")
        }
    }

    // ---------------- 核心 6：节奏与沉默 ----------------

    /**
     * 核心第 6 段 = 原【互动节奏】+ 原【沉默与主动性】+ 原【沉默间隔谱】。
     *
     * 三段共用同一个 30 分钟阈值：节奏问"多快接上"，沉默问"多久没说话"，
     * 间隔谱把从"隔一分钟"到"隔几天"的全量静默铺成一张谱。
     * 合并后先给结论型 KPI（平均间隔 / 最长冷场 / 最长连发 / 最长一条），
     * 再给静默分档分布，最后落到"谁更常先开口"的关系结论上。
     */
    private fun appendCoreRhythm(
        r: StringBuilder,
        ex: ExtraStats,
        talker: String,
        isGroup: Boolean,
        textN: Int,
        gapSum: Long,
        gapCount: Int,
        maxGapMs: Long,
        maxStreak: Int,
        longestLen: Int,
        longestFromKey: String,
        nickCache: MutableMap<String, String>,
    ) {
        r.append("\n【节奏与沉默】\n")
        if (gapCount > 0) {
            r.append("平均间隔：").append(humanDuration(gapSum / gapCount)).append("\n")
        }
        if (maxGapMs > 0L) {
            r.append("最长冷场：").append(humanDuration(maxGapMs)).append("\n")
        }
        r.append("最长连发：").append(maxStreak).append(" 条\n")
        if (longestLen > 0) {
            r.append("最长一条：").append(longestLen).append(" 字")
            if (longestFromKey.isNotBlank()) {
                r.append("（").append(speakerDisplayName(longestFromKey, talker, isGroup, nickCache)).append("）")
            }
            r.append("\n")
        }
        r.append("沉默次数：").append(ex.silentBreaks).append(" 次\n")
        if (ex.silentBreaks > 0) {
            r.append("平均每次沉默：").append(humanDuration(ex.silentSum / ex.silentBreaks)).append("\n")
        }
        if (ex.replyGapCount > 0) {
            r.append("平均回复间隔：").append(humanDuration(ex.replyGapSum / ex.replyGapCount)).append("\n")
        }
        if (ex.maxGapEnd > ex.maxGapStart && ex.maxGapStart > 0L) {
            r.append("最长沉默区间 ").append(clockText(ex.maxGapStart)).append(" → ")
                .append(clockText(ex.maxGapEnd)).append("\n")
        }
        var silenceSum = 0
        for (v in ex.silence) silenceSum += v
        if (silenceSum > 0) {
            var sMax = 0
            for (v in ex.silence) if (v > sMax) sMax = v
            r.append("静默次数：").append(silenceSum).append(" 次\n")
            r.append("静默分档 相邻两条消息之间的间隔\n")
            for (i in ROUND18_SILENCE_LABELS.indices) {
                r.append(ROUND18_SILENCE_LABELS[i]).append(' ').append(ex.silence[i]).append(' ')
                    .append(bar(ex.silence[i], sMax, 16)).append("\n")
            }
            val short = ex.silence[0] + ex.silence[1] + ex.silence[2]
            r.append("静默点评 ").append(
                when {
                    pct(short, silenceSum) >= 70 -> "密集短间隔 大部分消息在一刻钟内就接上了"
                    ex.silence[5] + ex.silence[6] > silenceSum / 2 -> "半数是长间隔 更像留言板而不是实时聊天"
                    ex.maxGapMs >= 7L * 86_400_000L -> "中间断过一整周 这段关系有过长长的空白"
                    else -> "间隔分布正常 有快有慢"
                }
            ).append("\n")
        } else {
            r.append("静默分档 只有一条消息，没有可比较的间隔\n")
        }
        if (ex.initiator.isEmpty()) {
            r.append("谁更常先开口 没有跨越 30 分钟的中断\n")
        } else {
            r.append("谁更常先开口（沉默后的第一条）\n")
            val ik = topKeys(ex.initiator, 4)
            val iMax = (ex.initiator[ik[0]] ?: 1).coerceAtLeast(1)
            var firstKey = ik[0]
            for (k in ik) {
                val v = ex.initiator[k] ?: 0
                if (v <= 0) continue
                if (v > (ex.initiator[firstKey] ?: 0)) firstKey = k
                val dn = textSafe(speakerDisplayName(k, talker, isGroup, nickCache))
                r.append(dn).append(" ").append(v).append(" ").append(bar(v, iMax, 16)).append("\n")
            }
            r.append("先开口最多：")
                .append(textSafe(speakerDisplayName(firstKey, talker, isGroup, nickCache)))
                .append("（").append(ex.initiator[firstKey] ?: 0).append(" 次）\n")
        }
        // 本轮整合后把「话题段」的结论也并进节奏段（原【话题切换】的段落结构口径）
        val topicCount = ex.silentBreaks + 1
        if (textN > 0 || topicCount > 0) {
            r.append("话题段数：").append(topicCount).append(" 段\n")
            if (ex.maxTopicMs > 0L) {
                r.append("最长话题：").append(humanDuration(ex.maxTopicMs)).append("\n")
            }
            if (ex.maxTopicEnd > ex.maxTopicStart && ex.maxTopicStart > 0L) {
                r.append("最长话题段 ").append(clockText(ex.maxTopicStart)).append(" → ")
                    .append(clockText(ex.maxTopicEnd)).append("\n")
            }
            if (ex.silentBreaks > 0) {
                r.append("切换间隔：").append(humanDuration(ex.silentSum / ex.silentBreaks)).append("/次\n")
            } else {
                r.append("切换节奏 全程连贯，没有跨越 30 分钟的中断\n")
            }
        }
    }

    // ---------------- 核心 7~9：消息长度 / 情绪与语气 / 高频词与口头禅 ----------------

    /**
     * 核心第 7~9 段（都是"正文本身的形状"）。
     *
     * 第 7 段 = 原【消息长度画像】+ 原【废话程度鉴定】+ 原【每人说话画像】：
     * 三者都是长度口径（整体平均 / 分档占比 / 每人平均），合并后不再重复报同一批分档。
     * 第 8 段 = 原【情绪指纹】+ 原【标点与语气】：一个是消息级命中，一个是每百字密度，
     * 讲的都是"语气"，放在一张卡片里对比着看才有意义。
     * 第 9 段 = 原【高频词】+ 原【口头禅】：两套 n-gram / 词表口径，同一张卡片里给两张云。
     */
    private fun appendCoreContent(
        r: StringBuilder,
        ex: ExtraStats,
        talker: String,
        isGroup: Boolean,
        textN: Int,
        lenShort: Int,
        lenMid: Int,
        lenLong: Int,
        lenHuge: Int,
        laugh: Int,
        question: Int,
        exclaim: Int,
        wave: Int,
        speechless: Int,
        wordMap: Map<String, Int>,
        nickCache: MutableMap<String, String>,
    ) {
        // ── 7) 消息长度画像 ─────────────────────────────────────────
        r.append("\n【消息长度画像】\n")
        if (textN > 0) {
            r.append("平均字数：").append((ex.lenSum.toDouble() / textN).roundToInt()).append(" 字\n")
            r.append("短句占比：").append(pct(lenShort, textN)).append("%\n")
            r.append("中句占比：").append(pct(lenMid, textN)).append("%\n")
            r.append("长句占比：").append(pct(lenLong + lenHuge, textN)).append("%\n")
            r.append("鉴定 ").append(
                when {
                    pct(lenShort, textN) >= 60 -> "全员惜字如金"
                    pct(lenHuge, textN) >= 15 -> "小作文大户实锤"
                    else -> "正常人类浓度"
                }
            ).append("\n")
            r.append("长度分档 越多越短说明越像即时对话\n")
            val lenNames = listOf("≤5字", "5到20字", "20到50字", "50字以上")
            val lenBands = intArrayOf(lenShort, lenMid, lenLong, lenHuge)
            var lMax = 0
            for (v in lenBands) if (v > lMax) lMax = v
            for (i in lenNames.indices) {
                r.append(lenNames[i]).append(' ').append(lenBands[i]).append(' ')
                    .append(bar(lenBands[i], lMax, 16)).append("\n")
            }
            // 每人说话画像（第 18 轮口径：条数不足 ROUND18_PROFILE_MIN_MSGS 的不参与排名）
            val avgMap = mutableMapOf<String, Int>()
            for ((k, n) in ex.rankTexts) {
                if (n < ROUND18_PROFILE_MIN_MSGS) continue
                val chars = ex.rankChars[k] ?: 0
                avgMap[k] = chars / n
            }
            r.append("画像人数：").append(avgMap.size).append(" 人\n")
            val avgKeys = topKeys(avgMap, 6)
            if (avgKeys.isNotEmpty()) {
                r.append("人均字数榜 只统计文字消息，条数不足 ")
                    .append(ROUND18_PROFILE_MIN_MSGS).append(" 条不参与\n")
                val aMax = (avgMap[avgKeys[0]] ?: 1).coerceAtLeast(1)
                for ((i, k) in avgKeys.withIndex()) {
                    val v = avgMap[k] ?: 0
                    if (v <= 0) continue
                    r.append(i + 1).append(". ")
                        .append(textSafe(speakerDisplayName(k, talker, isGroup, nickCache)))
                        .append(' ').append(v).append(' ').append(bar(v, aMax, 16)).append("\n")
                }
            }
            val longKeys = topKeys(ex.rankLongest, 3)
            if (longKeys.isNotEmpty()) {
                val top = longKeys[0]
                val topLen = ex.rankLongest[top] ?: 0
                if (topLen > 0) {
                    r.append("最长单条：").append(topLen).append(" 字\n")
                    r.append("最长单条作者：")
                        .append(textSafe(speakerDisplayName(top, talker, isGroup, nickCache))).append("\n")
                }
            }
        }
        if (ex.topBodies.isEmpty()) {
            r.append("最长摘录：无\n")
        } else {
            for ((i, tp) in ex.topBodies.withIndex()) {
                r.append("最长摘录 ").append(i + 1).append("：").append(tp.second.length).append(" 字")
                val who = textSafe(speakerDisplayName(tp.first, talker, isGroup, nickCache))
                if (who.isNotBlank()) r.append(" · ").append(who)
                r.append("\n")
                r.append(excerpt(tp.second)).append("\n")
            }
        }

        // ── 8) 情绪与语气（消息级命中 + 每百字密度，两种口径互补）──
        r.append("\n【情绪与语气】\n")
        if (textN > 0) {
            r.append("哈哈哈浓度：").append(pct(laugh, textN)).append("%\n")
            r.append("疑问句比例：").append(pct(question, textN)).append("%\n")
            r.append("感叹号比例：").append(pct(exclaim, textN)).append("%\n")
            r.append("波浪号比例：").append(pct(wave, textN)).append("%\n")
            r.append("无语指数　：").append(pct(speechless, textN)).append("%\n")
        }
        if (ex.charTotal > 0) {
            val qD = density(ex.qMark, ex.charTotal)
            val eD = density(ex.eMark, ex.charTotal)
            val lD = density(ex.ellipsis, ex.charTotal)
            val wD = density(ex.tilde, ex.charTotal)
            r.append("问号密度：").append(oneDecimal(qD)).append(" /百字\n")
            r.append("感叹密度：").append(oneDecimal(eD)).append(" /百字\n")
            r.append("省略号密度：").append(oneDecimal(lD)).append(" /百字\n")
            r.append("波浪号密度：").append(oneDecimal(wD)).append(" /百字\n")
            r.append("字母占比：").append(pct(ex.letterChars, ex.charTotal)).append("%\n")
            r.append("表情符号率：").append(pct(ex.emojiMsgs, textN)).append("%\n")
            r.append("颜文字率：").append(pct(ex.kaoMsgs, textN)).append("%\n")
            r.append("语气倾向：").append(toneTrend(qD, eD, lD, wD, ex, textN)).append("\n")
        } else if (textN == 0) {
            r.append("标点统计：无可用正文\n")
        }

        // 情绪词（消息级词表命中）：原来单独占一个【情绪词雷达】段，与本节讲的是同一件事
        // ——"情绪从哪读出来"。第 21 轮并进本节：上面是标点/语气的频次口径，下面是正负向词的
        // 词表口径，两种口径在同一张卡片里对照着看，比分散在两个维度里更容易读。
        if (textN > 0) {
            r.append("正向词命中率：").append(pct(ex.moodPosMsgs, textN)).append("%\n")
            r.append("负向词命中率：").append(pct(ex.moodNegMsgs, textN)).append("%\n")
            r.append("正负比 ").append(ratioText(ex.moodPosMsgs, ex.moodNegMsgs)).append("\n")
            val moodPosKeys = topKeys(ex.moodPos, 8)
            val moodNegKeys = topKeys(ex.moodNeg, 8)
            if (moodPosKeys.isNotEmpty()) {
                val chips = StringBuilder()
                for ((i, k) in moodPosKeys.withIndex()) {
                    if (i > 0) chips.append("  ")
                    chips.append(k).append("×").append(ex.moodPos[k] ?: 0)
                }
                r.append(chips).append("\n")
            }
            if (moodNegKeys.isNotEmpty()) {
                val chips = StringBuilder()
                for ((i, k) in moodNegKeys.withIndex()) {
                    if (i > 0) chips.append("  ")
                    chips.append(k).append("×").append(ex.moodNeg[k] ?: 0)
                }
                r.append(chips).append("\n")
            }
            r.append("情绪点评 ").append(
                when {
                    ex.moodPosMsgs == 0 && ex.moodNegMsgs == 0 -> "情绪不写在明面上 两边都没有明显情绪词"
                    ex.moodPosMsgs >= ex.moodNegMsgs * 3 -> "情绪很正 正向词压倒性地多"
                    ex.moodNegMsgs >= ex.moodPosMsgs * 3 -> "情绪偏低 负向词明显更多"
                    else -> "正负交织 有开心也有吐槽"
                }
            ).append("\n")
        }

        // ── 9) 高频词与口头禅 ───────────────────────────────────────
        r.append("\n【高频词与口头禅】\n")
        if (wordMap.isNotEmpty()) {
            val wk = topKeys(wordMap, 12)
            // 词频行：整行只允许 `词×次数`（两侧解析器靠这一点认出标签云）
            val chips = StringBuilder()
            for ((i, k) in wk.withIndex()) {
                if (i > 0) chips.append("  ")
                chips.append(k).append("×").append(wordMap[k])
            }
            r.append(chips).append("\n")
        } else {
            r.append("高频词 无\n")
        }
        r.append("口头禅浓度：").append(pct(ex.clicheMsgs, textN)).append("%\n")
        val ck = topKeys(ex.cliche, 12)
        if (ck.isNotEmpty()) {
            val top = ck[0]
            r.append("最常挂嘴边：").append(top).append("（").append(ex.cliche[top] ?: 0).append(" 次）\n")
            r.append("口头禅统计口径 含该词的消息条数\n")
            val chip2 = StringBuilder()
            for ((i, k) in ck.withIndex()) {
                if (i > 0) chip2.append("  ")
                chip2.append(k).append("×").append(ex.cliche[k] ?: 0)
            }
            r.append(chip2).append("\n")
        } else {
            r.append("最常挂嘴边：无\n")
        }
    }

    // ---------------- 核心 10~13：话题 / 发言与平衡 / 特殊消息 / 开场收尾 ----------------

    /**
     * 核心第 10~13 段（关系与事件）。
     *
     * 第 10 段 = 原【话题关键词】+ 原【话题切换】+ 原【话题时段偏好】：
     * 话题三连（聊什么 / 聊多久 / 什么时候聊）本来就是一组，合并成一张"话题雷达"。
     * 第 11 段 = 原【发言排行】+ 原【互动平衡】：排行是绝对量、平衡是占比，同一批数据的两种读法。
     * 第 12 段 = 原【@与互动消息】+ 原【特殊消息雷达】+ 原【撤回与系统事件】：
     * 全是"非普通文字"的消息类型，正好合成一栏。
     * 第 13 段 = 原【每日开场与收尾】（第 18 轮口径，原样保留）。
     */
    private fun appendCoreInteraction(
        r: StringBuilder,
        ex: ExtraStats,
        talker: String,
        isGroup: Boolean,
        textN: Int,
        totalAll: Int,
        typeCount: Map<String, Int>,
        rank: Map<String, Int>,
        nickCache: MutableMap<String, String>,
        showRank: Boolean,
    ) {
        // ── 10) 话题雷达与时段 ──────────────────────────────────────
        r.append("\n【话题雷达与时段】\n")
        if (textN > 0) {
            r.append("话题浓度：").append(pct(ex.topicMsgs, textN)).append("%\n")
            val top = topKeys(ex.topic, 12)
            if (top.isNotEmpty()) {
                r.append("最热话题 ").append(top[0]).append("：")
                    .append(ex.topic[top[0]] ?: 0).append(" 次\n")
                r.append("话题词表 固定 28 词的命中次数\n")
                val chips = StringBuilder()
                for ((i, w) in top.withIndex()) {
                    if (i > 0) chips.append("  ")
                    chips.append(w).append("×").append(ex.topic[w] ?: 0)
                }
                r.append(chips).append("\n")
            } else {
                r.append("话题命中：0 个\n")
            }
            r.append("深夜话题数：").append(ex.topicNightMsgs).append(" 条\n")
            r.append("白天话题数：").append(ex.topicDayMsgs).append(" 条\n")
            if (ex.nightText > 0) {
                r.append("深夜话题率：").append(pct(ex.topicNightMsgs, ex.nightText)).append("%\n")
            }
            if (ex.dayText > 0) {
                r.append("白天话题率：").append(pct(ex.topicDayMsgs, ex.dayText)).append("%\n")
            }
            val nightKeys = topKeys(ex.topicNight, 6)
            if (nightKeys.size >= 2) {
                r.append("深夜最常聊\n")
                val nightMax = (ex.topicNight[nightKeys[0]] ?: 1).coerceAtLeast(1)
                for (k in nightKeys) {
                    val v = ex.topicNight[k] ?: 0
                    if (v <= 0) continue
                    r.append(k).append(' ').append(v).append(' ').append(bar(v, nightMax, 16)).append("\n")
                }
            }
            val dayKeys = topKeys(ex.topicDay, 6)
            if (dayKeys.size >= 2) {
                r.append("白天最常聊\n")
                val dayMax = (ex.topicDay[dayKeys[0]] ?: 1).coerceAtLeast(1)
                for (k in dayKeys) {
                    val v = ex.topicDay[k] ?: 0
                    if (v <= 0) continue
                    r.append(k).append(' ').append(v).append(' ').append(bar(v, dayMax, 16)).append("\n")
                }
            }
            val nightRate = if (ex.nightText > 0) pct(ex.topicNightMsgs, ex.nightText) else 0
            val dayRate = if (ex.dayText > 0) pct(ex.topicDayMsgs, ex.dayText) else 0
            r.append("时段点评 ").append(
                when {
                    nightRate > dayRate + 5 -> "深夜更好聊 夜里的话题密度反而更高"
                    ex.topicNightMsgs == 0 -> "夜聊不聊正题 深夜基本只是闲聊"
                    ex.topicDayMsgs == 0 -> "白天不聊正题 话题都堆在夜里"
                    else -> "昼夜话题密度接近 什么时候都能聊到点子上"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }

        // ── 11) 发言与互动平衡 ──────────────────────────────────────
        if (showRank) {
            r.append("\n").append(if (isGroup) "【发言排行 Top10】" else "【发言对比】").append("\n")
            val rk = topKeys(rank, 10)
            if (rk.isNotEmpty()) {
                val rMax = rank[rk[0]] ?: 1
                for ((i, key) in rk.withIndex()) {
                    val v = rank[key] ?: 0
                    val dn = speakerDisplayName(key, talker, isGroup, nickCache)
                    r.append(i + 1).append(". ").append(dn).append("：").append(v).append(" 条 ")
                        .append(bar(v, rMax, 16)).append("\n")
                }
            }
        }
        // 第 22 轮：把原【互动平衡】收敛进【互动均衡度】—— 原有的占比/条数比/平衡度全部保留，
        // 另外补一个**基尼系数**（纯由 rank 推导，不额外扫描）来回答"话量到底有多集中"。
        // 之所以合并成一个维度而不是并列两个：两者读的是同一份数据（rank），分开列只会重复。
        r.append("\n【互动均衡度】\n")
        val rankTotal = rank.values.sum()
        val mine = rank["我"] ?: 0
        val mineChars = ex.rankChars["我"] ?: 0
        val allChars = ex.rankChars.values.sum()
        val others = (rankTotal - mine).coerceAtLeast(0)
        val otherChars = (allChars - mineChars).coerceAtLeast(0)
        r.append("我的条数占比：").append(pct(mine, rankTotal)).append("%\n")
        r.append("我的字数占比：").append(pct(mineChars, allChars)).append("%\n")
        if (isGroup) {
            val othersMap = rank.filterKeys { it != "我" }
            val ok = topKeys(othersMap, 3)
            for ((i, k) in ok.withIndex()) {
                val dn = textSafe(speakerDisplayName(k, talker, isGroup, nickCache))
                r.append("TOP").append(i + 1).append(" ").append(dn).append(" 占比：")
                    .append(pct(rank[k] ?: 0, rankTotal)).append("%\n")
            }
            r.append("条数比 ").append(ratioText(mine, others)).append("（我 vs 其余人）\n")
            val top1Pct = if (ok.isEmpty()) 0 else pct(rank[ok[0]] ?: 0, rankTotal)
            r.append("平衡度：").append(groupBalanceText(top1Pct)).append("\n")
        } else {
            r.append("条数比 ").append(ratioText(mine, others)).append("（我 vs 对方）\n")
            r.append("字数比 ").append(ratioText(mineChars, otherChars)).append("（我 vs 对方）\n")
            r.append("平衡度：").append(balanceText(mine, others)).append("\n")
        }
        // 基尼系数：发言条数的洛伦兹曲线面积（0 = 人人一样多，1 = 一个人全包）
        val shares = rank.values.filter { it > 0 }.sorted()
        val nShare = shares.size
        val totalShare = shares.sum()
        if (nShare > 1 && totalShare > 0) {
            var acc = 0.0
            for (i in shares.indices) acc += (i + 1).toDouble() * shares[i]
            val gini = 2.0 * acc / (nShare.toDouble() * totalShare) - (nShare + 1).toDouble() / nShare
            val g100 = (gini * 100).roundToInt().coerceIn(0, 100)
            r.append("发言基尼系数：").append(g100 / 100).append(".")
                .append((g100 % 100).toString().padStart(2, '0'))
                .append("（").append(giniText(gini)).append("）\n")
            r.append("发言人数：").append(nShare).append(" 人（人均 ")
                .append(totalShare / nShare).append(" 条）\n")
        }

        // ── 12) 特殊消息与互动 ──────────────────────────────────────
        r.append("\n【特殊消息与互动】\n")
        if (textN > 0) {
            r.append("@提及次数：").append(ex.atAny).append(" 次\n")
            r.append("其中@我：").append(ex.atMeEx).append(" 次\n")
            r.append("引用回复：").append(ex.quoteMsgs).append(" 条\n")
            r.append("互动消息占比：").append(pct(ex.atAny + ex.quoteMsgs, textN)).append("%\n")
            r.append("互动点评 ").append(
                when {
                    ex.atAny + ex.quoteMsgs == 0 -> "既不 @ 人也不引用，全靠正文接话"
                    pct(ex.atAny + ex.quoteMsgs, textN) >= 10 -> "@ 与引用用得很勤，点名型选手"
                    else -> "@ 与引用不多，自然接话型"
                }
            ).append("\n")
        }
        if (totalAll > 0) {
            val transfer = typeCount["转账"] ?: 0
            val redPacket = typeCount["红包"] ?: 0
            val location = typeCount["位置"] ?: 0
            val recall = typeCount["撤回"] ?: 0
            val system = typeCount["系统"] ?: 0
            r.append("转账条数：").append(transfer).append(" 条\n")
            r.append("红包条数：").append(redPacket).append(" 条\n")
            r.append("位置条数：").append(location).append(" 条\n")
            r.append("撤回条数：").append(recall).append(" 条\n")
            r.append("系统消息：").append(system).append(" 条\n")
            r.append("特殊占比：")
                .append(pct(transfer + redPacket + location + recall, totalAll)).append("%\n")
            r.append("系统占比：").append(pct(ex.sysMsgs, totalAll)).append("%\n")
            r.append("撤回事件：").append(ex.revokeMsgs).append(" 次\n")
            r.append("撤回率：").append(pct(ex.revokeMsgs, totalAll)).append("%\n")
            val rk = topKeys(ex.revokeBy, 6)
            if (rk.isNotEmpty()) {
                r.append("撤回者榜 谁最爱说出口又收回去\n")
                val rMax = (ex.revokeBy[rk[0]] ?: 1).coerceAtLeast(1)
                for ((i, k) in rk.withIndex()) {
                    val v = ex.revokeBy[k] ?: 0
                    if (v <= 0) continue
                    r.append(i + 1).append(". ").append(textSafe(k))
                        .append(' ').append(v).append(' ').append(bar(v, rMax, 16)).append("\n")
                }
            }
            r.append("雷达点评 ").append(
                when {
                    transfer + redPacket > 0 && ex.revokeMsgs > 0 -> "有钱也有撤回 红包转账和收回消息都在"
                    transfer + redPacket > 0 -> "有红包转账来往 关系不算清淡"
                    ex.revokeMsgs > 0 -> "有人撤回消息 说出口又收回去"
                    ex.sysMsgs == 0 -> "零系统消息 清清爽爽，没人撤回也没人进退群"
                    else -> "零星系统事件 不影响聊天节奏"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有可统计的消息\n")
        }

        // ── 13) 每日开场与收尾 ──────────────────────────────────────
        r.append("\n【每日开场与收尾】\n")
        if (ex.senderDayKey != 0) {
            var openerSum = 0
            for (v in ex.dayOpener.values) openerSum += v
            r.append("归因天数：").append(openerSum).append(" 天\n")
            val ok = topKeys(ex.dayOpener, 6)
            if (ok.isNotEmpty()) {
                val oTop = ok[0]
                r.append("开场王：")
                    .append(textSafe(speakerDisplayName(oTop, talker, isGroup, nickCache))).append("\n")
                r.append("每日开场榜 每天第一条文字消息是谁发的\n")
                val oMax = (ex.dayOpener[oTop] ?: 1).coerceAtLeast(1)
                for ((i, k) in ok.withIndex()) {
                    val v = ex.dayOpener[k] ?: 0
                    if (v <= 0) continue
                    r.append(i + 1).append(". ")
                        .append(textSafe(speakerDisplayName(k, talker, isGroup, nickCache)))
                        .append(' ').append(v).append(' ').append(bar(v, oMax, 16)).append("\n")
                }
            }
            val ck = topKeys(ex.dayCloser, 6)
            if (ck.isNotEmpty()) {
                val cTop = ck[0]
                r.append("收尾王：")
                    .append(textSafe(speakerDisplayName(cTop, talker, isGroup, nickCache))).append("\n")
                r.append("每日收尾榜 每天最后一条文字消息是谁发的\n")
                val cMax = (ex.dayCloser[cTop] ?: 1).coerceAtLeast(1)
                for ((i, k) in ck.withIndex()) {
                    val v = ex.dayCloser[k] ?: 0
                    if (v <= 0) continue
                    r.append(i + 1).append(". ")
                        .append(textSafe(speakerDisplayName(k, talker, isGroup, nickCache)))
                        .append(' ').append(v).append(' ').append(bar(v, cMax, 16)).append("\n")
                }
            }
            val oTopV = if (ok.isNotEmpty()) ex.dayOpener[ok[0]] ?: 0 else 0
            val cTopV = if (ck.isNotEmpty()) ex.dayCloser[ck[0]] ?: 0 else 0
            val samePerson = ok.isNotEmpty() && ck.isNotEmpty() && ok[0] == ck[0]
            r.append("开场收尾点评 ").append(
                when {
                    samePerson && oTopV * 2 >= openerSum -> "同一个人既开场又收尾 每天的聊天由他起头也由他收尾"
                    oTopV <= 1 && cTopV <= 1 -> "谁先冒头都不固定 没有固定的开场人"
                    oTopV * 2 >= openerSum -> "一个人常先开口 每天多半是他先冒头"
                    else -> "开场与收尾都比较分散"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }
    }

    // ---------------- 进阶包一（时间与趋势）：4 个维度 ----------------

    /**
     * 时间包：活跃日历与趋势 / 回应速度 / 活跃密度与连续 / 连击与轮次。
     *
     * 整合来源：原【活跃日历】+【每日趋势】；原【回复延迟分布】+【回应速度画像】
     * （只保留信息量更大的 7 档细分布，4 档粗分布是同一批数据的重复口径，已去掉）；
     * 原【发言密度】+【连续活跃】；原【连击与打断】+【对话轮次结构】。
     */
    private fun appendTimePackSections(
        r: StringBuilder,
        ex: ExtraStats,
        talker: String,
        isGroup: Boolean,
        textN: Int,
        totalAll: Int,
        weekday: IntArray,
        nickCache: MutableMap<String, String>,
    ) {
        // ── 14) 活跃日历与趋势 ──────────────────────────────────────
        if (weekday.sum() > 0) {
            r.append("\n【活跃日历与趋势】\n")
            val wMax = weekday.max()
            for (i in 0 until 7) {
                r.append(DAY_NAMES[i]).append(" ").append(weekday[i]).append(" ")
                    .append(bar(weekday[i], wMax, 16)).append("\n")
            }
            val weekend = weekday[5] + weekday[6]
            val workday = weekday.sum() - weekend
            r.append("工作日 / 周末：").append(workday).append(" / ").append(weekend).append(" 条\n")
            val tn = ex.trendBuckets
            if (tn >= 2) {
                var peak = 0
                var lowest = Int.MAX_VALUE
                var filled = 0
                for (i in 0 until tn) {
                    val v = ex.trend[i]
                    if (v > peak) peak = v
                    if (v < lowest) lowest = v
                    if (v > 0) filled++
                }
                if (peak > 0) {
                    r.append("峰值段条数：").append(peak).append(" 条\n")
                    r.append("谷值段条数：").append(lowest).append(" 条\n")
                    r.append("有消息的段：").append(filled).append(" 个\n")
                    r.append("分段趋势 每根柱是一个时间分段\n")
                    // 每根柱一行（标签含中文 + 数字 → 两侧都当柱状图；零值的桶没有 █ 也仍然认得出）
                    for (i in 0 until tn) {
                        val v = ex.trend[i]
                        val label = ex.trendLabels.getOrNull(i).orEmpty().ifEmpty { "第 ${i + 1} 段" }
                        r.append(label).append(' ').append(v).append(' ').append(bar(v, peak, 16)).append("\n")
                    }
                    val third = (tn / 3).coerceAtLeast(1)
                    var head = 0
                    var tail = 0
                    for (i in 0 until third) head += ex.trend[i]
                    for (i in tn - third until tn) tail += ex.trend[i]
                    r.append("趋势点评 ").append(
                        when {
                            tail * 2 > head * 3 -> "越聊越热 后段明显比前段活跃"
                            head * 2 > tail * 3 -> "渐渐冷清 后段不如前段活跃"
                            else -> "热度平稳 前后段相差不大"
                        }
                    ).append("\n")
                }
            }
        }

        // ── 15) 回应速度 ────────────────────────────────────────────
        r.append("\n【回应速度】\n")
        if (ex.replyGapCount > 0) {
            val half = (ex.replyGapCount + 1) / 2
            var acc = 0
            var median = LATENCY_FINE_BANDS - 1
            for (i in 0 until LATENCY_FINE_BANDS) {
                acc += ex.latencyFine[i]
                if (acc >= half) {
                    median = i
                    break
                }
            }
            var fineMax = 0
            for (v in ex.latencyFine) if (v > fineMax) fineMax = v
            r.append("平均回复速度：").append(humanDuration(ex.replyGapSum / ex.replyGapCount)).append("\n")
            r.append("中位速度：").append(LATENCY_FINE_LABELS[median]).append("\n")
            r.append("秒回率：").append(pct(ex.latency[0], ex.replyGapCount)).append("%\n")
            r.append("最快回复：").append(humanDuration(ex.fastestGapMs)).append("\n")
            r.append("回复样本：").append(ex.replyGapCount).append(" 次\n")
            r.append("秒回次数：").append(ex.latencyFine[0]).append(" 次\n")
            r.append("慢回复数：").append(ex.latencyFine[5] + ex.latencyFine[6]).append(" 次\n")
            // 标签不含空格：0 条的那一档整行没有 █，解析器会退回 PLAIN_COUNT 分支（^(\\S+)\\s+(\\d+)$）
            r.append("速度分档 30 分钟内的回复间隔\n")
            for (i in 0 until LATENCY_FINE_BANDS) {
                r.append(LATENCY_FINE_LABELS[i]).append(' ')
                    .append(ex.latencyFine[i]).append(' ')
                    .append(bar(ex.latencyFine[i], fineMax, 16)).append("\n")
            }
            val fastPct = pct(ex.latencyFine[0] + ex.latencyFine[1], ex.replyGapCount)
            r.append("速度点评 ").append(
                when {
                    fastPct >= 60 -> "秒回成风 多半在一分钟内就接上"
                    fastPct >= 30 -> "回应利索 大部分消息有及时回音"
                    ex.latencyFine[6] * 2 >= ex.replyGapCount -> "慢热型 一半以上拖到十分钟开外"
                    else -> "节奏正常 快慢分布均匀"
                }
            ).append("\n")
            val fast = topKeys(ex.fastReply, 1)
            if (fast.isNotEmpty()) {
                r.append("谁最爱秒回：")
                    .append(textSafe(speakerDisplayName(fast[0], talker, isGroup, nickCache)))
                    .append("（").append(ex.fastReply[fast[0]] ?: 0).append(" 次）\n")
            }
        } else {
            r.append("样本不足 该时段没有 30 分钟以内的连续对话\n")
        }

        // ── 16) 活跃密度与连续 ──────────────────────────────────────
        r.append("\n【活跃密度与连续】\n")
        if (ex.activeDays > 0) {
            val perDay = if (ex.activeDays > 0) totalAll / ex.activeDays else 0
            r.append("日均条数：").append(perDay).append(" 条\n")
            r.append("峰值日条数：").append(ex.dayMaxCount).append(" 条\n")
            r.append("活跃天数：").append(ex.activeDays).append(" 天\n")
            if (ex.workdayDays > 0) {
                r.append("工作日日均：").append(ex.workdayMsgs / ex.workdayDays).append(" 条\n")
            }
            if (ex.weekendDays > 0) {
                r.append("周末日均：").append(ex.weekendMsgs / ex.weekendDays).append(" 条\n")
            }
            r.append("活跃小时数：").append(ex.activeHours).append(" 个\n")
            if (ex.activeHours > 0) {
                r.append("每小时条数：").append(oneDecimal(totalAll.toDouble() / ex.activeHours)).append(" 条\n")
            }
            if (ex.dayMaxStart > 0L) r.append("最忙的一天 ").append(dayTickLabel(ex.dayMaxStart)).append("\n")
            r.append("最长连续：").append(ex.dayRunMax).append(" 天\n")
            r.append("当前连续：").append(ex.dayRun).append(" 天\n")
            r.append("断档次数：").append(ex.dayBreak).append(" 次\n")
            r.append("覆盖天数：").append(ex.activeDays).append(" 天\n")
            r.append("密度点评 ").append(
                when {
                    perDay >= 200 -> "高频轰炸 手机基本没停过"
                    perDay >= 60 -> "聊天很密 一天能刷好几屏"
                    ex.dayBreak == 0 -> "全程连续 每一条时间线上都有话"
                    ex.dayRunMax <= 2 -> "来一阵走一阵 基本没有连着聊的日子"
                    perDay >= 15 -> "日常挂机 想起来就聊两句"
                    else -> "轻度联络 几天才冒一次头"
                }
            ).append("\n")
        } else if (totalAll > 0) {
            r.append("统计口径 该时段没有可统计的消息\n")
        }

        // ── 17) 连击与轮次 ──────────────────────────────────────────
        r.append("\n【连击与轮次】\n")
        if (textN > 0) {
            val turns = if (ex.turns > 0) ex.turns else 1
            r.append("最长连击：").append(ex.streakMax).append(" 条\n")
            r.append("平均连击：").append(oneDecimal(textN.toDouble() / turns.toDouble())).append(" 条\n")
            r.append("打断次数：").append(ex.interrupts).append(" 次\n")
            if (ex.roundCount > 0) {
                r.append("对话轮数：").append(ex.roundCount).append(" 轮\n")
                r.append("平均轮长：").append(ex.roundLenSum / ex.roundCount).append(" 条\n")
                r.append("最长轮长：").append(ex.roundMax).append(" 条\n")
                var bandMax = 0
                for (v in ex.roundBands) if (v > bandMax) bandMax = v
                r.append("轮长分布 每轮之间相隔 30 分钟以上\n")
                for (i in ROUND18_ROUND_LABELS.indices) {
                    r.append(ROUND18_ROUND_LABELS[i]).append(' ').append(ex.roundBands[i]).append(' ')
                        .append(bar(ex.roundBands[i], bandMax, 16)).append("\n")
                }
            }
            if (ex.streakMaxKey.isNotEmpty()) {
                r.append("连击王：")
                    .append(textSafe(speakerDisplayName(ex.streakMaxKey, talker, isGroup, nickCache)))
                    .append("\n")
            }
            val avgRound = if (ex.roundCount > 0) ex.roundLenSum / ex.roundCount else 0
            r.append("轮次点评 ").append(
                when {
                    ex.streakMax >= 10 -> "有人能连发十条不喘气"
                    ex.roundMax >= 100 -> "有一轮聊了上百条 那是压轴长谈"
                    avgRound in 1..2 -> "一句一停 基本都是单句往来，说完就散"
                    avgRound >= 20 -> "一聊就是一大轮 中间几乎不歇"
                    ex.roundCount == 1 -> "整段只有一轮 中间没有超过半小时的中断"
                    ex.turns * 2 >= textN -> "轮流发言，几乎没有连发"
                    ex.interrupts * 4 >= turns -> "话题老被打断，跳得很快"
                    else -> "有来有回，节奏正常"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }
    }

    // ---------------- 进阶包二（关系与习惯）：4 个维度 ----------------

    /**
     * 关系包：接话·提问·默契 / 复读与重复 / 回复速度榜 / 个人作息雷达。
     *
     * 整合来源：原【被接话榜】+【提问与回应】+【默契搭档】（都是"谁跟谁怎么互动"）；
     * 原【复读与重复】原样保留。
     * 新维度：【回复速度榜】把"谁最快/谁最慢"落到人头上（原来只有"谁最爱秒回"一个单项），
     * 【个人作息雷达】把 24 格小时直方图按人拆开（原来只有整体作息）。
     */
    private fun appendRelationPackSections(
        r: StringBuilder,
        ex: ExtraStats,
        talker: String,
        isGroup: Boolean,
        textN: Int,
        nickCache: MutableMap<String, String>,
    ) {
        // ── 18) 接话·提问·默契 ──────────────────────────────────────
        r.append("\n【接话·提问·默契】\n")
        if (ex.turnsAttributed > 0) {
            r.append("接话次数：").append(ex.turnsAttributed).append(" 次\n")
            r.append("我的被接话：").append(ex.myFetched).append(" 次\n")
            if (ex.myTexts > 0) {
                r.append("我的被接话率：").append(pct(ex.myFetched, ex.myTexts)).append("%\n")
            }
            val fetchKeys = topKeys(ex.replyFetch, 6)
            if (fetchKeys.isNotEmpty()) {
                r.append("被接话榜 谁的话最容易被别人接上\n")
                val fetchMax = (ex.replyFetch[fetchKeys[0]] ?: 1).coerceAtLeast(1)
                for (k in fetchKeys) {
                    val v = ex.replyFetch[k] ?: 0
                    if (v <= 0) continue
                    r.append(textSafe(speakerDisplayName(k, talker, isGroup, nickCache)))
                        .append(' ').append(v).append(' ').append(bar(v, fetchMax, 16)).append("\n")
                }
            }
            val giveKeys = topKeys(ex.replyGive, 1)
            if (giveKeys.isNotEmpty()) {
                val v = ex.replyGive[giveKeys[0]] ?: 0
                r.append("接话王 ").append(textSafe(speakerDisplayName(giveKeys[0], talker, isGroup, nickCache))).append("\n")
                r.append("接话王次数：").append(v).append(" 次\n")
            }
        } else {
            r.append("接话样本不足 该时段没有换人接话的文字消息\n")
        }
        if (textN > 0) {
            val answered = pct(ex.qAnswered, ex.qAsks)
            r.append("提问条数：").append(ex.qAsks).append(" 条\n")
            r.append("被回应条数：").append(ex.qAnswered).append(" 条\n")
            r.append("回应率：").append(answered).append("%\n")
            if (ex.waitCount > 0) {
                r.append("平均等待：").append(humanDuration(ex.waitSum / ex.waitCount)).append("\n")
            }
            r.append("自己追问：").append(ex.qSelfFollow).append(" 次\n")
            r.append("提问点评 ").append(
                when {
                    ex.qAsks == 0 -> "全程没有疑问句 一句都没问出口"
                    answered >= 80 -> "有问必答 问出去的基本都有人接"
                    answered <= 30 -> "问得多答得少 疑问句常常没人接"
                    else -> "问与答基本对得上"
                }
            ).append("\n")
        }
        if (ex.pairs.isNotEmpty()) {
            val topPairs = topKeys(ex.pairs, 6)
            val best = topPairs.firstOrNull()
            r.append("搭档组合：").append(ex.pairs.size).append(" 对\n")
            if (best != null) {
                val bestV = ex.pairs[best] ?: 0
                r.append("最高搭档次数：").append(bestV).append(" 次\n")
                val sp = splitPairKey(best)
                if (sp != null) {
                    r.append("最默契组合：")
                        .append(textSafe(speakerDisplayName(sp.first, talker, isGroup, nickCache)))
                        .append(" × ")
                        .append(textSafe(speakerDisplayName(sp.second, talker, isGroup, nickCache)))
                        .append("\n")
                }
                r.append("默契榜 互相接话最多的两人组合\n")
                val pMax = bestV.coerceAtLeast(1)
                for (k in topPairs) {
                    val v = ex.pairs[k] ?: 0
                    if (v <= 0) continue
                    val pair = splitPairKey(k) ?: continue
                    r.append(textSafe(speakerDisplayName(pair.first, talker, isGroup, nickCache)))
                        .append(" × ")
                        .append(textSafe(speakerDisplayName(pair.second, talker, isGroup, nickCache)))
                        .append(' ').append(v).append(' ').append(bar(v, pMax, 16)).append("\n")
                }
                val topV = ex.pairs[best] ?: 0
                r.append("默契点评 ").append(
                    when {
                        ex.turnsAttributed > 0 && topV * 3 >= ex.turnsAttributed ->
                            "固定搭子 大半的话都是那两个人你来我往"
                        ex.pairs.size >= 10 -> "多线并行 谁跟谁都能聊上几句"
                        else -> "接话比较分散 没有特别固定的搭子"
                    }
                ).append("\n")
            }
        } else {
            r.append("默契样本不足 该时段没有换人接话的文字消息\n")
        }

        // ── 19) 复读与重复 ──────────────────────────────────────────
        r.append("\n【复读与重复】\n")
        if (textN > 0) {
            r.append("复读次数：").append(ex.repeatMsgs).append(" 次\n")
            r.append("复读率：").append(pct(ex.repeatMsgs, textN)).append("%\n")
            r.append("最长复读链：").append(ex.repeatChainMax).append(" 连\n")
            if (ex.repeatSample.isNotEmpty()) {
                r.append("复读金句 ").append(excerpt(ex.repeatSample)).append("\n")
            }
            r.append("复读点评 ").append(
                when {
                    ex.repeatMsgs == 0 -> "零复读 各说各的，没有一句被原样复述"
                    ex.repeatChainMax >= 5 -> "复读机开动 同一句话被连着刷了五条以上"
                    pct(ex.repeatMsgs, textN) >= 8 -> "复读成风 隔十几条就有人接上同一句"
                    else -> "偶尔撞句 基本是巧合"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }

        // ── 20) 回复速度榜（新）────────────────────────────────────
        r.append("\n【回复速度榜】\n")
        if (ex.replyMsCntBySender.size >= 2 && ex.replyGapCount > 0) {
            val avgSec = mutableMapOf<String, Int>()
            for ((k, c) in ex.replyMsCntBySender) {
                if (c < REPLY_RANK_MIN_SAMPLES) continue
                val sum = ex.replyMsSumBySender[k] ?: 0L
                avgSec[k] = (sum / c / 1000L).toInt()
            }
            r.append("参与统计：").append(avgSec.size).append(" 人\n")
            r.append("样本门槛：").append(REPLY_RANK_MIN_SAMPLES).append(" 次\n")
            if (avgSec.size >= 2) {
                // 报告期的小表排序（键数 ≤ 40，人数规模而非消息规模），不进消息循环
                val ranked = avgSec.entries.sortedBy { it.value }.take(6)
                val slow = ranked.last().value.coerceAtLeast(1)
                var slowestName = ""
                var slowestSec = -1
                for ((k, v) in avgSec) {
                    if (v > slowestSec) {
                        slowestSec = v
                        slowestName = k
                    }
                }
                r.append("最快响应：").append(ranked[0].value).append(" 秒（")
                    .append(textSafe(speakerDisplayName(ranked[0].key, talker, isGroup, nickCache)))
                    .append("）\n")
                if (slowestSec >= 0) {
                    r.append("最慢响应：").append(slowestSec).append(" 秒（")
                        .append(textSafe(speakerDisplayName(slowestName, talker, isGroup, nickCache)))
                        .append("）\n")
                }
                r.append("平均响应秒数 越短回得越快\n")
                for ((k, v) in ranked) {
                    r.append(textSafe(speakerDisplayName(k, talker, isGroup, nickCache)))
                        .append(' ').append(v).append(' ').append(bar(v, slow, 16)).append("\n")
                }
                r.append("响应点评 ").append(
                    when {
                        ranked[0].value <= 10 -> "有人几乎是秒接 消息刚发出去就回来了"
                        ranked[0].value <= 60 -> "最快的那个基本一分钟内回"
                        slowestSec >= 600 -> "最慢的要拖十分钟以上，快慢差距很大"
                        else -> "大家的响应速度差不多"
                    }
                ).append("\n")
            } else {
                r.append("样本不足 单人发言次数不够比排名\n")
            }
        } else {
            r.append("样本不足 该时段没有可比的响应间隔\n")
        }

        // ── 21) 个人作息雷达（新）──────────────────────────────────
        r.append("\n【个人作息雷达】\n")
        if (ex.hourBySender.isNotEmpty() && textN > 0) {
            val people = topKeys(ex.rankTexts, 12).filter { ex.hourBySender.containsKey(it) }.take(6)
            r.append("统计人数：").append(people.size).append(" 人\n")
            var nightPerson = 0
            val nightPct = mutableMapOf<String, Int>()
            val peaks = StringBuilder()
            for (k in people) {
                val hours = ex.hourBySender[k] ?: continue
                var total = 0
                var nightMsgs = 0
                var peakH = 0
                var peakV = 0
                for (h in 0 until 24) {
                    val v = hours[h]
                    total += v
                    if (isNightHour(h)) nightMsgs += v
                    if (v > peakV) {
                        peakV = v
                        peakH = h
                    }
                }
                if (total <= 0) continue
                val np = pct(nightMsgs, total)
                nightPct[k] = np
                if (np >= 30) nightPerson++
                if (peaks.isNotEmpty()) peaks.append("｜")
                peaks.append(textSafe(speakerDisplayName(k, talker, isGroup, nickCache)))
                    .append(" ").append(peakH).append(" 点")
            }
            r.append("夜猫子人数：").append(nightPerson).append(" 人\n")
            if (nightPct.isNotEmpty()) {
                var pMax = 0
                for (v in nightPct.values) if (v > pMax) pMax = v
                if (pMax <= 0) pMax = 1
                r.append("深夜占比 23 点到次日 5 点的消息占比\n")
                val ordered = nightPct.entries.sortedByDescending { it.value }.take(6)
                for ((k, v) in ordered) {
                    r.append(textSafe(speakerDisplayName(k, talker, isGroup, nickCache)))
                        .append(' ').append(v).append(' ').append(bar(v, pMax, 16)).append("\n")
                }
            }
            if (peaks.isNotEmpty()) {
                r.append("个人峰值时段 ").append(peaks).append("\n")
            }
            r.append("作息点评 ").append(
                when {
                    nightPerson == 0 -> "全员白天型 深夜没什么人冒头"
                    nightPerson * 2 >= people.size -> "半个群都是夜猫子 深夜反而是主场"
                    else -> "只有个别夜猫子 多数人作息正常"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }
    }

    // ---------------- 进阶包三（语言与习惯）：4 个维度 ----------------

    /**
     * 语言包：打字习惯 / 约定与提醒 / 时段话量画像 / 用词广度。
     *
     * 四个维度都在**同一次扫描**里就地累计（固定词表 contains + 几个整数计数器 +
     * 24 格定长数组），没有新增查询、没有第二遍遍历、没有随消息条数增长的内存。
     * 第 21 轮把原来的【情绪词雷达】并入核心【情绪与语气】（同源指标不该占两个维度），
     * 补进来的【用词广度】复用主循环已有的全量词频表（[wordMap]），同样零新增扫描。
     */
    private fun appendLanguagePackSections(
        r: StringBuilder,
        ex: ExtraStats,
        textN: Int,
        wordMap: Map<String, Int>,
    ) {
        // ── 22) 打字习惯 ──────────────────────────────────────────
        r.append("\n【打字习惯】\n")
        if (textN > 0) {
            r.append("无标点消息：").append(pct(ex.typNoPunct, textN)).append("%\n")
            r.append("全角标点率：").append(pct(ex.typFullPunct, textN)).append("%\n")
            r.append("带空格率：").append(pct(ex.typSpace, textN)).append("%\n")
            r.append("纯英数消息：").append(pct(ex.typPlainAlnum, textN)).append("%\n")
            r.append("单字消息率：").append(pct(ex.typSingleChar, textN)).append("%\n")
            r.append("习惯点评 ").append(
                when {
                    pct(ex.typNoPunct, textN) >= 70 -> "基本不打标点 靠行尾和语气断句"
                    pct(ex.typPlainAlnum, textN) >= 30 -> "中英混排很常见 顺手就是几个英文词"
                    pct(ex.typSingleChar, textN) >= 25 -> "单字回应成风 嗯/哦/好 一两个字就够"
                    pct(ex.typFullPunct, textN) >= 80 -> "标点很规矩 该有的逗号句号一个不少"
                    else -> "打字习惯正常 该有标点的时候都有"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }

        // ── 23) 约定与提醒 ────────────────────────────────────────
        r.append("\n【约定与提醒】\n")
        if (textN > 0) {
            r.append("约定词命中率：").append(pct(ex.apptMsgs, textN)).append("%\n")
            r.append("命中消息数：").append(ex.apptMsgs).append(" 条\n")
            val ak = topKeys(ex.appt, 10)
            if (ak.isNotEmpty()) {
                r.append("最常提的时间词 ").append(ak[0]).append("：")
                    .append(ex.appt[ak[0]] ?: 0).append(" 次\n")
                r.append("高频时间词 命中该词的消息条数\n")
                val chips = StringBuilder()
                for ((i, k) in ak.withIndex()) {
                    if (i > 0) chips.append("  ")
                    chips.append(k).append("×").append(ex.appt[k] ?: 0)
                }
                r.append(chips).append("\n")
            } else {
                r.append("时间词命中：0 个\n")
            }
            r.append("约定点评 ").append(
                when {
                    ex.apptMsgs == 0 -> "完全没有约定类内容 基本都是即时闲聊"
                    pct(ex.apptMsgs, textN) >= 30 -> "句句都在约 这段关系靠见面撑着"
                    pct(ex.apptMsgs, textN) >= 10 -> "时不时约一下 聊的都是具体安排"
                    else -> "偶尔提一句时间 大多还是随口聊"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }

        // ── 24) 时段话量画像 ──────────────────────────────────────
        r.append("\n【时段话量画像】\n")
        if (textN > 0) {
            val avg = IntArray(24)
            var peakH = 0
            var peakAvg = 0
            for (h in 0 until 24) {
                avg[h] = if (ex.hourTextN[h] > 0) ex.hourChars[h] / ex.hourTextN[h] else 0
                if (avg[h] > peakAvg) {
                    peakAvg = avg[h]
                    peakH = h
                }
            }
            var longSum = 0
            for (h in 0 until 24) longSum += ex.hourLong[h]
            r.append("平均字数最高：").append(peakH).append(" 点\n")
            r.append("该时段均值：").append(peakAvg).append(" 字\n")
            r.append("整体平均：").append((ex.lenSum.toDouble() / textN).roundToInt()).append(" 字\n")
            r.append("长句占比：").append(pct(longSum, textN)).append("%\n")
            r.append("各小时平均字数 只统计纯文字消息\n")
            var lineMax = 0
            for (h in 0 until 24) if (avg[h] > lineMax) lineMax = avg[h]
            if (lineMax <= 0) lineMax = 1
            for (h in 0 until 24) {
                r.append(h).append("时 ").append(avg[h]).append(' ')
                    .append(bar(avg[h], lineMax, 16)).append("\n")
            }
            r.append("话量点评 ").append(
                when {
                    peakAvg >= 60 -> "最长的消息集中在少数几个时段 那多半是认真说话的时间"
                    peakH in 0..5 -> "深夜字数明显更长 夜里更容易掏心窝"
                    peakH in 20..23 -> "晚上话最多也最长 是这段关系的主场"
                    else -> "各时段字数差不多 说话方式很稳定"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有文字消息\n")
        }

        // ── 25) 用词广度 ──────────────────────────────────────────
        // 与核心【高频词与口头禅】用的是同一份全量词频表，但回答的是另一个问题：
        // 那边给"最常说的是哪几个词"（TopN 词表），这里给"用词到底散不散"
        // （独立词种 / 总词次 / 集中度）。同一份统计的两种读法，信息不重复。
        r.append("\n【用词广度】\n")
        if (textN > 0 && wordMap.isNotEmpty()) {
            var wordTotal = 0
            for (v in wordMap.values) wordTotal += v
            val kinds = wordMap.size
            val top3 = topKeys(wordMap, 3)
            var top3Sum = 0
            for (k in top3) top3Sum += wordMap[k] ?: 0
            r.append("独立词种：").append(kinds).append(" 种\n")
            r.append("总词次：").append(wordTotal).append(" 次\n")
            r.append("词种占比：").append(pct(kinds, wordTotal)).append("%\n")
            r.append("Top3 词占比：").append(pct(top3Sum, wordTotal)).append("%\n")
            if (top3.isNotEmpty()) {
                r.append("最常用三词：").append(top3.joinToString(" / ")).append("\n")
            }
            r.append("用词点评 ").append(
                when {
                    pct(kinds, wordTotal) >= 40 -> "用词很散 很少重复同一批词"
                    pct(top3Sum, wordTotal) >= 40 -> "翻来覆去就那几个词 句句都是老配方"
                    else -> "用词集中度正常 该重复的重复 该换的换"
                }
            ).append("\n")
        } else {
            r.append("统计口径 该时段没有可切分的词\n")
        }
    }
    /** 热力格下标（周几 × 24 + 小时）→ 「周三 21 点」 */
    private fun heatLabel(idx: Int): String {

        if (idx < 0 || idx >= HEAT_CELLS) return ""
        return DAY_NAMES[idx / 24] + " " + (idx % 24) + " 点"
    }

    // ---------------- 第 14 轮新增：扫描期的增量统计 ----------------

    /**
     * 收尾一个话题段：段时长 = 段内最后一条消息 - 段内第一条消息。
     *
     * 只在「沉默 ≥30 分钟」和扫描结束时各调一次，纯整数比较，无分配。
     */
    private fun closeTopic(ex: ExtraStats, endCt: Long) {
        if (ex.topicStart <= 0L || endCt <= ex.topicStart) return
        val dur = endCt - ex.topicStart
        if (dur > ex.maxTopicMs) {
            ex.maxTopicMs = dur
            ex.maxTopicStart = ex.topicStart
            ex.maxTopicEnd = endCt
        }
    }

    /**
     * 第 18 轮：结算一轮对话（把已累计的条数并进轮长分布），然后把当前轮置零。
     *
     * 调用时机与 [closeTopic] 完全一致：**只在 ≥[TOPIC_BREAK_MS] 的中断处**、以及整段扫描结束时各调一次。
     * 条数 ≤0 表示这一轮还没开始（例如时段内第一条消息之前），直接返回，
     * 不会往分布里塞一个"0 条的轮次"。
     */
    private fun closeRound(ex: ExtraStats) {
        val len = ex.curRound
        ex.curRound = 0
        if (len <= 0) return
        ex.roundCount++
        ex.roundLenSum += len
        if (len > ex.roundMax) ex.roundMax = len
        ex.roundBands[roundBand(len)]++
    }

    /**
     * 第 18 轮：把无向对键拆回两个人（键格式见 [ROUND18_PAIR_SEP]）。
     *
     * 报告只在最后对 ≤[ROUND18_PAIR_MAX] 个键走一遍，**不进入消息循环**；
     * 拿不到分隔符（理论上不会发生）时返回 null，由调用方跳过。
     */
    private fun splitPairKey(key: String): Pair<String, String>? {
        val at = key.indexOf(ROUND18_PAIR_SEP)
        if (at <= 0 || at >= key.length - 1) return null
        return key.substring(0, at) to key.substring(at + 1)
    }

    /**
     * 标点 / 字母 / 表情的**一次**字符扫描。
     *
     * 为什么要单独走一遍字符：这些量要的是「每百字几个」的密度口径，
     * 上面那批 `contains` 只能回答「有没有」，给不出密度；而字符循环是纯算术、
     * 零对象分配，同一条正文多扫一遍的代价远小于再查一次数据库。
     */
    private fun scanPunctuation(body: String, ex: ExtraStats) {
        if (body.isEmpty()) return
        ex.charTotal += body.length
        var emoji = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '?' || c == '？' -> ex.qMark++
                c == '!' || c == '！' -> ex.eMark++
                c == '…' -> ex.ellipsis++
                c == '~' || c == '～' -> ex.tilde++
                c in 'a'..'z' || c in 'A'..'Z' -> ex.letterChars++
            }
            // 表情符号在 UTF-16 里是代理对，必须按码点判断（只看一个 char 永远判不出来）
            if (Character.isHighSurrogate(c) && i + 1 < body.length && Character.isLowSurrogate(body[i + 1])) {
                val cp = Character.toCodePoint(c, body[i + 1])
                if (cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF) emoji = true
                i++
            }
            i++
        }
        if (emoji) ex.emojiMsgs++
        if (KAOMOJI.any { body.contains(it) }) ex.kaoMsgs++
    }

    /** 口头禅：固定词表逐个 `contains`，一条消息对同一个词只记一次（消息级口径） */
    private fun scanCliches(body: String, ex: ExtraStats) {
        var hit = false
        for (w in CLICHES) {
            if (body.contains(w)) {
                ex.cliche[w] = (ex.cliche[w] ?: 0) + 1
                hit = true
            }
        }
        if (hit) ex.clicheMsgs++
    }

    /** 话题词：固定词表逐个 `contains`（消息级口径，与【口头禅】同一套判定） */
    /**
     * 话题词扫描（消息级）。
     *
     * 第 16 轮加了 [night] 参数：同一遍 `contains` 循环里顺带把命中分流到"深夜 / 白天"
     * 两张小表（词表固定 28 个 → 两张表最多 56 个键，不随消息条数增长），
     * 于是「话题时段偏好」不需要第二次遍历，也不需要留下任何正文。
     */
    private fun scanTopics(body: String, ex: ExtraStats, night: Boolean) {
        var hit = false
        for (w in TOPIC_WORDS) {
            if (body.contains(w)) {
                ex.topic[w] = (ex.topic[w] ?: 0) + 1
                if (night) ex.topicNight[w] = (ex.topicNight[w] ?: 0) + 1
                else ex.topicDay[w] = (ex.topicDay[w] ?: 0) + 1
                hit = true
            }
        }
        if (hit) {
            ex.topicMsgs++
            if (night) ex.topicNightMsgs++ else ex.topicDayMsgs++
        }
    }


    // ---------------- 第 20 轮新增：三个新维度的扫描器 ----------------

    /**
     * 情绪词雷达（消息级）：固定 20 词的两次 `contains` 循环。
     *
     * 与口头禅同一套口径 —— 命中即记一次该词、一条消息最多给每个词记一次，
     * 两张表都是固定词表 → 大小恒定，不随消息量增长。
     * 只在 `body.length <= TOPIC_BODY_MAX` 时调用（长正文不整段扫）。
     */
    private fun scanMood(body: String, ex: ExtraStats) {
        var pos = false
        var neg = false
        for (w in MOOD_POS) {
            if (body.contains(w)) {
                ex.moodPos[w] = (ex.moodPos[w] ?: 0) + 1
                pos = true
            }
        }
        for (w in MOOD_NEG) {
            if (body.contains(w)) {
                ex.moodNeg[w] = (ex.moodNeg[w] ?: 0) + 1
                neg = true
            }
        }
        if (pos) ex.moodPosMsgs++
        if (neg) ex.moodNegMsgs++
    }

    /**
     * 约定与提醒（消息级）：固定 20 词的时间 / 约定词命中。
     *
     * 回答的是"这段聊天里有多少话是在安排事情"，而不是"聊了什么"，
     * 所以只看时间与行动词，不看话题词（话题词另有词表）。
     */
    private fun scanAppointment(body: String, ex: ExtraStats) {
        var hit = false
        for (w in APPT_WORDS) {
            if (body.contains(w)) {
                ex.appt[w] = (ex.appt[w] ?: 0) + 1
                hit = true
            }
        }
        if (hit) ex.apptMsgs++
    }

    /**
     * 打字习惯（消息级）：一遍字符循环回答五个问题。
     *
     * 无标点（整条消息一个中英文标点都没有）/ 有全角标点 / 带空格（含全角空格）/
     * 纯英数（只由英文字母、数字、空格、标点组成）/ 单字消息。
     * 纯算术与 `indexOf` 比较，零对象分配；与本文件其它字符级统计一样，
     * 多扫一遍正文的代价远小于再查一次数据库。
     */
    private fun scanTyping(body: String, ex: ExtraStats) {
        if (body.isEmpty()) return
        if (body.length == 1) ex.typSingleChar++
        var punct = false
        var full = false
        var space = false
        var alnumOnly = true
        for (c in body) {
            if (c == ' ' || c == '\t' || c == '\u3000') {
                space = true
                continue
            }
            if (FULL_PUNCT.indexOf(c) >= 0) {
                punct = true
                full = true
                continue
            }
            if (HALF_PUNCT.indexOf(c) >= 0) {
                punct = true
                continue
            }
            val ascii = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'
            if (!ascii) alnumOnly = false
        }
        if (!punct) ex.typNoPunct++
        if (full) ex.typFullPunct++
        if (space) ex.typSpace++
        if (alnumOnly) ex.typPlainAlnum++
    }
    /**
     * 最长摘录：[EXCERPT_N] 条定长插入。
     *
     * 不排序、不收集全部消息（内存恒定），只保留已有字符串的引用；
     * 长度为 0 的正文直接跳过，避免"空摘录"占位。
     */
    private fun rememberExcerpt(ex: ExtraStats, senderKey: String, body: String) {
        if (body.isEmpty()) return
        var at = -1
        for (i in ex.topBodies.indices) {
            if (body.length > ex.topBodies[i].second.length) {
                at = i
                break
            }
        }
        if (at < 0 && ex.topBodies.size < EXCERPT_N) at = ex.topBodies.size
        if (at < 0) return
        ex.topBodies.add(at, senderKey to body)
        while (ex.topBodies.size > EXCERPT_N) ex.topBodies.removeAt(ex.topBodies.size - 1)
    }

    /**
     * 摘录正文的安全化：去掉换行、全/半角冒号与条形块。
     *
     * 为什么必须做：摘录是**任意用户文本**，里面一旦出现 `：`，弹窗和 PNG 的通用解析器
     * 就会把这一行当成「指标：值」，排版会错位；换成逗号后它永远是普通正文行。
     */
    private fun excerpt(body: String): String {
        val cleaned = textSafe(body).trim()
        val cut = if (cleaned.length > EXCERPT_MAX) cleaned.substring(0, EXCERPT_MAX) + "…" else cleaned
        return "「" + cut + "」"
    }

    /** 展示名 / 正文里会被段解析器误判的字符一律替换掉（不删除信息，只换字形） */
    private fun textSafe(s: String): String = s
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('：', '，')
        .replace(':', ',')
        .replace('█', ' ')

    /** 每百字出现次数（密度口径）；分母为 0 时返回 0，绝不产生 NaN */
    private fun density(count: Int, total: Int): Double =
        if (total <= 0) 0.0 else count.toDouble() * 100.0 / total.toDouble()

    /** 保留一位小数。用整数运算而不是 String.format，省掉一处 Locale import */
    private fun oneDecimal(v: Double): String {
        if (v <= 0.0) return "0.0"
        val t = (v * 10.0).roundToInt()
        return (t / 10).toString() + "." + (t % 10)
    }

    /** 比值文本：以较小的一方为 1；任一方为 0 时直接给整数比 */
    private fun ratioText(a: Int, b: Int): String {
        if (a <= 0 && b <= 0) return "0 : 0"
        if (a <= 0 || b <= 0) return if (a > b) "$a : 0" else "0 : $b"
        return if (a >= b) oneDecimal(a.toDouble() / b.toDouble()) + " : 1"
        else "1 : " + oneDecimal(b.toDouble() / a.toDouble())
    }

    /** 一句「语气倾向」结论（只判档位不含数字，避免被 KPI 卡片当数值渲染） */
    private fun toneTrend(qD: Double, eD: Double, lD: Double, wD: Double, ex: ExtraStats, textN: Int): String = when {
        qD >= 1.0 && qD >= eD -> "探询型（总想再确认一句）"
        eD >= 0.8 -> "外放型（感叹号比句号还多）"
        lD >= 0.3 -> "留白型（省略号里都是没说出口的）"
        wD >= 0.3 -> "拖音型（波浪号把语气拉长）"
        textN > 0 && ex.emojiMsgs * 2 >= textN -> "活泼型（表情符号撑起半句话）"
        else -> "平铺直叙型（标点很克制）"
    }

    /**
     * 分享密度结论（第 22 轮新增）。
     *
     * 档位阈值刻意与第 2 段【内容载体与表情】里的「媒体占比」口径保持同一量纲，
     * 免得同一份数据在两个段落里被描述成两种样子。
     */
    private fun shareDensityText(pctText: String): String {
        val v = pctText.toDoubleOrNull() ?: 0.0
        return when {
            v >= 40.0 -> "重度分享（近半消息都是转来的）"
            v >= 20.0 -> "常分享（链接与图片是主要谈资）"
            v >= 8.0 -> "适度分享（以聊天为主、分享为辅）"
            v > 0.0 -> "极少分享（几乎只用文字交流）"
            else -> "只聊天不分享（没有链接、图片或位置）"
        }
    }

    /**
     * 发言基尼系数的文字结论（第 22 轮新增）。
     *
     * 阈值取自「聊天群话量集中度」的常见经验区间：0.25 以下基本人人都在说，
     * 0.55 以上就属于"少数人撑起整个群"。
     */
    private fun giniText(gini: Double): String = when {
        gini >= 0.55 -> "高度集中（少数人撑起整个群）"
        gini >= 0.40 -> "偏集中（头部几个人贡献大半）"
        gini >= 0.25 -> "较均衡（有人多说、有人少说）"
        else -> "很均衡（几乎人人都在说）"
    }

    /** 私聊平衡度结论 */
    private fun balanceText(mine: Int, others: Int): String {
        if (others <= 0) return "一边倒（对方一句没说）"
        if (mine <= 0) return "全程潜水（我一句没说）"
        val hi = maxOf(mine, others)
        val lo = minOf(mine, others)
        val ratio = hi.toDouble() / lo.toDouble()
        return when {
            ratio < 1.2 -> "势均力敌，你来我往"
            ratio < 1.8 && mine > others -> "我稍主动，对方接得住"
            ratio < 1.8 -> "对方稍主动，我接得住"
            mine > others -> "我在输出，对方以听为主"
            else -> "对方在输出，我以听为主"
        }
    }

    /** 群聊平衡度结论：看头名的条数占比 */
    private fun groupBalanceText(top1Pct: Int): String = when {
        top1Pct >= 50 -> "一个人带全场，其余人负责围观"
        top1Pct >= 30 -> "少数人撑起大部分发言"
        top1Pct > 0 -> "发言比较分散，没有绝对主角"
        else -> "暂无可比数据"
    }

    /** 毫秒 → "MM-dd HH:mm"（本地时区）：沉默区间 / 话题段起止点用 */
    private fun clockText(ms: Long): String {
        if (ms <= 0L) return ""
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        val mo = ((c.get(Calendar.MONTH) + 1).toString()).padStart(2, '0')
        val day = (c.get(Calendar.DAY_OF_MONTH).toString()).padStart(2, '0')
        val hh = (c.get(Calendar.HOUR_OF_DAY).toString()).padStart(2, '0')
        val mi = (c.get(Calendar.MINUTE).toString()).padStart(2, '0')
        return "$mo-$day $hh:$mi"
    }

    // ---------------- 工具函数 ----------------

    fun typeName(t: Int): String = when (t) {
        1 -> "文字"
        3 -> "图片"
        34 -> "语音"
        43 -> "视频"
        47 -> "表情"
        48 -> "位置"
        49 -> "卡片/链接"
        10000 -> "系统"
        10002 -> "撤回"
        419430449 -> "转账"
        436207665 -> "红包"
        else -> "其他"
    }

    /** Top-K 选择（与脚本一致：逐轮找最大） */
    fun topKeys(m: Map<String, Int>, k: Int): List<String> {
        if (m.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val used = mutableSetOf<String>()
        val n = minOf(m.size, k)
        repeat(n) {
            var bestKey: String? = null
            var bestVal = -1
            for ((key, v) in m) {
                if (key in used) continue
                if (v > bestVal) {
                    bestVal = v
                    bestKey = key
                }
            }
            if (bestKey == null) return@repeat
            used.add(bestKey)
            out.add(bestKey)
        }
        return out
    }

    fun bar(v: Int, max: Int, width: Int): String {
        if (max <= 0 || v <= 0) return ""
        val n = (v.toDouble() / max.toDouble() * width).roundToInt().coerceAtLeast(1)
        return "█".repeat(n.coerceAtMost(width))
    }

    fun pct(part: Int, total: Int): Int = if (total <= 0) 0 else (part.toDouble() / total.toDouble() * 100.0).roundToInt()

    /** 毫秒 → 人话时长（用于「平均间隔 / 最长冷场」这类节奏指标）。 */
    fun humanDuration(millis: Long): String = when {
        millis <= 0L -> "0 秒"
        millis < 60_000L -> "${millis / 1000} 秒"
        millis < 3_600_000L -> "${millis / 60_000} 分 ${millis % 60_000 / 1000} 秒"
        millis < 86_400_000L -> "${millis / 3_600_000} 小时 ${millis % 3_600_000 / 60_000} 分"
        else -> "${millis / 86_400_000} 天 ${millis % 86_400_000 / 3_600_000} 小时"
    }

    /**
     * 第 17 轮：复读判定（消息级，只看上一条）。
     *
     * 「复读」= 本条正文与上一条**逐字相同**，且发送者不是同一个人
     * （同一个人自己说两遍是重复劳动，不算复读）。链长 = 连续命中的次数。
     *
     * 状态只有两个字符串引用和三个计数器：不存历史、不建索引，与消息总量无关。
     */
    private fun scanRepeat(ex: ExtraStats, senderKey: String, body: String) {
        if (body.isNotEmpty() && body == ex.prevBody && senderKey != ex.prevBodyFrom) {
            ex.repeatMsgs++
            ex.repeatChain++
            if (ex.repeatChain > ex.repeatChainMax) ex.repeatChainMax = ex.repeatChain
            if (ex.repeatSample.isEmpty()) {
                ex.repeatSample =
                    if (body.length > REPEAT_SAMPLE_MAX) body.substring(0, REPEAT_SAMPLE_MAX) else body
            }
        } else {
            ex.repeatChain = 0
        }
        ex.prevBody = body
        ex.prevBodyFrom = senderKey
    }

    /**
     * 第 17 轮：提问与回应（消息级，只看下一条）。
     *
     * 上一条是疑问句时，本条就是它的"下一条"：换人发言且在 [ASK_WINDOW_MS] 内 →
     * 记一次"被回应"并累计等待时长；同一人在窗口内接着说 → 记一次"自己追问"；
     * 超过窗口 → 什么都不记（无人回应）。判定复用扫描循环里已有的 [gap]，不新增遍历。
     */
    private fun scanQuestion(ex: ExtraStats, senderKey: String, body: String, gap: Long) {
        if (ex.pendingAskFrom.isNotEmpty()) {
            if (gap > 0L && gap <= ASK_WINDOW_MS) {
                if (ex.pendingAskFrom == senderKey) {
                    ex.qSelfFollow++
                } else {
                    ex.qAnswered++
                    ex.waitSum += gap
                    ex.waitCount++
                }
            }
            ex.pendingAskFrom = ""
        }
        if (body.indexOf('?') >= 0 || body.indexOf('？') >= 0) {
            ex.qAsks++
            ex.pendingAskFrom = senderKey
        }
    }

    /**
     * 第 17 轮：两个「年 × 1000 + 年内第几天」的键是不是相邻的两天。
     *
     * 年内第几天在同年内连续（差值 1 即次日），跨年要单独判一次：
     * 上一键是年末（≥ 365 天，闰年 366 同样 ≥ 365）且新键是次年 1 月 1 日才算连续。
     */
    private fun isNextDay(prevKey: Int, curKey: Int): Boolean {
        if (prevKey / 1000 == curKey / 1000) return curKey - prevKey == 1
        if (curKey / 1000 == prevKey / 1000 + 1 && curKey % 1000 == 1) return prevKey % 1000 >= 365
        return false
    }

    /**
     * 收尾「当前这一天」：把它的活跃跨度（当天末条 - 当天首条）并进日画像。
     *
     * 只在日期切换与扫描结束时调用（每天一次），纯整数减法。没有开着的天
     * （curDayFirst / curDayLast 仍为 0）时直接返回，所以首次调用是安全的。
     */
    private fun closeDay(ex: ExtraStats) {
        if (ex.curDayFirst <= 0L || ex.curDayLast <= 0L) return
        val span = ex.curDayLast - ex.curDayFirst
        if (span < 0L) return
        ex.daySpanSum += span
        ex.daySpanCount++
        if (span > ex.daySpanMax) ex.daySpanMax = span
    }

    /**
     * 毫秒 → "3月2日"：趋势柱的日期刻度。
     *
     * 为什么不写成 "03-02"：零值的桶没有 █ 块，弹窗与 PNG 的 PLAIN_COUNT 分支要求
     * **标签里含中文**才认得出这是分布行；含数字则是为了让整段被当成柱状图（而不是环形图）。
     */
    private fun dayTickLabel(ms: Long): String {
        if (ms <= 0L) return "第 1 段"
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return (c.get(Calendar.MONTH) + 1).toString() + "月" + c.get(Calendar.DAY_OF_MONTH) + "日"
    }

    /** 毫秒 → "14时"：今天 / 昨天（≤2 天跨度）用的小时刻度 */
    private fun hourTickLabel(ms: Long): String {
        if (ms <= 0L) return "第 1 段"
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return c.get(Calendar.HOUR_OF_DAY).toString() + "时"
    }

    /** 当天分钟数（0-1439）→ "7:12"；-1（尚无样本）返回空串，调用处用长度判空 */
    private fun clockShort(minuteOfDay: Int): String {
        if (minuteOfDay < 0) return ""
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        return h.toString() + ":" + (if (m < 10) "0$m" else m.toString())
    }

    /** 词频：中文按 2-4 字窗口切分，跳过纯数字（脚本语义） */
    private fun countWords(text: String, out: MutableMap<String, Int>) {
        if (text.isEmpty()) return
        val buf = StringBuilder()
        for (i in 0..text.length) {
            var keep = false
            if (i < text.length) {
                val ch = text[i]
                keep = ch in '\u4e00'..'\u9fff' || ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
            }
            if (keep) {
                buf.append(text[i])
            } else {
                emitWords(buf.toString(), out)
                buf.setLength(0)
            }
        }
    }

    private fun emitWords(run: String, out: MutableMap<String, Int>) {
        val n = run.length
        if (n < 2) return
        var digitOnly = true
        for (ch in run) {
            if (ch !in '0'..'9') {
                digitOnly = false
                break
            }
        }
        if (digitOnly) return
        val maxWin = minOf(n, 4)
        for (win in 2..maxWin) {
            for (s in 0..n - win) {
                val w = run.substring(s, s + win)
                out[w] = (out[w] ?: 0) + 1
            }
        }
    }

    /** 群消息 content 前缀提取发送者 wxid：`wxid:\n内容` 或 `wxid:内容` */
    private fun groupSenderFromContent(content: String): String {
        var p = content.indexOf(":\n")
        if (p >= 1 && p <= 41) {
            val s = content.substring(0, p).trim()
            if (validSender(s)) return s
        }
        p = content.indexOf(":")
        if (p >= 1 && p <= 41) {
            val s = content.substring(0, p).trim()
            if (validSender(s)) return s
        }
        return ""
    }

    private fun validSender(s: String): Boolean {
        if (s.length < 3 || s.length > 40) return false
        return s.matches(Regex("^[a-zA-Z0-9_\\-@]+$"))
    }

    private fun stripGroupSenderPrefix(body: String, wx: String): String {
        var cut = body.indexOf(":\n")
        if (cut >= 1 && cut <= 41 && body.substring(0, cut).trim() == wx) return body.substring(cut + 2)
        cut = body.indexOf(":")
        if (cut >= 1 && cut <= 41 && body.substring(0, cut).trim() == wx) return body.substring(cut + 1)
        return body
    }

    /** 说话人显示名：我 / 对方 / 群成员昵称（群昵称 → 微信名/备注 → wxid） */
    private fun speakerDisplayName(key: String, talker: String, isGroup: Boolean, nickCache: MutableMap<String, String>): String {
        if (key == "我") return "我"
        if (key == "对方") return talkerDisplayName(talker)
        if (key == "群友") return "群友"
        if (!isGroup) return key
        return nickCache.getOrPut(key) {
            // 1. 群备注/群昵称（roomdata protobuf）
            val groupNick = runCatching { WeDatabaseApi.getGroupMemberDisplayNameMap(talker)[key] }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (groupNick != null) return@getOrPut groupNick
            // 2. 微信名/备注（rcontact 表）
            val display = runCatching { WeDatabaseApi.getDisplayName(key) }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != key }
            display ?: key
        }
    }

    fun talkerDisplayName(talker: String): String =
        runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrNull()?.takeIf { it.isNotBlank() } ?: talker
}
