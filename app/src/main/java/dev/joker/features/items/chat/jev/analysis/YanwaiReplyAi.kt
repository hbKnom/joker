package dev.joker.features.items.chat.jev.analysis

import dev.joker.features.items.chat.AiModelConfig
import dev.joker.features.items.chat.ChatAnalysisAi
import dev.joker.features.items.chat.ChatAnalysisModelStore
import dev.joker.features.items.chat.jev.core.AnalysisInput
import dev.joker.features.items.chat.jev.core.ContextMessage
import dev.joker.features.items.chat.jev.core.ModulePrefs
import dev.joker.features.items.chat.jev.core.Mood
import dev.joker.features.items.chat.jev.core.MoodLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 潜语（决策分析）的「解读 + 回复」补写：**非 Jev** 的通用对话模型。
 *
 * ## 为什么要有这一层
 *
 * 用户实测（第 56 轮）：自己的 Jev 渠道只支持「决策」不支持「分析」——第一轮（场景/情绪）
 * 正常，第二轮（深度解读）直接 HTTP 400，卡片上就只剩情绪概率，而且把技术报错摆到了脸上；
 * 同时用户要的「针对这一条消息、可以直接复制发出去的回复」也就没有了。
 *
 * 于是分工改成：
 *  - **决策**交给 Jev（它擅长的：场景 / 情绪概率 / 阶段 / 候选动作 / 复核）；
 *  - **说人话**交给「聊天分析 → AI 模型」里那套已经配好的 OpenAI 兼容模型
 *    （同一套 `ChatAnalysisModelStore`，不新增第二处配置）。
 *
 * 三条硬约束（决定实现形状）：
 *  1. **绝不拖垮分析**：这条链路是「锦上添花」，任何失败（没配模型 / 超时 / 渠道报错 /
 *     返回不可解析）都只能返回原结论，不允许把一条已经成功的决策分析变成失败卡。
 *     所以 [enrich] 内部全程 `runCatching`，且用 [ChatAnalysisAi.plainQuick]（短超时）而不是
 *     180 秒超时的长连接客户端 —— 潜语的补写是在分析 worker 里同步做的，
 *     服务端挂起会把整个 worker 卡住（第二铁律：不能卡）。
 *  2. **不额外花冤枉钱**：每条消息最多一次请求；解析不出内容就放弃，不重试、不追问。
 *  3. **回复必须像真人**：提示词沿用聊天自动回复那套「去 AI 味」硬规则
 *     （长度匹配、禁助手腔、禁列点、不许提 AI），而不是一句泛泛的「要自然」。
 */
object YanwaiReplyAi {

    private const val TAG = "YanwaiReplyAi"

    /** 送进提示词的上下文上限（条数由用户的「上下文条数」设置先卡过一道，这里再兜一层）。 */
    private const val MAX_CONTEXT_MESSAGES = 20

    /** 上下文文本的字符预算：超了就丢最老的几条（和决策侧的做法一致：宁可少、不许漫）。 */
    private const val MAX_CONTEXT_CHARS = 6_000

    /** 输出上限：解读 + 一句回复，512 token 足够，也顺带逼模型说短话。 */
    private const val MAX_TOKENS = 512

    /** 解读 / 回复各自的字数硬上限（模型偶尔会写小作文，卡片上放不下）。 */
    private const val MAX_READING_CHARS = 600
    private const val MAX_REPLY_CHARS = 400

    private const val MARK_READING = "解读"
    private const val MARK_REPLY = "回复"

    private const val STAMP_PATTERN = "MM-dd HH:mm"

    /** 生成结果：人话解读 + 可直接发出去的回复。 */
    data class Draft(val reading: String, val reply: String)

    /** 命中的标记：标记名 + 内容在行内的起始下标。 */
    private class Mark(val name: String, val start: Int)

    // ------------------------------------------------------------------ 配置

