package dev.joker.features.items.chat

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.joker.reflekt.reflekt
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.api.core.models.MessageInfo
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.WeLogger
import dev.joker.utils.monet.MonetColors
import java.util.WeakHashMap

/**
 * 【第 52 轮】「已领取 / 已过期」的红包与转账卡片：**运行期**圆角与配色。
 *
 * 为什么不能靠资源覆盖（这是用户反馈了四轮的同一个问题）：
 * 实机日志证明资源侧全部成功 ——
 * ```
 * 状态族 chat.transfer.incoming.received 同卡片扩展：基础 1 → 最终 3 个背景（0x7f0803b7, 0x7f0803b4, 0x7f0803b3）
 * 覆盖写入率 100%（406/406）
 * ```
 * 但用户在微信里看到的仍是**原版卡片**，而「未领取」状态的同族卡片却是莫奈色。
 * 唯一能解释「同族资源写对了、这一个状态不生效」的机制是：宿主对**已领取/已过期**这两种状态
 * 是**运行期自己着色/换背景**（拿 ColorDrawable / setBackgroundColor / tint 覆盖掉资源），
 * 资源层再怎么写都会被它盖掉。
 *
 * 所以这里改走**视图层**（与遮盖层同款的「只碰已有 View、绝不 addView」铁律）：
 * hook 会话列表的每一次 bind，遇到「转账/红包 + 已领取/已过期」的 appmsg，
 * 就把它那张卡片自身的背景换成莫奈令牌下的圆角矩形。
 *
 * 安全约束（任一条不满足就什么都不做）：
 *  1. 只在莫奈已生效（[MonetColors.applied] 非空）时动手 —— 关掉莫奈就一定回到原版观感；
 *  2. 只处理 `wcpayinfo` 且 `paysubtype` 属于「领取后/过期」的 appmsg，其它消息一律不碰；
 *  3. 只改**已有子 View 的 background**，从不 addView / 从不改布局参数；
 *  4. 每个 View 只做一次（[skinned] 弱引用表），行被复用给别的消息时不会残留；
 *  5. 全程 runCatching —— 失败只是「这张卡还是原版」，绝不影响宿主列表。
 *
 * ## 【第 56 轮】为什么「自己发的已领取转账/红包还是原版」
 *
 * 第 55 轮把判据改成「状态词优先」——方向是对的，**但落点错了**：
 * `已被接收 / 已收款 / 已领取` 这类字样是宿主**渲染期**才画上去的 UI 文案，
 * 消息 XML（`field_content`）里根本没有 ⇒ 那把「状态词优先」的判定永远命中不了，
 * 实际生效的仍然只有 `paysubtype ∈ {2,3}`，于是：
 *  - 入账（对方发来的）领取后 subtype = 2/3 → 换卡成功（用户截图里蓝色的那张）；
 *  - 出账（自己发的）领取后 subtype 仍是 1 → **整条被子句挡在门外**（用户截图里橙色那张）。
 *
 * 本轮的修法是把判据搬到**渲染后的视图树上**，并给三层判据（任一命中即换）：
 *  1. 视图文案里出现「已被接收 / 已收款 / 已领取 / 已过期 / 已退还」——**与方向无关**，最可靠；
 *  2. `paysubtype ∈ {2,3}`（保留原有口径，入账方向继续生效）；
 *  3. 出账兜底：视图文案读到了内容、且**没有任何**「待领取 / 待对方确认 / 未领取 / 将退还」字样
 *     ⇒ 认为这张卡已经结算。（出账方向没有状态词的唯一可能是「待领取」，而待领取的卡片
 *     文案里一定有「待」类字样，所以这条兜底不会把「未领取」的卡片错杀。）
 *
 * 同时把**静默失败**全部改成有日志：找不到底板、判据不成立、换了几个 View，都打一行，
 * 下次实机日志能一眼看出是哪一层没过（第 52~55 轮的教训：静默 return 让三轮都在猜）。
 */
object MonetTransferCardSkin : ApiFeature(), IResolveDex {

    private const val TAG = "MonetTransferCardSkin"

