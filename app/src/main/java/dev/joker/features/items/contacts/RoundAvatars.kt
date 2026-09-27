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
import dev.joker.preferences.HotPrefs
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

    /**
     * 头像圆角系数。
     *
     * 【2026-09-27 修卡顿】它在 `hookBefore { setFloatArg(2, radiusFactor) }` 里被读 —— 也就是
     * **每创建一个头像就触发一次**（聊天列表、通讯录、群成员、朋友圈都是这个入口）。原先直查
     * `WePrefs.getFloatOrDef`，而 WePrefs 的一次读就是一次真正的 SQLite 查询（建语句/开游标/加锁），
     * 在快速滑动时是可见的掉帧来源。改用 [HotPrefs.float]（1 秒 TTL 缓存）；
     * 设置界面里的写入路径会 `invalidate`，所以「改完立刻生效」这条语义不变。
     */
    private val radiusFactor: Float
        get() = HotPrefs.float(KEY_ROUND_AVATAR, 0.5f).coerceIn(0.1f, 0.5f)

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
                                        HotPrefs.invalidate(KEY_ROUND_AVATAR)
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
