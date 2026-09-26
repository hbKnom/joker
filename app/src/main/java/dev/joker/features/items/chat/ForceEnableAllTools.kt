package dev.joker.features.items.chat

import dev.joker.reflekt.reflekt
import dev.joker.reflekt.utils.Modifiers
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexClass
import dev.joker.dexkit.dsl.dexField
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import org.luckypray.dexkit.DexKitBridge

object ForceEnableAllTools : SwitchFeature(), IResolveDex {

    override val technicalId = "强制启用所有功能"
    override val nameRes = R.string.feature_force_enable_all_tools_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_force_enable_all_tools_description

    private val classAppPanelConfig by dexClass()
    private val fieldAppPanelConfig by dexField()
    private val fieldFirstFlag by dexField()
    private val fieldFlagEnabled by dexField()
    private val methodRefreshAppPanel by dexMethod()

    override fun onEnable() {
        methodRefreshAppPanel.hookBefore {
            val appPanel = thisObject ?: return@hookBefore
            val config = fieldAppPanelConfig.field.get(appPanel) ?: return@hookBefore
            config.reflekt().fields {
                type = fieldFirstFlag.field.type
            }.forEach {
                fieldFlagEnabled.field.setBoolean(it.get()!!, true)
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val refreshMethod = dexKit.findMethod {
            searchPackages(APP_PANEL_PACKAGE)
            matcher {
                declaredClass = APP_PANEL_CLASS
                usingEqStrings("MicroMsg.AppPanel", "roomEnable:%s, hideRoomLive:%s")
                paramCount = 0
                returnType = "void"
            }
        }.single()
        methodRefreshAppPanel.setDescriptor(refreshMethod)

        val usedFields = refreshMethod.usingFields.map { it.field }.distinct()
        val firstFlag = usedFields.first { field ->
            field.modifiers and Modifiers.FINAL != 0 &&
                field.type.fields.count { it.typeName == "boolean" } == 1
        }
        val configClass = firstFlag.declaredClass
        val appPanelConfig = usedFields.single { field ->
            field.className == APP_PANEL_CLASS && field.typeName == configClass.name
        }
        val flagEnabled = firstFlag.type.fields.single {
            it.typeName == "boolean"
        }

        classAppPanelConfig.setDescriptor(configClass)
        fieldAppPanelConfig.setDescriptor(appPanelConfig)
        fieldFirstFlag.setDescriptor(firstFlag)
        fieldFlagEnabled.setDescriptor(flagEnabled)
    }

    private const val APP_PANEL_PACKAGE = "com.tencent.mm.pluginsdk.ui.chat"
    private const val APP_PANEL_CLASS = "$APP_PANEL_PACKAGE.AppPanel"
}
