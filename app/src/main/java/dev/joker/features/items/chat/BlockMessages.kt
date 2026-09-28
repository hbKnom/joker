/*
 * BlockMessages.kt — 屏蔽消息 【第 29 轮 WeKit1945 整合】
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
 * ★ 实现：
 *   - SwitchFeature（用户开关）
 *   - BlockMessagesRuntime ApiFeature（hook 实际安装）
 *   - 入口：微信 `NotificationCenter.dealNotify` — 与 BlockAtAllNotifications 同款，
 *     拦截在通知发送前，命中规则即吞掉（result = null）
 *
 * ★ 规则集（本轮最小实现）：JSON 配置 `message_block_templates`，字段：
 *   - talkers: List<String>（黑名单会话）
 *   - keywords: List<String>（关键词）
 *   - senderKeywords: List<String>（发送人匹配）
 *   默认空规则 = 完全放行，不破坏现有微信行为。
 *
 * ★ 整合铁律（与上游脱钩）：
 *   - 包名 dev.joker.*
 *   - 不引入 EventBus/AutomationSpec（我方不存在），走 Joker 自有 ApiFeature 双层架构
 *   - defaultEnabled = false；用户主动开启才接管通知链
 */
package dev.joker.features.items.chat

import android.content.Context
import dev.joker.R
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.preferences.WePrefs.Companion.prefOption
import dev.joker.utils.TargetProcess
import dev.joker.utils.WeLogger

/**
 * 屏蔽消息（用户开关）
 *
 * 默认关闭。开启后委托 [BlockMessagesRuntime] 在通知发送前拦截
 * 与配置规则匹配的新消息。
 */
object BlockMessages : SwitchFeature() {

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
}

/**
 * BlockMessages 的规则集数据类 + 偏好加载。
 *
 * 默认空规则；用户通过设置页写入 JSON 后即生效。
 * 配置 key：`block_messages_rules_json`（单个 JSON 字符串）
 */
data class BlockMessagesRules(
    val talkers: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val senderKeywords: List<String> = emptyList(),
) {
    companion object {
        val current: BlockMessagesRules by lazy {
                runCatching {
                    val raw = rulesJsonPref.value
                    if (raw.isBlank()) {
                        BlockMessagesRules()
                    } else {
                        // 最小 JSON 解析：逗号分隔字符串
                        val t = mutableListOf<String>()
                        val k = mutableListOf<String>()
                        val s = mutableListOf<String>()
                        raw.split('\n').forEach { line ->
                            val t1 = line.trim()
                            if (t1.isEmpty() || t1.startsWith("#")) return@forEach
                            val prefix = t1.substringBefore('=', "")
                            val value = t1.substringAfter('=', "").trim()
                            if (value.isEmpty()) return@forEach
                            when (prefix) {
                                "talkers" -> t.addAll(value.split(',').map { it.trim() })
                                "keywords" -> k.addAll(value.split(',').map { it.trim() })
                                "senders" -> s.addAll(value.split(',').map { it.trim() })
                            }
                        }
                        BlockMessagesRules(t, k, s)
                    }
                }.getOrDefault(BlockMessagesRules())
            }

        private val rulesJsonPref =
            prefOption("block_messages_rules_json", "")
    }
}

/**
 * BlockMessages 的 hook 安装器（ApiFeature）。
 *
 * 入口点：微信 `NotificationCenter.dealNotify`（与 BlockAtAllNotifications 同款签名）。
 * 命中则 `result = null`，通知被吞掉；未命中则原样通过。
 *
 * 限制：本轮只 hook 通知发送，**不动消息入 DB / 不动 UI 不动可见消息**。
 * 因为动 DB 风险太高（破坏微信消息链）。这只是「通知层」屏蔽。
 */
object BlockMessagesRuntime : ApiFeature() {

    override val technicalId: String = "屏蔽消息服务"
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
            val talker = args[1] as String
            val rawContent = args[2] as String
            // 不取发送人（dealNotify 没有 sender 入参；上面 caller 已是 dealNotify 的 talker）
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