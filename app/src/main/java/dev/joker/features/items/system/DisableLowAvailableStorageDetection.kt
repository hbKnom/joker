package dev.joker.features.items.system

import android.app.Activity
import dev.joker.reflekt.reflekt
import dev.joker.reflekt.utils.toClass
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexClass
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

object DisableLowAvailableStorageDetection : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用存储空间不足检测"
    override val nameRes = R.string.feature_disable_low_available_storage_detection_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_disable_low_available_storage_detection_description

    private val methodSplashActivitySplashFinished by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.splash.SplashActivity"
            usingEqStrings("WxSplash.SplashActivity", "Call splashFinished.")
        }
    }
    private val classStaticValuesHolder by dexClass {
        matcher {
            usingEqStrings("UIPageFragmentActivity", "LuckyMoneyNewPrepareUI", "RemittanceUI")
        }
    }

    override fun onEnable() {
        methodSplashActivitySplashFinished.hookBefore {
            classStaticValuesHolder.clazz.reflekt()
                .firstField { type = Boolean::class }
                .setStatic(false)
        }

        "com.tencent.mm.plugin.clean.ui.fileindexui.StorageDisableAlertUI"
            .toClass().hookAfterOnCreate {
                val activity = thisObject as Activity
                activity.finish()
            }
    }
}
