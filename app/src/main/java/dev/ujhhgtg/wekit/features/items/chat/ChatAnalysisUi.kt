package dev.ujhhgtg.wekit.features.items.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Article
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Error
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Memory
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Smart_toy
import com.composables.icons.materialsymbols.outlined.Sort
import com.composables.icons.materialsymbols.outlined.Star
import com.composables.icons.materialsymbols.outlined.Sunny
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.LocalSegmentedItemShape
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 聊天记录分析 —— Compose UI 组件（设计系统版 v3）
 *
 * 全部弹窗基于 showComposeDialog + AlertDialogContent（WeKit 标准）。
 *
 * ── 三条排版铁律（本文件所有弹窗都遵守）────────────────────────────────
 *  1. **绝不越出窗口**：任何弹窗都用 [DialogBudget] 先算出「窗口可用高度」，
 *     再以此同时约束卡片高度（Surface）与正文滚动区高度（LazyColumn）。
 *     正文一律内部滚动，按钮行永远留在可视区内。
 *  2. **绝不越出容器**：所有文本都带 maxLines + TextOverflow.Ellipsis
 *     （长文本该换行的换行、该省略的省略），所有 Row 的宽度都由 weight /
 *     fillMaxWidth 分配，不靠"内容自己撑"，因此不存在把兄弟节点顶出去的情况。
 *  3. **数值只有一个来源**：圆角、间距、字号、颜色、进度条厚度全部取自下文的
 *     设计规范常量（Design Tokens）与语义色（Tone*），不在各处写魔法数字、
 *     更不写死颜色 —— 写死的色值在深色主题下会直接失去对比度。
 *
 * ── 设计系统（v3 新增，为什么这样定）──────────────────────────────────
 *  间距：4dp 基准的有限梯度（2/4/6/8/10/12/16），跨弹窗复用同一组常量，
 *        于是"标题→正文""卡片→卡片"的节奏在任何屏上都一致。
 *  圆角：卡片 20 / 卡内块 16 / 胶囊 10 / 徽章 8 —— 圆角随层级递减，
 *        越靠里的元素越"小"，视觉上自然收敛。
 *  颜色：全部映射到 MaterialTheme 的语义槽位（见 [ToneAccent] 等），
 *        只保留"强调/次强调/第三强调/危险/文本/表面/轨道"七种语义，
 *        深浅主题自动适配，不再出现写死的金色银色。
 *  层级：弹窗标题块（Hero）→ 分节卡片（带序号 + 计数徽章）→ 卡内行，
 *        三级之间靠留白与字号拉开，而不是靠加线加框。
 */
internal object ChatAnalysisUi {

    // ==================================================================
    // 一、设计规范（Design Tokens）—— 全弹窗唯一的数值来源
    // ==================================================================

    // ---- 圆角梯度：卡片 20 / 卡片内块 16 / 胶囊 10 / 徽章 8 ----
    private val RadiusCard = 20.dp
    private val RadiusInner = 16.dp
    private val RadiusChip = 10.dp
    private val RadiusBadge = 8.dp

    // ---- 间距梯度（4dp 基准）：2 / 4 / 6 / 8 / 10 / 12 / 16 ----
    private val Space2 = 2.dp
    private val Space4 = 4.dp
    private val Space6 = 6.dp
    private val Space8 = 8.dp
    private val Space10 = 10.dp
    private val Space12 = 12.dp
    private val Space16 = 16.dp

    // ---- 结构节奏 ----
    /** 卡片四边内边距（上下左右一致，保证卡片内部左右留白对称） */
    private val CardPad = Space16

    /** 卡片内「标题 → 正文」的间距 */
    private val CardInnerGap = Space12

    /** 相邻分节卡片之间的留白（比行距大一档，分节感来自留白而不是线） */
    private val SectionGap = Space12

    /** 胶囊 / 芯片之间的留白 */
    private val ChipGap = Space6

    // ---- 组件尺寸 ----
    /** KPI 数字卡片的最小高度（保证一行两格高度一致） */
    private val KpiCellMinH = 96.dp

    /** KPI 网格的列/行间距 */
    private val KpiGap = Space10

    /** KPI 网格降为单列的宽度阈值（窄屏 / 大字号下两列会挤到读不清） */
    private val KpiTwoColumnMinW = 320.dp

    /** 进度条厚度（自绘，圆角不会被压平） */
    private val BarThickness = 10.dp

    /** 触控友好的图标按钮边长（44dp ≥ 无障碍最小触控尺寸） */
    private val IconTouchSize = 44.dp

    /** 会话首字方块边长（报告 / 选择页的头像位） */
    private val GlyphTileSize = 44.dp

    /** 状态图标圆底边长 */
    private val StatusIconSize = 40.dp

    /** 步骤点直径（测试进度条上的序号圆点） */
    private val StepDotSize = 20.dp

    /** 报告弹窗正文区的兜底高度（无高度预算上下文时使用） */
    private val DialogBodyFallback = 360.dp

    // ---- 弹窗高度预算 ----
    /** 弹窗总高上限 = 可用高度 × 0.9（留出上下呼吸空间，绝不顶满） */
    private const val DialogScreenFraction = 0.9f

    /**
     * 弹窗框架预留高度：标题块（Hero 最多两行 + 副标题 + 胶囊）+ 分隔线 + 按钮区
     * （v3 起按钮区最多两行）+ 内边距。实测 ~200dp，这里留到 216dp。
     *
     * 为什么宁可高估：本值只用于「扣减正文高度」，高估只会让正文短一点（仍然滚动），
     * 低估则会让正文把按钮顶出弹窗（AlertDialogContent 的 Surface 会直接裁掉）。
     */
    private val DialogChrome = 216.dp

    /** 弹窗总高下限 / 正文高度下限（超小屏也要能看能点） */
    private val MinDialogHeight = 320.dp
    private val MinBodyHeight = 160.dp

    /** 判断宿主约束是否"无界"的阈值（超过即视为无界，退回屏幕高度估算） */
    private const val UnboundedDp = 4000f

    // ---- 字号微调（只在主题字号不够用时覆盖）----
    /** 徽章内数字字号 */
    private val FsBadge = 11.sp

    /** 正文行距（bodyMedium 默认 20sp → 23sp：长段落更透气） */
    private val LhBody = 23.sp

    /** 分节标题字距（加一点字距，中文标题更像"标题"） */
    private val LsHeader = 0.5.sp

    // ---- 第 14 轮：图表几何（弹窗里的环形图 / 柱状图）----
    /** 环形图直径；[DonutStroke] 是环宽，两者都按系统字号缩放后再用 */
    private val DonutSide = 92.dp
    private val DonutStroke = 14.dp

    /** 第 15 轮：活跃热力（7×24）的几何。格子必须按字体缩放，否则大字号下星期标签会挤出格区 */
    private val HeatCellH = 13.dp
    private val HeatRowGap = 3.dp
    private val HeatCellGap = 2.dp
    private val HeatLabelW = 30.dp
    private val HeatAxisGap = 4.dp
    private val HeatLegendBox = 10.dp
    private val HeatLegendCorner = 3.dp
    private val HeatPeakStroke = 1.5f
    private const val HeatLegendSteps = 5
    /** 热力格的透明度阶梯：0 值用轨道色，非 0 值在 [HeatMinAlpha]~[HeatMaxAlpha] 之间按量级插值 */
    private const val HeatMinAlpha = 0.14f
    private const val HeatMaxAlpha = 0.92f
    /** 底部小时刻度（0/6/12/18/23）：与格区同权重划分，末位贴右边界 */
    private val HeatAxisTicks = listOf("0", "6", "12", "18", "23")

    /** 环形图扇区之间的缝隙（度）。放在这里而不是写死在画布里：扇形数量的变化只影响这里 */
    private const val DonutGapDeg = 2f

    /** 柱状图：画布高度 / 最高柱的高度（必须留出「数值标签 + 间距」的位置，否则文字会被压出画布） */
    private val ColumnChartH = 132.dp
    private val ColumnChartBarMaxH = 92.dp

    // ---- 语义色映射（唯一颜色来源，全部取自 MaterialTheme）----
    /** 强调：主行动、当前项、主要数据 */
    private val ToneAccent: Color
        @Composable
        get() = MaterialTheme.colorScheme.primary

    /** 次强调：次要分组 / 载体偏好 / 高频词 */
    private val ToneAlt: Color
        @Composable
        get() = MaterialTheme.colorScheme.secondary

    /** 第三强调：成功态、AI 相关、活跃频次 */
    private val ToneThird: Color
        @Composable
        get() = MaterialTheme.colorScheme.tertiary

    /** 危险 / 错误态 */
    private val ToneDanger: Color
        @Composable
        get() = MaterialTheme.colorScheme.error

    /** 正文色 */
    private val ToneText: Color
        @Composable
        get() = MaterialTheme.colorScheme.onSurface

    /** 次要文本色（说明、单位、辅助信息） */
    private val ToneTextDim: Color
        @Composable
        get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** 卡片底色 */
    private val ToneSurface: Color
        @Composable
        get() = MaterialTheme.colorScheme.surfaceBright

    /** 进度条轨道 / 未激活底 */
    private val ToneTrack: Color
        @Composable
        get() = MaterialTheme.colorScheme.surfaceVariant

    /**
     * 图表底轨（第 16 轮）：比 [ToneTrack] 再淡一档，专门给柱状图的"每列满高轨道"用。
     *
     * 为什么要单独一档：轨道铺满整列高度，若用和进度条同一个不透明度，
     * 空柱的轨道会比瘦高的柱子更抢眼，反而干扰读数；淡一档后它退成背景参照。
     */
    private val ToneTrackLow: Color
        @Composable
        get() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    /**
     * 强调色底上的前景色。
     *
     * 为什么用 surface 而不是写死 Color.White：surface 在浅色主题下近白、深色主题下近黑，
     * 恰好与各自主题里的 primary/secondary/tertiary 形成对比；写死白色在深色主题的
     * 浅色调强调色上会糊成一片。
     */
    private val OnAccent: Color
        @Composable
        get() = MaterialTheme.colorScheme.surface

    /** 统一卡片描边：浅色细边，深浅主题下都规整 */
    private val CardStroke: BorderStroke
        @Composable
        get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

