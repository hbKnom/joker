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
import dev.joker.preferences.HotPrefs
import dev.joker.preferences.hotPrefOption
import dev.joker.preferences.WePrefs
import dev.joker.preferences.WePrefs.Companion.prefOption
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.ContactsSelector
import dev.joker.ui.content.SingleContactSelector
import dev.joker.ui.content.TextButton
import dev.joker.ui.content.m3.BaseWidget
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.content.m3.SwitchWidget
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.TargetProcess
import dev.joker.features.api.core.WeConversationApi
import dev.joker.features.api.core.models.IWeContact
import dev.joker.utils.WeLogger
import dev.joker.utils.android.showToast
import dev.joker.utils.strings.isGroupChatWxId
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
    fun shouldBlock(talker: String, sender: String, content: String): Boolean =
        matchReason(talker, sender, content) != null

    /**
     * 命中判定 + 命中原因（Round43 拆出来，配置页要显示「为什么拦了这条」）。
     *
     * @return null = 放行；否则返回中文原因标签。
     */
    fun matchReason(talker: String, sender: String, content: String): String? =
        matchReason(
            rules = BlockMessagesRules.current,
            useWhitelist = BlockMessagesWhitelistPrefs.useWhitelist,
            talker = talker,
            sender = sender,
            content = content,
        )

    /**
     * 【Round44】热路径重载：调用方自备规则快照与白名单开关。
     *
     * 聊天界面「屏蔽消息遮盖」逐条 bind 都会调用它，如果走上面那个重载，
     * 每一行都会重新解析一次 `block_messages_rules_json`（JSON）—— 那是热路径上的性能事故。
     * 调用方（[BlockedMessageMask]）用 HotPrefs 缓存原始串、只在串变化时重新 parse，
     * 再把快照传进来。语义与上面完全一致，两个入口共用这一份实现。
     */
    fun matchReason(
        rules: BlockMessagesRules,
        useWhitelist: Boolean,
        talker: String,
        sender: String,
        content: String,
    ): String? {
        // ── ① 会话级独立规则最高优先（Round45 新增）────────────────────────────
        // 用户原话：「这个遮盖如果遇到多个会话是不是不同会话要分开设置，
        // 确保不出现被全部遮盖的 bug」。因此每个会话可以有**自己的**模式，
        // 与全局规则完全隔离：一个会话设成「全部遮盖」不会影响另一个会话。
        if (talker.isNotEmpty()) {
            rules.perTalker[talker]?.let { per ->
                return when (per.mode) {
                    BlockTalkerMode.PASS -> null
                    BlockTalkerMode.MASK_ALL -> "该会话全部"
                    BlockTalkerMode.KEYWORD -> {
                        if (per.keywords.isEmpty() || content.isEmpty()) {
                            null
                        } else {
                            val lowered = content.lowercase()
                            if (per.keywords.any { lowered.contains(it.lowercase()) }) "该会话关键词" else null
                        }
                    }
                }
            }
        }

        // 规则为空 = 放行
        if (rules.isEmpty) return null

        // 白名单模式：仅名单内的会话放行；talker 不在名单 = 屏蔽；keywords/senderKeywords 不参与。
        if (useWhitelist) {
            if (talker.isEmpty()) return "白名单外"  // 无 talker 在白名单模式下默认屏蔽
            return if (!rules.talkers.contains(talker)) "白名单外" else null
        }

        // 黑名单会话
        if (talker.isNotEmpty() && rules.talkers.contains(talker)) return "会话名单"
        // 发送人黑名单
        if (sender.isNotEmpty() && rules.senderKeywords.any { sender.contains(it, ignoreCase = true) }) {
            return "发送人"
        }
        // 关键词（受总开关控制：关掉后「会话名单」里的人就是全部消息遮盖，
        // 正是用户要的「开关关闭的话就默认指定的那个人所有的消息都遮盖」）
        if (rules.keywordEnabled && rules.keywords.isNotEmpty() && content.isNotEmpty()) {
            val lowered = content.lowercase()
            if (rules.keywords.any { lowered.contains(it.lowercase()) }) return "关键词"
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    //  命中记录（Round43 新增：让「到底有没有生效」可见）
    // ═══════════════════════════════════════════════════════════════
    //
    // 用户实机反馈「开了功能配置完依旧还是不生效，无法使用」——而日志里其实一直在拦。
    // 问题出在「拦截完全不可见」：通知被吞掉之后用户没有任何反馈渠道，只能靠猜。
    // 这里在内存里保留最近若干条命中记录（不进 DB、不做持久化，重启即清），
    // 配置页顶部直接显示，用户发条测试消息就能立刻看到「已拦截 N 条」。
    private const val MAX_HITS = 20

    private val hits = ArrayDeque<BlockHit>()

    /** 记录一次拦截（供配置页展示）。线程安全：`dealNotify` 可能在非主线程回调。 */
    fun recordHit(hit: BlockHit) {
        synchronized(hits) {
            hits.addFirst(hit)
            while (hits.size > MAX_HITS) hits.removeLast()
        }
    }

    fun recentHits(): List<BlockHit> = synchronized(hits) { hits.toList() }

    fun resetHits() {
        synchronized(hits) { hits.clear() }
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
                textScrolls = true,
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
                        // 【Round45】会话级独立规则入口。
                        // 用户原话：「不同会话是不是要分开设置，确保不出现被全部遮盖的 bug」。
                        item(key = "per_talker") {
                            val ruleMap = BlockMessagesRules.current.perTalker
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "按会话独立设置",
                                description = if (ruleMap.isEmpty()) {
                                    "为某个会话单独指定「全部遮盖 / 仅关键词 / 放行」，与其它会话互不影响。"
                                } else {
                                    "已单独设置 ${ruleMap.size} 个会话（点击修改）"
                                },
                                onClick = {
                                    // 先落盘当前草稿，切换页面回来不丢编辑
                                    persistRules(talkers, keywords, senders, whitelist)
                                    onDismiss()
                                    showTalkerRulePicker(context)
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
                        // 【Round45】关键词维度总开关：关掉后「会话名单」里的人 = 全部消息遮盖。
                        item(key = "keyword_switch") {
                            var keywordOn by remember { mutableStateOf(BlockMessagesRules.current.keywordEnabled) }
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "启用关键词遮盖",
                                description = "开启：只有命中关键词的消息才被遮盖/屏蔽；" +
                                    "关闭：会话名单里的人「所有消息」一律遮盖/屏蔽（关键词输入框隐藏）。",
                                checked = keywordOn,
                                onCheckedChange = {
                                    keywordOn = it
                                    persistRules(talkers, keywords, senders, whitelist, it)
                                },
                            )
                        }
                        if (BlockMessagesRules.current.keywordEnabled) {
                            item(key = "keywords") {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    value = keywords,
                                    onValueChange = { keywords = it },
                                    label = { Text("关键词（每行一个，命中即遮盖）") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                )
                            }
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
                        // 【Round45】群成员可视化选择：选群 → 勾选成员 → 自动填入发送人名单。
                        // 用户原话：「按道理不应该提供可以搜索和上下滑动翻阅查看的某个群聊的
                        // 所有成员列表吗，选中了还可以自动填入，而你居然还要自己手动注入 wxid」。
                        item(key = "group_members") {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "从群成员中选择",
                                description = "先选一个群，再勾选要遮盖的成员，自动填入上面的发送人名单。",
                                onClick = {
                                    persistRules(talkers, keywords, senders, whitelist)
                                    onDismiss()
                                    showGroupMembersPicker(context)
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
                        // ── 【Round45】遮盖外观（用户要求：密不透风 + 圆角 + 配色 + 点按可看）──
                        item(key = "mask_show_sender") {
                            var showSender by remember { mutableStateOf(BlockMessagesMaskPrefs.showSender) }
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "遮盖层显示发送者",
                                description = "在遮盖层上显示「头像 + 群昵称/备注」，一眼看出是谁的消息被遮盖。",
                                checked = showSender,
                                onCheckedChange = {
                                    showSender = it
                                    BlockMessagesMaskPrefs.showSender = it
                                },
                            )
                        }
                        item(key = "mask_click") {
                            var clickable by remember { mutableStateOf(BlockMessagesMaskPrefs.clickToReveal) }
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "点按可临时查看",
                                description = "点一下遮盖层显示原文，再点一下重新盖回去（仅对被遮盖的消息生效）。",
                                checked = clickable,
                                onCheckedChange = {
                                    clickable = it
                                    BlockMessagesMaskPrefs.clickToReveal = it
                                },
                            )
                        }
                        item(key = "mask_theme") {
                            var themeIndex by remember { mutableStateOf(BlockMessagesMaskPrefs.themeIndex) }
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "遮盖层配色",
                                description = "当前：" + BLOCK_MASK_THEME_LABELS[themeIndex.coerceIn(0, BLOCK_MASK_THEME_LABELS.size - 1)] +
                                    "（遮盖层从不透明，背后内容完全不可见）",
                                onClick = {
                                    themeIndex = (themeIndex + 1) % BLOCK_MASK_THEME_LABELS.size
                                    BlockMessagesMaskPrefs.themeIndex = themeIndex
                                },
                                trailingContent = {
                                    Text(
                                        text = "切换",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                        }
                        // 【Round43】屏蔽时顺带标记已读 —— 只吞通知的话列表里仍堆未读红点，
                        // 用户会认为「功能没生效」，这也是本轮反馈的主要来源之一。
                        item(key = "mark_read") {
                            var markRead by remember { mutableStateOf(BlockMessagesExtraPrefs.markReadOnBlock) }
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "屏蔽时同时标记为已读",
                                description = "命中屏蔽的消息不再计入未读（列表不再堆红点）。" +
                                    "关闭后仅「不弹通知」。",
                                checked = markRead,
                                onCheckedChange = {
                                    markRead = it
                                    BlockMessagesExtraPrefs.markReadOnBlock = it
                                },
                            )
                        }
                        // 【Round43】拦截记录 —— 让「到底有没有生效」一眼可见。
                        item(key = "hits") {
                            val history = BlockMessages.recentHits()
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "最近拦截记录（${history.size}）",
                                description = if (history.isEmpty()) {
                                    "暂无记录。保存规则后让对方发一条消息，命中即会出现在这里。"
                                } else {
                                    history.take(6).joinToString("\n") { hit ->
                                        val t = java.text.SimpleDateFormat(
                                            "HH:mm:ss",
                                            java.util.Locale.getDefault(),
                                        ).format(java.util.Date(hit.time))
                                        "[$t] ${hit.talker} · ${hit.reason}" +
                                            (if (hit.markedRead) " · 已读" else "")
                                    }
                                },
                                onClick = { BlockMessages.resetHits() },
                                trailingContent = {
                                    Text(
                                        text = "重置记录",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                        }
                        item(key = "hint") {
                            Text(
                                text = "说明：本功能在微信「发出通知前」拦下命中的消息。" +
                                    "命中维度 = 会话名单 / 发送人（群消息取「昵称:」前缀）/ 关键词；" +
                                    "开启白名单模式时则相反：只有名单内的会话能提醒。" +
                                    "未开启「同时标记为已读」时，消息仍会正常进入聊天列表并计未读。",
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

    // ═══════════════════════════════════════════════════════════════
    //  【Round45】可视化选择器：彻底替代「手动注入 wxid」
    // ═══════════════════════════════════════════════════════════════

    /** 会话级独立设置：先选会话，再进入该会话的模式编辑。 */
    private fun showTalkerRulePicker(context: ComponentActivity) {
        val talkers = BlockMessagesRules.current.talkers
        if (talkers.isEmpty()) {
            showToast(context, "请先在「会话名单」里选择会话")
            showRulesDialog(context)
            return
        }
        val contacts = talkers.map { talker -> pickerContactFor(talker) }
        showComposeDialog(context) {
            SingleContactSelector(
                title = "选择要单独设置的会话",
                contacts = contacts,
                initialSelectedWxId = null,
                onDismiss = { onDismiss(); showRulesDialog(context) },
                onConfirm = { talker ->
                    onDismiss()
                    showTalkerRuleDialog(context, talker)
                },
            )
        }
    }

    /** 单个会话的模式编辑：全部遮盖 / 仅关键词 / 放行。 */
    private fun showTalkerRuleDialog(context: ComponentActivity, talker: String) {
        showComposeDialog(context) {
            val existing = BlockMessagesRules.current.talkerRule(talker)
            var mode by remember { mutableStateOf(existing.mode) }
            var keywords by remember { mutableStateOf(existing.keywords.joinToString("\n")) }

            AlertDialogContent(
                textScrolls = true,
                title = { Text(pickerContactFor(talker).nickname) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        BlockTalkerMode.entries.forEach { candidate ->
                            item(key = "mode_${candidate.id}") {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = candidate.label,
                                    description = when (candidate) {
                                        BlockTalkerMode.MASK_ALL -> "该会话里对方的**所有**消息都被遮盖/屏蔽（与全局关键词无关）。"
                                        BlockTalkerMode.KEYWORD -> "只有命中下面关键词的消息才被遮盖/屏蔽。"
                                        BlockTalkerMode.PASS -> "该会话完全放行，即使它在全局会话名单里也不遮盖。"
                                    },
                                    onClick = { mode = candidate },
                                    trailingContent = {
                                        if (mode == candidate) {
                                            Text(
                                                text = "当前",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        if (mode == BlockTalkerMode.KEYWORD) {
                            item(key = "talker_keywords") {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    value = keywords,
                                    onValueChange = { keywords = it },
                                    label = { Text("该会话的关键词（每行一个）") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                )
                            }
                        }
                        item(key = "talker_clear") {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "清除该会话的独立设置",
                                description = "清除后该会话重新走全局规则。",
                                onClick = {
                                    runCatching {
                                        val rules = BlockMessagesRules.current
                                        BlockMessagesRules.save(rules.withoutTalkerRule(talker))
                                    }.onFailure { WeLogger.e(TAG, "清除会话独立规则失败", it) }
                                    showToast(context, "已清除")
                                    onDismiss()
                                    showRulesDialog(context)
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching {
                            val rules = BlockMessagesRules.current
                            BlockMessagesRules.save(
                                rules.withTalkerRule(
                                    talker,
                                    BlockTalkerRule(mode = mode, keywords = splitLines(keywords)),
                                ),
                            )
                        }.onFailure { WeLogger.e(TAG, "保存会话独立规则失败", it) }
                        showToast(context, "已保存该会话的独立设置")
                        onDismiss()
                        showRulesDialog(context)
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss(); showRulesDialog(context) }) { Text("返回") }
                },
            )
        }
    }

    /** 群成员可视化选择：选群 → 勾选成员 → 自动填入发送人名单。 */
    private fun showGroupMembersPicker(context: ComponentActivity) {
        val groups = runCatching { WeDatabaseApi.getGroups() }.getOrDefault(emptyList())
        if (groups.isEmpty()) {
            showToast(context, "没有可选的群聊")
            showRulesDialog(context)
            return
        }
        showComposeDialog(context) {
            SingleContactSelector(
                title = "选择群聊",
                contacts = groups,
                initialSelectedWxId = null,
                onDismiss = { onDismiss(); showRulesDialog(context) },
                onConfirm = { groupId ->
                    onDismiss()
                    showGroupMemberListPicker(context, groupId)
                },
            )
        }
    }

    private fun showGroupMemberListPicker(context: ComponentActivity, groupId: String) {
        val members = runCatching { WeDatabaseApi.getGroupMembers(groupId) }.getOrDefault(emptyList())
        if (members.isEmpty()) {
            showToast(context, "该群没有取到成员列表")
            showRulesDialog(context)
            return
        }
        val roomName = pickerContactFor(groupId).nickname
        showComposeDialog(context) {
            ContactsSelector(
                title = "选择群成员（$roomName）",
                contacts = members,
                initialSelectedWxIds = BlockMessagesRules.current.senderKeywords.toSet(),
                onDismiss = { onDismiss(); showRulesDialog(context) },
                onConfirm = { selected ->
                    runCatching {
                        val rules = BlockMessagesRules.current
                        BlockMessagesRules.save(rules.copy(senderKeywords = selected.toList()))
                    }.onFailure { WeLogger.e(TAG, "保存发送人名单失败", it) }
                    showToast(context, "已填入 ${selected.size} 个发送人")
                    onDismiss()
                    showRulesDialog(context)
                },
            )
        }
    }

    /** 把 wxid 解析成「头像 + 昵称/群备注」，供选择器展示。 */
    private fun pickerContactFor(wxid: String): IWeContact {
        val name = runCatching { WeDatabaseApi.getDisplayName(wxid) }.getOrDefault(wxid)
        val avatar = runCatching { WeDatabaseApi.getAvatarUrl(wxid) }.getOrDefault("")
        return MaskPickerContact(wxId = wxid, nickname = name.ifBlank { wxid }, avatarUrl = avatar)
    }

    private fun persistRules(
        talkers: Set<String>,
        keywordsText: String,
        sendersText: String,
        whitelist: Boolean,
        keywordEnabled: Boolean = BlockMessagesRules.current.keywordEnabled,
    ) {
        runCatching {
            // 【Round45】用 `previous.copy(...)` 而不是重新 new 一个对象：
            // 会话级独立规则（perTalker）在子对话框里单独保存，这里必须原样保留，
            // 否则每次「保存」都会把用户辛苦配好的每会话规则清空。
            val previous = BlockMessagesRules.current
            BlockMessagesRules.save(
                previous.copy(
                    talkers = talkers.toList(),
                    keywords = splitLines(keywordsText),
                    senderKeywords = splitLines(sendersText),
                    keywordEnabled = keywordEnabled,
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
    /** 【Round45】会话级独立规则（key = 会话 id）。与全局规则互不干扰。 */
    val perTalker: Map<String, BlockTalkerRule> = emptyMap(),
    /** 【Round45】关键词维度总开关。关掉后「会话名单」里的人 = 全部消息都被遮盖/屏蔽。 */
    val keywordEnabled: Boolean = true,
) {
    /** 是否完全没有规则（用于热路径短路）。 */
    val isEmpty: Boolean
        get() = talkers.isEmpty() && keywords.isEmpty() && senderKeywords.isEmpty() && perTalker.isEmpty()

    fun talkerRule(talker: String): BlockTalkerRule = perTalker[talker] ?: BlockTalkerRule()

    fun withTalkerRule(talker: String, rule: BlockTalkerRule): BlockMessagesRules =
        copy(perTalker = perTalker + (talker to rule))

    fun withoutTalkerRule(talker: String): BlockMessagesRules =
        copy(perTalker = perTalker - talker)

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
                        perTalker = readPerTalker(json.optJSONObject("perTalker")),
                        // 老配置里没有这个键 → 默认 true，保持升级前的行为不变
                        keywordEnabled = if (json.has("keywordEnabled")) json.optBoolean("keywordEnabled", true) else true,
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
                put("keywordEnabled", rules.keywordEnabled)
                val per = JSONObject()
                rules.perTalker.forEach { (talker, rule) ->
                    per.put(
                        talker,
                        JSONObject().apply {
                            put("mode", rule.mode.id)
                            put("keywords", JSONArray(rule.keywords))
                        },
                    )
                }
                put("perTalker", per)
            }
            WePrefs.putString(KEY, json.toString())
            // 【Round44】「屏蔽消息遮盖」按热路径走 HotPrefs 缓存读同一个 key，
            // 本进程写入后立即失效缓存，保证「改完规则马上回聊天」看到的就是新规则。
            HotPrefs.invalidate(KEY)
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

        private fun readPerTalker(obj: JSONObject?): Map<String, BlockTalkerRule> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, BlockTalkerRule>()
            val names = obj.keys()
            while (names.hasNext()) {
                val talker = names.next()
                val rule = obj.optJSONObject(talker) ?: continue
                out[talker] = BlockTalkerRule(
                    mode = BlockTalkerMode.fromId(rule.optString("mode", BlockTalkerMode.MASK_ALL.id)),
                    keywords = readArray(rule.optJSONArray("keywords")),
                )
            }
            return out
        }
    }
}

/** 单个会话的遮盖/屏蔽模式。 */
enum class BlockTalkerMode(val id: String, val label: String) {
    /** 该会话的对方消息**全部**遮盖/屏蔽。 */
    MASK_ALL("mask_all", "全部遮盖"),

    /** 只遮盖命中该会话自己关键词的消息。 */
    KEYWORD("keyword", "仅关键词"),

    /** 该会话完全放行（即使它在全局会话名单里也优先放行）。 */
    PASS("pass", "放行");

    companion object {
        fun fromId(id: String): BlockTalkerMode =
            entries.firstOrNull { it.id == id } ?: MASK_ALL
    }
}

/** 单个会话的独立规则（Round45）。 */
data class BlockTalkerRule(
    val mode: BlockTalkerMode = BlockTalkerMode.MASK_ALL,
    val keywords: List<String> = emptyList(),
)

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

            // 【Round43】dealNotify 没有 sender 入参，但群消息的通知正文是「昵称: 正文」格式，
            // 从里面把发送人抠出来喂给规则引擎 —— 否则「发送人名单」永远命中不了
            // （旧实现恒传 sender = ""，用户配了发送人规则也永远不会生效）。
            val sender = groupSenderOf(talker, rawContent)
            val content = if (sender.isNotEmpty()) {
                rawContent.substringAfter(':', rawContent).substringAfter('：', rawContent).trim()
            } else {
                rawContent
            }

            val reason = BlockMessages.matchReason(talker = talker, sender = sender, content = content)
                ?: return@hookBefore

            // 1) 吞掉通知
            result = null

            // 2) 顺带标记已读（默认开）——「屏蔽」的直觉是「我不想看到它」，
            //    只吞通知的话列表里还是会堆未读红点，用户就会认为功能没生效。
            val marked = if (BlockMessagesExtraPrefs.markReadOnBlock) {
                runCatching { WeConversationApi.markAsRead(talker) }.isSuccess
            } else {
                false
            }

            BlockMessages.recordHit(
                BlockHit(
                    talker = talker,
                    reason = reason,
                    preview = content.take(40),
                    markedRead = marked,
                    time = System.currentTimeMillis(),
                ),
            )
            WeLogger.i(
                TAG,
                "suppressing notification from $talker (reason=$reason, markedRead=$marked)",
            )
        }
    }

    /**
     * 群消息通知正文形如「群昵称: 正文」，提取发送人昵称片段；
     * 私聊/无冒号时返回空串（此时规则里只有会话与关键词会参与判定）。
     */
    private fun groupSenderOf(talker: String, rawContent: String): String {
        if (!talker.isGroupChatWxId) return ""
        val idx = rawContent.indexOfFirst { it == ':' || it == '：' }
        if (idx <= 0 || idx > 40) return ""
        return rawContent.substring(0, idx).trim()
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
    private const val KEY = "block_messages_use_whitelist"

    /**
     * 【Round44】读走 WePrefs（即时、永远最新），写顺带失效 HotPrefs 缓存 ——
     * 「屏蔽消息遮盖」在每条消息 bind 上走 hotPrefOption 读同一个 key。
     */
    var useWhitelist: Boolean
        get() = WePrefs.getBoolOrDef(KEY, false)
        set(value) {
            WePrefs.putBool(KEY, value)
            HotPrefs.invalidate(KEY)
        }
}

/**
 * 【Round43】屏蔽消息的附加行为偏好。
 */
object BlockMessagesExtraPrefs {

    /**
     * 命中屏蔽规则时，是否顺带把该会话标记为已读。
     *
     * 默认 true —— 用户对「屏蔽消息」的直觉期待是「我不想看到它」，
     * 只吞通知的话消息仍会在列表里堆未读红点，用户就会认为「没生效」。
     * 关掉后行为退回旧的「仅不弹通知」。
     */
    var markReadOnBlock: Boolean by prefOption("block_messages_mark_read", true)
}

/** 一条被拦截的记录（仅内存，供配置页展示）。 */
data class BlockHit(
    /** 会话 id。 */
    val talker: String,
    /** 命中的维度：会话 / 白名单外 / 关键词 / 发送人。 */
    val reason: String,
    /** 被拦截消息的正文摘要。 */
    val preview: String,
    /** 是否顺带标记了已读。 */
    val markedRead: Boolean,
    /** 拦截时刻（`SystemClock.elapsedRealtime()` 无关，用墙钟即可）。 */
    val time: Long,
)

/**
 * 【Round45】选择器里的「轻量联系人」——把 wxid 解析成头像 + 昵称/群备注后展示。
 */
private data class MaskPickerContact(
    override val wxId: String,
    override val nickname: String,
    override val avatarUrl: String = "",
) : IWeContact {
    override val displayName: String get() = nickname
}

/** 遮盖层配色方案（索引与 [BLOCK_MASK_THEME_LABELS] 一一对应）。 */
val BLOCK_MASK_THEME_LABELS = listOf("跟随深浅色", "纯白卡片", "深色卡片")

/**
 * 【Round45】「屏蔽消息遮盖」的外观偏好。
 *
 * 全部走 [hotPrefOption]（内存缓存 + 热路径安全）：遮盖是在**每条消息 bind** 上
 * 创建的，绝不能在这里读 SQLite。配置页写入后由各自的 setter 失效缓存。
 */
object BlockMessagesMaskPrefs {

    /**
     * 遮盖层是否显示发送者信息（头像 + 群昵称/备注）。
     *
     * 用户原话：「我要的只是消息内容被遮盖，不遮盖那个发送消息的人的头像和群昵称
     * 群备注，或者你看看可不可以在遮盖层显示被遮盖消息的人的头像和群昵称群备注」。
     */
    var showSender by hotPrefOption("block_messages_mask_show_sender", true)

    /**
     * 点按遮盖层是否可临时查看原文；再点一下重新盖回去。
     *
     * 用户原话：「我点击一下遮盖层就消失，那我再点击一下就恢复遮盖层」。
     */
    var clickToReveal by hotPrefOption("block_messages_mask_click_reveal", true)

    /** 配色方案索引，见 [BLOCK_MASK_THEME_LABELS]。 */
    var themeIndex by hotPrefOption("block_messages_mask_theme", 0)
}
