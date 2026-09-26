package dev.joker.features.items.home_screen_menu

import dev.joker.R
import dev.joker.features.api.ui.WeHomeScreenPopupMenuApi
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.ui.utils.CancelIcon
import dev.joker.utils.HookParam
import dev.joker.utils.killHost

object KillHostProcess : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "强行停止"
    override val nameRes = R.string.feature_kill_host_process_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_kill_host_process_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777015, localizedHomeMenuString(R.string.home_menu_force_stop), CancelIcon
            ) {
                killHost()
            }
        )
    }
}
