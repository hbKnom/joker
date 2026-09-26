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
import com.composables.icons.materialsymbols.outlined.Comedy_mask
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MultiSelectSupport
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 制作表情（WeKit 原生移植）
 *
 * 完整照搬原脚本「制作表情」的请求方式与关键词表（见 [EmojiKeywords]），未做任何增删。
 * 菜单命名「表情」，长按对方消息后选择表情源与动作，提交对方头像链接生成 GIF / 图片并发送。
 */
object MakeEmoji : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "制作表情"
    override val nameRes = R.string.feature_make_emoji_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_make_emoji_description

    private const val TAG = "MakeEmoji"
    private const val MENU_ID = 777270
    private const val FREE_API_BASE = "https://ovoav.com/api/jhbq/bq?key=6otJCIyTDGfBu"
    private const val EQUAL_API_BASE = "https://apix.iqfk.top/api/sv1"
    private const val EQUAL_API_BASE_SV2 = "https://apix.iqfk.top/api/sv2"
    private const val EQUAL_API_KEY = "6f0b407213ab2d7c"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDir by lazy { File(HostInfo.application.cacheDir, "emoji").apply { mkdirs() } }

    override fun onEnable() = WeChatMessageContextMenuApi.addProvider(this)
    override fun onDisable() = WeChatMessageContextMenuApi.removeProvider(this)

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ID,
            text = "表情",
            drawable = MenuIcons.res(R.drawable.ic_menu_emoji),
            imageVector = MaterialSymbols.Outlined.Comedy_mask,
            isSupported = { msg -> !msg.isSelfSender },
            multiSelect = MultiSelectSupport.Unsupported,
            onClick = { view, _, msgInfo ->
                val talker = msgInfo.talker
                val group = msgInfo.isInGroupChat
                val sender = if (group) msgInfo.sender else msgInfo.talker
                if (sender.isEmpty() || (group && (sender == talker))) {
                    showToast("未解析到消息发送者，请长按对方消息后重试")
                    return@MenuItem
                }
                showSourceDialog(view, talker, sender)
            },
        )
    )

    // ---------------- 请求 URL 构造（原脚本照搬） ----------------

    private fun buildFreeApiUrl(keyword: String, avatarUrl: String): String =
        FREE_API_BASE + "&msg=" + URLEncoder.encode(keyword, "UTF-8") +
            "&url1=" + URLEncoder.encode(avatarUrl, "UTF-8") +
            "&url2=&url3=&txte1=&txte2=&txte3=&id="

    private fun buildFreeApiUrl(keyword: String, avatarUrl1: String, avatarUrl2: String): String =
        FREE_API_BASE + "&msg=" + URLEncoder.encode(keyword, "UTF-8") +
            "&url1=" + URLEncoder.encode(avatarUrl1, "UTF-8") +
            "&url2=" + URLEncoder.encode(avatarUrl2, "UTF-8") +
            "&url3=&txte1=&txte2=&txte3=&id="

    private fun buildEqualApiUrl(meme: String, avatarUrl: String): String =
        EQUAL_API_BASE + "?meme=" + URLEncoder.encode(meme, "UTF-8") +
            "&url=" + URLEncoder.encode(avatarUrl, "UTF-8") +
            "&key=" + EQUAL_API_KEY

    private fun buildEqualApiUrlSv2(meme: String, avatarUrl1: String, avatarUrl2: String): String =
        EQUAL_API_BASE_SV2 + "?meme=" + URLEncoder.encode(meme, "UTF-8") +
            "&url1=" + URLEncoder.encode(avatarUrl1, "UTF-8") +
            "&url2=" + URLEncoder.encode(avatarUrl2, "UTF-8")

    // ---------------- 下载 / 校验 / 发送 ----------------

    private fun download(url: String, output: File, headers: Map<String, String>, timeoutSec: Long): File? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            val resp = client.newCall(builder.build()).execute()
            resp.use {
                if (!it.isSuccessful) return null
                val body = it.body ?: return null
                output.outputStream().use { out -> body.byteStream().use { src -> src.copyTo(out) } }
            }
            output
        } catch (e: Exception) {
            WeLogger.e(TAG, "download failed", e)
            null
        }
    }

    private fun isGif(file: File): Boolean {
        if (!file.isFile || file.length() < 6) return false
        return try {
            val head = ByteArray(6)
            file.inputStream().use { if (it.read(head) != 6) return false }
            val text = String(head, Charsets.US_ASCII)
            text == "GIF87a" || text == "GIF89a"
        } catch (e: Exception) {
            false
        }
    }

    private fun isImage(file: File): Boolean {
        if (!file.isFile || file.length() < 8) return false
        return try {
            val head = ByteArray(12)
            file.inputStream().use { val count = it.read(head); if (count < 8) return false }
            (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4e.toByte() && head[3] == 0x47.toByte()) ||
                (head[0] == 0xff.toByte() && head[1] == 0xd8.toByte()) ||
                (head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                    head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() && head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte())
        } catch (e: Exception) {
            false
        }
    }

    private fun requestFree(talker: String, sender: String, display: String, key: String, dual: Boolean) {
        scope.launch {
            val keyword = EmojiKeywords.FREE_KEYWORD_MSG[key]
            if (keyword.isNullOrEmpty()) {
                withContext(Dispatchers.Main) { showToast("不支持的表情关键词") }
                return@launch
            }
            val output = File(cacheDir, "free_${System.currentTimeMillis()}.gif")
            val apiUrl: String
            if (dual) {
                val self = WeApi.selfWxId
                val a1 = self.takeIf { it.isNotEmpty() }?.let { WeDatabaseApi.getAvatarUrl(it) }.orEmpty()
                val a2 = WeDatabaseApi.getAvatarUrl(sender)
                if (a1.isEmpty() || a2.isEmpty()) {
                    withContext(Dispatchers.Main) { showToast("未获取到头像，无法生成双人表情") }
                    return@launch
                }
                apiUrl = buildFreeApiUrl(keyword, a1, a2)
            } else {
                val avatarUrl = WeDatabaseApi.getAvatarUrl(sender)
                if (avatarUrl.isEmpty()) {
                    withContext(Dispatchers.Main) { showToast("未获取到对方头像，无法生成表情") }
                    return@launch
                }
                apiUrl = buildFreeApiUrl(keyword, avatarUrl)
            }
            withContext(Dispatchers.Main) { showToast("正在生成「$display」表情…") }

            val file = download(apiUrl, output, mapOf("User-Agent" to "Mozilla/5.0"), 60)
            if (file == null || !isGif(file)) {
                file?.delete()
                withContext(Dispatchers.Main) { showToast("生成失败：服务未返回 GIF 表情") }
                return@launch
            }
            val sent = withContext(Dispatchers.Main) { WeMessageApi.sendEmoji(talker, file.path) }
            withContext(Dispatchers.Main) { showToast(if (sent) "表情已发送" else "表情发送提交失败") }
        }
    }

    private fun requestEqual(talker: String, sender: String, display: String, meme: String, dual: Boolean) {
        scope.launch {
            if (meme.isBlank()) {
                withContext(Dispatchers.Main) { showToast("不支持的平等表情关键词") }
                return@launch
            }
            val output = File(cacheDir, "equal_${System.currentTimeMillis()}.tmp")
            val headers = HashMap<String, String>()
            headers["User-Agent"] = "Mozilla/5.0"
            val apiUrl: String
            if (dual) {
                val self = WeApi.selfWxId
                val a1 = self.takeIf { it.isNotEmpty() }?.let { WeDatabaseApi.getAvatarUrl(it) }.orEmpty()
                val a2 = WeDatabaseApi.getAvatarUrl(sender)
                if (a1.isEmpty() || a2.isEmpty()) {
                    withContext(Dispatchers.Main) { showToast("未获取到头像，无法生成双人表情") }
                    return@launch
                }
                val useSv2 = EmojiKeywords.EQUAL_SV2_MEMES.contains(meme)
                apiUrl = if (useSv2) {
                    headers["Authorization"] = "Bearer $EQUAL_API_KEY"
                    buildEqualApiUrlSv2(meme, a1, a2)
                } else {
                    buildEqualApiUrl(meme, a2)
                }
            } else {
                val avatarUrl = WeDatabaseApi.getAvatarUrl(sender)
                if (avatarUrl.isEmpty()) {
                    withContext(Dispatchers.Main) { showToast("未获取到对方头像，无法生成表情") }
                    return@launch
                }
                apiUrl = buildEqualApiUrl(meme, avatarUrl)
            }
            withContext(Dispatchers.Main) { showToast("正在生成「$display」表情…") }

            val file = download(apiUrl, output, headers, if (dual) 60 else 30)
            if (file == null) {
                withContext(Dispatchers.Main) { showToast("表情生成请求失败") }
                return@launch
            }
            val sent: Boolean
            if (isGif(file)) {
                sent = withContext(Dispatchers.Main) { WeMessageApi.sendEmoji(talker, file.path) }
            } else if (isImage(file)) {
                sent = withContext(Dispatchers.Main) { WeMessageApi.sendImage(talker, file.path) }
            } else {
                withContext(Dispatchers.Main) { showToast("生成失败：服务未返回图片或 GIF 表情") }
                return@launch
            }
            withContext(Dispatchers.Main) { showToast(if (sent) "表情已发送" else "表情发送提交失败") }
        }
    }

    // ---------------- UI ----------------

    private fun showSourceDialog(view: View, talker: String, sender: String) {
        showComposeDialog(view.context) {
            AlertDialogContent(
                title = { Text("选择表情源") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Comedy_mask,
                                iconPlaceholder = true,
                                title = "自由源 · 单人 GIF",
                                description = "ovoav 服务，提交对方头像链接",
                                onClick = { onDismiss(); showKeywordDialog(view, talker, sender, Source.FREE, false) },
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Comedy_mask,
                                iconPlaceholder = true,
                                title = "自由源 · 双人 GIF",
                                description = "ovoav 服务，提交自己与对方头像链接",
                                onClick = { onDismiss(); showKeywordDialog(view, talker, sender, Source.FREE, true) },
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Comedy_mask,
                                iconPlaceholder = true,
                                title = "平等源 · 单人图片/GIF",
                                description = "apix.iqfk.top 服务，提交对方头像链接",
                                onClick = { onDismiss(); showKeywordDialog(view, talker, sender, Source.EQUAL, false) },
                                trailingDivider = true,
                            )
                        }
                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Comedy_mask,
                                iconPlaceholder = true,
                                title = "平等源 · 双人图片/GIF",
                                description = "apix.iqfk.top 服务，提交自己与对方头像链接",
                                onClick = { onDismiss(); showKeywordDialog(view, talker, sender, Source.EQUAL, true) },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private enum class Source { FREE, EQUAL }

    private fun showKeywordDialog(view: View, talker: String, sender: String, source: Source, dual: Boolean) {
        val keywords = when (source) {
            Source.FREE -> if (dual) EmojiKeywords.FREE_DUAL else EmojiKeywords.FREE_SINGLE
            Source.EQUAL -> if (dual) EmojiKeywords.EQUAL_DUAL else EmojiKeywords.EQUAL_SINGLE
        }
        val title = (if (source == Source.FREE) "自由表情" else "平等表情") + (if (dual) " · 双人" else " · 单人")

        showComposeDialog(view.context) {
            var query by remember { mutableStateOf("") }
            val filtered = remember(query) {
                val needle = query.trim().lowercase()
                keywords.filter { (d, k) ->
                    needle.isEmpty() || d.lowercase().contains(needle) || k.lowercase().contains(needle)
                }.sortedBy { it.first }
            }
            AlertDialogContent(
                title = { Text(title) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("搜索动作") },
                            placeholder = { Text("例如：吃下、拍拍、贴贴") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(filtered) { (display, key) ->
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Comedy_mask,
                                    iconPlaceholder = true,
                                    title = display,
                                    onClick = {
                                        onDismiss()
                                        showConfirmDialog(view, talker, sender, source, dual, display, key)
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

    private fun showConfirmDialog(
        view: View,
        talker: String,
        sender: String,
        source: Source,
        dual: Boolean,
        display: String,
        key: String,
    ) {
        showComposeDialog(view.context) {
            AlertDialogContent(
                title = { Text(if (dual) "双人表情确认" else "确认生成表情") },
                text = {
                    Text(
                        (if (dual) "将使用「$display」生成双人表情。\n头像一：自己（默认）\n头像二：对方（默认）\n是否继续？"
                        else "将使用「$display」并向表情服务提交对方头像链接。是否继续？"),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        onDismiss()
                        when (source) {
                            Source.FREE -> requestFree(talker, sender, display, key, dual)
                            Source.EQUAL -> requestEqual(talker, sender, display, key, dual)
                        }
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
            )
        }
    }
}
