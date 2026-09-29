/*
 * AutoAcceptFriendRequests.kt — 自动通过好友申请 【Round40 · 真自动通过实装】
 *
 * 证据等级：A（Joker 侧发包链路已实证 + 逆向包字段表齐备）
 * 来源：
 *   - WeKit 1945 逆向包 `01_逆向源码/contacts/AutoAcceptFriendRequests.kt`（混淆类 g50）
 *   - `09_Hchat4功能逆向/AutoAcceptFriendRequests.kt`（Hchat 4 原版，含三级定位 + 三构造签名）
 *   - `07_反编译产物dump/Lm27.txt`（★ 好友申请 XML 多套兼容字段表，本文件解析器的直接依据）
 *   - `07_反编译产物dump/Lxa0.txt`（打招呼模板变量表）
 *   - `07_反编译产物dump/Lm27.txt` 的 g(Ld14;) 解析入口
 *
 * 对应日志：「新增: 自动通过好友申请」
 *
 * ★ 行为：
 *   - 微信收到好友申请（message 表 type=FRIEND_VERIFY=37 插入）→ 解析申请 XML →
 *     命中开关即调用 WeChat 协议层 verifyUser 真正通过，而不是只记录。
 *   - 通过后可选：自动打标签 / 自动打招呼。
 *
 * ★ Round40 相对 Round30 阶段 1 的关键变化：
 *   阶段 1 只 `WeLogger.i` 记录，不实际接受，理由是「无 DexKit 委托时误操作用户联系人」。
 *   本轮核查发现 **Joker 侧发包链路早已存在且被多处使用**：
 *     `WeContactApi.verifyUser(userId, ticket, scene, privacy)`（WeContactApi.kt:216）
 *       → `ctorNetSceneVerifyUser.newInstance(3, userId, ticket, scene, "", privacy, null, null)`
 *       → `WeNetSceneApi.sendNetScene(netScene)`（WeNetSceneApi.kt:18）
 *     锚点串 `"MicroMsg.NetSceneVerifyUser.dkverify"` 由 Joker 自有 DexKit 解析（WeContactApi.kt:103）。
 *   因此「逆向笔记 §七：WeKit 不用 verifyuser URL 明文，需换锚点」这条警告对本仓库不适用
 *   —— Joker 的锚点不是 URL 明文，而是同 Hchat 的 dkverify 语义串，早已可用。
 *
 * ★ 配置面（key 与 Hchat `hchat_pref_keys_all.txt` 对齐，保留原 key 名以便沿用用户既有设置）：
 *   auto_accept_enable / auto_accept_delay_ms / auto_accept_tag_enable / auto_accept_tag_name
 *   auto_accept_remark_*（本轮不做，Joker 无备注写入 API）/ auto_accept_reply_text（打招呼模板）
 *
 * ★ 整合铁律：
 *   - 包名 dev.joker.*，品牌 Joker
 *   - 默认关闭；用户主动启用才接管
 *   - 拿不到 ticket / scene 等辅助信息时只降级记录，绝不抛异常（不误操作用户联系人）
 *   - 通过动作在独立调度线程执行，绝不在数据库回调线程做网络发包（避免卡顿）
 */
package dev.joker.features.items.contacts

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
import dev.joker.R
import dev.joker.features.api.core.WeContactApi
import dev.joker.features.api.core.WeContactLabelApi
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.api.core.WeDatabaseListenerApi
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.api.core.models.MessageType
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.preferences.WePrefs.Companion.prefOption
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.TextButton
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.content.m3.SwitchWidget
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.WeLogger
import dev.joker.utils.android.showToast
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 自动通过好友申请（1945 新增，感谢 Hchat）
 *
 * 监听 message 表 FRIEND_VERIFY 插入 → 解析申请 XML → 真正调用协议层通过 → 通过后自动化。
 */
object AutoAcceptFriendRequests : ClickableFeature() {

    override val technicalId: String = "自动通过好友申请"
    override val nameRes: Int = R.string.feature_contacts_auto_accept_friend_requests_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes: Int =
        R.string.feature_contacts_auto_accept_friend_requests_description

    /**
     * 默认关闭 —— 自动通过好友申请属于不可逆的联系人操作，必须由用户显式开启。
     * 关闭时仍保留监听与日志（阶段 1 行为），方便用户观察规则是否命中。
     */
    override val defaultEnabled: Boolean = false

    private const val TAG = "AutoAcceptFriendRequests"

    /** 好友申请的 VerifyUser 场景值（Hchat 侧 const/4 v6, 0x3 魔数，跨版本未变）。 */
    private const val DEFAULT_VERIFY_SCENE = 3

