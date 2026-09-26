package dev.joker.features.items.scripting_java

import androidx.activity.ComponentActivity
import dev.joker.R
import dev.joker.activity.TransparentActivity
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.registerBshSnapshotDecompileLaunchers

object DecompileBeanShellSnapshot : ClickableFeature() {

    override val technicalId = "反编译 BeanShell 快照"
    override val nameRes = R.string.feature_decompile_bean_shell_snapshot_name
    override val categoryIds = listOf(FeatureCategoryIds.SCRIPTING_JAVA)
    override val descriptionRes = R.string.feature_decompile_bean_shell_snapshot_description

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val selectFileLauncher = registerBshSnapshotDecompileLaunchers { finish() }
            selectFileLauncher.launch("*/*")
        }
    }
}
