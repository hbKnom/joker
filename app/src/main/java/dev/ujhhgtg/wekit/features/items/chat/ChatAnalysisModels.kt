package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.preferences.WePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 聊天记录分析 —— 数据模型与配置存储
 *
 * 迁移自 WeKit Java 脚本「聊天记录分析 v0.3.4」：
 * 支持多套 OpenAI 兼容模型配置（不同 baseUrl + apiKey + model），按需选择。
 */
data class AiModelConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val path: String = "/chat/completions",
) {
    fun endpoint(): String {
        val b = baseUrl.trim().trimEnd('/')
        val p = path.trim().ifEmpty { "/chat/completions" }
        return if (p.startsWith("/")) b + p else b + "/" + p
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("path", path)

    companion object {
        fun fromJson(o: JSONObject): AiModelConfig = AiModelConfig(
            name = o.optString("name", "未命名模型"),
            baseUrl = o.optString("baseUrl", ""),
            apiKey = o.optString("apiKey", ""),
            model = o.optString("model", ""),
            path = o.optString("path", "/chat/completions"),
        )
    }
}

/** 分析结果：本地统计报告文本 + AI 报告 + 转录文本 */
data class AnalyzeResult(
    val statsReport: String,
    val aiReport: String = "",
    val transcript: String = "",
    val totalAll: Int = 0,
    val textN: Int = 0,
)

/** 测试连接结果 */
data class AiTestResult(
    val success: Boolean,
    val message: String,
    val models: List<String> = emptyList(),
    val testedModel: String = "",
    val streamOk: Boolean = false,
    val plainOk: Boolean = false,
)

/**
 * 模型配置仓库：JSON 数组持久化到 WePrefs，支持多套模型按需切换。
 */
object ChatAnalysisModelStore {

    private const val KEY_MODELS = "chat_analysis_ai_models"
    private const val KEY_SELECTED = "chat_analysis_ai_selected"

    /** 首次使用时的默认模型（用户可自行增删改） */
    private fun defaultModels(): List<AiModelConfig> = listOf(
        AiModelConfig(
            name = "DeepSeek 官方",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "",
            model = "deepseek-chat",
            path = "/chat/completions",
        ),
        AiModelConfig(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-4o-mini",
            path = "/chat/completions",
        ),
    )

    @Synchronized
    fun loadModels(): List<AiModelConfig> {
        val raw = WePrefs.getString(KEY_MODELS)
        if (raw.isNullOrBlank()) {
            val def = defaultModels()
            saveModels(def)
            return def
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                runCatching { AiModelConfig.fromJson(o) }.getOrNull()
            }
        }.getOrDefault(defaultModels())
    }

    @Synchronized
    fun saveModels(models: List<AiModelConfig>) {
        val arr = JSONArray()
        models.forEach { arr.put(it.toJson()) }
        WePrefs.putString(KEY_MODELS, arr.toString())
        // 选中项失效时回退到第一个
        val sel = selectedName()
        if (sel.isNotBlank() && models.none { it.name == sel }) {
            if (models.isNotEmpty()) WePrefs.putString(KEY_SELECTED, models[0].name)
        }
    }

    fun selectedName(): String = WePrefs.getStringOrDef(KEY_SELECTED, "")

    fun selectedModel(): AiModelConfig? {
        val models = loadModels()
        val sel = selectedName()
        return models.firstOrNull { it.name == sel } ?: models.firstOrNull()
    }

    fun select(name: String) {
        WePrefs.putString(KEY_SELECTED, name)
    }

    fun addOrUpdate(model: AiModelConfig) {
        val models = loadModels().toMutableList()
        val idx = models.indexOfFirst { it.name == model.name }
        if (idx >= 0) models[idx] = model else models.add(model)
        saveModels(models)
        // 新增/更新后默认选中它
        select(model.name)
    }

    fun remove(name: String) {
        val models = loadModels().filter { it.name != name }
        saveModels(models)
    }
}

