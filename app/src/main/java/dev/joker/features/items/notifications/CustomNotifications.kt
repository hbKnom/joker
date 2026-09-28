/*
 * CustomNotifications.kt — 自定义通知 【第 29 轮 WeKit1945 整合】
 *
 * 证据等级：A-（Hchat 4 + 逆向 jadx + 重建版）
 * 来源：
 *   - WeKit 1945 逆向包 `01_逆向源码/notifications/CustomNotifications.kt`（混淆类 ud2/td2/nd2/uc2）
 *   - `09_Hchat4功能逆向/CustomNotifications.kt`（Hchat 4 原版）
 *   - `02_原始反编译/defpackage/CustomNotifications.jadx.java`
 *   - `07_反编译产物dump/Lsd2.txt` `Lnd2.txt` `Luc2.txt`（两条广播 + 通道反汇编）
 *
 * 对应日志：「新增: 自定义通知」
 *
 * ★ 行为：
 *   - 替换微信通知样式（颜色 / 声音 / 振动 / 同会话堆叠 / 通知内快捷回复）。
 *   - 两条广播：
 *     - ACTION_WEKIT_CUSTOM_NOTIFY_MARK_READ：标记已读
 *     - ACTION_WEKIT_CUSTOM_NOTIFY_REPLY：通知内回复
 *
 * ★ 第 29 轮阶段 1 骨架：仅占位 SwitchFeature，不接管微信通知发送。
 *   阶段 2 再加 NotificationChannel 注册 + 两条 BroadcastReceiver + 最小通知样式。
 *
 * ★ 整合铁律：
 *   - 包名 dev.joker.*
 *   - 不引入自定义通知体系（我方不存在），阶段 2 用极简实现（仅基础 NotificationCompat）
 *   - 默认关闭
 */
package dev.joker.features.items.notifications

import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

/**
 * 自定义通知（1945 新增，感谢 Hchat）
 *
 * 接管微信新消息通知：可自定义通道 / 声音 / 颜色 / 同会话堆叠 / 通知内回复。
 */
object CustomNotifications : SwitchFeature() {

    override val technicalId: String = "自定义通知"
    override val nameRes: Int = R.string.feature_notifications_custom_notifications_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.NOTIFICATIONS)
    override val descriptionRes: Int = R.string.feature_notifications_custom_notifications_description

    /** 默认关闭 —— 等阶段 2 真接管通知后再允许用户开启。 */
    override val defaultEnabled: Boolean = false

    override fun onEnable() {
        // 阶段 1 骨架：不接管微信通知。
        // 阶段 2 实装：
        //   1. NotificationCompat.createChannel("joker_dev_favorites", "Joker 收藏消息")
        //   2. Register Receiver: ActionMarkRead / ActionReply
        //   3. Hooker.hookBefore(NotifyUI.showMsg, ::customShow)
    }

    override fun onDisable() {
        // 阶段 2 移除 Channel + Receiver；阶段 1 无操作。
    }
}