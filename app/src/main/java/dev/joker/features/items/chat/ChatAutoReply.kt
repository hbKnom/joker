package dev.joker.features.items.chat

import android.content.ContentValues
import android.os.SystemClock
import androidx.activity.ComponentActivity
import dev.joker.R
import dev.joker.features.api.core.WeDatabaseListenerApi
import dev.joker.features.api.core.WeMessageApi
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
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        cooldowns.clear()
        generation.incrementAndGet()
    }

    override fun onClick(context: ComponentActivity) {
        AutoReplySettings.showMainDialog(context)
    }

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

        // 【Round31 简化方案】场景字段探测：从 IInsertListener 的 ContentValues 接口
        // 上无法拿 msgSource 的结构化 Map（只有字符串），保守实现：
        //   - isAtMe       → false（保守：默认不认为消息是 @我，避免误触发 onlyAtMe 规则）
        //   - isNotifyAll  → false（同上）
        //   - isPatMe      → false（同上）
        //   - isQuote      → true iff type == MessageType.QUOTE.code（DB 字段直读）
        // 用户可用的实际场景字段触发只有 onlyQuote = type=QUOTE.code。
        // 阶段 2 实装：改用 WeMessageApi.methodMsgInfoStorageInsertMessage.hookAfter
        // 拿到 MessageInfo 实例，调用 message.isAtMe / isNotifyAll / isPat（这些属性
        // 内部已实装 msgSource XML 解析）。
        val isQuote = type == MessageType.QUOTE.code

        val gen = generation.get()
        executor.execute {
            try {
                process(rules, talker, content, gen, false, false, false, isQuote)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "auto reply processing failed", e)
            }
        }
    }

    /**
     * 【Round31 保守实现】@我 探测：IInsertListener 接口上拿不到 msgSource 的结构化 Map，
     * 阶段 2 改用 methodMsgInfoStorageInsertMessage.hookAfter 拿 MessageInfo 实例。
     * 当前保守返回 false，避免误触发 onlyAtMe 规则。
     */
    private fun isAtMeFromValues(@Suppress("UNUSED_PARAMETER") values: ContentValues): Boolean = false

    /** @所有人：保守返回 false，阶段 2 改用 MessageInfo.isNotifyAll */
    private fun isNotifyAllFromValues(
        @Suppress("UNUSED_PARAMETER") values: ContentValues,
        @Suppress("UNUSED_PARAMETER") content: String,
    ): Boolean = false

    /** 拍一拍我：保守返回 false，阶段 2 改用 MessageInfo 解析 PatMessage */
    private fun isPatMeFromValues(@Suppress("UNUSED_PARAMETER") values: ContentValues): Boolean = false

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
        val config = ChatAnalysisModelStore.selectedModel()
        if (config == null || config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            WeLogger.w(
                TAG,
                "AI reply skipped: 未在聊天分析里配置 AI 模型（或 baseUrl/apiKey/model 为空）" +
                    "，降级为 task.reply.text 固定文本",
            )
            return fallbackToFixedReply(task, talker)
        }

        // 2) system prompt：用户自定义 > 默认 Hchat 风格提示
        val sys = task.aiSystemPrompt.takeIf { it.isNotBlank() }
            ?: buildDefaultSystemPrompt(talker)
        val userPrompt = buildUserPrompt(content, talker)
        val maxTokens = task.aiMaxTokens.coerceIn(64, 4096)

        // 3) 调用 AI（非流式 plain，超时由 longClient 180s 控制）
        return try {
            val text = ChatAnalysisAi.plain(config, sys, userPrompt)
            if (gen != generation.get()) {
                WeLogger.i(TAG, "AI reply completed but feature already disabled, skip send")
                false
            } else if (text.isNullOrBlank()) {
                WeLogger.w(TAG, "AI reply empty: 降级为固定文本")
                fallbackToFixedReply(task, talker)
            } else {
                val cleaned = text.trim().take(2000) // 截断保护：微信单条文本上限 2000 字符
                WeMessageApi.sendText(talker, cleaned)
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "AI reply failed: 降级为固定文本", e)
            fallbackToFixedReply(task, talker)
        }
    }

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

    private fun buildDefaultSystemPrompt(talker: String): String =
        "你是一个友好的聊天助手，正在微信中和对方对话。请用简短、自然、口语化的方式回复，" +
            "1-3 句话以内，不要使用 markdown 标题/列表/代码块。"

    private fun buildUserPrompt(content: String, talker: String): String {
        // 复用 ChatAutoReply.renderTemplate 的 {content}/{talker}/{name} 占位符
        val user = sender(talker)
        return "原消息：${content.trim()}\n\n会话：${talker}${if (user.isNotEmpty()) "（发送者：$user）" else ""}\n\n请回复："
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
