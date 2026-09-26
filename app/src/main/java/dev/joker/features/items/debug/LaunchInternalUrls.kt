package dev.joker.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.Button
import dev.joker.ui.content.DefaultColumn
import dev.joker.ui.content.TextButton
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.unsafe.TheUnsafe

object LaunchInternalUrls : ClickableFeature(), IResolveDex {

    override val technicalId = "启动微信内部 URL"
    override val nameRes = R.string.feature_launch_internal_urls_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_launch_internal_urls_description

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var url by remember { mutableStateOf("weixin://") }
            var argsInput by remember { mutableStateOf("") }

            AlertDialogContent(
                title = { Text(stringResource(R.string.debug_launch_internal_url_title)) },
                text = {
                    DefaultColumn {
                        TextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text(stringResource(R.string.debug_launch_internal_url_url)) })
                        TextField(
                            value = argsInput,
                            onValueChange = { argsInput = it },
                            label = { Text(stringResource(R.string.debug_launch_internal_url_arguments)) })
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        val args = if (argsInput.isBlank()) emptyList() else argsInput.split("\n")
                        methodOpenUrl.method.invoke(
                            // FIXME: getDeclaredConstructor() says no ctor exists?? but Unsafe works????
                            TheUnsafe.allocateInstance(methodOpenUrl.method.declaringClass),
                            *arrayOf(context, url, args.toTypedArray())
                        )
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    private val methodOpenUrl by dexMethod {
        searchPackages("com.tencent.mm.app.plugin")
        matcher {
            usingEqStrings("MicroMsg.MMURIJumpHandler", "openSpecificUI, context is null")
        }
    }
}
