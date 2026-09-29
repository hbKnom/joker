package dev.joker.features.items.chat

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Drag_handle
import dev.joker.R
import dev.joker.i18n.LocalJokerLocalizedContext
import dev.joker.activity.TransparentActivity
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.api.core.models.IWeContact
import dev.joker.features.items.AtomicJsonConfigStore
import dev.joker.features.items.AutomationContactSettingsSelector
import dev.joker.features.items.AutomationKeywordMode
import dev.joker.features.items.AutomationKeywordRule
import dev.joker.features.items.AutomationTimeRangeRule
import dev.joker.features.items.AutomationToggleRule
import dev.joker.features.items.formatAutomationMinute
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.Button
import dev.joker.ui.content.IconButton
import dev.joker.ui.content.TextButton
import dev.joker.features.items.payment.PaymentErrorRow
import dev.joker.features.items.payment.PaymentNavigationRow
import dev.joker.features.items.payment.PaymentRuleRow
import dev.joker.features.items.payment.keywordItems
import dev.joker.features.items.payment.timeRangeItems
import dev.joker.ui.content.m3.BaseSupportingWidget
import dev.joker.ui.content.m3.BaseWidget
import dev.joker.ui.content.m3.DropDownMenuWidget
import dev.joker.ui.content.m3.DropdownOption
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.content.m3.SwitchWidget
import dev.joker.ui.utils.ReorderableList
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.WeLogger
import dev.joker.utils.android.showToast
import dev.joker.utils.fs.KnownPaths
import dev.joker.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.div

private const val CONFIG_VERSION = 1

@Serializable
internal enum class AutoReplyType { TEXT, IMAGE, VIDEO, VOICE }

@Serializable
internal data class AutoReplyRule(
    val type: AutoReplyType = AutoReplyType.TEXT,
    val text: String = "",
    val path: String = "",
    val voiceDurationMs: String = "1000",
)

@Serializable
internal data class AutoReplyTask(
    val name: String = "",
    val enabled: Boolean = true,
    val keyword: AutomationKeywordRule = AutomationKeywordRule(ignoreCase = true),
    /**
     * 【Round30】逆向版 Hchat AutoReplyMessage 9 字段（@我 / @所有人 / 拍一拍）场景：
     *   - onlyAtMe=true      → 仅 @我的消息触发
     *   - onlyNotifyAll=true → 仅 @所有人的消息触发
     *   - onlyPatMe=true     → 仅拍一拍我触发
     *   - onlyQuote=true     → 仅引用消息触发
     * 默认 false = 不区分场景，文本匹配即可（向后兼容）。
     */
    val onlyAtMe: Boolean = false,
    val onlyNotifyAll: Boolean = false,
    val onlyPatMe: Boolean = false,
    val onlyQuote: Boolean = false,
    val reply: AutoReplyRule = AutoReplyRule(),
    val delayMs: String = "0",
    val cooldownMs: String = "0",
    val stopAfterMatch: Boolean = true,
    /**
     * 【Round31】启用 AI 回复：useAi=true 时不再发 reply 固定文本，而是
     * 调 ChatAnalysisAi.plain(selectedModel(), aiSystemPrompt, "{content}...") 拿 AI 文本再发。
     * AI 模型配置复用 ChatAnalysisModelStore（与聊天分析功能共享），不在此处存独立配置。
     * 默认 false（向后兼容）。
     */
    val useAi: Boolean = false,
    val aiSystemPrompt: String = "",
    val aiTemperature: Double = 0.7,
    val aiMaxTokens: Int = 500,

    /**
     * 【Round43】AI 回复自然度扩展。
     *
     * 用户反馈：AI 回复「比较单调、功能单一」，希望更自然、上下文更正确。原实现只把**单条**
     * 原消息丢给模型（无历史），模型无从判断语境，只能干巴巴回一句。这里补三件：
     *  - [aiContextTurns]：把该会话最近 N 轮对话一并带上（0 = 只发单条，兼容旧行为）；
     *  - [aiMaxChars]：单条回复字符上限，防「长篇大论式」不自然回复；
     *  - [aiAvoidRepeat]：与「自己上一条发的内容」完全相同则丢弃/重试，避免复读机。
     */
    val aiContextTurns: Int = 6,
    val aiMaxChars: Int = 200,
    val aiAvoidRepeat: Boolean = true,
    /**
     * 【Round31】可选指定 AI 模型名（与 ChatAnalysisModelStore 的 name 字段对应）：
     *   - 空字符串 "" = 用 ChatAnalysisModelStore.selectedModel()（聊天分析选中的）
     *   - 非空 = 用 ChatAnalysisModelStore.findByName(aiModelName)（可被本任务覆盖）
     * 让用户能为自动回复选不同模型（如：聊天分析用 GPT-4，自动回复用本地 Ollama）。
     */
    val aiModelName: String = "",
)

@Serializable
internal data class AutoReplyRuleSet(
    val enabled: AutomationToggleRule = AutomationToggleRule(),
    val timeRange: AutomationTimeRangeRule = AutomationTimeRangeRule(),
    val tasks: List<AutoReplyTask> = emptyList(),
)

@Serializable
internal data class AutoReplyRuleOverrides(
    val enabled: AutomationToggleRule? = null,
    val timeRange: AutomationTimeRangeRule? = null,
    val tasks: List<AutoReplyTask>? = null,
) {
    fun isEmpty(): Boolean = enabled == null && timeRange == null && tasks == null
}

