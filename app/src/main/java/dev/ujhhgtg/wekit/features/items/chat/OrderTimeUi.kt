package dev.ujhhgtg.wekit.features.items.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Format_list_bulleted
import com.composables.icons.materialsymbols.outlined.Notifications_active
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Send
import com.composables.icons.materialsymbols.outlined.Touch_app
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget

/**
 * OrderTime —— WeKit 风格 Compose UI
 * 复刻 Java 脚本「OrderTime 增强版」的全部界面：主菜单 / 新建编辑 / 任务列表 / 任务操作。
 */
object OrderTimeUi {

    // ---------------- 主菜单 ----------------

    @Composable
    fun MainContent(
        onNew: () -> Unit,
        onList: () -> Unit,
        onClose: () -> Unit,
    ) {
        AlertDialogContent(
            title = { Text("OrderTime · 定时发送") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Add,
                            iconPlaceholder = true,
                            title = "新建文本任务",
                            description = "设置内容 / 时间 / 目标，支持超时补发与重复任务",
                            onClick = onNew,
                            trailingDivider = true,
                            trailingContent = {
                                Icon(MaterialSymbols.Outlined.Chevron_right, null)
                            },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Format_list_bulleted,
                            iconPlaceholder = true,
                            title = "任务列表",
                            description = "查看 / 编辑 / 暂停 / 删除所有定时任务",
                            onClick = onList,
                            trailingDivider = true,
                            trailingContent = {
                                Icon(MaterialSymbols.Outlined.Chevron_right, null)
                            },
                        )
                    }
                    item {
                        HorizontalDivider()
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Notifications_active,
                            iconPlaceholder = true,
                            title = "使用提示",
                            description = "长按任意聊天消息 → 「定时发送」可快速创建并预填当前聊天对象；微信被杀后系统闹钟会拉起进程补发。",
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClose) { Text("关闭") }
            },
        )
    }

    // ---------------- 新建 / 编辑 ----------------

    @Composable
    fun EditContent(
        editTask: OrderTask?,
        prefillTalker: String,
        onSave: (content: String, timeStr: String, targets: String, repeatType: Int, intervalDays: Int, intervalSec: Long, sendOnTimeout: Boolean) -> Unit,
        onBack: () -> Unit,
    ) {
        val isEdit = editTask != null
        var content by remember { mutableStateOf(editTask?.content ?: "") }
        var timeStr by remember {
            mutableStateOf(editTask?.let { OrderTime.formatTimeHM(it.planTime) } ?: "08:00")
        }
        var targets by remember {
            mutableStateOf(editTask?.targets?.joinToString(",") ?: prefillTalker)
        }
        var repeatPos by remember { mutableStateOf(when (editTask?.repeatType) { 1 -> 1; 3 -> 2; else -> 0 }) }
        var intervalDays by remember { mutableStateOf((editTask?.repeatDayInterval ?: 1).toString()) }
        var intervalSec by remember { mutableStateOf((editTask?.intervalSec ?: 0L).toString()) }
        var sendOnTimeout by remember { mutableStateOf(editTask?.sendOnTimeout ?: true) }

        AlertDialogContent(
            title = { Text(if (isEdit) "编辑任务" else "新建文本任务") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("消息内容") },
                            placeholder = { Text("请输入要发送的文本...") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = timeStr,
                            onValueChange = { timeStr = it },
                            label = { Text("发送时间 (HH:mm)") },
                            placeholder = { Text("例如 08:00") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = targets,
                                onValueChange = { targets = it },
                                label = { Text("目标 wxid（多个用逗号分隔）") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (!isEdit && prefillTalker.isNotBlank()) {
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Touch_app,
                                iconPlaceholder = true,
                                title = "已填入当前聊天对象",
                                description = prefillTalker,
                                onClick = { targets = prefillTalker },
                                trailingDivider = true,
                            )
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text(
                            "重复方式",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    item {
                        Column {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Schedule,
                                iconPlaceholder = true,
                                title = "单次",
                                description = "仅发送一次",
                                selected = repeatPos == 0,
                                onClick = { repeatPos = 0 },
                                trailingDivider = true,
                            )
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Schedule,
                                iconPlaceholder = true,
                                title = "每天",
                                description = "每天同一时间重复发送",
                                selected = repeatPos == 1,
                                onClick = { repeatPos = 1 },
                                trailingDivider = true,
                            )
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Schedule,
                                iconPlaceholder = true,
                                title = "每隔N天",
                                description = "按固定间隔天数重复发送",
                                selected = repeatPos == 2,
                                onClick = { repeatPos = 2 },
                                trailingDivider = true,
                            )
                        }
                    }
                    if (repeatPos == 2) {
                        item {
                            OutlinedTextField(
                                value = intervalDays,
                                onValueChange = { intervalDays = it.filter { c -> c.isDigit() } },
                                label = { Text("每隔（天）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = intervalSec,
                            onValueChange = { intervalSec = it.filter { c -> c.isDigit() } },
                            label = { Text("发送间隔（秒，多目标时）") },
                            placeholder = { Text("0 = 不间隔") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Notifications_active,
                            iconPlaceholder = true,
                            title = "超时补发",
                            description = "错过时间后立即补发（30 分钟内）",
                            checked = sendOnTimeout,
                            onCheckedChange = { sendOnTimeout = it },
                        )
                    }
                }
            },
            confirmButton = {
                Button({
                    val c = content.trim()
                    val t = timeStr.trim()
                    val ts = targets.trim()
                    if (c.isEmpty() || t.isEmpty() || ts.isEmpty()) {
                        dev.ujhhgtg.wekit.utils.android.showToast("请填写完整信息")
                        return@Button
                    }
                    val pos = repeatPos
                    val repeatType = when (pos) { 1 -> 1; 2 -> 3; else -> 0 }
                    val days = intervalDays.toIntOrNull() ?: 1
                    if (repeatType == 3 && days < 1) {
                        dev.ujhhgtg.wekit.utils.android.showToast("每隔天数不能小于 1")
                        return@Button
                    }
                    val sec = intervalSec.toLongOrNull() ?: 0L
                    onSave(c, t, ts, repeatType, days, sec, sendOnTimeout)
                }) {
                    Icon(MaterialSymbols.Outlined.Send, null)
                    Text(if (isEdit) "更新" else "保存")
                }
                Button(onBack) { Text("返回") }
            },
        )
    }

    // ---------------- 任务列表 ----------------

    @Composable
    fun ListContent(
        tasks: List<OrderTask>,
        onPick: (String) -> Unit,
        onBack: () -> Unit,
    ) {
        AlertDialogContent(
            title = { Text("任务列表 (${tasks.size})") },
            text = {
                if (tasks.isEmpty()) {
                    Column(Modifier.padding(vertical = 16.dp)) {
                        Text("暂无任务", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "长按任意聊天消息 → 定时发送，创建第一个任务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        itemsIndexed(tasks) { _, task ->
                            val status = OrderTask.statusText(task.status)
                            val repeat = OrderTask.repeatText(task)
                            val timeText = OrderTime.formatTime(task.planTime)
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Schedule,
                                iconPlaceholder = true,
                                title = "$timeText · $repeat · $status",
                                description = task.content.ifBlank { "(空内容)" } +
                                    " → ${task.targets.size} 个目标",
                                onClick = { onPick(task.taskId) },
                                trailingDivider = true,
                                trailingContent = {
                                    Icon(MaterialSymbols.Outlined.Chevron_right, null)
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onBack) { Text("返回") }
            },
        )
    }

    // ---------------- 任务操作 ----------------

    @Composable
    fun ActionContent(
        task: OrderTask?,
        onEdit: () -> Unit,
        onToggle: () -> Unit,
        onExecute: () -> Unit,
        onDelete: () -> Unit,
        onBack: () -> Unit,
    ) {
        val status = task?.let { OrderTask.statusText(it.status) } ?: "不存在"
        val timeText = task?.let { OrderTime.formatTime(it.planTime) } ?: ""
        AlertDialogContent(
            title = { Text("任务操作 · $status") },
            text = {
                if (task == null) {
                    Column(Modifier.padding(vertical = 16.dp)) { Text("任务不存在") }
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Schedule,
                                iconPlaceholder = true,
                                title = timeText,
                                description = OrderTask.repeatText(task) + " · " +
                                    task.content.ifBlank { "(空内容)" } +
                                    " → ${task.targets.size} 个目标",
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Edit,
                                iconPlaceholder = true,
                                title = "编辑",
                                onClick = onEdit,
                                trailingDivider = true,
                                trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null) },
                            )
                        }
                        item {
                            BaseWidget(
                                icon = if (task.status == "paused") MaterialSymbols.Outlined.Play_arrow
                                else MaterialSymbols.Outlined.Pause,
                                iconPlaceholder = true,
                                title = if (task.status == "paused") "恢复" else "暂停",
                                onClick = onToggle,
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Send,
                                iconPlaceholder = true,
                                title = "立即执行",
                                description = "无视原计划时间，马上发送",
                                onClick = onExecute,
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Delete,
                                iconPlaceholder = true,
                                title = "删除",
                                isError = true,
                                onClick = onDelete,
                                trailingDivider = true,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onBack) { Text("返回") }
            },
        )
    }
}
