package dev.ujhhgtg.wekit.ui.utils

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个可被「生效聊天」类设置选中的会话。 */
data class ConversationOption(
    val wxId: String,
    val title: String,
    val isGroup: Boolean,
)

private const val TAG = "ConversationPicker"

/** 一次最多渲染多少行（好友上千时全部渲染会让设置页严重掉帧）。 */
private const val MAX_RENDERED_ROWS = 300

/**
 * 拉取**全部**会话（群聊 + 好友），按最近消息时间倒序，返回 (列表, 是否加载中)。
 *
 * 背景（2026-09-22 用户反馈）：QQ 点歌的「生效聊天」以前只能靠会话右键菜单一条条加，
 * 设置页里点了没有任何反应。这里给出通用会话选择器的数据源，其他功能（如语音播报）也能直接用：
 *   - 群聊用 [WeDatabaseApi.getGroups]（rcontact 里 `@chatroom` 的会话）
 *   - 好友用 [WeDatabaseApi.getFriends]
 *   - 排序用 [WeDatabaseApi.getLastMessageTimes]，最近聊过的排最前面，避免上千好友里翻不到人
 * 数据库查询一律放 IO 线程：微信进程只有 512MB 堆，在主线程跑全量会话查询很容易 ANR。
 */
@Composable
fun rememberAllConversations(refreshKey: Int = 0): Pair<List<ConversationOption>, Boolean> {
    var options by remember { mutableStateOf<List<ConversationOption>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(refreshKey) {
        loading = true
        options = withContext(Dispatchers.IO) { loadAllConversations() }
        loading = false
    }
    return options to loading
}

private fun loadAllConversations(): List<ConversationOption> = runCatching {
    val lastMessageTimes = runCatching { WeDatabaseApi.getLastMessageTimes() }
        .onFailure { WeLogger.w(TAG, "load last message times failed", it) }
        .getOrDefault(emptyMap())
    val seen = LinkedHashSet<String>()
    val result = ArrayList<ConversationOption>()

    fun add(contact: IWeContact, group: Boolean) {
        val wxId = contact.wxId
        if (wxId.isEmpty() || !seen.add(wxId)) return
        val title = contact.displayName.ifBlank { contact.nickname }.ifBlank { wxId }
        result += ConversationOption(wxId = wxId, title = title, isGroup = group)
    }

    runCatching { WeDatabaseApi.getGroups() }
        .onFailure { WeLogger.w(TAG, "load groups failed", it) }
        .getOrDefault(emptyList())
        .forEach { add(it, group = true) }
    runCatching { WeDatabaseApi.getFriends() }
        .onFailure { WeLogger.w(TAG, "load friends failed", it) }
        .getOrDefault(emptyList())
        .forEach { add(it, group = false) }

    WeLogger.i(TAG, "loaded ${result.size} conversations")
    result.sortedByDescending { lastMessageTimes[it.wxId] ?: 0L }
}.onFailure { WeLogger.w(TAG, "load conversations failed", it) }
    .getOrDefault(emptyList())

/**
 * 「生效聊天」选择区。
 *
 * 设计取舍：外面的设置弹窗本身就是一个 `LazyColumn`，这里**不能再嵌套一个 LazyColumn**
 * （无界高度会直接崩），所以做成「固定上限高度的内部竖向滚动区 + 搜索框」。
 * 搜索同时匹配昵称和 wxId，方便在上千会话里定位；只渲染前 [MAX_RENDERED_ROWS] 行。
 */
@Composable
fun ConversationPickerSection(
    selected: Set<String>,
    onToggle: (wxId: String, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
) {
    val (options, loading) = rememberAllConversations(refreshKey)
    var query by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.conversation_picker_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Text(
            text = stringResource(R.string.conversation_picker_counts, selected.size, options.size),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        when {
            loading -> PickerHint(stringResource(R.string.conversation_picker_loading))

            options.isEmpty() -> PickerHint(stringResource(R.string.conversation_picker_empty))

            else -> {
                val trimmed = query.trim()
                val filtered = if (trimmed.isEmpty()) {
                    options
                } else {
                    options.filter {
                        it.title.contains(trimmed, ignoreCase = true) || it.wxId.contains(trimmed)
                    }
                }
                if (filtered.isEmpty()) {
                    PickerHint(stringResource(R.string.conversation_picker_no_match))
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        filtered.take(MAX_RENDERED_ROWS).forEach { option ->
                            Row(Modifier.fillMaxWidth()) {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = option.title,
                                    description = if (option.isGroup) {
                                        stringResource(R.string.conversation_picker_group) +
                                            " · " + option.wxId
                                    } else {
                                        option.wxId
                                    },
                                    checked = option.wxId in selected,
                                    onCheckedChange = { onToggle(option.wxId, it) },
                                    trailingDivider = true,
                                )
                            }
                        }
                        if (filtered.size > MAX_RENDERED_ROWS) {
                            PickerHint(
                                stringResource(
                                    R.string.conversation_picker_truncated,
                                    MAX_RENDERED_ROWS,
                                    filtered.size,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
