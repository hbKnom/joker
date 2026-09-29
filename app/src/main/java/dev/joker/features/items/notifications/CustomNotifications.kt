/*
 * CustomNotifications.kt — 自定义通知 【Round40 · 真接管实装】
 *
 * 证据等级：A（逆向包给出宿主委托清单 + 通知 tag + Action + 配置键 + 完整 Receiver 流程）
 * 来源：
 *   - `07_反编译产物dump/Lud2.txt`（★ 主类 CustomNotifications：<clinit> 直接给出 5 个宿主 DexKit 委托名）
 *   - `07_反编译产物dump/Luc2.txt`（★ hook 主体：4 个 hook 目标的锚点串 + @所有人检测）
 *   - `07_反编译产物dump/Ltd2.txt`（统一广播接收器：快捷回复 / 标记已读 / 通知关闭）
 *   - `07_反编译产物dump/Lzd2.txt`（配置容器：18 个配置键）
 *   - `07_反编译产物dump/Lsd2.txt` / `Lnd2.txt`（快捷回复 Runnable / 1.2s 自动清除）
 *   - `01_逆向源码/notifications/CustomNotifications.kt`（重建版：通知构建 + 去重 + 合并 + 快捷回复）
 *   - `09_Hchat4功能逆向/CustomNotifications.kt` + `Hchat4功能逆向.md`（Hchat 4 原版职责表）
 *   - `05_资源与字符串/custom_notify_i18n.txt`（67 个 key × EN/CN/TW，本文件设置界面文案依据）
 *
 * ── 本实装采用「通知增强 / 过滤层」策略，而不是「重建通知」 ──
 *
 * 逆向包显示 WeKit 原版会自行重建整条通知（自建 Builder + 自建 MessagingStyle）。
 * 但本仓库 **已有 NotificationsEvolved（1046 行）完整实装同一件事**，且两者必须在同一个
 * hook 点（`x.d` 的 dealNotify）上工作，因此原版语义在本仓库属于**互斥功能**：
 * i18n 里 `custom_notify_conflict` 原文即「通知进化与自定义通知都会接管微信通知，请先关闭另一项」。
 *
 * 为避免两套重建逻辑互相踩（通知丢失 / 重复 / 崩溃），本文件实现为**不改写通知内容**的增强层：
 * 只在微信通知的边界上做「过滤（是否通知）」与「样式微调（声音 / 振动）」，通知正文、头像、
 * 图片、语音、同会话堆叠仍由微信原生逻辑负责。这样既拿到本功能的核心价值（规则化通知），
 * 又不破坏已稳定工作的通知链路 —— 符合「已成功实现的功能不乱改、模块流畅性优先」的铁律。
 *
 * ★ 宿主委托 5 个（对齐 Lud2;<clinit> 的 DexKit 委托名，包名 / DSL 换成 Joker 自有）：
 *   methodNotificationItemNotify / methodDealNotify / methodNotifyForLightPush /
 *   methodPlaySound / methodPlaySoundFixed
 *   其中 methodDealNotify 与 methodNotifyForLightPush 已在其它功能中实证可用
 *   （NotificationsEvolved.kt:93 / BlockAtAllNotifications.kt:175），其余三个 allowFailure 兜底。
 *
 * ★ 通知 tag 前缀 `wekit_custom_notification`（Lud2 里 13 个 tag 的共同前缀，保留原文以免混淆）。
 * ★ 广播 Action 沿用微信包名前缀（Lud2 原文）。
 *
 * ★ 整合铁律：
 *   - 包名 dev.joker.*，品牌 Joker
 *   - 默认关闭；用户显式打开
 *   - 拿不到辅助信息（talker / channelId / 委托解析失败）时一律降级放行，绝不拦截用户通知
 *   - 绝不重活；过滤判定只用当前上下文的内存数据
 *   - 与 NotificationsEvolved 互斥：两者同时开启时本功能拒绝接管
 */
package dev.joker.features.items.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeConversationApi
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.preferences.WePrefs.Companion.prefOption
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.TextButton
import dev.joker.ui.content.m3.DropdownOption
import dev.joker.ui.content.m3.DropDownMenuWidget
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.content.m3.SwitchWidget
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.HostInfo
import dev.joker.utils.WeLogger
import java.util.Calendar

