package dev.ujhhgtg.wekit.utils.monet

import androidx.compose.runtime.mutableStateOf

/**
 * 「WeKit 注入微信界面的组件」取色的统一入口。
 *
 * 背景：莫奈引擎靠替换**微信自身**的资源（`R.color` / `R.drawable`）给微信原生界面取色，
 * 而 WeKit 自己塞进微信界面里的视图 / Composable 用的是 WeKit 自己的资源 id，不在替换范围内 ——
 * 于是出现「微信原生已经莫奈化、WeKit 组件还是旧配色」的割裂（用户实机反馈的问题）。
 * 这些组件改为从这里取色，就能和原生部分保持一致。
 *
 * [applied] 是 Compose 状态：引擎解析完成且应用成功后写入，关闭开关时清空。
 * 读不到（莫奈未启用 / 未解析成功）时，组件必须保持自己原来的配色，不要退化成随机色。
 */
object MonetColors {

    /** 引擎当前已应用的色板；`null` = 莫奈未启用或尚未解析成功。 */
    val applied = mutableStateOf<Palette?>(null)

    val isActive: Boolean get() = applied.value != null

    /** 当前配置（浅色 / 深色）下的一组具体色值。 */
    fun tokens(isNight: Boolean): Tokens? = applied.value?.let { Tokens.of(it, isNight) }

    /**
     * 当前配置下的一组具体色值，角色名与 Material 3 对齐，便于直接映射到 ColorScheme
     * 或者喂给自绘 View。
     */
    data class Tokens(
        val night: Boolean,
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val surface: Int,
        val surfaceContainer: Int,
        val surfaceContainerHigh: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val outline: Int,
        /** 强调色（引擎品牌色回填用的那一个）。 */
        val accent: Int,
        /** 强调色的浅色调 / 深色调，用于渐变、描边、进度条底。 */
        val accentDim: Int,
        val accentBright: Int,
    ) {
        companion object {
            fun of(palette: Palette, night: Boolean): Tokens {
                val primary = if (night) palette.primaryDark else palette.primaryLight
                val primaryContainer =
                    if (night) palette.primaryContainerDark else palette.primaryContainerLight
                val surface = if (night) palette.surfaceDark else palette.surfaceLight
                val surfaceContainer =
                    if (night) palette.surfaceContainerDark else palette.surfaceContainerLight
                val surfaceContainerHigh =
                    if (night) palette.surfaceContainerHighDark else palette.surfaceContainerHighLight
                val onSurface = onColor(surface)
                return Tokens(
                    night = night,
                    primary = primary,
                    onPrimary = onColor(primary),
                    primaryContainer = primaryContainer,
                    onPrimaryContainer = onColor(primaryContainer),
                    surface = surface,
                    surfaceContainer = surfaceContainer,
                    surfaceContainerHigh = surfaceContainerHigh,
                    onSurface = onSurface,
                    // 次级文字：介于正文色与容器色之间，保证深色/浅色下都够对比
                    onSurfaceVariant = blend(onSurface, surface, if (night) 0.45 else 0.38),
                    outline = blend(surface, onSurface, if (night) 0.35 else 0.28),
                    accent = palette.accent1_500,
                    accentDim = palette.accent1_300,
                    accentBright = palette.accent1_700,
                )
            }
        }
    }

    /** 按背景亮度选前景色（浅背景用深字，深背景用浅字）。 */
    fun onColor(background: Int, dark: Int = DARK_ON, light: Int = LIGHT_ON): Int =
        if (luminance(background) > 0.5) dark else light

    /** 把 [overlay] 按 [ratio] 混到 [base] 上，保留 [base] 的 alpha。 */
    fun blend(base: Int, overlay: Int, ratio: Double): Int {
        val weight = ratio.coerceIn(0.0, 1.0)
        fun channel(shift: Int): Int {
            val from = (base shr shift) and 0xFF
            val to = (overlay shr shift) and 0xFF
            return (from + (to - from) * weight).toInt().coerceIn(0, 255)
        }
        return (base and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** 把 [color] 的 alpha 换成 0..255 的 [alpha]。 */
    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private const val DARK_ON = 0xFF1C1B1F.toInt()
    private const val LIGHT_ON = 0xFFFFFFFF.toInt()

    /** WCAG 相对亮度，用于决定前景色。 */
    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color shr 16 and 0xFF) +
            0.7152 * channel(color shr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }
}
