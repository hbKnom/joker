package dev.joker.features.api.ui

import android.app.Activity
import com.tencent.mm.ui.LauncherUI
import dev.joker.R
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.ui.utils.LifecycleOwnerProvider
import dev.joker.ui.utils.rootView
import dev.joker.ui.utils.setLifecycleOwner

object WeViewTreeLifecycleProvider : ApiFeature() {

    override val technicalId = "Compose 生命周期提供方"
    override val nameRes = R.string.feature_we_view_tree_lifecycle_provider_name
    override val categoryIds = listOf(FeatureCategoryIds.API)

    override fun onEnable() {
        LauncherUI::class.hookAfterOnCreate {
            val activity = thisObject as Activity

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner

            val decorView = activity.window.decorView
            decorView.setLifecycleOwner(lifecycleOwner)
            activity.rootView.setLifecycleOwner(lifecycleOwner)
        }
    }
}
