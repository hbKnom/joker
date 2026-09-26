package dev.ujhhgtg.wekit.features.items.moments

import android.app.Activity
import android.view.MotionEvent
import android.widget.FrameLayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isBuiltin
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.lang.reflect.Field
import java.lang.reflect.Method

object NoCloseVideoPlayerOnClick : SwitchFeature(), IResolveDex {

    override val technicalId = "单击不关闭视频播放器"
    override val nameRes = R.string.feature_no_close_video_player_on_click_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_no_close_video_player_on_click_description

    private lateinit var activityField: Field
    private lateinit var viewStateField: Field
    private lateinit var getToggleBtnMethod: Method

    override fun onEnable() {
        methodVideoOnTouchListenerOnTouch.hookBefore {
            val event = args[1] as MotionEvent
            if ((event.action and 0xFF) == MotionEvent.ACTION_UP) {
                if (!::activityField.isInitialized) {
                    activityField = thisObject!!.reflekt()
                        .firstField { type { it isSubclassOf Activity::class } }
                        .self
                }

                val activity = activityField.get(thisObject) as Activity

                // The expandable seek bar does NOT inherit HeroSeekBarView, and on newer WeChat
                // builds it is no longer reachable by guessing a declared field type (both the
                // old view-state field pick and the old seek-bar field pick silently failed).
                // Resolve it by value instead, walking a couple of levels through plain holders.
                val expandableSeekBar = findExpandableSeekBar(activity)
                    ?: run {
                        if (!::viewStateField.isInitialized) {
                            viewStateField = activity.reflekt()
                                .firstField { type { !it.isBuiltin } }.self
                        }
                        findExpandableSeekBar(viewStateField.get(activity))
                    }
                    ?: return@hookBefore

                if (!::getToggleBtnMethod.isInitialized) {
                    getToggleBtnMethod = expandableSeekBar.reflekt()
                        .firstMethod { name = "getExpandBarBtn" }
                        .self.makeAccessible()
                }

                val toggleBtn = getToggleBtnMethod.invoke(expandableSeekBar) as FrameLayout
                toggleBtn.performClick()
            }

            // always consume
            result = false
        }
    }

    private fun isExpandableSeekBar(candidate: Any?): Boolean =
        candidate != null && candidate.javaClass.name.contains("ExpandableHeroSeekBar")

    /**
     * Finds the expandable seek bar inside [root] by inspecting field *values* rather than
     * declared types. View subtrees are not walked (the bar is held by a plain controller
     * object, not by the view hierarchy we are inspecting).
     */
    private fun findExpandableSeekBar(root: Any?, depth: Int = 0): Any? {
        if (root == null || depth > 3) return null
        val fields = runCatching { root.reflekt().fields { superclass = true } }.getOrNull()
            ?: return null

        for (field in fields) {
            val value = runCatching { field.get() }.getOrNull() ?: continue
            if (isExpandableSeekBar(value)) return value
        }

        for (field in fields) {
            val value = runCatching { field.get() }.getOrNull() ?: continue
            if (value is android.view.View) continue
            findExpandableSeekBar(value, depth + 1)?.let { return it }
        }
        return null
    }

    private val methodVideoOnTouchListenerOnTouch by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui")
        matcher {
            name = "onTouch"
            usingEqStrings("com/tencent/mm/plugin/sns/ui/SnsOnlineVideoActivity$5", $$"android/view/View$OnTouchListener", "onTouch")
        }
    }
}
