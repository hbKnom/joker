package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import java.util.HashMap
import java.util.WeakHashMap

/**
 * 头衔标签 View 注入器（忠实还原原脚本「会话自定义头衔」的注入机制）
 *
 * 原脚本在昵称 TextView（userTV）的父容器里动态 addView 一个独立 TextView：
 *  - LinearLayout：插入到昵称的 index 位置（昵称左侧同一行）
 *  - RelativeLayout：ABOVE + ALIGN_START（昵称上方）
 *  - ConstraintLayout：bottomToTop + startToStart（昵称上方）
 *
 * 「头衔上」用 [TitlePlacement.ABOVE]（还原原脚本位置）；
 * 「头衔下」用 [TitlePlacement.BELOW]（LinearLayout 昵称右侧 / Relative、Constraint 昵称下方），
 * 两者注入位置永远不同，杜绝「上/下互串」。
 *
 * 稳定性改造：
 *  - WeakHashMap 按昵称 View 缓存标签 View，bind 复用、不重复 addView，避免重布局卡顿。
 *  - 缓存命中时校验 titleView.parent === nick.parent——列表 view 复用换布局（不同 viewType）
 *    时旧标签仍挂在旧容器（可能被复用给其他消息），必须移除重建，杜绝「不同 wxid 头衔互串」。
 */
object TitleTagOverlay {

    enum class TitlePlacement { ABOVE, BELOW }

    private val tagViews = WeakHashMap<TextView, HashMap<String, TextView>>()

    /** 取（或创建）昵称 TextView 对应的头衔标签 View；父容器不支持时返回 null。 */
    fun getOrCreate(nick: TextView, key: String, placement: TitlePlacement, onStyle: (TextView) -> Unit): TextView? {
        synchronized(tagViews) {
            val cached = tagViews[nick]?.get(key)
            if (cached != null) {
                // 同一 nick 仍挂在同一父容器 → 直接复用
                if (cached.parent === nick.parent && nick.parent != null) return cached
                // view 复用换布局：旧标签挂在旧容器（可能已被其他消息复用），移除重建
                (cached.parent as? ViewGroup)?.removeView(cached)
                tagViews[nick]?.remove(key)
            }
        }
        val parent = nick.parent as? ViewGroup ?: return null
        val title = TextView(nick.context).apply {
            visibility = View.GONE
            onStyle(this)
        }
        val added = when (parent) {
            is LinearLayout -> {
                val index = parent.indexOfChild(nick)
                if (index < 0) return null
                val insertAt = if (placement == TitlePlacement.BELOW) index + 1 else index
                parent.addView(
                    title,
                    insertAt.coerceAtMost(parent.childCount),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                true
            }
            is RelativeLayout -> {
                if (nick.id == View.NO_ID) nick.id = View.generateViewId()
                val lp = RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (placement == TitlePlacement.BELOW) {
                        addRule(RelativeLayout.BELOW, nick.id)
                        addRule(RelativeLayout.ALIGN_START, nick.id)
                        topMargin = dp(nick, 2f)
                    } else {
                        addRule(RelativeLayout.ABOVE, nick.id)
                        addRule(RelativeLayout.ALIGN_START, nick.id)
                        bottomMargin = dp(nick, 2f)
                    }
                }
                parent.addView(title, lp)
                true
            }
            is ConstraintLayout -> {
                if (nick.id == View.NO_ID) nick.id = View.generateViewId()
                val lp = ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (placement == TitlePlacement.BELOW) {
                        topToBottom = nick.id
                        startToStart = nick.id
                        topMargin = dp(nick, 2f)
                    } else {
                        bottomToTop = nick.id
                        startToStart = nick.id
                        bottomMargin = dp(nick, 2f)
                    }
                }
                parent.addView(title, lp)
                true
            }
            else -> false
        }
        if (!added) return null
        synchronized(tagViews) {
            tagViews.getOrPut(nick) { HashMap() }[key] = title
        }
        return title
    }

    /** 隐藏已创建的标签（复用前先隐藏，避免闪出旧头衔）。 */
    fun hide(nick: TextView, key: String) {
        val view = synchronized(tagViews) { tagViews[nick]?.get(key) } ?: return
        view.visibility = View.GONE
    }

    private fun dp(view: View, value: Float): Int {
        val density = view.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }
}
