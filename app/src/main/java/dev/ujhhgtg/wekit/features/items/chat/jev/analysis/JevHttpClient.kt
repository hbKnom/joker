package dev.ujhhgtg.wekit.features.items.chat.jev.analysis

import android.os.SystemClock
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ApiSettings
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jev 协议 HTTP 客户端。Never reports raw service bodies.
 *
 * 合并后新增两件事，直接对着用户实测的「有些消息能出结果、有些出不来」：
 *
 *  1. **全局限速**（[MIN_INTERVAL_MS]）：进入会话时本屏多条消息会同时提交分析，
 *     原来这些请求会一起打出去，撞上免费额度/频率限制（例如 OpenRouter 免费模型
 *     每天 50 次、每分钟若干次）就会出现「一部分成功、一部分 429」。
 *     现在所有请求串行且间隔不小于 [MIN_INTERVAL_MS]，把突发摊平。
 *  2. **可重试失败自动退避重试**（429 / 408 / 5xx / 网络中断）：失败一次不再直接报错，
 *     最多重试 [MAX_RETRIES] 次；仍然失败时，报错文案里会写明「已自动重试 N 次」，
 *     用户能区分「额度/限流」和「配置错误」。
 */
class JevHttpClient {
    /**
     * OkHttp 客户端**懒创建**。
     *
     * 第 16 轮改动：原来写成构造期 `val client = OkHttpClient.Builder()...build()`，
     * 而 [SignalAnalyzer] 这个 object 是在 `YanwaiScanner.install()` 里第一次被访问的 ——
     * 也就是**在宿主 UI 线程的 feature 挂载路径上**。构造 OkHttpClient 要建 ConnectionPool、
     * Dispatcher、连接池清理线程，实测在主线程上是几十毫秒级别的固定开销（冷启动首帧更明显）。
     * 改成 lazy 后，真正的构造推迟到工作线程第一次发请求时，主线程代价 0。
     */
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }

    private val calls = AtomicInteger()

    @Volatile
    private var lastCallAt = 0L

    /**
     * 自适应限速倍率：命中 429 就 ×2（上限 [MAX_ADAPTIVE_FACTOR]），成功后减半回落。
     *
     * 为什么要自适应：用户填的额度/渠道差别很大，固定间隔要么太慢（一屏消息排很久，
     * 看起来像「分析不出来」），要么还是被打回 429（丢一批消息）。命中限流时自动放慢，
     * 限流过去了自动恢复，配合 [ModulePrefs.KEY_REQUEST_INTERVAL] 让用户能自己定基准。
     */
    @Volatile
    private var adaptiveFactor = 1

    /** 本次进程已经发出的请求数（含重试）。设置页用它解释额度与限流。 */
    val requestCount: Int get() = calls.get()

    /** 当前生效的请求间隔（毫秒），设置页展示用。 */
    val currentIntervalMs: Long get() = MIN_INTERVAL_MS * adaptiveFactor

    /** 「等一下就好了」的失败：交给 [exchange] 退避重试。[rateLimited] 用于自适应放慢。 */
    private class Retryable(reason: String, val rateLimited: Boolean = false) :
        IllegalStateException(reason)

    fun exchange(payload: JSONObject, settings: ApiSettings): String {
        check(settings.isConfigured) { "请先填写并保存 API Key" }
        var attempt = 0
        while (true) {
            throttle()
            try {
                val body = post(payload, settings)
                // 成功后逐步把自适应倍率降回去，最终回到用户设定的基准间隔。
                if (adaptiveFactor > 1) adaptiveFactor /= 2
                return body
            } catch (e: Retryable) {
                if (e.rateLimited && adaptiveFactor < MAX_ADAPTIVE_FACTOR) {
                    adaptiveFactor *= 2
                    MoodLog.w("命中限流，请求间隔自适应放慢到 ${MIN_INTERVAL_MS * adaptiveFactor}ms")
                }
                if (attempt >= MAX_RETRIES) {
                    throw IllegalStateException("${e.message}（已自动重试 $MAX_RETRIES 次仍未成功）")
                }
                attempt++
                val rest = RETRY_BACKOFF_MS * attempt
                MoodLog.w("请求失败，${rest}ms 后第 $attempt 次重试：${e.message}")
                runCatching { Thread.sleep(rest) }
            }
        }
    }

    /** 全局限速：串行 + 最小间隔，避免一屏消息把额度一次打光。 */
    @Synchronized
    private fun throttle() {
        val interval = MIN_INTERVAL_MS * adaptiveFactor
        val now = SystemClock.elapsedRealtime()
        val wait = interval - (now - lastCallAt)
        if (wait > 0) runCatching { Thread.sleep(wait) }
        lastCallAt = SystemClock.elapsedRealtime()
    }

    private fun post(payload: JSONObject, settings: ApiSettings): String {
        val request = Request.Builder().url(settings.endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        calls.incrementAndGet()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                val httpOk = response.isSuccessful && json?.has("error") != true
                if (httpOk) return body

                val error = json?.optJSONObject("error")
                val code = if (response.isSuccessful) {
                    error?.optInt("code", response.code) ?: response.code
                } else {
                    response.code
                }
                val reason = when {
                    error?.optString("type") == "customer_verification_required" ->
                        "Vercel 账户尚未验证，请到 AI Gateway 控制台绑定信用卡后重试"
                    code == 401 -> "API Key 无效，请检查所选渠道与密钥是否对应"
                    code == 403 -> "账户或模型尚未授权，请到所选渠道控制台检查"
                    code == 402 -> "模型账户额度不足，请到所选渠道充值或检查额度"
                    code == 429 -> "请求过于频繁或当日免费额度已用尽，请稍后重试或到渠道控制台查看额度"
                    code == 400 || code == 404 || code == 422 -> "接口地址或模型不受支持，请检查渠道和配置"
                    code in 300..399 -> "接口发生重定向，请填写最终的 Jev 接口地址"
                    else -> "模型服务暂不可用，请稍后重试"
                }
                // 429 / 408 / 5xx 是「等一下再来」；其余是配置问题，重试没有意义。
                if (code == 429 || code == 408 || response.code >= 500) {
                    throw Retryable(reason, rateLimited = code == 429)
                }
                throw IllegalStateException("$reason（HTTP ${response.code}）")
            }
        } catch (e: java.io.IOException) {
            throw Retryable("连接超时或网络不可用")
        }
    }

    private companion object {
        /**
         * 请求之间的最小间隔的默认值（毫秒）。真正的取值是
         * [ModulePrefs.KEY_REQUEST_INTERVAL]（用户可调，0..5000），再乘以自适应倍率。
         *
         * 第 14 轮从 1200ms 收到 700ms：每条消息要两轮请求（[JevProtocol.payload] +
         * [JevProtocol.detailPayload]），1.2s 的间隔让一屏 12 条消息要等两分钟，
         * 用户看到的就是「有的行半天不出结果」。700ms 仍能把突发摊平，
         * 真被限流时 [exchange] 还有退避重试 + 自适应放慢兜着。
         *
         * 读设置走的是 [ModulePrefs] 的 [dev.ujhhgtg.wekit.preferences.HotPrefs] 内存缓存
         * （工作线程上调用，无主线程开销、无 SQLite 查询）。
         */
        val MIN_INTERVAL_MS: Long get() = ModulePrefs.requestIntervalMs.toLong()

        /** 自适应放慢的上限倍数：最慢 = 基准间隔 × 8。 */
        const val MAX_ADAPTIVE_FACTOR = 8

        /** 可重试失败的最大重试次数。 */
        const val MAX_RETRIES = 2

        /** 退避基数：第 n 次重试等 n × 该值。 */
        const val RETRY_BACKOFF_MS = 900L
    }
}