/**
 * 第 20 轮「维度整合」开关组：把 41 个报告段位整合成 25 个维度；第 21 轮又做了一轮
 * 「凝练」，合并掉两处同源维度、补进一个真正独立的维度，总数 24。
 *
 * 整合结果（详见 ChatAnalysisEngine 的报告段注释）：
 *  - 核心 13 个维度**始终输出**（核心指标 / 内容载体与表情 / 分享物与链接密度 /
 *    活跃时段与热力 / 作息与昼夜 / 节奏与沉默 / 消息长度画像 / 情绪与语气 /
 *    高频词与口头禅 / 话题雷达与时段 / 发言排行与互动均衡度 / 特殊消息与互动 /
 *    每日开场与收尾）；
 *  - 进阶 12 个维度分成三个包，每包 4 个，各有开关、默认全开：
 *    时间包（活跃日历与趋势 / 回应速度 / 活跃密度与连续 / 连击与轮次）、
 *    关系包（接话·提问·默契 / 复读与重复 / 回复速度榜 / 个人作息雷达）、
 *    语言包（打字习惯 / 约定与提醒 / 时段话量画像 / 用词广度）。
 *
 * 第 21 轮的合并与新增（都是"信息重复就去掉、缺的才补进来"）：
 *  - 原核心【活跃热力】并入【活跃时段与热力】（同一份小时活跃矩阵的两种读法）；
 *  - 原语言包【情绪词雷达】并入核心【情绪与语气】（同一件事：情绪从哪读出来）；
 *  - 语言包补进【用词广度】（独立词种 / 总词次 / 集中度），复用已有的全量词频表，
 *    与【高频词与口头禅】的 TopN 词表口径互补而不重复。
 *
 * 第 22 轮的凝练 + 新增（一边合并、一边补真正缺的，总维度 24 → 25）：
 *  - 原核心【互动平衡】并入新的【互动均衡度】（两者读的是同一份 rank，分开列只会重复），
 *    并补上**发言基尼系数**与人均条数 —— 回答"话量到底有多集中"这个原来没答的问题；
 *  - 核心新增【分享物与链接密度】（链接/卡片、位置、转账/红包、图片/语音/视频的构成与占比），
 *    全部由主循环里已有的 typeCount 推导，不额外扫描、不查库；
 *  - 加上第 21 轮已做的三处合并，核心维度列表不再有"同一份数据的两种读法"。
 *
 * 三个开关的 key **沿用第 16/17/18 轮的三个老 key**：老用户即使之前关过某个包，
 * 设置也不会丢，只是该包现在装的是整合后的 4 个维度（而不是原来那 6~7 个）。
 *
 * 存的是布尔值，读失败一律按"开启"处理（宁可多显示，也不要因为一次读盘异常把功能吞掉）。
 */
object ChatAnalysisDimPacks {
    /** 始终输出的核心维度数量：设置页与统计口径共用，避免两处写死数字 */
    const val CORE_DIM_COUNT = 13

    /** 每个进阶包的维度数量 */
    const val PACK_DIM_COUNT = 4

    /** 进阶维度总量（三个包合计）= 12 */
    const val EXTRA_DIM_COUNT = PACK_DIM_COUNT * 3

    /** 维度总量（核心 + 进阶）= 25 */
    const val TOTAL_DIM_COUNT = CORE_DIM_COUNT + EXTRA_DIM_COUNT

    /** 时间包（活跃日历与趋势 / 回应速度 / 活跃密度与连续 / 连击与轮次） */
    const val PACK_TIME = 0

    /** 关系包（接话·提问·默契 / 复读与重复 / 回复速度榜 / 个人作息雷达） */
    const val PACK_RELATION = 1

    /** 语言包（打字习惯 / 约定与提醒 / 时段话量画像 / 用词广度） */
    const val PACK_LANGUAGE = 2

    /** 三个包分别沿用的老 key（顺序与 PACK_* 常量一致） */
    private val KEYS = arrayOf(
        "chat_analysis_extra_dims",
        "chat_analysis_dims_v17",
        "chat_analysis_dims_v18",
    )

    fun isEnabled(pack: Int): Boolean {
        if (pack < 0 || pack >= KEYS.size) return true
        return runCatching { WePrefs.getBoolOrDef(KEYS[pack], true) }.getOrDefault(true)
    }

    fun setEnabled(pack: Int, on: Boolean) {
        if (pack < 0 || pack >= KEYS.size) return
        runCatching { WePrefs.putBool(KEYS[pack], on) }
    }
}
