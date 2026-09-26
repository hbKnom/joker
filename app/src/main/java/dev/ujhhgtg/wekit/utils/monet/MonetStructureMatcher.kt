package dev.ujhhgtg.wekit.utils.monet

import dev.ujhhgtg.wekit.utils.WeLogger


object MonetStructureMatcher {

    private const val TAG = "MonetStructureMatcher"

    /** 匹配阶段占空比：连续跑满这么久就让出一次 CPU。 */
    private const val MATCH_SLICE_NANOS = 2_000_000_000L
    private const val MATCH_REST_MILLIS = 500L

    /**
     * 匹配阶段的「占空比让出」。
     *
     * 为什么必须要有：微信 base.apk 有 7 万个资源、同类型节点可达 2 万个，而 231 个语义角色
     * 每个都要遍历同类型节点做特征计算 —— 实机日志
     * `resolved 231 roles (27 drawables, 186 colors, 0 unresolved)，匹配用时 145544 ms`。
     * 这 145 秒里如果一直吃满一个核，用户在前台看到的就是「生成莫奈解析资源的过程很卡/闪退」
     * （本次实机反馈的卡顿之一）。每连续跑满 [MATCH_SLICE_NANOS] 就 `Thread.sleep` 让出
     * [MATCH_REST_MILLIS]，把 CPU 还给前台；代价是总时长增加约 25%，换前台不抖。
     */
    private class CpuYielder {
        private var sliceStart = System.nanoTime()
        private var yields = 0

        fun maybeYield() {
            if (System.nanoTime() - sliceStart < MATCH_SLICE_NANOS) return
            yields++
            runCatching { Thread.sleep(MATCH_REST_MILLIS) }
            sliceStart = System.nanoTime()
        }

        fun report(label: String) {
            if (yields > 0) WeLogger.d(TAG, "$label 分片让出 $yields 次，避免长时间占用 CPU")
        }
    }

    val roleIds: Set<String> = MONET_RULES.mapTo(linkedSetOf(), MonetSemanticRule::id)

    fun resolveAll(
        graph: MonetResourceGraph,
        dexProvider: MonetDexEvidenceProvider? = null,
        onProgress: (completed: Int?, total: Int?, detail: String) -> Unit = { _, _, _ -> },
    ): Map<String, MonetResourceNode> {
        // 角色候选解析里任何一环抛异常都不能作废整次解析：实机日志中出现过
        // `resource analysis failed during RESOLVING_ROLES / java.util.NoSuchElementException:
        // List is empty.` —— 只要它从这里冒出去，用户看到的就是「莫奈完全不生效」。
        // 现在退回**纯结构消歧**的结果：能解析出来的角色照常生效，最坏只是少数歧义角色缺席。
        val audited = runCatching { resolveCandidateIds(graph, dexProvider, onProgress) }
            .onFailure { WeLogger.w(TAG, "角色候选解析异常，退回纯结构消歧（其余角色照常）", it) }
            .getOrElse {
                runCatching { structuralResolution(graph, onProgress).candidates }
                    .onFailure { WeLogger.e(TAG, "结构消歧同样失败，本次没有可解析的角色", it) }
                    .getOrDefault(emptyMap())
            }
        onProgress(null, null, "校验语义角色解析结果")
        // 每个角色独立降级：某个角色「0 个或 2 个以上候选」只让那个角色缺席，
        // 绝不把整次解析打断。旧实现在这里 require(single) 抛错，
        // 微信某次版本变化就会让用户看到「解析失败」（用户反馈的解析问题主因之一）。
        val resolved = linkedMapOf<String, MonetResourceNode>()
        var skipped = 0
        MONET_RULES.forEach { rule ->
            val candidates = audited[rule].orEmpty().mapNotNull(graph::node)
            val optional = rule.optional || rule.optionalWhenResourceAbsent?.let { graph.node(it) == null } == true
            when {
                candidates.size == 1 -> resolved[rule.id] = candidates.single()
                optional && candidates.isEmpty() -> Unit
                else -> {
                    skipped++
                    WeLogger.w(
                        TAG,
                        "角色 ${rule.id} 无法唯一解析（${candidates.size} 个候选：${candidates.map { it.key }}），跳过该角色",
                    )
                }
            }
        }
        if (skipped > 0) WeLogger.i(TAG, "$skipped/${MONET_RULES.size} 个角色未解析，其余照常应用")
        // 多个角色指向同一资源只是命名重叠，不冲突：下游按角色取用，各自照常生效。
        val duplicateRoles = resolved.entries.groupBy { it.value.id }.filterValues { it.size > 1 }
        if (duplicateRoles.isNotEmpty()) {
            WeLogger.w(TAG, "多个角色解析到同一资源：${duplicateRoles.values.map { it.map { e -> e.key } }}")
        }
        onProgress(MONET_RULES.size, MONET_RULES.size, "语义角色解析完成")
        return resolved
    }

    fun audit(
        graph: MonetResourceGraph,
        dexProvider: MonetDexEvidenceProvider? = null,
    ): Map<String, List<MonetResourceNode>> {
        val finalCandidates = resolveCandidateIds(graph, dexProvider)
        return MONET_RULES.associate { rule ->
            rule.id to finalCandidates.getValue(rule).mapNotNull(graph::node)
        }
    }

