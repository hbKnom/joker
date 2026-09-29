package dev.joker.utils.monet

import dev.joker.preferences.WePrefs
import dev.joker.utils.WeLogger

/**
 * 【Round45】「未读角标永远是品牌红」的自动兜底重着色。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────────────
 * 用户实机反馈（含截图）：聊天记录分析正常、底栏/标题栏也取色了，但
 * **消息角标（会话列表头像右上角的实心点、底栏导航的角标、顶部对话分组栏的角标）
 * 永远是微信的红**。历史结论是「角标红没有指纹、要拿到目标微信 APK 的规则证据才能补
 * `MONET_RULES`」—— 但那要求每次换微信版本都重新取指纹，且用户手机上的包我们拿不到。
 *
 * 这里换一条**不需要指纹**的路：按「资源值 + 资源名」在运行时自动识别角标类颜色资源，
 * 直接把它们重着色到莫奈主色。识别依据是两条互相独立、可观测的证据：
 *
 *  * **值证据**：该 color 条目的取值等于微信家族惯用的角标红（`#FA5151` / `#FF3B30` /
 *    `#E64340` / `#F5222D` / `#FB4E4E` / `#FF5A5F` …）；
 *  * **名证据**：资源名里出现 `badge` / `unread` / `red_dot` / `count_bg` / `new_msg`
 *    等角标语义词（也接受「无名证据但值精确命中且名字不含红包/钱包类词」的保守兜底）。
 *
 * 命中后写入的就是一块普通的 [ColorTarget]（light/night 都是 [ColorValue.Literal]），
 * 与 `MONET_RULES` 解析出来的颜色走完全相同的写包/注入通道，因此：
 *  * 不新增任何 hook、不新增任何启动期开销（只在解析期多扫一遍已建好的资源图）；
 *  * 写包失败/宿主不认时，现有的逐条冒烟校验会照常把它拉黑并回滚 —— 不会把坏包留在微信里。
 *
 * 名字里含 `hongbao` / `wallet` / `pay` / `red_packet` 等**有真实语义**的资源一律跳过：
 * 红包红不能变莫奈紫，那不是美化而是把功能语义改坏。
 */
internal object MonetBadgeRecolor {

    private const val TAG = "MonetBadgeRecolor"

    /**
     * 【第 47 轮】「角标跟随莫奈色」开关（默认**关闭**）。
     *
     * 为什么必须默认关：第 46 轮实机反馈「微信里所有实心圆点全没了」—— 不只是会话列表
     * 未读角标，连聊天里未播放语音的小红点、发现页/朋友圈的红点也一起消失。
     * 这三处红点分属宿主完全不同的绘制路径，唯一能同时影响它们的就是本文件：它按
     * 「值 == 微信品牌红」在**宿主的资源表里全局改写**颜色条目，而微信的品牌红被
     * 几十处 UI 复用（会话角标 / 语音未读点 / 朋友圈红点 / 各种提示点…）。
     * 一旦被改写的那一条是某个红点 drawable 依赖的色，那个红点就没了。
     *
     * 结论：自动取色角标收益（颜色好看一点）远小于风险（红点消失 = 用户以为丢消息）。
     * 因此**默认不碰任何宿主红点资源**；想要莫奈色角标的用户在莫奈设置里显式打开，
     * 打开后仍受下面三道校验保护（只改「本身是字面量色 + 名字/值双证据 + 对比度足够」的条目）。
     */
    const val KEY_ENABLED = "monet_badge_recolor"

    fun isEnabled(): Boolean = runCatching {
        WePrefs.getBoolOrDef(KEY_ENABLED, false)
    }.getOrDefault(false)

    fun setEnabled(on: Boolean) {
        runCatching { WePrefs.putBool(KEY_ENABLED, on) }
    }

    /** 一次最多改写多少条角标颜色（防止某个版本里「大红色」被滥用成几十条而把包撑肿）。 */
    private const val MAX_TARGETS = 60

    /** 角标底色与「面」色的最小明度差（低于它就换成同源的深/浅强调色）。 */
    private const val MIN_BADGE_LUMINANCE_DELTA = 0.22f