    /**
     * 解析「用哪个模型补写」。
     *
     * [name] 为空 = 跟随「聊天分析」当前选中的模型（默认行为，开箱即用）；
     * 非空则按名字取用户保存的那一条。返回 null 表示**不可用**（没配 / 缺 Key / 缺地址），
     * 调用方据此彻底跳过这条链路 —— 一次网络请求都不会发出去。
     */
    fun resolveModel(name: String = ModulePrefs.replyAiModelName): AiModelConfig? {
        val explicit = name.trim().takeIf { it.isNotEmpty() }?.let { ChatAnalysisModelStore.findByName(it) }
        val config = explicit ?: ChatAnalysisModelStore.selectedModel()
        return config?.takeIf { it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() && it.model.isNotBlank() }
    }

    /** 这条链路现在能不能跑（开关 + 模型都就绪）。设置页和卡片的说明文案都用它。 */
    fun isReady(): Boolean = ModulePrefs.replyAiEnabled && resolveModel() != null

    /** 展示用：当前会用到哪个模型（`名字 · 模型id`）。没配时返回 null。 */
    fun currentLabel(name: String = ModulePrefs.replyAiModelName): String? {
        val config = resolveModel(name) ?: return null
        return "${config.name} · ${config.model}"
    }

    // ------------------------------------------------------------------ 主流程

    /**
     * 给一条**已经有决策结论**的 [mood] 补上「解读 + 回复」。
     *
     * 绝不影响原结论：失败、超时、模型不听话、解析不出来 —— 一律原样返回 [mood]。
     * 成功时：
     *  - 写好 [Mood.replyDraft]（覆盖 Jev 那份；用户要的就是通用模型写的这一句）；
     *  - 补 [Mood.aiReading]（没有结构化解读时卡片就显示它）与 [Mood.aiModel]（来源标注）；
     *  - 清掉 [Mood.note]：第二轮 400 那类技术报错这时已经有了完整的替代结果，
     *    再把它摆在用户脸上只会让人以为功能坏了（失败原因仍写进日志）。
     */
    fun enrich(input: AnalysisInput, mood: Mood, name: String = ModulePrefs.replyAiModelName): Mood {
        if (!ModulePrefs.replyAiEnabled) return mood
        val config = resolveModel(name) ?: run {
            MoodLog.w("$TAG 已跳过：没有可用的通用模型（聊天分析 → AI 模型里填好 Base URL 与 Key 即可）")
            return mood
        }
        val draft = runCatching { generate(config, input, mood) }
            .onFailure { failure -> MoodLog.w("$TAG 生成失败（保留决策结论）：${failure.message}") }
            .getOrNull()
        if (draft == null) return mood
        val reading = draft.reading.takeIf { it.isNotBlank() }
        val reply = draft.reply.takeIf { it.isNotBlank() }
        if (reading == null && reply == null) {
            MoodLog.w("$TAG 模型返回里没有可用的解读或回复，保留决策结论")
            return mood
        }
        MoodLog.i("$TAG 已补写（${config.name}）：解读=${reading?.length ?: 0} 字，回复=${reply?.length ?: 0} 字")
        return mood.copy(
            aiReading = reading.orEmpty(),
            aiModel = config.name,
            replyDraft = reply ?: mood.replyDraft,
            note = null,
            // detail 是「回插会话 / 复制全文」用的多行正文：回复行必须与 replyDraft 一致，
            // 否则同一张卡上会出现两个互相矛盾的「回复：」。
            detail = withReplyLine(mood.detail, reply ?: mood.replyDraft),
        )
    }

    /** 真打一次模型（同步、短超时）。异常向上抛，由 [enrich] 收口。 */
    fun generate(config: AiModelConfig, input: AnalysisInput, mood: Mood): Draft {
        val raw = ChatAnalysisAi.plainQuick(config, systemPrompt(), userPrompt(input, mood), MAX_TOKENS)
        return parse(raw.orEmpty())
    }

