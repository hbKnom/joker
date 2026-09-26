package dev.joker.features.api.ui

import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds

object WeMainActivityBeautifyApi : ApiFeature(), IResolveDex {

    override val technicalId = "微信主屏幕美化服务"
    override val nameRes = R.string.feature_we_main_activity_beautify_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_main_activity_beautify_api_description

    val methodDoOnCreate by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.MainTabUI"
            usingEqStrings("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
        }
    }
}
