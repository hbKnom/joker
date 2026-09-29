package dev.joker.features.api.ui

import android.view.View
import dev.joker.reflekt.reflekt
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.api.core.models.MessageInfo
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.HookParam
import dev.joker.utils.WeLogger
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

object WeChatMessageViewApi : ApiFeature(), IResolveDex {

    override val technicalId = "消息 View 创建监听服务"
    override val nameRes = R.string.feature_we_chat_message_view_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_chat_message_view_api_description

    fun interface ICreateViewListener {
        fun onCreateView(
            param: HookParam, view: View
        )
    }

    interface IMessageViewLifecycleListener {
        fun onMessageViewAttached(view: View, message: MessageInfo) {}

        /**
         * 行与消息解绑。
         *
         * [rebound] 区分两种本质不同的事件，**只能在派发点判断**：
         *  - `true`：同一个 View 被绑到了另一条消息（发送消息、批量刷新、会话去重都会这样），
         *    这一行并没有消失，只是换了内容；
         *  - `false`：这一行真的离开了窗口（删除、整页销毁、回收前的 detach）。
         *
         * 以前两种事件共用一个无参回调，下游只能靠「view.parent 还在不在」猜 ——
         * 而真实 detach 时 parent 往往也还在（先 dispatchDetachedFromWindow 再 removeFromArray），
         * 于是「误判成删除」和「漏判真删除」同时存在。现在由这里给出确定答案。
         */
        fun onMessageViewDetached(view: View, message: MessageInfo, rebound: Boolean) {}
        fun onMessageViewRecycled(view: View, message: MessageInfo) {}
    }

    private val listeners = CopyOnWriteArrayList<ICreateViewListener>()
    private val lifecycleListeners = CopyOnWriteArrayList<IMessageViewLifecycleListener>()
    private val currentBindings =
        Collections.synchronizedMap(WeakHashMap<View, MessageInfo>())
    private val attachStateListeners =
        Collections.synchronizedMap(WeakHashMap<View, View.OnAttachStateChangeListener>())

