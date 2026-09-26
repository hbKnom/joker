package dev.joker.features.items.chat

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.joker.i18n.LocaleResourceMode
import dev.joker.i18n.LocalizedContextFactory
import dev.joker.i18n.JokerLocaleController
import dev.joker.utils.HostInfo

fun localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedChatString(id, *formatArgs)

fun Context.localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    chatLocalizedContext().getString(id, *formatArgs)

fun localizedChatQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.localizedChatQuantity(id, quantity, *formatArgs)

fun Context.localizedChatQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = chatLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.chatLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        JokerLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
