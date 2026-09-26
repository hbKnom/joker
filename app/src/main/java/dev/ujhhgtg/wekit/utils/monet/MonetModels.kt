package dev.ujhhgtg.wekit.utils.monet

import kotlinx.serialization.Serializable

/**
 * Models shared by the Monet engine pipeline: WeChat resource bindings, the tinting plan and the
 * XML primitives used to author the replacement resources.
 *
 * Naming follows the upstream 09-25 runtime-injection rebuild — the RRO/module generator was
 * replaced by a runtime resource package that is fed straight into [android.content.res.loader.ResourcesLoader],
 * so the generation-only models live here next to the runtime ones and the serialisable binding
 * cache can be persisted across launches.
 */

/** One WeChat resource targeted by the engine: `id` is the resolved `0x7f…` resource id. */
@Serializable
data class MonetBinding(
    val id: Int,
    val name: String,
    val type: String,
    val qualifiers: List<String> = emptyList(),
    val specFlags: Int = 0,
)

/** How a binding should be tinted: full replacement, or an alpha overlay on a translucent token. */
@Serializable
data class MonetTint(
    val binding: MonetBinding,
    val lightAlpha: Int,
    val nightAlpha: Int? = null,
)

/**
 * The persistent result of analysing one WeChat build.
 *
 * [fingerprint] identifies the WeChat APK set (version + signature digest), so a cached binding set
 * is only reused while the analysed build is unchanged. [roles] maps a role name (see
 * `MonetSemanticRules`) to the resource id it resolved to; [unresolved] keeps the roles the matcher
 * could not anchor so the settings UI can report `resolved/total`.
 */
@Serializable
data class MonetBindings(
    val fingerprint: String,
    val typeNames: List<String> = emptyList(),
    val roles: Map<String, Int> = emptyMap(),
    val tints: List<MonetTint> = emptyList(),
    val unresolved: List<String> = emptyList(),
)

/**
 * 莫奈运行时包的「应用状态」：用于**自保**。
 *
 * 背景：运行时包一旦出错（覆盖到宿主的非同类资源，见 `MonetRuntimePackageWriter`），表现是
 * 微信在启动阶段直接闪退（页面切换动画取插值器就抛 `Resources$NotFoundException`），
 * 而每次重启又会重新应用同一个坏包 —— 用户看到的是「一直闪退、根本进不去」。
 * 实机 2026-09-25 就是这个循环。这里记住「上一个包 / 什么时候应用的 / 有没有活过观察窗口」，
 * 连续两次「应用后很快重启」就自动停用，等用户在设置页点「重新解析」（force）再放行。
 */
@Serializable
data class MonetRuntimeState(
    val packageName: String = "",
    val appliedAt: Long = 0L,
    val confirmedAt: Long = 0L,
    val failStreak: Int = 0,
    /**
     * 「本次全量解析开始」的时间戳，解析正常收尾（成功或抛错）时清零。
     *
     * 进程若在解析期间被杀/崩溃，这个标记会留到下一次启动 —— 这是唯一能识别
     * 「解析把微信搞崩了」的信号：崩溃发生在应用运行时包**之前**，旧的
     * [failStreak]（应用后没确认就重启）永远统计不到。实机 2026-09-26 因此陷入
     * 「启动→解析→原生崩溃→重启」死循环。
     */
    val resolveStartedAt: Long = 0L,
    /** 连续「解析没跑完就退出」的次数；达到上限后本次启动跳过解析（用户可手动重试）。 */
    val resolveFailStreak: Int = 0,
) {
    /** 应用后没等到确认就重启 = 疑似是被这个包搞崩的。 */
    fun suspiciousRestart(now: Long, restartWindowMs: Long, expectedPackage: String): Boolean =
        expectedPackage == packageName &&
            appliedAt > 0 &&
            confirmedAt < appliedAt &&
            (now - appliedAt) in 0..restartWindowMs
}

/** Stage of a WeChat resource analysis run, surfaced by the progress dialog. */
enum class MonetResolveStage(val label: String) {
    LOADING_APKS("loading APKs"),
    BUILDING_RESOURCE_GRAPH("building resource graph"),
    RESOLVING_ROLES("resolving roles"),
    BUILDING_PACKAGE("building resource package"),
}

/** Progress event emitted while analysing WeChat resources. */
data class MonetResolveProgress(
    val stage: MonetResolveStage,
    val detail: String,
    val completed: Int? = null,
    val total: Int? = null,
) {
    init {
        require(detail.isNotBlank())
        require((completed == null) == (total == null))
        if (completed != null && total != null) require(total > 0 && completed in 0..total)
    }
}