@Serializable
private data class StoredConfig(
    val version: Int = CONFIG_VERSION,
    val global: AutoReplyRuleSet = AutoReplyRuleSet(),
    val contacts: Map<String, AutoReplyRuleOverrides> = emptyMap(),
    val groupMembers: Map<String, Map<String, AutoReplyRuleOverrides>> = emptyMap(),
)

/** 聊天自动回复分层配置（全局 → 联系人 → 群成员），模式与 RedPacketSettings 一致。 */
internal object AutoReplySettings {
    private const val TAG = "AutoReplySettings"

    private val configFile by lazy { KnownPaths.moduleData / "auto_reply_settings.json" }

    private enum class RuleKey { ENABLED, TIME_RANGE, TASKS }

    private val store by lazy {
        AtomicJsonConfigStore(
            file = configFile,
            serializer = StoredConfig.serializer(),
            tag = TAG,
            initialValue = { StoredConfig() },
        )
    }

    fun resolve(talker: String, sender: String?): AutoReplyRuleSet {
        val config = loadConfig()
        var rules = config.global.apply(config.contacts[talker])
        if (talker.isGroupChatWxId && !sender.isNullOrBlank()) {
            rules = rules.apply(config.groupMembers[talker]?.get(sender))
        }
        return rules
    }

