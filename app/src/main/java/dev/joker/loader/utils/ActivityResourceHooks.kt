package dev.joker.loader.utils

import android.app.Activity
import android.content.res.Resources
import dev.joker.utils.WeLogger
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「每个 Activity 的 Resources 刚建出来」这个时刻的分发点。
 *
 * 存在的理由：莫奈的运行时资源包是靠 `ResourcesLoader`（`ResourcesProvider.loadFromApk`）
 * 挂到**具体一个 `Resources` 实例**上的，谁没挂上，谁的取色/圆角/角标就还是原生样式。
 * 之前只在 `application.resources` 上挂一次，实机上用户看到的是「解析全部正常、
 * 包也 applied 了，但取色/圆角/角标一律没生效」—— 也就是说宿主 UI 真正在用的那批
 * `Resources` 并没有拿到这个 loader。
 *
 * 这里由 [ActivityProxy] 的 `ProxyInstrumentation.callActivityOnCreate` 在**所有** Activity
 * 创建时调用（宿主和我们自己的都包括），订阅方（莫奈引擎）自己做幂等与去重。
 *
 * ## 2026-09-27 修：订阅方来晚了怎么办
 *
 * 事件式分发只对「订阅之后创建」的 Activity 有效。莫奈的运行时包是**延后 20 s 才开始解析**的
 * （`MonetEngine.scheduleInitialResolve`），首屏的 `LauncherUI`/会话列表早在它 `applied`
 * 之前就建好了 —— 它们的 `Resources` 永远收不到回调，于是首屏一律原生，用户必须
 * 「去设置里关掉再打开」才看得到效果。所以这里同时记一份**存活 Activity 的弱引用**，
 * 订阅方在包就绪后可以调 [forEachLiveResources] 把之前错过的实例一次性补挂回来。
 *
 * 热路径注意：没订阅者时这里只有一次 `isEmpty()` 判断；`remember` 每建一个 Activity 才有
 * 一次弱引用分配，与 Activity 创建本身的开销相比可忽略。
 */
object ActivityResourceHooks {

    private const val TAG = "ActivityResourceHooks"

    private val callbacks = CopyOnWriteArrayList<(Activity) -> Unit>()

    /** 活着的 Activity（弱引用），用于「订阅方来晚了」时补挂。 */
    private val liveActivities = ArrayList<WeakReference<Activity>>()

    /** 记最近一次 Activity 事件的订阅者数量，判断要不要维护存活表。 */
    private var everSubscribed = false

    /** 订阅方通常是 `MonetEngine`（在它自己的 onEnable 里注册）。 */
    fun register(callback: (Activity) -> Unit) {
        synchronized(callbacks) {
            if (callbacks.none { it === callback }) callbacks.add(callback)
        }
        everSubscribed = true
    }

    /** 反注册（`MonetEngine.onDisable` 用）：否则用户每关开一次莫奈就多一个永久回调。 */
    fun unregister(callback: (Activity) -> Unit) {
        callbacks.remove(callback)
    }

    fun dispatch(activity: Activity) {
        remember(activity)
        if (callbacks.isEmpty()) return
        val resources = activity.resources ?: return
        callbacks.forEach { callback ->
            runCatching { callback(activity) }
                .onFailure { WeLogger.w(TAG, "activity resources callback failed", it) }
        }
    }

    /**
     * 对**当前还活着**的每个 Activity 的 `Resources` 执行一次 [action]（订阅方事后补挂用）。
     *
     * 顺带清理已被回收的弱引用。只在包就绪时调用一次，不在热路径上。
     */
    fun forEachLiveResources(action: (Activity) -> Unit) {
        val snapshot = synchronized(liveActivities) {
            liveActivities.removeAll { it.get() == null }
            liveActivities.mapNotNull { it.get() }
        }
        snapshot.forEach { activity ->
            runCatching { action(activity) }
                .onFailure { WeLogger.w(TAG, "live activity replay failed", it) }
        }
        WeLogger.i(TAG, "补挂存活 Activity 的运行时资源：本次重放 ${snapshot.size} 个实例")
    }

    private fun remember(activity: Activity) {
        if (!everSubscribed) return
        synchronized(liveActivities) {
            if (liveActivities.size > 256) {
                liveActivities.removeAll { it.get() == null }
            }
            liveActivities.add(WeakReference(activity))
        }
    }
}
