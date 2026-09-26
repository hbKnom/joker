package dev.joker.features.items.system

import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

object NerfBackgroundProcessChecker : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用微信进程状态检测器"
    override val nameRes = R.string.feature_nerf_background_process_checker_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_nerf_background_process_checker_description

    private val methodPerformProcessCheck by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.AbstractProcessChecker", "pass this check,because request is null! ????")
        }
    }

    override fun onEnable() {
        methodPerformProcessCheck.hookBefore {
            result = null
        }
    }
}
