package dev.joker.features.items.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.api.core.models.MessageInfo
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.preferences.hotPrefOption
import dev.joker.reflekt.utils.Modifiers
import dev.joker.reflekt.reflekt
import dev.joker.utils.HostInfo
import dev.joker.utils.WeLogger
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

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

    /** 【第 48 轮】宿主头像补定位：最多试几次、每次最小间隔（毫秒）。 */
    private const val HOST_LOOKUP_MAX = 4
    private const val HOST_LOOKUP_INTERVAL_MS = 320L

    /** 【第 48 轮】解码失败后的重试间隔（毫秒）：失败不再永久拉黑。 */
    private const val FAILED_RETRY_MS = 60_000L

    /** 【第 49 轮】网络直链头像的磁盘缓存目录 / 上限 / 超时。 */
    private const val AVATAR_CACHE_DIR = "joker_avatars"
    private const val AVATAR_CACHE_MAX = 400
    private const val AVATAR_NET_TIMEOUT_MS = 4000

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

        drawable.rowRef = WeakReference(view)
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
                // 宿主行内头像：此刻行已经量好，正是定位宿主头像视图的最佳时机。
                // 找不到也没关系：draw 每帧都会再读一次弱引用，宿主异步加载完成即可用。
                if (showSender) {
                    MaskHostAvatar.viewOf(view)?.let { state.drawable.hostAvatarRef = WeakReference(it) }
                }
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

        /** 宿主行视图（弱引用）：draw 时用它校正 bounds（行变高后遮罩必须跟着变高）。 */
        var rowRef: WeakReference<View>? = null
            set(value) {
                if (value?.get() !== field?.get()) {
                    // 【第 48 轮】换行 / 换消息：宿主头像的补定位状态必须跟着重置，
                    // 否则这一次绑定的第一帧就已经是「试满 4 次」，真头像永远补不上。
                    hostAvatarRef = null
                    hostLookupTries = 0
                    lastHostLookupAt = 0L
                    lastHostSource = null
                    hostCopy = null
                }
                field = value
            }

        /** 宿主行里宿主**自己已经加载好**的头像视图（弱引用）。零解码复用，优先于自行解码。 */
        var hostAvatarRef: WeakReference<ImageView>? = null

        /** 【第 48 轮】宿主头像的补定位节流状态（拿不到真头像时按间隔再试几次）。 */
        private var hostLookupTries = 0
        private var lastHostLookupAt = 0L

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

        /** 【第 47 轮】卡片底色改成极淡的纵向渐变（上浅下深），比纯平色有层次且零额外绘制成本。 */
        private val bgTop: Int =
            blend(bgColor, if (isDarkBg) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.04f)
        private val bgBottom: Int =
            blend(bgColor, if (isDarkBg) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.10f)

        /** 左侧竖向点缀条颜色（跟随深浅主题的强调色，给整行一个「被屏蔽」的视觉锚点）。 */
        private val accentColor: Int = when (themeIndex) {
            1 -> 0xFF7A7A80.toInt()
            2 -> 0xFFB9B4FF.toInt()
            else -> if (isDarkBg) 0xFFB9B4FF.toInt() else 0xFF5B5BD6.toInt()
        }

        private var gradient: LinearGradient? = null
        private var gradientHeight = -1

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkBg) 0x33FFFFFF else 0x1A000000
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.FILL
        }
        /** 头像外圈：亮底给白环，暗底给一层淡描边，让头像从卡片上「浮」起来。 */
        private val avatarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkBg) 0x22FFFFFF else 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
        }
        private val avatarHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkBg) 0x14FFFFFF else 0x0F000000
            style = Paint.Style.FILL
        }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val chevronPath = Path()
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
            // 【第 47 轮】bounds 自愈：绑定时宿主行往往还没量好（宽高为 0 / 只有上半截），
            // 旧实现只在那一帧 setBounds 一次，之后行变高也永远盖不全 —— 实机观感就是
            // 「遮罩只盖住头像那一行，下面的消息正文照样看得见」。这里每帧对齐一次，
            // 成本只是一次宽高比较（无分配）。
            rowRef?.get()?.let { row ->
                val w = row.width
                val h = row.height
                if (w > 0 && h > 0 && (bounds.width() != w || bounds.height() != h)) {
                    setBounds(0, 0, w, h)
                }
            }
            val b = bounds
            if (b.isEmpty) return
            rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val r = radius.coerceAtMost(b.height() / 3f)

            // 纵向渐变底：只在高度变化时重建 shader（draw 里零分配）。
            if (gradient == null || gradientHeight != b.height()) {
                gradientHeight = b.height()
                gradient = LinearGradient(
                    0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                    bgTop, bgBottom, Shader.TileMode.CLAMP,
                )
                bgPaint.shader = gradient
            }
            canvas.drawRoundRect(rect, r, r, bgPaint)
            canvas.drawRoundRect(rect, r, r, borderPaint)

            val h = b.height().toFloat()
            val density = if (h > 0f) (h / 48f).coerceIn(1.2f, 4f) else 2f

            // 左侧竖向点缀条：让「这一行被屏蔽了」在快速滑动时也能一眼扫到。
            val barW = (3f * density).coerceAtMost(b.width() / 60f)
            val barInset = (8f * density).coerceAtMost(h * 0.18f)
            canvas.drawRoundRect(
                RectF(
                    b.left + barInset,
                    b.top + barInset,
                    b.left + barInset + barW,
                    b.bottom - barInset,
                ),
                barW / 2f, barW / 2f, accentPaint,
            )

            if (senderName.isNotEmpty()) {
                val avatarR = (h * 0.24f).coerceIn(16f * density, 30f * density)
                val cx = b.left + barInset + barW + 10f * density + avatarR
                val cy = b.exactCenterY()
                drawAvatar(canvas, cx, cy, avatarR, density)

                val textX = cx + avatarR + 10f * density
                hintPaint.textAlign = Paint.Align.LEFT
                namePaint.textSize = (h * 0.135f).coerceIn(22f, 40f)
                hintPaint.textSize = (h * 0.105f).coerceIn(18f, 32f)
                val name = if (senderName.length > 14) senderName.take(14) + "…" else senderName

                canvas.drawText(
                    name,
                    textX,
                    cy - (namePaint.descent() + namePaint.ascent()) / 2f - namePaint.textSize * 0.42f,
                    namePaint,
                )
                // 提示行前画一个「斜杠圆」小图标（画出来的，不吃资源、不新增字符串）。
                val glyphR = (hintPaint.textSize * 0.34f)
                val glyphCy = cy + hintPaint.textSize * 0.62f
                glyphPaint.strokeWidth = glyphR * 0.28f
                canvas.drawCircle(textX + glyphR, glyphCy - glyphR * 0.18f, glyphR, glyphPaint)
                canvas.drawLine(
                    textX,
                    glyphCy + glyphR * 0.5f,
                    textX + glyphR * 2f,
                    glyphCy - glyphR * 0.85f,
                    glyphPaint,
                )
                canvas.drawText(
                    hint,
                    textX + glyphR * 2f + 6f * density,
                    cy - (hintPaint.descent() + hintPaint.ascent()) / 2f + hintPaint.textSize * 0.62f,
                    hintPaint,
                )

                // 右侧「可点开」指示：一个细箭头（画出来，零资源）。
                val chR = (h * 0.055f).coerceIn(7f * density, 13f * density)
                val chCx = b.right - barInset - chR
                chevronPath.reset()
                chevronPath.moveTo(chCx - chR * 0.5f, cy - chR)
                chevronPath.lineTo(chCx + chR * 0.5f, cy)
                chevronPath.lineTo(chCx - chR * 0.5f, cy + chR)
                glyphPaint.strokeWidth = (2f * density).coerceAtLeast(2f)
                canvas.drawPath(chevronPath, glyphPaint)
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

        private fun drawAvatar(canvas: Canvas, cx: Float, cy: Float, r: Float, density: Float) {
            // 外圈光晕 + 描边：先把头像「托」起来，再画头像本体。
            canvas.drawCircle(cx, cy, r + 2.5f * density, avatarHaloPaint)
            avatarRingPaint.strokeWidth = (1.5f * density).coerceAtLeast(1.5f)

            // 【第 47 轮】第一优先：直接复用宿主这一行里**已经加载好的头像 drawable**。
            // 为什么这是最优解：宿主自己就是把头像画在这儿，它一定有正确的解码结果
            // （包括 wcf:// 虚拟路径、加密缓存等我们摸不到的路径），我们只需把它画进
            // 自己的圆里 —— 零文件 IO、零解码、零额外内存，也不存在「解码失败退化成首字圆」。
            val host = hostRefDrawable()
            if (host != null) {
                canvas.save()
                clipPath.reset()
                clipPath.addCircle(cx, cy, r, Path.Direction.CW)
                canvas.clipPath(clipPath)
                dstRect.set(cx - r, cy - r, cx + r, cy + r)
                host.setBounds(dstRect.left.toInt(), dstRect.top.toInt(), dstRect.right.toInt(), dstRect.bottom.toInt())
                host.draw(canvas)
                canvas.restore()
                canvas.drawCircle(cx, cy, r, avatarRingPaint)
                return
            }

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
                canvas.drawCircle(cx, cy, r, avatarRingPaint)
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
                canvas.drawCircle(cx, cy, r, avatarRingPaint)
            }
        }

        /**
         * 宿主行内头像 drawable 的**副本**（弱引用读取 + 只在宿主换了 drawable 时复制一次）。
         *
         * 为什么要副本：直接给宿主的 drawable `setBounds` 会改到宿主自己的绘制状态
         * （宿主每帧也要用它画真实头像位），副本的 constantState 与原对象共享位图，
         * 内存开销近乎为零。
         */
        private fun hostRefDrawable(): Drawable? {
            currentHostDrawable()?.let { return it }
            // 【第 48 轮】宿主头像没就绪（还没定位到 / 还没加载出来）不是「永久结论」：
            // 每次绘制都按节流补一次定位，宿主把头像画出来之后遮罩上立刻就是真头像。
            relocateHostIfDue()
            return currentHostDrawable()
        }

        /** 节流补定位：最多 [HOST_LOOKUP_MAX] 次、每次间隔 [HOST_LOOKUP_INTERVAL_MS]。 */
        private fun relocateHostIfDue() {
            if (hostLookupTries >= HOST_LOOKUP_MAX) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastHostLookupAt < HOST_LOOKUP_INTERVAL_MS) return
            lastHostLookupAt = now
            val first = hostLookupTries == 0
            hostLookupTries++
            val row = rowRef?.get() ?: return
            // 第一次保留缓存（视图身份通常不变），之后再找不到就强制重新定位
            // （宿主重绑/换肤后可能换掉了头像视图，缓存里那个永远拿不到真实头像）。
            val view = runCatching { MaskHostAvatar.viewOf(row, force = !first) }.getOrNull()
            if (view != null) hostAvatarRef = WeakReference(view)
            // 宿主是异步把头像加载进那个 ImageView 的，它不会带着我们的 overlay 一起重绘，
            // 所以这里补一次行重绘（上限 4 次、间隔 320ms，且只在遮罩仍挂着时）。
            if (view == null || currentHostDrawable() == null) {
                runCatching { row.postDelayed({ runCatching { row.invalidate() } }, HOST_LOOKUP_INTERVAL_MS) }
            }
        }

        private fun currentHostDrawable(): Drawable? {
            val view = hostAvatarRef?.get() ?: return null
            val src = runCatching { view.drawable }.getOrNull() ?: return null
            // 明显是「空位图占位」的直接放弃，交给下一级兜底（首字圆），别画成一片空白。
            if (src is android.graphics.drawable.BitmapDrawable && src.bitmap == null) return null
            // 纯色占位（微信还没加载出头像时的灰底）没有复用价值，直接走下一级兜底。
            if (src is android.graphics.drawable.ColorDrawable) return null
            if (src === lastHostSource) return hostCopy
            lastHostSource = src
            hostCopy = runCatching { src.constantState?.newDrawable() }.getOrNull() ?: src
            return hostCopy
        }

        private var lastHostSource: Drawable? = null
        private var hostCopy: Drawable? = null

        /**
         * 宿主/框架可能调用 setAlpha 做淡入淡出 —— 这里**忽略**它：底色必须恒为完全不透明，
         * 否则又会出现「半透明能透出正文」的老问题（用户本轮核心投诉）。
         */
        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        override fun getOpacity(): Int = PixelFormat.OPAQUE

        /** 按 [ratio] 把 [overlay] 混进 [base]（用来生成同色系的渐变上下端，零资源）。 */
        private fun blend(base: Int, overlay: Int, ratio: Float): Int {
            val r = mix(Color.red(base), Color.red(overlay), ratio)
            val g = mix(Color.green(base), Color.green(overlay), ratio)
            val b = mix(Color.blue(base), Color.blue(overlay), ratio)
            return Color.argb(255, r, g, b)
        }

        private fun mix(a: Int, b: Int, ratio: Float): Int =
            (a + ((b - a) * ratio)).toInt().coerceIn(0, 255)
    }

    /**
     * 【第 47 轮】在宿主行里定位**宿主自己的头像视图**。
     *
     * 用户实机反馈：遮盖层里群成员头像一直出不来，只能退化成首字彩圆。第 46 轮的
     * 多来源解码（真实路径 / `wcf://` 虚拟路径）在这台机器上仍然全部失败
     * （日志：`avatar: decode failed for 'wxid_…' (candidates=2)`）——因为新版微信的头像
     * 走的是宿主内部的加密缓存与 VFS，外部代码很难稳定复现。
     *
     * 换个思路：宿主**自己**已经把这张头像画在这一行里了（用户看得到微信原生头像），
     * 我们只需要把它的 drawable 拿过来画进遮盖层的圆里 —— 零 IO、零解码、恒定成本，
     * 而且永远与微信显示的头像一致。
     *
     * 定位规则（只做一次，结果按行缓存）：行视图树里**最靠左**的那个「近似正方形且
     * 边长在 24dp ~ 72dp」的 ImageView —— 微信聊天行的头像恰好满足这三个条件
     * （气泡里的图片/表情要么更大要么明显偏右）。
     */
    private object MaskHostAvatar {

        private val cache = Collections.synchronizedMap(WeakHashMap<View, ImageView>())

        /**
         * 取这一行的宿主头像视图。
         *
         * [force] = true 时丢弃缓存重新定位：宿主行在重绑/换肤后可能换掉了头像视图，
         * 而缓存里那个虽然还 attached、却永远拿不到真实头像（drawable 一直是占位色）。
         */
        fun viewOf(row: View, force: Boolean = false): ImageView? {
            if (force) cache.remove(row)
            cache[row]?.let { if (it.isAttachedToWindow) return it }
            val found = runCatching { locate(row) }.getOrNull() ?: return null
            cache[row] = found
            return found
        }

        /**
         * 定位宿主行内的头像视图（第 48 轮重写）。
         *
         * 第 47 轮在「绑定的那一帧」量尺寸，而那时宿主行的子视图宽高常常还是 0
         * （RecyclerView 尚未量完 / 行刚 inflate），于是 `w in minPx..maxPx` 全部落空、
         * 返回 null —— 遮罩层就永久退化成首字彩圆。用户截图里「群成员头像加载不出来」
         * 主要就是这一条（定位失败），其次才是解码。
         *
         * 现在三条改进：
         *  ① 尺寸优先取实测值，取不到退到 `layoutParams`；两者都没有（还没量）时，
         *     按「有没有真实位图 / 类名像不像头像」判定，不再一票否决；
         *  ② 打分排序：有真实位图的优先，其次最靠左（群聊里最左就是发送者头像）；
         *  ③ 找不到时**不写缓存**，下一次绘制还能再试（配合 draw 里的节流重定位）。
         */
        private fun locate(row: View): ImageView? {
            val density = row.resources.displayMetrics.density
            val minPx = (18f * density).toInt()
            val maxPx = (96f * density).toInt()
            var best: ImageView? = null
            var bestScore = Int.MAX_VALUE
            var depthGuard = 0

            /** 单边尺寸：实测优先，其次 layoutParams；都拿不到返回 0（未知）。 */
            fun edgeOf(v: View, measured: Int, fromLp: Int): Int {
                if (measured > 0) return measured
                return if (fromLp > 0) fromLp else 0
            }

            fun walk(view: View, depth: Int) {
                if (depth > 8 || depthGuard > 200) return
                depthGuard++
                if (view is ImageView && view.visibility == View.VISIBLE) {
                    val lp = view.layoutParams
                    val w = edgeOf(view, view.width, lp?.width ?: 0)
                    val h = edgeOf(view, view.height, lp?.height ?: 0)
                    val known = w > 0 && h > 0
                    val real = hasRealBitmap(view)
                    val avatarish = view.javaClass.name.contains("Avatar", ignoreCase = true)
                    if (known) {
                        val square = abs(w - h) <= (maxOf(w, h) / 2)
                        if (w in minPx..maxPx && h in minPx..maxPx && square) {
                            val left = runCatching { offsetLeft(row, view) }.getOrDefault(Int.MAX_VALUE)
                            val score = (if (real) 0 else 100_000) + left.coerceAtMost(99_000)
                            if (score < bestScore) {
                                bestScore = score
                                best = view
                            }
                        }
                    } else if (real || avatarish) {
                        // 还没量到尺寸，但它已经有真实位图 / 类名就是头像视图 → 明显优于「首字圆」。
                        val left = runCatching { offsetLeft(row, view) }.getOrDefault(Int.MAX_VALUE)
                        val score = (if (real) 0 else 100_000) + 200_000 + left.coerceAtMost(99_000)
                        if (score < bestScore) {
                            bestScore = score
                            best = view
                        }
                    }
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
                }
            }

            walk(row, 0)
            return best
        }

        /** 这个 ImageView 上是否已经是一张**真实位图**（而不是占位色 / 空 drawable）。 */
        private fun hasRealBitmap(view: ImageView): Boolean = runCatching {
            when (val d = view.drawable) {
                is android.graphics.drawable.BitmapDrawable -> d.bitmap != null
                null -> false
                is android.graphics.drawable.ColorDrawable -> false
                else -> d.intrinsicWidth > 0 && d.intrinsicHeight > 0
            }
        }.getOrDefault(false)

        /** 目标视图相对行左边缘的横向偏移（拿不到就退化成「越深越靠右」，不影响正确性）。 */
        private fun offsetLeft(row: View, target: View): Int {
            var x = 0
            var cur: View? = target
            while (cur != null && cur !== row) {
                x += cur.left
                val parent = cur.parent
                cur = if (parent is View) parent else null
            }
            return x
        }
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

        /**
         * 【第 48 轮】失败结果不再永久拉黑：宿主 VFS 解析器往往在启动早期还没就绪
         * （DexKit 尚在解析、Resources 还没注入），一次抢跑失败就永久退化成首字圆。
         * 现在记下失败时刻，[FAILED_RETRY_MS] 之后允许再试一次。
         */
        private val failedAt = Collections.synchronizedMap(HashMap<String, Long>())

        private fun recentlyFailed(key: String): Boolean {
            val at = failedAt[key] ?: return false
            if (SystemClock.elapsedRealtime() - at < FAILED_RETRY_MS) return true
            failedAt.remove(key)
            return false
        }

        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "joker-mask-avatar").apply { isDaemon = true }
        }

        /**
         * 宿主 VFS 的「读文件」静态方法（`MicroMsg.VFSFileOp` 的 `(String) -> InputStream`）。
         *
         * 【第 46 轮】微信新版的头像只以 `wcf://avatar/xx/yy/user_xxx.png` 这类**虚拟路径**
         * 存在（`img_flag.reserved*` 里拿到的就是它），`BitmapFactory.decodeFile()` 一定拿不到
         * —— 这就是用户截图里「群成员头像加载不出来、只剩一个字母圆」的根因。
         * 只有借宿主自己的 VFS 引擎（同一个 `MicroMsg.VFSFileOp`，语音/朋友圈大图都在用）
         * 才能把虚拟路径读成字节流。
         *
         * 解析失败（DexKit 未就绪、该版本没有这个方法）**不缓存 null**：下次请求再试一次，
         * 否则一次抢跑就会让本进程内所有头像永久退化成首字圆。
         */
        @Volatile private var vfsReadResolved = false
        @Volatile private var vfsReadMethod: java.lang.reflect.Method? = null

        private fun vfsRead(): java.lang.reflect.Method? {
            if (vfsReadResolved) return vfsReadMethod
            val found = runCatching {
                WeMessageApi.classVfs.reflekt().firstMethod {
                    modifiers(Modifiers.STATIC)
                    parameters(String::class)
                    returnType = InputStream::class
                }.self
            }.getOrNull()
            if (found != null) {
                vfsReadMethod = found
                vfsReadResolved = true
            }
            return found
        }

        fun cached(key: String): Bitmap? = runCatching { cache.get(key) }.getOrNull()

        fun request(key: String, onReady: () -> Unit) {
            if (key.isEmpty() || recentlyFailed(key) || cached(key) != null) return
            executor.execute {
                val decoded = runCatching { decodeAvatar(key) }.getOrNull()
                if (decoded == null) {
                    failedAt[key] = SystemClock.elapsedRealtime()
                    return@execute
                }
                failedAt.remove(key)
                cache.put(key, scaleDown(decoded))
                runCatching { onReady() }
            }
        }

        /**
         * 多来源尝试：真实文件路径 → 宿主 VFS（`wcf://` 等虚拟路径）；全失败才返回 null
         * （调用方会退化成首字彩圆）。整个方法只在后台线程调用，主线程零 IO。
         */
        private fun decodeAvatar(wxid: String): Bitmap? {
            val candidates = runCatching { WeDatabaseApi.getAvatarCandidates(wxid) }
                .getOrDefault(emptyList())
            if (candidates.isEmpty()) {
                WeLogger.d(TAG, "avatar: no candidate for '$wxid'")
                return null
            }
            candidates.filter { it.startsWith("/") }.forEach { path ->
                runCatching {
                    if (File(path).isFile) BitmapFactory.decodeFile(path)?.let { return it }
                }
            }
            // 【第 47 轮】虚拟路径 → 真实文件兜底：登录后的头像在磁盘上其实是
            // `<dataDir>/avatar/<xx>/<yy>/user_<md5>.png`，而 img_flag 给的是
            // `wcf://avatar/<xx>/<yy>/user_<md5>.png` —— 两者只差一个根目录。
            // 宿主 VFS 那条路一旦解析不到方法（不同微信版本混淆差异），这里还能救回来。
            val roots = avatarRoots()
            if (roots.isNotEmpty()) {
                candidates.forEach { candidate ->
                    val rel = candidate.substringAfter("://", "").trimStart('/', '\\')
                    if (rel.isEmpty()) return@forEach
                    roots.forEach { root ->
                        runCatching {
                            val file = File(root, rel)
                            if (file.isFile) BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
                        }
                    }
                }
            }

            // 【第 49 轮】网络直链兜底：`制作表情`功能一直在用的就是这条路
            // （`WeDatabaseApi.getAvatarUrl` 拿到的就是 http(s) 直链，用户实机确认它对
            // 「每个群成员 + 私聊对方」都拿得到）。遮盖层以前只解本地文件/宿主虚拟路径，
            // `img_flag` 里存的是直链时直接放弃 → 只能退化成首字圆。
            candidates.forEach { candidate ->
                if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) {
                    downloadAvatar(candidate, wxid)?.let { return it }
                }
            }

            val read = vfsRead()
            if (read == null) {
                WeLogger.d(TAG, "avatar: VFS reader unavailable for '$wxid'")
                return null
            }
            candidates.forEach { candidate ->
                runCatching {
                    (read.invoke(null, candidate) as? InputStream)?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.let { return it }
                    }
                }
            }
            // 【第 48 轮】同一批候选再走一遍「返回 byte[]」的 VFS 读法：微信各版本的
            // `MicroMsg.VFSFileOp` 有「返回 InputStream」与「返回 byte[]」两套签名，
            // 只认前者时某些版本永远解不出头像（实机表现就是清一色首字圆）。
            vfsReadBytes()?.let { bytesRead ->
                candidates.forEach { candidate ->
                    runCatching {
                        val bytes = bytesRead.invoke(null, candidate) as? ByteArray ?: return@forEach
                        if (bytes.isEmpty()) return@forEach
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
                    }
                }
            }
            WeLogger.d(TAG, "avatar: decode failed for '$wxid' (candidates=${candidates.size})")
            return null
        }

        /**
         * 【第 49 轮】网络直链头像：**「制作表情」功能一直在用的那条路**。
         *
         * 用户点名的参考实现就是 `MakeEmoji.kt` → `WeDatabaseApi.getAvatarUrl(sender)`，
         * 它拿到的就是 http(s) 直链，实机确认对「每个群成员 + 私聊对方」都拿得到。
         * 遮盖层以前只认本地文件与宿主虚拟路径，直链直接放弃 → 只能画首字圆。
         *
         * 这里在**后台执行器**上下载一次并落盘缓存（`files/joker_avatars/<md5>.img`），
         * 之后永久命中本地文件、不再走网络；失败照旧只是回退首字圆，不影响任何已有路径。
         */
        private fun downloadAvatar(url: String, key: String): Bitmap? {
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
            val file = cacheFileFor(key)
            if (file.isFile) runCatching { BitmapFactory.decodeFile(file.absolutePath)?.let { return it } }
            runCatching {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = AVATAR_NET_TIMEOUT_MS
                    readTimeout = AVATAR_NET_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Referer", "https://weixin.qq.com/")
                }
                conn.inputStream.use { input ->
                    file.parentFile?.mkdirs()
                    val tmp = File(file.absolutePath + ".tmp")
                    tmp.outputStream().use { output -> input.copyTo(output) }
                    if (tmp.length() > 0L) {
                        if (!tmp.renameTo(file)) {
                            file.delete()
                            tmp.renameTo(file)
                        }
                    } else {
                        tmp.delete()
                    }
                }
                runCatching { conn.disconnect() }
            }.onFailure { WeLogger.d(TAG, "avatar: download failed for '$key'") }
            pruneAvatarCache()
            return runCatching { if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null }.getOrNull()
        }

        private fun cacheFileFor(key: String): File =
            File(File(HostInfo.application.filesDir, AVATAR_CACHE_DIR), md5Hex(key) + ".img")

        /** 磁盘缓存有界：超过 [AVATAR_CACHE_MAX] 个就按最后修改时间删最旧的（后台线程，低频）。 */
        private fun pruneAvatarCache() {
            runCatching {
                val dir = File(HostInfo.application.filesDir, AVATAR_CACHE_DIR)
                val files = dir.listFiles() ?: return
                if (files.size <= AVATAR_CACHE_MAX) return
                files.sortedBy { it.lastModified() }
                    .take(files.size - AVATAR_CACHE_MAX)
                    .forEach { it.delete() }
            }
        }

        private fun md5Hex(value: String): String = runCatching {
            val digest = java.security.MessageDigest.getInstance("MD5")
            digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        }.getOrDefault(value.hashCode().toString())

        /** 同一批 VFS 读法里「返回 byte[]」的变体（惰性解析、失败不缓存）。 */        @Volatile private var vfsBytesResolved = false
        @Volatile private var vfsBytesMethod: java.lang.reflect.Method? = null

        private fun vfsReadBytes(): java.lang.reflect.Method? {
            if (vfsBytesResolved) return vfsBytesMethod
            val found = runCatching {
                WeMessageApi.classVfs.reflekt().firstMethod {
                    modifiers(Modifiers.STATIC)
                    parameters(String::class)
                    returnType = ByteArray::class
                }.self
            }.getOrNull()
            if (found != null) {
                vfsBytesMethod = found
                vfsBytesResolved = true
            }
            return found
        }

        /**
         * 可能的头像根目录（宿主数据目录下的几个候选）。惰性求值，只算一次。
         *
         * 【第 48 轮】补三类：`<data>/avatar`、`<data>/files/avatar`，以及**每个账号目录**下的
         * `<data>/MicroMsg/<md5>/avatar`（微信把头像按「账号目录」分开存，只试 MicroMsg 根
         * 会在多账号/新版本布局上全部落空）。目录探测只在后台线程做一次并缓存。
         */
        private val rootsLazy: List<File> by lazy {
            runCatching {
                val out = LinkedHashSet<File>()
                val dataDir = HostInfo.application.filesDir.parentFile ?: return@lazy emptyList()
                out += dataDir
                out += File(dataDir, "avatar")
                out += File(dataDir, "files")
                out += File(dataDir, "files/avatar")
                val micro = File(dataDir, "MicroMsg")
                out += micro
                out += File(micro, "avatar")
                runCatching { micro.listFiles() }.getOrNull()
                    ?.asSequence()
                    ?.filter { it.isDirectory }
                    ?.take(4)
                    ?.forEach { out += File(it, "avatar") }
                out.filter { it.exists() }
            }.getOrDefault(emptyList())
        }

        private fun avatarRoots(): List<File> = rootsLazy

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
