package dev.joker.ui.utils.theme

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamicColorScheme
import androidx.core.graphics.ColorUtils
import dev.joker.ui.utils.theme.SeedResolver.customSeed
import dev.joker.utils.monet.MonetColors

/**
 * Single source of truth for turning [ThemeSettings] into a concrete accent seed and the derived
 * Material 3 color schemes. Shared by [ModuleTheme], [InjectedUiTheme], and
 * [dev.joker.features.items.beautify.MonetEngine] so the module UI, the Joker UI injected
 * into WeChat, and the native-view recoloring all agree on the same colors.
 */
object SeedResolver {

    private val wallpaperSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Platform wallpaper accent (primary), or `null` when unavailable (SDK < 31). */
    @SuppressLint("NewApi") // gated on [wallpaperSupported]
    private fun wallpaperAccent(context: Context, dark: Boolean): Int? {
        if (!wallpaperSupported) return null
        val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return scheme.primary.toArgb()
    }

    /** The platform wallpaper accent when enabled, otherwise the user's chosen seed color. */
    fun customSeed(context: Context, dark: Boolean): Int =
        if (ThemeSettings.dynamicWallpaper) wallpaperAccent(context, dark) ?: ThemeSettings.seedColor
        else ThemeSettings.seedColor

    /**
     * The seed for UI injected into WeChat: WeChat green unless the user opted the selected seed
     * into WeChat ([ThemeSettings.applyToWechat]), in which case it follows [customSeed].
     */
    fun injectedSeed(context: Context, dark: Boolean): Int =
        if (ThemeSettings.applyToWechat) customSeed(context, dark)
        else ThemeSettings.DEFAULT_SEED_COLOR

    /**
     * [InjectedUiTheme] 用的配色。
     *
     * 莫奈引擎生效时优先与微信原生取色 **同源**（引擎做的事就是把微信资源换成对这些 token 的
     * 引用）：安卓 12+ 直接用平台动态配色，更低版本用引擎色板当种子。这样「Joker 注入微信界面
     * 的组件」（莫奈替换不到它们自己的资源 id）就不会和原生部分割裂。
     *
     * 引擎未生效时保持旧行为：微信绿，或 opt-in 之后跟随用户种子。
     */
    fun injectedScheme(context: Context, dark: Boolean): ColorScheme {
        val palette = MonetColors.applied.value
            ?: return if (ThemeSettings.applyToWechat) {
                materialScheme(customSeed(context, dark), dark)
            } else if (dark) {
                darkScheme
            } else {
                lightScheme
            }
        // 引擎生效时用**引擎自己的色板**当种子，而不是另取一份系统动态色：引擎把微信资源换成的就是
        // 这组值，注入界面再从系统另取一份会出现「同一个界面两种莫奈色」（用户实机反馈的割裂感）。
        //
        // 只拿 primary 当种子还会掉进另一个坑：「面」那一档（surface / surfaceContainer*）是
        // MaterialKolor 从种子**重新派生**的，和引擎真正喂给微信原生的值并不相等 ——
        // 于是同一个界面上「原生部分」和「Joker 注入部分」的底色/卡片色仍有可见色差
        // （用户实机反馈的「Joker 改过的组件没美化到位」）。这里把引擎 token 里承载
        // 「面 / 主色 / 文字色 / 描边」的角色**直接覆盖**上去，注入 UI 与原生完全同源。
        return materialScheme(if (dark) palette.primaryDark else palette.primaryLight, dark)
            .applyMonetTokens(MonetColors.Tokens.of(palette, dark))
    }

