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
import android.os.SystemClock
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
    fun matchReason(
        talker: String,
        sender: String,
        content: String,
        senderAliases: List<String> = emptyList(),
    ): String? =
        matchReason(
            rules = BlockMessagesRules.current,
            useWhitelist = BlockMessagesWhitelistPrefs.useWhitelist,
            talker = talker,
            sender = sender,
            content = content,
            senderAliases = senderAliases,
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
        senderAliases: List<String> = emptyList(),
    ): String? {
        // ── ① 会话级独立规则最高优先（Round45 新增，Round46 增加成员粒度）────────
        // 用户原话：「这个遮盖如果遇到多个会话是不是不同会话要分开设置，
        // 确保不出现被全部遮盖的 bug」。因此每个会话可以有**自己的**模式，
        // 与全局规则完全隔离：一个会话设成「全部遮盖」不会影响另一个会话。
        if (talker.isNotEmpty()) {
            rules.perTalker[talker]?.let { per ->
                return when (per.mode) {
                    BlockTalkerMode.PASS -> null
                    BlockTalkerMode.MASK_ALL -> "该会话全部"
                    // 【Round46】只遮该会话里点名的成员；成员名单为空 → 不命中任何消息
                    // （保存对话框时若没选人，绝不能把整个会话盖掉）。
                    BlockTalkerMode.MASK_MEMBERS ->
                        if (per.members.isNotEmpty() && senderHit(per.members, sender, senderAliases)) {
                            "该会话指定成员"
                        } else {
                            null
                        }
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
        if (senderHit(rules.senderKeywords, sender, senderAliases)) {
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

    /**
     * 发送人 / 会话内成员命中判定（Round46）。
     *
     * 名单里存的是**wxid**（「群成员可视化选择」给的就是 wxid），但
     * [BlockMessagesRuntime] 的通知路径从通知正文里只能抠出**昵称/群昵称**
     * （`dealNotify` 只有 talker + content 两个入参）。
     * 旧实现只拿昵称去比对 wxid 名单 —— **永远不可能命中**，这正是用户反复反馈的
     * 「发送人名单配了、开关也开了，就是一点效果没有」的根因。
     *
     * 现在同时比对：① 发送人本身（聊天界面路径给的就是 wxid，本来就对）；
     * ② [aliases]（通知路径把昵称反查出的 wxid / 昵称本身）。
     *
     * @param aliases 允许为空：拿不到辅助信息只是少一条判定路径，绝不抛异常。
     */
    private fun senderHit(list: List<String>, sender: String, aliases: List<String>): Boolean {
        if (list.isEmpty()) return false
        fun hits(candidate: String): Boolean {
            if (candidate.isEmpty()) return false
            return list.any { rule ->
                rule.isNotEmpty() && (candidate.equals(rule, ignoreCase = true) || candidate.contains(rule, ignoreCase = true))
            }
        }
        if (hits(sender)) return true
        for (alias in aliases) if (hits(alias)) return true
        return false
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
            // 【Round45b】关键词总开关的草稿必须提升到本层（这里才是可组合作用域，才 remember 得了）。
            // 之前把它 remember 在 `item(key = "keyword_switch") { }` 内部，导致下面控制
            // 「关键词输入框」显隐时只能读持久化值 —— 而持久化值不是 snapshot state，
            // 拨动开关不会让 SegmentedColumn 重组，输入框显隐与开关状态对不上（要重开
            // 对话框才同步）。提升到本层后 `if (keywordOn)` 会被登记成 SegmentedColumn 的
            // 依赖，开关与输入框实时联动；顺带省掉一次 per-recomposition 的 JSON 解析。
            var keywordOn by remember { mutableStateOf(initial.keywordEnabled) }

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
                                    persistRules(talkers, keywords, senders, whitelist, keywordOn)
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
                                    "为某个会话单独指定「**只遮我点名的成员** / 全部遮盖 / 仅关键词 / 放行」，与其它会话互不影响 —— 多个会话各屏蔽各的人时用这个。"
                                } else {
                                    "已单独设置 ${ruleMap.size} 个会话（点击修改）"
                                },
                                onClick = {
                                    // 先落盘当前草稿，切换页面回来不丢编辑
                                    persistRules(talkers, keywords, senders, whitelist, keywordOn)
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
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "启用关键词遮盖",
                                description = "开启：只有命中关键词的消息才被遮盖/屏蔽；" +
                                    "关闭：会话名单里的人「所有消息」一律遮盖/屏蔽（关键词输入框自动收起）。",
                                checked = keywordOn,
                                onCheckedChange = {
                                    keywordOn = it
                                    persistRules(talkers, keywords, senders, whitelist, it)
                                },
                            )
                        }
                        if (keywordOn) {
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
                                description = "先选一个群，再勾选要遮盖的成员。选完会让你选作用域：默认「只在该会话生效」（推荐，多会话互不影响），也可以选「所有会话都遮盖」。",
                                onClick = {
                                    persistRules(talkers, keywords, senders, whitelist, keywordOn)
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
                                    "默认关闭：只吞通知、不动未读角标。",
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
                        persistRules(talkers, keywords, senders, whitelist, keywordOn)
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
        val rules = BlockMessagesRules.current
        val talkers = rules.talkers
        if (talkers.isEmpty()) {
            showToast(context, "请先在「会话名单」里选择会话")
            showRulesDialog(context)
            return
        }
        // 【Round46】列表里直接标出每个会话当前的独立规则，避免「配了哪个会话、什么模式」全靠记
        val contacts = talkers.map { talker ->
            pickerContactFor(talker, ruleSummary(talker, rules))
        }
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

    /** 单个会话的模式编辑：仅指定成员 / 全部遮盖 / 仅关键词 / 放行。 */
    private fun showTalkerRuleDialog(context: ComponentActivity, talker: String) {
        showComposeDialog(context) {
            val existing = BlockMessagesRules.current.talkerRule(talker)
            var mode by remember { mutableStateOf(existing.mode) }
            var keywords by remember { mutableStateOf(existing.keywords.joinToString("\n")) }
            var members by remember { mutableStateOf(existing.members) }
            val isGroup = talker.isGroupChatWxId

            AlertDialogContent(
                textScrolls = true,
                title = { Text(pickerContactFor(talker).nickname) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        BlockTalkerMode.entries.forEach { candidate ->
                            // 单聊没有「成员」概念：不展示该模式，避免选了之后一脸问号
                            if (candidate == BlockTalkerMode.MASK_MEMBERS && !isGroup) return@forEach
                            item(key = "mode_${candidate.id}") {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = candidate.label,
                                    description = when (candidate) {
                                        BlockTalkerMode.MASK_MEMBERS -> "只遮盖该会话里**你点名的成员**，其他人照常显示。多会话各配各的人时用这个。"
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
                        if (isGroup && mode == BlockTalkerMode.MASK_MEMBERS) {
                            item(key = "talker_members_pick") {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = if (members.isEmpty()) "选择要遮盖的成员" else "选择要遮盖的成员（已选 ${members.size} 人）",
                                    description = if (members.isEmpty()) {
                                        "还没选人 —— 此时该规则不遮盖任何消息。"
                                    } else {
                                        "点这里可以继续增删；下面点名单里的成员也可以直接移除。"
                                    },
                                    onClick = {
                                        onDismiss()
                                        showTalkerMembersPicker(context, talker)
                                    },
                                )
                            }
                            members.forEach { member ->
                                item(key = "talker_member_$member") {
                                    BaseWidget(
                                        iconPlaceholder = false,
                                        title = displayNameFor(member),
                                        description = "点按把 ta 从本会话的遮盖名单里移除",
                                        onClick = { members = members.filterNot { it == member } },
                                    )
                                }
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
                                    BlockTalkerRule(
                                        mode = mode,
                                        keywords = splitLines(keywords),
                                        members = members,
                                    ),
                                ),
                            )
                        }.onFailure { WeLogger.e(TAG, "保存会话独立规则失败", it) }
                        showToast(
                            context,
                            when {
                                mode == BlockTalkerMode.MASK_MEMBERS && members.isEmpty() ->
                                    "已保存（还没选成员，暂不遮盖任何消息）"
                                mode == BlockTalkerMode.MASK_MEMBERS -> "已保存：只遮盖这 ${members.size} 个成员"
                                else -> "已保存该会话的独立设置"
                            },
                        )
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

    /**
     * 【Round46】会话内「只遮这几个成员」的成员选择器。
     *
     * 选完**立即写盘**（并失效 HotPrefs 缓存），然后回到该会话的模式对话框继续编辑 ——
     * 与其余对话框的「关掉当前 → 打开下一个」的节奏保持一致，避免嵌套弹窗。
     */
    private fun showTalkerMembersPicker(context: ComponentActivity, talker: String) {
        val members = runCatching { WeDatabaseApi.getGroupMembers(talker) }.getOrDefault(emptyList())
        if (members.isEmpty()) {
            showToast(context, "该会话取不到成员列表（可能不是群聊）")
            showTalkerRuleDialog(context, talker)
            return
        }
        val current = BlockMessagesRules.current.talkerRule(talker)
        val roomName = pickerContactFor(talker).nickname
        showComposeDialog(context) {
            ContactsSelector(
                title = "选择要遮盖的成员（$roomName）",
                contacts = members,
                initialSelectedWxIds = current.members.toSet(),
                onDismiss = {
                    onDismiss()
                    showTalkerRuleDialog(context, talker)
                },
                onConfirm = { selected ->
                    runCatching {
                        val rules = BlockMessagesRules.current
                        val rule = rules.talkerRule(talker)
                        BlockMessagesRules.save(
                            rules.withTalkerRule(
                                talker,
                                rule.copy(
                                    mode = BlockTalkerMode.MASK_MEMBERS,
                                    members = selected.toList(),
                                ),
                            ),
                        )
                    }.onFailure { WeLogger.e(TAG, "保存会话内成员名单失败", it) }
                    showToast(context, "已保存 ${selected.size} 个成员（仅本会话生效）")
                    onDismiss()
                    showTalkerRuleDialog(context, talker)
                },
            )
        }
    }

    /** wxid → 展示名（失败退回 wxid 本身，绝不抛异常）。 */
    private fun displayNameFor(wxid: String): String =
        runCatching { WeDatabaseApi.getDisplayName(wxid) }.getOrDefault(wxid).ifBlank { wxid }

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
                    // 【Round46】不再闷头塞进全局名单 —— 让用户选作用域（默认推荐「只在本会话生效」）
                    onDismiss()
                    showMemberScopeDialog(context, groupId, roomName, selected.toList())
                },
            )
        }
    }

    /**
     * 【Round46】选完群成员后决定作用域。
     *
     * 用户实测痛点：多个会话要各屏蔽各的人 —— 旧流程把这些成员塞进**全局**发送人名单，
     * 等于「这个人在所有会话里都被遮住」，正是「第二个会话配完全员都被屏蔽/影响别的会话」
     * 的观感来源。现在默认推荐存成**该会话专属规则（仅指定成员）**，
     * 全局名单降级为需要用户显式选择的第二选项。
     */
    private fun showMemberScopeDialog(
        context: ComponentActivity,
        groupId: String,
        roomName: String,
        members: List<String>,
    ) {
        if (members.isEmpty()) {
            showRulesDialog(context)
            return
        }
        showComposeDialog(context) {
            AlertDialogContent(
                textScrolls = true,
                title = { Text("这 ${members.size} 个人在哪里生效？") },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "scope_talker") {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "只在这个会话里遮盖（推荐）",
                                description = "存成「$roomName」的专属规则：只遮这 ${members.size} 个人，该会话其他人照常看得到；其它会话完全不受影响。",
                                onClick = {
                                    runCatching {
                                        val rules = BlockMessagesRules.current
                                        val rule = rules.talkerRule(groupId)
                                        BlockMessagesRules.save(
                                            rules.withTalkerRule(
                                                groupId,
                                                rule.copy(
                                                    mode = BlockTalkerMode.MASK_MEMBERS,
                                                    members = members,
                                                ),
                                            ),
                                        )
                                    }.onFailure { WeLogger.e(TAG, "保存会话专属成员名单失败", it) }
                                    showToast(context, "已设为「$roomName」专属：只遮这 ${members.size} 个人")
                                    onDismiss()
                                    showRulesDialog(context)
                                },
                            )
                        }
                        item(key = "scope_global") {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "所有会话里都遮盖这些人",
                                description = "存进全局发送人名单：这 ${members.size} 个人在**任何**会话里发的消息都会被遮盖/屏蔽。",
                                onClick = {
                                    runCatching {
                                        val rules = BlockMessagesRules.current
                                        BlockMessagesRules.save(rules.copy(senderKeywords = members))
                                    }.onFailure { WeLogger.e(TAG, "保存发送人名单失败", it) }
                                    showToast(context, "已填入全局发送人名单（${members.size} 人）")
                                    onDismiss()
                                    showRulesDialog(context)
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss(); showGroupMemberListPicker(context, groupId) }) {
                        Text("返回重选")
                    }
                },
            )
        }
    }

    /** 把 wxid 解析成「头像 + 昵称/群备注」，供选择器展示。 */
    private fun pickerContactFor(wxid: String, note: String = ""): IWeContact {
        val name = runCatching { WeDatabaseApi.getDisplayName(wxid) }.getOrDefault(wxid)
        val avatar = runCatching { WeDatabaseApi.getAvatarUrl(wxid) }.getOrDefault("")
        val label = name.ifBlank { wxid }
        return MaskPickerContact(
            wxId = wxid,
            nickname = if (note.isBlank()) label else "$label · $note",
            avatarUrl = avatar,
        )
    }

    /** 【Round46】会话当前独立规则的一句话摘要（未设置 = 跟随全局）。 */
    private fun ruleSummary(talker: String, rules: BlockMessagesRules): String {
        val rule = rules.perTalker[talker] ?: return "跟随全局"
        return when (rule.mode) {
            BlockTalkerMode.MASK_MEMBERS -> if (rule.members.isEmpty()) "仅指定成员（未选人）" else "仅指定成员 ${rule.members.size} 人"
            BlockTalkerMode.MASK_ALL -> "全部遮盖"
            BlockTalkerMode.KEYWORD -> "仅关键词"
            BlockTalkerMode.PASS -> "放行"
        }
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
                            // 【Round46】会话内「仅指定成员」名单（空数组也必须写，
                            // 保证老/新版本互读时语义不歧义）
                            put("members", JSONArray(rule.members))
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
                    members = readArray(rule.optJSONArray("members")),
                )
            }
            return out
        }
    }
}

