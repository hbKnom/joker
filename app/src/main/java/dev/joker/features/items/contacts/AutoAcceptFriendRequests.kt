/*
 * AutoAcceptFriendRequests.kt — 自动通过好友申请 【第 29 轮 WeKit1945 整合 · Round30 阶段 1 实 hook】
 *
 * 证据等级：A-（Hchat 4 + 逆向 jadx + 重建版）
 * 来源：
 *   - WeKit 1945 逆向包 `01_逆向源码/contacts/AutoAcceptFriendRequests.kt`（混淆类 g50）
 *   - `09_Hchat4功能逆向/AutoAcceptFriendRequests.kt`（Hchat 4 原版）
 *   - `02_原始反编译/defpackage/AutoAcceptFriendRequests.jadx.java`
 *   - `07_反编译产物dump/Lm27.txt`（好友申请 XML 多套兼容字段反汇编）
 *
 * 对应日志：「新增: 自动通过好友申请」
 *
 * ★ 行为：
 *   - 微信收到好友申请（type=FRIEND_VERIFY=37）后，命中规则即自动通过。
 *   - 通过后自动打标签 / 写备注 / 打招呼（阶段 2 实装）。
 *
 * ★ Round30 阶段 1 真 hook（最小安全版）：
 *   - 注册 IInsertListener 监听 message 表 FRIEND_VERIFY 插入
 *   - 仅 WeLogger.i 记录申请 talker（不实际接受，避免无 DexKit 委托时误操作用户联系人）
 *   - 自动打招呼模板用 var by prefOption 保存，可由用户在设置页修改
 *   - 真正的"自动通过"阶段 2 实装：需 DexKit 委托 hook 微信 AcceptFriendHelper.accept
 *
 * ★ 整合铁律：
 *   - 包名 dev.joker.*
 *   - 默认关闭；用户主动启用才接管监听
 *   - 不引入 EventBus 完整套件
 */
package dev.joker.features.items.contacts

import android.content.ContentValues
import dev.joker.R
import dev.joker.features.api.core.WeDatabaseListenerApi
import dev.joker.features.api.core.models.MessageType
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.preferences.WePrefs.Companion.prefOption
import dev.joker.utils.WeLogger

/**
 * 自动通过好友申请（1945 新增，感谢 Hchat）
 */
object AutoAcceptFriendRequests : SwitchFeature() {

    override val technicalId: String = "自动通过好友申请"
    override val nameRes: Int = R.string.feature_contacts_auto_accept_friend_requests_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes: Int = R.string.feature_contacts_auto_accept_friend_requests_description

    /** 默认关闭 —— 等阶段 2 真 hook 接受功能就绪后再允许用户开启。 */
    override val defaultEnabled: Boolean = false

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(autoAcceptFriendRequestsInsertListener)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(autoAcceptFriendRequestsInsertListener)
    }
}

/**
 * 自动打招呼模板（可在设置页修改）。
 * Round30 阶段 1 预留字段；阶段 2 实装时 WeMessageApi.sendText 会用此模板。
 */
internal var autoAcceptFriendRequestsReplyText: String by prefOption(
    "auto_accept_reply_text",
    "你好，已收到好友申请",
)

/**
 * 【Round30 ☆ 真 hook 阶段 1】
 * 监听 message 表 FRIEND_VERIFY 插入 → 记录好友申请到来。
 * 阶段 1 仅记录，阶段 2 实装真正的"自动通过"。
 */
internal val autoAcceptFriendRequestsInsertListener = object : WeDatabaseListenerApi.IInsertListener {
    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val type = values.getAsInteger("type") ?: return
        if (type != MessageType.FRIEND_VERIFY.code) return
        val talker = values.getAsString("talker") ?: return
        if (talker.isEmpty()) return
        WeLogger.i(
            "AutoAcceptFriendRequests",
            "收到好友申请：talker=$talker（阶段 2 实装自动通过，当前仅记录）",
        )
    }
}