    private fun resolveCandidateIds(
        graph: MonetResourceGraph,
        dexProvider: MonetDexEvidenceProvider?,
        onProgress: (completed: Int?, total: Int?, detail: String) -> Unit = { _, _, _ -> },
    ): Map<MonetSemanticRule, Set<Int>> {
        val structural = structuralResolution(graph, onProgress)
        val candidates = structural.candidates
        val anchored = candidates.filter { (rule, ids) -> rule.requiredDexEvidence.isNotEmpty() && ids.size > 1 }
        val dexFiltered = if (anchored.isEmpty() || dexProvider == null) {
            if (anchored.isNotEmpty()) {
                WeLogger.w(
                    TAG,
                    "缺少 DEX 证据提供者，${anchored.size} 个歧义角色改用结构消歧",
                )
            }
            emptyMap()
        } else {
            onProgress(null, null, "分析代码引用，区分 ${anchored.size} 个歧义角色")
            val provider = dexProvider
            val neighborIds = anchored.keys.flatMap { rule ->
                rule.requiredDexEvidence.mapNotNull { token ->
                    token.removePrefix("neighbor:").takeIf { token.startsWith("neighbor:") }
                }
            }.mapNotNull { role ->
                candidates.entries.firstOrNull { it.key.id == role }?.value?.singleOrNull()
                    ?.let { role to it }
            }.toMap()
            val requestedIds = (anchored.values.flatten() + neighborIds.values).distinct().sorted()
            // DexKit 不可用、扫描失败、图里取不到节点，都只退化成「结构消歧」：
            // 歧义角色的候选原样保留，其余角色照常解析，绝不把整次解析打断。
            val evidence = runCatching {
                provider.query(requestedIds.mapNotNull { id ->
                    val node = graph.node(id) ?: return@mapNotNull null
                    MonetDexCandidate(id, node.key.type, node.key.name)
                })
            }.onFailure { WeLogger.w(TAG, "DEX 证据扫描失败，改用结构消歧", it) }.getOrDefault(emptyList())
            if (evidence.isEmpty()) {
                emptyMap()
            } else {
                val byId = evidence.distinctBy { it.resourceId }.associateBy { it.resourceId }
                anchored.mapValues { (rule, ids) ->
                    ids.filterTo(linkedSetOf()) { id ->
                        byId[id]?.methods.orEmpty().any { method ->
                            val tokens = method.tokens(neighborIds)
                            tokens.containsAll(rule.requiredDexEvidence)
                        }
                    }
                }
            }
        }
        val combined = MONET_RULES.associateWith { rule ->
            dexFiltered[rule] ?: candidates.getValue(rule)
        }
        onProgress(null, null, "校验角色关联并消除歧义")
        val related = applyRoleRelations(combined, graph)
        return disambiguate(assignPreferred(related, structural.preferredScores), assignEquivalentGroups = true)
    }

    private fun applyRoleRelations(
        input: Map<MonetSemanticRule, Set<Int>>,
        graph: MonetResourceGraph,
    ): Map<MonetSemanticRule, Set<Int>> {
        var result = disambiguate(input, assignEquivalentGroups = false)
        while (true) {
            var changed = false
            val byRole = result.entries.associate { it.key.id to it.value }
            val filtered = result.mapValues { (rule, ids) ->
                val related = rule.requiredAdjacentRoles.mapNotNull { (offset, role) ->
                    byRole[role]?.singleOrNull()?.let { offset to it }
                }
                if (related.size != rule.requiredAdjacentRoles.size) ids else ids.filterTo(linkedSetOf()) { id ->
                    related.all { (offset, expected) -> graph.node(id + offset)?.id == expected }
                }.also { if (it != ids) changed = true }
            }
            result = disambiguate(filtered, assignEquivalentGroups = false)
            if (!changed) return result
        }
    }

    fun structuralCandidates(graph: MonetResourceGraph): Map<MonetSemanticRule, Set<Int>> {
        return structuralResolution(graph).candidates
    }

    fun structuralAudit(graph: MonetResourceGraph): Map<String, List<MonetResourceNode>> {
        val resolution = structuralResolution(graph)
        val related = applyRoleRelations(resolution.candidates, graph)
        return disambiguate(assignPreferred(related, resolution.preferredScores), assignEquivalentGroups = true)
            .mapKeys { it.key.id }.mapValues { (_, ids) -> ids.mapNotNull(graph::node) }
    }

