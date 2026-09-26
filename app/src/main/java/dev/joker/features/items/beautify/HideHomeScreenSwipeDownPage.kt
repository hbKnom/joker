package dev.joker.features.items.beautify

import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ListView
import dev.joker.reflekt.reflekt
import dev.joker.R
import dev.joker.features.api.ui.WeConversationListViewApi
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.features.items.chat.ConversationGrouping
import dev.joker.utils.invokeOriginalMethod

object HideHomeScreenSwipeDownPage : SwitchFeature() {

    override val technicalId = "隐藏主页下滑「最近」页"
    override val nameRes = R.string.feature_hide_home_screen_swipe_down_page_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_hide_home_screen_swipe_down_page_description

    override fun onEnable() {
        ListView::class.reflekt()
            .firstMethod {
                name = "addHeaderView"
                parameterCount = 3
            }
            .hookBefore {
                if (thisObject!!.javaClass.simpleName != "ConversationListView") return@hookBefore
                val view = args[0] as View
                val className = view.javaClass.simpleName
                if (className == "TaskBarContainer") {
                    val heightDp = if (!ConversationGrouping.isEnabled) 48 else 94
                    val heightPx = (heightDp * view.resources.displayMetrics.density).toInt()
                    val spacer = View(view.context).apply {
                        layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, heightPx)
                    }
                    invokeOriginalMethod(args = arrayOf(spacer, null, true))
                    result = null
                }
            }

        val recyclerAddHeader = WeConversationListViewApi.methodRecyclerAddHeaderView
        if (!recyclerAddHeader.isPlaceholder) {
            recyclerAddHeader.hookBefore {
                val view = args[0] as View
                if (view.javaClass.simpleName != "TaskBarContainer") return@hookBefore
                val heightDp = if (!ConversationGrouping.isEnabled) 48 else 94
                val heightPx = (heightDp * view.resources.displayMetrics.density).toInt()
                args[0] = View(view.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        heightPx,
                    )
                }
            }
        }
    }
}
