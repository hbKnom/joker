package dev.joker.features.items.home_screen_menu

import com.tencent.mm.ui.LauncherUI
import dev.joker.R
import dev.joker.features.api.ui.WeHomeScreenPopupMenuApi
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.features.items.contacts.showOpenConversationDialog
import dev.joker.ui.utils.ChatInfoIcon
import dev.joker.utils.HookParam

object OpenConversationMenu : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "跳转对话菜单"
    override val nameRes = R.string.feature_open_conversation_menu_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_open_conversation_menu_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777025, localizedHomeMenuString(R.string.home_menu_open_conversation), ChatInfoIcon
            ) {
                showOpenConversationDialog(LauncherUI.getInstance()!!)
            }
        )
    }
}
