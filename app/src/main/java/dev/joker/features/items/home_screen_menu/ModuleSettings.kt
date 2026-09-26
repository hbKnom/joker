package dev.joker.features.items.home_screen_menu

import com.tencent.mm.ui.LauncherUI
import dev.joker.R
import dev.joker.BuildConfig
import dev.joker.features.api.ui.WeHomeScreenPopupMenuApi
import dev.joker.features.api.ui.WeSettingsInjector
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.ui.utils.ExtensionIcon
import dev.joker.utils.HookParam

object ModuleSettings : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "模块设置"
    override val nameRes = R.string.feature_module_settings_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_module_settings_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> =
        listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                0, BuildConfig.TAG, ExtensionIcon
            ) { WeSettingsInjector.openSettingsDialog(LauncherUI.getInstance()!!) }
        )
}
