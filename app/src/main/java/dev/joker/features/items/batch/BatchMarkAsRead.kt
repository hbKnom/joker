package dev.joker.features.items.batch

import androidx.activity.ComponentActivity
import dev.joker.R
import dev.joker.features.api.core.WeConversationApi
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.ui.content.ContactsSelector
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.WeLogger
import dev.joker.utils.android.showToast
import dev.joker.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object BatchMarkAsRead : ClickableFeature() {

    override val technicalId = "批量标为已读"
    override val nameRes = R.string.feature_batch_mark_as_read_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_mark_as_read_description

    private const val TAG = "BatchMarkAsRead"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = context.localizedBatchString(R.string.batch_mark_read_select),
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast(context.localizedBatchString(R.string.batch_select_at_least_one_conversation))
                        return@ContactsSelector
                    }

                    onDismiss()
                    markAsRead(selectedWxIds)
                }
            )
        }
    }

    private fun markAsRead(wxIds: Set<String>) {
        // These are local DB writes (no server CGI), so no rate-limit pacing is needed.
        CoroutineScope(Dispatchers.IO).launch {
            wxIds.forEach { wxId ->
                runCatching { WeConversationApi.markAsRead(wxId) }
                    .onFailure { WeLogger.e(TAG, "failed to mark $wxId as read", it) }
            }
            WeConversationApi.reloadConversations()
            showToastSuspend(
                localizedBatchQuantity(R.plurals.batch_mark_read_done, wxIds.size, wxIds.size),
            )
        }
    }
}
