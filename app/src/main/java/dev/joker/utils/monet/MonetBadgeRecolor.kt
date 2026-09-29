package dev.joker.utils.monet

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

    /** 一次最多改写多少条角标颜色（防止某个版本里「大红色」被滥用成几十条而把包撑肿）。 */
    private const val MAX_TARGETS = 60

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
     * 扫出所有「角标类」颜色资源并给出莫奈覆盖目标。
     *
     * 全程 `runCatching` 兜底：扫描失败只是「角标保持原色」，绝不能影响其余角色的解析。
     */
    fun targets(graph: MonetResourceGraph, palette: Palette): List<ColorTarget> {
        return runCatching {
            val hits = ArrayList<Pair<MonetResourceNode, Boolean>>() // node to 是否名证据命中
            graph.allNodes().forEach { node ->
                if (node.key.type != "color") return@forEach
                val name = node.key.name.lowercase()
                if (NAME_BLOCKLIST.any { name.contains(it) }) return@forEach

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
            val picked = hits.take(MAX_TARGETS).map { (node, _) ->
                ColorTarget(
                    binding = node.binding(),
                    light = ColorValue.Literal(palette.primaryLight),
                    night = ColorValue.Literal(palette.primaryDark),
                )
            }
            WeLogger.i(
                TAG,
                "角标重着色：命中 ${hits.size} 条颜色资源，取前 ${picked.size} 条改写为莫奈主色 " +
                    "(${hits.take(8).joinToString { it.first.key.name }})",
            )
            picked
        }.onFailure { WeLogger.w(TAG, "角标重着色扫描失败，保持原色", it) }
            .getOrDefault(emptyList())
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
