package dev.joker.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tencent.mars.xlog.Log
import dev.joker.reflekt.reflekt
import dev.joker.reflekt.utils.Modifiers
import dev.joker.R
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.preferences.WePrefs
import dev.joker.preferences.WePrefs.Companion.getBoolOrFalse
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.TextButton
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.content.m3.SwitchWidget
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.WeLogger

object RedirectHostLogs : ClickableFeature() {

    override val technicalId = "重定向微信日志"
    override val nameRes = R.string.feature_redirect_host_logs_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_redirect_host_logs_description

    private const val TAG = "RedirectHostLogs"
    private const val KEY_PREFIX = "redirect_"

    /**
     * Safety gate against a host-log storm. WeChat can emit tens of thousands of
     * xlog lines per minute (SignalAnrTracer, ContactStorage, remote-scene jobs…);
     * when host-log redirection is enabled, blindly forwarding every one blows up
     * the Joker log file in minutes, spools CPU/IO, and can crash the app. Forward
     * at most [MAX_PER_TAG_PER_SEC] lines per tag per second — the storm is dropped,
     * the useful signal still reaches the log.
     */
    private const val MAX_PER_TAG_PER_SEC = 30

    /**
     * 【第 52 轮】全局总量闸门（每分钟）。
     *
     * 实机日志（72 包，两份共 37k 行 / 6.9MB）里 **全部** 的 `[E]`/`[W]` 行都是
     * `[HOST]` —— 也就是微信自己的内部日志（`DynamicConfig parseInt failed`、
     * `NewTipsHelper` NPE、`No listener for this event`…），由本功能忠实转发进 Joker 日志。
     * 用户逐份翻日志看到「每个文件都有不同的错误」，实际全是宿主的噪声。
     *
     * 处置：①升级时**一次性关闭全部五级转发**（见 [forceOffOnceOnUpgrade]）；
     * ②即使手动打开，也再压一道全局总量闸门，避免日志与 IO 被宿主的日志风暴带崩。
     */
    private const val MAX_TOTAL_PER_MIN = 600

    /** 一次性迁移标记：保证「升级即静音」只做一次，之后用户自己的选择不被覆盖。 */
    private const val KEY_OFF_ONCE = "redirect_off_once_v2"

    private data class Bucket(val windowStartMillis: Long, val count: Int)

    private var globalWindowStart = 0L
    private var globalCount = 0

    /** 全局闸门：每分钟最多 [MAX_TOTAL_PER_MIN] 行，超出的一条都不写。 */
    private fun withinGlobalBudget(nowMillis: Long): Boolean = synchronized(throttles) {
        if (nowMillis - globalWindowStart >= 60_000L) {
            globalWindowStart = nowMillis
            globalCount = 0
        }
        if (globalCount >= MAX_TOTAL_PER_MIN) return false
        globalCount++
        true
    }

