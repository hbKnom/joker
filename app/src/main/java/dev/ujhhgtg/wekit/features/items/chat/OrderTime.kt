package dev.ujhhgtg.wekit.features.items.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MultiSelectSupport
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.utils.OrderTimeIcon
import dev.ujhhgtg.wekit.ui.utils.ShowComposeDialogScope
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Schedule

private fun ShowComposeDialogScope.dismiss() = onDismiss()

/**
 * OrderTime —— 微信定时发送文本消息
 *
 * 由 WeKit Java 脚本 OrderTime 增强版（作者 Monk）复刻为原生 Kotlin Feature：
 *  - 长按消息菜单「定时发送」入口，自动预填当前聊天对象
 *  - 超时补发 / 间隔发送 / 重复任务（单次·每天·每隔N天）/ 多目标
 *  - 系统闹钟唤醒（微信被杀后由闹钟拉起进程，恢复并补发）
 *  - send_log 去重 + 本地日志
 *  UI 采用 WeKit 标准 AlertDialogContent + BaseWidget 风格。
 */
object OrderTime : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "定时发送"
    override val nameRes = R.string.feature_order_time_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_order_time_description

    private const val MENU_ID = 777267
    private const val PREF_KEY = "ordertime_tasks_v3"
    private const val TIMEOUT_CHECK_MS = 30L * 60L * 1000L
    private const val TAG = "OrderTime"
    private const val WEIXIN_PACKAGE = "com.tencent.mm"

    @Volatile
    private var busy = false

    private val handler = Handler(Looper.getMainLooper())
    private val tasks = LinkedHashMap<String, OrderTask>()
    private val runnables = HashMap<String, Runnable>()
    @Volatile
    private var checkerRunning = false

    // ---------------- 菜单入口 ----------------

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
        restoreAllTasks()
        startTimeoutChecker()
        writeLog("OrderTime 已启用，恢复 " + tasks.size + " 个任务")
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
        cancelAllTimers()
        saveAllTasks()
        checkerRunning = false
        writeLog("OrderTime 已停用")
    }

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ID,
            text = "定时",
            drawable = MenuIcons.res(R.drawable.ic_menu_timer),
            imageVector = MaterialSymbols.Outlined.Schedule,
            isSupported = { _ -> true },
            multiSelect = MultiSelectSupport.Unsupported,
            onClick = { view, _, msgInfo ->
                if (busy) {
                    showToast("请等待当前任务完成")
                } else {
                    showMainDialog(view, msgInfo.talker)
                }
            },
        )
    )

    // ---------------- 持久化 ----------------

    private fun loadTasksFromPrefs() {
        tasks.clear()
        val jsonStr = WePrefs.getString(PREF_KEY) ?: return
        if (jsonStr.isBlank()) return
        try {
            val root = JSONObject(jsonStr)
            val it = root.keys()
            while (it.hasNext()) {
                val id = it.next()
                val task = OrderTask.fromJson(root.optJSONObject(id) ?: continue)
                tasks[id] = task
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "loadTasks failed", e)
        }
    }

    private fun saveAllTasks() {
        try {
            val root = JSONObject()
            for ((id, task) in tasks) root.put(id, task.toJson())
            WePrefs.putString(PREF_KEY, root.toString())
        } catch (e: Exception) {
            WeLogger.e(TAG, "saveAllTasks failed", e)
        }
    }

    // ---------------- 日志 ----------------

    private fun logDir(): File {
        val dir = File(HostInfo.application.filesDir, "ordertime")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sendLogFile(): File = File(logDir(), "send_log.txt")

    private fun writeLog(msg: String) {
        WeLogger.i(TAG, msg)
        try {
            val f = File(logDir(), "order_time.log")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())
            FileOutputStream(f, true).use { it.write(("[$ts] $msg\n").toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            WeLogger.e(TAG, "writeLog failed", e)
        }
    }

    private fun recordSend(talker: String, taskId: String, result: String) {
        try {
            val line = System.currentTimeMillis().toString() + "," + talker + "," + result + ":" + taskId + "\n"
            FileOutputStream(sendLogFile(), true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            writeLog("记录发送日志失败: " + e.message)
        }
    }

    private fun isTaskSentForTime(taskId: String, planTime: Long): Boolean {
        try {
            val f = sendLogFile()
            if (!f.exists()) return false
            val threshold = planTime - 60000L
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!line.contains(taskId)) continue
                    val parts = line.split(",")
                    if (parts.size < 3) continue
                    val t = parts[0].toLongOrNull() ?: continue
                    if (t in threshold..(planTime + 60000L) && parts[2].contains("success")) return true
                }
            }
        } catch (e: Exception) {
            writeLog("检查发送日志异常: " + e.message)
        }
        return false
    }

    // ---------------- 时间工具 ----------------

    private fun calculatePlanTime(timeStr: String, sendOnTimeout: Boolean): Long {
        return try {
            val parts = timeStr.split(":")
            val h = parts[0].trim().toInt()
            val m = parts[1].trim().toInt()
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, m)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val today = cal.timeInMillis
            val now = System.currentTimeMillis()
            if (today <= now) {
                if (sendOnTimeout) now else today + 24L * 3600L * 1000L
            } else {
                today
            }
        } catch (e: Exception) {
            System.currentTimeMillis() + 60000L
        }
    }

    private fun calculateNextPlanTime(current: Long, repeatType: Int, intervalDays: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = current
        val now = System.currentTimeMillis()
        if (repeatType == 1) {
            while (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        } else if (repeatType == 3) {
            val step = if (intervalDays >= 1) intervalDays else 1
            while (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, step)
        } else {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    fun formatTime(ts: Long): String =
        if (ts <= 0) "未设置"
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date(ts))

    fun formatTimeHM(ts: Long): String =
        if (ts <= 0) "未设置"
        else java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(ts))

    // ---------------- 闹钟（唤醒微信进程） ----------------

    private fun setAlarmForTask(taskId: String, triggerTime: Long) {
        try {
            val ctx: Context = HostInfo.application
            val it = ctx.packageManager.getLaunchIntentForPackage(WEIXIN_PACKAGE) ?: run {
                writeLog("无法获取微信启动 Intent")
                return
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (android.os.Build.VERSION.SDK_INT >= 23) flags = flags or 0x02000000
            val pi = PendingIntent.getActivity(ctx, taskId.hashCode(), it, flags)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
                writeLog("获取 AlarmManager 失败")
                return
            }
            val at = maxOf(System.currentTimeMillis(), triggerTime - 15000L)
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else if (android.os.Build.VERSION.SDK_INT >= 19) {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
            writeLog("设置闹钟 taskId=" + taskId.take(8) + " 时间 " + formatTime(at))
        } catch (e: Exception) {
            writeLog("设置闹钟异常: " + e.message)
        }
    }

    private fun cancelAlarmForTask(taskId: String) {
        try {
            val ctx: Context = HostInfo.application
            val it = ctx.packageManager.getLaunchIntentForPackage(WEIXIN_PACKAGE) ?: return
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (android.os.Build.VERSION.SDK_INT >= 23) flags = flags or 0x02000000
            val pi = PendingIntent.getActivity(ctx, taskId.hashCode(), it, flags)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(pi)
        } catch (e: Exception) {
            writeLog("取消闹钟异常: " + e.message)
        }
    }

    // ---------------- 调度核心 ----------------

    private fun scheduleTask(task: OrderTask) {
        cancelTaskTimer(task.taskId)
        val delay = maxOf(0L, task.planTime - System.currentTimeMillis())
        val runnable = Runnable { executeTaskSend(task.taskId) }
        runnables[task.taskId] = runnable
        handler.postDelayed(runnable, delay)
        setAlarmForTask(task.taskId, task.planTime)
        writeLog("任务已调度: " + task.taskId.take(8) + " 延迟 " + delay + "ms")
    }

    private fun cancelTaskTimer(taskId: String) {
        runnables.remove(taskId)?.let { handler.removeCallbacks(it) }
        cancelAlarmForTask(taskId)
    }

    private fun cancelAllTimers() {
        for ((id, r) in runnables) handler.removeCallbacks(r)
        runnables.clear()
        val ids = tasks.keys.toList()
        for (id in ids) cancelAlarmForTask(id)
    }

    // ---------------- 执行发送 ----------------

    private fun executeTaskSend(taskId: String) {
        val task = tasks[taskId] ?: return
        val status = task.status
        if (status == "paused" || status == "completed" || status == "expired") return

        task.status = "running"
        saveAllTasks()

        Thread {
            try {
                var success = 0
                var fail = 0
                val targets = task.targets
                for ((i, talker) in targets.withIndex()) {
                    try {
                        val ok = WeMessageApi.sendText(talker, task.content)
                        if (ok) {
                            success++
                            recordSend(talker, taskId, "success")
                        } else {
                            fail++
                            recordSend(talker, taskId, "fail")
                        }
                    } catch (ex: Exception) {
                        fail++
                        recordSend(talker, taskId, "exception")
                        WeLogger.e(TAG, "send failed to $talker", ex)
                    }
                    if (i < targets.size - 1 && task.intervalSec > 0) {
                        Thread.sleep(task.intervalSec * 1000L)
                    }
                }

                val finalSuccess = success
                val finalFail = fail
                handler.post {
                    try {
                        val cur = tasks[taskId] ?: return@post
                        if (cur.repeatType > 0) {
                            val next = calculateNextPlanTime(cur.planTime, cur.repeatType, cur.repeatDayInterval)
                            cur.planTime = next
                            cur.status = "pending"
                            saveAllTasks()
                            scheduleTask(cur)
                        } else {
                            if (finalFail > 0 && finalSuccess == 0) {
                                val now = System.currentTimeMillis()
                                if (now - cur.planTime < TIMEOUT_CHECK_MS) {
                                    cur.retryCount++
                                    cur.status = "pending"
                                    saveAllTasks()
                                    handler.postDelayed({ executeTaskSend(taskId) }, 15000)
                                    writeLog("单次任务全部失败，15秒后重试: " + taskId.take(8))
                                    return@post
                                }
                            }
                            cur.status = "completed"
                            saveAllTasks()
                            cancelTaskTimer(taskId)
                            tasks.remove(taskId)
                            saveAllTasks()
                            writeLog("单次任务完成: " + taskId.take(8) + " 成功" + finalSuccess + " 失败" + finalFail)
                        }
                    } catch (e: Exception) {
                        writeLog("更新任务状态异常: " + e.message)
                    }
                }
            } catch (e: InterruptedException) {
                writeLog("发送线程被中断: " + taskId)
            } catch (e: Exception) {
                writeLog("发送线程异常: " + e.message)
                handler.post {
                    tasks[taskId]?.let {
                        it.status = "pending"
                        saveAllTasks()
                        handler.postDelayed({ scheduleTask(it) }, 30000)
                    }
                }
            }
        }.start()
    }

    // ---------------- 超时检测 ----------------

    private fun checkTimeoutTasks() {
        try {
            val now = System.currentTimeMillis()
            for ((id, task) in tasks) {
                if (task.status != "pending") continue
                val planTime = task.planTime
                if (planTime > 0 && (now - planTime) > TIMEOUT_CHECK_MS) {
                    if (task.sendOnTimeout) {
                        writeLog("超时补发: " + id.take(8))
                        executeTaskSend(id)
                    } else {
                        task.status = "expired"
                        saveAllTasks()
                    }
                }
            }
        } catch (e: Exception) {
            writeLog("超时检测异常: " + e.message)
        }
    }

    private fun startTimeoutChecker() {
        if (checkerRunning) return
        checkerRunning = true
        Thread {
            while (checkerRunning) {
                try {
                    Thread.sleep(60000)
                } catch (e: InterruptedException) {
                    break
                }
                checkTimeoutTasks()
            }
        }.start()
        writeLog("超时检测线程已启动")
    }

    // ---------------- 恢复任务 ----------------

    private fun restoreAllTasks() {
        loadTasksFromPrefs()
        if (tasks.isEmpty()) return
        val now = System.currentTimeMillis()
        val toAdd = LinkedHashMap<String, OrderTask>()
        toAdd.putAll(tasks)
        tasks.clear()
        for ((id, task) in toAdd) {
            if (task.taskId.isBlank()) task.taskId = id
            tasks[id] = task
            val planTime = task.planTime
            when (task.status) {
                "running" -> {
                    if (!isTaskSentForTime(id, planTime)) {
                        writeLog("恢复未完成任务 (running)，补发: " + id.take(8))
                        handler.postDelayed({ executeTaskSend(id) }, 3000)
                    } else {
                        task.status = "completed"
                        saveAllTasks()
                        writeLog("任务已在被杀前发送，标记完成: " + id.take(8))
                    }
                }
                "pending" -> {
                    if (planTime > now) {
                        scheduleTask(task)
                    } else {
                        if (task.repeatType > 0) {
                            val next = calculateNextPlanTime(planTime, task.repeatType, task.repeatDayInterval)
                            task.planTime = next
                            task.status = "pending"
                            scheduleTask(task)
                        } else {
                            if (task.sendOnTimeout && (now - planTime) < TIMEOUT_CHECK_MS) {
                                if (!isTaskSentForTime(id, planTime)) {
                                    handler.postDelayed({ executeTaskSend(id) }, 1000)
                                } else {
                                    task.status = "completed"
                                    saveAllTasks()
                                }
                            } else {
                                task.status = "expired"
                                saveAllTasks()
                            }
                        }
                    }
                }
                "paused" -> {
                    if (planTime > now) setAlarmForTask(id, planTime)
                }
            }
        }
        saveAllTasks()
        writeLog("恢复 " + tasks.size + " 个任务")
    }

    // ---------------- 对外操作（UI 调用） ----------------

    fun createOrUpdateTask(
        editTaskId: String?,
        content: String,
        timeStr: String,
        targetsStr: String,
        repeatType: Int,
        repeatDayInterval: Int,
        intervalSec: Long,
        sendOnTimeout: Boolean,
    ) {
        val planTime = calculatePlanTime(timeStr, sendOnTimeout)
        val id = editTaskId ?: UUID.randomUUID().toString()
        val targets = targetsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val task = OrderTask(
            taskId = id,
            content = content,
            targets = targets,
            planTime = planTime,
            repeatType = repeatType,
            repeatDayInterval = repeatDayInterval,
            intervalSec = intervalSec,
            sendOnTimeout = sendOnTimeout,
            status = "pending",
        )

        if (editTaskId != null) cancelTaskTimer(editTaskId)
        tasks[id] = task
        saveAllTasks()
        writeLog((if (editTaskId != null) "更新" else "创建") + "任务: " + id.take(8) + " 时间 " + formatTime(planTime))

        val delay = planTime - System.currentTimeMillis()
        if (delay <= 0) {
            if (sendOnTimeout) {
                handler.post { executeTaskSend(id) }
            } else {
                task.planTime = planTime + 24L * 3600L * 1000L
                task.status = "pending"
                saveAllTasks()
                scheduleTask(task)
            }
        } else {
            scheduleTask(task)
        }
    }

    fun togglePause(taskId: String) {
        val task = tasks[taskId] ?: return
        if (task.status == "paused") {
            task.status = "pending"
            if (task.planTime <= System.currentTimeMillis()) {
                if (task.repeatType > 0) {
                    task.planTime = calculateNextPlanTime(task.planTime, task.repeatType, task.repeatDayInterval)
                } else {
                    task.status = "expired"
                }
            }
            saveAllTasks()
            scheduleTask(task)
        } else if (task.status == "pending" || task.status == "running") {
            task.status = "paused"
            cancelTaskTimer(taskId)
            saveAllTasks()
        }
        writeLog("任务操作: $taskId -> ${task.status}")
    }

    fun executeNow(taskId: String) {
        val task = tasks[taskId] ?: return
        task.planTime = System.currentTimeMillis()
        task.status = "pending"
        saveAllTasks()
        executeTaskSend(taskId)
    }

    fun deleteTask(taskId: String) {
        cancelTaskTimer(taskId)
        tasks.remove(taskId)
        saveAllTasks()
        writeLog("删除任务: " + taskId.take(8))
    }

    fun getTask(taskId: String): OrderTask? = tasks[taskId]

    fun getTasks(): List<OrderTask> = tasks.values.sortedBy { it.planTime }

    // ---------------- UI 入口 ----------------

    private fun showMainDialog(view: View, talker: String) {
        busy = true
        try {
            showComposeDialog(view.context) {
                OrderTimeUi.MainContent(
                    onNew = {
                        dismiss()
                        showEditDialog(view, null, talker)
                    },
                    onList = {
                        dismiss()
                        showListDialog(view)
                    },
                    onClose = { dismiss() },
                )
            }
        } finally {
            busy = false
        }
    }

    private fun showEditDialog(view: View, editTaskId: String?, prefillTalker: String) {
        busy = true
        try {
            val editTask = editTaskId?.let { getTask(it) }
            showComposeDialog(view.context) {
                OrderTimeUi.EditContent(
                    editTask = editTask,
                    prefillTalker = prefillTalker,
                    onSave = { content, timeStr, targets, repeatType, intervalDays, intervalSec, sendOnTimeout ->
                        createOrUpdateTask(editTaskId, content, timeStr, targets, repeatType, intervalDays, intervalSec, sendOnTimeout)
                        dismiss()
                        if (editTaskId != null) {
                            showActionDialog(view, editTaskId)
                        } else {
                            showMainDialog(view, prefillTalker)
                        }
                    },
                    onBack = {
                        dismiss()
                        if (editTaskId != null) showActionDialog(view, editTaskId) else showMainDialog(view, prefillTalker)
                    },
                )
            }
        } finally {
            busy = false
        }
    }

    private fun showListDialog(view: View) {
        busy = true
        try {
            val list = getTasks()
            showComposeDialog(view.context) {
                OrderTimeUi.ListContent(
                    tasks = list,
                    onPick = { taskId ->
                        dismiss()
                        showActionDialog(view, taskId)
                    },
                    onBack = {
                        dismiss()
                        showMainDialog(view, "")
                    },
                )
            }
        } finally {
            busy = false
        }
    }

    private fun showActionDialog(view: View, taskId: String) {
        busy = true
        try {
            showComposeDialog(view.context) {
                OrderTimeUi.ActionContent(
                    task = getTask(taskId),
                    onEdit = {
                        dismiss()
                        showEditDialog(view, taskId, "")
                    },
                    onToggle = {
                        togglePause(taskId)
                        dismiss()
                        showListDialog(view)
                    },
                    onExecute = {
                        executeNow(taskId)
                        dismiss()
                        showListDialog(view)
                    },
                    onDelete = {
                        deleteTask(taskId)
                        dismiss()
                        showListDialog(view)
                    },
                    onBack = {
                        dismiss()
                        showListDialog(view)
                    },
                )
            }
        } finally {
            busy = false
        }
    }
}