    /** 微信家族里被用作「未读 / 新消息」角标的红（ARGB 位模式）。 */
    private val BADGE_REDS = setOf(
        0xFFFA5151.toInt(),
        0xFFFF3B30.toInt(),
        0xFFE64340.toInt(),
        0xFFF5222D.toInt(),
        0xFFFB4E4E.toInt(),
        0xFFFF5A5F.toInt(),
        0xFFFF5151.toInt(),
        0xFFF52F2F.toInt(),
        0xFFFA5A5A.toInt(),
        0xFFD93A3A.toInt(),
    )

    /** 资源名里出现任一关键词 → 直接认定是角标类资源。 */
    private val NAME_HINTS = listOf(
        "badge",
        "unread",
        "reddot",
        "red_dot",
        "red_point",
        "newmsg",
        "new_msg",
        "tipdot",
        "tip_dot",
        "count_bg",
        "countbg",
        "unread_count",
        "unreadcount",
        "msg_count",
        "msgcount",
    )

    /** 名字里出现任一关键词 → **绝不改**（红包 / 金额 / 品牌语义）。 */
    private val NAME_BLOCKLIST = listOf(
        "hongbao",
        "lucky",
        "money",
        "wallet",
        "pay",
        "envelope",
        "red_packet",
        "redpacket",
        "coupon",
        "festival",
        "brand",
        "logo",
        "gift",
        "packet",
    )

    /**
     * 【第 46 轮】名字里出现任一关键词 → **绝不改**：这些是**前景/文字色**。
     *
     * 为什么必须排除：角标是「底 + 字」两个色。如果底和字都被重着色成同一个莫奈主色，
     * 数字就会溶进底色里 —— 实机观感正是「有消息进来，但消息角标变成空白的」。
     * 我们无法可靠地判断一个字色资源配的是哪块底，所以这类资源一律不动（保守即安全）。
     */
    private val NAME_TEXT_BLOCKLIST = listOf(
        "text",
        "txt",
        "_fg",
        "fore",
        "font",
        "white",
        "title_color",
        "digit",
    )

    /**
     * 扫出所有「角标类」颜色资源并给出莫奈覆盖目标。
     *
     * 全程 `runCatching` 兜底：扫描失败只是「角标保持原色」，绝不能影响其余角色的解析。
     */
    fun targets(graph: MonetResourceGraph, palette: Palette): List<ColorTarget> {
        if (!isEnabled()) return emptyList()
        return runCatching {
            val hits = ArrayList<Pair<MonetResourceNode, Boolean>>() // node to 是否名证据命中
            graph.allNodes().forEach { node ->
                if (node.key.type != "color") return@forEach
                val name = node.key.name.lowercase()
                if (NAME_BLOCKLIST.any { name.contains(it) }) return@forEach
                if (NAME_TEXT_BLOCKLIST.any { name.contains(it) }) return@forEach

                // 【第 47 轮】只改「当前就是一个字面量颜色」的条目：把 selector / 引用型
                // 颜色条目改写成字面量，等于把宿主 drawable 的取色链掐断 —— 那就是红点消失。
                if (!node.hasLiteralColor()) return@forEach

                val byName = NAME_HINTS.any { name.contains(it) }
                val byValue = node.hasLiteralBadgeRed()
                if (!byName && !byValue) return@forEach
                // 名证据命中但值不是角标红：仍要命中（有些版本角标色被换成了别的红/橙）
                hits += node to byName
            }
            if (hits.isEmpty()) {
                WeLogger.i(TAG, "未发现角标类颜色资源（本版本角标可能不是独立 color 资源）")
                return@runCatching emptyList()
            }
            // 【第 46 轮】不再无脑写 primaryLight/primaryDark：如果主色与「面」色的明度太接近，
            // 角标底色就会和会话行背景糊在一起 —— 实机观感就是「角标是空的 / 看不见」。
            // 这里做一次明度差守卫，不够对比时改用同源的深/浅强调色（仍是莫奈色，只是更有对比）。
            val lightColor = badgeColorOrNull(palette.primaryLight, palette.surfaceLight, palette.accent1_700)
            val nightColor = badgeColorOrNull(palette.primaryDark, palette.surfaceDark, palette.accent1_300)
            if (lightColor == null || nightColor == null) {
                // 主色/强调色都不合格（透明、或与面色糊在一起）→ 宁可一条都不改。
                WeLogger.w(TAG, "角标重着色：没有合格的角标色（守校验全部未过），本次保持宿主原色")
                return@runCatching emptyList()
            }
            val picked = hits.take(MAX_TARGETS).map { (node, _) ->
                ColorTarget(
                    binding = node.binding(),
                    light = ColorValue.Literal(lightColor),
                    night = ColorValue.Literal(nightColor),
                )
            }
            // 逐条列出「资源名 旧值→新值」：下一轮日志里就能直接看出角标到底被改成了什么色，
            // 不必再靠截图猜（第 46 轮用户反馈「角标为空」时缺的就是这条证据）。
            WeLogger.i(
                TAG,
                "角标重着色：命中 ${hits.size} 条颜色资源，取前 ${picked.size} 条改写为莫奈角标色 " +
                    "light=#${Integer.toHexString(lightColor)} night=#${Integer.toHexString(nightColor)} | " +
                    hits.take(12).joinToString {
                        "${it.first.key.name}(${if (it.second) "名" else "值"}证据," +
                            "旧值=#${Integer.toHexString(it.first.representativeLiteral())})"
                    },
            )
            picked
        }.onFailure { WeLogger.w(TAG, "角标重着色扫描失败，保持原色", it) }
            .getOrDefault(emptyList())
    }

