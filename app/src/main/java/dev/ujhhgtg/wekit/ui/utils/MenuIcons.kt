package dev.ujhhgtg.wekit.ui.utils

import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import dev.ujhhgtg.wekit.utils.HostInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * 从 res/drawable-nodpi 加载菜单图标的轻量缓存。
 *
 * 菜单项的原生 drawable 统一走这里的 PNG 资源（用户提供的脚本 icon 原图），
 * 避免每次构建菜单都重新 decode 位图。
 */
object MenuIcons {
    private val cache = ConcurrentHashMap<Int, Drawable>()

    fun res(resId: Int): Drawable = cache.getOrPut(resId) {
        AppCompatResources.getDrawable(HostInfo.application, resId)!!
    }
}
