package dev.ujhhgtg.wekit.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.utils.HostInfo

/**
 * Theme for WeKit UI injected INTO WeChat.
 *
 * Colors come from [SeedResolver.injectedScheme]: WeChat green by default, the selected seed when
 * opted into WeChat ([ThemeSettings.applyToWechat]), or — when the Monet engine is on — the same
 * source WeChat's own UI is recoloured from. The Monet palette is Compose state, so a freshly
 * resolved palette re-themes injected UI on the spot instead of waiting for a WeChat restart.
 *
 * NEVER CALL THIS INSIDE MODULE APP.
 */
@Composable
fun InjectedUiTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
        val dark = darkTheme ?: isSystemInDarkTheme()
        val materialScheme = SeedResolver.injectedScheme(HostInfo.application, dark)

        MaterialExpressiveTheme(
            colorScheme = materialScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }
}
