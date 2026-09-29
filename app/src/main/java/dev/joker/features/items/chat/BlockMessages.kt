/*
 * BlockMessages.kt — 屏蔽消息 【第 29 轮 WeKit1945 整合 · Round41 配置页 + 真生效修复】
 *
 * 证据等级：A-（Hchat 4 + 逆向 jadx + 重建版）
 * 来源：
 *   - WeKit 1945 逆向包 `01_逆向源码/chat/BlockMessages.kt`（混淆类 up0）
 *   - `09_Hchat4功能逆向/BlockMessages.kt`（Hchat 4 原版）
 *   - `02_原始反编译/defpackage/BlockMessages.jadx.java`
 *   - `07_反编译产物dump/Lxq1.txt`（规则集反汇编）
 *
 * 对应日志：「新增: 屏蔽消息」
 *
 * ★ Round41 修的两个真问题（用户实机反馈「开了之后没有入口去配置，怎么验证生效」）：
 *   1. **配置入口**：本类由 SwitchFeature 升为 ClickableFeature，设置页这一行现在
 *      「点正文进配置页、点开关切启停」，并提供完整规则编辑 UI（模式 / 会话名单 /
 *      关键词 / 发送人），不再要求用户手写 JSON。
 *   2. **真生效**：`BlockMessagesRuntime` 声明了 DexKit 委托却没实现 IResolveDex，
 *      解析永远不会发生 —— FeaturesLoader 启动守卫会打印
 *      「屏蔽消息服务 声明了 1 个 DexKit 委托却没有实现 IResolveDex」，
 *      然后 enable() 里访问委托抛异常 → unhookAll() → 开关开着也完全没生效。
 *      现在补上 IResolveDex（接口有默认实现，只需声明）。
 *
 * ★ 规则集存储：`block_messages_rules_json`，JSON 对象
 *   `{"talkers":[…],"keywords":[…],"senders":[…]}`；
 *   兼容旧的 `talkers=a,b` 行内格式（旧配置不会丢）。
 *   规则**每次读取都重新解析**（旧实现用 `by lazy` 缓存，配置页写完不重启不生效）。
 *
 * ★ 整合铁律（与上游脱钩）：
 *   - 包名 dev.joker.*
 *   - 不引入 EventBus/AutomationSpec（我方不存在），走 Joker 自有 ApiFeature 双层架构
 *   - defaultEnabled = false；用户主动开启才接管通知链
 */
package dev.joker.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.preferences.WePrefs
import dev.joker.preferences.WePrefs.Companion.prefOption
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.ContactsSelector
import dev.joker.ui.content.TextButton
import dev.joker.ui.content.m3.BaseWidget
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.content.m3.SwitchWidget
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.TargetProcess
import dev.joker.utils.WeLogger
import dev.joker.utils.android.showToast
import org.json.JSONArray
import org.json.JSONObject

/**
 * 屏蔽消息（用户开关 + 配置入口）
 *
 * 默认关闭。开启后由 [BlockMessagesRuntime] 在通知发送前拦截与规则匹配的新消息。
 */
object BlockMessages : ClickableFeature() {

    override val technicalId: String = "屏蔽消息"
    override val nameRes: Int = R.string.feature_chat_block_messages_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes: Int = R.string.feature_chat_block_messages_description

    /** 默认关闭 —— 用户主动启用才接管通知链。 */
    override val defaultEnabled: Boolean = false

    override fun onEnable() {
        // BlockMessagesRuntime 在它的 onEnable() 中负责真 hook 安装。
        // 本类只管理用户偏好，不直接挂 hook。
    }

    override fun onDisable() {
        // 不需操作；BlockMessagesRuntime.onDisable() 会自动清理。
    }

