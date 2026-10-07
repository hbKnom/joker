package dev.joker.features.api.net

import dev.joker.constants.Preferences
import dev.joker.features.api.net.abc.IWePacketInterceptor
import dev.joker.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

object WePacketManager {

    private val listeners = CopyOnWriteArrayList<IWePacketInterceptor>()

    fun addInterceptor(interceptor: IWePacketInterceptor) = listeners.addIfAbsent(interceptor)

    fun removeInterceptor(interceptor: IWePacketInterceptor) = listeners.remove(interceptor)

    fun hasInterceptors(): Boolean = listeners.isNotEmpty()

    fun handleRequestTamper(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (Preferences.verboseLog) {
            // 【第 52 轮】日志瘦身：①去掉 `Stack=`（每个请求一条完整调用栈，实机 2MB 日志里
            // 699 行都是它，用户看日志时误以为满屏报错）；②`Data=` 里是**消息正文**
            // （隐私），只有打开「日志正文转储」才落盘 —— 与 WeDatabaseListenerApi 同一口径。
            val body = if (WeLogger.bodyDumpEnabled) {
                ", Data=${WeProtoData.fromBytes(reqBytes).toJsonObject()}"
            } else {
                ", Data=未转储（如需全文请打开「日志正文转储」）"
            }
            WeLogger.logChunkedI("WePacketInterceptor.Request", "Request: $uri, CGI=$cgiId, LEN=${reqBytes.size}$body")
        }

        for (listener in listeners) {
            val tampered = listener.onRequest(uri, cgiId, reqBytes)
            if (tampered != null) return tampered
        }
        return null
    }

    fun handleResponseTamper(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (Preferences.verboseLog) {
            val body = if (WeLogger.bodyDumpEnabled) {
                ", Data=${WeProtoData.fromBytes(respBytes).toJsonObject()}"
            } else {
                ", Data=未转储（如需全文请打开「日志正文转储」）"
            }
            WeLogger.logChunkedI(
                "WePacketInterceptor.Response",
                "Response: $uri, CGI=$cgiId, LEN=${respBytes.size}$body",
            )
        }
        for (listener in listeners) {
            val tampered = listener.onResponse(uri, cgiId, respBytes)
            if (tampered != null) return tampered
        }
        return null
    }
}
