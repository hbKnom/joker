package dev.ujhhgtg.wekit.features.items.chat.jev

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Autorenew
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Compare_arrows
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete_sweep
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Person_search
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Restart_alt
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Send
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlined.Warning
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.ChatAnalysisUi
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.ChatInsights
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ApiProfiles
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ApiSettings
import dev.ujhhgtg.wekit.features.items.chat.jev.core.JevProvider
import dev.ujhhgtg.wekit.features.items.chat.jev.core.JevText
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.MoodMessageChannel
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.YanwaiScanner
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.ConversationPickerSection
import dev.ujhhgtg.wekit.ui.utils.rememberAllConversations
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 潜语（合并后的聊天分析决策）设置页。
 *
 * 上游 wechatmood 是独立 APK，设置页要同时承担「配置」和「跨进程下发配置给微信」两件事，
 * 所以有状态概览、连接检测、日志导出、使用引导等一整套。本模块设置与 hook 同进程，
 * 保存即生效。
 *
 * 第 17 轮把这一页按「用户排查问题的顺序」重新分节，并把原来散在开关堆里的东西收到
 * 该在的地方（编号在同一弹窗里也用于其他地方，这里沿用同一套视觉）：
 *  1. 总开关（分析 / 气泡卡 / 回插会话 / 也分析我发的 / 诊断）+ 「N/M 已开」计数徽章
 *  2. 分析范围（全部聊天 / 仅选定聊天 + 会话选择器）
 *  3. 卡片扩展（建议强度 / 互动均衡 / 话题标签 / 情绪趋势）
 *  4. 卡片外观（**卡片预览** + 默认展开 + 与前几句对比）—— 预览随开关实时变
 *  5. 上下文与回插（回插新鲜度 + 上下文条数，都带范围说明）
 *  6. 上限与性能（单条上限 / 队列上限 / 请求间隔 + 一行「当前」实测值）
 *  7. 渠道与密钥（沿用上游 `channel_{id}_{key,endpoint,model}` 键位，便于迁移）
 *  8. 运行状态与操作（已发请求数、成功/失败/缓存/排队 + 五个动作按钮）
 * 末尾是「最近解读」流水与「按人查看历史」——用户反馈「有些消息能出结果、有些不行」时，
 * 这一段能直接看出是额度/限流还是配置问题。
 *
 * 控件约定（WeKit 侧）：
 *  - 对话框正文必须走 [AlertDialogContent] 的 `text = { … }` 槽位（该函数的尾参是间距 Dp，
 *    不能用尾随 lambda）；
 *  - 开关用 [SwitchWidget]（需要 `title`），单选列表用 [RadioButtonWidget]（单选语义 + 无障碍 role）；
 *  - 灰底提示条用 [Banner]（运行状态 / 操作反馈各一处，失败态走告警色）；
 *  - 卡片长什么样：这里用 [CardPreview] 画一份「示意卡」，**随下面几个开关实时变化**，
 *    不必真的插到聊天里试。
 */
object YanwaiSettings {

    /** 提示条语气：普通说明 / 需要看一眼的失败。 */
    private enum class Tone { Info, Warning }

