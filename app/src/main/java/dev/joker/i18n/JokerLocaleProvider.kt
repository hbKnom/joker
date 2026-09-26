package dev.joker.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

/**
 * A context for accessing Joker resources only.
 *
 * Do not use it for an Activity, windows, Activity Result, SAF, system services, or third-party
 * UI construction.
 */
val LocalJokerLocalizedContext = staticCompositionLocalOf<Context> {
    error("LocalJokerLocalizedContext was not provided")
}

@Composable
fun JokerLocaleProvider(
    mode: LocaleResourceMode,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val locale = JokerLocaleController.resolvedLocale
    val localizedContext = remember(baseContext, parentConfiguration, locale, mode) {
        LocalizedContextFactory.create(baseContext, locale, mode)
    }
    val localizedConfiguration = remember(localizedContext, locale) {
        Configuration(localizedContext.resources.configuration)
    }

    CompositionLocalProvider(
        LocalResources provides localizedContext.resources,
        LocalConfiguration provides localizedConfiguration,
        LocalJokerLocalizedContext provides localizedContext,
        content = content,
    )
}
