package dev.ujhhgtg.wekit.ui.utils.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.ujhhgtg.wekit.utils.monet.MonetColors
import dev.ujhhgtg.wekit.utils.monet.Palette

/**
 * 平台没有 Material You token（安卓 11，以及部分没做动态取色的 ROM）时，
 * 用 WeKit 主题种子合成一套与引擎同形的色板。
 *
 * 这样「莫奈引擎」在安卓 11 上也能取色（旧实现在这里直接抛错 → 整次解析失败），
 * 而且颜色跟随 WeKit 设置里配置的种子，和注入界面保持一致。
 */
object MonetPaletteFactory {

    fun fromTheme(context: Context): Palette {
        val seed = SeedResolver.customSeed(context, dark = false)
        val light = SeedResolver.materialScheme(seed, dark = false)
        val dark = SeedResolver.materialScheme(seed, dark = true)
        val primary = light.primary.toArgb()
        return Palette(
            surfaceLight = light.surface.toArgb(),
            surfaceDark = dark.surface.toArgb(),
            surfaceContainerLight = light.surfaceContainer.toArgb(),
            surfaceContainerDark = dark.surfaceContainer.toArgb(),
            surfaceContainerHighLight = light.surfaceContainerHigh.toArgb(),
            surfaceContainerHighDark = dark.surfaceContainerHigh.toArgb(),
            primaryLight = primary,
            primaryDark = dark.primary.toArgb(),
            primaryContainerLight = light.primaryContainer.toArgb(),
            primaryContainerDark = dark.primaryContainer.toArgb(),
            accent1_500 = primary,
            accent1_400 = MonetColors.blend(primary, light.surface.toArgb(), 0.18),
            accent1_300 = MonetColors.blend(primary, light.surface.toArgb(), 0.36),
            accent1_700 = MonetColors.blend(primary, Color.Black.toArgb(), 0.28),
            accent2_100 = light.tertiaryContainer.toArgb(),
            neutral2_700 = light.outline.toArgb(),
        )
    }
}