    private fun structuralResolution(
        graph: MonetResourceGraph,
        onProgress: (completed: Int?, total: Int?, detail: String) -> Unit = { _, _, _ -> },
    ): StructuralResolution {
        val requiredByType = MONET_RULES.groupBy(MonetSemanticRule::type).mapValues { (_, rules) ->
            rules.flatMapTo(hashSetOf()) { it.requiredEvidence + it.preferredEvidence }
        }
        val idsByToken = HashMap<String, MutableSet<Int>>()
        val nodesByType = requiredByType.keys.associateWith(graph::nodes)
        val resourceTotal = nodesByType.values.sumOf { it.size }
        var scanned = 0
        // 这两段循环实机合计要跑 ~145 秒，全程让出 CPU 给前台的见 [CpuYielder]。
        val yielder = CpuYielder()
        if (resourceTotal > 0) onProgress(0, resourceTotal, "扫描资源特征与引用关系")
        requiredByType.forEach { (type, required) ->
            nodesByType.getValue(type).forEach { node ->
                calculateEvidence(node, graph, required).forEach { token ->
                    idsByToken.getOrPut(token, ::linkedSetOf).add(node.id)
                }
                scanned++
                yielder.maybeYield()
                // Resource scans can be large; avoid flooding the main-thread event queue.
                if (scanned % 32 == 0 || scanned == resourceTotal) {
                    onProgress(scanned, resourceTotal, "扫描资源特征与引用关系（$type）")
                }
            }
        }
        yielder.report("资源特征扫描")
        var matched = 0
        // 公告卡片（notice card）的颜色只在部分版本的布局里存在，探测里有 `single()` /
        // `require` 这类硬断言。旧实现把它放在**逐角色的 runCatching 之外**，于是
        // 「这台机器的布局和基线对不上」时抛出的 NoSuchElementException("List is empty")
        // 会一路冒到顶层 —— 实机看到的正是「解析失败：List is empty」，整次解析白跑、莫奈全灭。
        // 现在它和逐角色一样只降级：探测不出来就只让这两个角色缺席。
        val noticeCardColors = runCatching { graph.noticeCardColors() }
            .onFailure { WeLogger.w(TAG, "公告卡片颜色特征不可用，相关角色跳过", it) }
            .getOrDefault(emptySet<Int>() to emptySet<Int>())
        val initialCandidates = MONET_RULES.associateWith { rule ->
            yielder.maybeYield()
            onProgress(matched, MONET_RULES.size, "匹配角色候选：${rule.id}")
            // Reuse the type-indexed node list built for the evidence scan instead of rebuilding it
            // for every rule (hundreds of rules × tens of thousands of resources).
            val nodesOfType = nodesByType.getValue(rule.type)
            // 单个角色的特征计算失败（某版本没有这块资源 / 基线断言不成立）只让**该角色缺席**。
            // 旧实现让 `require` 直接抛出去，用户看到的就是整次「解析失败」（解析报错的主要来源之一）。
            val matchedCandidates = runCatching {
            val baseline = COLOR_BASELINES[rule.id]
            val colorCandidates = baseline?.let { expected ->
                nodesOfType.filterTo(linkedSetOf()) { node ->
                    node.values.associate { it.qualifiers to ((it.value as? MonetResourceValue.Literal)?.data ?: Long.MIN_VALUE) } == expected
                }.mapTo(linkedSetOf(), MonetResourceNode::id)
            }
            val selectorCandidates = COLOR_SELECTOR_BASELINES[rule.id]?.let { expected ->
                nodesOfType.filterTo(linkedSetOf()) { node ->
                    graph.xmlTrees(node.id).any { it.containsLiteralColor(expected) }
                }.mapTo(linkedSetOf(), MonetResourceNode::id)
            }
            val structural = if (rule.requiredEvidence.isEmpty()) {
                nodesOfType.mapTo(linkedSetOf(), MonetResourceNode::id)
            } else {
                rule.requiredEvidence.map { idsByToken[it].orEmpty() }
                    .reduce { result, ids -> result.intersect(ids) }
            }
            val semanticCandidates: Set<Int>? = when (rule.id) {
                // Older search bars share the global surface color. Only overlay a dedicated tint;
                // the shared color remains owned by slot-40, whose night palette is different.
                // 探测为空就是「该角色缺席」，不再 require 抛错（空集与抛错的最终效果一样，
                // 但抛错会把这一条错误留在日志里误导排查）。
                SEARCH_BAR_BACKGROUND -> graph.actionBarSearchBackgroundColors() -
                    idsByToken["usage:drawable:selector/item/rotate/rotate/shape/solid:16843173:color"].orEmpty()
                VOICE_INPUT_BACKGROUND -> graph.voiceInputBackgroundColors()
                THREE_STATE_STROKE -> graph.threeStateSelectorDefaultStrokeColors()
                FINDER_LIVE_TAB -> colorCandidates?.filterNotTo(linkedSetOf()) { graph.isVipBadgeColor(it) }
                DELETE_ACTION_COLOR -> graph.sharedRawIconTextColors("icons_outlined_delete", 0xffedededL)
                APP_BRAND_PAGE_BACKGROUND -> graph.sandwichedColor(0xff333333L, 0xfff2f2f2L, 0xff191919L)
                SURFACE_CONTAINER_SLOT_56 -> graph.ecsFeedbackBackgroundColors()
                SURFACE_CONTAINER_SLOT_58 -> noticeCardColors.first
                SURFACE_CONTAINER_SLOT_59 -> noticeCardColors.second
                SURFACE_CONTAINER_SLOT_57 -> graph.sharedArrowIconTextColors()
                else -> null
            }
            val static = STATIC_ROLE_NAMES[rule.id].orEmpty().firstNotNullOfOrNull { name ->
                graph.node(MonetResourceKey(rule.type, name))?.takeIf { node ->
                    (node.id in structural || node.id in colorCandidates.orEmpty()) &&
                        (!rule.id.endsWith("received") || graph.xmlTrees(node.id).any { it.name == "selector" })
                }?.id
            }?.let(::setOf)
            if (semanticCandidates != null) {
                semanticCandidates
            } else if (static != null && (structural.isEmpty() || static.any { it in structural })) {
                static.intersect(structural).takeIf { it.isNotEmpty() } ?: static
            } else if (rule.requiredEvidence.isEmpty()) {
                selectorCandidates?.takeIf { it.isNotEmpty() } ?: colorCandidates?.takeIf { it.isNotEmpty() } ?: nodesOfType.mapTo(linkedSetOf(), MonetResourceNode::id)
            } else {
                selectorCandidates?.takeIf { it.isNotEmpty() }?.let { structural.intersect(it).takeIf { it.isNotEmpty() } ?: it }
                    ?: colorCandidates?.takeIf { it.isNotEmpty() }?.let { colors ->
                    structural.intersect(colors).takeIf { it.isNotEmpty() } ?: colors
                } ?: structural
            }
            }.onFailure {
                WeLogger.w(TAG, "角色 ${rule.id} 特征计算失败，跳过该角色", it)
            }.getOrDefault(emptySet())
            matched++
            onProgress(matched, MONET_RULES.size, "已检查角色候选：${rule.id}")
            matchedCandidates
        }
        onProgress(null, null, "整理角色候选与资源特征")
        val candidates = disambiguate(initialCandidates, assignEquivalentGroups = false)
        val scores = MONET_RULES.associateWith { rule ->
            candidates.getValue(rule).associateWith { id ->
                rule.preferredEvidence.count { id in idsByToken[it].orEmpty() }
            }
        }
        return StructuralResolution(candidates, scores)
    }

