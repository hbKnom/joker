package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Text_to_speech
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MultiSelectSupport
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 魔方文转音（WeKit 原生移植）
 *
 * 完整照搬原脚本的第三方接口：peiyinmofang.com 开放接口
 *   GET  /api/open/v1/voices、/api/open/v1/user-voices
 *   POST /api/open/v1/tts/simple-generate，请求体 {"voiceId":"...","text":"..."}
 *   Authorization: Bearer <key> + X-API-Key: <key>
 * 默认 API Key 与默认音色沿用 config.prop：
 *   mofang_api_key = vbox-0b1d9db7132465c5e274357f0c5913c6-d20414b7
 *   mofang_default_voice_id = 狂飙-高启强
 * 合成后下载音频，用 [AudioUtils.anyToSilk] 转 SILK，再 [WeMessageApi.sendVoice] 发送。
 */
object MofangTTS : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "魔方文转音"
    override val nameRes = R.string.feature_mofang_tts_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_mofang_tts_description

    private const val TAG = "MofangTTS"
    private const val MENU_ID = 777271
    private const val BASE_URL = "https://peiyinmofang.com"
    private const val KEY_API_KEY = "mofang_api_key"
    private const val KEY_DEFAULT_VOICE_ID = "mofang_default_voice_id"
    private const val KEY_DEFAULT_VOICE_NAME = "mofang_default_voice_name"
    private const val KEY_BUILTIN_VOICES = "mofang_builtin_voices"
    private const val KEY_USER_VOICES = "mofang_user_voices"
    private const val DEFAULT_API_KEY = "vbox-0b1d9db7132465c5e274357f0c5913c6-d20414b7"
    private const val DEFAULT_VOICE_ID = "狂飙-高启强"
    private const val DEFAULT_VOICE_NAME = "狂飙 / 高启强 · 张颂文"

    private data class VoiceChoice(val label: String, val voiceId: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDir by lazy { File(HostInfo.application.cacheDir, "mofang").apply { mkdirs() } }

    override fun onEnable() = WeChatMessageContextMenuApi.addProvider(this)
    override fun onDisable() = WeChatMessageContextMenuApi.removeProvider(this)

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ID,
            text = "语音",
            drawable = MenuIcons.res(R.drawable.ic_menu_voice),
            imageVector = MaterialSymbols.Outlined.Text_to_speech,
            isSupported = { true },
            multiSelect = MultiSelectSupport.Unsupported,
            onClick = { view, _, msgInfo ->
                val talker = msgInfo.talker
                if (talker.isEmpty()) {
                    showToast("未取得会话对象")
                    return@MenuItem
                }
                showMainMenu(view, talker)
            },
        )
    )

    // ---------------- 配置 ----------------

    private fun apiKey(): String = WePrefs.getString(KEY_API_KEY).orEmpty().ifBlank { DEFAULT_API_KEY }

    private fun defaultVoiceId(): String =
        WePrefs.getString(KEY_DEFAULT_VOICE_ID).orEmpty().ifBlank { DEFAULT_VOICE_ID }

    private fun defaultVoiceName(): String =
        WePrefs.getString(KEY_DEFAULT_VOICE_NAME).orEmpty().ifBlank { DEFAULT_VOICE_NAME }

    private fun saveDefaultVoice(voiceId: String, name: String) {
        WePrefs.putString(KEY_DEFAULT_VOICE_ID, voiceId)
        WePrefs.putString(KEY_DEFAULT_VOICE_NAME, name.ifBlank { voiceId })
    }

    private fun authHeaders(): Map<String, String> {
        val key = apiKey()
        return mapOf(
            "Authorization" to "Bearer $key",
            "X-API-Key" to key,
            "Accept" to "application/json",
        )
    }

    // ---------------- HTTP ----------------

    private fun httpGet(url: String, headers: Map<String, String>, timeoutSec: Long): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            val resp = client.newCall(builder.build()).execute()
            resp.use { if (!it.isSuccessful) null else it.body?.string() }
        } catch (e: Exception) {
            WeLogger.e(TAG, "httpGet failed", e)
            null
        }
    }

    private fun httpPostJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val builder = Request.Builder().url(url).post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            headers.forEach { (k, v) -> builder.header(k, v) }
            val resp = client.newCall(builder.build()).execute()
            resp.use { if (!it.isSuccessful) null else it.body?.string() }
        } catch (e: Exception) {
            WeLogger.e(TAG, "httpPostJson failed", e)
            null
        }
    }

    private fun downloadAudio(url: String, output: File, useAuth: Boolean): File? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val builder = Request.Builder().url(url)
            if (useAuth) authHeaders().forEach { (k, v) -> builder.header(k, v) }
            val resp = client.newCall(builder.build()).execute()
            resp.use {
                if (!it.isSuccessful) return null
                val body = it.body ?: return null
                output.outputStream().use { out -> body.byteStream().use { src -> src.copyTo(out) } }
            }
            output
        } catch (e: Exception) {
            WeLogger.e(TAG, "downloadAudio failed", e)
            null
        }
    }

    // ---------------- 合成并发送 ----------------

    private fun generateAndSend(talker: String, text: String, voiceId: String) {
        scope.launch {
            val finalVoiceId = voiceId.ifBlank { DEFAULT_VOICE_ID }
            val finalText = text.trim()
            if (finalText.isEmpty()) {
                withContext(Dispatchers.Main) { showToast("文字不能为空") }
                return@launch
            }
            val body = JSONObject().put("voiceId", finalVoiceId).put("text", finalText).toString()
            val resp = httpPostJson("$BASE_URL/api/open/v1/tts/simple-generate", body, authHeaders(), 120000)
            if (resp == null) {
                withContext(Dispatchers.Main) { showToast("合成请求失败：无响应") }
                return@launch
            }
            var audioUrl = ""
            var format = "wav"
            var message = ""
            try {
                val root = JSONObject(resp)
                message = root.optString("message", "")
                val data = root.optJSONObject("data")
                if (data != null) {
                    audioUrl = data.optString("audio", "")
                    format = data.optString("format", "wav").ifBlank { "wav" }
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "parse generate resp failed", e)
            }
            if (audioUrl.isEmpty()) {
                withContext(Dispatchers.Main) { showToast("合成失败：" + (message.ifBlank { "未取得音频链接" })) }
                return@launch
            }

            val ext = if (format.lowercase() == "mp3") "mp3" else "wav"
            val audioPath = File(cacheDir, "mofang_${System.currentTimeMillis()}.$ext")
            withContext(Dispatchers.Main) { showToast("合成成功，正在生成语音…") }

            var audioFile = downloadAudio(audioUrl, audioPath, false)
            if (audioFile == null || !audioFile.isFile || audioFile.length() == 0L) {
                audioFile = downloadAudio(audioUrl, audioPath, true)
            }
            if (audioFile == null || !audioFile.isFile || audioFile.length() == 0L) {
                withContext(Dispatchers.Main) { showToast("音频下载失败") }
                return@launch
            }

            val silkPath = audioFile.path + ".silk"
            val converted = AudioUtils.anyToSilk(audioFile.path, silkPath)
            if (!converted) {
                withContext(Dispatchers.Main) { showToast("音频转 SILK 失败") }
                return@launch
            }
            val durationMs = AudioUtils.getDurationMs(silkPath).toInt().coerceAtLeast(1000)
            val sent = withContext(Dispatchers.Main) { WeMessageApi.sendVoice(talker, silkPath, durationMs) }
            withContext(Dispatchers.Main) { showToast(if (sent) "语音已发送" else "语音发送提交失败") }
        }
    }

    // ---------------- 音色 ----------------

    private fun parseBuiltin(raw: String): List<VoiceChoice> {
        val result = mutableListOf<VoiceChoice>()
        try {
            val root = JSONObject(raw)
            val data = root.optJSONArray("data") ?: return result
            for (i in 0 until data.length()) {
                val group = data.optJSONObject(i) ?: continue
                val title = group.optString("title", "")
                val chars = group.optJSONArray("characters") ?: continue
                for (j in 0 until chars.length()) {
                    val ch = chars.optJSONObject(j) ?: continue
                    val name = ch.optString("name", "")
                    val actor = ch.optString("actor", "")
                    val voiceId = ch.optString("voice_id", "")
                    if (voiceId.isEmpty()) continue
                    val label = title + " / " + name + (if (actor.isEmpty()) "" else " · " + actor)
                    result.add(VoiceChoice(label, voiceId))
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "parseBuiltin failed", e)
        }
        return result
    }

    private fun parseUserVoices(raw: String): List<VoiceChoice> {
        val result = mutableListOf<VoiceChoice>()
        try {
            val root = JSONObject(raw)
            val data = root.optJSONArray("data") ?: return result
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val name = item.optString("name", "自定义音色")
                val voiceId = item.optString("voice_id", "")
                val type = if (item.optInt("audio_type", 0) == 2) "录制" else "上传"
                if (voiceId.isNotEmpty()) result.add(VoiceChoice("$name · $type", voiceId))
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "parseUserVoices failed", e)
        }
        return result
    }

    private fun loadBuiltinChoices(): List<VoiceChoice> = parseBuiltin(WePrefs.getString(KEY_BUILTIN_VOICES).orEmpty())

    private fun loadUserChoices(): List<VoiceChoice> = parseUserVoices(WePrefs.getString(KEY_USER_VOICES).orEmpty())

    // ---------------- UI ----------------

    private fun showMainMenu(view: View, talker: String) {
        showComposeDialog(view.context) {
            AlertDialogContent(
                title = { Text("魔方文转音") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                iconPlaceholder = true,
                                title = "合成语音",
                                description = "输入文字，用当前音色合成并发送语音",
                                onClick = { onDismiss(); showTextInput(view, talker) },
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                iconPlaceholder = true,
                                title = "更换音色",
                                description = "当前：${defaultVoiceName()}",
                                onClick = { onDismiss(); showVoiceChooser(view, talker) },
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                iconPlaceholder = true,
                                title = "API Key 设置",
                                description = "设置 peiyinmofang.com 的 API Key",
                                onClick = { onDismiss(); showApiKeyInput(view) },
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                iconPlaceholder = true,
                                title = "帮助",
                                description = "查看当前配置与接口说明",
                                onClick = { onDismiss(); showHelp(view) },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun showTextInput(view: View, talker: String) {
        showComposeDialog(view.context) {
            var text by remember { mutableStateOf("") }
            AlertDialogContent(
                title = { Text("合成语音 · ${defaultVoiceName()}") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("要合成的文字") },
                        placeholder = { Text("输入要转成语音的文字") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        val t = text.trim()
                        if (t.isEmpty()) {
                            showToast("文字不能为空")
                        } else {
                            onDismiss()
                            generateAndSend(talker, t, defaultVoiceId())
                        }
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
            )
        }
    }

    private fun showVoiceChooser(view: View, talker: String) {
        val builtin = loadBuiltinChoices()
        val user = loadUserChoices()
        showComposeDialog(view.context) {
            var query by remember { mutableStateOf("") }
            val needle = query.trim().lowercase()
            val filtered = remember(query) {
                (builtin + user).filter { needle.isEmpty() || it.label.lowercase().contains(needle) || it.voiceId.lowercase().contains(needle) }
            }
            AlertDialogContent(
                title = { Text("更换音色") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("搜索音色") },
                            placeholder = { Text("影视剧 / 角色 / 演员 / voice_id") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Text_to_speech,
                                    iconPlaceholder = true,
                                    title = "刷新内置音色",
                                    description = "从服务端重新拉取内置音色列表",
                                    onClick = { onDismiss(); refreshVoices(view, true) },
                                    trailingDivider = true,
                                )
                            }
                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Text_to_speech,
                                    iconPlaceholder = true,
                                    title = "手动设置 voiceId",
                                    description = "内置格式：琅琊榜-梅长苏；自定义：user-123",
                                    onClick = { onDismiss(); showVoiceIdInput(view) },
                                    trailingDivider = true,
                                )
                            }
                            items(filtered) { choice ->
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Text_to_speech,
                                    iconPlaceholder = true,
                                    title = choice.label,
                                    description = choice.voiceId,
                                    onClick = {
                                        saveDefaultVoice(choice.voiceId, choice.label.substringBefore('\n'))
                                        onDismiss()
                                        showToast("已切换音色：${choice.label}")
                                    },
                                    trailingDivider = true,
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun refreshVoices(view: View, builtin: Boolean) {
        scope.launch {
            val url = if (builtin) "$BASE_URL/api/open/v1/voices" else "$BASE_URL/api/open/v1/user-voices"
            val raw = httpGet(url, authHeaders(), 60)
            if (raw == null) {
                withContext(Dispatchers.Main) { showToast("获取音色失败，请检查 API Key 与网络") }
                return@launch
            }
            WePrefs.putString(if (builtin) KEY_BUILTIN_VOICES else KEY_USER_VOICES, raw)
            val count = (if (builtin) parseBuiltin(raw) else parseUserVoices(raw)).size
            withContext(Dispatchers.Main) {
                showToast("已刷新 $count 个音色")
                showVoiceChooser(view, "")
            }
        }
    }

    private fun showVoiceIdInput(view: View) {
        showComposeDialog(view.context) {
            var id by remember { mutableStateOf(defaultVoiceId()) }
            AlertDialogContent(
                title = { Text("手动设置 voiceId") },
                text = {
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        label = { Text("voiceId") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        val v = id.trim()
                        if (v.isEmpty()) {
                            showToast("voiceId 不能为空")
                        } else {
                            saveDefaultVoice(v, v)
                            onDismiss()
                            showToast("已设置 voiceId：$v")
                        }
                    }) { Text(stringResource(R.string.action_save)) }
                },
            )
        }
    }

    private fun showApiKeyInput(view: View) {
        showComposeDialog(view.context) {
            var key by remember { mutableStateOf(WePrefs.getString(KEY_API_KEY).orEmpty()) }
            AlertDialogContent(
                title = { Text("魔方 API Key") },
                text = {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        val k = key.trim()
                        if (k.isEmpty()) {
                            showToast("API Key 不能为空")
                        } else {
                            onDismiss()
                            verifyApiKey(k)
                        }
                    }) { Text(stringResource(R.string.action_save)) }
                },
            )
        }
    }

    private fun verifyApiKey(key: String) {
        scope.launch {
            val headers = mapOf("Authorization" to "Bearer $key", "X-API-Key" to key, "Accept" to "application/json")
            val raw = httpGet("$BASE_URL/api/open/v1/me", headers, 60)
            withContext(Dispatchers.Main) {
                if (raw == null) {
                    showToast("Key 校验失败：无响应")
                } else {
                    WePrefs.putString(KEY_API_KEY, key)
                    showToast("Key 校验成功，已保存")
                }
            }
        }
    }

    private fun showHelp(view: View) {
        val key = apiKey()
        val masked = if (key.length > 8) key.take(6) + "…" + key.takeLast(4) else key
        showComposeDialog(view.context) {
            AlertDialogContent(
                title = { Text("魔方文转音") },
                text = {
                    Text(
                        "API Key：$masked\n默认音色：${defaultVoiceName()}\nvoiceId：${defaultVoiceId()}\n\n" +
                            "接口：peiyinmofang.com\n" +
                            "  GET /api/open/v1/voices\n" +
                            "  GET /api/open/v1/user-voices\n" +
                            "  POST /api/open/v1/tts/simple-generate\n\n" +
                            "合成请求体：{\"voiceId\":\"…\",\"text\":\"…\"}\n请求头：Authorization: Bearer <key>",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }
}