    /** 打招呼模板默认值（用户可在设置页修改）。 */
    private const val DEFAULT_GREET_TEMPLATE = "你好，已收到好友申请"

    // ═══════════════════════════════════════════════════════════════
    //  配置（Hchat key 名对齐）
    // ═══════════════════════════════════════════════════════════════

    /** 是否真正自动通过对的好友申请（关 = 只记录，即 Round30 阶段 1 行为）。 */
    internal var autoAcceptAutoAccept: Boolean by prefOption("auto_accept_enable", false)

    /** 通过延迟，毫秒。0 = 立即。 */
    internal var autoAcceptDelayMs: Long by prefOption("auto_accept_delay_ms", 0L)

    /** 通过后是否自动打标签。 */
    internal var autoAcceptTagEnable: Boolean by prefOption("auto_accept_tag_enable", false)

    /** 自动标签名（为空则跳过）。 */
    internal var autoAcceptTagName: String by prefOption("auto_accept_tag_name", "")

    /** 通过后是否自动打招呼。 */
    internal var autoAcceptGreetEnable: Boolean by prefOption("auto_accept_greet_enable", false)

    /** 打招呼文案模板，支持 `$nickname` / `$talker` / `$date` / `$time`。 */
    internal var autoAcceptReplyText: String by prefOption(
        "auto_accept_reply_text",
        DEFAULT_GREET_TEMPLATE,
    )

    /** 打招呼延迟，毫秒。 */
    internal var autoAcceptGreetDelayMs: Long by prefOption("auto_accept_greet_delay_ms", 0L)

    /** 每个申请人只自动处理一次的护栏（防重复插入触发重复通过）。 */
    internal val handledApplicants = HashSet<String>()

