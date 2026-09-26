package dev.joker.features.items.debug

import android.content.Context
import androidx.annotation.StringRes
import dev.joker.i18n.LocaleResourceMode
import dev.joker.i18n.LocalizedContextFactory
import dev.joker.i18n.JokerLocaleController
import dev.joker.utils.HostInfo

fun localizedDebugString(@StringRes id: Int, vararg args: Any): String =
    HostInfo.application.debugLocalizedContext().getString(id, *args)

fun Context.localizedDebugString(@StringRes id: Int, vararg args: Any): String =
    debugLocalizedContext().getString(id, *args)

private fun Context.debugLocalizedContext(): Context = LocalizedContextFactory.create(
    this,
    JokerLocaleController.resolvedLocale,
    LocaleResourceMode.InjectedHost,
)
