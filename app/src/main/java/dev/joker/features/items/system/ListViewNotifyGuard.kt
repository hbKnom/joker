package dev.joker.features.items.system

import android.widget.AbsListView
import android.widget.ListView
import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.reflekt.reflekt
import dev.joker.utils.WeLogger

/**
 * 列表闪退防护：把「宿主的 ListView 数据变了但没收到 notifyDataSetChanged」拉回一致。
 *
 * 用户实机崩溃日志（2026-09-27 17:49:43，微信 8.0.72，宿主堆 206 线程）：
 *
 * ```
 * java.lang.IllegalStateException: The content of the adapter has changed but ListView
 * did not receive a notification. Make sure the content of your adapter is not modified
 * from a background thread, but only from the UI thread. Make sure your adapter calls
 * notifyDataSetChanged() when its content changes.
 * [in ListView(-1, class o95.y3) with Adapter(class com.tencent.mm.ui.ng)]
 *     at android.widget.ListView.layoutChildren(ListView.java:1715)
 *     at android.widget.AbsListView.onTouchUp(AbsListView.java:3577)
 * ```
 *
 * 抛出点是 `ListView.layoutChildren()` 的自检 `mItemCount != mAdapter.getCount()`。
 * `mItemCount` 只在 `AbsListView.onLayout`（经由 `handleDataChanged`）里同步，而
 * **`onTouchUp` 里的补布局路径不经过那一步** —— 只要「adapter 数据变更」与「通知」
 * 之间被一次抬手打断，宿主自己就会把整个进程带走。
 *
 * 注入模块无法保证宿主每个 adapter 都守规矩（我们自己也确实会往菜单 / 会话列表里
 * 插项），所以这里做一层**零副作用兜底**：进入 `layoutChildren` 之前，若
 * `mItemCount != adapter.count`，就把 `mItemCount` 对齐到真实值。
 *
 * 为什么这样是安全的：
 *  * 只在**已经失步**时才写字段；正常情况下每次调用只多一次 `getInt` + 一次
 *    `getCount()`，不改变任何布局行为；
 *  * 对齐后 `layoutChildren` 会按 adapter 的真实 count 重建子 View，用户看到的
 *    仍然是正确数据（不显示旧数据、不跳项）；
 *  * 不碰 `mDataChanged`、不 addView、不调用宿主任何回调 —— 铁律「绝不往宿主
 *    列表 addView」不受影响，这里改的只是框架自己缓存的计数。
 */
object ListViewNotifyGuard : SwitchFeature() {

    override val technicalId = "列表数据同步防护"
    override val nameRes = R.string.feature_list_view_notify_guard_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_list_view_notify_guard_description
    override val defaultEnabled = true

    private const val TAG = "ListViewNotifyGuard"

    /** `AdapterView.mItemCount`：沿父类链找到的声明处字段。 */
    @Volatile
    private var itemCountField: java.lang.reflect.Field? = null

    /**
     * 沿父类链找 `mItemCount` 的**声明处**。
     *
     * 【Round43 实机修复】旧实现写的是 `AbsListView::class.java.getDeclaredField("mItemCount")`，
     * 而 `getDeclaredField` **只查本类声明的成员、不查父类**；这个字段实际声明在
     * `android.widget.AdapterView`（package-private，配合 `getCount()` 使用），
     * 于是每次都在这里抛 `NoSuchFieldException` —— 实机日志里那条
     * `AbsListView.mItemCount not found, guard disabled` 就是把整个防护关掉的元凶。
     *
     * 现在逐级向上找，命中处即为声明类；同时按级 `setAccessible(true)`，
     * 顺带绕开「按非 SDK 类名签名直接取成员」在部分 ROM 上被拦成 `NoSuchFieldException` 的情况。
     */
    private fun findItemCountField(): java.lang.reflect.Field? {
        var cls: Class<*>? = AbsListView::class.java
        while (cls != null && cls != Any::class.java) {
            val found = runCatching { cls.getDeclaredField("mItemCount") }.getOrNull()
            if (found != null) {
                runCatching { found.isAccessible = true }
                return found
            }
            cls = cls.superclass
        }
        return null
    }

    /** 只在第一次成功纠正时打一条日志，之后静默 —— 这是每帧都可能命中的热路径 */
    @Volatile
    private var loggedFirstResync = false

    override fun onEnable() {
        val field = findItemCountField()
        if (field == null) {
            // 不再只说一句 "not found" 就静默禁用：把查找过的类名打出来，下次能从日志直接定位。
            WeLogger.e(
                TAG,
                "mItemCount not found on AbsListView/AdapterView chain, guard disabled",
            )
            return
        }
        itemCountField = field
        WeLogger.i(TAG, "mItemCount resolved on ${field.declaringClass.name}")

        runCatching {
            ListView::class.reflekt()
                .firstMethod { name = "layoutChildren" }
                .hookBefore {
                    val listView = thisObject as? ListView ?: return@hookBefore
                    val adapter = listView.adapter ?: return@hookBefore
                    // 整段都在 runCatching 里：兜底自身绝不允许成为新的崩溃源
                    runCatching {
                        val cached = field.getInt(listView)
                        val live = adapter.count
                        if (cached == live) return@runCatching
                        field.setInt(listView, live)
                        if (!loggedFirstResync || WeLogger.verboseEnabled) {
                            loggedFirstResync = true
                            WeLogger.i(
                                TAG,
                                "resynced mItemCount $cached -> $live for ${adapter.javaClass.name}",
                            )
                        }
                    }
                }
            WeLogger.i(TAG, "ListView.layoutChildren guard installed")
        }.onFailure {
            WeLogger.e(TAG, "failed to install ListView.layoutChildren guard", it)
        }
    }

    override fun onDisable() {
        itemCountField = null
        loggedFirstResync = false
    }
}
