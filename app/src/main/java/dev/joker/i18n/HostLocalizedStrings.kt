package dev.joker.i18n

import androidx.annotation.StringRes
import dev.joker.utils.HostInfo

/**
 * Localized module strings for code that runs in the injected host process
 * outside Compose composition (loader entry points, hook bridges).
 */
object HostLocalizedStrings {
    @JvmStatic
    fun get(@StringRes id: Int, vararg formatArgs: Any): String =
        LocalizedContextFactory.create(
            HostInfo.application,
            JokerLocaleController.resolvedLocale,
            LocaleResourceMode.InjectedHost,
        ).getString(id, *formatArgs)
}
