package dev.ujhhgtg.wekit.loader.utils

import android.content.res.Resources
import dev.ujhhgtg.wekit.utils.WeLogger
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
 * 热路径注意：没订阅者时这里只有一次 `isEmpty()` 判断，不做任何反射/日志。
 */
object ActivityResourceHooks {

    private const val TAG = "ActivityResourceHooks"

    private val callbacks = CopyOnWriteArrayList<(Resources) -> Unit>()

    /** 订阅方通常是 `MonetEngine`（在它自己的 onEnable 里注册）。 */
    fun register(callback: (Resources) -> Unit) {
        callbacks.add(callback)
    }

    fun dispatch(resources: Resources) {
        if (callbacks.isEmpty()) return
        callbacks.forEach { callback ->
            runCatching { callback(resources) }
                .onFailure { WeLogger.w(TAG, "activity resources callback failed", it) }
        }
    }
}
