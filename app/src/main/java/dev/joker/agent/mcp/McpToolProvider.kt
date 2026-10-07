package dev.joker.agent.mcp

import dev.joker.BuildConfig
import dev.joker.agent.data.entity.McpTransport
import dev.joker.agent.tool.ProviderKind
import dev.joker.agent.tool.ProviderTool
import dev.joker.agent.tool.ToolProvider
import dev.joker.utils.WeLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Connection state of an [McpToolProvider], surfaced in the settings UI (§4). */
enum class McpConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/**
 * Everything the settings UI shows about one MCP server, in a single immutable snapshot so a
 * composable can observe it with one collector instead of polling three independent fields.
 */
data class McpProviderStatus(
    val state: McpConnectionState = McpConnectionState.DISCONNECTED,
    val lastError: String? = null,
    val tools: List<ProviderTool> = emptyList(),
)

/**
 * One configured MCP server, adapted to the [ToolProvider] contract so it is structurally identical
 * to the builtin provider from the model's perspective (§3.4). Owns its MCP [Client] + transport,
 * caches its `tools/list` result, and reports connection [state]/[lastError] (§4).
 *
 * Reconnection with exponential backoff is orchestrated by [McpClientManager]; this class exposes
 * [connect]/[disconnect]/[refreshTools] as the primitives.
 */
