package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Lyrics
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Repeat
import com.composables.icons.materialsymbols.outlined.Repeat_one
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Shuffle
import com.composables.icons.materialsymbols.outlined.Skip_next
import com.composables.icons.materialsymbols.outlined.Skip_previous
import dev.ujhhgtg.wekit.R
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal data class HomeSidePanelSong(
    val title: String,
    val artist: String,
    val dataUrl: String,
    val picUrl: String,
    val lyric: String,
    val lyricLines: List<String>,
    val lyricTimes: List<Long>,
    val mid: String,
    val songId: String,
    val mediamid: String,
)

internal data class HomeSidePanelMusicUiState(
    val keyword: String = "",
    val searching: Boolean = false,
    val error: String? = null,
    val playlist: List<HomeSidePanelSong> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val favorite: Boolean = false,
    val loopMode: Int = 0,
    val lyricDisplay: String = "",
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

internal object HomeSidePanelMusicController {

    private val _state = MutableStateFlow(HomeSidePanelMusicUiState())
    val state: StateFlow<HomeSidePanelMusicUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private var mediaPlayer: MediaPlayer? = null
    private var preparedUrl: String? = null
    private var lyricJob: Job? = null

    fun search(keyword: String, notFoundMessage: String, failedMessage: String) {
        val kw = keyword.trim()
        if (kw.isEmpty() || _state.value.searching) return
        _state.update { it.copy(keyword = kw, searching = true, error = null) }
        scope.launch {
            val song = withContext(Dispatchers.IO) { searchSong(kw) }
            if (song == null) {
                _state.update { it.copy(searching = false, error = notFoundMessage) }
                return@launch
            }
            val playlist = listOf(song) + _state.value.playlist.filter { it.songId != song.songId }
            _state.update {
                it.copy(
                    searching = false,
                    error = null,
                    playlist = playlist,
                    currentIndex = 0,
                    lyricDisplay = "",
                )
            }
            playCurrent(failedMessage)
        }
    }

    fun togglePlay(playTapMessage: String, failedMessage: String) {
        val s = _state.value
        val song = s.playlist.getOrNull(s.currentIndex) ?: run {
            _state.update { it.copy(error = playTapMessage) }
            return
        }
        if (s.isPreparing) return
        val mp = mediaPlayer
        if (s.isPlaying) {
            mp?.pause()
            _state.update { it.copy(isPlaying = false) }
            stopLyricSync()
            return
        }
        if (preparedUrl != null && preparedUrl == song.dataUrl && mp != null) {
            try {
                mp.start()
                _state.update { it.copy(isPlaying = true) }
                startLyricSync()
            } catch (_: Exception) {
                playMusicUrl(song.dataUrl, failedMessage)
            }
        } else {
            playMusicUrl(song.dataUrl, failedMessage)
        }
    }

    fun next(failedMessage: String) {
        val s = _state.value
        if (s.playlist.isEmpty()) return
        val idx = if (s.loopMode == 2) {
            (0 until s.playlist.size).random()
        } else {
            (s.currentIndex + 1).mod(s.playlist.size)
        }
        _state.update { it.copy(currentIndex = idx, lyricDisplay = "") }
        playCurrent(failedMessage)
    }

    fun prev(failedMessage: String) {
        val s = _state.value
        if (s.playlist.isEmpty()) return
        val idx = if (s.loopMode == 2) {
            (0 until s.playlist.size).random()
        } else {
            (s.currentIndex - 1 + s.playlist.size).mod(s.playlist.size)
        }
        _state.update { it.copy(currentIndex = idx, lyricDisplay = "") }
        playCurrent(failedMessage)
    }

    fun toggleFavorite() {
        _state.update { it.copy(favorite = !it.favorite) }
    }

    fun cycleLoopMode() {
        _state.update { it.copy(loopMode = (it.loopMode + 1) % 3) }
    }

    fun seekTo(positionMs: Int) {
        val mp = mediaPlayer ?: return
        try {
            mp.seekTo(positionMs)
            _state.update { it.copy(positionMs = positionMs) }
        } catch (_: Exception) {
        }
    }

    private fun playCurrent(failedMessage: String) {
        val song = _state.value.playlist.getOrNull(_state.value.currentIndex) ?: return
        if (song.dataUrl.isEmpty()) {
            _state.update { it.copy(error = failedMessage) }
            return
        }
        playMusicUrl(song.dataUrl, failedMessage)
    }

    private fun playMusicUrl(url: String, failedMessage: String) {
        val mp = mediaPlayer ?: MediaPlayer().also {
            it.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mediaPlayer = it
        }
        try {
            if (mp.isPlaying) mp.stop()
            mp.reset()
        } catch (_: Exception) {
        }
        preparedUrl = null
        _state.update { it.copy(isPlaying = false, isPreparing = true, error = null) }
        stopLyricSync()
        try {
            mp.setDataSource(url)
            mp.setOnPreparedListener { player ->
                player.isLooping = _state.value.loopMode == 1
                player.start()
                preparedUrl = url
                _state.update { it.copy(isPlaying = true, isPreparing = false) }
                startLyricSync()
            }
            mp.setOnCompletionListener {
                _state.update { it.copy(isPlaying = false) }
                stopLyricSync()
                when (_state.value.loopMode) {
                    0 -> next(failedMessage)
                    2 -> next(failedMessage)
                    else -> Unit
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                _state.update { it.copy(isPlaying = false, isPreparing = false) }
                stopLyricSync()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            _state.update { it.copy(isPlaying = false, isPreparing = false, error = failedMessage) }
        }
    }

    private fun startLyricSync() {
        stopLyricSync()
        lyricJob = scope.launch {
            while (true) {
                val mp = mediaPlayer
                val s = _state.value
                val song = s.playlist.getOrNull(s.currentIndex)
                if (mp == null || song == null || !s.isPlaying) {
                    delay(200)
                    continue
                }
                val pos = try {
                    mp.currentPosition
                } catch (_: Exception) {
                    -1
                }
                val dur = try {
                    mp.duration
                } catch (_: Exception) {
                    -1
                }
                _state.update {
                    it.copy(
                        positionMs = if (pos >= 0) pos else it.positionMs,
                        durationMs = if (dur >= 0) dur else it.durationMs,
                    )
                }
                val times = song.lyricTimes
                val lines = song.lyricLines
                if (times.isNotEmpty() && lines.isNotEmpty()) {
                    var idx = 0
                    for (i in times.indices) {
                        if (times[i] > pos) break
                        idx = i
                    }
                    val end = minOf(idx + 3, lines.size)
                    val sb = StringBuilder()
                    for (i in idx until end) {
                        if (i == idx) sb.append("▸ ")
                        sb.append(lines[i]).append("\n")
                    }
                    _state.update { it.copy(lyricDisplay = sb.toString().trim()) }
                }
                delay(200)
            }
        }
    }

    private fun stopLyricSync() {
        lyricJob?.cancel()
        lyricJob = null
    }

    // ==================== QQ 音乐 API（复刻原脚本请求方式） ====================

    private const val API_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val PIC_URL = "https://y.gtimg.cn/music/photo_new/T002R500x500M000"
    private const val LYRIC_URL =
        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1"
    private const val BASE_URL = "http://aqqmusic.tc.qq.com/"
    private const val MUSIC_UA = "Mozilla/5.0 Chrome/92.0.4515.105 Safari/537.36"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private fun searchSong(keyword: String): HomeSidePanelSong? {
        for (retry in 0..3) {
            if (retry > 0) {
                try {
                    Thread.sleep(3000)
                } catch (_: Exception) {
                }
            }
            try {
                val searchJson = buildSearchJson(keyword)
                val searchUrl = API_URL + "?data=" + URLEncoder.encode(searchJson, "UTF-8")
                val resp = syncGet(searchUrl) ?: continue

                val searchResult = JSONObject(resp)
                val music = searchResult.optJSONObject("searchMusic")
                    ?.optJSONObject("data")
                    ?.optJSONObject("body")
                    ?.optJSONObject("song")
                    ?.optJSONArray("list")
                    ?.optJSONObject(0)
                if (music == null) {
                    val code = searchResult.optJSONObject("searchMusic")?.optInt("code", -1)
                    if (code == 2001) continue
                    return null
                }

                val mid = music.getString("mid")
                val songId = music.getString("id")
                val title = music.getString("name")
                val singers = music.optJSONArray("singer")
                val artist = if (singers != null && singers.length() > 0) {
                    singers.optJSONObject(0).optString("name", "未知歌手")
                } else {
                    "未知歌手"
                }
                val pmid = music.optJSONObject("album")?.optString("pmid", "") ?: ""
                val picUrl = if (pmid.isEmpty()) "" else PIC_URL + pmid + ".jpg"
                val mediamid = music.optJSONObject("file")?.optString("media_mid", "") ?: ""

                val dataUrl = getMusicDataUrl(songId, mid, mediamid) ?: continue

                val rawLyric = getLyric(mid)
                val cleanLyric = cleanLyricText(rawLyric)
                val parsed = parseLyricTimes(rawLyric)

                return HomeSidePanelSong(
                    title = title,
                    artist = artist,
                    dataUrl = dataUrl,
                    picUrl = picUrl,
                    lyric = cleanLyric,
                    lyricLines = parsed.second,
                    lyricTimes = parsed.first,
                    mid = mid,
                    songId = songId,
                    mediamid = mediamid,
                )
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun getMusicDataUrl(songId: String, mid: String, mediamid: String): String? {
        val purlResp = syncPost(API_URL, buildPurlJson(songId))
        if (purlResp != null) {
            try {
                val purlObj = JSONObject(purlResp)
                val request = purlObj.optJSONObject("request")
                if (request != null && request.optInt("code") == 0) {
                    val tracks = request.optJSONObject("data")?.optJSONArray("tracks")
                    if (tracks != null && tracks.length() > 0) {
                        val ppurl = tracks.optJSONObject(0)
                            ?.optJSONObject("control")
                            ?.optString("ppurl", "")
                        if (!ppurl.isNullOrEmpty()) {
                            val vkeyResp = syncPost(API_URL, buildVkeyJson(mid, ppurl))
                            if (vkeyResp != null) {
                                val purl = JSONObject(vkeyResp)
                                    .optJSONObject("request")
                                    ?.optJSONObject("data")
                                    ?.optJSONObject("data")
                                    ?.optJSONObject("yun")
                                    ?.optString("purl", "")
                                if (!purl.isNullOrEmpty()) {
                                    return purl
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val getVkeyResp = syncPost(API_URL, buildGetVkeyJson(mid, mediamid))
        if (getVkeyResp != null) {
            try {
                val flowurl = JSONObject(getVkeyResp)
                    .optJSONObject("request")
                    ?.optJSONObject("data")
                    ?.optJSONArray("midurlinfo")
                    ?.optJSONObject(0)
                    ?.optString("flowurl", "")
                if (!flowurl.isNullOrEmpty()) {
                    return BASE_URL + flowurl
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun getLyric(mid: String): String {
        val resp = syncGet(LYRIC_URL + "&songmid=" + mid) ?: return ""
        return try {
            JSONObject(resp).optString("lyric", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanLyricText(raw: String?): String {
        if (raw.isNullOrEmpty()) return "暂无歌词"
        val processed = raw
            .replace("&#x0A;", "\n")
            .replace("&#x0D;", "\r")
            .replace("&#x20;", " ")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        val result = StringBuilder()
        for (line in processed.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val cleaned = trimmed.replaceFirst(Regex("^\\[\\d{2}:\\d{2}\\.\\d{2,3}\\]"), "").trim()
            if (cleaned.isNotEmpty()) result.append(cleaned).append("\n")
        }
        val finalResult = result.toString().trim()
        return finalResult.ifEmpty { "暂无歌词" }
    }

    private fun parseLyricTimes(raw: String?): Pair<List<Long>, List<String>> {
        val times = mutableListOf<Long>()
        val lines = mutableListOf<String>()
        if (!raw.isNullOrEmpty()) {
            val processed = raw
                .replace("&#x0A;", "\n")
                .replace("&#x0D;", "\r")
                .replace("&#x20;", " ")
                .replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            for (line in processed.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val m = Regex("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{2,3}))?\\]").find(trimmed) ?: continue
                val min = m.groupValues[1].toInt()
                val sec = m.groupValues[2].toInt()
                var ms = 0
                val msStr = m.groupValues[3]
                if (msStr.isNotEmpty()) {
                    ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
                }
                val time = (min * 60L + sec) * 1000L + ms
                val text = trimmed.replace(Regex("\\[.*?\\]"), "").trim()
                if (text.isNotEmpty()) {
                    times.add(time)
                    lines.add(text)
                }
            }
        }
        return times to lines
    }

    private fun syncGet(urlStr: String): String? = try {
        val request = Request.Builder()
            .url(urlStr)
            .header("User-Agent", MUSIC_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("referer", "http://aqqmusic.tc.qq.com/")
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (resp.code == 200) resp.body?.string()?.trim() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun syncPost(urlStr: String, jsonData: String): String? = try {
        val body = jsonData.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(urlStr)
            .post(body)
            .header("User-Agent", MUSIC_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("referer", "http://aqqmusic.tc.qq.com/")
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (resp.code == 200) resp.body?.string()?.trim() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun buildSearchJson(name: String): String = try {
        val root = JSONObject()
        val comm = JSONObject()
        comm.put("ct", "19")
        comm.put("cv", "1882")
        comm.put("uin", "3449496653")
        root.put("comm", comm)
        val searchMusic = JSONObject()
        searchMusic.put("method", "DoSearchForQQMusicDesktop")
        searchMusic.put("module", "music.search.SearchCgiService")
        val param = JSONObject()
        param.put("grp", 1)
        param.put("num_per_page", 1)
        param.put("page_num", 1)
        param.put("query", name)
        param.put("search_type", 0)
        searchMusic.put("param", param)
        root.put("searchMusic", searchMusic)
        root.toString()
    } catch (_: Exception) {
        "{}"
    }

    private fun buildPurlJson(idStr: String): String = try {
        val id = idStr.toLong()
        val root = JSONObject()
        val comm = JSONObject()
        comm.put("ct", "11")
        comm.put("cv", "22060004")
        comm.put("tmeAppID", "ztelite")
        comm.put("OpenUDID", "nouid")
        comm.put("uid", "3449496653")
        root.put("comm", comm)
        val request = JSONObject()
        request.put("module", "music.qqmusiclite.MtLimitFreeSvr")
        request.put("method", "Obtain")
        val param = JSONObject()
        param.put("songid", JSONArray().put(id))
        param.put("need_ppurl", true)
        request.put("param", param)
        root.put("request", request)
        root.toString()
    } catch (_: Exception) {
        "{}"
    }

    private fun buildVkeyJson(mid: String, pUrl: String): String = try {
        val root = JSONObject()
        val request = JSONObject()
        request.put("module", "music.vkey.GetVkey")
        request.put("method", "CgiGetTempVkey")
        val param = JSONObject()
        param.put("guid", "yun")
        val songItem = JSONObject()
        songItem.put("mediamid", "yun")
        songItem.put("tempVkey", pUrl)
        songItem.put("songMID", mid)
        param.put("songlist", JSONArray().put(songItem))
        request.put("param", param)
        root.put("request", request)
        root.toString()
    } catch (_: Exception) {
        "{}"
    }

    private fun buildGetVkeyJson(mid: String, mediamid: String): String = try {
        val root = JSONObject()
        val comm = JSONObject()
        comm.put("ct", "11")
        comm.put("cv", "22060004")
        comm.put("tmeAppID", "ztelite")
        comm.put("OpenUDID", "nouid")
        comm.put("uid", "3449496653")
        root.put("comm", comm)
        val request = JSONObject()
        request.put("module", "music.vkey.GetVkey")
        request.put("method", "UrlGetVkey")
        val param = JSONObject()
        param.put("guid", "yun")
        param.put("songmid", JSONArray().put(mid))
        param.put("filename", JSONArray().put("M500" + mediamid + ".mp3"))
        request.put("param", param)
        root.put("request", request)
        root.toString()
    } catch (_: Exception) {
        "{}"
    }
}private fun formatTime(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

@Composable
internal fun HomeSidePanelMusicCard(
    card: MusicCardConfig,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val state by HomeSidePanelMusicController.state.collectAsState()
    val notFoundMessage = stringResource(R.string.home_side_panel_music_not_found)
    val failedMessage = stringResource(R.string.home_side_panel_music_search_failed)
    val playTapMessage = stringResource(R.string.home_side_panel_music_tap_search)
    val searchHint = stringResource(R.string.home_side_panel_music_search_hint)

    var searchExpanded by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf(state.keyword) }
    var lyricsExpanded by remember { mutableStateOf(false) }

    val song = state.playlist.getOrNull(state.currentIndex)
    val title = song?.title ?: stringResource(R.string.home_side_panel_music_tap_search)
    val artist = song?.artist ?: ""

    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
    ) {
        val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MaterialSymbols.Outlined.Music_note,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor,
                )
                Text(
                    text = stringResource(R.string.home_side_panel_card_music),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                IconButton(
                    onClick = { searchExpanded = !searchExpanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Search,
                        contentDescription = stringResource(R.string.home_side_panel_music_search_hint),
                        modifier = Modifier.size(20.dp),
                        tint = contentColor,
                    )
                }
            }

            AnimatedVisibility(
                visible = searchExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(searchHint) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                HomeSidePanelMusicController.search(
                                    keyword,
                                    notFoundMessage,
                                    failedMessage,
                                )
                            },
                        ),
                    )
                    Button(
                        onClick = {
                            HomeSidePanelMusicController.search(
                                keyword,
                                notFoundMessage,
                                failedMessage,
                            )
                        },
                        modifier = Modifier.padding(start = 8.dp),
                        enabled = !state.searching,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = if (state.searching) {
                                stringResource(R.string.home_side_panel_music_searching)
                            } else {
                                stringResource(R.string.home_side_panel_music_search_hint)
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (song != null && song.picUrl.isNotEmpty()) {
                        AsyncImage(
                            model = song.picUrl,
                            contentDescription = title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(20.dp)),
                        )
                    } else {
                        Icon(
                            MaterialSymbols.Outlined.Music_note,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = contentColor.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (state.isPreparing) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                        Text(
                            text = stringResource(R.string.home_side_panel_music_loading),
                            modifier = Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.72f),
                        )
                    }
                } else if (song != null && state.lyricDisplay.isNotEmpty()) {
                    Text(
                        text = state.lyricDisplay,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { HomeSidePanelMusicController.toggleFavorite() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (state.favorite) {
                            MaterialTheme.colorScheme.error
                        } else {
                            contentColor.copy(alpha = 0.6f)
                        },
                    )
                }
                IconButton(
                    onClick = { HomeSidePanelMusicController.prev(failedMessage) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Skip_previous,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = contentColor,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            HomeSidePanelMusicController.togglePlay(
                                playTapMessage,
                                failedMessage,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) {
                            MaterialSymbols.Outlined.Pause
                        } else {
                            MaterialSymbols.Outlined.Play_arrow
                        },
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                IconButton(
                    onClick = { HomeSidePanelMusicController.next(failedMessage) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Skip_next,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = contentColor,
                    )
                }
                IconButton(
                    onClick = { HomeSidePanelMusicController.cycleLoopMode() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = when (state.loopMode) {
                            0 -> MaterialSymbols.Outlined.Repeat
                            1 -> MaterialSymbols.Outlined.Repeat_one
                            else -> MaterialSymbols.Outlined.Shuffle
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (state.loopMode == 0) {
                            contentColor.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }

            if (song != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Slider(
                        value = state.positionMs.toFloat().coerceAtMost(
                            state.durationMs.toFloat().coerceAtLeast(1f),
                        ),
                        onValueChange = { HomeSidePanelMusicController.seekTo(it.toInt()) },
                        valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = contentColor.copy(alpha = 0.2f),
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatTime(state.positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.72f),
                        )
                        Text(
                            text = formatTime(state.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.72f),
                        )
                    }
                }
            }

            if (song != null && song.lyric.isNotEmpty() && song.lyric != "暂无歌词") {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { lyricsExpanded = !lyricsExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            MaterialSymbols.Outlined.Lyrics,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = contentColor.copy(alpha = 0.72f),
                        )
                        Text(
                            text = if (lyricsExpanded) {
                                stringResource(R.string.home_side_panel_music_lyric_collapse)
                            } else {
                                stringResource(R.string.home_side_panel_music_lyric_expand)
                            },
                            modifier = Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor.copy(alpha = 0.85f),
                        )
                    }
                    AnimatedVisibility(
                        visible = lyricsExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Text(
                            text = song.lyric,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.9f),
                        )
                    }
                }
            }

            val errorText = state.error
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}