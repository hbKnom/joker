package dev.joker.utils.monet

import android.annotation.SuppressLint
import android.content.res.Resources
import dev.joker.utils.WeLogger
import java.io.File
import java.security.MessageDigest

/**
 * Turns a WeChat resource graph into everything the runtime package needs: the resolved role map,
 * the 16-colour [Palette] plus the authored [MonetOverlayPlan].
 *
 * Upstream 09-25 extracted this from `MonetModuleGenerator`, which had it entangled with the RRO
 * writer, the signer and the Magisk packer. The palette still comes from the *platform* dynamic
 * colours (`android.R.color.system_accent1_*`), so WeChat's replacement resources reference the same
 * android framework ids that Material You uses — that is what makes the recolouring track the
 * wallpaper without the engine ever reading the wallpaper itself.
 */
object MonetResourceResolver {

    private const val TAG = "MonetResourceResolver"

    /** Result of one analysis run over a WeChat build. */
    data class Resolution(
        val fingerprint: String,
        val bindings: MonetBindings,
        val resolved: Map<String, MonetResourceNode>,
        val palette: Palette,
        val plan: MonetOverlayPlan,
    )

    /**
     * Stable id for the analysed APK set: 版本号 + 每个 APK 的文件名 / 大小 / 修改时间。
     *
     * 这里**故意不读 APK 内容**：微信基础包 + 各 split 动辄数百 MB，旧实现逐个整包读入
     * 既分配整包内存、又在启动路径上做全量 I/O（用户要求「莫奈取色不影响微信流畅运行」）。
     * 版本号 + 每个 APK 的大小与 mtime 足以判定「微信是否被更新过」。
     */
    fun fingerprint(sourceApkPaths: List<String>, versionCode: Long, versionName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$versionCode/$versionName".toByteArray())
        sourceApkPaths.forEach { path ->
            val file = File(path)
            digest.update(path.substringAfterLast('/').toByteArray())
            digest.update(file.length().toString().toByteArray())
            digest.update(file.lastModified().toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * 【第 50 轮】卡片族扩展：把「同一张卡片其它状态」的 drawable 也纳入编排。
     *
     * 证据链（用户 2026-09-30 实机日志，微信 8.0.72）：
     *   `reusing cached bindings 231 roles (unresolved 0)`、`覆盖写入率 100%（406/406）`、
     *   `冒烟校验通过` —— 说明**不是解析失败**；而用户观感是「领取前的红包/转账是 pro 圆角，
     *   领取完的就是微信原版不变」。即：同一张卡在「已领取」状态下用的是**同一族里另一个
     *   没被任何规则覆盖的 drawable**。
     *
     * 不猜新指纹，改用两条已有事实走一跳：
     *  ① 资源图的引用关系：引用该角色 drawable 的布局 → 这些布局另作背景引用的 drawable；
     *  ② **结构同形**：只接受 XML 结构签名（标签序列）与该角色**完全一致**的候选 ——
     *     同形即「同一张卡的不同状态」，形状不同的一律不收（避免误伤同布局里的图标等）。
     *
     * 只**增加**覆盖（写入编排的额外 target），不替换任何已解析角色；上限 [CARD_FAMILY_MAX]。
     * 命中情况写入日志，便于下一轮凭日志核对。
     */
    private fun expandCardFamilies(
        graph: MonetResourceGraph,
        resolved: Map<String, MonetResourceNode>,
        out: MutableMap<String, List<MonetResourceNode>>,
    ) {
        CARD_FAMILY_ROLES.forEach { role ->
            val node = resolved[role] ?: return@forEach
            val signature = structureSignature(graph, node)
            val candidates = LinkedHashSet<Int>()
            graph.incoming(node.id).forEach { layoutId ->
                graph.outgoing(layoutId).forEach { refId -> if (refId != node.id) candidates += refId }
            }
            // 二跳（受限）：状态变体常常挂在另一层容器上（同族 drawable 互为邻居）。
            candidates.toList().take(8).forEach { refId ->
                graph.incoming(refId).forEach { upId ->
                    graph.outgoing(upId).forEach { sibling -> if (sibling != node.id) candidates += sibling }
                }
            }
            // 【第 52 轮】不再「拿不到 XML 树就整族放弃」，并增加一条**布局 background 直锚**。
            //
            // 实机日志（2026-07-10-07，72 包）铁证：`卡片族扩展` 只在
            // `chat.red-envelope.incoming/outgoing.normal` 上命中（各 +8），而
            // **转账 received/expired 一条都没扩展** —— 用户看到的正是「领取完的转账还是微信原版」。
            // 原因是两处过严：①`structureSignature(graph, node) ?: return@forEach`：转账卡的背景
            // 多为 9-patch/PNG（没有 XML 树）→ 整个角色直接跳过；②候选必须是「同 XML 形状」。
            //
            // 现在：`android:background` 引用的同布局背景**无需同形**（布局自己引用的背景就是卡片背景，
            // 这本身就是最强的结构证据）；同形判定只在能拿到 XML 树时才作为附加条件。
            val backgroundAnchored = LinkedHashSet<Int>()
            graph.incoming(node.id)
                .filter { graph.node(it)?.key?.type == "layout" }
                .take(FAMILY_MAX_LAYOUTS)
                .forEach { layoutId ->
                    graph.xmlTrees(layoutId).take(6).forEach { collectBackgroundRefs(it, backgroundAnchored, 0) }
                }
            backgroundAnchored.remove(node.id)
            val sameShape = if (signature == null) {
                emptyList()
            } else {
                candidates.asSequence()
                    .mapNotNull { graph.node(it) }
                    .filter { it.key.type == "drawable" }
                    .filter { graph.incoming(it.id).isNotEmpty() }
                    .filter { structureSignature(graph, it) == signature }
                    .toList()
            }
            val extras = (sameShape.asSequence().mapNotNull { it.id } + backgroundAnchored.asSequence())
                .distinct()
                .mapNotNull { graph.node(it) }
                .filter { it.key.type == "drawable" }
                .distinctBy { it.id }
                .take(CARD_FAMILY_MAX)
                .toList()
            if (extras.isEmpty()) {
                // 留证据：下一轮日志能直接看出「这一族没找到任何同卡背景」还是「角色没解析」。
                WeLogger.i(TAG, "卡片族扩展 $role：无可扩展背景（同形 ${sameShape.size}、布局背景 ${backgroundAnchored.size}）")
                return@forEach
            }
            out[role] = (out[role].orEmpty() + extras).distinctBy { it.id }
            WeLogger.i(
                TAG,
                "卡片族扩展 $role：+${extras.size} 个背景（同形 ${sameShape.size}、布局背景 ${backgroundAnchored.size}）" +
                    extras.joinToString(",") { "${it.key.type}/${it.key.name}" },
            )
        }
    }

    /** 一棵 XML 树的结构签名（标签名的先序序列）；没有 XML 树（如 PNG/别名）返回 null。 */
    private fun structureSignature(graph: MonetResourceGraph, node: MonetResourceNode): String? {
        val tree = runCatching { graph.xmlTrees(node.id).firstOrNull() }.getOrNull() ?: return null
        val sb = StringBuilder()
        fun walk(element: MonetXmlElement, depth: Int) {
            if (depth > 6) return
            sb.append(element.name).append('(').append(element.children.size).append(");")
            element.children.forEach { walk(it, depth + 1) }
        }
        walk(tree, 0)
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * 允许做「同族扩展」的角色：红包与转账的卡片族。
     *
     * 只放这一组：它们是**同一张卡的多状态**最确定的地方（用户实机反复反馈的就这一处），
     * 其它角色一律不做扩展，避免把某个界面里形状偶然一致的 drawable 也拉进来。
     */
    private val CARD_FAMILY_ROLES = listOf(
        "chat.transfer.incoming.received",
        "chat.transfer.outgoing.received",
        "chat.transfer.incoming.expired",
        "chat.transfer.outgoing.expired",
        "chat.red-envelope.incoming.alias",
        "chat.red-envelope.outgoing.alias",
        "chat.red-envelope.incoming.normal",
        "chat.red-envelope.outgoing.normal",
    )

    /** 每个角色最多扩展多少个同族 drawable。 */
    private const val CARD_FAMILY_MAX = 8

    /** 【第 49c】同卡片背景扩展的封顶（布局数 / 最终目标数）+ `android:background` 属性 id。 */
    private const val FAMILY_MAX_LAYOUTS = 4
    private const val FAMILY_MAX_TARGETS = 10
    private const val ATTR_BACKGROUND = 0x010100d4

    /**
     * 找与 [nodeId] **共用同一张卡片布局**的其它 drawable。
     *
     * 起点全是宿主自己的结构（不猜指纹）：引用该节点的布局、以及该节点自身的 selector items。
     * 深度与数量都封顶，避免在个别畸形资源上滚雪球。
     */
    private fun collectSameCardDrawables(graph: MonetResourceGraph, nodeId: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        runCatching {
            graph.incoming(nodeId)
                .filter { graph.node(it)?.key?.type == "layout" }
                .take(FAMILY_MAX_LAYOUTS)
                .forEach { layoutId ->
                    graph.xmlTrees(layoutId).take(6).forEach { tree -> collectBackgroundRefs(tree, out, 0) }
                }
            graph.xmlTrees(nodeId).take(4).forEach { tree -> collectItemRefs(tree, out, 0) }
        }.onFailure { WeLogger.w(TAG, "同卡片背景收集失败（$nodeId），该节点按原样处理", it) }
        out.remove(nodeId)
        return out.toList()
    }

    /** 递归收集 `android:background` 引用的 drawable（含 complex/selector 形式的 item）。 */
    private fun collectBackgroundRefs(element: MonetXmlElement, out: MutableSet<Int>, depth: Int) {
        if (depth > 8) return
        element.attributes.forEach { attr ->
            if (attr.nameId != ATTR_BACKGROUND) return@forEach
            when (val value = attr.value) {
                is MonetResourceValue.Reference -> out += value.resourceId
                is MonetResourceValue.Complex -> value.items.forEach { item ->
                    (item.value as? MonetResourceValue.Reference)?.let { out += it.resourceId }
                }
                else -> Unit
            }
        }
        element.children.forEach { collectBackgroundRefs(it, out, depth + 1) }
    }

    /** selector / state-list 里的 drawable item（卡片的「已领取 / 已过期」等状态背景常在这里）。 */
    private fun collectItemRefs(element: MonetXmlElement, out: MutableSet<Int>, depth: Int) {
        if (depth > 8) return
        element.children.forEach { child ->
            child.attributes.forEach { attr ->
                (attr.value as? MonetResourceValue.Reference)?.let { out += it.resourceId }
            }
            collectItemRefs(child, out, depth + 1)
        }
    }

    /**
     * Resolves every semantic role and authors the replacement resources.
     *
     * @param resources host resources, used only to look up `android.R.color.system_*`.
     */
    fun resolve(
        graph: MonetResourceGraph,
        resources: Resources,
        fingerprint: String,
        bubbleStyle: MonetBubbleStyle,
        multiSceneCorners: Boolean,
        errorColors: Boolean = false,
        fallbackPalette: Palette? = null,
        dexProvider: MonetDexEvidenceProvider? = null,
        onProgress: (completed: Int?, total: Int?, detail: String) -> Unit = { _, _, _ -> },
    ): Resolution {
        val matchStart = System.nanoTime()
        // 【第 49 轮】状态族角色（红包 alias / 转账 received·expired）在宿主里是一族指纹相同的
        // drawable，消歧必然多候选；这里收集「同族变体」交给资源编排一并覆盖，
        // 用户实机反馈的「领取完的红包/转账还是原版微信」即出在这一族上。
        val familyExtras = linkedMapOf<String, List<MonetResourceNode>>()
        val resolved = MonetStructureMatcher.resolveAll(graph, dexProvider, { completed, total, detail ->
            onProgress(completed, total, detail)
        }, familyExtras)
        // 【第 49b】状态族「同卡片布局」扩展。
        //
        // 用户实机第 2 次反馈：**领取前**的红包/转账有 pro 圆角，**领取完**的还是微信原版。
        // 机制：同一张卡的不同状态往往是「同一布局下**不同的背景 drawable**」，而这些背景
        // 与已解析角色的结构证据并不相同 —— 既解析不到，也不会出现在同族候选（指纹相同）里，
        // 所以上一轮的「同指纹扩展」对它们无效。
        //
        // 这里换一个**宿主自证**的锚点，不猜指纹：以族内节点为起点，沿
        //   ① 引用它的布局（`layout` 节点）的 `android:background` 属性引用
        //   ② 它自身 selector/conplex 的 item 引用
        // 收集「同一张卡片的其它背景 drawable」，一并纳入该族的注入集合。
        // 只对显式标记 family 的角色生效、每族封顶，颜色链路一行未动。
        // 【第 52 轮】改为按 `CARD_FAMILY_ROLES` 遍历 —— 上一轮这里是
        // `familyExtras.entries.toList()`，而 `familyExtras` 只在「同指纹族拿到 ≥2 个候选」时
        // 才有内容。实机日志证明转账族每个角色都只解析到 1 个候选（`状态族` 一行都没打印），
        // 于是这段「同卡片背景扩展」**从来没跑过**，转账领取后的背景自然一直没被覆盖。
        CARD_FAMILY_ROLES.forEach { role ->
            val node = resolved[role] ?: return@forEach
            val base = LinkedHashSet<Int>()
            base += node.id
            familyExtras[role].orEmpty().forEach { base += it.id }
            val expanded = LinkedHashSet(base)
            base.forEach { id ->
                collectSameCardDrawables(graph, id).forEach { expanded += it }
            }
            val bounded = expanded.filter { graph.node(it) != null }.take(FAMILY_MAX_TARGETS)
            familyExtras[role] = bounded.mapNotNull { graph.node(it) }
            WeLogger.i(
                TAG,
                "状态族 $role 同卡片扩展：基础 ${base.size} → 最终 ${bounded.size} 个背景" +
                    "（${familyExtras[role].orEmpty().joinToString { "0x${it.id.toString(16)}" }}）",
            )
        }
        // 【第 50 轮】卡片族扩展：见 [expandCardFamilies]。
        runCatching { expandCardFamilies(graph, resolved, familyExtras) }
            .onFailure { WeLogger.w(TAG, "卡片族扩展失败（只影响红包/转账其它状态的圆角）", it) }
        val matchMs = (System.nanoTime() - matchStart) / 1_000_000
        val palette = overlayPalette(resources, fallbackPalette)
        // 合成资源（自适应图标图层）要借宿主同类型里空的槽位，需要全量节点的类型统计。
        val slots = runCatching { MonetHostTypeSlots.of(graph.allNodes()) }
            .onFailure { WeLogger.w(TAG, "宿主类型槽位统计失败，合成资源本次跳过", it) }
            .getOrDefault(MonetHostTypeSlots.EMPTY)
        val skippedIdentity = mutableListOf<String>()
        // 颜色收集是逐规则的启发式（平台 token 缺失、id 身份撞车、图里查不到引用都可能发生）。
        // 实测日志：`resource analysis failed during RESOLVING_ROLES /
        // java.util.NoSuchElementException: List is empty.` 就出在这一段，代价是莫奈整体失效。
        // 现在只丢颜色：可视化资源（气泡/底栏/启动图）照常编排，最坏结果是「一部分颜色没跟上
        // 主题」而不是「莫奈完全没生效」。
        val colors = runCatching {
            MONET_RULES
                .filter { it.type == "color" && it.id != MAIN_TAB_ROLE }
                .mapNotNull { rule ->
                    val node = resolved[rule.id] ?: return@mapNotNull null
                    if (!node.acceptsColorValue()) {
                        // 类型身份撞车：这个 id 的默认值不是颜色（文件/文本），把颜色写进去就是改坏
                        // 宿主的别的资源 —— 2026-09-25 实机闪退正是这一类（anim 被写成 COLOR_RGB8）。
                        // 日志带上「实际落到的 id/类型」：只看角色名没法判断这个角色到底被谁占了，
                        // 而排查「某个界面没取色」时需要的就是这条对应关系。
                        skippedIdentity.add(
                            "${rule.id}(落在 0x${node.id.toString(16)} ${node.key.type}/${node.key.name})",
                        )
                        return@mapNotNull null
                    }
                    val (light, night) = paletteFor(rule.id, resources)
                    ColorTarget(node.binding(), light, night)
                }
        }.onFailure { WeLogger.w(TAG, "颜色规则收集失败，本次只注入可视化资源", it) }
            .getOrDefault(emptyList<ColorTarget>())
        // 启动图标是可选的：旧实现用 requireNotNull，微信某次改动挪走 drawable/icon
        // 就足以让整次解析失败（用户看到的就是「解析出错」）。缺了就跳过这一张图。
        val splashIconId = graph.node(MonetResourceKey("drawable", "icon"))?.id ?: 0
        // 可视化资源（气泡/底栏/启动图/主题图标）的编排依赖大量「按布局结构探测」的启发式，
        // 任何一个角色或锚点缺失都不该带走整次解析 —— 颜色才是莫奈的主干。
        // 这里再兜一层：编排整体失败就**只注入颜色**（plan 里 colors 照常带上），
        // 于是「某台机器上编排崩了」最坏的结果是少了气泡圆角，而不是莫奈完全不生效。
        val authored = runCatching {
            MonetAssetInjector.plan(
                resolved = resolved,
                palette = palette,
                style = bubbleStyle,
                multiSceneCorners = multiSceneCorners,
                splashIconId = splashIconId,
                slots = slots,
                familyExtras = familyExtras,
            )
        }.onFailure {
            WeLogger.w(TAG, "可视化资源编排失败，本次只注入颜色（其余照常）", it)
        }.getOrDefault(MonetOverlayPlan())
        // 【Round45】角标兜底：不受 MONET_RULES 覆盖的「未读角标品牌红」按值+名自动识别并重着色。
        // 用户实机反复反馈「底栏导航角标 / 顶部对话分组栏角标 / 会话头像右上角实心点永远是红色」，
        // 而这类资源历史上没有指纹可写进规则表。这里在**已建好的资源图**上多扫一遍即可，
        // 不额外增加任何启动期 hook 或 IO；命中失败只降级成「保持原色」。
        val badgeColors = MonetBadgeRecolor.targets(graph, palette)
        val plan = authored.copy(colors = colors + badgeColors)
        val unresolved = MonetStructureMatcher.roleIds - resolved.keys
        @Suppress("UNUSED_EXPRESSION") palette
        val bindings = MonetBindings(
            fingerprint = fingerprint,
            typeNames = resolved.values.map { it.key.type }.distinct().sorted(),
            roles = resolved.mapValues { (_, node) -> node.id },
            tints = paletteTints(resolved, palette, errorColors),
            unresolved = unresolved.sorted(),
        )
        WeLogger.i(
            TAG,
            "resolved ${resolved.size} roles (${plan.drawables.size} drawables, ${colors.size} colors, " +
                "${unresolved.size} unresolved)，匹配用时 $matchMs ms",
        )
        if (skippedIdentity.isNotEmpty()) {
            WeLogger.w(
                TAG,
                "颜色规则命中非颜色资源，已跳过（id 身份撞车）：${skippedIdentity.joinToString()}",
            )
        }
        return Resolution(fingerprint, bindings, resolved, palette, plan)
    }

    /**
     * 颜色规则只能落在「原本就是颜色」的条目上。
     *
     * 不变量：解析出来的 id 必须真的属于 `color` 类型的那一条资源。多 APK 合并时 id 可能撞车
     * （同一个 `0x7f…` 在两个 APK 里指向不同资源），于是规则会锚到一个值类型完全不同的条目上。
     * 此时把颜色写进去的直接后果是宿主的别的资源被改坏 —— 2026-09-25 实机日志里
     * `Resource ID #0x7f010092 type #0x1d is not valid`（动画插值器被写成 COLOR_RGB8）就是
     * 这一类，微信启动即闪退。宁可少替换一个颜色，也不能写到错的地方。
     */
    private fun MonetResourceNode.acceptsColorValue(): Boolean {
        val value = values.firstOrNull { it.qualifiers.isEmpty() }?.value ?: return true
        return when (value) {
            is MonetResourceValue.Literal -> {
                // aapt2 写 COLOR_*，个别旧包写 INT_DEC/INT_HEX；两者都还算颜色。
                val type = value.valueType
                type.startsWith("COLOR") || type.startsWith("INT")
            }
            is MonetResourceValue.Reference -> true
            is MonetResourceValue.Complex -> true
            is MonetResourceValue.File, is MonetResourceValue.Text -> false
        }
    }

    private const val MAIN_TAB_ROLE = "main.tab.background"

    /**
     * Alpha overlays applied to WeChat's own translucent tokens. Only emitted when the user turned
     * the option on, because they fight with WeChat's built-in gradients otherwise.
     */
    private fun paletteTints(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
        errorColors: Boolean,
    ): List<MonetTint> {
        if (!errorColors) return emptyList()
        return ERROR_TINT_ROLES.mapNotNull { role ->
            resolved[role]?.let { MonetTint(it.binding(), 0x12, 0x1f) }
        }
    }

    private val ERROR_TINT_ROLES = listOf(
        "chat.input.background",
        "chat.quote.background",
        "chat.input.transparent-layer",
    )

    /**
     * The 16 palette entries。优先用平台的 Material You token（安卓 12+：写进微信资源的是对
     * framework id 的引用，能实时跟随壁纸）；平台没提供时退回 Joker 主题种子合成的色板
     * （安卓 11 以及部分 ROM —— `system_*` 从 API 31 才有，旧实现在这里直接抛错，
     * 于是整次解析失败）；再不行用内置基线色板。
     */
    @SuppressLint("DiscouragedApi")
    fun overlayPalette(resources: Resources, fallback: Palette? = null): Palette {
        frameworkPalette(resources)?.let { return it }
        if (fallback != null) {
            WeLogger.i(TAG, "平台未提供 Material You 取色，改用 Joker 主题种子色板")
            return fallback
        }
        WeLogger.w(TAG, "平台未提供 Material You 取色且无备用色板，使用内置基线色板")
        return BASELINE_PALETTE
    }

    /** 平台 Material You 色板：任一必需 token 缺失都返回 null（不抛错）。 */
    @SuppressLint("DiscouragedApi")
    private fun frameworkPalette(resources: Resources): Palette? {
        if (!paletteAvailable(resources)) return null
        fun token(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
            resources.getIdentifier(name, "color", "android").takeIf { it != 0 }
        }
        return Palette(
            surfaceLight = token("system_surface_light") ?: return null,
            surfaceDark = token("system_surface_dark") ?: return null,
            surfaceContainerLight = token(
                "system_surface_container_light", "system_neutral2_50", "system_surface_light",
            ) ?: return null,
            surfaceContainerDark = token(
                "system_surface_container_dark", "system_neutral2_800", "system_surface_dark",
            ) ?: return null,
            surfaceContainerHighLight = token(
                "system_surface_container_high_light", "system_surface_container_light", "system_surface_light",
            ) ?: return null,
            surfaceContainerHighDark = token(
                "system_surface_container_high_dark", "system_surface_container_dark", "system_surface_dark",
            ) ?: return null,
            primaryLight = token("system_primary_light", "system_accent1_500") ?: return null,
            primaryDark = token("system_primary_dark", "system_accent1_200") ?: return null,
            primaryContainerLight = token("system_primary_container_light", "system_accent1_100") ?: return null,
            primaryContainerDark = token("system_primary_container_dark", "system_accent1_800") ?: return null,
            accent1_300 = token("system_accent1_300") ?: return null,
            accent1_400 = token("system_accent1_400") ?: return null,
            accent1_500 = token("system_accent1_500") ?: return null,
            accent1_700 = token("system_accent1_700") ?: return null,
            accent2_100 = token("system_accent2_100") ?: return null,
            neutral2_700 = token("system_neutral2_700", "system_surface_dark") ?: return null,
        )
    }

    /**
     * 把色板里的 framework 资源引用（`android.R.color.system_*` 的 id）解析成具体 ARGB，
     * 供 Joker 自己注入微信界面的组件（不走资源替换，拿不到引用）取色。
     */
    @SuppressLint("DiscouragedApi")
    fun resolveArgb(resources: Resources, palette: Palette): Palette = Palette(
        surfaceLight = argb(resources, palette.surfaceLight),
        surfaceDark = argb(resources, palette.surfaceDark),
        surfaceContainerLight = argb(resources, palette.surfaceContainerLight),
        surfaceContainerDark = argb(resources, palette.surfaceContainerDark),
        surfaceContainerHighLight = argb(resources, palette.surfaceContainerHighLight),
        surfaceContainerHighDark = argb(resources, palette.surfaceContainerHighDark),
        primaryLight = argb(resources, palette.primaryLight),
        primaryDark = argb(resources, palette.primaryDark),
        primaryContainerLight = argb(resources, palette.primaryContainerLight),
        primaryContainerDark = argb(resources, palette.primaryContainerDark),
        accent1_300 = argb(resources, palette.accent1_300),
        accent1_400 = argb(resources, palette.accent1_400),
        accent1_500 = argb(resources, palette.accent1_500),
        accent1_700 = argb(resources, palette.accent1_700),
        accent2_100 = argb(resources, palette.accent2_100),
        neutral2_700 = argb(resources, palette.neutral2_700),
    )

    /**
     * 色板里可能是**字面 ARGB**（主题种子色板 / 基线色板，alpha 恒为 `0xFF`），也可能是
     * `android.R.color.system_*` 的**资源 id**。
     *
     * 判据只能看 alpha 字节：framework 资源 id 是 `0x01xxxxxx`（**不是** `0x00xxxxxx`），
     * 旧实现用 `value and 0xFF000000 == 0` 判断，于是安卓 12+ 的常规路径把资源 id 原样
     * 当成颜色发给了注入组件 —— 注入 UI 拿到「看着像颜色、其实是 id」的垃圾值，
     * 这正是用户反馈「微信原生已莫奈化、Joker 组件还是旧配色」的直接原因之一。
     */
    @SuppressLint("DiscouragedApi")
    private fun argb(resources: Resources, value: Int): Int {
        if (value ushr 24 == 0xFF) return value
        return runCatching { resources.getColor(value, null) }.getOrDefault(value)
    }

    /** 最后兜底：Material You 基线色板（AOSP 默认紫）。 */
    private val BASELINE_PALETTE = Palette(
        surfaceLight = 0xFFFFFBFE.toInt(),
        surfaceDark = 0xFF1C1B1F.toInt(),
        surfaceContainerLight = 0xFFF3EDF7.toInt(),
        surfaceContainerDark = 0xFF211F26.toInt(),
        surfaceContainerHighLight = 0xFFECE6F0.toInt(),
        surfaceContainerHighDark = 0xFF2B2930.toInt(),
        primaryLight = 0xFF6750A4.toInt(),
        primaryDark = 0xFFD0BCFF.toInt(),
        primaryContainerLight = 0xFFEADDFF.toInt(),
        primaryContainerDark = 0xFF4F378B.toInt(),
        accent1_300 = 0xFFB69DF8.toInt(),
        accent1_400 = 0xFF9A82DB.toInt(),
        accent1_500 = 0xFF6750A4.toInt(),
        accent1_700 = 0xFF4F378B.toInt(),
        accent2_100 = 0xFFFFD8E4.toInt(),
        neutral2_700 = 0xFF49454F.toInt(),
    )

    /** Whether the platform exposes Material You colours at all (Android 12+). */
    @SuppressLint("DiscouragedApi")
    fun paletteAvailable(resources: Resources): Boolean =
        resources.getIdentifier("system_accent1_500", "color", "android") != 0

    /**
     * Resolves one `theme.color.<light>--<night>[.slot-NN]` rule id into the colour pair written into
     * the runtime package. Tokens are either a literal ARGB hex string, `unknown`, or a
     * `system-<role>` name from [overlayPalette].
     */
    @SuppressLint("DiscouragedApi")
    fun paletteFor(id: String, resources: Resources): Pair<ColorValue?, ColorValue?> {
        val semantic = id.removePrefix("theme.color.").substringBefore(".slot-")
        val parts = semantic.split("--", limit = 2)
        fun resolve(token: String): ColorValue? {
            if (token == "unknown") return null
            if (token.length == 8 && token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return runCatching { ColorValue.Literal(token.toUInt(16).toInt()) }.getOrNull()
            }
            if (!token.startsWith("system-")) {
                // 规则表里出现未知 token 时只放弃这一侧，不能让整次解析失败
                WeLogger.w(TAG, "unsupported Monet color token: $token")
                return null
            }
            val normalized = token.replace('-', '_')
            val fallbacks = when (normalized) {
                "system_surface_container_light" ->
                    listOf(normalized, "system_neutral2_50", "system_surface_light")
                "system_surface_container_dark" ->
                    listOf(normalized, "system_neutral2_800", "system_surface_dark")
                else -> listOf(normalized)
            }
            val id = frameworkColorId(resources, *fallbacks.toTypedArray()) ?: return null
            return ColorValue.Reference(id)
        }
        // 用 firstOrNull 兜住「空列表」这种不可能但一旦发生就整次失败的输入。
        val primary = parts.firstOrNull() ?: semantic
        return resolve(primary) to resolve(parts.getOrElse(1) { primary })
    }

    /** 平台 token 的**资源 id**（写进替换资源里作为引用），取不到返回 null。 */
    @SuppressLint("DiscouragedApi")
    private fun frameworkColorId(resources: Resources, vararg names: String): Int? =
        names.firstNotNullOfOrNull { name ->
            resources.getIdentifier(name, "color", "android").takeIf { it != 0 }
        }
}
