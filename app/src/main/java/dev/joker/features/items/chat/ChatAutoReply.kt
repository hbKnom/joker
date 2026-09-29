package dev.joker.features.items.chat

import android.content.ContentValues
import android.os.SystemClock
import androidx.activity.ComponentActivity
import dev.joker.R
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.api.core.WeDatabaseListenerApi
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.api.core.models.MessageInfo
import dev.joker.features.api.core.models.MessageType
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.WeLogger
import dev.joker.utils.strings.isGroupChatWxId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object ChatAutoReply : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "聊天自动回复"
    override val nameRes = R.string.feature_chat_auto_reply_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_auto_reply_description

    private const val TAG = "ChatAutoReply"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ChatAutoReply").apply { isDaemon = true }
    }
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val generation = AtomicLong()

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        // 【Round31】hookAfter methodMsgInfoStorageInsertMessage 用以做基础过滤
        // （isSelfSender / type / talker / content 都不依赖 NativeXmlParser）
        // 场景字段 isAtMe/isNotifyAll/isPatMe 保守 false（避免引入 NativeXmlParser
        // 跨包 import 在 K2 编译器的 Unresolved 'asString' 问题）。
        WeMessageApi.methodMsgInfoStorageInsertMessage.hookAfter {
            try {
                val raw = args[0] ?: return@hookAfter
                val info = MessageInfo(raw)
                if (info.isSelfSender) return@hookAfter
                val msgType = info.type ?: return@hookAfter
                if (!msgType.isText) return@hookAfter
                if (info.talker.isEmpty()) return@hookAfter
                val talker = info.talker
                val content = info.content
                if (content.isEmpty()) return@hookAfter
                val sender = info.sender.takeIf { talker.isGroupChatWxId }

                val rules = AutoReplySettings.resolve(talker, sender)
                if (!rules.enabled.enabled) return@hookAfter
                if (!rules.timeRange.matches()) return@hookAfter

                // 【Round31 保守场景字段探测】只取 type（不依赖 NativeXmlParser）
                val isQuote = msgType.code == MessageType.QUOTE.code
                // isAtMe/isNotifyAll/isPatMe 留 false：阶段 2 实装时需把项目内
                // serialization 全套 import 适配 ChatAutoReply.kt 后再启用。
                val isAtMe = false
                val isNotifyAll = false
                val isPatMe = false

                val gen = generation.get()
                executor.execute {
                    try {
                        process(rules, talker, content, gen, isAtMe, isNotifyAll, isPatMe, isQuote)
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "auto reply processing failed", e)
                    }
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "methodMsgInfoStorageInsertMessage.hookAfter failed", e)
            }
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        cooldowns.clear()
        generation.incrementAndGet()
    }

    override fun onClick(context: ComponentActivity) {
        AutoReplySettings.showMainDialog(context)
    }

    /**
     * 保留 IInsertListener.onInsert 作为兜底通道：DB 层插入也兜一次（保守 false 探测）。
     * 当 methodMsgInfoStorageInsertMessage.hookAfter 没触发到（极少数情况）时仍能响应。
     */
    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return
        val isSend = values.getAsInteger("isSend") ?: 1
        if (isSend != 0) return
        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: return
        val sender = values.getAsString("sender").takeIf { talker.isGroupChatWxId }

        val rules = AutoReplySettings.resolve(talker, sender)
        if (!rules.enabled.enabled) return
        if (!rules.timeRange.matches()) return

        val isQuote = type == MessageType.QUOTE.code
        val gen = generation.get()
        executor.execute {
            try {
                process(rules, talker, content, gen, false, false, false, isQuote)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "auto reply (db fallback) failed", e)
            }
        }
    }

    private fun process(
        rules: AutoReplyRuleSet,
        talker: String,
        content: String,
        gen: Long,
        isAtMe: Boolean,
        isNotifyAll: Boolean,
        isPatMe: Boolean,
        isQuote: Boolean,
    ) {
        rules.tasks.forEachIndexed { index, task ->
            if (gen != generation.get()) return
            if (!task.enabled) return@forEachIndexed

            // 【Round30】场景字段过滤：onlyAtMe / onlyNotifyAll / onlyPatMe / onlyQuote
            if (task.onlyAtMe && !isAtMe) return@forEachIndexed
            if (task.onlyNotifyAll && !isNotifyAll) return@forEachIndexed
            if (task.onlyPatMe && !isPatMe) return@forEachIndexed
            if (task.onlyQuote && !isQuote) return@forEachIndexed

            if (!task.keyword.matches(content)) return@forEachIndexed

            val now = SystemClock.elapsedRealtime()
            val cooldownKey = "$index:$talker"
            val cooldown = task.cooldownMs.toLongOrNull() ?: 0L
            if (cooldown > 0 && now - (cooldowns[cooldownKey] ?: 0L) < cooldown) {
                WeLogger.i(TAG, "task skipped by cooldown: task=${task.name}, talker=$talker")
                return@forEachIndexed
            }

            val delay = task.delayMs.toLongOrNull()?.coerceIn(0L, 60000L) ?: 0L
            if (delay > 0) Thread.sleep(delay)
            if (gen != generation.get()) return

            // 【Round30】模板替换：{content}/{talker}/{name}
            val reply = task.reply
            val resolvedText = renderTemplate(reply.text, content, talker, sender(talker))

            // 【Round31】AI 回复分支：useAi=true 时不再发固定 reply 文本，
            // 而是调 ChatAnalysisAi.plain(selectedModel(), sys, prompt) 拿 AI 生成文本再发。
            // 模型配置复用 ChatAnalysisModelStore（与聊天分析功能共享）。
            val sent = if (task.useAi) {
                sendAiReply(task, content, talker, gen)
            } else when (reply.type) {
                AutoReplyType.TEXT ->
                    resolvedText.isNotBlank() && WeMessageApi.sendText(talker, resolvedText)

                AutoReplyType.IMAGE ->
                    reply.path.isNotBlank() && File(reply.path).isFile &&
                        WeMessageApi.sendImage(talker, reply.path)

                AutoReplyType.VIDEO ->
                    reply.path.isNotBlank() && File(reply.path).isFile &&
                        WeMessageApi.sendVideo(talker, reply.path)

                AutoReplyType.VOICE -> {
                    val duration = reply.voiceDurationMs.toIntOrNull() ?: 0
                    reply.path.isNotBlank() && File(reply.path).isFile && duration in 1..60000 &&
                        WeMessageApi.sendVoice(talker, reply.path, duration)
                }
            }
            if (sent) cooldowns[cooldownKey] = SystemClock.elapsedRealtime()
            if (task.stopAfterMatch) return
        }
    }

    /**
     * 【Round31】AI 回复实现：复用聊天分析的 [ChatAnalysisAi] + [ChatAnalysisModelStore]。
     *
     * 设计要点：
     *   - 失败自动降级为 task.reply.text 固定文本（向后兼容）
     *   - 命中规则按 Hchat 风格：「你是一个聊天助手，正在和对方对话，原消息：…，请简短自然地回复」
     *   - maxTokens 默认 500（可由任务覆盖）
     *   - generation 检查：AI 慢响应时防止被 onDisable 后的过期 gen 覆盖
     */
    private fun sendAiReply(
        task: AutoReplyTask,
        content: String,
        talker: String,
        gen: Long,
    ): Boolean {
        // 0) generation 检查：onDisable 后过期 gen 不再发
        if (gen != generation.get()) return false

        // 1) 取当前选中的 AI 模型（聊天分析配置共享）
        val config = ChatAnalysisModelStore.findByName(task.aiModelName)
            ?: ChatAnalysisModelStore.selectedModel()
        if (config == null || config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            WeLogger.w(
                TAG,
                "AI reply skipped: 未在聊天分析里配置 AI 模型（或 baseUrl/apiKey/model 为空）" +
                    "，降级为 task.reply.text 固定文本",
            )
            return fallbackToFixedReply(task, talker)
        }

        // 2) system prompt：用户自定义 > 默认自然聊天提示
        // 【Round43】默认提示词升级：模型现在能看到会话上下文（见 buildUserPrompt），
        // 因此提示词里明确要求「结合上下文、口语化、不重复、不暴露自己是 AI」。
        val sys = task.aiSystemPrompt.takeIf { it.isNotBlank() }
            ?: buildDefaultSystemPrompt(task, talker)
        val maxTokens = task.aiMaxTokens.coerceIn(64, 4096)

        // 【Round43】带上该会话最近 N 轮对话 —— 这是「上下文更正确」的关键：
        // 原实现只把当前这一条丢给模型，模型看不到上文，只能干巴巴回一句。
        val history = runCatching { loadRecentContext(talker, task.aiContextTurns) }
            .getOrDefault(emptyList())
        val userPrompt = buildUserPrompt(content, talker, history)

        // 3) 调用 AI（非流式 plain，超时由 longClient 180s 控制）
        return try {
            val first = ChatAnalysisAi.plain(config, sys, userPrompt)
            if (gen != generation.get()) {
                WeLogger.i(TAG, "AI reply completed but feature already disabled, skip send")
                return false
            }
            var cleaned = sanitizeReply(first, task.aiMaxChars)
            if (cleaned.isBlank()) {
                WeLogger.w(TAG, "AI reply empty: 降级为固定文本")
                return fallbackToFixedReply(task, talker)
            }

            // 4)【Round43】去重：生成的回复与自己上一条完全相同 → 让模型换个说法再生成一次；
            //    仍然相同则放弃本条（不发比复读更好），避免「复读机」观感。
            if (task.aiAvoidRepeat && isSameAsLastOwnText(talker, cleaned)) {
                val retry = runCatching {
                    ChatAnalysisAi.plain(
                        config,
                        sys,
                        userPrompt + "\n\n（注意：上一句和之前说过的一模一样了，请换一种说法，不要重复。）",
                    )
                }.getOrNull()
                val second = sanitizeReply(retry, task.aiMaxChars)
                if (second.isNotBlank() && !isSameAsLastOwnText(talker, second)) {
                    cleaned = second
                } else {
                    WeLogger.i(TAG, "AI reply 与上一条重复，已放弃本条发送")
                    return false
                }
            }
            WeMessageApi.sendText(talker, cleaned)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "AI reply failed: 降级为固定文本", e)
            fallbackToFixedReply(task, talker)
        }
    }

    // ── 【Round43】AI 回复自然度：上下文 / 清洗 / 去重 ─────────────────

    /**
     * 读取该会话最近 [turns] 轮「文本」消息（按时间正序），喂给模型做上下文。
     *
     * 约束：
     *   - 只取文本消息（语音/图片/系统消息对模型无意义，反而干扰）；
     *   - 每条裁到 120 字符，整段最多 1200 字符，避免把 token 预算烧在历史上；
     *   - 数据库访问包在 runCatching 里（拿不到历史 ≠ 回复失败，降级为无上下文）。
     */
    private fun loadRecentContext(talker: String, turns: Int): List<Pair<Boolean, String>> {
        if (turns <= 0) return emptyList()
        val limit = (turns + 1).coerceAtMost(31)
        val rows = WeDatabaseApi.getMessages(talker, 1, limit)
        if (rows.isEmpty()) return emptyList()
        return rows.asReversed() // SQL 是倒序取最新，这里翻回时间正序
            .filter { it.typeCode == MessageType.TEXT.code }
            .map { it.isSend == 1 to plainTextOf(it.content, 120) }
            .filter { (_, text) -> text.isNotBlank() }
    }

    /** 原始 content 可能是 XML（引用/链接/表情等），这里只抠出可读文本。 */
    private fun plainTextOf(raw: String, limit: Int): String {
        var t = raw
        if (t.contains('<')) {
            // 分享卡片/链接类消息把正文放在 <title> 里，优先取它；取不到就退回「剥标签」。
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(t)?.groupValues?.getOrNull(1)
            t = title ?: t.replace(Regex("<[^>]{1,200}>"), " ")
        }
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.length > limit) t.take(limit) + "…" else t
    }

    /** 生成结果清洗：去掉包裹引号/markdown/多余换行，并按任务配置截断。 */
    private fun sanitizeReply(raw: String?, maxChars: Int): String {
        var t = raw.orEmpty().trim()
        if (t.isEmpty()) return ""
        t = t.removePrefix("```").removeSuffix("```").trim()
        t = t.replace(Regex("^[\"'「『]+"), "").replace(Regex("[\"'」』]+$"), "")
        t = t.replace(Regex("\\s*\\n+\\s*"), " ")
        t = t.replace(Regex("[ \t]{2,}"), " ")
        t = t.trim()
        val cap = maxChars.coerceIn(10, 2000)
        if (t.length > cap) t = t.take(cap).trimEnd()
        return t
    }

    /** 本条生成的文本是否与「自己最近发过的一条文本」完全相同。 */
    private fun isSameAsLastOwnText(talker: String, text: String): Boolean = runCatching {
        WeDatabaseApi.getMessages(talker, 1, 8)
            .firstOrNull { it.isSend == 1 && it.typeCode == MessageType.TEXT.code }
            ?.content
            ?.trim() == text
    }.getOrDefault(false)

    private fun fallbackToFixedReply(task: AutoReplyTask, talker: String): Boolean {
        val reply = task.reply
        return when (reply.type) {
            AutoReplyType.TEXT -> {
                val text = renderTemplate(reply.text, "", talker, sender(talker))
                text.isNotBlank() && WeMessageApi.sendText(talker, text)
            }
            AutoReplyType.IMAGE ->
                reply.path.isNotBlank() && File(reply.path).isFile &&
                    WeMessageApi.sendImage(talker, reply.path)
            AutoReplyType.VIDEO ->
                reply.path.isNotBlank() && File(reply.path).isFile &&
                    WeMessageApi.sendVideo(talker, reply.path)
            AutoReplyType.VOICE -> {
                val duration = reply.voiceDurationMs.toIntOrNull() ?: 0
                reply.path.isNotBlank() && File(reply.path).isFile && duration in 1..60000 &&
                    WeMessageApi.sendVoice(talker, reply.path, duration)
            }
        }
    }

    private fun buildDefaultSystemPrompt(
        @Suppress("UNUSED_PARAMETER") task: AutoReplyTask,
        talker: String,
    ): String {
        val isGroup = talker.isGroupChatWxId
        val identity = if (isGroup) {
            "你在一个微信群聊里，以群成员口吻接话"
        } else {
            "你在和一个朋友一对一聊天"
        }
        return "你是一个真实的微信用户，$identity。回复要求：" +
            "① 先读懂上面的对话上下文，接得上话、答得对题；" +
            "② 口语化、短句，1-2 句，像朋友随手回的消息；" +
            "③ 不要重复上一句说过的话，不要复述对方原文；" +
            "④ 不要用 markdown（标题/列表/代码块/加粗），不要用书面语套话；" +
            "⑤ 绝不要提到自己是 AI、模型或助手，也不要解释你在做什么。"
    }

    /**
     * 【Round43】用户提示词：附带该会话最近若干轮对话。
     *
     * 格式刻意贴近人类阅读顺序（时间正序 + 「我/对方」标签），
     * 让模型能直接判断「现在轮到谁、上文聊了什么」。
     */
    private fun buildUserPrompt(
        content: String,
        talker: String,
        history: List<Pair<Boolean, String>>,
    ): String {
        val user = sender(talker)
        val sb = StringBuilder()
        if (history.isNotEmpty()) {
            sb.append("最近对话（时间正序，「我」= 你自己）：\n")
            history.forEach { (isSend, text) ->
                sb.append(if (isSend) "我：" else "对方：").append(text).append('\n')
            }
            sb.append('\n')
        }
        sb.append("对方刚发来的新消息：").append(content.trim())
        if (user.isNotEmpty() && talker.isGroupChatWxId) sb.append("（发送者：").append(user).append("）")
        sb.append("\n\n请只输出你要回复的那一句话，不要任何前缀或解释。")
        return sb.toString()
    }

    /**
     * 【Round30】Hchat 模板替换对齐：{content} → 原消息正文，{talker} → 会话 ID，
     * {name} → 发送者 ID（群聊时）/ talker（私聊时）。缺失占位符保留原样。
     */
    private fun renderTemplate(template: String, content: String, talker: String, name: String): String =
        template
            .replace("{content}", content)
            .replace("{talker}", talker)
            .replace("{name}", name)

    private fun sender(@Suppress("UNUSED_PARAMETER") talker: String): String = ""
}
