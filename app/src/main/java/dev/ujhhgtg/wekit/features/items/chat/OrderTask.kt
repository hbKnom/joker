package dev.ujhhgtg.wekit.features.items.chat

import org.json.JSONArray
import org.json.JSONObject

/** OrderTime 定时任务模型（与 Java 脚本持久化格式兼容） */
data class OrderTask(
    var taskId: String = "",
    val content: String = "",
    val targets: List<String> = emptyList(),
    var planTime: Long = 0L,
    var repeatType: Int = 0,        // 0 单次 / 1 每天 / 3 每隔N天
    var repeatDayInterval: Int = 1,
    var intervalSec: Long = 0L,     // 多目标发送间隔（秒）
    var sendOnTimeout: Boolean = true,
    var status: String = "pending", // pending / running / paused / completed / expired
    var retryCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("taskId", taskId)
        put("type", 0)
        put("content", content)
        val arr = JSONArray()
        for (t in targets) arr.put(t)
        put("targets", arr)
        put("planTime", planTime)
        put("repeatType", repeatType)
        put("repeatDayInterval", repeatDayInterval)
        put("repeatDays", JSONArray())
        put("interval", intervalSec)
        put("sendOnTimeout", sendOnTimeout)
        put("status", status)
        put("retryCount", retryCount)
    }

    companion object {
        fun fromJson(o: JSONObject): OrderTask {
            val arr = o.optJSONArray("targets") ?: JSONArray()
            val targets = buildList {
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
            return OrderTask(
                taskId = o.optString("taskId", ""),
                content = o.optString("content", ""),
                targets = targets,
                planTime = o.optLong("planTime", 0L),
                repeatType = o.optInt("repeatType", 0),
                repeatDayInterval = o.optInt("repeatDayInterval", 1),
                intervalSec = o.optLong("interval", 0L),
                sendOnTimeout = o.optBoolean("sendOnTimeout", true),
                status = o.optString("status", "pending"),
                retryCount = o.optInt("retryCount", 0),
            )
        }

        fun statusText(status: String): String = when (status) {
            "pending" -> "待发送"
            "running" -> "发送中"
            "paused" -> "已暂停"
            "completed" -> "已完成"
            "expired" -> "已过期"
            else -> status
        }

        fun repeatText(task: OrderTask): String = when (task.repeatType) {
            1 -> "每天"
            3 -> "每隔${task.repeatDayInterval}天"
            else -> "单次"
        }
    }
}
