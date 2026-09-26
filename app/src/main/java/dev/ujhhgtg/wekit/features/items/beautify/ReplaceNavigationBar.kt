package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlined.Explore
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBar
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarDefaults
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarMode
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.rememberViewBackdrop
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.ReorderableList
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.monet.MonetColors
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import kotlin.math.abs
import kotlin.math.roundToInt

object ReplaceNavigationBar : ClickableFeature(), IResolveDex {

    override val technicalId = "美化首页底部导航栏"
    override val nameRes = R.string.feature_replace_navigation_bar_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_replace_navigation_bar_description

    private data class NavItem(
        val wechatIndex: Int,
        val outlined: ImageVector,
        val filled: ImageVector,
        @StringRes val labelRes: Int,
    )

    @Stable
    private val TAB_ITEMS = listOf(
        NavItem(0, MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home, R.string.nav_tab_home),
        NavItem(1, MaterialSymbols.Outlined.Contacts, MaterialSymbols.OutlinedFilled.Contacts, R.string.nav_tab_contacts),
        NavItem(2, MaterialSymbols.Outlined.Explore, MaterialSymbols.OutlinedFilled.Explore, R.string.nav_tab_discover),
        NavItem(3, MaterialSymbols.Outlined.Person, MaterialSymbols.OutlinedFilled.Person, R.string.nav_tab_me),
    )

    // ────────────────────────────────────────────
    // 0924 重构：将散落的多个布尔开关合并为三态枚举。
    // 旧键保留为迁移来源（首次读取时推导并落盘）；
    // 下方五个只读派生属性使下游渲染逻辑零改动。
    // ────────────────────────────────────────────
    enum class BarStyle(val storageKey: String) {
        NATIVE("native"),
        DOCKED("docked"),
        FLOATING("floating"),
        HIDDEN("hidden"),
        LIQUID_GLASS("liquid_glass");

        companion object {
            fun from(key: String?): BarStyle? = values().firstOrNull { it.storageKey == key }
        }
    }

    enum class IconStyle(val storageKey: String) {
        MATERIAL("material"),
        NATIVE("native");

        companion object {
            fun from(key: String?): IconStyle? = values().firstOrNull { it.storageKey == key }
        }
    }

    enum class LabelMode(val storageKey: String) {
        ICON("icon"),
        ICON_AND_TEXT("icon_and_text"),
        TEXT("text");

        companion object {
            fun from(key: String?): LabelMode? = values().firstOrNull { it.storageKey == key }
        }
    }

    private var barStyleKey by prefOption("nav_bar_style", "")
    private var iconStyleKey by prefOption("nav_bar_icon_style", "")
    private var labelModeKey by prefOption("nav_bar_label_mode", "")
    private var badgeTabIndices by prefOption("nav_bar_badge_tabs", emptySet<String>())

    // 旧开关：仅作为迁移输入，新代码不再写入
    private var legacyUseFloating by prefOption("nav_bar_use_floating", true)
    private var legacyUseBackdrop by prefOption("nav_bar_use_backdrop", true)
    private var legacyUseWechatIcons by prefOption("nav_bar_use_wechat_icons", false)
    private var legacyHideLabels by prefOption("nav_bar_hide_labels", false)
    private var legacyShowFinderBadge by prefOption("nav_bar_show_finder_badge", true)

    val barStyle: BarStyle
        get() = BarStyle.from(barStyleKey) ?: run {
            val derived = when {
                !legacyUseFloating -> BarStyle.DOCKED
                legacyUseBackdrop -> BarStyle.LIQUID_GLASS
                else -> BarStyle.FLOATING
            }
            barStyleKey = derived.storageKey
            derived
        }

    val iconStyle: IconStyle
        get() = IconStyle.from(iconStyleKey) ?: run {
            val derived = if (legacyUseWechatIcons) IconStyle.NATIVE else IconStyle.MATERIAL
            iconStyleKey = derived.storageKey
            derived
        }

    val labelMode: LabelMode
        get() = LabelMode.from(labelModeKey) ?: run {
            val derived = if (legacyHideLabels) LabelMode.ICON else LabelMode.ICON_AND_TEXT
            labelModeKey = derived.storageKey
            derived
        }

    // ↓↓↓ 只读派生属性：「渲染零改动」的关键 ↓↓↓
    private val useFloating: Boolean
        get() = barStyle == BarStyle.FLOATING || barStyle == BarStyle.LIQUID_GLASS

    private val useBackdrop: Boolean
        get() = barStyle == BarStyle.LIQUID_GLASS

    private val useWechatIcons: Boolean
        get() = iconStyle == IconStyle.NATIVE

    private val hideLabels: Boolean
        get() = labelMode == LabelMode.ICON

    private val textOnlyLabels: Boolean
        get() = labelMode == LabelMode.TEXT

    /**
     * 每页独立角标（0924 把「发现页角标」总开关拆成了逐页开关）。
     *
     * 迁移口径与上面三个枚举一致：新键缺席时用旧开关 `nav_bar_show_finder_badge` 推导一次并落盘。
     * 否则老用户即使把发现页角标关掉了，升级后也会被默认值（全开）重新打开。
     */
    private val badgeTabs: Set<String>
        get() {
            WePrefs.getStringSet("nav_bar_badge_tabs")?.let { return it }
            val derived = if (legacyShowFinderBadge) {
                TAB_ITEMS.map { it.wechatIndex.toString() }.toSet()
            } else {
                emptySet()
            }
            badgeTabIndices = derived
            return derived
        }

    private val showFinderBadge: Boolean
        get() = 2 in normalizedEnabledTabIndices(badgeTabs)

