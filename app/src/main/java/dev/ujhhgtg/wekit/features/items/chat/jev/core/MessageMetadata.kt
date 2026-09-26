package dev.ujhhgtg.wekit.features.items.chat.jev.core

import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType

/**
 * 微信消息 -> 言外分析口径的适配层。
 *
 * 上游 wechatmood 只能拿到裸的消息对象，所以自带一套反射读 `field_type` / `field_isSend` /
 * `field_content` / `field_talker` 的 `MessageMetadata.read(item)`。
 * WeKit 已经把这些字段封装成 [MessageInfo]，这里改成纯映射，反射全部去掉。
 *
 * 两个口径必须与上游逐字一致，否则分析结果会漂移：
 *  - 群聊在 `content` 里带 `发送者wxid:\n` 前缀，判断目标消息的正文时要去掉；
 *  - 只有 `isSend == 0`（对方发的）且 `typeCode == 1`（纯文本）才进入分析。
 */
object MessageMetadata {

    private const val TYPE_TEXT = 1

    /** 是否是一条「对方发来的纯文本」。 */
    fun isIncomingText(message: MessageInfo): Boolean =
        message.typeCode == TYPE_TEXT && message.isSend == 0 && message.talker.isNotBlank()

    /** 目标正文；不满足条件返回 null。 */
    fun incomingText(message: MessageInfo): String? =
        if (isIncomingText(message)) plainText(message) else null

    /** 按开关取分析正文：默认只分析对方发来的，开启后连自己发的也算。 */
    fun analyzeText(message: MessageInfo, includeSelf: Boolean): String? =
        if (includeSelf) {
            if (message.typeCode == TYPE_TEXT && message.isSend in 0..1 && message.talker.isNotBlank())
                plainText(message) else null
        } else incomingText(message)

    /** 不计发送方，只要求是纯文本。 */
    fun plainText(message: MessageInfo): String? {
        if (message.typeCode != TYPE_TEXT) return null
        if (message.isSend !in 0..1) return null
        if (message.talker.isBlank()) return null
        val raw = message.content
        val text = if (message.isSend == 0 && message.talker.endsWith("@chatroom") && raw.contains(":\n"))
            raw.substringAfter(":\n") else raw
        return MessagePolicy.textOrNull(text)
    }

    /** 前文里的说话人署名。群聊取 `昵称:\n` 前缀，否则按发送方向判定。 */
    fun speaker(message: MessageInfo): String = when {
        message.isSend == 1 -> "我"
        message.talker.endsWith("@chatroom") && message.content.contains(":\n") ->
            message.content.substringBefore(":\n").ifBlank { "对方" }
        else -> "对方"
    }

    /** 诊断用：把 [MessageInfo] 压成一行，便于定位「为什么这条没被分析」。 */
    fun describe(message: MessageInfo): String =
        "type=${message.typeCode}(${message.type?.displayName ?: MessageType.UNKNOWN.displayName}) " +
            "isSend=${message.isSend} talker=${message.talker} len=${message.content.length}"
}
