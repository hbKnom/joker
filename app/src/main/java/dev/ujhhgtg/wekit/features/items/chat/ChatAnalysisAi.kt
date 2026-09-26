package dev.ujhhgtg.wekit.features.items.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 聊天记录分析 —— OpenAI 兼容 AI 客户端
 *
 * 保持脚本原始请求方式：POST {baseUrl}{path}，Authorization: Bearer <key>，
 * body { model, temperature, stream, messages }。支持流式（SSE data: 行）与非流式降级。
 * 额外提供「测试连接」：拉取模型列表 + 最小对话 ping。
 *
 * 2026-08-30 修复：
 *  - 测试连接 max_tokens 由 32 提升到 2048（推理模型光 reasoning 就耗尽 32，导致流式/非流式误判失败）
 *  - 兼容 reasoning_content 兜底（deepseek-chat V3.1+ / deepseek-reasoner 只有 reasoning 时也算可用）
 *  - 测试连接与模型列表用短超时 client，避免服务端挂起时干等 180s
 *  - 流式解析兼容无 data: 前缀的裸 JSON 行、delta/message 缺省、[DONE]
 */
object ChatAnalysisAi {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 长超时 client：AI 总结等长文本生成用 */
    private val longClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 短超时 client：测试连接 / 模型列表用，避免等待过久 */
    private val shortClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun buildBody(model: String, sys: String, userContent: String, stream: Boolean, maxTokens: Int? = null): String {
        val body = JSONObject()
        body.put("model", model)
        body.put("temperature", 0.7)
        body.put("stream", stream)
        if (maxTokens != null) body.put("max_tokens", maxTokens)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", sys))
        messages.put(JSONObject().put("role", "user").put("content", userContent))
        body.put("messages", messages)
        return body.toString()
    }

    private fun post(client: OkHttpClient, config: AiModelConfig, body: String): okhttp3.Response {
        val req = Request.Builder()
            .url(config.endpoint())
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Accept", "text/event-stream, application/json")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body.toRequestBody(jsonType))
            .build()
        return client.newCall(req).execute()
    }

    /** 流式请求：回调增量文本，返回完整文本。失败抛异常。 */
    @Throws(Exception::class)
    fun stream(config: AiModelConfig, sys: String, userContent: String, onDelta: (String) -> Unit = {}): String {
        return streamInternal(longClient, config, sys, userContent, null, includeReasoning = false, onDelta)
    }

    /** 流式请求（带 max_tokens 限制，用于测试连接；reasoning 兜底计入结果）。 */
    @Throws(Exception::class)
    fun streamWithMaxTokens(config: AiModelConfig, sys: String, userContent: String, maxTokens: Int): String {
        return streamInternal(shortClient, config, sys, userContent, maxTokens, includeReasoning = true, {})
    }

