package dev.joker.features.items.moments

import android.content.ContentValues
import dev.joker.R
import dev.joker.features.api.core.WeApi
import dev.joker.features.api.core.WeDatabaseListenerApi
import dev.joker.features.api.net.WeProtoData
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.utils.WeLogger

object AntiMomentsDelete : SwitchFeature(),
    WeDatabaseListenerApi.IUpdateListener,
    WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "朋友圈防撤回"
    override val nameRes = R.string.feature_anti_moments_delete_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_anti_moments_delete_description

    private const val TAG = "AntiMomentsDelete"
    private const val TBL_SNS_INFO = "SnsInfo"
    const val INTERCEPT_MARKER = "[拦截删除]"

    /** SnsInfo 表的 userName 列 —— 用于【本人豁免】。 */
    private const val COL_USER_NAME = "userName"

    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        try {
            when (table) {
                TBL_SNS_INFO -> handleSnsRecord(values)
            }
        } catch (ex: Throwable) {
            WeLogger.e(TAG, "拦截处理异常", ex)
        }
    }

    /**
     * 【Round31 新增】SnsInfo 插入路径：本人发朋友圈时同样打标记，
     * 让后续撤回（DELETE 路径）也能命中（SQL 守卫 hook 不到的部分）。
     * ContentValues insert + updateWithOnConflict 都会走这里。
     */
    override fun onInsert(table: String, values: ContentValues) {
        try {
            when (table) {
                TBL_SNS_INFO -> handleSnsRecord(values)
            }
        } catch (ex: Throwable) {
            WeLogger.e(TAG, "insert 拦截处理异常", ex)
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    private fun handleSnsRecord(values: ContentValues) {
        val typeVal = values.get("type") as? Int ?: return
        val sourceVal = values.get("sourceType") as? Int ?: return

        if (!MomentsContentType.allTypeIds.contains(typeVal)) return
        if (sourceVal != 0) return

        // 【★★★ Round30 三件套·本人豁免】
        // 逆向版做法：反射 SnsInfo.field_userName，与 selfWxId 比对。
        // 我方路径：ContentValues 是 DB 列级别，列名就是 userName；
        // 当 update 不带 userName 时（insert/update 已有 row）放过；带 userName
        // 时若等于自己的 wxId → 不打标记，避免误标自己朋友圈。
        val publisherUserName = values.getAsString(COL_USER_NAME)
        if (publisherUserName != null) {
            val self = runCatching { WeApi.selfWxId }.getOrNull()
            if (self != null && self.isNotEmpty() && publisherUserName == self) {
                // 自己的朋友圈不打标记（即使微信自身想撤回也放过——避免误撤回）
                return
            }
        }

        val kindName = MomentsContentType.fromId(typeVal)?.name ?: "Unknown[$typeVal]"

        // 移除来源
        values.remove("sourceType")

        // 注入水印
        val contentBytes = values.getAsByteArray("content")
        if (contentBytes != null) {
            try {
                val proto = WeProtoData.fromMessageBytes(contentBytes)

                if (appendWatermark(proto, 5)) {
                    values.put("content", proto.toMessageBytes())
                    WeLogger.i(TAG, "intercepted: [$kindName], marker injected")
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to handle moments protobuf", e)
            }
        }
    }

    private fun appendWatermark(proto: WeProtoData, fieldNumber: Int): Boolean {
        try {
            val json = proto.toJsonObject()
            val key = fieldNumber.toString()

            if (!json.has(key)) return false

            val currentVal = json.get(key)

            if (currentVal is String) {
                if (currentVal.contains(INTERCEPT_MARKER)) {
                    return false
                }
                val newVal = "$INTERCEPT_MARKER $currentVal "
                proto.setLenUtf8(fieldNumber, 0, newVal)
                return true
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "注入标记失败", e)
        }
        return false
    }
}
