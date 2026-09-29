/*
 * AutoReplyAiModelPicker.kt — 自动回复任务的「AI 模型」选择器 【Round43】
 *
 * 用户实机反馈（原话）：
 *   「关于 AI 回复的模型配置里面，居然要手动输入模型，而不是自动获取所有并选择指定模型
 *     测试后填入，同时无法变化模型提供商，你照抄一下聊天分析功能的不就好了，赶紧修复。」
 *
 * 原实现只有一行 `InlineTaskTextField` —— 手输模型名（`aiModelName`），
 * 既不能自动拉取、也不能测试、更不能换提供商。本文件把它换成四件事：
 *   ① 提供商/模型下拉：直接列出「聊天分析 → AI 模型」里已配置的全部条目，
 *      每条显示「名称 · 模型」，切换它 = 切换模型提供商；
 *   ② 拉取模型列表：调用提供商 `GET {baseUrl}/models`（复用 ChatAnalysisAi.fetchModels），
 *      把该 key 真正可用的模型 id 全部列出来 —— 「自动获取所有」；
 *   ③ 点选 + 测试后填入：从拉取到的列表里点选任意一个 id，
 *      先发一次最小对话 ping（ChatAnalysisAi.testModel），成功才写回配置（addOrUpdate），
 *      即「选择指定模型测试后填入」；
 *   ④ ＋新建提供商：当场填名称 / Base URL / API Key / 模型并保存，解决「无法变化模型提供商」。
 *
 * 设计约束（延续本项目铁律）：
 *   - 网络调用一律 `Dispatchers.IO`，绝不在 Compose 主线程做 IO；UI 只读状态。
 *   - 下拉控件的当前值必须出现在 options 里（DropDownMenuWidget 内部用
 *     `options.first { it.value == value }`，缺失会直接抛异常），因此对「已删除的旧模型名」
 *     会补一个占位项。
 *   - 全部失败路径只提示、不抛异常（拿不到辅助信息 ≠ 功能失败）。
 */
package dev.joker.features.items.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.joker.R
import dev.joker.ui.content.Button
import dev.joker.ui.content.m3.BaseSupportingWidget
import dev.joker.ui.content.m3.DropDownMenuWidget
import dev.joker.ui.content.m3.DropdownOption
import dev.joker.ui.content.m3.SegmentedColumnScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「新建提供商」在下拉里的哨兵值（不会与用户真实模型名冲突）。 */
internal const val AI_PICKER_NEW_PROVIDER = "__joker_new_provider__"

/** 跟随聊天分析当前选中模型（等价于原实现的「留空」语义）。 */
internal const val AI_PICKER_FOLLOW = ""

/**
 * 自动回复任务编辑器里的「AI 模型」区块。
 *
 * 必须在 [SegmentedColumnScope] 内调用（与其它设置项拼在同一张卡片列表里）。
 */
