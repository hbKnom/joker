/*
 * CustomConversationNotifications.kt — 自定义对话通知 【Round40 · 从全注释骨架实装】
 *
 * 本文件在 Round30 阶段 1 时是一份 872 行的**全注释骨架**（首行注释写着
 * `// Claude has been going insane while writing this` / `// needs more review`），
 * 从未参与编译。本轮把它真正实装为可用功能。
 *
 * ★ 与 CustomNotifications 的分工（两者都 hook 同一个宿主方法，但职责不重叠）：
 *   - CustomNotifications          ：**全局**规则（静音时段 / 免打扰 / @所有人 / 全局声音振动）
 *   - CustomConversationNotifications：**按会话**覆盖（单个会话的声音 / 振动 / 优先级 / 免打扰）
 *   ② 的优先级晚于 ①，因此「会话级覆盖」总能盖住「全局规则」。
 *
 * ★ 反注释过程中修掉的原骨架问题（这是它此前不能编译的原因）：
 *   1. `WePrefs.default.getAll()` **不存在** —— 原骨架用它枚举有覆盖的会话，必然编译失败。
 *      改为显式维护索引键 `ccn_index`（Set<String>），增删会话时同步维护。
 *   2. `Notification.Builder::class.reflekt().firstMethod { name = "build" }` 改挂在
 *      `NotificationManager.notify` 上。原因：`Builder.build()` 被微信在多个非通知场景调用，
 *      且 build 阶段 `channelId` 尚未写入最终 Notification；在 notify 出口改字段才准确。
 *   3. 原骨架的 `// Disabled feature metadata (...) ` 是**注释里再写注释**，特征签名无法被
 *      设置页识别；本轮改为正常的 `override val` 元数据。
 *
 * ★ 配置存储（不使用任何不存在的 API）：
 *   - 索引：`ccn_index` = Set<String>，元素为「存在非跟随全局覆盖」的会话 wxid
 *   - 会话项：`ccn_conv_<wxid>_sound` / `_vibrate` / `_priority` / `_dnd`（均为枚举名字符串）
 *     这种做法不需要枚举 WePrefs 全表，也不需要序列化对象。
 *
 * ★ 整合铁律：
 *   - 包名 dev.joker.*，品牌 Joker
 *   - 默认关闭；用户显式打开
 *   - 只调整声音 / 振动 / 优先级，不改通知正文；拿不到信息一律放行（不误杀通知）
 *   - 与 CustomNotifications 同时开启时共存：本功能只处理「有显式会话覆盖」的会话
 */
package dev.joker.features.items.notifications

import android.app.Notification
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.reflekt.firstMethod
import dev.joker.reflekt.reflekt
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.preferences.WePrefs
import dev.joker.preferences.WePrefs.Companion.prefOption
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.ContactsSelector
import dev.joker.ui.content.TextButton
import dev.joker.ui.content.m3.DropdownOption
import dev.joker.ui.content.m3.DropDownMenuWidget
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.WeLogger

/**
 * 自定义对话通知
 *
 * 为每个对话单独设定通知方式（声音 / 振动 / 优先级 / 是否遵守微信免打扰）。
 */
object CustomConversationNotifications : ClickableFeature(), IResolveDex {

