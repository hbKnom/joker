package dev.joker.features.items.voip

import androidx.annotation.StringRes
import dev.joker.i18n.LocaleResourceMode
import dev.joker.i18n.LocalizedContextFactory
import dev.joker.i18n.JokerLocaleController
import dev.joker.utils.HostInfo

fun localizedVoipString(@StringRes id: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        JokerLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(id, *formatArgs)
