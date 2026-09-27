package dev.joker.features.items.chat.jev.core

import dev.joker.features.api.core.models.MessageInfo

/**
 * 消息的**归属唯一键** —— 卡片渲染、宿主行比对、回填归属只准用它。
 *
 * ## 为什么必须单独有这么一个东西
 *
 * 第 27 轮的实测症状是「卡片先画在别的消息上面，过一会才归位」。串位的经典成因只有两类：
 *
 *  1. **拿位置/行视图当键**：微信的聊天列表是 RecyclerView，行视图会被回收复用，
 *     滚动一次「第 3 行」就换成了另一条消息。任何以「第几行」「哪个 View」为键的
 *     缓存/回填都会把上一条消息的结论画到下一条消息上，直到下一次 bind 才纠正 ——
 *     与用户描述完全一致。
 *  2. **拿「分析键」当归属键**：[AnalysisInput.key] 把**上下文**一起哈希了
 *     （旁边来一条新消息，同一条消息的 key 就变了），用它会「同一条消息每次都换人」。
 *
 * 所以归属一律用「消息自身 + 会话」推出来的稳定键，位置只用于展示：
 *
 *  - 有 `field_msgId`（绝大多数情况）：`会话#消息id` —— 零哈希、零歧义，rebind / 复用 /
 *    重启进程之后都指向同一条消息；
 *  - 拿不到 msgId（本地暂态消息，微信还没落库；发出去的瞬间就是这种）：退回
 *    **会话 + 时间 + 发送者 + 内容哈希** 的内容键。内容键必须用**原始 content**，
 *    不能用 [MessagePolicy] 过滤过的“可分析文本”：超长消息会被
 *    `textOrNull` 判成 null，而那种消息也是要出卡片的（超长提示卡）。
 *
 * 注意与 [AnalysisInput.identity] 的分工（后者是**分析去重身份**，走 `identity` 里的
 * 公式；两者在 msgId 可用时取值一致）。别再混用，混用就是串位和重复分析。
 */
object MessageKey {

    /** 没有 msgId 时给内容键用的分隔符外的稳定前缀，避免与 msgId 形态撞键。 */
    private const val CONTENT_PREFIX = "c"

    /**
     * 是否是一个「稳定」消息（有 msgId）。
     *
     * 不稳定（msgId<=0）的消息在宿主列表里可能只是一瞬间的占位行，
     * 调用方据此决定要不要给它排分析预算（现在会给：内容键也足够稳）。
     */
    fun isStable(messageId: Long): Boolean = messageId > 0L

    /**
     * 由消息字段直接构造归属键。
     *
     * [rawContent] 必须是原始内容（含群聊前缀），不要传清洗后的文本 ——
     * 群聊里同一个人的两条「图片」消息原始 XML 不同，清洗后可能都变成空串，
     * 那就把两条不同的消息判成同一行了。
     */
    fun of(talker: String, messageId: Long, speaker: String, createdAt: Long, rawContent: String): String =
        if (messageId > 0L) {
            "$talker#$messageId"
        } else {
            // 长度前缀 + 分隔符：避免「发送者/时间/内容」不同组合拼出同一个串
            "$talker#$CONTENT_PREFIX${createdAt}:${speaker.length}:$speaker:${rawContent.hashCode()}"
        }

    /**
     * 由宿主消息对象构造归属键。
     *
     * **热路径**：有 msgId 时只读 talker 与 id（都是 `by lazy` 缓存值），
     * 不碰 `sender` / `content`（群聊 sender 要反射、content 可能几百字节），
     * 所以每帧/每次绘制都调它也不会有可感知开销。
     */
    fun of(message: MessageInfo): String {
        val id = message.id
        if (id > 0L) return "${message.talker}#$id"
        return of(message.talker, 0L, message.sender, message.createTime, message.content)
    }
}
