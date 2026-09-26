package dev.ujhhgtg.wekit.features.items.chat.jev.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Toast
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.ChatInsights
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.JevProtocol
import dev.ujhhgtg.wekit.features.items.chat.jev.analysis.SignalAnalyzer
import dev.ujhhgtg.wekit.features.items.chat.jev.core.AnalysisInput
import dev.ujhhgtg.wekit.features.items.chat.jev.core.JevText
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.Mood
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodBar
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.features.items.chat.jev.core.dominantName
import dev.ujhhgtg.wekit.utils.monet.MonetColors
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 分析卡的渲染层。
 *
 * ## 第 19 轮：改成「零宿主子 View 注入 + 宿主列表 overlay 直绘」
 *
 * ### 为什么要推翻上一版
 *
 * 上一版把卡片作为子 View 追加进宿主行内部的那个纵向 LinearLayout。它确实没往
 * 宿主 RecyclerView 直接 addView，但**改了宿主行的 View 树结构**（行内容器多一个 child、
 * 行高随之变化）。实机崩溃栈正是这一类「宿主按预期结构取子 View」被破坏后的典型表现：
 *
 * ```
 * java.lang.RuntimeException: Attempt to invoke virtual method 'int android.view.View.getId()'
 *         on a null object reference
 *   at com.tencent.mm.pluginsdk.ui.tools.q3.onCreateViewHolder(SourceFile:55)
 *   at com.tencent.mm.ui.chatting.layoutmanager.ChattingLinearLayoutManager
 *         .scrollVerticallyBy(SourceFile:6)
 * Caused by: java.lang.NullPointerException
 *   at com.tencent.mm.ui.chatting.viewitems.si.<init>(SourceFile:626)
 *   at com.tencent.mm.ui.chatting.viewitems.lp.F(SourceFile:13)
 *   at com.tencent.mm.view.recyclerview.WxRecyclerAdapter.r0(SourceFile:17)
 * ```
 *
 * 滚动时宿主新建 ViewHolder（复用被我们改过的行视图），构造里拿不到预期的子 View → NPE。
 * 所以这一版的原则是：**宿主行的子 View 一个都不增、一个都不删、不占下标、不改 id、
 * 不改 tag、不改 LayoutParams**。只有「预留空间」和「在宿主自己的画布上画」两件事。
 *
 * ### 现在怎么画（两条合法路里的 overlay 直绘）
 *
 * 1. **预留空间**：只改宿主行内容器的 `paddingBottom`。容器高度是 wrap_content，
 *    所以行会像以前一样长高，「卡片落在消息下方、下一条消息不被盖住」由宿主自己的
 *    布局保证；恢复时把 padding 写回原值即可。
 * 2. **绘制**：卡片本体是一个纯 [Drawable]，挂到宿主列表自己的 `ViewOverlay`。
 *    `android.view.ViewOverlay` / `android.graphics.drawable.Drawable` 都是 **framework 类**，
 *    与宿主进程里那一份共享同一个类身份（这一点很关键：`androidx.recyclerview.*` 是宿主
 *    自带库，我们**不能**引用它，见 [findList] 的注释），所以能安全挂上去，
 *    宿主每次绘制列表时会把我们一并画进去。
 * 3. **坐标**：overlay 的绘制坐标系就是列表自身的坐标系，`view.top` 逐级累加到列表即可
 *    得到行当前位置。位置**每帧现算**，因此不需要滚动监听、不需要垂直同步回调、
 *    不需要维护任何帧间状态 —— 列表滚到哪里，卡片就画到哪里，零额外卡顿。
 * 4. **交互**：卡片落在内容器的 padding 留白里，那片区域没有任何宿主子 View，
 *    因此点击/长按直接用内容器自己的 click/longClick；气泡上的手势完全不受影响。
 * 5. **排版**：文本一律走 [StaticLayout]，只在「内容变了 / 宽度变了 / 配置变了」时重排；
 *    每帧绘制只有 `drawRoundRect` / `layout.draw` / `drawText`，没有 View 测量、
 *    没有 View 绘制、没有对象分配。
 *
 * 卡片形态（第 17 轮起的信息层级，本轮把每个块的视觉权重重新排了一遍）：
 * ```
 *    ┃ ● 潜语 · 平静  · 较前几句 ↑12          ← 情绪色点 + 品牌（压暗）/ 主情绪（放大加粗）/ 趋势（跟随走向色）
 *    ┃ 平静 ▓▓▓▓▓▓░░░░ 59%                  ← 收起只给主情绪一条（更高更亮），展开给全概率
 *    ┃ 邀约安排 · 等具体安排 · 消息 09-26 14:03  ← 场景 / 阶段 / 本条时间（模型原文 + 本地时间戳）
 *    ┃ 〔建议：稳步回复〕〔置信度 62%〕〔强度 +0.21〕 ← 标签行：建议强度 / 置信度 / 情绪强度
 *    ┃ 解读：这句可能在给见面留位置              ← 解读（标签加粗 + 情绪色）
 *    ┃ ▍建议：顺着刚提到的事，问一个还没说的细节   ← 建议块（带底色 + 左侧强调条）
 *    ┃ 建议：信号中性，正常回应即可               ← 扩展：强度说明 / 风险依据
 *    ┃ 〔见面〕〔时间〕                        ← 扩展：话题标签（圆角小标签、最多两行）
 *    ┃ 情绪趋势：近 6 条在变好 · 均值 0.21 · 波动 0.12
 *    ┃ ╱╲＿╱ ‾ ╲＿                          ← 扩展：情绪走势迷你曲线（零轴 + 末点）
 *    ┃ 互动均衡（本屏 12 条）                 ← 展开后：双方话量对比（真正画成横幅）
 *    ┃ ────────────────────────────────
 *    ┃ ▾ 点击展开完整解读 · 长按复制            ← 交互提示 / 降级说明
 * ```
 *
 * 造型与配色的三条约束（本轮新增，别破坏）：
 *  - **颜色一律来自 [MonetColors] 色板**：底色是「容器色 → 容器高亮色」的极淡竖向渐变，
 *    描边是同色系 + 少量强调色，投影用正文色的极低 alpha（浅色下是暗影、深色下是浮起感），
 *    没有任何硬编码的品牌色；
 *  - **渐变 / 路径只在排版期构建**（[LinearGradient]、[Path]），绘制期只做
 *    `drawRoundRect` / `drawPath` / `drawText`，每帧零分配、零布局、无硬件层；
 *  - **不用动画换注意力**：没有按帧 `invalidate` 的脉冲/闪烁，静态层级本身就足够读，
 *    滚动时不会因为这张卡掉帧。
 *
 * 铁律（这一版全部满足，别再退回子 View 注入）：
 *  - 绝不向宿主 RecyclerView（或宿主任意行、容器）增删子 View；
 *  - 渲染失败一律降级为「这张卡不画」，绝不抛异常、绝不影响宿主与其他功能；
 *  - 逐 bind / 逐帧诊断日志一律走 `MoodLog`（`verboseEnabled` 门闩）。
 */
object YanwaiBubble {

    // ------------------------------------------------------------------ 几何常量（dp / sp）

    /** 内容左边界：左侧情绪指示条 10dp + 5dp 留白。 */
    private const val PAD_LEFT_DP = 15f
    private const val PAD_RIGHT_DP = 12f
    private const val PAD_TOP_DP = 10f
    private const val PAD_BOTTOM_DP = 10f

    /** 情绪指示条：不贴边、画成一根竖向药丸，避免与卡片圆角打架。 */
    private const val STRIPE_LEFT_DP = 7f
    private const val STRIPE_WIDTH_DP = 3f
    private const val STRIPE_INSET_DP = 9f

    private const val CARD_RADIUS_DP = 14f
    private const val CARD_STROKE_DP = 1f

    /**
     * 投影：向下偏移 1.5dp、往下 3dp 内由「正文色低 alpha」渐隐到全透明。
     *
     * 这是不引硬件层、不用 blur 的「伪阴影」：一段竖向渐变就够表达 1dp 的高度差，
     * 深色下用更低的 alpha（亮底上的暗影与暗底上的浮起感需要不同强度）。
     */
    private const val SHADOW_OFFSET_DP = 1.5f
    private const val SHADOW_LENGTH_DP = 3f
    private const val SHADOW_ALPHA_LIGHT = 22
    private const val SHADOW_ALPHA_DARK = 18

    /** 卡片底色渐变：从容器色到「容器色 + 一点容器高亮色」，纯色块会显得很平。 */
    private const val CARD_GRADIENT_RATIO = 0.45

    /** 标题行前的情绪色点。 */
    private const val DOT_SIZE_DP = 6f
    private const val DOT_GAP_DP = 6f

    /** 情绪走势迷你曲线取最近多少段（跟 [ChatInsights] 的趋势窗口不同：这里只为了看形状）。 */
    private const val SPARK_SAMPLES = 12

    /** 卡片与列表左/右边缘的留白。 */
    private const val SIDE_MARGIN_DP = 10f

    /** 超宽屏（平板/横屏）下不让卡片拉满整屏。 */
    private const val MAX_CARD_WIDTH_DP = 560

    /** 卡片与下一条消息之间的留白。 */
    private const val BOTTOM_GAP_DP = 6f

    /** 卡片底边与「预留区底边」之间再留一点，卡片不会贴着下一条消息。 */
    private const val CARD_EDGE_GAP_DP = 2f

    /** 排版结果缓存条数：一次会话一屏十几张卡，来回滚动靠它免掉重复排版。 */
    private const val MAX_CACHED_LAYOUTS = 48

    /** 行内容器找不到（未知气泡布局）后的放弃阈值，避免每拍重试。 */
    private const val MAX_LANDING_ATTEMPTS = 6

    /** 补预留的延后一拍（约一帧）：等宿主本帧的布局走完再改 padding，避免嵌在布局里再触发一次布局。 */
    private const val REPAIR_DELAY_MS = 16L

    /** 连续多少帧「位置没就绪」后记一行诊断（约 1 秒；只记一行，不刷屏）。 */
    private const val MAX_NO_ROOM_FRAMES = 60

    /** 行已绑定但还没绘制出来（可重试）的卡片。 */
    private val cards = IdentityHashMap<View, Card>()

