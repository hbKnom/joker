package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactHeaderApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.system.localizedSystemString
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows when a contact was added.
 *
 * Upstream 09-12 widened this from "friends, in the profile header only" to friends, chatrooms
 * and official accounts, and additionally injects the value as a row in the contact / chatroom
 * detail list (see [WeContactPrefsScreenApi]).
 *
 * That widening shows the same value twice on the friend profile screen: once in the header row
 * injected by [WeContactHeaderApi] and once as a list row. The list row is therefore skipped on
 * the friend profile (the header already covers it there; long-pressing the header row still
 * copies through the host's own popup) and kept everywhere the host renders no header, i.e. the
 * chatroom detail screen.
 */
object ShowFriendAddTime : SwitchFeature(),
    WeContactHeaderApi.Provider,
    WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "显示联系人添加时间"
    override val nameRes = R.string.feature_show_friend_add_time_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_show_friend_add_time_description

    private const val TAG = "ShowFriendAddTime"
    private const val ITEM_KEY = "wekit_contact_add_time"

    private const val QUERY_CREATE_TIME =
        "SELECT createTime FROM rcontact WHERE username = ? LIMIT 1"

    /**
     * Reads the contact creation timestamp for the currently open profile.
     *
     * ContactSyncExtension writes the server's ContactCreateTime (seconds) into
     * rcontact.createTime. Verification/chat messages are not this date. Unlike the old
     * friend-only version this query deliberately does not filter by row type, so chatrooms and
     * official accounts resolve as well (WeChat stores their createTime the same way).
     */
    private fun readCreateTime(wxId: String): Long? = try {
        WeDatabaseApi.rawQuery(QUERY_CREATE_TIME, arrayOf(wxId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0 } else null
        }
    } catch (e: Exception) {
        WeLogger.e(TAG, "failed to read contact creation time", e)
        null
    }

    private fun formatCreateTime(seconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .format(Date(Math.multiplyExact(seconds, 1000L)))

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
