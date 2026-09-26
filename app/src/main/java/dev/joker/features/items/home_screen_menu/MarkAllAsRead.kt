package dev.joker.features.items.home_screen_menu

import dev.joker.R
import dev.joker.features.api.core.WeConversationApi
import dev.joker.features.api.ui.WeHomeScreenPopupMenuApi
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.ui.utils.MarkChatReadIcon
import dev.joker.utils.HookParam
import dev.joker.utils.android.showToast

object MarkAllAsRead : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "清空未读"
    override val nameRes = R.string.feature_mark_all_as_read_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_mark_all_as_read_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777012, localizedHomeMenuString(R.string.home_menu_mark_all_read), MarkChatReadIcon
            ) {
                WeConversationApi.markAllAsRead()
                showToast(localizedHomeMenuString(R.string.home_menu_all_marked_read))
            }
        )
    }
}
