package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.view.View
import androidx.activity.ComponentActivity
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.utils.SendIcon
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐点歌（由 Hchat 移植）
 *
 * 触发词命中后走 QQ 音乐公开接口链：
 *   ① 搜索   musicu.fcg / music.search.SearchCgiService / DoSearchForQQMusicDesktop
 *   ② 兜底   smartbox_new.fcg
 *   ③ 详情   musicu.fcg / music.pf_song_detail_svr / get_song_detail
 *   ④ 歌词   fcg_query_lyric_new.fcg
 *   ⑤ 直链   music.qqmusiclite.MtLimitFreeSvr / Obtain        → control.ppurl
 *   ⑥ vkey   music.vkey.GetVkey / CgiGetTempVkey              → data.data.purl
 *            兜底 music.vkey.GetVkey / UrlGetVkey              → midurlinfo[0].flowurl
 *   ⑦ 音频   https://sjy.stream.qqmusic.qq.com/ + flowurl
 *   ⑧ 封面   https://y.gtimg.cn/music/photo_new/T002R500x500M000<pmid>.jpg
 *
 * 发送：卡片走 [WeMessageApi.sendXmlAppMsg]，语音走 [WeMessageApi.sendVoice]（经 SILK 转换）。
 */
object QqMusicOrder : ClickableFeature(), WeDatabaseListenerApi.IInsertListener,
    WeConversationContextMenuApi.IMenuItemsProvider {

    override val technicalId = "QQ音乐点歌"
    override val nameRes = R.string.feature_qq_music_order_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_qq_music_order_description

    private const val TAG = "QqMusicOrder"

    internal const val KEY_TRIGGERS = "qq_music_order_triggers"

    /**
     * 语音卡片触发词（2026-09-23 用户要求「音乐卡片的消息指令要和语音卡片的消息指令要不同」）：
     * 命中卡片触发词只发音乐卡片，命中语音触发词只发语音，两者不再一起发。
     */
    internal const val KEY_VOICE_TRIGGERS = "qq_music_order_voice_triggers"
    internal const val KEY_SEND_AS_CARD = "qq_music_order_send_as_card"
    internal const val KEY_SEND_AS_VOICE = "qq_music_order_send_as_voice"
    internal const val KEY_CUSTOM_SINGER = "qq_music_order_custom_singer"
    internal const val KEY_DEFAULT_SINGER = "qq_music_order_default_singer"
    internal const val KEY_SINGER_AS_NICKNAME = "qq_music_order_replace_singer_with_nickname"
    internal const val KEY_COVER_AS_AVATAR = "qq_music_order_replace_cover_with_avatar"
    internal const val KEY_APP_ID = "qq_music_order_app_id"
    internal const val KEY_INTERCEPT_OWN = "qq_music_order_intercept_own_command"
    internal const val KEY_ONLY_OWN = "qq_music_order_only_own_command"
    internal const val KEY_ALLOWED_TALKERS = "qq_music_order_allowed_talkers"
    internal const val KEY_COOKIE = "qq_music_order_cookie"
    internal const val KEY_APP_NAME = "qq_music_order_app_name"

    /**
     * 「拦截自己发出的点歌指令」（用户 2026-09-22 二轮要求）：
     * 自己发「点歌 xxx」时，指令文字**不进聊天**，只出音乐卡片/音乐语音。
     * 与 [KEY_INTERCEPT_OWN]（连自己发的也识别）、[KEY_ONLY_OWN]（只认自己发的）都是独立维度。
     */
    internal const val KEY_HIDE_OWN_COMMAND = "qq_music_order_hide_own_command"

    internal const val DEFAULT_TRIGGER = "点歌"
    internal const val DEFAULT_VOICE_TRIGGERS = "点歌语音,语音点歌"

    /**
     * 卡片来源 AppID 的默认值。
     *
     * 2026-09-23 真机结论：微信只会给**它自己认识**的 AppID 渲染卡片左下角的「来源应用」，
     * 模块里原本用的 QQ音乐 AppID 发出来的卡片在手机上是一条**不带来源**的灰卡
     * （用户反馈「音乐卡片没有带 appid 显示出来」）。所以默认值改成**用户手机上实测能正常显示**
     * 的网易云音乐 AppID（就是用户提供的真实卡片报文里的那个），设置页里可以一键切回 QQ音乐。
     */
    const val DEFAULT_APP_ID = "wx8dd6ecd81906fd84"

    /** 已知能被微信渲染出来源的 AppID 预设：appid → 卡片上显示的应用名。 */
    val APP_ID_PRESETS: Map<String, String> = linkedMapOf(
        "wx8dd6ecd81906fd84" to "网易云音乐",
        "wx485a97c844086dc9" to "QQ音乐",
    )

    /** Conversation long-press menu ids (kept in a range no other feature uses). */
    private const val MENU_ID_CHAT_ON = 777441
    private const val MENU_ID_CHAT_OFF = 777442

    private const val MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg?data="
    private const val SMARTBOX =
        "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&inCharset=utf8&outCharset=utf-8&key="
    private const val LYRIC =
        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid="
    private const val STREAM_PREFIX = "https://sjy.stream.qqmusic.qq.com/"

    /** QQ音乐 Lite 身份用的 uid / guid（与侧边栏音乐卡片一致，实测能拿到限免 ppurl）。 */
    private const val LITE_UID = "3449496653"
    private const val TEMP_GUID = "yun"
    private const val LITE_BASE_URL = "http://aqqmusic.tc.qq.com/"
    private const val COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R500x500M000"
    private const val SONG_PAGE = "https://y.qq.com/n/ryqq/songDetail/"
    private const val NO_LYRIC = "[99:99.99]暂无歌词"

    private const val MAX_AUDIO_BYTES = 128L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val jsonClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val dlClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val voiceDir: File
        get() = File(HostInfo.application.cacheDir, "wekit_qq_music_order_voice").apply { mkdirs() }

    // ------------------------------------------------------------------ config

    /** 音乐卡片的触发词（默认「点歌」）。 */
    fun cardTriggers(): List<String> {
        val raw = WePrefs.getStringOrDef(KEY_TRIGGERS, DEFAULT_TRIGGER)
        return splitTriggers(raw, DEFAULT_TRIGGER)
    }

    /** 语音卡片的触发词（默认「点歌语音 / 语音点歌」），命中它只发语音不发卡片。 */
    fun voiceTriggers(): List<String> {
        val raw = WePrefs.getStringOrDef(KEY_VOICE_TRIGGERS, DEFAULT_VOICE_TRIGGERS)
        return splitTriggers(raw, DEFAULT_VOICE_TRIGGERS)
    }

    private fun splitTriggers(raw: String, fallback: String): List<String> {
        val list = raw.split(',', '\uFF0C', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return list.ifEmpty { listOf(fallback) }
    }

    /** 兼容旧调用：卡片 + 语音两套触发词的并集。 */
    fun triggers(): List<String> = (cardTriggers() + voiceTriggers()).distinct()

    fun sendAsCard(): Boolean = WePrefs.getBoolOrDef(KEY_SEND_AS_CARD, true)

    fun sendAsVoice(): Boolean = WePrefs.getBoolOrDef(KEY_SEND_AS_VOICE, false)

    fun customSinger(): Boolean = WePrefs.getBoolOrDef(KEY_CUSTOM_SINGER, false)

    fun defaultSinger(): String = WePrefs.getStringOrDef(KEY_DEFAULT_SINGER, "")

    fun singerAsNickname(): Boolean = WePrefs.getBoolOrDef(KEY_SINGER_AS_NICKNAME, false)

    fun coverAsAvatar(): Boolean = WePrefs.getBoolOrDef(KEY_COVER_AS_AVATAR, false)

    fun appId(): String = WePrefs.getStringOrDef(KEY_APP_ID, DEFAULT_APP_ID).ifBlank { DEFAULT_APP_ID }

    fun interceptOwnCommand(): Boolean = WePrefs.getBoolOrDef(KEY_INTERCEPT_OWN, false)

    /**
     * 「仅识别自己发出的指令」：开启后其它人发的点歌指令一律不触发（不发卡片、不发语音）。
     * 与原 [interceptOwnCommand]（= 连自己发的也识别）是两个独立维度，用户明确想要的
     * 是「只认我自己的指令」，所以这里必须是**互斥**语义而不是叠加：
     *  - onlyOwn 开 → 只处理 isSend == 1（消息表里 `isSend=1` 表示这条是账号本人发的），
     *    别人发的一律丢弃；
     *  - onlyOwn 关 → 沿用原行为（默认只认别人发的 isSend == 0，除非 interceptOwn 也打开）。
     */
    fun onlyOwnCommand(): Boolean = WePrefs.getBoolOrDef(KEY_ONLY_OWN, false)

    /**
     * 拦截自己发出的点歌指令文字（默认开）：指令在"发送点击"阶段就被清掉，不落进聊天。
     * 只影响指令那一句，不影响点歌功能是否开启。
     */
    fun hideOwnCommand(): Boolean = WePrefs.getBoolOrDef(KEY_HIDE_OWN_COMMAND, true)

    fun allowedTalkers(): Set<String> = WePrefs.getStringSetOrDef(KEY_ALLOWED_TALKERS, emptySet())

    /** 用户填写的 QQ 音乐 Cookie（可选，仅用于换取可播放直链）。 */
    fun cookie(): String = WePrefs.getStringOrDef(KEY_COOKIE, "").trim()

    fun setTalkerEnabled(talker: String, enabled: Boolean) {
        val current = allowedTalkers().toMutableSet()
        if (enabled) current.add(talker) else current.remove(talker)
        WePrefs.putStringSet(KEY_ALLOWED_TALKERS, current)
    }

    /** Live view of the enabled-chat set, refreshed on every dialog open. */
    internal fun refreshTalkers(): Set<String> = allowedTalkers()

    internal fun removeTalker(talker: String) {
        setTalkerEnabled(talker, false)
    }

    override fun onClick(context: ComponentActivity) {
        QqMusicOrderSettings.show(context)
    }

    // ------------------------------------------------------------------ per-chat menu

    /**
     * Per-chat switch for the allow-list.
     *
     * The allow-list starts empty, which means "every chat", and the settings sheet can only clear
     * entries — so without this entry point a chat could never be added and the option was a dead
     * end. Two mutually exclusive rows are published (add / remove) because a single row cannot
     * know the chat before it is shown.
     */
    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> = listOf(
        WeConversationContextMenuApi.MenuItem(
            id = MENU_ID_CHAT_ON,
            text = localizedChatString(R.string.qq_music_order_this_chat_only),
            drawable = SendIcon,
            shouldShow = { context, _ ->
                context.talker.isNotEmpty() && context.talker !in allowedTalkers()
            },
        ) { context -> toggleTalker(context.activity, context.talker, true) },
        WeConversationContextMenuApi.MenuItem(
            id = MENU_ID_CHAT_OFF,
            text = localizedChatString(R.string.qq_music_order_this_chat_off),
            drawable = SendIcon,
            shouldShow = { context, _ ->
                context.talker.isNotEmpty() && context.talker in allowedTalkers()
            },
        ) { context -> toggleTalker(context.activity, context.talker, false) },
    )

    private fun toggleTalker(context: android.content.Context, talker: String, enabled: Boolean) {
        setTalkerEnabled(talker, enabled)
        val text = if (enabled) {
            R.string.qq_music_order_this_chat_only
        } else {
            R.string.qq_music_order_this_chat_off
        }
        showToast(context, localizedChatString(text))
    }

    // ------------------------------------------------------------------ hooks

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        WeConversationContextMenuApi.addProvider(this)
        installSendGuard()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeConversationContextMenuApi.removeProvider(this)
        unhookAll()
    }

    /**
     * 「拦截自己发出的点歌指令」的注入点。
     *
     * 数据库 listener 只能**事后**看到消息已经落库，拦不住它进聊天；能拦的是发送点击本身。
     *
     * 2026-09-23 真机日志定位（用户上传日志 wekit-2026-09-23.x.log）：
     *   00:14:48.277 RedirectHostLogs: [MicroMsg.ChattingUI.SendTextComponent] doSendMessage end cost:6
     *   00:14:48.314 QqMusicOrder: order detected: talker=… isSend=1 song=粉红色的回忆
     *   00:14:48.315 RedirectHostLogs: [MicroMsg.MsgInfoStorage] insert:28869 … type:1 issend:1
     * —— 指令文字**照样落库并发了出去**，说明只 hook `n1.onClick` 时真机的发送点击根本没进我们的回调。
     * 用户点「发送」实际执行的是 ChatFooter 里发送按钮的 OnClickListener.onClick，也就是
     * [WeChatInputBarMenuApi.methodSendMessage]（多个功能（如 ReadReceipts）都挂在它上面）。
     * 因此两条路径都挂，优先级取 300（高于 CustomAt/ReadReceipts 的 100），保证我们**先**清输入框。
     *
     * 任何一步失败都只记日志：这种情况下指令照常发出去，点歌功能本身不受影响。
     */
    private fun installSendGuard() {
        val installed = mutableListOf<String>()

        runCatching {
            WeChatInputBarMenuApi.methodSendMessage.hookBefore(300) { handleSendGuard(thisObject) }
            installed += "methodSendMessage"
        }.onFailure { WeLogger.w(TAG, "发送拦截 Hook（methodSendMessage）安装失败", it) }

        runCatching {
            val n1 = Class.forName("com.tencent.mm.pluginsdk.ui.chat.n1", false, ClassLoaders.HOST)
            val onClick = n1.getDeclaredMethod("onClick", View::class.java)
            onClick.isAccessible = true
            onClick.hookBefore(300) { handleSendGuard(thisObject) }
            installed += "n1.onClick"
        }.onFailure { WeLogger.w(TAG, "发送拦截 Hook（n1.onClick）安装失败", it) }

        if (installed.isEmpty()) {
            WeLogger.w(TAG, "点歌指令发送拦截 Hook 全部安装失败（指令将照常发出）")
        } else {
            WeLogger.i(TAG, "点歌指令发送拦截 Hook 已安装：${installed.joinToString("/")}")
        }
    }

    /** 同一次点击会被上面两条 hook 各回调一次：用「同一文案 + 短时间窗」去重。 */
    private val guardLastHit = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 发送点击前：识别"自己发出的点歌指令"，按开关决定是否把它拦在聊天之外。
     *
     * 每个提前返回的分支都留了日志（用户上一轮反馈"拦截没生效"时，日志里只有安装成功、
     * 没有任何拦截记录，无法判断卡在哪一步）—— 下一次真机日志能直接看出是取不到输入框、
     * 还是文本没匹配上指令。
     */
    private fun handleSendGuard(thisObject: Any?) {
        if (!hideOwnCommand()) return
        runCatching {
            val footer = resolveFooterForGuard(thisObject)
            val text = readFooterText(footer)
            if (text.isBlank()) {
                WeLogger.d(TAG, "guard: 取不到输入框文本（footer=${footer?.javaClass?.name ?: "null"}），放行")
                return
            }
            val parsed = parseOrder(text)
            if (parsed == null) {
                WeLogger.d(TAG, "guard: 不是点歌指令 text='${text.take(40)}'，放行")
                return
            }
            // 对应的通道被关掉时不要吞掉这句话：让用户看到自己发的普通消息。
            val channelOn = when (parsed.kind) {
                CommandKind.CARD -> sendAsCard()
                CommandKind.VOICE -> sendAsVoice()
            }
            if (!channelOn) {
                WeLogger.d(TAG, "guard: ${parsed.kind} 通道未启用，放行")
                return
            }
            val talker = resolveTalkerForGuard(footer)
            if (talker.isBlank()) {
                WeLogger.d(TAG, "guard: 取不到会话 wxId，放行")
                return
            }
            val allow = allowedTalkers()
            if (allow.isNotEmpty() && talker !in allow) {
                WeLogger.d(TAG, "guard: 会话 $talker 不在生效列表，放行")
                return
            }

            val now = System.currentTimeMillis()
            val last = guardLastHit.get()
            if (now - last < GUARD_DEDUPE_MS) {
                WeLogger.d(TAG, "guard: 同一次点击的第二次回调，忽略")
                return
            }
            guardLastHit.set(now)

            clearFooterText(footer)
            // 这条指令是「我们自己取消的发送」：宿主若把乐观插入的行再撤掉，不该演删除碎裂特效
            MessageDeletionAnimation.suppressDeletionAnimation()
            // 拦下之后我们自己出歌；宿主如果仍然把这条落库，onInsert 那边会看到同文案而跳过，
            // 避免同一句指令出两份卡片。
            guardSuppress[talker] = text.trim() to now
            WeLogger.i(
                TAG,
                "已拦截自己的点歌指令：kind=${parsed.kind} talker=$talker " +
                    "song=${parsed.query.song} singer=${parsed.query.singer}",
            )
            scope.launch { process(talker, "", parsed.query, true, parsed.kind) }
        }.onFailure { WeLogger.e(TAG, "发送拦截异常", it) }
    }

    /** 同一次点击的两条 hook 都在 1 帧内发生，400ms 足够去重又不会误伤连点两次的正常操作。 */
    private const val GUARD_DEDUPE_MS = 400L

    /**
     * 拿到当前聊天输入框（ChatFooter）。
     *
     * 顺序：① [WeCurrentConversationApi.chatFooter]（`ChatFooter.setUserName` 的 after hook，
     * 最可靠）→ ② 从点击回调对象里找（原脚本的 `n1.d` 路径）→ ③ 对回调对象做一层字段下钻。
     */
    private fun resolveFooterForGuard(thisObject: Any?): ChatFooter? {
        WeCurrentConversationApi.chatFooter?.let { return it }
        CustomAt.findFooterFromListener(thisObject)?.let { return it }
        if (thisObject == null) return null
        // 下钻一层：监听器通常持有 `this$0` / 控制器，控制器里才是 ChatFooter。
        runCatching {
            val fields = thisObject.javaClass.declaredFields
            for (field in fields) {
                if (field.type.isPrimitive) continue
                runCatching { field.isAccessible = true }
                val value = runCatching { field.get(thisObject) }.getOrNull() ?: continue
                CustomAt.findFooterFromListener(value)?.let { return it }
            }
        }
        return null
    }

    /**
     * 读输入框正文。
     *
     * 优先 [CustomAt.getFooterText]（`getLastText` / `getLastContent`），取不到就直接读输入框里的
     * EditText（`getToSendEt`）——真机上有版本这两个 getter 返回空串，导致整条拦截静默失效。
     */
    private fun readFooterText(footer: ChatFooter?): String {
        footer ?: return ""
        val viaGetter = CustomAt.getFooterText(footer)
        if (viaGetter.isNotBlank()) return viaGetter
        return runCatching {
            val et = footer.reflekt().firstMethodOrNull { name = "getToSendEt" }?.invoke()
            (et?.reflekt()?.firstMethodOrNull { name = "getText" }?.invoke() as? CharSequence)
                ?.toString().orEmpty()
        }.getOrDefault("")
    }

    /** 清空输入框：footer 的 setter 与 EditText 两条都做，避免只清了一处。 */
    private fun clearFooterText(footer: ChatFooter?) {
        footer ?: return
        runCatching { CustomAt.setFooterText(footer, "") }
        runCatching {
            val et = footer.reflekt().firstMethodOrNull { name = "getToSendEt" }?.invoke() ?: return@runCatching
            runCatching { et.reflekt().firstMethodOrNull { name = "setText" }?.invoke("") }
            runCatching {
                val editable = et.reflekt().firstMethodOrNull { name = "getText" }?.invoke() as? android.text.Editable
                editable?.clear()
            }
        }
    }

    /** 会话 wxId：先按 footer 里的 @映射/String 字段找，退回 [WeCurrentConversationApi.value]。 */
    private fun resolveTalkerForGuard(footer: ChatFooter?): String {
        footer?.let { CustomAt.findTalkerFromFooter(it).takeIf { t -> t.isNotBlank() }?.let { return it } }
        return WeCurrentConversationApi.value
    }

    /** talker → (指令原文, 拦截时间)：拦截后短时间内宿主仍可能落库同一条指令，用它去重。 */
    private val guardSuppress = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return

        val isSend = values.getAsInteger("isSend") ?: 1
        if (onlyOwnCommand()) {
            // 「仅识别自己发出的指令」（用户 2026-09-22 明确要求）：别人发的消息一律不触发，
            // 无论 interceptOwn 怎么设。这是一条**互斥**规则，不是叠加。
            if (isSend != 1) return
        } else if (isSend != 0 && !interceptOwnCommand()) {
            return
        }

        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: return
        // Group chats carry the sender wxId here; it is what the nickname replacement needs.
        val sender = values.getAsString("sender").orEmpty()

        val allow = allowedTalkers()
        if (allow.isNotEmpty() && talker !in allow) return

        // 群聊带 `发送者:\n` 前缀时先剥掉再匹配，否则群里点歌的"指令在句首"永远不成立。
        val body = commandBody(talker, content, sender)
        val parsed = parseOrder(body) ?: return
        val query = parsed.query

        // 指令已经在"发送点击"阶段被拦下并单独出过歌了，宿主要是仍把它落库就跳过，别出两份。
        guardSuppress[talker]?.let { (guardedText, at) ->
            if ((guardedText == content.trim() || guardedText == body.trim()) &&
                System.currentTimeMillis() - at < 5_000
            ) {
                WeLogger.i(TAG, "指令已被发送拦截，跳过重复处理")
                return
            }
        }

        WeLogger.i(
            TAG,
            "order detected: talker=$talker isSend=$isSend kind=${parsed.kind} " +
                "trigger=${parsed.trigger} song=${query.song} singer=${query.singer}",
        )
        scope.launch { process(talker, sender, query, isSend == 1, parsed.kind) }
    }

    data class SongQuery(val song: String, val singer: String?)

    /**
     * 取消息里真正承载指令的那段文本。
     *
     * 群聊 content 形如 `发送者:\n正文`，前缀会让 [parseCommand] 里「触发词必须在句首」的判定失败，
     * 于是群里别人点歌**永远出不了卡**。只有【前缀像发送者】且【剥掉后确实能解析出指令】时才剥，
     * 否则原样返回 —— 剥不动时行为与改动前完全一致，1:1 聊天不受影响。
     */
    private fun commandBody(talker: String, content: String, sender: String): String {
        if (!talker.isGroupChatWxId) return content
        val nl = content.indexOf('\n')
        if (nl <= 0) return content
        val head = content.substring(0, nl)
        if (!head.endsWith(":")) return content
        val rest = content.substring(nl + 1)
        if (parseCommand(rest) == null) return content
        val name = head.dropLast(1)
        val senderLike = (sender.isNotEmpty() && name == sender) ||
            name.startsWith("wxid_") ||
            name.endsWith("@chatroom") ||
            name.endsWith("@im.chatroom") ||
            head.length <= 64
        return if (senderLike) rest else content
    }

    /**
     * Returns the requested song when [text] contains one of the configured triggers.
     *
     * Mirrors Hchat `ge4.c`: a plain `contains` locates the trigger, everything after it becomes
     * the query, and an optional `歌名&歌手` suffix is honoured when custom singer is enabled.
     *
     * 但「全句 contains」太宽松：真机日志里用户发的一段普通聊天（"…qq点歌，发送电点歌无法发出
     * 卡片和歌曲…"）被当成点歌指令，还去搜索了一个超长"歌名"。因此收紧为：
     *  - 触发词必须出现在句首，或前面只允许一个 `@某人 ` 前缀（群里 @机器人 点歌的常见写法）；
     *  - 整条消息不能含换行；
     *  - 查询串长度上限 40 字（歌名+歌手）。
     */
    fun parseCommand(text: String): SongQuery? = parseOrder(text)?.query

    /**
     * 解析点歌指令，并**同时判定用户想要哪种卡片**（2026-09-23 用户要求）：
     * 音乐卡片与语音卡片各有独立的触发词，命中哪个就只发哪个 —— 不再两个一起发。
     *
     * 两套触发词都命中时取**最长的那个**（「点歌语音」优先于「点歌」），长度相同时卡片优先，
     * 这样原来的「点歌」行为完全不变，新增的语音指令也不会被卡片指令吞掉。
     */
    fun parseOrder(text: String): ParsedCommand? {
        val body = text.trim()
        if (body.isEmpty() || body.contains('\n')) return null

        val candidates = cardTriggers().map { it to CommandKind.CARD } +
            voiceTriggers().map { it to CommandKind.VOICE }

        val hit = candidates.mapNotNull { (candidate, kind) ->
            val idx = body.indexOf(candidate)
            if (idx < 0) return@mapNotNull null
            val prefix = body.substring(0, idx)
            if (prefix.isNotEmpty() && !MENTION_ONLY.matches(prefix)) return@mapNotNull null
            Triple(candidate, kind, idx)
        }.maxWithOrNull(
            compareBy({ it.first.length }, { if (it.second == CommandKind.CARD) 1 else 0 }),
        ) ?: return null

        val after = body.substring(hit.third + hit.first.length).trim()
        if (after.isEmpty() || after.length > MAX_SONG_QUERY_CHARS) return null

        val query = if (customSinger() && after.contains('&')) {
            val idx = after.indexOf('&')
            val song = after.substring(0, idx).trim()
            val singer = after.substring(idx + 1).trim().ifEmpty { null }
            if (song.isEmpty()) return null else SongQuery(song, singer)
        } else {
            SongQuery(after, null)
        }
        return ParsedCommand(query, hit.second, hit.first)
    }

    /** 指令种类：决定这次只发音乐卡片还是只发语音。 */
    enum class CommandKind { CARD, VOICE }

    data class ParsedCommand(
        val query: SongQuery,
        val kind: CommandKind,
        val trigger: String,
    )

    /** 歌名 + 歌手的合理长度上限，超过就当成普通聊天。 */
    private const val MAX_SONG_QUERY_CHARS = 40

    /** 只允许一个 `@某人` 前缀出现在触发词之前。 */
    private val MENTION_ONLY = Regex("^@[^\\s@]{1,24}\\s*$")

    // 注意：这是个 standalone object，**不能**再包一层 `companion object`
    // （Kotlin 报 "Modifier 'companion' is not applicable inside 'standalone object'"，且 KSP 生成的
    //  FeaturesProvider 会去访问不存在的 QqMusicOrder.Companion 一起报错）。

    // ------------------------------------------------------------------ pipeline

    private fun process(
        talker: String,
        sender: String,
        query: SongQuery,
        own: Boolean,
        kind: CommandKind,
    ) {
        try {
            // 指令种类决定这次发哪种卡片：卡片指令只发音乐卡片、语音指令只发语音，
            // 两者不再一起发（2026-09-23 用户要求）。
            val wantsCard = kind == CommandKind.CARD && sendAsCard()
            val wantsVoice = kind == CommandKind.VOICE && sendAsVoice()
            if (!wantsCard && !wantsVoice) {
                notice(talker, R.string.qq_music_order_need_a_channel)
                return
            }

            val hit = searchMid(query.song)
            if (hit == null) {
                notice(talker, R.string.qq_music_order_not_found)
                return
            }

            // 详情接口偶发失败时不要整单放弃：搜索结果里的 mid / 媒体 mid / 歌曲 id 已经够发卡片和取流。
            val detail = fetchDetail(hit.mid) ?: SongDetail(
                mid = hit.mid,
                name = hit.name,
                singer = hit.singer,
                songId = hit.songId,
                albumPmid = null,
                mediaMid = hit.mediaMid,
            ).also { WeLogger.w(TAG, "detail unavailable, fallback to search hit ${hit.mid}") }

            val lyric = fetchLyric(hit.mid)
            val audioUrl = resolveAudioUrl(detail)
            // 「用头像替换封面」/「用昵称替换歌手」都用**点歌人**的身份：以前封面传的是歌曲 mid
            // （detail.mid），拿歌名 mid 去 img_flag 里查人永远查不到 → 覆盖开关等于没生效；
            // 昵称则在单聊里用 selfWxId 兜底，导致"对方点歌却显示我的昵称"。
            val requester = requesterWxId(talker, sender, own)
            val thumbUrl = resolveCoverUrl(detail, requester)
            val singer = resolveSinger(talker, requester, detail)

            var cardOk = false
            if (wantsCard) cardOk = sendCard(talker, detail, singer, lyric, audioUrl, thumbUrl)

            var voiceOk = false
            if (wantsVoice && !audioUrl.isNullOrBlank()) voiceOk = sendVoice(talker, audioUrl)

            WeLogger.i(
                TAG,
                "order done: kind=$kind card=$wantsCard/$cardOk voice=$wantsVoice/$voiceOk " +
                    "audioUrl=${if (audioUrl.isNullOrBlank()) "none" else "resolved"}",
            )

            when {
                wantsCard && wantsVoice && !cardOk && !voiceOk ->
                    notice(talker, R.string.qq_music_order_both_failed)

                wantsCard && !cardOk ->
                    notice(talker, R.string.qq_music_order_card_failed)

                // 取不到可播放直链（会员曲/版权限制）不是"发送失败"，要说清楚只发了卡片。
                wantsVoice && audioUrl.isNullOrBlank() ->
                    notice(talker, R.string.qq_music_order_voice_unavailable)

                wantsVoice && !voiceOk ->
                    notice(talker, R.string.qq_music_order_voice_failed)
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "order failed", e)
        }
    }

    private fun notice(talker: String, @androidx.annotation.StringRes resId: Int) {
        val text = HostInfo.application.getString(resId)
        runCatching {
            // 10000 is WeChat's local tip-like message type used for in-chat notices.
            WeMessageApi.createSimpleMsgInfoAndInsert(10000, talker, text, System.currentTimeMillis())
        }.onFailure { WeLogger.e(TAG, "failed to post local notice", it) }
    }

    // ------------------------------------------------------------------ QQ Music API

    private data class SongHit(
        val mid: String,
        val name: String,
        val singer: String,
        val songId: Long,
        val mediaMid: String?,
    )

    /**
     * 搜索歌曲。
     *
     * 真机/接口实测（2026-09-22）：musicu 搜索的响应是
     * `req.data.body.song.list[0]`，字段是 `title` / `mid` / `file.media_mid` / `id`；
     * 而旧代码读的是 `data.song.itemlist`（那是 smartbox 的路径），所以**主路永远命中不了**，
     * 只能靠 smartbox 兜底 —— 结果经常搜到翻唱、伴奏或干脆搜不到。
     */
    private fun searchMid(song: String): SongHit? {
        runCatching {
            val param = JSONObject()
                .put("num_per_page", 10)
                .put("page_num", 1)
                .put("query", song)
                .put("search_type", 0)
            val req = JSONObject()
                .put("method", "DoSearchForQQMusicDesktop")
                .put("module", "music.search.SearchCgiService")
                .put("param", param)
            val body = JSONObject()
                .put("comm", JSONObject().put("ct", "19").put("cv", "1882"))
                .put("req", req)

            val item = musicu(body)?.optJSONObject("req")
                ?.optJSONObject("data")
                ?.optJSONObject("body")
                ?.optJSONObject("song")
                ?.optJSONArray("list")
                ?.optJSONObject(0)
            if (item != null) {
                val hit = hitOf(item)
                if (hit != null) {
                    WeLogger.i(TAG, "search hit via musicu: ${hit.name} / ${hit.mid}")
                    return hit
                }
            }
        }.onFailure { WeLogger.w(TAG, "musicu search failed", it) }

        // ② smartbox 兜底（路径确实是 data.song.itemlist）
        return runCatching {
            val raw = getString(SMARTBOX + URLEncoder.encode(song, "UTF-8"), jsonHeaders())
                ?: return@runCatching null
            val item = JSONObject(raw).optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("itemlist")
                ?.optJSONObject(0)
                ?: return@runCatching null
            hitOf(item)?.also { WeLogger.i(TAG, "search hit via smartbox: ${it.name} / ${it.mid}") }
        }.onFailure { WeLogger.w(TAG, "smartbox fallback failed", it) }.getOrNull()
    }

    private fun hitOf(item: JSONObject): SongHit? {
        val mid = item.optString("mid").takeIf { it.isNotBlank() } ?: return null
        return SongHit(
            mid = mid,
            name = item.optString("title").ifBlank { item.optString("name").ifBlank { mid } },
            singer = item.optJSONArray("singer")?.optJSONObject(0)?.optString("name").orEmpty(),
            songId = item.optLong("id", 0L),
            mediaMid = item.optJSONObject("file")?.optString("media_mid")?.takeIf { it.isNotBlank() },
        )
    }

    data class SongDetail(
        val mid: String,
        val name: String,
        val singer: String,
        val songId: Long,
        val albumPmid: String?,
        val mediaMid: String?,
    )

    private fun fetchDetail(mid: String): SongDetail? = runCatching {
        val req = JSONObject()
            .put("module", "music.pf_song_detail_svr")
            .put("method", "get_song_detail")
            .put("param", JSONObject().put("song_mid", mid))
        val body = JSONObject()
            .put("comm", JSONObject().put("ct", "19").put("cv", "1882"))
            .put("req", req)
            .toString()

        val raw = getString(MUSICU + URLEncoder.encode(body, "UTF-8"), jsonHeaders())
            ?: return@runCatching null
        val info = JSONObject(raw).optJSONObject("req")
            ?.optJSONObject("data")
            ?.optJSONObject("track_info")
            ?: return@runCatching null

        SongDetail(
            mid = info.optString("mid").ifBlank { mid },
            name = info.optString("name").ifBlank { mid },
            singer = info.optJSONArray("singer")?.optJSONObject(0)?.optString("name").orEmpty(),
            songId = info.optLong("id", 0L),
            albumPmid = info.optJSONObject("album")?.optString("pmid")?.takeIf { it.isNotBlank() },
            mediaMid = info.optJSONObject("file")?.optString("media_mid")?.takeIf { it.isNotBlank() },
        )
    }.onFailure { WeLogger.e(TAG, "fetchDetail failed", it) }.getOrNull()

    private fun fetchLyric(mid: String): String = runCatching {
        val raw = getString(LYRIC + mid, jsonHeaders()) ?: return@runCatching NO_LYRIC
        JSONObject(raw).optString("lyric").ifBlank { NO_LYRIC }
    }.getOrDefault(NO_LYRIC)

    /**
     * 解析可播放直链。
     *
     * 真机 / 接口实测（2026-09-22，容器内直连 u.y.qq.com 验证）：
     *  - 旧实现只读 `flowurl`，而真正可取流的字段是 **`purl`**，所以语音条从来没成功过；
     *  - **可用链路 = 侧边栏负一屏音乐卡片（HomeSidePanelMusic）那一套**：用 QQ音乐 Lite 身份
     *    （`ct=11, cv=22060004, tmeAppID=ztelite, OpenUDID=nouid, uid=3449496653`）调
     *    `music.qqmusiclite.MtLimitFreeSvr/Obtain` 拿限免 `ppurl`，再用 `music.vkey.GetVkey/CgiGetTempVkey`
     *    （`guid=yun`、`mediamid` 也填 `yun`）换临时 vkey，取 `request.data.data.yun.purl`
     *    —— 绝对地址（`http://sjy.stream.qqmusic.qq.com/O400….ogg?…vkey=…`），实测下载 HTTP 206 / audio/x-ogg；
     *  - 同一请求换成网页身份（`ct=19, cv=1882`）或 `CgiGetVkey(uin=0)`、`UrlGetVkey(M500…)`，
     *    ppurl / purl / flowurl **全为空** → 必须带 Lite 身份，别再用网页那套；
     *  - 免 vkey 直链（`ws.stream.qqmusic.qq.com/C400<media>.m4a`）一律 403，不要依赖；
     *  - 限免额度**按曲目**给（同歌手另一首可能没有 ppurl）；拿不到就只发卡片，提示里要说清原因。
     *  - 返回的音频可能是 **ogg/vorbis**，所以 native 侧 symphonia 必须开 `vorbis` feature，
     *    否则 anyToSilk 解不了码、发出去的语音条在微信里放不了。
     *
     * 尝试顺序：Lite 限免额度 → 用户 Cookie（会员/全集）→ Lite 身份 UrlGetVkey flowurl。
     */
    private fun resolveAudioUrl(detail: SongDetail): String? = secureUrl(resolveRawAudioUrl(detail))

    /**
     * 宿主进程里跑的是微信自己的 NetworkSecurityPolicy：targetSdk≥28 且未开 usesCleartextTraffic 时，
     * OkHttp 对 `http://` 会直接抛 CleartextNotPermitted（宿主清单我们改不了，也没法覆盖）。
     * QQ 音乐的取流主机两种协议都实测可用（`https://sjy.stream.qqmusic.qq.com/…ogg?…vkey=…` 同样 206），
     * 所以统一升级成 https：卡片里播放更稳，下载也不会被明文策略拦。
     */
    private fun secureUrl(url: String?): String? {
        if (url == null || !url.startsWith("http://")) return url
        val host = url.removePrefix("http://").substringBefore('/').substringAfter('@').substringBefore(':')
        return if (host.endsWith(".qq.com")) {
            "https://" + url.removePrefix("http://")
        } else {
            url
        }
    }

    private fun resolveRawAudioUrl(detail: SongDetail): String? {
        resolveViaFreeQuota(detail)?.let {
            WeLogger.i(TAG, "audio url resolved via lite free-quota vkey")
            return it
        }
        resolveViaCookie(detail)?.let {
            WeLogger.i(TAG, "audio url resolved via cookie vkey")
            return it
        }
        resolveViaFlowUrl(detail)?.let {
            WeLogger.i(TAG, "audio url resolved via lite flowurl")
            return it
        }
        WeLogger.i(TAG, "no playable audio url for mid=${detail.mid} (no free quota / vip only)")
        return null
    }

    /** QQ音乐 Lite（车载/精简版）身份：取流接口必须带这个 comm 才给 ppurl。 */
    private fun liteComm(): JSONObject = JSONObject()
        .put("ct", "11")
        .put("cv", "22060004")
        .put("tmeAppID", "ztelite")
        .put("OpenUDID", "nouid")
        .put("uid", LITE_UID)

    /**
     * 限免额度链路：`MtLimitFreeSvr/Obtain` → `control.ppurl` → `CgiGetTempVkey` → 绝对 purl。
     * 与 HomeSidePanelMusic 的实现保持一致（那条链路是现网可用的）。
     */
    private fun resolveViaFreeQuota(detail: SongDetail): String? {
        if (detail.songId <= 0L) return null
        return runCatching {
            val obtain = JSONObject()
                .put("comm", liteComm())
                .put(
                    "request",
                    JSONObject()
                        .put("module", "music.qqmusiclite.MtLimitFreeSvr")
                        .put("method", "Obtain")
                        .put(
                            "param",
                            JSONObject()
                                .put("songid", JSONArray().put(detail.songId))
                                .put("need_ppurl", true),
                        ),
                )
            val ppurl = musicu(obtain)
                ?.optJSONObject("request")
                ?.optJSONObject("data")
                ?.optJSONArray("tracks")
                ?.optJSONObject(0)
                ?.optJSONObject("control")
                ?.optString("ppurl")
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null

            val temp = musicu(
                JSONObject().put(
                    "request",
                    JSONObject()
                        .put("module", "music.vkey.GetVkey")
                        .put("method", "CgiGetTempVkey")
                        .put(
                            "param",
                            JSONObject()
                                .put("guid", TEMP_GUID)
                                .put(
                                    "songlist",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("mediamid", TEMP_GUID)
                                            .put("tempVkey", ppurl)
                                            .put("songMID", detail.mid),
                                    ),
                                ),
                        ),
                ),
            )
            val data = temp?.optJSONObject("request")?.optJSONObject("data")
                ?: return@runCatching null
            val item = data.optJSONObject("data")?.optJSONObject(TEMP_GUID)
                ?: return@runCatching null
            if (item.optInt("result", 0) != 0) {
                WeLogger.w(TAG, "temp vkey rejected: result=${item.optInt("result")}")
                return@runCatching null
            }
            val purl = item.optString("purl").takeIf { it.isNotBlank() }
                ?: return@runCatching null
            // 这条链路回的是绝对地址；万一回相对路径，补 sip 主机。
            if (purl.startsWith("http")) {
                purl
            } else {
                data.optJSONArray("sip")?.optString(0).orEmpty().ifBlank { STREAM_PREFIX } + purl
            }
        }.onFailure { WeLogger.w(TAG, "lite free-quota chain failed", it) }.getOrNull()
    }

    /** Lite 身份再试 `UrlGetVkey`（M500 mp3）：`flowurl` 是相对路径，要拼 `aqqmusic.tc.qq.com`。 */
    private fun resolveViaFlowUrl(detail: SongDetail): String? = runCatching {
        val media = detail.mediaMid ?: detail.mid
        val request = JSONObject()
            .put("module", "music.vkey.GetVkey")
            .put("method", "UrlGetVkey")
            .put(
                "param",
                JSONObject()
                    .put("guid", TEMP_GUID)
                    .put("songmid", JSONArray().put(detail.mid))
                    .put("filename", JSONArray().put("M500$media.mp3")),
            )
        val flowurl = musicu(JSONObject().put("comm", liteComm()).put("request", request))
            ?.optJSONObject("request")
            ?.optJSONObject("data")
            ?.optJSONArray("midurlinfo")
            ?.optJSONObject(0)
            ?.optString("flowurl")
            ?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        if (flowurl.startsWith("http")) flowurl else LITE_BASE_URL + flowurl
    }.onFailure { WeLogger.w(TAG, "lite UrlGetVkey failed", it) }.getOrNull()

    /**
     * 用用户填的 QQ 音乐 Cookie 换取正式 vkey（相当于官方客户端/侧边栏音乐卡片那条路）。
     *
     * 请求形状与 QQ 音乐客户端一致：`comm` 带 `uin` + `authst`（即 `qm_keyst`），
     * `req` 走 `vkey.GetVkeyServer/CgiGetVkey`、`platform=20`、`loginflag=1`；
     * 返回 `req.data.midurlinfo[0].purl` 是相对路径，要拼上 `req.data.sip[0]`。
     */
    private fun resolveViaCookie(detail: SongDetail): String? {
        val (uin, authst) = cookieAuth() ?: return null
        return runCatching {
            val param = JSONObject()
                .put("guid", "10000")
                .put("songmid", JSONArray().put(detail.mid))
                .put("songtype", JSONArray().put(0))
                .put("uin", uin)
                .put("loginflag", 1)
                .put("platform", "20")
            val request = JSONObject()
                .put("module", "vkey.GetVkeyServer")
                .put("method", "CgiGetVkey")
                .put("param", param)
            val body = JSONObject()
                .put(
                    "comm",
                    JSONObject()
                        .put("uin", uin)
                        .put("format", "json")
                        .put("ct", 24)
                        .put("cv", 0)
                        .put("authst", authst),
                )
                .put("req", request)
            extractPurl(musicu(body), "midurlinfo")
        }.onFailure { WeLogger.w(TAG, "cookie vkey request failed", it) }.getOrNull()
    }

    /**
     * 解析用户填写的 QQ 音乐 Cookie（可选）。
     *
     * 支持整段 Cookie（登录 y.qq.com 后从开发者工具复制：`uin=123456; qm_keyst=xxx; ...`），
     * 键顺序无关，`o123456` 这类带前缀的 uin 会自动去掉前缀。没填或填不全时返回 null
     * —— 此时仍会走 Lite 限免额度那条匿名链路（那条不需要登录）。
     */
    private fun cookieAuth(): Pair<String, String>? {
        val raw = cookie()
        if (raw.isEmpty() || !raw.contains('=')) return null

        var uin = ""
        var auth = ""
        raw.split(';', '\n').forEach { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@forEach
            val key = part.substring(0, idx).trim().lowercase()
            val value = part.substring(idx + 1).trim().trim('"')
            if (value.isEmpty()) return@forEach
            when (key) {
                "uin", "wxuin", "p_uin", "o_uin", "musickey_uin" -> if (uin.isEmpty()) uin = value
                "qm_keyst", "qqmusic_key", "qqmusic_key_new", "music_key", "skey" ->
                    if (auth.isEmpty()) auth = value
            }
        }
        if (uin.isEmpty() || auth.isEmpty()) {
            WeLogger.w(TAG, "qq music cookie incomplete: uin=${uin.isNotEmpty()} key=${auth.isNotEmpty()}")
            return null
        }
        val digits = uin.removePrefix("o")
        if (digits.isEmpty() || !digits.all { it.isDigit() }) {
            WeLogger.w(TAG, "qq music cookie uin malformed")
            return null
        }
        return digits to auth
    }

    /** musicu 通用请求：统一拼接 URL、编码与异常兜底。 */
    private fun musicu(body: JSONObject): JSONObject? = runCatching {
        val raw = getString(MUSICU + URLEncoder.encode(body.toString(), "UTF-8"), jsonHeaders())
            ?: return@runCatching null
        JSONObject(raw)
    }.onFailure { WeLogger.w(TAG, "musicu request failed", it) }.getOrNull()

    /**
     * 从 musicu 响应里取 `purl`（其次 `flowurl`）并拼上 `sip` 主机前缀。
     *
     * 响应外层键名跟随请求（`request` / `req` 两种都见过），所以两个都试。
     */
    private fun extractPurl(resp: JSONObject?, listKey: String): String? {
        if (resp == null) return null
        val data = (resp.optJSONObject("request") ?: resp.optJSONObject("req"))
            ?.optJSONObject("data") ?: return null
        val item = data.optJSONArray(listKey)?.optJSONObject(0) ?: return null
        val raw = item.optString("purl").takeIf { it.isNotBlank() }
            ?: item.optString("flowurl").takeIf { it.isNotBlank() }
            ?: return null
        if (raw.startsWith("http")) return raw
        val host = data.optJSONArray("sip")?.optString(0).orEmpty().ifBlank { STREAM_PREFIX }
        return if (host.endsWith("/")) host + raw else "$host/$raw"
    }

    /**
     * Replaces the singer with the requester's nickname (Hchat behaviour).
     *
     * The member id has to come from the inserted row ([sender]): passing an empty id made the
     * lookup always return "" (WeDatabaseApi short-circuits on a blank member id), so the option
     * silently did nothing. For our own commands the row usually has no sender, hence the
     * self-wxId fallback.
     */
    private fun resolveSinger(talker: String, memberId: String, detail: SongDetail): String {
        if (!singerAsNickname()) return detail.singer

        val fallback = defaultSinger().ifBlank { detail.singer }
        if (memberId.isBlank()) return fallback

        val nick = runCatching {
            if (talker.isGroupChatWxId) {
                WeDatabaseApi.getGroupMemberDisplayName(talker, memberId)
                    .ifBlank { WeDatabaseApi.getDisplayName(memberId) }
            } else {
                WeDatabaseApi.getDisplayName(memberId)
            }
        }.getOrDefault("")

        return nick.ifBlank { fallback }
    }

    /**
     * 点歌人的 wxId（「用头像替换封面」要用的那个）。
     *  - 自己发的指令：自己（`WeApi.selfWxId`）
     *  - 群里别人点歌：插入行里的 `sender`
     *  - 单聊里对方点歌：会话本身就是对方，用 `talker`
     */
    private fun requesterWxId(talker: String, sender: String, own: Boolean): String {
        if (own) return runCatching { WeApi.selfWxId }.getOrDefault("").ifBlank { sender }
        if (sender.isNotBlank()) return sender
        return if (talker.isGroupChatWxId) "" else talker
    }

    /**
     * 卡片的缩略图地址。
     *
     * 之前这里下载封面字节再传给发送接口，但 `WeMessageApi.sendXmlAppMsg` 的 url/data 参数恒为
     * null（封面由 XML 里的 `thumburl` 交给微信自己去下），所以那次下载完全是白跑的网络请求
     * —— 去掉后卡片依旧正常，还少一次请求。
     *
     * 「用头像替换封面」的修复（2026-09-22 用户反馈不生效）：旧实现把**歌曲 mid** 当成 wxId
     * 去查头像，`img_flag` 里当然没有这一行，于是永远回退到专辑封面 —— 开关看起来完全没效果。
     * 现在传点歌人的 wxId，并且只接受 http(s) 直链（本地路径塞进 thumburl 微信下不下来）。
     */
    private fun resolveCoverUrl(detail: SongDetail, avatarWxId: String): String {
        val album = detail.albumPmid?.let { "$COVER_PREFIX$it.jpg" }.orEmpty()
        if (!coverAsAvatar()) return album
        val avatar = runCatching { WeDatabaseApi.getAvatarHttpUrl(avatarWxId) }.getOrDefault("")
        if (avatar.isBlank()) {
            WeLogger.i(TAG, "avatar cover unavailable (wxid='$avatarWxId'), fallback to album cover")
            return album
        }
        WeLogger.i(TAG, "avatar cover for '$avatarWxId' -> ${avatar.take(80)}")
        return avatar
    }

    // ------------------------------------------------------------------ send

    private fun sendCard(
        talker: String,
        detail: SongDetail,
        singer: String,
        lyric: String,
        audioUrl: String?,
        thumbUrl: String,
    ): Boolean = runCatching {
        val xml = buildSongXml(detail, singer, lyric, audioUrl, thumbUrl)
        // 用户反馈「音乐卡片没有带 appid / 自定义的 appid 不生效」：把**真正生效的 appid**
        // 打进 INFO 日志，配合 WeMessageApi 的 `appmsg info: appid=…` 就能一眼确认是
        // 设置没保存、还是宿主把 appid 吃掉了，不用再靠猜。
        WeLogger.i(
            TAG,
            "send card appid='${effectiveAppId()}' appname='${appName()}' statextstr='${appIdStateExtStr()}' " +
                "thumb=${thumbUrl.take(72)} xmlLen=${xml.length}",
        )
        WeLogger.i(TAG, "card xml=${xml.take(700)}")
        val ok = WeMessageApi.sendXmlAppMsg(talker, xml)
        WeLogger.i(TAG, "send card result=$ok xmlLen=${xml.length}")
        ok
    }.onFailure { WeLogger.e(TAG, "send card failed", it) }.getOrDefault(false)

    /**
     * Standard QQ Music appmsg card. `appid` must be a registered music appId so WeChat renders
     * the music card instead of a generic link.
     *
     * 必须是完整的 `<msg>…</msg>` 报文：宿主解析入口（WeAppMsgApi.methodParseXml 命中的那个方法）
     * 是「解析一条完整消息 XML 里的 appmsg 段」，只给 `<appmsg>` 片段时它会直接返回 null 并打印
     * "parse amessage xml failed" —— 这正是线上点歌卡片一直发不出去的根因（真机日志实测）。
     * 参考可用的报文样例：MarkdownRendering.toNativeMarkdownAppMsg、ReadReceipts 的卡片 XML。
     *
     * [audioUrl] 是解析出来的可播放直链（可能为空）：非空时写进 dataurl/lowdataurl，
     * 让微信卡片自带播放能力；为空时仍发卡片（标题/歌手/封面/歌词都可用），只按链接跳转。
     */
    private fun buildSongXml(
        detail: SongDetail,
        singer: String,
        lyric: String,
        audioUrl: String? = null,
        thumbUrl: String = "",
    ): String {
        val url = SONG_PAGE + detail.mid
        val cover = thumbUrl
        val play = audioUrl.orEmpty()
        return buildString {
            append("<msg>")
            append("<appmsg appid=\"").append(escape(effectiveAppId())).append("\" sdkver=\"0\">")
            append("<title>").append(escape(detail.name)).append("</title>")
            append("<des>").append(escape(singer)).append("</des>")
            append("<action>view</action>")
            append("<type>3</type>")
            append("<showtype>0</showtype>")
            // 宿主自己的音乐卡片都是 soundtype=0；补上它能让微信更稳地按「音乐」渲染，
            // 而不是退化成普通链接卡（字段缺失时部分版本会走通用 appmsg 分支）。
            append("<soundtype>0</soundtype>")
            append("<content></content>")
            append("<url>").append(escape(url)).append("</url>")
            append("<lowurl>").append(escape(url)).append("</lowurl>")
            append("<dataurl>").append(escape(play)).append("</dataurl>")
            append("<lowdataurl>").append(escape(play)).append("</lowdataurl>")
            // `statextstr`：微信用它把卡片绑定到来源应用（实测报文里 `<appmsg appid>` 之外还有这一段）。
            // 只有 `<appmsg appid>` 而没有 statextstr 时，微信会把卡片降级渲染成"无来源"的灰卡，
            // 这就是用户看到的"卡片没有带 appid、自定义 appid 不生效"。
            append("<statextstr>").append(escape(appIdStateExtStr())).append("</statextstr>")
            append("<thumburl>").append(escape(cover)).append("</thumburl>")
            append("<songalbumurl>").append(escape(cover)).append("</songalbumurl>")
            append("<songlyric>").append(escape(lyric)).append("</songlyric>")
            // 卡片右下角显示的「来源应用」；缺了它有些版本不显示来源，用户会以为
            // appid 没带上（真机反馈「音乐卡片没有带 appid」）。
            append("<sourcedisplayname>").append(escape(appName())).append("</sourcedisplayname>")
            append("<appattach><totallen>0</totallen><attachid></attachid><fileext></fileext></appattach>")
            append("<frommsgid>0</frommsgid>")
            append("</appmsg>")
            // 完整报文里"来源应用"是 `<msg>` 的**同级** `<appinfo>`，不是 appmsg 的子节点。
            // 对照用户提供的网易云音乐卡片实测报文：
            //   <appinfo><version>52</version><appname>网易云音乐</appname></appinfo>
            // 缺了它微信不认来源应用，卡片只能按通用 appmsg 渲染。
            append("<appinfo><version>52</version><appname>").append(escape(appName()))
                .append("</appname></appinfo>")
            append("</msg>")
        }
    }

    /**
     * 真正写进卡片的 appid。
     *
     * 用户可以在设置里填自己的 appid；这里做两步清洗，避免一个格式不对的值把整张卡片弄坏：
     *  - 去掉首尾空白与不可见字符（从网页/文档里复制粘贴时很常见）；
     *  - 微信 appid 的规范形式是 `wx` + 16 位十六进制，不符就记一条日志但仍然按用户填的发
     *    （尊重用户，同时留下可排查的痕迹），为空才回退默认值。
     */
    internal fun effectiveAppId(): String {
        val raw = appId().filter { !it.isWhitespace() && it.code > 0x1F }
        if (raw.isEmpty()) return DEFAULT_APP_ID
        if (!APP_ID_PATTERN.matches(raw)) {
            WeLogger.w(TAG, "custom appid '$raw' does not look like wx+16 hex, sending as-is")
        }
        return raw
    }

    /**
     * 卡片来源名（同时用于 `<sourcedisplayname>` 与同级 `<appinfo><appname>`）。
     *
     * 用户把 appid 换成自己的应用时，来源名也该跟着换（微信按 appname 显示"来自 XX"）。
     * 设置里留空则按 appid 推断：默认 appid → QQ音乐，其它 → 音乐。
     */
    internal fun appName(): String {
        val custom = WePrefs.getStringOrDef(KEY_APP_NAME, "").trim()
        if (custom.isNotEmpty()) return custom
        // 预设表里认识的 AppID 用它对应的官方来源名；不认识才退回泛称「音乐」。
        return APP_ID_PRESETS[effectiveAppId()] ?: "音乐"
    }

    /**
     * `statextstr` = protobuf `{ field3 { field1: appid } }` 的 base64。
     *
     * 对照实测报文反解：`GhQKEnd4OGRkNmVjZDgxOTA2ZmQ4NA==`
     *   → `1a 14 | 0a 12 | "wx8dd6ecd81906fd84"`（1a = field3, 14 = 20 字节；0a = field1, 12 = 18 字节）
     * 这里按同样的结构编码当前生效的 appid（appid 定长 18 字节，varint 长度只占 1 字节）。
     */
    internal fun appIdStateExtStr(): String = runCatching {
        val bytes = effectiveAppId().toByteArray(Charsets.UTF_8)
        val inner = ByteArray(2 + bytes.size)
        inner[0] = 0x0a
        inner[1] = bytes.size.toByte()
        System.arraycopy(bytes, 0, inner, 2, bytes.size)
        val out = ByteArray(2 + inner.size)
        out[0] = 0x1a
        out[1] = inner.size.toByte()
        System.arraycopy(inner, 0, out, 2, inner.size)
        android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }.getOrElse {
        WeLogger.w(TAG, "statextstr encode failed", it)
        ""
    }

    private val APP_ID_PATTERN = Regex("^wx[0-9a-fA-F]{16}$")

    private fun sendVoice(talker: String, audioUrl: String): Boolean = runCatching {
        val ext = audioUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it in AUDIO_EXTS } ?: "audio"
        val stamp = "${System.currentTimeMillis()}_${System.nanoTime()}"
        val target = File(voiceDir, "qq_music_$stamp.$ext")
        val tmp = File(target.absolutePath + ".part")
        val silk = File(voiceDir, "qq_music_$stamp.silk")

        try {
            if (!downloadTo(tmp, audioUrl)) return@runCatching false
            if (!tmp.renameTo(target) || !target.isFile || target.length() <= 0L) return@runCatching false

            // WeChat only accepts SILK for voice messages; fall back to the raw file if the
            // converter is unavailable so the user still gets something playable.
            val converted = runCatching {
                AudioUtils.anyToSilk(target.absolutePath, silk.absolutePath)
            }.getOrDefault(false)

            val playable = if (converted && silk.isFile && silk.length() > 0L) silk else target
            val durationMs = runCatching { AudioUtils.getDurationMs(playable.absolutePath) }
                .getOrDefault(0L)
                .takeIf { it > 0L }
                ?.toInt()
                ?: probeDuration(playable.absolutePath).toInt()

            WeMessageApi.sendVoice(talker, playable.absolutePath, durationMs.coerceIn(1000, 60_000))
        } finally {
            cleanup(tmp)
            cleanup(target)
            cleanup(silk)
        }
    }.onFailure { WeLogger.e(TAG, "send voice failed", it) }.getOrDefault(false)

    private fun cleanup(file: File) {
        runCatching { if (file.exists() && !file.delete()) file.deleteOnExit() }
    }

    /** Standalone duration probe, useful when the native helper cannot read the container. */
    internal fun probeDuration(path: String): Long {
        // MediaMetadataRetriever only became AutoCloseable in API 29, so close it manually.
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Throwable) {
            WeLogger.w(TAG, "probeDuration failed for $path: ${e.message}")
            0L
        } finally {
            runCatching { retriever?.release() }
        }
    }

    // ------------------------------------------------------------------ http

    private fun jsonHeaders(): Map<String, String> = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36",
        "Referer" to "https://y.qq.com/",
        "Origin" to "https://y.qq.com",
        "Accept" to "application/json, text/plain, */*",
    )

    private fun getString(url: String, headers: Map<String, String>): String? {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return jsonClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    private fun downloadTo(target: File, url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MicroMessenger Client")
            .header("Referer", "https://y.qq.com/")
            .get()
            .build()

        dlClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return false

            val ctype = resp.header("Content-Type").orEmpty().substringBefore(';').lowercase()
            if (ctype.startsWith("text/") || ctype.contains("json") || ctype.contains("xml")) return false

            val body = resp.body ?: return false
            if (body.contentLength() > MAX_AUDIO_BYTES) return false

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > MAX_AUDIO_BYTES) return false
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
            }
        }
        return target.isFile && target.length() > 0L
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val AUDIO_EXTS = setOf("mp3", "m4a", "mp4", "flac", "ogg", "wav")
}