    /**
     * 设置页的「测试生成」：拿一条示例消息跑通整条链路，把结果原样回给用户看。
     *
     * 用户要的是「我不用等真消息也能验证模型配置对不对」，所以这里连提示词与解析都走同一条路径。
     */
    fun selfTest(name: String = ModulePrefs.replyAiModelName): String {
        val config = resolveModel(name) ?: throw IllegalStateException("还没有可用的通用模型，请先在上面选择或新建一个")
        val draft = generate(config, sampleInput(), sampleMood())
        if (draft.reading.isBlank() && draft.reply.isBlank()) {
            throw IllegalStateException("模型有响应，但没有按「解读：/回复：」的格式回答，请换一个模型再试")
        }
        return buildString {
            if (draft.reading.isNotBlank()) append(MARK_READING).append("：").append(draft.reading)
            if (draft.reading.isNotBlank() && draft.reply.isNotBlank()) append('\n')
            if (draft.reply.isNotBlank()) append(MARK_REPLY).append("：").append(draft.reply)
        }
    }

    /** 示例输入（只用于设置页测试；不会碰任何真实聊天数据）。 */
    fun sampleInput(): AnalysisInput = AnalysisInput(
        text = "你明天有空吗？我下午都在。",
        talker = "sample",
        speaker = "对方",
        context = listOf(
            ContextMessage(speaker = "我", text = "上次那家店还不错"),
            ContextMessage(speaker = "对方", text = "嗯嗯，下次再去"),
        ),
    )

    /** 示例结论（模拟 Jev 已经跑完的那一轮）。 */
    private fun sampleMood(): Mood = Mood(
        label = "邀约安排",
        score = 0.35,
        risk = 0,
        raw = "",
        detail = "潜语解读 · 情绪概率\n情绪：平静 59% · 开心 31%",
        advice = "接住对方的时间信息，给出一个明确的时段",
        dominant = "平静",
        confidence = 0.62,
    )

    // ------------------------------------------------------------------ 提示词

    /**
     * 系统提示词：三段式要求 ——「你是真人」「解读要大白话」「回复是直接能发出去的那句话」。
     *
     * 表达约束照抄聊天自动回复那套（第 52 轮按用户「不要 AI 人机味」反馈重写过的），
     * 因为那套规则是**可判定**的（长度匹配 / 禁助手腔 / 禁列点 / 不许提 AI），
     * 而不是一句泛泛的「要自然」——泛泛要求模型不会照做。
     */
    private fun systemPrompt(): String = "你是一个真实的微信用户，正在看别人发给自己的一条消息。" +
        "现在要你做两件事，输出两段，每段一行，格式必须是「解读：…」和「回复：…」，不要有别的内容：" +
        "解读：用大白话讲清对方这句话的意图和情绪、以及你现在处在什么位置（一到两句，别用术语，别分点）；" +
        "回复：写出你接下来**要发出去的那条微信消息本身**，是能直接复制粘贴发出去的成品，不要加引号、不要加说话人前缀。" +
        "回复必须遵守：" +
        "① 长度跟对方这条消息相当：对方一句话就回一句话，别长篇大论；" +
        "② 用日常口语，可以有语气词（嗯、行、哈哈、哎、不是吧）但别每句都堆；" +
        "③ 先接住对方这句话再往下说；不确定的事不要编，直接顺着问一句；" +
        "④ 禁止客服/助手腔：不要出现「我理解你的感受」「希望能帮到你」「如有需要」「收到」「亲」这类词；" +
        "⑤ 禁止书面语与结构化表达：不要 markdown、不要分点、不要总结、不要标题、不要 emoji 堆砌；" +
        "⑥ 不要复述对方原话，也不要重复你上面已经说过的话；" +
        "⑦ 绝不提 AI / 模型 / 助手 / 系统，不要解释自己在做什么。" +
        "风格参考（照这个味儿写，不要照抄内容）：「行，那我下午过去」「明天下午都行，你说几点」「哈哈你可真会挑时间」。"

