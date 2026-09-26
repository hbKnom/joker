package dev.ujhhgtg.wekit.features.items.beautify

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.reflected.ReflectedField
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.monet.MonetColors
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private enum class ConversationListPreset(
    val rowRadiusDp: Int,
    val horizontalInsetDp: Int,
    val verticalInsetDp: Int,
    val lightBackgroundColor: Int,
    val darkBackgroundColor: Int,
) {
    NO_LAYOUT(0, 0, 0, 0, 0),
    COMFORT_CARD(14, 10, 4, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
    PINNED_GROUPED_CARD(14, 10, 4, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
    COMPACT_ROUNDED(10, 6, 2, 0xFFF9FBFA.toInt(), 0xFF272928.toInt()),
    MINIMAL_LIST(6, 0, 0, 0xFFFCFCFC.toInt(), 0xFF232323.toInt()),
    // Upstream 09-19 "floating island": pinned / non-pinned conversations rendered as
    // separate rounded islands (radius 20dp, 12dp horizontal inset, 8dp gap).
    ISLAND(20, 12, 8, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
}

object BeautifyConversationList : ClickableFeature() {

    override val technicalId = "美化对话列表"
    override val nameRes = R.string.feature_beautify_conversation_list_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_beautify_conversation_list_description

    private const val TAG = "BeautifyConversationList"

    private var layoutEnabled by prefOption("beautify_conversation_list_layout_enabled", true)
    private var highlightUnreadEnabled by prefOption("beautify_conversation_list_highlight_unread", false)
    private var hideDividersEnabled by prefOption("beautify_conversation_list_hide_dividers", true)

    private val selectedPreset: ConversationListPreset
        get() = if (layoutEnabled) ConversationListPreset.ISLAND else ConversationListPreset.NO_LAYOUT

    private enum class GroupPosition { SINGLE, FIRST, MIDDLE, LAST }

    private data class RowBackgroundKey(
        val preset: ConversationListPreset,
        val unread: Boolean,
        val isDark: Boolean,
        val density: Float,
        val groupPosition: GroupPosition,
    )

    private data class RowVisualState(
        var baselineBackground: Drawable?,
        var baselinePaddingLeft: Int,
        var baselinePaddingTop: Int,
        var baselinePaddingRight: Int,
        var baselinePaddingBottom: Int,
        var moduleBackground: Drawable? = null,
        var backgroundKey: RowBackgroundKey? = null,
    )

    private sealed interface UnreadAccessor {
        data class Field(val get: (Any) -> Any?) : UnreadAccessor
        data object Missing : UnreadAccessor
    }

    private val rowStates = WeakHashMap<View, RowVisualState>()
    private val unreadAccessorCache = ConcurrentHashMap<Class<*>, UnreadAccessor>()
    private val unreadFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()
    private val usernameAccessorCache = ConcurrentHashMap<Class<*>, UnreadAccessor>()
    private val usernameFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()

    private val bindListener = WeConversationListViewApi.IBindViewListener { _, row, conversation, context ->
        applyRowVisuals(row, conversation, context)
    }

    override fun onEnable() {
        WeConversationListViewApi.addListener(bindListener)
        updateDividerRequest()
        WeConversationListViewApi.refresh()
    }

    override fun onDisable() {
        WeConversationListViewApi.removeListener(bindListener)
        WeConversationListViewApi.removeDividerOwner(this)
        rowStates.clear()
        unreadAccessorCache.clear()
        usernameAccessorCache.clear()
        unreadFailuresLogged.clear()
        usernameFailuresLogged.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var layoutEnabledInput by remember { mutableStateOf(layoutEnabled) }
            var highlightUnread by remember { mutableStateOf(highlightUnreadEnabled) }
            var hideDividers by remember { mutableStateOf(hideDividersEnabled) }

            fun applyChanges(highlight: Boolean, dividers: Boolean) {
                highlightUnreadEnabled = highlight
                hideDividersEnabled = dividers
                updateDividerRequest()
                WeConversationListViewApi.refresh()
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_conversation_list_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "layout") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_conversation_layout),
                                description = stringResource(R.string.beautify_conversation_layout_summary),
                                checked = layoutEnabledInput,
                                onCheckedChange = {
                                    layoutEnabledInput = it
                                    layoutEnabled = it
                                    applyChanges(highlight = highlightUnread, dividers = hideDividers)
                                },
                            )
                        }
                        item(
                            key = "highlight_unread",
                            animatedVisibility = layoutEnabledInput,
                        ) {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_conversation_highlight_unread),
                                checked = highlightUnread,
                                onCheckedChange = {
                                    highlightUnread = it
                                    applyChanges(highlight = it, dividers = hideDividers)
                                },
                            )
                        }
                        item(key = "hide_dividers") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_conversation_hide_dividers),
                                checked = hideDividers,
                                onCheckedChange = {
                                    hideDividers = it
                                    applyChanges(highlight = highlightUnread, dividers = it)
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun applyRowVisuals(
        row: View,
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ) {
        val state = rowStates.getOrPut(row) {
            RowVisualState(
                baselineBackground = row.background,
                baselinePaddingLeft = row.paddingLeft,
                baselinePaddingTop = row.paddingTop,
                baselinePaddingRight = row.paddingRight,
                baselinePaddingBottom = row.paddingBottom,
            )
        }
        restoreRowBaseline(row, state)

        val preset = selectedPreset
        if (preset == ConversationListPreset.NO_LAYOUT) {
            WeConversationListViewApi.setRowDividerHidden(this, row, false)
            return
        }

        val grouped = preset == ConversationListPreset.PINNED_GROUPED_CARD ||
            preset == ConversationListPreset.ISLAND
        val groupPosition = if (grouped) groupPosition(conversation, context) else GroupPosition.SINGLE
        val pinned = if (grouped) isPinnedConversation(conversation) else false
        val nextPinned = if (grouped) context.nextConversation?.let(::isPinnedConversation) else null
        WeConversationListViewApi.setRowDividerHidden(
            owner = this,
            row = row,
            hidden = grouped && pinned && nextPinned == false,
        )

        val unread = highlightUnreadEnabled && isUnread(conversation)
        val backgroundKey = RowBackgroundKey(
            preset = preset,
            unread = unread,
            isDark = row.context.isDarkMode,
            density = row.resources.displayMetrics.density,
            groupPosition = groupPosition,
        )
        val background = if (state.backgroundKey == backgroundKey) {
            state.moduleBackground!!
        } else {
            buildRowBackground(row.context, preset, unread, groupPosition).also {
                state.backgroundKey = backgroundKey
                state.moduleBackground = it
            }
        }
        row.background = background
        row.setPadding(
            state.baselinePaddingLeft,
            state.baselinePaddingTop,
            state.baselinePaddingRight,
            state.baselinePaddingBottom,
        )
    }

    private fun restoreRowBaseline(row: View, state: RowVisualState) {
        if (row.background === state.moduleBackground) {
            row.background = state.baselineBackground
            row.setPadding(
                state.baselinePaddingLeft,
                state.baselinePaddingTop,
                state.baselinePaddingRight,
                state.baselinePaddingBottom,
            )
        } else {
            state.baselineBackground = row.background
            state.baselinePaddingLeft = row.paddingLeft
            state.baselinePaddingTop = row.paddingTop
            state.baselinePaddingRight = row.paddingRight
            state.baselinePaddingBottom = row.paddingBottom
            state.moduleBackground = null
            state.backgroundKey = null
        }
    }

    private fun buildRowBackground(
        context: Context,
        preset: ConversationListPreset,
        unread: Boolean,
        groupPosition: GroupPosition,
    ): Drawable {
        val isDark = context.isDarkMode
        // 会话列表卡片是 WeKit 自己画的 Drawable（微信侧视图，拿不到 Compose 主题），
        // 所以直接取引擎色板；莫奈未生效时完全保持原来的配色（trailing default）。
        val tokens = MonetColors.tokens(isDark)
        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (preset == ConversationListPreset.PINNED_GROUPED_CARD ||
                preset == ConversationListPreset.ISLAND
            ) {
                setCornerRadii(cornerRadii(context, preset.rowRadiusDp, groupPosition))
            } else {
                cornerRadius = preset.rowRadiusDp.dpToPx(context).toFloat()
            }
            setColor(
                when {
                    unread && isDark -> tokens?.primaryContainer ?: 0xFF253E37.toInt()
                    unread -> tokens?.primaryContainer ?: 0xFFEAF8F2.toInt()
                    isDark -> tokens?.surfaceContainerHigh ?: preset.darkBackgroundColor
                    else -> tokens?.surfaceContainerHigh ?: preset.lightBackgroundColor
                },
            )
            setStroke(
                1.dpToPx(context).coerceAtLeast(1),
                tokens?.let { MonetColors.withAlpha(it.outline, if (isDark) 0x22 else 0x16) }
                    ?: if (isDark) 0x22FFFFFF else 0x16161D1C
            )
        }
        val horizontalInset = preset.horizontalInsetDp.dpToPx(context)
        val verticalInset = preset.verticalInsetDp.dpToPx(context)
        val topInset = if (preset == ConversationListPreset.PINNED_GROUPED_CARD) {
            when (groupPosition) {
                GroupPosition.SINGLE, GroupPosition.FIRST -> verticalInset
                GroupPosition.MIDDLE, GroupPosition.LAST -> 0
            }
        } else {
            verticalInset
        }
        val bottomInset = if (preset == ConversationListPreset.PINNED_GROUPED_CARD) {
            when (groupPosition) {
                GroupPosition.SINGLE, GroupPosition.LAST -> verticalInset
                GroupPosition.FIRST, GroupPosition.MIDDLE -> 0
            }
        } else {
            verticalInset
        }
        val inset = InsetDrawable(card, horizontalInset, topInset, horizontalInset, bottomInset)
        val rippleColor = tokens?.let { MonetColors.withAlpha(it.primary, if (isDark) 0x2A else 0x18) }
            ?: if (isDark) 0x2AFFFFFF else 0x18006A62
        return RippleDrawable(ColorStateList.valueOf(rippleColor), inset, null)
    }

    private fun cornerRadii(context: Context, radiusDp: Int, position: GroupPosition): FloatArray {
        val radius = radiusDp.dpToPx(context).toFloat()
        val zero = 0f
        return when (position) {
            GroupPosition.SINGLE -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            GroupPosition.FIRST -> floatArrayOf(radius, radius, radius, radius, zero, zero, zero, zero)
            GroupPosition.MIDDLE -> floatArrayOf(zero, zero, zero, zero, zero, zero, zero, zero)
            GroupPosition.LAST -> floatArrayOf(zero, zero, zero, zero, radius, radius, radius, radius)
        }
    }

    private fun groupPosition(
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ): GroupPosition {
        val pinned = isPinnedConversation(conversation)
        val previousPinned = context.previousConversation?.let(::isPinnedConversation)
        val nextPinned = context.nextConversation?.let(::isPinnedConversation)
        return when {
            previousPinned != pinned && nextPinned != pinned -> GroupPosition.SINGLE
            previousPinned != pinned -> GroupPosition.FIRST
            nextPinned != pinned -> GroupPosition.LAST
            else -> GroupPosition.MIDDLE
        }
    }

    private fun isPinnedConversation(conversation: Any): Boolean {
        val modelClass = conversation.javaClass
        val accessor = usernameAccessorCache.computeIfAbsent(modelClass, ::findUsernameAccessor)
        if (accessor === UnreadAccessor.Missing) return false
        return try {
            val talker = (accessor as UnreadAccessor.Field).get(conversation) as? String ?: return false
            WeConversationApi.isPinned(talker)
        } catch (error: Exception) {
            logUsernameFailureOnce(modelClass, "could not read field_username", error)
            false
        }
    }

    private fun findUsernameAccessor(modelClass: Class<*>): UnreadAccessor = try {
        val field = modelClass.reflekt().firstFieldOrNull {
            name = "field_username"
            superclass()
        } ?: run {
            logUsernameFailureOnce(modelClass, "field_username is absent", null)
            return UnreadAccessor.Missing
        }
        @Suppress("UNCHECKED_CAST")
        val accessor = field as ReflectedField<Any>
        UnreadAccessor.Field { conversation -> accessor.get(conversation) }
    } catch (error: Exception) {
        logUsernameFailureOnce(modelClass, "could not resolve field_username", error)
        UnreadAccessor.Missing
    }

    private fun logUsernameFailureOnce(modelClass: Class<*>, message: String, error: Exception?) {
        if (!usernameFailuresLogged.add(modelClass)) return
        if (error == null) WeLogger.w(TAG, "$message on ${modelClass.name}")
        else WeLogger.w(TAG, "$message on ${modelClass.name}", error)
    }

    private fun isUnread(conversation: Any): Boolean {
        val modelClass = conversation.javaClass
        val accessor = unreadAccessorCache.computeIfAbsent(modelClass, ::findUnreadAccessor)
        if (accessor === UnreadAccessor.Missing) return false
        return try {
            val unreadCount = ((accessor as UnreadAccessor.Field).get(conversation) as? Number)
                ?.toInt() ?: return false
            unreadCount > 0
        } catch (error: Exception) {
            logUnreadFailureOnce(modelClass, "could not read field_unReadCount", error)
            false
        }
    }

    private fun findUnreadAccessor(modelClass: Class<*>): UnreadAccessor = try {
        val field = modelClass.reflekt().firstFieldOrNull {
            name = "field_unReadCount"
            superclass()
        } ?: run {
            logUnreadFailureOnce(modelClass, "field_unReadCount is absent", null)
            return UnreadAccessor.Missing
        }
        @Suppress("UNCHECKED_CAST")
        val accessor = field as ReflectedField<Any>
        UnreadAccessor.Field { conversation -> accessor.get(conversation) }
    } catch (error: Exception) {
        logUnreadFailureOnce(modelClass, "could not resolve field_unReadCount", error)
        UnreadAccessor.Missing
    }

    private fun logUnreadFailureOnce(modelClass: Class<*>, message: String, error: Exception?) {
        if (!unreadFailuresLogged.add(modelClass)) return
        if (error == null) WeLogger.w(TAG, "$message on ${modelClass.name}")
        else WeLogger.w(TAG, "$message on ${modelClass.name}", error)
    }

    private fun updateDividerRequest() {
        WeConversationListViewApi.setDividerHidden(owner = this, hidden = isEnabled && hideDividersEnabled)
    }
}
