package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.TextView
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Label
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MultiSelectSupport
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.MenuIcons
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.monet.MonetColors
import kotlin.math.roundToInt

/**
 * 会话自定义头衔 · 下（WeKit 原生移植，改版注入位置）
 *
 * 相对原脚本的稳定性改造：
 *  - 群聊：复用 WeChatMessageViewApi 视图监听 + 前缀 ReplacementSpan 渲染，
 *    直接把头衔绘制进昵称 TextView 的文本流里，无 addView / 无重布局，滑动零额外开销。
 *  - 私聊：微信私聊消息的气泡昵称（userTV）不可见，Span 方案无法显示，
 *    改为参考原脚本在昵称父容器注入独立标签 View（[TitleTagOverlay]），私聊同样生效。
 *  - 样式扩展为 12 种预设（原 4 种 + 扩展 8 种），并支持自定义颜色覆盖。
 *
 * 与「头衔上」完全独立：各自菜单按钮、各自存储（memberTitlesV9 / memberTitlesV9Up），互不干扰。
 */
object ChatTitleTagDown : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener,
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "会话自定义头衔下"
    override val nameRes = R.string.feature_chat_title_tag_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_title_tag_description

    private const val TAG = "ChatTitleTagDown"
    private const val MENU_ID = 777268
    private const val PREF_KEY = "memberTitlesV9"
    private const val DEFAULT_TITLE = "带刀侍卫"
    private const val OVERLAY_KEY = "down"

    private val store = TitleStore(PREF_KEY, TAG)

    override fun onEnable() {
        store.load()
        WeChatMessageViewApi.addListener(this)
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    // ---------------- 菜单 ----------------

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ID,
            text = "头衔下",
            drawable = MenuIcons.res(R.drawable.ic_menu_title_group),
            imageVector = MaterialSymbols.Outlined.Label,
            isSupported = { msg -> !msg.isSelfSender },
            multiSelect = MultiSelectSupport.Unsupported,
            onClick = { view, _, msgInfo ->
                val group = msgInfo.isInGroupChat
                val room = msgInfo.talker
                val wxid = if (group) msgInfo.sender else msgInfo.talker
                if (wxid.isEmpty()) {
                    showToast("未取得对方发送者，请长按对方消息后重试")
                    return@MenuItem
                }
                showTitleEditDialog(view.context, store, group, room, wxid, "头衔下", DEFAULT_TITLE)
            },
        )
    )

    // ---------------- 渲染 ----------------

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = try {
            WeChatMessageViewApi.getMsgInfoFromParam(param)
        } catch (e: Exception) {
            return
        }
        if (msgInfo.isSelfSender) {
            hideOverlay(view)
            return
        }

        val group = msgInfo.isInGroupChat
        val room = msgInfo.talker
        val wxid = if (group) msgInfo.sender else msgInfo.talker
        val entry = runCatching { store.entryFor(group, room, wxid) }.getOrNull()
        // 诊断日志：头衔串排查用（对照实际消息确认 wxid 是否正确）。
        // 每条消息 bind 都会走到这里，挂在「详细日志」开关后面，避免滚动时每帧一次日志写入。
        if (WeLogger.verboseEnabled) {
            runCatching {
                WeLogger.d(
                    TAG,
                    "render group=$group room=$room sender=${msgInfo.sender} talker=${msgInfo.talker} wxid=$wxid hit=${entry != null}"
                )
            }
        }
        if (wxid.isEmpty()) return

        val tag = view.tag
        val textView = findUserTvField(tag)?.get(tag) as? TextView ?: return

        if (entry == null || entry.title.isBlank()) {
            // 无配置时隐藏可能残留的注入标签，并清理旧 Span 污染（view 复用会残留上一条消息的头衔前缀）
            TitleTagOverlay.hide(textView, OVERLAY_KEY)
            clearInlineSpan(textView)
            return
        }

        if (group || textView.visibility == View.VISIBLE) {
            renderInlineSpan(textView, entry)
        } else {
            renderOverlayTag(textView, entry)
        }
    }

    /**
     * 清除昵称文本流中残留的旧头衔 Span，并还原干净昵称。
     * 列表 view 复用时微信可能不重置 userTV.text（或 setText 晚于 bind 回调），
     * 不清理会导致上一条消息的头衔前缀串到当前消息。
     */
    private fun clearInlineSpan(textView: TextView) {
        val text = textView.text
        if (text !is Spanned) return
        val spans = text.getSpans(0, text.length, TitleBadgeSpan::class.java)
        if (spans.isEmpty()) return
        val end = text.getSpanEnd(spans.first())
        val clean = text.toString().substring(end).trimStart()
        textView.text = SpannableStringBuilder(clean)
    }

    private fun renderInlineSpan(textView: TextView, entry: TitleEntry) {
        // 先清旧 Span 还原干净昵称，避免 view 复用叠加
        clearInlineSpan(textView)
        val name = textView.text
        val style = TITLE_PRESETS[normalizeTitleStyle(entry.style)]
        // 头衔标签是 WeKit 自己加在消息流里的装饰，莫奈引擎覆盖不到；这里在用户**没有**自定义
        // 颜色时跟随引擎色板（渐变用 accent→accentDim，文字用 onPrimary）。用户自定义颜色优先，
        // 莫奈未启用 / 未解析成功时保持原来的预设配色。
        val monet = MonetColors.tokens(textView.context.isDarkMode)
        val badge = TitleBadgeSpan(
            bgStart = entry.customBgStart ?: monet?.accent ?: style.bgStart,
            bgEnd = entry.customBgEnd ?: monet?.accentDim ?: style.bgEnd,
            textColor = entry.customFg ?: monet?.onPrimary ?: style.fg,
        )

        val sb = SpannableStringBuilder()
        sb.append(entry.title)
        sb.append(" ")
        sb.append(name)
        sb.setSpan(badge, 0, entry.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        textView.text = sb
    }

    /** 私聊昵称不可见时：参考原脚本在昵称父容器注入独立标签（头衔「下」→ 昵称下方/右侧，与「头衔上」位置错开）。 */
    private fun renderOverlayTag(nick: TextView, entry: TitleEntry) {
        // 切到 overlay 渲染前同样清理旧 Span，避免昵称文本残留头衔前缀
        clearInlineSpan(nick)
        val titleView = TitleTagOverlay.getOrCreate(nick, OVERLAY_KEY, TitleTagOverlay.TitlePlacement.BELOW) { applyTitleStyle(it, entry) } ?: return
        titleView.text = entry.title
        titleView.visibility = View.VISIBLE
    }

    private fun hideOverlay(view: View) {
        val tag = view.tag
        val tv = findUserTvField(tag)?.get(tag) as? TextView ?: return
        TitleTagOverlay.hide(tv, OVERLAY_KEY)
    }
}

/** 带渐变背景的圆角标签，作为昵称前缀绘制进 TextView 文本流，避免 addView 重布局。 */
private class TitleBadgeSpan(
    private val bgStart: Int,
    private val bgEnd: Int,
    private val textColor: Int,
    private val cornerRadius: Float = 16f,
    private val padding: Float = 12f,
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        return (paint.measureText(text, start, end) + padding * 2).roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val width = paint.measureText(text, start, end)
        val rect = RectF(x, top.toFloat(), x + width + padding * 2, bottom.toFloat())

        val shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, bgStart, bgEnd, Shader.TileMode.CLAMP)
        paint.shader = shader
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        paint.shader = null

        paint.color = textColor
        canvas.drawText(text, start, end, x + padding, y.toFloat(), paint)
    }
}
