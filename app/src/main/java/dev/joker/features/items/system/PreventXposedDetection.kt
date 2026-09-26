package dev.joker.features.items.system

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.i18n.HostLocalizedStrings
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.TextButton
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.HostInfo
import dev.joker.utils.android.showToast

object PreventXposedDetection : SwitchFeature(), IResolveDex {

    override val technicalId = "禁止微信检测 Xposed"
    override val nameRes = R.string.feature_prevent_xposed_detection_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_prevent_xposed_detection_description

    private val methodCheckStackTraceElements by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.app")
        matcher {
            usingEqStrings(
                "de.robv.android.xposed.XposedBridge",
                "com.zte.heartyservice.SCC.FrameworkBridge"
            )
        }
    }

    override fun onEnable() {
        if (HostInfo.isHostGooglePlay) {
            showToast(HostLocalizedStrings.get(R.string.system_prevent_xposed_google_play_warning))
            applyToggle(false)
            return
        }

        if (methodCheckStackTraceElements.isPlaceholder) return

        methodCheckStackTraceElements.hookBefore {
            result = false
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && HostInfo.isHostGooglePlay) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(stringResource(R.string.feature_prevent_xposed_detection_name)) },
                    text = {
                        Text(stringResource(R.string.system_prevent_xposed_google_play_warning))
                    },
                    confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } })
            }
            return false
        }

        return true
    }
}
