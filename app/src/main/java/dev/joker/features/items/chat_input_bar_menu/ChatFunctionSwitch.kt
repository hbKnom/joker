/*
 * ChatFunctionSwitch.kt — 聊天功能开关 【第 29 轮 WeKit1945 整合】
 *
 * 证据等级：B+（重建版 + 原始 jadx）
 * 来源：WeKit 1945 逆向包 `01_逆向源码/chat_input_bar_menu/ChatFunctionSwitch.kt` + jadx `us1.txt`
 * 对应日志：「添加: 聊天功能开关」
 *
 * ★ 行为：
 *   - 与 WeChatInputBarMenuApi（我方已有 `api/ui/WeChatInputBarMenuApi.kt`）配合使用。
 *   - 当用户开启本功能时，向 WeChatInputBarMenuApi 注册一个统一的「聊天功能」入口按钮；
 *     关闭时移除。
 *   - 设置页：复用 Joker 自有 settings 框架，调用 [ClickableFeature.onClick] 显示配置列。
 *
 * ★ 整合铁律（与上游脱钩）：
 *   - 包名 dev.joker.*（非 dev.ujhhgtg.wekit.*）
 *   - 沿用 Joker 自有的 ClickableFeature / SwitchFeature 抽象
 *   - 接入点：我方 `WeChatInputBarMenuApi`（已在）；不绑死逆向包 `us1` 的具体方法名。
 *
 * 第 29 轮阶段 1：先建骨架，不接 onEnable 真逻辑；阶段 2+ 由用户在实机反馈后激活。
 */
package dev.joker.features.items.chat_input_bar_menu

import androidx.activity.ComponentActivity
import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.android.showToast

/**
 * 聊天功能开关 —— 在会话底栏菜单中提供统一入口（可被 Joker 其它聊天类功能复用）。
 */
object ChatFunctionSwitch : dev.joker.features.core.SwitchFeature() {

    // ── 功能元数据 ──────────────────────────────────────────────────
    override val technicalId: String = "聊天功能开关"
    override val nameRes: Int = R.string.feature_chat_input_bar_menu_chat_function_switch_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes: Int = R.string.feature_chat_input_bar_menu_chat_function_switch_description

    /** 默认关闭 —— 用户主动启用才接管入口按钮；不影响现存功能。 */
    override val defaultEnabled: Boolean = false

    override fun onEnable() {
        // 阶段 2 再注册 WeChatInputBarMenuApi 入口（默认关闭时不做任何动作）。
        // 现阶段保持空，避免影响现有 WeChatInputBarMenuApi 注册列表。
    }

    override fun onDisable() {
        // 同上；阶段 2 移除入口。
    }

    /**
     * 设置入口（由 ClickableFeature 自动接入）；
     * 阶段 2 改造为 ClickableFeature，提供更细的菜单配置 UI。
     */
    fun openSettings(context: ComponentActivity) {
        showToast(context, technicalId)
    }
}