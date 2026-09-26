package dev.joker.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexConstructor
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.preferences.WePrefs
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.TextButton
import dev.joker.ui.content.m3.BaseItemContainer
import dev.joker.ui.content.m3.IntNumberPickerWidget
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.HookParam
import kotlin.math.roundToInt
import org.luckypray.dexkit.DexKitBridge

object RoundAvatars : ClickableFeature(), IResolveDex {

    override val technicalId = "圆角头像"
    override val nameRes = R.string.feature_round_avatars_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_round_avatars_description

    private const val KEY_ROUND_AVATAR = "round_avatar_radius_factor"

    private val ctorAvatarCreate by dexConstructor {
        matcher {
            usingEqStrings("workerScope", "username")
        }
    }
    private val methodAvatarModify by dexMethod()

    private val radiusFactor: Float
        get() = WePrefs.getFloatOrDef(KEY_ROUND_AVATAR, 0.5f).coerceIn(0.1f, 0.5f)

    override fun onEnable() {
        CustomLocalFriendAvatars.methodConversationAvatar.hookBefore {
            setFloatArg(2, radiusFactor)
        }

        ctorAvatarCreate.hookBefore {
            setFloatArg(2, radiusFactor)
        }

        if (!methodAvatarModify.isPlaceholder) {
            methodAvatarModify.hookBefore {
                setFloatArg(3, radiusFactor)
            }
        }

        notifyCustomContactAvatarChanged()
    }

    override fun onDisable() {
        notifyCustomContactAvatarChanged()
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val modifyMethods = dexKit.findMethod {
            matcher {
                usingEqStrings("workerScope", "username")
            }
        }.filter { it.methodName != "<init>" }

        val modifyMethod = modifyMethods.singleOrNull()
        if (modifyMethod == null) {
            methodAvatarModify.setPlaceholderDescriptor(
                expectedFailure = true,
                reason = "avatar modify method is absent in this host variant",
            )
        } else {
            methodAvatarModify.setDescriptor(modifyMethod)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var percent by remember { mutableIntStateOf((radiusFactor * 100).roundToInt()) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_round_avatars_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.contacts_round_avatar_radius),
                                    value = percent,
                                    startInt = 10,
                                    endInt = 50,
                                    stepSize = 1,
                                    valueSuffix = "%",
                                    onValueChange = {
                                        percent = it
                                        WePrefs.putFloat(KEY_ROUND_AVATAR, it / 100f)
                                        notifyCustomContactAvatarChanged()
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun HookParam.setFloatArg(index: Int, value: Float) {
        if (index in args.indices) args[index] = value
    }

    private fun notifyCustomContactAvatarChanged() {
        runCatching {
            if (CustomLocalFriendAvatars.isActive) {
                CustomLocalFriendAvatars.onRoundAvatarConfigChanged()
            }
        }
    }
}