    fun show(context: Context) {
        showComposeDialog(context) {
            var enabled by remember { mutableStateOf(ModulePrefs.enabled) }
            var showBadge by remember { mutableStateOf(ModulePrefs.showBadge) }
            var displayMessage by remember { mutableStateOf(ModulePrefs.displayMessage) }
            var analyzeSelf by remember { mutableStateOf(ModulePrefs.analyzeSelf) }
            var cardExpanded by remember { mutableStateOf(ModulePrefs.cardExpanded) }
            var showTrend by remember { mutableStateOf(ModulePrefs.showTrend) }
            // 卡片扩展块（建议强度 / 互动均衡 / 话题 / 情绪趋势）：默认全开，可逐项关掉
            var showLevel by remember { mutableStateOf(ModulePrefs.showLevel) }
            var showBalance by remember { mutableStateOf(ModulePrefs.showBalance) }
            var showTopics by remember { mutableStateOf(ModulePrefs.showTopics) }
            var showTrendPanel by remember { mutableStateOf(ModulePrefs.showTrendPanel) }
            var insertFreshText by remember {
                mutableStateOf(ModulePrefs.insertFreshSeconds.toString())
            }
            // 上限与性能：三个数字都做成「填完点保存才生效」，范围写在 supportingText 里
            var maxCharsText by remember { mutableStateOf(ModulePrefs.maxChars.toString()) }
            var queueCapText by remember { mutableStateOf(ModulePrefs.queueCap.toString()) }
            var intervalText by remember { mutableStateOf(ModulePrefs.requestIntervalMs.toString()) }
            var explore by remember { mutableStateOf(ModulePrefs.exploreMode) }
            var scopeAll by remember { mutableStateOf(ModulePrefs.scopeAll) }
            var contextLimitText by remember { mutableStateOf(ModulePrefs.contextLimit.toString()) }
            var selectedTalkers by remember { mutableStateOf(ModulePrefs.scopeTalkers) }
            var notice by remember { mutableStateOf("") }
            var noticeTone by remember { mutableStateOf(Tone.Info) }
            var refreshKey by remember { mutableStateOf(0) }
            var recent by remember { mutableStateOf(MoodStore.recent()) }
            var runtime by remember { mutableStateOf(runtimeLine(context)) }
            // 「按人查看历史」：同一弹窗内展开，避免再套一层对话框
            var showHistory by remember { mutableStateOf(false) }

            // 会话标题表：选择器需要它才能把 wxId 存成「能看懂的名字」（存名字是为了在
            // 会话改名/无法查库时仍能显示）。加载走 IO 线程，与选择器共用同一份数据。
            val (options, _) = rememberAllConversations(refreshKey)
            val names = remember(options) { options.associate { it.wxId to it.title } }

            val initialProvider = JevProvider.resolve(
                WePrefs.getStringOrDef(ModulePrefs.KEY_API_PROVIDER, ""),
                WePrefs.getStringOrDef(ModulePrefs.KEY_API_BASE, ""),
            )
            var provider by remember { mutableStateOf(initialProvider) }
            var endpoint by remember {
                mutableStateOf(
                    WePrefs.getStringOrDef("channel_${initialProvider.id}_endpoint", "").ifBlank {
                        if (initialProvider == JevProvider.CUSTOM) "" else initialProvider.endpoint
                    },
                )
            }
            var model by remember {
                mutableStateOf(
                    WePrefs.getStringOrDef("channel_${initialProvider.id}_model", "").ifBlank {
                        initialProvider.model
                    },
                )
            }
            var apiKey by remember {
                mutableStateOf(WePrefs.getStringOrDef("channel_${initialProvider.id}_key", ""))
            }

            fun switchProvider(next: JevProvider) {
                provider = next
                endpoint = WePrefs.getStringOrDef("channel_${next.id}_endpoint", "").ifBlank {
                    if (next == JevProvider.CUSTOM) "" else next.endpoint
                }
                model = WePrefs.getStringOrDef("channel_${next.id}_model", "").ifBlank { next.model }
                apiKey = WePrefs.getStringOrDef("channel_${next.id}_key", "")
            }

            /** 记一条操作反馈（成功/说明用 Info，填错/失败用 Warning，颜色跟着语气走）。 */
            fun tell(message: String, tone: Tone = Tone.Info) {
                notice = message
                noticeTone = tone
            }

            /** 把表单里的值写进 WePrefs；「检测连接」也先走它，保证测的是**保存后**的配置。 */
            fun persist(): Boolean {
                val rangeNotice = { min: Int, max: Int ->
                    JevText.of(context, R.string.jev_notice_bad_number, min, max)
                }
                val limit = contextLimitText.trim().toIntOrNull()?.coerceIn(0, ModulePrefs.MAX_CONTEXT_LIMIT)
                if (limit == null) {
                    tell(rangeNotice(0, ModulePrefs.MAX_CONTEXT_LIMIT), Tone.Warning)
                    return false
                }
                val fresh = insertFreshText.trim().toIntOrNull()
                    ?.coerceIn(ModulePrefs.MIN_INSERT_FRESH_SECONDS, ModulePrefs.MAX_INSERT_FRESH_SECONDS)
                if (fresh == null) {
                    tell(
                        rangeNotice(
                            ModulePrefs.MIN_INSERT_FRESH_SECONDS,
                            ModulePrefs.MAX_INSERT_FRESH_SECONDS,
                        ),
                        Tone.Warning,
                    )
                    return false
                }
                val maxChars = maxCharsText.trim().toIntOrNull()
                    ?.coerceIn(ModulePrefs.MIN_MAX_CHARS, ModulePrefs.MAX_MAX_CHARS)
                if (maxChars == null) {
                    tell(rangeNotice(ModulePrefs.MIN_MAX_CHARS, ModulePrefs.MAX_MAX_CHARS), Tone.Warning)
                    return false
                }
                val queueCap = queueCapText.trim().toIntOrNull()
                    ?.coerceIn(ModulePrefs.MIN_QUEUE_CAP, ModulePrefs.MAX_QUEUE_CAP)
                if (queueCap == null) {
                    tell(rangeNotice(ModulePrefs.MIN_QUEUE_CAP, ModulePrefs.MAX_QUEUE_CAP), Tone.Warning)
                    return false
                }
                val interval = intervalText.trim().toIntOrNull()
                    ?.coerceIn(ModulePrefs.MIN_REQUEST_INTERVAL_MS, ModulePrefs.MAX_REQUEST_INTERVAL_MS)
                if (interval == null) {
                    tell(
                        rangeNotice(
                            ModulePrefs.MIN_REQUEST_INTERVAL_MS,
                            ModulePrefs.MAX_REQUEST_INTERVAL_MS,
                        ),
                        Tone.Warning,
                    )
                    return false
                }
                val settings = ApiSettings.fromInput(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    providerId = provider.id,
                    model = model,
                )
                // `read` 必须能返回 null：ApiProfiles 用它判断「这个渠道还没有独立键」，
                // 从而把 1.0.x 的全局配置迁移过去。传 getStringOrDef("") 会让判断永远为假。
                ApiProfiles.valuesToSave(settings) { WePrefs.getString(it) }
                    .forEach { (k, v) -> WePrefs.putString(k, v) }
                ModulePrefs.setSwitch(ModulePrefs.KEY_ENABLED, enabled)
                ModulePrefs.setSwitch(ModulePrefs.KEY_SHOW_BADGE, showBadge)
                ModulePrefs.setDisplayMessage(displayMessage)
                ModulePrefs.setAnalyzeSelf(analyzeSelf)
                ModulePrefs.setCardExpanded(cardExpanded)
                ModulePrefs.setShowTrend(showTrend)
                ModulePrefs.setShowLevel(showLevel)
                ModulePrefs.setShowBalance(showBalance)
                ModulePrefs.setShowTopics(showTopics)
                ModulePrefs.setShowTrendPanel(showTrendPanel)
                ModulePrefs.setInsertFreshSeconds(fresh)
                ModulePrefs.setContextLimit(limit)
                ModulePrefs.setMaxChars(maxChars)
                ModulePrefs.setQueueCap(queueCap)
                ModulePrefs.setRequestInterval(interval)
                ModulePrefs.setScope(
                    all = scopeAll,
                    talkers = if (scopeAll) emptySet() else selectedTalkers,
                    names = if (scopeAll) emptyMap() else selectedTalkers.associateWith { names[it] ?: it },
                )
                WePrefs.putBool(ModulePrefs.KEY_EXPLORE, explore)
                // WePrefs 的 SQLite 实现每次 put 即落库（save() 只是 commit 的空实现），
                // 这里不需要再调 save()。
                MoodLog.i("设置已保存：provider=${settings.provider.id} model=${settings.model}")
                return true
            }

            fun refreshRuntime() {
                recent = MoodStore.recent()
                runtime = runtimeLine(context)
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_yanwai_name)) },
                confirmButton = {
                    Button(onClick = {
                        runCatching {
                            if (persist()) {
                                ModulePrefs.reload()
                                YanwaiScanner.refresh()
                            }
                        }.onFailure {
                            MoodLog.e("保存失败", it)
                            tell(
                                JevText.of(context, R.string.jev_notice_save_failed, it.message.orEmpty()),
                                Tone.Warning,
                            )
                        }
                        if (notice.isEmpty()) onDismiss()
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss() }) {
                        Text(stringResource(R.string.action_close))
                    }
                },
                text = {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                        // ---------------------------------------------------------- 1 总开关
                        item {
                            ChatAnalysisUi.SectionHeader(
                                title = stringResource(R.string.jev_settings_section_switch),
                                index = 1,
                                badge = stringResource(
                                    R.string.jev_settings_count,
                                    listOf(enabled, showBadge, displayMessage, analyzeSelf, explore)
                                        .count { it },
                                    5,
                                ),
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = stringResource(R.string.yanwai_enable),
                                description = stringResource(R.string.yanwai_enable_desc),
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Chat,
                                title = stringResource(R.string.yanwai_show_badge),
                                description = stringResource(R.string.yanwai_show_badge_desc),
                                checked = showBadge,
                                onCheckedChange = { showBadge = it },
                                trailingDivider = true,
                            )
                        }
                        // 原来独立成「Jev 聊天决策实时分析」那个功能，现在只是本功能的一个展示通道
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Send,
                                title = stringResource(R.string.yanwai_display_message),
                                description = stringResource(R.string.yanwai_display_message_desc),
                                checked = displayMessage,
                                onCheckedChange = { displayMessage = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Person,
                                title = stringResource(R.string.yanwai_analyze_self),
                                description = stringResource(R.string.yanwai_analyze_self_desc),
                                checked = analyzeSelf,
                                onCheckedChange = { analyzeSelf = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Bug_report,
                                title = stringResource(R.string.yanwai_explore_mode),
                                description = stringResource(R.string.yanwai_explore_mode_desc),
                                checked = explore,
                                onCheckedChange = { explore = it },
                                trailingDivider = true,
                            )
                        }

                        // ---------------------------------------------------------- 2 分析范围
                        item {
                            ChatAnalysisUi.SectionHeader(
                                title = stringResource(R.string.yanwai_scope),
                                index = 2,
                                badge = if (scopeAll) {
                                    stringResource(R.string.jev_settings_count, 0, 0).let { null }
                                } else {
                                    stringResource(R.string.yanwai_scope_summary, selectedTalkers.size)
                                },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                icon = MaterialSymbols.Outlined.Groups,
                                title = stringResource(R.string.yanwai_scope_all),
                                description = stringResource(R.string.yanwai_scope_all_desc),
                                selected = scopeAll,
                                onClick = { scopeAll = true },
                                trailingDivider = true,
                            )
                        }
                        item {
                            RadioButtonWidget(
                                icon = MaterialSymbols.Outlined.Person_search,
                                title = stringResource(R.string.yanwai_scope_pick),
                                description = if (scopeAll) {
                                    stringResource(R.string.yanwai_scope_pick_desc)
                                } else {
                                    stringResource(R.string.yanwai_scope_summary, selectedTalkers.size)
                                },
                                selected = !scopeAll,
                                onClick = { scopeAll = false },
                                trailingDivider = true,
                            )
                        }
                        if (!scopeAll) {
                            item {
                                ConversationPickerSection(
                                    selected = selectedTalkers,
                                    onToggle = { wxId, on ->
                                        selectedTalkers = if (on) {
                                            selectedTalkers + wxId
                                        } else {
                                            selectedTalkers - wxId
                                        }
                                        // 名字表只用于「已选 N 个」的可读性，取不到就退回 wxId
                                        names[wxId]?.let { MoodLog.i("潜语范围已选：$it") }
                                    },
                                    refreshKey = refreshKey,
                                )
                            }
                        }

                        // ---------------------------------------------------------- 3 卡片扩展
                        item {
                            ChatAnalysisUi.SectionHeader(
                                title = stringResource(R.string.jev_settings_section_ext),
                                index = 3,
                                badge = stringResource(
                                    R.string.jev_settings_count,
                                    listOf(showLevel, showBalance, showTopics, showTrendPanel)
                                        .count { it },
                                    4,
                                ),
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Warning,
                                title = stringResource(R.string.jev_ext_level_enable),
                                description = stringResource(R.string.jev_ext_level_enable_desc),
                                checked = showLevel,
                                onCheckedChange = { showLevel = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Groups,
                                title = stringResource(R.string.jev_ext_balance_enable),
                                description = stringResource(R.string.jev_ext_balance_enable_desc),
                                checked = showBalance,
                                onCheckedChange = { showBalance = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Tune,
                                title = stringResource(R.string.jev_ext_topics_enable),
                                description = stringResource(
                                    R.string.jev_ext_topics_enable_desc,
                                    ChatInsights.MAX_TOPICS,
                                ),
                                checked = showTopics,
                                onCheckedChange = { showTopics = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.History,
                                title = stringResource(R.string.jev_ext_trend_enable),
                                description = stringResource(R.string.jev_ext_trend_enable_desc),
                                checked = showTrendPanel,
                                onCheckedChange = { showTrendPanel = it },
                                trailingDivider = true,
                            )
                        }

                        // ---------------------------------------------------------- 4 卡片外观
                        item {
                            ChatAnalysisUi.SectionHeader(
                                title = stringResource(R.string.jev_settings_section_card),
                                index = 4,
                                badge = stringResource(
                                    R.string.jev_settings_count,
                                    listOf(cardExpanded, showTrend).count { it },
                                    2,
                                ),
                            )
                        }
                        item {
                            CardPreview(
                                showLevel = showLevel,
                                showBalance = showBalance,
                                showTopics = showTopics,
                                showTrendPanel = showTrendPanel,
                                showTrend = showTrend,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Expand_more,
                                title = stringResource(R.string.yanwai_card_expanded),
                                description = stringResource(R.string.yanwai_card_expanded_desc),
                                checked = cardExpanded,
                                onCheckedChange = { cardExpanded = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Compare_arrows,
                                title = stringResource(R.string.yanwai_show_trend),
                                description = stringResource(R.string.yanwai_show_trend_desc),
                                checked = showTrend,
                                onCheckedChange = { showTrend = it },
                                trailingDivider = true,
                            )
                        }

                        // ---------------------------------------------------------- 5 上下文与回插
                        item {
                            ChatAnalysisUi.SectionHeader(
                                title = stringResource(R.string.jev_settings_section_context),
                                index = 5,
                                badge = stringResource(R.string.yanwai_scope_summary, ModulePrefs.contextLimit),
                            )
                        }
                        // 只在真的开着「回插会话」时才需要它：窗口越小越安静
                        if (displayMessage) {
                            item {
                                OutlinedTextField(
                                    value = insertFreshText,
                                    onValueChange = { insertFreshText = it.filter(Char::isDigit).take(4) },
                                    label = { Text(stringResource(R.string.yanwai_insert_fresh)) },
                                    supportingText = { Text(stringResource(R.string.yanwai_insert_fresh_desc)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = contextLimitText,
                                onValueChange = { contextLimitText = it.filter(Char::isDigit).take(2) },
                                label = { Text(stringResource(R.string.yanwai_context_limit)) },
                                supportingText = { Text(stringResource(R.string.yanwai_context_limit_desc)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        // ---------------------------------------------------------- 6 上限与性能
                        item {
                            ChatAnalysisUi.SectionHeader(
                                title = stringResource(R.string.jev_settings_section_perf),
                                index = 6,
                                badge = JevText.of(
                                    context,
                                    R.string.jev_perf_summary,
                                    SignalAnalyzer.pendingDepth,
                                    SignalAnalyzer.currentIntervalMs,
                                    SignalAnalyzer.queuedDepth,
                                    ModulePrefs.queueCap,
                                    ModulePrefs.maxChars,
                                ),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = maxCharsText,
                                onValueChange = { maxCharsText = it.filter(Char::isDigit).take(4) },
                                label = { Text(stringResource(R.string.jev_perf_max_chars)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            R.string.jev_perf_max_chars_desc,
                                            ModulePrefs.MIN_MAX_CHARS,
                                            ModulePrefs.MAX_MAX_CHARS,
                                        ),
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = queueCapText,
                                onValueChange = { queueCapText = it.filter(Char::isDigit).take(3) },
                                label = { Text(stringResource(R.string.jev_perf_queue_cap)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            R.string.jev_perf_queue_cap_desc,
                                            ModulePrefs.MIN_QUEUE_CAP,
                                            ModulePrefs.MAX_QUEUE_CAP,
                                        ),
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = intervalText,
                                onValueChange = { intervalText = it.filter(Char::isDigit).take(4) },
                                label = { Text(stringResource(R.string.jev_perf_interval)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            R.string.jev_perf_interval_desc,
                                            ModulePrefs.MIN_REQUEST_INTERVAL_MS,
                                            ModulePrefs.MAX_REQUEST_INTERVAL_MS,
                                        ),
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        // ---------------------------------------------------------- 7 渠道与密钥
                        item {
                            ChatAnalysisUi.SectionHeader(
                                title = stringResource(R.string.yanwai_provider),
                                index = 7,
                                badge = provider.label,
                            )
                        }
                        items(JevProvider.entries.toList()) { candidate ->
                            RadioButtonWidget(
                                icon = MaterialSymbols.Outlined.Bolt,
                                title = candidate.label,
                                description = if (candidate == JevProvider.CUSTOM) {
                                    stringResource(R.string.yanwai_provider_custom_desc)
                                } else {
                                    candidate.endpoint
                                },
                                selected = provider == candidate,
                                onClick = { switchProvider(candidate) },
                                trailingDivider = true,
                            )
                        }

                        if (provider == JevProvider.CUSTOM) {
                            item {
                                OutlinedTextField(
                                    value = endpoint,
                                    onValueChange = { endpoint = it },
                                    label = { Text(stringResource(R.string.yanwai_endpoint)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                label = { Text(stringResource(R.string.yanwai_model)) },
                                singleLine = true,
                                enabled = provider == JevProvider.CUSTOM,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text(stringResource(R.string.yanwai_api_key)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        // ---------------------------------------------------------- 8 运行状态与动作
                        item {
                            ChatAnalysisUi.SectionHeader(
                                title = stringResource(R.string.jev_settings_section_runtime),
                                index = 8,
                                badge = stringResource(R.string.yanwai_scope_summary, ModulePrefs.contextLimit),
                            )
                        }
                        item { Banner(text = runtime, tone = Tone.Info) }
                        if (notice.isNotEmpty()) {
                            item { Banner(text = notice, tone = noticeTone) }
                        }
                        item {
                            ActionButton(
                                icon = MaterialSymbols.Outlined.Refresh,
                                label = stringResource(R.string.yanwai_test_connection),
                                onClick = {
                                    runCatching { if (persist()) ModulePrefs.reload() }
                                        .onFailure {
                                            tell(
                                                JevText.of(
                                                    context,
                                                    R.string.jev_notice_save_failed,
                                                    it.message.orEmpty(),
                                                ),
                                                Tone.Warning,
                                            )
                                        }
                                    tell(JevText.of(context, R.string.jev_notice_testing))
                                    SignalAnalyzer.testConnection { ok, message ->
                                        tell(message)
                                        refreshRuntime()
                                        if (ok) MoodLog.i("潜语连接检测通过")
                                    }
                                },
                            )
                        }
                        item {
                            // 立即对「当前屏幕上可见的消息」重跑一遍（不必等下一次滚动/新消息）。
                            ActionButton(
                                icon = MaterialSymbols.Outlined.Autorenew,
                                label = stringResource(R.string.yanwai_analyse_now),
                                onClick = {
                                    YanwaiScanner.reanalyzeVisible()
                                    refreshKey++
                                    refreshRuntime()
                                    tell(JevText.of(context, R.string.jev_notice_reanalysed))
                                },
                            )
                        }
                        item {
                            ActionButton(
                                icon = MaterialSymbols.Outlined.Restart_alt,
                                label = stringResource(R.string.yanwai_retry_failed),
                                onClick = {
                                    val cleared = SignalAnalyzer.retryAllFailures()
                                    YanwaiScanner.reanalyseFailed()
                                    refreshRuntime()
                                    if (cleared == 0) {
                                        tell(JevText.of(context, R.string.jev_notice_no_failure))
                                    } else {
                                        tell(JevText.of(context, R.string.jev_notice_retried, cleared))
                                    }
                                },
                            )
                        }
                        item {
                            // 「清空」是不可逆的：单独给一档告警配色，避免误点
                            ActionButton(
                                icon = MaterialSymbols.Outlined.Delete_sweep,
                                label = stringResource(R.string.yanwai_clear_results),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                                onClick = {
                                    // 必须先数再清：清完再读 size() 永远是 0（此前这里显示「已清 0 条」）
                                    val before = MoodStore.size()
                                    SignalAnalyzer.clearResults()
                                    refreshRuntime()
                                    tell(JevText.of(context, R.string.jev_notice_cleared, before))
                                    YanwaiScanner.reanalyzeVisible()
                                },
                            )
                        }

                        // ---------------------------------------------------------- 9 历史与统计
                        item {
                            ChatAnalysisUi.SectionHeader(
                                title = stringResource(R.string.jev_settings_section_history),
                                index = 9,
                                badge = if (recent.isEmpty()) null else recent.size.toString(),
                            )
                        }
                        item {
                            ActionButton(
                                icon = MaterialSymbols.Outlined.Content_copy,
                                label = stringResource(R.string.yanwai_export),
                                onClick = {
                                    val entries = MoodStore.recent()
                                    if (entries.isEmpty()) {
                                        tell(context.getString(R.string.yanwai_export_empty))
                                    } else {
                                        copyToClipboard(
                                            context,
                                            JevText.of(context, R.string.jev_clip_label),
                                            exportText(context, entries),
                                        )
                                        tell(
                                            context.getString(R.string.yanwai_export_done, entries.size),
                                        )
                                    }
                                },
                            )
                        }
                        if (recent.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.yanwai_recent_empty),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            items(recent) { entry ->
                                val name = names[entry.talker] ?: entry.talker.takeLast(10)
                                val detail = if (entry.ok) {
                                    entry.label
                                } else {
                                    JevText.of(context, R.string.jev_export_failed, entry.note.orEmpty())
                                }
                                Text(
                                    text = JevText.of(
                                        context,
                                        R.string.jev_recent_line,
                                        stamp(entry.at),
                                        name,
                                        detail,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (entry.ok) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                )
                            }
                        }
                        item {
                            TextButton(
                                onClick = { showHistory = !showHistory },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                Icon(
                                    MaterialSymbols.Outlined.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.jev_history_view))
                            }
                        }
                        if (showHistory) {
                            item { HistorySection(context = context, recent = recent, names = names, onTell = ::tell) }
                            item {
                                TextButton(
                                    onClick = { showHistory = false },
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                ) {
                                    Text(stringResource(R.string.jev_history_close))
                                }
                            }
                        }

                        item {
                            Text(
                                text = stringResource(R.string.yanwai_privacy),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                },
            )
        }
    }

    /**
     * 「按人查看历史」：把最近流水按对方分组，给每人一行「分析了几条 / 最近一次 / 成功失败缓存」
     * 和一个「复制这个人的解读」。
     *
     * 只读 [MoodStore] 的既有接口（同 [exportText]），不碰协议与数据层；复用同一弹窗内的
     * 内联展开，避免嵌套对话框。
     */
    @Composable
    private fun HistorySection(
        context: Context,
        recent: List<MoodStore.Entry>,
        names: Map<String, String>,
        onTell: (String) -> Unit,
    ) {
        // 本分区由调用方用 item { } 放进 LazyColumn，自身是一个普通 Column：
        // 组数最多十几组、每组一行，一次性铺开比再嵌一层懒加载更省事，也不会踩
        // LazyListScope 的接收者作用域（items 只能在 LazyColumn 的 scope 里调用）。
        val grouped = remember(recent) { recent.groupBy { it.talker } }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.jev_history_title, grouped.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (grouped.isEmpty()) {
                Text(
                    text = stringResource(R.string.jev_history_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                grouped.forEach { (talker, entries) ->
                    val label = names[talker] ?: talker.takeLast(10)
                    val ok = entries.count { it.ok }
                    val cached = entries.count { MoodStore.get(it.key) != null }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.jev_history_person_line,
                                label,
                                entries.size,
                                stamp(entries.first().at),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.jev_history_stats, ok, entries.size - ok, cached),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        entries.filter { !it.ok }.take(3).forEach { entry ->
                            Text(
                                text = stringResource(
                                    R.string.jev_history_fail_line,
                                    stamp(entry.at),
                                    entry.note.orEmpty(),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(
                            onClick = {
                                copyToClipboard(
                                    context,
                                    JevText.of(context, R.string.jev_clip_label),
                                    exportText(context, entries),
                                )
                                onTell(JevText.of(context, R.string.jev_history_copied, entries.size))
                            },
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Icon(
                                MaterialSymbols.Outlined.Content_copy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.jev_history_copy_person))
                        }
                    }
                }
            }
        }
    }

    /**
     * 卡片示意预览：与 [dev.ujhhgtg.wekit.features.items.chat.jev.hook.YanwaiBubble] 的
     * 层级一致（标题 → 概率条 → 标签 → 解读 → 建议块 → 细节 → 页脚），
     * 并按当前开关实时增删对应的块 —— 改开关不用真插到聊天里就能看出效果。
     */
    @Composable
    private fun CardPreview(
        showLevel: Boolean,
        showBalance: Boolean,
        showTopics: Boolean,
        showTrendPanel: Boolean,
        showTrend: Boolean,
    ) {
        val scheme = MaterialTheme.colorScheme
        val accent = scheme.primary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(scheme.surfaceContainer, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.jev_card_title,
                    stringResource(R.string.jev_preview_mood),
                ) + if (showTrend) {
                    "  ·  " + stringResource(R.string.jev_trend_up, 12)
                } else {
                    ""
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.jev_preview_mood),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(scheme.surfaceContainerHighest, RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.59f)
                            .fillMaxHeight()
                            .background(accent, RoundedCornerShape(4.dp)),
                    )
                }
                Text(
                    text = "59%",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (showLevel) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PreviewChip(
                        text = stringResource(
                            R.string.jev_chip_level,
                            stringResource(R.string.jev_level_steady),
                        ),
                        color = scheme.tertiary,
                    )
                    PreviewChip(
                        text = stringResource(R.string.jev_meta_confidence, 62),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.jev_card_reading,
                    stringResource(R.string.jev_preview_reading),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = stringResource(
                    R.string.jev_advice,
                    stringResource(R.string.jev_preview_advice),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .background(accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            if (showTopics) {
                PreviewLine(
                    stringResource(
                        R.string.jev_line_topics,
                        stringResource(R.string.jev_topic_meet),
                    ),
                )
            }
            if (showTrendPanel) {
                PreviewLine(
                    stringResource(
                        R.string.jev_line_trend,
                        stringResource(R.string.jev_ext_trend_up, 6),
                    ),
                )
            }
            if (showBalance) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.jev_card_balance_self),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.width(40.dp),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .background(scheme.surfaceContainerHighest, RoundedCornerShape(4.dp)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.42f)
                                .fillMaxHeight()
                                .background(accent, RoundedCornerShape(4.dp)),
                        )
                    }
                    Text(
                        text = "42%",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.jev_card_balance_other),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.width(40.dp),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .background(scheme.surfaceContainerHighest, RoundedCornerShape(4.dp)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.58f)
                                .fillMaxHeight()
                                .background(scheme.onSurfaceVariant, RoundedCornerShape(4.dp)),
                        )
                    }
                    Text(
                        text = "58%",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.jev_hint_expand),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    @Composable
    private fun PreviewLine(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    @Composable
    private fun PreviewChip(text: String, color: Color) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }

    /** 灰底提示条：运行状态、操作反馈各一条（失败走告警配色，多看一眼就知道不对劲）。 */
    @Composable
    private fun Banner(text: String, tone: Tone) {
        val container = if (tone == Tone.Warning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
        val onContainer = if (tone == Tone.Warning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(container, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (tone == Tone.Warning) {
                    MaterialSymbols.Outlined.Warning
                } else {
                    MaterialSymbols.Outlined.Info
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = onContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = onContainer,
            )
        }
    }

    /** 动作按钮：一行一个、图标 + 文案、整宽，五个动作看起来是一组。 */
    @Composable
    private fun ActionButton(
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
        colors: ButtonColors = ButtonDefaults.buttonColors(),
        modifier: Modifier = Modifier,
    ) {
        Button(
            onClick = onClick,
            colors = colors,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }

    private fun runtimeLine(context: Context): String {
        val (ok, bad) = MoodStore.stats()
        return JevText.of(
            context,
            R.string.jev_runtime_line,
            SignalAnalyzer.requestCount,
            ok,
            bad,
            MoodStore.size(),
            SignalAnalyzer.queuedDepth + MoodStore.pendingCount(),
        )
    }

    /**
     * 导出文本：优先导出**完整解读**（与气泡卡/回插通道同一份结构化结论），
     * 结果缓存已被清掉的历史流水退回它记下的那一行结论/失败原因。
     */
    private fun exportText(context: Context, entries: List<MoodStore.Entry>): String = buildString {
        append(JevText.of(context, R.string.jev_export_title, entries.size))
        entries.forEach { entry ->
            append('\n')
            append('\n')
            append(stamp(entry.at)).append(" · ").append(entry.talker.takeLast(10))
            append('\n')
            val mood = MoodStore.get(entry.key)
            append(
                when {
                    mood != null -> MoodMessageChannel.format(mood)
                    entry.ok -> entry.label
                    else -> JevText.of(context, R.string.jev_export_failed, entry.note.orEmpty())
                },
            )
        }
    }

    private fun stamp(at: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
}
