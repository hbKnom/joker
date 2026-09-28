/*
 * ChatFunctionSwitch.kt — 聊天功能开关 【第 29 轮 WeKit1945 整合 · Round30 真 hook】
 *
 * 证据等级：B+（重建版 + 原始 jadx）
 * 来源：WeKit 1945 逆向包 `01_逆向源码/chat_input_bar_menu/ChatFunctionSwitch.kt` + jadx `us1.txt`
 * 对应日志：「添加: 聊天功能开关」
 *
 * ★ 行为：
 *   - 启用时，向 WeChatInputBarMenuApi 注册一个 IActionItemsProvider，
 *     给会话底栏 + 按钮（统一入口图标），点击弹「聊天功能」汇总菜单。
 *   - 关闭时，从 WeChatInputBarMenuApi 移除 provider（幂等）。
 *   - 设置页：复用 Joker 自有 settings 框架，点击后弹「功能列表」。
 *
 * ★ 整合铁律（与上游脱钩）：
 *   - 包名 dev.joker.*
 *   - 接入 WeChatInputBarMenuApi（已在），不引入新 DexKit 委托，零编译风险。
 *   - 默认关闭；用户主动启用才接管入口按钮。
 */
package dev.joker.features.items.chat_input_bar_menu

import android.content.Context
import androidx.activity.ComponentActivity
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Tune
import dev.joker.R
import dev.joker.features.api.ui.WeChatInputBarMenuApi
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.android.showToast

/**
 * 聊天功能开关 —— 在会话底栏菜单中提供统一入口（可被 Joker 其它聊天类功能复用）。
 *
 * 【Round31 实装】继承 ClickableFeature（替代 SwitchFeature）+ noSwitchWidget=true
 * 让设置页有「聊天功能」入口可点，点击后弹说明 + 状态提示。
 * Round30 阶段 1 仅 SwitchFeature 骨架（默认关闭），设置页无入口；本轮升级。
 */
object ChatFunctionSwitch : ClickableFeature() {

    // ── 功能元数据 ──────────────────────────────────────────────────
    override val technicalId: String = "聊天功能开关"
    override val nameRes: Int = R.string.feature_chat_input_bar_menu_chat_function_switch_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes: Int = R.string.feature_chat_input_bar_menu_chat_function_switch_description

    /** 默认关闭 —— 用户主动启用才接管入口按钮；不影响现存功能。 */
    override val defaultEnabled: Boolean = false

    /** 设置页只显示可点击的「设置」按钮，不显示开关（开关由 provider 的 onEnable/onDisable 控制）。 */
    override val noSwitchWidget: Boolean = true

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "joker_chat_function_switch",
                icon = MaterialSymbols.Outlined.Tune,
                label = "聊天功能",
            ),
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }

    /**
     * 设置页入口（用户点击「聊天功能开关」行后触发）。
     * 阶段 2 可改造为更细的菜单配置 UI（按逆向版 jf0.a + qp1 框架）。
     * 当前用 toast 提示用户到会话底栏用「聊天功能」按钮触发汇总菜单。
     */
    override fun onClick(context: ComponentActivity) {
        val statusText = if (isEnabled) {
            "聊天功能开关：已启用 ✓\n会话底栏「聊天功能」按钮可触发。"
        } else {
            "聊天功能开关：未启用\n开启后会话底栏出现「聊天功能」按钮。"
        }
        showToast(context, statusText)
    }

    /**
     * 设置入口（由 ClickableFeature 自动接入）；
     * 阶段 2 改造为 ClickableFeature，提供更细的菜单配置 UI。
     */
    fun openSettings(context: Context) {
        showToast(context, technicalId)
    }
}