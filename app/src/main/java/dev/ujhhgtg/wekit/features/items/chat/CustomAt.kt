package dev.ujhhgtg.wekit.features.items.chat

import android.util.Base64
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Alternate_email
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Format_list_numbered
import com.composables.icons.materialsymbols.outlined.Format_quote
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Sort
import com.composables.icons.materialsymbols.outlined.Star
import com.composables.icons.materialsymbols.outlined.Swap_vert
import com.composables.icons.materialsymbols.outlined.Tune
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MultiSelectSupport
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 群聊自定义艾特（CustomAt）
 *
 * 移植自脚本「群聊自定义艾特」(WauxV 插件)，微信 8.0.72 验证逻辑。
 *
 * 能力：
 *  - 长按群消息 →「专属艾特」为成员设置固定 @ 文本；「艾特设置」全局配置。
 *  - 发送时改写输入框中的 @token（保留微信真实 @ 提醒，只换正文显示）。
 *  - 多种显示样式：纯 wxid / 群昵称 / 匿名代号 / 备注名 / 顺序代号 / 性别标签 /
 *    地区标签 / 自定义前后缀 / 稳定趣味称号 / 自定义模板。
 *  - 可限制只在指定群生效（enabled_groups 为空 = 全部群生效）。
 *
 * 逆向定位硬编码（x0/e/d/setLastText 等）与脚本一致，均针对微信 8.0.72 验证。
 */