    /**
     * Cross-version source anchors confirmed in Play and domestic decompilations.
     * Names are aliases for one source use, not a reference-module mapping.
     */
    private val STATIC_ROLE_NAMES = mapOf(
        "chat.transfer.incoming.expired" to listOf("c2c_chatfrom_remittance_expired_bg"),
        "chat.transfer.outgoing.expired" to listOf("c2c_chatto_remittance_expired_bg"),
        "chat.transfer.incoming.received" to listOf("z1", "k6", "ym"),
        "chat.transfer.outgoing.received" to listOf("zc", "k9", "yy"),
        "theme.color.unknown--10ffffff.slot-06" to listOf("rh", "aa4"),
        "theme.color.unknown--system-surface-dark.slot-02" to listOf("e2", "ni"),
    )
    private data class StructuralResolution(
        val candidates: Map<MonetSemanticRule, Set<Int>>,
        val preferredScores: Map<MonetSemanticRule, Map<Int, Int>>,
    )

    private val COLOR_BASELINES = mapOf(
        "theme.color.system-surface-light--system-surface-dark.slot-04" to mapOf("" to 4291801463L),
    )
    private val COLOR_SELECTOR_BASELINES = mapOf(
        "theme.color.system-surface-dark--system-surface-dark.slot-02" to 4278627926L,
    )
    private const val SURFACE_CONTAINER_SLOT_57 =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-57"
    private const val SURFACE_CONTAINER_SLOT_56 =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-56"
    private const val SURFACE_CONTAINER_SLOT_58 =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-58"
    private const val SURFACE_CONTAINER_SLOT_59 =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-59"
    private const val SEARCH_BAR_BACKGROUND =
        "theme.color.system-surface-container-light--10ffffff.slot-02"
    private const val VOICE_INPUT_BACKGROUND =
        "theme.color.system-surface-container-light--10ffffff.slot-03"
    private const val THREE_STATE_STROKE =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-50"
    private const val FINDER_LIVE_TAB =
        "theme.color.system-surface-light--system-surface-dark.slot-04"
    private const val DELETE_ACTION_COLOR =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-26"
    private const val APP_BRAND_PAGE_BACKGROUND =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-42"

    private fun MonetXmlElement.containsLiteralColor(expected: Long): Boolean =
        attributes.any { (it.value as? MonetResourceValue.Literal)?.data == expected } ||
            children.any { it.containsLiteralColor(expected) }

    private fun assignPreferred(
        input: Map<MonetSemanticRule, Set<Int>>,
        scores: Map<MonetSemanticRule, Map<Int, Int>>,
    ): Map<MonetSemanticRule, Set<Int>> {
        val result = input.mapValuesTo(linkedMapOf()) { it.value.toSet() }
        result.entries.filter { it.value.size > 1 && it.key.preferredEvidence.isNotEmpty() }
            .groupBy { it.key.equivalentOutputSemantic() }.filterKeys { it != null }
            .forEach { (_, entries) ->
                val roles = entries.map { it.key }.sortedBy(MonetSemanticRule::id)
                val candidates = entries.flatMap { it.value }.distinct().sorted()
                if (roles.size > candidates.size) return@forEach
                val assignment = minimumCostAssignment(roles, candidates) { role, candidate ->
                    if (candidate !in result.getValue(role)) 1_000_000 else -scores.getValue(role).getValue(candidate)
                }
                if (assignment.all { (roleIndex, candidateIndex) ->
                        val role = roles[roleIndex]
                        val candidate = candidates[candidateIndex]
                        candidate in result.getValue(role) && scores.getValue(role).getValue(candidate) > 0
                    }
                ) {
                    assignment.forEach { (roleIndex, candidateIndex) ->
                        result[roles[roleIndex]] = setOf(candidates[candidateIndex])
                    }
                }
            }
        return result
    }

    private fun minimumCostAssignment(
        rows: List<MonetSemanticRule>,
        columns: List<Int>,
        cost: (MonetSemanticRule, Int) -> Int,
    ): Map<Int, Int> {
        val rowPotential = IntArray(rows.size + 1)
        val columnPotential = IntArray(columns.size + 1)
        val matchedRow = IntArray(columns.size + 1)
        val way = IntArray(columns.size + 1)
        for (row in rows.indices) {
            matchedRow[0] = row + 1
            var column = 0
            val minimum = IntArray(columns.size + 1) { Int.MAX_VALUE }
            val used = BooleanArray(columns.size + 1)
            do {
                used[column] = true
                val currentRow = matchedRow[column]
                var delta = Int.MAX_VALUE
                var nextColumn = 0
                for (candidateColumn in 1..columns.size) if (!used[candidateColumn]) {
                    val current = cost(rows[currentRow - 1], columns[candidateColumn - 1]) -
                        rowPotential[currentRow] - columnPotential[candidateColumn]
                    if (current < minimum[candidateColumn]) {
                        minimum[candidateColumn] = current
                        way[candidateColumn] = column
                    }
                    if (minimum[candidateColumn] < delta) {
                        delta = minimum[candidateColumn]
                        nextColumn = candidateColumn
                    }
                }
                for (candidateColumn in 0..columns.size) if (used[candidateColumn]) {
                    rowPotential[matchedRow[candidateColumn]] += delta
                    columnPotential[candidateColumn] -= delta
                } else {
                    minimum[candidateColumn] -= delta
                }
                column = nextColumn
            } while (matchedRow[column] != 0)
            do {
                val previous = way[column]
                matchedRow[column] = matchedRow[previous]
                column = previous
            } while (column != 0)
        }
        return (1..columns.size).filter { matchedRow[it] != 0 }
            .associate { matchedRow[it] - 1 to it - 1 }
    }

    private fun disambiguate(
        input: Map<MonetSemanticRule, Set<Int>>,
        assignEquivalentGroups: Boolean,
    ): Map<MonetSemanticRule, Set<Int>> {
        val result = input.mapValuesTo(linkedMapOf()) { (_, ids) -> ids.toSet() }
        while (true) {
            var changed = false
            val singletons = result.filterValues { it.size == 1 }.entries.groupBy { it.value.single() }
            val claimed = singletons.keys
            result.entries.filter { it.value.size > 1 }.forEach { entry ->
                val filtered = entry.value - claimed
                if (filtered != entry.value) {
                    entry.setValue(filtered)
                    changed = true
                }
            }
            if (assignEquivalentGroups) {
                result.entries.filter { it.value.size > 1 }.groupBy { it.value }.forEach { (ids, entries) ->
                    val semantic = entries.map { it.key.equivalentOutputSemantic() }.distinct()
                    if (entries.size == ids.size && semantic.size == 1 && semantic.single() != null) {
                        entries.sortedBy { it.key.id }.zip(ids.sorted()).forEach { (entry, id) ->
                            entry.setValue(setOf(id))
                        }
                        changed = true
                    }
                }
            }
            if (!changed) return result
        }
    }

