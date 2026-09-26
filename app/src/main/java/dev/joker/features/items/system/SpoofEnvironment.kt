package dev.joker.features.items.system

import android.provider.Settings
import dev.joker.reflekt.reflekt
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

object SpoofEnvironment : SwitchFeature(), IResolveDex {

    override val technicalId = "环境伪装"
    override val nameRes = R.string.feature_spoof_environment_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_spoof_environment_description

    override fun onEnable() {
        Settings.Global::class.reflekt()
            .firstMethod {
                name = "getInt"
                parameterCount = 3
            }.hookBefore {
                val name = args[1] as? String? ?: return@hookBefore
                if (name == "adb_enabled")
                    result = 0
            }

        Settings.Secure::class.reflekt()
            .firstMethod {
                name = "getInt"
                parameterCount = 3
            }.hookBefore {
                val name = args[1] as? String? ?: return@hookBefore
                if (name == "development_settings_enabled")
                    result = 0
            }

        methodIsVpnEnabled.hookBefore {
            result = false
        }
    }

    private val methodIsVpnEnabled by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.WalletSecurityUtilService")
            }

            usingEqStrings("connectivity")
            usingNumbers(4)
        }
    }
}