    fun showMainDialog(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                textScrolls = true,
                title = { Text(stringResource(R.string.chat_auto_reply_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.chat_auto_reply_global_settings),
                                description = stringResource(R.string.chat_auto_reply_global_settings_summary),
                                onClick = { showGlobalDialog(context) },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.chat_auto_reply_contact_settings),
                                description = stringResource(R.string.chat_auto_reply_contact_settings_summary),
                                onClick = { showContactSelector(context) },
                            )
                        }
                        // 【Round41】AI 自动回复可见性：AI 回复开关藏在「全局规则 → 任务编辑」里，
                        // 用户实测找不到（以为没做 AI 自动回复）。这里给一个显式入口 + 当前模型状态。
                        item {
                            val model = runCatching { ChatAnalysisModelStore.selectedModel() }.getOrNull()
                            val modelState = when {
                                model == null -> "未配置 AI 模型"
                                model.apiKey.isBlank() -> "模型「${model.name}」缺少 API Key"
                                else -> "当前模型：${model.name}（${model.model}）"
                            }
                            PaymentNavigationRow(
                                title = "AI 自动回复",
                                description = "在「全局规则 → 任务 → 使用 AI 回复」里开启；$modelState",
                                onClick = { showAiReplyDialog(context) },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    /**
     * 【Round41】AI 自动回复说明 + 当前模型状态。
     *
     * 用户实机反馈「自动回复是不是没有加 AI」——其实 Round31 就已实装（任务级 useAi），
     * 但入口只有「全局规则 → 任务编辑 → 使用 AI 回复」这一条深路径，且没有任何地方
     * 告诉用户「AI 回复用的模型是哪一个、配没配好」。本对话框把这两件事讲清楚。
     */
    internal fun showAiReplyDialog(context: Context) {
        showComposeDialog(context) {
            val models = runCatching { ChatAnalysisModelStore.loadModels() }.getOrDefault(emptyList())
            val selected = runCatching { ChatAnalysisModelStore.selectedModel() }.getOrNull()
            AlertDialogContent(
                textScrolls = true,
                title = { Text("AI 自动回复") },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "how") {
                            Text(
                                text = "开启方式：自动回复 → 「全局规则」或某个会话/群 → 添加或编辑任务 → " +
                                    "打开「使用 AI 回复」。开启后该任务不再发固定文案，改为把收到的消息" +
                                    "交给 AI 生成回复（可单独填写系统提示词与最大 token）。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        item(key = "model") {
                            val state = when {
                                selected == null -> "还没有可用的 AI 模型"
                                selected.apiKey.isBlank() -> "模型「${selected.name}」缺少 API Key，调用会失败"
                                else -> "当前模型：${selected.name}\n接口：${selected.baseUrl}${selected.path}\n模型名：${selected.model}"
                            }
                            Text(
                                text = "AI 模型（与聊天记录分析功能共用）：\n$state\n\n共 ${models.size} 个已配置模型。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        item(key = "where") {
                            Text(
                                text = "模型管理在「聊天记录分析 → AI 设置」里：可添加/切换模型、填 API Key。" +
                                    "任务里「模型名」留空 = 使用上面这个当前模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    private fun showGlobalDialog(context: Context) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(globalRules()) }
            val validationError = validate(draft)
            val localizedContext by rememberUpdatedState(LocalJokerLocalizedContext.current)

            AlertDialogContent(
                textScrolls = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(stringResource(R.string.chat_auto_reply_global_settings)) },
                text = {
                    RuleSetEditor(
                        rules = draft,
                        overriddenKeys = null,
                        parentLabel = "",
                        onActivate = {},
                        onReset = {},
                        onChange = { _, updated -> draft = updated },
                        onEditTask = { index ->
                            showTaskDialog(context, draft.tasks[index]) { updated ->
                                val tasks = draft.tasks.toMutableList().apply { this[index] = updated }
                                draft = draft.copy(tasks = tasks)
                            }
                        },
                        onAddTask = {
                            showTaskDialog(context, AutoReplyTask()) { updated ->
                                draft = draft.copy(tasks = draft.tasks + updated)
                            }
                        },
                        validationError = validationError,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            updateConfig { it.copy(global = draft) }
                            showToast(localizedContext.getString(R.string.chat_auto_reply_global_saved))
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun showContactSelector(context: Context) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val contacts = remember { loadContacts() }
            val contactSettingsTitle = stringResource(R.string.chat_auto_reply_contact_settings)
            val groupSettings = stringResource(R.string.chat_auto_reply_group_settings)
            val followsGlobal = stringResource(R.string.chat_auto_reply_follows_global)
            val globalSettings = stringResource(R.string.chat_auto_reply_global_settings)
            val localizedContext by rememberUpdatedState(LocalJokerLocalizedContext.current)
            AutomationContactSettingsSelector(
                title = contactSettingsTitle,
                contacts = contacts,
                selectionKey = revision,
                subtitle = { contact ->
                    val count = contactOverrides(contact.wxId).overriddenCount()
                    when {
                        contact.wxId.isGroupChatWxId && count > 0 ->
                            localizedContext.resources.getQuantityString(
                                R.plurals.chat_auto_reply_group_overridden_count,
                                count,
                                count,
                            )
                        contact.wxId.isGroupChatWxId -> groupSettings
                        count > 0 -> localizedContext.resources.getQuantityString(
                            R.plurals.chat_auto_reply_overridden_count,
                            count,
                            count,
                        )
                        else -> followsGlobal
                    }
                },
                isConfigured = { contact ->
                    contactOverrides(contact.wxId).overriddenCount() > 0 ||
                        memberOverridesCount(contact.wxId) > 0
                },
                onDismiss = onDismiss,
                onOpen = { contact ->
                    if (contact.wxId.isGroupChatWxId) {
                        showGroupSettingsDialog(context, contact.wxId) { revision++ }
                    } else {
                        showOverrideDialog(
                            context = context,
                            title = contact.displayName.ifBlank { contact.wxId },
                            parentLabel = globalSettings,
                            parent = globalRules(),
                            initial = contactOverrides(contact.wxId),
                            onSave = {
                                setContactOverrides(contact.wxId, it)
                                revision++
                            },
                        )
                    }
                },
            )
        }
    }

    private fun showGroupSettingsDialog(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
            val groupOverrideCount = remember(revision) {
                contactOverrides(groupId).overriddenCount()
            }
            val memberCount = remember(revision) { memberOverridesCount(groupId) }
            val globalSettings = stringResource(R.string.chat_auto_reply_global_settings)
            val groupGlobalSettings = stringResource(R.string.chat_auto_reply_group_global_settings)

            AlertDialogContent(
                textScrolls = true,
                title = { Text(groupName) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.chat_auto_reply_group_global_settings),
                                description = if (groupOverrideCount == 0) {
                                    stringResource(R.string.chat_auto_reply_follows_global)
                                } else {
                                    pluralStringResource(
                                        R.plurals.chat_auto_reply_overridden_count,
                                        groupOverrideCount,
                                        groupOverrideCount,
                                    )
                                },
                                onClick = {
                                    showOverrideDialog(
                                        context = context,
                                        title = groupGlobalSettings,
                                        parentLabel = globalSettings,
                                        parent = globalRules(),
                                        initial = contactOverrides(groupId),
                                        onSave = {
                                            setContactOverrides(groupId, it)
                                            revision++
                                            onUpdated()
                                        },
                                    )
                                },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.chat_auto_reply_group_member_settings),
                                description = if (memberCount == 0) {
                                    stringResource(R.string.chat_auto_reply_all_members_follow_group)
                                } else {
                                    pluralStringResource(
                                        R.plurals.chat_auto_reply_configured_member_count,
                                        memberCount,
                                        memberCount,
                                    )
                                },
                                onClick = {
                                    showGroupMemberSelector(context, groupId) {
                                        revision++
                                        onUpdated()
                                    }
                                },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    private fun showGroupMemberSelector(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val members = remember(groupId) {
                runCatching { WeDatabaseApi.getGroupMembers(groupId) }
                    .onFailure { WeLogger.e(TAG, "failed to load members of $groupId", it) }
                    .getOrDefault(emptyList())
            }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
            val localizedContext by rememberUpdatedState(LocalJokerLocalizedContext.current)
            val groupGlobalSettings = stringResource(R.string.chat_auto_reply_group_global_settings)

            AutomationContactSettingsSelector(
                title = stringResource(R.string.chat_auto_reply_group_member_settings_title, groupName),
                contacts = members,
                selectionKey = revision,
                subtitle = { member ->
                    val count = groupMemberOverrides(groupId, member.wxId).overriddenCount()
                    if (count == 0) {
                        localizedContext.getString(R.string.chat_auto_reply_follows_group)
                    } else {
                        localizedContext.resources.getQuantityString(
                            R.plurals.chat_auto_reply_overridden_count,
                            count,
                            count,
                        )
                    }
                },
                isConfigured = { member ->
                    groupMemberOverrides(groupId, member.wxId).overriddenCount() > 0
                },
                onDismiss = onDismiss,
                onOpen = { member ->
                    showOverrideDialog(
                        context = context,
                        title = member.displayName.ifBlank { member.wxId },
                        parentLabel = groupGlobalSettings,
                        parent = globalRules().apply(contactOverrides(groupId)),
                        initial = groupMemberOverrides(groupId, member.wxId),
                        onSave = {
                            setGroupMemberOverrides(groupId, member.wxId, it)
                            revision++
                            onUpdated()
                        },
                    )
                },
            )
        }
    }

    private fun showOverrideDialog(
        context: Context,
        title: String,
        parentLabel: String,
        parent: AutoReplyRuleSet,
        initial: AutoReplyRuleOverrides,
        onSave: (AutoReplyRuleOverrides) -> Unit,
    ) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(initial) }
            val effective = parent.apply(draft)
            val validationError = validate(effective, draft.keys())
            val localizedContext by rememberUpdatedState(LocalJokerLocalizedContext.current)

            AlertDialogContent(
                textScrolls = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(title) },
                text = {
                    RuleSetEditor(
                        rules = effective,
                        overriddenKeys = draft.keys(),
                        parentLabel = parentLabel,
                        onActivate = { key -> draft = draft.withRule(key, effective) },
                        onReset = { key -> draft = draft.withoutRule(key) },
                        onChange = { key, updated -> draft = draft.withRule(key, updated) },
                        onEditTask = { index ->
                            val base = draft.tasks ?: effective.tasks
                            showTaskDialog(context, base[index]) { updated ->
                                val tasks = base.toMutableList().apply { this[index] = updated }
                                draft = draft.copy(tasks = tasks)
                            }
                        },
                        onAddTask = {
                            val base = draft.tasks ?: effective.tasks
                            showTaskDialog(context, AutoReplyTask()) { updated ->
                                draft = draft.copy(tasks = base + updated)
                            }
                        },
                        validationError = validationError,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            onSave(draft)
                            showToast(localizedContext.getString(R.string.chat_auto_reply_settings_saved))
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun showTaskDialog(
        context: Context,
        initial: AutoReplyTask,
        onSave: (AutoReplyTask) -> Unit,
    ) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(initial) }
            val validationError = validateTask(draft)

            AlertDialogContent(
                textScrolls = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = {
                    Text(initial.name.ifBlank { stringResource(R.string.chat_auto_reply_task_settings) })
                },
                text = {
                    TaskEditor(
                        task = draft,
                        onChange = { draft = it },
                        validationError = validationError,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            onSave(draft)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    @Composable
    private fun RuleSetEditor(
        rules: AutoReplyRuleSet,
        overriddenKeys: Set<RuleKey>?,
        parentLabel: String,
        onActivate: (RuleKey) -> Unit,
        onReset: (RuleKey) -> Unit,
        onChange: (RuleKey, AutoReplyRuleSet) -> Unit,
        onEditTask: (Int) -> Unit,
        onAddTask: () -> Unit,
        validationError: String?,
    ) {
        fun overridden(key: RuleKey): Boolean? = overriddenKeys?.let { key in it }
        fun editable(key: RuleKey): Boolean = overriddenKeys == null || key in overriddenKeys

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
            item(key = "enabled") { PaymentRuleRow(
                title = stringResource(R.string.chat_auto_reply_enabled_title),
                summary = if (rules.enabled.enabled) {
                    stringResource(R.string.chat_auto_reply_enabled_summary)
                } else {
                    stringResource(R.string.chat_auto_reply_disabled_summary)
                },
                checked = rules.enabled.enabled,
                overridden = overridden(RuleKey.ENABLED),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.ENABLED) },
                onReset = { onReset(RuleKey.ENABLED) },
                onCheckedChange = {
                    onChange(RuleKey.ENABLED, rules.copy(enabled = rules.enabled.copy(enabled = it)))
                },
            ) }

            item(key = "time_range") { PaymentRuleRow(
                title = stringResource(R.string.chat_auto_reply_time_range_title),
                summary = if (rules.timeRange.enabled) {
                    "${formatAutomationMinute(rules.timeRange.startMinute)} - ${formatAutomationMinute(rules.timeRange.endMinute)}"
                } else {
                    stringResource(R.string.chat_auto_reply_time_unrestricted)
                },
                checked = rules.timeRange.enabled,
                overridden = overridden(RuleKey.TIME_RANGE),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.TIME_RANGE) },
                onReset = { onReset(RuleKey.TIME_RANGE) },
                onCheckedChange = {
                    onChange(
                        RuleKey.TIME_RANGE,
                        rules.copy(timeRange = rules.timeRange.copy(enabled = it)),
                    )
                },
            ) }
            timeRangeItems(
                rule = rules.timeRange,
                editable = editable(RuleKey.TIME_RANGE),
                visible = rules.timeRange.enabled,
                onChange = { onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = it)) },
            )

            item(key = "tasks_header") {
                BaseWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.chat_auto_reply_tasks_title),
                    description = if (rules.tasks.isEmpty()) {
                        stringResource(R.string.chat_auto_reply_no_tasks)
                    } else {
                        pluralStringResource(
                            R.plurals.chat_auto_reply_task_count_summary,
                            rules.tasks.size,
                            rules.tasks.size,
                        )
                    },
                )
            }
            }
            if (rules.tasks.isNotEmpty()) {
                ReorderableList(
                    items = rules.tasks,
                    itemKey = { System.identityHashCode(it) },
                    onMove = { from, to ->
                        val tasks = rules.tasks.toMutableList()
                        tasks.add(to, tasks.removeAt(from))
                        onChange(RuleKey.TASKS, rules.copy(tasks = tasks))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) { task, dragHandleModifier ->
                    val index = rules.tasks.indexOfFirst { it === task }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .then(dragHandleModifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                MaterialSymbols.Outlined.Drag_handle,
                                contentDescription = stringResource(R.string.chat_auto_reply_drag_task),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onEditTask(index) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = task.name.ifBlank {
                                    stringResource(R.string.chat_auto_reply_task_number, index + 1)
                                },
                                maxLines = 1,
                            )
                            Text(
                                text = autoReplyKeywordSummary(task.keyword),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        IconButton(
                            onClick = {
                                onChange(RuleKey.TASKS, rules.copy(tasks = rules.tasks - task))
                            },
                        ) {
                            Icon(
                                MaterialSymbols.Outlined.Delete,
                                contentDescription = stringResource(R.string.chat_auto_reply_delete_task),
                            )
                        }
                    }
                }
            }
            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                item(key = "add_task") {
                    PaymentNavigationRow(
                        title = stringResource(R.string.chat_auto_reply_add_task),
                        description = stringResource(R.string.chat_auto_reply_add_task_summary),
                        onClick = onAddTask,
                    )
                }
                validationError?.let { error ->
                    item(key = "validation_error") { PaymentErrorRow(error) }
                }
            }
        }
    }

    @Composable
    private fun TaskEditor(
        task: AutoReplyTask,
        onChange: (AutoReplyTask) -> Unit,
        validationError: String?,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
            item(key = "name") {
                BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_task_name)) {
                    InlineTaskTextField(value = task.name, onValueChange = { onChange(task.copy(name = it)) })
                }
            }
            item(key = "enabled") {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.chat_auto_reply_enable_task),
                    checked = task.enabled,
                    onCheckedChange = { onChange(task.copy(enabled = it)) },
                )
            }
            keywordItems(
                keyPrefix = "task_keyword",
                rule = task.keyword,
                editable = true,
                visible = true,
                modes = AutomationKeywordMode.entries,
                onChange = { onChange(task.copy(keyword = it)) },
                onEditText = {},
                inlineTextFields = true,
            )
            item(key = "reply_type") {
                DropDownMenuWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.chat_auto_reply_task_settings),
                    description = null,
                    value = task.reply.type,
                    options = AutoReplyType.entries.map { type ->
                        DropdownOption(type, stringResource(type.labelRes))
                    },
                    onValueChange = { onChange(task.copy(reply = task.reply.copy(type = it))) },
                )
            }
            when (task.reply.type) {
                AutoReplyType.TEXT -> item(key = "reply_text") {
                    BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_reply_content)) {
                        InlineTaskTextField(
                            value = task.reply.text,
                            onValueChange = { onChange(task.copy(reply = task.reply.copy(text = it))) },
                        )
                    }
                }

                AutoReplyType.IMAGE -> item(key = "image_path") { AssetPathField(
                    type = AutoReplyType.IMAGE,
                    path = task.reply.path,
                    onChange = { onChange(task.copy(reply = task.reply.copy(path = it))) },
                ) }

                AutoReplyType.VIDEO -> item(key = "video_path") { AssetPathField(
                    type = AutoReplyType.VIDEO,
                    path = task.reply.path,
                    onChange = { onChange(task.copy(reply = task.reply.copy(path = it))) },
                ) }

                AutoReplyType.VOICE -> {
                    item(key = "voice_path") { AssetPathField(
                        type = AutoReplyType.VOICE,
                        path = task.reply.path,
                        onChange = { onChange(task.copy(reply = task.reply.copy(path = it))) },
                    ) }
                    item(key = "voice_duration") {
                        BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_voice_duration_ms)) {
                            InlineTaskTextField(
                                value = task.reply.voiceDurationMs,
                                keyboardType = KeyboardType.Number,
                                onValueChange = {
                                    onChange(task.copy(reply = task.reply.copy(voiceDurationMs = it.filter(Char::isDigit).take(5))))
                                },
                            )
                        }
                    }
                }
            }
            item(key = "delay") {
                BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_delay_ms)) {
                    InlineTaskTextField(
                        value = task.delayMs,
                        keyboardType = KeyboardType.Number,
                        onValueChange = { onChange(task.copy(delayMs = it.filter(Char::isDigit).take(5))) },
                    )
                }
            }
            item(key = "cooldown") {
                BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_cooldown_ms)) {
                    InlineTaskTextField(
                        value = task.cooldownMs,
                        keyboardType = KeyboardType.Number,
                        onValueChange = { onChange(task.copy(cooldownMs = it.filter(Char::isDigit).take(7))) },
                    )
                }
            }
            item(key = "stop_after_match") {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.chat_auto_reply_stop_after_match),
                    checked = task.stopAfterMatch,
                    onCheckedChange = { onChange(task.copy(stopAfterMatch = it)) },
                )
            }

            // 【Round31】AI 回复配置 — 复用 ChatAnalysisModelStore
            item(key = "use_ai") {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.chat_auto_reply_use_ai),
                    description = stringResource(R.string.chat_auto_reply_use_ai_summary),
                    checked = task.useAi,
                    onCheckedChange = { onChange(task.copy(useAi = it)) },
                )
            }
            // 【Round43】AI 模型选择器（取代 Round31 的手输文本框）
            // 用户反馈原话：「居然要手动输入模型，而不是自动获取所有并选择指定模型测试后填入，
            // 同时无法变化模型提供商」。详见 AutoReplyAiModelPicker.kt。
            // 注意：必须包在 item { } 里 —— SegmentedColumnScope 的 item content 才是
            // @Composable 上下文，直接裸调会报
            // 「@Composable invocations can only happen from the context of a @Composable function」。
            // 【Round45 · 崩溃修复】AiModelPicker 本身是 SegmentedColumnScope 的扩展、体内自己
            // 调用 item(...)。若再套一层 item { }，它的内容 lambda 会在 SegmentedColumn 的
            // Layout 子组合阶段执行，此时往作用域里 add 会打断正在进行的迭代 → CME 崩微信。
            // 正确用法：直接在本作用域内展开（与 keywordItems 等其它扩展一致）。
            AiModelPicker(task = task, onChange = onChange)
            // 当前 AI 模型提示
            val currentAiModel = ChatAnalysisModelStore.selectedModel()
            item(key = "ai_model_hint") {
                BaseSupportingWidget(
                    title = if (currentAiModel != null && currentAiModel.model.isNotBlank()) {
                        stringResource(
                            R.string.chat_auto_reply_ai_model_current,
                            "${currentAiModel.name} (${currentAiModel.model})",
                        )
                    } else {
                        stringResource(R.string.chat_auto_reply_ai_no_model)
                    },
                ) {}
            }
            if (task.useAi) {
                item(key = "ai_system_prompt") {
                    BaseSupportingWidget(
                        title = stringResource(R.string.chat_auto_reply_ai_system_prompt),
                        description = stringResource(R.string.chat_auto_reply_ai_system_prompt_hint),
                    ) {
                        InlineTaskTextField(
                            value = task.aiSystemPrompt,
                            onValueChange = { onChange(task.copy(aiSystemPrompt = it)) },
                        )
                    }
                }
                item(key = "ai_max_tokens") {
                    BaseSupportingWidget(
                        title = stringResource(R.string.chat_auto_reply_ai_max_tokens),
                    ) {
                        InlineTaskTextField(
                            value = task.aiMaxTokens.toString(),
                            keyboardType = KeyboardType.Number,
                            onValueChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(4)
                                val parsed = digits.toIntOrNull()?.coerceIn(64, 4096) ?: task.aiMaxTokens
                                onChange(task.copy(aiMaxTokens = parsed))
                            },
                        )
                    }
                }
                // 【Round43】上下文 / 长度 / 去重 —— 让回复「更自然、上下文更正确」
                item(key = "ai_context_turns") {
                    BaseSupportingWidget(
                        title = stringResource(R.string.chat_auto_reply_ai_context_turns),
                        description = stringResource(R.string.chat_auto_reply_ai_context_turns_hint),
                    ) {
                        InlineTaskTextField(
                            value = task.aiContextTurns.toString(),
                            keyboardType = KeyboardType.Number,
                            onValueChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(2)
                                val parsed = digits.toIntOrNull()?.coerceIn(0, 30) ?: task.aiContextTurns
                                onChange(task.copy(aiContextTurns = parsed))
                            },
                        )
                    }
                }
                item(key = "ai_max_chars") {
                    BaseSupportingWidget(
                        title = stringResource(R.string.chat_auto_reply_ai_max_chars),
                        description = stringResource(R.string.chat_auto_reply_ai_max_chars_hint),
                    ) {
                        InlineTaskTextField(
                            value = task.aiMaxChars.toString(),
                            keyboardType = KeyboardType.Number,
                            onValueChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(4)
                                val parsed = digits.toIntOrNull()?.coerceIn(10, 2000) ?: task.aiMaxChars
                                onChange(task.copy(aiMaxChars = parsed))
                            },
                        )
                    }
                }
                item(key = "ai_avoid_repeat") {
                    SwitchWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.chat_auto_reply_ai_avoid_repeat),
                        description = stringResource(R.string.chat_auto_reply_ai_avoid_repeat_hint),
                        checked = task.aiAvoidRepeat,
                        onCheckedChange = { onChange(task.copy(aiAvoidRepeat = it)) },
                    )
                }
            }

            validationError?.let { error ->
                item(key = "validation_error") { PaymentErrorRow(error) }
            }
            }
        }
    }

    @Composable
    private fun InlineTaskTextField(
        value: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        onValueChange: (String) -> Unit,
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
        )
    }

    private val AutoReplyType.labelRes: Int
        get() = when (this) {
            AutoReplyType.TEXT -> R.string.chat_auto_reply_type_text
            AutoReplyType.IMAGE -> R.string.chat_auto_reply_type_image
            AutoReplyType.VIDEO -> R.string.chat_auto_reply_type_video
            AutoReplyType.VOICE -> R.string.chat_auto_reply_type_voice
        }

    @Composable
    private fun AssetPathField(
        type: AutoReplyType,
        path: String,
        onChange: (String) -> Unit,
    ) {
        val context = LocalContext.current
        val title = stringResource(
            when (type) {
                AutoReplyType.IMAGE -> R.string.chat_auto_reply_image_path
                AutoReplyType.VIDEO -> R.string.chat_auto_reply_video_path
                AutoReplyType.VOICE -> R.string.chat_auto_reply_voice_path
                AutoReplyType.TEXT -> R.string.chat_auto_reply_reply_content
            }
        )
        BaseSupportingWidget(title = title) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                value = path,
                onValueChange = onChange,
                label = { Text(title) },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            importAsset(
                                context = context,
                                mimeTypes = when (type) {
                                    AutoReplyType.IMAGE -> arrayOf("image/*")
                                    AutoReplyType.VIDEO -> arrayOf("video/*")
                                    AutoReplyType.VOICE -> arrayOf("audio/*")
                                    AutoReplyType.TEXT -> return@IconButton
                                },
                                typePrefix = when (type) {
                                    AutoReplyType.IMAGE -> "image"
                                    AutoReplyType.VIDEO -> "video"
                                    AutoReplyType.VOICE -> "voice"
                                    AutoReplyType.TEXT -> ""
                                },
                                onImported = onChange,
                            )
                        },
                    ) {
                        Icon(
                            MaterialSymbols.Outlined.Download,
                            contentDescription = stringResource(R.string.chat_auto_reply_import),
                        )
                    }
                },
                singleLine = true,
            )
        }
    }

    /**
     * 用 TransparentActivity 拉起系统文件选择器，把所选文件拷贝到
     * `KnownPaths.userAssets`（文件名 `<type>_<timestamp>.<ext>`），成功后回填路径。
     */
    private fun importAsset(
        context: Context,
        mimeTypes: Array<String>,
        typePrefix: String,
        onImported: (String) -> Unit,
    ) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val extension = queryDisplayName(contentResolver, uri)?.substringAfterLast('.', "")
                            ?.lowercase()?.takeIf(String::isNotBlank)
                            ?: fallbackExtension(contentResolver.getType(uri))
                        val target = KnownPaths.userAssets /
                            "${typePrefix}_${System.currentTimeMillis()}.$extension"
                        contentResolver.openInputStream(uri)?.use { input ->
                            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                        } ?: error("cannot open picked file")
                        withContext(Dispatchers.Main) {
                            onImported(target.toString())
                            finish()
                        }
                    }.onFailure {
                        WeLogger.e(TAG, "import asset failed", it)
                        withContext(Dispatchers.Main) { finish() }
                    }
                }
            }
            launcher.launch(mimeTypes)
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun fallbackExtension(mimeType: String?): String = when {
        mimeType?.startsWith("image/") == true -> "jpg"
        mimeType?.startsWith("video/") == true -> "mp4"
        mimeType?.startsWith("audio/") == true -> "m4a"
        else -> "bin"
    }

    private fun AutoReplyRuleSet.apply(overrides: AutoReplyRuleOverrides?): AutoReplyRuleSet {
        if (overrides == null) return this
        return copy(
            enabled = overrides.enabled ?: enabled,
            timeRange = overrides.timeRange ?: timeRange,
            tasks = overrides.tasks ?: tasks,
        )
    }

    private fun AutoReplyRuleOverrides.keys(): Set<RuleKey> = buildSet {
        if (enabled != null) add(RuleKey.ENABLED)
        if (timeRange != null) add(RuleKey.TIME_RANGE)
        if (tasks != null) add(RuleKey.TASKS)
    }

    private fun AutoReplyRuleOverrides.withRule(key: RuleKey, rules: AutoReplyRuleSet): AutoReplyRuleOverrides =
        when (key) {
            RuleKey.ENABLED -> copy(enabled = rules.enabled)
            RuleKey.TIME_RANGE -> copy(timeRange = rules.timeRange)
            RuleKey.TASKS -> copy(tasks = rules.tasks)
        }

    private fun AutoReplyRuleOverrides.withoutRule(key: RuleKey): AutoReplyRuleOverrides =
        when (key) {
            RuleKey.ENABLED -> copy(enabled = null)
            RuleKey.TIME_RANGE -> copy(timeRange = null)
            RuleKey.TASKS -> copy(tasks = null)
        }

    private fun AutoReplyRuleOverrides.overriddenCount(): Int =
        listOf(enabled, timeRange, tasks).count { it != null }

    @Composable
    private fun autoReplyKeywordSummary(rule: AutomationKeywordRule): String {
        if (!rule.enabled) return stringResource(R.string.chat_auto_reply_keyword_unrestricted)
        return when (rule.mode) {
            AutomationKeywordMode.STRING_LIST -> pluralStringResource(
                R.plurals.chat_auto_reply_keyword_list_summary,
                rule.strings.size,
                rule.strings.size,
            )
            AutomationKeywordMode.EXACT -> pluralStringResource(
                R.plurals.chat_auto_reply_keyword_exact_summary,
                rule.strings.size,
                rule.strings.size,
            )
            AutomationKeywordMode.PREFIX -> pluralStringResource(
                R.plurals.chat_auto_reply_keyword_prefix_summary,
                rule.strings.size,
                rule.strings.size,
            )
            AutomationKeywordMode.REGEX -> if (rule.regex.isBlank()) {
                stringResource(R.string.chat_auto_reply_keyword_regex_empty)
            } else {
                stringResource(R.string.chat_auto_reply_keyword_regex_summary)
            }
        }
    }

    @Composable
    private fun validate(rules: AutoReplyRuleSet, keys: Set<RuleKey>? = null): String? {
        fun validates(key: RuleKey) = keys == null || key in keys

        if (validates(RuleKey.TASKS)) {
            for ((index, task) in rules.tasks.withIndex()) {
                if (!task.enabled) continue
                val error = validateTask(task)
                if (error != null) {
                    val name = task.name.ifBlank {
                        stringResource(R.string.chat_auto_reply_task_number, index + 1)
                    }
                    return stringResource(R.string.chat_auto_reply_task_error, name, error)
                }
            }
        }
        return null
    }

    @Composable
    private fun validateTask(task: AutoReplyTask): String? {
        if (!task.enabled) return null
        if (task.keyword.enabled) {
            when (task.keyword.mode) {
                AutomationKeywordMode.STRING_LIST, AutomationKeywordMode.EXACT, AutomationKeywordMode.PREFIX ->
                    if (task.keyword.strings.none(String::isNotBlank)) {
                        return stringResource(R.string.chat_auto_reply_error_keyword_list_empty)
                    }
                AutomationKeywordMode.REGEX -> when {
                    task.keyword.regex.isBlank() ->
                        return stringResource(R.string.chat_auto_reply_error_keyword_regex_empty)
                    runCatching { Regex(task.keyword.regex) }.isFailure ->
                        return stringResource(R.string.chat_auto_reply_error_keyword_regex_invalid)
                }
            }
        }
        if (task.useAi) {
            // 【Round43】开启「使用 AI 回复」后，回复内容由 AI 现场生成，
            // 固定文本/媒体路径与语音时长都不再是必填项。
            //
            // 修的是用户实机截图里的死锁：只打开「使用 AI 回复」、固定文本留空时，
            // 弹窗一直红字提示「文本回复内容不能为空」并且「确定」按钮变灰 ——
            // AI 回复任务根本无法保存。此处只保留「必须有一个可用的 AI 模型」这一条硬校验。
            val model = ChatAnalysisModelStore.selectedModel()
                ?: ChatAnalysisModelStore.loadModels().firstOrNull()
            val override = task.aiModelName.trim()
                .takeIf { it.isNotBlank() }
                ?.let { name -> ChatAnalysisModelStore.loadModels().firstOrNull { it.name == name } }
            val effective = override ?: model
            if (effective == null || effective.baseUrl.isBlank() || effective.model.isBlank()) {
                return stringResource(R.string.chat_auto_reply_ai_error_no_model)
            }
        } else {
            when (task.reply.type) {
                AutoReplyType.TEXT -> if (task.reply.text.isBlank()) {
                    return stringResource(R.string.chat_auto_reply_error_text_empty)
                }
                AutoReplyType.IMAGE, AutoReplyType.VIDEO, AutoReplyType.VOICE -> if (task.reply.path.isBlank()) {
                    return stringResource(R.string.chat_auto_reply_error_path_empty)
                }
            }
            if (task.reply.type == AutoReplyType.VOICE) {
                val duration = task.reply.voiceDurationMs.toIntOrNull()
                if (duration == null || duration < 1 || duration > 60000) {
                    return stringResource(R.string.chat_auto_reply_error_voice_duration)
                }
            }
        }
        val delay = task.delayMs.toLongOrNull()
        if (delay == null || delay < 0 || delay > 60000) {
            return stringResource(R.string.chat_auto_reply_error_delay)
        }
        val cooldown = task.cooldownMs.toLongOrNull()
        if (cooldown == null || cooldown < 0) {
            return stringResource(R.string.chat_auto_reply_error_cooldown)
        }
        return null
    }

    private fun loadContacts(): List<IWeContact> = runCatching {
        (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups())
            .distinctBy(IWeContact::wxId)
    }.onFailure {
        WeLogger.e(TAG, "failed to load contacts", it)
    }.getOrDefault(emptyList())

    private fun globalRules(): AutoReplyRuleSet = loadConfig().global

    private fun contactOverrides(wxId: String): AutoReplyRuleOverrides =
        loadConfig().contacts[wxId] ?: AutoReplyRuleOverrides()

    private fun groupMemberOverrides(groupId: String, memberId: String): AutoReplyRuleOverrides =
        loadConfig().groupMembers[groupId]?.get(memberId) ?: AutoReplyRuleOverrides()

    private fun memberOverridesCount(groupId: String): Int =
        loadConfig().groupMembers[groupId]?.count { !it.value.isEmpty() } ?: 0

    private fun setContactOverrides(wxId: String, overrides: AutoReplyRuleOverrides) {
        updateConfig { config ->
            val contacts = config.contacts.toMutableMap()
            if (overrides.isEmpty()) contacts.remove(wxId) else contacts[wxId] = overrides
            config.copy(contacts = contacts)
        }
    }

    private fun setGroupMemberOverrides(groupId: String, memberId: String, overrides: AutoReplyRuleOverrides) {
        updateConfig { config ->
            val groups = config.groupMembers.toMutableMap()
            val members = groups[groupId].orEmpty().toMutableMap()
            if (overrides.isEmpty()) members.remove(memberId) else members[memberId] = overrides
            if (members.isEmpty()) groups.remove(groupId) else groups[groupId] = members
            config.copy(groupMembers = groups)
        }
    }

    private fun loadConfig(): StoredConfig = store.get()

    private fun updateConfig(transform: (StoredConfig) -> StoredConfig) {
        store.update { transform(it).copy(version = CONFIG_VERSION) }
    }
}
