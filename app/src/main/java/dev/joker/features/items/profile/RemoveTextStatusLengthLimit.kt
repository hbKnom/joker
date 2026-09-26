package dev.joker.features.items.profile

import dev.joker.reflekt.reflekt
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.data
import dev.joker.dexkit.dsl.dexField
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType

object RemoveTextStatusLengthLimit : SwitchFeature(), IResolveDex {

    override val technicalId = "解除状态词长度限制"
    override val nameRes = R.string.feature_remove_text_status_length_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.PROFILE)
    override val descriptionRes = R.string.feature_remove_text_status_length_limit_description

    private val methodStatusTextChanged by dexMethod {
        searchPackages("com.tencent.mm.plugin.textstatus.ui")
        matcher {
            name = "afterTextChanged"
            paramTypes("android.text.Editable")
            returnType = "void"
            usingEqStrings(
                "MicroMsg.TextStatus.TextStatusDoWhatActivityV2",
                "afterTextChanged inputCount:",
            )
        }
    }
    private val fieldStatusTextLengthLimit by dexField()

    override fun resolveDex(dexKit: DexKitBridge) {
        fieldStatusTextLengthLimit.setDescriptor(
            methodStatusTextChanged.data.usingFields
                .filter { it.usingType == FieldUsingType.Read }
                .map { it.field }
                .distinctBy { it.descriptor }
                .single {
                    it.className == STATUS_EDITOR_CLASS && it.typeName == "int"
                }
        )
    }

    override fun onEnable() {
        val limitField = fieldStatusTextLengthLimit.field
        limitField.declaringClass.reflekt().constructors().forEach { constructor ->
            constructor.hookAfter {
                limitField.setInt(thisObject!!, MAX_STATUS_TEXT_LENGTH)
            }
        }
    }

    private const val STATUS_EDITOR_CLASS =
        "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2"
    private const val MAX_STATUS_TEXT_LENGTH = 2000
}