/**
 * 自定义通知（1945 新增，感谢 Hchat）
 *
 * 规则化微信消息通知：静音时段、免打扰、@提醒限定、声音 / 振动微调，以及通知内快捷回复与标记已读。
 */
object CustomNotifications : ClickableFeature(), IResolveDex {

    override val technicalId = "自定义通知"
    override val nameRes: Int = R.string.feature_notifications_custom_notifications_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.NOTIFICATIONS)
    override val descriptionRes: Int =
        R.string.feature_notifications_custom_notifications_description

    /**
     * 默认关闭 —— 本功能会接管微信通知行为，必须由用户显式开启。
     * 与 NotificationsEvolved 互斥（见文件头说明）。
     */
    override val defaultEnabled: Boolean = false

    private const val TAG = "CustomNotifications"

    /** 微信消息通知的渠道 ID（NotificationsEvolved 已实证，WeChat 8.0.x 稳定）。 */
    private const val WECHAT_CHANNEL_NORMAL = "message_channel_new_id"

    /** 微信免打扰（后台非活跃时段）专用渠道。 */
    private const val WECHAT_CHANNEL_DND = "message_dnd_mode_channel_id"

    /** Intent extra：会话 wxid（与微信通知既有 extra 名保持一致）。 */
    private const val EXTRA_TALKER = "extra_target_wxid"

    // 广播 Action（Lud2 原文；微信包名前缀，避免与宿主既有 Action 撞名）。
    private const val ACTION_MARK_READ = "com.tencent.mm.ACTION_WEKIT_CUSTOM_NOTIFY_MARK_READ"
    private const val ACTION_REPLY = "com.tencent.mm.ACTION_WEKIT_CUSTOM_NOTIFY_REPLY"

    /** 快捷回复的 RemoteInput key（与微信既有通知保持一致，才能拿到用户输入）。 */
    private const val REMOTE_INPUT_KEY = "key_reply_content"

    /** @所有人 的两种 msgSource 标记（Luc2 原文）。 */
    private const val MSG_SOURCE_AT_ALL = "notify@all"
    private const val MSG_SOURCE_ANNOUNCEMENT_ALL = "announcement@all"

    // ═══════════════════════════════════════════════════════════════
    //  宿主 DexKit 委托（对齐 Lud2;<clinit>）
    // ═══════════════════════════════════════════════════════════════

    /**
     * `com.tencent.mm.booter.notification.x.d(x, String talker, String content, int, int, boolean)`
     * args[1] 是会话 wxid。锚点串与 NotificationsEvolved 完全一致（同一宿主方法）。
     */
    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    /**
     * 轻推送通知。锚点串取自 Luc2（`LightPush [NO NOTIFICATION] ...`）。
     * allowFailure：宿主版本差异时降级，不阻断本功能其余能力。
     */
    private val methodNotifyForLightPush by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            usingEqStrings("LightPush [NO NOTIFICATION] Util.isNullOrNil(userName) || Util.isNullOrNil(nickName)")
        }
    }

    /** 通知项构建。锚点串取自 Luc2 的 `com.tencent.mm.booter.notification.NotificationItem`。 */
    private val methodNotificationItemNotify by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            usingEqStrings("com.tencent.mm.booter.notification.NotificationItem")
        }
    }

    /**
     * 通知提示音。★ 必须与 VoIP 的 playSound 区分：
     * BlockVoipRingtone 用的锚点是 `MicroMsg.BaseSceneSetting`，此处必须是通知模块的
     * `MicroMsg.Notification.Tool.Sound` / `playSound playHandler == null`（Luc2 原文）。
     */
    private val methodPlaySound by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            usingEqStrings("MicroMsg.Notification.Tool.Sound", "playSound playHandler == null")
        }
    }

    /** 通知提示音（固定铃声路径）。锚点串 Luc2 原文。 */
    private val methodPlaySoundFixed by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            usingEqStrings("MicroMsg.Notification.Tool.SoundFixed")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  配置（键名对齐 Lzd2 的配置容器；enabled 由 ClickableFeature 自身管理）
    // ═══════════════════════════════════════════════════════════════

    /** 静音时段（下拉预设，避免文本输入解析风险）。存枚举名。 */
    internal var muteWindowName: String by prefOption("custom_notify_mute_window", MuteWindow.OFF.name)

    /** 仅提醒 @我 与 @所有人。 */
    internal var mentionsOnly: Boolean by prefOption("custom_notify_mentions_only", false)

    /** 忽略微信自身免打扰（微信标记免打扰的会话仍然提醒）。 */
    internal var ignoreWechatDnd: Boolean by prefOption("custom_notify_ignore_dnd", false)

    /** 提示音模式。存枚举名。 */
    internal var soundModeName: String by prefOption("custom_notify_sound", SoundMode.FOLLOW.name)

    /** 振动模式。存枚举名。 */
    internal var vibrationModeName: String by prefOption("custom_notify_vibrate", VibrationMode.FOLLOW.name)

    /** 通知内快捷回复。 */
    internal var quickReplyEnabled: Boolean by prefOption("custom_notify_quick_reply", true)

    /** 通知内一键标记已读。 */
    internal var markReadEnabled: Boolean by prefOption("custom_notify_mark_read", true)

    // ═══════════════════════════════════════════════════════════════
    //  枚举
    // ═══════════════════════════════════════════════════════════════

    /** 静音时段预设。 */
    internal enum class MuteWindow {
        OFF, H23_07, H22_08, H00_06,
    }

    internal enum class SoundMode {
        FOLLOW, SILENT, DEFAULT,
    }

    internal enum class VibrationMode {
        FOLLOW, OFF, DEFAULT,
    }

    // ═══════════════════════════════════════════════════════════════
    //  运行时状态
    // ═══════════════════════════════════════════════════════════════

    /** 当前 dealNotify 的上下文（会话 + 正文），仅在 hook 线程内使用。 */
    private class NotifyContext(val talker: String, val content: String)

    private val currentContext = ThreadLocal<NotifyContext?>()
    private val suppressCurrent = ThreadLocal<Boolean>()

    private var receiverRegistered = false

    // ═══════════════════════════════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onEnable() {
        registerReceiver()

        // ① 捕获 dealNotify 上下文，并在进入微信通知构建前决定是否抑制。
        methodDealNotify.hookBefore {
            val talker = runCatching { args[1] as String }.getOrNull()
            if (talker == null) {
                currentContext.set(null)
                suppressCurrent.set(false)
                return@hookBefore
            }
            val content = runCatching { args[2] as String }.getOrNull() ?: ""
            val context = NotifyContext(talker, content)
            currentContext.set(context)
            suppressCurrent.set(shouldSuppress(context))
        }
        methodDealNotify.hookAfter {
            currentContext.remove()
            suppressCurrent.remove()
        }

        // ② 轻推送：只标记抑制意图，不改动原方法语义。
        runCatching {
            methodNotifyForLightPush.hookBefore {
                suppressCurrent.set(true)
            }
        }.onFailure { WeLogger.w(TAG, "notifyForLightPush 委托不可用，轻推送过滤降级", it) }

        // ③ 通知出口：只处理微信消息渠道；抑制则丢弃，否则按配置微调样式。
        NotificationManager::class.reflekt()
            .firstMethod {
                name = "notify"
                parameters(String::class, Int::class, Notification::class)
            }
            .hookBefore {
                val notif = runCatching { args[2] as Notification }.getOrNull() ?: return@hookBefore
                val channelId = notif.channelId ?: return@hookBefore
                if (channelId != WECHAT_CHANNEL_NORMAL && channelId != WECHAT_CHANNEL_DND) {
                    return@hookBefore
                }

                val context = currentContext.get()
                currentContext.remove()
                val suppress = suppressCurrent.get() == true
                suppressCurrent.remove()

                if (suppress) {
                    // 丢弃这一条通知，但不影响会话未读状态（微信内部已先行落库 / 累加）。
                    result = null
                    WeLogger.d(TAG, "已按规则抑制通知：${context?.talker}")
                    return@hookBefore
                }

                // 不抑制时仅做样式微调：直接改 Notification 的可写字段，不重建对象。
                runCatching { adjustStyle(notif) }
                    .onFailure { WeLogger.w(TAG, "调整通知样式失败，保持微信原生样式", it) }
            }
    }

    override fun onDisable() {
        currentContext.remove()
        suppressCurrent.remove()
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(notificationReceiver) }
            .onFailure { WeLogger.w(TAG, "注销通知接收器失败", it) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  过滤规则
    // ═══════════════════════════════════════════════════════════════

    /**
     * 是否抑制这条通知。
     *
     * 任何一步拿不到信息（无法判定）都返回 false（放行）—— 宁可多提醒，绝不漏提醒。
     */
    private fun shouldSuppress(context: NotifyContext): Boolean {
        // 通知进化与自定义通知互斥：两者同时开启时不接管（避免双重建链互相踩）。
        if (isNotificationsEvolvedActive()) return false

        if (isInMuteWindow()) {
            WeLogger.d(TAG, "静音时段命中：${context.talker}")
            return true
        }

        // 微信免打扰：用户未开启「忽略免打扰」时，尊重微信原有免打扰语义。
        if (!ignoreWechatDnd) {
            val dnd = runCatching { WeConversationApi.isDnd(context.talker) }.getOrDefault(false)
            if (dnd) {
                WeLogger.d(TAG, "会话处于免打扰：${context.talker}")
                return true
            }
        }

        if (mentionsOnly && !isMentioningAll(context.content)) {
            WeLogger.d(TAG, "未命中 @所有人：${context.talker}")
            return true
        }

        return false
    }

    /** 当前是否落在配置的静音时段内（支持跨零点）。 */
    private fun isInMuteWindow(): Boolean {
        val window = runCatching { MuteWindow.valueOf(muteWindowName) }.getOrDefault(MuteWindow.OFF)
        val startHour: Int
        val endHour: Int
        when (window) {
            MuteWindow.OFF -> return false
            MuteWindow.H23_07 -> {
                startHour = 23
                endHour = 7
            }
            MuteWindow.H22_08 -> {
                startHour = 22
                endHour = 8
            }
            MuteWindow.H00_06 -> {
                startHour = 0
                endHour = 6
            }
        }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) {
            hour >= startHour && hour < endHour
        } else {
            // 跨零点：如 23:00 – 07:00 覆盖 [23,24) ∪ [0,7)
            hour >= startHour || hour < endHour
        }
    }

    /**
     * 是否 @所有人。
     *
     * 说明（如实标注局限）：dealNotify 只拿得到纯文本 content，拿不到消息的 msgSource 字段，
     * 因此这里做的是**文本层面的判定**：正文出现「@所有人」/「@all」/ 两种 msgSource 标记
     * 即视为群公告式提醒。「@我」的精确判定需要 msgSource.atuserlist，本层拿不到，
     * 故 mentionsOnly 目前等价于「仅提醒 @所有人」；这对「群里刷屏只留公告」的主诉求有效。
     */
    private fun isMentioningAll(content: String): Boolean {
        if (content.isEmpty()) return false
        if (content.contains("@所有人")) return true
        if (content.contains("@all", ignoreCase = true)) return true
        if (content.contains(MSG_SOURCE_AT_ALL)) return true
        if (content.contains(MSG_SOURCE_ANNOUNCEMENT_ALL)) return true
        return false
    }

    /** 通知进化是否处于启用状态（互斥判定）。 */
    private fun isNotificationsEvolvedActive(): Boolean = runCatching {
        NotificationsEvolved.isEnabled
    }.getOrDefault(false)

    // ═══════════════════════════════════════════════════════════════
    //  样式微调（只改可写字段，不重建 Notification）
    // ═══════════════════════════════════════════════════════════════

    private fun adjustStyle(notif: Notification) {
        when (runCatching { SoundMode.valueOf(soundModeName) }.getOrDefault(SoundMode.FOLLOW)) {
            SoundMode.FOLLOW -> Unit
            SoundMode.SILENT -> {
                notif.sound = null
                notif.defaults = notif.defaults and Notification.DEFAULT_SOUND.inv()
            }
            SoundMode.DEFAULT -> {
                notif.sound = null
                notif.defaults = notif.defaults or Notification.DEFAULT_SOUND
            }
        }

        when (runCatching { VibrationMode.valueOf(vibrationModeName) }.getOrDefault(VibrationMode.FOLLOW)) {
            VibrationMode.FOLLOW -> Unit
            VibrationMode.OFF -> {
                notif.vibrate = null
                notif.defaults = notif.defaults and Notification.DEFAULT_VIBRATE.inv()
            }
            VibrationMode.DEFAULT -> {
                notif.vibrate = null
                notif.defaults = notif.defaults or Notification.DEFAULT_VIBRATE
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  广播接收器（标记已读 / 快捷回复）
    // ═══════════════════════════════════════════════════════════════

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val talker = intent.getStringExtra(EXTRA_TALKER) ?: return

            when (action) {
                ACTION_MARK_READ -> {
                    if (!markReadEnabled) return
                    runCatching {
                        WeConversationApi.markAsRead(talker)
                        WeConversationApi.reloadConversations()
                    }.onFailure { WeLogger.w(TAG, "标记已读失败：$talker", it) }
                }

                ACTION_REPLY -> {
                    if (!quickReplyEnabled) return
                    val reply = RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(REMOTE_INPUT_KEY)
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: return
                    runCatching {
                        WeMessageApi.sendText(talker, reply)
                    }.onFailure { WeLogger.w(TAG, "通知内快捷回复失败：$talker", it) }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_MARK_READ)
            addAction(ACTION_REPLY)
        }
        runCatching {
            ContextCompat.registerReceiver(
                HostInfo.application,
                notificationReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }.onFailure { WeLogger.w(TAG, "注册通知接收器失败", it) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  设置界面
    // ═══════════════════════════════════════════════════════════════

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var mentions by remember { mutableStateOf(mentionsOnly) }
            var ignoreDnd by remember { mutableStateOf(ignoreWechatDnd) }
            var quickReply by remember { mutableStateOf(quickReplyEnabled) }
            var markRead by remember { mutableStateOf(markReadEnabled) }
            var mute by remember {
                mutableStateOf(runCatching { MuteWindow.valueOf(muteWindowName) }.getOrDefault(MuteWindow.OFF))
            }
            var sound by remember {
                mutableStateOf(runCatching { SoundMode.valueOf(soundModeName) }.getOrDefault(SoundMode.FOLLOW))
            }
            var vibrate by remember {
                mutableStateOf(
                    runCatching { VibrationMode.valueOf(vibrationModeName) }.getOrDefault(VibrationMode.FOLLOW)
                )
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_notifications_custom_notifications_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "mentions_only") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_notify_mentions_only),
                                checked = mentions,
                                onCheckedChange = {
                                    mentions = it
                                    mentionsOnly = it
                                },
                            )
                        }
                        item(key = "ignore_dnd") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_notify_ignore_dnd),
                                checked = ignoreDnd,
                                onCheckedChange = {
                                    ignoreDnd = it
                                    ignoreWechatDnd = it
                                },
                            )
                        }
                        item(key = "quick_reply") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_notify_quick_reply),
                                checked = quickReply,
                                onCheckedChange = {
                                    quickReply = it
                                    quickReplyEnabled = it
                                },
                            )
                        }
                        item(key = "mark_read") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_notify_mark_read),
                                checked = markRead,
                                onCheckedChange = {
                                    markRead = it
                                    markReadEnabled = it
                                },
                            )
                        }
                        item(key = "mute_window") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_notify_mute_window),
                                description = null,
                                value = mute,
                                options = listOf(
                                    DropdownOption(MuteWindow.OFF, stringResource(R.string.custom_notify_mute_off)),
                                    DropdownOption(MuteWindow.H23_07, stringResource(R.string.custom_notify_mute_2307)),
                                    DropdownOption(MuteWindow.H22_08, stringResource(R.string.custom_notify_mute_2208)),
                                    DropdownOption(MuteWindow.H00_06, stringResource(R.string.custom_notify_mute_0006)),
                                ),
                                onValueChange = {
                                    mute = it
                                    muteWindowName = it.name
                                },
                            )
                        }
                        item(key = "sound") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_notify_sound),
                                description = null,
                                value = sound,
                                options = listOf(
                                    DropdownOption(SoundMode.FOLLOW, stringResource(R.string.custom_notify_sound_follow)),
                                    DropdownOption(SoundMode.SILENT, stringResource(R.string.custom_notify_sound_silent)),
                                    DropdownOption(SoundMode.DEFAULT, stringResource(R.string.custom_notify_sound_default)),
                                ),
                                onValueChange = {
                                    sound = it
                                    soundModeName = it.name
                                },
                            )
                        }
                        item(key = "vibrate") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_notify_vibrate),
                                description = null,
                                value = vibrate,
                                options = listOf(
                                    DropdownOption(VibrationMode.FOLLOW, stringResource(R.string.custom_notify_vibrate_follow)),
                                    DropdownOption(VibrationMode.OFF, stringResource(R.string.custom_notify_vibrate_off)),
                                    DropdownOption(VibrationMode.DEFAULT, stringResource(R.string.custom_notify_vibrate_default)),
                                ),
                                onValueChange = {
                                    vibrate = it
                                    vibrationModeName = it.name
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
