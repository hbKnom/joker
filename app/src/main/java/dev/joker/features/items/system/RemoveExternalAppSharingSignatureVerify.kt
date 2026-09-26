package dev.joker.features.items.system

import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

object RemoveExternalAppSharingSignatureVerify : SwitchFeature(), IResolveDex {

    override val technicalId = "移除分享签名校验"
    override val nameRes = R.string.feature_remove_external_app_sharing_signature_verify_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_remove_external_app_sharing_signature_verify_description

    private val methodSignCheck by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        matcher {
            usingEqStrings("checkAppSignature get local signature failed")
        }
    }

    override fun onEnable() {
        methodSignCheck.hookBefore {
            result = true
        }
    }
}