    private val throttles = object : java.util.LinkedHashMap<String, Bucket>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>): Boolean = size > 128
    }

    /** Returns true when this (tag, now) may be forwarded, and records the hit. */
    private fun shouldForward(tag: String, nowMillis: Long): Boolean {
        val window = 1000L
        return synchronized(throttles) {
            val existing = throttles[tag]
            if (existing == null || nowMillis - existing.windowStartMillis >= window) {
                throttles[tag] = Bucket(nowMillis, 1)
                true
            } else if (existing.count < MAX_PER_TAG_PER_SEC) {
                throttles[tag] = existing.copy(count = existing.count + 1)
                true
            } else {
                false
            }
        }
    }

    /** 每行转发前统一走的两道闸门（全局总量 + 单 tag 速率）。 */
    private fun allowed(tag: String, nowMillis: Long): Boolean =
        withinGlobalBudget(nowMillis) && shouldForward(tag, nowMillis)

    /**
     * 【第 52 轮】升级即静音：把五级宿主日志转发一次性全部关掉。
     *
     * 只做一次（[KEY_OFF_ONCE] 标记），所以用户之后在设置页里自己打开的选择不会被再覆盖。
     * 为什么要这么激进：用户两次反馈「运行日志里每个文件几乎都有不同的错误和问题」，
     * 而逐行核查后这些「错误」100% 是微信自身的日志 —— 留着默认开，用户永远会以为模块在报错。
     */
    private fun forceOffOnceOnUpgrade() {
        runCatching {
            if (WePrefs.getBoolOrFalse(KEY_OFF_ONCE)) return
            WePrefs.putBool(KEY_OFF_ONCE, true)
            listOf("v", "d", "i", "w", "e").forEach { WePrefs.putBool("$KEY_PREFIX$it", false) }
            WeLogger.i(
                TAG,
                "已一次性关闭宿主日志转发（v/d/i/w/e）：日志里的 [HOST] 行是微信自身的日志，" +
                    "不是 Joker 的错误。需要时在设置页「重定向微信日志」里按级别重新打开。",
            )
        }
    }

    override fun onEnable() {
        forceOffOnceOnUpgrade()
        // 【第 50 轮】把宿主日志明确标成 [HOST]。
        // 用户实机反馈「运行日志里每个文件几乎都有不同的错误」——逐行核查后，那些 [E] 行
        // 全是**微信自己**的内部日志（DynamicConfig parseInt failed / NewTipsHelper NPE…），
        // 由本功能忠实转发进来，不是 Joker 的错误。加前缀后一眼可分：
        // [HOST] 开头 = 微信自身日志；其余 = Joker 自己的日志。
        WeLogger.i(TAG, "[HOST] 以下带 [HOST] 前缀的行来自微信自身日志（不是 Joker 的错误）")
        Log::class.reflekt().apply {
            if (getBoolOrFalse("${KEY_PREFIX}v"))
                firstMethod {
                    name = "v"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!allowed(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.v(TAG, "[HOST] [V] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}d"))
                firstMethod {
                    name = "d"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!allowed(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.d(TAG, "[HOST] [D] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}i"))
                firstMethod {
                    name = "i"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!allowed(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.i(TAG, "[HOST] [I] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}w"))
                firstMethod {
                    name = "w"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!allowed(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.w(TAG, "[HOST] [W] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}e"))
                firstMethod {
                    name = "e"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!allowed(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.e(TAG, "[HOST] [E] [$tag] $formatString")
                    }
                }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var v by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}v")) }
            var d by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}d")) }
            var i by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}i")) }
            var w by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}w")) }
            var e by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}e")) }
            var dirty by remember { mutableStateOf(false) }

            // 日志级别开关在 onEnable 时决定挂钩哪些方法, 立即写偏好不会刷新已装的 hook;
            // 对话框关闭时统一重启
            DisposableEffect(Unit) {
                onDispose {
                    if (dirty && isActive) {
                        disable()
                        enable()
                    }
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.debug_redirect_host_logs_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.debug_log_level_verbose),
                                checked = v,
                                onCheckedChange = {
                                    v = it
                                    WePrefs.putBool("${KEY_PREFIX}v", it)
                                    dirty = true
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.debug_log_level_debug),
                                checked = d,
                                onCheckedChange = {
                                    d = it
                                    WePrefs.putBool("${KEY_PREFIX}d", it)
                                    dirty = true
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.debug_log_level_info),
                                checked = i,
                                onCheckedChange = {
                                    i = it
                                    WePrefs.putBool("${KEY_PREFIX}i", it)
                                    dirty = true
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.debug_log_level_warning),
                                checked = w,
                                onCheckedChange = {
                                    w = it
                                    WePrefs.putBool("${KEY_PREFIX}w", it)
                                    dirty = true
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.debug_log_level_error),
                                checked = e,
                                onCheckedChange = {
                                    e = it
                                    WePrefs.putBool("${KEY_PREFIX}e", it)
                                    dirty = true
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
