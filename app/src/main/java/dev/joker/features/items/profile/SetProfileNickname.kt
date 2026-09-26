package dev.joker.features.items.profile

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.joker.R
import dev.joker.features.api.net.WePacketHelper
import dev.joker.features.api.net.models.protobuf.OpLog
import dev.joker.features.api.net.models.protobuf.OpLogRespProto
import dev.joker.features.api.net.models.protobuf.SetNicknameProto
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.Button
import dev.joker.ui.content.TextButton
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.WeLogger

object SetProfileNickname : ClickableFeature() {

    override val technicalId = "设置微信昵称"
    override val nameRes = R.string.feature_set_profile_nickname_name
    override val categoryIds = listOf(FeatureCategoryIds.PROFILE)
    override val descriptionRes = R.string.feature_set_profile_nickname_description

    private const val TAG = "SetProfileNickname"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var nickname by remember { mutableStateOf("") }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_set_profile_nickname_name)) },
                text = {
                    TextField(
                        label = { Text(stringResource(R.string.profile_new_nickname)) },
                        value = nickname, onValueChange = { nickname = it }, singleLine = false
                    )
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        val reqBytes = OpLog.encodeSingle(
                            OpLog.CMD_SET_NICKNAME, SetNicknameProto(nickname = nickname)
                        )

                        WePacketHelper.sendCgi(
                            "/cgi-bin/micromsg-bin/oplog",
                            681, 0, 0,
                            reqBytes = reqBytes
                        ) {
                            onSuccess { bytes ->
                                val resp = bytes?.let { OpLogRespProto.decode(it) }
                                WeLogger.i(TAG, "success: ret=${resp?.ret}")
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text(stringResource(R.string.profile_nickname_success)) },
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.profile_nickname_server_code,
                                                    resp?.ret?.toString() ?: stringResource(R.string.unknown),
                                                )
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                                        }
                                    )
                                }
                            }

                            onFailure { type, code, msg ->
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text(stringResource(R.string.profile_nickname_failure)) },
                                        text = {
                                            Text(stringResource(R.string.profile_nickname_failure_details, type, code, msg))
                                        },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                                        }
                                    )
                                }
                            }
                        }
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    override val noSwitchWidget: Boolean
        get() = true
}
