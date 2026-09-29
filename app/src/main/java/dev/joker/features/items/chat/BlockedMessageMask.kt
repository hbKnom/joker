package dev.joker.features.items.chat

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.api.core.models.MessageInfo
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.preferences.hotPrefOption
import dev.joker.reflekt.reflekt
import dev.joker.utils.WeLogger
import java.util.WeakHashMap

/**
 * 屏蔽消息 · 聊天界面遮盖（Round44 新增）
 *
 * 与「屏蔽消息」（[BlockMessagesRuntime]：只吞通知 + 标记已读）是**互补的两件事**：
 * 本功能把命中规则的**对方消息**在聊天列表里直接盖住（看不到原文），点一下该行才显示。
 * 两者各有独立开关，可只开其一，也可都开；共用同一份规则存储（`block_messages_rules_json`）。
 *
 * 用户 2026-09-29 的实机反馈原文：
 * 「屏蔽消息我以为是指定的对方发送任意消息在 UI 层面我都被遮盖怎么看也看不到，
 *   除了我点击一下某条被遮盖的消息才显示，没想到现在的效果居然只是屏蔽通知并标为已读。」
 * 本类就是补上这句期望里的「UI 层面遮盖」。
 *
 * 实现铁律（逐条对应 host-injection-ui-hijack-iron-rules）：
 *  1. **绝不向宿主 itemView / RecyclerView 做 addView** —— 那会产出「无 ViewHolder 的外来子 View」，
 *     踩 GapWorker 预取与 LayoutParams 自检；这里只走 `View.overlay` + 纯 [Drawable] 直绘，
 *     坐标系天然与 itemView 对齐，零子 View 注入、零重布局。
 *  2. **热路径零解析、零查库** —— 这是每条消息绑定都会跑的路径：规则原始串与白名单开关都走
 *     [hotPrefOption]（内存缓存），JSON 只在原始串真正变化时重新 parse 一次；
 *     Drawable 复用同一个实例，不物化内容、不读位图。
 *  3. **状态放 WeakHashMap** —— View 被回收条目自动释放，无泄漏、无全局队列。
 *  4. **绝不成为崩溃源** —— 全部包 runCatching，任何一步失败都只降级成「不遮」，不抛给宿主。
 *  5. **不破坏宿主交互** —— 只有遮罩仍在时才消费 ACTION_DOWN 用于揭示，
 *     其余情况一律返回 false，宿主自己的点击 / 长按照常走。
 *
 * 与 [MessageEntranceAnimation] 同款锚点：`MicroMsg.ChattingDataAdapterV3` 的逐条绑定
 * 方法（hookAfter）+ `getItem(int)` 取消息对象。两个功能各自独立挂钩、互不干扰。
 */
object BlockedMessageMask : SwitchFeature(), IResolveDex {

    override val technicalId = "屏蔽消息遮盖"
    override val nameRes: Int = R.string.feature_chat_block_messages_mask_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes: Int = R.string.feature_chat_block_messages_mask_description

    /**
     * 默认**开启**（可关）。
     *
     * 为什么默认开：用户对「屏蔽消息」的直觉就是「我看不到它」。若默认关闭，用户开了
     * 「屏蔽消息」却仍然看得到对方消息，又是同一句「开了没效果」。
     * 本功能只在「规则非空 **且** 是对方发来的消息」时才盖，没配规则时零副作用；
     * 不想要的人可以在设置里单独关掉它，而不必关掉整个通知拦截。
     */
    override val defaultEnabled: Boolean = true

    private const val TAG = "BlockedMessageMask"

    /** 与「屏蔽消息」共用同一份规则存储（只读；热路径走内存缓存，跨进程最坏 1s 收敛）。 */
    private var rulesJson by hotPrefOption("block_messages_rules_json", "")

    /** 与「屏蔽消息」共用同一份白名单开关。 */
    private var useWhitelist by hotPrefOption("block_messages_use_whitelist", false)

    /** 解析缓存：仅在原始串变化时重新 parse（避免每行 JSON 解析）。 */
    private var cachedRaw: String = "\u0000"
    private var cachedRules: BlockMessagesRules = BlockMessagesRules()

    /**
     * `ChattingDataAdapterV3.getItem(int) -> 消息存储对象`，
     * 用于读取稳定的 `field_msgId` / `field_talker` / `field_content` / `field_isSend`。
     * 类名用 `MicroMsg.ChattingDataAdapterV3` 字符串锁定，方法名 getItem / 单 int 入参在
     * 所有支持版本唯一，故与 [MessageEntranceAnimation] 用同一处锚点、按项目约定不加 allowFailure。
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

    /** 当前挂着遮罩的行 → 遮罩状态。View 被回收时条目自动释放。 */
    private val active = WeakHashMap<View, MaskHolder>()

