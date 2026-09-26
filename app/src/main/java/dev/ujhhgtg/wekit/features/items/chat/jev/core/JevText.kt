package dev.ujhhgtg.wekit.features.items.chat.jev.core

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

/**
 * 注入进宿主聊天行的卡片要用的文案（三语，见 `res/values/strings_features_jev.xml`）。
 *
 * 为什么需要这一层：
 *  - 卡片是 WeKit 自己 new 出来的 View，它的 Context 是**宿主**的（微信），
 *    直接 `context.getString(R.string.jev_xxx)` 会在宿主的资源表里查 WeKit 的资源 id ——
 *    查不到就是 NotFoundException，查到了也是错的那一条。必须用
 *    [LocalizedContextFactory]（InjectedHost 模式：注入模块资源 + 跟随模块语言）取一次。
 *  - `LocalizedContextFactory.create()` 会新建 Configuration Context 并注入模块资源，
 *    **有成本**，绝不能在每次渲染/每帧去调（那正是本功能之前卡顿的老毛病）。
 *    这里按「模块语言」缓存一份结果，语言不变就永远复用。
 *
 * 取不到文案时返回空串而不是抛异常：卡片少一行字是可接受的降级，
 * 在宿主进程里抛异常会让整条消息的渲染挂掉。
 */
object JevText {

    private val lock = Any()
    private var cachedTag: String? = null
    private var cachedContext: Context? = null

    /** 只记一次失败原因，避免刷屏。 */
    private var reported = false

    private fun context(): Context? = synchronized(lock) {
        val locale = runCatching { WeKitLocaleController.resolvedLocale }.getOrNull() ?: return null
        val tag = locale.androidTag
        cachedContext?.let { if (cachedTag == tag) return it }
        val fresh = runCatching {
            LocalizedContextFactory.create(
                HostInfo.application,
                locale,
                LocaleResourceMode.InjectedHost,
            )
        }.getOrNull() ?: return null
        cachedContext = fresh
        cachedTag = tag
        fresh
    }

    /** 无格式化参数。**不要**走 `getString(id, *emptyArray())`：含 `%` 的文案会被 String.format 炸掉。 */
    fun get(@StringRes id: Int): String = runCatching {
        context()?.getString(id)
    }.getOrElse {
        report(it)
        null
    } ?: ""

    fun get(@StringRes id: Int, vararg args: Any): String = runCatching {
        context()?.getString(id, *args)
    }.getOrElse {
        report(it)
        null
    } ?: ""

    /** 面板/设置页这类本来就是模块自己的 Context，直接透传即可。 */
    fun of(context: Context, @StringRes id: Int, vararg args: Any): String = runCatching {
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }.getOrElse { "" }

    /** 供 [Resources] 缺失时的诊断：只在第一次失败时记一行。 */
    private fun report(error: Throwable) {
        if (reported) return
        reported = true
        MoodLog.w("潜语文案读取失败，卡片降级为空文案：${error.javaClass.simpleName} ${error.message}")
    }
}