    /**
     * 选一个「与背景有足够明度差」的角标底色。
     *
     * 明度差 ≥ 0.22 时用莫奈主色本身（用户要的就是「角标跟主色」）；
     * 否则退回同源的深/浅强调色 —— 宁可角标颜色深一点/浅一点，也**绝不能糊到看不出来**。
     */
    private fun badgeColorOrNull(primary: Int, surface: Int, fallback: Int): Int? {
        if (isOpaque(primary) && contrastEnough(primary, surface)) return primary
        if (isOpaque(fallback) && contrastEnough(fallback, surface)) return fallback
        // 【第 47 轮】两个候选都不合格时返回 null（= 不写），而不是硬塞一个可能糊掉的颜色。
        // 旧实现只算「明度差」，主色为 0（token 缺失/解析失败）时会直接返回 0x00000000，
        // 那是个**完全透明**的角标色 —— 实机上就是「角标全空、看不见」。
        return null
    }

    /** 必须完全不透明：半透明色写到红点底色上 = 红点变淡甚至消失。 */
    private fun isOpaque(argb: Int): Boolean = ((argb ushr 24) and 0xFF) == 0xFF

    /** 与「面」色的明度差必须够大，否则角标糊进背景里看不见。 */
    private fun contrastEnough(color: Int, surface: Int): Boolean {
        val delta = luminance(color) - luminance(surface)
        return delta >= MIN_BADGE_LUMINANCE_DELTA || -delta >= MIN_BADGE_LUMINANCE_DELTA
    }

    /** 相对亮度（0..1，sRGB 简化式，够用来判断「糊没糊在一起」）。 */
    private fun luminance(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    /** 取该节点第一个字面量色值，仅供日志（拿不到就返回 0）。 */
    private fun MonetResourceNode.representativeLiteral(): Int {
        values.forEach { configured ->
            val value = configured.value
            if (value is MonetResourceValue.Literal) return value.data.toInt()
        }
        return 0
    }

    /** 该 color 条目当前是否是「一个字面量颜色」（引用型 / selector 一律不动）。 */
    private fun MonetResourceNode.hasLiteralColor(): Boolean = values.any { configured ->
        val value = configured.value
        value is MonetResourceValue.Literal && value.valueType.startsWith("COLOR")
    }

    /** 该 color 条目的值是否恰好是角标红。 */
    private fun MonetResourceNode.hasLiteralBadgeRed(): Boolean = values.any { configured ->
        val value = configured.value
        if (value !is MonetResourceValue.Literal) return@any false
        val type = value.valueType
        if (!type.startsWith("COLOR") && !type.startsWith("INT")) return@any false
        BADGE_REDS.contains(value.data.toInt())
    }
}