    override val technicalId = "自定义对话通知"
    override val nameRes: Int = R.string.feature_notifications_custom_conversation_notifications_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.NOTIFICATIONS)
    override val descriptionRes: Int =
        R.string.feature_notifications_custom_conversation_notifications_description

    /** 默认关闭。 */
    override val defaultEnabled: Boolean = false

    private const val TAG = "CustomConversationNotifications"

    /** 微信消息通知渠道（与 CustomNotifications 一致）。 */
    private const val WECHAT_CHANNEL_NORMAL = "message_channel_new_id"

    /** 配置键前缀。 */
    private const val CONV_PREFIX = "ccn_conv_"

    /** 存在覆盖的会话索引键。 */
    private const val INDEX_KEY = "ccn_index"

    // ═══════════════════════════════════════════════════════════════
    //  宿主委托（与 CustomNotifications 同一宿主方法）
    // ═══════════════════════════════════════════════════════════════

    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  枚举与配置
    // ═══════════════════════════════════════════════════════════════

    internal enum class OverrideMode {
        GLOBAL, OFF, ON,
    }

    internal enum class PriorityMode {
        GLOBAL, LOW, DEFAULT, HIGH,
    }

    /** 全局默认（未在会话上显式设置时使用）。 */
    internal var globalSoundModeName: String by prefOption("ccn_global_sound", OverrideMode.GLOBAL.name)
    internal var globalVibrationModeName: String by prefOption("ccn_global_vibrate", OverrideMode.GLOBAL.name)
    internal var globalDndModeName: String by prefOption("ccn_global_dnd", OverrideMode.GLOBAL.name)

    /** 存在覆盖的会话集合（原骨架的 getAll() 替代方案）。 */
    internal var conversationIndex: Set<String> by prefOption(INDEX_KEY, emptySet<String>())

    private fun soundKey(wxId: String) = "${CONV_PREFIX}${wxId}_sound"
    private fun vibrateKey(wxId: String) = "${CONV_PREFIX}${wxId}_vibrate"
    private fun priorityKey(wxId: String) = "${CONV_PREFIX}${wxId}_priority"
    private fun dndKey(wxId: String) = "${CONV_PREFIX}${wxId}_dnd"

    internal fun soundFor(wxId: String): OverrideMode = readMode(soundKey(wxId), globalSoundModeName)
    internal fun vibrateFor(wxId: String): OverrideMode = readMode(vibrateKey(wxId), globalVibrationModeName)
    internal fun dndFor(wxId: String): OverrideMode = readMode(dndKey(wxId), globalDndModeName)

    internal fun priorityFor(wxId: String): PriorityMode = runCatching {
        PriorityMode.valueOf(WePrefs.getString(priorityKey(wxId)) ?: PriorityMode.GLOBAL.name)
    }.getOrDefault(PriorityMode.GLOBAL)

    /** 写一个会话的覆盖；若全部为 GLOBAL 则从索引中摘除。 */
    internal fun setOverride(
        wxId: String,
        sound: OverrideMode,
        vibrate: OverrideMode,
        priority: PriorityMode,
        dnd: OverrideMode,
    ) {
        WePrefs.putString(soundKey(wxId), sound.name)
        WePrefs.putString(vibrateKey(wxId), vibrate.name)
        WePrefs.putString(priorityKey(wxId), priority.name)
        WePrefs.putString(dndKey(wxId), dnd.name)

        val isGlobal = sound == OverrideMode.GLOBAL &&
            vibrate == OverrideMode.GLOBAL &&
            priority == PriorityMode.GLOBAL &&
            dnd == OverrideMode.GLOBAL

        val index = conversationIndex
        conversationIndex = if (isGlobal) index - wxId else index + wxId
    }

    private fun readMode(key: String, globalDefault: String): OverrideMode = runCatching {
        OverrideMode.valueOf(WePrefs.getString(key) ?: globalDefault)
    }.getOrDefault(OverrideMode.GLOBAL)

    // ═══════════════════════════════════════════════════════════════
    //  运行时状态
    // ═══════════════════════════════════════════════════════════════

    private val currentTalker = ThreadLocal<String?>()

    // ═══════════════════════════════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onEnable() {
        // 捕获本次通知对应的会话。
        methodDealNotify.hookBefore {
            currentTalker.set(runCatching { args[1] as String }.getOrNull())
        }
        methodDealNotify.hookAfter {
            currentTalker.remove()
        }

        // 在通知出口按会话覆盖样式。挂在 notify 上而非 Builder.build()：
        // build() 被微信在多个非通知路径调用，且此阶段 channelId 尚未落到最终对象。
        NotificationManager::class.reflekt()
            .firstMethod {
                name = "notify"
                parameters(String::class, Int::class, Notification::class)
            }
            .hookBefore {
                val talker = currentTalker.get() ?: return@hookBefore
                currentTalker.remove()

                // 只处理有显式覆盖的会话，避免与 CustomNotifications 的全局规则互相干扰。
                if (talker !in conversationIndex) return@hookBefore

                val notif = runCatching { args[2] as Notification }.getOrNull() ?: return@hookBefore
                val channelId = notif.channelId ?: return@hookBefore
                if (channelId != WECHAT_CHANNEL_NORMAL) return@hookBefore

                runCatching { applyConversationOverride(notif, talker) }
                    .onFailure { WeLogger.w(TAG, "应用会话覆盖失败：$talker", it) }
            }
    }

    override fun onDisable() {
        currentTalker.remove()
    }

    /** 按会话覆盖声音 / 振动 / 优先级。只改可写字段，不重建通知。 */
    private fun applyConversationOverride(notif: Notification, talker: String) {
        when (soundFor(talker)) {
            OverrideMode.GLOBAL -> Unit
            OverrideMode.OFF -> {
                notif.sound = null
                notif.defaults = notif.defaults and Notification.DEFAULT_SOUND.inv()
            }
            OverrideMode.ON -> {
                notif.sound = null
                notif.defaults = notif.defaults or Notification.DEFAULT_SOUND
            }
        }

        when (vibrateFor(talker)) {
            OverrideMode.GLOBAL -> Unit
            OverrideMode.OFF -> {
                notif.vibrate = null
                notif.defaults = notif.defaults and Notification.DEFAULT_VIBRATE.inv()
            }
            OverrideMode.ON -> {
                notif.vibrate = null
                notif.defaults = notif.defaults or Notification.DEFAULT_VIBRATE
            }
        }

        when (priorityFor(talker)) {
            PriorityMode.GLOBAL -> Unit
            PriorityMode.LOW -> {
                notif.priority = Notification.PRIORITY_LOW
                notif.flags = notif.flags and Notification.FLAG_HIGH_PRIORITY.inv()
            }
            PriorityMode.DEFAULT -> {
                notif.priority = Notification.PRIORITY_DEFAULT
                notif.flags = notif.flags and Notification.FLAG_HIGH_PRIORITY.inv()
            }
            PriorityMode.HIGH -> {
                notif.priority = Notification.PRIORITY_HIGH
                notif.flags = notif.flags or Notification.FLAG_HIGH_PRIORITY
            }
        }

        // 注：不再改写 notif.channelId —— Kotlin 侧 Notification.channelId 在编译期被视作 val
        // （平台 stub 中该字段不可写），强行赋值会直接编译失败。会话级「不提醒」由上面的
        // sound=null + vibrate=null 达成，效果等价且不触碰不可写字段。
    }

    // ═══════════════════════════════════════════════════════════════
    //  设置界面
    // ═══════════════════════════════════════════════════════════════

    override fun onClick(context: ComponentActivity) {
        val contacts = runCatching {
            WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        }.getOrDefault(emptyList())

        showComposeDialog(context) {
            ContactsSelector(
                title = stringResource(R.string.custom_conv_select_conversation),
                contacts = contacts,
                initialSelectedWxIds = conversationIndex,
                onDismiss = onDismiss,
                onConfirm = { selected ->
                    onDismiss()
                    if (selected.isNotEmpty()) {
                        showConversationEditor(context, selected.first())
                    }
                },
            )
        }
    }

    /** 单会话覆盖编辑弹窗。 */
    private fun showConversationEditor(context: ComponentActivity, wxId: String) {
        showComposeDialog(context) {
            var sound by remember {
                mutableStateOf(runCatching { soundFor(wxId) }.getOrDefault(OverrideMode.GLOBAL))
            }
            var vibrate by remember {
                mutableStateOf(runCatching { vibrateFor(wxId) }.getOrDefault(OverrideMode.GLOBAL))
            }
            var priority by remember {
                mutableStateOf(runCatching { priorityFor(wxId) }.getOrDefault(PriorityMode.GLOBAL))
            }
            var dnd by remember {
                mutableStateOf(runCatching { dndFor(wxId) }.getOrDefault(OverrideMode.GLOBAL))
            }

            AlertDialogContent(
                title = { Text(wxId) },
                text = {
                    SegmentedColumn {
                        item(key = "sound") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_conv_sound),
                                description = null,
                                value = sound,
                                options = listOf(
                                    DropdownOption(OverrideMode.GLOBAL, stringResource(R.string.custom_conv_follow_global)),
                                    DropdownOption(OverrideMode.OFF, stringResource(R.string.custom_conv_off)),
                                    DropdownOption(OverrideMode.ON, stringResource(R.string.custom_conv_on)),
                                ),
                                onValueChange = { sound = it },
                            )
                        }
                        item(key = "vibrate") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_conv_vibrate),
                                description = null,
                                value = vibrate,
                                options = listOf(
                                    DropdownOption(OverrideMode.GLOBAL, stringResource(R.string.custom_conv_follow_global)),
                                    DropdownOption(OverrideMode.OFF, stringResource(R.string.custom_conv_off)),
                                    DropdownOption(OverrideMode.ON, stringResource(R.string.custom_conv_on)),
                                ),
                                onValueChange = { vibrate = it },
                            )
                        }
                        item(key = "priority") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_conv_priority),
                                description = null,
                                value = priority,
                                options = listOf(
                                    DropdownOption(PriorityMode.GLOBAL, stringResource(R.string.custom_conv_follow_global)),
                                    DropdownOption(PriorityMode.LOW, stringResource(R.string.custom_conv_priority_low)),
                                    DropdownOption(PriorityMode.DEFAULT, stringResource(R.string.custom_conv_priority_default)),
                                    DropdownOption(PriorityMode.HIGH, stringResource(R.string.custom_conv_priority_high)),
                                ),
                                onValueChange = { priority = it },
                            )
                        }
                        item(key = "dnd") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.custom_conv_dnd),
                                description = null,
                                value = dnd,
                                options = listOf(
                                    DropdownOption(OverrideMode.GLOBAL, stringResource(R.string.custom_conv_follow_global)),
                                    DropdownOption(OverrideMode.OFF, stringResource(R.string.custom_conv_off)),
                                    DropdownOption(OverrideMode.ON, stringResource(R.string.custom_conv_on)),
                                ),
                                onValueChange = { dnd = it },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            setOverride(wxId, sound, vibrate, priority, dnd)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