    private var autoHideOnScroll by prefOption("nav_bar_auto_hide_on_scroll", false)
    private var animatePageChange by prefOption("nav_bar_animate_page_change", true)
    private var blurRadius by prefOption("nav_bar_blur_radius", 8)
    private var dynamicGravityHighlight by prefOption("nav_bar_dynamic_gravity_highlight", false)
    private var barScalePercent by prefOption("nav_bar_scale", 100)
    private var tabOrder by prefOption("nav_bar_tab_order", TAB_ITEMS.joinToString(",") { it.wechatIndex.toString() })
    private var enabledTabs by prefOption("nav_bar_enabled_tabs", TAB_ITEMS.map { it.wechatIndex.toString() }.toSet())

    private const val MIN_BLUR_RADIUS = 0
    private const val MAX_BLUR_RADIUS = 40

    private const val MIN_BAR_SCALE = 50
    private const val MAX_BAR_SCALE = 150
    private const val BAR_SCALE_STEP = 5
    private const val BASE_BAR_HEIGHT_DP = 56

    // Matches the double-tap threshold WeChat's own tab listener (f8/r8) uses.
    private const val DOUBLE_TAP_WINDOW_MS = 300L

    // Matches NagramXF's CubicBezierInterpolator.EASE_OUT_QUINT, the curve of its
    // scroll-hide animator for the floating bottom bar.
    private val SCROLL_HIDE_EASING = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    private const val SCROLL_HIDE_DURATION_MS = 300
    private const val SCROLL_HIDE_MIN_SCALE = 0.85f

    private fun normalizedTabOrder(rawOrder: String = tabOrder): List<NavItem> {
        val orderedIndices = rawOrder.split(",")
            .mapNotNull(String::toIntOrNull)
            .filter { index -> TAB_ITEMS.any { it.wechatIndex == index } }
            .distinct()
            .toMutableList()
        TAB_ITEMS.forEach { item ->
            if (item.wechatIndex !in orderedIndices) orderedIndices += item.wechatIndex
        }
        return orderedIndices.map { index -> TAB_ITEMS.first { it.wechatIndex == index } }
    }

    private fun normalizedEnabledTabIndices(rawEnabled: Set<String> = enabledTabs): Set<Int> {
        val validIndices = TAB_ITEMS.mapTo(mutableSetOf(), NavItem::wechatIndex)
        return rawEnabled.mapNotNull(String::toIntOrNull)
            .filterTo(linkedSetOf()) { it in validIndices }
    }

    override fun onEnable() {
        // Freeze the page set for this process. Changing these options is intentionally applied
        // only on the next WeChat launch because FragmentStatePagerAdapter cannot safely change
        // the meaning of already-instantiated positions.
        val orderedTabItems = normalizedTabOrder()
        val enabledTabIndices = normalizedEnabledTabIndices()

        // 0924：「微信原生」样式 = 功能启用但不插手底栏（完全保持微信自己的渲染）
        if (barStyle == BarStyle.NATIVE) return

        // 0924：「隐藏」样式 = 清空可见页集合，直接复用下方已有的隐藏分支
        //（该分支会移除底栏子 View 并同步关掉 FrostedContentView 磨砂）
        val effectiveEnabledIndices =
            if (barStyle == BarStyle.HIDDEN) emptySet<Int>() else enabledTabIndices

        val visibleTabItems = orderedTabItems.filter { it.wechatIndex in effectiveEnabledIndices }

        if (visibleTabItems.isEmpty()) {
            WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
                val viewPager = thisObject!!.reflekt()
                    .firstField {
                        name = "mViewPager"
                    }
                    .get()!! as WxViewPager
                val viewParent = viewPager.parent as ViewGroup
                val bottomTabViewGroup = viewParent.getChildAt(1) as ViewGroup

                bottomTabViewGroup.removeAllViews()
                bottomTabViewGroup.visibility = View.GONE
            }

            // Without a replacement bar, WeChat's bottom blur must also be disabled or it
            // leaves a frosted strip where the original navigation bar used to be.
            "com.tencent.mm.ui.FrostedContentView".toClass().firstMethod {
                parameters { it[0] == bool && it[1] == int }
            }.hookBefore {
                args[0] = false
            }
            return
        }

        val visibleWechatIndices = visibleTabItems.map(NavItem::wechatIndex)
        val homePagerIndex = visibleWechatIndices.indexOf(0)
        val remapProgrammaticTab = ThreadLocal.withInitial { false }
        val animateNextPageChange = ThreadLocal.withInitial { false }
        val allowLogicalTabCount = ThreadLocal.withInitial { false }
        val callbackPagerIndex = ThreadLocal<Int?>()

        val tabsAdapterClass = $$"com.tencent.mm.ui.MainTabUI$TabsAdapter".toClass()
        tabsAdapterClass.reflekt().apply {
            firstMethod { name = "getCount" }.hookAfter(priority = 100) {
                result = if (allowLogicalTabCount.get() == true) TAB_ITEMS.size else visibleTabItems.size
            }
            firstMethod {
                name = "getItem"
                parameters(int)
            }.hookBefore(priority = 100) {
                args[0] = visibleTabItems[args[0] as Int].wechatIndex
            }

            listOf("onPageScrolled", "onPageSelected").forEach { callbackName ->
                firstMethod { name = callbackName }.apply {
                    hookBefore(priority = 100) {
                        val pagerIndex = args[0] as Int
                        callbackPagerIndex.set(pagerIndex)
                        args[0] = visibleTabItems[pagerIndex].wechatIndex
                    }
                    hookAfter(priority = 100) {
                        callbackPagerIndex.remove()
                    }
                }
            }

            firstMethod {
                name = "onTabClick"
                parameters(int)
            }.apply {
                hookBefore(priority = 100) {
                    if (args[0] as Int !in visibleWechatIndices) {
                        result = null
                    } else {
                        remapProgrammaticTab.set(true)
                        animateNextPageChange.set(true)
                    }
                }
                hookAfter(priority = 100) {
                    remapProgrammaticTab.remove()
                    animateNextPageChange.remove()
                }
            }
        }

