package dev.ujhhgtg.wekit.features.api.ui

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.Collections
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

    fun getMsgInfoFromParam(param: HookParam): MessageInfo {
        val chattingDataAdapter = param.thisObject!!.reflekt()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get()!!
        val msgId = param.args[2] as Int
        val raw = chattingDataAdapter.reflekt()
            .firstMethod { name = "getItem" }
            .invoke(msgId)!!
        val msgInfo = MessageInfo(raw)
        // 诊断日志：确认 onBindView 参数与 getItem 取到的消息是否一致（头衔串排查用）。
        // 这里每条消息 bind 都会被调用（实测 1000+ 条/分钟），必须挂在「详细日志」开关后面：
        // 无条件打日志 = 滚动时每帧多一次字符串拼接 + 一次日志文件写入，是可见的掉帧来源。
        if (WeLogger.verboseEnabled) {
            runCatching {
                WeLogger.d(
                    TAG,
                    "bind args=${param.args.size} a0=${param.args[0]?.javaClass?.simpleName} " +
                        "a1=${param.args[1]?.javaClass?.simpleName} a2=${param.args[2]} " +
                        "getItem talker=${msgInfo.talker} sender=${msgInfo.sender} type=${msgInfo.typeCode}"
                )
            }
        }
        return msgInfo
    }
}
