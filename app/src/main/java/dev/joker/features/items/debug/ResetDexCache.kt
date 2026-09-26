package dev.joker.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.joker.R
import dev.joker.dexkit.cache.DexCacheManager
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.Button
import dev.joker.ui.content.TextButton
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ResetDexCache : ClickableFeature() {

    override val technicalId = "重置适配信息"
    override val nameRes = R.string.feature_reset_dex_cache_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_reset_dex_cache_description

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.debug_reset_dex_cache_title)) },
                text = {
                    Text(stringResource(R.string.debug_reset_dex_cache_confirmation))
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            showToastSuspend(localizedDebugString(R.string.debug_reset_dex_cache_clearing))
                            DexCacheManager.clearAllCache()
                            showToastSuspend(localizedDebugString(R.string.debug_reset_dex_cache_success))
                            withContext(Dispatchers.Main) {
                                onDismiss()
                            }
                        }
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    override val noSwitchWidget = true
}
