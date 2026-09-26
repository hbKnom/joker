package dev.joker.features.items.scripting_java

import androidx.annotation.StringRes
import dev.joker.i18n.LocaleResourceMode
import dev.joker.i18n.LocalizedContextFactory
import dev.joker.i18n.JokerLocaleController
import dev.joker.utils.HostInfo

fun localizedScriptingJavaString(@StringRes id: Int, vararg args: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        JokerLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(id, *args)
