package dev.ujhhgtg.wekit.utils.monet

import dev.ujhhgtg.wekit.utils.WeLogger

/**
 * Authors the replacement WeChat resources. Every `*Bubbles` / `baseVisuals` / `corners` function
 * produces a list of [DrawableTarget]s that [MonetRuntimePackageWriter] turns into the runtime
 * resource package; this object is pure XML authoring and never touches ARSCLib.
 *
 * Upstream 09-25 renamed `MonetCustomOverlays` to `MonetAssetInjector` when the RRO module generator
 * was replaced by runtime injection, but the authored visuals are byte-for-byte the previous ones.
 */
object MonetAssetInjector {

    private const val TAG = "MonetAssetInjector"

    fun baseVisuals(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
        splashIconId: Int,
    ): List<DrawableTarget> = buildList {
        addPair(resolved, "brand.circular.background", oval(palette.accent1_300), oval(palette.accent1_300))
        addPair(
            resolved,
            "launcher.splash.background",
            splash(XmlValue.Color(0xfff4fbf5.toInt()), splashIconId),
            splash(XmlValue.Reference(palette.surfaceDark), splashIconId),
        )
        addPair(
            resolved,
            "chat.brand-action.background",
            simpleSelector(rounded(palette.primaryLight, 8f), rounded(palette.primaryLight, 8f)),
            simpleSelector(rounded(palette.primaryDark, 8f), rounded(palette.primaryDark, 8f)),
        )
        addPair(
            resolved,
            "chat.red-envelope.incoming.normal",
            messageSelector(rounded(palette.accent1_700, 16f), rounded(palette.primaryLight, 16f)),
            messageSelector(rounded(palette.accent1_300, 16f), rounded(palette.primaryDark, 16f)),
        )
        addPair(
            resolved,
            "chat.red-envelope.outgoing.normal",
            messageSelector(rounded(palette.accent1_700, 16f), rounded(palette.primaryLight, 16f)),
            messageSelector(rounded(palette.accent1_300, 16f), rounded(palette.primaryDark, 16f)),
        )
        val paymentLight = paymentSelector(palette.accent1_400, palette.primaryLight)
        val paymentNight = paymentSelector(palette.accent1_400, palette.primaryDark)
        addPair(resolved, "payment.key.primary", paymentLight, paymentNight)
        addPair(resolved, "payment.key.secondary", paymentLight, paymentNight)
        addPair(resolved, "chat.input.transparent-layer", solid(0x00ffffff), solid(0x00000000))
        addPair(
            resolved,
            "main.surface.header.primary",
            header(palette.surfaceLight, palette.surfaceContainerLight),
            header(palette.surfaceDark, palette.surfaceContainerDark),
        )
        addPair(
            resolved,
            "main.surface.header.secondary",
            header(palette.surfaceLight, palette.surfaceContainerLight),
            header(palette.surfaceDark, palette.surfaceContainerDark),
        )
    }

