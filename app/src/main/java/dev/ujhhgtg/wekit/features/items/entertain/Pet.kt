package dev.ujhhgtg.wekit.features.items.entertain

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.pet.PetService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * User-facing desktop-pet entry. The toggle mounts/tears down the pet overlay
 * ([dev.ujhhgtg.wekit.pet.PetOverlayController]); long-press the pet to open its
 * info/treat panel. When WeAgent is also enabled, the pet's activity follows the
 * agent session state via [PetService.onAgentEvent].
 */
object Pet : SwitchFeature() {

    override val technicalId = "桌面宠物"
    override val nameRes = R.string.feature_pet_name
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val descriptionRes = R.string.feature_pet_description

    override fun onEnable() {
        PetService.init()
        MainScope().launch(Dispatchers.Main) {
            PetService.setVisible(true)
        }
    }

    override fun onDisable() {
        MainScope().launch(Dispatchers.Main) {
            PetService.setVisible(false)
        }
    }
}