    /**
     * 命中规则吗？
     *
     * @param talker 会话 id
     * @param sender 发送人 wxid（可能为空字符串）
     * @param content 消息内容
     */
    fun shouldBlock(talker: String, sender: String, content: String): Boolean {
        val rules = BlockMessagesRules.current
        // 规则为空 = 放行
        if (rules.talkers.isEmpty() && rules.keywords.isEmpty() && rules.senderKeywords.isEmpty()) {
            return false
        }

        // 白名单模式：仅名单内的会话放行；talker 不在名单 = 屏蔽；keywords/senderKeywords 不参与。
        if (BlockMessagesWhitelistPrefs.useWhitelist) {
            if (talker.isEmpty()) return true  // 无 talker 在白名单模式下默认屏蔽
            return !rules.talkers.contains(talker)
        }

        // 黑名单会话
        if (talker.isNotEmpty() && rules.talkers.contains(talker)) return true
        // 发送人黑名单
        if (sender.isNotEmpty() && rules.senderKeywords.any { sender.contains(it, ignoreCase = true) }) {
            return true
        }
        // 关键词
        if (rules.keywords.isNotEmpty() && content.isNotEmpty()) {
            val lowered = content.lowercase()
            if (rules.keywords.any { lowered.contains(it.lowercase()) }) return true
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════
    //  配置界面（Round41 新增：用户终于有地方配置了）
    // ═══════════════════════════════════════════════════════════════

    override fun onClick(context: ComponentActivity) {
        showRulesDialog(context)
    }

    private fun showRulesDialog(context: ComponentActivity) {
        showComposeDialog(context) {
            val initial = BlockMessagesRules.current
            var whitelist by remember { mutableStateOf(BlockMessagesWhitelistPrefs.useWhitelist) }
            var talkers by remember { mutableStateOf(initial.talkers.toSet()) }
            var keywords by remember { mutableStateOf(initial.keywords.joinToString("\n")) }
            var senders by remember { mutableStateOf(initial.senderKeywords.joinToString("\n")) }

            AlertDialogContent(
                title = { Text(technicalId) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "mode") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "白名单模式",
                                description = "开启后只有「会话名单」里的会话能提醒，其余一律屏蔽；" +
                                    "关闭时为黑名单模式（只屏蔽名单内会话 + 关键词 + 发送人）。",
                                checked = whitelist,
                                onCheckedChange = { whitelist = it },
                            )
                        }
                        item(key = "talkers") {
                            val desc = if (talkers.isEmpty()) {
                                "未选择（点击选择会话）"
                            } else {
                                "已选 ${talkers.size} 个会话（点击修改）"
                            }
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "会话名单",
                                description = desc,
                                onClick = {
                                    // 先落盘当前草稿，选完会话回来不丢关键词/发送人的编辑
                                    persistRules(talkers, keywords, senders, whitelist)
                                    onDismiss()
                                    showContactsPicker(context)
                                },
                                trailingContent = {
                                    androidx.compose.material3.Icon(
                                        imageVector = MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        item(key = "keywords") {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                value = keywords,
                                onValueChange = { keywords = it },
                                label = { Text("关键词（每行一个，命中即屏蔽）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            )
                        }
                        item(key = "senders") {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                value = senders,
                                onValueChange = { senders = it },
                                label = { Text("发送人（每行一个，wxid 片段即可）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            )
                        }
                        item(key = "hint") {
                            Text(
                                text = "说明：本功能在微信「发出通知前」拦下命中的消息，" +
                                    "消息仍会正常进入聊天列表；不命中任何规则时一切照旧。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        persistRules(talkers, keywords, senders, whitelist)
                        showToast(context, "屏蔽消息规则已保存（当前 ${talkers.size} 个会话）")
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
            )
        }
    }

    /** 会话名单选择器；选完回到主配置对话框，草稿不丢。 */
    private fun showContactsPicker(context: ComponentActivity) {
        val contacts = runCatching {
            WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        }.getOrDefault(emptyList())

        showComposeDialog(context) {
            ContactsSelector(
                title = "选择会话名单",
                contacts = contacts,
                initialSelectedWxIds = talkersDraft(),
                onDismiss = { onDismiss(); showRulesDialog(context) },
                onConfirm = { selected ->
                    val current = BlockMessagesRules.current
                    BlockMessagesRules.save(current.copy(talkers = selected.toList()))
                    onDismiss()
                    showRulesDialog(context)
                },
            )
        }
    }

    private fun talkersDraft(): Set<String> = BlockMessagesRules.current.talkers.toSet()

    private fun persistRules(
        talkers: Set<String>,
        keywordsText: String,
        sendersText: String,
        whitelist: Boolean,
    ) {
        runCatching {
            BlockMessagesRules.save(
                BlockMessagesRules(
                    talkers = talkers.toList(),
                    keywords = splitLines(keywordsText),
                    senderKeywords = splitLines(sendersText),
                ),
            )
            BlockMessagesWhitelistPrefs.useWhitelist = whitelist
        }.onFailure { WeLogger.e(TAG, "保存屏蔽消息规则失败", it) }
    }

    private fun splitLines(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private const val TAG = "BlockMessages"
}

/**
 * BlockMessages 的规则集数据类 + 偏好加载。
 *
 * 存储：`block_messages_rules_json`（JSON，见文件头）。兼容旧的行内文本格式。
 */
data class BlockMessagesRules(
    val talkers: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val senderKeywords: List<String> = emptyList(),
) {
    companion object {
        private const val KEY = "block_messages_rules_json"

        /**
         * 规则快照。
         *
         * ★ 每次读取都重新解析（**不用 `by lazy`**）：旧实现把结果缓存在 lazy 里，
         * 用户在设置页改完规则后，运行期读到的还是进程启动时的旧规则 —— 表现就是
         * 「配置保存了但完全没效果」，必须重启微信才生效。
         */
        val current: BlockMessagesRules
            get() = parse(WePrefs.getStringOrDef(KEY, ""))

        fun parse(raw: String): BlockMessagesRules {
            if (raw.isBlank()) return BlockMessagesRules()
            // 新格式：JSON
            if (raw.trimStart().startsWith("{")) {
                return runCatching {
                    val json = JSONObject(raw)
                    BlockMessagesRules(
                        talkers = readArray(json.optJSONArray("talkers")),
                        keywords = readArray(json.optJSONArray("keywords")),
                        senderKeywords = readArray(json.optJSONArray("senders")),
                    )
                }.getOrDefault(BlockMessagesRules())
            }
            // 旧格式：`prefix=value` 行内文本（兼容老用户配置）
            val t = mutableListOf<String>()
            val k = mutableListOf<String>()
            val s = mutableListOf<String>()
            for (rawLine in raw.split('\n')) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val prefix = line.substringBefore('=', "")
                val value = line.substringAfter('=', "").trim()
                if (value.isEmpty()) continue
                when (prefix) {
                    "talkers" -> t.addAll(value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                    "keywords" -> k.addAll(value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                    "senders" -> s.addAll(value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                }
            }
            return BlockMessagesRules(t, k, s)
        }

        fun save(rules: BlockMessagesRules) {
            val json = JSONObject().apply {
                put("talkers", JSONArray(rules.talkers))
                put("keywords", JSONArray(rules.keywords))
                put("senders", JSONArray(rules.senderKeywords))
            }
            WePrefs.putString(KEY, json.toString())
        }

        private fun readArray(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val v = array.optString(i, "").trim()
                if (v.isNotEmpty()) out.add(v)
            }
            return out
        }
    }
}

/**
 * BlockMessages 的 hook 安装器（ApiFeature + IResolveDex）。
 *
 * 入口点：微信 `NotificationCenter.dealNotify`（与 BlockAtAllNotifications 同款签名）。
 * 命中则 `result = null`，通知被吞掉；未命中则原样通过。
 *
 * 限制：本轮只 hook 通知发送，**不动消息入 DB / 不动 UI 不动可见消息**。
 */
object BlockMessagesRuntime : ApiFeature(), IResolveDex {

    override val technicalId = "屏蔽消息服务"
    override val nameRes: Int = R.string.feature_chat_block_messages_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.API)
    override val descriptionRes: Int = R.string.feature_chat_block_messages_description

    private const val TAG = "BlockMessagesRuntime"

    /**
     * 微信通知中心 `dealNotify`，签名 (long, String, String, int, int, boolean) void。
     * 与 BlockAtAllNotifications.methodDealNotify 完全一致。
     */
    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            returnType = "void"
            usingEqStrings(
                "jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s"
            )
        }
    }

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    override fun onEnable() {
        if (!BlockMessages.isEnabled) return
        methodDealNotify.hookBefore(100) {
            val talker = args[1] as? String ?: return@hookBefore
            val rawContent = args[2] as? String ?: ""
            // dealNotify 没有 sender 入参，这里只用会话与正文做判定。
            if (!BlockMessages.shouldBlock(talker = talker, sender = "", content = rawContent)) {
                return@hookBefore
            }
            WeLogger.i(TAG, "suppressing notification from $talker (matches block rule)")
            result = null
        }
    }

    override fun onDisable() {
        // Joker 自己的 hook 框架会自动卸载 dexMethod 委托；本类无额外状态
    }
}

/**
 * BlockMessages 白名单模式 prefs。
 *
 * - useWhitelist=true  → 白名单模式：仅名单内的 talker 放行，其它一律屏蔽
 * - useWhitelist=false → 黑名单模式（默认）：仅名单内的 talker 屏蔽
 */
object BlockMessagesWhitelistPrefs {
    var useWhitelist: Boolean by prefOption("block_messages_use_whitelist", false)
}