/** 单个会话的遮盖/屏蔽模式。 */
enum class BlockTalkerMode(val id: String, val label: String) {
    /**
     * 【Round46】只遮盖该会话里**指定成员**的消息（推荐模式，也是新建规则的默认值）。
     *
     * 用户第 45 轮实测口径：「多个会话各自屏蔽各自的人」——第一个会话配好正常，
     * 第二个会话一保存竟然**整个会话的人都看不见了**。根因就是旧枚举只有「全部遮盖」，
     * 且新建规则的默认模式是 MASK_ALL：进对话框没动模式直接点保存 = 全遮。
     * 现在默认模式是 MASK_MEMBERS 且**成员为空时恒不命中**（保存不动 = 零副作用）。
     */
    MASK_MEMBERS("mask_members", "仅指定成员"),

    /** 该会话的对方消息**全部**遮盖/屏蔽。 */
    MASK_ALL("mask_all", "全部遮盖"),

    /** 只遮盖命中该会话自己关键词的消息。 */
    KEYWORD("keyword", "仅关键词"),

    /** 该会话完全放行（即使它在全局会话名单里也优先放行）。 */
    PASS("pass", "放行");

    companion object {
        /**
         * 反序列化。
         *
         * 注意：`mode` 键缺失时退回 [MASK_ALL]（老配置里一定带 mode，缺失只可能是手工改过
         * 存储的场景，此时保守地按老行为处理）。新建规则请用 [BlockTalkerRule] 的默认值，
         * 那条路径走的是 [MASK_MEMBERS]。
         */
        fun fromId(id: String): BlockTalkerMode =
            entries.firstOrNull { it.id == id } ?: MASK_ALL
    }
}

