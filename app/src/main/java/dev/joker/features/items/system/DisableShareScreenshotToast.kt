package dev.joker.features.items.system

import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

object DisableShareScreenshotToast : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用「转发截图」提示"
    override val nameRes = R.string.feature_disable_share_screenshot_toast_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_disable_share_screenshot_toast_description

    private val methodDisplayToast by dexMethod {
        searchPackages("com.tencent.mm.ui.feature.api.screenshot")
        matcher {
            usingEqStrings("MicroMsg.ScreenShotShareService", "showShareTongue, shareTongue already showing, reset onClick & countDown")
        }
    }

    override fun onEnable() {
        methodDisplayToast.hookBefore {
            result = null
        }
    }
}
