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
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Tune
import dev.joker.R
import dev.joker.features.api.ui.WeChatInputBarMenuApi
import dev.joker.activity.settings.SettingsActivity
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.FeaturesProvider
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.TextButton
import dev.joker.ui.content.m3.BaseWidget
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.WeLogger
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
                onClick = { context, _ ->
                    showChatFunctionMenu(context)
                },
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
            "已启用 ✓ 会话底栏右侧会出现「聊天功能」按钮，点它即可打开下面的汇总菜单。"
        } else {
            "未启用：请先打开本行开关，会话底栏才会出现「聊天功能」按钮。"
        }
        showToast(context, statusText)
        showChatFunctionMenu(context)
    }

    /**
     * 设置入口（由 ClickableFeature 自动接入）。
     */
    fun openSettings(context: Context) {
        showChatFunctionMenu(context)
    }

    // ═══════════════════════════════════════════════════════════════
    //  「聊天功能」汇总菜单（Round41 实装）
    //
    //  原实现只弹一句「阶段 2 提供汇总菜单」的 toast —— 用户点了等于没点，
    //  既看不到有哪些聊天功能、也没有任何配置入口。现在改为真正的汇总菜单：
    //  列出 Joker 里所有「聊天」分类且带配置页的功能，点进去就是各自的配置页；
    //  末尾附一个「打开 Joker 设置」直达入口。
    // ═══════════════════════════════════════════════════════════════

    private fun showChatFunctionMenu(context: Context) {
        // 必须用 filterIsInstance 而不是 filter { it is ClickableFeature }：
        // filter 不做类型收窄，lambda 之后的 feature 仍是 BaseFeature，
        // 于是 feature.onClick(activity) 会报 Unresolved reference 'onClick'
        // （CI run 36514971450 的真实报错）。
        val candidates = FeaturesProvider.ALL_FEATURES
            .filterIsInstance<ClickableFeature>()
            .filter { feature -> feature !== this && FeatureCategoryIds.CHAT in feature.categoryIds }
            .distinctBy { it.technicalId }
            .sortedBy { it.technicalId }

        showComposeDialog(context) {
            AlertDialogContent(
                    textScrolls = true,
                    title = { Text(technicalId) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        if (candidates.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = "当前没有可配置的聊天类功能。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        candidates.forEach { feature ->
                            item(key = feature.technicalId) {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = runCatching { feature.localizedName(context) }
                                        .getOrDefault(feature.technicalId),
                                    description = runCatching { feature.localizedDescription(context) }
                                        .getOrDefault(""),
                                    onClick = {
                                        onDismiss()
                                        val activity = context.findActivity()
                                        if (activity is ComponentActivity) {
                                            runCatching { feature.onClick(activity) }
                                                .onFailure { WeLogger.w(TAG, "打开 ${feature.technicalId} 配置页失败", it) }
                                        } else {
                                            showToast(context, "请回到 Joker 设置页打开 ${feature.technicalId}")
                                        }
                                    },
                                    trailingContent = {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Chevron_right,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                        }
                        item(key = "open_settings") {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = "打开 Joker 设置",
                                description = "查看全部功能开关与分类配置",
                                onClick = {
                                    onDismiss()
                                    val activity = context.findActivity() ?: return@BaseWidget
                                    runCatching {
                                        activity.startActivity(
                                            Intent(activity, SettingsActivity::class.java).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            },
                                        )
                                    }.onFailure { WeLogger.w(TAG, "打开设置页失败", it) }
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
            )
        }
    }

    private const val TAG = "ChatFunctionSwitch"
}

private fun Context.findActivity(): android.app.Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}