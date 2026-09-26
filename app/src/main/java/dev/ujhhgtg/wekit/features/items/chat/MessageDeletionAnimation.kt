// SPDX-License-Identifier: GPL-3.0-only
package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Telegram 风格的消息删除动画。
 *
 * 消息被删除时，该行先被截成一张位图快照，随即碎裂为粒子向四周飞散并淡出；
 * 其余仍然存在的消息行从「删除前的落点」平滑滑回最终位置。
 *
 * ## 2026-09-25 崩溃根因（必须保留这段说明）
 *
 * 旧实现把粒子覆盖层 `container.addView(overlay, ViewGroup.LayoutParams(MATCH_PARENT, …))`
 * 直接加到了**消息列表本身**（消息行的 parent 就是聊天页的消息 RecyclerView）。
 * `ViewGroup.addViewInner` 在 `checkLayoutParams=false` 时会用宿主自己的
 * `generateLayoutParams` 转换参数 —— RecyclerView 的转换结果是 `RecyclerView.LayoutParams`，
 * 它的 `mViewHolder` 是 null，于是这个「外来子 View」在 RecyclerView 内部就是一个
 * **没有 ViewHolder 的子 View**。后果有两个，用户 09-25 的日志与崩溃报告正好一一对应：
 *
 *  1. `androidx.recyclerview.widget.GapWorker.prefetchPositionWithDeadline` →
 *     `isPrefetchPositionAttached` 里 `getChildViewHolder(child).mPosition` 直接 NPE
 *     （崩溃栈：`k3 r0.c(RecyclerView, int, long)`，NullPointerException 读 `int k3.mPosition`）。
 *  2. LinearLayoutManager 的 anchor/position 被这个无 holder 的子 View 带偏，而聊天页的
 *     RecyclerView 在不同会话之间是复用的 —— 于是「打开任意一个会话，显示的都是另一个会话」，
 *     并且列表越用越卡（覆盖层还会 MATCH_PARENT 全屏 invalidate）。
 *
 * 聊天页在一次会话打开/关闭/批量更新时会有上百行同时 detach，旧实现会对每一行都做一次
 * 整行 `Picture` 重绘 + `Bitmap.createBitmap(Picture)` 快照，并插入一个覆盖层 —— 日志里
 * `onViewUpdate count=113 totalCount=10967` 这种场景直接制造出上百个外来子 View。
 *
 * 因此现在的铁律：
 *  - **绝不往消息列表 RecyclerView 里加/删任何 View**；也**不往列表的宿主容器里加 View**
 *    （那会触发整页 relayout，同样会把「卡」带回来）。粒子层画在**列表自身的 `ViewOverlay`**
 *    上，是一个纯 `Drawable` —— 不参与测量/布局、不进入任何 ViewGroup 的 children，
 *    坐标系就是列表自己的坐标系（`view.left` / `view.top` 可直接用，无需任何宿主偏移换算）；
 *  - 快照只在**确认是删除**之后才抓，且同一时刻只允许一个粒子层；
 *  - 短时间大量 detach 一律判为「列表重建」直接放弃（连快照都不抓）；
 *  - 粒子共用一张快照位图（只做 src/dst 裁剪），不再为每个粒子 `createBitmap` 复制。
 */
object MessageDeletionAnimation : SwitchFeature(), WeChatMessageViewApi.IMessageViewLifecycleListener {

    override val technicalId = "消息删除动画"
    override val nameRes = R.string.feature_message_deletion_animation_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_message_deletion_animation_description

    private const val TAG = "MessageDeletionAnimation"

    /**
     * 行 View 上「当前绑定的是哪条消息」的键控标记。
     *
     * 由 [WeChatMessageViewApi] 的绑定点统一写入（见那里的 [WeChatMessageViewApi.ROW_TAG_MESSAGE_KEY]）。
     * 这里只读不写：以前本文件自己定了一个同样的常量却从没人写，`getTag` 永远是 null，
     * 导致「这一行是否还留在列表里」的判据形同虚设 —— 发送消息时的行重绑被当成删除，
     * 每条消息都要放一次删除特效（用户 2026-09-25 反馈的根因）。
     */
    private const val ROW_TAG_MSG_ID = WeChatMessageViewApi.ROW_TAG_MESSAGE_KEY

    private const val SETTLE_MS = 250L
    private const val SETTLE_HFR_MS = 475L
    private const val DISSOLVE_MS = 520L
    private const val RECYCLE_WINDOW_MS = 200L
    private const val COLUMNS = 12
    private const val MIN_CHUNK_PX = 8
    private const val MAX_PARTICLES = 120
    private const val HFR_THRESHOLD = 90f

