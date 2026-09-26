package dev.joker.features.items.contacts

import dev.joker.R
import dev.joker.features.api.core.WeApi
import dev.joker.features.api.ui.WeConversationContextMenuApi
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.features.items.chat.ConversationAggregation
import dev.joker.ui.utils.CameraIcon
import dev.joker.utils.strings.isGroupChatWxId

object QuickOpenMoments : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {

    override val technicalId = "快捷打开朋友圈"
    override val nameRes = R.string.feature_quick_open_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_quick_open_moments_description

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777018,
                text = localizedContactsString(R.string.contacts_open_moments),
                drawable = CameraIcon,
                shouldShow = { context, _ ->
                    val talker = context.talker
                    talker.isNotEmpty() &&
                            !talker.isGroupChatWxId &&
                            !talker.startsWith("gh_") &&
                            !talker.endsWith("@app") &&
                            !talker.startsWith(ConversationAggregation.FOLDER_PREFIX)
                },
            ) { context ->
                WeApi.openMoments(context.activity, context.talker)
            }
        )
    }
}
