package dev.ujhhgtg.wekit.features.items.system

import com.tencent.mm.ui.LauncherUI
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.outlinedfilled.Qr_code_scanner
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Globe
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Shopping_cart
import com.tencent.mm.plugin.webview.ui.tools.WebViewUI
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.QrCodeScannerIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.features.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.nul
import dev.ujhhgtg.wekit.utils.openInSystem
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.DexKitBridge

object QrCodeRecord : ClickableFeature(), IResolveDex, WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "二维码扫描记录"
    override val nameRes = R.string.feature_qr_code_record_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_qr_code_record_description

    private const val TAG = "QrCodeRecord"
    private const val HOME_MENU_ITEM_ID = 0xbdb47

    @Serializable
    data class QrRecord(
        val url: String,
        val time: Long,
        // Extra scan metadata kept so a record can be replayed through WeChat's own
        // scan-result pipeline (see openInWeChat). Defaults keep old records readable.
        val codeType: Int = 0,
        val codeVersion: Int = 0,
    )

    private val records = mutableListOf<QrRecord>()
    private const val KEY_RECORDS = "qr_code_records"
    private var prefRecords by prefOption(KEY_RECORDS, nul<String>())
    private var showInHomeMenu by prefOption("qr_code_record_home_menu_enabled", false)
    private var loaded = false

    override fun onEnable() {
        methodQBarString.hookBefore {
            val rawUrl = args[1] as? String? ?: return@hookBefore
            handleUrl(rawUrl)
        }
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        if (!showInHomeMenu) return emptyList()
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                HOME_MENU_ITEM_ID,
                localizedSystemString(R.string.qr_code_record_home_menu_title),
                QrCodeScannerIcon,
            ) {
                LauncherUI.getInstance()?.let { activity ->
                    (activity as ComponentActivity).runOnUiThread {
                        showRecordsDialog(activity as ComponentActivity)
                    }
                }
            },
        )
    }

    /**
     * Replay a recorded scan through WeChat's own scan-result pipeline, so friend / group /
     * payment QR codes behave exactly as if they had just been scanned.
     */
    private fun openInWeChat(activity: ComponentActivity, record: QrRecord) {
        runCatching {
            val intent = Intent().apply {
                setClassName(
                    HostInfo.packageName,
                    "com.tencent.mm.plugin.webview.stub.WebviewScanImageActivity",
                )
                putExtra("key_string_for_scan", record.url)
                putExtra("key_codetype_for_scan", record.codeType)
                putExtra("key_codeversion_for_scan", record.codeVersion)
                putExtra("dev.ujhhgtg.wekit.qr_code_record_replay", true)
            }
            activity.startActivity(intent)
        }.onFailure {
            WeLogger.e(TAG, "failed to open QR result in WeChat", it)
            showToast(activity, localizedSystemString(R.string.qr_code_record_open_failed))
        }
    }

    private fun handleUrl(rawUrl: String) {
        if (!loaded) {
            loadRecords()
            loaded = true
        }

        records.add(0, QrRecord(rawUrl, System.currentTimeMillis()))
        WeLogger.i(TAG, "added $rawUrl")
        saveRecords()
    }

    override fun onClick(context: ComponentActivity) = showRecordsDialog(context)

    private fun showRecordsDialog(context: ComponentActivity) {
        if (!loaded) {
            loadRecords()
            loaded = true
        }

        showComposeDialog(context) {
            var list by remember { mutableStateOf(records.toList()) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_qr_code_record_name)) },
                text = {
                    if (list.isEmpty()) {
                        Text(
                            text = stringResource(R.string.system_qr_code_record_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(list, key = { "${it.url}${it.time}" }) { record ->
                                val (icon, tint) = getQrTypeConfig(record.url)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                ) {
                                    // Header row: icon badge + timestamp & url
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 2.dp, end = 12.dp)
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(tint.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = tint,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = formatEpoch(record.time, true),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.height(3.dp))
                                            Text(
                                                text = record.url,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    // Action buttons, end-aligned below content
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        IconButton({ openInWeChat(context, record) }) {
                                            Icon(
                                                imageVector = MaterialSymbols.OutlinedFilled.Qr_code_scanner,
                                                contentDescription = stringResource(
                                                    R.string.system_qr_code_record_native_open
                                                ),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton({
                                            copyToClipboard(context, record.url)
                                            showToast(
                                                context,
                                                context.localizedSystemString(R.string.copied_to_clipboard)
                                            )
                                        }) {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Content_copy,
                                                contentDescription = stringResource(
                                                    R.string.system_qr_code_record_copy
                                                ),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton({
                                            context.startActivity(
                                                Intent(context, WebViewUI::class.java).apply {
                                                    putExtra("rawUrl", record.url)
                                                })
                                        }) {
                                            Icon(
                                                imageVector =
                                                    if (LinkExternalAppJump.isEnabled) MaterialSymbols.Outlined.Open_in_new
                                                    else MaterialSymbols.Outlined.Globe,
                                                contentDescription = stringResource(
                                                    R.string.system_qr_code_record_open
                                                ),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (!LinkExternalAppJump.isEnabled) {
                                            IconButton({
                                                record.url.toUri().openInSystem(context, true)
                                            }) {
                                                Icon(
                                                    imageVector = MaterialSymbols.Outlined.Open_in_new,
                                                    contentDescription = stringResource(
                                                        R.string.system_qr_code_record_open_in_system
                                                    ),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton({
                        records.clear()
                        list = emptyList()
                        clearRecords()
                    }) { Text(stringResource(R.string.action_clear)) }
                },
                confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } }
            )
        }
    }

    /**
     * Determines the icon presentation and branding color based on URL targets.
     */
    @Composable
    private fun getQrTypeConfig(url: String): Pair<ImageVector, Color> {
        return when {
            url.startsWith("https://u.wechat.com") -> {
                MaterialSymbols.Outlined.Person to Color(0xFF07C160)
            }

            url.startsWith("https://wx.tenpay.com") || url.startsWith("weixin://wxpay") -> {
                MaterialSymbols.Outlined.Shopping_cart to Color(0xFFFDAE17)
            }

            else -> {
                MaterialSymbols.Outlined.Info to MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    }

    private fun saveRecords() {
        prefRecords = DefaultJson.encodeToString(records.toList())
    }

    private fun loadRecords() {
        records.clear()
        prefRecords
            ?.let { runCatching { DefaultJson.decodeFromString<List<QrRecord>>(it) }.getOrNull() }
            ?.let { records.addAll(it) }
    }

    private fun clearRecords() {
        WePrefs.remove(KEY_RECORDS)
    }

    val methodQBarString by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.QBarStringHandler", "key_offline_scan_show_tips")
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
    }
}
