package dev.joker.features.items.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.api.core.models.MessageInfo
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.preferences.hotPrefOption
import dev.joker.reflekt.reflekt
import dev.joker.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 屏蔽消息 · 聊天界面遮盖（Round44 新增 / Round45 大改）
 *
 * 与「屏蔽消息」（[BlockMessagesRuntime]：只吞通知 + 标记已读）是**互补的两件事**：
 * 本功能把命中规则的**对方消息**在聊天列表里直接盖住，点一下切换显示/再次盖住。
 * 两者各有独立开关，可只开其一，也可都开；共用同一份规则存储（`block_messages_rules_json`）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * Round45 依据用户实机反馈（含截图）重做的四点：
 *
 *  1. **遮盖必须「密不透风」**：Round44 用 `0xF2FFFFFF`（约 95% 不透明），
 *     用户截图里能透出背后正文 —— 「那你这么遮盖有何意义」。现在底色一律**完全不透明**
 *     （alpha 固定 255，`setAlpha` 也不再影响底色），并且用圆角卡片 + 细描边保证美观。
 *  2. **遮盖层显示「谁的消息」**：用户原话「可不可以显示被遮盖消息的人的头像和群昵称
 *     群备注，以便知道谁的消息被遮盖」。现在左侧画真实头像（后台线程解码 + LRU 缓存，
 *     未就绪时退化成首字彩色圆），右侧两行显示「昵称/群备注」+「已屏蔽 · 点按查看」。
 *  3. **点按可来回切换**：点一下显示原文，**再点一下重新盖回去**（Round44 是单向后
 *     永久揭示）；并且是否允许点按由「点按可临时查看」开关控制，关掉后完全不消费触摸。
 *  4. **多会话互不影响**：规则判定统一走 [BlockMessages.matchReason]，它已支持会话级
 *     独立规则（perTalker），因此「A 会话全部遮盖」不会污染 B 会话。
 * ══════════════════════════════════════════════════════════════════════════
 *
 * 实现铁律（逐条对应 host-injection-ui-hijack-iron-rules）：
 *  1. **绝不向宿主 itemView / RecyclerView 做 addView** —— 只走 `View.overlay` + 纯
 *     [Drawable] 直绘，坐标系天然与 itemView 对齐，零子 View 注入、零重布局。
 *  2. **热路径零解析、零查库** —— 每条消息绑定都会跑：规则原始串走 [hotPrefOption]
 *     （内存缓存），JSON 只在原始串真正变化时重新 parse；昵称解析走带缓存的
 *     [MaskNameCache]，头像解码走后台线程 + LRU，主线程只做一次 map 查表。
 *  3. **状态放 WeakHashMap / 有界缓存** —— View 被回收自动释放；已揭示集合有上限。
 *  4. **绝不成为崩溃源** —— 全部包 runCatching，任何一步失败只降级成「不遮」。
 *  5. **不破坏宿主交互** —— 只有遮罩仍在且开关允许时才消费 ACTION_DOWN，其余返回 false。
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
     * 本功能只在「规则非空 **且** 是对方发来的消息」时才盖，没配规则时零副作用。
     */
    override val defaultEnabled: Boolean = true

    private const val TAG = "BlockedMessageMask"

    /** 与「屏蔽消息」共用同一份规则存储（只读；热路径走内存缓存，跨进程最坏 1s 收敛）。 */
    private var rulesJson by hotPrefOption("block_messages_rules_json", "")

    /** 与「屏蔽消息」共用同一份白名单开关。 */
    private var useWhitelist by hotPrefOption("block_messages_use_whitelist", false)

    /** 【Round45】遮盖层外观开关（全部走热缓存，绝不在 bind 路径查库）。 */
    private var showSender by hotPrefOption("block_messages_mask_show_sender", true)
    private var clickToReveal by hotPrefOption("block_messages_mask_click_reveal", true)
    private var themeIndex by hotPrefOption("block_messages_mask_theme", 0)

    /** 解析缓存：仅在原始串变化时重新 parse（避免每行 JSON 解析）。 */
    private var cachedRaw: String = "\u0000"
    private var cachedRules: BlockMessagesRules = BlockMessagesRules()

    /**
     * `ChattingDataAdapterV3.getItem(int) -> 消息存储对象`，
     * 用于读取稳定的 `field_msgId` / `field_talker` / `field_content` / `field_isSend`。
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

    /** 已经点开过的消息（按 msgId 记），滚动回来保持「已显示」状态；上限 512 条防无限增长。 */
    private val revealed = object : LinkedHashMap<Long, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 512
    }

    private class MaskHolder(
        val drawable: MaskDrawable,
        val msgId: Long,
        var revealed: Boolean,
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
        // 【Round45】这里必须用 rules.isEmpty（含 perTalker），否则「只配了会话级规则」会被误短路。
        if (!whitelist && rules.isEmpty) {
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
        val senderKey = senderIdentityOf(info)
        val senderName = MaskNameCache.displayNameOf(info.talker, info.sender)
        applyMask(itemView, msgId, senderKey, senderName)
    }

    /** 头像/昵称的查询主体：群聊用「发送人」，私聊用「会话」。 */
    private fun senderIdentityOf(info: MessageInfo): String =
        info.sender.ifEmpty { info.talker }

    private fun applyMask(view: View, msgId: Long, senderKey: String, senderName: String) {
        val existing = active[view]
        if (existing != null && existing.msgId == msgId) {
            // 同一行重复 bind：保持当前状态（含「已点开」），只把 overlay 同步回来。
            syncOverlay(view, existing)
            return
        }
        if (existing != null) clearMask(view) // View 复用换了消息 → 先撤旧遮罩

        val nightMode = runCatching {
            (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }.getOrDefault(false)

        val hint = runCatching {
            view.context.getString(R.string.chat_block_messages_mask_hint)
        }.getOrDefault("已屏蔽 · 点按查看")

        val drawable = MaskDrawable(
            hint = hint,
            senderName = if (showSender) senderName else "",
            themeIndex = themeIndex,
            nightMode = nightMode,
        )
        val density = runCatching { view.resources.displayMetrics.density }.getOrDefault(2f)
        drawable.radius = 14f * density

        val revealedNow = msgId > 0 && synchronized(revealed) { revealed.containsKey(msgId) }
        val state = MaskHolder(drawable, msgId, revealedNow)
        active[view] = state

        // 头像：命中缓存则同步拿到，否则后台线程解码后回调刷新（主线程绝不做 IO/解码）。
        if (showSender && senderKey.isNotEmpty()) {
            val cached = MaskAvatars.cached(senderKey)
            if (cached != null) {
                drawable.avatar = cached
            } else {
                MaskAvatars.request(senderKey) {
                    view.post {
                        runCatching {
                            if (active[view] !== state) return@runCatching
                            state.drawable.avatar = MaskAvatars.cached(senderKey)
                            state.drawable.invalidateSelf()
                            view.invalidate()
                        }
                    }
                }
            }
        }

        view.post {
            runCatching {
                if (active[view] !== state) return@runCatching
                state.drawable.setBounds(0, 0, view.width, view.height)
                // 只在「遮罩仍在」时消费 ACTION_DOWN 用于切换显示/遮盖；其余一律放行给宿主。
                if (clickToReveal) {
                    view.setOnTouchListener { v, ev ->
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN && active[v] === state) {
                            toggleMask(v)
                            true
                        } else {
                            false
                        }
                    }
                }
                syncOverlay(view, state)
            }.onFailure { WeLogger.e(TAG, "attach mask failed", it) }
        }
    }

    /** 按当前状态把 overlay 对齐（已点开 = 移除遮罩但保留触摸监听，随时可再盖回去）。 */
    private fun syncOverlay(view: View, state: MaskHolder) {
        runCatching {
            if (state.revealed) {
                view.overlay.remove(state.drawable)
            } else {
                state.drawable.setBounds(0, 0, view.width, view.height)
                view.overlay.add(state.drawable)
                view.invalidate()
            }
        }
    }

    /**
     * 点按切换：已遮盖 → 显示原文；已显示 → 重新盖回去。
     *
     * Round44 是「点一次就永久揭示」，用户要求改成可来回切换；同时保留「滚动回来后
     * 仍是点开状态」的记忆（记在有界 LinkedHashMap 里）。
     */
    private fun toggleMask(view: View) {
        val state = active[view] ?: return
        state.revealed = !state.revealed
        if (state.msgId > 0) {
            synchronized(revealed) {
                if (state.revealed) revealed[state.msgId] = true else revealed.remove(state.msgId)
            }
        }
        syncOverlay(view, state)
    }

    private fun clearMask(view: View) {
        val state = active.remove(view) ?: return
        runCatching { view.overlay.remove(state.drawable) }
    }

    /**
     * 盖住整行的圆角遮罩（Round45）。
     *
     * 只画一块**完全不透明**圆角卡片 + 头像 + 「昵称 / 提示」两行字；内容一律不采集、
     * 不读消息正文、不查库 —— 绘制成本恒定且极低。
     */
    private class MaskDrawable(
        private val hint: String,
        private val senderName: String,
        themeIndex: Int,
        nightMode: Boolean,
    ) : Drawable() {

        var radius = 14f

        /** 真实头像（可为空 → 退化成首字彩色圆）。 */
        var avatar: Bitmap? = null

        /** 完全**不透明**的底色：用户明确要求「密不透风，不想遮盖后还能看到消息内容」。 */
        private val bgColor: Int = when (themeIndex) {
            1 -> 0xFFFFFFFF.toInt()                 // 纯白卡片
            2 -> 0xFF1B1B1F.toInt()                 // 深色卡片
            else -> if (nightMode) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
        }

        private val isDarkBg: Boolean = run {
            val r = Color.red(bgColor)
            val g = Color.green(bgColor)
            val b = Color.blue(bgColor)
            (r * 0.299f + g * 0.587f + b * 0.114f) < 140f
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkBg) 0x33FFFFFF else 0x1A000000
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkBg) 0xFF8E8E93.toInt() else 0xFF9A9AA0.toInt()
        }
        private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkBg) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
            isFakeBoldText = true
        }
        private val avatarFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val avatarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val avatarBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }
        private val rect = RectF()
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val clipPath = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val r = radius.coerceAtMost(b.height() / 3f)
            canvas.drawRoundRect(rect, r, r, bgPaint)
            canvas.drawRoundRect(rect, r, r, borderPaint)

            val h = b.height().toFloat()
            val density = if (h > 0f) (h / 48f).coerceIn(1.2f, 4f) else 2f

            if (senderName.isNotEmpty()) {
                val avatarR = (h * 0.26f).coerceIn(16f * density, 30f * density)
                val cx = b.left + 12f * density + avatarR
                val cy = b.exactCenterY()
                drawAvatar(canvas, cx, cy, avatarR)

                val textX = cx + avatarR + 10f * density
                namePaint.textSize = (h * 0.135f).coerceIn(22f, 40f)
                hintPaint.textSize = (h * 0.105f).coerceIn(18f, 32f)
                val name = if (senderName.length > 14) senderName.take(14) + "…" else senderName

                canvas.drawText(
                    name,
                    textX,
                    cy - (namePaint.descent() + namePaint.ascent()) / 2f - namePaint.textSize * 0.42f,
                    namePaint,
                )
                canvas.drawText(
                    hint,
                    textX,
                    cy - (hintPaint.descent() + hintPaint.ascent()) / 2f + hintPaint.textSize * 0.62f,
                    hintPaint,
                )
            } else {
                // 不显示发送者：居中的提示文字（底部仍完全不透明）
                hintPaint.textAlign = Paint.Align.CENTER
                hintPaint.textSize = (h * 0.14f).coerceIn(24f, 40f)
                canvas.drawText(
                    hint,
                    b.exactCenterX(),
                    b.exactCenterY() - (hintPaint.descent() + hintPaint.ascent()) / 2f,
                    hintPaint,
                )
            }
        }

        private fun drawAvatar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val bmp = avatar
            if (bmp != null && !bmp.isRecycled) {
                canvas.save()
                clipPath.reset()
                clipPath.addCircle(cx, cy, r, Path.Direction.CW)
                canvas.clipPath(clipPath)
                srcRect.set(0, 0, bmp.width, bmp.height)
                dstRect.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawBitmap(bmp, srcRect, dstRect, avatarBitmapPaint)
                canvas.restore()
            } else {
                // 退化方案：首字彩色圆（零 IO、零解码，头像就绪后自动替换）
                val seed = senderName.hashCode()
                val hue = ((seed % 360) + 360) % 360
                avatarFallbackPaint.color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.42f, 0.82f))
                canvas.drawCircle(cx, cy, r, avatarFallbackPaint)
                val initial = senderName.firstOrNull { !it.isWhitespace() }?.toString().orEmpty()
                if (initial.isNotEmpty()) {
                    avatarTextPaint.textSize = r * 1.05f
                    canvas.drawText(
                        initial,
                        cx,
                        cy - (avatarTextPaint.descent() + avatarTextPaint.ascent()) / 2f,
                        avatarTextPaint,
                    )
                }
            }
        }

        /**
         * 宿主/框架可能调用 setAlpha 做淡入淡出 —— 这里**忽略**它：底色必须恒为完全不透明，
         * 否则又会出现「半透明能透出正文」的老问题（用户本轮核心投诉）。
         */
        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /**
     * 昵称/群备注解析（带缓存）。
     *
     * 只在**命中遮盖规则的行**上调用，并且每对 (talker, sender) 只查一次库；
     * 目的是让遮盖层能显示「谁发的」，同时不给热路径引入重复 DB 查询。
     */
    private object MaskNameCache {

        private val cache = object : LruCache<String, String>(256) {
            override fun sizeOf(key: String, value: String) = 1
        }

        private val empty = ""

        fun displayNameOf(talker: String, sender: String): String {
            if (talker.isEmpty()) return empty
            val key = "$talker|$sender"
            cache.get(key)?.let { return it }
            val resolved = runCatching {
                if (sender.isNotEmpty() && talker != sender) {
                    // 群聊：优先取该成员的「群昵称」
                    val groupName = WeDatabaseApi.getGroupMemberDisplayName(talker, sender)
                    if (groupName.isNotBlank()) groupName
                    else WeDatabaseApi.getDisplayName(sender).ifBlank { sender }
                } else {
                    // 私聊：取该会话的备注/昵称
                    WeDatabaseApi.getDisplayName(talker).ifBlank { talker }
                }
            }.getOrDefault(if (sender.isNotEmpty()) sender else talker)
            val value = resolved.trim()
            if (value.isNotEmpty()) cache.put(key, value)
            return value
        }
    }

    /**
     * 头像位图（后台解码 + 有界 LRU）。
     *
     * 宿主的 512MB 堆与「不卡顿」铁律决定了：主线程绝不做文件 IO / 解码。
     * 这里只解一次、缩放到 96px 以内后进 LRU；**不 recycle**（LRU 淘汰后交给 GC，
     * 避免出现「遮罩仍在绘制但位图已被回收」的 use-after-recycle 崩溃）。
     */
    private object MaskAvatars {

        private val cache = object : LruCache<String, Bitmap>(512) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        private val failed = Collections.synchronizedSet(HashSet<String>())

        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "joker-mask-avatar").apply { isDaemon = true }
        }

        fun cached(key: String): Bitmap? = runCatching { cache.get(key) }.getOrNull()

        fun request(key: String, onReady: () -> Unit) {
            if (key.isEmpty() || failed.contains(key) || cached(key) != null) return
            runCatching {
                val path = WeDatabaseApi.getAvatarUrl(key)
                // 只处理本地文件路径；http(s) 直链在这里不做网络请求（避免引入任何网络开销）
                if (path.isBlank() || !path.startsWith("/")) {
                    failed.add(key)
                    return
                }
                executor.execute {
                    runCatching {
                        val decoded = BitmapFactory.decodeFile(path)
                        if (decoded == null) {
                            failed.add(key)
                            return@execute
                        }
                        val scaled = scaleDown(decoded)
                        cache.put(key, scaled)
                        onReady()
                    }.onFailure { failed.add(key) }
                }
            }.onFailure { failed.add(key) }
        }

        private fun scaleDown(src: Bitmap): Bitmap {
            val max = 96
            val w = src.width
            val h = src.height
            if (w <= max && h <= max) return src
            val ratio = max.toFloat() / maxOf(w, h).toFloat()
            val scaled = Bitmap.createScaledBitmap(src, (w * ratio).toInt(), (h * ratio).toInt(), true)
            if (scaled !== src) runCatching { src.recycle() }
            return scaled
        }
    }
}