        methodChangeTab.apply {
            hookBefore(priority = 100) {
                val requestedIndex = args[0] as Int
                if (requestedIndex !in visibleWechatIndices) {
                    args[0] = visibleWechatIndices.first()
                }
                remapProgrammaticTab.set(true)
                // MainTabUI checks the logical WeChat index against getCount() before it
                // reaches the pager. Let that check see four logical tabs; the pager itself
                // sees the reduced count after setCurrentItem is entered below.
                allowLogicalTabCount.set(true)
            }
            hookAfter(priority = 100) {
                remapProgrammaticTab.remove()
                allowLogicalTabCount.remove()

                val logicalIndex = args[0] as Int
                val pagerIndex = visibleWechatIndices.indexOf(logicalIndex)
                if (pagerIndex >= 0) {
                    val viewPager = thisObject!!.reflekt()
                        .firstField { name = "mViewPager" }
                        .get()!! as WxViewPager
                    if (viewPager.currentItem != pagerIndex) {
                        viewPager.setCurrentItem(pagerIndex, false)
                    }
                }
            }
        }

        val animatePageChange = animatePageChange

        "com.tencent.mm.ui.mogic.WxViewPager".toClass().reflekt().apply {
            listOf("setCurrentItem", "setCurrentItemNotify").forEach { methodName ->
                firstMethod {
                    name = methodName
                    parameters(int, bool)
                }.hookBefore(priority = 100) {
                    if (remapProgrammaticTab.get() != true) return@hookBefore
                    val logicalIndex = args[0] as Int
                    val pagerIndex = visibleWechatIndices.indexOf(logicalIndex)
                    if (pagerIndex >= 0) args[0] = pagerIndex
                    allowLogicalTabCount.set(false)
                    // The second parameter is the pager's `smoothScroll` flag. Flipping it to
                    // true makes WxViewPager animate the same horizontal slide a finger swipe
                    // produces. This is scoped to `onTabClick`-originated changes (actual tab
                    // taps) only: MainTabUI.a(int) is also driven by programmatic flows that
                    // fire rapid same-frame tab bounces — e.g. returning from the wallet
                    // "服务" page starts LauncherUI with FLAG_ACTIVITY_CLEAR_TOP +
                    // preferred_tab, which makes MainTabUI.f() call a(0) then a(3) back to
                    // back. Stock WeChat snaps both (smoothScroll=false) so the bounce is
                    // invisible; animating both round-trips desyncs the pager (content stays
                    // on the first page while the logical tab says the second). The
                    // state-restore and first-layout paths never reach here either because
                    // the `remapProgrammaticTab` guard is only armed by tab interactions.
                    // Non-adjacent jumps sweep past the pages in between, but MainTabUI sets
                    // an offscreen page limit of 4, so every one of them is alive and renders
                    // real content. The pager caps the scroll duration at 600ms on its own.
                    if (animatePageChange && animateNextPageChange.get() == true) args[1] = true
                }
            }
        }

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject!!.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity
            val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)
            val viewPager = thisObject!!.reflekt()
                .firstField {
                    name = "mViewPager"
                }
                .get()!! as WxViewPager
            val tabsAdapter = thisObject!!.reflekt()
                .firstField {
                    name = "mTabsAdapter"
                }
                .get()!!
            val methodOnTabClick = tabsAdapter.reflekt()
                .firstMethod {
                    name = "onTabClick"
                }.self

            val navigateToTab = { pagerIndex: Int ->
                methodOnTabClick.invoke(tabsAdapter, visibleTabItems[pagerIndex].wechatIndex)
            }

            val viewParent = viewPager.parent as ViewGroup
            val bottomTabViewGroup = viewParent.getChildAt(1) as ViewGroup

            // Sample the original tab bar's icon drawables before we clear its children, so
            // "use WeChat native icons" has real bitmaps to render.
            if (useWechatIcons) sampleWechatTabIcons(thisObject!!)

            // WeChat's original bottom tab (LauncherUIBottomTabView) is kept alive — we only
            // clear its children below — so its own OnClickListener (an `f8`/`r8` instance)
            // survives with its double-tap state machine and the LiveData event it fires.
            // Double-tapping the Chat tab makes that listener fire WeChat's "scroll to next
            // unread conversation" event, which MainUI already observes. We capture the
            // listener and replay two rapid clicks to reproduce that behaviour, so we don't
            // have to resolve the fully-obfuscated event class ourselves.
            val bottomTabClickListener = runCatching {
                bottomTabViewGroup.reflekt()
                    .firstField { type = View.OnClickListener::class }
                    .get() as? View.OnClickListener
            }.getOrNull()
            val doubleTapProbeView = View(activity).apply { tag = 0 }

            var lastHomeTapUptime = 0L
            val onTabClicked = { index: Int ->
                val isHome = visibleTabItems[index].wechatIndex == 0
                if (isHome && bottomTabClickListener != null &&
                    SystemClock.uptimeMillis() - lastHomeTapUptime <= DOUBLE_TAP_WINDOW_MS
                ) {
                    // Second tap on the Chat tab within the double-tap window: drive WeChat's
                    // own listener twice so its internal timing check trips and fires the
                    // scroll-to-next-unread event.
                    bottomTabClickListener.onClick(doubleTapProbeView)
                    bottomTabClickListener.onClick(doubleTapProbeView)
                    lastHomeTapUptime = SystemClock.uptimeMillis()
                } else {
                    navigateToTab(index)
                    lastHomeTapUptime = if (isHome) SystemClock.uptimeMillis() else 0L
                }
            }

            bottomTabViewGroup.setLifecycleOwner(lifecycleOwner)

            val initialPagerIndex = viewPager.currentItem
            val selectedPageIndexState = mutableIntStateOf(initialPagerIndex)
            val scrollOffsetState = mutableFloatStateOf(0f)
            // Target page as soon as it's decided: immediately on a tab tap, and at the
            // half-way crossing during a finger swipe. Drives the discrete spring so a tap
            // still bulges + slides the pill instead of teleporting.
            val targetPageIndexState = mutableIntStateOf(initialPagerIndex)

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageScrolled" }
                .hookBefore {
                    val position = callbackPagerIndex.get()
                        ?: visibleWechatIndices.indexOf(args[0] as Int).coerceAtLeast(0)
                    val positionOffset = args[1] as Float

                    selectedPageIndexState.intValue = position
                    scrollOffsetState.floatValue = positionOffset

                    // Leaving the conversation page always restores the bar and invalidates
                    // the scroll-direction tracker, so returning to the list starts fresh.
                    if (position != homePagerIndex) {
                        barScrollHiddenState.value = false
                        scrollUpdated = false
                    }
                }

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageSelected" }
                .hookBefore {
                    targetPageIndexState.intValue = callbackPagerIndex.get()
                        ?: visibleWechatIndices.indexOf(args[0] as Int).coerceAtLeast(0)
                }

            val useFloating = useFloating
            val useBackdrop = useBackdrop
            val showFinderBadge = showFinderBadge
            val hideLabels = hideLabels
            val dynamicGravityHighlight = dynamicGravityHighlight
            val barScale = barScalePercent.coerceIn(MIN_BAR_SCALE, MAX_BAR_SCALE) / 100f

            val composeView = ComposeView(activity).apply {
                setLifecycleOwner(lifecycleOwner)

                setContent {
                    InjectedUiTheme {
                        val view = LocalView.current

                        // Long-press "发现" tab to jump straight into the improved timeline.
                        val openImproveSnsTimeline = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            activity.startActivity(
                                Intent().setClassName(
                                    "com.tencent.mm",
                                    "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"
                                )
                            )
                        }

                        var selectedIndex by selectedPageIndexState
                        val targetIndex by targetPageIndexState
                        val unreadCount by unreadCountState
                        val finderUnreadCount by finderUnreadCountState
                        val showFinderDot by showFinderDotState
                        val contactUnreadCount by contactUnreadCountState

                        // 底栏是 WeKit 自己接管的 UI：莫奈生效时直接吃引擎色板（与微信原生同源，
                        // 比从 primary 种子重新派生的主题色更贴合），未生效时保持原来的固定灰/黑，
                        // 避免改变既有外观。
                        val night = isSystemInDarkTheme()
                        val tokens = MonetColors.applied.value?.let { MonetColors.tokens(night) }
                        val backgroundColor = tokens?.surfaceContainer?.let { Color(it) }
                            ?: if (night) Color(0xFF191919) else Color(0xFFF7F7F7)
                        val activeColor = tokens?.primary?.let { Color(it) }
                            ?: MaterialTheme.colorScheme.primary
                        val inactiveColor = tokens?.onSurfaceVariant?.let { Color(it) }
                            ?: if (night) Color(0xFF999999) else Color(0xFF181818)

                        // Scale the bar by overriding the density rather than wrapping it in a
                        // graphicsLayer: every dp/sp inside (height, icons, pill, blur radius,
                        // shadows) is then laid out at the new size instead of being resampled,
                        // so the glass stays crisp and touch targets match what's drawn. Window
                        // insets are unaffected — they round-trip through the same density.
                        val baseDensity = LocalDensity.current
                        val scaledDensity = remember(baseDensity, barScale) {
                            Density(baseDensity.density * barScale, baseDensity.fontScale)
                        }

                        if (!useFloating) {
                            val offset by scrollOffsetState
                            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                                NavigationBar(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(BASE_BAR_HEIGHT_DP.dp),
                                    containerColor = backgroundColor
                                ) {
                                    visibleTabItems.forEachIndexed { index, item ->
                                        val label = stringResource(item.labelRes)
                                        val isSelected = index == selectedIndex
                                        val isNext = index == selectedIndex + 1

                                        val tint = when {
                                            isSelected -> lerpColor(
                                                activeColor,
                                                inactiveColor,
                                                offset
                                            )

                                            isNext -> lerpColor(
                                                inactiveColor,
                                                activeColor,
                                                offset
                                            )

                                            else -> inactiveColor
                                        }

                                        val showFilled = if (offset < 0.5f) isSelected else isNext

                                        NavigationBarItem(
                                            selected = isSelected && offset < 0.5f,
                                            onClick = {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                                onTabClicked(index)
                                            },
                                            modifier = if (item.wechatIndex == 2) Modifier.onLongPress(openImproveSnsTimeline) else Modifier,
                                            icon = {
                                                BadgedBox(
                                                    badge = {
                                                        if (index == 0 && unreadCount > 0) {
                                                            Badge(containerColor = MaterialTheme.colorScheme.error) {
                                                                Text(
                                                                    if (unreadCount <= 99) unreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                    color = MaterialTheme.colorScheme.onError, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (item.wechatIndex == 1 && contactUnreadCount > 0) {
                                                            Badge(containerColor = MaterialTheme.colorScheme.error) {
                                                                Text(
                                                                    if (contactUnreadCount <= 99) contactUnreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                    color = MaterialTheme.colorScheme.onError, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (item.wechatIndex == 2 && showFinderBadge) {
                                                            if (finderUnreadCount > 0) {
                                                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                                                    Text(
                                                                        if (finderUnreadCount <= 99) finderUnreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                        color = MaterialTheme.colorScheme.onError, fontSize = 10.sp
                                                                    )
                                                                }
                                                            } else if (showFinderDot) {
                                                                Badge(containerColor = MaterialTheme.colorScheme.error)
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    val wechatIcon = wechatTabIcons.value[item.wechatIndex]
                                                    if (useWechatIcons && wechatIcon != null) {
                                                        Image(
                                                            bitmap = wechatIcon,
                                                            contentDescription = label,
                                                            colorFilter = ColorFilter.tint(tint),
                                                            modifier = Modifier.size(24.dp),
                                                        )
                                                    } else {
                                                        Crossfade(
                                                            targetState = showFilled,
                                                            animationSpec = tween(200),
                                                            label = "navIcon"
                                                        ) { filled ->
                                                            Icon(
                                                                imageVector = if (filled) item.filled else item.outlined,
                                                                contentDescription = label,
                                                                tint = tint
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            label = null,
                                            alwaysShowLabel = false,
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = activeColor.copy(alpha = 0.15f),
                                                selectedIconColor = activeColor,
                                                unselectedIconColor = inactiveColor,
                                                selectedTextColor = activeColor,
                                                unselectedTextColor = inactiveColor
                                            )
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val bottomCenter = Modifier.align(Alignment.BottomCenter)

                                // NagramXF-style scroll auto-hide: slide below the screen,
                                // shrink to 85% and fade out, reversing when the list scrolls
                                // up again. barHeightPx includes the bottom padding because
                                // onSizeChanged observes the padded bounds, so translating by
                                // it moves the bar fully off screen.
                                val autoHideProgress by animateFloatAsState(
                                    targetValue = if (barScrollHiddenState.value) 1f else 0f,
                                    animationSpec = tween(
                                        SCROLL_HIDE_DURATION_MS,
                                        easing = SCROLL_HIDE_EASING
                                    ),
                                    label = "navAutoHide"
                                )
                                val barHeightPx = remember { mutableIntStateOf(0) }
                                val bottomPadding =
                                    12.dp + WindowInsets.navigationBars.asPaddingValues()
                                        .calculateBottomPadding()

                                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                                    FloatingBottomBar(
                                        items = visibleTabItems,
                                        modifier = bottomCenter
                                            .onSizeChanged { barHeightPx.intValue = it.height }
                                            .padding(bottom = bottomPadding)
                                            .graphicsLayer {
                                                val progress = autoHideProgress
                                                translationY = progress * barHeightPx.intValue
                                                alpha = 1f - progress
                                                val scale =
                                                    1f - (1f - SCROLL_HIDE_MIN_SCALE) * progress
                                                scaleX = scale
                                                scaleY = scale
                                            },
                                        selectedIndex = { targetIndex },
                                        onSelected = { index ->
                                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                            navigateToTab(index)
                                        },
                                        // Sample WeChat's real content (native ViewPager) into the
                                        // glass. rememberLayerBackdrop would only capture Compose
                                        // pixels, of which there are none behind this overlay bar.
                                        backdrop = rememberViewBackdrop(viewPager, lifecycleOwner),
                                        mode = if (useBackdrop) {
                                            FloatingBottomBarMode.LiquidGlass
                                        } else {
                                            FloatingBottomBarMode.None
                                        },
                                        colors = FloatingBottomBarDefaults.colors(
                                            containerColor = backgroundColor,
                                            indicatorColor = activeColor,
                                            contentColor = inactiveColor,
                                            activeContentColor = activeColor
                                        ),
                                        onSelectedTabTap = { index ->
                                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                            if (visibleTabItems[index].wechatIndex == 0) {
                                                onTabClicked(index)
                                            }
                                        },
                                        onTabLongPress = { index ->
                                            if (visibleTabItems[index].wechatIndex == 2) {
                                                openImproveSnsTimeline()
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                        liquidGlassBlurRadius = blurRadius.dp,
                                        dynamicGravityHighlight = dynamicGravityHighlight,
                                        iconContent = { item, index ->
                                            val label = stringResource(item.labelRes)
                                            // Key the fill crossfade to the target page (the same
                                            // driver as the pill), not the settled page: target
                                            // flips immediately on a tab tap and on finger release
                                            // during a swipe, while the settled page only advances
                                            // after the pager stops. This matches SettingsActivity's
                                            // Miuix bar, where the icon fills the moment the tab
                                            // decision is made instead of a beat after the pill.
                                            val isSelected = index == targetIndex

                                            BadgedBox(
                                                badge = {
                                                    if (index == 0 && unreadCount > 0) {
                                                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                                                            Text(
                                                                if (unreadCount <= 99) unreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                color = MaterialTheme.colorScheme.onError, fontSize = 10.sp
                                                            )
                                                        }
                                                    } else if (item.wechatIndex == 1 && contactUnreadCount > 0) {
                                                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                                                            Text(
                                                                if (contactUnreadCount <= 99) contactUnreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                color = MaterialTheme.colorScheme.onError, fontSize = 10.sp
                                                            )
                                                        }
                                                    } else if (item.wechatIndex == 2 && showFinderBadge) {
                                                        if (finderUnreadCount > 0) {
                                                            Badge(containerColor = MaterialTheme.colorScheme.error) {
                                                                Text(
                                                                    if (finderUnreadCount <= 99) finderUnreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                    color = MaterialTheme.colorScheme.onError, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (showFinderDot) {
                                                            Badge(containerColor = MaterialTheme.colorScheme.error)
                                                        }
                                                    }
                                                }
                                            ) {
                                                // 0924：标签内容=「文本」时不渲染图标
                                                val wechatIconFloating = wechatTabIcons.value[item.wechatIndex]
                                                if (!textOnlyLabels && useWechatIcons && wechatIconFloating != null) {
                                                    Image(
                                                        bitmap = wechatIconFloating,
                                                        contentDescription = label,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                } else if (!textOnlyLabels) {
                                                    Crossfade(
                                                        targetState = isSelected,
                                                        animationSpec = tween(200),
                                                        label = "navIconFloating"
                                                    ) { selected ->
                                                        Icon(
                                                            imageVector = if (selected) item.filled else item.outlined,
                                                            contentDescription = label
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        labelContent = { item, _ ->
                                            if (!hideLabels) {
                                                Text(
                                                    text = stringResource(item.labelRes),
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Visible
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (useFloating) {
                // In floating mode, hide the original tab bar container so that WeChat's
                // FrostedContentView reads its height as 0 and doesn't draw a frosted grey
                // overlay behind it. Instead, attach the ComposeView directly to the parent
                // FrameLayout as an overlay on top of the content.
                bottomTabViewGroup.removeAllViews()
                bottomTabViewGroup.visibility = View.GONE

                // The pill scales up (press bulge ~1.39x plus velocity overshoot) via a
                // graphicsLayer, so it draws beyond the ComposeView's WRAP_CONTENT bounds.
                // The bottom overdraw lands in the padding/inset gap, but the top overdraw
                // extends above the ComposeView and would be clipped by the Android view
                // hierarchy. Disable child/padding clipping on the parent so it renders.
                viewParent.clipChildren = false
                viewParent.clipToPadding = false
                composeView.clipChildren = false
                composeView.clipToPadding = false

                viewParent.addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    )
                )
            } else {
                bottomTabViewGroup.removeAllViews()
                bottomTabViewGroup.addView(composeView)
            }
        }

        methodUpdateTabUnread.hookBefore {
            val count = args[0] as Int
            unreadCountState.intValue = count
            result = null
        }

        methodUpdateFriendTabUnread.hookBefore {
            val count = args[0] as Int
            finderUnreadCountState.intValue = count
            result = null
        }

        methodShowFriendPoint.hookBefore {
            val show = args[0] as Boolean
            showFinderDotState.value = show
            result = null
        }

        methodUpdateContactTabUnread.hookBefore {
            val count = args[0] as Int
            contactUnreadCountState.intValue = count
            result = null
        }

        // Scroll auto-hide: observe the home conversation list. ConversationListView extends
        // ListView and installs itself as its own OnScrollListener, so instead of replacing
        // that listener (which would break WeChat's own handling) we hook its listener
        // methods directly. They fire on every item-scroll position change, drag or fling.
        val conversationListViewClass = "com.tencent.mm.ui.conversation.ConversationListView".toClass()
        conversationListViewClass.reflekt().apply {
            firstMethod {
                name = "onScroll"
                parameters { it.size == 4 }
            }.hookAfter {
                // Same gating as NagramXF: hide on any downward movement, but only show
                // again while the finger is actively dragging the list up (touch scroll);
                // a settling downward fling must not resurrect the bar.
                if (!autoHideOnScroll) return@hookAfter
                val list = thisObject as AbsListView
                val firstPosition = args[1] as Int
                val firstViewTop = list.getChildAt(0)?.top ?: return@hookAfter
                updateConversationScroll(firstPosition, firstViewTop)
            }

            firstMethod {
                name = "onScrollStateChanged"
                parameters { it.size == 2 }
            }.hookAfter {
                scrollingManually =
                    args[1] as Int == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL
                if (scrollingManually) dragSawDownward = false
            }
        }

        val recyclerOnScrolled = WeConversationListViewApi.methodRecyclerOnScrolled
        if (!recyclerOnScrolled.isPlaceholder) {
            recyclerOnScrolled.hookAfter {
                if (!autoHideOnScroll) return@hookAfter
                val recyclerView = args[0] as ViewGroup
                val firstPosition =
                    WeConversationListViewApi.methodRecyclerFirstVisiblePosition.method
                        .invoke(recyclerView) as Int
                if (firstPosition < 0) return@hookAfter
                val firstViewTop = recyclerView.getChildAt(0)?.top ?: return@hookAfter
                updateConversationScroll(firstPosition, firstViewTop)
            }
            WeConversationListViewApi.methodRecyclerOnScrollStateChanged.hookAfter {
                scrollingManually = args[1] as Int == 1
                if (scrollingManually) dragSawDownward = false
            }
        }

        // Suppress FrostedContentView's bottom blur overlay in floating mode.
        //
        // In WeChat 8.0.69, MainUI.q0() (onResume) calls:
        //   frostedContentView.a(true, tabBar.getHeight())
        // synchronously during doOnCreate — before our hookAfter fires and
        // sets the tab bar to GONE. By that point bottomBlurAreaHeight is
        // already set to the real measured height. Worse, a() has a <= 0
        // fallback: if height is 0 it computes dimen.b2*density + nav_bar_height,
        // producing the short frosted-glass strip you see below our bar.
        // Hooking a() and forcing its first arg (frostedEnabled) to false is the
        // only reliable fix regardless of call timing.
        "com.tencent.mm.ui.FrostedContentView".toClass().firstMethod {
            parameters { it[0] == bool && it[1] == int }
        }.hookBefore {
            if (useFloating) args[0] = false
        }
    }

    private val unreadCountState = mutableIntStateOf(0)
    private val finderUnreadCountState = mutableIntStateOf(0)
    private val showFinderDotState = mutableStateOf(false)
    private val contactUnreadCountState = mutableIntStateOf(0)

    // True while the bar should be hidden by the conversation-list scroll auto-hide.
    private val barScrollHiddenState = mutableStateOf(false)
    // ----------------------------------------------------------------------------------------------
    // Native (WeChat) tab icons (nav_bar_use_wechat_icons)
    // ----------------------------------------------------------------------------------------------
    private val wechatTabIcons = mutableStateOf<Map<Int, ImageBitmap>>(emptyMap())

    private fun findImageView(view: View): ImageView? {
        if (view is ImageView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findImageView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * Snapshot the original bottom tab bar's icon drawables before it is hidden, so the replacement
     * bar can render WeChat's own icons in "native icon" mode.
     */
    private fun sampleWechatTabIcons(mainUi: Any) {
        runCatching {
            val viewPager = mainUi.reflekt().firstFieldOrNull { name = "mViewPager" }
                ?.get() as? WxViewPager ?: return
            val parent = viewPager.parent as? ViewGroup ?: return
            val bottomTabViewGroup = parent.getChildAt(1) as? ViewGroup ?: return
            val result = HashMap<Int, ImageBitmap>()
            for (i in 0 until bottomTabViewGroup.childCount) {
                val image = findImageView(bottomTabViewGroup.getChildAt(i)) ?: continue
                val drawable = image.drawable ?: continue
                result[i] = drawable.toBitmap().asImageBitmap()
            }
            if (result.isNotEmpty()) wechatTabIcons.value = result
        }.onFailure { WeLogger.w("ReplaceNavigationBar", "sample native tab icons failed", it) }
    }

    // Conversation-list scroll tracking, mirroring NagramXF's DialogsActivity logic:
    // direction is derived from the first visible item's position/top delta, and the
    // bar only reappears while the finger is actively dragging up — so it never pops
    // back the moment a downward fling settles. All touched from the main thread only.
    private var scrollPrevPosition = -1
    private var scrollPrevTop = 0
    private var scrollUpdated = false
    private var scrollingManually = false

    // Whether the current touch drag has scrolled the list downward yet, reset on each new
    // drag. A downward fling that follows a downward drag is a genuine user scroll, while a
    // downward motion during a fling that never had one is WeChat's top overscroll bounce.
    private var dragSawDownward = false

    private fun updateConversationScroll(firstPosition: Int, firstViewTop: Int) {
        val goingDown: Boolean
        val changed: Boolean
        if (scrollPrevPosition == firstPosition) {
            val topDelta = scrollPrevTop - firstViewTop
            goingDown = firstViewTop < scrollPrevTop
            changed = abs(topDelta) > 1
        } else {
            goingDown = firstPosition > scrollPrevPosition
            changed = true
        }
        // Pull-down overscroll springs the first row upward after release. Suppress that
        // bounce unless the same gesture actually dragged the list downward first.
        if (scrollingManually && goingDown) dragSawDownward = true
        val isOverscrollBounceBack = goingDown && !scrollingManually &&
            !dragSawDownward && firstPosition == scrollPrevPosition
        if (changed && scrollUpdated && (goingDown || scrollingManually) &&
            !isOverscrollBounceBack
        ) {
            barScrollHiddenState.value = goingDown
        }
        scrollPrevPosition = firstPosition
        scrollPrevTop = firstViewTop
        scrollUpdated = true
    }

    /**
     * Non-consuming long-press modifier. Fires [block] when the pointer is held down long enough,
     * but does **not** consume the down/up events, so the item's own tap ripple and onClick still work.
     */
    private fun Modifier.onLongPress(block: () -> Unit): Modifier = pointerInput(block) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            block()
        }
    }

    private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            red = start.red + (stop.red - start.red) * f,
            green = start.green + (stop.green - start.green) * f,
            blue = start.blue + (stop.blue - start.blue) * f,
            alpha = start.alpha + (stop.alpha - start.alpha) * f
        )
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var barStyleInput by remember { mutableStateOf(barStyle) }
            var iconStyleInput by remember { mutableStateOf(iconStyle) }
            var labelModeInput by remember { mutableStateOf(labelMode) }
            var autoHideOnScrollInput by remember { mutableStateOf(autoHideOnScroll) }
            var dynamicGravityHighlightInput by remember { mutableStateOf(dynamicGravityHighlight) }
            var animatePageChangeInput by remember { mutableStateOf(animatePageChange) }
            var blurRadiusInput by remember { mutableFloatStateOf(blurRadius.toFloat()) }
            var barScaleInput by remember {
                mutableFloatStateOf(barScalePercent.coerceIn(MIN_BAR_SCALE, MAX_BAR_SCALE).toFloat())
            }

            // 0924：底栏样式决定哪些子项生效
            val floatingInput = barStyleInput == BarStyle.FLOATING || barStyleInput == BarStyle.LIQUID_GLASS
            val glassInput = barStyleInput == BarStyle.LIQUID_GLASS
            val zeroPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_replace_navigation_bar_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(
                            title = stringResource(R.string.nav_bar_style),
                            contentPadding = zeroPadding,
                        ) {
                            listOf(
                                BarStyle.NATIVE to R.string.nav_bar_style_native,
                                BarStyle.DOCKED to R.string.nav_bar_style_docked,
                                BarStyle.FLOATING to R.string.nav_bar_style_floating,
                                BarStyle.HIDDEN to R.string.nav_bar_style_hidden,
                                BarStyle.LIQUID_GLASS to R.string.nav_bar_style_liquid_glass,
                            ).forEach { option ->
                                item {
                                    RadioButtonWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(option.second),
                                        selected = barStyleInput == option.first,
                                        onClick = {
                                            val picked = option.first
                                            barStyleInput = picked
                                            barStyleKey = picked.storageKey
                                            // 退出悬浮/液态玻璃时重置滚动隐藏态
                                            if (picked != BarStyle.FLOATING && picked != BarStyle.LIQUID_GLASS) {
                                                barScrollHiddenState.value = false
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.nav_icon_style),
                            contentPadding = zeroPadding,
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            listOf(
                                IconStyle.MATERIAL to R.string.nav_icon_style_material,
                                IconStyle.NATIVE to R.string.nav_icon_style_native,
                            ).forEach { option ->
                                item {
                                    RadioButtonWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(option.second),
                                        selected = iconStyleInput == option.first,
                                        onClick = {
                                            iconStyleInput = option.first
                                            iconStyleKey = option.first.storageKey
                                        },
                                    )
                                }
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.nav_label_content),
                            contentPadding = zeroPadding,
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            listOf(
                                LabelMode.ICON to R.string.nav_label_icon,
                                LabelMode.ICON_AND_TEXT to R.string.nav_label_icon_and_text,
                                LabelMode.TEXT to R.string.nav_label_text,
                            ).forEach { option ->
                                item {
                                    RadioButtonWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(option.second),
                                        selected = labelModeInput == option.first,
                                        onClick = {
                                            labelModeInput = option.first
                                            labelModeKey = option.first.storageKey
                                        },
                                    )
                                }
                            }
                        }

                        SegmentedColumn(contentPadding = zeroPadding, modifier = Modifier.padding(top = 16.dp)) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_page_management),
                                    description = stringResource(R.string.nav_page_management_summary),
                                    onClick = { showTabManagementDialog(context) },
                                    trailingContent = {
                                        Icon(
                                            MaterialSymbols.Outlined.Chevron_right,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_page_animation),
                                    description = stringResource(R.string.nav_page_animation_summary),
                                    checked = animatePageChangeInput,
                                    onCheckedChange = {
                                        animatePageChangeInput = it
                                        animatePageChange = it
                                    },
                                )
                            }
                            item(animatedVisibility = floatingInput) {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_auto_hide_bar),
                                    description = stringResource(R.string.nav_auto_hide_bar_summary),
                                    checked = autoHideOnScrollInput,
                                    onCheckedChange = {
                                        autoHideOnScrollInput = it
                                        autoHideOnScroll = it
                                        if (!it) barScrollHiddenState.value = false
                                    },
                                )
                            }
                            item(animatedVisibility = glassInput) {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_dynamic_gravity_highlight),
                                    description = stringResource(R.string.nav_dynamic_gravity_highlight_summary),
                                    checked = dynamicGravityHighlightInput,
                                    onCheckedChange = {
                                        dynamicGravityHighlightInput = it
                                        dynamicGravityHighlight = it
                                    },
                                )
                            }
                            item(animatedVisibility = glassInput) {
                                BaseItemContainer {
                                    val radius = blurRadiusInput.roundToInt()
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.nav_blur_radius),
                                        value = radius,
                                        startInt = MIN_BLUR_RADIUS,
                                        endInt = MAX_BLUR_RADIUS,
                                        stepSize = 1,
                                        valueSuffix = "px",
                                        onValueChange = {
                                            blurRadiusInput = it.toFloat()
                                            blurRadius = it
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.nav_bar_scale),
                                        value = barScaleInput.roundToInt(),
                                        startInt = MIN_BAR_SCALE,
                                        endInt = MAX_BAR_SCALE,
                                        stepSize = BAR_SCALE_STEP,
                                        valueSuffix = "%",
                                        onValueChange = {
                                            barScaleInput = it.toFloat()
                                            barScalePercent = it
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun showTabManagementDialog(context: ComponentActivity) {
        showComposeDialog(context) {
            val currentOrder = remember { normalizedTabOrder().toMutableStateList() }
            val currentEnabled = remember {
                normalizedEnabledTabIndices().toMutableStateList()
            }
            // 0924：每个页面独立的角标开关
            val currentBadges = remember {
                normalizedEnabledTabIndices(badgeTabs).toMutableStateList()
            }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(stringResource(R.string.nav_page_management)) },
                text = {
                    DefaultColumn {
                        Column {
                            Text(stringResource(R.string.nav_display_and_order), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.nav_reorder_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ReorderableList(
                            items = currentOrder,
                            itemKey = NavItem::wechatIndex,
                            onMove = { from, to ->
                                currentOrder.add(to, currentOrder.removeAt(from))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) { item, dragHandleModifier ->
                            val label = stringResource(item.labelRes)
                            val checked = item.wechatIndex in currentEnabled
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .then(dragHandleModifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Drag_handle,
                                        contentDescription = stringResource(R.string.nav_drag_tab_description, label),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    imageVector = item.outlined,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = label,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Switch(
                                    checked = checked,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            if (item.wechatIndex !in currentEnabled) {
                                                currentEnabled += item.wechatIndex
                                            }
                                        } else {
                                            currentEnabled.remove(item.wechatIndex)
                                        }
                                    },
                                )
                                Text(
                                    text = stringResource(R.string.nav_badge_toggle_description, ""),
                                    modifier = Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Switch(
                                    checked = item.wechatIndex in currentBadges,
                                    onCheckedChange = { badged ->
                                        if (badged) {
                                            if (item.wechatIndex !in currentBadges) {
                                                currentBadges += item.wechatIndex
                                            }
                                        } else {
                                            currentBadges.remove(item.wechatIndex)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        tabOrder = currentOrder.joinToString(",") { it.wechatIndex.toString() }
                        enabledTabs = currentEnabled.map(Int::toString).toSet()
                        badgeTabIndices = currentBadges.map(Int::toString).toSet()
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
            )
        }
    }

    private val methodUpdateTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("MicroMsg.LauncherUITabView", "updateMainTabUnread %d")
        }
    }

    private val methodChangeTab by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.MainTabUI"
            usingEqStrings(
                "change tab to %d, cur tab %d, has init tab %B, tab cache size %d"
            )
        }
    }

    private val methodUpdateFriendTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[updateFriendTabUnread] unread : ")
        }
    }

    private val methodShowFriendPoint by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[showFriendPoint] show : ")
        }
    }

    private val methodUpdateContactTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[updateContactTabUnread] unread : ")
        }
    }
}
