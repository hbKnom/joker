package dev.ujhhgtg.wekit.features.items.debug

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
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.getBoolOrFalse
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

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
     * the WeKit log file in minutes, spools CPU/IO, and can crash the app. Forward
     * at most [MAX_PER_TAG_PER_SEC] lines per tag per second — the storm is dropped,
     * the useful signal still reaches the log.
     */
    private const val MAX_PER_TAG_PER_SEC = 30

    private data class Bucket(val windowStartMillis: Long, val count: Int)

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

    override fun onEnable() {
        Log::class.reflekt().apply {
            if (getBoolOrFalse("${KEY_PREFIX}v"))
                firstMethod {
                    name = "v"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!shouldForward(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.v(TAG, "[V] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}d"))
                firstMethod {
                    name = "d"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!shouldForward(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.d(TAG, "[D] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}i"))
                firstMethod {
                    name = "i"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!shouldForward(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.i(TAG, "[I] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}w"))
                firstMethod {
                    name = "w"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!shouldForward(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.w(TAG, "[W] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}e"))
                firstMethod {
                    name = "e"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    val tag = args[0] as? String ?: return@hookBefore
                    if (!shouldForward(tag, System.currentTimeMillis())) return@hookBefore
                    runCatching {
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.e(TAG, "[E] [$tag] $formatString")
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
