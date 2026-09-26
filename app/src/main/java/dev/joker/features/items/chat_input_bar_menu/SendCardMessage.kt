package dev.joker.features.items.chat_input_bar_menu

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Send_time_extension
import dev.joker.R
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.api.ui.WeChatInputBarMenuApi
import dev.joker.features.api.ui.WeCurrentConversationApi
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.utils.android.showToast

object SendCardMessage : SwitchFeature() {

    override val technicalId = "发送卡片消息"
    override val nameRes = R.string.feature_send_card_message_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_send_card_message_description

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_card_message",
                icon = MaterialSymbols.Outlined.Send_time_extension,
                label = localizedChatInputString(R.string.feature_send_card_message_name),
                onClick = { context, chatFooter ->
                    val currentConv = WeCurrentConversationApi.value
                    val content = chatFooter.lastText

                    if (content.isEmpty()) {
                        showToast(
                            context,
                            context.localizedChatInputString(R.string.send_card_message_input_empty),
                        )
                        return@ActionItem
                    }

                    val isSuccess = WeMessageApi.sendXmlAppMsg(currentConv, content)
                    if (!isSuccess) {
                        showToast(
                            context,
                            context.localizedChatInputString(R.string.send_card_message_failed),
                        )
                        return@ActionItem
                    }

                    chatFooter.lastText = ""
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