    private fun streamInternal(
        client: OkHttpClient,
        config: AiModelConfig,
        sys: String,
        userContent: String,
        maxTokens: Int?,
        includeReasoning: Boolean,
        onDelta: (String) -> Unit,
    ): String {
        val body = buildBody(config.model, sys, userContent, stream = true, maxTokens = maxTokens)
        post(client, config, body).use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw Exception("HTTP ${resp.code}：${err.lineSequence().firstOrNull().orEmpty().take(300)}")
            }
            val sb = StringBuilder()
            val source = resp.body?.source() ?: throw Exception("响应为空")
            var done = false
            while (!done) {
                val line = source.readUtf8Line() ?: break
                val t = line.trim()
                if (t.isEmpty()) continue
                val payload: String
                if (t.startsWith("data:")) {
                    payload = t.substring(5).trim()
                } else if (t.startsWith("{")) {
                    // 部分代理在 stream=true 时仍返回裸 JSON 行
                    payload = t
                } else {
                    continue
                }
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                val choices = obj.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val c0 = choices.optJSONObject(0) ?: continue
                // 流式：delta；个别实现直接给 message
                val msg = c0.optJSONObject("delta") ?: c0.optJSONObject("message") ?: continue
                val c = msg.optString("content")
                val rz = msg.optString("reasoning_content")
                if (c.isNotEmpty()) {
                    sb.append(c)
                    onDelta(c)
                } else if (includeReasoning && rz.isNotEmpty()) {
                    sb.append(rz)
                    onDelta(rz)
                }
            }
            return sb.toString()
        }
    }

    /** 非流式请求（流式失败时的降级路径）。 */
    @Throws(Exception::class)
    fun plain(config: AiModelConfig, sys: String, userContent: String): String? {
        return plainInternal(longClient, config, sys, userContent, null, includeReasoning = false)
    }

    /** 非流式请求（带 max_tokens 限制，用于测试连接；reasoning 兜底计入结果）。 */
    @Throws(Exception::class)
    fun plainWithMaxTokens(config: AiModelConfig, sys: String, userContent: String, maxTokens: Int): String? {
        return plainInternal(shortClient, config, sys, userContent, maxTokens, includeReasoning = true)
    }

    private fun plainInternal(
        client: OkHttpClient,
        config: AiModelConfig,
        sys: String,
        userContent: String,
        maxTokens: Int?,
        includeReasoning: Boolean,
    ): String? {
        val body = buildBody(config.model, sys, userContent, stream = false, maxTokens = maxTokens)
        post(client, config, body).use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw Exception("HTTP ${resp.code}：${err.lineSequence().firstOrNull().orEmpty().take(300)}")
            }
            val text = resp.body?.string() ?: return null
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val c0 = choices.optJSONObject(0) ?: return null
            // 非流式：message；个别实现给 delta
            val msg = c0.optJSONObject("message") ?: c0.optJSONObject("delta") ?: return null
            val c = msg.optString("content")
            if (c.isNotEmpty()) return c
            if (includeReasoning) {
                val rz = msg.optString("reasoning_content")
                if (rz.isNotEmpty()) return rz
            }
            return null
        }
    }

    /**
     * 测试指定模型是否可用：先发流式请求，再发非流式请求，两者任一成功即判定可用。
     * 用于「从 /models 拉取列表 → 点选某个模型 → 验证该模型」的测试流程。
     * 短超时 + 大 max_tokens（2048），reasoning 兜底，兼容推理模型。
     */
    @Throws(Exception::class)
    fun testModel(config: AiModelConfig, targetModel: String): AiTestResult {
        val model = targetModel.trim().ifBlank { config.model.trim().ifBlank { "gpt-4o-mini" } }

        // 1) 流式测试（大 max_tokens，reasoning 兜底）
        var streamText = ""
        var streamError: String? = null
        try {
            streamText = streamWithMaxTokens(config.copy(model = model), "你是测试助手，只回复OK", "ping", 2048)
        } catch (e: Exception) {
            streamError = e.message
        }

        // 2) 非流式测试
        var plainText = ""
        var plainError: String? = null
        try {
            plainText = plainWithMaxTokens(config.copy(model = model), "你是测试助手，只回复OK", "ping", 2048).orEmpty()
        } catch (e: Exception) {
            plainError = e.message
        }

        val streamOk = streamText.isNotBlank()
        val plainOk = plainText.isNotBlank()
        val success = streamOk || plainOk

        val sb = StringBuilder()
        sb.append("模型「").append(model).append("」").append(if (success) "可用" else "不可用").append("\n\n")
        if (streamOk) {
            sb.append("✅ 流式请求正常：").append(streamText.trim().take(60)).append("\n")
        } else {
            sb.append("❌ 流式请求失败：").append(streamError ?: "无返回内容").append("\n")
        }
        if (plainOk) {
            sb.append("✅ 非流式请求正常：").append(plainText.trim().take(60)).append("\n")
        } else {
            sb.append("❌ 非流式请求失败：").append(plainError ?: "无返回内容").append("\n")
        }
        if (success && !streamOk) sb.append("（流式不可用时，AI 总结将自动降级为非流式）\n")

        return AiTestResult(
            success = success,
            message = sb.toString().trim(),
            testedModel = model,
            streamOk = streamOk,
            plainOk = plainOk,
        )
    }

    /** 拉取 OpenAI 兼容 /models 列表（短超时）。 */
    @Throws(Exception::class)
    fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val b = baseUrl.trim().trimEnd('/')
        val req = Request.Builder()
            .url("$b/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        shortClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw Exception("HTTP ${resp.code}：${err.lineSequence().firstOrNull().orEmpty().take(300)}")
            }
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val arr = obj.optJSONArray("data") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotEmpty()) out.add(id)
            }
            return out.distinct().sorted()
        }
    }
}