    /** 已经点开过的消息（按 msgId 记），滚动回来不再重新盖住；上限 512 条防无限增长。 */
    private val revealed = object : LinkedHashMap<Long, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 512
    }

    private class MaskHolder(
        val drawable: MaskDrawable,
        val msgId: Long,
    )

    /** 读规则快照：热路径只做一次字符串比较，串没变就不重新解析 JSON。 */
    private fun currentRules(): BlockMessagesRules {
        val raw = rulesJson
        if (raw == cachedRaw) return cachedRules
        cachedRaw = raw
        cachedRules = runCatching { BlockMessagesRules.parse(raw) }.getOrDefault(BlockMessagesRules())
        return cachedRules
    }

    override fun onEnable() {
        WeMessageApi.methodChattingDataAdapterOnBindViewHolder.hookAfter {
            // 每一条消息绑定都会跑的热路径：参数只在 hook receiver 上可用，
            // 必须在这里取出后交给普通函数；异常一律吞掉，绝不逃逸到宿主。
            val adapter = thisObject ?: return@hookAfter
            val holder = args.getOrNull(0) ?: return@hookAfter
            val position = args.getOrNull(1) as? Int ?: return@hookAfter
            runCatching { handleBind(adapter, holder, position) }
        }
    }

    override fun onDisable() {
        // 只撤遮罩、清状态；不重置 itemView 的触摸监听（遮罩没了它也只返回 false，
        // 行为与从未安装完全一致），避免误删宿主自己的监听器。
        active.keys.toList().forEach { view -> runCatching { clearMask(view) } }
        active.clear()
        synchronized(revealed) { revealed.clear() }
        cachedRaw = "\u0000"
        cachedRules = BlockMessagesRules()
    }

    private fun handleBind(adapter: Any, holder: Any, position: Int) {
        val rules = currentRules()
        val whitelist = useWhitelist
        // 规则为空且非白名单模式时 matchReason 恒返回 null：先短路，省掉整条反射链。
        if (!whitelist && rules.talkers.isEmpty() && rules.keywords.isEmpty() && rules.senderKeywords.isEmpty()) {
            return
        }

        val itemView = holder.reflekt()
            .firstField { name = "itemView"; superclass() }
            .get() as? View ?: return

        val item = methodChattingAdapterGetItem.method.invoke(adapter, position) ?: run {
            clearMask(itemView)
            return
        }
        val info = MessageInfo(item)

        // 只遮「对方发来的」消息：自己发的内容没有屏蔽的必要。
        if (info.isSend != 0) {
            clearMask(itemView)
            return
        }

        val reason = runCatching {
            BlockMessages.matchReason(
                rules = rules,
                useWhitelist = whitelist,
                talker = info.talker,
                sender = info.sender,
                content = info.actualContent,
            )
        }.getOrNull()

        if (reason == null) {
            clearMask(itemView)
            return
        }

        val msgId = info.id
        if (msgId > 0 && synchronized(revealed) { revealed.containsKey(msgId) }) {
            clearMask(itemView)
            return
        }

        applyMask(itemView, msgId, reason)
    }

    private fun applyMask(view: View, msgId: Long, reason: String) {
        val existing = active[view]
        if (existing != null && existing.msgId == msgId) return // 同一行重复 bind，已是遮罩态
        if (existing != null) clearMask(view)                   // View 复用换了消息 → 先撤旧遮罩

        val hint = runCatching {
            view.context.getString(R.string.chat_block_messages_mask_hint)
        }.getOrDefault("已屏蔽 · 点击查看")

        val drawable = MaskDrawable(hint)
        drawable.radius = runCatching { 16f * view.resources.displayMetrics.density }.getOrDefault(16f)

        active[view] = MaskHolder(drawable, msgId)

        view.post {
            runCatching {
                if (active[view]?.drawable !== drawable) return@runCatching
                drawable.setBounds(0, 0, view.width, view.height)
                view.overlay.add(drawable)
                // 只在「遮罩仍在」时消费 ACTION_DOWN 用于揭示；其余一律放行给宿主。
                view.setOnTouchListener { v, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN && active[v]?.drawable === drawable) {
                        reveal(v)
                        true
                    } else {
                        false
                    }
                }
            }.onFailure { WeLogger.e(TAG, "attach mask failed", it) }
        }
    }

    /** 点一下就显示原文：撤掉遮罩，并把 msgId 记进已揭示集合（滚动回来不再盖）。 */
    private fun reveal(view: View) {
        val holder = active.remove(view) ?: return
        if (holder.msgId > 0) {
            synchronized(revealed) { revealed[holder.msgId] = true }
        }
        runCatching { view.overlay.remove(holder.drawable) }
    }

    private fun clearMask(view: View) {
        val holder = active.remove(view) ?: return
        runCatching { view.overlay.remove(holder.drawable) }
    }

    /**
     * 盖住整行的圆角遮罩。
     *
     * 只画一块近不透明圆角矩形 + 一行浅色提示，**不采集任何内容**、不读位图、不查库 ——
     * 这是每行一次绘制的热路径，绘制成本必须恒定且极低。
     */
    private class MaskDrawable(private val hint: String) : Drawable() {

        var radius = 16f

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF2FFFFFF.toInt()
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x14000000
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9A9AA0.toInt()
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val r = radius.coerceAtMost(b.height() / 3f)
            canvas.drawRoundRect(rect, r, r, bgPaint)
            canvas.drawRoundRect(rect, r, r, borderPaint)

            val paint = hintPaint
            paint.textSize = (b.height() * 0.14f).coerceIn(26f, 40f)
            val cx = b.exactCenterX()
            val cy = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(hint, cx, cy, paint)
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            hintPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
