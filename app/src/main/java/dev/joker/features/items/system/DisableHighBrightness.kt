package dev.joker.features.items.system

import android.view.WindowManager
import com.android.internal.policy.PhoneWindow
import dev.joker.reflekt.reflekt
import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

object DisableHighBrightness : SwitchFeature() {

    override val technicalId = "禁止屏幕高亮度"
    override val nameRes = R.string.feature_disable_high_brightness_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_disable_high_brightness_description

    override fun onEnable() {
        PhoneWindow::class.reflekt()
            .firstMethod {
                name = "setAttributes"
                parameters(WindowManager.LayoutParams::class)
            }
            .hookBefore {
                val lp = args[0] as WindowManager.LayoutParams
                if (lp.screenBrightness >= 0.5f) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
    }
}