    /** 通过 / 打招呼均在独立线程，绝不阻塞数据库回调线程。 */
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, TAG).apply { isDaemon = true }
    }

    // ═══════════════════════════════════════════════════════════════
    //  申请 XML 字段名兼容表
    //
    //  直接依据 `07_反编译产物dump/Lm27.txt`（混淆类 m27，好友申请解析器）
    //  的多套字段名。微信不同版本 / 不同来源（个人号、群邀请、企业号、
    //  陌生人、OpenIM）写入的 XML 字段名并不统一，因此必须逐个候选尝试。
    // ═══════════════════════════════════════════════════════════════

    private val TICKET_KEYS = listOf(
        "verifyticket", "verify_ticket", "ticket", "antispamticket", "antispam_ticket",
    )
    private val SCENE_KEYS = listOf("sceneid", "scene_id", "scene", "scence")
    private val WXID_KEYS = listOf("encryptusername", "encryptuser", "fromusername", "username")
    private val NICKNAME_KEYS = listOf("fromnickname", "nickname")

    /** `<tag>value</tag>` 形式。 */
    private val TAG_VALUE_RE = Pattern.compile("<\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*>([^<]*)<\\s*/\\s*\\1\\s*>")

    /** `name="value"` / `name='value'` 形式。 */
    private val ATTR_RE = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*['\"]([^'\"]*)['\"]")

    // ═══════════════════════════════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(autoAcceptFriendRequestsInsertListener)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(autoAcceptFriendRequestsInsertListener)
        synchronized(handledApplicants) { handledApplicants.clear() }
    }

    // ═══════════════════════════════════════════════════════════════
    //  配置界面（Round41 新增）
    //
    //  用户实机反馈「开了开关却没有地方配置、无法验证生效」：本类原来只是
    //  SwitchFeature，设置页那一行只有开关。现在升为 ClickableFeature：
    //  点正文进配置页，开关照旧管启停。
    // ═══════════════════════════════════════════════════════════════

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var autoAccept by remember { mutableStateOf(autoAcceptAutoAccept) }
            var delayMs by remember { mutableStateOf(autoAcceptDelayMs.toString()) }
            var tagEnable by remember { mutableStateOf(autoAcceptTagEnable) }
            var tagName by remember { mutableStateOf(autoAcceptTagName) }
            var greetEnable by remember { mutableStateOf(autoAcceptGreetEnable) }
            var greetText by remember { mutableStateOf(autoAcceptReplyText) }
            var greetDelayMs by remember { mutableStateOf(autoAcceptGreetDelayMs.toString()) }

            AlertDialogContent(
                title = { Text(technicalId) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "auto_accept") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "真正自动通过",
                                description = "关闭 = 只记录日志（不会替你做任何联系人操作）；" +
                                    "开启后收到好友申请立即调用协议层通过。",
                                checked = autoAccept,
                                onCheckedChange = { autoAccept = it },
                            )
                        }
                        item(key = "delay") {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                value = delayMs,
                                onValueChange = { delayMs = it.filter(Char::isDigit).take(7) },
                                label = { Text("通过延迟（毫秒，0 = 立即）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                        item(key = "tag_enable") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "通过后自动打标签",
                                checked = tagEnable,
                                onCheckedChange = { tagEnable = it },
                            )
                        }
                        if (tagEnable) {
                            item(key = "tag_name") {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    value = tagName,
                                    onValueChange = { tagName = it },
                                    label = { Text("标签名") },
                                    singleLine = true,
                                )
                            }
                        }
                        item(key = "greet_enable") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "通过后自动打招呼",
                                checked = greetEnable,
                                onCheckedChange = { greetEnable = it },
                            )
                        }
                        if (greetEnable) {
                            item(key = "greet_text") {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    value = greetText,
                                    onValueChange = { greetText = it },
                                    label = { Text("打招呼文案（支持 \$nickname / \$talker / \$date / \$time）") },
                                    )
                            }
                            item(key = "greet_delay") {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    value = greetDelayMs,
                                    onValueChange = { greetDelayMs = it.filter(Char::isDigit).take(7) },
                                    label = { Text("打招呼延迟（毫秒）") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }
                        item(key = "hint") {
                            Text(
                                text = "提示：通过好友申请需要申请里带有 ticket（部分版本的申请不带），" +
                                    "拿不到关键信息时只记录日志、不会误操作用户联系人。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        autoAcceptAutoAccept = autoAccept
                        autoAcceptDelayMs = delayMs.toLongOrNull() ?: 0L
                        autoAcceptTagEnable = tagEnable
                        autoAcceptTagName = tagName
                        autoAcceptGreetEnable = greetEnable
                        if (greetText.isNotBlank()) autoAcceptReplyText = greetText
                        autoAcceptGreetDelayMs = greetDelayMs.toLongOrNull() ?: 0L
                        showToast(context, if (autoAccept) "已保存：开启真自动通过" else "已保存：仅记录模式")
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  申请解析
    // ═══════════════════════════════════════════════════════════════

    /** 一条好友申请的可解析视图。 */
    internal data class FriendRequest(
        val wxid: String,
        val ticket: String?,
        val scene: Int,
        val nickname: String?,
    )

    /**
     * 从申请 XML 里按多套候选字段名解析出 wxid / ticket / scene / 昵称。
     *
     * 解析失败只返回 null（降级为记录），绝不让异常冒泡到数据库回调线程。
     */
    internal fun parseFriendRequest(content: String?, fallbackTalker: String?): FriendRequest? {
        if (content.isNullOrBlank()) {
            // 无正文时仍可用 talker 兜底：ticket/scene 缺失则无法通过，只记录。
            val talker = fallbackTalker?.takeIf { it.isNotBlank() } ?: return null
            return FriendRequest(talker, null, DEFAULT_VERIFY_SCENE, null)
        }

        val fields = HashMap<String, String>()
        runCatching {
            TAG_VALUE_RE.matcher(content).let { m ->
                while (m.find()) {
                    fields[m.group(1)!!.lowercase(Locale.ROOT)] = m.group(2)!!.trim()
                }
            }
            ATTR_RE.matcher(content).let { m ->
                while (m.find()) {
                    fields.putIfAbsent(m.group(1)!!.lowercase(Locale.ROOT), m.group(2)!!.trim())
                }
            }
        }.onFailure { WeLogger.w(TAG, "failed to scan friend-request xml", it) }

        fun pick(keys: List<String>): String? =
            keys.firstNotNullOfOrNull { key -> fields[key]?.takeIf { it.isNotEmpty() } }

        val wxid = pick(WXID_KEYS)
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackTalker?.takeIf { it.isNotBlank() }
            ?: return null

        val scene = pick(SCENE_KEYS)?.toIntOrNull() ?: DEFAULT_VERIFY_SCENE

        return FriendRequest(
            wxid = wxid,
            ticket = pick(TICKET_KEYS),
            scene = scene,
            nickname = pick(NICKNAME_KEYS),
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  处理一条好友申请
    // ═══════════════════════════════════════════════════════════════

    internal fun handleRequest(request: FriendRequest) {
        if (!autoAcceptAutoAccept) {
            WeLogger.i(
                TAG,
                "收到好友申请：wxid=${request.wxid}" +
                    (if (request.ticket.isNullOrEmpty()) "（无 ticket，仅记录）" else "") +
                    "（自动通过未开启，当前仅记录）",
            )
            return
        }

        if (request.ticket.isNullOrEmpty()) {
            WeLogger.w(TAG, "通过好友申请失败: ticket 为空 wxid=${request.wxid}（仅记录）")
            return
        }

        val delay = autoAcceptDelayMs.coerceAtLeast(0L)
        executor.schedule(
            {
                runCatching { accept(request) }
                    .onFailure { WeLogger.e(TAG, "accept ${request.wxid} failed", it) }
            },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    /** 真正通过：调用协议层 verifyUser。 */
    private fun accept(request: FriendRequest) {
        val ticket = request.ticket ?: return
        WeContactApi.verifyUser(request.wxid, ticket, request.scene, 0)
        WeLogger.i(TAG, "已通过好友申请：wxid=${request.wxid} scene=${request.scene}")
        onAccepted(request)
    }

    /** 通过后的自动化：打标签 / 打招呼。每一步失败都只降级，不影响其它步骤。 */
    private fun onAccepted(request: FriendRequest) {
        val displayName = runCatching {
            WeDatabaseApi.getDisplayName(request.wxid)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != request.wxid }
            ?: request.nickname?.takeIf { it.isNotBlank() }
            ?: request.wxid

        applyLabel(request.wxid)

        if (autoAcceptGreetEnable) {
            val greetDelay = autoAcceptGreetDelayMs.coerceAtLeast(0L)
            executor.schedule(
                { sendGreeting(request.wxid, displayName) },
                greetDelay,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** 自动打标签：先确保标签存在，再追加到该联系人的标签集合（不覆盖既有标签）。 */
    private fun applyLabel(wxid: String) {
        if (!autoAcceptTagEnable) return
        val tagName = autoAcceptTagName.trim()
        if (tagName.isEmpty()) return

        runCatching {
            val existing = WeContactLabelApi.getLabelNamesForContact(wxid)
            if (existing.any { it == tagName }) return@runCatching
            val labelId = WeContactLabelApi.createLabel(tagName)
            if (labelId == null) {
                WeLogger.w(TAG, "创建标签失败：$tagName")
                return@runCatching
            }
            WeContactLabelApi.modifyLabel(wxid, (existing + tagName).distinct())
        }.onFailure { WeLogger.w(TAG, "打标签失败：wxid=$wxid tag=$tagName", it) }
    }

    /** 自动打招呼（模板变量替换后发送）。 */
    private fun sendGreeting(wxid: String, displayName: String) {
        val template = autoAcceptReplyText
        if (template.isBlank()) return

        val text = renderTemplate(template, wxid, displayName)
        runCatching {
            val ok = WeMessageApi.sendText(wxid, text)
            if (!ok) WeLogger.w(TAG, "打招呼发送失败：wxid=$wxid")
        }.onFailure { WeLogger.w(TAG, "打招呼异常：wxid=$wxid", it) }
    }

    /** 模板变量：`$nickname` / `$talker` / `$date` / `$time`（对齐 Lxa0 的变量风格）。 */
    private fun renderTemplate(template: String, wxid: String, displayName: String): String {
        val now = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        return template
            .replace("\$nickname", displayName)
            .replace("\$talker", wxid)
            .replace("\$date", date)
            .replace("\$time", time)
    }
}

/**
 * 监听 message 表 FRIEND_VERIFY 插入 → 解析好友申请。
 *
 * 注意：回调运行在数据库线程，此处只做轻量解析 + 投递到独立 executor，
 * 绝不在此线程做网络发包或数据库写操作。
 */
internal val autoAcceptFriendRequestsInsertListener = WeDatabaseListenerApi.IInsertListener { table, values ->
    if (table != "message") return@IInsertListener

    val type = runCatching { values.getAsInteger("type") }.getOrNull() ?: return@IInsertListener
    if (type != MessageType.FRIEND_VERIFY.code) return@IInsertListener

    val talker = runCatching { values.getAsString("talker") }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: return@IInsertListener

    // 每个申请人只处理一次，避免重复插入导致重复通过。
    val firstTime = synchronized(AutoAcceptFriendRequests.handledApplicants) {
        AutoAcceptFriendRequests.handledApplicants.add(talker)
    }
    if (!firstTime) return@IInsertListener

    val content = runCatching { values.getAsString("content") }.getOrNull()
    val request = runCatching {
        AutoAcceptFriendRequests.parseFriendRequest(content, talker)
    }.getOrNull() ?: return@IInsertListener

    runCatching { AutoAcceptFriendRequests.handleRequest(request) }
        .onFailure { WeLogger.w("AutoAcceptFriendRequests", "handle friend request failed", it) }
}
