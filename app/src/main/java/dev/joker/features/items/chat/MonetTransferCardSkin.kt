package dev.joker.features.items.chat

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
 */
object MonetTransferCardSkin : ApiFeature(), IResolveDex {

    private const val TAG = "MonetTransferCardSkin"

    override val technicalId = "莫奈领取态卡片"
    override val nameRes = dev.joker.R.string.feature_monet_engine_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = dev.joker.R.string.feature_monet_engine_description

    /**
     * 默认开：它**不修改任何宿主资源**，只在「莫奈已生效」时把领取态卡片的背景换成莫奈圆角；
     * 莫奈一关就完全无副作用（[handleBind] 第一行就 return，一个像素都不碰）。
     */
    override val defaultEnabled: Boolean = true

    /** 「已领取 / 已过期」的 `wcpayinfo.paysubtype`：1=待领取（宿主本来就跟莫奈色，不动），2/3=领取后/过期。 */
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

    /** 已经处理过的行（弱引用键，行被回收后自动释放）。 */
    private val skinned = WeakHashMap<View, Long>()

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
        if (content.isEmpty() || !content.contains(PAY_TAG)) return
        if (!isClaimedState(content)) return
        val msgId = runCatching { info.id }.getOrDefault(0L)
        synchronized(skinned) {
            if (skinned[itemView] == msgId && msgId != 0L) return
            skinned[itemView] = msgId
        }
        // 2) 宿主是在这一帧之后才把卡片画成原版的 —— 等布局稳定再改。
        itemView.post { runCatching { skin(itemView) }.onFailure { WeLogger.d(TAG, "skin failed: ${it.message}") } }
    }

    /** 是否是「领取后 / 已过期」的收付款卡片。 */
    private fun isClaimedState(content: String): Boolean {
        val subtype = Regex("<paysubtype>(\\d+)</paysubtype>").find(content)?.groupValues?.getOrNull(1)
        if (subtype != null) return subtype in CLAIMED_SUBTYPES
        // 没有 paysubtype（旧版/其它 appmsg）：只认「已被接收 / 已收款 / 已过期」这些状态词。
        return CLAIMED_STATE_WORDS.any { content.contains(it) }
    }

    /**
     * 把这张卡片自身的背景换成莫奈圆角。
     *
     * 找法：在这行里挑「面积最大、自己有 background」的那个非图片子 View —— 转账/红包卡片是
     * 一行里唯一的整块背景，稳定命中；找不到就什么都不做（宁可保持原版，也不乱改别的 View）。
     */
    private fun skin(root: View) {
        val isNight = runCatching {
            (root.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }.getOrDefault(false)
        val tokens = MonetColors.tokens(isNight) ?: return
        val density = runCatching { root.resources.displayMetrics.density }.getOrDefault(2f)
        val target = findCardBackground(root) ?: return
        val night = tokens.night
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CARD_RADIUS_DP * density
            setColor(if (night) tokens.primaryContainer else tokens.primaryContainer)
            setStroke((1f * density).toInt(), tokens.outline)
        }
        runCatching { target.background = shape }
        WeLogger.d(TAG, "已把领取态卡片换成莫奈圆角（${if (night) "夜间" else "浅色"}）")
    }

    /** 递归找「面积最大 + 自带 background」的非图片子 View（即卡片底板）。 */
    private fun findCardBackground(root: View): View? {
        var best: View? = null
        var bestArea = 0
        var guard = 0
        fun walk(view: View, depth: Int) {
            if (depth > 8 || guard > 200) return
            guard++
            if (view !== root && view !is ImageView && view.visibility == View.VISIBLE &&
                view.background != null && view.width > 0 && view.height > 0
            ) {
                val area = view.width * view.height
                if (area > bestArea) {
                    bestArea = area
                    best = view
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        // 至少要占这一行 25% 的面积才认（太小的是角标/图标底板）
        val rowArea = root.width * root.height
        return if (rowArea > 0 && bestArea * 4 >= rowArea) best else null
    }

    private const val PAY_TAG = "<wcpayinfo>"

    private val CLAIMED_STATE_WORDS = listOf("已被接收", "已收款", "已领取", "已过期", "已退还")
}
