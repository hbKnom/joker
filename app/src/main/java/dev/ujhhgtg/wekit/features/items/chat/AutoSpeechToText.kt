package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.collections.LruCache
import java.lang.reflect.InvocationTargetException

object AutoSpeechToText : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "自动语音转文字"
    override val nameRes = R.string.feature_auto_speech_to_text_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_speech_to_text_description

    private val processedMessages = LruCache<Long, Boolean>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.typeCode != MessageType.VOICE.code) return

        val id = msgInfo.id
        if (processedMessages[id] == true) {
            return
        }

        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)
        val apiMan = chattingContext.reflekt()
            .firstField {
                type = WeServiceApi.apiManagerClass
            }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, WeMessageApi.classTransformChattingComponent.clazz)
        val chatViewItem = api.reflekt()
            .firstMethod {
                parameters(Long::class)
                returnType { clazz ->
                    clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems")
                }
            }
            .invoke(id)

        if (chatViewItem.toString() != "NoTransform") return

        processedMessages[id] = true

        // Clear the unplayed red dot the same way WeChat does when the voice is listened to,
        // since we consume the message via transform instead of playback.
        runCatching { WeMessageApi.markVoicePlayed(msgInfo) }

        if (WeMessageApi.methodGetIsTransformed.method.invoke(msgInfo.instance) as Boolean) return
        try {
            // 8.0.79 起宿主给这条方法追加了形参：旧实现把 `parameters(MessageInfo, Boolean, Int, Int)`
            // 写死，于是匹配不到方法、整条路径静默失效（「自动语音转文字在 8.0.79 失效」的根因）。
            // 现在先按精确签名找旧形态，再退回「返回 void + 形参更多」的新形态，多出来的形参补 0。
            // 解析结果按宿主类缓存：`Class.methods` 每次调用都会复制一份方法数组，是每次转写都要付的开销。
            val target = msgInfo.instance
            val method = transcribeMethod(api, target)
            if (method != null) {
                val args = arrayOfNulls<Any?>(method.parameterCount)
                args[0] = target
                args[1] = false
                args[2] = -1
                args[3] = 0
                for (i in 4 until args.size) args[i] = 0
                method.isAccessible = true
                method.invoke(api, *args)
            }
        } catch (_: InvocationTargetException) {
            // WeChat throws `java.lang.NullPointerException: getImgPath(...) must not be null`,
            // but that's not what we should care about and doesn't affect functionality
        }
    }

    private class CachedTranscribeMethod(val owner: Class<*>, val method: java.lang.reflect.Method)

    @Volatile
    private var cachedTranscribeMethod: CachedTranscribeMethod? = null

    /**
     * 定位语音转文字入口方法。
     *
     * 精确形态（旧版）优先：`(MessageInfo, Boolean, Int, Int) -> void`。
     * 退而求其次（8.0.79 追加形参后的形态）：返回 void、首参吃 msgInfo、第 2/3/4 参是 boolean/int/int。
     * 两者都不匹配时返回 null —— 宁可不转写，也不去调用一个身份不明的方法。
     */
    private fun transcribeMethod(owner: Any, target: Any): java.lang.reflect.Method? {
        val ownerClass = owner.javaClass
        cachedTranscribeMethod?.let { if (it.owner === ownerClass) return it.method }
        val methods = ownerClass.methods
        fun matches(candidate: java.lang.reflect.Method): Boolean {
            if (candidate.returnType != Void::class.javaPrimitiveType) return false
            if (candidate.parameterCount < 4) return false
            val types = candidate.parameterTypes
            if (!types[0].isInstance(target)) return false
            if (types[1] != java.lang.Boolean.TYPE) return false
            if (types[2] != Integer.TYPE) return false
            return types[3] == Integer.TYPE
        }
        val resolved = methods.firstOrNull { it.parameterCount == 4 && matches(it) }
            ?: methods.firstOrNull { matches(it) }
            ?: return null
        resolved.isAccessible = true
        cachedTranscribeMethod = CachedTranscribeMethod(ownerClass, resolved)
        return resolved
    }
}
