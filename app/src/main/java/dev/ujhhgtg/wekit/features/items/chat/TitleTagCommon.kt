package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Label
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.ColorPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import java.util.concurrent.ConcurrentHashMap

// ================= 样式（原 4 种 + 扩展 8 种，支持渐变与自定义颜色） =================

internal data class TitleStyle(val name: String, val bgStart: Int, val bgEnd: Int, val fg: Int)

internal val TITLE_PRESETS = listOf(
    TitleStyle("鎏金侍卫", 0xFF80520F.toInt(), 0xFFD9A441.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("赤焰金章", 0xFFD32F2F.toInt(), 0xFFFF9800.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("翡翠守卫", 0xFF00695C.toInt(), 0xFF43A047.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("星夜密令", 0xFF1A237E.toInt(), 0xFF8E24AA.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("樱花落", 0xFFE91E63.toInt(), 0xFFFF80AB.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("暗夜蔷薇", 0xFF4A148C.toInt(), 0xFF7B1FA2.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("深海流光", 0xFF0D47A1.toInt(), 0xFF0288D1.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("墨玉鎏金", 0xFF37474F.toInt(), 0xFF607D8B.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("紫霞流云", 0xFF6A1B9A.toInt(), 0xFFAB47BC.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("青花瓷韵", 0xFF0D47A1.toInt(), 0xFF26C6DA.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("落日熔金", 0xFFE65100.toInt(), 0xFFFFB300.toInt(), 0xFFFFFFFF.toInt()),
    TitleStyle("冰晶极光", 0xFF006064.toInt(), 0xFF80CBC4.toInt(), 0xFFFFFFFF.toInt()),
)

internal data class TitleEntry(
    val title: String,
    val style: Int,
    val customBgStart: Int? = null,
    val customBgEnd: Int? = null,
    val customFg: Int? = null,
)

internal fun titleKeyOf(group: Boolean, room: String, wxid: String): String =
    (if (group) "G" else "P") + "\u0001" + (if (group) room else "") + "\u0001" + wxid

internal fun titleEncode(value: String): String =
    android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

internal fun titleDecode(value: String): String =
    try {
        String(android.util.Base64.decode(value, android.util.Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

internal fun normalizeTitleStyle(style: Int) = if (style < 0 || style >= TITLE_PRESETS.size) 0 else style

/** 头衔配置存储（「头衔下」「头衔上」各自独立实例，互不干扰）。 */
internal class TitleStore(private val prefKey: String, private val tag: String) {

    val titles = ConcurrentHashMap<String, TitleEntry>()

    fun load() {
        titles.clear()
        val raw = WePrefs.getString(prefKey) ?: return
        if (raw.isBlank()) return
        try {
            for (row in raw.split("\n")) {
                val p = row.split("|")
                if (p.size < 5) continue
                val group = titleDecode(p[0]) == "G"
                val room = titleDecode(p[1])
                val wxid = titleDecode(p[2])
                val title = titleDecode(p[3]).trim()
                val style = titleDecode(p[4]).toIntOrNull() ?: 0
                if (wxid.isEmpty()) continue
                titles[titleKeyOf(group, room, wxid)] = TitleEntry(title, normalizeTitleStyle(style))
            }
        } catch (e: Exception) {
            WeLogger.e(tag, "loadTitles failed", e)
        }
    }

    fun save() {
        try {
            val sb = StringBuilder()
            for ((key, entry) in titles) {
                val p = key.split("\u0001")
                if (p.size != 3) continue
                val group = p[0] == "G"
                val room = p[1]
                val wxid = p[2]
                if (wxid.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(titleEncode(if (group) "G" else "P")).append("|")
                    .append(titleEncode(room)).append("|")
                    .append(titleEncode(wxid)).append("|")
                    .append(titleEncode(entry.title)).append("|")
                    .append(titleEncode(normalizeTitleStyle(entry.style).toString()))
            }
            WePrefs.putString(prefKey, sb.toString())
        } catch (e: Exception) {
            WeLogger.e(tag, "saveTitles failed", e)
            showToast("${tag}保存失败，请查看日志")
        }
    }

    fun entryFor(group: Boolean, room: String, wxid: String): TitleEntry? =
        titles[titleKeyOf(group, room, wxid)]

    fun set(group: Boolean, room: String, wxid: String, entry: TitleEntry) {
        val key = titleKeyOf(group, room, wxid)
        if (entry.title.isBlank()) titles.remove(key) else titles[key] = entry
        save()
    }
}

/** 把样式应用到独立 TextView（原版脚本 applyStyle 行为：渐变圆角描边）。 */
internal fun applyTitleStyle(view: TextView, entry: TitleEntry) {
    val preset = TITLE_PRESETS[normalizeTitleStyle(entry.style)]
    val bgStart = entry.customBgStart ?: preset.bgStart
    val bgEnd = entry.customBgEnd ?: preset.bgEnd
    val fg = entry.customFg ?: preset.fg
    val density = view.resources.displayMetrics.density

    val bg = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(bgStart, bgEnd),
    )
    bg.setCornerRadius(9f * density + 0.5f)
    bg.setStroke((1f * density + 0.5f).toInt(), Color.argb(180, 255, 239, 190))
    view.background = bg
    view.setTextColor(fg)
    view.textSize = 10f
    view.typeface = Typeface.DEFAULT_BOLD
    view.setSingleLine(true)
    view.includeFontPadding = false
    view.gravity = Gravity.CENTER
    view.setPadding(
        (7f * density + 0.5f).toInt(),
        (2f * density + 0.5f).toInt(),
        (7f * density + 0.5f).toInt(),
        (2f * density + 0.5f).toInt(),
    )
    view.setShadowLayer(
        1f * density + 0.5f,
        0f,
        1f * density + 0.5f,
        Color.argb(120, 0, 0, 0),
    )
}

internal fun titleDisplayName(group: Boolean, room: String, wxid: String): String {
    val name = if (group) {
        dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi.getGroupMemberDisplayName(room, wxid)
    } else {
        dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi.getDisplayName(wxid)
    }
    return name.ifBlank { wxid }
}

// ================= userTV 反射缓存（避免列表滚动时反复反射遍历造成卡顿） =================

private val userTvFieldCache = ConcurrentHashMap<Class<*>, java.lang.reflect.Field?>()

/** 从消息项 tag/holder 反射取昵称 TextView 的 userTV 字段，带缓存（按类名）。 */
internal fun findUserTvField(tag: Any): java.lang.reflect.Field? {
    val clazz = tag.javaClass
    return userTvFieldCache.getOrPut(clazz) {
        runCatching {
            var c: Class<*>? = clazz
            while (c != null) {
                for (field in c.declaredFields) {
                    if (field.name == "userTV" && android.widget.TextView::class.java.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        return@getOrPut field
                    }
                }
                c = c.superclass
            }
            null
        }.getOrNull()
    }
}

/** 头衔设置弹窗（「头衔下」「头衔上」共用，store 独立）。 */
internal fun showTitleEditDialog(
    context: Context,
    store: TitleStore,
    group: Boolean,
    room: String,
    wxid: String,
    tag: String,
    defaultTitle: String,
) {
    val name = titleDisplayName(group, room, wxid)
    val existing = store.entryFor(group, room, wxid)

    showComposeDialog(context) {
        var title by remember { mutableStateOf(existing?.title ?: "") }
        var selectedStyle by remember { mutableIntStateOf(normalizeTitleStyle(existing?.style ?: 0)) }
        var customBgStart by remember { mutableStateOf((existing?.customBgStart ?: TITLE_PRESETS[selectedStyle].bgStart).toTitleColorHex()) }
        var customBgEnd by remember { mutableStateOf((existing?.customBgEnd ?: TITLE_PRESETS[selectedStyle].bgEnd).toTitleColorHex()) }
        var customFg by remember { mutableStateOf((existing?.customFg ?: TITLE_PRESETS[selectedStyle].fg).toTitleColorHex()) }

        AlertDialogContent(
            title = { Text((if (group) "群聊" else "私聊") + "$tag：$name") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("头衔文字") },
                        placeholder = { Text("默认：$defaultTitle") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )

                    SegmentedColumn(
                        title = "选择样式",
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        TITLE_PRESETS.forEachIndexed { index, preset ->
                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Label,
                                    iconPlaceholder = true,
                                    title = preset.name,
                                    description = "渐变底 · 圆角标签",
                                    selected = selectedStyle == index,
                                    onClick = { selectedStyle = index },
                                    trailingDivider = index < TITLE_PRESETS.lastIndex,
                                )
                            }
                        }
                    }

                    SegmentedColumn(
                        title = "自定义颜色（覆盖预设）",
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        item {
                            ColorPickerWidget(
                                title = "背景起点色",
                                value = customBgStart,
                                onValueChange = { customBgStart = it },
                            )
                        }
                        item {
                            ColorPickerWidget(
                                title = "背景终点色",
                                value = customBgEnd,
                                onValueChange = { customBgEnd = it },
                            )
                        }
                        item {
                            ColorPickerWidget(
                                title = "文字色",
                                value = customFg,
                                onValueChange = { customFg = it },
                            )
                        }
                    }
                }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {
                Button({
                    val trimmed = title.trim()
                    store.set(
                        group,
                        room,
                        wxid,
                        TitleEntry(
                            title = trimmed,
                            style = selectedStyle,
                            customBgStart = customBgStart.toTitleColorIntOrNull(),
                            customBgEnd = customBgEnd.toTitleColorIntOrNull(),
                            customFg = customFg.toTitleColorIntOrNull(),
                        ),
                    )
                    onDismiss()
                    showToast(if (trimmed.isEmpty()) "已恢复默认头衔" else "${tag}已保存")
                }) { Text(stringResource(R.string.action_save)) }
            },
        )
    }
}

private fun Int.toTitleColorHex(): String = String.format("#%08X", this)
private fun String.toTitleColorIntOrNull(): Int? = runCatching { toColorInt() }.getOrNull()