    /**
     * 用户提示词：消息 + 带时间的前文 + Jev 的决策结论。
     *
     * 决策结论是**参考**而不是要复述的内容 —— 用户要的是「基于这些判断，说一句人话」，
     * 所以明确写了「不要照抄上面的用词」「不要出现术语」。
     */
    private fun userPrompt(input: AnalysisInput, mood: Mood): String {
        val builder = StringBuilder()
        val lines = contextLines(input)
        if (lines.isNotEmpty()) {
            builder.append("最近对话（时间正序，「我」= 你自己）：\n")
            lines.forEach { builder.append(it).append('\n') }
            builder.append('\n')
        }
        builder.append("对方刚发来的新消息")
        stampOf(input.createdAt)?.let { builder.append("（").append(it).append("）") }
        if (input.speaker.isNotBlank() && input.speaker != "对方") {
            builder.append("（发送者：").append(input.speaker).append("）")
        }
        builder.append("：").append(input.text.trim()).append("\n\n")

        val facts = decisionLines(mood)
        if (facts.isNotEmpty()) {
            builder.append("潜语的决策判定（供参考，**不要照抄用词、不要出现术语**）：\n")
            facts.forEach { builder.append("· ").append(it).append('\n') }
            builder.append('\n')
        }
        builder.append("现在只输出两行：第一行以「").append(MARK_READING)
            .append("：」开头（大白话，别分点、别用术语），第二行以「").append(MARK_REPLY)
            .append("：」开头（直接能发出去的那条消息本身，不要引号、不要前缀）。")
        return builder.toString()
    }

    /** 前文行：`[MM-dd HH:mm] 我：…`；时间读不到就只给说话人（不编时间）。 */
    private fun contextLines(input: AnalysisInput): List<String> {
        val all = input.context.takeLast(MAX_CONTEXT_MESSAGES)
        val kept = ArrayDeque<ContextMessage>()
        var budget = MAX_CONTEXT_CHARS
        for (message in all.asReversed()) {
            val cost = message.text.length + 16
            if (budget - cost < 0 && kept.isNotEmpty()) break
            budget -= cost
            kept.addFirst(message)
        }
        return kept.map { message ->
            val stamp = stampOf(message.createdAt)
            val who = message.speaker.ifBlank { "对方" }
            if (stamp == null) "$who：${message.text}" else "[$stamp] $who：${message.text}"
        }
    }

