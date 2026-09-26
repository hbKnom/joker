package dev.joker.features.items.chat

import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.joker.reflekt.utils.fastJavaMethod
import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

object DisableSpeechToTextButton : SwitchFeature() {

    override val technicalId = "禁用输入框快捷语音转文字"
    override val nameRes = R.string.feature_disable_speech_to_text_button_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_speech_to_text_button_description

    override fun onEnable() {
        ChatFooter::getV2TBtnLayout.fastJavaMethod!!.hookBefore {
            result = null
        }
    }
}