    /**
     * 短窗口内这么多行被 detach，就判定为「列表重建 / 数据批量更新」，不是删除。
     * 聊天页打开、关闭、`onViewUpdate count=N` 都会触发这种批量 detach 风暴。
     */
    private const val BURST_WINDOW_MS = 250L
    private const val BURST_LIMIT = 3
    private const val STORM_COOLDOWN_MS = 500L

    // 还原上游使用的两条缓动曲线
    private val settleEasing = PathInterpolator(0.19919473f, 0.010644531f, 0.27920938f, 0.9102539f)
    private val dissolveEasing = PathInterpolator(0f, 0f, 0.58f, 1f)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** View -> 被 RecyclerView 回收的时间戳。 */
    private val recycledAt = WeakHashMap<View, Long>()

    /** View -> 最近一次已知的父容器。 */
    private val knownParent = WeakHashMap<View, WeakReference<ViewGroup>>()

    /** 当前正在播放的粒子层，避免多个删除叠加（弱引用：生命周期由列表的 overlay 持有）。 */
    private var activeOverlay: WeakReference<ParticleDrawable>? = null

    private var burstWindowStart = 0L
    private var burstCount = 0
    private var stormUntil = 0L

    private var stormLogAt = 0L

    /**
     * 「这段时间内别放删除动画」的最后截止时刻（uptimeMillis）。
     *
     * 场景：[QqMusicOrder] 把用户自己发的点歌指令拦在发送之前（清空输入框、由模块自己出卡片/语音）。
     * 宿主可能先把这条乐观插入的消息行挂上、发现文本被清空后又把它撤掉 —— 那是**我们主动取消的发送**，
     * 不是用户删除消息。不抑制的话，用户每发一次点歌指令就会看到一次删除碎裂特效（用户 2026-09-25 反馈
     * 「语音指令消息不应该出现删除特效，直接拦截发送」）。
     */
    @Volatile
    private var suppressedUntil = 0L

    /** 让接下来 [windowMs] 毫秒内的行 detach 都不播放删除动画（可从任意线程调用）。 */
    fun suppressDeletionAnimation(windowMs: Long = SUPPRESS_DEFAULT_MS) {
        suppressedUntil = SystemClock.uptimeMillis() + windowMs
    }

    private const val SUPPRESS_DEFAULT_MS = 1_500L

    @Volatile
    private var installed = false

    /**
     * 高刷屏判定。
     *
     * 不能用 `DisplayMetrics.refreshRate`（API 30 才有，编译期就解析不到），
     * 改用从 API 1 就存在的 [android.view.Display.getRefreshRate]。
     */
    @Suppress("DEPRECATION")
    private val isHighRefreshRate: Boolean
        get() = runCatching {
            val windowManager = HostInfo.application
                .getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            val display = windowManager?.defaultDisplay ?: return@runCatching false
            display.refreshRate >= HFR_THRESHOLD
        }.getOrDefault(false)

    override fun onEnable() {
        if (installed) return
        installed = true
        burstCount = 0
        burstWindowStart = 0L
        stormUntil = 0L
        WeChatMessageViewApi.addLifecycleListener(this)
    }

    override fun onDisable() {
        installed = false
        runCatching { WeChatMessageViewApi.removeLifecycleListener(this) }
        activeOverlay?.get()?.cancel()
        activeOverlay = null
        recycledAt.clear()
        knownParent.clear()
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        (view.parent as? ViewGroup)?.let { knownParent[view] = WeakReference(it) }
        recycledAt.remove(view)
    }

    override fun onMessageViewRecycled(view: View, message: MessageInfo) {
        // RecyclerView 回收 = 滚动复用，绝不能当成删除
        recycledAt[view] = SystemClock.uptimeMillis()
        knownParent.remove(view)
    }

