package dev.ujhhgtg.wekit.agent.model

import io.ktor.client.plugins.DefaultRequest
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaController
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaModels
import dev.ujhhgtg.wekit.agent.model.local.LOCAL_LLAMA_MIN_CONTEXT_WINDOW
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * Builds and caches [LlmClient] adapters per model provider, and resolves a stored [ModelEntity]
 * into a ready-to-send [LlmRequest] shell (model id + reasoning gear + custom-JSON override).
 *
 * One shared [HttpClient] (Ktor CIO) is reused across all adapters, mirroring [dev.ujhhgtg.wekit.utils.EdgeTtsClient]'s
 * singleton pattern. A long read timeout accommodates slow streaming completions.
 */
object ModelProviderManager {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 600_000
            socketTimeoutMillis = 600_000
            connectTimeoutMillis = 30_000
        }
        // Upstream 09-19: WeAgent now sends the headers gateways expect. opencode (and several
        // other OpenAI-compatible proxies) refuse to open an SSE stream unless the request
        // advertises `Accept: text/event-stream`, and some reject requests without a User-Agent
        // outright. Clients may still override these per request.
        install(DefaultRequest) {
            header(HttpHeaders.UserAgent, "WeKit/${BuildConfig.VERSION_NAME}")
            header(HttpHeaders.Accept, "text/event-stream, application/json")
        }
    }

    // providerId -> adapter, rebuilt when the provider's config changes.
    private val clientCache = HashMap<String, CachedClient>()

    private data class CachedClient(val configHash: Int, val client: LlmClient)

    /**
     * Returns an [LlmClient] for [provider], building one if absent or if the provider's config
     * (type/baseUrl/apiKey) changed since last cached. [provider] must carry a usable apiKey (use
     * [WeAgentRepository.getModelProvider]); keys are stored as-is, so nothing needs decrypting.
     */
    fun clientFor(provider: ModelProviderEntity): LlmClient {
        check(provider.type != ModelProviderType.LOCAL_LLAMA) {
            "local llama clients require a model-specific server lease"
        }
        return cachedClientFor(provider)
    }

    /**
     * Returns a cold local client whose every collected stream resolves the current model file,
     * starts the requested tuple, and exclusively leases that server/port through collection.
     */
    fun localClientFor(
        provider: ModelProviderEntity,
        modelIdRemote: String,
        nCtx: Int,
        backend: String,
    ): LlmClient {
        check(provider.type == ModelProviderType.LOCAL_LLAMA) {
            "localClientFor requires a LOCAL_LLAMA provider"
        }
        return object : LlmClient {
            override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
                val gguf = LocalLlamaModels.resolveModelFile(modelIdRemote)
                    ?: error("local model pack is not installed: $modelIdRemote")
                val lease = LocalLlamaController.acquireServerLease(gguf, nCtx, backend)
                try {
                    cachedClientFor(provider).stream(request).collect { emit(it) }
                } finally {
                    lease.release()
                }
            }
        }
    }

    @Synchronized
    private fun cachedClientFor(provider: ModelProviderEntity): LlmClient {
        val effectiveBase = if (provider.type == ModelProviderType.LOCAL_LLAMA) {
            LocalLlamaController.baseUrlOrNull().orEmpty()
        } else {
            provider.baseUrl
        }
        val hash = provider.type.hashCode() * 31 + effectiveBase.hashCode() * 31 + provider.apiKey.hashCode()
        clientCache[provider.id]?.let { if (it.configHash == hash) return it.client }
        val client = build(provider)
        clientCache[provider.id] = CachedClient(hash, client)
        return client
    }

    private fun build(provider: ModelProviderEntity): LlmClient = when (provider.type) {
        ModelProviderType.OPENAI_CHAT_COMPLETION ->
            OpenAiChatCompletionsClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.OPENAI_RESPONSES ->
            OpenAiResponsesClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.ANTHROPIC_MESSAGES ->
            AnthropicMessagesClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.GEMINI_GENERATE_CONTENT ->
            GeminiGenerateContentClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.GEMINI_INTERACTIONS ->
            GeminiInteractionsClient(httpClient, provider.baseUrl.trimEnd('/'), provider.apiKey)

        ModelProviderType.LOCAL_LLAMA -> {
            val base = LocalLlamaController.baseUrlOrNull()
                ?: error("local llama server is not running")
            OpenAiChatCompletionsClient(httpClient, base, "")
        }
    }

    /**
     * Resolves a [ModelEntity] + a fully-composed message/tool set into an [LlmRequest]. The stored
     * [ModelEntity.reasoningEffort] is passed through literally; the sentinel "off"/"none" and null
     * both mean "omit the field", so callers store a real gear string or null.
     */
    fun buildRequest(
        model: ModelEntity,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        stream: Boolean = true,
    ): LlmRequest {
        val effort = model.reasoningEffort?.takeIf { it.isNotBlank() && it != "off" }
        val override = model.customJsonOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        return LlmRequest(
            modelIdRemote = model.modelIdRemote,
            messages = messages,
            tools = tools,
            reasoningEffort = effort,
            customJsonOverride = override,
            maxTokens = model.maxTokens,
            stream = stream,
        )
    }

    /** Drops a cached adapter (call after a provider is edited/deleted). */
    @Synchronized
    fun invalidate(providerId: String) {
        clientCache.remove(providerId)
    }

    /**
     * Fetches the provider's available model ids. Three list-endpoint shapes are supported:
     *  - **OpenAI** (`OPENAI_CHAT_COMPLETION` / `OPENAI_RESPONSES`):
     *    `GET {baseUrl}/models` → `{ "data": [ { "id": "…" }, … ] }`. Auth: `Authorization: Bearer`.
     *  - **Gemini** (`GEMINI_GENERATE_CONTENT` / `GEMINI_INTERACTIONS`): `GET {baseUrl}/models` →
     *    `{ "models": [ { "name": "models/gemini-…", … }, … ] }`. Auth: `x-goog-api-key`.
     *    We strip the `"models/"` prefix so the stored id matches what goes in the URL / request.
     *  - **Anthropic** (`ANTHROPIC_MESSAGES`): no public list endpoint; returns a failure.
     *
     * [provider] must carry a decrypted API key.
     */
    suspend fun listRemoteModels(provider: ModelProviderEntity): Result<List<String>> {
        if (provider.type == ModelProviderType.LOCAL_LLAMA) {
            return Result.success(LocalLlamaModels.listInstalled().map { it.id })
        }
        if (provider.type == ModelProviderType.ANTHROPIC_MESSAGES) {
            return Result.failure(LlmException("Anthropic 不支持自动获取模型列表，请手动添加。"))
        }
        val isGemini = provider.type == ModelProviderType.GEMINI_GENERATE_CONTENT
                || provider.type == ModelProviderType.GEMINI_INTERACTIONS
        val resolvedBase = provider.baseUrl.trimEnd('/')
        val endpoint = "$resolvedBase/models"
        return runCatching {
            val resp = httpClient.get(endpoint) {
                if (provider.apiKey.isNotBlank()) {
                    if (isGemini) {
                        header(GeminiCommon.API_KEY_HEADER, provider.apiKey)
                    } else {
                        header(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                    }
                }
            }
            if (!resp.status.isSuccess()) {
                throw LlmException("HTTP ${resp.status.value}: ${resp.bodyAsText().take(300)}")
            }
            val root = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            if (isGemini) {
                // Gemini: { "models": [ { "name": "models/gemini-…" }, … ] }
                root["models"]?.jsonArray
                    ?.mapNotNull {
                        it.jsonObject["name"]?.jsonPrimitive?.content
                            ?.removePrefix("models/")
                            ?.takeIf(String::isNotBlank)
                    }
                    ?.distinct()
                    ?.sorted()
                    ?: emptyList()
            } else {
                // OpenAI: { "data": [ { "id": "…" }, … ] }
                root["data"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) }
                    ?.distinct()
                    ?.sorted()
                    ?: emptyList()
            }
        }
    }

    /**
     * Verifies a provider + specific model id actually answers by sending one minimal non-streaming
     * round-trip (a single "ping" user message with a tiny token cap). Returns [Result.success] with
     * the model's reply text on success, or [Result.failure] carrying the [LlmException] message.
     *
     * Local llama providers start (or reuse) the inference server through a lease, exactly like a
     * normal completion, so this also validates that the GGUF pack is installed and runnable.
     */
    suspend fun testConnection(
        provider: ModelProviderEntity,
        modelIdRemote: String,
        apiKey: String? = null,
    ): Result<String> {
        val effectiveProvider = if (apiKey != null) provider.copy(apiKey = apiKey) else provider
        val request = LlmRequest(
            modelIdRemote = modelIdRemote,
            messages = listOf(LlmMessage(role = LlmRole.USER, content = "ping")),
            stream = false,
            maxTokens = 16,
        )
        return runCatching {
            var reply: String? = null
            when (effectiveProvider.type) {
                ModelProviderType.LOCAL_LLAMA -> {
                    val gguf = LocalLlamaModels.resolveModelFile(modelIdRemote)
                        ?: error("local model pack is not installed: $modelIdRemote")
                    localClientFor(
                        provider = effectiveProvider,
                        modelIdRemote = modelIdRemote,
                        nCtx = LocalLlamaModels.defaultContextWindow(modelIdRemote)
                            ?: LOCAL_LLAMA_MIN_CONTEXT_WINDOW,
                        backend = "auto",
                    ).stream(request).collect { event ->
                        when (event) {
                            is LlmStreamEvent.Completed -> reply = event.message.content
                            is LlmStreamEvent.Failed -> throw event.error
                            else -> Unit
                        }
                    }
                }

                else -> {
                    clientFor(effectiveProvider).stream(request).collect { event ->
                        when (event) {
                            is LlmStreamEvent.Completed -> reply = event.message.content
                            is LlmStreamEvent.Failed -> throw event.error
                            else -> Unit
                        }
                    }
                }
            }
            reply ?: "OK"
        }
    }
}
