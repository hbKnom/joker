package dev.joker.features.items.system

import android.os.PowerManager
import dev.joker.reflekt.reflekt
import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

object PowerSaver : SwitchFeature() {

    override val technicalId = "省电模式"
    override val nameRes = R.string.feature_power_saver_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_power_saver_description

    override fun onEnable() {
        PowerManager.WakeLock::class.reflekt().apply {
            methods {
                name = "acquire"
            }.forEach {
                it.hookBefore { result = null }
            }

            firstMethod {
                name = "release"
                parameterCount = 1
            }.hookBefore { result = null }
        }
    }
}