/**
 * 单个会话的独立规则（Round45 引入 / Round46 增加成员粒度）。
 *
 * @param members 仅 [BlockTalkerMode.MASK_MEMBERS] 生效：该会话里要遮盖的成员名单
 *   （存 **wxid**，与选择器一致；通知路径用「昵称 → wxid」反查补齐，见
 *   [BlockMessagesRuntime.senderAliasesOf]）。空名单 = 该规则不命中任何消息。
 */
data class BlockTalkerRule(
    val mode: BlockTalkerMode = BlockTalkerMode.MASK_MEMBERS,
    val keywords: List<String> = emptyList(),
    val members: List<String> = emptyList(),
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

            // 【Round46】通知路径只能拿到「昵称」，而名单里存的是 wxid：
            // 这里把昵称反查成 wxid 一起喂给规则引擎，否则「发送人/会话内指定成员」
            // 在通知路径上永远命中不了（用户实测「配了没效果」的根因）。
            val aliases = senderAliasesOf(talker, sender)
            val reason = BlockMessages.matchReason(
                talker = talker,
                sender = sender,
                content = content,
                senderAliases = aliases,
            ) ?: return@hookBefore

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

    /**
     * 【Round46】把通知正文里的**群昵称**反查成 wxid，供「发送人 / 会话内指定成员」名单命中。
     *
     * 为什么必须做：`dealNotify` 只有 (talker, content)，发送人只能从「昵称: 正文」里抠出昵称；
     * 而选择器存进规则的是 wxid。两者不在同一命名空间，旧实现因此永远不命中。
     *
     * 成本控制：
     *  - 只在**群聊**上做，且带上限 64 条的 LRU + 120s TTL（同一发送人反复发消息只查一次库）；
     *  - 通知路径（不是逐条 bind 的热路径），且只在准备判定前查一次；
     *  - 任何失败只是返回空列表（降级为「不比别名」），绝不抛异常、绝不吞掉通知链。
     */
    private val aliasCache = object : LinkedHashMap<String, Pair<Long, List<String>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<String>>>?) =
            size > 64
    }

    private fun senderAliasesOf(talker: String, sender: String): List<String> {
        if (sender.isEmpty() || !talker.isGroupChatWxId) return emptyList()
        val key = "$talker|$sender"
        val now = SystemClock.elapsedRealtime()
        synchronized(aliasCache) {
            aliasCache[key]?.let { (stamp, value) ->
                if (now - stamp < ALIAS_TTL_MS) return value
            }
        }
        val resolved = runCatching {
            WeDatabaseApi.getGroupMembers(talker)
                .filter { contact ->
                    val name = contact.nickname
                    name.isNotEmpty() &&
                        (name == sender || name.endsWith(sender) || sender.endsWith(name))
                }
                .map { it.wxId }
                .filter { it.isNotEmpty() }
                .distinct()
        }.getOrDefault(emptyList())
        synchronized(aliasCache) {
            aliasCache.remove(key)
            aliasCache[key] = now to resolved
        }
        return resolved
    }

    private const val ALIAS_TTL_MS = 120_000L

    override fun onDisable() {
        // Joker 自己的 hook 框架会自动卸载 dexMethod 委托；本类无额外状态
        synchronized(aliasCache) { aliasCache.clear() }
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
     * 【第 47 轮】默认从 true 改成 **false**：第 46 轮实机反馈「明明有消息进来，
     * 会话列表角标全部是空的」，日志侧证据是宿主自己的未读总数长期为 0
     * （`saveTotalUnreadMsg 0` / `getUnreadConversationCursor ... []`）—— 消息被
     * 「屏蔽时顺带标记已读」静默清掉了未读。用户要的是「屏蔽通知」，不是「清掉我的未读」，
     * 所以默认改成只吞通知；需要「屏蔽即已读」的可以显式打开（配置页有开关）。
     */
    var markReadOnBlock: Boolean by prefOption("block_messages_mark_read", false)
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