    /**
     * 排版结果缓存：[Fingerprint]（身份 + 一切影响画面的输入）→ 排好版的指令表。
     *
     * 一屏卡片会随着滚动反复绑定/解绑，重扫每拍也会重算一次指纹 ——
     * 没有这层缓存，每次都要重跑 [buildCard]（十几次 StaticLayout + 若干 Path/Shader）。
     * 有它之后，「同样的身份 + 同样的指纹」直接复用同一份 [CardLayout]：
     * 滚动来回、重绑、每拍重试，主线程零排版成本。
     *
     * 访问序 LRU（[removeEldestEntry]）：容量固定，绝不随会话数/消息数增长。
     */
    private val layouts = object : LinkedHashMap<Fingerprint, CardLayout>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Fingerprint, CardLayout>?) =
            size > MAX_CACHED_LAYOUTS
    }

    /** 补齐预留用的主线程节拍（一次消息一拍，16ms 后跑）。 */
    private val main = Handler(Looper.getMainLooper())
    private var repairPosted = false

    /** 宿主列表 -> 挂在它 overlay 上的卡片层（WeakHashMap：列表销毁后自动放手）。 */
    private val layers = WeakHashMap<View, CardLayer>()

    /** 记录过的「画不了」签名：同一类宿主布局只记一条日志。 */
    private val unsupported = HashSet<String>()

    /** 记录过的「降级/异常」签名：绘制与排版路径都在热点上，同一类问题只允许记一行。 */
    private val warned = HashSet<String>()

    /** 已经打过一次「首批卡片已绘制」诊断。 */
    private var drewOnce = false

    /**
     * 一张卡片的全部状态。
     *
     * 宿主侧只保留「我改过什么」以便完整还原：padding 原值、clickable 原值、监听器。
     */
    private class Card(val row: View, var key: String, val identity: String) {
        /**
         * 这张卡归属的那条消息的**本地 msgId**（>0 才允许画）。
         *
         * 卡片与消息的唯一绑定标识。宿主行、行内容器、列表位置都会在滚动中被复用/换绑，
         * 只有 msgId 能说明「这张卡属于哪一条消息」；拿不到（<=0）就整条放弃 ——
         * 绝不用行位置/下标/时间近似去猜归属（那正是「卡片串来串去」的来源）。
         */
        var messageId: Long = 0L

        /** 归属会话（诊断与日志用）。 */
        var talker: String = ""

        var input: AnalysisInput? = null
        var note: String? = null
        var screen: ChatInsights.Screen = ChatInsights.Screen()
        var capacityPending = false
        var expanded: Boolean = ModulePrefs.cardExpanded

        /** 承载绘制的宿主列表视图（overlay 宿主）。 */
        var list: View? = null

        /** 被预留空间、并接收点击的宿主行内容器。 */
        var container: View? = null

        /**
         * 卡片纵向落点的锚：容器里**直接装气泡**的那一个子 View。
         *
         * 卡片位置以「锚的底边 + 间距」为准，而不是「容器底边 - paddingBottom」：
         * 后者在「padding 已改、但容器还没按新 padding 重新测量」的那一帧里会算到
         * 容器内容区里（把卡片压到气泡上），而前者在任何一帧都指向真实的气泡底边。
         */
        var anchor: View? = null

        /** 我们要求容器保持的 paddingBottom（[reserve] 写入，绘制前据它校验预留是否还在）。 */
        var wantPadding: Int = -1

        /** 连续多少帧因为「位置没就绪」跳过绘制（仅用于记一行诊断）。 */
        var noRoomFrames = 0

        /** padding / 监听器是否已由我们装上（[release] 据此还原）。 */
        var hooked = false
        var origPaddingBottom = 0
        var origClickable = false
        var origLongClickable = false

        /** 点击/长按监听器是**我们装的**才由 [release] 摘下；宿主自己的监听器一律不碰。 */
        var hookedClick = false
        var hookedLongClick = false

        /** 已经预留的高度（px），仅用于日志与断言。 */
        var reserved = 0

        /** 找不到落点时的重试次数；超过阈值后不再重试。 */
        var landingAttempts = 0
        var blocked = false

        var width = 0
        var fingerprint: Fingerprint? = null

        /** 排好版的卡片（画的时候直接用，不需要重新测量）。 */
        var layout: CardLayout? = null

        /** 内容与落点都就绪，可以画了。 */
        val ready: Boolean get() = hooked && layout != null
    }

    /**
     * 渲染指纹：任何会影响画面的输入都放进来，只有它变了才重排。
     *
     * 两条纪律（都是「整屏重排」踩出来的）：
     *  1. **不含全局队列长度**：队列每出一条结论就 +1，会让全屏卡片一起重排重画 —— 这正是
     *     用户实测的「决策分析造成卡顿」的主因之一。真正会打印排队条数的只有
     *     「正在排队」那种卡（state = 'p'），所以 [queued] 只在那一种状态下参与比较，
     *     而且按 5 取整（见 [queuedBucket]）。
     *  2. **走势版本必须按会话**（[MoodStore.trendVersionOf]）：全局走势版本号同样是
     *     「一条消息出结论 → 全屏卡片重排」，本质上是第 1 条的翻版。
     *  3. **归属用 [identity]（会话 + 消息 id），不用结论键 `key`**：结论键把上下文一起
     *     哈希了，旁边一来新消息就整屏换键 —— 卡片会被误判成「换人」而拆掉重建。
     */
    private data class Fingerprint(
        val identity: String,
        val createdAt: Long,
        val state: Char,
        val moodId: Int,
        val failure: String?,
        val note: String?,
        val expanded: Boolean,
        val night: Boolean,
        val trend: Int,
        val queued: Int,
        val capacity: Boolean,
        val screenId: Int,
        val uiRevision: Int,
        val paletteId: Int,
        val width: Int,
    )

    private class CardLayout(val width: Int, val height: Int, val ops: List<Op>)

    // ------------------------------------------------------------------ 绘制指令（排版期生成，绘制期零分配）

    private sealed class Op

    /** 圆角矩形（`strokeWidth > 0` 时画描边；`color` 给透明值就是纯描边）。 */
    private class RectOp(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val color: Int,
        val radius: Float,
        val strokeWidth: Float = 0f,
        val strokeColor: Int = 0,
    ) : Op()

    /**
     * 带渐变的圆角矩形（卡片底色 / 投影 / 左侧指示条）。
     *
     * 渐变对象在**排版期**建一次（[LinearGradient] 构造会分配，不能每帧建），
     * 绘制期只是把它挂到复用的 Paint 上，仍然零分配。
     */
    private class GradientOp(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radius: Float,
        val shader: Shader,
    ) : Op()

    /** 已经是路径的形状（左侧指示条、情绪曲线的填充区）。 */
    private class PathOp(val path: Path, val color: Int) : Op()

    /** 描边路径（情绪曲线本体）：线宽在排版期定好，绘制期只是换掉 Paint 的宽度。 */
    private class LineOp(val path: Path, val color: Int, val width: Float) : Op()

    private class TextOp(val layout: Layout, val x: Float, val top: Float) : Op()

    /** 情绪概率 / 互动均衡横条：标签 + 轨道 + 填充 + 百分比。 */
    private class BarOp(
        val top: Float,
        val barHeight: Float,
        val barLeft: Float,
        val barRight: Float,
        val labelX: Float,
        val percentX: Float,
        val baseline: Float,
        val textSize: Float,
        val label: String,
        val percent: Int,
        val highlight: Boolean,
        /** 主情绪行的标签加粗（互动均衡那两条不加粗，避免两条都抢眼）。 */
        val bold: Boolean,
        val accent: Int,
        val labelColor: Int,
        val muted: Int,
        val track: Int,
    ) : Op()

    /** 圆角小标签（建议强度 / 置信度 / 情绪强度 / 话题）。 */
    private class ChipOp(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radius: Float,
        val background: Int,
        /** 1dp 描边：深浅两套底色下都能看清边界。 */
        val border: Int,
        val borderWidth: Float,
        val text: String,
        val textColor: Int,
        val textSize: Float,
        val textX: Float,
        val baseline: Float,
    ) : Op()

    // ------------------------------------------------------------------ 绘制（每帧调用，零分配）

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 挂在宿主列表 overlay 上的卡片层。
     *
     * [Drawable] 是 framework 类，宿主列表的 `ViewOverlay` 认它；`draw` 收到的 canvas
     * 就在**列表自己的坐标系**里，所以这里每帧现算每张卡的位置。
     */
    private class CardLayer(private val render: (Canvas) -> Unit) : Drawable() {
        override fun draw(canvas: Canvas) {
            render(canvas)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ------------------------------------------------------------------ 对外入口

    /**
     * 画/刷新一张卡片。返回 true 表示这一行已经由本模块接手（不论是否已经画出来）。
     *
     * 与上一版不同：这里**不再因为「行还没测量」而返回 false** —— 卡片内容是先排好版、
     * 再按宿主列表宽度预留空间的，行什么时候测量完都不影响它最终出现。
     */
    fun show(
        row: View,
        message: AnalysisInput?,
        note: String? = null,
        screen: ChatInsights.Screen = ChatInsights.Screen(),
        capacityPending: Boolean = false,
    ): Boolean {
        if (message == null || !ModulePrefs.enabled || !ModulePrefs.displayBubble ||
            !ModulePrefs.inScope(message.talker)
        ) {
            clear(row)
            return false
        }
        // ---------------- 归属闸门（唯一的写入口，所有调用方都被它保护） ----------------
        // 卡片必须按「消息唯一标识」绑定：
        //  1. 拿不到稳定标识（msgId <= 0 的本地暂态消息）：**放弃这一条**，绝不用位置/时间猜；
        //  2. 这一行现在绑的不是这条消息（宿主已把它复用给别的消息）：先摘掉旧卡再说。
        // 少了这道闸门，回填路径（结论落地时按 View 缓存回填）会把上一条消息的卡片
        // 画到已经被复用给新消息的那一行上，也就是用户看到的「卡片串来串去」。
        if (message.messageId <= 0L || rowMessageId(row) != message.messageId) {
            clear(row)
            return false
        }
        var card = cards[row]
        // 归属按**会话 + 消息 id**（[AnalysisInput.identity]）判定：
        // 同一条消息哪怕结论键变了（上下文变了、重试了），卡片与它预留的空间都原地保留，
        // 不拆不建 —— 拆建一次就是「还原 padding + 再预留一次」，两次改宿主几何、
        // 两次 requestLayout，全屏卡片一起拆的时候就是肉眼可见的一卡。
        if (card != null && card.identity != message.identity) {
            // 宿主行被复用成另一条消息：先还原它对容器的改动，再按新身份重建
            release(card)
            card = null
        }
        if (card == null) {
            card = Card(row, message.key, message.identity)
            cards[row] = card
        }
        // 结论键随上下文变，卡片每次都要跟上（结果查询/点击重试都按它取）
        card.key = message.key
        card.input = message
        card.messageId = message.messageId
        card.talker = message.talker
        card.note = note
        card.screen = screen
        card.capacityPending = capacityPending
        if (prepare(card)) render(card)
        return true
    }

    /**
     * 这一行**当前**绑定的消息本地 msgId；拿不到稳定标识（没绑定 / msgId <= 0）返回 null。
     *
     * 数据源是宿主 onBindView 写下的绑定表（[WeChatMessageViewApi]）—— 那是「这一行现在
     * 是谁」的唯一真相。列表位置、下标、时间戳、文本哈希都不能拿来判定卡片归属：
     * 行会被复用、会被换绑，用它们就是在猜。
     */
    private fun rowMessageId(row: View): Long? {
        val message = WeChatMessageViewApi.getBoundMessage(row) ?: return null
        val id = try {
            message.id
        } catch (t: Throwable) {
            0L
        }
        return if (id > 0L) id else null
    }

    /**
     * 队列深度对指纹的影响：**只影响「正在排队」那张卡**，而且按 5 取整。
     *
     * 全局队列长度绝不能无条件进指纹（见 [Fingerprint]）：这里只让「排队中」这类卡感知
     * 深度，而且每 5 条才可能变一次 —— 与「每出一条结论全屏卡片一起重排」完全不是一个量级。
     */
    private fun queuedBucket(): Int {
        val depth = MoodStore.pendingCount()
        return if (depth <= 1) depth else ((depth + 4) / 5) * 5
    }

    /** 同一类降级/异常只记一行（日志每行都要落一次磁盘，而这条降级路径每拍都会被重试）。 */
    private fun warnOnce(signature: String, message: String) {
        if (warned.add(signature)) MoodLog.w(message)
    }

    /** 卡片随行解绑/回收一起消失，并把宿主的 padding / 监听器还原。 */
    fun clear(row: View) {
        val card = cards.remove(row) ?: return
        release(card)
    }

    fun clearAll() {
        for (card in cards.values) release(card)
        cards.clear()
        detachStaleLayers()
        runCatching { main.removeCallbacks(repair) }
        repairPosted = false
        layouts.clear()
    }

    /**
     * 离开会话（当前可见会话换了人）时收摊：**别的会话**的卡片、缓存与已失效的层全清掉。
     *
     * 卡片与缓存原来只按行 View / 指纹挂着，换会话后旧会话的东西要等宿主把这些行全部
     * 回收/复用才逐个消失 —— 中间这段时间里，从旧列表 overlay 上画的卡片就可能被用户
     * 看成「卡片跑到别的聊天上去了」。这里按会话一次性收干净，代价是 O(卡片数)。
     */
    fun endSession(keepTalker: String?) {
        if (keepTalker == null) {
            clearAll()
            return
        }
        for (row in cards.keys.toList()) {
            val card = cards[row] ?: continue
            if (card.talker != keepTalker) clear(row)
        }
        val prefix = "$keepTalker#"
        val iterator = layouts.keys.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().identity.startsWith(prefix)) iterator.remove()
        }
        detachStaleLayers()
    }

    /** 摘掉宿主列表已经不在窗口上的卡片层（同时把 overlay 上那一份也 remove，别留悬挂层）。 */
    private fun detachStaleLayers() {
        for ((list, layer) in layers.entries.toList()) {
            if (list.isAttachedToWindow && cards.values.any { it.list === list }) continue
            runCatching { list.overlay.remove(layer) }
            layers.remove(list)
        }
    }

    /**
     * 清理不可见的卡片，并补做「还没画出来」的卡片（落点/宽度/绘制通道晚一点才可用的那些）。
     *
     * @return 是否还有卡片需要继续跑兜底节拍 —— 扫描器据此决定要不要继续排下一拍，
     *         因此这里必须如实返回（宁可多跑几拍，也不要留下「既没画出来也没人管」的卡）。
     */
    fun prune(): Boolean {
        for (row in cards.keys.toList()) {
            if (!row.isAttachedToWindow) clear(row)
        }
        detachStaleLayers()
        if (cards.isEmpty()) return false
        var pending = false
        for (card in cards.values) {
            if (card.blocked || card.ready) continue
            if (!card.row.isAttachedToWindow) continue
            // 落点/列表宽度/结论可能是刚刚才可用的：这里**强制**重算一次指纹与排版。
            // （旧实现是把 card.fingerprint 置空来"逼"重排 —— 语义一样，但那样会让
            //  「缓存命中」这条快路径在每拍重试时也走不到，白付一次 StaticLayout。）
            if (prepare(card)) render(card, force = true)
            if (!card.ready) pending = true
        }
        return pending
    }

    /**
     * 绘制时发现「预留没生效」（宿主把 padding 写回去了、或行还没按新 padding 测量）时，
     * 延后一帧补一次预留。
     *
     * 只发一条主线程消息、只改 padding，**不进宿主布局/绘制调用栈** ——
     * 绘制路径里改宿主几何是最容易把宿主画崩/画乱的做法，这里刻意避开。
     */
    private fun scheduleRepair() {
        if (repairPosted) return
        repairPosted = true
        main.postDelayed(repair, REPAIR_DELAY_MS)
    }

    private val repair = Runnable {
        repairPosted = false
        try {
            for (card in cards.values) {
                val container = card.container ?: continue
                val layout = card.layout ?: continue
                if (!card.row.isAttachedToWindow || !container.isAttachedToWindow) continue
                if (card.wantPadding < 0 || container.paddingBottom >= card.wantPadding) continue
                reserve(card, layout.height)
                if (card.hooked) hookClicks(card)
                card.list?.let { runCatching { it.postInvalidateOnAnimation() } }
            }
        } catch (t: Throwable) {
            warnOnce("repair:${t.javaClass.simpleName}", "卡片预留修复异常（已跳过）：${t.javaClass.simpleName} ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 落点：只改 padding，不动子 View

    /**
     * 找落点（宿主行内容器 + 承载绘制的宿主列表），并预留卡片高度。
     *
     * 落点规则与上一版一致（同一套宿主布局判定），只是**不再往里 addView**：
     *  - 优先：从气泡往上找到的第一个 `vertical LinearLayout`（宿主行里装气泡的那一列）；
     *  - 退路：行根 `RelativeLayout`（未知布局时的兜底）；
     * 两者都要求 `height = wrap_content` —— 只有它们会跟着内容长高，卡片才有地方落。
     */
    private fun prepare(card: Card): Boolean {
        val row = card.row
        if (card.blocked) return false
        var container = card.container
        if (container == null || container.parent == null || !container.isAttachedToWindow) {
            // 落点失效（行被重建/换绑）：先还原旧容器的改动，再重新找
            if (card.hooked) release(card)
            card.container = null
            card.anchor = null
            card.list = null
            val landing = findLanding(row)
            if (landing == null) {
                card.landingAttempts++
                if (card.landingAttempts >= MAX_LANDING_ATTEMPTS) {
                    card.blocked = true
                    val root = row as? ViewGroup
                    val signature = "${root?.javaClass?.name}/${findBubble(root)?.parent?.javaClass?.name}"
                    if (unsupported.add(signature)) {
                        MoodLog.w("暂不绘制未知气泡布局：$signature")
                    }
                }
                return false
            }
            card.landingAttempts = 0
            container = landing.container
            card.container = container
            card.anchor = landing.anchor
        }
        if (card.list == null) {
            // overlay 宿主：从行的父链往上找那个 RecyclerView 类的视图
            card.list = findList(row) ?: run {
                card.landingAttempts++
                if (card.landingAttempts >= MAX_LANDING_ATTEMPTS) card.blocked = true
                return false
            }
        }
        attachLayer(card.list!!)
        return true
    }

    /**
     * 在宿主列表的 overlay 上装一层卡片绘制层（每个列表只装一次）。
     *
     * 这里用 `View.overlay`（framework 的 `ViewOverlay`）而不是 `RecyclerView.addItemDecoration`：
     * 前者不参与宿主布局、不引用宿主自带的 `androidx.recyclerview` 类，只借用宿主自己的画布。
     */
    private fun attachLayer(list: View) {
        if (layers.containsKey(list)) return
        // **不能**让绘制层强引用列表：WeakHashMap 的 value 强引用 key 时，key 永远不会被回收，
        // 条目与它捕获的整棵旧聊天视图会一直活着（聊天进进出出就是一条稳定的泄漏）。
        val ref = WeakReference(list)
        val layer = CardLayer { canvas -> ref.get()?.let { drawCards(it, canvas) } }
        val added = runCatching { list.overlay.add(layer) }.isSuccess
        if (!added) return
        layers[list] = layer
        if (MoodLog.verbose) MoodLog.i("卡片层已挂到宿主列表 ${list.javaClass.simpleName}")
    }

    /**
     * 从行视图往上找承载它的列表视图。
     *
     * **不能**写成 `is RecyclerView`：`androidx.recyclerview.widget.RecyclerView` 是宿主 APK
     * 自带的库类，与本进程里我们能引用的那一份不是同一个类身份（WeKit 也刻意不链接它），
     * 所以只按类名/方法签名判断（与删除动画那边同一个做法）。
     */
    private fun findList(view: View): View? {
        var current = view.parent as? View
        while (current != null) {
            if (isRecyclerViewLike(current)) return current
            current = current.parent as? View
        }
        return null
    }

    private fun isRecyclerViewLike(view: View): Boolean {
        val cls = view.javaClass
        val name = cls.name
        if (name.startsWith("androidx.recyclerview.widget")) return true
        if (name.contains("RecyclerView")) return true
        return synchronized(recyclerLookup) {
            recyclerLookup.getOrPut(cls) {
                generateSequence(cls as Class<*>) { it.superclass }.any { c ->
                    c.declaredMethods.any { it.name == "getScrollState" && it.parameterCount == 0 } &&
                        c.declaredMethods.any { it.name == "getAdapter" && it.parameterCount == 0 }
                }
            }
        }
    }

    private val recyclerLookup = HashMap<Class<*>, Boolean>()

    /** 落点：改 padding 的那个容器 + 卡片纵向对齐用的锚（容器里直接装气泡的那个子 View）。 */
    private class Landing(val container: View, val anchor: View)

    private fun findLanding(row: View): Landing? {
        val root = row as? ViewGroup ?: return null
        val anchor = findBubble(root) ?: return null
        var branch: View = anchor
        var parent = branch.parent as? ViewGroup
        while (parent != null && isInside(parent, root)) {
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL &&
                parent.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT
            ) {
                return Landing(parent, branch)
            }
            if (parent === root) break
            branch = parent
            parent = branch.parent as? ViewGroup
        }
        // 未知气泡布局的退路：行根 RelativeLayout 自己（同样只动 padding，不动子 View）
        if (root is RelativeLayout && branch.parent === root &&
            root.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT
        ) {
            return Landing(root, branch)
        }
        return null
    }

    /**
     * 预留卡片高度：改宿主内容器的 `paddingBottom`，并把卡片区域的点击接过来。
     *
     * 只改这两个属性，**不增删任何子 View**：容器高度是 wrap_content，因此行会像以前
     * （把卡片作为子 View 追加时）一样精确长高相同的量，而宿主的 ViewHolder 构造、
     * 子 View 下标、id、tag、LayoutParams 全都不受影响。
     *
     * 写进去的目标值同时记在 [Card.wantPadding] 上：绘制前拿它校验「预留还在不在」——
     * 宿主自己也在这条内容器上写 padding，被改回去的那一帧绝不能画（见 [drawCards]）。
     */
    private fun reserve(card: Card, height: Int) {
        val container = card.container ?: return
        if (!container.isAttachedToWindow) return
        val gap = dp(container, BOTTOM_GAP_DP).toInt()
        val edge = dp(container, CARD_EDGE_GAP_DP).toInt()
        val want = card.origPaddingBottom + height + gap + edge
        if (!card.hooked) {
            card.origPaddingBottom = container.paddingBottom
            card.origClickable = container.isClickable
            card.origLongClickable = container.isLongClickable
            card.hooked = true
        }
        // 只增不减：宿主自己加的留白比我们要的还多时不回退它（回退会动到宿主的间距）
        if (container.paddingBottom < want) {
            runCatching {
                container.setPadding(
                    container.paddingLeft,
                    container.paddingTop,
                    container.paddingRight,
                    want,
                )
            }
        }
        // 注意：这里**不动** clickable / 监听器 —— 那是 [hookClicks] 的事，
        // 而且只有在装得上我们自己的监听器时才动（宿主原有的监听器不许被覆盖/抹掉）。
        card.wantPadding = want
        card.reserved = height
    }

    /** 还原对宿主内容器的一切改动（padding / clickable / 监听器）。 */
    private fun release(card: Card) {
        val container = card.container
        if (card.hooked && container != null) {
            runCatching {
                // 只摘我们装上去的那一个，并把 clickable 还原成装之前的原值；
                // 宿主原本的监听器不许被我们抹掉
                if (card.hookedClick) {
                    container.setOnClickListener(null)
                    container.isClickable = card.origClickable
                }
                if (card.hookedLongClick) {
                    container.setOnLongClickListener(null)
                    container.isLongClickable = card.origLongClickable
                }
                container.setPadding(
                    container.paddingLeft,
                    container.paddingTop,
                    container.paddingRight,
                    card.origPaddingBottom,
                )
            }
        }
        card.hooked = false
        card.hookedClick = false
        card.hookedLongClick = false
        card.reserved = 0
        card.wantPadding = -1
    }

    private fun hookClicks(card: Card) {
        val container = card.container ?: return
        val row = card.row
        runCatching {
            // 宿主自己在容器上装了监听器时**不抢**：抢过来再在 release 里置空，
            // 等于把宿主原有的交互永久改坏（我们拿不到它的原值，没法还原）。
            // 这种情况下卡片的展开/复制/重试让位给宿主的交互语义。
            if (!card.hookedClick && !container.hasOnClickListeners()) {
                card.origClickable = container.isClickable
                container.setOnClickListener { onClick(row) }
                card.hookedClick = true
            }
            if (!card.hookedLongClick && !container.hasOnLongClickListeners()) {
                card.origLongClickable = container.isLongClickable
                container.setOnLongClickListener { onLongClick(row) }
                card.hookedLongClick = true
            }
        }
    }

    // ------------------------------------------------------------------ 内容与指纹

    private fun render(card: Card, force: Boolean = false) {
        val row = card.row
        val input = card.input ?: return
        val key = input.key
        prepare(card)
        val mood = MoodStore.get(key)
        val failure = SignalAnalyzer.failure(key)
        val night = isNight(row)
        val state = when {
            failure != null -> 'f'
            mood != null -> 'm'
            !ModulePrefs.canAnalyze -> 'u'
            card.note != null -> 's'
            else -> 'p'
        }
        val list = card.list
        val width = cardWidth(row, list)
        if (width <= 0) return
        val fingerprint = Fingerprint(
            identity = input.identity,
            createdAt = input.createdAt,
            state = state,
            moodId = if (mood != null) System.identityHashCode(mood) else 0,
            failure = failure,
            note = card.note,
            expanded = card.expanded,
            night = night,
            trend = MoodStore.trendVersionOf(input.talker),
            queued = if (state == 'p') queuedBucket() else 0,
            capacity = card.capacityPending,
            screenId = System.identityHashCode(card.screen),
            uiRevision = ModulePrefs.uiRevision,
            paletteId = System.identityHashCode(MonetColors.applied.value),
            width = width,
        )
        if (!force && fingerprint == card.fingerprint && card.layout != null) return

        // 指纹之后先查排版缓存：同样的身份 + 同样的指纹 = 同样的画面，直接复用，零成本。
        // 滚动来回、重绑、每拍重试都走这条路（缓存里没有才真正排版）。
        val cached = layouts[fingerprint]
        if (cached != null) {
            card.width = width
            card.layout = cached
            reserve(card, cached.height)
            if (card.hooked) hookClicks(card)
            card.fingerprint = fingerprint
            card.list?.let { runCatching { it.postInvalidateOnAnimation() } }
            return
        }

        // 指纹通过之后才取色板 / 排版（莫奈取色与 StaticLayout 都有成本，没变化就不该付）
        val layout = runCatching {
            val pal = palette(row, night)
            val accent = when {
                failure != null -> pal.warning
                mood == null -> pal.muted
                mood.score > 0.25 -> pal.positive
                mood.score < -0.25 -> pal.negative
                else -> pal.neutral
            }
            buildCard(row, card, width, pal, accent, mood, failure)
        }.getOrElse {
            // 拿不到辅助信息（截图/OCR/native/洞察失败）只降级：这张卡这一帧不画，绝不影响分析。
            // 同一类异常只记一行：这条降级路径每拍都会被重试，无节制的日志本身就是卡顿源
            // （MoodLog 每写一行都要落一次磁盘）。
            warnOnce("layout:${it.javaClass.simpleName}", "卡片排版降级：${it.javaClass.simpleName} ${it.message}")
            return
        }
        card.width = width
        card.layout = layout
        layouts[fingerprint] = layout
        reserve(card, layout.height)
        if (card.hooked) hookClicks(card)
        // 指纹只在真正排好版之后才落：排版失败那一拍不该被记成「已经画好了」
        card.fingerprint = fingerprint
        card.list?.let { runCatching { it.postInvalidateOnAnimation() } }
        if (card.ready) logFirstDraw(card, layout)
    }

    /** 首批绘制打一行诊断（只在详细日志下），便于实机确认「卡片确实被宿主画出来了」。 */
    private fun logFirstDraw(card: Card, layout: CardLayout) {
        if (drewOnce) return
        drewOnce = true
        if (!MoodLog.verbose) return
        MoodLog.i(
            "首张卡片已排版并交给宿主绘制：${card.width}x${layout.height}px，" +
                "ops=${layout.ops.size}，容器=${card.container?.javaClass?.simpleName}，" +
                "列表=${card.list?.javaClass?.simpleName}"
        )
    }

    /** 卡片可用宽度：宿主列表宽度减去左右边距与列表自身 padding（超宽屏再限个上限）。 */
    private fun cardWidth(row: View, list: View?): Int {
        val margin = dp(row, SIDE_MARGIN_DP).toInt()
        val available = when {
            list != null && list.width > 0 ->
                list.width - list.paddingLeft - list.paddingRight - margin * 2
            row.width > 0 -> row.width - margin * 2
            else -> row.resources.displayMetrics.widthPixels - margin * 2
        }
        val max = dp(row, MAX_CARD_WIDTH_DP).toInt()
        return minOf(available, max).coerceAtLeast(minOf(dp(row, 120).toInt(), available))
            .coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ 排版

    /** 标题行里的一段：品牌 / 主情绪 / 趋势各自的大小、颜色与字重。 */
    private class TitlePart(
        val text: String,
        val sizeSp: Float,
        val color: Int,
        val bold: Boolean = false,
    )

    private class LayoutBuilder(
        private val width: Int,
        private val pal: Palette,
        private val accent: Int,
        private val density: Float,
        private val scaled: Float,
    ) {
        val ops = ArrayList<Op>(28)
        val contentLeft = PAD_LEFT_DP * density
        val contentRight = width - PAD_RIGHT_DP * density
        val contentWidth = (contentRight - contentLeft).toInt()
        var y = PAD_TOP_DP * density

        fun dp(value: Float) = value * density
        fun sp(value: Float) = value * scaled

        fun gap(value: Float) {
            y += dp(value)
        }

        fun paint(sizeSp: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(sizeSp)
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        @Suppress("DEPRECATION")
        fun build(
            text: CharSequence,
            sizeSp: Float,
            color: Int,
            bold: Boolean = false,
            lineSpacingDp: Float = 2f,
            widthPx: Int = contentWidth,
            singleLine: Boolean = false,
        ): Layout {
            val paint = paint(sizeSp, color, bold)
            val source = if (singleLine) {
                TextUtils.ellipsize(text, paint, widthPx.toFloat(), TextUtils.TruncateAt.END)
            } else {
                text
            }
            return StaticLayout(
                source, paint, widthPx.coerceAtLeast(1),
                Layout.Alignment.ALIGN_NORMAL, 1f, dp(lineSpacingDp), false,
            )
        }

        fun place(layout: Layout, x: Float = contentLeft) {
            ops += TextOp(layout, x, y)
            y += layout.height
        }

        fun line(
            text: CharSequence,
            sizeSp: Float,
            color: Int,
            bold: Boolean = false,
            lineSpacingDp: Float = 2f,
            singleLine: Boolean = false,
        ) {
            place(build(text, sizeSp, color, bold, lineSpacingDp, singleLine = singleLine))
        }

        fun divider() {
            ops += RectOp(
                contentLeft, y, contentRight, y + dp(1f),
                pal.divider, 0f,
            )
        }

        /**
         * 情绪概率 / 互动均衡横条。
         *
         * [emphasis] 为真时给「主情绪」那一行加粗、加高（9dp / 6dp 的对比），一行之内就能
         * 看出模型认为哪一个是主角；互动均衡那两条是并列关系，传 false 保持等高。
         */
        fun bars(bars: List<MoodBar>, emphasis: Boolean = true) {
            if (bars.isEmpty()) return
            val textSize = sp(10f)
            val metrics = paint(10f, pal.body)
            val rowHeight = sp(16f)
            val labelWidth = sp(38f)
            val percentWidth = sp(34f)
            bars.forEach { bar ->
                val hot = emphasis && bar.highlight
                val barHeight = if (hot) sp(9f) else sp(6f)
                val centerY = y + rowHeight / 2f
                val barLeft = contentLeft + labelWidth
                val barRight = (contentRight - percentWidth).coerceAtLeast(barLeft + sp(20f))
                ops += BarOp(
                    top = centerY - barHeight / 2f,
                    barHeight = barHeight,
                    barLeft = barLeft,
                    barRight = barRight,
                    labelX = contentLeft,
                    percentX = contentRight,
                    baseline = centerY - (metrics.ascent() + metrics.descent()) / 2f,
                    textSize = textSize,
                    label = bar.name,
                    percent = bar.percent,
                    highlight = bar.highlight,
                    bold = hot,
                    accent = accent,
                    labelColor = pal.body,
                    muted = pal.muted,
                    track = pal.track,
                )
                y += rowHeight
            }
        }

        /**
         * 标签行（建议强度 / 置信度 / 情绪强度 / 话题）：放不下就换行，最多 [maxRows] 行。
         *
         * 先前那版「放不下就少画一个」会把话题悄悄吃掉一两个；这里换成换行，
         * 行数与高度仍然在排版期算死，绘制期没有任何额外开销。
         *
         * @return 是否真的画出了至少一枚（调用方据此决定要不要留住上面那点留白）。
         */
        fun chips(items: List<Pair<String, Int>>, maxRows: Int = 2): Boolean {
            if (items.isEmpty()) return false
            val metrics = paint(10f, pal.body)
            val height = dp(17f)
            val padH = dp(7f)
            val gapX = dp(6f)
            val gapY = dp(4f)
            var row = 0
            var x = contentLeft
            var rowTop = y
            var drawn = 0
            items.forEach { (label, color) ->
                if (row >= maxRows) return@forEach
                val w = metrics.measureText(label) + padH * 2
                if (x > contentLeft && x + w > contentRight) {
                    if (row + 1 >= maxRows) return@forEach
                    row++
                    x = contentLeft
                    rowTop += height + gapY
                }
                if (x + w > contentRight) return@forEach
                val background = MonetColors.blend(pal.card, color, 0.12)
                ops += ChipOp(
                    left = x,
                    top = rowTop,
                    right = x + w,
                    bottom = rowTop + height,
                    radius = dp(7f),
                    background = background,
                    border = MonetColors.blend(background, color, 0.34),
                    borderWidth = dp(1f),
                    text = label,
                    textColor = color,
                    textSize = sp(10f),
                    textX = x + padH,
                    baseline = rowTop + (height - (metrics.ascent() + metrics.descent())) / 2f,
                )
                x += w + gapX
                drawn++
            }
            if (drawn > 0) y = rowTop + height
            return drawn > 0
        }

        /** 建议块：一层底色 + 左侧强调条，把「最该看的一行」从正文里托出来。 */
        fun advice(text: CharSequence) {
            val innerPad = dp(9f)
            val innerWidth = (contentWidth - innerPad - dp(10f)).coerceAtLeast(1f)
            val layout = build(text, 12f, pal.title, lineSpacingDp = 2f, widthPx = innerWidth.toInt())
            val innerPadV = dp(7f)
            val top = y
            val bottom = top + layout.height + innerPadV * 2
            ops += RectOp(
                contentLeft, top, contentRight, bottom,
                MonetColors.blend(pal.card, accent, 0.10), dp(10f),
            )
            ops += RectOp(
                contentLeft, top, contentLeft + dp(3f), bottom, accent, dp(1.5f),
            )
            ops += TextOp(layout, contentLeft + innerPad, top + innerPadV)
            y = bottom
        }

        /**
         * 标题行：情绪色点 + 若干「字号 / 颜色 / 字重各不相同」的短段，**按基线对齐**摆一行。
         *
         * 用「多个单行短文本」而不是一份带 span 的长文本：每段的字号本来就不同，
         * 拆开后宽度可以精确测量，放不下时从尾部**整段**丢掉（先丢趋势），不会剩下半截字。
         */
        fun title(parts: List<TitlePart>, dotColor: Int? = null) {
            if (parts.isEmpty()) return
            val gapX = dp(5f)
            val dotSize = if (dotColor != null) dp(DOT_SIZE_DP) else 0f
            val dotGap = if (dotColor != null) dp(DOT_GAP_DP) else 0f
            val available = (contentWidth - dotSize - dotGap).coerceAtLeast(1f)
            val built = ArrayList<Pair<TitlePart, Layout>>(parts.size)
            var used = 0f
            for (part in parts) {
                val layout = build(
                    part.text, part.sizeSp, part.color, part.bold,
                    lineSpacingDp = 2f, widthPx = available.toInt(), singleLine = true,
                )
                val w = layout.getLineWidth(0)
                if (built.isNotEmpty() && used + gapX + w > available) break
                built += part to layout
                used += (if (built.size > 1) gapX else 0f) + w
            }
            if (built.isEmpty()) return
            val baseline = built.maxOf { it.second.getLineBaseline(0) }
            val lineHeight = built.maxOf { it.second.height }
            if (dotColor != null) {
                val centerY = y + lineHeight / 2f
                ops += RectOp(
                    contentLeft, centerY - dotSize / 2f,
                    contentLeft + dotSize, centerY + dotSize / 2f,
                    dotColor, dotSize / 2f,
                )
            }
            var x = contentLeft + dotSize + dotGap
            built.forEach { (_, layout) ->
                ops += TextOp(layout, x, y + (baseline - layout.getLineBaseline(0)))
                x += layout.getLineWidth(0) + gapX
            }
            y += lineHeight
        }

        /**
         * 情绪走势迷你曲线：零轴 + 浅色填充 + 曲线本体 + 末点实心圆。
         *
         * 只画同一个会话最近几段（[MoodStore.recentScores]），纵轴按本次样本的最大绝对值
         * （下限 0.4）缩放 —— 不然一条本来就平坦的曲线会被放大成心电图，反而误导。
         * 两个 [Path] 都在排版期建好，绘制期只 `drawPath` 两下。
         */
        fun sparkline(values: List<Double>, color: Int) {
            if (values.size < 3) return
            val h = dp(22f)
            val top = y
            val mid = top + h / 2f
            val amplitude = (h / 2f - dp(2.5f)).coerceAtLeast(1f)
            val scale = maxOf(0.4, values.maxOf { abs(it) })
            val step = contentWidth.toFloat() / (values.size - 1)
            val line = Path()
            val area = Path()
            var lastY = mid
            values.forEachIndexed { index, value ->
                val px = contentLeft + step * index
                val py = (mid - ((value / scale) * amplitude).toFloat()).coerceIn(top, top + h)
                if (index == 0) {
                    line.moveTo(px, py)
                    area.moveTo(px, mid)
                    area.lineTo(px, py)
                } else {
                    line.lineTo(px, py)
                    area.lineTo(px, py)
                }
                lastY = py
            }
            val lastX = contentLeft + step * (values.size - 1)
            area.lineTo(lastX, mid)
            area.close()
            val dotRadius = dp(2.5f)
            // 零轴：只比发丝线明显一点点，作用是让「在变好还是变差」一眼可读
            ops += RectOp(contentLeft, mid - dp(0.4f), contentRight, mid + dp(0.4f), pal.divider, 0f)
            ops += PathOp(area, MonetColors.withAlpha(color, 26))
            ops += LineOp(line, color, dp(1.4f))
            ops += RectOp(
                lastX - dotRadius, lastY - dotRadius,
                lastX + dotRadius, lastY + dotRadius,
                color, dotRadius,
            )
            y = top + h
        }
    }

    /**
     * 把卡片内容排成一份「绘制指令表」。
     *
     * 四个状态共用同一套骨架（标题 → 横条 → 场景 → 标签 → 解读 → 建议 → 扩展行 → 页脚），
     * 差别只在填什么内容：失败 / 有结论 / 未配置 / 分析中（含「队列已满，等空位」和
     * 「本条过长，未分析」）。任何一段拿不到内容就整段不画，绝不让整张卡失败。
     */
    private fun buildCard(
        row: View,
        card: Card,
        width: Int,
        pal: Palette,
        accent: Int,
        mood: Mood?,
        failure: String?,
    ): CardLayout {
        val metrics = row.resources.displayMetrics
        val builder = LayoutBuilder(width, pal, accent, metrics.density, metrics.scaledDensity)
        val note = card.note
        var footer: CharSequence? = null

        when {
            failure != null -> {
                builder.line(
                    "⚠ " + JevText.get(R.string.jev_card_failed_title),
                    12.5f, pal.warning, bold = true, singleLine = true,
                )
                builder.gap(5f)
                builder.line(
                    twoTone(failure, JevText.get(R.string.jev_card_retry_hint), pal.body, pal.muted),
                    12f, pal.body,
                )
                footer = JevText.get(R.string.jev_card_retry_footer)
            }

            mood != null -> {
                // 标题行：情绪色点 + 品牌（压暗、小一号）+ 主情绪（强调色、放大加粗）
                // +（可选）与前几句的对比（跟随走向色：转好=正向 / 转差=负向 / 平稳=次级色）
                val trend = if (ModulePrefs.showTrend) {
                    MoodStore.trendOf(card.input?.talker.orEmpty())?.let { delta ->
                        val points = (abs(delta) * 100).roundToInt()
                        val label = when {
                            points < 5 -> JevText.get(R.string.jev_trend_flat)
                            delta > 0 -> JevText.get(R.string.jev_trend_up, points)
                            else -> JevText.get(R.string.jev_trend_down, points)
                        }
                        label to when {
                            points < 5 -> pal.muted
                            delta > 0 -> pal.positive
                            else -> pal.negative
                        }
                    }
                } else {
                    null
                }
                val (brand, subject) = titleParts(
                    JevText.get(R.string.jev_card_title, mood.dominantName()),
                )
                val title = ArrayList<TitlePart>(3)
                if (brand.isNotEmpty()) title += TitlePart(brand, 11f, pal.muted)
                if (subject.isNotEmpty()) title += TitlePart(subject, 13.5f, accent, bold = true)
                trend?.let { title += TitlePart(it.first, 10.5f, it.second) }
                builder.title(title, dotColor = accent)

                // 情绪概率：收起只给主情绪一条，展开给全部
                val visibleBars = if (card.expanded) {
                    mood.bars
                } else {
                    mood.bars.filter { it.highlight }.ifEmpty { mood.bars.take(1) }
                }
                if (visibleBars.isNotEmpty()) {
                    builder.gap(6f)
                    builder.bars(visibleBars)
                }

                // 场景 · 阶段 · 本条时间（模型给的短标签 + 本地时间戳；时间读不到就少一段）
                val stamp = timeText(card.input?.createdAt ?: 0L)
                    .takeIf { it.isNotEmpty() }
                    ?.let { JevText.get(R.string.jev_time_message, it) }
                val meta = listOfNotNull(mood.sceneLabel, mood.progressLabel, stamp)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (meta.isNotBlank()) {
                    builder.gap(5f)
                    builder.line(meta, 10f, pal.muted)
                }

                val insight = renderableInsight(card, mood)
                val chips = ArrayList<Pair<String, Int>>(3)
                if (ModulePrefs.showLevel) {
                    insight?.level?.let { level ->
                        chips += JevText.get(
                            R.string.jev_chip_level,
                            JevText.get(ChatInsights.levelLabel(level)),
                        ) to levelColor(level, pal)
                    }
                }
                if (mood.confidence > 0.0) {
                    chips += JevText.get(
                        R.string.jev_meta_confidence,
                        (mood.confidence * 100).roundToInt(),
                    ) to pal.muted
                }
                // 情绪强度：给这张卡的颜色一个可读的数值，和置信度并列在同一行
                if (mood.score != 0.0) {
                    chips += JevText.get(R.string.jev_meta_score, signedScore(mood.score)) to
                        when {
                            mood.score > 0.25 -> pal.positive
                            mood.score < -0.25 -> pal.negative
                            else -> pal.muted
                        }
                }
                if (chips.isNotEmpty()) {
                    builder.gap(6f)
                    builder.chips(chips)
                }

                // 解读：标签 + 标题一行（收起）/ 标题 + 问题 + 备选概率（展开）
                val reading = readingText(card, mood)
                if (reading.isNotBlank()) {
                    builder.gap(6f)
                    builder.line(
                        labelTone(JevText.get(R.string.jev_card_reading, reading), accent, pal.body),
                        12f, pal.body,
                    )
                }

                // 建议：最该看的一行，永远显示（底色 + 左侧强调条 + 标签跟随强调色）
                if (!mood.advice.isNullOrBlank()) {
                    builder.gap(7f)
                    builder.advice(
                        labelTone(JevText.get(R.string.jev_advice, mood.advice), accent, pal.title),
                    )
                }

                // 扩展块 1：建议强度说明 + 话题（画成小标签，最多两行）+ 风险依据
                val topics = if (ModulePrefs.showTopics) {
                    insight?.topics.orEmpty().map { JevText.get(it) }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
                val lines = levelLines(insight, card, pal, accent, topicsAreEmpty = topics.isEmpty())
                if (lines.isNotEmpty()) {
                    builder.gap(7f)
                    builder.line(styled(lines), 11f, pal.body)
                }
                if (topics.isNotEmpty()) {
                    builder.gap(6f)
                    builder.chips(topics.map { it to accent })
                }

                // 扩展块 2：情绪趋势（走向文本 + 迷你曲线），展开后才出现
                if (card.expanded && ModulePrefs.showTrendPanel) {
                    val trendLines = trendLines(insight, pal, accent)
                    if (trendLines.isNotEmpty()) {
                        builder.gap(6f)
                        builder.line(styled(trendLines), 11f, pal.body)
                    }
                    val series = runCatching {
                        MoodStore.recentScores(card.input?.talker.orEmpty(), SPARK_SAMPLES)
                    }.getOrDefault(emptyList())
                    if (series.size >= 3) {
                        val direction = insight?.trend?.direction ?: 0
                        builder.gap(3f)
                        builder.sparkline(
                            series,
                            when {
                                direction > 0 -> pal.positive
                                direction < 0 -> pal.negative
                                else -> accent
                            },
                        )
                    }
                }

                // 扩展块 3：互动均衡（标题 + 双方横幅 + 连续发言提示）
                val balance = insight?.balance
                if (card.expanded && ModulePrefs.showBalance && balance != null) {
                    builder.gap(7f)
                    builder.line(
                        JevText.get(R.string.jev_ext_balance_title, balance.total),
                        10.5f, pal.muted, singleLine = true,
                    )
                    builder.gap(3f)
                    builder.bars(
                        listOf(
                            MoodBar(JevText.get(R.string.jev_card_balance_self), balance.selfPercent, true),
                            MoodBar(
                                JevText.get(R.string.jev_card_balance_other),
                                balance.otherPercent,
                                false,
                            ),
                        ),
                        emphasis = false,
                    )
                    val runLines = balanceLines(insight, pal)
                    if (runLines.isNotEmpty()) {
                        builder.gap(4f)
                        builder.line(styled(runLines), 10f, pal.muted)
                    }
                }

                footer = hintText(card, mood)
            }

            !ModulePrefs.canAnalyze -> {
                builder.line(
                    JevText.get(R.string.jev_card_unconfigured_title),
                    12.5f, pal.muted, bold = true, singleLine = true,
                )
                builder.gap(5f)
                builder.line(JevText.get(R.string.jev_card_unconfigured_body), 12f, pal.muted)
            }

            else -> {
                // 分析中 / 队列已满 / 本条过长：标题 + 一行说明，不画扩展块
                builder.line(
                    if (note != null) {
                        JevText.get(R.string.jev_card_skipped_title)
                    } else {
                        JevText.get(R.string.jev_card_pending_title)
                    },
                    12.5f, pal.muted, bold = true, singleLine = true,
                )
                builder.gap(5f)
                val queued = if (note == null) MoodStore.pendingCount() else 0
                builder.line(
                    note ?: when {
                        card.capacityPending -> JevText.get(R.string.jev_card_pending_capacity, queued)
                        queued > 1 -> JevText.get(R.string.jev_card_pending_queued, queued)
                        else -> JevText.get(R.string.jev_card_pending_solo)
                    },
                    12f, pal.muted,
                )
            }
        }

        if (footer != null) {
            builder.gap(7f)
            builder.divider()
            builder.gap(6f)
            builder.line(footer, 10f, pal.muted)
        }
        val density = metrics.density
        val height = (builder.y + PAD_BOTTOM_DP * density).toInt().coerceAtLeast(1)
        val cardHeight = height.toFloat()
        val radius = dpf(density, CARD_RADIUS_DP)

        // 背景 / 投影 / 指示条放最前面：指令按顺序执行，后面画的才是内容
        val head = ArrayList<Op>(4)

        // 1. 投影：同圆角的形状向下偏移，颜色是「正文色」的极低 alpha，往下 3dp 渐隐到全透明。
        //    浅色下看起来是暗影、深色下是浮起感，全程不建硬件层、不做 blur。
        val shadowTop = dpf(density, SHADOW_OFFSET_DP)
        val shadowBottom = cardHeight + dpf(density, SHADOW_LENGTH_DP)
        head += GradientOp(
            dpf(density, 0.5f), shadowTop,
            width - dpf(density, 0.5f), shadowBottom,
            radius,
            LinearGradient(
                0f, shadowTop, 0f, shadowBottom,
                MonetColors.withAlpha(
                    pal.title,
                    if (pal.night) SHADOW_ALPHA_DARK else SHADOW_ALPHA_LIGHT,
                ),
                MonetColors.withAlpha(pal.title, 0),
                Shader.TileMode.CLAMP,
            ),
        )

        // 2. 卡片本体：容器色往下混一点容器高亮色。纯色的卡片在浅色主题下会很平，
        //    渐变幅度故意压得很小，只在余光里能感觉到「有厚度」。
        val fillTop = if (failure != null) {
            MonetColors.blend(pal.card, pal.warning, 0.12)
        } else {
            pal.card
        }
        head += GradientOp(
            0f, 0f, width.toFloat(), cardHeight, radius,
            LinearGradient(
                0f, 0f, 0f, cardHeight,
                fillTop, MonetColors.blend(fillTop, pal.track, CARD_GRADIENT_RATIO),
                Shader.TileMode.CLAMP,
            ),
        )

        // 3. 描边：纯描边（填充给全透明），同色系里掺一点强调色就够把卡片从聊天背景里拎出来
        head += RectOp(
            0f, 0f, width.toFloat(), cardHeight,
            0,
            radius,
            dpf(density, CARD_STROKE_DP),
            if (failure != null) {
                MonetColors.blend(pal.stroke, pal.warning, 0.55)
            } else {
                MonetColors.blend(pal.stroke, accent, 0.28)
            },
        )

        // 4. 左侧情绪指示条：竖向渐变的药丸，上端是纯情绪色、下端稍微往卡片色靠一点
        head += GradientOp(
            dpf(density, STRIPE_LEFT_DP),
            dpf(density, STRIPE_INSET_DP),
            dpf(density, STRIPE_LEFT_DP + STRIPE_WIDTH_DP),
            cardHeight - dpf(density, STRIPE_INSET_DP),
            dpf(density, STRIPE_WIDTH_DP / 2f),
            LinearGradient(
                0f, dpf(density, STRIPE_INSET_DP),
                0f, cardHeight - dpf(density, STRIPE_INSET_DP),
                accent, MonetColors.blend(accent, pal.card, 0.35),
                Shader.TileMode.CLAMP,
            ),
        )
        return CardLayout(width, height, head + builder.ops)
    }

    /** 左侧情绪指示条：竖着的药丸 + 竖向渐变，纵向内缩，和卡片圆角互不打架（见 buildCard 第 4 条指令）。 */
    private fun dpf(density: Float, dp: Float) = density * dp

    /** 解读正文：优先用结构化解读，降级结果退回原始正文裁剪。 */
    private fun readingText(card: Card, mood: Mood): String {
        val title = mood.readingTitle
        if (title == null) return bodyText(mood)
        val builder = StringBuilder(title)
        if (card.expanded) {
            mood.readingQuestion?.let { builder.append('\n').append(it) }
            if (mood.readingOptions.isNotEmpty()) {
                builder.append('\n')
                    .append(mood.readingOptions.joinToString(" · ") { "${it.label} ${it.percent}%" })
            }
        }
        return builder.toString().trim()
    }

    /** 页脚：降级说明优先，其次交互提示。 */
    private fun hintText(card: Card, mood: Mood): CharSequence = when {
        mood.note != null -> JevText.get(R.string.jev_hint_note, mood.note)
        canExpand(mood) && card.expanded -> JevText.get(R.string.jev_hint_collapse)
        canExpand(mood) -> JevText.get(R.string.jev_hint_expand)
        else -> JevText.get(R.string.jev_hint_copy)
    }

    /**
     * 扩展洞察（话题 / 趋势 / 风险 / 互动均衡）的计算。
     *
     * 一律包在 runCatching 里：**辅助信息拿不到就整块不画**，绝不让这一条分析变成失败。
     */
    private fun renderableInsight(card: Card, mood: Mood): ChatInsights.Insight? {
        if (!ModulePrefs.showLevel && !ModulePrefs.showBalance &&
            !ModulePrefs.showTopics && !ModulePrefs.showTrendPanel
        ) {
            return null
        }
        val talker = card.input?.talker ?: return null
        return runCatching {
            ChatInsights.build(mood, card.screen, MoodStore.recentScores(talker))
        }.getOrNull()
    }

    /**
     * 扩展块的文本行。
     *
     * 收起时只留「话题 + 一行风险等级」（三秒内看得完），展开后再补建议说明、
     * 情绪趋势、风险依据与互动均衡比例。
     */
    /**
     * 扩展块 1 的文本行：建议强度说明 +（话题没能画成标签时的）兜底文案 + 风险依据。
     *
     * 话题标签改由 [LayoutBuilder.chips] 单独画，所以这里只在「没有话题」时补一行文字，
     * 同一份信息不显示两遍。
     */
    private fun levelLines(
        insight: ChatInsights.Insight?,
        card: Card,
        pal: Palette,
        accent: Int,
        topicsAreEmpty: Boolean,
    ): List<StyledLine> {
        if (insight == null) return emptyList()
        val lines = ArrayList<StyledLine>(6)
        if (ModulePrefs.showLevel && card.expanded) {
            insight.level?.let {
                lines += StyledLine(JevText.get(ChatInsights.levelDesc(it)), pal.body, pal.muted)
            }
        }
        if (ModulePrefs.showTopics && topicsAreEmpty) {
            lines += StyledLine(
                JevText.get(R.string.jev_line_topics, JevText.get(R.string.jev_ext_topics_none)),
                pal.body,
                accent,
            )
        }
        if (ModulePrefs.showLevel) {
            val risk = insight.risk
            if (risk == null) {
                // 看过、没发现值得提的地方：明确说一句，比留白更像「已经分析过了」
                if (card.expanded) {
                    lines += StyledLine(JevText.get(R.string.jev_risk_none), pal.muted, pal.muted)
                }
            } else if (risk.level >= 1 || card.expanded) {
                lines += StyledLine(JevText.get(riskLevelText(risk.level)), pal.warning, pal.warning)
                if (card.expanded) {
                    risk.notes.forEach { (id, args) ->
                        lines += StyledLine(JevText.get(id, *args), pal.warning, pal.warning)
                    }
                }
            }
        }
        return lines
    }

    /** 扩展块 2 的文本行：情绪走向 + 均值 / 波动；走向文本的颜色跟随走向本身。 */
    private fun trendLines(
        insight: ChatInsights.Insight?,
        pal: Palette,
        accent: Int,
    ): List<StyledLine> {
        val trend = insight?.trend ?: return emptyList()
        val direction = when {
            trend.direction > 0 -> R.string.jev_ext_trend_up
            trend.direction < 0 -> R.string.jev_ext_trend_down
            else -> R.string.jev_ext_trend_flat
        }
        return listOf(
            StyledLine(
                JevText.get(R.string.jev_line_trend, JevText.get(direction, trend.samples)),
                pal.body,
                when {
                    trend.direction > 0 -> pal.positive
                    trend.direction < 0 -> pal.negative
                    else -> accent
                },
            ),
            StyledLine(
                JevText.get(
                    R.string.jev_ext_trend_stat,
                    formatScore(trend.mean),
                    formatScore(trend.swing),
                ),
                pal.muted,
                pal.muted,
            ),
        )
    }

    /**
     * 扩展块 3 的提示行：连续发言。
     *
     * 「对方连发 N 条」是「该你回一句」的信号，用告警色；自己连发只是事实描述，用次级色。
     */
    private fun balanceLines(insight: ChatInsights.Insight?, pal: Palette): List<StyledLine> {
        val balance = insight?.balance ?: return emptyList()
        val lines = ArrayList<StyledLine>(2)
        if (balance.otherRun >= 2) {
            lines += StyledLine(
                JevText.get(R.string.jev_ext_balance_other_run, balance.otherRun),
                pal.warning,
                pal.warning,
            )
        }
        if (balance.selfRun >= 3) {
            lines += StyledLine(
                JevText.get(R.string.jev_ext_balance_self_run, balance.selfRun),
                pal.muted,
                pal.muted,
            )
        }
        return lines
    }

    // ------------------------------------------------------------------ 绘制实现

    /**
     * 每帧由宿主列表的 ViewOverlay 调用（[CardLayer.draw]）。
     *
     * 这里在宿主的 `draw()` 调用栈里，所以有两条硬约束：
     *  1. **绝不抛异常**：任何异常都会顺着宿主 draw 直接闪退微信。整段包 try，出错只记
     *     一行日志并跳过这一帧的卡片（宁可少画一张卡，也不能崩宿主）。
     *  2. **画之前再校验一次归属**：只有「这一行现在绑定的消息就是这张卡那一条」才画。
     *     宿主行被复用/换绑、而卡片还没来得及被回收的那一小段时间里，宁可不画。
     */
    private fun drawCards(list: View, canvas: Canvas) {
        if (cards.isEmpty()) return
        try {
            drawCardsUnchecked(list, canvas)
        } catch (t: Throwable) {
            warnOnce("draw:${t.javaClass.simpleName}", "卡片绘制异常（已跳过，不影响宿主）：${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun drawCardsUnchecked(list: View, canvas: Canvas) {
        val height = list.height
        val density = list.resources.displayMetrics.density
        val margin = SIDE_MARGIN_DP * density
        val gap = BOTTOM_GAP_DP * density
        val x = list.paddingLeft + margin
        var staleReserve = false
        for (card in cards.values) {
            val layout = card.layout ?: continue
            val container = card.container ?: continue
            if (card.list !== list) continue
            if (!container.isAttachedToWindow) continue
            // 归属校验：行已经不是这条消息了就不画（等 handle/clear 把它收走）
            if (card.messageId <= 0L || rowMessageId(card.row) != card.messageId) continue
            // 预留校验：宿主把它自己那条内容器的 padding 改回去了（或这一帧还没按新 padding
            // 测量完）就先不画，交给 [repair] 下一帧补预留 —— **宁可晚一帧，也绝不把卡片
            // 画到「没有给它留位置」的地方**。用户看到的「卡片压在别的消息上、过一会儿才
            // 规范」就是少了这道校验：位置按 padding 算，可那一帧的 padding 已经不在了。
            if (card.wantPadding < 0 || container.paddingBottom < card.wantPadding) {
                staleReserve = true
                continue
            }
            val containerTop = offsetWithin(container, list) ?: continue
            // 卡片落在气泡那一列的底边之下（锚 = 容器里直接装气泡的那个子 View）。
            // 用锚而不是「容器高度 - paddingBottom」：容器还没按新 padding 重新测量的那一帧里，
            // 后者会把卡片算进内容区（压到气泡上），前者任何一帧都指着真实的气泡底边。
            val anchor = card.anchor
            val anchorBottom = if (anchor != null && anchor.parent === container) {
                offsetWithin(anchor, container)?.let { it + anchor.height }
            } else {
                null
            }
            val bandTop = if (anchorBottom != null) {
                containerTop + anchorBottom + gap
            } else {
                containerTop + container.height - container.paddingBottom + gap
            }
            val bandBottom = bandTop + layout.height
            if (bandBottom < 0f || bandTop > height) continue
            // 位置自校验：这条带子里必须真的没有别的消息行 —— 宿主行还没按新 padding 长高时，
            // 下一条消息的顶边就落在这条带子里，此时不画（几何会在 padding 改动触发的下一次
            // 布局里跟上，卡片随后自然出现）。这是「卡片绝不串到别的消息上」的最后一道闸门。
            if (!hasRoomFor(list, card.row, bandTop, bandBottom)) {
                card.noRoomFrames++
                if (card.noRoomFrames == MAX_NO_ROOM_FRAMES) {
                    warnOnce("noroom", "卡片位置未就绪：宿主行没有按预留长高（已跳过绘制，继续等待）")
                }
                continue
            }
            card.noRoomFrames = 0
            val save = canvas.save()
            canvas.translate(x, bandTop)
            paintLayout(canvas, layout)
            canvas.restoreToCount(save)
        }
        if (staleReserve) scheduleRepair()
    }

    /**
     * 这条带子里是否真的没有别的消息行（自身行除外）。
     *
     * 用列表的直接子 View 坐标判定（overlay 的坐标系就是列表自己的坐标系，两者天然一致）：
     * 卡片带必须落在**属于自己**的行范围里。这样「宿主行还没长高」与「宿主把 padding 写回去了」
     * 两种时序问题都会表现为「这一帧不画」，而不是「画到别人身上」。
     */
    private fun hasRoomFor(list: View, self: View, bandTop: Float, bandBottom: Float): Boolean {
        val group = list as? ViewGroup ?: return true
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) ?: continue
            if (child === self || child.visibility != View.VISIBLE) continue
            val top = child.top + child.translationY
            if (top < bandBottom && top + child.height > bandTop) return false
        }
        return true
    }

    /** 视图在祖先坐标系里的纵向偏移（不含滚动，overlay 的坐标系就是列表自己的坐标系）。 */
    private fun offsetWithin(view: View, ancestor: View): Float? {
        var y = 0f
        var current: View? = view
        while (current != null && current !== ancestor) {
            y += current.top + current.translationY
            current = current.parent as? View
        }
        return if (current === ancestor) y else null
    }

    private fun paintLayout(canvas: Canvas, layout: CardLayout) {
        for (op in layout.ops) {
            when (op) {
                is RectOp -> {
                    rectPaint.style = Paint.Style.FILL
                    rectPaint.color = op.color
                    if (op.radius > 0f) {
                        canvas.drawRoundRect(op.left, op.top, op.right, op.bottom, op.radius, op.radius, rectPaint)
                    } else {
                        canvas.drawRect(op.left, op.top, op.right, op.bottom, rectPaint)
                    }
                    if (op.strokeWidth > 0f) {
                        rectPaint.style = Paint.Style.STROKE
                        rectPaint.strokeWidth = op.strokeWidth
                        rectPaint.color = op.strokeColor
                        canvas.drawRoundRect(op.left, op.top, op.right, op.bottom, op.radius, op.radius, rectPaint)
                        rectPaint.style = Paint.Style.FILL
                    }
                }

                is GradientOp -> {
                    rectPaint.style = Paint.Style.FILL
                    rectPaint.shader = op.shader
                    canvas.drawRoundRect(op.left, op.top, op.right, op.bottom, op.radius, op.radius, rectPaint)
                    // 渐变对象是排版期建好的；这里只把它摘下来，别污染后面的纯色指令
                    rectPaint.shader = null
                }

                is PathOp -> {
                    rectPaint.style = Paint.Style.FILL
                    rectPaint.color = op.color
                    canvas.drawPath(op.path, rectPaint)
                }

                is LineOp -> {
                    rectPaint.style = Paint.Style.STROKE
                    rectPaint.strokeWidth = op.width
                    rectPaint.strokeJoin = Paint.Join.ROUND
                    rectPaint.strokeCap = Paint.Cap.ROUND
                    rectPaint.color = op.color
                    canvas.drawPath(op.path, rectPaint)
                    rectPaint.style = Paint.Style.FILL
                }

                is TextOp -> {
                    val save = canvas.save()
                    canvas.translate(op.x, op.top)
                    op.layout.draw(canvas)
                    canvas.restoreToCount(save)
                }

                is BarOp -> {
                    chipPaint.color = op.track
                    val radius = op.barHeight / 2f
                    canvas.drawRoundRect(
                        op.barLeft, op.top, op.barRight, op.top + op.barHeight,
                        radius, radius, chipPaint,
                    )
                    val filled = (op.barRight - op.barLeft) *
                        (op.percent.coerceIn(0, 100) / 100f)
                    if (filled > 0f) {
                        chipPaint.color = if (op.highlight) op.accent else op.muted
                        canvas.drawRoundRect(
                            op.barLeft, op.top, op.barLeft + filled, op.top + op.barHeight,
                            radius, radius, chipPaint,
                        )
                    }
                    barPaint.textSize = op.textSize
                    barPaint.typeface = if (op.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    barPaint.textAlign = Paint.Align.LEFT
                    barPaint.color = if (op.highlight) op.accent else op.labelColor
                    canvas.drawText(op.label, op.labelX, op.baseline, barPaint)
                    barPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText("${op.percent}%", op.percentX, op.baseline, barPaint)
                    barPaint.textAlign = Paint.Align.LEFT
                }

                is ChipOp -> {
                    chipPaint.color = op.background
                    canvas.drawRoundRect(op.left, op.top, op.right, op.bottom, op.radius, op.radius, chipPaint)
                    // 细描边：深色主题下「淡底色的小标签」容易和卡片糊成一片
                    chipPaint.style = Paint.Style.STROKE
                    chipPaint.strokeWidth = op.borderWidth
                    chipPaint.color = op.border
                    canvas.drawRoundRect(op.left, op.top, op.right, op.bottom, op.radius, op.radius, chipPaint)
                    chipPaint.style = Paint.Style.FILL
                    barPaint.textSize = op.textSize
                    barPaint.color = op.textColor
                    barPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(op.text, op.textX, op.baseline, barPaint)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 点击：失败态重试；有结论时展开/收起完整解读。
     *
     * 「展开」比「复制」更适合作为单击默认行为 —— 复制改成**长按**，
     * 卡片上常驻一行提示说明这两件事。
     */
    private fun onClick(row: View) {
        val card = cards[row] ?: return
        val key = card.key
        runCatching {
            when {
                SignalAnalyzer.failure(key) != null -> YanwaiScanner.retryRow(row)
                MoodStore.get(key) != null -> {
                    card.expanded = !card.expanded
                    render(card)
                }
            }
        }
    }

    /** 长按：把整份解读（含情绪概率与建议）复制成纯文本。 */
    private fun onLongClick(row: View): Boolean {
        val card = cards[row] ?: return false
        val mood = MoodStore.get(card.key) ?: return false
        val text = MoodMessageChannel.format(mood)
        val copied = runCatching {
            val manager = row.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText(JevText.get(R.string.jev_clip_label), text))
            true
        }.getOrDefault(false)
        runCatching {
            val toast = if (copied) {
                JevText.get(R.string.jev_toast_copied)
            } else {
                JevText.get(R.string.jev_toast_copy_failed)
            }
            Toast.makeText(row.context, toast, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    // ------------------------------------------------------------------ 富文本与文案工具

    /**
     * 一行扩展文案 + 它的两个颜色：正文色与「标签」色。
     *
     * 标签 = 冒号（中英文都认）之前的那一段，渲染时加粗并换成标签色 ——
     * 这样「话题：…」「风险：…」在视觉上自动分成「标签 + 内容」两层。
     */
    private class StyledLine(val text: String, val body: Int, val label: Int)

    /** 把 [lines] 拼成一份带样式文本：标签加粗上色，正文按各自的行色。 */
    private fun styled(lines: List<StyledLine>): CharSequence {
        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) builder.append('\n')
            val start = builder.length
            builder.append(line.text)
            val end = builder.length
            val cut = line.text.indexOfFirst { it == '：' || it == ':' }
            val labelEnd = if (cut >= 0) start + cut + 1 else start
            if (cut >= 0) {
                builder.setSpan(StyleSpan(Typeface.BOLD), start, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(
                    ForegroundColorSpan(line.label),
                    start,
                    labelEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            builder.setSpan(
                ForegroundColorSpan(line.body),
                labelEnd,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }

    /** 单行「标签：正文」：标签加粗上色，正文用 [body] 色。 */
    private fun labelTone(text: String, label: Int, body: Int): CharSequence =
        styled(listOf(StyledLine(text, body, label)))

    /** 失败态：第一行（原因）加粗，第二行（怎么办）压暗。 */
    private fun twoTone(first: String, second: String, firstColor: Int, secondColor: Int): CharSequence {
        val builder = SpannableStringBuilder(first).append('\n').append(second)
        builder.setSpan(StyleSpan(Typeface.BOLD), 0, first.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            ForegroundColorSpan(firstColor),
            0,
            first.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            ForegroundColorSpan(secondColor),
            first.length + 1,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return builder
    }

    /** 建议强度 → 语义色（推进=正向 / 稳步=中性 / 观察=告警 / 暂缓=负向）。 */
    private fun levelColor(level: ChatInsights.Level, pal: Palette): Int = when (level) {
        ChatInsights.Level.ADVANCE -> pal.positive
        ChatInsights.Level.STEADY -> pal.neutral
        ChatInsights.Level.WATCH -> pal.warning
        ChatInsights.Level.HOLD -> pal.negative
    }

    private fun riskLevelText(level: Int): Int = when {
        level >= 2 -> R.string.jev_risk_level_high
        level == 1 -> R.string.jev_risk_level_medium
        else -> R.string.jev_risk_level_low
    }

    private fun formatScore(value: Double): String = String.format(Locale.US, "%.2f", value)

    /** 情绪强度带上符号：正数补「+」，负数自带「-」。 */
    private fun signedScore(value: Double): String =
        if (value > 0) "+" + formatScore(value) else formatScore(value)

    private val timeLock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** 本条消息的时间戳（本地时区，形如 09-26 14:03）；读不到（0）就返回空串，卡片自然少画一段。 */
    private fun timeText(millis: Long): String {
        if (millis <= 0L) return ""
        return synchronized(timeLock) {
            runCatching { timeFormat.format(Date(millis)) }.getOrDefault("")
        }
    }

    /**
     * 把「潜语 · 平静」拆成「品牌」与「主题」两段：品牌压暗收小、主题放大加粗。
     *
     * 三语资源里的分隔符都是中点（也兼容冒号）。拆不开就整串当主题 ——
     * 翻译改了写法顶多退化成旧观感，绝不会丢字。
     */
    private fun titleParts(full: String): Pair<String, String> {
        val cut = full.indexOfFirst { it == '·' || it == '：' || it == ':' }
        if (cut <= 0) return "" to full.trim()
        val brand = full.substring(0, cut).trim()
        val subject = full.substring(cut + 1).trim()
        return if (brand.isEmpty() || subject.isEmpty()) "" to full.trim() else brand to subject
    }

    /** 还有没有「收起来时看不到」的内容可展开。 */
    private fun canExpand(mood: Mood): Boolean =
        mood.bars.size > 1 || mood.readingQuestion != null || mood.readingOptions.isNotEmpty()

    /** 正文 = 去掉 Jev 头、「情绪：」行与「建议：」行后的其余解读内容（降级结果用）。 */
    private fun bodyText(mood: Mood): String = mood.detail.lines()
        .filterNot {
            it.startsWith(JevProtocol.header) || it.startsWith("情绪：") || it.startsWith("建议：")
        }
        .joinToString("\n")
        .trim()

    // ------------------------------------------------------------------ 配色

    private class Palette(
        val card: Int,
        val stroke: Int,
        val title: Int,
        val body: Int,
        val track: Int,
        val muted: Int,
        val positive: Int,
        val neutral: Int,
        val negative: Int,
        val warning: Int,
        /** 标签底色。 */
        val chip: Int,
        /** 分隔线。 */
        val divider: Int,
        /** 夜间模式：投影 / 渐变的强度按这个分档（浅色要暗影，深色要浮起感）。 */
        val night: Boolean,
    )

    private val paletteLock = Any()
    private var paletteCache: Palette? = null
    private var paletteNight = false
    private var paletteEngine: Any? = null

    private fun isNight(row: View): Boolean = row.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * 取色板（带缓存）。
     *
     * 莫奈取色 + Palette 构造没必要每次 show 都做：只有夜间模式切换或引擎色板换了一版
     * 才需要重算。缓存键是这两样（注意用**引擎色板实例**，不是 tokens(night) 的返回值 ——
     * 后者每次都新建一个 Tokens，拿它当键等于永远不命中）。
     */
    private fun palette(row: View, night: Boolean): Palette {
        val engine = MonetColors.applied.value
        synchronized(paletteLock) {
            val cached = paletteCache
            if (cached != null && paletteNight == night && paletteEngine === engine) return cached
            val fresh = buildPalette(night)
            paletteNight = night
            paletteEngine = engine
            paletteCache = fresh
            return fresh
        }
    }

    private fun buildPalette(dark: Boolean): Palette {
        val base = if (dark) {
            Palette(0xFF23262B.toInt(), 0xFF343A42.toInt(), 0xFFD6DAE1.toInt(), 0xFFC3C8D0.toInt(),
                0xFF33373D.toInt(), 0xFF8B9099.toInt(), 0xFF5CC08A.toInt(), 0xFFE0A45A.toInt(),
                0xFFE07A70.toInt(), 0xFFE0A45A.toInt(), 0xFF2C3037.toInt(), 0xFF31363E.toInt(), true)
        } else {
            Palette(0xFFFFFFFF.toInt(), 0xFFE3E6EC.toInt(), 0xFF4A4F58.toInt(), 0xFF3C4149.toInt(),
                0xFFEDEFF3.toInt(), 0xFF8A8F98.toInt(), 0xFF2F9E63.toInt(), 0xFFCC8A2E.toInt(),
                0xFFC0453B.toInt(), 0xFFCC8A2E.toInt(), 0xFFF1F3F7.toInt(), 0xFFE9ECF1.toInt(), false)
        }
        // 莫奈引擎生效时改用引擎色板：这张卡是 WeKit 画在会话里的，宿主的资源替换覆盖不到它，
        // 不接过来就会在已经莫奈化的会话里显得突兀。语义色（正向/中性/负向/告警）仍用卡片自己的，
        // 情绪含义不跟着主题漂移。
        val tokens = MonetColors.tokens(dark) ?: return base
        return Palette(
            card = tokens.surfaceContainer,
            stroke = tokens.outline,
            title = tokens.onSurface,
            body = tokens.onSurfaceVariant,
            track = tokens.surfaceContainerHigh,
            muted = tokens.onSurfaceVariant,
            positive = base.positive,
            neutral = base.neutral,
            negative = base.negative,
            warning = base.warning,
            chip = MonetColors.blend(tokens.surfaceContainerHigh, tokens.accent, 0.10),
            divider = MonetColors.blend(tokens.surfaceContainer, tokens.onSurface, 0.14),
            night = dark,
        )
    }

    // ------------------------------------------------------------------ 宿主视图定位

    private val mainContainerLookup = HashMap<Class<*>, java.lang.reflect.Method?>()

    /**
     * 取宿主行里那个真正的文本气泡视图。
     *
     * 它只用于「从气泡往上找到行内容器」这一步（落点判定），不再参与任何绘制。
     */
    private fun findBubble(root: ViewGroup?): View? {
        if (root == null) return null
        val holder = root.tag
        if (holder != null) {
            val cls = holder.javaClass
            val method = synchronized(mainContainerLookup) {
                if (mainContainerLookup.containsKey(cls)) {
                    mainContainerLookup[cls]
                } else {
                    val found = generateSequence(holder.javaClass as Class<*>) { it.superclass }
                        .flatMap { it.declaredMethods.asSequence() }
                        .firstOrNull { it.name == "getMainContainerView" && it.parameterCount == 0 }
                    mainContainerLookup[cls] = found
                    found
                }
            }
            val main = runCatching {
                method?.isAccessible = true
                method?.invoke(holder) as? View
            }.getOrNull()
            if (main != null && main !== root && main.isShown && isInside(main, root)) return main
        }
        fun find(view: View, depth: Int): View? {
            if (depth > 24 || view.visibility != View.VISIBLE) return null
            if (view.javaClass.name.endsWith(".MMNeat7extView")) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)?.let { return it }
            }
            return null
        }
        return find(root, 0)
    }

    private fun isInside(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun dp(view: View, value: Float): Float = value * view.resources.displayMetrics.density

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    /** 只给 [YanwaiScanner] 诊断用：当前挂着的卡片数与预留高度（不触发任何宿主改动）。 */
    fun describe(): String {
        if (cards.isEmpty()) return "当前无卡片"
        val rows = cards.values
        val reserved = rows.sumOf { it.reserved }
        val ready = rows.count { it.ready }
        return "卡片 ${cards.size} 张（已就绪 $ready，预留合计 ${reserved}px）"
    }
}
