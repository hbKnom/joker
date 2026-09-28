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
 * ★ 行为：
 *   - 拦截微信处理新消息（AddMsg）入口。
 *   - 命中规则的会话 / 关键词 / 发送人 → 直接吞掉（不再进入微信消息链）。
 *   - 规则配置：阶段 1 用最小实现（默认全部放行）；阶段 2 接 AutomationSpec 引擎。
 *
 * ★ 第 29 轮阶段 1 骨架：仅占位 SwitchFeature，不挂 hook（避免与微信自身消息链冲突）。
 *   阶段 2 再加 DexMethodDelegate(methodProcessAddMsg) + Hooker.hookBefore + 最小规则集。
 *
 * ★ 整合铁律（与上游脱钩）：
 *   - 包名 dev.joker.*（非 dev.ujhhgtg.wekit.*）
 *   - 不引入 EventBus/AutomationSpec（我方不存在），走 Joker 自有 SwitchFeature 流程
 *   - 默认关闭（defaultEnabled = false）；用户主动启用才接管 AddMsg 链
 */
package dev.joker.features.items.chat

import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

/**
 * 屏蔽消息（1945 新增，感谢 Hchat）
 *
 * 在微信处理新消息（AddMsg）之前拦截，命中规则的会话直接吞掉，
 * 不产生通知、不写入消息列表。
 */
object BlockMessages : SwitchFeature() {

    // ── 功能元数据 ──────────────────────────────────────────────────
    override val technicalId: String = "屏蔽消息"
    override val nameRes: Int = R.string.feature_chat_block_messages_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes: Int = R.string.feature_chat_block_messages_description

    /** 默认关闭 —— 等阶段 2 hook 真生效后再允许用户开启。 */
    override val defaultEnabled: Boolean = false

    override fun onEnable() {
        // 阶段 1 骨架：不挂 hook，避免误吞微信消息。
        // 阶段 2 实装：
        //   val delegate = methodProcessAddMsg.find(dexKit, allowFailure = true) {
        //     matcher {
        //       declaredClass = "com.tencent.mm.plugin.messenger.foundation.r$"
        //       modifiers = Modifier.PUBLIC or Modifier.STATIC
        //       paramTypes(...)
        //     }
        //   }
        //   Hooker.hookBefore(delegate.method, HOOK_PRIORITY, ::handleBeforeAddMsg)
    }

    override fun onDisable() {
        // 阶段 2 移除 hook；阶段 1 无操作。
    }

    /**
     * 阶段 2 命中的最小规则引擎。
     *
     * 当前实现：默认返回 false（放行）；规则集由配置写入后再启用。
     */
    private fun shouldBlock(talker: String?, content: String?, msgType: Int?): Boolean {
        // TODO(阶段 2): 接入 AutomationSpec / 关键词集合 / 发送人白名单
        return false
    }
}