    fun modernBubbles(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        addPair(
            resolved,
            "chat.bubble.incoming.normal",
            rounded(palette.surfaceLight, 16f, ALL_BUBBLE_PADDING),
            rounded(palette.surfaceDark, 16f, ALL_BUBBLE_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.normal",
            rounded(palette.accent2_100, 16f, ALL_BUBBLE_PADDING),
            rounded(palette.neutral2_700, 16f, ALL_BUBBLE_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.incoming.link",
            rounded(palette.surfaceLight, 16f, INCOMING_LINK_PADDING),
            rounded(palette.surfaceDark, 16f, INCOMING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.link",
            rounded(palette.surfaceLight, 16f, OUTGOING_LINK_PADDING),
            rounded(palette.surfaceDark, 16f, OUTGOING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.incoming.link.mask",
            linkMask(0x1a000000, INCOMING_LINK_PADDING),
            linkMask(0x10ffffff, INCOMING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.link.mask",
            linkMask(0x1a000000, OUTGOING_LINK_PADDING),
            linkMask(0x10ffffff, OUTGOING_LINK_PADDING),
        )
        val redLight = messageSelector(rounded(palette.accent1_700, 16f), rounded(palette.primaryLight, 16f))
        val redNight = messageSelector(rounded(palette.accent1_300, 16f), rounded(palette.primaryDark, 16f))
        addPair(resolved, "chat.red-envelope.incoming.alias", redLight, redNight)
        addPair(resolved, "chat.red-envelope.outgoing.alias", redLight, redNight)
        val received = messageSelector(rounded(palette.accent1_400, 16f), rounded(palette.accent1_300, 16f))
        addPair(resolved, "chat.transfer.incoming.received", received, received)
        addPair(resolved, "chat.transfer.outgoing.received", received, received)
        val expired = messageSelector(rounded(palette.accent1_500, 16f), rounded(palette.accent1_400, 16f))
        addPair(resolved, "chat.transfer.incoming.expired", expired, expired)
        addPair(resolved, "chat.transfer.outgoing.expired", expired, expired)
        val voiceLight = voiceSelector(rounded(palette.surfaceLight, 16f))
        val voiceNight = voiceSelector(rounded(palette.surfaceDark, 16f))
        addPair(resolved, "chat.voice-to-text.background", voiceLight, voiceNight)
    }

    fun proBubbles(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        val incomingLight = proBubble(palette.surfaceContainerHighLight, 0x26000000)
        val incomingNight = proBubble(palette.surfaceContainerHighDark, 0x36ffffff)
        val outgoingLight = proBubble(palette.primaryContainerLight, 0x26000000)
        val outgoingNight = proBubble(palette.primaryContainerDark, 0x36ffffff)
        PRO_INCOMING.forEach { addPair(resolved, it, incomingLight, incomingNight) }
        PRO_OUTGOING.forEach { addPair(resolved, it, outgoingLight, outgoingNight) }
        addPair(resolved, "chat.bubble.incoming.link.mask", rounded(0x00000000, 20f), rounded(0x26000000, 20f))
        addPair(resolved, "chat.bubble.outgoing.link.mask", rounded(0x00000000, 20f), rounded(0x26000000, 20f))
    }

    fun classicBubbles(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        addPair(
            resolved,
            "chat.bubble.incoming.normal",
            classicShape(palette.surfaceLight, true, ALL_BUBBLE_PADDING),
            classicShape(palette.surfaceDark, true, ALL_BUBBLE_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.normal",
            classicShape(palette.accent2_100, false, ALL_BUBBLE_PADDING),
            classicShape(palette.neutral2_700, false, ALL_BUBBLE_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.incoming.link",
            classicShape(palette.surfaceLight, true, INCOMING_LINK_PADDING),
            classicShape(palette.surfaceDark, true, INCOMING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.link",
            classicShape(palette.surfaceLight, false, OUTGOING_LINK_PADDING),
            classicShape(palette.surfaceDark, false, OUTGOING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.incoming.link.mask",
            classicLinkMask(true, INCOMING_LINK_PADDING),
            classicLinkMask(true, INCOMING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.link.mask",
            classicLinkMask(false, OUTGOING_LINK_PADDING),
            classicLinkMask(false, OUTGOING_LINK_PADDING),
        )
        val incomingPrimaryLight = classicShape(palette.primaryLight, true, INCOMING_LINK_PADDING)
        val incomingPrimaryNight = classicShape(palette.primaryDark, true, INCOMING_LINK_PADDING)
        val outgoingPrimaryLight = classicShape(palette.primaryLight, false, OUTGOING_LINK_PADDING)
        val outgoingPrimaryNight = classicShape(palette.primaryDark, false, OUTGOING_LINK_PADDING)
        addPair(
            resolved,
            "chat.red-envelope.incoming.normal",
            messageSelector(incomingPrimaryLight, incomingPrimaryLight),
            messageSelector(incomingPrimaryNight, incomingPrimaryNight),
        )
        addPair(
            resolved,
            "chat.red-envelope.outgoing.normal",
            messageSelector(outgoingPrimaryLight, outgoingPrimaryLight),
            messageSelector(outgoingPrimaryNight, outgoingPrimaryNight),
        )
        val incomingMaskLight = classicShape(palette.accent1_700, true, INCOMING_LINK_PADDING)
        val incomingMaskNight = classicShape(palette.accent1_300, true, INCOMING_LINK_PADDING)
        val outgoingMaskLight = classicShape(palette.accent1_700, false, OUTGOING_LINK_PADDING)
        val outgoingMaskNight = classicShape(palette.accent1_300, false, OUTGOING_LINK_PADDING)
        listOf("chat.transfer.incoming.received", "chat.transfer.incoming.expired").forEach {
            addPair(resolved, it, messageSelector(incomingMaskLight, incomingMaskLight), messageSelector(incomingMaskNight, incomingMaskNight))
        }
        listOf("chat.transfer.outgoing.received", "chat.transfer.outgoing.expired").forEach {
            addPair(resolved, it, messageSelector(outgoingMaskLight, outgoingMaskLight), messageSelector(outgoingMaskNight, outgoingMaskNight))
        }
    }

    fun corners(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        addPair(resolved, "chat.input.background", rounded(palette.surfaceLight, 12f), rounded(palette.surfaceDark, 12f))
        addPair(resolved, "chat.quote.background", rounded(0x10000000, 10f), rounded(0x10ffffff, 10f))
        addPair(
            resolved,
            "payment.key.pressed",
            simpleSelector(rounded(0x10000000, 10f), rounded(palette.surfaceLight, 10f)),
            simpleSelector(rounded(palette.surfaceContainerDark, 10f), rounded(palette.surfaceDark, 10f)),
        )
    }

    fun themedIcon(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
        slots: MonetHostTypeSlots = MonetHostTypeSlots.EMPTY,
    ): List<DrawableTarget> {
        // 「拿不到辅助信息 ≠ 功能失败」：主题图标是可选增强，锚点缺失就整块跳过，
        // 绝不因此让整次解析失败（旧实现 requireNotNull 在此处直接抛 IllegalArgumentException）。
        val anchor = resolved["launcher.themed.icon"] ?: run {
            WeLogger.w(TAG, "launcher.themed.icon 未解析，跳过主题图标")
            return emptyList()
        }
        val mipmapTarget = anchor.binding()
        val adaptive = XmlNode(
            "adaptive-icon",
            children = listOf(
                XmlNode("background", listOf(android("drawable", ATTR_DRAWABLE, XmlValue.NamedReference("drawable", "wekit_icon_bg")))),
                XmlNode("foreground", listOf(android("drawable", ATTR_DRAWABLE, XmlValue.NamedReference("drawable", "wekit_icon_fg")))),
                XmlNode("monochrome", listOf(android("drawable", ATTR_DRAWABLE, XmlValue.NamedReference("drawable", "wekit_icon_mono")))),
            ),
        )
        val background = adaptiveIconBinding(resolved, "wekit_icon_bg", 0, slots) ?: return emptyList()
        val foreground = adaptiveIconBinding(resolved, "wekit_icon_fg", 1, slots) ?: return emptyList()
        val monochrome = adaptiveIconBinding(resolved, "wekit_icon_mono", 2, slots) ?: return emptyList()
        return listOf(
            DrawableTarget(background, solid(0xfff4fbf5.toInt()), solid(palette.surfaceDark)),
            DrawableTarget(foreground, foregroundIcon()),
            DrawableTarget(monochrome, monochromeIcon()),
            DrawableTarget(
                mipmapTarget.copy(qualifiers = listOf("-anydpi-v26")),
                adaptive,
                lightQualifiers = "-anydpi-v26",
            ),
        )
    }

    private fun MutableList<DrawableTarget>.addPair(
        resolved: Map<String, MonetResourceNode>,
        role: String,
        light: XmlNode,
        night: XmlNode,
    ) {
        // 这一个角色没解析出来**只少这一张图**，绝不让整包作废。
        //
        // 旧实现是 `requireNotNull(resolved[role]) { role }`：实机日志（2026-09-26）里
        // `resource analysis failed during RESOLVING_ROLES / IllegalArgumentException:
        // launcher.splash.background` 就是它 —— 同一轮里 44 个角色未解析，
        // 只要其中任何一个在被要求注入的名单里，整次解析就归零、莫奈完全不生效。
        val node = resolved[role]
        if (node == null) {
            WeLogger.w(TAG, "角色 $role 未解析，跳过该视觉项")
            return
        }
        add(DrawableTarget(node.binding(), light, night))
    }

    /**
     * The adaptive-icon layers are authored assets rather than WeChat resources, so they borrow the
     * launcher icon's package/type and only swap the name.
     *
     * 它们的 id 必须是**宿主同类型里没被占用的槽位**（[MonetHostTypeSlots.syntheticId]）：
     * 旧实现给的是 `id = 0`，写包时由 ARSCLib 顺序分配 —— 那等于把颜色/路径盖到宿主某个真实
     * 条目上（实机崩溃就是这么来的）。名字仍要写成 `wekit_icon_*`，因为自适应图标 XML 里用
     * `@drawable/wekit_icon_bg` 按名引用，写包时按名解析成 id。
     */
    private fun adaptiveIconBinding(
        resolved: Map<String, MonetResourceNode>,
        name: String,
        sequence: Int,
        slots: MonetHostTypeSlots,
    ): MonetBinding? {
        val anchor = resolved["launcher.themed.icon"]?.binding() ?: return null
        return anchor.copy(
            id = slots.syntheticId("drawable", sequence, fallbackTypeId = (anchor.id ushr 16) and 0xff),
            name = name,
            type = "drawable",
        )
    }

    private fun solid(color: Int): XmlNode = solid(colorValue(color))
    private fun solid(color: XmlValue): XmlNode = XmlNode(
        "shape",
        listOf(android("shape", ATTR_SHAPE, XmlValue.Integer(0))),
        listOf(XmlNode("solid", listOf(android("color", ATTR_COLOR, color)))),
    )

    private fun rounded(color: Int, radius: Float, padding: Padding? = null): XmlNode = XmlNode(
        "shape",
        listOf(android("shape", ATTR_SHAPE, XmlValue.Integer(0))),
        buildList {
            add(XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color)))))
            add(XmlNode("corners", listOf(android("radius", ATTR_RADIUS, XmlValue.Dimension(radius)))))
            padding?.let { add(it.node()) }
        },
    )

    private fun oval(color: Int): XmlNode = XmlNode(
        "shape",
        listOf(android("shape", ATTR_SHAPE, XmlValue.Integer(1))),
        listOf(XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color))))),
    )

    private fun proBubble(color: Int, outline: Int): XmlNode = XmlNode(
        "shape",
        children = listOf(
            XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color)))),
            XmlNode("stroke", listOf(
                android("width", ATTR_WIDTH, XmlValue.Dimension(1f)),
                android("color", ATTR_COLOR, XmlValue.Color(outline)),
            )),
            XmlNode("corners", listOf(android("radius", ATTR_RADIUS, XmlValue.Dimension(20f)))),
            ALL_BUBBLE_PADDING.node(),
        ),
    )

    private fun classicShape(color: Int, incoming: Boolean, padding: Padding): XmlNode = XmlNode(
        "shape",
        listOf(android("shape", ATTR_SHAPE, XmlValue.Integer(0))),
        listOf(
            XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color)))),
            XmlNode("corners", listOf(
                android("topLeftRadius", ATTR_TOP_LEFT_RADIUS, XmlValue.Dimension(if (incoming) 6f else 16f)),
                android("topRightRadius", ATTR_TOP_RIGHT_RADIUS, XmlValue.Dimension(if (incoming) 16f else 6f)),
                android("bottomLeftRadius", ATTR_BOTTOM_LEFT_RADIUS, XmlValue.Dimension(16f)),
                android("bottomRightRadius", ATTR_BOTTOM_RIGHT_RADIUS, XmlValue.Dimension(16f)),
            )),
            padding.node(),
        ),
    )

    private fun classicLinkMask(incoming: Boolean, padding: Padding): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_pressed", ATTR_STATE_PRESSED, classicShape(0x1a000000, incoming, padding)),
            stateItem("state_selected", ATTR_STATE_SELECTED, classicShape(0x1a000000, incoming, padding)),
            XmlNode("item", children = listOf(solid(0x00000000))),
        ),
    )

    private fun linkMask(color: Int, padding: Padding): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_pressed", ATTR_STATE_PRESSED, rounded(color, 16f, padding)),
            stateItem("state_selected", ATTR_STATE_SELECTED, rounded(color, 16f, padding)),
            XmlNode("item", children = listOf(solid(0x00000000))),
        ),
    )

    private fun messageSelector(pressed: XmlNode, normal: XmlNode): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_focused", ATTR_STATE_FOCUSED, pressed),
            stateItem("state_pressed", ATTR_STATE_PRESSED, pressed),
            stateItem("state_selected", ATTR_STATE_SELECTED, pressed),
            XmlNode("item", children = listOf(normal)),
        ),
    )

    private fun voiceSelector(drawable: XmlNode): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_focused", ATTR_STATE_FOCUSED, drawable),
            stateItem("state_pressed", ATTR_STATE_PRESSED, drawable),
            stateItem("state_selected", ATTR_STATE_SELECTED, drawable),
            XmlNode("item", children = listOf(drawable)),
        ),
    )

    private fun simpleSelector(pressed: XmlNode, normal: XmlNode): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_pressed", ATTR_STATE_PRESSED, pressed),
            XmlNode("item", children = listOf(normal)),
        ),
    )

    private fun paymentSelector(pressedColor: Int, normalColor: Int): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_pressed", ATTR_STATE_PRESSED, rounded(pressedColor, 8f)),
            stateItem("state_enabled", ATTR_STATE_ENABLED, rounded(pressedColor, 8f), false),
            XmlNode("item", children = listOf(rounded(normalColor, 8f))),
        ),
    )

    private fun stateItem(name: String, id: Int, child: XmlNode, value: Boolean = true): XmlNode =
        XmlNode("item", listOf(android(name, id, XmlValue.Boolean(value))), listOf(child))

    /**
     * 启动图（`launcher.splash.background`）：纯色底层 + 居中图标层。
     *
     * **图标层只在宿主真的有可引用的 icon 时才加**。旧实现无条件写
     * `<item android:drawable="@{iconId}">`，而 iconId 来自
     * `graph.node(MonetResourceKey("drawable", "icon"))?.id ?: 0` —— 宿主没有那个资源名时就是 0，
     * 于是写出 `android:drawable="@0x0"`：宿主解析这个 layer-list 会失败（旧版实机日志里的
     * `IllegalArgumentException: launcher.splash.background` 就是它），或者退化成一张透明/全黑
     * 的窗口底 —— 而 **WeKit 设置页正是借宿主的 WeChatSplashActivity 当壳启动的，它的窗口底
     * 就是这个 drawable**，于是「打开 WeKit 设置直接黑屏」（实机 2026-09-26 13:42:47 截图）。
     * 少一个图标图层只是不好看，写一个悬空引用却是黑屏，取舍很清楚。
     */
    private fun splash(color: XmlValue, iconId: Int): XmlNode = XmlNode(
        "layer-list",
        listOf(android("opacity", ATTR_OPACITY, XmlValue.Integer(-1))),
        buildList {
            add(XmlNode("item", children = listOf(solid(color))))
            if (iconId != 0) {
                add(
                    XmlNode(
                        "item",
                        listOf(
                            android("gravity", ATTR_GRAVITY, XmlValue.Integer(17)),
                            android("drawable", ATTR_DRAWABLE, XmlValue.Reference(iconId)),
                        ),
                    ),
                )
            }
        },
    )

    private fun header(surface: Int, accent: Int): XmlNode = XmlNode(
        "layer-list",
        children = listOf(
            XmlNode("item", children = listOf(solid(surface))),
            XmlNode("item", listOf(android("top", ATTR_TOP, XmlValue.Dimension(120f))), listOf(solid(accent))),
        ),
    )

    private fun foregroundIcon(): XmlNode = vector(listOf(
        path(0xff5bb974.toInt(), "M87,145a54,45 0 0 1 108,0a54,45 0 0 1 -108,0Z"),
        path(0xff5bb974.toInt(), "M106,179a4.627,4.627,0,0,1,1,4c-0.641,2.143-3,9-3,9s-0.942,4.954,4,2,14-9,14-9l-9-8Z"),
        path(0xff00ac47.toInt(), SECOND_BUBBLE_PATH),
        path(0xff00ac47.toInt(), "M221.3,206.689a4,4,0,0,0-.864,3.459c.554,1.853,2.594,7.781,2.594,7.781s.814,4.283-3.459,1.729-12.1-7.781-12.1-7.781l7.781-6.917Z"),
        XmlNode("group", children = listOf(
            XmlNode("clip-path", listOf(android("pathData", ATTR_PATH_DATA, XmlValue.String("M87,145a54,45 0 0 1 108,0a54,45 0 0 1 -108,0Z")))),
            path(0xff00832d.toInt(), SECOND_BUBBLE_PATH),
        )),
    ))

    private fun monochromeIcon(): XmlNode = vector(listOf(
        path(0x0106000c, "M191,138c-27.062,0-49,18.132-49,40.5a34.025,34.025,0,0,0,1.991,11.428C143,189.973,142,190,141,190c-29.823,0-54-20.147-54-45s24.177-45,54-45c27.01,0,49.388,16.526,53.369,38.106C193.255,138.043,192.134,138,191,138Z"),
        path(0x0106000c, "M106,179a4.627,4.627,0,0,1,1,4c-0.641,2.143-3,9-3,9s-0.942,4.954,4,2,14-9,14-9l-9-8Z"),
        path(0x0106000c, SECOND_BUBBLE_PATH),
        path(0x0106000c, "M221.3,206.689a4,4,0,0,0-.864,3.459c.554,1.853,2.594,7.781,2.594,7.781s.814,4.283-3.459,1.729-12.1-7.781-12.1-7.781l7.781-6.917Z"),
    ))

    private fun vector(children: List<XmlNode>): XmlNode = XmlNode(
        "vector",
        listOf(
            android("height", ATTR_HEIGHT, XmlValue.Dimension(324f)),
            android("width", ATTR_WIDTH, XmlValue.Dimension(324f)),
            android("viewportWidth", ATTR_VIEWPORT_WIDTH, XmlValue.Float(324f)),
            android("viewportHeight", ATTR_VIEWPORT_HEIGHT, XmlValue.Float(324f)),
        ),
        children,
    )

    private fun path(color: Int, data: String): XmlNode = XmlNode(
        "path",
        listOf(
            android("fillColor", ATTR_FILL_COLOR, colorValue(color)),
            android("pathData", ATTR_PATH_DATA, XmlValue.String(data)),
        ),
    )

    private fun Padding.node(): XmlNode = XmlNode("padding", listOf(
        android("left", ATTR_LEFT, XmlValue.Dimension(left)),
        android("top", ATTR_TOP, XmlValue.Dimension(top)),
        android("right", ATTR_RIGHT, XmlValue.Dimension(right)),
        android("bottom", ATTR_BOTTOM, XmlValue.Dimension(bottom)),
    ))

    private fun colorValue(value: Int): XmlValue = if (value ushr 24 == 0x01) {
        XmlValue.Reference(value)
    } else {
        XmlValue.Color(value)
    }

    private fun android(name: String, id: Int, value: XmlValue) = XmlAttribute(name, id, value)


    private val ALL_BUBBLE_PADDING = Padding(12f, 8f, 12f, 8f)
    private val INCOMING_LINK_PADDING = Padding(0f, 5f, 5f, 5f)
    private val OUTGOING_LINK_PADDING = Padding(5f, 5f, 0f, 5f)

    private val PRO_INCOMING = listOf(
        "chat.bubble.incoming.normal", "chat.bubble.incoming.link",
        "chat.bubble.incoming.pro", "chat.bubble.incoming.pro.handled",
    )
    private val PRO_OUTGOING = listOf(
        "chat.bubble.outgoing.normal", "chat.bubble.outgoing.link",
        "chat.bubble.outgoing.pro", "chat.bubble.outgoing.pro.handled",
    )

    private const val SECOND_BUBBLE_PATH = "M 191.5 141 C 179.437 141 167.856 144.954 159.327 151.983 C 150.797 159.013 146 168.558 146 178.5 C 146 188.442 150.797 197.987 159.327 205.017 C 167.856 212.046 179.437 216 191.5 216 C 203.563 216 215.144 212.046 223.673 205.017 C 232.203 197.987 237 188.442 237 178.5 C 237 168.558 232.203 159.013 223.673 151.983 C 215.144 144.954 203.563 141 191.5 141 Z"

    private const val ATTR_STATE_FOCUSED = 0x0101009c
    private const val ATTR_STATE_ENABLED = 0x0101009e
    private const val ATTR_STATE_SELECTED = 0x010100a1
    private const val ATTR_STATE_PRESSED = 0x010100a7
    private const val ATTR_GRAVITY = 0x010100af
    private const val ATTR_WIDTH = 0x01010159
    private const val ATTR_HEIGHT = 0x01010155
    private const val ATTR_SHAPE = 0x0101019a
    private const val ATTR_COLOR = 0x010101a5
    private const val ATTR_RADIUS = 0x010101a8
    private const val ATTR_TOP_LEFT_RADIUS = 0x010101a9
    private const val ATTR_TOP_RIGHT_RADIUS = 0x010101aa
    private const val ATTR_BOTTOM_LEFT_RADIUS = 0x010101ab
    private const val ATTR_BOTTOM_RIGHT_RADIUS = 0x010101ac
    private const val ATTR_LEFT = 0x010101ad
    private const val ATTR_TOP = 0x010101ae
    private const val ATTR_RIGHT = 0x010101af
    private const val ATTR_BOTTOM = 0x010101b0
    private const val ATTR_DRAWABLE = 0x01010199
    private const val ATTR_OPACITY = 0x0101031e
    private const val ATTR_VIEWPORT_WIDTH = 0x01010402
    private const val ATTR_VIEWPORT_HEIGHT = 0x01010403
    private const val ATTR_FILL_COLOR = 0x01010404
    private const val ATTR_PATH_DATA = 0x01010405

    /** Splits the authored visuals by bubble style and folds [multiSceneCorners] in. */
    fun plan(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
        style: MonetBubbleStyle,
        multiSceneCorners: Boolean,
        splashIconId: Int,
        slots: MonetHostTypeSlots = MonetHostTypeSlots.EMPTY,
    ): MonetOverlayPlan {
        var drawables = baseVisuals(resolved, palette, splashIconId)
        drawables = drawables + when (style) {
            MonetBubbleStyle.MODERN -> modernBubbles(resolved, palette)
            MonetBubbleStyle.CLASSIC -> classicBubbles(resolved, palette)
            MonetBubbleStyle.PRO -> proBubbles(resolved, palette)
        }
        if (multiSceneCorners) drawables = drawables + corners(resolved, palette)
        return MonetOverlayPlan(drawables = drawables + themedIcon(resolved, palette, slots))
    }
}
