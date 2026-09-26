package dev.joker.features.items.contacts

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.joker.R
import dev.joker.features.api.core.WeApi
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.Button
import dev.joker.ui.content.TextButton
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.android.showToast

object OpenConversation : ClickableFeature() {

    override val technicalId = "跳转对话"
    override val nameRes = R.string.feature_open_conversation_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_open_conversation_description

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showOpenConversationDialog(context)
    }
}

fun showOpenConversationDialog(context: Context) {
    showComposeDialog(context) {
        var wxId by remember { mutableStateOf("") }
        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_open_conversation_name)) },
            text = {
                TextField(
                    value = wxId,
                    onValueChange = { wxId = it },
                    label = { Text(stringResource(R.string.contacts_wechat_id)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (wxId.isBlank()) {
                        showToast(context, context.localizedContactsString(R.string.contacts_wechat_id_empty))
                        return@TextButton
                    }
                    WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
                }) { Text(stringResource(R.string.contacts_open_homepage)) }

                TextButton(onClick = {
                    if (wxId.isBlank()) {
                        showToast(context, context.localizedContactsString(R.string.contacts_wechat_id_empty))
                        return@TextButton
                    }
                    WeApi.openContact(context, wxId, WeApi.OpenContactDestination.SETTINGS)
                }) { Text(stringResource(R.string.contacts_open_settings)) }

                Button(onClick = {
                    if (wxId.isBlank()) {
                        showToast(context, context.localizedContactsString(R.string.contacts_wechat_id_empty))
                        return@Button
                    }
                    WeApi.openContact(context, wxId, WeApi.OpenContactDestination.CONVERSATION)
                }) { Text(stringResource(R.string.contacts_open_chat)) }
            })
    }
}
