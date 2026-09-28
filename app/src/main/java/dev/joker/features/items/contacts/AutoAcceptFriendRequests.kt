/*
 * AutoAcceptFriendRequests.kt — 自动通过好友申请 【第 29 轮 WeKit1945 整合】
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
 *   - 微信收到好友申请（FriendAutoAdd / VerifyMessage）后，按规则自动通过。
 *   - 通过后自动打标签 / 写备注 / 打招呼（阶段 2 实装）。
 *
 * ★ 第 29 轮阶段 1 骨架：仅占位 SwitchFeature，不挂 hook。
 *   阶段 2 再加 DexMethodDelegate(FriendAutoAdd) + eventBus 订阅 + 最小动作链。
 *
 * ★ 整合铁律：
 *   - 包名 dev.joker.*
 *   - 不引入 EventBus 完整套件（我方不存在），阶段 2 用极简实现
 *   - 默认关闭；用户主动启用才接管好友申请链
 */
package dev.joker.features.items.contacts

import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

/**
 * 自动通过好友申请（1945 新增，感谢 Hchat）
 *
 * 命中规则的微信好友申请（按场景需排除/自动通过）自动通过，
 * 通过后按规则打标签 / 写备注 / 打招呼。
 */
object AutoAcceptFriendRequests : SwitchFeature() {

    override val technicalId: String = "自动通过好友申请"
    override val nameRes: Int = R.string.feature_contacts_auto_accept_friend_requests_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes: Int = R.string.feature_contacts_auto_accept_friend_requests_description

    /** 默认关闭 —— 等阶段 2 hook 真生效后再允许用户开启。 */
    override val defaultEnabled: Boolean = false

    override fun onEnable() {
        // 阶段 1 骨架：不挂 hook。
        // 阶段 2 实装：
        //   1. 监听 EventBus.OnFriendAutoAdd / VerifyContactEvent
        //   2. 命中规则则调 ContactInfoApi.acceptRequest / addContactLabel / setRemark
        //   3. 调 WeMessageApi.sendText 触发自动打招呼
    }

    override fun onDisable() {
        // 阶段 2 移除监听；阶段 1 无操作。
    }
}