/** Outcome of one analysis run. */
sealed interface MonetResolveResult {
    data class Success(val bindings: MonetBindings, val runtimePackage: java.io.File?) : MonetResolveResult
    data class Failure(val progress: MonetResolveProgress, val message: String) : MonetResolveResult
}

/**
 * The 16-colour palette the overlays are built from. Values are ARGB ints; entries whose high byte is
 * `0x01` are resource references (`0x01rrggbb`) and are emitted as `@color/…` references rather than
 * literals, exactly like WeChat's own theme colours.
 */
data class Palette(
    val surfaceLight: Int,
    val surfaceDark: Int,
    val surfaceContainerLight: Int,
    val surfaceContainerDark: Int,
    val surfaceContainerHighLight: Int,
    val surfaceContainerHighDark: Int,
    val primaryLight: Int,
    val primaryDark: Int,
    val primaryContainerLight: Int,
    val primaryContainerDark: Int,
    val accent1_300: Int,
    val accent1_400: Int,
    val accent1_500: Int,
    val accent1_700: Int,
    val accent2_100: Int,
    val neutral2_700: Int,
)

/** Per-side padding applied to a generated `<shape>`. */
data class Padding(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Corner radii for a generated `<shape>`; use [circular] for a uniform radius. */
data class CornerRadius(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    companion object {
        fun circular(radius: Float) = CornerRadius(radius, radius, radius, radius)
    }
}

/** An authored binary-XML node to be written into the runtime resource package. */
data class XmlNode(
    val name: String,
    val attributes: List<XmlAttribute> = emptyList(),
    val children: List<XmlNode> = emptyList(),
)

/** An attribute of an authored [XmlNode]; [id] is the platform attribute resource id. */
data class XmlAttribute(val name: String, val id: Int, val value: XmlValue)

/** Typed attribute value understood by the resource writer. */
sealed interface XmlValue {
    data class Reference(val id: Int) : XmlValue
    data class NamedReference(val type: kotlin.String, val name: kotlin.String) : XmlValue
    data class Color(val argb: Int) : XmlValue
    data class Dimension(val dp: kotlin.Float) : XmlValue
    data class Integer(val value: Int) : XmlValue
    data class Boolean(val value: kotlin.Boolean) : XmlValue
    data class Float(val value: kotlin.Float) : XmlValue
    data class String(val value: kotlin.String) : XmlValue
}

/** A colour resource to be overridden in the runtime package. */
sealed interface ColorValue {
    data class Reference(val id: Int) : ColorValue
    data class Literal(val argb: Int) : ColorValue
}

data class ColorTarget(val binding: MonetBinding, val light: ColorValue?, val night: ColorValue?) {
    init { require(light != null || night != null) }
}

data class LiteralColorTarget(val binding: MonetBinding, val lightArgb: Int, val nightArgb: Int? = null)

data class StringTarget(val binding: MonetBinding, val value: String, val qualifiers: String = "")

/** A drawable resource to be replaced by an authored XML document, optionally per-qualifier. */
data class DrawableTarget(
    val binding: MonetBinding,
    val light: XmlNode,
    val night: XmlNode? = null,
    val lightQualifiers: String = "",
    val nightQualifiers: String = "-night",
)

/** Builds the [MonetBinding] for a resolved role. */
fun MonetResourceNode.binding(): MonetBinding =
    MonetBinding(id = id, name = key.name, type = key.type)

/** Everything the engine wants to override in one WeChat build. */
data class MonetOverlayPlan(
    val drawables: List<DrawableTarget> = emptyList(),
    val colors: List<ColorTarget> = emptyList(),
    val literalColors: List<LiteralColorTarget> = emptyList(),
    val strings: List<StringTarget> = emptyList(),
) {
    val isEmpty: Boolean
        get() = drawables.isEmpty() && colors.isEmpty() && literalColors.isEmpty() && strings.isEmpty()

    operator fun plus(other: MonetOverlayPlan) = MonetOverlayPlan(
        drawables + other.drawables,
        colors + other.colors,
        literalColors + other.literalColors,
        strings + other.strings,
    )
}

/** Bubble geometry preset authored by [MonetAssetInjector]. */
enum class MonetBubbleStyle { MODERN, CLASSIC, PRO }

/**
 * 宿主资源表里每个**类型名**对应的 typeId 与最大 entryId。
 *
 * 只有[MonetBinding.id]（宿主真实 id）还不够：WeKit 自己合成的资源（自适应图标的
 * `wekit_icon_bg/fg/mono`，宿主里根本没有对应条目）拿不到宿主 id，但如果随便分一个
 * entryId，就可能正好压在宿主同类型的某个条目上 —— 那等于把别人的资源盖掉。
 * 所以合成资源一律借「宿主同类型最大 entryId 之后」的槽位：那个 id 一定是空的。
 */
data class MonetHostTypeSlots(
    private val typeIds: Map<String, Int>,
    private val highestEntryIds: Map<String, Int>,
) {
    fun typeId(type: String, fallback: Int = 0): Int = typeIds[type] ?: fallback

    fun highestEntryId(type: String): Int = highestEntryIds[type] ?: 0

    /** 借一个宿主同类型里不存在的 id；[sequence] 区分同一类型的多个合成资源。 */
    fun syntheticId(type: String, sequence: Int, fallbackTypeId: Int = 0): Int =
        MonetRuntimePackageWriter.syntheticId(
            typeId = typeId(type, fallbackTypeId),
            hostHighestEntryId = highestEntryId(type),
            sequence = sequence,
        )

    companion object {
        val EMPTY = MonetHostTypeSlots(emptyMap(), emptyMap())

        fun of(nodes: Collection<MonetResourceNode>): MonetHostTypeSlots {
            val grouped = nodes.groupBy { it.key.type }
            val typeIds = linkedMapOf<String, Int>()
            val highest = linkedMapOf<String, Int>()
            grouped.forEach { (type, list) ->
                val ids = list.map { (it.id ushr 16) and 0xff }
                // 多 APK 合并时同一类型名可能出现多个 typeId：以出现最多的为准。
                // 真正写包时会按 typeId 定位，撞车会被 [MonetRuntimePackageWriter] 的
                // 冲突校验拦下，所以这里只需给出一个稳定答案。
                typeIds[type] = ids.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                highest[type] = list.maxOf { it.id and 0xffff }
            }
            return MonetHostTypeSlots(typeIds, highest)
        }
    }
}


/**
 * DEX-derived evidence models. Upstream 09-25 deleted the DexKit collector that produced these
 * (`MonetDexEvidenceCollector`), so the matcher now runs on the resource graph alone; the shape is
 * kept because [MonetStructureMatcher] still accepts an optional provider and because
 * `MonetSemanticRule.requiredDexEvidence` is preserved in the rule table.
 */
fun interface MonetDexEvidenceProvider {
    fun query(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence>
}

data class MonetDexCandidate(val resourceId: Int, val type: String, val name: String)

data class MonetResourceDexEvidence(
    val resourceId: Int,
    val methods: List<MonetMethodDexEvidence>,
)

data class MonetMethodDexEvidence(
    val descriptor: String,
    val ownerPackage: String,
    val methodShape: String,
    val stableStrings: List<String>,
    val invokedMethodShapes: List<String>,
    val neighboringResourceIds: List<Int>,
    val fieldAccesses: List<MonetFieldAccessEvidence>,
)

data class MonetFieldAccessEvidence(val descriptor: String, val access: MonetFieldAccess)

enum class MonetFieldAccess { READ, WRITE }

// ---------------------------------------------------------------------------
// Resource-graph models (moved out of `MonetResourceGraph.kt` by the 09-25
// rebuild so every shared shape lives in one place).
// ---------------------------------------------------------------------------


data class MonetResourceKey(val type: String, val name: String) : java.io.Serializable

sealed interface MonetResourceValue : java.io.Serializable {
    data class Literal(val valueType: String, val data: Long) : MonetResourceValue
    data class Reference(val resourceId: Int, val valueType: String = "REFERENCE") : MonetResourceValue
    data class File(val path: String, val structure: MonetFileStructure?) : MonetResourceValue
    data class Text(val value: String) : MonetResourceValue
    data class Complex(val parentId: Int, val items: List<MonetComplexValue>) : MonetResourceValue
}

data class MonetFileStructure(
    val format: String,
    val width: Int? = null,
    val height: Int? = null,
    val colorType: Int? = null,
    val firstDataLength: Int? = null,
    val ninePatchLength: Int? = null,
    val sampleSum: Long? = null,
    val alphaSum: Long? = null,
    val distinctSamples: Int? = null,
    val pixelSha256: String? = null,
) : java.io.Serializable

data class MonetComplexValue(val nameId: Int, val value: MonetResourceValue) : java.io.Serializable
data class MonetConfiguredValue(val qualifiers: String, val value: MonetResourceValue) : java.io.Serializable
data class MonetResourceNode(
    val id: Int,
    val key: MonetResourceKey,
    val values: List<MonetConfiguredValue>,
) : java.io.Serializable

data class MonetXmlElement(
    val name: String,
    val namespace: String? = null,
    val attributes: List<MonetXmlAttribute>,
    val children: List<MonetXmlElement>,
) : java.io.Serializable

data class MonetXmlAttribute(
    val namespace: String?,
    val name: String,
    val nameId: Int?,
    val valueType: String,
    val value: MonetResourceValue,
) : java.io.Serializable
