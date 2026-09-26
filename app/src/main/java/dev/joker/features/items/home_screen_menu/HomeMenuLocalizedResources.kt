package dev.joker.features.items.home_screen_menu

import androidx.annotation.StringRes
import dev.joker.i18n.LocaleResourceMode
import dev.joker.i18n.LocalizedContextFactory
import dev.joker.i18n.JokerLocaleController
import dev.joker.utils.HostInfo

fun localizedHomeMenuString(@StringRes id: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        JokerLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(id, *formatArgs)