    override val technicalId = "莫奈领取态卡片"
    override val nameRes = dev.joker.R.string.feature_monet_engine_name
    /**
     * ⚠️【第 54 轮·崩溃教训】必须是 **API 分类**，绝不能放进用户可见分类（BEAUTIFY 等）。
     *
     * 第一版放在 BEAUTIFY → 设置页的功能列表会把它当普通开关行渲染，而
     * `FeatureRow` 里对非 `SwitchFeature` 的功能做了裸强转
     * （`(item as SwitchFeature).setToggleCompletionCallback{…}`）→ 一进那个页面就
     * `ClassCastException: MonetTransferCardSkin cannot be cast to SwitchFeature` **闪退**。
     * 实机三份崩溃日志同一根因（R8 映射：tt6=本类、e9a=SwitchFeature）。
     * 现在 FeatureRow 已改成安全强转（`as?`），但**本功能本身也不需要开关** ——
     * 它是纯服务（`ApiFeature`，`startup()` 即生效），与 `BlockMessagesRuntime` 同类，归 API 分类。
     */
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.API)
    override val descriptionRes = dev.joker.R.string.feature_monet_engine_description

    /**
     * 说明：本对象是 [ApiFeature]（`startup()` 即 `enable()`，没有开关、也不需要 defaultEnabled ——
     * 上一版误加了 `override val defaultEnabled` 导致 CI 报 `'defaultEnabled' overrides nothing`）。
     * 它的实际生效条件是**运行期**的：只有莫奈已生效时才会动手，莫奈一关就完全无副作用
     * （[handleBind] 第一行 return，一个像素都不碰）。
     */

    /** `wcpayinfo.paysubtype`：1=待领取（宿主本来就跟莫奈色，不动），2/3=领取后/过期。 */
    private val CLAIMED_SUBTYPES = setOf("2", "3")

    private const val CARD_RADIUS_DP = 16f

    /**
     * 会话列表 `ChattingDataAdapterV3.getItem(int) -> 消息存储对象`。
     *
     * 与 [BlockedMessageMask] 用的是**同一个**宿主入口；本功能是 ApiFeature + IResolveDex，
     * 由 FeaturesLoader 统一解析（铁律：带 dex 委托的功能必须实现 IResolveDex）。
     */
    private val methodChattingAdapterGetItem by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ChattingDataAdapterV3")
            }
            name = "getItem"
            paramTypes(Int::class.java)
        }
    }

    /**
     * 行 → **我们打上去的那个背景**（弱引用键，行被回收后自动释放）。
     *
     * 【第 55 轮】以前存的是 msgId、且命中就**整行跳过**。实机反馈暴露了两个后果：
     *  ①「点一下卡片就退回微信原版」—— 宿主点击后会重新设置一次背景，而我们因为「这行做过了」
     *    不再补，直到下次滑动重新 bind 才恢复（用户描述完全吻合）；
     *  ②「自己发的已领取转账还是原版」—— 见 [handleBind] 里的三层判据。
     * 现在存的是 drawable 本体：每次 bind 都调用 [skin]，只有**当前背景还是我们那个**时才跳过，
     * 被宿主盖掉就自动补回来（自愈）。
     */
    private val skinned = WeakHashMap<View, Drawable>()

    override fun onEnable() {
        WeMessageApi.methodChattingDataAdapterOnBindViewHolder.hookAfter {
            val adapter = thisObject ?: return@hookAfter
            val holder = args.getOrNull(0) ?: return@hookAfter
            val position = args.getOrNull(1) as? Int ?: return@hookAfter
            runCatching { handleBind(adapter, holder, position) }
                .onFailure { WeLogger.d(TAG, "skip: ${it.javaClass.simpleName}") }
        }
    }

    override fun onDisable() {
        synchronized(skinned) { skinned.clear() }
    }

    private fun handleBind(adapter: Any, holder: Any, position: Int) {
        // 1) 莫奈没生效 → 一个像素都不碰
        if (MonetColors.applied.value == null) return
        val itemView = holder.reflekt()
            .firstField { name = "itemView"; superclass() }
            .get() as? View ?: return
        val instance = methodChattingAdapterGetItem.method.invoke(adapter, position) ?: return
        val info = MessageInfo(instance)
        val content = info.content
        // 只认付款卡片（转账 / 红包）。放宽到「任意一处付款标记」：不同版本、不同方向
        // 的 XML 字段并不完全一致，但这一串标记只要出现一个就一定是收付款卡片。
        if (!looksLikePayCard(content)) return
        val outgoing = info.isSend != 0
        val subtype = subtypeOf(content)
        // 2) 宿主是在这一帧之后才把卡片画成原版的 —— 等布局稳定再改；并且**每次 bind 都补**。
        //    另外再延迟补三次：宿主对付款卡片有「点击后重绘」的行为（用户实测点一下就回退），
        //    延迟补能在不依赖再次 bind 的情况下把它拉回来。只对付款卡片生效，开销可忽略。
        fun apply(round: Int) = runCatching {
            val viewed = viewedText(itemView)
            val claimedWord = CLAIMED_STATE_WORDS.firstOrNull { viewed.contains(it) }
            val claimedBySubtype = subtype != null && subtype in CLAIMED_SUBTYPES
            val claimedByDirection = outgoing && viewed.isNotEmpty() &&
                PENDING_STATE_WORDS.none { viewed.contains(it) }
            if (claimedWord == null && !claimedBySubtype && !claimedByDirection) {
                if (round == 0) {
                    WeLogger.d(
                        TAG,
                        "不换卡（未结清）：msg=${info.id} 方向=${direction(outgoing)} " +
                            "paysubtype=${subtype ?: "-"} 视图文案=「${viewed.take(40)}」",
                    )
                }
                return@runCatching
            }
            val reason = when {
                claimedWord != null -> "视图文案「$claimedWord」"
                claimedBySubtype -> "paysubtype=$subtype"
                else -> "出账方向无待领取文案"
            }
            skin(itemView, info.id, direction(outgoing), reason)
        }.onFailure { WeLogger.d(TAG, "skin failed: ${it.message}") }
        itemView.post { apply(0) }
        itemView.postDelayed({ apply(1) }, 120L)
        itemView.postDelayed({ apply(2) }, 480L)
        itemView.postDelayed({ apply(3) }, 1200L)
    }

    /**
     * 卡片是否**一定**是收付款卡片。
     *
     * 三个标记任一命中即可（都用小写比较，避免大小写差异）：不同微信版本上，
     * 出账/入账两个方向的 XML 字段名并不完全一致，只认 `<wcpayinfo>` 会让出账方向漏判。
     */
    private fun looksLikePayCard(content: String): Boolean {
        if (content.isEmpty()) return false
        val lower = content.lowercase()
        return lower.contains("wcpayinfo") || lower.contains("paysubtype") ||
            lower.contains("wcpayinfo>") || lower.contains("lucky")
    }

    private fun subtypeOf(content: String): String? =
        PAY_SUBTYPE.find(content)?.groupValues?.getOrNull(1)

    private fun direction(outgoing: Boolean) = if (outgoing) "我方" else "对方"

    /**
     * 把这张卡片自身的背景换成莫奈圆角（幂等、可被反复调用）。
     *
     * [reason] 只进日志：这轮必须能从实机日志反查「是哪一层判据命中的」。
     */
    private fun skin(root: View, msgId: Long, direction: String, reason: String) {
        val isNight = runCatching {
            (root.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }.getOrDefault(false)
        val tokens = MonetColors.tokens(isNight) ?: return
        val density = runCatching { root.resources.displayMetrics.density }.getOrDefault(2f)
        val primary = findCardBackground(root) ?: run {
            WeLogger.d(TAG, "找到付款卡片但没找到底板：msg=$msgId 方向=$direction（候选面积不足或全在 ImageView 上）")
            return
        }
        val night = tokens.night
        val targets = LinkedHashSet<View>()
        targets += primary
        // 宿主有时把颜色画在卡片内层的**同尺寸**整块 View 上（外层是透明容器）：
        // 一并换掉，避免「换了外层、里层还盖着一块方角色」。
        collectSameSizedChildren(primary, targets)
        var changed = 0
        for (target in targets) {
            val ours = synchronized(skinned) { skinned[target] }
            if (ours != null && target.background === ours) continue
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = CARD_RADIUS_DP * density
                setColor(if (night) tokens.primaryContainer else tokens.primaryContainer)
                setStroke((1f * density).toInt(), tokens.outline)
            }
            runCatching { target.background = shape }
            synchronized(skinned) { skinned[target] = shape }
            changed++
        }
        if (changed > 0) {
            WeLogger.d(
                TAG,
                "已把领取态卡片换成莫奈圆角（${if (night) "夜间" else "浅色"}）：msg=$msgId 方向=$direction " +
                    "判据=$reason 底板=${primary.javaClass.simpleName} 共换 $changed 个 View",
            )
        }
    }

    /**
     * 递归找「面积最大 + 自带 background」的子 View（即卡片底板）。
     *
     * 【第 56 轮】放开 ImageView：宿主给卡片上色/上九宫格时，**可以**把背景挂在 ImageView 上，
     * 旧实现把 ImageView 整类排除 —— 这正是「同一张卡、一个方向能换、另一个方向换不了」的
     * 候选原因之一。放开后靠「面积占比」兜住风险：头像/图标那点面积过不了 25% 这关。
     *
     * 两级阈值：
     *  1. 面积 ≥ 25% 行面积（正常卡片底板）；
     *  2. 首选找不到时，退到「宽度 ≥ 40% 行宽」的最大候选（卡片整体变窄/行变高时的兜底），
     *     仍然排除头像（头像宽度 ≈ 8~10% 行宽）。
     */
    private fun findCardBackground(root: View): View? {
        val candidates = ArrayList<View>(8)
        var guard = 0
        fun walk(view: View, depth: Int) {
            if (depth > 8 || guard > 240) return
            guard++
            if (view !== root && view.visibility == View.VISIBLE &&
                view.background != null && view.width > 0 && view.height > 0
            ) {
                candidates += view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        if (candidates.isEmpty()) return null
        val rowArea = root.width * root.height
        val rowWidth = root.width
        var best: View? = null
        var bestArea = 0
        for (view in candidates) {
            val area = view.width * view.height
            if (rowArea > 0 && area * 4 >= rowArea && area > bestArea) {
                bestArea = area
                best = view
            }
        }
        if (best != null) return best
        // 兜底：按宽度占比挑（面积阈值在「行特别高」的卡片上可能过不去）
        if (rowWidth > 0) {
            for (view in candidates) {
                if (view.width * 10 >= rowWidth * 4) {
                    val area = view.width * view.height
                    if (area > bestArea) {
                        bestArea = area
                        best = view
                    }
                }
            }
        }
        return best
    }

    /** 与 [parent] 同尺寸（宽度 ≥ 90%）的后代 View：宿主把卡片色画在内层时一并处理。 */
    private fun collectSameSizedChildren(parent: View, out: MutableSet<View>) {
        if (parent !is ViewGroup) return
        var guard = 0
        fun walk(view: View, depth: Int) {
            if (depth > 4 || guard > 60) return
            guard++
            if (view !== parent && view.visibility == View.VISIBLE && view.background != null &&
                view.width > 0 && parent.width > 0 && view.width * 10 >= parent.width * 9
            ) {
                out += view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
            }
        }
        walk(parent, 0)
    }

    /**
     * 把这一行里**已经渲染出来**的文案拼起来（`\u0001` 分隔，最多 320 字）。
     *
     * 为什么必须读视图而不是消息 XML：`已被接收 / 已收款 / 已领取` 全是宿主渲染期画的文案，
     * 消息体里没有（第 55 轮的「状态词优先」就是栽在这一点上）。限制长度与深度是为了
     * 这条热路径（只有付款卡片会走到这里）开销可以忽略。
     */
    private fun viewedText(root: View): String {
        val builder = StringBuilder(96)
        var guard = 0
        fun walk(view: View, depth: Int) {
            if (depth > 8 || guard > 160 || builder.length > 320) return
            guard++
            if (view is TextView) {
                val text = view.text?.toString()
                if (!text.isNullOrEmpty()) builder.append(text).append('\u0001')
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        return builder.toString()
    }

    private val PAY_SUBTYPE = Regex("<paysubtype>(\\d+)</paysubtype>")

    /** 结清状态词（视图文案，与消息方向无关）。 */
    private val CLAIMED_STATE_WORDS = listOf("已被接收", "已收款", "已领取", "已过期", "已退还")

    /** 「还没结清」的文案：出账方向的兜底判据靠它排除「待领取」的卡片。 */
    private val PENDING_STATE_WORDS = listOf(
        "待对方确认", "待确认", "待领取", "未领取", "未确认", "待收款", "将退还", "待接收",
    )
}
