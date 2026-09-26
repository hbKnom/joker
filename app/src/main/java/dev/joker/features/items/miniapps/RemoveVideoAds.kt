package dev.joker.features.items.miniapps

import dev.joker.reflekt.reflekt
import dev.joker.reflekt.utils.toClass
import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.utils.TargetProcess
import org.json.JSONObject

object RemoveVideoAds : SwitchFeature() {

    override val technicalId = "移除视频广告"
    override val nameRes = R.string.feature_remove_video_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_remove_video_ads_description

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.APPBRAND)

    override fun onEnable() {
        "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding".toClass().reflekt()
            .firstMethod { name = "subscribeHandler" }
            .hookBefore {
                val type = args[0] as String? ?: ""
                val json = args[1] as String? ?: ""

                if (type == "onVideoTimeUpdate") {
                    val json = JSONObject(json)
                    json.put("position", 60)
                    json.put("duration", 1)
                    args[1] = json.toString()
                }
            }
    }
}