    override fun onMessageViewDetached(view: View, message: MessageInfo, rebound: Boolean) {
        if (!installed) return

        // 重绑不是删除：发送消息、批量刷新、会话去重都会把同一行绑到新消息上。
        // 判据来自派发点（[WeChatMessageViewApi.IMessageViewLifecycleListener.onMessageViewDetached]），
        // 不再依赖「view.parent 是否还在」这类启发式 —— 用户实测「每发一条消息先演一次删除特效」
        // 就是这条路径被误判造成的。
        if (rebound) return

        // 外部显式抑制：被发送拦截取消掉的点歌指令行不是「删除」，不播特效
        if (SystemClock.uptimeMillis() < suppressedUntil) return

        val list = (view.parent as? ViewGroup)?.takeIf { isRecyclerViewLike(it) }
            ?: knownParent[view]?.get()?.takeIf { isRecyclerViewLike(it) }
            ?: return

        // 退出会话 / 页面销毁中：整页会瞬间 detach 全部行，绝不能在这里做任何工作
        if (!list.isAttachedToWindow) return
        if (view.width <= 0 || view.height <= 0) return
        if (list.childCount < 3) return
        if (isContainerScrolling(list)) return

        val now = SystemClock.uptimeMillis()
        if (isStorm(now)) return

        val msgId = runCatching { view.getTag(ROW_TAG_MSG_ID) }.getOrNull()
        val left = view.left + view.translationX.toInt()
        val top = view.top + view.translationY.toInt()

        // 这一行的「行数基线」：真删除必然让列表少一行（removeView 之后 childCount 减一），
        // 而发送消息引起的行替换/重排是「先摘一行再挂一行」，行数不变。见下面 post 块里的判据。
        val rowsBefore = list.childCount

        // 快照必须在重新布局之前抓：此刻这一行还是删除前的样子（坐标也在列表坐标系里）
        val snapshot = runCatching { capture(view) }.getOrNull()

        // baseline 同样必须在重新布局之前抓：此刻其余行还是删除前的坐标
        val baseline = captureBaseline(list)

        // 延后一帧：真正被删除的行不会收到 onMessageViewRecycled
        mainHandler.post {
            if (!installed) return@post
            if (SystemClock.uptimeMillis() < stormUntil) return@post
            val recycled = recycledAt[view]
            if (recycled != null && SystemClock.uptimeMillis() - recycled < RECYCLE_WINDOW_MS) {
                snapshot?.recycle()
                return@post
            }
            if (!list.isAttachedToWindow) {
                snapshot?.recycle()
                return@post
            }
            // 这一行自己又被挂回列表 ⇒ 重绑，不是删除
            if (view.parent != null && view.isAttachedToWindow) {
                snapshot?.recycle()
                return@post
            }
            // 携带的消息身份还在列表里 ⇒ 重建/复用/行替换，不是删除
            val identityStillBound = msgId != null && containsMessageId(list, msgId)
            if (identityStillBound) {
                snapshot?.recycle()
                return@post
            }
            // 到这里才允许放动画，且还要再过一道「行数」判据：
            // 真删除必然少一行；行数没少（行替换/重建）就不放。
            // msgId 可读且不在列表里（identityStillBound 为假）也算确认删除。
            val rowsDropped = list.childCount < rowsBefore
            val identityGone = msgId != null
            if (!rowsDropped && !identityGone) {
                snapshot?.recycle()
                return@post
            }
            if (activeOverlay?.get() != null) {
                snapshot?.recycle()
                return@post
            }

            if (snapshot != null) playDissolve(list, snapshot, left, top)
            settleRows(list, baseline)
        }
    }

    /**
     * 短时间内的批量 detach 视为列表重建，直接放弃（不抓快照、不入队动画）。
     * 这是「打开任意一个会话都是某个会话 / 卡死」那次事故的核心防线。
     */
    private fun isStorm(now: Long): Boolean {
        if (now - burstWindowStart >= BURST_WINDOW_MS) {
            burstWindowStart = now
            burstCount = 0
        }
        burstCount++
        if (burstCount > BURST_LIMIT) {
            stormUntil = now + STORM_COOLDOWN_MS
            if (now - stormLogAt > 5_000L) {
                stormLogAt = now
                WeLogger.d(TAG, "detach burst ($burstCount rows), skipping deletion animation")
            }
        }
        return now < stormUntil
    }

    /**
     * 消息列表所在的类：微信自己的 androidx 是**混淆过的**（栈里是
     * `androidx.recyclerview.widget.r0`），我们工程里编译进去的同名类又是另一个类身份，
     * 所以既不能 `is RecyclerView` 也不能拿我们的类去比，只能按包名 / 方法特征判断。
     */
    private fun isRecyclerViewLike(view: View): Boolean {
        val name = view.javaClass.name
        if (name.startsWith("androidx.recyclerview.widget")) return true
        if (name.contains("RecyclerView")) return true
        val cls = view.javaClass
        return hasMethod(cls, "getScrollState") && hasMethod(cls, "getAdapter")
    }