    /** 卡片内分隔线（比描边更淡，只做行间暗示，不抢视觉） */
    private val RowDivider: Color
        @Composable
        get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    /**
     * 弹窗高度仲裁 —— 本文件所有弹窗的统一入口。
     *
     * 为什么需要：AlertDialogContent 的正文区若用固定 420/440dp 上限，
     * 「正文 + 标题 + 分隔线 + 按钮行」在小屏 / 横屏 / 系统大字号下会高于窗口可用高度，
     * 于是内容被窗口裁掉、按钮被顶出可视区（用户反馈的"很紧凑、经常超出画布"）。
     *
     * 做法：取「真实窗口约束」与「屏幕高度」的较小值 × 0.9 作为弹窗总高上限，
     * 再扣掉弹窗框架的预留高度，得到正文可用高度。因为 [totalDp] 直接约束 Surface，
     * 正文滚动区又被父级二次夹紧，所以任何字号下都不会越出窗口。
     */
    @Composable
    private fun DialogBudget(content: @Composable (totalDp: Dp, bodyDp: Dp) -> Unit) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val screen = LocalConfiguration.current.screenHeightDp.dp
            val window = if (maxHeight.value < UnboundedDp) maxHeight else screen
            val total = (minOf(screen, window) * DialogScreenFraction).coerceAtLeast(MinDialogHeight)
            val body = (total - DialogChrome).coerceAtLeast(MinBodyHeight)
            content(total, body)
        }
    }

    /**
     * 弹窗骨架 —— 把「高度预算 → AlertDialogContent → 正文限高」这套固定动作收成一处。
     *
     * 为什么要有它：v3 有 9 个弹窗，如果每个都手写一遍 heightIn(max = totalDp) /
     * 正文 heightIn(max = bodyDp)，早晚会漏掉一个（漏掉的那个必然越出窗口）。
     * 收成一个入口后，"正文一律内部滚动"这件事在结构上就不可能被忘记：
     * [body] 的形参就是「正文最大高度」，调用方不拿到它就没法写内容。
     *
     * @param title 标题块（[DialogTitle] 或 [DialogHero]）。
     * @param confirmButton 主行动区（右对齐，可传一行或两行）。
     * @param dismissButton 次行动（M3 惯例：文字按钮，视觉弱于主行动）。
     * @param body 正文；形参为正文可用高度，必须用它约束滚动容器。
     */
    @Composable
    private fun BudgetedDialog(
        title: @Composable () -> Unit,
        confirmButton: (@Composable () -> Unit)? = null,
        dismissButton: (@Composable () -> Unit)? = null,
        body: @Composable (bodyDp: Dp) -> Unit,
    ) {
        DialogBudget { totalDp, bodyDp ->
            AlertDialogContent(
                modifier = Modifier.heightIn(max = totalDp),
                title = title,
                text = { body(bodyDp) },
                confirmButton = confirmButton,
                dismissButton = dismissButton,
            )
        }
    }

    // ==================================================================
    // 二、通用小组件
    // ==================================================================

    /**
     * 弹窗标题块：主标题（titleLarge/Bold）+ 可选副标题（bodySmall/次要色）。
     * 主标题限 2 行省略、副标题自由换行，长会话名不会把标题区撑爆。
     *
     * 副标题不是装饰：每个弹窗都在这里回答"这个弹窗干什么、有什么前提"，
     * 用户不必靠猜（例如"改动即时保存"「0 = 不限制」）。
     */
    @Composable
    private fun DialogTitle(title: String, subtitle: String? = null) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ToneText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(Space6))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ToneTextDim,
                )
            }
        }
    }

    /**
     * 报告 / 选择页的标题块（Hero）：会话首字方块 + 标题 + 副标题 + 胶囊行。
     *
     * 为什么和 [DialogTitle] 分开：报告弹窗的标题区承担"报告抬头"的职责 ——
     * 一块彩色首字方块把"这是哪个会话的报告"变成一眼可辨的视觉锚点，
     * 胶囊行再把时段 / 条数 / AI 状态摊平在标题下面，读者不必往下滚就知道数据口径。
     *
     * @param chips 标题下方的胶囊行（FlowRow），传 null 则不占位。
     */
    @Composable
    private fun DialogHero(
        glyph: String,
        title: String,
        accent: Color,
        subtitle: String? = null,
        chips: @Composable (() -> Unit)? = null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphTile(glyph, accent)
                Spacer(Modifier.width(Space12))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = ToneText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(Space2))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = ToneTextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (chips != null) {
                Spacer(Modifier.height(Space10))
                chips()
            }
        }
    }

    /** 会话首字方块：强调色渐变 + 首字，报告与选择页共用同一个"身份标识"。 */
    @Composable
    private fun GlyphTile(glyph: String, accent: Color, size: Dp = GlyphTileSize) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(RadiusInner))
                .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.72f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = OnAccent,
                maxLines = 1,
            )
        }
    }

    /**
     * 分节标题。
     *
     * @param index 可选的序号（从 1 开始）。报告各分节带序号后，读者能一眼看出结构，
     *              设置页的三大块也用同一套序号视觉，全弹窗观感一致。
     * @param badge 可选的右侧计数徽章（如「5 项指标」）。v3 新增：
     *              标题右边直接标出这一节有多少条数据，"还有多少没看"一目了然。
     */
    @Composable
    fun SectionHeader(
        title: String,
        accent: Color = MaterialTheme.colorScheme.primary,
        index: Int? = null,
        badge: String? = null,
    ) {
        SectionHeaderRow(
            title = title,
            accent = accent,
            modifier = Modifier.padding(top = SectionGap),
            index = index,
            badge = badge,
        )
    }

    /**
     * 分节标题的实际渲染：3dp 竖条（accent）+ 序号徽章 + titleSmall/Bold + 可选计数徽章。
     * 竖条先 clip 再 background，保证圆角外不会溢出颜色。
     *
     * @param index 分节序号（从 1 开始）。传 null 表示不是报告分节（如设置页的状态块），
     *              不显示序号徽章 —— 报告里加了序号后，读者能一眼看出共有几大块、
     *              现在读到第几块，长篇报告的"结构感"明显更强。
     * @param badge 右侧计数徽章文本（传 null 不占位，标题仍独占剩余宽度）。
     */
    @Composable
    private fun SectionHeaderRow(
        title: String,
        accent: Color,
        modifier: Modifier = Modifier,
        index: Int? = null,
        badge: String? = null,
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    // 标题带下沿一条极淡的归属色细线（画在已有的 4dp 底部内边距里，
                    // 不占任何布局）：与 PNG 的"渐变下划线 + 细分隔线"同一视觉语言。
                    val hair = 1.5f * density
                    drawRect(
                        color = accent.copy(alpha = 0.16f),
                        topLeft = Offset(0f, size.height - hair),
                        size = Size(size.width, hair),
                    )
                }
                .padding(bottom = Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(Space8))
            if (index != null) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(RadiusBadge))
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        index.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = FsBadge),
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(Space6))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(letterSpacing = LsHeader),
                fontWeight = FontWeight.Bold,
                color = ToneText,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!badge.isNullOrBlank()) {
                Spacer(Modifier.width(Space8))
                MetaChip(badge, accent)
            }
        }
    }

    /**
     * 大按钮：整宽主行动（保留旧签名，语义等价于 kit 的 Button）。
     * 单独存在的意义是让"弹窗里唯一的整宽按钮"有统一叫法，避免各处手写 fillMaxWidth。
     */
    @Composable
    fun BigButton(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
        ) { content() }
    }

    /**
     * 胶囊外壳（**全弹窗唯一**的胶囊几何与字级定义）。
     *
     * v18 之前本文件有四套各自为政的胶囊写法（[MetaChip] / [ModuleChip] /
     * [DimensionChip] / [TocChip]），圆角、内边距、底色透明度和字号各写一遍：
     * 改一次圆角要改四处，漏一处就会出现"两种胶囊并排"的错位感。
     * 现在几何量（圆角 [RadiusChip]、内边距 Space8 × Space4、图标 14dp、字级 labelMedium、
     * 单行省略）全部收在这里，四个对外函数只负责决定"底色 + 图标 + 可点不可点"。
     *
     * @param tone  前景与底色来源色（底色统一按 [fillAlpha] 稀释，不再各处手填透明度）
     * @param icon  可选前置图标；给 null 就是纯标签胶囊
     * @param onClick 仅 [TocChip] 这种"明确可点"的胶囊才传，其余保持静态以免误触
     */
    @Composable
    private fun ChipShell(
        text: String,
        tone: Color,
        fillAlpha: Float = 0.12f,
        icon: ImageVector? = null,
        onClick: (() -> Unit)? = null,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(RadiusChip))
                .background(tone.copy(alpha = fillAlpha))
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = Space8, vertical = Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp), tint = tone)
                Spacer(Modifier.width(Space4))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = tone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * 元信息胶囊（时段 / 条数 / 模型名这类次级信息）：小字号 + 强调色浅底，
     * 与标题形成明确的字号层级。宽高都由 FlowRow 约束，长文本按测量宽度省略。
     *
     * @param icon 可选前置小图标（状态胶囊用它把"通过/失败/提示"再强调一层）。
     */
    @Composable
    private fun MetaChip(
        text: String,
        accent: Color = MaterialTheme.colorScheme.primary,
        icon: ImageVector? = null,
    ) {
        ChipShell(text = text, tone = accent, fillAlpha = 0.13f, icon = icon)
    }

    /**
     * 状态 / 说明横幅：浅色底 + 同色描边 + 前置图标。
     *
     * 为什么需要：v3 之前"帮助文字"是裸 Text，和正文混在一起分不清主次；
     * 收成横幅后，每个弹窗的"说明 / 校验 / 结果"都有统一容器与语义色，
     * 用户扫一眼颜色就知道是提示还是错误。
     *
     * @param tone 语义色（[ToneAccent] 提示 / [ToneThird] 成功 / [ToneDanger] 错误）。
     */
    @Composable
    private fun StatusBanner(
        text: String,
        tone: Color,
        icon: ImageVector,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(RadiusInner),
            color = tone.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, tone.copy(alpha = 0.25f)),
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space12, vertical = Space10),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = tone)
                Spacer(Modifier.width(Space8))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = ToneText,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    /**
     * 空状态：图标圆底 + 标题 + 说明。**每个可能没有内容的界面都必须走它**，
     * 绝不允许出现"一个空白框"（用户看到空白只会以为功能坏了）。
     */
    @Composable
    private fun EmptyState(
        icon: ImageVector,
        title: String,
        hint: String,
        modifier: Modifier = Modifier,
        tone: Color = MaterialTheme.colorScheme.primary,
    ) {
        Surface(
            shape = RoundedCornerShape(RadiusCard),
            color = ToneSurface,
            tonalElevation = 1.dp,
            border = CardStroke,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(CardPad),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(StatusIconSize)
                        .clip(CircleShape)
                        .background(tone.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = tone)
                }
                Spacer(Modifier.height(Space10))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ToneText,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space4))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = ToneTextDim,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    /**
     * 设置页 / 列表页的「分组卡片」：标题（带序号与计数徽章）+ 内容，统一圆角与描边。
     * 把散落的开关收进卡片后，弹窗从"一长条控件流"变成"几块功能"，层级一眼可辨。
     *
     * @param index 序号；传 null 表示这是状态块而非编号分节。
     */
    @Composable
    private fun GroupCard(
        title: String,
        index: Int? = null,
        accent: Color = MaterialTheme.colorScheme.primary,
        badge: String? = null,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(RadiusCard),
            color = ToneSurface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            border = CardStroke,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                SectionHeaderRow(
                    title = title,
                    accent = accent,
                    index = index,
                    badge = badge,
                    modifier = Modifier.padding(
                        start = CardPad,
                        end = CardPad,
                        top = CardPad,
                        bottom = CardInnerGap,
                    ),
                )
                content()
                Spacer(Modifier.height(Space4))
            }
        }
    }

    /** 卡片内的行间分隔线（左右与内容对齐，给 BaseWidget 行留出它们的内部缩进） */
    @Composable
    private fun InCardDivider() {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = Space16),
            color = RowDivider,
        )
    }

    /** 卡内通用细分隔线（不加横向缩进，用于已经处在卡片内边距里的内容） */
    @Composable
    private fun ThinDivider(modifier: Modifier = Modifier) {
        HorizontalDivider(modifier = modifier.fillMaxWidth(), color = RowDivider)
    }

    /**
     * 排名徽章：Top1-3 用主/次/第三强调色实底，其余用本节强调色浅底。
     *
     * 为什么不再写死金/银/铜：写死的三色在深色主题下与卡片底色对比度不足，
     * 而且和主题强调色互相打架；改用主题的三个强调槽位后，"前三名"依然醒目，
     * 却始终落在当前主题的色系里（浅色/深色/动态取色下都成立）。
     */
    @Composable
    private fun RankBadge(rank: Int, accent: Color) {
        val medal = rank in 1..3
        val container = when (rank) {
            1 -> ToneAccent
            2 -> ToneAlt
            3 -> ToneThird
            else -> accent.copy(alpha = 0.16f)
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(RadiusBadge))
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                rank.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = FsBadge),
                fontWeight = FontWeight.Bold,
                color = if (medal) OnAccent else accent,
                maxLines = 1,
            )
        }
    }

    /** 右侧「进入 / 编辑」箭头（多处复用，避免每处重写 tint） */
    @Composable
    private fun ChevronTrailing() {
        Icon(
            MaterialSymbols.Outlined.Chevron_right,
            null,
            tint = ToneTextDim,
        )
    }

    /** 次行动按钮（关闭 / 取消 / 导出 / 复制）：M3 惯例用文字按钮，视觉让位给主行动 */
    @Composable
    private fun DismissAction(text: String, onClick: () -> Unit) {
        TextButton(onClick = onClick) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    /** 带图标的文字按钮（等宽排布时用 weight 兜住，任何字号下都不会把兄弟顶出去） */
    @Composable
    private fun SecondaryAction(
        icon: ImageVector,
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        TextButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = Space8, vertical = Space6),
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(Space4))
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    /**
     * 表单输入框（模型配置 / 数值输入共用）。
     *
     * 为什么不用裸 OutlinedTextField：每个输入框都需要"标签 + 说明 + 校验态 + 可选尾部按钮"，
     * 手写四遍必然出现间距不一致。统一在这里定：说明文字永远存在（保证行高一致），
     * 校验态由 [isError] 驱动（由调用方算好，组合期不做解析）。
     */
    @Composable
    private fun AnalysisTextField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        helper: String,
        modifier: Modifier = Modifier,
        isError: Boolean = false,
        singleLine: Boolean = true,
        keyboardType: KeyboardType = KeyboardType.Text,
        visualTransformation: VisualTransformation = VisualTransformation.None,
        trailingIcon: (@Composable () -> Unit)? = null,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            isError = isError,
            supportingText = { Text(helper, style = MaterialTheme.typography.labelSmall) },
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            trailingIcon = trailingIcon,
            modifier = modifier.fillMaxWidth(),
        )
    }

    /** 表单分组卡片（内部左右带卡片内边距，输入框不再各自调间距） */
    @Composable
    private fun FormCard(
        title: String,
        index: Int? = null,
        badge: String? = null,
        accent: Color = MaterialTheme.colorScheme.primary,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(RadiusCard),
            color = ToneSurface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            border = CardStroke,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(CardPad)
            ) {
                SectionHeaderRow(title = title, accent = accent, index = index, badge = badge)
                Spacer(Modifier.height(CardInnerGap))
                content()
            }
        }
    }

    // ==================================================================
    // 三、时间范围选择
    // ==================================================================

    // 时段名称与口径说明一律走三语资源：这里只存**资源 ID**（顶层 val 不能调用
    // stringResource —— 非 @Composable 上下文），由下面的 rangeLabels()/rangeHints()
    // 在组合期解析。这样六个时段不会再各写一遍中文字面量，也不会和设置页的文案漂移。
    private val RangeLabelRes = listOf(
        R.string.chat_analysis_range_today,
        R.string.chat_analysis_range_yesterday,
        R.string.chat_analysis_range_this_week,
        R.string.chat_analysis_range_last_week,
        R.string.chat_analysis_range_this_month,
        R.string.chat_analysis_range_last_month,
    )

    private val RangeIcons = listOf(
        MaterialSymbols.Outlined.Sunny,
        MaterialSymbols.Outlined.Schedule,
        MaterialSymbols.Outlined.Star,
        MaterialSymbols.Outlined.History,
        MaterialSymbols.Outlined.Tune,
        MaterialSymbols.Outlined.Refresh,
    )

    private val RangeHintRes = listOf(
        R.string.chat_analysis_range_hint_today,
        R.string.chat_analysis_range_hint_yesterday,
        R.string.chat_analysis_range_hint_this_week,
        R.string.chat_analysis_range_hint_last_week,
        R.string.chat_analysis_range_hint_this_month,
        R.string.chat_analysis_range_hint_last_month,
    )

    /** 六个时段的显示名（组合期解析资源，顺序与 [RangeIcons] / [RangeHintRes] 一一对应） */
    @Composable
    private fun rangeLabels(): List<String> = RangeLabelRes.map { stringResource(it) }

    /** 六个时段的口径说明（同上） */
    @Composable
    private fun rangeHints(): List<String> = RangeHintRes.map { stringResource(it) }

    @Composable
    fun RangePickerContent(
        sessionName: String,
        onPick: (Int) -> Unit,
        onSettings: () -> Unit,
        onClose: () -> Unit,
    ) {
        val labels = rangeLabels()
        val hints = rangeHints()
        BudgetedDialog(
            title = {
                DialogTitle(
                    title = stringResource(R.string.chat_analysis_time_range_title),
                    subtitle = stringResource(R.string.chat_analysis_time_range_hint),
                )
            },
            dismissButton = { DismissAction(stringResource(R.string.chat_analysis_close), onClose) },
        ) { bodyDp ->
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyDp),
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                // 会话卡片置顶：先让用户确认"分析的是哪一个会话"，再看时段。
                item(key = "session") {
                    SessionCard(sessionName)
                }
                item(key = "ranges") {
                    GroupCard(
                        title = stringResource(R.string.chat_analysis_range_section),
                        index = 1,
                        badge = stringResource(R.string.chat_analysis_range_items, labels.size),
                    ) {
                        labels.forEachIndexed { index, label ->
                            if (index > 0) InCardDivider()
                            BaseWidget(
                                icon = RangeIcons[index],
                                iconPlaceholder = true,
                                title = label,
                                description = hints[index],
                                onClick = { onPick(index) },
                                trailingContent = { ChevronTrailing() },
                            )
                        }
                    }
                }
                item(key = "more") {
                    GroupCard(title = stringResource(R.string.chat_analysis_more_section), index = 2) {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Settings,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_settings_title),
                            description = stringResource(R.string.chat_analysis_settings_entry_desc),
                            onClick = onSettings,
                            trailingContent = { ChevronTrailing() },
                        )
                    }
                }
            }
        }
    }

    /** 会话卡片：首字方块 + 会话名 + 统计口径说明（口径写在这里，用户不必去猜"算不算图片"） */
    @Composable
    private fun SessionCard(sessionName: String, accent: Color = MaterialTheme.colorScheme.primary) {
        Surface(
            shape = RoundedCornerShape(RadiusCard),
            color = ToneSurface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            border = CardStroke,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(CardPad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphTile(firstGlyph(sessionName), accent)
                Spacer(Modifier.width(Space12))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.chat_analysis_current_session),
                        style = MaterialTheme.typography.labelSmall,
                        color = ToneTextDim,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(Space2))
                    Text(
                        sessionName.ifBlank { stringResource(R.string.chat_analysis_unknown_session) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ToneText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // ==================================================================
    // 四、设置
    // ==================================================================

    @Composable
    fun SettingsContent(
        features: Set<String>,
        maxCount: Int,
        sampleLimit: Int,
        lineMax: Int,
        transcriptMaxChars: Int,
        selectedModelName: String,
        onToggleFeature: (String, Boolean) -> Unit,
        onEditMaxCount: () -> Unit,
        onEditSampleLimit: () -> Unit,
        onEditLineMax: () -> Unit,
        onEditTranscriptMaxChars: () -> Unit,
        onModelManager: () -> Unit,
        onTestModel: () -> Unit,
        onClose: () -> Unit,
    ) {
        val aiOn = ChatAnalysisEngine.FEATURE_AI in features
        val statsOn = ChatAnalysisEngine.FEATURE_STATS in features
        val rankOn = ChatAnalysisEngine.FEATURE_RANK in features
        val enabledCount = listOf(aiOn, statsOn, rankOn).count { it }
        val modelReady = selectedModelName.isNotBlank()
        // 第 20 轮整合成 25 个维度；第 21 轮再凝练成 24 个（核心 12 个始终输出 + 三个进阶包各 4 个），
        // 三个开关各自记着自己的包是否输出；自己是唯一写者，改动即时落盘，下一次分析生效。
        var packTimeOn by remember { mutableStateOf(ChatAnalysisDimPacks.isEnabled(ChatAnalysisDimPacks.PACK_TIME)) }
        var packRelationOn by remember { mutableStateOf(ChatAnalysisDimPacks.isEnabled(ChatAnalysisDimPacks.PACK_RELATION)) }
        var packLanguageOn by remember { mutableStateOf(ChatAnalysisDimPacks.isEnabled(ChatAnalysisDimPacks.PACK_LANGUAGE)) }

        BudgetedDialog(
            title = {
                DialogTitle(
                    title = stringResource(R.string.chat_analysis_settings_dialog_title),
                    subtitle = stringResource(R.string.chat_analysis_settings_subtitle),
                )
            },
            dismissButton = { DismissAction(stringResource(R.string.chat_analysis_close), onClose) },
        ) { bodyDp ->
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyDp),
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                // 状态块置顶：先回答"我现在这套配置能不能跑"，再让用户去调开关。
                item(key = "status") {
                    StatusCard(
                        aiOn = aiOn,
                        statsOn = statsOn,
                        rankOn = rankOn,
                        enabledCount = enabledCount,
                        modelReady = modelReady,
                    )
                }
                item(key = "features") {
                    GroupCard(
                        title = stringResource(R.string.chat_analysis_settings_features),
                        index = 1,
                        badge = stringResource(R.string.chat_analysis_features_badge, enabledCount),
                    ) {
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Smart_toy,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_feature_ai),
                            description = stringResource(R.string.chat_analysis_feature_ai_desc),
                            checked = aiOn,
                            onCheckedChange = { onToggleFeature(ChatAnalysisEngine.FEATURE_AI, it) },
                        )
                        InCardDivider()
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Tune,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_feature_stats),
                            description = stringResource(R.string.chat_analysis_feature_stats_desc),
                            checked = statsOn,
                            onCheckedChange = { onToggleFeature(ChatAnalysisEngine.FEATURE_STATS, it) },
                        )
                        InCardDivider()
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Sort,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_feature_rank),
                            description = stringResource(R.string.chat_analysis_feature_rank_desc),
                            checked = rankOn,
                            onCheckedChange = { onToggleFeature(ChatAnalysisEngine.FEATURE_RANK, it) },
                        )
                    }
                }
                item(key = "params") {
                    GroupCard(
                        title = stringResource(R.string.chat_analysis_settings_params),
                        index = 2,
                        badge = stringResource(R.string.chat_analysis_param_items, 4),
                    ) {
                        ParamRow(
                            title = stringResource(R.string.chat_analysis_max_count_title),
                            description = if (maxCount <= 0) {
                                stringResource(R.string.chat_analysis_max_count_hint_all)
                            } else {
                                stringResource(R.string.chat_analysis_max_count_hint_value, maxCount)
                            },
                            onClick = onEditMaxCount,
                        )
                        InCardDivider()
                        ParamRow(
                            title = stringResource(R.string.chat_analysis_sample_limit_title),
                            description = stringResource(
                                R.string.chat_analysis_sample_limit_hint_value,
                                sampleLimit,
                            ),
                            onClick = onEditSampleLimit,
                        )
                        InCardDivider()
                        ParamRow(
                            title = stringResource(R.string.chat_analysis_line_max_title),
                            description = stringResource(
                                R.string.chat_analysis_line_max_hint_value,
                                lineMax,
                            ),
                            onClick = onEditLineMax,
                        )
                        InCardDivider()
                        ParamRow(
                            title = stringResource(R.string.chat_analysis_transcript_max_title),
                            description = stringResource(
                                R.string.chat_analysis_transcript_max_hint_value,
                                transcriptMaxChars,
                            ),
                            onClick = onEditTranscriptMaxChars,
                        )
                    }
                }
                item(key = "model") {
                    GroupCard(
                        title = stringResource(R.string.chat_analysis_ai_models),
                        index = 3,
                        badge = if (modelReady) {
                            stringResource(R.string.chat_analysis_model_configured)
                        } else {
                            stringResource(R.string.chat_analysis_model_unconfigured)
                        },
                    ) {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Memory,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_model_current),
                            description = selectedModelName.ifEmpty {
                                stringResource(R.string.chat_analysis_model_current_empty)
                            },
                            onClick = onModelManager,
                            trailingContent = { ChevronTrailing() },
                        )
                        InCardDivider()
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Edit,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_model_manager),
                            description = stringResource(R.string.chat_analysis_ai_models_desc),
                            onClick = onModelManager,
                            trailingContent = {
                                Icon(MaterialSymbols.Outlined.Edit, null, tint = ToneTextDim)
                            },
                        )
                        InCardDivider()
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Bolt,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_model_test),
                            description = stringResource(R.string.chat_analysis_model_test_desc),
                            onClick = onTestModel,
                            trailingContent = {
                                Icon(MaterialSymbols.Outlined.Refresh, null, tint = ToneTextDim)
                            },
                        )
                    }
                }
                // 第 20 轮整合为 25 个维度，第 21 轮再凝练为 24 个（核心 12 + 三个进阶包各 4）。
                // 三张卡就是三个进阶包的开关：关掉只少这一包的四段，核心与其它包一行都不变。
                item(key = "pack_time") {
                    GroupCard(
                        title = stringResource(R.string.chat_analysis_extra_dims_card),
                        index = 4,
                        badge = stringResource(
                            R.string.chat_analysis_extra_dims_badge,
                            ChatAnalysisDimPacks.PACK_DIM_COUNT,
                        ),
                    ) {
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Star,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_extra_dims_title),
                            description = stringResource(R.string.chat_analysis_extra_dims_desc),
                            checked = packTimeOn,
                            onCheckedChange = { on ->
                                packTimeOn = on
                                ChatAnalysisDimPacks.setEnabled(ChatAnalysisDimPacks.PACK_TIME, on)
                            },
                        )
                        InCardDivider()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Space16, vertical = Space12)
                        ) {
                            Text(
                                stringResource(R.string.chat_analysis_extra_dims_list_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = ToneText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(Space8))
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(ChipGap),
                                verticalArrangement = Arrangement.spacedBy(ChipGap),
                            ) {
                                DimensionChip(stringResource(R.string.chat_analysis_dim_trend))
                                DimensionChip(stringResource(R.string.chat_analysis_dim_latency))
                                DimensionChip(stringResource(R.string.chat_analysis_dim_density))
                                DimensionChip(stringResource(R.string.chat_analysis_dim_rounds))
                            }
                        }
                    }
                }
                item(key = "pack_relation") {
                    GroupCard(
                        title = stringResource(R.string.chat_analysis_dims17_card),
                        index = 5,
                        badge = stringResource(
                            R.string.chat_analysis_dims17_badge,
                            ChatAnalysisDimPacks.PACK_DIM_COUNT,
                        ),
                    ) {
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Star,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_dims17_title),
                            description = stringResource(R.string.chat_analysis_dims17_desc),
                            checked = packRelationOn,
                            onCheckedChange = { on ->
                                packRelationOn = on
                                ChatAnalysisDimPacks.setEnabled(ChatAnalysisDimPacks.PACK_RELATION, on)
                            },
                        )
                        InCardDivider()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Space16, vertical = Space12)
                        ) {
                            Text(
                                stringResource(R.string.chat_analysis_dims17_list_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = ToneText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(Space8))
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(ChipGap),
                                verticalArrangement = Arrangement.spacedBy(ChipGap),
                            ) {
                                DimensionChip(stringResource(R.string.chat_analysis_dim_ask_reply))
                                DimensionChip(stringResource(R.string.chat_analysis_dim_repeat))
                                DimensionChip(stringResource(R.string.chat_analysis_dim_reply_rank))
                                DimensionChip(stringResource(R.string.chat_analysis_dim_profile_radar))
                            }
                        }
                    }
                }
                item(key = "pack_language") {
                    GroupCard(
                        title = stringResource(R.string.chat_analysis_dims18_card),
                        index = 6,
                        badge = stringResource(
                            R.string.chat_analysis_dims18_badge,
                            ChatAnalysisDimPacks.PACK_DIM_COUNT,
                        ),
                    ) {
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Star,
                            iconPlaceholder = true,
                            title = stringResource(R.string.chat_analysis_dims18_title),
                            description = stringResource(R.string.chat_analysis_dims18_desc),
                            checked = packLanguageOn,
                            onCheckedChange = { on ->
                                packLanguageOn = on
                                ChatAnalysisDimPacks.setEnabled(ChatAnalysisDimPacks.PACK_LANGUAGE, on)
                            },
                        )
                        InCardDivider()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Space16, vertical = Space12)
                        ) {
                            Text(
                                stringResource(R.string.chat_analysis_dims18_list_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = ToneText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(Space8))
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(ChipGap),
                                verticalArrangement = Arrangement.spacedBy(ChipGap),
                            ) {
                                DimensionChip(stringResource(R.string.chat_analysis_dim_vocab_breadth))
                                DimensionChip(stringResource(R.string.chat_analysis_dim_typing))
                                DimensionChip(stringResource(R.string.chat_analysis_dim_appointment))
                                DimensionChip(stringResource(R.string.chat_analysis_dim_hour_volume))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 配置状态块：三个模块的开关态（芯片）+ 一条按优先级取色的结论横幅。
     *
     * 为什么放最上面：设置页有 3 个开关 + 4 个数值 + 3 个模型入口，共 10 个可点项；
     * 用户改完最想知道的是"现在能不能跑"。把校验结论前置成一块状态面板，
     * 比在 10 个控件里自己拼凑语义要省事得多。
     */
    @Composable
    private fun StatusCard(
        aiOn: Boolean,
        statsOn: Boolean,
        rankOn: Boolean,
        enabledCount: Int,
        modelReady: Boolean,
    ) {
        val tone = when {
            enabledCount == 0 -> ToneDanger
            aiOn && !modelReady -> ToneAlt
            else -> ToneThird
        }
        val icon = when {
            enabledCount == 0 -> MaterialSymbols.Outlined.Error
            aiOn && !modelReady -> MaterialSymbols.Outlined.Info
            else -> MaterialSymbols.Outlined.Check_circle
        }
        val message = when {
            enabledCount == 0 ->
                stringResource(R.string.chat_analysis_status_none_on)
            aiOn && !modelReady ->
                stringResource(R.string.chat_analysis_status_ai_no_model)
            aiOn ->
                stringResource(R.string.chat_analysis_status_ready_all)
            else ->
                stringResource(R.string.chat_analysis_status_ready_stats)
        }

        GroupCard(
            title = stringResource(R.string.chat_analysis_status_section),
            accent = tone,
            badge = stringResource(R.string.chat_analysis_features_badge, enabledCount),
        ) {
            Column(Modifier.padding(horizontal = CardPad)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ChipGap),
                    verticalArrangement = Arrangement.spacedBy(ChipGap),
                ) {
                    ModuleChip(stringResource(R.string.chat_analysis_feature_ai), aiOn)
                    ModuleChip(stringResource(R.string.chat_analysis_feature_stats), statsOn)
                    ModuleChip(stringResource(R.string.chat_analysis_feature_rank), rankOn)
                }
                Spacer(Modifier.height(Space10))
                StatusBanner(message, tone, icon)
                // 补足卡片底部内边距：GroupCard 只给内容留 4dp 收尾，
                // 状态块的横幅是"贴边元素"，必须自己凑到 CardPad 才不显得局促。
                Spacer(Modifier.height(Space12))
            }
        }
    }

    /** 模块状态芯片：开启=第三强调色 + 勾，关闭=次要文本色 + 叉（一眼分清"有没有开"） */
    @Composable
    private fun ModuleChip(label: String, on: Boolean) {
        ChipShell(
            text = label,
            tone = if (on) ToneThird else ToneTextDim,
            icon = if (on) MaterialSymbols.Outlined.Check_circle else MaterialSymbols.Outlined.Close,
        )
    }

    /**
     * 只读维度芯片（第 16 轮）：列出"这一包新增了哪几个维度"。
     *
     * 与 [ModuleChip] 的区别：那些是开/关状态芯片（带 ✓/✕ 图标），这里是纯标签，
     * 所以用无图标 + 强调色低透明底的写法，避免用户误以为它可以单独点。
     * 文字一律走三语资源（本文件不再新增硬编码中文 UI 文案）。
     */
    @Composable
    private fun DimensionChip(label: String) {
        ChipShell(text = label, tone = ToneAccent, fillAlpha = 0.10f)
    }

    /** 参数行：BaseWidget + 统一的编辑图标（四处参数行共用，避免图标/描述风格漂移） */
    @Composable
    private fun ParamRow(title: String, description: String, onClick: () -> Unit) {

        BaseWidget(
            icon = MaterialSymbols.Outlined.Tune,
            iconPlaceholder = true,
            title = title,
            description = description,
            onClick = onClick,
            trailingContent = {
                Icon(MaterialSymbols.Outlined.Edit, null, tint = ToneTextDim)
            },
        )
    }

    // ==================================================================
    // 五、模型管理
    // ==================================================================

    @Composable
    fun ModelManagerContent(
        models: List<AiModelConfig>,
        selectedName: String,
        onSelect: (AiModelConfig) -> Unit,
        onEdit: (AiModelConfig) -> Unit,
        onDelete: (AiModelConfig) -> Unit,
        onAdd: () -> Unit,
        onClose: () -> Unit,
    ) {
        val selectedModel = remember(models, selectedName) {
            models.firstOrNull { it.name == selectedName }
        }
        BudgetedDialog(
            title = {
                DialogTitle(
                    title = "AI 模型管理",
                    subtitle = "支持多套 OpenAI 兼容配置（不同 baseURL + APIKey），点按即切换当前模型。",
                )
            },
            confirmButton = {
                Button(onClick = onAdd) {
                    Icon(MaterialSymbols.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space6))
                    Text("新增模型", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = { DismissAction("关闭", onClose) },
        ) { bodyDp ->
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyDp),
                verticalArrangement = Arrangement.spacedBy(Space8),
            ) {
                item(key = "summary") {
                    StatusBanner(
                        text = if (models.isEmpty()) {
                            "暂无模型配置：AI 总结不可用，本地统计不受影响。"
                        } else {
                            "共 ${models.size} 个配置 · 当前：${selectedModel?.name ?: "未选择"}"
                        },
                        tone = ToneAccent,
                        icon = MaterialSymbols.Outlined.Memory,
                    )
                }
                if (models.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = MaterialSymbols.Outlined.Memory,
                            title = "还没有模型配置",
                            hint = "点下方「新增模型」，填写 Base URL / API Key / 模型 ID 即可。\n" +
                                "配置只保存在本机，不会上传到任何服务器。",
                        )
                    }
                }
                itemsIndexed(
                    models,
                    key = { _, model -> "model-" + model.name },
                ) { _, model ->
                    ModelRow(
                        model = model,
                        selected = model.name == selectedName,
                        onSelect = onSelect,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }

    /**
     * 模型卡片：外层 Surface 提供卡片描边，内层用 [BaseWidget] 承担点按 / 选中底色 / 尾部按钮。
     *
     * 为什么要 provide LocalSegmentedItemShape：BaseWidget 的行形状默认取
     * CornerRadius(16dp)，与卡片圆角(20dp)不同；不统一的话选中态的主色底会在
     * 卡片里露出一圈"错位的圆角"。把局部形状对齐成卡片形状后，两者严丝合缝。
     */
    @Composable
    private fun ModelRow(
        model: AiModelConfig,
        selected: Boolean,
        onSelect: (AiModelConfig) -> Unit,
        onEdit: (AiModelConfig) -> Unit,
        onDelete: (AiModelConfig) -> Unit,
    ) {
        val shape = RoundedCornerShape(RadiusCard)
        Surface(
            shape = shape,
            color = ToneSurface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            border = CardStroke,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CompositionLocalProvider(LocalSegmentedItemShape provides shape) {
                BaseWidget(
                    icon = MaterialSymbols.Outlined.Memory,
                    iconPlaceholder = true,
                    title = model.name.ifBlank { "未命名模型" },
                    description = model.model.ifBlank { "（未填模型 ID）" } + "\n" +
                        model.baseUrl.ifBlank { "（未填 Base URL）" },
                    selected = selected,
                    onClick = { onSelect(model) },
                    headlineTrailingContent = {
                        if (selected) {
                            Spacer(Modifier.width(Space6))
                            MetaChip("当前使用", ToneAccent)
                        }
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { onEdit(model) },
                            modifier = Modifier.size(IconTouchSize),
                        ) {
                            Icon(MaterialSymbols.Outlined.Edit, "编辑模型")
                        }
                        IconButton(
                            onClick = { onDelete(model) },
                            modifier = Modifier.size(IconTouchSize),
                        ) {
                            Icon(
                                MaterialSymbols.Outlined.Delete,
                                "删除模型",
                                tint = ToneDanger,
                            )
                        }
                    },
                )
            }
        }
    }

    // ==================================================================
    // 六、模型编辑
    // ==================================================================

    @Composable
    fun ModelEditContent(
        model: AiModelConfig,
        onSave: (AiModelConfig) -> Unit,
        onTest: () -> Unit,
        onClose: () -> Unit,
    ) {
        var name by remember { mutableStateOf(model.name) }
        var baseUrl by remember { mutableStateOf(model.baseUrl) }
        var apiKey by remember { mutableStateOf(model.apiKey) }
        var modelId by remember { mutableStateOf(model.model) }
        var path by remember { mutableStateOf(model.path) }
        var showKey by remember { mutableStateOf(false) }

        val urlInvalid = baseUrl.isNotBlank() &&
            !baseUrl.trim().startsWith("http://") &&
            !baseUrl.trim().startsWith("https://")
        // 校验在组合期只做一次字符串判断（不做 IO、不做正则回溯），开销可忽略；
        // 结论同时喂给「字段 isError」与「底部状态横幅」，两处永远一致。
        val missing = buildList {
            if (name.isBlank()) add("模型名称")
            if (baseUrl.isBlank()) add("Base URL")
            if (apiKey.isBlank()) add("API Key")
            if (modelId.isBlank()) add("模型 ID")
        }

        BudgetedDialog(
            title = {
                DialogTitle(
                    title = if (model.name.isBlank()) "新增模型" else "编辑模型",
                    subtitle = "OpenAI 兼容接口：Base URL + API Key + 模型 ID 三项齐全即可请求。",
                )
            },
            confirmButton = {
                // 等分宽度 + 省略号：任何字号下两个动作按钮都不会顶出弹窗
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space8),
                ) {
                    TextButton(
                        onClick = onTest,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                    ) {
                        Icon(MaterialSymbols.Outlined.Bolt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space6))
                        Text("测试连接", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = {
                            onSave(
                                AiModelConfig(
                                    name = name.trim().ifEmpty { "未命名模型" },
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                    model = modelId.trim(),
                                    path = path.trim().ifEmpty { "/chat/completions" },
                                )
                            )
                        },
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                    ) {
                        Icon(MaterialSymbols.Outlined.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space6))
                        Text("保存", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            dismissButton = { DismissAction("取消", onClose) },
        ) { bodyDp ->
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyDp),
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                item(key = "basic") {
                    FormCard(title = "基本信息", index = 1) {
                        AnalysisTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = "模型名称",
                            helper = "必填，仅用于在模型列表里区分不同配置",
                        )
                        Spacer(Modifier.height(Space10))
                        AnalysisTextField(
                            value = modelId,
                            onValueChange = { modelId = it },
                            label = "模型 ID",
                            helper = "服务端识别的模型名，如 deepseek-chat / gpt-4o-mini",
                        )
                    }
                }
                item(key = "endpoint") {
                    FormCard(title = "接口地址", index = 2) {
                        AnalysisTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = "Base URL",
                            helper = if (urlInvalid) {
                                "需以 http:// 或 https:// 开头"
                            } else {
                                "服务根地址，例：https://api.deepseek.com/v1"
                            },
                            isError = urlInvalid,
                            keyboardType = KeyboardType.Uri,
                        )
                        Spacer(Modifier.height(Space10))
                        AnalysisTextField(
                            value = path,
                            onValueChange = { path = it },
                            label = "请求路径",
                            helper = "默认 /chat/completions，兼容 OpenAI 协议时不用改",
                            keyboardType = KeyboardType.Uri,
                        )
                    }
                }
                item(key = "auth") {
                    FormCard(title = "鉴权", index = 3) {
                        AnalysisTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = "API Key",
                            helper = "仅保存在本机，用于请求鉴权；留空则无法发起 AI 总结",
                            visualTransformation = if (showKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { showKey = !showKey },
                                    modifier = Modifier.size(IconTouchSize),
                                ) {
                                    Icon(
                                        if (showKey) {
                                            MaterialSymbols.Outlined.Visibility_off
                                        } else {
                                            MaterialSymbols.Outlined.Visibility
                                        },
                                        "显示/隐藏 API Key",
                                    )
                                }
                            },
                        )
                    }
                }
                item(key = "status") {
                    StatusBanner(
                        text = when {
                            missing.isEmpty() && !urlInvalid ->
                                "配置完整：可以直接「测试连接」，或保存后在模型列表里点选使用。"
                            urlInvalid ->
                                "Base URL 格式不对：需要以 http:// 或 https:// 开头。"
                            else ->
                                "还缺：" + missing.joinToString(" / ") +
                                    "（可先保存草稿，稍后补齐；缺 Base URL 或 API Key 时请求会失败）"
                        },
                        tone = when {
                            missing.isEmpty() && !urlInvalid -> ToneThird
                            urlInvalid -> ToneDanger
                            else -> ToneAlt
                        },
                        icon = when {
                            missing.isEmpty() && !urlInvalid -> MaterialSymbols.Outlined.Check_circle
                            urlInvalid -> MaterialSymbols.Outlined.Error
                            else -> MaterialSymbols.Outlined.Info
                        },
                    )
                }
            }
        }
    }

    // ==================================================================
    // 七、报告渲染
    // ==================================================================

    sealed class ReportUnit {
        data class Section(val title: String) : ReportUnit()
        data class BarRow(val label: String, val value: String, val ratio: Float) : ReportUnit()
        data class KeyValue(val key: String, val value: String) : ReportUnit()
        data class TextLine(val text: String) : ReportUnit()
        data class WordChips(val words: List<Pair<String, Int>>) : ReportUnit()

        /** 活跃热力矩阵的一行：星期标签 + 各小时格的消息数 */
        data class HeatRow(val label: String, val values: List<Int>)

        /**
         * 活跃热力矩阵（第 15 轮新增的形状）。
         *
         * 为什么单独给一种 unit：7×24 = 168 个格子用条形行 / 柱状图都表达不了（168 根柱子会把
         * 画布挤爆），而热力图是这个维度的标准读法。数据来自报告里连续的 7 行
         * `周X → 24 个数字`，判据见 [HeatPattern]/[parseHeatRow]。
         */
        data class Heat(val rows: List<HeatRow>) : ReportUnit()

        object Gap : ReportUnit()
    }

    /**
     * 热力数据行：`周一 → 0 0 1 2 …`（恰好 24 个数字）。
     *
     * 判据刻意收得很紧（星期名 + 箭头 + 正好 24 个整数）：判不出来就退回普通正文行，
     * 绝不会把别的数字行误当成矩阵 —— 宁可不画，不画错。
     */
    private val HeatPattern = Regex("^(周[一二三四五六日])\\s+→\\s+((?:\\d{1,9}\\s+){23}\\d{1,9})$")

    /** 热力矩阵的行数与列数：与引擎（ChatAnalysisEngine）的输出口径一致 */
    private const val HeatRowCount = 7
    private const val HeatColumns = 24


    /** 键值行的判定阈值：与 PNG 导出（ChatAnalysisPng）同一套规则，弹窗与导图观感一致 */
    private const val KvMaxLineLen = 40

    /** 第 16 轮：零值分布行 `标签 0`（与 PNG 的 PLAIN_COUNT_LINE 同一判据） */
    private val PlainCountLine = Regex("^(\\S+)\\s+(\\d+)$")

    /** 汉字码点区间（U+4E00–U+9FFF）：分布行的标签必须含汉字，否则退回普通正文行 */
    private const val CjkFirstCode = 0x4E00
    private const val CjkLastCode = 0x9FFF
    private const val KvMaxKeyLen = 20
    private const val KvMaxValueLen = 18

    fun parseReport(text: String): List<ReportUnit> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<ReportUnit>()
        for (line in text.split("\n")) {
            val t = line.trim()
            when {
                t.isEmpty() -> out.add(ReportUnit.Gap)
                t.startsWith("【") && t.endsWith("】") -> {
                    // 引擎每个段前已有一个空行（Gap），此处不再额外插 Gap，
                    // 分节间距统一由 SectionHeader / 分节卡片承担。
                    out.add(ReportUnit.Section(t.removeSurrounding("【", "】")))
                }
                t.contains("█") -> {
                    val barLen = t.count { it == '█' }
                    val clean = t.replace("█", "").trim()
                    var label = clean
                    var value = ""
                    // 格式1：排行行 "1. 张三：45 条 ████" → label=排名+昵称, value=45条
                    val rankM = Regex("^(\\d+[.．、]?\\s*.*?)[:：]\\s*(\\d+)\\s*条?$").find(clean)
                    if (rankM != null) {
                        label = rankM.groupValues[1].trim()
                        value = rankM.groupValues[2] + "条"
                    } else {
                        // 格式2/3：载体偏好 "图片 5 ██"、活跃频次 "凌晨0-5点 3 ██" → 最后一个空格分隔
                        val lastSpace = clean.lastIndexOf(' ')
                        if (lastSpace > 0 && clean.substring(lastSpace + 1).trim().all { it.isDigit() }) {
                            label = clean.substring(0, lastSpace).trim()
                            value = clean.substring(lastSpace + 1).trim()
                        }
                    }
                    out.add(ReportUnit.BarRow(label, value, (barLen / 16f).coerceIn(0f, 1f)))
                }
                t.matches(Regex("^([^\\s×]+×\\d+[\\s　]*)+$")) -> {
                    // 高频词行：word×n word×n ...
                    val words = Regex("([^\\s×]+)×(\\d+)").findAll(t)
                        .map { it.groupValues[1] to it.groupValues[2].toInt() }
                        .toList()
                    if (words.isNotEmpty()) out.add(ReportUnit.WordChips(words))
                    else out.add(ReportUnit.TextLine(t))
                }
                t.contains("：") && t.length <= KvMaxLineLen -> {
                    val idx = t.indexOf("：")
                    val key = t.substring(0, idx).trim()
                    val value = t.substring(idx + 1).trim()
                    // 键或值过长（多半是整句话里带了个冒号）时不当作指标行，
                    // 否则会被渲染成"左边一句话、右边一句话"的错位两列。
                    if (key.isNotEmpty() && key.length <= KvMaxKeyLen &&
                        value.isNotEmpty() && value.length <= KvMaxValueLen
                    ) {
                        out.add(ReportUnit.KeyValue(key, value))
                    } else {
                        out.add(ReportUnit.TextLine(t))
                    }
                }
                else -> {
                    // 第 16 轮：**没有条形的分布行**（值恰好为 0 的桶 / 档位）。
                    // 引擎画条形时"值为 0 就不给块"，于是零值桶在文本里只剩 `标签 0`。
                    // 判据与 PNG 的 parsePlainCount 逐字一致，保证同一份报告在弹窗里和
                    // 导出图里被切成的块类型相同 —— 不会出现"图上一根空柱、弹窗一行裸文本"。
                    val plain = parsePlainCountRow(t)
                    out.add(plain ?: ReportUnit.TextLine(t))
                }
            }
        }
        return collapseHeat(out)
    }

    /**
     * 第 16 轮：**没有条形的分布行**（`标签 0`，即值为 0 的桶 / 档位）。
     *
     * 引擎对分布行的条形是"值 > 0 才给块"，零值桶于是只剩 `标签 0` 两个词。
     * 判据与 PNG 的 `parsePlainCount` **逐字一致**：标签不含（全/半角）冒号、
     * 标签里必须含汉字、行尾是纯数字。三条约束合起来，普通正文行几乎不可能被误判
     * （正文里带数字的句子通常在行尾还有别的字），而 `3月2日 0`、`30秒内 0`
     * 这类零值刻度行会稳稳落进条形行。
     */
    private fun parsePlainCountRow(t: String): ReportUnit.BarRow? {
        val m = PlainCountLine.find(t) ?: return null
        val label = m.groupValues[1]
        val value = m.groupValues[2]
        if (label.contains("：") || label.contains(":")) return null
        if (!label.any { it.code in CjkFirstCode..CjkLastCode }) return null
        return ReportUnit.BarRow(label, value, 0f)
    }

    /** 解析一行热力数据：格式不对、或数字个数不是 [HeatColumns] 个，一律返回 null */
    private fun parseHeatRow(text: String): ReportUnit.HeatRow? {
        val m = HeatPattern.find(text.trim()) ?: return null
        val nums = ArrayList<Int>(HeatColumns)
        for (tok in m.groupValues[2].trim().split(" ")) {
            val v = tok.toIntOrNull() ?: return null
            nums.add(v)
        }
        if (nums.size != HeatColumns) return null
        return ReportUnit.HeatRow(m.groupValues[1], nums)
    }

    /**
     * 把连续的 [HeatRowCount] 行热力数据折叠成一个 [ReportUnit.Heat]。
     *
     * 为什么不塞进上面那个逐行循环：循环是「一行一个判定」的无状态结构，而热力块是跨行才成立的
     * 条件（必须凑满 7 行）。收尾统一折叠的好处是解析循环一行都不用改，老行情的判定结果逐字不变；
     * 凑不满 7 行就整段退回普通正文行（既有降级路径，不需要新的空态）。
     */
    private fun collapseHeat(units: List<ReportUnit>): List<ReportUnit> {
        var hasHeat = false
        for (u in units) {
            if (u is ReportUnit.TextLine && HeatPattern.find(u.text.trim()) != null) {
                hasHeat = true
                break
            }
        }
        // 绝大多数报告（第 15 轮之前的段位）没有热力行，直接原样返回，不做第二次遍历
        if (!hasHeat) return units
        val out = ArrayList<ReportUnit>(units.size)
        var i = 0
        while (i < units.size) {
            val u = units[i]
            if (u is ReportUnit.TextLine) {
                val first = parseHeatRow(u.text)
                if (first != null) {
                    val rows = ArrayList<ReportUnit.HeatRow>(HeatRowCount)
                    rows.add(first)
                    var j = i + 1
                    while (j < units.size && rows.size < HeatRowCount) {
                        val next = units[j] as? ReportUnit.TextLine ?: break
                        rows.add(parseHeatRow(next.text) ?: break)
                        j++
                    }
                    if (rows.size == HeatRowCount) {
                        out.add(ReportUnit.Heat(rows))
                        i = j
                        continue
                    }
                }
            }
            out.add(u)
            i++
        }
        return out
    }

    /** 一个【段】= 一张卡片：[title] 为该段标题（null 表示报告开头无标题的前置内容）。 */
    private data class ReportBlock(val title: String?, val units: List<ReportUnit>)

    /**
     * 报告目录：分节较多时给一排可点的胶囊，点一下直接滚到那一节。
     *
     * 长报告（AI 正文动辄上万字）最痛的就是"想回看第二节要滑半天"，所以目录只做一件事：
     * 用分节序号 + 标题做锚点，点击滚过去。序号与正文章节徽章同源（都按"有标题的块"计数），
     * 不会出现目录 03 跳到正文 05 这种错位。
     */
    @Composable
    private fun ReportToc(
        entries: List<Pair<Int, String>>,
        accent: Color,
        onJump: (Int) -> Unit,
    ) {
        val tocAccent = MaterialTheme.colorScheme.tertiary
        SectionCard(
            title = stringResource(R.string.chat_analysis_toc_section),
            accent = tocAccent,
            badge = stringResource(R.string.chat_analysis_toc_badge, entries.size),
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ChipGap),
                verticalArrangement = Arrangement.spacedBy(ChipGap),
            ) {
                entries.forEachIndexed { order, entry ->
                    val label = (order + 1).toString().padStart(2, '0') + " · " + entry.second
                    TocChip(label, if (order % 2 == 0) tocAccent else accent) { onJump(entry.first) }
                }
            }
        }
    }

    /** 目录胶囊：比普通 MetaChip 多一点点击反馈与描边，明确"可以点"。 */
    @Composable
    private fun TocChip(text: String, accent: Color, onClick: () -> Unit) {
        ChipShell(text = text, tone = accent, fillAlpha = 0.10f, onClick = onClick)
    }

    /** 把线性 unit 流按 Section 切块；Gap 不再产生任何间距（节奏由卡片与标题承担）。 */
    private fun groupIntoBlocks(units: List<ReportUnit>): List<ReportBlock> {
        val blocks = mutableListOf<ReportBlock>()
        var title: String? = null
        var bucket = mutableListOf<ReportUnit>()
        for (unit in units) {
            when (unit) {
                is ReportUnit.Section -> {
                    if (bucket.isNotEmpty() || title != null) blocks.add(ReportBlock(title, bucket))
                    title = unit.title
                    bucket = mutableListOf()
                }
                is ReportUnit.Gap -> Unit // no-op：间距节奏统一为 分节 12dp → 卡片内 16dp → 行间 6dp
                else -> bucket.add(unit)
            }
        }
        if (bucket.isNotEmpty() || title != null) blocks.add(ReportBlock(title, bucket))
        return blocks
    }

    /**
     * 段位配色分流：核心指标/发言排行 → primary；载体偏好/高频词/活跃日历 → secondary；
     * 活跃频次/情绪指纹/互动节奏 → tertiary。（第 13 轮新增的三个段位沿用相邻段位的色调，
     * 保证一张报告里同族信息不同色、不会出现没有归属的「默认灰」卡片。）
     *
     * 第 14 轮新增的六个段位按主题挑色：同样是"同族信息不同色"，
     * 并且刻意与相邻章节错开（老报告里排在最后的是【昼夜结构】= primary，
     * 紧跟其后的【消息长度画像】就必须是 secondary 或 tertiary）。
     */
    @Composable
    private fun sectionAccent(title: String?, fallback: Color): Color = when {
        title == null -> fallback
        // 第 20 轮：报告整合成 25 个段位后，这里与导出图 ChatAnalysisPng.sectionAccentOf
        // 用**同一套落点**（同族信息同色、与相邻章节错开）：那边的四个固定色位在弹窗里
        // 收敛成三个主题色位（COLOR_ACCENT / 2 / 3 → ToneAccent / ToneAlt / ToneThird）。
        // 判断一律"标题包含关键词"：先吃新标题，再兜住整合前的老标题关键词，
        // 所以旧报告、AI 自由标题也能取到颜色，取不到才回退到分组色。
        title.contains("核心指标") -> ToneAccent
        title.contains("内容载体") || title.contains("载体偏好") -> ToneAlt
        title.contains("活跃时段") || title.contains("活跃频次") -> ToneThird
        title.contains("活跃热力") -> ToneAccent
        title.contains("作息与昼夜") || title.contains("昼夜结构") ||
            title.contains("昼夜话量") || title.contains("作息画像") -> ToneAlt
        title.contains("节奏与沉默") || title.contains("互动节奏") ||
            title.contains("沉默") -> ToneThird
        title.contains("消息长度") || title.contains("废话") || title.contains("每人说话") -> ToneAlt
        title.contains("情绪与语气") || title.contains("情绪指纹") ||
            title.contains("标点与语气") -> ToneThird
        title.contains("高频词") || title.contains("口头禅") -> ToneAlt
        title.contains("话题雷达") || title.contains("话题切换") ||
            title.contains("话题关键词") || title.contains("话题时段") -> ToneAccent
        title.contains("发言与互动") || title.contains("发言排行") ||
            title.contains("发言对比") || title.contains("互动平衡") -> ToneThird
        title.contains("特殊消息") || title.contains("@与互动") ||
            title.contains("撤回与系统事件") -> ToneAccent
        title.contains("每日开场") -> ToneAlt
        title.contains("活跃日历") || title.contains("每日趋势") -> ToneAccent
        title.contains("回应速度") || title.contains("回复延迟") -> ToneThird
        title.contains("活跃密度") || title.contains("连续活跃") || title.contains("发言密度") -> ToneAlt
        title.contains("连击与轮次") || title.contains("连击") || title.contains("对话轮次") -> ToneAccent
        title.contains("接话") || title.contains("提问与回应") || title.contains("默契") -> ToneAlt
        title.contains("复读") -> ToneThird
        title.contains("回复速度") -> ToneAccent
        title.contains("个人作息") -> ToneAlt
        title.contains("情绪词雷达") || title.contains("情绪词") -> ToneThird
        title.contains("打字习惯") || title.contains("打字") -> ToneAlt
        title.contains("约定与提醒") || title.contains("约定") -> ToneAccent
        title.contains("时段话量") || title.contains("话量") -> ToneThird
        title.contains("表情符号") || title.contains("媒体与表情") -> ToneAccent
        else -> fallback
    }

    /** 是否是「核心指标」式的纯 KeyValue 段，适合用 KPI 网格（大数字卡片）渲染。
     *
     * 三个条件缺一不可：全是键值行、至少两项、且至少两项的值以数字开头。
     * 为什么要卡"数字"这一条：私聊的核心指标里有「会话类型：私聊（我 / 对方）」这种文字值，
     * 它一旦进了 KPI 卡片就会被大字号渲染成"标题"，反而被省略号截断；
     * 留在普通键值行里则两列对齐、完整可读。数字类指标（消息总数/百分比）才是网格的适用场景。
     */
    private val ReportBlock.isKpiLike: Boolean
        get() = units.size >= 2 &&
            units.all { it is ReportUnit.KeyValue } &&
            units.count { it is ReportUnit.KeyValue && isNumericValue(it.value) } >= 2

    /**
     * 分节右侧计数徽章：KPI 段说"几项指标"，其余说"几行数据"（无内容则不显示徽章）。
     *
     * 走 @Composable 是为了直接取三语资源；只返回一个短 String，不参与布局测量，
     * 所以不会因为变成组合函数而多出重组开销（调用点本来就在组合里）。
     */
    @Composable
    private fun sectionBadge(block: ReportBlock): String? = when {
        block.units.isEmpty() -> null
        block.isKpiLike -> stringResource(R.string.chat_analysis_badge_metrics, block.units.size)
        else -> stringResource(R.string.chat_analysis_badge_rows, block.units.size)
    }

    /**
     * 统一分节卡片：20dp 圆角 + 浅色细描边 + 极轻投影，顶部一条渐变发丝线标明归属色。
     * 卡片宽度始终 fillMaxWidth，卡内所有文本都限行/省略，绝不会顶出卡片。
     *
     * @param badge 标题右侧的计数徽章（[sectionBadge] 算好传入）。
     */
    @Composable
    private fun SectionCard(
        title: String?,
        accent: Color,
        modifier: Modifier = Modifier,
        index: Int? = null,
        badge: String? = null,
        content: @Composable () -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(RadiusCard),
            color = ToneSurface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            border = CardStroke,
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 顶部渐变发丝线（章节主色 → 透明）：卡片有自己的"归属色"，分区一眼可辨
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(accent, accent.copy(alpha = 0.04f))
                            )
                        )
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            // 顶部一层极淡的归属色渐隐带（只画，不改任何内边距）：
                            // 卡片因此有了"底色倾向"，与 PNG 卡片的白→浅强调色渐变同源。
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(accent.copy(alpha = 0.07f), Color.Transparent),
                                    startY = 0f,
                                    endY = 56f * density,
                                )
                            )
                            drawContent()
                        }
                        .padding(CardPad)
                ) {
                    if (title != null) {
                        SectionHeaderRow(title = title, accent = accent, index = index, badge = badge)
                        Spacer(Modifier.height(CardInnerGap))
                    }
                    content()
                }
            }
        }
    }

    /**
     * 报告正文。
     *
     * 结构：概览条（数据分节 / 数据行 / AI 字数）→ 逐分节卡片。
     * 概览条放在滚动区最上方而不是弹窗标题区：标题区高度是"框架预留"，
     * 每多一行就少一行正文；放在滚动区里则无论多长都不会挤掉按钮行。
     *
     * @param maxHeight 正文最大高度（由 [DialogBudget] 给出）。
     * @param aiChars AI 报告字数；≤0 表示本次没有 AI 内容，概览条不显示这一格。
     */
    @Composable
    fun ReportContent(
        units: List<ReportUnit>,
        accent: Color = MaterialTheme.colorScheme.primary,
        maxHeight: Dp = DialogBodyFallback,
        aiChars: Int = 0,
    ) {
        val blocks = remember(units) { groupIntoBlocks(units) }
        // 分节序号预计算（只数"有标题"的块）。不能放在 itemsIndexed 里用可变计数器累加：
        // 列表项在滚动/重组时会反复执行，累加会导致序号越滚越大。
        val sectionNos = remember(blocks) {
            var n = 0
            blocks.map { b -> if (b.title != null) ++n else null }
        }
        val rowCount = remember(units) { units.count { it !is ReportUnit.Gap } }

        // 目录（快速跳转）：分节 ≥ 2 才值得给，1 节的报告加目录纯属噪音。
        val toc = remember(blocks) {
            blocks.mapIndexedNotNull { i, b -> b.title?.let { t -> i to t } }
        }
        val hasToc = toc.size >= 2
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // 目录第 i 个分节对应的 LazyColumn 下标 = 1（报告概览）+ 1（目录自身）+ i
        val tocOffset = if (hasToc) 2 else 1

        if (blocks.isEmpty()) {
            // 空报告绝不留白：给一张说明卡，用户至少知道"为什么没有内容"。
            Box(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                EmptyState(
                    icon = MaterialSymbols.Outlined.Article,
                    title = stringResource(R.string.chat_analysis_empty_report_title),
                    hint = stringResource(R.string.chat_analysis_empty_report_hint),
                )
            }
            return
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = maxHeight)
                .fadeTopWhenScrolled(listState, ToneSurface),
            contentPadding = PaddingValues(bottom = Space4),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            item(key = "overview") {
                OverviewStrip(
                    sectionCount = blocks.size,
                    rowCount = rowCount,
                    aiChars = aiChars,
                    accent = accent,
                )
            }
            if (hasToc) {
                item(key = "toc") {
                    ReportToc(toc, accent) { blockIndex ->
                        scope.launch { listState.animateScrollToItem(blockIndex + tocOffset) }
                    }
                }
            }
            itemsIndexed(
                blocks,
                key = { index, block -> "blk-" + index + "-" + block.title.orEmpty() },
            ) { index, block ->
                val blockAccent = sectionAccent(block.title, accent)
                val no = sectionNos.getOrNull(index)
                SectionCard(
                    title = block.title,
                    accent = blockAccent,
                    index = no,
                    badge = sectionBadge(block),
                ) {
                    // 「核心指标」这类**整段都是键值行**的段落，保持原来的整块 KPI 网格（与改动前逐像素一致）；
                    // 其余段落（尤其是第 14 轮新增的「指标 + 结论 + 分布」混合段）按「同类连续行」分段渲染。
                    // 判据与 PNG 导出（groupKpis / shapeBody）逐条对齐：弹窗里看到的图形，
                    // 保存出来的图片里就是同一张。
                    if (block.isKpiLike) {
                        KpiGrid(block.units.filterIsInstance<ReportUnit.KeyValue>(), blockAccent)
                    } else {
                        val runs = reportRuns(block.units)
                        runs.forEachIndexed { runIndex, run ->
                            val prevWasKv = runIndex > 0 && runs[runIndex - 1].lastOrNull() is ReportUnit.KeyValue
                            // 第 15 轮：上一段是「指标网格 / 分布图」时，本段若是纯正文（结论句），
                            // 补一条细线把"看数字"和"读结论"两件事在视觉上分层。
                            val prevWasFigure = runIndex > 0 && runs[runIndex - 1].let { prev ->
                                prev.size >= 2 && (prev.all { it is ReportUnit.KeyValue } ||
                                    prev.all { it is ReportUnit.BarRow })
                            }
                            when {
                                // 连续的可量化指标行 → KPI 网格（大数字 + 单位 + 说明）
                                run.size >= 2 && run.all { it is ReportUnit.KeyValue } -> {
                                    KpiGrid(run.filterIsInstance<ReportUnit.KeyValue>(), blockAccent)
                                }
                                // 连续的分布行 → 环形图 / 柱状图；形状不成立就退回条形行（宁可不画，不画错）
                                run.size >= 2 && run.all { it is ReportUnit.BarRow } -> {
                                    val rows = run.filterIsInstance<ReportUnit.BarRow>()
                                    when {
                                        isDonutRun(rows) -> DonutView(rows, blockAccent)
                                        isColumnRun(rows) -> ColumnChartView(rows, blockAccent)
                                        else -> rows.forEach { ReportUnitView(it, blockAccent) }
                                    }
                                }
                                else -> {
                                    // 连续的两行"键：值"之间补一条细线，把散行收成一张表；
                                    // 其它类型（条形行自带进度条）之间不加线，避免视觉噪音。
                                    if (prevWasFigure && run.all { it is ReportUnit.TextLine }) {
                                        ThinDivider(Modifier.padding(vertical = Space2))
                                    }
                                    if (prevWasKv && run.first() is ReportUnit.KeyValue) {
                                        ThinDivider(Modifier.padding(vertical = Space2))
                                    }
                                    run.forEachIndexed { i, unit ->
                                        if (i > 0 && unit is ReportUnit.KeyValue &&
                                            run[i - 1] is ReportUnit.KeyValue
                                        ) {
                                            ThinDivider(Modifier.padding(vertical = Space2))
                                        }
                                        ReportUnitView(unit, blockAccent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 长列表顶部的渐隐边界：列表上滚后，顶边压一条极淡的渐变，提示「上面还有内容」。
     *
     * 为什么用 drawWithContent 而不是再套一个 Box 叠一层：
     *  - 读 [LazyListState] 发生在**绘制阶段**，滚动时只触发重绘，不会让整份报告重组
     *    （这一点直接关系到「不能变卡」的硬约束）；
     *  - 不新增布局节点、不接触摸事件，列表的测量与滚动行为与改动前逐字节一致。
     *
     * @param fade 渐变的起始色（由调用方在组合期取好，绘制期不能再读主题色）。
     */
    private fun Modifier.fadeTopWhenScrolled(state: LazyListState, fade: Color): Modifier =
        this.drawWithContent {
            drawContent()
            val scrolled = state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0
            if (!scrolled) return@drawWithContent
            val band = (1.2f * density).coerceAtLeast(8f)
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(fade.copy(alpha = 0.9f), Color.Transparent),
                    startY = 0f,
                    endY = band,
                ),
                size = Size(size.width, band),
            )
        }

    /** 概览条：报告级 KPI 三格（分节数 / 数据行数 / AI 正文字数），像看板抬头一样先给全局量级 */
    @Composable
    private fun OverviewStrip(
        sectionCount: Int,
        rowCount: Int,
        aiChars: Int,
        accent: Color,
    ) {
        Surface(
            shape = RoundedCornerShape(RadiusCard),
            color = ToneSurface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            border = CardStroke,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(CardPad)
            ) {
                Text(
                    stringResource(R.string.chat_analysis_overview_section),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = LsHeader),
                    color = ToneTextDim,
                    maxLines = 1,
                )
                Spacer(Modifier.height(Space8))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KpiGap),
                ) {
                    MiniStat(
                        stringResource(R.string.chat_analysis_overview_sections),
                        sectionCount.toString(),
                        accent,
                        Modifier.weight(1f),
                    )
                    MiniStat(
                        stringResource(R.string.chat_analysis_overview_rows),
                        rowCount.toString(),
                        ToneAlt,
                        Modifier.weight(1f),
                    )
                    if (aiChars > 0) {
                        MiniStat(
                            stringResource(R.string.chat_analysis_overview_ai_chars),
                            aiChars.toString(),
                            ToneThird,
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    /** 概览条小格：标签 + 等宽数字（等宽字体让三格数字列对齐，读起来像仪表盘） */
    @Composable
    private fun MiniStat(label: String, value: String, tone: Color, modifier: Modifier = Modifier) {
        Column(
            modifier
                .clip(RoundedCornerShape(RadiusInner))
                .background(tone.copy(alpha = 0.08f))
                .padding(horizontal = Space10, vertical = Space8)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = ToneTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space2))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = tone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /** 数值 / 单位拆分用的正则（与 PNG 导出用同一套规则，弹窗与导图观感一致） */
    private val ValueUnitRegex = Regex("^([0-9][0-9 .,%:+\\-]*)(.*)$")

    /** 排行行前缀："1. 张三" → rank=1；不是排行行则 rank=0 */
    private val RankPrefixRegex = Regex("^(\\d+)[.．、]\\s*(.+)$")

    /** 拆出排行行序号与昵称；非排行行原样返回（rank=0） */
    private fun splitRank(label: String): Pair<Int, String> {
        val m = RankPrefixRegex.matchEntire(label.trim()) ?: return 0 to label
        val no = m.groupValues[1].toIntOrNull() ?: return 0 to label
        val name = m.groupValues[2].trim()
        if (no <= 0 || name.isEmpty()) return 0 to label
        return no to name
    }

    /**
     * KPI 网格：默认每行 2 张大数字卡片；窄屏/特大字号自动降为单列（宁可长，不要挤）。
     * 这里手写 Column + Row 而不是 LazyVerticalGrid —— 后者嵌在 LazyColumn 里高度无界会直接崩。
     * 数值按"数字 + 单位"拆开：数字大而重（等宽）、单位小而轻，读起来才像数据看板而不是表格。
     */
    @Composable
    private fun KpiGrid(items: List<ReportUnit.KeyValue>, accent: Color) {
        val shape = RoundedCornerShape(RadiusInner)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < KpiTwoColumnMinW) 1 else 2
            Column(Modifier.fillMaxWidth()) {
                items.chunked(columns).forEachIndexed { rowIndex, row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = if (rowIndex == 0) 0.dp else KpiGap)
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(KpiGap),
                    ) {
                        row.forEach { item ->
                            KpiCell(item, accent, shape, Modifier.weight(1f).fillMaxHeight())
                        }
                        // 末行不满时补空占位，保证最后一张卡片不会被拉伸成整行
                        if (row.size < columns) {
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun KpiCell(
        item: ReportUnit.KeyValue,
        accent: Color,
        shape: Shape,
        modifier: Modifier = Modifier,
    ) {
        val (number, unit) = remember(item.value) { splitValueUnit(item.value) }
        // 单位很短才做"大数字 + 小单位"同排；单位较长（含补充说明）改排到下一行，
        // 否则会被省略号吃掉信息 —— 排版只改位置，一个字都不丢。
        val inlineUnit = unit.isNotEmpty() && unit.length <= 4
        Box(
            modifier
                .clip(shape)
                .background(accent.copy(alpha = 0.08f))
                .drawWithContent {
                    // 左侧 3dp 归属色竖条（只画，不改内边距）：
                    // KPI 卡片也带上自己那一节的颜色，一张网格里能看出分组。
                    drawRect(
                        color = accent.copy(alpha = 0.55f),
                        size = Size(3.dp.toPx(), size.height),
                    )
                    drawContent()
                }
                .padding(horizontal = Space12, vertical = Space10)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = KpiCellMinH)
            ) {
                Text(
                    item.key,
                    style = MaterialTheme.typography.labelSmall,
                    color = ToneTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space4))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        number,
                        style = if (isNumericValue(number)) {
                            numericStyle(MaterialTheme.typography.titleLarge, number)
                        } else {
                            // 非数值（如「私聊（我 / 对方）」）降一档字号：大字号会把长文本截断
                            MaterialTheme.typography.bodyLarge
                        },
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (inlineUnit) {
                        Spacer(Modifier.width(Space4))
                        Text(
                            unit,
                            style = MaterialTheme.typography.labelMedium,
                            color = ToneTextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                if (!inlineUnit && unit.isNotEmpty()) {
                    Spacer(Modifier.height(Space2))
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = ToneTextDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 百分比类指标补一根细占比条：让「38%」不只是一个数字，而能一眼看出量级。
                // 与 PNG 导出里的 share bar 同款，弹窗和图片的读法一致。
                val share = remember(item.value) { shareFractionOf(item.value) }
                if (share != null) {
                    Spacer(Modifier.height(Space6))
                    ShareBar(share, accent)
                }
            }
        }
    }

    /**
     * 百分比类的占比条取值：值以 `%` 结尾且能解析成 0~100 时给出占比，否则返回 null。
     *
     * 只做「额外的视觉」：解析不出来就什么都不画，绝不改动、也不吞掉原来的数字文本。
     */
    private fun shareFractionOf(value: String): Float? {
        val t = value.trim()
        if (!t.endsWith("%")) return null
        val n = t.dropLast(1).trim().replace(",", "").toFloatOrNull() ?: return null
        if (n < 0f || n > 100f) return null
        return n / 100f
    }

    /** 一根极细的占比条（轨道 + 前景），用于 KPI 卡片内与分布行的百分比可视化 */
    @Composable
    private fun ShareBar(fraction: Float, accent: Color, thickness: Dp = 4.dp, modifier: Modifier = Modifier) {
        Box(
            modifier
                .fillMaxWidth()
                .height(thickness)
                .clip(RoundedCornerShape(thickness / 2))
                .background(ToneTrack)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(thickness / 2))
                    .background(accent.copy(alpha = 0.85f))
            )
        }
    }

    /**
     * "12,345 条" → ("12,345", "条")：把数值和单位拆开渲染（单位用轻字号）。
     * 非数字开头的值（如模型名）整串当数值，不丢字。
     */
    private fun splitValueUnit(value: String): Pair<String, String> {
        val t = value.trim()
        if (t.isEmpty()) return "" to ""
        val m = ValueUnitRegex.find(t) ?: return t to ""
        val number = m.groupValues[1].trim()
        val unit = m.groupValues[2].trim()
        return if (number.isEmpty()) t to "" else number to unit
    }

    // ---- 第 14 轮：报告行的「分段渲染」与两种图表 ----
    // 下面这批判据全部照抄 PNG 导出侧（groupKpis / shapeBody / asDonut / asColumnChart）。
    // 照抄不是偷懒：弹窗与导出各有自己的解析器，只有判据逐条一致，
    // 用户在弹窗里看到的图形，才会和保存出来的图片是同一张。

    /** 「可量化」= 值里含数字（PNG 侧 isMeasurable 的同款判据）；不含数字的值是一句话结论 */
    private fun isMeasurableValue(v: String): Boolean = v.any { it.isDigit() }

    /** 条形行的数值（只取数字字符，兼容 "1,234"）；取不出来给 -1，表示「不是分布数据」 */
    private fun countOf(r: ReportUnit.BarRow): Long = r.value.filter { it.isDigit() }.toLongOrNull() ?: -1L

    /**
     * 把一段正文切成「同类连续行」。
     *
     * 为什么需要：老代码是"整块要么全 KPI 网格、要么逐行渲染"二选一，
     * 而新增的段落是「几项指标 + 一句结论 + 一条分布」的混合体，整块二选一会把 KPI 网格整个丢掉。
     * 按连续同类行切段后，每种形状各自用最合适的渲染方式（行为与 PNG 的 groupKpis 对齐）。
     */
    private fun reportRuns(units: List<ReportUnit>): List<List<ReportUnit>> {
        val runs = ArrayList<List<ReportUnit>>(units.size)
        var i = 0
        while (i < units.size) {
            var j = i + 1
            when (val head = units[i]) {
                is ReportUnit.KeyValue -> if (isMeasurableValue(head.value)) {
                    while (j < units.size && (units[j] as? ReportUnit.KeyValue)?.let { isMeasurableValue(it.value) } == true) j++
                }
                is ReportUnit.BarRow -> while (j < units.size && units[j] is ReportUnit.BarRow) j++
                else -> Unit
            }
            runs.add(units.subList(i, j))
            i = j
        }
        return runs
    }

    /** 环形图判据（= PNG asDonut）：2~8 项、标签不含数字、数值全为正 */
    private fun isDonutRun(rows: List<ReportUnit.BarRow>): Boolean {
        if (rows.size !in 2..8) return false
        if (rows.any { r -> r.label.any { it.isDigit() } }) return false
        return rows.all { countOf(it) > 0L }
    }

    /** 柱状图判据（= PNG asColumnChart）：2~24 项、至少一个标签含数字、数值可解析且不全为 0 */
    private fun isColumnRun(rows: List<ReportUnit.BarRow>): Boolean {
        if (rows.size !in 2..24) return false
        if (rows.none { r -> r.label.any { it.isDigit() } }) return false
        val counts = rows.map { countOf(it) }
        if (counts.any { it < 0L }) return false
        return counts.any { it > 0L }
    }

    /** 百分比文本（一位小数），与 PNG 导出的百分比口径一致 */
    private fun sharePercentText(part: Long, total: Long): String =
        if (total <= 0L) "0%" else String.format(Locale.US, "%.1f%%", part.toDouble() * 100.0 / total.toDouble())

    /**
     * 环形图（弹窗版）：左边环、右边图例，环心给合计，图例给 名称 / 占比 / 原值。
     *
     * 与 PNG 导出共用同一套判据（[isDonutRun]）与同一套配色顺序，
     * 所以「谁占多少」在弹窗和图片里是同一个答案；图例仍带原值，信息一点不少。
     */
    @Composable
    private fun DonutView(rows: List<ReportUnit.BarRow>, accent: Color) {
        val fs = LocalDensity.current.fontScale
        val counts = rows.map { countOf(it).coerceAtLeast(0L) }
        val total = counts.sum().coerceAtLeast(1L)
        // 颜色在组合期取好：Canvas 的绘制 lambda 不是 @Composable，里面读不了主题色
        val palette = listOf(
            ToneAccent, ToneAlt, ToneThird,
            accent.copy(alpha = 0.72f),
            ToneAccent.copy(alpha = 0.55f),
            ToneAlt.copy(alpha = 0.55f),
            ToneThird.copy(alpha = 0.55f),
            accent.copy(alpha = 0.4f),
        )
        val track = ToneTrack
        val side = DonutSide * fs
        val stroke = DonutStroke * fs
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = (3 * fs).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(side)) {
                    val ring = stroke.toPx()
                    val inset = ring / 2f
                    val arcSize = Size(size.width - ring, size.height - ring)
                    // 轨道：先铺满整圈，扇区不足时也能看出"这是一个环"
                    drawArc(
                        color = track,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = ring),
                    )
                    var start = -90f
                    counts.forEachIndexed { index, c ->
                        val sweep = 360f * (c.toFloat() / total.toFloat())
                        if (sweep > 0f) {
                            val gap = if (counts.size > 1) DonutGapDeg else 0f
                            drawArc(
                                color = palette[index % palette.size],
                                startAngle = start + gap / 2f,
                                sweepAngle = (sweep - gap).coerceAtLeast(1f),
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = ring),
                            )
                        }
                        start += sweep
                    }
                }
                // 环心：合计。环本身会给人"这是一份占比"的直觉，中心给绝对值才不空
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        total.toString(),
                        style = if (isNumericValue(total.toString())) {
                            numericStyle(MaterialTheme.typography.titleMedium, total.toString())
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.Bold,
                        color = ToneText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.chat_analysis_chart_total),
                        style = MaterialTheme.typography.labelSmall,
                        color = ToneTextDim,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(Space12))
            Column(Modifier.weight(1f)) {
                rows.forEachIndexed { index, row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = (1 * fs).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(palette[index % palette.size])
                        )
                        Spacer(Modifier.width(Space6))
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = ToneText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Space6))
                        Text(
                            sharePercentText(counts[index], total),
                            style = MaterialTheme.typography.labelSmall,
                            color = ToneTextDim,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(Space6))
                        Text(
                            row.value,
                            style = if (isNumericValue(row.value)) {
                                numericStyle(MaterialTheme.typography.bodySmall, row.value)
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    /**
     * 柱状图（弹窗版）：横排竖柱 + 0 轴基线 + 每根柱子的数值标签 + 底部标签。
     *
     * 为什么不是"横向条形行的堆叠"：带数字标签的分布（如「凌晨0-5点」）本质是**连续时间段**，
     * 竖柱的相邻关系能直接读出"哪一段最活跃"；横向条形行读不出这种相邻性。
     * 柱高按该段最大值归一化，与 PNG 的柱状图保持同一比例，绝不按绝对像素放大失真。
     */
    @Composable
    private fun ColumnChartView(rows: List<ReportUnit.BarRow>, accent: Color) {
        val fs = LocalDensity.current.fontScale
        val counts = rows.map { countOf(it).coerceAtLeast(0L) }
        val maxV = (counts.maxOrNull() ?: 1L).coerceAtLeast(1L)
        val barShape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = (3 * fs).dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ColumnChartH * fs),
                horizontalArrangement = Arrangement.spacedBy(Space4),
            ) {
                rows.forEachIndexed { index, row ->
                    val ratio = (counts[index].toFloat() / maxV.toFloat()).coerceIn(0f, 1f)
                    val isPeak = counts[index] == maxV
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        // 柱底对齐：不同柱高都落在同一条 0 轴上，比例才可读
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            row.value,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPeak) accent else ToneTextDim,
                            fontWeight = if (isPeak) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(Space2))
                        // 第 16 轮：柱底加一条与柱等宽的浅色轨道。
                        // 小数值的柱子只有一两毫米高，"很矮"和"根本没有"肉眼分不清；
                        // 轨道给出每一列的位置参照，配合柱顶的数值标签，量级一眼可读。
                        // 轨道用固定满高（不随 ratio 变），柱高按 ratio 从底部生长 —— 两者共用同一条 0 轴。
                        Box(
                            Modifier
                                .fillMaxWidth(0.62f)
                                .height(ColumnChartBarMaxH * fs)
                                .clip(barShape)
                                .background(ToneTrackLow),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(ratio)
                                    .clip(barShape)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(accent.copy(alpha = 0.7f), accent)
                                        )
                                    )
                            )
                        }
                    }
                }
            }
            // 0 轴基线：柱状图没有基线就只剩一团色块，量级无从比较
            Spacer(Modifier.height(Space4))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(RowDivider)
            )
            Spacer(Modifier.height(Space4))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space4)) {
                rows.forEach { row ->
                    Text(
                        row.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = ToneTextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    @Composable
    private fun ReportUnitView(unit: ReportUnit, accent: Color) {
        when (unit) {
            is ReportUnit.Gap -> Unit
            is ReportUnit.Section -> SectionHeaderRow(title = unit.title, accent = accent)
            is ReportUnit.BarRow -> BarRowView(unit, accent)
            is ReportUnit.KeyValue -> KeyValueView(unit)
            is ReportUnit.WordChips -> WordChipsView(unit.words)
            is ReportUnit.Heat -> HeatView(unit, accent)
            is ReportUnit.TextLine -> {
                Text(
                    unit.text,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = LhBody),
                    color = ToneText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Space4),
                )
            }
        }
    }

    /**
     * 排行 / 占比行：第一行「序号徽章 + 标签 …… 数值」，第二行渐变进度条。
     * 标签与数值各占一份权重且都限行省略，长昵称只会被省略，不会把数值顶出卡片。
     */
    @Composable
    private fun BarRowView(unit: ReportUnit.BarRow, accent: Color) {
        val density = LocalDensity.current
        val fs = density.fontScale
        val barShape = RoundedCornerShape(BarThickness / 2)
        val ratio = unit.ratio.coerceIn(0f, 1f)
        val (rank, name) = remember(unit.label) { splitRank(unit.label) }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = (3 * fs).dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (rank > 0) {
                    RankBadge(rank, accent)
                    Spacer(Modifier.width(Space6))
                }
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = ToneText,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unit.value.isNotBlank()) {
                    Spacer(Modifier.width(Space8))
                    Text(
                        unit.value,
                        style = numericStyle(MaterialTheme.typography.bodySmall, unit.value),
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height((3 * fs).dp))
            // 自绘进度条：M3 的 LinearProgressIndicator 在小高度下会把圆角压平，
            // 这里用 Box 轨道 + Box 填充，显式 clip 保证两端圆角完整。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BarThickness * fs)
                    .clip(barShape)
                    .background(ToneTrack)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .clip(barShape)
                        // 渐变填充（浅→实）比纯色更有"数据条"的层次感，且不改变任何布局尺寸
                        .background(
                            Brush.horizontalGradient(
                                listOf(accent.copy(alpha = 0.55f), accent)
                            )
                        )
                )
            }
        }
    }

    /**
     * 键值行。
     *
     * 两种排版：值是"短数值"时左右两列对齐（像表格，便于纵向扫读）；
     * 值本身还带冒号（例如"≤5字：12%　≤20字：34%"这种一行塞了两组数据）
     * 时改为"标签在上、值在下"的通栏排版 —— 两列排版会把这种复合值截断。
     */
    @Composable
    private fun KeyValueView(unit: ReportUnit.KeyValue) {
        val fs = LocalDensity.current.fontScale
        val compound = unit.value.contains("：")
        if (compound) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = (3 * fs).dp)
            ) {
                Text(
                    unit.key,
                    style = MaterialTheme.typography.labelSmall,
                    color = ToneTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space2))
                Text(
                    unit.value,
                    style = numericStyle(MaterialTheme.typography.bodyMedium, unit.value),
                    color = ToneText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            return
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = (3 * fs).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // key / value 各占一半且都设上限，避免长文本把对方顶出卡片边界
            Text(
                unit.key,
                style = MaterialTheme.typography.bodyMedium,
                color = ToneTextDim,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(Space12))
            Text(
                unit.value,
                style = numericStyle(MaterialTheme.typography.bodyLarge, unit.value),
                fontWeight = FontWeight.SemiBold,
                color = ToneText,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * 活跃热力（周几 × 小时）。
     *
     * 为什么是热力图而不是柱状图：168 根柱子在小屏上既画不清也读不出，热力图才是这个维度的
     * 标准读法 —— 每行一天、每列一小时、颜色越深消息越多，一眼看出"这段对话什么时候活着"。
     *
     * 表现规范（与卡片内其它图元保持同一套语言）：
     *  - 左侧固定星期标签列，右侧格区按权重占满，任何字号下都不越出卡片；
     *  - 峰值格只描一圈卡片主色的细边（不引入第二种颜色，避免在浅色卡片上多出一套语义）；
     *  - 深浅色都只用「主色 + 透明度阶梯」着色，因此浅色/深色主题下观感一致；
     *  - 底部配 0/6/12/18/23 的小时刻度与「少 → 多」图例，把读法交代清楚，不用猜颜色。
     */
    @Composable
    private fun HeatView(unit: ReportUnit.Heat, accent: Color) {
        val fs = LocalDensity.current.fontScale
        val rows = unit.rows
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.values.size }
        if (cols <= 0) return
        val maxV = rows.maxOf { r -> r.values.maxOrNull() ?: 0 }.coerceAtLeast(1)
        // 峰值格：第一个达到最大值的格子（只在非 0 时描边）
        var peakRow = -1
        var peakCol = -1
        for ((ri, r) in rows.withIndex()) {
            val ci = r.values.indexOfFirst { it == maxV && it > 0 }
            if (ci >= 0) {
                peakRow = ri
                peakCol = ci
                break
            }
        }
        val track = ToneTrack
        val dim = ToneTextDim
        val cellH = HeatCellH * fs
        val rowGap = HeatRowGap * fs
        val cellGap = HeatCellGap * fs
        val labelW = HeatLabelW * fs
        val axisGap = HeatAxisGap * fs
        val legendBox = HeatLegendBox * fs
        val legendShape = RoundedCornerShape(HeatLegendCorner * fs)
        val legendAlpha = { step: Int ->
            HeatMinAlpha +
                (HeatMaxAlpha - HeatMinAlpha) * (step.toFloat() / (HeatLegendSteps - 1).toFloat())
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = (3 * fs).dp),
        ) {
            rows.forEachIndexed { ri, row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(cellH),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(labelW),
                    )
                    Canvas(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        val gap = cellGap.toPx()
                        val cw = (size.width - gap * (cols - 1)) / cols
                        if (cw <= 0f) return@Canvas
                        for (ci in 0 until cols) {
                            val v = row.values.getOrElse(ci) { 0 }
                            val left = ci * (cw + gap)
                            if (v <= 0) {
                                drawRect(
                                    color = track,
                                    topLeft = Offset(left, 0f),
                                    size = Size(cw, size.height),
                                )
                            } else {
                                val ratio = (v.toFloat() / maxV.toFloat()).coerceIn(0f, 1f)
                                drawRect(
                                    color = accent.copy(
                                        alpha = HeatMinAlpha + (HeatMaxAlpha - HeatMinAlpha) * ratio
                                    ),
                                    topLeft = Offset(left, 0f),
                                    size = Size(cw, size.height),
                                )
                            }
                            if (ri == peakRow && ci == peakCol) {
                                val sw = HeatPeakStroke * fs
                                drawRect(
                                    color = accent,
                                    topLeft = Offset(left + sw / 2f, sw / 2f),
                                    size = Size(
                                        (cw - sw).coerceAtLeast(1f),
                                        (size.height - sw).coerceAtLeast(1f),
                                    ),
                                    style = Stroke(width = sw),
                                )
                            }
                        }
                    }
                }
                if (ri < rows.size - 1) Spacer(Modifier.height(rowGap))
            }
            Spacer(Modifier.height(axisGap))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(labelW))
                Row(Modifier.weight(1f)) {
                    HeatAxisTicks.forEachIndexed { i, tick ->
                        Text(
                            text = tick,
                            style = MaterialTheme.typography.labelSmall,
                            color = dim,
                            maxLines = 1,
                            textAlign = when (i) {
                                0 -> TextAlign.Start
                                HeatAxisTicks.size - 1 -> TextAlign.End
                                else -> TextAlign.Center
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(axisGap))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    stringResource(R.string.chat_analysis_heat_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = dim,
                    maxLines = 1,
                )
                Spacer(Modifier.width(cellGap))
                repeat(HeatLegendSteps) { step ->
                    Box(
                        Modifier
                            .size(legendBox)
                            .clip(legendShape)
                            .background(accent.copy(alpha = legendAlpha(step))),
                    )
                    if (step < HeatLegendSteps - 1) Spacer(Modifier.width(cellGap))
                }
                Spacer(Modifier.width(cellGap))
                Text(
                    stringResource(R.string.chat_analysis_heat_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = dim,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun WordChipsView(words: List<Pair<String, Int>>) {
        val fs = LocalDensity.current.fontScale
        val chipShape = RoundedCornerShape(RadiusChip)
        // FlowRow 自动换行，避免词太多挤成一排被截断
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = (3 * fs).dp),
            horizontalArrangement = Arrangement.spacedBy(ChipGap),
            verticalArrangement = Arrangement.spacedBy(ChipGap),
        ) {
            words.take(20).forEachIndexed { index, (word, count) ->
                // 按热度分档：Top1-3 / 4-8 / 9-20。
                // 第 14 轮加强：除了底色，字号、内边距、文字浓度也随档位递降 ——
                // 一眼扫过去"最大的词就是最热的词"，不用逐个读数字。
                val tier = when (index) {
                    in 0..2 -> 0
                    in 3..7 -> 1
                    else -> 2
                }
                val container = when (tier) {
                    0 -> MaterialTheme.colorScheme.primaryContainer
                    1 -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                }
                val onContainer = when (tier) {
                    0 -> MaterialTheme.colorScheme.onPrimaryContainer
                    1 -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
                }
                val chipTextStyle = when (tier) {
                    0 -> MaterialTheme.typography.titleSmall
                    1 -> MaterialTheme.typography.labelMedium
                    else -> MaterialTheme.typography.labelSmall
                }
                // 文字浓度递降（只降第三档，避免尾部的词淡到看不清）
                val textAlpha = if (tier == 2) 0.82f else 1f
                Box(
                    Modifier
                        .clip(chipShape)
                        .background(container)
                        .padding(
                            horizontal = if (tier == 0) Space10 else Space8,
                            vertical = if (tier == 0) Space6 else Space4,
                        )
                ) {
                    Text(
                        "$word ×$count",
                        style = chipTextStyle,
                        color = onContainer.copy(alpha = textAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // ==================================================================
    // 八、输入弹窗
    // ==================================================================

    @Composable
    fun IntInputContent(
        title: String,
        hint: String,
        initial: Int,
        onSave: (Int) -> Unit,
        onClose: () -> Unit,
    ) {
        var text by remember { mutableStateOf(initial.toString()) }
        val parsed = text.trim().toIntOrNull()
        BudgetedDialog(
            title = {
                DialogTitle(
                    title = title,
                    subtitle = "0 = 不限制；数值越大越慢，但统计越完整。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        parsed?.let(onSave)
                        onClose()
                    },
                    enabled = parsed != null,
                ) {
                    Icon(MaterialSymbols.Outlined.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space6))
                    Text("保存", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = { DismissAction("取消", onClose) },
        ) { bodyDp ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyDp)
                    .verticalScroll(rememberScrollState())
            ) {
                StatusBanner(hint, ToneAccent, MaterialSymbols.Outlined.Info)
                Spacer(Modifier.height(SectionGap))
                AnalysisTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(9) },
                    label = "数值",
                    helper = "最多 9 位数字（上限 999,999,999）",
                    isError = parsed == null,
                    keyboardType = KeyboardType.Number,
                )
                Spacer(Modifier.height(Space10))
                // 实时回显"将会保存成什么"：输入框里的裸数字没有量级感，
                // 补一句千分位 + 语义说明，用户能立刻确认自己填的是 0 还是 0 的个数。
                StatusBanner(
                    text = when {
                        parsed == null -> "还没有填写数值：保存按钮已禁用。"
                        parsed == 0 -> "将保存为 0 = 不限制（读取该时段全部消息）。"
                        else -> "将保存为 " + formatCount(parsed ?: 0) + "。"
                    },
                    tone = if (parsed == null) ToneDanger else ToneThird,
                    icon = if (parsed == null) {
                        MaterialSymbols.Outlined.Error
                    } else {
                        MaterialSymbols.Outlined.Check_circle
                    },
                )
            }
        }
    }

    @Composable
    fun AiExtraContent(
        onStart: (String) -> Unit,
        onClose: () -> Unit,
    ) {
        var text by remember { mutableStateOf("") }
        // 常用要求做成可点芯片：用户不必每次手打同一句话，点一下就能叠加到输入框。
        val presets = listOf(
            "重点总结待办与决定",
            "多写人物发言风格",
            "按时间线还原经过",
            "语气更犀利一点",
        )
        BudgetedDialog(
            title = {
                DialogTitle(
                    title = "AI 附加要求",
                    subtitle = "可留空。填写后会被拼进提示词，用于调整总结的重点与语气。",
                )
            },
            confirmButton = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space8),
                ) {
                    if (text.isNotBlank()) {
                        TextButton(
                            onClick = { text = "" },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                        ) {
                            Icon(MaterialSymbols.Outlined.Close, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Space6))
                            Text("清空", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = {
                            onStart(text.trim())
                            onClose()
                        },
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                    ) {
                        Icon(MaterialSymbols.Outlined.Smart_toy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space6))
                        Text("开始生成", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            dismissButton = { DismissAction("取消", onClose) },
        ) { bodyDp ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyDp)
                    .verticalScroll(rememberScrollState())
            ) {
                StatusBanner(
                    "留空则只按默认提示词生成（约 1500~2500 字深度报告）。",
                    ToneAccent,
                    MaterialSymbols.Outlined.Info,
                )
                Spacer(Modifier.height(SectionGap))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("附加要求", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text("例如：重点总结讨论的事项、语气更毒舌一点") },
                    supportingText = { Text("${text.length} 字", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                )
                Spacer(Modifier.height(Space10))
                Text(
                    "常用要求（点按填入）",
                    style = MaterialTheme.typography.labelSmall,
                    color = ToneTextDim,
                    maxLines = 1,
                )
                Spacer(Modifier.height(Space6))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ChipGap),
                    verticalArrangement = Arrangement.spacedBy(ChipGap),
                ) {
                    presets.forEach { preset ->
                        PresetChip(preset) {
                            text = if (text.isBlank()) preset else text.trimEnd() + "；" + preset
                        }
                    }
                }
            }
        }
    }

    /** 可点芯片（常用要求）：点一下就把该条要求追加进输入框 */
    @Composable
    private fun PresetChip(text: String, onClick: () -> Unit) {
        Box(
            Modifier
                .clip(RoundedCornerShape(RadiusChip))
                .background(ToneAccent.copy(alpha = 0.10f))
                .clickable(onClick = onClick)
                .padding(horizontal = Space10, vertical = Space6)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = ToneAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // ==================================================================
    // 九、报告对话框
    // ==================================================================

    /**
     * 报告弹窗底部操作栏（两行）：
     *   第一行 = 次级动作（导出 PNG / 复制报告），等宽文字按钮；
     *   第二行 = 关闭（文字按钮，视觉次要）+ AI 总结（填充按钮，唯一主行动）。
     *
     * 为什么分两行：v3 之前是"两个图标按钮 + 两个等宽按钮"挤在一行，
     * 图标按钮没有文字标签（看不懂），四个动作也没分出主次。
     * 拆成两行后每个动作都有完整文字，主行动（AI 总结）在视觉上唯一突出。
     */
    @Composable
    private fun ReportActionBar(
        onExportPng: () -> Unit,
        onCopy: () -> Unit,
        onClose: () -> Unit,
        onAiSummary: (() -> Unit)? = null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 固定尾部与滚动正文之间的一条发丝线：让"这一块是不动的"有明确视觉边界，
            // 滚动到最后一张卡片时不会和按钮区糊在一起（遮挡感就来自这里）。
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(Space10))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space8),
            ) {
                SecondaryAction(
                    icon = MaterialSymbols.Outlined.Download,
                    text = stringResource(R.string.chat_analysis_export_png),
                    onClick = onExportPng,
                    modifier = Modifier.weight(1f),
                )
                SecondaryAction(
                    icon = MaterialSymbols.Outlined.Content_copy,
                    text = stringResource(R.string.chat_analysis_copy_report),
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Space8))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space8),
            ) {
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                ) {
                    Text(
                        stringResource(R.string.chat_analysis_close),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onAiSummary != null) {
                    Button(
                        onClick = onAiSummary,
                        modifier = Modifier.weight(1.15f),
                        contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                    ) {
                        Icon(MaterialSymbols.Outlined.Smart_toy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space6))
                        Text(
                            stringResource(R.string.chat_analysis_ai_summary_action),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    /** 报告头部信息胶囊（时段 / 条数 / AI 状态）：长会话名不会把标题区撑爆 */
    @Composable
    private fun ReportMetaFlow(periodLabel: String, stats: String, ai: String) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ChipGap),
            verticalArrangement = Arrangement.spacedBy(ChipGap),
        ) {
            MetaChip(periodLabel, ToneAccent)
            MetaChip(
                stringResource(R.string.chat_analysis_meta_text_n, countText(stats)),
                ToneAlt,
            )
            if (ai.isNotBlank()) {
                MetaChip(
                    stringResource(R.string.chat_analysis_meta_has_ai),
                    ToneThird,
                    MaterialSymbols.Outlined.Check_circle,
                )
            } else {
                MetaChip(stringResource(R.string.chat_analysis_meta_stats_only), ToneTextDim)
            }
        }
    }

    @Composable
    fun ReportDialogContent(
        sessionName: String,
        periodLabel: String,
        stats: String,
        ai: String,
        units: List<ReportUnit>,
        hasTranscript: Boolean,
        onAiSummary: () -> Unit,
        onExportPng: () -> Unit,
        onCopy: () -> Unit,
        onClose: () -> Unit,
    ) {
        BudgetedDialog(
            title = {
                DialogHero(
                    glyph = firstGlyph(sessionName),
                    title = sessionName.ifBlank {
                        stringResource(R.string.feature_chat_record_analysis_name)
                    },
                    accent = ToneAccent,
                    subtitle = stringResource(R.string.chat_analysis_stats_report_subtitle),
                ) {
                    ReportMetaFlow(periodLabel, stats, ai)
                }
            },
            confirmButton = {
                ReportActionBar(
                    onExportPng = onExportPng,
                    onCopy = onCopy,
                    onClose = onClose,
                    onAiSummary = onAiSummary,
                )
            },
        ) { bodyDp ->
            if (hasTranscript) {
                ReportContent(units, maxHeight = bodyDp, aiChars = ai.length)
            } else {
                Box(Modifier.fillMaxWidth().heightIn(max = bodyDp)) {
                    EmptyState(
                        icon = MaterialSymbols.Outlined.Article,
                        title = stringResource(R.string.chat_analysis_empty_stats_title),
                        hint = stringResource(R.string.chat_analysis_empty_stats_hint),
                    )
                }
            }
        }
    }

    @Composable
    fun AiReportDialogContent(
        sessionName: String,
        ai: String,
        units: List<ReportUnit>,
        onExportPng: () -> Unit,
        onCopy: () -> Unit,
        onClose: () -> Unit,
    ) {
        BudgetedDialog(
            title = {
                DialogHero(
                    glyph = firstGlyph(sessionName),
                    title = sessionName.ifBlank {
                        stringResource(R.string.feature_chat_record_analysis_name)
                    },
                    accent = ToneThird,
                    subtitle = stringResource(R.string.chat_analysis_ai_report_subtitle),
                ) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ChipGap),
                        verticalArrangement = Arrangement.spacedBy(ChipGap),
                    ) {
                        MetaChip(
                            stringResource(R.string.chat_analysis_meta_ai_generated),
                            ToneThird,
                            MaterialSymbols.Outlined.Smart_toy,
                        )
                        MetaChip(
                            stringResource(R.string.chat_analysis_meta_body_chars, ai.length),
                            ToneAlt,
                        )
                    }
                }
            },
            confirmButton = {
                ReportActionBar(
                    onExportPng = onExportPng,
                    onCopy = onCopy,
                    onClose = onClose,
                )
            },
        ) { bodyDp ->
            when {
                units.isNotEmpty() ->
                    ReportContent(
                        units,
                        MaterialTheme.colorScheme.tertiary,
                        maxHeight = bodyDp,
                        aiChars = ai.length,
                    )
                ai.isNotBlank() -> {
                    // 长文本分支：显式限高 + 内部滚动，保证按钮行始终可见、内容能滚到底。
                    // 外面套一张卡片，让"整段散文"也有明确的内容边界（不再是一堵无框文字墙）。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = bodyDp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SectionCard(
                            title = stringResource(R.string.chat_analysis_ai_body_section),
                            accent = ToneThird,
                        ) {
                            Text(
                                ai,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = LhBody),
                                color = ToneText,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxWidth().heightIn(max = bodyDp)) {
                        EmptyState(
                            icon = MaterialSymbols.Outlined.Article,
                            title = stringResource(R.string.chat_analysis_ai_empty_title),
                            hint = stringResource(R.string.chat_analysis_ai_empty_hint),
                        )
                    }
                }
            }
        }
    }

    /**
     * 从统计文本里抠出「纯文本 N 条」的 N；拿不到返回 0。
     * 只在标题胶囊里用（组合期一次字符串查找，不涉及解析报告结构）。
     */
    private fun countText(stats: String): Int {
        val m = Regex("纯文本\\s*(\\d+)").find(stats) ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    // ==================================================================
    // 十、测试结果
    // ==================================================================

    /** 模型测试弹窗状态机：拉取列表 → 点选模型 → 流式/非流式验证 → 结果 */
    sealed interface TestUiState {
        object LoadingModels : TestUiState
        data class ModelList(val models: List<String>, val error: String?) : TestUiState
        data class Testing(val model: String) : TestUiState
        data class Result(val result: AiTestResult, val error: String?) : TestUiState
    }

    @Composable
    fun TestResultContent(
        state: TestUiState,
        onTestModel: (String) -> Unit,
        onUseModel: (String) -> Unit = {},
        onClose: () -> Unit,
    ) {
        val titleText = when (state) {
            is TestUiState.LoadingModels -> "测试连接"
            is TestUiState.ModelList -> "选择要测试的模型"
            is TestUiState.Testing -> "测试中"
            is TestUiState.Result ->
                if (state.result.success) "测试通过" else "测试失败"
        }
        val subtitle = when (state) {
            is TestUiState.LoadingModels -> "先拉取服务端模型列表"
            is TestUiState.ModelList -> "已获取 ${state.models.size} 个可选模型"
            is TestUiState.Testing -> state.model
            is TestUiState.Result -> state.result.testedModel.ifBlank { "未指定模型" }
            else -> ""
        }

        BudgetedDialog(
            title = { DialogTitle(titleText, subtitle) },
            confirmButton = { TestResultActions(state, onTestModel, onUseModel, onClose) },
        ) { bodyDp ->
            when (state) {
                is TestUiState.LoadingModels -> {
                    TestProgressCard(
                        activeStep = 0,
                        model = null,
                        bodyDp = bodyDp,
                    )
                }
                is TestUiState.Testing -> {
                    TestProgressCard(
                        activeStep = 1,
                        model = state.model,
                        bodyDp = bodyDp,
                    )
                }
                is TestUiState.ModelList -> {
                    Column(Modifier.fillMaxWidth()) {
                        if (state.error.isNullOrBlank()) {
                            StatusBanner(
                                text = "已拉取 ${state.models.size} 个模型，点按任一模型即可发起验证。",
                                tone = ToneAccent,
                                icon = MaterialSymbols.Outlined.Check_circle,
                            )
                        } else {
                            StatusBanner(
                                text = "拉取列表失败：" + state.error +
                                    "（仍可在「模型管理」里手动填写模型 ID）",
                                tone = ToneDanger,
                                icon = MaterialSymbols.Outlined.Error,
                            )
                        }
                        Spacer(Modifier.height(Space8))
                        Text(
                            "验证内容：流式 + 非流式各发一次最小请求。",
                            style = MaterialTheme.typography.labelSmall,
                            color = ToneTextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Space8))
                        // 用 weight 而非固定高度：上方的横幅先占位，列表吃剩下的空间，
                        // 于是无论横幅几行、字号多大，列表都不会把按钮行顶出弹窗。
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(Space8),
                        ) {
                            itemsIndexed(
                                state.models,
                                key = { index, model -> "tm-" + index + "-" + model },
                            ) { _, model ->
                                Surface(
                                    shape = RoundedCornerShape(RadiusCard),
                                    color = ToneSurface,
                                    tonalElevation = 1.dp,
                                    border = CardStroke,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    BaseWidget(
                                        icon = MaterialSymbols.Outlined.Smart_toy,
                                        iconPlaceholder = true,
                                        title = model,
                                        description = "点按测试此模型",
                                        onClick = { onTestModel(model) },
                                        trailingContent = { ChevronTrailing() },
                                    )
                                }
                            }
                        }
                    }
                }
                is TestUiState.Result -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = bodyDp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        TestResultCard(state.result)
                        Spacer(Modifier.height(SectionGap))
                        SectionCard(
                            title = "测试详情",
                            accent = if (state.result.success) ToneThird else ToneDanger,
                        ) {
                            Text(
                                state.result.message,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = LhBody),
                                color = ToneText,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (!state.error.isNullOrBlank()) {
                            Spacer(Modifier.height(SectionGap))
                            StatusBanner(
                                text = "注意：" + state.error,
                                tone = ToneDanger,
                                icon = MaterialSymbols.Outlined.Error,
                            )
                        }
                    }
                }
            }
        }
    }

    /** 测试结果抬头：状态图标 + 结论 + 模型名 + 流式/非流式两项能力芯片 */
    @Composable
    private fun TestResultCard(result: AiTestResult) {
        val tone = if (result.success) ToneThird else ToneDanger
        Surface(
            shape = RoundedCornerShape(RadiusCard),
            color = ToneSurface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            border = CardStroke,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(CardPad)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(StatusIconSize)
                            .clip(CircleShape)
                            .background(tone.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (result.success) {
                                MaterialSymbols.Outlined.Check_circle
                            } else {
                                MaterialSymbols.Outlined.Error
                            },
                            null,
                            Modifier.size(22.dp),
                            tint = tone,
                        )
                    }
                    Spacer(Modifier.width(Space12))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (result.success) "连接正常" else "连接失败",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = tone,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(Space2))
                        Text(
                            result.testedModel.ifBlank { "（未指定模型）" },
                            style = MaterialTheme.typography.bodySmall,
                            color = ToneTextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(Space10))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ChipGap),
                    verticalArrangement = Arrangement.spacedBy(ChipGap),
                ) {
                    CapabilityChip("流式请求", result.streamOk)
                    CapabilityChip("非流式请求", result.plainOk)
                }
            }
        }
    }

    /** 单项能力芯片：通过=第三强调色 + 勾，失败=危险色 + 叉 */
    @Composable
    private fun CapabilityChip(label: String, ok: Boolean) {
        val tone = if (ok) ToneThird else ToneDanger
        Row(
            Modifier
                .clip(RoundedCornerShape(RadiusChip))
                .background(tone.copy(alpha = 0.12f))
                .padding(horizontal = Space8, vertical = Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (ok) MaterialSymbols.Outlined.Check_circle else MaterialSymbols.Outlined.Close,
                null,
                Modifier.size(14.dp),
                tint = tone,
            )
            Spacer(Modifier.width(Space4))
            Text(
                label + if (ok) "正常" else "失败",
                style = MaterialTheme.typography.labelMedium,
                color = tone,
                maxLines = 1,
            )
        }
    }

    /**
     * 测试进度卡片：三步清单 + 进度条。
     *
     * 为什么做成分步清单：拉列表 / 发两次请求 / 出结果之间有 5~20 秒空窗，
     * 只给一条转圈进度条用户会以为卡死；标出"现在在哪一步、后面还有几步"，
     * 长等待也能被理解（并给出超时说明）。
     */
    @Composable
    private fun TestProgressCard(activeStep: Int, model: String?, bodyDp: Dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = bodyDp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionCard(title = "正在测试连接", accent = ToneAccent) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space12))
                TestStepRow(
                    step = 0,
                    activeStep = activeStep,
                    text = "拉取模型列表（GET /models）",
                )
                Spacer(Modifier.height(Space8))
                TestStepRow(
                    step = 1,
                    activeStep = activeStep,
                    text = if (model.isNullOrBlank()) {
                        "验证模型可用性（流式 + 非流式）"
                    } else {
                        "验证模型「" + model + "」（流式 + 非流式）"
                    },
                )
                Spacer(Modifier.height(Space8))
                TestStepRow(step = 2, activeStep = activeStep, text = "生成测试结果")
            }
            Spacer(Modifier.height(SectionGap))
            StatusBanner(
                text = "大模型首次响应可能较慢，通常 5~20 秒；超时或失败会给出具体原因。",
                tone = ToneAccent,
                icon = MaterialSymbols.Outlined.Info,
            )
        }
    }

    /** 进度步骤行：序号圆点按"已完成/进行中/未开始"取色，进行中的一行加粗 */
    @Composable
    private fun TestStepRow(step: Int, activeStep: Int, text: String) {
        val done = step < activeStep
        val active = step == activeStep
        val tone = when {
            active -> ToneAccent
            done -> ToneThird
            else -> ToneTextDim
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(StepDotSize)
                    .clip(CircleShape)
                    .background(if (active || done) tone else ToneTrack),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(
                        MaterialSymbols.Outlined.Check,
                        null,
                        Modifier.size(12.dp),
                        tint = OnAccent,
                    )
                } else {
                    Text(
                        (step + 1).toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = FsBadge),
                        fontWeight = FontWeight.Bold,
                        color = if (active) OnAccent else ToneTextDim,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(Space10))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active || done) ToneText else ToneTextDim,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * 测试弹窗的底部按钮区。
     *
     * 三种状态各一套：加载/测试中不给按钮（避免用户误以为要确认）；
     * 列表态只给「关闭」；结果态给「再测一次」（次）与「关闭 + 同步为当前模型」（主）。
     * 结果态用两行而不是一行三按钮：三个中文标签挤在一行必然被省略号截断。
     */
    @Composable
    private fun TestResultActions(
        state: TestUiState,
        onTestModel: (String) -> Unit,
        onUseModel: (String) -> Unit,
        onClose: () -> Unit,
    ) {
        when (state) {
            is TestUiState.ModelList -> {
                TextButton(onClick = onClose) {
                    Text("关闭", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            is TestUiState.Result -> {
                val result = state.result
                Column(Modifier.fillMaxWidth()) {
                    if (result.testedModel.isNotBlank()) {
                        Row(Modifier.fillMaxWidth()) {
                            SecondaryAction(
                                icon = MaterialSymbols.Outlined.Refresh,
                                text = "再测一次",
                                onClick = { onTestModel(result.testedModel) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(Space8))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space8),
                    ) {
                        TextButton(
                            onClick = onClose,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                        ) {
                            Text("关闭", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (result.success && result.testedModel.isNotBlank()) {
                            Button(
                                onClick = { onUseModel(result.testedModel) },
                                modifier = Modifier.weight(1.6f),
                                contentPadding = PaddingValues(horizontal = Space10, vertical = Space6),
                            ) {
                                Icon(MaterialSymbols.Outlined.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(Space6))
                                Text("同步为当前模型", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    // ==================================================================
    // 十一、工具
    // ==================================================================

    /**
     * 数值字体：数字开头的文本用等宽字体（tabular 效果），中文/混合文本保持默认字体。
     *
     * 为什么只对"数字开头"生效：报告里的数值列（12,345 / 45条 / 62%）在等宽字体下
     * 位数对齐、扫读更快；而像"正常人类浓度"这种文字值用等宽字体会显得突兀。
     */
    private fun numericStyle(base: TextStyle, value: String): TextStyle {
        return if (isNumericValue(value)) base.copy(fontFamily = FontFamily.Monospace) else base
    }

    /** 值是否以数字开头（= 适合等宽 + 大字号渲染的数值型内容） */
    private fun isNumericValue(value: String): Boolean {
        val first = value.trim().firstOrNull() ?: return false
        return first in '0'..'9'
    }

    /** 千分位格式化（固定 Locale.US：不同地区分隔符不一致会误导用户读数量级） */
    private fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)

    /** 取首个码点作为"头像字"：用 codePointAt 而不是 first()，避免把 emoji 切成半个代理对 */
    private fun firstGlyph(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return "#"
        return runCatching { String(Character.toChars(t.codePointAt(0))) }.getOrDefault("#")
    }
}