    fun addListener(listener: ICreateViewListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ICreateViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}"
        )
    }

    fun addLifecycleListener(listener: IMessageViewLifecycleListener) {
        if (!lifecycleListeners.contains(listener)) {
            lifecycleListeners.add(listener)
        }
    }

    fun removeLifecycleListener(listener: IMessageViewLifecycleListener) {
        val removed = lifecycleListeners.remove(listener)
        WeLogger.i(
            TAG,
            "lifecycle listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${lifecycleListeners.size}"
        )
    }

    private const val TAG = "WeChatMessageViewApi"

    /**
     * 行 View 上「当前绑定的是哪条消息」的键控标记（`View.setTag(key, value)`）。
     *
     * 为什么要放在这里统一写：聊天列表在**发送、重排、去重**时会重新绑定同一批 View
     * （`onBindView` 会把同一个 View 绑到另一条消息上，先回调一次 Detached 再回调 Attached）。
     * 下游功能（消息删除动画）必须能区分「这一行真的被删了」和「同一个 View 换了消息」——
     * 判据就是这条标记；标记由绑定点统一写入，避免每个功能各写一套、或者像以前那样
     * 只读不写导致判据永久为 null。
     *
     * 取值为 [MessageInfo.id]（本地 msgId，> 0 时唯一）；本地暂态消息（msgId 还没落库）没有
     * 稳定 id，写入消息对象本身，仅用于同对象身份比较。
     */
    const val ROW_TAG_MESSAGE_KEY = 2113929222

    private fun rowTagValue(message: MessageInfo): Any =
        runCatching { message.id }.getOrNull()?.takeIf { it > 0L } ?: message.instance

    private val methodChatItemOnBindView by dexMethod {
        matcher {
            usingStrings(
                "MicroMsg.MvvmChattingItem",
                "[onBindView]"
            )
        }
    }

    private val methodChatItemOnViewRecycled by dexMethod {
        matcher {
            usingStrings("rvnotify-test-onViewRecycled viewType=")
        }
    }

    override fun onEnable() {
        methodChatItemOnBindView.hookAfter {
            val holder = args[0]!!
            val view = holder.reflekt()
                .firstField {
                    type = View::class
                    superclass()
                }
                .get()!! as View
            val message = getMsgInfoFromParam(this)
            ensureAttachStateListener(view)

            val previous = synchronized(currentBindings) { currentBindings[view] }
            val bindingChanged = previous?.instance !== message.instance
            if (view.isAttachedToWindow && bindingChanged && previous != null) {
                // rebound = true：这一行还在列表里，只是被绑到了另一条消息（不是删除）
                dispatchLifecycle { it.onMessageViewDetached(view, previous, true) }
            }
            synchronized(currentBindings) {
                currentBindings[view] = message
            }
            // 先更新标记再派发 Attached：下游在 Attached 回调里就能读到本条消息的身份。
            runCatching { view.setTag(ROW_TAG_MESSAGE_KEY, rowTagValue(message)) }
            if (view.isAttachedToWindow && bindingChanged) {
                dispatchLifecycle { it.onMessageViewAttached(view, message) }
            }

            for (listener in listeners) {
                try {
                    listener.onCreateView(this, view)
                } catch (ex: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
                }
            }
        }

        methodChatItemOnViewRecycled.hookBefore {
            val holder = args[0]!!
            val view = holder.reflekt()
                .firstField {
                    type = View::class
                    superclass()
                }
                .get()!! as View
            val message = synchronized(currentBindings) { currentBindings[view] } ?: return@hookBefore
            dispatchLifecycle { it.onMessageViewRecycled(view, message) }
            synchronized(currentBindings) {
                currentBindings.remove(view)
            }
        }
    }

    private fun ensureAttachStateListener(view: View) {
        synchronized(attachStateListeners) {
            if (attachStateListeners.containsKey(view)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    dispatchLifecycle { it.onMessageViewAttached(view, message) }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    // rebound = false：这一行真的离开了窗口（删除 / 整页销毁 / 回收）
                    dispatchLifecycle { it.onMessageViewDetached(view, message, false) }
                }
            }
            attachStateListeners[view] = listener
            view.addOnAttachStateChangeListener(listener)
        }
    }

    private fun dispatchLifecycle(callback: (IMessageViewLifecycleListener) -> Unit) {
        for (listener in lifecycleListeners) {
            try {
                callback(listener)
            } catch (ex: Exception) {
                WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
            }
        }
    }

    fun getChattingContextFromParam(param: HookParam): Any {
        return param.thisObject!!.reflekt()
            .firstField { type = WeMessageApi.classChattingContext.clazz }
            .get()!!
    }

    /** Returns every (view, message) pair currently bound whose message satisfies [matcher]. */
    fun findBoundViews(matcher: (MessageInfo) -> Boolean): List<Pair<View, MessageInfo>> {
        synchronized(currentBindings) {
            return currentBindings.entries
                .filter { matcher(it.value) }
                .map { it.key to it.value }
        }
    }

    fun getBoundMessage(view: View): MessageInfo? =
        synchronized(currentBindings) { currentBindings[view] }

    /**
     * 「宿主行容器 → 聊天数据 adapter」的字段，按宿主类缓存。
     *
     * 【2026-09-27 修卡顿】[getMsgInfoFromParam] 在**每一条消息 bind**时都会执行（实机 1000+
     * 条/分钟），而它原本每次都做两次 `reflekt()` 查找（`firstField` 扫字段 + `firstMethod`
     * 扫方法）后再 invoke。改成按宿主类缓存**已解析好的 `java.lang.reflect.Field`/`Method`**
     * （`isAccessible` 只设一次），热路径上就只剩一次 `Field.get` + 一次 `Method.invoke`。
     * 用「按类缓存」而不是「单例缓存」：宿主在不同消息类型下可能用不同的 adapter 实现类，
     * 按类缓存天然覆盖这种情况，也不会因为某个类被替换而串味。
     */
    private val adapterFieldByHolder = ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()
    private val getItemMethodByAdapter = ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()

    private fun chattingDataAdapterFieldOf(holder: Any): java.lang.reflect.Field =
        adapterFieldByHolder[holder.javaClass] ?: run {
            val resolved = holder.reflekt()
                .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
                .self
                .also { it.isAccessible = true }
            adapterFieldByHolder.putIfAbsent(holder.javaClass, resolved) ?: resolved
        }

    private fun getItemMethodOf(adapter: Any): java.lang.reflect.Method =
        getItemMethodByAdapter[adapter.javaClass] ?: run {
            val resolved = adapter.reflekt()
                .firstMethod { name = "getItem" }
                .self
                .also { it.isAccessible = true }
            getItemMethodByAdapter.putIfAbsent(adapter.javaClass, resolved) ?: resolved
        }

    // 注意：这是个 standalone object，**不能**再包一层 `companion object`（第 47 轮踩坑）：
    //   ① Kotlin 直接报 "Modifier 'companion' is not applicable inside 'standalone object'"；
    //   ② KSP 生成的 `FeaturesProvider` 会去访问 `WeChatMessageViewApi.Companion`，连带报
    //      "Cannot access 'companion object Companion': it is private"。
    // 常量与可变字段**直接写在 object 体里**即可（`const val` 在 object 内是合法的）。
    // 同一条坑 2026-09-23 在 `QqMusicOrder` 上已经吃过一次。

    /** bind 日志限流：1 条/秒。 */
    private const val BIND_LOG_INTERVAL_MILLIS = 1000L

    @Volatile private var lastBindLogAt = 0L
    @Volatile private var bindLogSuppressed = 0

    fun getMsgInfoFromParam(param: HookParam): MessageInfo {
        val holder = param.thisObject!!
        val chattingDataAdapter = chattingDataAdapterFieldOf(holder).get(holder)!!
        val msgId = param.args[2] as Int
        val raw = getItemMethodOf(chattingDataAdapter).invoke(chattingDataAdapter, msgId)!!
        val msgInfo = MessageInfo(raw)
        // 诊断日志：确认 onBindView 参数与 getItem 取到的消息是否一致（头衔串排查用）。
        // 这里每条消息 bind 都会被调用（实测 1000+ 条/分钟），必须挂在「详细日志」开关后面：
        // 无条件打日志 = 滚动时每帧多一次字符串拼接 + 一次日志文件写入，是可见的掉帧来源。
        if (WeLogger.verboseEnabled) {
            // 【第 47 轮】这条日志每条消息 bind 都会走到（实测 1000+ 条/分钟）。
            // 详细日志打开时它是滚动掉帧 + 日志文件暴涨的主因之一，因此限流到 1 条/秒，
            // 其余同类折叠计数（诊断所需的「bind 参数是否与 getItem 一致」结论不受影响）。
            val now = System.currentTimeMillis()
            if (now - lastBindLogAt >= BIND_LOG_INTERVAL_MILLIS) {
                lastBindLogAt = now
                val suppressed = bindLogSuppressed
                bindLogSuppressed = 0
                runCatching {
                    WeLogger.d(
                        TAG,
                        "bind args=${param.args.size} a0=${param.args[0]?.javaClass?.simpleName} " +
                            "a1=${param.args[1]?.javaClass?.simpleName} a2=${param.args[2]} " +
                            "getItem talker=${msgInfo.talker} sender=${msgInfo.sender} type=${msgInfo.typeCode}" +
                            if (suppressed > 0) " (已折叠 $suppressed 条同类)" else ""
                    )
                }
            } else {
                bindLogSuppressed++
            }
        }
        return msgInfo
    }
}
