package dev.joker.features.api.net

import dev.joker.reflekt.reflekt
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds

object WeNetSceneApi : ApiFeature(), IResolveDex {

    override val technicalId = "NetScene 服务"
    override val nameRes = R.string.feature_we_net_scene_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_net_scene_api_description

    fun sendNetScene(netScene: Any) {
        val queue = WeDatabaseApi.classMmKernel.clazz.reflekt()
            .firstMethod {
                returnType = methodAddNetSceneToQueue.method.declaringClass
            }.invokeStatic()!!
        methodAddNetSceneToQueue.method.invoke(queue, netScene, 0)
    }

    val methodAddNetSceneToQueue by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.NetSceneQueue", "forbid in waiting: type=", "forbid in running: type=")
        }
    }
}
