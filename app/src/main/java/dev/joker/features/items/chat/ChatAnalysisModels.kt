package dev.joker.features.items.chat

import dev.joker.preferences.WePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 聊天记录分析 —— 数据模型与配置存储
 *
 * 迁移自 Joker Java 脚本「聊天记录分析 v0.3.4」：
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
 * 聊天记录分析的**维度块开关组**。
 *
 * 历史：第 20 轮把 41 个报告段位整合成 25 个维度；第 21/22/23 轮继续合并同源读数并补真缺的维度；
 * **第 26 轮按用户反馈（"25 个分析项目太多"）收敛成 10 个维度块** ——
 * 报告里 `【…】` 一级标题的条数就是对外展示的维度数，原子段位降为块内 `▸ ` 二级标题。
 *
 * 核心 7 块（始终输出）：
 *   基础画像 / 内容与载体 / 互动与点名 / 时间节律 / 节奏与冷场 / 语言与表达 / 发言与互动。
 * 进阶 3 块（三个开关各管 1 块，默认全开）：
 *   进阶·时间与趋势 / 进阶·关系与习惯 / 进阶·语言与习惯。
 *
 * 三个开关的 key **沿用第 16/17/18 轮的三个老 key**：老用户之前关过某个包，设置也不会丢。
 * 存的是布尔值，读失败一律按"开启"处理（宁可多显示，也不要因为一次读盘异常把功能吞掉）。
 */
object ChatAnalysisDimPacks {
    /**
     * 始终输出的核心**维度块**数量。
     *
     * 第 26 轮起对外展示的口径从「25 个原子维度」收敛成「10 个维度块」：核心 7 块
     * （基础画像 / 内容与载体 / 互动与点名 / 时间节律 / 节奏与冷场 / 语言与表达 / 发言与互动），
     * 原子段位全部降为块内二级标题（引擎里的 `SUB_HEAD_PREFIX`），读数一条没丢。
     */
    const val CORE_DIM_COUNT = 7

    /** 每个进阶开关管几个维度块（第 26 轮起每包 1 块，块内含 4 个原子段位） */
    const val PACK_DIM_COUNT = 1

    /** 进阶维度块总量（三个包合计）= 3 */
    const val EXTRA_DIM_COUNT = PACK_DIM_COUNT * 3

    /** 维度块总量（核心 + 进阶）= 10 */
    const val TOTAL_DIM_COUNT = CORE_DIM_COUNT + EXTRA_DIM_COUNT

    /** 时间包 =【进阶·时间与趋势】（活跃日历与趋势 / 回应速度 / 活跃密度与连续 / 连击与轮次） */
    const val PACK_TIME = 0

    /** 关系包 =【进阶·关系与习惯】（接话·提问·默契 / 复读与重复 / 回复速度榜 / 个人作息雷达） */
    const val PACK_RELATION = 1

    /** 语言包 =【进阶·语言与习惯】（打字习惯 / 约定与提醒 / 时段话量画像 / 用词广度） */
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