    /** 把 Jev 的结论压成几行「事实」（情绪 / 场景 / 阶段 / 建议 / 结构化解读）。 */
    private fun decisionLines(mood: Mood): List<String> {
        val out = ArrayList<String>(6)
        mood.dominant?.takeIf { it.isNotBlank() }?.let { dominant ->
            val percent = (mood.confidence * 100).toInt()
            out += if (percent > 0) "对方这条的主情绪：$dominant（置信度 $percent%）" else "对方这条的主情绪：$dominant"
        }
        mood.bars.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") { "${it.name} ${it.percent}%" }
            ?.let { out += "情绪概率：$it" }
        val scene = mood.sceneLabel?.takeIf { it.isNotBlank() }
        val progress = mood.progressLabel?.takeIf { it.isNotBlank() }
        if (scene != null || progress != null) {
            out += "场景与阶段：${listOfNotNull(scene, progress).joinToString(" · ")}"
        }
        mood.readingTitle?.takeIf { it.isNotBlank() }?.let { out += "这句话可能在说：$it" }
        mood.readingQuestion?.takeIf { it.isNotBlank() }?.let { out += "要回答的问题：$it" }
        mood.advice?.takeIf { it.isNotBlank() }?.let { out += "建议的动作：$it" }
        return out
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 从模型输出里取「解读 + 回复」。
     *
     * 兼容三种写法（模型不听话是常态，这里必须宽容，但**绝不猜**）：
     *  1. JSON：`{"reading": "...", "reply": "..."}`（含 ```json 围栏）；
     *  2. 两行标记式：`解读：… / 回复：…`（含 `**解读**：`、`1. 解读：` 这类前缀噪声）；
     *  3. 只有一段：整段当作解读，回复留空（宁可少给，不给错）。
     */
    fun parse(raw: String): Draft {
        val text = raw.trim()
        if (text.isEmpty()) return Draft("", "")
        jsonDraft(text)?.let { return it }

        val reading = StringBuilder()
        val reply = StringBuilder()
        var target: StringBuilder? = null
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val hit = markerOf(trimmed)
            when (hit?.name) {
                MARK_READING -> {
                    target = reading
                    reading.append(clean(trimmed.substring(hit?.start ?: trimmed.length)))
                }
                MARK_REPLY -> {
                    target = reply
                    reply.append(clean(trimmed.substring(hit?.start ?: trimmed.length)))
                }
                else -> {
                    // 没标记的行：跟着上一个标记走（解读经常换行），第一个标记之前的内容算解读
                    val slot = target ?: reading.also { target = it }
                    if (slot.isNotEmpty()) slot.append(' ')
                    slot.append(clean(trimmed))
                }
            }
        }
        return Draft(
            tidy(reading.toString(), MAX_READING_CHARS),
            tidy(reply.toString(), MAX_REPLY_CHARS),
        )
    }

    /** 命中的标记：`解读：…` / `回复：…`（也容忍半角冒号与 markdown 加粗）。 */
    private fun markerOf(line: String): Mark? {
        for (mark in arrayOf(MARK_READING, MARK_REPLY)) {
            val at = line.indexOf("$mark：").takeIf { it >= 0 }
                ?: line.indexOf("$mark:").takeIf { it >= 0 }
                ?: continue
            return Mark(mark, at + mark.length + 1)
        }
        return null
    }

    /** JSON 形态（含围栏）：键名不敏感（reading/reply，也接受 analysis/answer）。 */
    private fun jsonDraft(text: String): Draft? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val obj = org.json.JSONObject(text.substring(start, end + 1))
            val reading = firstOf(obj, "reading", "analysis", "解读")
            val reply = firstOf(obj, "reply", "answer", "回复")
            if (reading.isEmpty() && reply.isEmpty()) null
            else Draft(tidy(reading, MAX_READING_CHARS), tidy(reply, MAX_REPLY_CHARS))
        }.getOrNull()
    }

    /** 依次取第一个非空键（键名不敏感：模型有时把 reading 写成 analysis）。 */
    private fun firstOf(obj: org.json.JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    // ------------------------------------------------------------------ 文本清洗

    /** 去掉 markdown 噪音、引号、以及模型爱加的「回复：」重复前缀。 */
    private fun clean(raw: String): String = raw
        .replace("**", "")
        .replace("__", "")
        .removePrefix("#")
        .trim()
        .trim('`', '"', '“', '”', '「', '」', '\'', '*', '-', ' ')
        .replace(Regex("^\\d+[.、)]\\s*"), "")
        .trim()

    /** 压掉多余空白 + 硬截断。单行展示用，所以换行统一成空格。 */
    private fun tidy(raw: String, limit: Int): String {
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= limit) flat else flat.take(limit).trimEnd() + "…"
    }

    /** `detail` 里的「回复：」行换成通用模型写的那句（没有就原样返回）。 */
    private fun withReplyLine(detail: String, reply: String): String {
        if (reply.isBlank()) return detail
        val kept = detail.lines().filterNot { it.startsWith("$MARK_REPLY：") }
        return (kept + "$MARK_REPLY：$reply").joinToString("\n")
    }

    /**
     * 毫秒时间戳 → `MM-dd HH:mm`（0 / 非法值返回 null，绝不编时间）。
     *
     * 刻意**每次新建** formatter：`SimpleDateFormat` 不是线程安全的，而这里是 3 个分析 worker
     * 并发调用（共享一个实例会串出乱七八糟的时间，甚至抛异常）。
     */
    private fun stampOf(millis: Long): String? {
        if (millis <= 0L) return null
        return runCatching {
            SimpleDateFormat(STAMP_PATTERN, Locale.getDefault()).format(Date(millis))
        }.getOrNull()
    }
}
