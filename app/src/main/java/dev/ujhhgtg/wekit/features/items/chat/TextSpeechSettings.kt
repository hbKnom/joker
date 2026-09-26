package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
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
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Text_to_speech
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.ConversationPickerSection
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast

/**
 * Settings sheet for [TextSpeechAnnouncer].
 *
 * Keys match Hchat's `text_speech_*` so an imported backup keeps working.
 */
object TextSpeechSettings {

    fun show(context: Context) {
        showComposeDialog(context) {
            var template by remember { mutableStateOf(TextSpeechAnnouncer.template()) }
            var announceSender by remember { mutableStateOf(TextSpeechAnnouncer.announceSender()) }
            var playVoice by remember { mutableStateOf(TextSpeechAnnouncer.playVoiceMessages()) }
            var quietEnable by remember { mutableStateOf(TextSpeechAnnouncer.quietEnabled()) }
            var quietStart by remember { mutableStateOf(TextSpeechAnnouncer.quietStart()) }
            var quietEnd by remember { mutableStateOf(TextSpeechAnnouncer.quietEnd()) }
            var respectDnd by remember { mutableStateOf(TextSpeechAnnouncer.respectWechatDnd()) }
            var volumeControl by remember { mutableStateOf(TextSpeechAnnouncer.volumeControl()) }
            var engineName by remember { mutableStateOf(TextSpeechAnnouncer.ttsEngine()) }
            var contacts by remember { mutableStateOf(TextSpeechAnnouncer.allowedContacts()) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.text_speech_settings_title)) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_announce_sender),
                                checked = announceSender,
                                onCheckedChange = { announceSender = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_play_voice_messages),
                                checked = playVoice,
                                onCheckedChange = { playVoice = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_respect_wechat_dnd),
                                checked = respectDnd,
                                onCheckedChange = { respectDnd = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_volume_control),
                                checked = volumeControl,
                                onCheckedChange = { volumeControl = it },
                                trailingDivider = true,
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = template,
                                onValueChange = { template = it },
                                label = { Text(stringResource(R.string.text_speech_template)) },
                                supportingText = {
                                    Text(
                                        stringResource(R.string.text_speech_template_summary) +
                                            "\n${TextSpeechAnnouncer.PH_SENDER}  " +
                                            TextSpeechAnnouncer.PH_BODY + "  " +
                                            TextSpeechAnnouncer.PH_GROUP,
                                    )
                                },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }

                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_quiet_enable),
                                checked = quietEnable,
                                onCheckedChange = { quietEnable = it },
                                trailingDivider = true,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = quietStart,
                                onValueChange = { quietStart = it },
                                label = { Text(stringResource(R.string.text_speech_quiet_start)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = quietEnd,
                                onValueChange = { quietEnd = it },
                                label = { Text(stringResource(R.string.text_speech_quiet_end)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }

                        item {
                            // 引擎选择：本机可能装了好几个 TTS 引擎（用户反馈"手动输入很麻烦、
                            // 也不知道有哪些"），这里枚举出来点选，选完立刻重建引擎生效。
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_tts_engine),
                                description = if (engineName.isBlank()) {
                                    stringResource(R.string.text_speech_engine_system_default)
                                } else {
                                    engineName
                                },
                                onClick = {
                                    showEnginePicker(
                                        context = context,
                                        current = engineName,
                                        onPick = { picked ->
                                            engineName = picked
                                            WePrefs.putString(TextSpeechAnnouncer.KEY_TTS_ENGINE, picked)
                                            TextSpeechAnnouncer.reinitEngine()
                                            showToast(context, context.getString(R.string.text_speech_engine_switched))
                                        },
                                    )
                                },
                                trailingDivider = true,
                            )
                        }

                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_test_speak),
                                description = stringResource(R.string.text_speech_tts_engine_summary),
                                onClick = { TextSpeechAnnouncer.speakTest() },
                                trailingDivider = true,
                            )
                        }

                        item {
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = stringResource(R.string.text_speech_allowed_contacts),
                                description = if (contacts.isEmpty()) {
                                    stringResource(R.string.text_speech_allowed_contacts_summary)
                                } else {
                                    stringResource(
                                        R.string.text_speech_allowed_contacts_count,
                                        contacts.size,
                                    )
                                },
                                onClick = { },
                                trailingDivider = true,
                            )
                        }
                        // 用户 2026-09-22 反馈「允许播报的聊天没有任何选择」：以前只能靠聊天页右键
                        // 一条条加、在设置里一条条删（列表为空时根本无从下手）。
                        // 这里直接复用与「QQ点歌 → 生效聊天」同一个全量会话选择器：可搜索、勾选即生效，
                        // 数据源与选中逻辑完全一致（宿主的会话列表 + WePrefs 落盘）。
                        item {
                            ConversationPickerSection(
                                selected = contacts,
                                onToggle = { wxId, enabled ->
                                    TextSpeechAnnouncer.setContactEnabled(wxId, enabled)
                                    contacts = TextSpeechAnnouncer.allowedContacts()
                                },
                            )
                        }
                        if (contacts.isNotEmpty()) {
                            item {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Text_to_speech,
                                    title = stringResource(R.string.text_speech_clear_contacts),
                                    onClick = {
                                        WePrefs.putStringSet(TextSpeechAnnouncer.KEY_ALLOWED, emptySet())
                                        contacts = emptySet()
                                    },
                                    trailingDivider = true,
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        WePrefs.putString(TextSpeechAnnouncer.KEY_TEMPLATE, template.trim())
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_ANNOUNCE_SENDER, announceSender)
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_PLAY_VOICE, playVoice)
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_QUIET_ENABLE, quietEnable)
                        WePrefs.putString(
                            TextSpeechAnnouncer.KEY_QUIET_START,
                            quietStart.trim().ifBlank { TextSpeechAnnouncer.DEFAULT_QUIET_START },
                        )
                        WePrefs.putString(
                            TextSpeechAnnouncer.KEY_QUIET_END,
                            quietEnd.trim().ifBlank { TextSpeechAnnouncer.DEFAULT_QUIET_END },
                        )
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_RESPECT_DND, respectDnd)
                        WePrefs.putBool(TextSpeechAnnouncer.KEY_VOLUME_CONTROL, volumeControl)
                        WePrefs.putString(TextSpeechAnnouncer.KEY_TTS_ENGINE, engineName.trim())
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        }
    }

    /**
     * TTS 引擎选择器：枚举本机已安装引擎（含"系统默认"），点选后立即回写并重建引擎。
     * 用嵌套弹窗（与本项目其它 picker 一致）而不是再点开一个新页面。
     */
    private fun showEnginePicker(context: Context, current: String, onPick: (String) -> Unit) {
        showComposeDialog(context) {
            val options = remember { TextSpeechAnnouncer.ttsEngineOptions() }
            AlertDialogContent(
                title = { Text(stringResource(R.string.text_speech_engine_pick)) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(options) { option ->
                            val label = option.label.substringBefore("\n").trim()
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Text_to_speech,
                                title = label,
                                description = option.name.ifBlank {
                                    stringResource(R.string.text_speech_engine_system_default)
                                },
                                onClick = {
                                    onPick(option.name)
                                    onDismiss()
                                },
                                trailingContent = {
                                    if (option.name == current) {
                                        Icon(MaterialSymbols.Outlined.Check, contentDescription = null)
                                    }
                                },
                                trailingDivider = true,
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }
}