    private fun hasMethod(cls: Class<*>, name: String): Boolean =
        runCatching { cls.getMethod(name) != null }.getOrDefault(false)

    private fun containsMessageId(list: ViewGroup, msgId: Any): Boolean {
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) ?: continue
            if (runCatching { child.getTag(ROW_TAG_MSG_ID) }.getOrNull() == msgId) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun capture(view: View): Bitmap {
        val picture = Picture()
        val canvas = picture.beginRecording(view.width, view.height)
        view.draw(canvas)
        picture.endRecording()
        return Bitmap.createBitmap(picture)
    }

    private fun captureBaseline(container: ViewGroup): HashMap<Any, Float> {
        val out = HashMap<Any, Float>(max(8, container.childCount))
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            val key: Any = child.getTag(ROW_TAG_MSG_ID) ?: child
            out.putIfAbsent(key, child.translationY + child.top)
        }
        return out
    }

    private fun isContainerScrolling(container: ViewGroup): Boolean = runCatching {
        val m = container.javaClass.getMethod("getScrollState")
        (m.invoke(container) as Int) != 0
    }.getOrDefault(false)

    // ========== 归位 ==========

    private fun settleRows(list: ViewGroup, baseline: HashMap<Any, Float>) {
        if (baseline.isEmpty()) return
        val durationMs = if (isHighRefreshRate) SETTLE_HFR_MS else SETTLE_MS
        val easing = if (isHighRefreshRate) dissolveEasing else settleEasing
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) ?: continue
            val key: Any = child.getTag(ROW_TAG_MSG_ID) ?: child
            val oldTop = baseline[key] ?: continue
            val offset = oldTop - child.top
            if (abs(offset) < 0.5f) continue
            child.animate().cancel()
            child.translationY = offset
            child.animate()
                .translationY(0f)
                .setDuration(durationMs)
                .setInterpolator(easing)
                .start()
        }
    }

    // ========== 粒子 ==========

    /**
     * 播放一次碎裂动画。
     *
     * **不能**新建 View 挂到任何容器上（见类注释：挂消息列表会闪退，挂宿主容器会整页 relayout）。
     * 粒子层是加在 `list.overlay` 上的 [ParticleDrawable]：不参与测量/布局，绘制时坐标系
     * 就是列表自身的坐标系，所以 `left` / `top` 直接用行的坐标即可。
     */
    private fun playDissolve(list: ViewGroup, snapshot: Bitmap, left: Int, top: Int) {
        val particles = runCatching { buildParticles(snapshot, left, top) }.getOrDefault(emptyList())
        if (particles.isEmpty()) {
            snapshot.recycle()
            return
        }

        val layer = ParticleDrawable(list, snapshot, particles)
        val added = runCatching { list.overlay.add(layer) }.isSuccess
        if (!added) {
            snapshot.recycle()
            return
        }
        activeOverlay = WeakReference(layer)
        layer.start {
            if (activeOverlay?.get() === layer) activeOverlay = null
        }
    }

    private fun buildParticles(source: Bitmap, left: Int, top: Int): List<Particle> {
        if (source.width <= 0 || source.height <= 0) return emptyList()

        var chunk = max(MIN_CHUNK_PX, source.width / COLUMNS)
        // 限制总粒子数：整行很高时放大切片，避免一次 drawBitmap 上百次
        val estimated = (source.width / chunk + 1).toLong() * (source.height / chunk + 1).toLong()
        if (estimated > MAX_PARTICLES) {
            val area = source.width.toLong() * source.height.toLong()
            chunk = max(MIN_CHUNK_PX, sqrt(area.toDouble() / MAX_PARTICLES).toInt())
        }

        val out = ArrayList<Particle>(64)
        val rnd = Random(SystemClock.uptimeMillis())
        val cx = source.width / 2f
        val cy = source.height / 2f

        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val w = minOf(chunk, source.width - x)
                val h = minOf(chunk, source.height - y)
                if (w > 0 && h > 0) {
                    val dx = (x + w / 2f) - cx
                    val dy = (y + h / 2f) - cy
                    val len = max(1f, sqrt(dx * dx + dy * dy))
                    val speed = 70f + rnd.nextFloat() * 130f
                    out.add(
                        Particle(
                            srcX = x,
                            srcY = y,
                            width = w,
                            height = h,
                            startX = (left + x).toFloat(),
                            startY = (top + y).toFloat(),
                            vx = dx / len * speed + (rnd.nextFloat() - 0.5f) * 60f,
                            vy = dy / len * speed + (rnd.nextFloat() - 0.5f) * 60f - 40f,
                            rotation = (rnd.nextFloat() - 0.5f) * 90f,
                        )
                    )
                }
                x += chunk
            }
            y += chunk
        }
        return out
    }

    /** 粒子只做 src/dst 裁剪，全部共用 [ParticleDrawable.snapshot] 这一张位图。 */
    private class Particle(
        val srcX: Int,
        val srcY: Int,
        val width: Int,
        val height: Int,
        val startX: Float,
        val startY: Float,
        val vx: Float,
        val vy: Float,
        val rotation: Float,
    )

    /**
     * 列表 `ViewOverlay` 上的粒子层。
     *
     * 它是纯 [Drawable]，不是 View：不参与测量 / 布局，也不进入任何 ViewGroup 的 children，
     * 所以既不可能让消息列表出现「没有 ViewHolder 的子 View」（2026-09-25 闪退根因），
     * 也不会触发宿主容器的 relayout。
     *
     * 绘制坐标系 = **列表自身的坐标系**（overlay 是列表 `draw()` 的第 5 步，在子 View 之后绘制），
     * 所以 `startX` / `startY` 直接就是行在列表里的坐标，不需要任何窗口位置换算。
     * 重绘显式打在列表本体上（[View.invalidate]），不依赖 overlay 自身的 attach 状态。
     */
    private class ParticleDrawable(
        private val list: ViewGroup,
        private val snapshot: Bitmap,
        private val particles: List<Particle>,
    ) : Drawable() {

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val src = Rect()
        private val dst = RectF()

        /** 粒子落到列表底部之外就跳过绘制（与 bounds 无关，避免被 overlay 改 bounds 影响）。 */
        private val limitY = list.height.toFloat()

        private var elapsed = 0L
        private var duration = DISSOLVE_MS
        private var lastFrame = 0L
        private var ticking = false
        private var finished = false
        private var onFinished: (() -> Unit)? = null

        private val frame = object : Runnable {
            override fun run() {
                if (!ticking) return
                val now = SystemClock.uptimeMillis()
                elapsed += (now - lastFrame).coerceAtMost(48L)
                lastFrame = now
                if (elapsed >= duration) {
                    finish()
                    return
                }
                runCatching { list.invalidate() }
                runCatching { list.postOnAnimation(this) }
            }
        }

        /** 硬兜底：帧回调被系统打断（页面切换 / 掉帧）也必须收尾，绝不留下残留粒子层。 */
        private val guard = Runnable { finish() }

        init {
            setBounds(0, 0, max(1, list.width), max(1, list.height))
        }

        fun start(onFinished: () -> Unit) {
            this.onFinished = onFinished
            lastFrame = SystemClock.uptimeMillis()
            mainHandler.postDelayed(guard, duration + 300L)
            ticking = true
            runCatching { list.invalidate() }
            runCatching { list.postOnAnimation(frame) }
        }

        /** 外部强制收尾（功能被关闭 / 列表销毁）。 */
        fun cancel() {
            finish()
        }

        private fun finish() {
            if (finished) return
            finished = true
            ticking = false
            mainHandler.removeCallbacks(guard)
            runCatching { list.removeCallbacks(frame) }
            runCatching { list.overlay.remove(this) }
            runCatching { snapshot.recycle() }
            val cb = onFinished
            onFinished = null
            cb?.invoke()
        }

        override fun draw(canvas: Canvas) {
            if (finished) return
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            if (progress >= 1f) return

            paint.alpha = ((1f - progress) * 255f).toInt().coerceIn(0, 255)
            val t = dissolveEasing.getInterpolation(progress) * duration / 1000f

            for (p in particles) {
                val x = p.startX + p.vx * t
                val y = p.startY + p.vy * t + 260f * t * t
                if (y > limitY) continue
                src.set(p.srcX, p.srcY, p.srcX + p.width, p.srcY + p.height)
                dst.set(x, y, x + p.width, y + p.height)
                if (p.rotation == 0f) {
                    canvas.drawBitmap(snapshot, src, dst, paint)
                } else {
                    val rotate = canvas.save()
                    canvas.rotate(p.rotation * progress, dst.centerX(), dst.centerY())
                    canvas.drawBitmap(snapshot, src, dst, paint)
                    canvas.restoreToCount(rotate)
                }
            }
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
