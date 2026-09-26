package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import com.tencent.mm.ui.base.CustomViewPager
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Swap_vert
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.monet.MonetColors
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.ref.WeakReference
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.lang.reflect.Modifier as ReflectModifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object ConversationGrouping : ClickableFeature(), IResolveDex {

    override val technicalId = "对话分组"
    override val nameRes = R.string.feature_conversation_grouping_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_conversation_grouping_description

    const val GROUP_PREFIX = "wekit_group_"

    // The fixed "全部" tab. It behaves like a group for ordering purposes — it can be dragged to any
    // position and that position is persisted alongside the real groups — but it can never be
    // edited or deleted, and selecting it applies no filter (null predicate). It's stored as an
    // ordinary ChatGroup entry (identified solely by this id) so the list order is enough to
    // remember where it sits.
    private const val ALL_TAB_ID = "${GROUP_PREFIX}all"

    private enum class GroupingBackend(val value: String) {
        ADAPTER_FILTER("adapter_filter"),
        QUERY_REWRITE("query_rewrite");

        companion object {
            fun from(value: String): GroupingBackend =
                entries.firstOrNull { it.value == value } ?: ADAPTER_FILTER
        }
    }

    private var groupingBackendValue by WePrefs.prefOption(
        "conversation_grouping_backend",
        GroupingBackend.ADAPTER_FILTER.value,
    )

    private val groupingBackend: GroupingBackend
        get() = GroupingBackend.from(groupingBackendValue)

    /**
     * Tab bar layout style. Mirrors upstream `conversation_grouping_tab_style` (Int).
     * 0 = floating island (浮动岛), 1 = full-width bar (全宽度标签栏).
     */
    private enum class TabStyle(val value: Int) {
        FLOATING(0),
        FULL_WIDTH(1);

        companion object {
            fun from(value: Int): TabStyle = entries.firstOrNull { it.value == value } ?: FLOATING
        }
    }

    private var tabStyleValue by WePrefs.prefOption("conversation_grouping_tab_style", TabStyle.FLOATING.value)
    private val tabStyleState by lazy { mutableStateOf(TabStyle.from(tabStyleValue)) }

    private var pinTabsValue by WePrefs.prefOption("conversation_grouping_pin_tabs", true)
    private val pinTabsState by lazy { mutableStateOf(pinTabsValue) }

    private var tabShowUnreadValue by WePrefs.prefOption("conversation_grouping_show_unread", true)
    private val tabShowUnreadState by lazy { mutableStateOf(tabShowUnreadValue) }

    private var tabIncludeOfficialUnreadValue by
        WePrefs.prefOption("conversation_grouping_include_official_unread", false)
    private val tabIncludeOfficialUnreadState by lazy { mutableStateOf(tabIncludeOfficialUnreadValue) }

    private var tabRememberScrollValue by WePrefs.prefOption("conversation_grouping_remember_scroll_state", false)
    private val tabRememberScrollState by lazy { mutableStateOf(tabRememberScrollValue) }

    private var tabTakeOverSwipeValue by
        WePrefs.prefOption("conversation_grouping_take_over_horizontal_scroll", true)
    private val tabTakeOverSwipeState by lazy { mutableStateOf(tabTakeOverSwipeValue) }
    // ----------------------------------------------------------------------------------------------
    // Horizontal swipe takeover (接管横向滑动)
    // ----------------------------------------------------------------------------------------------
    private const val SWIPE_TAKEOVER_TAG = "ConversationGrouping.Swipe"
    private const val SWIPE_TOUCH_SLOP_DP = 24f

    private val customViewPagerDispatchTouch by lazy {
        CustomViewPager::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
    }

    // Shared with the injected tab bar so a swipe can move the visible selection too.
    @Volatile
    private var selectedGroupIdState: MutableState<String>? = null
    @Volatile
    private var groupsState: MutableState<List<ChatGroup>>? = null

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeConsumed = false

    private fun switchToAdjacentGroup(direction: Int): Boolean {
        val groups = loadGroups()
        if (groups.size <= 1) return false
        val ids = groups.map { it.id }
        val currentId = selectedGroupIdState?.value ?: activeAdapterGroup.id
        val current = ids.indexOf(currentId).takeIf { it >= 0 } ?: 0
        val target = current + direction
        // At the edges we deliberately hand the gesture back to WeChat (page / side panel).
        if (target !in ids.indices) return false
        val nextId = ids[target]
        selectedGroupIdState?.value = nextId
        selectTab(nextId)
        return true
    }

    // ----------------------------------------------------------------------------------------------
    // Scroll memory (记忆滑动状态)
    // ----------------------------------------------------------------------------------------------
    private val scrollPositions = ConcurrentHashMap<String, Int>()
    @Volatile
    private var lastConversationRecycler = WeakReference<View>(null)

    private fun recyclerViewFirstVisible(view: View): Int? = runCatching {
        val lm = view.javaClass.getMethod("getLayoutManager").invoke(view) ?: return null
        lm.javaClass.getMethod("findFirstVisibleItemPosition").invoke(lm) as Int
    }.getOrNull()

    private fun recyclerViewScrollTo(view: View, position: Int) {
        runCatching {
            val lm = view.javaClass.getMethod("getLayoutManager").invoke(view) ?: return
            lm.javaClass.getMethod("scrollToPosition", Int::class.javaPrimitiveType).invoke(lm, position)
        }.onFailure { WeLogger.w(TAG, "restore scroll position failed", it) }
    }

    // ----------------------------------------------------------------------------------------------
    // Pinned tabs (固定分组标签): keep the tab row glued under the title bar while scrolling.
    // ----------------------------------------------------------------------------------------------
    @Volatile
    private var tabsHeaderView = WeakReference<View>(null)

    /**
     * 依次对顶栏的所有祖先容器打开/恢复「不拦截触摸」。
     *
     * 下拉小程序面板由哪一层容器响应随宿主版本变化（ViewPager / TaskBar 容器），所以往上一直
     * 遍历到根；只影响当前这一次触摸，不改变任何分组/排序/过滤逻辑。
     */
    private fun disallowParentIntercept(view: View, disallow: Boolean) {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            runCatching { parent.requestDisallowInterceptTouchEvent(disallow) }
            parent = parent.parent
        }
    }

    /**
     * 触摸期间阻止宿主的祖先容器（RecyclerView / ViewPager / 下拉面板容器）拦截事件。
     *
     * 为什么必须放在 Compose 内部：ComposeView 是 ViewGroup，事件分发先把 DOWN 交给子 View
     * （AndroidComposeView），Compose 的 clickable 会在那里成为 touch target 并消费 DOWN；
     * 一旦存在 touch target，ViewGroup 自身就再也不会走 `View.dispatchTouchEvent` 里的
     * OnTouchListener 分支（OnTouchListener 只在 mFirstTouchTarget == null 时被回调）
     * ——给 ComposeView 挂 OnTouchListener 是**无效**的（它永远收不到回调）。
     *
     * 所以这里用 pointerInput 在 [PointerEventPass.Initial] 阶段（早于所有子节点的手势识别器）
     * 监听：按下瞬间对全部祖先调 [disallowParentIntercept]（true），抬手/取消恢复 false；
     * 一旦判定为「明确的横向滑动」（分组横向切换手势）也立即恢复 false，把事件让回原有的
     * 横向滑动接管逻辑，避免分组横滑失效。
     *
     * 2026-09-22 真机补充：只关掉「父容器拦截」还不够 —— 微信的下拉小程序面板有一部分是走
     * **嵌套滚动**（列表在顶部继续往下拖时把滚动交给父容器，日志里的 TaskBarAnimController
     * updateScrollOffset 就是那套），而嵌套滚动不经过 onInterceptTouchEvent，[disallowParentIntercept]
     * 对它无效。所以在 [PointerEventPass.Initial] 阶段把**纵向拖动**直接消费掉（不消费横向），
     * 祖先既拿不到 MOVE 也就不会把它当成一次下拉手势；tab 点击（DOWN+UP）与横向横滑均不受影响。
     * 排序模式（长按拖拽）本身就是纵向手势，此时关闭消费以免拖拽失效。
     */
    private fun Modifier.guardAgainstParentIntercept(
        view: View,
        slopPx: Float,
        consumeVertical: Boolean = true,
    ): Modifier =
        pointerInput(view, slopPx, consumeVertical) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    disallowParentIntercept(view, true)

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // 抬手 / 取消：本次手势结束，恢复拦截并回到外层等下一次按下。
                        if (event.changes.all { !it.pressed }) break

                        if (event.type == PointerEventType.Move) {
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            if (change != null) {
                                val dx = abs(change.position.x - down.position.x)
                                val dy = abs(change.position.y - down.position.y)
                                if (dx > dy && dx > slopPx) break
                                if (consumeVertical && dy > dx && dy > slopPx) {
                                    change.consume()
                                }
                            }
                        }
                    }

                    disallowParentIntercept(view, false)
                }
            }
        }

    private fun applyTabsPin(recycler: View?) {
        val header = tabsHeaderView.get() ?: return
        if (!pinTabsState.value) {
            header.translationY = 0f
            header.translationZ = 0f
            return
        }
        if (recycler == null) return
        // The header is the first child of the list; when it scrolls past the top edge,
        // translate it back down so it visually sticks under the title bar.
        val top = header.top
        val pinned = top < 0
        header.translationY = if (pinned) (-top).toFloat() else 0f
        // 只改 translationY 会出现「看得见、点不到 / 点到别的行」的错位：ViewGroup 的触摸派发按
        // 子 View 的 Z 排序（先派发给 Z 更大的子 View），而列表项默认 Z=0 且索引在 header 之后，
        // 于是被 header 盖住的那条会话会抢走落在分组按钮上（尤其按钮偏上位置）的 DOWN，事件随即
        // 由列表/下拉面板处理 → 表现为「点分组按钮却把小程序面板拉下来」。
        // 固定住的时候把 header 的 translationZ 抬到 1px，让它同时在上层绘制与上层命中测试。
        header.translationZ = if (pinned) 1f else 0f
    }

    private fun hookScrollMemory() {
        if (WeConversationListViewApi.methodRecyclerOnScrolled.isPlaceholder) return
        WeConversationListViewApi.methodRecyclerOnScrolled.hookAfter {
            val recycler = args.getOrNull(0) as? View ?: return@hookAfter
            lastConversationRecycler = WeakReference(recycler)
            applyTabsPin(recycler)
            if (!tabRememberScrollValue) return@hookAfter
            recyclerViewFirstVisible(recycler)?.let { scrollPositions[activeAdapterGroup.id] = it }
        }
    }

    private fun restoreScrollPosition(groupId: String?) {
        if (!tabRememberScrollValue) return
        val target = scrollPositions[groupId ?: ALL_TAB_ID] ?: return
        if (target <= 0) return
        Handler(Looper.getMainLooper()).postDelayed({
            val recycler = lastConversationRecycler.get() ?: return@postDelayed
            // 切换分组时的「向上跳」会被宿主的下拉小程序面板误判：TaskBarAnimController 跟随
            // 会话列表的滚动偏移，列表在顶部附近发生一次性大幅反向滚动就会被当成下拉手势，
            // 面板随即跟着拉下来（真机日志里 updateScrollOffset 就是这么被喂出来的）。
            // 只有当目标位置确实在当前可视位置之下时才滚动，等价于原来的「恢复上次位置」，
            // 但不会因为列表本来就在顶部而制造一次虚假的向上滚动。
            val current = recyclerViewFirstVisible(recycler)
            if (current != null && current <= target) return@postDelayed
            recyclerViewScrollTo(recycler, target)
        }, 120)
    }

    private fun hookHorizontalSwipeTakeover() {
        customViewPagerDispatchTouch.hookBeforeDirectly {
            if (!tabTakeOverSwipeState.value) return@hookBeforeDirectly
            val event = args.getOrNull(0) as? MotionEvent ?: return@hookBeforeDirectly
            val view = thisObject as? View ?: return@hookBeforeDirectly
            val slop = SWIPE_TOUCH_SLOP_DP * view.resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x
                    swipeStartY = event.y
                    swipeConsumed = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (swipeConsumed) {
                        result = true
                        return@hookBeforeDirectly
                    }
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        swipeConsumed = true
                        if (switchToAdjacentGroup(if (dx < 0) 1 else -1)) {
                            result = true
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (swipeConsumed) result = true
                    swipeConsumed = false
                }
            }
        }
    }


    private val groupTabHorizontalPadding = 16.dp

    private fun isAllTab(id: String?): Boolean = id == ALL_TAB_ID

    private fun allTab(): ChatGroup = ChatGroup(id = ALL_TAB_ID)

    // The SQL predicate for the currently selected tab, injected into WeChat's homepage
    // conversation-list query. null = "全部" (no filtering). We resolve the predicate once, when the
    // tab is tapped (on the main thread), so the query hook itself never runs nested DB reads while
    // WeChat is mid-query. Switching tabs then just asks WeChat to reload the cursor.
    @Volatile
    private var activePredicate: String? = null

    @Volatile
    private var activeAdapterGroup: ChatGroup = allTab()

    @Volatile
    private var activeAdapterMembers: Set<String> = emptySet()

    private data class AdapterCache(
        val visiblePositions: List<Int>,
        val rawToVisible: IntArray,
    )
    private data class AdapterMethods(
        val getCount: Method,
        val getItem: Method,
        val getView: Method,
        /**
         * `getItemViewType(显示下标)`。
         *
         * 过滤之后「显示下标 -> 原始下标」的映射必须**成套**覆盖宿主读下标的每一个入口
         * （getCount / getItem / getItemViewType / getView）。少任何一个都会错位：宿主
         * `RecyclerView.createViewHolder` 拿到的 viewType 来自未映射的
         * `getItemViewType(显示下标)`，而 item 来自已映射的 `getItem(显示下标)` ——
         * 两者不是同一行，宿主按 viewType 造出来的 holder 再去 `findViewById` 就是 null：
         *   NullPointerException: Attempt to invoke virtual method
         *   'int android.view.View.getId()' on a null object reference
         *     at uk5.n0.onCreateViewHolder(SourceFile:326)
         *     at com.tencent.mm.pluginsdk.ui.tools.q3.onCreateViewHolder(SourceFile:9)
         *（实机 wekit-crash-2026-09-26_13-27-42 / 13-44-08，crashActivity=com.tencent.mm.ui.LauncherUI，
         *  viewType=1070927991 / -1349806852 就是原始行的类型值）。
         * 拿不到这个方法时退化成 null（不映射）—— 比接错安全，宿主最多多造几个 holder。
         */
        val getItemViewType: Method?,
        val storage: AdapterStorage,
    )
    private data class AdapterItemFields(
        val username: Field?,
        val unreadCounts: List<Field>,
    )

    private val adapterCaches = WeakHashMap<Any, AdapterCache>()
    private var adapterMethods: List<AdapterMethods> = emptyList()
    private val adapterSnapshotReader = ConversationAdapterSnapshotReader()
    private val adapterItemFields = ConcurrentHashMap<Class<*>, AdapterItemFields>()
    private val snapshotFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()
    private val bindingAdapter = ThreadLocal<Any?>()

    /**
     * 正在构建 adapter 快照的线程标记。
     *
     * ADAPTER_FILTER 会把 `getItem` 的入参从「显示下标」映射成「原始下标」（见
     * [hookConversationListAdapter] 里的 getItem hook），而快照构建本身就是用原始下标
     * 直接读 item 的 —— 必须屏蔽掉这层映射，否则会把数据读串。
     *
     * 用 [ThreadLocal]（而不是普通 Boolean）：宿主的 getCount / getItem 不一定只在主线程调用，
     * 标记跨线程共享会让另一条线程的下标映射被误跳过。
     */
    private val buildingAdapterCache = ThreadLocal<Boolean>()

    /**
     * 「正在读宿主 adapter 的真实条目数」标记。
     *
     * [rawAdapterCount] 会直接 invoke 宿主被 hook 的 `getCount()`；没有这层标记时它会再次
     * 走 [hookConversationListAdapter] 里的过滤分支，读回过滤后的数字 —— 那正是我们要避开的。
     */
    private val suppressCountFilter = ThreadLocal<Boolean>()
    private val recyclerLists = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>()),
    )
    private val adapterPositionProvider = WeConversationListViewApi.IAdapterPositionProvider { adapter, rawPosition ->
        adapterPositionSnapshot(adapter, rawPosition)
    }

    private val groupsFile by lazy { KnownPaths.moduleData / "conversation_groups.json" }

    @Volatile
    private var groupsCache: List<ChatGroup>? = null

    private val groupMembersCache = ConcurrentHashMap<String, List<String>>()

    override fun onEnable() {
        if (groupingBackend == GroupingBackend.QUERY_REWRITE) {
            hookConversationListQuery()
        } else {
            hookConversationListAdapter()
        }

        methodOnTabCreate.hookAfter {
            val mainUi = thisObject!!
            val conversationHostView = WeConversationListViewApi.hostView(mainUi)

            val composeView = ComposeView(conversationHostView.context).apply {
                val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
                setLifecycleOwner(lifecycleOwner)

                // 注意：**不要**在这里给 ComposeView 挂 View 层的 OnTouchListener。
                // ComposeView 是 ViewGroup，事件分发会先把 DOWN 交给子 View（AndroidComposeView），
                // Compose 的 clickable 在那里成为 touch target 并消费 DOWN；只要存在 touch target，
                // ViewGroup 自身就再也不会走 View.dispatchTouchEvent 里的 OnTouchListener 分支
                // （OnTouchListener 只在 mFirstTouchTarget == null 时被回调）——监听器永远不会触发，
                // 父容器照常拦截 MOVE，下拉小程序面板照旧，等于没改。
                // 正确做法：把防御放进 Compose 内部，见 ConversationTabs 根 Box 上的
                // Modifier.guardAgainstParentIntercept（PointerEventPass.Initial 阶段）。

                val context = conversationHostView.context

                // These values get lost when ComposeView becomes invisible, so we have to lift them
                // out of the Composable.
                val selectedGroupIdState = mutableStateOf(ALL_TAB_ID)
                val groupsState = mutableStateOf(loadGroups())
                // Expose to the swipe hook so a horizontal gesture can drive the same selection.
                this@ConversationGrouping.selectedGroupIdState = selectedGroupIdState
                this@ConversationGrouping.groupsState = groupsState
                setContent {
                    MonetInjectedTheme {
                        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
                        var selectedGroupId by selectedGroupIdState
                        var groups by groupsState

                        ConversationTabs(
                            groups = groups,
                            selectedGroupId = selectedGroupId,
                            onTabSelected = { groupId ->
                                selectedGroupId = groupId
                                selectTab(groupId)
                            },
                            onCreateGroup = {
                                showCreateGroupDialog(context) {
                                    groups = loadGroups()
                                }
                            },
                            onEditGroup = { group ->
                                showEditGroupDialog(
                                    context = context,
                                    group = group,
                                    onGroupUpdated = {
                                        groups = loadGroups()
                                        // Recompute the filter if the edited group is the active one.
                                        if (selectedGroupId == group.id) selectTab(group.id)
                                    },
                                    onGroupDeleted = {
                                        groups = loadGroups()
                                        if (selectedGroupId == group.id) {
                                            selectedGroupId = ALL_TAB_ID
                                            selectTab(ALL_TAB_ID)
                                        }
                                    }
                                )
                            },
                            onDeleteGroup = { group ->
                                showConfirmDeleteGroupDialog(context, group) {
                                    saveGroups(loadGroups().filterNot { it.id == group.id })
                                    groups = loadGroups()
                                    if (selectedGroupId == group.id) {
                                        selectedGroupId = ALL_TAB_ID
                                        selectTab(ALL_TAB_ID)
                                    }
                                    showToast(
                                        localizedContext.getString(
                                            R.string.conversation_group_deleted,
                                            localizedGroupName(localizedContext, group),
                                        )
                                    )
                                }
                            },
                            onReorder = { orderedIds ->
                                val current = loadGroups()
                                val byId = current.associateBy { it.id }
                                val reordered = orderedIds.mapNotNull { byId[it] }
                                // Keep any groups that somehow weren't in the ordered list appended.
                                val missing = current.filterNot { g -> orderedIds.contains(g.id) }
                                saveGroups(reordered + missing)
                                groups = loadGroups()
                            }
                        )
                    }
                }
            }
            WeConversationListViewApi.addHeaderView(mainUi, composeView)
            tabsHeaderView = WeakReference(composeView)
        }
        if (groupingBackend == GroupingBackend.ADAPTER_FILTER) {
            WeConversationListViewApi.addPositionProvider(adapterPositionProvider)
        }
        hookHorizontalSwipeTakeover()
        hookScrollMemory()
    }

    override fun onDisable() {
        WeConversationListViewApi.removePositionProvider(adapterPositionProvider)
        bindingAdapter.remove()
        synchronized(recyclerLists) { recyclerLists.clear() }
        clearAdapterCaches()
        snapshotFailuresLogged.clear()
        // BaseFeature.disable() 的顺序是 unhookAll() → onDisable()，到这里 hook 已经摘掉：
        // getCount() 立刻回到原始值，而宿主的 mItemCount 还停在过滤后的数字上。补一次同步
        // 通知把两者对齐，否则摘掉 hook 之后第一次触摸/布局就会抛 ISE。
        onMainTurn { WeConversationListViewApi.refreshNow() }
    }

    /**
     * 在**同一个主线程 turn** 内执行 [block]（已处于主线程时同步执行，否则 post 一次）。
     *
     * 为什么必须是这样：宿主首页会话列表是 AbsListView 子类，它只在
     * `setAdapter` / `notifyDataSetChanged()` 时刷新自己的 mItemCount，之后每次触摸、
     * 滚动、布局都会校验 `mItemCount == adapter.getCount()`，不等就抛
     *
     *     IllegalStateException: The content of the adapter has changed but ListView did not
     *     receive a notification.（wekit-crash-2026-09-26_16-02-11-423）
     *
     * 而我们的分组过滤正是改写 `getCount()` 的返回值，所以「换过滤结果」和「通知宿主」
     * 必须原子地落在同一个 turn 里。
     */
    private inline fun onMainTurn(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post { block() }
        }
    }

    private fun hookConversationListAdapter() {
        val viewHooks = listOf(
            WeConversationListViewApi.methodLegacyGetView to AdapterStorage.LEGACY_CURSOR,
            WeConversationListViewApi.methodMvvmGetView to AdapterStorage.MVVM_LIST,
        ).filterNot { (delegate, _) -> delegate.isPlaceholder }
        if (viewHooks.isEmpty()) {
            error("conversation adapter filter targets were not resolved")
        }
        adapterMethods = viewHooks.map { (delegate, storage) ->
            val getView = delegate.method
            val owner = getView.declaringClass.reflekt()
            AdapterMethods(
                getCount = owner.firstMethod {
                    name = "getCount"
                    parameters()
                    returnType = Int::class.java
                    superclass()
                }.self,
                getItem = owner.firstMethod {
                    name = "getItem"
                    parameters(Int::class.java)
                    returnType = Any::class.java
                    superclass()
                }.self,
                getView = getView,
                getItemViewType = runCatching {
                    owner.firstMethod {
                        name = "getItemViewType"
                        parameters(Int::class.java)
                        returnType = Int::class.java
                        superclass()
                    }.self
                }.getOrNull(),
                storage = storage,
            )
        }
        adapterMethods.forEach { methods ->
            methods.getCount.hookAfter {
                if (suppressCountFilter.get() == true) return@hookAfter
                if (groupingBackend != GroupingBackend.ADAPTER_FILTER) return@hookAfter
                if (isAllTab(activeAdapterGroup.id)) return@hookAfter
                val adapter = thisObject!!
                // The inherited count method is also called by unrelated adapters.
                if (!methods.getView.declaringClass.isInstance(adapter)) return@hookAfter
                // **只读缓存，绝不在这里惰性重建。**
                //
                // 重建会让 `getCount()` 的返回值在同一帧里「悄悄」变成另一个数字，而宿主
                // ListView 只在 setAdapter/onChanged 时更新自己的 mItemCount —— 两者一旦
                // 脱节，滑动/触摸首页就会抛
                //   IllegalStateException: The content of the adapter has changed but ListView
                //   did not receive a notification.（wekit-crash-2026-09-26_16-02-11-423）
                //
                // 缓存由 primeAdapterCaches() 在「同一个主线程 turn 内、notify 之前」建好；
                // 缓存为空 = 这个实例不参与过滤，返回原始 count 反而与宿主 mItemCount 一致。
                val cache = synchronized(adapterCaches) { adapterCaches[adapter] } ?: return@hookAfter
                result = cache.visiblePositions.size
            }
            methods.getView.hookBefore(priority = 100) {
                if (groupingBackend != GroupingBackend.ADAPTER_FILTER) return@hookBefore
                if (isAllTab(activeAdapterGroup.id)) return@hookBefore
                val adapter = thisObject!!
                val position = args[0] as Int
                val cache = synchronized(adapterCaches) { adapterCaches[adapter] } ?: return@hookBefore
                bindingAdapter.set(adapter)
                if (position in cache.visiblePositions.indices) {
                    args[0] = cache.visiblePositions[position]
                }
            }
            methods.getView.hookAfter(priority = 100) {
                if (bindingAdapter.get() === thisObject) bindingAdapter.remove()
            }
            /*
             * 过滤只改了 getCount/getView，没改 getItem 的话，列表的「显示下标」与
             * adapter 的「原始下标」就会错位：微信点会话是 `ConversationClickListener` →
             * `MvvmConversationAdapter.onClickItem(position)` 按下标反查会话的（用户日志里
             * 就是 `[onItemClick] position:5 [x@chatroom,x@chatroom]`），于是**点任意一行
             * 都会打开另一个会话** —— 用户反馈的「打开任意一个会话都是某个会话」。
             *
             * 所以显示下标必须在这里也映射成原始下标：
             *  - getView 内部（bindRows）自己会再调 getItem，那时参数已经是原始下标
             *    （bindingAdapter 标记），不能再映射；
             *  - 构建快照时用的是原始下标（buildingAdapterCache 标记），同样不能映射。
             */
            methods.getItem.hookBefore(priority = 100) {
                if (groupingBackend != GroupingBackend.ADAPTER_FILTER) return@hookBefore
                if (isAllTab(activeAdapterGroup.id)) return@hookBefore
                val adapter = thisObject!!
                if (!methods.getView.declaringClass.isInstance(adapter)) return@hookBefore
                if (bindingAdapter.get() === adapter) return@hookBefore
                if (buildingAdapterCache.get() == true) return@hookBefore
                val cache = synchronized(adapterCaches) { adapterCaches[adapter] } ?: return@hookBefore
                val position = args[0] as Int
                if (position in cache.visiblePositions.indices) {
                    args[0] = cache.visiblePositions[position]
                }
            }
            // getItemViewType 与 getItem / getView 必须用**同一套映射**（同样的守卫、
            // 同样的可见下标表）。宿主是拿 viewType 去选 holder 的布局、拿 item 去填内容的，
            // 少映射一个就等于「按 A 行的类型去装 B 行的内容」，实机表现就是建 holder 时
            // NPE 崩进程（wekit-crash-2026-09-26_13-27-42 / 13-44-08）。
            methods.getItemViewType?.hookBefore(priority = 100) {
                if (groupingBackend != GroupingBackend.ADAPTER_FILTER) return@hookBefore
                if (isAllTab(activeAdapterGroup.id)) return@hookBefore
                val adapter = thisObject!!
                if (!methods.getView.declaringClass.isInstance(adapter)) return@hookBefore
                if (bindingAdapter.get() === adapter) return@hookBefore
                if (buildingAdapterCache.get() == true) return@hookBefore
                val cache = synchronized(adapterCaches) { adapterCaches[adapter] } ?: return@hookBefore
                val position = args[0] as Int
                if (position in cache.visiblePositions.indices) {
                    args[0] = cache.visiblePositions[position]
                }
            }
        }

        if (!WeConversationListViewApi.classConversationRecyclerAdapter.isPlaceholder) {
            hookRecyclerDataSource()
        }
    }

    private fun hookRecyclerDataSource() {
        methodRecyclerQueryPage.hookAfter {
            if (!classRecyclerDataSource.clazz.isInstance(thisObject)) return@hookAfter
            val page = result!!
            @Suppress("UNCHECKED_CAST")
            val rows = fieldRecyclerPageItems.field.get(page) as MutableList<Any>
            filterRecyclerRows(rows)
        }

        WeConversationListViewApi.classConversationRecyclerAdapter.clazz.constructors.forEach { constructor ->
            constructor.hookAfter {
                captureRecyclerList(thisObject!!)
            }
        }
        WeConversationListViewApi.currentAdapter()?.let { adapter ->
            if (WeConversationListViewApi.classConversationRecyclerAdapter.clazz.isInstance(adapter)) {
                captureRecyclerList(adapter)
            }
        }

        methodRecyclerSubmitUiChange.hookBefore {
            if (!recyclerLists.contains(thisObject)) return@hookBefore
            val pendingData = args[0]!!
            @Suppress("UNCHECKED_CAST")
            val rows = fieldRecyclerPendingItems.field.get(pendingData) as MutableList<Any>
            filterRecyclerRows(rows)
        }
    }

    private fun captureRecyclerList(adapter: Any) {
        recyclerLists.add(fieldRecyclerMvvmList.field.get(adapter)!!)
    }

    private fun filterRecyclerRows(rows: MutableList<Any>) {
        if (groupingBackend != GroupingBackend.ADAPTER_FILTER) return
        val group = activeAdapterGroup
        if (isAllTab(group.id)) return
        rows.removeAll { row ->
            classRecyclerRow.clazz.isInstance(row) &&
                !adapterItemMatches(fieldRecyclerRowConversation.field.get(row), group)
        }
    }

    private fun refreshRecyclerData(): Boolean {
        val lists = synchronized(recyclerLists) { recyclerLists.toList() }
        if (lists.isEmpty()) return false
        for (list in lists) {
            methodRecyclerRefreshAll.method.invoke(null, list, null, 1, null)
        }
        return true
    }

    private fun rebuildAdapterCache(adapter: Any, rawCount: Int): AdapterCache? {
        synchronized(adapterCaches) {
            // Never let a failed refresh leave an index built for an older backing dataset.
            adapterCaches.remove(adapter)
            val group = activeAdapterGroup
            val methods = adapterMethods(adapter)
            val items: List<Any?>? = runCatching {
                when (methods.storage) {
                    AdapterStorage.MVVM_LIST -> adapterSnapshotReader.read(adapter, rawCount) { index ->
                        readRawItem(adapter, methods, index)
                    }
                    AdapterStorage.LEGACY_CURSOR -> object : AbstractList<Any?>() {
                        override val size: Int get() = rawCount
                        override fun get(index: Int): Any? = readRawItem(adapter, methods, index)
                    }
                }
            }.getOrElse { error ->
                if (snapshotFailuresLogged.add(adapter.javaClass)) {
                    WeLogger.e(TAG, "adapter filter snapshot probe failed for ${adapter.javaClass.name}", error)
                }
                return null
            }
            if (items == null) {
                if (snapshotFailuresLogged.add(adapter.javaClass)) {
                    WeLogger.e(
                        TAG,
                        "adapter filter backing list unresolved for ${adapter.javaClass.name}; leaving it unfiltered",
                    )
                }
                adapterCaches.remove(adapter)
                return null
            }
            val visible = runCatching {
                items.mapIndexedNotNull { index, item ->
                    if (adapterItemMatches(item, group)) index else null
                }
            }.getOrElse { error ->
                if (snapshotFailuresLogged.add(adapter.javaClass)) {
                    WeLogger.e(TAG, "adapter filter snapshot failed for ${adapter.javaClass.name}", error)
                }
                adapterCaches.remove(adapter)
                return null
            }
            val rawToVisible = IntArray(rawCount) { -1 }
            visible.forEachIndexed { visiblePosition, rawPosition ->
                if (rawPosition in rawToVisible.indices) rawToVisible[rawPosition] = visiblePosition
            }
            return AdapterCache(visible, rawToVisible).also { adapterCaches[adapter] = it }
        }
    }

    private fun readRawItem(adapter: Any, methods: AdapterMethods, index: Int): Any? {
        val previous = buildingAdapterCache.get()
        buildingAdapterCache.set(true)
        return try {
            methods.getItem.invoke(adapter, index)
        } finally {
            buildingAdapterCache.set(previous)
        }
    }

    private fun adapterPositionSnapshot(
        adapter: Any,
        currentRawPosition: Int,
    ): WeConversationListViewApi.AdapterPositionSnapshot? = synchronized(adapterCaches) {
        val cache = adapterCaches[adapter] ?: return@synchronized null
        val visiblePosition = cache.rawToVisible.getOrNull(currentRawPosition) ?: return@synchronized null
        if (visiblePosition < 0) return@synchronized null
        WeConversationListViewApi.AdapterPositionSnapshot(
            visiblePosition = visiblePosition,
            itemCount = cache.visiblePositions.size,
            currentRawPosition = currentRawPosition,
            previousRawPosition = cache.visiblePositions.getOrNull(visiblePosition - 1),
            nextRawPosition = cache.visiblePositions.getOrNull(visiblePosition + 1),
        )
    }

    private fun clearAdapterCaches() {
        synchronized(adapterCaches) { adapterCaches.clear() }
    }

    /**
     * 首页会话列表的 count 稳定性规则 —— 「滑动首页/切换分组偶发闪退」的根治办法。
     *
     * Android 的 AbsListView 只在 `setAdapter` / `AdapterDataSetObserver.onChanged()` 时更新
     * 自己的 `mItemCount`，之后在触摸、滚动、布局路径里都会做
     * `mItemCount != mAdapter.getCount()` 的校验，不等就直接抛
     *
     *     IllegalStateException: The content of the adapter has changed but ListView did not
     *     receive a notification. [in ListView(-1, class o95.y3) with
     *     Adapter(class com.tencent.mm.ui.ng)]
     *
     * （用户日志 wekit-crash-2026-09-26_16-02-11-423 里的真实崩溃。）
     *
     * 我们过滤首页列表靠的是改写 `getCount()` 的返回值，所以必须保证：
     * **getCount 的返回值只在「与一次 notifyDataSetChanged 同一个主线程 turn」里变化。**
     * 落成两条纪律：
     *  1. `getCount` 的 hook 只读缓存、绝不自己重建；
     *  2. 改变过滤状态的唯一入口是 [selectTab] → 本函数（同步把缓存换好）
     *     → [refreshConversations]（同 turn 通知宿主）。
     *
     * 这样「缓存为空」就等价于「这个 adapter 实例不参与过滤」，它返回的原始 count 与宿主
     * mItemCount 天然一致 —— 宁可这次不过滤，也绝不让两者脱节。
     */
    private fun primeAdapterCaches() {
        val adapter = WeConversationListViewApi.currentAdapter() ?: return
        val methods = adapterMethods.firstOrNull { it.getView.declaringClass.isInstance(adapter) } ?: return
        val raw = rawAdapterCount(adapter, methods) ?: return
        if (raw <= 0) return
        rebuildAdapterCache(adapter, raw)
    }

    /** 读宿主 adapter 的真实（未过滤）条目数，绕过 getCount 上的过滤 hook。 */
    private fun rawAdapterCount(adapter: Any, methods: AdapterMethods): Int? {
        suppressCountFilter.set(true)
        return try {
            runCatching { methods.getCount.invoke(adapter) as Int }.getOrNull()
        } finally {
            suppressCountFilter.remove()
        }
    }

    private fun adapterMethods(adapter: Any): AdapterMethods =
        adapterMethods.first { it.getView.declaringClass.isInstance(adapter) }

    private fun adapterItemMatches(item: Any?, group: ChatGroup): Boolean {
        if (isAllTab(group.id)) return true
        val username = adapterItemUsername(item) ?: return false
        return when (group.type) {
            GroupType.PRESET_UNREAD -> adapterItemUnread(item) > 0
            GroupType.PRESET_GROUPS -> username.endsWith("@chatroom")
            GroupType.PRESET_FRIENDS -> !username.endsWith("@chatroom") && !username.startsWith("gh_")
            GroupType.MANUAL, GroupType.SQL -> activeAdapterMembers.contains(username)
            GroupType.PRESET_OFFICIALS -> username.startsWith("gh_")
        }
    }

    private fun adapterItemUsername(item: Any?): String? {
        if (item == null) return null
        if (item is Map<*, *>) return item["username"]?.toString()
        return itemFields(item).username?.get(item) as? String
    }

    private fun adapterItemUnread(item: Any?): Int {
        if (item == null) return 0
        if (item is Map<*, *>) {
            return listOf("field_unReadCount", "unReadCount", "field_unReadMuteCount", "unReadMuteCount")
                .sumOf { (item[it] as? Number)?.toInt() ?: 0 }
        }
        return itemFields(item).unreadCounts.sumOf { (it.get(item) as? Number)?.toInt() ?: 0 }
    }

    /**
     * Snapshot of (username -> total unread) from WeChat's conversation table, used to render
     * per-group unread badges on the tabs.
     */
    private fun conversationUnreadSnapshot(): Map<String, Int> = runCatching {
        val result = HashMap<String, Int>()
        WeDatabaseApi.rawQuery(
            "SELECT username, unReadCount, unReadMuteCount FROM rconversation",
        ).use { cursor ->
            val usernameIndex = cursor.getColumnIndex("username")
            val unreadIndex = cursor.getColumnIndex("unReadCount")
            val muteIndex = cursor.getColumnIndex("unReadMuteCount")
            if (usernameIndex < 0) return@use
            while (cursor.moveToNext()) {
                val username = cursor.getString(usernameIndex) ?: continue
                val unread = (if (unreadIndex >= 0) cursor.getInt(unreadIndex) else 0) +
                    (if (muteIndex >= 0) cursor.getInt(muteIndex) else 0)
                if (unread > 0) result[username] = (result[username] ?: 0) + unread
            }
        }
        result
    }.getOrElse {
        WeLogger.w(TAG, "failed to read conversation unread snapshot", it)
        emptyMap()
    }

    private fun usernameMatchesGroup(username: String, group: ChatGroup): Boolean =
        when (group.type) {
            GroupType.PRESET_UNREAD -> true
            GroupType.PRESET_GROUPS -> username.endsWith("@chatroom")
            GroupType.PRESET_FRIENDS -> !username.endsWith("@chatroom") && !username.startsWith("gh_")
            GroupType.PRESET_OFFICIALS -> username.startsWith("gh_")
            GroupType.MANUAL, GroupType.SQL -> group.members.contains(username)
        }

    private fun groupUnreadCount(group: ChatGroup, snapshot: Map<String, Int>): Int {
        if (!tabShowUnreadState.value) return 0
        if (isAllTab(group.id)) return snapshot.values.sum()
        val includeOfficials = tabIncludeOfficialUnreadState.value
        var total = 0
        for ((username, unread) in snapshot) {
            if (!includeOfficials && username.startsWith("gh_")) continue
            if (usernameMatchesGroup(username, group)) total += unread
        }
        return total
    }

    private fun itemFields(item: Any): AdapterItemFields =
        adapterItemFields.getOrPut(item.javaClass) {
            val fields = generateSequence(item.javaClass as Class<*>?) { it.superclass }
                .takeWhile { it != Any::class.java }
                .flatMap { it.declaredFields.asSequence() }
                .onEach { it.isAccessible = true }
                .toList()
            AdapterItemFields(
                username = fields.firstOrNull { it.name == "field_username" || it.name == "username" },
                unreadCounts = fields.filter {
                    it.name == "field_unReadCount" || it.name == "unReadCount" ||
                        it.name == "field_unReadMuteCount" || it.name == "unReadMuteCount"
                },
            )
        }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var selected by remember { mutableStateOf(groupingBackend) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_conversation_grouping_name)) },
                textTopSpacing = 0.dp,
                text = {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item {
                            SegmentedColumn(
                                title = stringResource(R.string.conversation_grouping_tab_layout_title),
                                contentPadding = PaddingValues(0.dp),
                                titlePadding = PaddingValues(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                item {
                                    RadioButtonWidget(
                                        title = stringResource(R.string.conversation_grouping_tab_style_floating),
                                        selected = tabStyleState.value == TabStyle.FLOATING,
                                        onClick = {
                                            tabStyleValue = TabStyle.FLOATING.value
                                            tabStyleState.value = TabStyle.FLOATING
                                        },
                                    )
                                }
                                item {
                                    RadioButtonWidget(
                                        title = stringResource(R.string.conversation_grouping_tab_style_full_width),
                                        selected = tabStyleState.value == TabStyle.FULL_WIDTH,
                                        onClick = {
                                            tabStyleValue = TabStyle.FULL_WIDTH.value
                                            tabStyleState.value = TabStyle.FULL_WIDTH
                                        },
                                    )
                                }
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_pin_tabs),
                                        description = stringResource(R.string.conversation_grouping_pin_tabs_description),
                                        checked = pinTabsState.value,
                                        onCheckedChange = {
                                            pinTabsValue = it
                                            pinTabsState.value = it
                                        },
                                    )
                                }
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_remember_scroll_state),
                                        description = stringResource(R.string.conversation_grouping_remember_scroll_state_description),
                                        checked = tabRememberScrollState.value,
                                        onCheckedChange = {
                                            tabRememberScrollValue = it
                                            tabRememberScrollState.value = it
                                        },
                                    )
                                }
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_take_over_horizontal_scroll),
                                        description = stringResource(R.string.conversation_grouping_take_over_horizontal_scroll_description),
                                        checked = tabTakeOverSwipeState.value,
                                        onCheckedChange = {
                                            tabTakeOverSwipeValue = it
                                            tabTakeOverSwipeState.value = it
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            SegmentedColumn(
                                title = stringResource(R.string.conversation_grouping_unread_title),
                                contentPadding = PaddingValues(0.dp),
                                titlePadding = PaddingValues(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_show_unread),
                                        description = stringResource(R.string.conversation_grouping_show_unread_description),
                                        checked = tabShowUnreadState.value,
                                        onCheckedChange = {
                                            tabShowUnreadValue = it
                                            tabShowUnreadState.value = it
                                        },
                                    )
                                }
                                item {
                                    SwitchWidget(
                                        title = stringResource(R.string.conversation_grouping_include_official_unread),
                                        description = stringResource(R.string.conversation_grouping_include_official_unread_description),
                                        enabled = tabShowUnreadState.value,
                                        checked = tabIncludeOfficialUnreadState.value,
                                        onCheckedChange = {
                                            tabIncludeOfficialUnreadValue = it
                                            tabIncludeOfficialUnreadState.value = it
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            SegmentedColumn(
                                title = stringResource(R.string.conversation_grouping_backend_title),
                                contentPadding = PaddingValues(0.dp),
                                titlePadding = PaddingValues(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                item(key = GroupingBackend.ADAPTER_FILTER.value) {
                                    RadioButtonWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.conversation_grouping_backend_adapter),
                                        description = stringResource(R.string.conversation_grouping_backend_adapter_description),
                                        selected = selected == GroupingBackend.ADAPTER_FILTER,
                                        onClick = {
                                            selected = GroupingBackend.ADAPTER_FILTER
                                            selectGroupingBackend(GroupingBackend.ADAPTER_FILTER)
                                        },
                                    )
                                }
                                item(key = GroupingBackend.QUERY_REWRITE.value) {
                                    RadioButtonWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.conversation_grouping_backend_query),
                                        description = stringResource(R.string.conversation_grouping_backend_query_description),
                                        selected = selected == GroupingBackend.QUERY_REWRITE,
                                        onClick = {
                                            selected = GroupingBackend.QUERY_REWRITE
                                            selectGroupingBackend(GroupingBackend.QUERY_REWRITE)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun selectGroupingBackend(backend: GroupingBackend) {
        if (groupingBackend == backend) return
        groupingBackendValue = backend.value
        activePredicate = if (backend == GroupingBackend.QUERY_REWRITE &&
            !isAllTab(activeAdapterGroup.id)
        ) {
            buildGroupPredicate(activeAdapterGroup)
        } else {
            null
        }
        // 摘/挂 hook 会让 getCount() 的返回值在「有缓存 ↔ 无缓存」之间切换，必须与通知宿主
        // 待在同一个主线程 turn 里；enable() 失败时也要通知一次，把宿主拉回「未过滤」，
        // 绝不让它的 mItemCount 停在旧的过滤值上。
        onMainTurn {
            clearAdapterCaches()
            if (isActive) disable()
            if (isEnabled) enable()
            refreshConversations(backend)
        }
    }

    private fun selectTab(groupId: String?) {
        // Resolve the predicate here, on the main thread, NOT inside the query hook: preset/SQL
        // groups need a DB read to materialize their member list, and doing that while WeChat is
        // already running the list query would nest reads on the same path.
        // The "全部" tab (or a null id) applies no filter.
        activeAdapterGroup = if (groupId == null || isAllTab(groupId)) {
            allTab()
        } else {
            groupById(groupId) ?: allTab()
        }
        activeAdapterMembers = when (activeAdapterGroup.type) {
            GroupType.MANUAL, GroupType.SQL ->
                getGroupMembers(activeAdapterGroup).toSet()
            else -> emptySet()
        }
        activePredicate = if (groupingBackend == GroupingBackend.QUERY_REWRITE &&
            groupId != null && !isAllTab(groupId)
        ) {
            buildGroupPredicate(activeAdapterGroup)
        } else {
            null
        }
        // 时序（关键，见 primeAdapterCaches 的注释）：先把过滤缓存原子换好，再让宿主列表
        // 通过 notifyDataSetChanged 重新取 count。两步必须落在同一个主线程 turn 内 ——
        // 这样宿主的 mItemCount 始终等于我们 getCount 返回的值。
        // 清缓存 → 重建 → 通知宿主重新取 count，三步必须在同一个主线程 turn 内完成
        // （onMainTurn 在已处于主线程时是同步调用 —— 从 Compose 点击回调进来就是这条路）。
        // 否则宿主的 mItemCount 会有一瞬间与 getCount() 的返回值不一致，滑动/触摸首页
        // 就抛 ISE（wekit-crash-2026-09-26_16-02-11-423）。
        onMainTurn {
            clearAdapterCaches()
            primeAdapterCaches()
            refreshConversations(groupingBackend)
        }
        restoreScrollPosition(groupId)
    }

    private fun refreshConversations(backend: GroupingBackend) {
        if (backend == GroupingBackend.ADAPTER_FILTER) {
            // 页式 Recycler 适配器要通过它自己的数据源重建（count / item / bind / 点击 /
            // 增量更新的下标必须落在同一份真实列表上）；宿主首页的会话列表则是 ListView
            // （实机崩溃栈里的 `ListView(-1, class o95.y3)`），它只认
            // `adapter.notifyDataSetChanged()`。
            //
            // **这里不能短路。** 原来写成 `if (!refreshRecyclerData()) refresh()`，只要进程里
            // 存在任何一个 RecyclerView 列表（recyclerLists 非空）就会跳过 ListView 的刷新，
            // 我们改过的 getCount 返回值就再也传不到 ListView 的 mItemCount —— 滑动/触摸首页
            // 直接抛 IllegalStateException 闪退（wekit-crash-2026-09-26_16-02-11-423）。
            // 两条路都要走。
            refreshRecyclerData()
            WeConversationListViewApi.refreshNow()
        } else {
            // Query Rewrite needs a fresh host query so the new SQL predicate is applied.
            WeConversationApi.reloadConversations()
        }
    }

    /**
     * Translates a group definition into a SQL predicate over rconversation. Preset groups use a
     * live LIKE so newly-arrived chats appear without re-selecting the tab; manual / SQL groups
     * resolve to an explicit username set. A missing group or an empty member set yields "0" (match
     * nothing) rather than null, so an empty group shows an empty list instead of everything.
     */
    private fun buildGroupPredicate(group: ChatGroup?): String {
        group ?: return "0"
        return when (group.type) {
            GroupType.PRESET_UNREAD -> "rconversation.unReadCount>0 OR rconversation.unReadMuteCount>0"
            GroupType.PRESET_GROUPS -> "rconversation.username LIKE '%@chatroom'"
            GroupType.PRESET_FRIENDS ->
                "rconversation.username NOT LIKE '%@chatroom' AND rconversation.username NOT LIKE 'gh_%'"
            GroupType.PRESET_OFFICIALS -> "rconversation.username LIKE 'gh_%'"
            GroupType.MANUAL -> membersInClause(group.members)
            GroupType.SQL -> membersInClause(resolveGroupMembers(group))
        }
    }

    private fun membersInClause(members: List<String>): String {
        val cleaned = members.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return "0"
        val list = cleaned.joinToString(",") { "'${it.replace("'", "''")}'" }
        return "rconversation.username IN ($list)"
    }

    // The homepage conversation-list cursor does NOT flow through the standard
    // SQLiteDatabase.rawQuery path that WeDatabaseListenerApi hooks; WeChat builds it through its
    // own SQLite wrapper (n3 -> i0.a(sql, args, int)). We hook that wrapper directly, the same
    // chokepoint AggregateChats uses, and append our tab predicate to the SQL before it runs.
    private fun hookConversationListQuery() {
        if (WeDatabaseApi.methodSqliteWrapperRawQuery.isPlaceholder) {
            WeLogger.w(TAG, "SQLite wrapper query method not resolved; tab filtering disabled")
            return
        }
        WeDatabaseApi.methodSqliteWrapperRawQuery.hookBefore {
            val sql = args.firstOrNull() as? String ?: return@hookBefore
            rewriteConversationListSql(sql)?.let { args[0] = it }
        }
    }

    // Returns the rewritten SQL, or null to leave it untouched (all non-list queries and "全部").
    private fun rewriteConversationListSql(sql: String): String? {
        val predicate = activePredicate ?: return null
        if (!looksLikeConversationListQuery(sql)) return null

        val hidden = if (HideContacts.isEnabled) HideContacts.hiddenContacts else emptySet()
        val hiddenClause = if (hidden.isEmpty()) {
            ""
        } else {
            " AND rconversation.username NOT IN (" +
                    hidden.joinToString(",") { "'${it.replace("'", "''")}'" } + ")"
        }

        return injectCondition(sql, "($predicate)$hiddenClause")
    }

    private fun looksLikeConversationListQuery(sql: String): Boolean {
        val lower = sql.lowercase()
        if (!lower.contains("select")) return false
        if (!lower.contains("from rconversation")) return false
        // Don't touch AggregateChats folder-container queries (scoped to a wekit_folder_ parentRef)
        // or WeChat's own conversation-box container; the tabs only apply to the homepage list.
        if (lower.contains("wekit_folder_") || lower.contains("conversationboxservice")) return false
        // The homepage list query is the one carrying per-conversation display columns; ignore
        // aggregate/count/single-row lookups so we don't corrupt unrelated reads.
        return lower.contains("conversationtime") &&
                lower.contains("unreadcount") &&
                lower.contains("digestuser")
    }

    // Insert an extra WHERE predicate before any ORDER BY / GROUP BY / LIMIT tail, joining with the
    // existing WHERE when present. Mirrors AggregateChats.appendParentRefFilter.
    private fun injectCondition(sql: String, condition: String): String {
        val insertionPoint = listOf(" order by ", " group by ", " limit ")
            .map { sql.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: sql.length
        val head = sql.substring(0, insertionPoint)
        val tail = sql.substring(insertionPoint)
        val connector = if (head.contains(" where ", ignoreCase = true)) " AND " else " WHERE "
        return "$head$connector$condition$tail"
    }

    private const val TAG = "ConversationGrouping"

    private val classRecyclerDataSource by dexClass()

    private val methodRecyclerQueryPage by dexMethod()

    private val fieldRecyclerPageItems by dexField()

    private val classRecyclerRow by dexClass()

    private val fieldRecyclerRowConversation by dexField()

    private val methodRecyclerSubmitUiChange by dexMethod()

    private val fieldRecyclerPendingItems by dexField()

    private val fieldRecyclerMvvmList by dexField()

    private val methodRecyclerRefreshAll by dexMethod()

    private val methodOnTabCreate by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.conversation.MainUI"
            usingEqStrings("MicroMsg.MainUI", "onTabCreate, %d")
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        DexResolutionContext.ensureResolved(WeConversationListViewApi.classConversationRecyclerAdapter)
        if (WeConversationListViewApi.classConversationRecyclerAdapter.isPlaceholder) {
            val reason = "conversation RecyclerView architecture is absent"
            classRecyclerDataSource.setPlaceholderDescriptor(true, reason)
            methodRecyclerQueryPage.setPlaceholderDescriptor(true, reason)
            fieldRecyclerPageItems.setPlaceholderDescriptor(true, reason)
            classRecyclerRow.setPlaceholderDescriptor(true, reason)
            fieldRecyclerRowConversation.setPlaceholderDescriptor(true, reason)
            methodRecyclerSubmitUiChange.setPlaceholderDescriptor(true, reason)
            fieldRecyclerPendingItems.setPlaceholderDescriptor(true, reason)
            fieldRecyclerMvvmList.setPlaceholderDescriptor(true, reason)
            methodRecyclerRefreshAll.setPlaceholderDescriptor(true, reason)
            return
        }

        classRecyclerDataSource.find(dexKit) {
            matcher {
                usingEqStrings(
                    "MicroMsg.ConversationAdapter.ConvRecyclerDataSource",
                    "syncFoldExpandStatus: isShowPlaceTop=",
                )
            }
        }
        methodRecyclerQueryPage.find(dexKit) {
            matcher {
                paramCount = 1
                usingEqStrings(
                    "getConvList: may getContact error, size mismatch",
                    "getConvList ",
                )
            }
        }
        fieldRecyclerPageItems.find(dexKit) {
            matcher {
                declaredClass(methodRecyclerQueryPage.data.returnTypeName)
                type = "java.util.ArrayList"
            }
        }

        val conversationClassName = WeConversationListViewApi.methodAdapterGetItem.data.returnTypeName
        val rowBuilders = methodRecyclerQueryPage.data.invokes.distinctBy { it.descriptor }
            .filter { candidate ->
                candidate.paramTypeNames.firstOrNull() == conversationClassName &&
                    dexKit.getClassData(candidate.returnTypeName)?.fields?.any {
                        it.typeName == conversationClassName
                    } == true
            }
        require(rowBuilders.size == 1) {
            "expected one conversation RecyclerView row builder, found: " +
                rowBuilders.joinToString { it.descriptor }
        }
        val recyclerRow = dexKit.getClassData(rowBuilders.single().returnTypeName)!!
        classRecyclerRow.setDescriptor(recyclerRow)
        fieldRecyclerRowConversation.setDescriptor(recyclerRow.fields.single {
            it.typeName == conversationClassName
        })

        val recyclerAdapterBase =
            WeConversationListViewApi.classConversationRecyclerAdapter.data.superClass!!
        require(recyclerAdapterBase.fields.size == 1) {
            "expected one Recycler adapter base field, found: " +
                recyclerAdapterBase.fields.joinToString { it.descriptor }
        }
        val recyclerMvvmListField = recyclerAdapterBase.fields.single()
        fieldRecyclerMvvmList.setDescriptor(recyclerMvvmListField)

        methodRecyclerSubmitUiChange.find(dexKit) {
            matcher {
                declaredClass(recyclerMvvmListField.typeName)
                paramCount = 1
                returnType = "void"
                usingEqStrings(
                    "submitUIChange callback:",
                    " currentDataListVersion:",
                )
            }
        }
        fieldRecyclerPendingItems.find(dexKit) {
            matcher {
                declaredClass(methodRecyclerSubmitUiChange.data.paramTypeNames.single())
                type = "java.util.List"
                addReadMethod {
                    declaredClass(methodRecyclerSubmitUiChange.data.declaredClassName)
                    paramTypes(methodRecyclerSubmitUiChange.data.paramTypeNames.single())
                    usingEqStrings("submitUIChange callback:", " currentDataListVersion:")
                }
            }
        }
        methodRecyclerRefreshAll.find(dexKit) {
            matcher {
                declaredClass(methodRecyclerSubmitUiChange.data.declaredClassName)
                modifiers(ReflectModifier.STATIC)
                paramTypes(
                    methodRecyclerSubmitUiChange.data.declaredClassName,
                    null,
                    "int",
                    "java.lang.Object",
                )
                returnType = "void"
                usingEqStrings("submitRefreshAll")
            }
        }
    }

    // WeChat's SQLite wrapper query: i0.a(String sql, String[] args, int) -> Cursor. Same anchor
    // AggregateChats uses to intercept the homepage/folder list queries.
    // ----------------------------------------------------------------------------------------------
    // Tab bar UI
    // ----------------------------------------------------------------------------------------------

    /**
     * 分组顶栏底色。
     *
     * 顶栏是 WeKit 自己塞进会话列表的组件，莫奈引擎只替换宿主微信的资源 id，改不到这里，
     * 所以直接读引擎色板与原生保持一致；莫奈未启用 / 未解析成功时（[MonetColors.tokens]
     * 返回 null）完全保持原来的固定灰，不会退化成随机色。
     */
    @Composable
    private fun monetTabBarContainerColor(): Color {
        val night = isSystemInDarkTheme()
        return MonetColors.applied.value
            ?.let { MonetColors.tokens(night) }
            ?.let { Color(it.surfaceContainerHigh) }
            ?: if (night) Color(0xFF111111) else Color(0xFFEDEDED)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ConversationTabs(
        groups: List<ChatGroup>,
        selectedGroupId: String,
        onTabSelected: (String) -> Unit,
        onCreateGroup: () -> Unit,
        onEditGroup: (ChatGroup) -> Unit,
        onDeleteGroup: (ChatGroup) -> Unit,
        onReorder: (List<String>) -> Unit,
        modifier: Modifier = Modifier,
        containerColor: Color = monetTabBarContainerColor(),
    ) {
        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
        // Per-group unread badges: refreshed whenever the tab set / selection / toggle changes.
        val unreadSnapshot by produceState(
            initialValue = emptyMap<String, Int>(),
            groups,
            selectedGroupId,
            tabShowUnreadState.value,
            tabIncludeOfficialUnreadState.value,
        ) {
            value = if (tabShowUnreadState.value) conversationUnreadSnapshot() else emptyMap()
        }
        var menuForGroupId by remember { mutableStateOf<String?>(null) }
        // Sort (edit) mode: long-press a tab to drag-reorder.
        var sortMode by remember { mutableStateOf(false) }
        // The working order while sorting. Seeded from `groups` on entry and mutated live as the
        // user drags; committed via onReorder only when the check button is tapped.
        var order by remember { mutableStateOf(groups.map { it.id }) }

        // Keep the working order in sync while NOT sorting (groups added/removed/edited elsewhere).
        LaunchedEffect(groups, sortMode) {
            if (!sortMode) order = groups.map { it.id }
        }

        val orderedGroups = remember(order, groups) {
            val byId = groups.associateBy { it.id }
            order.mapNotNull { byId[it] }
        }

        val guardView = LocalView.current
        val guardSlopPx = with(LocalDensity.current) { 24.dp.toPx() }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(containerColor)
                .guardAgainstParentIntercept(guardView, guardSlopPx, consumeVertical = !sortMode)
        ) {
            if (sortMode) {
                SortableTabsRow(
                    groups = orderedGroups,
                    selectedGroupId = selectedGroupId,
                    onMove = { from, to ->
                        order = order.toMutableList().apply { add(to, removeAt(from)) }
                    }
                )
            } else {
                val tabs: @Composable () -> Unit = {
                    orderedGroups.forEach { group ->
                        key(group.id) {
                            val allTab = isAllTab(group.id)
                            val label = groupDisplayName(group)
                            Box {
                                GroupTab(
                                    label = label,
                                    selected = selectedGroupId == group.id,
                                    unread = groupUnreadCount(group, unreadSnapshot),
                                    onClick = { onTabSelected(group.id) },
                                    onLongClick = { menuForGroupId = group.id },
                                    floating = tabStyleState.value == TabStyle.FLOATING,
                                )

                                DropdownMenu(
                                    expanded = menuForGroupId == group.id,
                                    onDismissRequest = { menuForGroupId = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.conversation_group_action_new)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Add,
                                                contentDescription = stringResource(R.string.conversation_group_new_description),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            menuForGroupId = null
                                            onCreateGroup()
                                        }
                                    )
                                    // The fixed "全部" tab can be reordered but never edited or deleted.
                                    if (!allTab) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.conversation_group_action_edit)) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = MaterialSymbols.Outlined.Edit,
                                                    contentDescription = stringResource(R.string.conversation_group_action_edit),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            },
                                            onClick = {
                                                menuForGroupId = null
                                                onEditGroup(group)
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.conversation_group_action_reorder)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Swap_vert,
                                                contentDescription = stringResource(R.string.conversation_group_action_reorder),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            menuForGroupId = null
                                            order = groups.map { it.id }
                                            sortMode = true
                                        }
                                    )
                                    if (!allTab) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.conversation_group_action_delete)) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = MaterialSymbols.Outlined.Delete,
                                                    contentDescription = stringResource(R.string.conversation_group_action_delete),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            },
                                            onClick = {
                                                menuForGroupId = null
                                                onDeleteGroup(group)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val selectedTabIndex = orderedGroups.indexOfFirst { it.id == selectedGroupId }
                    .coerceAtLeast(0)
                if (tabStyleState.value == TabStyle.FULL_WIDTH) {
                    PrimaryTabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = containerColor,
                        divider = {},
                        tabs = tabs,
                    )
                } else {
                    val textMeasurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    val textStyle = MaterialTheme.typography.titleSmall
                    val tabsWidth = orderedGroups.fold(0.dp) { width, group ->
                        val textWidth = textMeasurer.measure(
                            text = groupDisplayName(group),
                            style = textStyle,
                            maxLines = 1,
                            softWrap = false,
                        ).size.width
                        width + with(density) {
                            (textWidth + groupTabHorizontalPadding.roundToPx() * 2)
                                .coerceAtLeast(48.dp.roundToPx()).toDp()
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // Center short rows; retain only the edge inset once tabs overflow.
                        PrimaryScrollableTabRow(
                            selectedTabIndex = selectedTabIndex,
                            containerColor = containerColor,
                            edgePadding = ((maxWidth - tabsWidth) / 2).coerceAtLeast(12.dp),
                            minTabWidth = 48.dp,
                            divider = {},
                            tabs = tabs,
                        )
                    }
                }
            }

            // In sort mode the trailing "+" turns into a "✓" that commits the new order. Overlaid on
            // the right so it stays put regardless of how far the row scrolls.
            if (sortMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .background(containerColor, CircleShape)
                ) {
                    IconButton(
                        onClick = {
                            onReorder(order)
                            sortMode = false
                            showToast(localizedContext.getString(R.string.conversation_group_order_saved))
                        },
                        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors()
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Check,
                            contentDescription = stringResource(R.string.conversation_group_save_order_description),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    private fun localizedGroupName(context: Context, group: ChatGroup): String {
        if (isAllTab(group.id)) return context.getString(R.string.conversation_group_all)
        if (group.name.isNotBlank()) return group.name
        return when (group.builtInLabel) {
            BuiltInGroupLabel.UNREAD -> context.getString(R.string.conversation_group_default_unread)
            BuiltInGroupLabel.GROUPS -> context.getString(R.string.conversation_group_default_groups)
            BuiltInGroupLabel.FRIENDS -> context.getString(R.string.conversation_group_default_friends)
            BuiltInGroupLabel.OFFICIALS -> context.getString(R.string.conversation_group_default_officials)
            null -> ""
        }
    }

    data class GroupChoice(val id: String, val name: String, val members: List<String>)

    /** Public member snapshots used by contact pickers that need to filter by group. */
    fun groupFilterOptions(context: Context): List<GroupChoice> =
        loadGroups()
            .filterNot { isAllTab(it.id) }
            .map { group ->
                GroupChoice(
                    id = group.id,
                    name = localizedGroupName(context, group),
                    members = getGroupMembers(group),
                )
            }

    @Composable
    private fun groupDisplayName(group: ChatGroup): String =
        localizedGroupName(LocalWeKitLocalizedContext.current, group)

    /**
     * Long-press a tab to drag it into a new position. The working order is persisted only when
     * the check button is tapped.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SortableTabsRow(
        groups: List<ChatGroup>,
        selectedGroupId: String,
        onMove: (from: Int, to: Int) -> Unit,
    ) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // Drag state, all read live inside a single row-level gesture detector so nothing captures a
        // stale `groups` snapshot:
        //  - draggingIndex: the position of the picked-up tab, updated as it swaps past neighbours.
        //  - initialOffset: the tab's layout offset at pickup (fixed for the whole drag).
        //  - draggedDelta: raw accumulated finger movement on X since pickup.
        // The tab's visual translation is initialOffset + draggedDelta - itsCurrentLayoutOffset, so a
        // swap that shifts the layout is compensated automatically without rebasing draggedDelta.
        var draggingIndex by remember { mutableIntStateOf(-1) }
        var initialOffset by remember { mutableIntStateOf(0) }
        var draggedDelta by remember { mutableFloatStateOf(0f) }

        // Drop-settle animation: on release the tab keeps its visual offset and springs it back to 0
        // (its slot), instead of teleporting. settleIndex marks which slot owns settleAnim.
        var settleIndex by remember { mutableIntStateOf(-1) }
        val settleAnim = remember { Animatable(0f) }

        // The dragged tab's live layout info (found by its current index, which we keep updated).
        fun offsetForIndex(index: Int): Float {
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                ?: return 0f
            return initialOffset + draggedDelta - item.offset
        }

        // Reserve space for the save button outside the scrolling and drag-hit-test area.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(end = 56.dp)) {
            LazyRow(
                state = listState,
                // Keep normal horizontal scrolling while nothing is picked up, so an overflowing tab
                // row can be swiped left/right. Once a tab is picked up the drag consumes the gesture,
                // and the auto-scroll below handles scrolling near the edges.
                userScrollEnabled = draggingIndex == -1,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                // Hit-test the touch against the live layout to pick up the right tab.
                                val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    offset.x.toInt() in it.offset..it.offset + it.size
                                }
                                if (hit != null) {
                                    draggingIndex = hit.index
                                    initialOffset = hit.offset
                                    draggedDelta = 0f
                                }
                            },
                            onDragEnd = {
                                val landed = draggingIndex
                                val from = offsetForIndex(landed)
                                draggingIndex = -1
                                // Spring the residual offset back to the slot so the tab glides home.
                                if (landed >= 0) scope.launch {
                                    settleIndex = landed
                                    settleAnim.snapTo(from)
                                    settleAnim.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                    settleIndex = -1
                                }
                            },
                            onDragCancel = { draggingIndex = -1 },
                            onDrag = { change, amount ->
                                change.consume()
                                if (draggingIndex < 0) return@detectDragGesturesAfterLongPress
                                draggedDelta += amount.x
                                val info = listState.layoutInfo.visibleItemsInfo
                                val cur = info.firstOrNull { it.index == draggingIndex }
                                    ?: return@detectDragGesturesAfterLongPress
                                // Center of the dragged tab as it currently sits under the finger.
                                val center = (cur.offset + offsetForIndex(draggingIndex) + cur.size / 2f).toInt()
                                val target = info.firstOrNull { other ->
                                    other.index != draggingIndex &&
                                            center in other.offset..other.offset + other.size
                                }
                                if (target != null) {
                                    onMove(draggingIndex, target.index)
                                    draggingIndex = target.index
                                }
                            }
                        )
                    },
                contentPadding = PaddingValues(horizontal = if (tabStyleState.value == TabStyle.FULL_WIDTH) 0.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(groups.size, key = { groups[it].id }) { index ->
                    val group = groups[index]
                    val dragging = index == draggingIndex
                    val settling = index == settleIndex

                    val scale by animateFloatAsState(
                        targetValue = if (dragging || settling) 1.1f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "dragScale",
                    )
                    GroupTabContent(
                        label = groupDisplayName(group),
                        selected = selectedGroupId == group.id,
                        modifier = Modifier
                            .then(
                                if (tabStyleState.value == TabStyle.FULL_WIDTH) Modifier.width(maxWidth / groups.size)
                                else Modifier.widthIn(min = 48.dp)
                            )
                            .zIndex(if (dragging || settling) 1f else 0f)
                            .graphicsLayer {
                                translationX = when {
                                    dragging -> offsetForIndex(index)
                                    settling -> settleAnim.value
                                    else -> 0f
                                }
                                scaleX = scale
                                scaleY = scale
                            }
                            .then(if (dragging || settling) Modifier else Modifier.animateItem()),
                    )
                }
            }
        }

        // Auto-scroll the row when the dragged tab is pushed near either edge.
        LaunchedEffect(Unit) {
            snapshotFlow { if (draggingIndex >= 0) draggedDelta else Float.NaN }.collect { delta ->
                if (delta.isNaN()) return@collect
                val info = listState.layoutInfo
                val cur = info.visibleItemsInfo.firstOrNull { it.index == draggingIndex } ?: return@collect
                val center = cur.offset + offsetForIndex(draggingIndex) + cur.size / 2f
                val edge = 64
                when {
                    center < info.viewportStartOffset + edge && listState.canScrollBackward ->
                        scope.launch { listState.scrollBy(-12f) }

                    center > info.viewportEndOffset - edge && listState.canScrollForward ->
                        scope.launch { listState.scrollBy(12f) }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GroupTab(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        unread: Int = 0,
        floating: Boolean = false,
    ) {
        GroupTabContent(
            label = label,
            selected = selected,
            unread = unread,
            floating = floating,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.selected = selected }
                .combinedClickable(
                    role = Role.Tab,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        )
    }

    @Composable
    private fun GroupTabContent(
        label: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        unread: Int = 0,
        floating: Boolean = false,
    ) {
        val labelColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        val labelRow: @Composable () -> Unit = {
            Text(
                text = label,
                color = labelColor,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unread > 0) {
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(percent = 50))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (unread > 99) "99+" else unread.toString(),
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            modifier = modifier
                .heightIn(min = 48.dp)
                .padding(
                    horizontal = if (floating) 4.dp else groupTabHorizontalPadding,
                    vertical = 12.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (floating) {
                // Floating-island style: a rounded pill that lights up when selected.
                Box(
                    modifier = Modifier
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else Color.Transparent,
                            RoundedCornerShape(percent = 50),
                        )
                        .padding(horizontal = groupTabHorizontalPadding, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        labelRow()
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    labelRow()
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Group configuration UI (copied 1:1 from AggregateChats' folder editor)
    // ----------------------------------------------------------------------------------------------

    private fun showCreateGroupDialog(context: Context, onGroupCreated: () -> Unit) {
        showComposeDialog(context) {
            GroupEditorDialog(
                titleRes = R.string.conversation_group_create_title,
                group = null,
                onDismiss = onDismiss,
                onSave = { group ->
                    val current = loadGroups()
                    saveGroups(current + group)
                    onGroupCreated()
                    onDismiss()
                }
            )
        }
    }

    private fun showEditGroupDialog(
        context: Context,
        group: ChatGroup,
        onGroupUpdated: () -> Unit,
        onGroupDeleted: () -> Unit
    ) {
        showComposeDialog(context) {
            GroupEditorDialog(
                titleRes = R.string.conversation_group_edit_title,
                group = group,
                onDismiss = onDismiss,
                onDelete = {
                    showConfirmDeleteGroupDialog(context, group) {
                        val current = loadGroups()
                        saveGroups(current.filterNot { it.id == group.id })
                        onGroupDeleted()
                        onDismiss()
                    }
                },
                onSave = { updated ->
                    val current = loadGroups()
                    saveGroups(current.map { if (it.id == updated.id) updated else it })
                    onGroupUpdated()
                    onDismiss()
                }
            )
        }
    }

    private fun showConfirmDeleteGroupDialog(
        context: Context,
        group: ChatGroup,
        onConfirm: () -> Unit,
    ) {
        showComposeDialog(context) {
            val groupName = groupDisplayName(group)
            AlertDialogContent(
                title = { Text(stringResource(R.string.conversation_group_delete_title)) },
                text = { Text(stringResource(R.string.conversation_group_delete_message, groupName)) },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        onConfirm()
                    }) { Text(stringResource(R.string.conversation_group_action_delete)) }
                }
            )
        }
    }

    @Composable
    private fun GroupEditorDialog(
        @StringRes titleRes: Int,
        group: ChatGroup?,
        onDismiss: () -> Unit,
        onDelete: (() -> Unit)? = null,
        onSave: (ChatGroup) -> Unit
    ) {
        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
        val groupId = remember(group) { group?.id ?: newGroupId() }
        var name by remember(group) { mutableStateOf(group?.name ?: "") }
        var members by remember(group) { mutableStateOf(group?.members?.toSet().orEmpty()) }

        var type by remember(group) { mutableStateOf(group?.type ?: GroupType.MANUAL) }
        var selectFields by remember(group) { mutableStateOf(group?.selectFields ?: "r.username") }
        var whereClause by remember(group) { mutableStateOf(group?.whereClause ?: "") }

        val matchedCount = remember(type, members, selectFields, whereClause) {
            val temp = ChatGroup(
                id = groupId,
                name = name,
                members = members.toList(),
                type = type,
                selectFields = selectFields,
                whereClause = whereClause
            )
            // Resolve directly instead of going through getGroupMembers: that cache is keyed by
            // group id, and this preview group reuses the id of the group being edited, so the
            // cached (stale) member list would freeze the count at the first result.
            resolveGroupMembers(temp).size
        }

        AlertDialogContent(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            title = { Text(stringResource(titleRes)) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.conversation_group_name)) },
                        placeholder = group?.takeIf { it.builtInLabel != null }?.let { builtInGroup ->
                            { Text(groupDisplayName(builtInGroup)) }
                        },
                        singleLine = true
                    )

                    var typeExpanded by remember { mutableStateOf(false) }
                    Column {
                        Text(stringResource(R.string.conversation_group_mode), style = MaterialTheme.typography.labelSmall)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { typeExpanded = true }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = when (type) {
                                    GroupType.MANUAL -> stringResource(R.string.conversation_group_mode_manual)
                                    GroupType.PRESET_UNREAD -> stringResource(R.string.conversation_group_mode_unread)
                                    GroupType.PRESET_GROUPS -> stringResource(R.string.conversation_group_mode_groups)
                                    GroupType.PRESET_FRIENDS -> stringResource(R.string.conversation_group_mode_friends)
                                    GroupType.PRESET_OFFICIALS -> stringResource(R.string.conversation_group_mode_officials)
                                    GroupType.SQL -> stringResource(R.string.conversation_group_mode_sql)
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        DropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_manual)) },
                                onClick = {
                                    type = GroupType.MANUAL
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_unread)) },
                                onClick = {
                                    type = GroupType.PRESET_UNREAD
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_groups)) },
                                onClick = {
                                    type = GroupType.PRESET_GROUPS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_friends)) },
                                onClick = {
                                    type = GroupType.PRESET_FRIENDS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_officials)) },
                                onClick = {
                                    type = GroupType.PRESET_OFFICIALS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_group_mode_sql)) },
                                onClick = {
                                    type = GroupType.SQL
                                    typeExpanded = false
                                }
                            )
                        }
                    }

                    when (type) {
                        GroupType.MANUAL -> {
                            Text(pluralStringResource(R.plurals.conversation_group_selected_count, matchedCount, matchedCount))
                            val context = LocalContext.current
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = stringResource(R.string.conversation_group_select_conversations),
                                            contacts = remember { WeDatabaseApi.getContacts() },
                                            initialSelectedWxIds = members,
                                            onDismiss = this.onDismiss,
                                            onConfirm = {
                                                members = it
                                                this.onDismiss()
                                            }
                                        )
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.conversation_group_select_conversations))
                            }
                        }

                        GroupType.PRESET_UNREAD -> {
                            Text(stringResource(R.string.conversation_group_unread_match_count, matchedCount))
                        }

                        GroupType.PRESET_GROUPS -> {
                            Text(stringResource(R.string.conversation_group_groups_match_count, matchedCount))
                        }

                        GroupType.PRESET_FRIENDS -> {
                            Text(stringResource(R.string.conversation_group_friends_match_count, matchedCount))
                        }

                        GroupType.PRESET_OFFICIALS -> {
                            Text(stringResource(R.string.conversation_group_officials_match_count, matchedCount))
                        }

                        GroupType.SQL -> {
                            OutlinedTextField(
                                value = selectFields,
                                onValueChange = { selectFields = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.conversation_group_select_fields)) },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = whereClause,
                                onValueChange = { whereClause = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.conversation_group_where_clause)) },
                                singleLine = false,
                                maxLines = 4
                            )
                            Text(
                                text = pluralStringResource(R.plurals.conversation_group_match_count, matchedCount, matchedCount),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.conversation_group_sql_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            dismissButton = {
                if (onDelete != null) {
                    TextButton(onDelete) { Text(stringResource(R.string.conversation_group_action_delete)) }
                }
                TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank() || group?.builtInLabel != null,
                    onClick = {
                        val next = ChatGroup(
                            id = groupId,
                            name = name.trim(),
                            members = members.toList().sorted(),
                            type = type,
                            selectFields = selectFields.trim(),
                            whereClause = whereClause.trim(),
                            builtInLabel = group?.builtInLabel,
                        )
                        onSave(next)
                        showToast(localizedContext.getString(R.string.conversation_group_saved))
                    }
                ) { Text(stringResource(R.string.dialog_confirm)) }
            }
        )
    }

    // ----------------------------------------------------------------------------------------------
    // Member resolution & persistence (adapted from AggregateChats)
    // ----------------------------------------------------------------------------------------------

    private fun resolveGroupMembers(group: ChatGroup): List<String> {
        return when (group.type) {
            GroupType.MANUAL -> group.members
            GroupType.PRESET_UNREAD -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT c.username FROM rconversation c WHERE c.unReadCount > 0 OR c.unReadMuteCount > 0"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset unread", it)
                    emptyList()
                }
            }

            GroupType.PRESET_GROUPS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username LIKE '%@chatroom'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset groups", it)
                    emptyList()
                }
            }

            GroupType.PRESET_FRIENDS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username NOT LIKE '%@chatroom' AND r.username NOT LIKE 'gh_%'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset friends", it)
                    emptyList()
                }
            }

            GroupType.PRESET_OFFICIALS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username LIKE 'gh_%'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset officials", it)
                    emptyList()
                }
            }

            GroupType.SQL -> {
                runCatching {
                    val select = group.selectFields.ifBlank { "r.username" }
                    val where = group.whereClause.ifBlank { "1=1" }
                    val query =
                        "SELECT $select FROM rcontact r LEFT JOIN img_flag i ON r.username = i.username LEFT JOIN rconversation c ON r.username = c.username WHERE $where"
                    val result = WeDatabaseApi.executeQuery(query)
                    result.mapNotNull { row ->
                        val username = row["username"]?.toString()
                        if (username != null) return@mapNotNull username
                        row.values.firstOrNull()?.toString()
                    }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query custom sql for group ${group.id}", it)
                    emptyList()
                }
            }
        }
    }

    private fun getGroupMembers(group: ChatGroup): List<String> {
        if (group.type == GroupType.MANUAL) {
            return group.members
        }
        val cached = groupMembersCache[group.id]
        if (cached != null) return cached

        if (!WeDatabaseApi.isReady) {
            return emptyList()
        }
        val resolved = resolveGroupMembers(group)
        if (resolved.isNotEmpty()) {
            groupMembersCache[group.id] = resolved
        }
        return resolved
    }

    private fun loadGroups(): List<ChatGroup> {
        groupsCache?.let { return it }
        val file = groupsFile
        // First run (no config yet): seed the groups that used to be the built-in tabs so the tab
        // bar isn't empty out of the box, then persist them so they're editable / deletable.
        if (!file.exists()) {
            val defaults = defaultGroups()
            saveGroups(defaults)
            return defaults
        }
        val groups = runCatching {
            val raw = file.readText()
            DefaultJson.decodeFromString<List<ChatGroup>>(raw)
                .map { group ->
                    group.copy(members = group.members.filter { it.isNotBlank() })
                }
                .map(::migrateLegacyBuiltInLabel)
                .filter {
                    (isGroupId(it.id) || isAllTab(it.id)) &&
                        (isAllTab(it.id) || it.name.isNotBlank() || it.builtInLabel != null)
                }
        }.onFailure {
            WeLogger.w(TAG, "failed to decode groups config from $groupsFile", it)
        }.getOrDefault(emptyList())
        // Guarantee the fixed "全部" tab is present. Configs written before this tab was orderable
        // won't contain it, so inject it at the front; once the user reorders, its slot persists.
        val withAll = if (groups.any { isAllTab(it.id) }) groups else listOf(allTab()) + groups
        groupsCache = withAll
        return withAll
    }

    private fun migrateLegacyBuiltInLabel(group: ChatGroup): ChatGroup {
        if (isAllTab(group.id)) return group.copy(name = "")
        if (group.builtInLabel != null) return group
        val label = when (group.type) {
            GroupType.PRESET_UNREAD if group.name == "未读" -> BuiltInGroupLabel.UNREAD
            GroupType.PRESET_GROUPS if group.name == "群聊" -> BuiltInGroupLabel.GROUPS
            GroupType.PRESET_FRIENDS if group.name == "好友" -> BuiltInGroupLabel.FRIENDS
            GroupType.PRESET_OFFICIALS if group.name == "公众号" -> BuiltInGroupLabel.OFFICIALS
            else -> null
        }
        return if (label == null) group else group.copy(name = "", builtInLabel = label)
    }

    // The groups seeded on first run, matching the fixed categories while keeping every category
    // editable and reorderable except the non-deletable 全部 tab.
    private fun defaultGroups(): List<ChatGroup> {
        // Distinct ids so each row is independently editable / deletable. The fixed "全部" tab leads
        // by default but can be dragged elsewhere.
        val base = System.currentTimeMillis()
        return listOf(
            allTab(),
            ChatGroup(
                id = "$GROUP_PREFIX${base}",
                type = GroupType.PRESET_UNREAD,
                builtInLabel = BuiltInGroupLabel.UNREAD,
            ),
            ChatGroup(
                id = "$GROUP_PREFIX${base + 1}",
                type = GroupType.PRESET_GROUPS,
                builtInLabel = BuiltInGroupLabel.GROUPS,
            ),
            ChatGroup(
                id = "$GROUP_PREFIX${base + 2}",
                type = GroupType.PRESET_FRIENDS,
                builtInLabel = BuiltInGroupLabel.FRIENDS,
            ),
            ChatGroup(
                id = "$GROUP_PREFIX${base + 3}",
                type = GroupType.PRESET_OFFICIALS,
                builtInLabel = BuiltInGroupLabel.OFFICIALS,
            ),
        )
    }

    private fun saveGroups(groups: List<ChatGroup>) {
        groupsCache = groups
        groupMembersCache.clear()
        runCatching {
            val raw = DefaultJson.encodeToString(groups)
            groupsFile.writeText(raw)
        }.onFailure {
            WeLogger.w(TAG, "failed to save groups to $groupsFile", it)
        }
    }

    private fun groupById(groupId: String): ChatGroup? {
        return loadGroups().firstOrNull { it.id == groupId }
    }

    private fun newGroupId(): String = "$GROUP_PREFIX${System.currentTimeMillis()}"

    private fun isGroupId(value: String): Boolean = value.startsWith(GROUP_PREFIX)

    enum class GroupType {
        MANUAL,
        PRESET_UNREAD,
        PRESET_GROUPS,
        PRESET_FRIENDS,
        PRESET_OFFICIALS,
        SQL
    }

    private enum class AdapterStorage {
        LEGACY_CURSOR,
        MVVM_LIST,
    }

    @Serializable
    private enum class BuiltInGroupLabel {
        UNREAD,
        GROUPS,
        FRIENDS,
        OFFICIALS,
    }

    @Serializable
    private data class ChatGroup(
        val id: String = "",
        val name: String = "",
        val members: List<String> = emptyList(),
        val type: GroupType = GroupType.MANUAL,
        val selectFields: String = "",
        val whereClause: String = "",
        val builtInLabel: BuiltInGroupLabel? = null,
    )
}