    private fun MonetSemanticRule.equivalentOutputSemantic(): String? = when {
        id.startsWith("theme.color.") -> id.substringBefore(".slot-")
        id.startsWith("chat.transfer.incoming.") || id.startsWith("chat.transfer.outgoing.") ->
            id.substringBeforeLast('.')
        else -> null
    }

    private fun MonetMethodDexEvidence.tokens(neighborIds: Map<String, Int>): Set<String> = buildSet {
        add("descriptor:$descriptor")
        add("owner-package:$ownerPackage")
        add("method-shape:$methodShape")
        stableStrings.forEach { add("string:$it") }
        invokedMethodShapes.forEach { add("invoke:$it") }
        neighborIds.forEach { (role, id) -> if (id in neighboringResourceIds) add("neighbor:$role") }
        fieldAccesses.forEach { field ->
            add("field:${if (field.access == MonetFieldAccess.READ) "read" else "write"}:${field.descriptor}")
        }
    }

    /**
     * Evidence token sink that drops everything the caller will not look up.
     *
     * Evidence strings are extremely verbose (a single layout contributes an entry per element and
     * per attribute, plus the `child:`/`sibling:`/`context:` neighbourhood expansions), so it is
     * kept out of memory entirely: materialising them per resource exhausted the host heap
     * (`OutOfMemoryError` at the 512 MB process limit) while only a few hundred tokens are ever
     * queried. `null` keeps every token and is used by [evidence] for one-off lookups.
     */
    private class FilteredEvidenceSink(private val accepted: Set<String>?) : AbstractMutableSet<String>() {
        private val kept = linkedSetOf<String>()

        override val size: Int get() = kept.size

        override fun add(element: String): Boolean =
            (accepted == null || element in accepted) && kept.add(element)

        override fun iterator(): MutableIterator<String> = kept.iterator()

        override fun remove(element: String): Boolean = kept.remove(element)

        override fun clear() = kept.clear()
    }

    /** Prefixes every token with [prefix] before handing it to [delegate], without allocating it. */
    private class PrefixedEvidenceSink(
        private val prefix: String,
        private val delegate: MutableSet<String>,
    ) : AbstractMutableSet<String>() {
        override val size: Int get() = delegate.size

        override fun add(element: String): Boolean = delegate.add(prefix + element)

        override fun iterator(): MutableIterator<String> = delegate.iterator()

        override fun remove(element: String): Boolean = delegate.remove(element)

        override fun clear() = delegate.clear()
    }

    fun evidence(node: MonetResourceNode, graph: MonetResourceGraph): Set<String> =
        FilteredEvidenceSink(null).also { computeEvidence(node, graph, it) }

    /** Evidence of [node] restricted to [accepted]; nothing else is ever allocated. */
    private fun calculateEvidence(
        node: MonetResourceNode,
        graph: MonetResourceGraph,
        accepted: Set<String>,
    ): Set<String> = FilteredEvidenceSink(accepted).also { computeEvidence(node, graph, it) }

    private fun computeEvidence(
        node: MonetResourceNode,
        graph: MonetResourceGraph,
        sink: MutableSet<String>,
    ) {
        computeLocalEvidence(node, graph, sink)
        computeUsageEvidence(node, graph, sink)
        graph.outgoing(node.id).mapNotNull(graph::node).forEach { child ->
            computeLocalEvidence(child, graph, PrefixedEvidenceSink("child:${child.key.type}:", sink))
            graph.outgoing(child.id).mapNotNull(graph::node).forEach { grandchild ->
                computeLocalEvidence(
                    grandchild,
                    graph,
                    PrefixedEvidenceSink("child:${child.key.type}:${grandchild.key.type}:", sink),
                )
            }
        }
        (-2..2).filter { it != 0 }.forEach { offset ->
            graph.node(node.id + offset)?.takeIf { it.key.type == node.key.type }?.let { neighbor ->
                computeLocalEvidence(neighbor, graph, PrefixedEvidenceSink("adjacent:$offset:", sink))
            }
        }
        graph.incoming(node.id).mapNotNull(graph::node).forEach { owner ->
            val context = PrefixedEvidenceSink("context:${owner.key.type}:", sink)
            computeLocalEvidence(owner, graph, context)
            computeUsageEvidence(owner, graph, context)
            graph.outgoing(owner.id).filter { it != node.id }.mapNotNull(graph::node).forEach { sibling ->
                computeLocalEvidence(
                    sibling,
                    graph,
                    PrefixedEvidenceSink("sibling:${owner.key.type}:${sibling.key.type}:", sink),
                )
            }
        }
    }

    private fun computeLocalEvidence(
        node: MonetResourceNode,
        graph: MonetResourceGraph,
        sink: MutableSet<String>,
    ) {
        node.values.forEach { configured ->
            sink += "config:${configured.qualifiers}:${configured.value.evidence(graph)}"
        }
        val trees = graph.xmlTrees(node.id)
        trees.forEach { it.collectEvidence("", graph, sink) }
        trees.forEach { it.collectSimpleEvidence("", graph, sink) }
        graph.outgoing(node.id).mapNotNull(graph::node).forEach { sink += "outgoing:${it.key.type}" }
    }