class McpToolProvider(
    override val id: String,
    override val name: String,
    private val transport: McpTransport,
    private val endpointUrl: String,
    private val headers: Map<String, String>,
    private val httpClient: HttpClient,
) : ToolProvider {

    override val kind: ProviderKind = ProviderKind.MCP

    /**
     * Observable connection state + last error + cached tools/list. A [MutableStateFlow] rather than
     * plain `@Volatile` fields because the settings screen reads these from a composable: plain
     * fields never trigger recomposition, so the status line and tool list stayed frozen at whatever
     * they were when the screen was first composed (a successful refresh appeared to do nothing).
     * Updated from the connect/refresh coroutines via atomic [update], so it stays thread-safe.
     */
    private val _status = MutableStateFlow(McpProviderStatus())
    val status: StateFlow<McpProviderStatus> = _status.asStateFlow()

    val state: McpConnectionState get() = _status.value.state
    val lastError: String? get() = _status.value.lastError

    override val isAvailable: Boolean get() = state == McpConnectionState.CONNECTED

    private val connectMutex = Mutex()
    private var client: Client? = null

    /**
     * 连续连接失败次数。
     *
     * 为什么需要它：MCP 服务器没起来时（手机上很常见 —— 这些端点跑在电脑/容器里），
     * 后台重连循环会一直重试，而每次失败都打一条**完整异常栈**到日志文件。
     * 实测一天里 MCP 相关的栈记录上千条，把日志文件刷满并造成无谓的磁盘 IO。
     * 前 [TRACE_FAILURES] 次保留异常栈（足够定位），之后只留一行摘要。
     */
    private val connectFailures = java.util.concurrent.atomic.AtomicInteger()

    private val traceFailures = 2

    /**
     * 【第 52 轮】离线冷却。
     *
     * 实机日志（72 包）里模块自身最吵的一类就是 MCP：
     * `transport error: Connection refused` + `failed to connect MCP server 'x' (第 N 次失败)`
     * 共 5 台服务器 × 每次重连 ~2 行，一天上千行 —— 这些端点跑在用户的电脑/容器上，
     * 手机端永远连不上，于是后台重连循环一直重试（同时白耗网络与电量）。
     *
     * 现在：连续失败到 [OFFLINE_THRESHOLD] 次即进入 [OFFLINE_COOLDOWN_MS] 冷却，
     * 冷却期内**不重连、不打日志**；用户手动刷新/重连（[refreshTools]）会立即解除冷却。
     */
    private val offlineUntil = java.util.concurrent.atomic.AtomicLong(0L)

    private companion object {
        const val TAG = "McpToolProvider"
        const val OFFLINE_THRESHOLD = 5
        const val OFFLINE_COOLDOWN_MS = 5 * 60_000L
    }

    /** Cached tools/list, refreshed on connect and on manual refresh. */
    override fun listTools(): List<ProviderTool> = _status.value.tools

    private fun requestBuilder(): HttpRequestBuilder.() -> Unit {
        val customHeaders = headers
        return { customHeaders.forEach { (k, v) -> headers.append(k, v) } }
    }

    /** Connects (idempotent guard via [connectMutex]) and caches the tool list. */
    suspend fun connect() = connectMutex.withLock {
        if (state == McpConnectionState.CONNECTED) return@withLock
        // 【第 52 轮】冷却期内直接返回：不重连、不打日志（安静地省下网络/电量/日志）。
        if (offlineUntil.get() > System.currentTimeMillis()) return@withLock
        _status.update { it.copy(state = McpConnectionState.CONNECTING, lastError = null) }
        runCatching {
            val t = when (transport) {
                McpTransport.STREAMABLE_HTTP ->
                    StreamableHttpClientTransport(httpClient, endpointUrl, requestBuilder = requestBuilder())

                McpTransport.SSE ->
                    SseClientTransport(httpClient, endpointUrl, requestBuilder = requestBuilder())
            }
            t.onClose { onTransportClosed() }
            t.onError { e -> onTransportError(e) }

            val c = Client(Implementation(name = "joker-mcp-client", version = BuildConfig.VERSION_NAME))
            c.connect(t)
            client = c
            val tools = fetchTools(c)
            _status.value = McpProviderStatus(McpConnectionState.CONNECTED, null, tools)
            connectFailures.set(0)
            WeLogger.i(TAG, "connected to MCP server '$name' ($endpointUrl), ${tools.size} tools")
        }.onFailure { e ->
            _status.update {
                it.copy(
                    state = McpConnectionState.FAILED,
                    lastError = e.message ?: e.javaClass.simpleName,
                )
            }
            val failures = connectFailures.incrementAndGet()
            when {
                failures <= traceFailures -> WeLogger.e(TAG, "failed to connect MCP server '$name'", e)
                failures >= OFFLINE_THRESHOLD -> {
                    // 到阈值：进入冷却并**只打一行**，之后冷却期内完全静音。
                    offlineUntil.set(System.currentTimeMillis() + OFFLINE_COOLDOWN_MS)
                    WeLogger.w(
                        TAG,
                        "MCP server '$name' 连续失败 $failures 次，暂停自动重连 ${OFFLINE_COOLDOWN_MS / 60_000} 分钟" +
                            "（在设置页手动重连可立即恢复）：${e.message}",
                    )
                }
                failures % 5 == 0 -> WeLogger.w(TAG, "failed to connect MCP server '$name' (第 $failures 次失败): ${e.message}")
                else -> Unit
            }
            runCatching { client?.close() }
            client = null
        }
    }

    suspend fun disconnect() = connectMutex.withLock {
        runCatching { client?.close() }
        client = null
        _status.value = McpProviderStatus(McpConnectionState.DISCONNECTED)
    }

    /** Re-fetches tools/list from a connected server. No-op if disconnected. */
    suspend fun refreshTools(): Boolean = connectMutex.withLock {
        // 【第 52 轮】手动刷新视作「我要立刻再试一次」：解除离线冷却与失败计数。
        offlineUntil.set(0L)
        connectFailures.set(0)
        val c = client ?: return@withLock false
        runCatching {
            val tools = fetchTools(c)
            _status.update { it.copy(tools = tools) }
            true
        }.getOrElse {
            WeLogger.w(TAG, "refreshTools failed for '$name'", it); false
        }
    }

    private suspend fun fetchTools(c: Client): List<ProviderTool> =
        c.listTools().tools.map { tool ->
            ProviderTool(
                name = tool.name,
                description = tool.description ?: "",
                jsonSchema = buildSchema(tool.inputSchema.properties, tool.inputSchema.required),
                // Remote tools count as side-effecting, like side-effecting built-ins: the server
                // alone decides what each tool does, and its name/description go verbatim into the
                // model's context — so with a MESSAGE trigger someone else's chat message could
                // otherwise drive a destructive tool with no approval card under permissive levels.
                sideEffect = true,
            )
        }

    private fun buildSchema(properties: JsonObject?, required: List<String>?): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties ?: JsonObject(emptyMap()))
        if (required != null) {
            put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): String {
        val c = client ?: return "MCP server '$name' is not connected."
        return runCatching {
            val result = c.callTool(name = toolName, arguments = arguments.toPlainMap())
            val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (result.isError == true) "工具调用返回错误：$text" else text.ifEmpty { "(no textual content)" }
        }.getOrElse { "MCP tool '$toolName' failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** MCP callTool takes Map<String, Any?>; flatten our JsonObject to plain Kotlin values. */
    private fun JsonObject.toPlainMap(): Map<String, Any?> = mapValues { (_, v) -> McpJsonBridge.toPlain(v) }

    private fun onTransportClosed() {
        val changed = _status.getAndUpdate {
            if (it.state == McpConnectionState.CONNECTED) it.copy(state = McpConnectionState.DISCONNECTED) else it
        }.state == McpConnectionState.CONNECTED
        if (changed) WeLogger.w(TAG, "MCP server '$name' transport closed")
    }

    private fun onTransportError(e: Throwable) {
        val message = e.message ?: e.javaClass.simpleName
        _status.update { it.copy(lastError = message) }
        WeLogger.w(TAG, "MCP server '$name' transport error: $message")
    }
}
