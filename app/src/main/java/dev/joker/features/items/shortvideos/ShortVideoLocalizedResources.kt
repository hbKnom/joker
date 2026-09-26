package dev.joker.features.items.shortvideos

import android.content.Context
import androidx.annotation.StringRes
import dev.joker.i18n.LocaleResourceMode
import dev.joker.i18n.LocalizedContextFactory
import dev.joker.i18n.JokerLocaleController
import dev.joker.utils.HostInfo

fun localizedShortVideoString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.shortVideoLocalizedContext().getString(id, *formatArgs)

private fun Context.shortVideoLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        JokerLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