    private fun computeUsageEvidence(
        node: MonetResourceNode,
        graph: MonetResourceGraph,
        sink: MutableSet<String>,
    ) {
        graph.incoming(node.id).mapNotNull(graph::node).forEach { owner ->
            sink += "incoming:${owner.key.type}"
            owner.values.forEach { configured ->
                configured.value.collectUsage(node.id, "owner:${owner.key.type}", graph, sink)
            }
            graph.xmlTrees(owner.id).forEach { tree ->
                tree.collectUsage(node.id, "", owner.key.type, graph, sink)
                tree.collectSimpleUsage(node.id, "", owner.key.type, sink)
            }
        }
    }

    fun candidates(
        reference: MonetResourceNode,
        referenceGraph: MonetResourceGraph,
        targetGraph: MonetResourceGraph,
    ): List<MonetResourceNode> {
        val expected = feature(reference, referenceGraph)
        return targetGraph.nodes(reference.key.type).filter { feature(it, targetGraph) == expected }
    }

    private fun feature(node: MonetResourceNode, graph: MonetResourceGraph) = ResourceFeature(
        values = node.values.map { ConfigFeature(it.qualifiers, it.value.feature(graph)) }.sortedBy { it.qualifiers },
        xml = graph.xmlTrees(node.id).map { it.feature(graph) },
    )

    private fun MonetXmlElement.feature(graph: MonetResourceGraph): XmlFeature = XmlFeature(
        name = name,
        attributes = attributes.map { attribute ->
            AttributeFeature(
                nameId = attribute.nameId,
                name = attribute.name,
                valueType = attribute.valueType,
                value = attribute.value.feature(graph),
            )
        }.sortedWith(compareBy({ it.nameId }, { it.name }, { it.valueType }, { it.value.toString() })),
        children = children.map { it.feature(graph) },
    )

    private fun MonetResourceValue.feature(graph: MonetResourceGraph): ValueFeature = when (this) {
        is MonetResourceValue.Reference -> ValueFeature(
            kind = "reference",
            type = graph.node(resourceId)?.key?.type ?: "framework",
            valueType = valueType,
        )
        is MonetResourceValue.Literal -> ValueFeature(
            kind = "literal",
            type = null,
            valueType = valueType,
            data = data,
        )
        is MonetResourceValue.File -> ValueFeature("file", null, structure?.toString() ?: "FILE")
        is MonetResourceValue.Text -> ValueFeature("text", null, "STRING", text = value)
        is MonetResourceValue.Complex -> ValueFeature(
            kind = "complex",
            type = graph.node(parentId)?.key?.type,
            valueType = "COMPLEX",
            items = items.map { it.nameId to it.value.feature(graph) },
        )
    }

    private data class ResourceFeature(val values: List<ConfigFeature>, val xml: List<XmlFeature>)
    private data class ConfigFeature(val qualifiers: String, val value: ValueFeature)
    private data class XmlFeature(
        val name: String,
        val attributes: List<AttributeFeature>,
        val children: List<XmlFeature>,
    )
    private data class AttributeFeature(
        val nameId: Int?,
        val name: String,
        val valueType: String,
        val value: ValueFeature,
    )
    private data class ValueFeature(
        val kind: String,
        val type: String?,
        val valueType: String,
        val text: String? = null,
        val data: Long? = null,
        val items: List<Pair<Int, ValueFeature>> = emptyList(),
    )
}

private fun MonetXmlElement.collectUsage(
    targetId: Int,
    parent: String,
    ownerType: String,
    graph: MonetResourceGraph,
    result: MutableSet<String>,
) {
    val path = if (parent.isEmpty()) name else "$parent/$name"
    attributes.filter { (it.value as? MonetResourceValue.Reference)?.resourceId == targetId }
        .forEach { result += "usage:$ownerType:$path:${it.nameId}:${it.name}" }
    children.forEach { it.collectUsage(targetId, path, ownerType, graph, result) }
}

private fun MonetXmlElement.collectSimpleUsage(
    targetId: Int,
    parent: String,
    ownerType: String,
    result: MutableSet<String>,
) {
    val simpleName = name.substringAfterLast('.')
    val path = if (parent.isEmpty()) simpleName else "$parent/$simpleName"
    attributes.filter { (it.value as? MonetResourceValue.Reference)?.resourceId == targetId }
        .forEach { result += "simple-usage:$ownerType:$path:${it.nameId}:${it.name}" }
    children.forEach { it.collectSimpleUsage(targetId, path, ownerType, result) }
}

private fun MonetXmlElement.referenceIds(): Set<Int> = buildSet {
    attributes.forEach { attribute ->
        (attribute.value as? MonetResourceValue.Reference)?.let { add(it.resourceId) }
    }
    children.forEach { addAll(it.referenceIds()) }
}

private fun MonetResourceGraph.sharedArrowIconTextColors(): Set<Int> = buildSet {
    nodes("layout").forEach { owner ->
        xmlTrees(owner.id).forEach { it.collectSharedArrowIconTextColors(this@sharedArrowIconTextColors, this) }
    }
}

private fun MonetResourceGraph.sharedRawIconTextColors(rawName: String, expectedColor: Long): Set<Int> = buildSet {
    nodes("layout").forEach { owner ->
        xmlTrees(owner.id).forEach { it.collectSharedRawIconTextColors(this@sharedRawIconTextColors, rawName, this) }
    }
    retainAll(nodes("color").filter { it.defaultLiteral() == expectedColor }.map(MonetResourceNode::id).toSet())
}

private fun MonetXmlElement.collectSharedRawIconTextColors(
    graph: MonetResourceGraph,
    rawName: String,
    result: MutableSet<Int>,
) {
    val icon = children.firstOrNull {
        it.reference("src")?.let(graph::node)?.key == MonetResourceKey("raw", rawName)
    }
    val iconColor = icon?.reference("iconColor")
    if (iconColor != null && children.any { it.name.substringAfterLast('.') == "TextView" && it.reference("textColor") == iconColor }) {
        result += iconColor
    }
    children.forEach { it.collectSharedRawIconTextColors(graph, rawName, result) }
}