object CustomAt : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "群聊自定义艾特"
    override val nameRes = R.string.feature_custom_at_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_custom_at_description

    private const val TAG = "CustomAt"

    // ---------------- 常量（与脚本一致） ----------------

    private const val MENU_ID = 777269

    private const val MEMBER_AT_CONFIG_KEY = "member_at_labels_v1"
    private const val MEMBER_AT_SEPARATOR = "\u0001"
    private const val AT_SEPARATOR = "\u2005"

    private const val KEY_AT_STYLE = "at_style"
    private const val KEY_ENABLED_GROUPS = "enabled_groups"
    private const val KEY_FALLBACK_ORDER = "profile_fallback_order"
    private const val KEY_FALLBACK_WXID = "profile_fallback_wxid"
    private const val KEY_FUN_TITLES = "fun_titles"
    private const val KEY_CUSTOM_PREFIX = "custom_prefix"
    private const val KEY_CUSTOM_SUFFIX = "custom_suffix"
    private const val KEY_CUSTOM_TEMPLATE = "custom_template"

    private val AT_STYLE_IDS = listOf(0, 1, 3, 4, 5, 7, 8, 9, 10, 11)
    private val AT_STYLE_NAMES = listOf(
        "纯 wxid", "群昵称", "匿名代号", "备注名", "顺序代号",
        "性别标签", "地区标签", "自定义前后缀", "稳定趣味称号", "自定义模板"
    )
    private val FALLBACK_ITEM_NAMES = mapOf(
        "group" to "群昵称",
        "remark" to "备注名",
        "nickname" to "好友昵称",
        "display" to "原显示名",
        "wxid" to "wxid"
    )
    private const val DEFAULT_FUN_TITLES = "摸鱼选手,气氛组,幸运成员,灵感担当,欢乐使者,神秘嘉宾,冒泡达人,潜水冠军,话题策划,快乐源泉,冷静军师,热心向导"
    private const val DEFAULT_TEMPLATE = "{群昵称}〔{地区}〕"

    // ---------------- 状态缓存 ----------------

    private val memberAtLabels = ConcurrentHashMap<String, String>()
    private val pendingAddedEntries = ConcurrentLinkedQueue<Pair<MutableMap<Any?, Any?>, String>>()
    private val memberInfoCache = ConcurrentHashMap<String, Map<String, Any?>>()
    private val groupNickCache = ConcurrentHashMap<String, Map<String, String>>()
    private val memberListCache = ConcurrentHashMap<String, List<WeContact>>()

    // ---------------- 生命周期 ----------------

    override fun onEnable() {
        loadMemberAtLabels()
        WeChatMessageContextMenuApi.addProvider(this)
        installSendHooks()
        // NOTE: the fixed 5s reinstall was removed: every process start it
        // unhooked+rehooked n1.onClick/s0, churning ART method state and
        // contributing to the 9/4 native crash storm. Class-replacement is
        // still self-healed lazily by ensureSendHooksAlive() before each send.
        WeLogger.d(TAG, "群聊自定义艾特已启用，当前样式=${getAtStyleName()}")
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
        unhookAll()
        clearPendingAddedEntries()
        groupNickCache.clear()
        memberListCache.clear()
        WeLogger.d(TAG, "群聊自定义艾特已停用")
    }

    // ---------------- 发送改写 Hook（对照脚本：明确 hook n1.onClick，带类替换自愈） ----------------

    private var hookedN1Class: Class<*>? = null

    private fun installSendHooks() {
        // 主路径（与脚本一致）：com.tencent.mm.pluginsdk.ui.chat.n1.onClick(View)
        var n1Ok = false
        runCatching {
            val n1Class = Class.forName("com.tencent.mm.pluginsdk.ui.chat.n1", false, ClassLoaders.HOST)
            val onClick = n1Class.getDeclaredMethod("onClick", View::class.java)
            onClick.isAccessible = true
            hookedN1Class = n1Class
            onClick.hookBefore(100) { handleSendClickBefore(thisObject) }
            n1Ok = true
        }.onFailure {
            WeLogger.e(TAG, "n1.onClick Hook 安装失败", it)
        }
        if (!n1Ok) {
            // 兜底：微信类名变化时退回 dexkit 匹配的 methodSendMessage
            runCatching {
                WeChatInputBarMenuApi.methodSendMessage.hookBefore(50) { handleSendClickBefore(thisObject) }
                WeChatInputBarMenuApi.methodSendMessage.hookAfter(50) { clearPendingAddedEntries() }
            }.onFailure {
                WeLogger.e(TAG, "发送改写兜底 Hook 安装失败", it)
            }
        }
        // s0 兜底（独立于 n1，恒定生效）：微信解析 @映射的必经方法
        installS0Hook()
        WeLogger.d(TAG, "群聊自定义艾特发送 Hook 已安装（n1=$n1Ok）")
    }

    private fun reinstallSendHooks(reason: String) {
        unhookAll()
        installSendHooks()
        WeLogger.d(TAG, "发送 Hook 已重装（原因：$reason）")
    }

    /**
     * 类替换自愈：微信热修复（Tinker 等）或启动期二次加载可能替换 n1 类，
     * 旧 ArtMethod 上的 hook 随之失效。每次发送前校验类对象是否一致，不一致即重装。
     */
    private fun ensureSendHooksAlive() {
        val hooked = hookedN1Class ?: return
        runCatching {
            val current = Class.forName("com.tencent.mm.pluginsdk.ui.chat.n1", false, ClassLoaders.HOST)
            if (current !== hooked) {
                WeLogger.w(TAG, "检测到 n1 类被替换（$hooked → $current），重装发送 Hook")
                reinstallSendHooks("类替换")
            }
        }.onFailure {
            WeLogger.e(TAG, "n1 类替换检测失败", it)
        }
    }

    // ---------------- 菜单 ----------------

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ID,
            text = "艾特",
            drawable = MenuIcons.res(R.drawable.ic_menu_at),
            imageVector = MaterialSymbols.Outlined.Alternate_email,
            isSupported = { msg -> msg.isInGroupChat },
            multiSelect = MultiSelectSupport.Unsupported,
            onClick = { view, _, msgInfo ->
                openAtPanel(view, msgInfo)
            }
        )
    )

    /** 点击「艾特」后先弹出面板：修改专属艾特 / 设置选项 两个入口 */
    private fun openAtPanel(view: View, msgInfo: MessageInfo) {
        val context = view.context
        val talker = msgInfo.talker
        val wxid = msgInfo.sender
        val isMemberTarget = wxid.isNotEmpty() && wxid != "system" &&
            !msgInfo.isSelfSender && wxid != WeApi.selfWxId
        val targetName = if (isMemberTarget) {
            myDisplayName(talker, wxid).ifEmpty { wxid }
        } else ""
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("群聊艾特") },
                text = {
                    Column {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Edit,
                            iconPlaceholder = true,
                            title = "修改专属艾特",
                            description = if (isMemberTarget) "为「$targetName」设置固定 @ 文本" else "请长按群成员的消息后再点「艾特」",
                            onClick = {
                                if (isMemberTarget) {
                                    onDismiss()
                                    openMemberAtInput(view, talker, wxid)
                                } else {
                                    showToast("请长按群成员的消息，再点「艾特」来设置专属艾特")
                                }
                            },
                        )
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Tune,
                            iconPlaceholder = true,
                            title = "设置选项",
                            description = "样式、生效群聊、趣味称号、模板等",
                            onClick = {
                                onDismiss()
                                openSettings(view)
                            },
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }

    // ---------------- 基础工具 ----------------

    private fun nonEmpty(value: Any?): String {
        if (value == null) return ""
        val text = value.toString().trim()
        return if (text.isEmpty()) "" else text
    }

    private fun stableAnonymousCode(wxid: String): String {
        val value = if (wxid.isEmpty()) 0L else wxid.hashCode().toLong() and 0xFFFFFFFFL
        var code = value.toHexString().uppercase(Locale.ROOT)
        while (code.length < 4) code = "0$code"
        return code.substring(code.length - 4)
    }

    private fun encodeBase64(value: String): String =
        runCatching { Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }.getOrDefault("")

    private fun decodeBase64(value: String): String =
        runCatching { String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8) }.getOrDefault("")

    // ---------------- 样式配置 ----------------

    private fun getAtStyle(): Int {
        val style = WePrefs.getIntOrDef(KEY_AT_STYLE, 0)
        if (style == 2 || style == 6) {
            WePrefs.putInt(KEY_AT_STYLE, 1)
            return 1
        }
        return if (AT_STYLE_IDS.contains(style)) style else 0
    }

    private fun getAtStyleName(): String {
        val index = AT_STYLE_IDS.indexOf(getAtStyle())
        return if (index >= 0) AT_STYLE_NAMES[index] else "纯 wxid"
    }

    private fun isTalkerEnabled(talker: String): Boolean {
        val enabledGroups = WePrefs.getStringSetOrDef(KEY_ENABLED_GROUPS, emptySet())
        return enabledGroups.isEmpty() || enabledGroups.contains(talker)
    }

    // ---------------- 专属艾特标签持久化（与原件同格式：Base64|Base64|Base64，行分隔） ----------------

    private fun memberAtKey(talker: String, wxid: String): String =
        nonEmpty(talker) + MEMBER_AT_SEPARATOR + nonEmpty(wxid)

    private fun loadMemberAtLabels() {
        memberAtLabels.clear()
        val raw = WePrefs.getString(MEMBER_AT_CONFIG_KEY) ?: return
        if (raw.isEmpty()) return
        try {
            for (row in raw.split("\n")) {
                val values = row.split("|")
                if (values.size != 3) continue
                val talker = decodeBase64(values[0])
                val wxid = decodeBase64(values[1])
                val label = nonEmpty(decodeBase64(values[2]))
                if (talker.endsWith("@chatroom") && wxid.isNotEmpty() && label.isNotEmpty()) {
                    memberAtLabels[memberAtKey(talker, wxid)] = label
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "loadMemberAtLabels failed", e)
        }
    }

    private fun saveMemberAtLabels() {
        try {
            val sb = StringBuilder()
            for ((key, label) in memberAtLabels) {
                val parts = key.split(MEMBER_AT_SEPARATOR)
                val cleanLabel = nonEmpty(label)
                if (parts.size != 2 || cleanLabel.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(encodeBase64(parts[0])).append("|")
                    .append(encodeBase64(parts[1])).append("|")
                    .append(encodeBase64(cleanLabel))
            }
            WePrefs.putString(MEMBER_AT_CONFIG_KEY, sb.toString())
        } catch (e: Exception) {
            WeLogger.e(TAG, "saveMemberAtLabels failed", e)
            showToast("专属艾特保存失败，请查看日志")
        }
    }

    private fun getMemberAtLabel(talker: String, wxid: String): String =
        nonEmpty(memberAtLabels[memberAtKey(talker, wxid)])

    private fun setMemberAtLabel(talker: String, wxid: String, label: String) {
        val key = memberAtKey(talker, wxid)
        val value = nonEmpty(label)
        if (value.isEmpty()) memberAtLabels.remove(key) else memberAtLabels[key] = value
        saveMemberAtLabels()
    }

    // ---------------- 资料缺失回退 ----------------

    private fun fallbackOrderIds(): List<String> {
        val result = mutableListOf<String>()
        val saved = WePrefs.getStringOrDef(KEY_FALLBACK_ORDER, "group,remark,nickname,display,wxid")
        for (part in saved.split(",")) {
            val id = part.trim()
            if (FALLBACK_ITEM_NAMES.containsKey(id) && !result.contains(id)) result.add(id)
        }
        for (default in listOf("group", "remark", "nickname", "display", "wxid")) {
            if (!result.contains(default)) result.add(default)
        }
        return result
    }

    private fun saveFallbackOrder(order: List<String>) {
        WePrefs.putString(KEY_FALLBACK_ORDER, order.joinToString(","))
    }

    private fun resolveFallbackName(displayName: String, wxid: String, talker: String, primaryId: String): String {
        if (!WePrefs.getBoolOrDef(KEY_FALLBACK_WXID, true)) return wxid
        for (id in fallbackOrderIds()) {
            if (id == primaryId) continue
            val value = when (id) {
                "group" -> getGroupNickName(talker, wxid)
                "remark" -> myRemarkName(wxid)
                "nickname" -> myNickName(wxid)
                "display" -> displayName
                "wxid" -> wxid
                else -> ""
            }
            if (value.isNotEmpty()) return value
        }
        return wxid
    }

    // ---------------- 群成员数据层（WeKit 原生 API） ----------------

    private fun groupNickMap(talker: String): Map<String, String> =
        groupNickCache.getOrPut(talker) {
            runCatching { WeDatabaseApi.getGroupMemberDisplayNameMap(talker) }.getOrDefault(emptyMap())
        }

    private fun getGroupNickName(talker: String, wxid: String): String =
        nonEmpty(groupNickMap(talker)[wxid])

    private fun groupMemberSequence(talker: String, wxid: String): Int {
        val members = memberListCache.getOrPut(talker) {
            runCatching { WeDatabaseApi.getGroupMembers(talker) }.getOrDefault(emptyList())
        }
        members.forEachIndexed { index, member ->
            if (member.wxId == wxid) return index + 1
        }
        return (((wxid.hashCode().toLong()) and 0x7FFFFFFFL) % 999L).toInt() + 1
    }

    private fun exactGroupMemberSequence(talker: String, wxid: String): Int {
        val members = memberListCache.getOrPut(talker) {
            runCatching { WeDatabaseApi.getGroupMembers(talker) }.getOrDefault(emptyList())
        }
        var index = 0
        for (member in members) {
            if (member.wxId.isEmpty()) continue
            index++
            if (member.wxId == wxid) return index
        }
        return 0
    }

    private fun sequenceLabel(talker: String, wxid: String): String =
        String.format(Locale.ROOT, "群成员%03d", groupMemberSequence(talker, wxid))

    // ---------------- 成员信息查询（rcontact 表；不依赖失效的 getFriendDisplayName） ----------------

    private fun queryContactInfo(wxid: String): Map<String, Any?> {
        if (wxid.isEmpty()) return emptyMap()
        memberInfoCache[wxid]?.let { return it }
        val info = runCatching {
            val rows = WeDatabaseApi.executeQuery(
                "SELECT nickname, conRemark, alias FROM rcontact WHERE username=?",
                arrayOf(wxid)
            )
            rows.firstOrNull()?.let { row ->
                mapOf(
                    "nickname" to (row["nickname"]?.toString() ?: "").trim(),
                    "remark" to (row["conRemark"]?.toString() ?: "").trim(),
                    "alias" to (row["alias"]?.toString() ?: "").trim()
                )
            } ?: emptyMap()
        }.getOrDefault(emptyMap())
        memberInfoCache[wxid] = info
        return info
    }

    private fun myNickName(wxid: String): String {
        val cached = queryContactInfo(wxid)["nickname"] as? String
        if (!cached.isNullOrEmpty()) return cached
        return nonEmpty(WeDatabaseApi.getFriend(wxid)?.nickname)
    }

    private fun myRemarkName(wxid: String): String {
        val cached = queryContactInfo(wxid)["remark"] as? String
        if (!cached.isNullOrEmpty()) return cached
        return nonEmpty(WeDatabaseApi.getFriend(wxid)?.remarkName)
    }

    // 微信 @ 列表/正文中显示的名字：备注 > 昵称 > username
    private fun myDisplayName(talker: String, wxid: String): String {
        val remark = myRemarkName(wxid)
        if (remark.isNotEmpty()) return remark
        val nick = myNickName(wxid)
        if (nick.isNotEmpty()) return nick
        return wxid
    }

    // ---------------- 性别 / 地区（contact 表，尽力而为） ----------------

    private fun getGroupMemberGender(talker: String, wxid: String): Int {
        return runCatching {
            val rows = WeDatabaseApi.executeQuery("SELECT sex FROM contact WHERE username=?", arrayOf(wxid))
            val sex = rows.firstOrNull()?.get("sex")?.toString()?.trim() ?: ""
            val s = sex.toIntOrNull() ?: 0
            if (s == 1 || s == 2) s else 0
        }.getOrDefault(0)
    }

    private fun getGroupMemberRegion(talker: String, wxid: String): String {
        return runCatching {
            val rows = WeDatabaseApi.executeQuery("SELECT reserved FROM contact WHERE username=?", arrayOf(wxid))
            val raw = rows.firstOrNull()?.get("reserved")
            val region = when (raw) {
                is ByteArray -> String(raw, Charsets.UTF_8)
                else -> raw?.toString().orEmpty()
            }.trim()
            if (region.isNotEmpty() && region.length <= 40) {
                val hasChinese = region.contains(Regex("[\\u4e00-\\u9fa5]"))
                if (hasChinese && !region.matches(Regex("^[\\d\\s\\-\\.,、]*$"))) return region
            }
            ""
        }.getOrDefault("")
    }

    private fun memberGenderLabel(talker: String, wxid: String): String =
        when (getGroupMemberGender(talker, wxid)) {
            1 -> "男"
            2 -> "女"
            else -> "未知性别"
        }

    private fun memberRegionLabel(talker: String, wxid: String): String {
        val region = nonEmpty(getGroupMemberRegion(talker, wxid))
        return if (region.isEmpty()) "未知地区" else region
    }

    // ---------------- 名称来源 ----------------

    private fun groupNameFirst(displayName: String, wxid: String, talker: String): String {
        val value = getGroupNickName(talker, wxid)
        if (value.isNotEmpty()) return value
        val nick = nonEmpty(myNickName(wxid))
        return if (nick.isEmpty()) "［特殊昵称］$wxid" else nick
    }

    private fun remarkNameFirst(displayName: String, wxid: String, talker: String): String {
        val value = nonEmpty(myRemarkName(wxid))
        return if (value.isEmpty()) resolveFallbackName(displayName, wxid, talker, "remark") else value
    }

    // ---------------- 稳定趣味称号 ----------------

    private fun defaultFunTitlesText(): String = DEFAULT_FUN_TITLES

    private fun funTitles(): List<String> {
        val saved = WePrefs.getStringOrDef(KEY_FUN_TITLES, defaultFunTitlesText())
        val titles = mutableListOf<String>()
        for (part in saved.replace('，', ',').replace('|', ',').replace('\n', ',').replace('\r', ',').split(",")) {
            var title = nonEmpty(part)
            while (title.startsWith("@")) title = title.substring(1).trim()
            title = title.replace(AT_SEPARATOR, "").trim()
            if (title.isNotEmpty() && !titles.contains(title)) titles.add(title)
        }
        if (titles.isNotEmpty()) return titles
        return defaultFunTitlesText().split(",")
    }

    private fun funTitleNumberSuffix(round: Int): String {
        val circled = arrayOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
        if (round in 1..circled.size) return circled[round - 1]
        return "（$round）"
    }

    private fun funTitle(talker: String, wxid: String): String {
        val titles = funTitles()
        val sequence = exactGroupMemberSequence(talker, wxid)
        if (sequence <= 0) {
            val hash = if (wxid.isEmpty()) 0 else wxid.hashCode()
            val index = (((hash.toLong()) and 0x7FFFFFFFL) % titles.size).toInt()
            return titles[index] + "（$wxid）"
        }
        val zeroBased = (sequence - 1).coerceAtLeast(0)
        val index = zeroBased % titles.size
        val round = zeroBased / titles.size
        return titles[index] + if (round == 0) "" else funTitleNumberSuffix(round)
    }

    // ---------------- 自定义模板 ----------------

    private fun applyAtTemplate(template: String, displayName: String, wxid: String, talker: String): String {
        var result = nonEmpty(template)
        if (result.isEmpty()) result = DEFAULT_TEMPLATE
        val groupName = groupNameFirst(displayName, wxid, talker)
        val remarkName = nonEmpty(myRemarkName(wxid))
        val nickname = nonEmpty(myNickName(wxid))
        val gender = memberGenderLabel(talker, wxid)
        val region = memberRegionLabel(talker, wxid)
        val variableNames = listOf("昵称", "群昵称", "备注名", "wxid", "短ID", "性别", "地区", "序号", "趣味称号")
        val variableValues = listOf(
            if (nickname.isEmpty()) resolveFallbackName(displayName, wxid, talker, "nickname") else nickname,
            groupName,
            if (remarkName.isEmpty()) resolveFallbackName(displayName, wxid, talker, "remark") else remarkName,
            wxid,
            stableAnonymousCode(wxid),
            gender,
            region,
            groupMemberSequence(talker, wxid).toString(),
            funTitle(talker, wxid)
        )
        for (i in variableNames.indices) {
            val name = variableNames[i]
            val value = variableValues[i]
            result = result.replace("{$name}", value)
            result = result.replace("[$name]", value)
            result = result.replace("［$name］", value)
        }
        return result
    }

    // ---------------- 艾特标签构建 ----------------

    private fun buildAtLabel(displayName: String, wxid: String, talker: String): String {
        val memberLabel = getMemberAtLabel(talker, wxid)
        if (memberLabel.isNotEmpty()) return memberLabel
        return when (getAtStyle()) {
            1 -> groupNameFirst(displayName, wxid, talker)
            3 -> "匿名成员-" + stableAnonymousCode(wxid)
            4 -> remarkNameFirst(displayName, wxid, talker)
            5 -> sequenceLabel(talker, wxid)
            7 -> groupNameFirst(displayName, wxid, talker) + " [" + memberGenderLabel(talker, wxid) + "]"
            8 -> groupNameFirst(displayName, wxid, talker) + " [" + memberRegionLabel(talker, wxid) + "]"
            9 -> WePrefs.getStringOrDef(KEY_CUSTOM_PREFIX, "「") + groupNameFirst(displayName, wxid, talker) + WePrefs.getStringOrDef(KEY_CUSTOM_SUFFIX, "」")
            10 -> funTitle(talker, wxid)
            11 -> applyAtTemplate(WePrefs.getStringOrDef(KEY_CUSTOM_TEMPLATE, DEFAULT_TEMPLATE), displayName, wxid, talker)
            else -> wxid
        }
    }

    private fun truncateAtLabel(label: String, maxLength: Int): String {
        val value = nonEmpty(label)
        if (value.length <= maxLength) return value
        var end = maxLength
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end)
    }

    private fun isAtLabelMappedToOther(entries: List<*>, label: String, wxid: String): Boolean {
        for (rawEntry in entries) {
            if (rawEntry !is Map<*, *>) continue
            val mappedWxid = rawEntry[label]
            if (mappedWxid != null && mappedWxid.toString() != wxid) return true
        }
        return false
    }

    private fun uniqueAtLabel(label: String, wxid: String, entries: List<*>): String {
        val base = nonEmpty(label)
        if (base.isEmpty()) return wxid
        var candidate = truncateAtLabel(base, 39)
        if (!isAtLabelMappedToOther(entries, candidate, wxid)) return candidate
        val suffix = "·" + stableAnonymousCode(wxid)
        candidate = truncateAtLabel(base, 39 - suffix.length) + suffix
        if (!isAtLabelMappedToOther(entries, candidate, wxid)) return candidate
        return truncateAtLabel(wxid, 39)
    }

    private fun replaceFirstAtToken(text: String, oldToken: String, newToken: String): String {
        val index = text.indexOf(oldToken)
        if (index < 0) return text
        return text.substring(0, index) + newToken + text.substring(index + oldToken.length)
    }

    // ---------------- 发送改写（微信 @token → 自定义 label） ----------------

    private data class RewriteResult(
        val text: String,
        val wxids: List<String>,
        val labels: Map<String, String>,
        val reason: String
    )

    /** 从 n1 发送监听器上取 ChatFooter（原脚本 n1.d；兜底遍历字段/当前会话） */
    internal fun findFooterFromListener(listener: Any?): ChatFooter? {
        if (listener == null) return null
        if (listener is ChatFooter) return listener
        runCatching {
            listener.reflekt().firstFieldOrNull { name = "d" }?.get() as? ChatFooter
        }.getOrNull()?.let { if (it != null) return it }
        runCatching {
            listener.reflekt().firstFieldOrNull {
                type { it == ChatFooter::class.java || ChatFooter::class.java.isAssignableFrom(it) }
            }?.get() as? ChatFooter
        }.getOrNull()?.let { if (it != null) return it }
        return null
    }

    /** footer.x0.e：Map<talker, List<Map<显示名, wxid>>> */
    private fun getTalkerAtMap(footer: ChatFooter): Map<*, *>? {
        return runCatching {
            val atState = footer.reflekt().firstFieldOrNull { name = "x0" }?.get()
            atState?.reflekt()?.firstFieldOrNull { name = "e" }?.get() as? Map<*, *>
        }.getOrNull()
    }

    internal fun findTalkerFromFooter(footer: ChatFooter): String {
        // 1) x0.e keySet（与脚本一致：找第一个 @chatroom key）
        runCatching {
            val atState = footer.reflekt().firstFieldOrNull { name = "x0" }?.get()
            val rawMap = atState?.reflekt()?.firstFieldOrNull { name = "e" }?.get() as? Map<*, *>
            rawMap?.keys?.firstOrNull { (it as? String)?.isGroupChatWxId == true } as? String
        }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        // 2) 兜底：遍历 footer 所有 String 字段找 @chatroom 结尾的值（切群后 x0.e 可能为空）
        runCatching {
            var clazz: Class<*>? = footer.javaClass
            while (clazz != null) {
                for (field in clazz.declaredFields) {
                    if (field.type != String::class.java) continue
                    runCatching { field.isAccessible = true }
                    val value = runCatching { field.get(footer) as? String }.getOrNull()
                    if (!value.isNullOrEmpty() && value.isGroupChatWxId) return value
                }
                clazz = clazz.superclass
            }
        }
        return ""
    }

    internal fun getFooterText(footer: ChatFooter): String {
        val lastText = runCatching {
            footer.reflekt().firstMethodOrNull { name = "getLastText" }?.invoke()?.toString().orEmpty()
        }.getOrDefault("")
        if (lastText.isNotEmpty()) return lastText
        return runCatching {
            footer.reflekt().firstMethodOrNull { name = "getLastContent" }?.invoke()?.toString().orEmpty()
        }.getOrDefault("")
    }

    internal fun setFooterText(footer: ChatFooter, text: String) {
        runCatching { footer.reflekt().firstMethodOrNull { name = "setLastText" }?.invoke(text) }
        runCatching { footer.reflekt().firstMethodOrNull { name = "setLastContent" }?.invoke(text) }
        runCatching {
            val et = footer.reflekt().firstMethodOrNull { name = "getToSendEt" }?.invoke()
            et?.reflekt()?.firstMethodOrNull { name = "setText" }?.invoke(text)
        }
    }

    private fun clearPendingAddedEntries() {
        val snapshot = pendingAddedEntries.toList()
        pendingAddedEntries.clear()
        for ((entry, label) in snapshot) {
            runCatching { entry.remove(label) }
        }
    }

    /** 核心：读取 footer.x0.e 映射 → 改写正文 → 注入 label→wxid → 更新输入框 */
    private fun rewriteFooterAtText(footer: ChatFooter?, talker: String): RewriteResult {
        val noToken = RewriteResult("", emptyList(), emptyMap(), "no_at_token")
        if (footer == null || talker.isEmpty()) return noToken

        val text = getFooterText(footer)
        if (text.indexOf('@') < 0) return RewriteResult(text, emptyList(), emptyMap(), "no_at_token")
        if (!isTalkerEnabled(talker)) return RewriteResult(text, emptyList(), emptyMap(), "group_filtered")

        val talkerMap = getTalkerAtMap(footer)
        if (talkerMap == null) {
            WeLogger.w(TAG, "改写未命中：footer.x0.e 读取失败（群=$talker），如频繁出现请反馈")
            return RewriteResult(text, emptyList(), emptyMap(), "talker_at_map_null")
        }

        val rawEntries = talkerMap[talker]
        if (rawEntries !is List<*>) {
            WeLogger.w(TAG, "改写未命中：x0.e 中无该群 @映射（群=$talker），如频繁出现请反馈")
            return RewriteResult(text, emptyList(), emptyMap(), "talker_entries_missing")
        }
        val entries = rawEntries

        data class Pair3(val displayName: String, val wxid: String, val entry: MutableMap<Any?, Any?>)
        val pairs = mutableListOf<Pair3>()
        for (rawEntry in entries) {
            if (rawEntry !is MutableMap<*, *>) continue
            val entry = rawEntry as MutableMap<Any?, Any?>
            val snapshot = entry.entries.toList()
            for ((k, v) in snapshot) {
                if (k == null || v == null) continue
                val displayName = k.toString()
                val wxid = v.toString()
                if (displayName.isEmpty() || wxid.isEmpty()) continue
                pairs.add(Pair3(displayName, wxid, entry))
            }
        }
        pairs.sortByDescending { it.displayName.length }
        if (pairs.isEmpty()) {
            WeLogger.w(TAG, "改写未命中：x0.e 中该群 @映射为空列表（群=$talker），如频繁出现请反馈")
            return RewriteResult(text, emptyList(), emptyMap(), "at_mapping_empty")
        }

        var rewritten = text
        val wxids = mutableListOf<String>()
        val labels = mutableMapOf<String, String>()
        clearPendingAddedEntries()
        for (pair in pairs) {
            val oldToken = "@" + pair.displayName + AT_SEPARATOR
            if (!rewritten.contains(oldToken)) continue
            val label = uniqueAtLabel(buildAtLabel(pair.displayName, pair.wxid, talker), pair.wxid, entries)
            rewritten = replaceFirstAtToken(rewritten, oldToken, "@$label$AT_SEPARATOR")
            if (!wxids.contains(pair.wxid)) wxids.add(pair.wxid)
            labels[pair.wxid] = label
            if (!pair.entry.containsKey(label)) {
                pair.entry[label] = pair.wxid
                pendingAddedEntries.add(pair.entry to label)
            }
        }
        return RewriteResult(
            text = rewritten,
            wxids = wxids,
            labels = labels,
            reason = if (wxids.isEmpty()) "display_token_not_matched" else "rewritten"
        )
    }

    /** 发送点击 before：改写输入框 + 注入映射（n1.onClick 是 void，hookBefore 安全） */
    private fun handleSendClickBefore(n1: Any?) {
        ensureSendHooksAlive()
        try {
            val footer = findFooterFromListener(n1) ?: return
            val talker = findTalkerFromFooter(footer)
            if (!talker.isGroupChatWxId || !isTalkerEnabled(talker)) return

            val rewrite = rewriteFooterAtText(footer, talker)
            if (rewrite.wxids.isEmpty()) return
            setFooterText(footer, rewrite.text)
            WeLogger.d(
                TAG,
                "发送前改写：样式=${getAtStyleName()}，群=$talker，改=${rewrite.wxids.size}个，正文=${rewrite.text}"
            )
        } catch (error: Throwable) {
            clearPendingAddedEntries()
            WeLogger.e(TAG, "发送前改写异常：${error::class.java.simpleName}", error)
        }
    }

    // ---------------- s0 兜底（微信解析 @映射的必经方法，恒定生效） ----------------

    private var s0DumpLogged = false

    /**
     * 兜底：微信发送/输入 @ 时调用 ChatFooter.s0(text, talker) 解析 @映射并返回 Map。
     * 切群后 footer.x0.e 可能没有当前群映射（主路径注入失败），但 s0 每次解析 @ 都会调用，
     * 在此把返回 Map 中的「显示名 → 自定义 label」替换，同时改写输入框 @显示名 → @label。
     * 不依赖 x0.e 状态 → 恒定生效。
     */
    private fun installS0Hook() {
        runCatching {
            val footerClass = Class.forName("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", false, ClassLoaders.HOST)
            val s0 = footerClass.getDeclaredMethod("s0", String::class.java, String::class.java)
            s0.isAccessible = true
            if (!Map::class.java.isAssignableFrom(s0.returnType)) {
                WeLogger.w(TAG, "s0 返回类型异常：${s0.returnType}")
                return
            }
            s0.hookAfter(100) { handleS0After(this) }
            WeLogger.d(TAG, "s0 兜底 Hook 已安装")
        }.onFailure {
            WeLogger.e(TAG, "s0 兜底 Hook 安装失败", it)
        }
    }

    private fun handleS0After(param: HookParam) {
        try {
            val footer = param.thisObject as? ChatFooter ?: return
            // talker 优先取 s0 参数（切群后 x0.e 可能空），fallback 遍历 x0.e keySet
            val talker = (param.args.getOrNull(1) as? String)
                ?.takeIf { it.isGroupChatWxId }
                ?: findTalkerFromFooter(footer)
            if (!talker.isGroupChatWxId || !isTalkerEnabled(talker)) return

            val result = param.result as? Map<*, *> ?: return
            if (result.isEmpty()) return

            if (!s0DumpLogged) {
                s0DumpLogged = true
                WeLogger.d(
                    TAG,
                    "s0 首次调用：talker=$talker，args0=${param.args.getOrNull(0)?.toString()?.take(40)}，" +
                        "返回Map=${result.entries.joinToString(limit = 5) { "${it.key}=${it.value}" }}"
                )
            }

            // 构造新 Map：显示名 → 自定义 label（不依赖原 Map 可变性）
            val newMap = LinkedHashMap<Any?, Any?>()
            var changed = false
            var text = getFooterText(footer)
            for ((k, v) in result) {
                val kStr = k?.toString().orEmpty()
                val vStr = v?.toString().orEmpty()
                if (kStr.isEmpty() || vStr.isEmpty()) {
                    newMap[k] = v
                    continue
                }
                // 方向1：key=显示名, value=wxid
                val labelFromV = getMemberAtLabel(talker, vStr)
                if (labelFromV.isNotEmpty() && labelFromV != kStr) {
                    newMap[labelFromV] = v
                    text = text.replace("@" + kStr + AT_SEPARATOR, "@" + labelFromV + AT_SEPARATOR)
                    changed = true
                    continue
                }
                // 方向2：key=wxid, value=显示名
                val labelFromK = getMemberAtLabel(talker, kStr)
                if (labelFromK.isNotEmpty() && labelFromK != vStr) {
                    newMap[k] = labelFromK
                    text = text.replace("@" + vStr + AT_SEPARATOR, "@" + labelFromK + AT_SEPARATOR)
                    changed = true
                    continue
                }
                newMap[k] = v
            }
            if (changed) {
                param.result = newMap
                setFooterText(footer, text)
                // 注入 x0.e（label→wxid），保证微信后续解析/发送 @label 能拿到映射
                runCatching {
                    val atState = footer.reflekt().firstFieldOrNull { name = "x0" }?.get()
                    val rawMap = atState?.reflekt()?.firstFieldOrNull { name = "e" }?.get() as? MutableMap<*, *>
                    if (rawMap != null) {
                        val talkerMap = rawMap as MutableMap<Any?, Any?>
                        val entries = talkerMap[talker]
                        val newEntry = LinkedHashMap<Any?, Any?>()
                        newEntry[newMap.keys.lastOrNull() ?: return@runCatching] = newMap.values.lastOrNull() ?: return@runCatching
                        if (entries is MutableList<*>) {
                            (entries as MutableList<Any?>).add(newEntry)
                        } else {
                            talkerMap[talker] = mutableListOf<Any?>(newEntry)
                        }
                    }
                }
                WeLogger.d(TAG, "s0 映射兜底改写：群=$talker，正文=${text.take(50)}")
            }
        } catch (error: Throwable) {
            WeLogger.e(TAG, "s0 兜底改写异常：${error::class.java.simpleName}", error)
        }
    }

    // ---------------- 专属艾特设置（长按成员消息 → 专属艾特） ----------------

    private fun openMemberAtInput(view: View, talker: String, wxid: String) {
        val context = view.context
        val name = myDisplayName(talker, wxid).ifEmpty { wxid }
        val current = getMemberAtLabel(talker, wxid)
        showComposeDialog(context) {
            var label by remember { mutableStateOf(current) }
            AlertDialogContent(
                title = { Text("设置专属艾特：$name") },
                text = {
                    Column {
                        Text("成员：$name\nwxid：$wxid\n保存后，该成员会优先使用专属文本；留空并确认可清除专属设置。")
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text("专属艾特文本") },
                            placeholder = { Text("例如：摸鱼大王") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        val trimmed = label.trim()
                        setMemberAtLabel(talker, wxid, trimmed)
                        onDismiss()
                        showToast(if (trimmed.isEmpty()) "已清除该成员的专属艾特" else "专属艾特已保存并立即生效")
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                }
            )
        }
    }

    // ---------------- 全局设置（长按群消息 → 艾特设置） ----------------

    private fun openSettings(view: View) {
        val context = view.context
        showComposeDialog(context) {
            var screen by remember { mutableStateOf("main") }
            when (screen) {
                "main" -> MainSettingsScreen(dismiss = onDismiss, onOpen = { screen = it })
                "style" -> StyleSettingsScreen(back = { screen = "main" })
                "groups" -> GroupSettingsScreen(back = { screen = "main" })
                "fallback" -> FallbackSettingsScreen(back = { screen = "main" })
                "affix" -> AffixSettingsScreen(back = { screen = "main" })
                "fun_titles" -> FunTitlesSettingsScreen(back = { screen = "main" })
                "template" -> TemplateSettingsScreen(back = { screen = "main" })
                "usage" -> UsageGuideScreen(back = { screen = "main" })
            }
        }
    }

    @Composable
    private fun MainSettingsScreen(dismiss: () -> Unit, onOpen: (String) -> Unit) {
        var fallbackEnabled by remember {
            mutableStateOf(WePrefs.getBoolOrDef(KEY_FALLBACK_WXID, true))
        }
        AlertDialogContent(
            title = { Text("艾特设置") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Contacts,
                            iconPlaceholder = true,
                            title = "设置生效群聊",
                            description = "不勾选任何群表示全部群聊生效",
                            onClick = { onOpen("groups") },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Format_list_numbered,
                            iconPlaceholder = true,
                            title = "选择艾特样式",
                            description = "当前：" + getAtStyleName(),
                            onClick = { onOpen("style") },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Swap_vert,
                            iconPlaceholder = true,
                            title = "资料缺失回退 wxid",
                            description = if (fallbackEnabled) "已开启：缺失时按顺序回退" else "已关闭：缺失时直接显示 wxid",
                            selected = fallbackEnabled,
                            onClick = {
                                fallbackEnabled = !fallbackEnabled
                                WePrefs.putBool(KEY_FALLBACK_WXID, fallbackEnabled)
                                showToast("资料缺失回退 wxid 已" + if (fallbackEnabled) "开启" else "关闭")
                            },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Sort,
                            iconPlaceholder = true,
                            title = "调整回退顺序",
                            description = fallbackOrderSummary(),
                            onClick = { onOpen("fallback") },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Format_quote,
                            iconPlaceholder = true,
                            title = "设置自定义前后缀",
                            description = "当前示例：@" + WePrefs.getStringOrDef(KEY_CUSTOM_PREFIX, "「") + "昵称" + WePrefs.getStringOrDef(KEY_CUSTOM_SUFFIX, "」"),
                            onClick = { onOpen("affix") },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Star,
                            iconPlaceholder = true,
                            title = "编辑趣味称号",
                            description = "当前 " + funTitles().size + " 个称号",
                            onClick = { onOpen("fun_titles") },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Edit,
                            iconPlaceholder = true,
                            title = "设置自定义模板",
                            description = "当前：" + WePrefs.getStringOrDef(KEY_CUSTOM_TEMPLATE, DEFAULT_TEMPLATE),
                            onClick = { onOpen("template") },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Info,
                            iconPlaceholder = true,
                            title = "使用说明",
                            description = "查看各样式与变量说明",
                            onClick = { onOpen("usage") },
                        )
                    }
                }
            },
            dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.dialog_close)) } }
        )
    }

    @Composable
    private fun StyleSettingsScreen(back: () -> Unit) {
        val currentStyle = getAtStyle()
        AlertDialogContent(
            title = { Text("艾特显示样式") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(AT_STYLE_IDS.size) { index ->
                        val style = AT_STYLE_IDS[index]
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Label,
                            iconPlaceholder = true,
                            title = AT_STYLE_NAMES[index],
                            description = styleDescription(style),
                            selected = currentStyle == style,
                            onClick = {
                                WePrefs.putInt(KEY_AT_STYLE, style)
                                showToast("已切换为：" + AT_STYLE_NAMES[index])
                            },
                            trailingDivider = index < AT_STYLE_IDS.size - 1,
                        )
                    }
                }
            },
            dismissButton = { TextButton(back) { Text("返回") } }
        )
    }

    @Composable
    private fun GroupSettingsScreen(back: () -> Unit) {
        var query by remember { mutableStateOf("") }
        val allGroups = remember {
            runCatching { WeDatabaseApi.getGroups() }
                .getOrDefault(emptyList())
                .sortedBy { it.nickname.lowercase(Locale.ROOT) }
        }
        var enabled by remember {
            mutableStateOf(WePrefs.getStringSetOrDef(KEY_ENABLED_GROUPS, emptySet()).toMutableSet())
        }
        val filtered = allGroups.filter {
            query.isBlank() ||
                it.nickname.contains(query, ignoreCase = true) ||
                it.wxId.contains(query, ignoreCase = true)
        }
        AlertDialogContent(
            title = { Text("生效群聊") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索群名/群号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.wxId }) { group ->
                            val selected = enabled.contains(group.wxId)
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Contacts,
                                iconPlaceholder = true,
                                title = group.nickname.ifEmpty { group.wxId },
                                description = group.wxId,
                                selected = selected,
                                onClick = {
                                    enabled = enabled.toMutableSet().apply {
                                        if (selected) remove(group.wxId) else add(group.wxId)
                                    }
                                    WePrefs.putStringSet(KEY_ENABLED_GROUPS, enabled)
                                },
                            )
                        }
                    }
                }
            },
            dismissButton = { TextButton(back) { Text("返回") } },
            confirmButton = {
                Button({
                    WePrefs.putStringSet(KEY_ENABLED_GROUPS, enabled)
                    back()
                    showToast(if (enabled.isEmpty()) "已设为全部群聊生效" else "已选择 ${enabled.size} 个群聊")
                }) { Text(stringResource(R.string.dialog_confirm)) }
            }
        )
    }

    @Composable
    private fun FallbackSettingsScreen(back: () -> Unit) {
        var order by remember { mutableStateOf(fallbackOrderIds()) }
        AlertDialogContent(
            title = { Text("资料回退顺序") },
            text = {
                Column {
                    Text("越靠前越优先；点右侧箭头调整位置。")
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(order.size) { index ->
                            val id = order[index]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Sort,
                                    iconPlaceholder = true,
                                    title = "${index + 1}. ${FALLBACK_ITEM_NAMES[id] ?: id}",
                                    description = "当前第 ${index + 1} 位",
                                    onClick = null,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    enabled = index > 0,
                                    onClick = {
                                        val newOrder = order.toMutableList()
                                        val item = newOrder.removeAt(index)
                                        newOrder.add(index - 1, item)
                                        order = newOrder
                                        saveFallbackOrder(newOrder)
                                    }
                                ) { Text("↑") }
                                TextButton(
                                    enabled = index < order.size - 1,
                                    onClick = {
                                        val newOrder = order.toMutableList()
                                        val item = newOrder.removeAt(index)
                                        newOrder.add(index + 1, item)
                                        order = newOrder
                                        saveFallbackOrder(newOrder)
                                    }
                                ) { Text("↓") }
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(back) { Text("返回") } }
        )
    }

    @Composable
    private fun AffixSettingsScreen(back: () -> Unit) {
        var prefix by remember { mutableStateOf(WePrefs.getStringOrDef(KEY_CUSTOM_PREFIX, "「")) }
        var suffix by remember { mutableStateOf(WePrefs.getStringOrDef(KEY_CUSTOM_SUFFIX, "」")) }
        AlertDialogContent(
            title = { Text("自定义前后缀") },
            text = {
                Column {
                    OutlinedTextField(
                        value = prefix,
                        onValueChange = { prefix = it },
                        label = { Text("前缀") },
                        placeholder = { Text("「") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = suffix,
                        onValueChange = { suffix = it },
                        label = { Text("后缀") },
                        placeholder = { Text("」") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("当前示例：@${prefix}昵称$suffix（允许留空，修改后重新发送即可生效）")
                }
            },
            dismissButton = { TextButton(back) { Text("返回") } },
            confirmButton = {
                Button({
                    WePrefs.putString(KEY_CUSTOM_PREFIX, prefix)
                    WePrefs.putString(KEY_CUSTOM_SUFFIX, suffix)
                    back()
                    showToast("自定义前后缀已保存并立即生效")
                }) { Text("保存") }
            }
        )
    }

    @Composable
    private fun FunTitlesSettingsScreen(back: () -> Unit) {
        var text by remember { mutableStateOf(funTitles().joinToString("\n")) }
        AlertDialogContent(
            title = { Text("自定义趣味称号") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("每行一个称号") },
                        placeholder = { Text("摸鱼选手、气氛组、幸运成员") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    )
                    Text("同一群成员按群成员顺序分配且互不重复；人数超过称号数量时自动添加①②等序号。留空恢复默认。")
                }
            },
            dismissButton = { TextButton(back) { Text("返回") } },
            confirmButton = {
                Button({
                    val value = text.trim()
                    WePrefs.putString(KEY_FUN_TITLES, if (value.isEmpty()) defaultFunTitlesText() else value)
                    back()
                    showToast("趣味称号已保存，共 ${funTitles().size} 个")
                }) { Text("保存") }
            }
        )
    }

    @Composable
    private fun TemplateSettingsScreen(back: () -> Unit) {
        var template by remember { mutableStateOf(WePrefs.getStringOrDef(KEY_CUSTOM_TEMPLATE, DEFAULT_TEMPLATE)) }
        AlertDialogContent(
            title = { Text("自定义模板") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = template,
                        onValueChange = { template = it },
                        label = { Text("模板") },
                        placeholder = { Text("例如：{群昵称}｜{短ID}") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    )
                    Text("可用变量：{昵称} {群昵称} {备注名} {wxid} {短ID} {性别} {地区} {序号} {趣味称号}\n留空恢复默认：{群昵称}〔{地区}〕")
                }
            },
            dismissButton = { TextButton(back) { Text("返回") } },
            confirmButton = {
                Button({
                    val value = template.trim()
                    WePrefs.putString(KEY_CUSTOM_TEMPLATE, if (value.isEmpty()) DEFAULT_TEMPLATE else value)
                    back()
                    showToast("自定义模板已保存")
                }) { Text("保存") }
            }
        )
    }

    @Composable
    private fun UsageGuideScreen(back: () -> Unit) {
        val guide = buildString {
            appendLine("【使用说明】")
            appendLine("• 自定义模板：按自定义变量组合显示。")
            appendLine("• 纯 wxid：直接显示成员 wxid。")
            appendLine("• 群昵称：优先显示成员设置的原始群昵称；未设置时显示实际微信昵称；昵称也无法读取时显示“［特殊昵称］wxid”，仍不使用备注名。")
            appendLine("• 匿名代号：显示“匿名成员-短ID”。")
            appendLine("• 备注名：优先显示好友备注名。")
            appendLine("• 顺序代号：显示“群成员001”等。")
            appendLine("• 性别标签：群昵称后附性别。")
            appendLine("• 地区标签：群昵称后附地区。")
            appendLine("• 自定义前后缀：前缀 + 群昵称 + 后缀。")
            appendLine("• 稳定趣味称号：按 wxid 固定分配自定义称号，如“摸鱼选手”“气氛组”。")
            appendLine("")
            appendLine("【资料缺失回退】开启后，群昵称、备注名、性别或地区缺失时，按设置顺序尝试群昵称、备注名、好友昵称、原显示名和 wxid。")
            appendLine("")
            appendLine("【自定义模板变量】{昵称}好友昵称 {群昵称}当前群昵称 {备注名}好友备注名 {wxid}完整wxid {短ID}稳定四位代号 {性别}男/女/未知性别 {地区}成员地区 {序号}群成员顺序编号 {趣味称号}稳定分配的称号")
            appendLine("")
            appendLine("当前样式：${getAtStyleName()}")
        }
        AlertDialogContent(
            title = { Text("使用说明") },
            text = {
                Text(
                    guide,
                    modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 380.dp),
                )
            },
            dismissButton = { TextButton(back) { Text("返回") } }
        )
    }

    private fun fallbackOrderSummary(): String =
        fallbackOrderIds().joinToString(" > ") { FALLBACK_ITEM_NAMES[it] ?: it }

    private fun styleDescription(style: Int): String = when (style) {
        0 -> "直接显示成员 wxid"
        1 -> "群昵称 > 昵称 > ［特殊昵称］wxid"
        3 -> "匿名成员-" + "短ID"
        4 -> "优先显示好友备注名"
        5 -> "群成员001 等入群顺序"
        7 -> "群昵称后附 [男/女]"
        8 -> "群昵称后附 [地区]"
        9 -> "前缀 + 群昵称 + 后缀"
        10 -> "按 wxid 固定分配称号"
        11 -> "按模板组合显示"
        else -> ""
    }
}
