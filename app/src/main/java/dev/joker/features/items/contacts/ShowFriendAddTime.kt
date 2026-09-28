package dev.joker.features.items.contacts

import android.app.Activity
import dev.joker.R
import dev.joker.features.api.core.WeApi
import dev.joker.features.api.core.WeDatabaseApi
import dev.joker.features.api.ui.WeContactHeaderApi
import dev.joker.features.api.ui.WeContactPrefsScreenApi
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.features.items.system.localizedSystemString
import dev.joker.utils.WeLogger
import dev.joker.utils.android.copyToClipboard
import dev.joker.utils.android.currentWxId
import dev.joker.utils.android.showToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows when a contact was added.
 *
 * Round30 ★★★ 三路径版本（对标 WeKit1945 逆向包 21 条日志第 7 条）：
 *  ① 群聊（@chatroom）—— 从 message 表 MAX/MIN 邀请时间推断；
 *  ② 公众号（gh_ 前缀）—— 直接读 rcontact.createTime；
 *  ③ 普通好友 —— 读 rcontact.createTime，加位掩码过滤 type & 1/8/32、verifyFlag=0。
 *
 * 时间戳兼容：微信有些表存秒，有些存毫秒 → 小于 1e11 自动升位到毫秒。
 */
object ShowFriendAddTime : SwitchFeature(),
    WeContactHeaderApi.Provider,
    WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "显示联系人添加时间"
    override val nameRes = R.string.feature_show_friend_add_time_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_show_friend_add_time_description

    private const val TAG = "ShowFriendAddTime"
    private const val ITEM_KEY = "joker_contact_add_time"

    private const val QUERY_CREATE_TIME =
        "SELECT createTime FROM rcontact WHERE username = ? LIMIT 1"

    private const val QUERY_OFFICIAL_CREATE_TIME =
        "SELECT createTime FROM rcontact WHERE username = ? LIMIT 1"

    /**
     * 位掩码过滤 + verifyFlag=0 后的好友路径（对标逆向版 Hchat 19 字段的 scope=friend）。
     *   type & 1  = 好友
     *   type & 8  = 群（排除）
     *   type & 32 = 公众号（排除）
     *   verifyFlag = 0 排除服务号
     */
    private val QUERY_FRIEND_CREATE_TIME = """
        SELECT createTime FROM rcontact
        WHERE username = ? AND encryptUsername != ''
        AND (type & 1) != 0 AND (type & 8) = 0
        AND (type & 32) = 0 AND verifyFlag = 0
        AND username NOT LIKE '%@%' LIMIT 1
    """.trimIndent()

    private val QUERY_GROUP_INVITED = """
        SELECT MAX(createTime) FROM message
        WHERE talker = ? AND type IN (10000, 570425393)
        AND (content LIKE '你加入了群聊%' OR content LIKE '你通过%加入群聊%' OR content LIKE '%邀请你%加入了群聊%')
    """.trimIndent()

    private val QUERY_GROUP_CREATED = """
        SELECT MIN(createTime) FROM message
        WHERE talker = ? AND type IN (10000, 570425393)
        AND (content LIKE '你创建了群聊%' OR content LIKE '你邀请%加入了群聊%' OR content LIKE '%你邀请%加入了群聊%')
    """.trimIndent()

    /** 秒 → 毫秒的阈值（1e11 毫秒≈ 5138-11-16，按 2026 年时间戳都是秒级或毫秒级，二选一） */
    private const val SECOND_TIMESTAMP_THRESHOLD = 100_000_000_000L

    /** 微信有些表存秒，有些存毫秒 → 小于 1e11 自动升位到毫秒 */
    private fun Long.toMillisIfNeeded(): Long =
        if (this < SECOND_TIMESTAMP_THRESHOLD) Math.multiplyExact(this, 1000L) else this

    /** 【Round30 ★★★】统一入口：按 wxId 类型选 3 条路径之一 */
    private fun readCreateTime(wxId: String): Long? = when {
        wxId.endsWith("@chatroom") -> queryGroupAddTime(wxId)
        wxId.startsWith("gh_") -> queryOfficialAccountAddTime(wxId)
        else -> queryFriendAddTime(wxId)
    }

    /** ① 群聊：从 message 表 MAX（被邀请）/MIN（自己创建）推断入群时间 */
    private fun queryGroupAddTime(talker: String): Long? = try {
        val selfWxId = runCatching { WeApi.selfWxId }.getOrNull() ?: ""
        val inviter = WeDatabaseApi.getGroupMemberInviter(talker, selfWxId)
        val invited = inviter.isNotEmpty() && inviter != selfWxId
        val sql = if (invited) QUERY_GROUP_INVITED else QUERY_GROUP_CREATED

        WeDatabaseApi.rawQuery(sql, arrayOf(talker)).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) return@use null
            c.getLong(0).takeIf { it > 0 }?.toMillisIfNeeded()
        }
    } catch (e: Exception) {
        WeLogger.e(TAG, "failed to read group add time", e)
        null
    }

    /** ② 公众号：直接读 rcontact */
    private fun queryOfficialAccountAddTime(talker: String): Long? = try {
        WeDatabaseApi.rawQuery(QUERY_OFFICIAL_CREATE_TIME, arrayOf(talker)).use { c ->
            if (!c.moveToFirst()) return@use null
            c.getLong(0).takeIf { it > 0 }?.toMillisIfNeeded()
        }
    } catch (e: Exception) {
        WeLogger.e(TAG, "failed to read official account add time", e)
        null
    }

    /** ③ 普通好友：位掩码过滤后的 rcontact */
    private fun queryFriendAddTime(talker: String): Long? = try {
        WeDatabaseApi.rawQuery(QUERY_FRIEND_CREATE_TIME, arrayOf(talker)).use { c ->
            if (!c.moveToFirst()) return@use null
            c.getLong(0).takeIf { it > 0 }?.toMillisIfNeeded()
        }
    } catch (e: Exception) {
        WeLogger.e(TAG, "failed to read contact creation time", e)
        null
    }

    private fun formatCreateTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .format(Date(millis))

    private fun addTimeText(activity: Activity): String? {
        val wxId = activity.currentWxId ?: return null
        val seconds = readCreateTime(wxId) ?: return null
        return activity.localizedContactsString(
            R.string.contacts_add_time_value,
            formatCreateTime(seconds),
        )
    }

    override fun getHeaderText(activity: Activity): String? =
        addTimeText(activity)
            ?: activity.localizedContactsString(R.string.contacts_get_failed)

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        // The friend profile screen already shows this value in the header row injected by
        // [WeContactHeaderApi]; listing it underneath again duplicates it on one screen.
        if (WeContactHeaderApi.showsHeaderOn(activity)) return emptyList()
        val text = addTimeText(activity) ?: return emptyList()
        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = ITEM_KEY,
                title = activity.localizedContactsString(R.string.feature_show_friend_add_time_name),
                summary = text,
            ),
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != ITEM_KEY) return false
        addTimeText(activity)?.let { text ->
            copyToClipboard(activity, text)
            showToast(activity, activity.localizedSystemString(R.string.copied_to_clipboard))
        }
        return true
    }

    override fun onEnable() {
        WeContactHeaderApi.addProvider(this)
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactHeaderApi.removeProvider(this)
        WeContactPrefsScreenApi.removeProvider(this)
    }
}