private fun MonetResourceGraph.sandwichedColor(before: Long, value: Long, after: Long): Set<Int> =
    nodes("color").filterTo(linkedSetOf()) { node ->
        node.defaultLiteral() == value && this@sandwichedColor.node(node.id - 1)?.defaultLiteral() == before &&
            this@sandwichedColor.node(node.id + 1)?.defaultLiteral() == after
    }.mapTo(linkedSetOf(), MonetResourceNode::id)

private fun MonetResourceNode.defaultLiteral(): Long? =
    (values.singleOrNull { it.qualifiers.isEmpty() }?.value as? MonetResourceValue.Literal)?.data

private fun MonetResourceGraph.actionBarSearchBackgroundColors(): Set<Int> = buildSet {
    nodes("layout").forEach { owner ->
        xmlTrees(owner.id).filter { tree ->
            tree.containsRaw(this@actionBarSearchBackgroundColors, "arrow_left_regular") &&
                tree.containsRaw(this@actionBarSearchBackgroundColors, "icons_outlined_search")
        }.forEach { it.collectSearchBackgroundColors(this@actionBarSearchBackgroundColors, this) }
    }
}

private fun MonetXmlElement.elements(): Sequence<MonetXmlElement> = sequence {
    yield(this@elements)
    children.forEach { yieldAll(it.elements()) }
}

/** The compact voice-input button is absent from the inspected 8.0.65 layouts. */
private fun MonetResourceGraph.voiceInputBackgroundColors(): Set<Int> = buildSet {
    nodes("layout").forEach { owner ->
        xmlTrees(owner.id).asSequence().flatMap { it.elements() }.filter { element ->
            element.name == "com.tencent.mm.ui.widget.RoundCornerLinearLayout" &&
                element.children.any { it.reference("src")?.let(this@voiceInputBackgroundColors::node)?.key ==
                    MonetResourceKey("raw", "mike_on_heavy") } &&
                element.children.any { it.name == "TextView" && it.literal("textSize") == 4353L &&
                    it.literal("textStyle") == 1L }
        }.forEach { button ->
            // 这一版布局里没有这个按钮 / 颜色对不上 → 只跳过这个元素。
            // 旧实现在这里 require 抛错，把「某个版本没有这块 UI」升级成「整次解析失败」。
            val color = button.reference("background")?.let(this@voiceInputBackgroundColors::node)
                ?: return@forEach
            if (color.key.type != "color" || color.defaultLiteral() != 0xfff2f2f2L) return@forEach
            add(color.id)
        }
    }
}

/** Old ECS feedback pages disappear along with their 6dp background in the newer layouts. */
private fun MonetResourceGraph.ecsFeedbackBackgroundColors(): Set<Int> = buildSet {
    val layouts = nodes("layout").flatMap { xmlTrees(it.id) }.filter { tree ->
        tree.elements().any { it.name == "com.tencent.mm.feature.ecs.product.view.EcsBarrageBoxView" }
    }
    layouts.asSequence().flatMap { it.elements() }.filter { element ->
        element.name == "TextView" && element.literal("layout_width") == 11265L &&
            element.literal("layout_height") == 6401L
    }.forEach { button ->
        val drawable = button.reference("background") ?: return@forEach
        val shape = xmlTrees(drawable).singleOrNull() ?: return@forEach
        if (shape.name != "shape" || shape.descendant("corners")?.literal("radius") != 1537L) return@forEach
        val color = shape.descendant("solid")?.reference("color")
            ?.let(this@ecsFeedbackBackgroundColors::node) ?: return@forEach
        if (color.key.type != "color" || color.defaultLiteral() != 0x26000000L) return@forEach
        add(color.id)
    }
    // 旧实现最后还有一句 `require(layouts.isEmpty() || isNotEmpty())`：只要这台机器上
    // 存在 ECS 页面但基线里找不到反馈底，整次解析就失败。现在没有就没有，让角色缺席即可。
}

/** This notice row and its dedicated colors are absent from the inspected 8.0.65/8.0.67 layouts. */
private fun MonetResourceGraph.noticeCardColors(): Pair<Set<Int>, Set<Int>> {
    val backgrounds = linkedSetOf<Int>()
    val foregrounds = linkedSetOf<Int>()
    nodes("layout").forEach { owner ->
        xmlTrees(owner.id).asSequence().flatMap { it.elements() }.filter { element ->
            element.name == "LinearLayout" && element.children.any {
                it.name == "ImageView" && it.reference("tint") != null && it.reference("src") != null
            } && element.children.any {
                it.reference("src")?.let(::node)?.key == MonetResourceKey("raw", "icons_filled_arrow")
            }
        }.forEach { row ->
            // 每一处探测失败都只跳过这一行（旧实现全是 `single()` / `requireNotNull`，
            // 任何一处对不上就把整次解析打断）。
            val text = row.children.singleOrNull {
                it.name == "TextView" && it.literal("maxLines") == 1L &&
                    it.literal("singleLine")?.let { value -> value != 0L } == true
            } ?: return@forEach
            val foreground = text.reference("textColor")?.let(::node) ?: return@forEach
            val shape = xmlTrees(row.reference("background") ?: return@forEach).singleOrNull()
                ?: return@forEach
            if (shape.name != "shape" || shape.descendant("corners")?.literal("radius") != 1025L) return@forEach
            val background = shape.descendant("solid")?.reference("color")?.let(::node) ?: return@forEach
            if (background.key.type != "color" || background.defaultLiteral() != 0xfff7f7f7L) return@forEach
            if (foreground.key.type != "color" || foreground.defaultLiteral() != 0x8c000000L) return@forEach
            backgrounds += background.id
            foregrounds += foreground.id
        }
    }
    return backgrounds to foregrounds
}

