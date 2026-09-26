package dev.joker.features.items.chat

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import dev.joker.R
import dev.joker.reflekt.reflekt
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.api.core.models.MessageInfo
import dev.joker.features.api.core.models.MessageType
import dev.joker.features.api.ui.WeChatMessageContextMenuApi
import dev.joker.features.api.ui.WeCurrentConversationApi
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.ui.utils.EditIcon
import dev.joker.utils.android.getSystemService
import dev.joker.utils.now
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

object QuickRevokeAndEdit : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "一键撤回并重新编辑"
    override val nameRes = R.string.feature_quick_revoke_and_edit_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_quick_revoke_and_edit_description

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    fun isSupported(msgInfo: MessageInfo): Boolean {
        return msgInfo.type?.isText == true && msgInfo.isSelfSender && now() - Instant.fromEpochMilliseconds(msgInfo.createTime) <= 2.minutes
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777016, localizedChatString(R.string.chat_swipe_action_edit), EditIcon, MaterialSymbols.Outlined.Edit,
                isSupported = { isSupported(it) },
                // revokes then loads one message's text into the input box; single-message only
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            ) { view, _, msgInfo ->
                quickRevokeAndEdit(view.context, msgInfo)
            }
        )
    }

    fun quickRevokeAndEdit(context: Context, msgInfo: MessageInfo) {
        val chatFooter = WeCurrentConversationApi.chatFooter ?: return
        WeMessageApi.revokeMsg(msgInfo)
        if (msgInfo.type == MessageType.QUOTE) {
            chatFooter.lastText = msgInfo.quoteMsgActualContent ?: ""
            WeMessageApi.setReferringMessage(
                chatFooter,
                WeMessageApi.getMsgInfoInstanceByMsgSvrId(msgInfo.toQuoteMessage()!!.svrid, msgInfo.talker)
            )
        } else {
            chatFooter.lastText = msgInfo.actualContent
        }

        chatFooter.setMode(1)
        val toSendEt = chatFooter.reflekt().invokeMethod("getToSendEt")!!

        val etView = toSendEt.reflekt().firstMethod {
            returnType = View::class
        }.invoke()!! as View

        etView.requestFocus()
        val im = context.getSystemService<InputMethodManager>()
        etView.post {
            im.showSoftInput(etView, 0)
        }
    }
}