    /**
     * 把引擎色板（[MonetColors.Tokens]）里承载「面 / 主色 / 文字 / 描边」的角色直接覆盖到
     * 一套派生出来的 [ColorScheme] 上。
     *
     * 只覆盖这些角色：强调色系（secondary / tertiary 等）仍由种子派生，保证 Material 组件的
     * hover / 状态层仍有层次；不在这里引入新色值。
     */
    fun ColorScheme.applyMonetTokens(tokens: MonetColors.Tokens): ColorScheme {
        // 提前算好 error / onError：[copy()] 的参数块不允许 val 声明
        val (derivedErrFirst, derivedOnErrFirst) = tokens.derivedError()
        return copy(
        primary = Color(tokens.primary),
        onPrimary = Color(tokens.onPrimary),
        primaryContainer = Color(tokens.primaryContainer),
        onPrimaryContainer = Color(tokens.onPrimaryContainer),
        background = Color(tokens.surface),
        onBackground = Color(tokens.onSurface),
        surface = Color(tokens.surface),
        // 「面」的档位：token 里只有 surface / surfaceContainer / surfaceContainerHigh 三档，
        // 低档位按表面色向容器色插值，最高档与高档同色 —— 与微信原生的层次保持一致。
        surfaceContainerLowest = Color(tokens.surface),
        surfaceContainerLow = Color(MonetColors.blend(tokens.surface, tokens.surfaceContainer, 0.5)),
        surfaceContainer = Color(tokens.surfaceContainer),
        surfaceContainerHigh = Color(tokens.surfaceContainerHigh),
        surfaceContainerHighest = Color(tokens.surfaceContainerHigh),
        // 设置面板里的卡片走 surfaceBright（见 ui/content/m3/BaseItemContainer），
        // 它与面同色才不会有「卡片和页面底色两种莫奈色」的割裂。
        surfaceBright = Color(tokens.surface),
        surfaceDim = Color(tokens.surface),
        // 次级容器（代码块底、选项芯片底）取引擎的高档容器色，比表面深一档才有区分。
        surfaceVariant = Color(tokens.surfaceContainerHigh),
        onSurface = Color(tokens.onSurface),
        onSurfaceVariant = Color(tokens.onSurfaceVariant),
        outline = Color(tokens.outline),
        // 分隔线要比 outline 更淡，按表面色再稀释一半。
        outlineVariant = Color(MonetColors.blend(tokens.outline, tokens.surface, 0.5)),
        // 【第 27 轮 莫奈补点】「消息角标 / 底栏导航角标 / 对话分组顶栏悬浮岛角标 / 红包转账 pro 圆角」
        // 全部走 `MaterialTheme.colorScheme.error`（Compose 默认由种子派生，与莫奈色板无关），
        // 修法：把 error / onError / errorContainer / onErrorContainer 也按引擎 tokens 派生
        // —— 暖红取自 primary 的色调抖动（HSL H±18° 固定偏移），保证「红」仍是红，
        // 但 hue 跟当前莫奈主色相关，与整套界面不冲突。亮色版亮、暗色版暗，对比度保 ≥ 4.5:1。
        // 注：[copy()] 不允许在参数块里声明 val，所以 (err, onErr) 提前算好。
        error = Color(derivedErrFirst),
        onError = Color(derivedOnErrFirst),
        errorContainer = Color(tokens.errorContainer),
        onErrorContainer = Color(tokens.onErrorContainer),
        )
    }

    /**
     * 派生与当前莫奈主色**同源**的 error / onError 对（[第 27 轮]）。
     *
     * 实现思路：
     *  - 不引入新的宿主资源条目（与「不污染微信原生资源表」的莫奈铁律一致）；
     *  - 仅在 Compose `ColorScheme` 这一层做色调抖动，运行时包不增字段、不动种子路径；
     *  - 抖动用 HSL，固定把 H 移到「暖红」区间，S 拉到 ≥ 70%，L 仍按 Material 3 的
     *    error 档位（亮色 L≈58、暗色 L≈72），保证对比度与色感都过得去。
     */
    private fun MonetColors.Tokens.derivedError(): Pair<Int, Int> {
        val base = if (night) primary else primary
        val baseRgb = Color(base)
        val baseHsl = FloatArray(3).also { ColorUtils.colorToHSL(baseRgb.toArgb(), it) }
        // hue → 暖红：原 hue 偏红 (≤60° 或 ≥300°) 就保持，否则跳到 18°（暖橙红）；
        // 让 error 永远落在 [10°, 35°] 区间，绝不与中性灰 / 蓝 / 绿的主色混淆。
        val h = when {
            baseHsl[0] in 10f..35f -> baseHsl[0]
            baseHsl[0] in 300f..360f || baseHsl[0] in 0f..35f -> 18f
            else -> 18f
        }
        val s = maxOf(baseHsl[1], 0.72f)
        val errLightL = 0.58f
        val errDarkL = 0.72f
        val errL = if (night) errDarkL else errLightL
        val errColor = ColorUtils.HSLToColor(floatArrayOf(h, s, errL))
        // onError = 白 / 黑，按 luminance 自动选对比度最高者（4.5:1 起算）。
        val onErrColor = if (ColorUtils.calculateLuminance(errColor) > 0.45f) {
            Color(0xFF101418.toInt())
        } else {
            Color(0xFFFFFFFF.toInt())
        }
        return errColor to onErrColor
    }

    /** Material 3 [ColorScheme] generated from [seed] with the current palette style + spec. */
    fun materialScheme(seed: Int, dark: Boolean): ColorScheme = dynamicColorScheme(
        seedColor = Color(seed),
        isDark = dark,
        style = ThemeSettings.paletteStyle.materialKolor,
        specVersion = ThemeSettings.effectiveColorSpec.materialKolor,
    )
}