private fun MonetXmlElement.collectSearchBackgroundColors(graph: MonetResourceGraph, result: MutableSet<Int>) {
    if (containsRaw(graph, "icons_outlined_search")) reference("backgroundTint")?.let(result::add)
    children.forEach { it.collectSearchBackgroundColors(graph, result) }
}

private fun MonetXmlElement.containsRaw(graph: MonetResourceGraph, name: String): Boolean =
    reference("src")?.let(graph::node)?.key == MonetResourceKey("raw", name) ||
        children.any { it.containsRaw(graph, name) }

private fun MonetResourceGraph.threeStateSelectorDefaultStrokeColors(): Set<Int> = buildSet {
    nodes("drawable").forEach { owner ->
        xmlTrees(owner.id).filter { it.name == "selector" }.forEach { selector ->
            val items = selector.children.filter { it.name == "item" }
            val activated = items.getOrNull(1)?.literal("state_activated")
            if (items.size != 3 || items[0].literal("state_enabled") != 0L ||
                activated == null || activated == 0L || items[2].attributes.any { it.name.startsWith("state_") }
            ) return@forEach
            val strokes = items.map { it.descendant("stroke")?.reference("color") }
            val solids = items.map { it.descendant("solid")?.reference("color") }
            if (strokes[0] != null && strokes[0] == strokes[1] && strokes[2] != null &&
                strokes[2] != strokes[0] && solids.toSet().size == 1 && solids[0] != null
            ) add(strokes[2]!!)
        }
    }
}

private fun MonetResourceGraph.isVipBadgeColor(targetId: Int): Boolean = nodes("layout").any { owner ->
    xmlTrees(owner.id).any { it.hasVipBadgeColor(this, targetId) }
}

private fun MonetXmlElement.hasVipBadgeColor(graph: MonetResourceGraph, targetId: Int): Boolean {
    val hasVipIcon = children.any {
        it.reference("src")?.let(graph::node)?.key == MonetResourceKey("raw", "vip_filled_new")
    }
    val usesTarget = children.any { child ->
        child.attributes.any { it.name in setOf("iconColor", "textColor") &&
            (it.value as? MonetResourceValue.Reference)?.resourceId == targetId }
    }
    return hasVipIcon && usesTarget || children.any { it.hasVipBadgeColor(graph, targetId) }
}

private fun MonetXmlElement.descendant(name: String): MonetXmlElement? =
    children.firstOrNull { it.name == name } ?: children.firstNotNullOfOrNull { it.descendant(name) }

private fun MonetXmlElement.literal(name: String): Long? =
    (attributes.firstOrNull { it.name == name }?.value as? MonetResourceValue.Literal)?.data

private fun MonetXmlElement.collectSharedArrowIconTextColors(
    graph: MonetResourceGraph,
    result: MutableSet<Int>,
) {
    val icon = children.firstOrNull { child ->
        child.name.substringAfterLast('.') == "WeImageView" &&
            child.reference("src")?.let(graph::node)?.key == MonetResourceKey("raw", "arrow_double_regular")
    }
    val text = children.firstOrNull { child ->
        child.name.substringAfterLast('.') == "TextView" && child.reference("textSize") != null &&
            (child.attributes.firstOrNull { it.name == "textFontWeight" }?.value as? MonetResourceValue.Literal)?.data == 500L
    }
    val iconColor = icon?.reference("iconColor")
    if (iconColor != null && iconColor == text?.reference("textColor")) result += iconColor
    children.forEach { it.collectSharedArrowIconTextColors(graph, result) }
}

private fun MonetXmlElement.reference(name: String): Int? =
    (attributes.firstOrNull { it.name == name }?.value as? MonetResourceValue.Reference)?.resourceId

private fun MonetResourceValue.collectUsage(
    targetId: Int,
    path: String,
    graph: MonetResourceGraph,
    result: MutableSet<String>,
) {
    when (this) {
        is MonetResourceValue.Reference -> if (resourceId == targetId) result += "usage:$path:reference"
        is MonetResourceValue.Complex -> items.forEach { item ->
            item.value.collectUsage(targetId, "$path:item:${item.nameId}", graph, result)
        }
        else -> Unit
    }
}

private fun MonetXmlElement.collectEvidence(
    parent: String,
    graph: MonetResourceGraph,
    result: MutableSet<String>,
) {
    val path = if (parent.isEmpty()) name else "$parent/$name"
    result += "element:$path"
    attributes.forEach { attribute ->
        result += "attribute:$path:${attribute.nameId}:${attribute.name}:${attribute.valueType}:" +
            attribute.value.evidence(graph)
    }
    children.forEach { it.collectEvidence(path, graph, result) }
}

private fun MonetXmlElement.collectSimpleEvidence(
    parent: String,
    graph: MonetResourceGraph,
    result: MutableSet<String>,
) {
    val simpleName = name.substringAfterLast('.')
    val path = if (parent.isEmpty()) simpleName else "$parent/$simpleName"
    attributes.forEach { attribute ->
        result += "simple-attribute:$path:${attribute.nameId}:${attribute.name}:${attribute.valueType}:" +
            attribute.value.evidence(graph)
    }
    children.forEach { it.collectSimpleEvidence(path, graph, result) }
}

private fun MonetResourceValue.evidence(graph: MonetResourceGraph): String = when (this) {
    is MonetResourceValue.Reference -> "reference:${graph.node(resourceId)?.key?.type ?: "framework"}:$valueType"
    is MonetResourceValue.Literal -> "literal:$valueType:$data"
    is MonetResourceValue.Text -> "text:$value"
    is MonetResourceValue.File -> structure?.let {
        "file:${it.format}:${it.width}:${it.height}:${it.colorType}:${it.firstDataLength}:${it.ninePatchLength}:" +
            "${it.sampleSum}:${it.alphaSum}:${it.distinctSamples}:${it.pixelSha256}"
    } ?: "file"
    is MonetResourceValue.Complex -> "complex:" + items.joinToString(";") {
        "${it.nameId}=${it.value.evidence(graph)}"
    }
}
