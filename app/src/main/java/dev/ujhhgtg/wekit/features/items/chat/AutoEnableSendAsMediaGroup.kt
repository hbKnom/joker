package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.widget.CheckBox
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

object AutoEnableSendAsMediaGroup : SwitchFeature(), IResolveDex {

    override val technicalId = "自动启用合并发送媒体"
    override val nameRes = R.string.feature_auto_enable_send_as_media_group_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_enable_send_as_media_group_description

    private const val ALBUM_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
    private const val IMAGE_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
    private const val KEY_SEND_AS_MEDIA_GROUP = "key_send_as_media_group"

    /** 灰测半屏相册的 picker 参数类（与「自动勾选发送原图」用的是同一个类）。 */
    private const val HALF_SCREEN_ARGUMENTS =
        "com.tencent.mm.plugin.picker.scene.chatting.ChattingLocalMediaPickerFeatureArguments"

    /**
     * 半屏相册里可能表示「合并/分组发送」的零参 boolean 访问器候选名。
     * 只做保守的命名枚举：命中不了就什么都不做。
     */
    private val HALF_SCREEN_GROUP_ACCESSORS = listOf(
        "getInitialSendAsMediaGroup",
        "getInitialSendAsMediaGrouping",
        "getInitialSendAsGroup",
        "getInitialSendGroup",
        "getInitialSendGrouping",
        "getInitialMergeSend",
        "getInitialSendMerge",
        "getInitialMergeGroup",
        "getInitialSendWithGroup",
    )

    private const val TAG = "AutoSendMediaGroup"

    /**
     * 「发送后合并展示」是 8.0.69+ 才有的选项（AlbumPreviewUI/ImagePreviewUI 中均包含
     * 宿主自身 AOP 埋点字符串 initSendAsMediaGroupingViews），8.0.65/8.0.67 不存在，
     * 因此允许解析失败。
     */
    private val methodInitSendAsMediaGroupingViews by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            usingEqStrings("initSendAsMediaGroupingViews")
        }
    }

    /**
     * 选择数量变化回调（包含 updateSendAsMediaGroupViews 埋点字符串的方法）。
     * 微信在数量 < 3 时会把勾选状态重置为 false，因此需要在这里随数量重新勾选。
     */
    private val methodUpdateSendAsMediaGroupViews by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            usingEqStrings("updateSendAsMediaGroupViews")
        }
    }

    /**
     * 勾选状态字段：在三个支持版本中，包含 updateSendAsMediaGroupViews 埋点字符串的方法
     * 只写入这一个 boolean 字段（选中状态重置为 false），据此唯一定位。
     */
    private val sendAsMediaGroupField by dexField(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            type = "boolean"
            addWriteMethod {
                usingEqStrings("updateSendAsMediaGroupViews")
            }
        }
    }

    /** AlbumPreviewUI 中唯一的 CheckBox 即「发送后合并展示」勾选框 */
    private val sendAsMediaGroupCheckBoxField by dexField(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            type(CheckBox::class.java)
        }
    }

    override fun onEnable() {
        if (
            methodInitSendAsMediaGroupingViews.isPlaceholder ||
            methodUpdateSendAsMediaGroupViews.isPlaceholder ||
            sendAsMediaGroupField.isPlaceholder ||
            sendAsMediaGroupCheckBoxField.isPlaceholder
        ) {
            return
        }

        // AlbumPreviewUI 直发时依据该字段决定是否合并展示，必须同步勾选状态字段与勾选框
        methodInitSendAsMediaGroupingViews.hookAfter {
            val activity = thisObject as Activity
            sendAsMediaGroupField.field.set(activity, true)
            (sendAsMediaGroupCheckBoxField.field.get(activity) as CheckBox).setChecked(true)
        }

        // 选择数量达到 3 张及以上时（合并展示仅对 3 张及以上生效）保持勾选
        methodUpdateSendAsMediaGroupViews.hookAfter {
            if (args[0] as Int >= 3) {
                val activity = thisObject as Activity
                sendAsMediaGroupField.field.set(activity, true)
                (sendAsMediaGroupCheckBoxField.field.get(activity) as CheckBox).setChecked(true)
            }
        }

        // ImagePreviewUI 在 initView 中读取该 extra 初始化勾选框
        IMAGE_PREVIEW_UI.toClass().hookBeforeOnCreate {
            val activity = thisObject as Activity
            activity.intent.putExtra(KEY_SEND_AS_MEDIA_GROUP, true)
        }

        installHalfScreenPickerHooks()
    }

    /**
     * 灰测的原生「半屏相册」（`clicfg_local_media_picker_chatting_opt`）走的是 Compose picker：
     * 「发送后合并展示」的初始勾选由 `ChattingLocalMediaPickerFeatureArguments` 上的零参 boolean
     * 访问器决定（对照同级的「原图」用的是 `getInitialSendOriginal`），
     * 上面那套 `AlbumPreviewUI` / `ImagePreviewUI` 钩子完全覆盖不到它 —— 这就是
     * 「自动勾选发送时合并展示 在这套 picker 上失效」的原因。
     *
     * 上游在这段用的是字节码谓词定位（逆向包 README §4.1 明确说明该谓词还原不出来），
     * 所以这里退一步、按**命名约束**尽力而为：零参 + 返回 boolean + 名字以 `getInitial`/`isInitial`
     * 开头且含 `Group`/`Merge` 的候选访问器；命中就在 before 阶段返回 true。
     * 一个都没命中时什么都不做（保持现状，不会比现在更坏），命中情况写日志，下一轮可据此收敛。
     */
    private fun installHalfScreenPickerHooks() {
        runCatching {
            val clazz = HALF_SCREEN_ARGUMENTS.toClassOrNull() ?: return
            val hooked = mutableListOf<String>()
            HALF_SCREEN_GROUP_ACCESSORS.forEach { candidateName ->
                val accessor = clazz.reflekt().firstMethodOrNull {
                    name = candidateName
                    parameters()
                    returnType { it == java.lang.Boolean.TYPE }
                }
                if (accessor != null) {
                    accessor.hookBefore { result = true }
                    hooked += candidateName
                }
            }
            WeLogger.i(
                TAG,
                if (hooked.isEmpty()) "half-screen picker: no group accessor matched"
                else "half-screen picker group accessors hooked: $hooked",
            )
        }.onFailure { WeLogger.w(TAG, "half-screen picker group hook failed", it) }
    }
}