@Composable
internal fun SegmentedColumnScope.AiModelPicker(
    task: AutoReplyTask,
    onChange: (AutoReplyTask) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var storeModels by remember { mutableStateOf(ChatAnalysisModelStore.loadModels()) }
    val followModel = remember(storeModels) { ChatAnalysisModelStore.selectedModel() }

    val pickedName = task.aiModelName
    val activeConfig = when {
        pickedName == AI_PICKER_NEW_PROVIDER -> null
        pickedName.isNotBlank() -> storeModels.firstOrNull { it.name == pickedName }
        else -> followModel
    }

    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // 「新建提供商」草稿
    var newName by remember { mutableStateOf("") }
    var newBase by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }
    var newModel by remember { mutableStateOf("") }

    // 【Round43 · 编译约束】`stringResource` 只能在 @Composable 上下文调用。
    // 本区块的「拉取 / 测试」全在 `scope.launch`、`onClick`、`Result.onSuccess` 这类
    // **普通 lambda** 里跑，而局部函数（fetchAll / testModel）也不是 composable ——
    // 直接在里面写 `stringResource(...)` 会编译失败：
    //   "@Composable invocations can only happen from the context of a @Composable function"
    // 而这些文案又都要带运行期参数（模型个数、失败原因），所以统一在这里**先取模板**，
    // 运行期用 String.format 填参 —— Android 的 `getString(id, args)` 内部就是
    // `String.format(getString(id), args)`，语义完全一致。
    val tBusy = stringResource(R.string.chat_auto_reply_ai_busy)
    val tFetchEmpty = stringResource(R.string.chat_auto_reply_ai_fetch_empty)
    val tFetchOk = stringResource(R.string.chat_auto_reply_ai_fetch_ok)
    val tFetchFail = stringResource(R.string.chat_auto_reply_ai_fetch_fail)
    val tTestOk = stringResource(R.string.chat_auto_reply_ai_test_ok)
    val tTestFail = stringResource(R.string.chat_auto_reply_ai_test_fail)
    val tFilled = stringResource(R.string.chat_auto_reply_ai_filled)
    val tSavedProvider = stringResource(R.string.chat_auto_reply_ai_saved_provider)
    val tDefaultProvider = stringResource(R.string.chat_auto_reply_ai_default_provider_name)

    fun reloadStore() {
        storeModels = ChatAnalysisModelStore.loadModels()
    }

    /** 拉取该提供商 `/models` 全部模型 id。 */
    fun fetchAll(config: AiModelConfig) {
        if (busy) return
        busy = true
        status = tBusy
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ChatAnalysisAi.fetchModels(config.baseUrl, config.apiKey) }
            }
            busy = false
            result.onSuccess { list ->
                fetched = list.distinct().sorted()
                status = if (fetched.isEmpty()) {
                    tFetchEmpty
                } else {
                    String.format(tFetchOk, fetched.size)
                }
            }.onFailure { e ->
                status = String.format(tFetchFail, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 测试指定模型是否真的可用（最小对话 ping）。 */
    fun testModel(config: AiModelConfig, target: String) {
        if (busy) return
        busy = true
        status = tBusy
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ChatAnalysisAi.testModel(config, target) }
            }
            busy = false
            result.onSuccess { r ->
                if (r.success) {
                    // 测试通过 → 立刻把模型写回该提供商（「测试后填入」）。
                    ChatAnalysisModelStore.addOrUpdate(config.copy(model = target))
                    reloadStore()
                    status = String.format(tTestOk, target)
                } else {
                    status = String.format(tTestFail, r.message.ifBlank { target })
                }
            }.onFailure { e ->
                status = String.format(tTestFail, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ── ① 提供商 / 模型下拉 ────────────────────────────────────────
    item(key = "ai_provider") {
        val options = buildList {
            add(DropdownOption(AI_PICKER_FOLLOW, stringResource(R.string.chat_auto_reply_ai_follow_chat_analysis)))
            storeModels.forEach { m ->
                add(DropdownOption(m.name, "${m.name} · ${m.model}"))
            }
            // 当前值必须存在于 options（否则 DropDownMenuWidget 的 first {} 会抛异常）：
            // 例如用户删掉了原提供商，或某个 provider 被重命名。
            // 注意：这里用 buildList receiver 自己的 none（**不能**写 options.none —— 在
            // 初始化器内部引用 options 会形成自引用，Kotlin 报 Unresolved reference）。
            if (pickedName.isNotBlank() && pickedName != AI_PICKER_NEW_PROVIDER &&
                none { it.value == pickedName }
            ) {
                add(DropdownOption(pickedName, "${pickedName}（已失效）"))
            }
            add(DropdownOption(AI_PICKER_NEW_PROVIDER, stringResource(R.string.chat_auto_reply_ai_new_provider)))
        }
        DropDownMenuWidget(
            iconPlaceholder = false,
            title = stringResource(R.string.chat_auto_reply_ai_provider),
            description = stringResource(R.string.chat_auto_reply_ai_provider_hint),
            value = pickedName,
            options = options,
            onValueChange = { v ->
                onChange(task.copy(aiModelName = v))
                fetched = emptyList()
                status = ""
            },
        )
    }

    // ── ④ 新建提供商（当场填 / 保存 / 拉取） ───────────────────────
    if (pickedName == AI_PICKER_NEW_PROVIDER) {
        item(key = "ai_new_provider") {
            BaseSupportingWidget(
                title = stringResource(R.string.chat_auto_reply_ai_new_provider),
                description = stringResource(R.string.chat_auto_reply_ai_new_provider_hint),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ProviderField(
                        value = newName,
                        label = stringResource(R.string.chat_auto_reply_ai_field_name),
                        onValueChange = { newName = it },
                    )
                    ProviderField(
                        value = newBase,
                        label = stringResource(R.string.chat_auto_reply_ai_field_base_url),
                        onValueChange = { newBase = it },
                    )
                    ProviderField(
                        value = newKey,
                        label = stringResource(R.string.chat_auto_reply_ai_field_api_key),
                        onValueChange = { newKey = it },
                    )
                    ProviderField(
                        value = newModel,
                        label = stringResource(R.string.chat_auto_reply_ai_field_model),
                        onValueChange = { newModel = it },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled = !busy && newBase.isNotBlank(),
                            onClick = {
                                fetchAll(
                                    AiModelConfig(
                                        name = newName.ifBlank { tDefaultProvider },
                                        baseUrl = newBase,
                                        apiKey = newKey,
                                        model = newModel,
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.chat_auto_reply_ai_fetch_models)) }
                        Button(
                            enabled = newName.isNotBlank() && newBase.isNotBlank() && newModel.isNotBlank(),
                            onClick = {
                                val cfg = AiModelConfig(
                                    name = newName.trim(),
                                    baseUrl = newBase.trim(),
                                    apiKey = newKey.trim(),
                                    model = newModel.trim(),
                                )
                                ChatAnalysisModelStore.addOrUpdate(cfg)
                                ChatAnalysisModelStore.select(cfg.name)
                                reloadStore()
                                onChange(task.copy(aiModelName = cfg.name))
                                status = String.format(tSavedProvider, cfg.name)
                            },
                        ) { Text(stringResource(R.string.chat_auto_reply_ai_save_provider)) }
                    }
                    if (fetched.isNotEmpty()) {
                        FetchedModelDropdown(
                            models = fetched,
                            onPick = { id ->
                                newModel = id
                                status = String.format(tFilled, id)
                            },
                        )
                    }
                    if (status.isNotBlank()) StatusLine(status)
                }
            }
        }
    }

    // ── ② ③ 拉取 / 测试（针对已存在的提供商） ──────────────────────
    if (activeConfig != null && pickedName != AI_PICKER_NEW_PROVIDER) {
        item(key = "ai_actions") {
            BaseSupportingWidget(
                title = stringResource(R.string.chat_auto_reply_ai_actions),
                description = "${activeConfig.name} · ${activeConfig.model}",
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled = !busy && activeConfig.baseUrl.isNotBlank(),
                            onClick = { fetchAll(activeConfig) },
                        ) { Text(stringResource(R.string.chat_auto_reply_ai_fetch_models)) }
                        Button(
                            enabled = !busy &&
                                activeConfig.baseUrl.isNotBlank() &&
                                activeConfig.model.isNotBlank(),
                            onClick = { testModel(activeConfig, activeConfig.model) },
                        ) { Text(stringResource(R.string.chat_auto_reply_ai_test_model)) }
                    }
                    if (fetched.isNotEmpty()) {
                        FetchedModelDropdown(
                            models = fetched,
                            onPick = { id ->
                                // 点选拉取到的模型 → 测试 → 通过才写回（测试失败的模型不会被填入）。
                                status = String.format(tFilled, id)
                                testModel(activeConfig, id)
                            },
                        )
                    }
                    if (status.isNotBlank()) StatusLine(status)
                }
            }
        }
    }

    // ── 未配置任何提供商时的提示 ────────────────────────────────────
    if (activeConfig == null && pickedName != AI_PICKER_NEW_PROVIDER) {
        item(key = "ai_no_provider") {
            BaseSupportingWidget(
                title = stringResource(R.string.chat_auto_reply_ai_no_model),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (status.isNotBlank()) StatusLine(status)
                    Text(
                        text = stringResource(R.string.chat_auto_reply_ai_pick_new_provider_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 拉取到的模型 id 列表 → 下拉点选。 */
@Composable
private fun FetchedModelDropdown(models: List<String>, onPick: (String) -> Unit) {
    // 用索引做 value：模型 id 里可能带 '/'、':' 等字符，直接当 value 也没问题，
    // 但下拉内部要做 first { it.value == value } 比较，用 id 本身更直观可读。
    var selectedIndex by remember { mutableStateOf(0) }
    DropDownMenuWidget(
        iconPlaceholder = false,
        title = stringResource(R.string.chat_auto_reply_ai_model_list, models.size),
        description = null,
        value = selectedIndex,
        options = models.mapIndexed { index, id -> DropdownOption(index, id) },
        onValueChange = { index ->
            selectedIndex = index
            models.getOrNull(index)?.let(onPick)
        },
    )
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun ProviderField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
    )
}
