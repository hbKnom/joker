package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天记录分析 —— 报告 PNG 导出（排版重构版 v4 · 1440px 专业报告画布）
 *
 * ── 为什么是 1440 宽 ──────────────────────────────────────────────────────
 * v3 的 1080px 在微信里二次压缩后标题与 KPI 数字发虚。v4 把画布加宽到 1440px
 * （+33% 分辨率），同时把高度上限从 30000 收到 20000：
 *   峰值内存 1440 × 20000 × 4B ≈ 115MB < v3 的 1080 × 30000 × 4B ≈ 130MB。
 * 即"更清晰"和"更省内存"同时成立 —— 宿主堆只有 512MB，这条底线不能破，
 * 因此 [MEMORY_BUDGET_BYTES] 与 init 里的 require 会把预算钉死在编译期常量上。
 *
 * ── 几何模型（全部 px，基于 1440 宽画布）─────────────────────────────────
 *
 *   0    CANVAS_PAD(72)                                     W-CANVAS_PAD(1368) 1440
 *   │◄───────►│                                                        │◄────────►│
 *   ┌───────────────────────────────────────────────────────────────────────────┐
 *   │ 卡片：CARD_LEFT(72) … CARD_RIGHT(1368)（圆角 / 细描边 / 左侧渐变强调条统一）│
 *   │  ┌ CARD_PAD_H(56) ─────────────────────────────────────── CARD_PAD_H(56) ┐ │
 *   │  │          内容区 CONTENT_LEFT(128) … CONTENT_RIGHT(1312)              │ │
 *   │  └──────────────────────────────────────────────────────────────────────┘ │
 *   └───────────────────────────────────────────────────────────────────────────┘
 *
 *   条形行严格三列（列间 COL_GAP(24)，两两互不相交）：
 *     [ label ← LABEL_W(473) ] gap [ value ← VALUE_W(213) ] gap [ bar ← BAR_W(450) ]
 *
 * ── 每个【】章节 = 一张独立卡片 ──────────────────────────────────────────
 *   卡片头部：序号徽章（渐变底 + 白色数字）→ 标题 → 渐变下划线 + 全宽细分隔线。
 *   正文里连续的 "键：值" 行聚合成 KPI 大数字网格（每行 2 格、等宽等高、单位同行或另起一行、
 *   百分比数值额外带一根份额进度条）。
 *
 *   三类"数据形状"会被自动图形化（只按结构判定，不依赖章节标题字符串，报告改字也不会失效）：
 *     · 无序号、标签不含数字的 2~8 项分布 → 环形图 + 图例（数值 + 占比）
 *     · 无序号、标签含数字的 2~24 项分布 → 柱状图（网格线 / 均值虚线 / 峰值标注 / x 轴刻度）
 *     · 含 ≥3 个 "词×次数" 的长整行       → 自适应换行的标签云
 *   判不出来的一律退回原来的条形行/正文，绝不猜。
 *
 * ── 排版铁律 ─────────────────────────────────────────────────────────────
 *  1. 所有卡片左右边界统一（CARD_LEFT / CARD_RIGHT），上下内边距统一（CARD_PAD_V）。
 *  2. 两遍法：先做一遍纯几何布局（layoutHeader / buildCards → 每行 top/height），
 *     再按同一份几何一次性绘出，卡片高度由内容高度反推，文字不可能溢出卡片。
 *  3. 每段文字都画在自己列的矩形内（canvas.save → clipRect → drawText → restore），
 *     每张卡片再额外 clip 一次卡片矩形，即便测量有偏差也绝不会串到相邻列/卡片外。
 *  4. 文字基线统一由 fitBaseline() 计算，并受容器底边硬约束：
 *     baseline + descent ≤ 容器 bottom（越界则上移，永不溢出）。
 *  5. 画布高度按内容累加，底部留 BOTTOM_PAD 收尾；超长报告自动分页（绝不因为"太长"导出失败）。
 *  6. 只有 1 张 Bitmap（即画布本身），不做任何全图拷贝，避免宿主堆 OOM。
 *  7. 尺寸常量是 Int，凡流入 RectF / drawText / fitBaseline 一律 .toFloat()
 *     （这条踩过 4 次编译坑，属于硬性纪律）。
 */
object ChatAnalysisPng {

    // ==================================================================
    // 一、统一度量常量（全部 px，基于 1440 宽画布）
    // ==================================================================

    /** 画布宽度 */
    private const val W = 1440

    /** 画布左右安全边距（硬约束：≥ 48px，所有卡片都对它对齐） */
    private const val CANVAS_PAD = 72

    /** 卡片内水平内边距 */
    private const val CARD_PAD_H = 56

    /** 卡片内垂直内边距（上下一致） */
    private const val CARD_PAD_V = 48

    /** 卡片与卡片之间的间距 */
    private const val CARD_GAP = 52

    /** 卡片圆角半径（全文件所有卡片、徽章、KPI 单元共用一套圆角语言） */
    private const val CARD_RADIUS = 40f

    /** 卡片左侧强调条宽度 */
    private const val CARD_ACCENT_W = 10

    /** 卡片最小高度 */
    private const val CARD_MIN_H = 220

    /** 卡片投影半径 / 垂直偏移 */
    private const val CARD_SHADOW_RADIUS = 24f
    private const val CARD_SHADOW_DY = 8f

    /** 卡片底色渐变到"强调色 6% 混白"的透明度（很淡，只做层次不抢内容） */
    private const val CARD_TINT_ALPHA = 0x10

    /** 卡片描边粗细 / 顶部高光细线高度与透明度 */
    private const val CARD_STROKE_W = 2f
    private const val CARD_TOP_LIGHT_H = 2f
    private const val CARD_TOP_LIGHT_ALPHA = 0x22

    /** 画布底部收尾留白 */
    private const val BOTTOM_PAD = 88

    /** 条形行 / 单行键值行高 */
    private const val ROW_H = 88

    /** 正文文本行高 */
    private const val TEXT_LINE_H = 74

    /** 正文文本行之间的额外留白 */
    private const val TEXT_LINE_GAP = 24

    /** 空行占位高度（段间距） */
    private const val GAP_H = 34

    /** 章节卡片头部高度（序号徽章 + 标题 + 渐变下划线） */
    private const val SECTION_HEADER_H = 168

    /** 章节标题下方的渐变下划线宽度 / 厚度 */
    private const val SECTION_UNDERLINE_W = 240
    private const val SECTION_UNDERLINE_H = 8f

    /** 章节头部下划线与右侧细分隔线之间的留白 */
    private const val SECTION_RULE_GAP = 28

    /** 章节序号徽章与标题之间的间距 / 标题带到下划线的留白 */
    private const val SECTION_TITLE_GAP = 24
    private const val SECTION_UNDERLINE_GAP = 26

    /** 章节序号徽章边长 */
    private const val SECTION_BADGE_BOX = 76

    /** 分组 pill（本地统计报告 / AI 洞察报告）高度 */
    private const val GROUP_PILL_H = 92

    /** 分组 pill 与上一张卡片的间距（分组间隔更大，节奏分明） */
    private const val GROUP_PILL_GAP = 60

    /** 分组 pill 与紧随其后的卡片之间的间距 */
    private const val GROUP_PILL_CARD_GAP = 20

    /** 分组 pill 最大宽度 */
    private const val GROUP_PILL_MAX_W = 700

    /** 分组 pill 内部：左内边距 / 圆点直径 / 圆点与文字间距 / 右内边距 */
    private const val GROUP_PILL_PAD_L = 34
    private const val GROUP_PILL_DOT = 22
    private const val GROUP_PILL_DOT_GAP = 22
    private const val GROUP_PILL_PAD_R = 32

    /**
     * 页脚高度 / 内部节奏。
     *
     * 第 14 轮在「文字行」之上加了一行**元信息**（会话名 · 时间范围 · 生成时间 · 数据来源脚注），
     * 所以高度按 [FOOTER_META_H] + [FOOTER_META_GAP] 加高，`init` 里的排版断言同步收紧 ——
     * 页脚高度、分页几何、单页最大高度都由常量推导，改这里一处即可，不会出现"页脚压住最后一张卡片"。
     */
    private const val FOOTER_H = 152
    private const val FOOTER_TOP_GAP = 12

    /** 元信息行高 / 元信息与品牌页码行的间距 */
    private const val FOOTER_META_H = 44
    private const val FOOTER_META_GAP = 12
    private const val FOOTER_TEXT_H = 54
    private const val FOOTER_STRIP_GAP = 12
    private const val FOOTER_STRIP_H = 7f

    /**
     * 多页导出的「续页页头」留白带（第 21 轮：续页观感修复）。
     *
     * 分页导出的绘制是「每页重放同一套绘制代码」，页头信息卡只落在第 1 页的页内，
     * 第 2 页起页顶就是没有任何标识的裸内容（页头卡片的绘制落在页外、被页裁剪丢掉）——
     * 看图的人分不清这是续页、还是画漏了页头，而内容直接贴到画布上边缘，观感上也像"内容溢出了画布"。
     *
     * 现在第 2 页起每页顶部预留本高度的留白带，带内画一条轻量续页横幅（品牌 · 会话名 + 第 N / M 页）。
     * 留白带**参与分页容量计算**（[paginate] 里续页容量 = 单页容量 − 本高度），所以它只会把内容往下推，
     * 永远不会压到内容上；单页导出（绝大多数报告）完全不受影响。
     */
    private const val PAGE_HEAD_BAND_H = 132

    /** 续页横幅文字行高（横幅在留白带内垂直居中） */
    private const val PAGE_HEAD_TEXT_H = 56

    /** 续页横幅左侧「品牌 · 会话名」占内容区宽度的比例（右侧留给页码，两者永不重叠） */
    private const val PAGE_HEAD_NAME_W_RATIO = 0.56f

    /** 顶部信息卡：品牌行高 */
    private const val BRAND_LINE_H = 84

    /** 顶部信息卡：品牌小方块边长 */
    private const val BRAND_BOX = 22

    /** 顶部信息卡：品牌行与主标题之间的细分隔线留白 */
    private const val HEADER_DIVIDER_GAP = 14

    /** 顶部信息卡：主标题行高 */
    private const val HEADER_TITLE_LINE_H = 116

    /** 顶部信息卡：副标题 / 生成时间行高 */
    private const val HEADER_META_LINE_H = 70

    /** 顶部信息卡：品牌行与主标题之间留白 */
    private const val HEADER_BRAND_TITLE_GAP = 30

    /** 顶部信息卡：主标题与副标题之间留白 */
    private const val HEADER_TITLE_SUB_GAP = 14

    /** 顶部信息卡：副标题与生成时间之间留白 */
    private const val HEADER_SUB_GEN_GAP = 10

    /** 顶部信息卡右上角装饰圆的半径（纯装饰，被卡片 clip 裁掉） */
    private const val HEADER_GLOW_R = 300f

    /** 顶部信息卡头像边长 */
    private const val AVATAR_SIZE = 152

    /** 头像与右侧文字列的水平间距 */
    private const val AVATAR_TEXT_GAP = 40

    /** 右上角范围徽章高度 */
    private const val BADGE_H = 76

    /** 右上角范围徽章水平内边距（单侧） */
    private const val BADGE_PAD_H = 34

    /** 右上角范围徽章最大宽度（超出则截断，防止挤占品牌行） */
    private const val BADGE_MAX_W = 480

    /** 列间距 */
    private const val COL_GAP = 24

    /** 条形轨高度 */
    private const val BAR_TRACK_H = 30f

    /** 条形最小可见宽度 */
    private const val BAR_MIN_W = 8f

    /** 文本 / 标签在列内的横向内缩 */
    private const val CELL_INSET = 8

    /** 排行行序号徽章（与 App 内报告视图同款）：边长 / 与标签的间距 / 字号 */
    private const val RANK_BOX = 56
    private const val RANK_GAP = 18
    private const val FS_RANK = 32f

    /**
     * KPI 网格内部节奏（单元高度由内部节奏推导，不再写死）：
     *   上内边距 + 标签行 + 标签↔数值间隙 + 数值行 + 下内边距 = KPI_CELL_H
     * 单位过长时（放不进数值行）额外加一行单位：+KPI_UNIT_GAP+KPI_UNIT_ROW_H。
     * 数值是百分比时再额外加一根份额进度条：[shareFraction] 非空才算。
     */
    private const val KPI_PAD_H = 34
    private const val KPI_PAD_V = 34
    private const val KPI_COL_GAP = 24

    /**
     * 第 17 轮：KPI 单元左侧那条归属色细条的宽度（比卡片的 CARD_ACCENT_W 更细）。
     * 只用于绘制，不参与任何几何计算，因此不进 init 的静态门禁。
     */
    private const val KPI_STRIP_W = 8f
    private const val KPI_ROW_GAP = 28
    private const val KPI_LABEL_ROW_H = 46
    private const val KPI_LABEL_VALUE_GAP = 12
    private const val KPI_VALUE_ROW_H = 84
    private const val KPI_UNIT_GAP = 6
    private const val KPI_UNIT_ROW_H = 42
    private const val KPI_SHARE_GAP = 16
    private const val KPI_SHARE_BAR_H = 12
    private const val KPI_CELL_H = KPI_PAD_V * 2 + KPI_LABEL_ROW_H + KPI_LABEL_VALUE_GAP + KPI_VALUE_ROW_H
    private const val KPI_CELL_H_TALL = KPI_CELL_H + KPI_UNIT_GAP + KPI_UNIT_ROW_H

    /** 单位短于等于这个长度才与数值同排；更长则另起一行（绝不因省略号丢信息） */
    private const val KPI_UNIT_INLINE_MAX = 4

    // ---- 图形化的规模上限（超过就退回条形行：环太细 / 柱太密都不好看）----

    /** 环形图最多扇区数 / 柱状图最多柱数 / 判定标签云的最少词条数 */
    private const val DONUT_MAX_SLICES = 8
    private const val CHART_MAX_BARS = 24
    private const val CHIP_MIN_TOKENS = 3

    // ---- 环形图（分布）----

    /** 环外径 / 环厚 / 扇区间隙（度） */
    private const val DONUT_SIZE = 380
    private const val DONUT_RING_W = 62f
    private const val DONUT_GAP_ANGLE = 2.2f

    /** 环与图例的水平间距 / 图例行高 / 色块边长 / 色块与文字间距 */
    private const val DONUT_LEGEND_GAP = 64
    private const val DONUT_LEGEND_ROW_H = 74
    private const val DONUT_LEGEND_SWATCH = 30
    private const val DONUT_LEGEND_SWATCH_GAP = 20

    /** 图例"数值 + 占比"之间的间距 */
    private const val DONUT_LEGEND_VALUE_GAP = 18

    /** 环心合计的说明文字（数值本身就是报告里各项之和，不做任何改写） */
    private const val DONUT_SUM_LABEL = "合计"

    // ---- 柱状图（时间分布）----

    /** 柱状图：顶部留白（够放峰值数字）/ 柱区高度 / x 轴标签区高度 / 轴标签与轴线的间距 */
    private const val CHART_TOP_PAD = 48
    private const val CHART_BAR_AREA_H = 300
    private const val CHART_AXIS_H = 60
    private const val CHART_AXIS_GAP = 10

    /** 柱状图左侧刻度槽宽度（放 y 轴数值） */
    private const val CHART_GUTTER_W = 110

    /** 柱宽上限 / 下限，以及柱底与轴线的留白（让柱体像"浮"在轴上，更像设计稿） */
    private const val CHART_BAR_MAX_W = 40f
    private const val CHART_BAR_MIN_W = 6f
    private const val CHART_BAR_BASE_GAP = 5f

    /** 均值虚线的短划长度 / 间隙 / 线厚 */
    private const val CHART_DASH_W = 18f
    private const val CHART_DASH_GAP = 14f
    private const val CHART_DASH_H = 3f

    /** 柱状图 x 轴标签间隔（每 N 根标一个，最后一根一定标） */
    private const val CHART_LABEL_EVERY = 3

    /** 柱状图整体高度 */
    private const val COLUMN_CHART_H = CHART_TOP_PAD + CHART_BAR_AREA_H + CHART_AXIS_H

    /** 均值虚线的说明前缀（值是各柱的算术平均，如实展示） */
    private const val AVG_LABEL = "均值"

    /** 均值标注：与虚线的间距 / 标注行高 */
    private const val CHART_AVG_LABEL_GAP = 34f
    private const val CHART_AVG_LABEL_H = 30f

    /**
     * 第 16 轮：柱状图的「底轨 + 趋势折线」。
     *
     * 底轨 = 每列一条满高的极淡竖条（柱体叠在它上面），让"这一列几乎没消息"与
     * "漏画了一列"可区分，同时给柱体提供列位参照；
     * 趋势折线把各柱顶点连起来，回答"整体在涨还是在跌"这个柱高本身读不出太快的问题。
     * 两者都刻意做得比柱体轻：轨道用淡化后的 [COLOR_TRACK]，折线用淡化的主色，
     * 节点只点非零柱 —— 加的是信息，不是噪声。
     */
    private const val CHART_TREND_LINE_W = 3f
    private const val CHART_TREND_NODE_R = 4f

    /** 柱底轨的淡化强度（与 [COLOR_TRACK] 混白后使用，绝不直接用半透明色叠背景） */
    private const val CHART_RAIL_ALPHA = 0x8C

    /** 趋势折线主色的淡化强度（同上，先混白再画） */
    private const val CHART_TREND_ALPHA = 0xCC

    // ---- 标签云（词频）----

    /** 标签高度 / 标签内水平内边距 / 标签间距 / 行间距 */
    private const val CHIP_H = 64
    private const val CHIP_PAD_H = 26
    private const val CHIP_GAP = 16
    private const val CHIP_LINE_GAP = 18

    /** 顶部卡片 / 卡片的最大显示行数（超出按测量宽度省略，绝不溢出） */
    private const val HEADER_NAME_MAX_LINES = 3
    private const val HEADER_META_MAX_LINES = 2

    /** 画布最小高度 */
    private const val MIN_H = 900

    /**
     * 分页时"尾页"的最小内容高度。
     *
     * 贪心装满会在最后剩下一小截内容时收到一张又空又小的图（例如只有一行 KPI + 一大片页脚）。
     * 低于这个高度就往前退一个条目边界，让最后一张图至少是一整张卡片的量。
     * 只在"退完之后剩余内容仍装得进一页续页"时才退 —— 因此绝不会为了尾页好看而多分出一页。
     */
    private const val MIN_TAIL_CONTENT = 1200

    /** 画布高度上限（超过直接拒绝导出，避免 OOM） */
    private const val MAX_HEIGHT = 20000

    /**
     * 分页的页数硬上限。
     *
     * 第 22 轮把分页从「按容量精确推算页数」改成「按条目边界贪心装箱」（见 [paginate]）后，
     * 页数不再由容量公式直接给出，而是一页一页装出来的 —— 因此必须有一个显式的终点，
     * 否则一段病态输入（例如某张卡片本身高过一页、且断点又恰好缺失）会让循环失去收敛保证。
     * 24 页 ≈ 480000px 内容，远超任何真实聊天报告；触顶时按「装满即换页」收尾，不会死循环。
     */
    private const val MAX_PAGES = 24

    /** 单张 ARGB_8888 画布的像素内存预算（512MB 宿主堆下的安全上限） */
    private const val MEMORY_BUDGET_BYTES = 120 * 1024 * 1024

    // ---- 由上面的常量推导：卡片 / 内容区 ----
    private const val CARD_LEFT = CANVAS_PAD                                  // 72
    private const val CARD_RIGHT = W - CANVAS_PAD                             // 1368
    private const val CONTENT_LEFT = CARD_LEFT + CARD_PAD_H                   // 128
    private const val CONTENT_RIGHT = CARD_RIGHT - CARD_PAD_H                 // 1312
    private const val CONTENT_W = CONTENT_RIGHT - CONTENT_LEFT                // 1184

    // ---- 由上面的常量推导：条形行三列（40% / 18% / 余量 − 2×COL_GAP）----
    private const val LABEL_W = CONTENT_W * 40 / 100                          // 473
    private const val VALUE_W = CONTENT_W * 18 / 100                          // 213
    private const val BAR_W = CONTENT_W - LABEL_W - VALUE_W - COL_GAP * 2     // 450

    private const val LABEL_LEFT = CONTENT_LEFT                               // 128
    private const val LABEL_RIGHT = LABEL_LEFT + LABEL_W                      // 601
    private const val VALUE_LEFT = LABEL_RIGHT + COL_GAP                      // 625
    private const val VALUE_RIGHT = VALUE_LEFT + VALUE_W                      // 838
    private const val BAR_LEFT = VALUE_RIGHT + COL_GAP                        // 862
    private const val BAR_RIGHT = BAR_LEFT + BAR_W                            // 1312

    /** KPI 单元宽度（一行两格，两格 + 列间距恰好占满内容区） */
    private const val KPI_CELL_W = (CONTENT_W - KPI_COL_GAP) / 2               // 580

    // ---- 第 15 轮：活跃热力（7 × 24 格阵 + 小时刻度 + 图例）----
    /** 行 = 周一…周日，列 = 0…23 点 */
    private const val HEAT_ROWS = 7
    private const val HEAT_COLS = 24

    /** 左侧星期标签列宽 + 与格区的间距 */
    private const val HEAT_LABEL_W = 100
    private const val HEAT_LABEL_GAP = 16

    /** 格子的边长 / 格间距 / 圆角：边长由内容区倒推，保证 24 列在画布上永远排得下 */
    private const val HEAT_CELL_GAP = 4
    private const val HEAT_CELL_W =
        (CONTENT_W - HEAT_LABEL_W - HEAT_LABEL_GAP - (HEAT_COLS - 1) * HEAT_CELL_GAP) / HEAT_COLS
    private const val HEAT_CELL_H = 40
    private const val HEAT_CELL_RADIUS = 10f
    private const val HEAT_PEAK_STROKE = 3f

    /** 底部小时刻度行 / 图例行的留白与行高 */
    private const val HEAT_AXIS_GAP = 10
    private const val HEAT_AXIS_H = 40
    private const val HEAT_LEGEND_GAP = 12
    private const val HEAT_LEGEND_H = 46
    private const val HEAT_LEGEND_SWATCH = 30
    private const val HEAT_LEGEND_SWATCH_GAP = 8
    private const val HEAT_LEGEND_SWATCH_RADIUS = 8f
    private const val HEAT_LEGEND_TEXT_GAP = 12
    private const val HEAT_LEGEND_LESS = "少"
    private const val HEAT_LEGEND_MORE = "多"
    private const val HEAT_LEGEND_STEPS = 5

    /** 热力配色：卡片主色 + 固定透明度阶梯（先混白再画，与背景无关） */
    private const val HEAT_MIN_ALPHA = 0x2E
    private const val HEAT_MAX_ALPHA = 0xE6

    /** 整块热力图的高度（布局与绘制共用同一个定义，绝不允许两处各算一遍） */
    private const val HEATMAP_H = HEAT_ROWS * HEAT_CELL_H + (HEAT_ROWS - 1) * HEAT_CELL_GAP +
        HEAT_AXIS_GAP + HEAT_AXIS_H + HEAT_LEGEND_GAP + HEAT_LEGEND_H

    /** 小时刻度（0/6/12/18/23）：与格阵同一套列坐标 */
    private val HEAT_AXIS_HOURS = intArrayOf(0, 6, 12, 18, 23)

    // ---- 字号（px）：标题 74 / 章节 54 / 正文 40 / 行 38 / 刻度 30 ----
    private const val FS_BRAND = 38f
    private const val FS_TITLE = 74f
    private const val FS_META = 38f
    private const val FS_SMALL = 34f
    private const val FS_TICK = 30f
    private const val FS_AVATAR = 66f
    private const val FS_BADGE = 34f
    private const val FS_GROUP = 40f
    private const val FS_SECTION = 54f
    private const val FS_SECTION_NO = 40f
    private const val FS_BODY = 40f
    private const val FS_ROW = 38f
    private const val FS_KPI_LABEL = 34f
    private const val FS_KPI_VALUE = 62f
    private const val FS_KPI_UNIT = 34f

    // ---- 配色（集中常量：主色 / 强调色 / 成功 / 警示 / 危险 / 文本主次 / 分隔线）----
    private const val COLOR_BG_TOP = 0xFFEEF5FC.toInt()
    private const val COLOR_BG_MID = 0xFFF7FAFE.toInt()
    private const val COLOR_BG_BOTTOM = 0xFFFFFFFF.toInt()
    private const val COLOR_CARD = 0xFFFFFFFF.toInt()
    private const val COLOR_TITLE = 0xFF12233A.toInt()
    private const val COLOR_BODY = 0xFF2B3A4B.toInt()
    private const val COLOR_META = 0xFF7C8CA0.toInt()
    private const val COLOR_ACCENT = 0xFF2E7DD1.toInt()
    private const val COLOR_ACCENT2 = 0xFF12B3A8.toInt()

    /**
     * 第 17 轮新增的两个分节色（紫 / 橙）。
     *
     * 老版本整本统计报告只有一种蓝："几十个 KPI 网格连成一整片"，分节只能靠标题认。
     * 加这两个色之后，分节按 [sectionAccentOf] 循环取色（与弹窗同一套落点），
     * 导出图与弹窗一眼对得上。亮度与既有两色对齐（白底上都清晰），不参与任何几何计算。
     */
    private const val COLOR_ACCENT3 = 0xFF7A5AF8.toInt()
    private const val COLOR_ACCENT4 = 0xFFE0603A.toInt()
    private const val COLOR_SUCCESS = 0xFF1F9D55.toInt()
    private const val COLOR_WARN = 0xFFD89A16.toInt()
    private const val COLOR_DANGER = 0xFFD64545.toInt()
    private const val COLOR_RULE = 0xFFE1E9F2.toInt()
    private const val COLOR_TRACK = 0xFFE7EEF6.toInt()
    private const val COLOR_SHADOW = 0x14000000
    private const val COLOR_RANK_GOLD = 0xFFD89A16.toInt()
    private const val COLOR_RANK_SILVER = 0xFF7F8C9B.toInt()
    private const val COLOR_RANK_BRONZE = 0xFFB9754A.toInt()

    /** 固定品牌文案（顶部品牌行 / 页脚水印）与空报告文案 */
    private const val BRAND_TEXT = "WeKit · 聊天记录分析"
    private const val FOOTER_PAGE_TEXT = "第 1 / 1 页"
    private const val GROUP_STATS = "本地统计报告"
    private const val GROUP_AI = "AI 洞察报告"
    private const val EMPTY_TITLE = "无可统计内容"
    private const val EMPTY_HINT = "该时段没有可统计的文本消息。"
    private const val EMPTY_HINT_SUB = "请换一个时间范围，或确认该会话在此范围内确实有文本消息。"

    /**
     * 页底收口文案（分页导出时每页内容区结尾处）。
     *
     * [CAP_SPLIT_TEXT] 用于"卡片被拦腰截断"（断点落在卡片内部），[CAP_CONTINUE_TEXT] 用于
     * "整张卡片收尾"（断点正好是条目边界）—— 后者只说"接下页"，不制造多余的紧张感。
     */
    private const val CAP_SPLIT_TEXT = "本节未完 · 见下页"
    private const val CAP_CONTINUE_TEXT = "接下页"

    /** 孤字控制：段落末行短于等于这个字符数时，从上一行挪一个字下来 */
    private const val ORPHAN_MAX_CHARS = 2

    /** 环形图配色（按扇区顺序取用；都是中明度色，白底上够清晰） */
    private val DONUT_PALETTE = intArrayOf(
        0xFF2E7DD1.toInt(),
        0xFF12B3A8.toInt(),
        0xFF7A5AF8.toInt(),
        0xFFD89A16.toInt(),
        0xFF1F9D55.toInt(),
        0xFFE0625E.toInt(),
        0xFF3C8CFF.toInt(),
        0xFF8A6E4B.toInt(),
    )

    /**
     * KPI 语义染色关键词：同样的数字在不同语境下含义不同，用颜色把"亮眼 / 需注意 / 风险"提前说清。
     * 只匹配强信号词，匹配不到就用卡片主色 —— 宁可不染色，也不乱染色。
     */
    private val KEY_SUCCESS = listOf("最多", "峰值", "最高", "增长", "上升")
    private val KEY_WARN = listOf("最少", "最低", "深夜", "凌晨", "废话")
    private val KEY_DANGER = listOf("风险", "异常", "冲突", "警告", "负面")

    init {
        // ---- 画布内存预算：单张 ARGB_8888 位图必须留在宿主堆能承受的范围内 ----
        require(W * MAX_HEIGHT * 4 <= MEMORY_BUDGET_BYTES) {
            "PNG 画布内存预算超限：${W}×${MAX_HEIGHT}×4B 超过 120MB"
        }
        require(MAX_HEIGHT >= MIN_H) { "PNG 画布高度上限小于最小高度" }

        // ---- 列几何：三列两两不相交，且恰好占满卡片内容区 ----
        require(LABEL_RIGHT <= VALUE_LEFT) { "PNG 排版列重叠：label/value" }
        require(VALUE_RIGHT <= BAR_LEFT) { "PNG 排版列重叠：value/bar" }
        require(LABEL_LEFT == CONTENT_LEFT && BAR_RIGHT == CONTENT_RIGHT) {
            "PNG 列未占满卡片内容区"
        }
        require(CONTENT_LEFT > CARD_LEFT && CONTENT_RIGHT < CARD_RIGHT) {
            "PNG 卡片内边距非法"
        }
        require(BAR_W > 0 && LABEL_W > 0 && VALUE_W > 0 && KPI_CELL_W > 0) { "PNG 列宽非法" }
        require(2 * KPI_CELL_W + KPI_COL_GAP <= CONTENT_W) { "PNG KPI 网格超宽" }
        require(CANVAS_PAD >= 48) { "PNG 画布左右边距不得小于 48px" }

        // ---- 行高必须装得下对应字号（CJK 的 descent 比拉丁文更吃高度）----
        require(
            TEXT_LINE_H > FS_BODY && ROW_H > FS_ROW && HEADER_TITLE_LINE_H > FS_TITLE &&
                HEADER_META_LINE_H > FS_META && BRAND_LINE_H > FS_BRAND &&
                KPI_LABEL_ROW_H > FS_KPI_LABEL && KPI_VALUE_ROW_H > FS_KPI_VALUE &&
                KPI_UNIT_ROW_H > FS_KPI_UNIT && SECTION_BADGE_BOX > FS_SECTION_NO &&
                RANK_BOX > FS_RANK && BADGE_H > FS_BADGE && GROUP_PILL_H > FS_GROUP &&
                FOOTER_TEXT_H > FS_SMALL && AVATAR_SIZE > FS_AVATAR && CHIP_H > FS_ROW &&
                DONUT_LEGEND_ROW_H > FS_ROW,
        ) { "PNG 行高与字号不匹配，文字会溢出" }

        // ---- 章节头部 / 徽章 / 分组 pill ----
        require(SECTION_HEADER_H >= SECTION_BADGE_BOX + 40) { "PNG 章节头部高度装不下序号徽章" }
        require(SECTION_UNDERLINE_W <= CONTENT_W) { "PNG 章节下划线超过内容区宽度" }
        require(SECTION_BADGE_BOX > SECTION_UNDERLINE_H) { "PNG 章节下划线比徽章还厚" }
        require(BADGE_H <= BRAND_LINE_H) { "PNG 范围徽章比品牌行还高" }
        require(GROUP_PILL_DOT + GROUP_PILL_DOT_GAP < GROUP_PILL_H) { "PNG 分组 pill 圆点装不下" }
        require(GROUP_PILL_PAD_L + GROUP_PILL_DOT + GROUP_PILL_DOT_GAP + GROUP_PILL_PAD_R <=
            GROUP_PILL_MAX_W) { "PNG 分组 pill 最小宽度超过最大宽度" }

        // ---- KPI 单元：内部排版（标签 + 数值 [+ 单位] [+ 份额条]）必须装得进单元高度 ----
        require(KPI_LABEL_ROW_H + KPI_LABEL_VALUE_GAP + KPI_VALUE_ROW_H + KPI_PAD_V <= KPI_CELL_H) {
            "PNG KPI 单元内部排版超出单元高度"
        }
        require(KPI_CELL_H_TALL > KPI_CELL_H) { "PNG KPI 带单位行的单元高度非法" }
        require(KPI_SHARE_BAR_H in 8..KPI_VALUE_ROW_H) { "PNG KPI 份额条高度非法" }

        // ---- 排行行：圆角序号徽章必须装得进行高，且标签列扣掉徽章后仍有余量 ----
        require(ROW_H >= RANK_BOX + 8) { "PNG 行长装不下排行序号徽章" }
        require(LABEL_W - CELL_INSET * 2 - RANK_BOX - RANK_GAP >= 120) { "PNG 排行标签列可用宽度过窄" }
        require(CARD_MIN_H >= CARD_PAD_V * 2 + ROW_H) { "PNG 卡片最小高度小于内边距 + 一行" }

        // ---- 环形图：环 + 间距 + 图例必须都装得进内容区 ----
        require(DONUT_SIZE > DONUT_RING_W * 2f + 80f) { "PNG 环形图内孔过小，放不下合计数字" }
        require(CONTENT_W - DONUT_SIZE - DONUT_LEGEND_GAP >= 400) { "PNG 环形图图例可用宽度过窄" }
        require(DONUT_LEGEND_SWATCH + DONUT_LEGEND_SWATCH_GAP < DONUT_LEGEND_ROW_H) {
            "PNG 环形图图例色块装不下"
        }

        // ---- 柱状图：刻度槽 + 柱区必须占满内容区且留得下柱宽 ----
        require(CHART_GUTTER_W >= 80 && CHART_GUTTER_W < CONTENT_W / 4) { "PNG 柱状图刻度槽宽度非法" }
        require(CONTENT_W - CHART_GUTTER_W >= 600) { "PNG 柱状图柱区过窄" }
        require(CHART_BAR_AREA_H >= 200) { "PNG 柱状图柱区高度过矮" }
        require(CHART_TOP_PAD >= CHART_DASH_H * 4f) { "PNG 柱状图顶部留白放不下峰值标注" }
        require(CHART_BAR_MAX_W > CHART_BAR_MIN_W) { "PNG 柱状图柱宽上下限非法" }
        // 第 16 轮：底轨与趋势折线都必须比柱体"轻"，线宽 / 节点半径一旦接近柱宽就会盖住柱体
        require(CHART_TREND_LINE_W < CHART_BAR_MAX_W / 4f) { "PNG 柱状图趋势折线线宽过大" }
        require(CHART_TREND_NODE_R < CHART_BAR_MAX_W / 4f) { "PNG 柱状图趋势节点半径过大" }
        require(CHART_RAIL_ALPHA in 0..255 && CHART_TREND_ALPHA in 0..255) { "PNG 柱状图附加图层透明度非法" }
        require(COLUMN_CHART_H == CHART_TOP_PAD + CHART_BAR_AREA_H + CHART_AXIS_H) {
            "PNG 柱状图高度定义不一致"
        }

        // ---- 标签云：单个标签的左右内边距不能超过内容区一半 ----
        require(CHIP_PAD_H * 2 < CONTENT_W / 2) { "PNG 标签云内边距过大" }

        // ---- 活跃热力：标签列 + 24 格必须装进内容区，高度定义必须与布局/绘制一致 ----
        require(HEAT_LABEL_W + HEAT_LABEL_GAP + HEAT_COLS * (HEAT_CELL_W + HEAT_CELL_GAP) <= CONTENT_W) {
            "PNG 活跃热力格阵超出内容区"
        }
        require(HEAT_CELL_W >= 24 && HEAT_CELL_H >= 24) { "PNG 活跃热力格子过小" }
        require(HEAT_MIN_ALPHA in 1..HEAT_MAX_ALPHA && HEAT_MAX_ALPHA <= 255) {
            "PNG 活跃热力透明度阶梯非法"
        }
        require(HEAT_LEGEND_STEPS >= 2 && HEAT_LEGEND_SWATCH + HEAT_LEGEND_TEXT_GAP * 2 < CONTENT_W / 2) {
            "PNG 活跃热力图例过宽"
        }
        require(
            HEATMAP_H == HEAT_ROWS * HEAT_CELL_H + (HEAT_ROWS - 1) * HEAT_CELL_GAP +
                HEAT_AXIS_GAP + HEAT_AXIS_H + HEAT_LEGEND_GAP + HEAT_LEGEND_H,
        ) { "PNG 活跃热力高度定义不一致" }

        // ---- 页脚：上留白 + 元信息行 + 间距 + 文字行 + 间距 + 品牌条 必须装得进页脚高度 ----
        require((FOOTER_TOP_GAP + FOOTER_META_H + FOOTER_META_GAP + FOOTER_TEXT_H + FOOTER_STRIP_GAP).toFloat() +
            FOOTER_STRIP_H <= FOOTER_H.toFloat()) { "PNG 页脚内部排版超出页脚高度" }
        // 元信息行的字号必须小于行高，否则文字会被自己的行高裁掉
        require(FOOTER_META_H > FS_SMALL) { "PNG 页脚元信息行高小于字号" }

        // ---- 续页页头：留白带要装得下横幅文字行，且不能吃掉续页的可用高度 ----
        require(PAGE_HEAD_BAND_H > PAGE_HEAD_TEXT_H + 40) { "PNG 续页页头装不下横幅文字行" }
        require(PAGE_HEAD_BAND_H < (MAX_HEIGHT - (CARD_GAP + FOOTER_H + BOTTOM_PAD)) / 4) {
            "PNG 续页页头占用单页容量比例过大"
        }
        require(PAGE_HEAD_NAME_W_RATIO in 0.2f..0.8f) { "PNG 续页页头会话名宽度比例非法" }
    }

    // ==================================================================
    // 二、数据模型（解析 → 布局 → 绘制三段式）
    // ==================================================================

    /** 报告文本切分出的最小单元 */
    private sealed class Block {
        data class Section(val title: String) : Block()
        data class BarLine(
            val label: String,
            val value: String,
            val ratio: Float,
            val rank: Int,
        ) : Block()
        data class KeyValue(val key: String, val value: String) : Block()
        data class KpiGrid(val items: List<KeyValue>) : Block()
        data class TextLine(val text: String) : Block()

        /** 分布类章节：环形图（环 + 图例，图例带占比） */
        data class Donut(val slices: List<Slice>) : Block()

        /** 时间分布章节：柱状图（网格 + 均值虚线 + 峰值标注 + x 轴刻度） */
        data class ColumnChart(val bars: List<Column>) : Block()

        /** 词频类章节：自适应换行的标签云（原来是一整段挤在一起的文字） */
        data class ChipCloud(val items: List<String>) : Block()

        /** 活跃热力矩阵的一行：星期标签 + 各小时格的消息数 */
        data class HeatRow(val label: String, val values: List<Int>)

        /**
         * 活跃热力（第 15 轮新增的形状）：行 = 星期、列 = 小时、值 = 该格消息数。
         *
         * 为什么单独画一种图：7×24 = 168 个格子用条形行或柱状图都表达不了
         * （168 根柱子在画布上既排不开也读不出），热力图才是这个维度的标准读法。
         */
        data class Heatmap(val rows: List<HeatRow>) : Block()

        object Gap : Block()
    }

    /** 环形图扇区：count 只用来算比例，展示时一律用原值文本（不改写报告给出的数字） */
    private data class Slice(val label: String, val value: String, val count: Long)

    /** 柱状图柱：label 即 x 轴标签 */
    private data class Column(val label: String, val value: String, val count: Long)

    /** 已定位的一行：top / height 都是相对卡片正文区顶部的偏移 */
    private class Row(
        val unit: Block,
        val top: Int,
        val height: Int,
        val lines: List<String> = emptyList(),
        /** 标签云换行结果（布局阶段算好，绘制阶段原样使用，保证两遍完全一致） */
        val chipLines: List<List<String>> = emptyList(),
    )

    /** 绘制单元：分组 pill 或章节卡片 */
    private sealed class Item {
        data class Pill(val label: String, val accent: Int, val width: Int) : Item()
        data class Card(
            val title: String?,
            val index: Int,
            val accent: Int,
            val rows: List<Row>,
            val height: Int,
        ) : Item()
    }

    /** 顶部信息卡的布局结果 */
    private class HeaderSpec(
        val avatarChar: String,
        val nameLines: List<String>,
        val subLines: List<String>,
        val genLines: List<String>,
        val nameTop: Int,
        val subTop: Int,
        val genTop: Int,
        val avatarTop: Int,
        val textLeft: Int,
        val textRight: Int,
        val badgeText: String,
        val badgeW: Int,
        val height: Int,
    )

    // ==================================================================
    // 三、对外接口（签名保持不变，被 ChatRecordAnalysis.kt 调用）
    // ==================================================================

    /**
     * 导出报告 PNG。
     *
     * **无论报告多长都能导出**：内容高度超过单页上限（[MAX_HEIGHT]）时按「卡片边界 / 行首」
     * 自动分页，每页独立成一张图（文件名带「第 N 页」），一次返回全部路径。
     * 分页只在真正超长时发生 —— 普通长度的报告仍然是单张图，观感不变。
     *
     * @param stats 本地统计报告文本
     * @param ai AI 报告文本
     * @param sessionName 会话显示名
     * @param sessionWxid 会话 wxid
     * @param period 时段标签
     * @return 保存路径列表（按页序）；单页时只有一个元素
     */
    @Throws(Exception::class)
    fun export(
        stats: String,
        ai: String,
        sessionName: String,
        sessionWxid: String,
        period: String,
    ): List<String> {
        val dir = exportDir()
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val base = "聊天记录分析_${fmt.format(Date())}"
        return drawToFiles(stats, ai, sessionName, sessionWxid, period, dir, base)
    }

    fun exportDir(): String {
        val base = File("/sdcard/Download/WeKit")
        if (!base.exists()) base.mkdirs()
        if (base.exists() && base.isDirectory && base.canWrite()) return base.absolutePath
        val fallback = File("/sdcard/Download")
        if (fallback.exists() && fallback.isDirectory && fallback.canWrite()) return fallback.absolutePath
        throw RuntimeException("没有可写的本地导出目录")
    }

    // ==================================================================
    // 四、文本解析
    // ==================================================================

    private val RANK_LINE = Regex("^(\\d+[.．、]?\\s*.*?)[:：]\\s*(\\d+)\\s*条?$")
    private val RANK_PREFIX = Regex("^(\\d+)[.．、]")

    /** 数值 + 单位拆分："12,345 条" → ("12,345", "条")；纯文本则单位为空串 */
    private val VALUE_UNIT = Regex("^([0-9][0-9 .,%:+\\-]*)(.*)$")

    /** 词频标签："哈哈×123"（引擎用 '×' 连接词与次数） */
    private val CHIP_TOKEN = Regex("^[^×\\s]+×\\d+$")

    /**
     * 没有 █ 的分布行："链接 233"。
     * 引擎按 value/max×16 画条形，占比太小的项会算出 0 个方块（整行没有 █），
     * 但它们在语义上仍然是分布项 —— 不认出来，环形图/柱状图就会漏掉这些小项。
     */
    private val PLAIN_COUNT_LINE = Regex("^(\\S+)\\s+(\\d+)$")

    /** 空白切分（词频行用两个空格分段） */
    private val WHITESPACE = Regex("\\s+")

    /**
     * 热力数据行：`周一 → 0 0 1 2 …`（星期名 + 箭头 + 正好 24 个整数）。
     *
     * 判据刻意收得很紧：判不出来就退回普通正文行，绝不会把别的数字行误当成矩阵 —— 宁可不画，不画错。
     */
    private val HEAT_LINE = Regex("^(周[一二三四五六日])\\s+→\\s+((?:\\d{1,9}\\s+){23}\\d{1,9})$")

    /** CJK 统一表意文字区间（识别中文标签用） */
    private const val CJK_FIRST = 0x4E00
    private const val CJK_LAST = 0x9FFF

    /** 报告里的全角空格（"≤5字：60%　≤20字：30%" 用它在同一行塞两组指标） */
    private const val IDEO_SPACE = '　'

    /** KPI 行的判定阈值（与 App 内报告视图的键值行判定保持一致，避免两处观感割裂） */
    private const val KV_MAX_LINE_LEN = 40
    private const val KV_MAX_KEY_LEN = 20

    /**
     * 值这一档比 App 内列表视图宽松（列表窄、PNG 单元格宽 580px）：
     * "消息总数：15842 条（纯文本 12033 条）" 这种头条数字在 PNG 里应该走 KPI 大数字，
     * 值太长也只是自动多占一行单位行，不会溢出 —— 见 [drawKpiCell]。
     */
    private const val KV_MAX_VALUE_LEN = 24

    private fun parseBlocks(text: String): List<Block> {
        val out = mutableListOf<Block>()
        if (text.isBlank()) return out
        for (line in text.split("\n")) {
            val t = line.trim()
            when {
                t.isEmpty() -> out.add(Block.Gap)
                t.startsWith("【") && t.endsWith("】") ->
                    out.add(Block.Section(t.removeSurrounding("【", "】")))
                t.contains("█") -> out.add(parseBarLine(t))
                else -> {
                    val plain = parsePlainCount(t)
                    if (plain != null) out.add(plain) else out.addAll(parseTextOrKeyValue(t))
                }
            }
        }
        return collapseHeat(out)
    }

    /** 解析一行热力数据：格式不对、或数字个数不是 [HEAT_COLS] 个，一律返回 null */
    private fun parseHeatRow(line: String): Block.HeatRow? {
        val m = HEAT_LINE.find(line.trim()) ?: return null
        val nums = ArrayList<Int>(HEAT_COLS)
        for (tok in m.groupValues[2].trim().split(" ")) {
            nums.add(tok.toIntOrNull() ?: return null)
        }
        if (nums.size != HEAT_COLS) return null
        return Block.HeatRow(m.groupValues[1], nums)
    }

    /**
     * 把连续的 [HEAT_ROWS] 行热力数据折叠成一个 [Block.Heatmap]。
     *
     * 为什么不塞进逐行循环：循环是「一行一个判定」的无状态结构，而热力块是跨行才成立的条件
     * （必须凑满 7 行）。收尾统一折叠的做法一行都不用改既有的解析分支，老行的判定结果逐字不变；
     * 凑不满 7 行就整段退回普通正文行（沿用既有降级路径，不需要新的空态）。
     *
     * 另外还做了一次廉价的短路：报告里没有热力行时（第 15 轮之前的所有报告）直接原样返回，
     * 不做第二次遍历，也不产生任何新对象。
     */
    private fun collapseHeat(blocks: List<Block>): List<Block> {
        var found = false
        for (b in blocks) {
            if (b is Block.TextLine && HEAT_LINE.find(b.text.trim()) != null) {
                found = true
                break
            }
        }
        if (!found) return blocks
        val out = ArrayList<Block>(blocks.size)
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            if (b is Block.TextLine) {
                val first = parseHeatRow(b.text)
                if (first != null) {
                    val rows = ArrayList<Block.HeatRow>(HEAT_ROWS)
                    rows.add(first)
                    var j = i + 1
                    while (j < blocks.size && rows.size < HEAT_ROWS) {
                        val next = blocks[j] as? Block.TextLine ?: break
                        rows.add(parseHeatRow(next.text) ?: break)
                        j++
                    }
                    if (rows.size == HEAT_ROWS) {
                        out.add(Block.Heatmap(rows))
                        i = j
                        continue
                    }
                }
            }
            out.add(b)
            i++
        }
        return out
    }

    /**
     * "链接 233" 这种没有条形图的分布行：标签必须是中文词、不能带冒号。
     * 这两条限制是为了不把正文里的 "2026 08"、"结论： 12" 之类误判成分布项。
     */
    private fun parsePlainCount(t: String): Block.BarLine? {
        val m = PLAIN_COUNT_LINE.find(t) ?: return null
        val label = m.groupValues[1]
        val value = m.groupValues[2]
        if (label.contains("：") || label.contains(":")) return null
        if (!label.any { it.code in CJK_FIRST..CJK_LAST }) return null
        return Block.BarLine(label, value, 0f, 0)
    }

    /**
     * 普通行：可能是一行里塞了多组指标的 "键：值" 行，也可能是普通正文。
     * 先用全角空格拆一次（每组都含 "：" 才认），拆不出来再按单组判定 ——
     * 因为 "≤5字：60%　≤20字：30%" 这种行如果整行当一个值，会被省略号吃掉一半信息。
     */
    private fun parseTextOrKeyValue(t: String): List<Block> {
        val segments = t.split(IDEO_SPACE)
        if (segments.size > 1 && segments.all { it.contains("：") }) {
            val pairs = segments.map { asKeyValue(it) }
            if (pairs.none { it == null }) return pairs.filterNotNull()
        }
        val single = asKeyValue(t)
        return if (single != null) listOf(single) else listOf(Block.TextLine(t))
    }

    /** 尝试把一行解析成 "键：值"；不满足阈值就返回 null（交给正文排版） */
    private fun asKeyValue(t: String): Block.KeyValue? {
        if (t.length > KV_MAX_LINE_LEN || !t.contains("：")) return null
        val idx = t.indexOf("：")
        val key = t.substring(0, idx).trim()
        val value = t.substring(idx + 1).trim()
        if (key.isEmpty() || key.length > KV_MAX_KEY_LEN) return null
        if (value.isEmpty() || value.length > KV_MAX_VALUE_LEN) return null
        return Block.KeyValue(key, value)
    }

    private fun parseBarLine(rawLine: String): Block.BarLine {
        val barLen = rawLine.count { it == '█' }
        val clean = rawLine.replace("█", "").trim()
        var label = clean
        var value = ""
        val rankM = RANK_LINE.find(clean)
        if (rankM != null) {
            // 排行行："1. 张三：45 条" → label=1. 张三, value=45条
            label = rankM.groupValues[1].trim()
            value = rankM.groupValues[2] + "条"
        } else {
            // 载体偏好 / 活跃频次："文字 5468" → label=文字, value=5468
            val lastSpace = clean.lastIndexOf(' ')
            if (lastSpace > 0 && clean.substring(lastSpace + 1).trim().all { it.isDigit() }) {
                label = clean.substring(0, lastSpace).trim()
                value = clean.substring(lastSpace + 1).trim()
            }
        }
        val rank = RANK_PREFIX.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Block.BarLine(label, value, (barLen / 16f).coerceIn(0f, 1f), rank)
    }

    /**
     * 把连续的 KeyValue 行聚合成 KPI 网格（≥2 项才聚合，单项按普通键值行绘制）。
     * 值里没有数字的 "键：值" 不是指标而是结论（"鉴定：正常人类浓度"），
     * 拿 62px 大字号去渲染只会变成一行怪字，所以这类一律退回正文排版。
     */
    private fun groupKpis(blocks: List<Block>): List<Block> {
        val out = ArrayList<Block>(blocks.size)
        var run = ArrayList<Block.KeyValue>()
        fun flush() {
            if (run.size >= 2) out.add(Block.KpiGrid(run.toList())) else out.addAll(run)
            run = ArrayList()
        }
        for (b in blocks) {
            if (b is Block.KeyValue && isMeasurable(b)) {
                run.add(b)
            } else {
                flush()
                out.add(if (b is Block.KeyValue) Block.TextLine("${b.key}：${b.value}") else b)
            }
        }
        flush()
        return out
    }

    /** 指标的值必须含数字，否则它是一句结论 */
    private fun isMeasurable(kv: Block.KeyValue): Boolean = kv.value.any { it.isDigit() }

    /** 去掉卡片正文首尾的空行，并把连续空行折叠成一行（节奏统一，不留黑洞） */
    private fun trimGaps(units: List<Block>): List<Block> {
        val out = ArrayList<Block>(units.size)
        for (u in units) {
            if (u is Block.Gap) {
                if (out.isEmpty() || out.last() is Block.Gap) continue
                out.add(u)
            } else {
                out.add(u)
            }
        }
        while (out.isNotEmpty() && out.last() is Block.Gap) out.removeAt(out.size - 1)
        return out
    }

    /**
     * 把一段正文里"连续的无序号条形行"识别成图表（只按数据形状判定，不看章节标题）：
     *   · 标签含数字（"凌晨0-5点"）→ 时间分布 → 柱状图
     *   · 标签不含数字（"文字"/"图片"）→ 类别分布 → 环形图
     * 任何一条不满足（有序号 / 数量超限 / 数值解析不出来）就原样返回，退回条形行 —— 宁可不画，不画错。
     */
    private fun shapeBody(body: List<Block>): List<Block> {
        val out = ArrayList<Block>(body.size)
        var i = 0
        while (i < body.size) {
            val b = body[i]
            if (b is Block.BarLine && b.rank == 0) {
                var j = i
                while (j < body.size && body[j] is Block.BarLine && (body[j] as Block.BarLine).rank == 0) j++
                val run = body.subList(i, j).map { it as Block.BarLine }
                val chart = asColumnChart(run) ?: asDonut(run)
                if (chart != null) {
                    out.add(chart)
                } else {
                    out.addAll(withRunRatios(run))
                }
                i = j
            } else {
                out.add(shapedText(b))
                i++
            }
        }
        return out
    }

    /**
     * 同一段分布内补齐缺失的比例：引擎对小数值不画 █（比例算出来是 0），
     * 直接画就会"有数字没条形"。按该段最大值补算后，条形才有参照意义。
     * 已经有方块的行原样保留 —— 引擎给的比例本来就是 value/max。
     */
    private fun withRunRatios(run: List<Block.BarLine>): List<Block.BarLine> {
        if (run.size < 2) return run
        val counts = run.map { parseCount(it.value) }
        if (counts.any { it < 0L }) return run
        val max = counts.maxOrNull() ?: return run
        if (max <= 0L) return run
        return run.mapIndexed { i, b ->
            if (b.ratio > 0f) b
            else b.copy(ratio = (counts[i].toFloat() / max.toFloat()).coerceIn(0f, 1f))
        }
    }

    /** 单行内容里的标签云替换：词频行是"哈哈×123  你好×99 …"，挤成一段正文很难读 */
    private fun shapedText(b: Block): Block {
        if (b !is Block.TextLine) return b
        val tokens = b.text.split(WHITESPACE).filter { it.isNotEmpty() }
        val chips = tokens.filter { CHIP_TOKEN.matches(it) }
        if (chips.size < CHIP_MIN_TOKENS || chips.size != tokens.size) return b
        return Block.ChipCloud(chips)
    }

    /** 文字分布 → 环形图（2~8 项、标签不含数字、数值全为正） */
    private fun asDonut(run: List<Block.BarLine>): Block.Donut? {
        if (run.size !in 2..DONUT_MAX_SLICES) return null
        if (run.any { it.label.any { c -> c.isDigit() } }) return null
        val counts = run.map { parseCount(it.value) }
        if (counts.any { it <= 0L }) return null
        return Block.Donut(run.mapIndexed { i, b -> Slice(b.label, b.value, counts[i]) })
    }

    /** 时间分布 → 柱状图（2~24 项、标签含数字、数值非负且不全为 0） */
    private fun asColumnChart(run: List<Block.BarLine>): Block.ColumnChart? {
        if (run.size !in 2..CHART_MAX_BARS) return null
        if (run.none { it.label.any { c -> c.isDigit() } }) return null
        val counts = run.map { parseCount(it.value) }
        if (counts.any { it < 0L } || counts.all { it == 0L }) return null
        return Block.ColumnChart(run.mapIndexed { i, b -> Column(b.label, b.value, counts[i]) })
    }

    /** 只取数字：报告里的值可能是 "5,468"/"5468 条"，解析不出来返回 -1 作为哨兵 */
    private fun parseCount(value: String): Long {
        val digits = value.filter { it.isDigit() }
        if (digits.isEmpty()) return -1L
        return digits.toLongOrNull() ?: -1L
    }

    /** 数值是百分比就返回 0..1 的份额（用于 KPI 单元里的份额进度条），否则 null */
    private fun shareFraction(value: String): Float? {
        val t = value.trim()
        if (t.length < 2 || !t.endsWith("%")) return null
        val n = t.dropLast(1).trim().toFloatOrNull() ?: return null
        return (n / 100f).coerceIn(0f, 1f)
    }

    /** KPI 语义色：匹配不到就用卡片主色 */
    private fun semanticColor(key: String): Int? = when {
        KEY_DANGER.any { key.contains(it) } -> COLOR_DANGER
        KEY_WARN.any { key.contains(it) } -> COLOR_WARN
        KEY_SUCCESS.any { key.contains(it) } -> COLOR_SUCCESS
        else -> null
    }

    // ==================================================================
    // 五、布局（纯几何，先量后排）
    // ==================================================================

    private fun layoutHeader(
        sessionName: String,
        sessionWxid: String,
        period: String,
        generated: String,
    ): HeaderSpec {
        val titleP = paint(FS_TITLE, COLOR_TITLE, bold = true)
        val metaP = paint(FS_META, COLOR_META)
        val smallP = paint(FS_SMALL, COLOR_META)
        val badgeP = paint(FS_BADGE, Color.WHITE, bold = true)

        // 徽章只显示范围标签（period = "范围 · 会话名"，会话名已在标题里，不重复）
        val badgeText = period.substringBefore(" · ").trim().ifEmpty { period.trim() }
        val badgeW = if (badgeText.isEmpty()) {
            0
        } else {
            // 宽度向上取整：.toInt() 会把测量值截掉不到 1px，恰好等于内容宽时
            // 内边距就少了那 1px，短标签（"本月"）会被判成放不下而只剩一个省略号
            (ceilToInt(badgeP.measureText(badgeText)) + BADGE_PAD_H * 2).coerceAtMost(BADGE_MAX_W)
        }

        val textLeft = CONTENT_LEFT + AVATAR_SIZE + AVATAR_TEXT_GAP
        val colW = (CONTENT_RIGHT - textLeft).toFloat().coerceAtLeast(160f)
        val nameLines = wrapLinesLimited(
            sessionName.trim().ifEmpty { "聊天记录分析" }, colW, titleP, HEADER_NAME_MAX_LINES,
        )
        val subLines = wrapLinesLimited(
            sessionWxid.trim().ifEmpty { "（未知会话）" }, colW, metaP, HEADER_META_MAX_LINES,
        )
        val genLines = wrapLinesLimited(generated, colW, smallP, HEADER_META_MAX_LINES)

        val nameTop = BRAND_LINE_H + HEADER_BRAND_TITLE_GAP
        val nameH = nameLines.size.coerceAtLeast(1) * HEADER_TITLE_LINE_H
        val subTop = nameTop + nameH + HEADER_TITLE_SUB_GAP
        val subH = subLines.size.coerceAtLeast(1) * HEADER_META_LINE_H
        val genTop = subTop + subH + HEADER_SUB_GEN_GAP
        val genH = genLines.size.coerceAtLeast(1) * HEADER_META_LINE_H

        val avatarTop = nameTop
        val contentH = maxOf(genTop + genH, avatarTop + AVATAR_SIZE)

        return HeaderSpec(
            avatarChar = firstGlyph(sessionName),
            nameLines = nameLines,
            subLines = subLines,
            genLines = genLines,
            nameTop = nameTop,
            subTop = subTop,
            genTop = genTop,
            avatarTop = avatarTop,
            textLeft = textLeft,
            textRight = CONTENT_RIGHT,
            badgeText = badgeText,
            badgeW = badgeW,
            height = CARD_PAD_V * 2 + contentH,
        )
    }

    private fun layoutRows(units: List<Block>, bodyP: Paint): List<Row> {
        val rows = ArrayList<Row>(units.size)
        var y = 0
        for (u in units) {
            when (u) {
                is Block.Gap -> {
                    rows.add(Row(u, y, GAP_H))
                    y += GAP_H
                }
                // 章节标题由卡片头部承担，正文里不会再出现 Section
                is Block.Section -> Unit
                is Block.KpiGrid -> {
                    // 高度必须与 drawKpiGrid 的分行方式完全一致（含"单位另起一行""份额条"两档）
                    val h = kpiGridHeight(u.items)
                    rows.add(Row(u, y, h))
                    y += h
                }
                is Block.KeyValue -> {
                    rows.add(Row(u, y, ROW_H))
                    y += ROW_H
                }
                is Block.BarLine -> {
                    rows.add(Row(u, y, ROW_H))
                    y += ROW_H
                }
                is Block.Donut -> {
                    val h = donutHeight(u)
                    rows.add(Row(u, y, h))
                    y += h
                }
                is Block.ColumnChart -> {
                    rows.add(Row(u, y, COLUMN_CHART_H))
                    y += COLUMN_CHART_H
                }
                is Block.ChipCloud -> {
                    // 换行结果在这里算一次并随行携带，绘制阶段直接复用（两遍必须完全一致）
                    val chipRows = chipLines(u.items)
                    val h = chipCloudHeight(chipRows.size)
                    rows.add(Row(u, y, h, emptyList(), chipRows))
                    y += h
                }
                is Block.Heatmap -> {
                    // 热力图的高度是定值（7×24 格 + 刻度行 + 图例行），不随内容变化
                    rows.add(Row(u, y, HEATMAP_H))
                    y += HEATMAP_H
                }
                is Block.TextLine -> {
                    val lines = wrapLines(u.text, CONTENT_W.toFloat(), bodyP)
                    val count = lines.size.coerceAtLeast(1)
                    val h = count * TEXT_LINE_H + TEXT_LINE_GAP
                    rows.add(Row(u, y, h, lines))
                    y += h
                }
            }
        }
        return rows
    }

    /** 环形图高度：环与图例谁高听谁的（环垂直居中在图例旁边） */
    private fun donutHeight(d: Block.Donut): Int =
        maxOf(DONUT_SIZE, d.slices.size * DONUT_LEGEND_ROW_H)

    /** 标签云高度：行数由 [chipLines] 决定，与绘制完全同源 */
    private fun chipCloudHeight(lineCount: Int): Int =
        lineCount.coerceAtLeast(1) * CHIP_H + (lineCount - 1).coerceAtLeast(0) * CHIP_LINE_GAP

    /** 单个标签宽度：文字宽 + 左右内边距；上限是整行宽（保证永不溢出内容区） */
    private fun chipWidth(item: String, p: Paint): Float =
        (p.measureText(item) + CHIP_PAD_H * 2).coerceAtMost(CONTENT_W.toFloat())

    /** 标签云换行：贪心填行；单个标签最宽只占一整行 */
    private fun chipLines(items: List<String>): List<List<String>> {
        val p = paint(FS_ROW, COLOR_BODY)
        val out = ArrayList<List<String>>()
        var line = ArrayList<String>()
        var used = 0f
        for (item in items) {
            val w = chipWidth(item, p)
            if (line.isNotEmpty() && used + CHIP_GAP + w > CONTENT_W.toFloat()) {
                out.add(line)
                line = ArrayList()
                used = 0f
            }
            if (line.isNotEmpty()) used += CHIP_GAP
            line.add(item)
            used += w
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    /** 把 block 流按 Section 切成章节卡片（每张卡片自己算高度），返回卡片列表 */
    private fun buildCards(
        units: List<Block>,
        accent: Int,
        startNo: Int,
        bodyP: Paint,
        tintBySection: Boolean = false,
    ): List<Item.Card> {
        val cards = ArrayList<Item.Card>()
        var no = startNo
        var title: String? = null
        var bucket = ArrayList<Block>()

        fun flush() {
            val body = trimGaps(bucket)
            bucket = ArrayList()
            if (title == null && body.isEmpty()) return
            // 先按数据形状把可图形化的连续行换成图表，再走同一套几何布局
            val rows = layoutRows(shapeBody(body), bodyP)
            val headerH = if (title != null) SECTION_HEADER_H else 0
            val lastBottom = rows.lastOrNull()?.let { it.top + it.height } ?: 0
            val height = maxOf(CARD_MIN_H, CARD_PAD_V * 2 + headerH + lastBottom)
            // title 是被闭包改写的可变局部变量，先取成 val 再做空判断（否则无法智能转换）
            val header = title
            val index = if (header != null) ++no else 0
            // 第 17 轮：本地统计的分节按标题取归属色（取不到就沿用分组色，行为不变）；
            // AI 报告不参与取色 —— 它整组共用分组色，这是它的"身份"，不能被标题打散。
            val cardAccent =
                if (tintBySection && header != null) sectionAccentOf(header) ?: accent else accent
            cards.add(Item.Card(header, index, cardAccent, rows, height))
        }

        for (u in units) {
            if (u is Block.Section) {
                flush()
                title = u.title
            } else {
                bucket.add(u)
            }
        }
        flush()
        return cards
    }

    /**
     * 第 17 轮引入、第 20 轮跟着「25 维整合」重排的分节归属色。
     *
     * 与弹窗 ChatAnalysisUi.sectionAccent **同一套落点**（同族信息同色、与相邻章节错开），
     * 只是把弹窗的三个主题色位映射成导出图的四个固定色：
     * ToneAccent→COLOR_ACCENT、ToneAlt→COLOR_ACCENT2、ToneThird→COLOR_ACCENT3，
     * 第 20 轮的六个新段位（回复速度榜 / 个人作息雷达 / 情绪词雷达 / 打字习惯 /
     * 约定与提醒 / 时段话量画像）继续沿用同一条规则接在后面。
     *
     * 判断一律用"标题包含关键词"（章节标题是本地拼的常量，同时也能吃掉 AI 报告里
     * 沿用了老标题的情况）：先用整段新标题的关键词，再兜住整合前的老标题关键词，
     * 所以旧报告、AI 自由标题都能取到颜色，取不到才返回 null 由调用方回退到分组色。
     */
    private fun sectionAccentOf(title: String): Int? = when {
        // ---- 第 20 轮的 25 个段位 ----
        title.contains("核心指标") -> COLOR_ACCENT
        title.contains("内容载体") || title.contains("载体偏好") -> COLOR_ACCENT2
        title.contains("活跃时段") || title.contains("活跃频次") -> COLOR_ACCENT3
        title.contains("活跃热力") -> COLOR_ACCENT
        title.contains("作息与昼夜") || title.contains("昼夜结构") ||
            title.contains("昼夜话量") || title.contains("作息画像") -> COLOR_ACCENT2
        title.contains("节奏与沉默") || title.contains("互动节奏") ||
            title.contains("沉默") -> COLOR_ACCENT3
        title.contains("消息长度") || title.contains("废话") || title.contains("每人说话") -> COLOR_ACCENT2
        title.contains("情绪与语气") || title.contains("情绪指纹") ||
            title.contains("标点与语气") -> COLOR_ACCENT3
        title.contains("高频词") || title.contains("口头禅") -> COLOR_ACCENT2
        title.contains("话题雷达") || title.contains("话题切换") ||
            title.contains("话题关键词") || title.contains("话题时段") -> COLOR_ACCENT
        title.contains("发言与互动") || title.contains("发言排行") ||
            title.contains("发言对比") || title.contains("互动平衡") -> COLOR_ACCENT3
        title.contains("特殊消息") || title.contains("@与互动") ||
            title.contains("撤回与系统事件") -> COLOR_ACCENT
        title.contains("每日开场") -> COLOR_ACCENT2
        title.contains("活跃日历") || title.contains("每日趋势") -> COLOR_ACCENT
        title.contains("回应速度") || title.contains("回复延迟") -> COLOR_ACCENT3
        title.contains("活跃密度") || title.contains("连续活跃") || title.contains("发言密度") -> COLOR_ACCENT2
        title.contains("连击与轮次") || title.contains("连击") || title.contains("对话轮次") -> COLOR_ACCENT
        title.contains("接话") || title.contains("提问与回应") || title.contains("默契") -> COLOR_ACCENT2
        title.contains("复读") -> COLOR_ACCENT3
        title.contains("回复速度") -> COLOR_ACCENT
        title.contains("个人作息") -> COLOR_ACCENT2
        title.contains("情绪词雷达") || title.contains("情绪词") -> COLOR_ACCENT3
        title.contains("打字习惯") || title.contains("打字") -> COLOR_ACCENT2
        title.contains("约定与提醒") || title.contains("约定") -> COLOR_ACCENT
        title.contains("时段话量") || title.contains("话量") -> COLOR_ACCENT3
        title.contains("表情符号") || title.contains("媒体与表情") -> COLOR_ACCENT
        else -> null
    }

    private fun buildItems(stats: String, ai: String, bodyP: Paint): List<Item> {
        val items = ArrayList<Item>()
        var nextNo = 1
        val statsCards = buildCards(groupKpis(parseBlocks(stats)), COLOR_ACCENT, nextNo, bodyP, true)
        nextNo += statsCards.count { it.title != null }
        val aiCards = buildCards(groupKpis(parseBlocks(ai)), COLOR_ACCENT2, nextNo, bodyP)

        if (statsCards.isNotEmpty()) {
            items.add(Item.Pill(GROUP_STATS, COLOR_ACCENT, pillWidth(GROUP_STATS, COLOR_ACCENT)))
            items.addAll(statsCards)
        }
        if (aiCards.isNotEmpty()) {
            items.add(Item.Pill(GROUP_AI, COLOR_ACCENT2, pillWidth(GROUP_AI, COLOR_ACCENT2)))
            items.addAll(aiCards)
        }
        if (items.isEmpty()) {
            // 空报告：给一张带标题和引导文案的说明卡，而不是一张光秃秃的白卡
            val rows = layoutRows(
                listOf(Block.TextLine(EMPTY_HINT), Block.TextLine(EMPTY_HINT_SUB)),
                bodyP,
            )
            val lastBottom = rows.lastOrNull()?.let { it.top + it.height } ?: 0
            items.add(
                Item.Card(
                    EMPTY_TITLE, 0, COLOR_WARN, rows,
                    maxOf(CARD_MIN_H, CARD_PAD_V * 2 + SECTION_HEADER_H + lastBottom),
                ),
            )
        }
        return items
    }

    private fun pillWidth(label: String, accent: Int): Int {
        val p = paint(FS_GROUP, accent, bold = true)
        val available = (GROUP_PILL_MAX_W - GROUP_PILL_PAD_L - GROUP_PILL_DOT - GROUP_PILL_DOT_GAP - GROUP_PILL_PAD_R).toFloat()
        val text = truncateToWidth(label, available, p)
        return (GROUP_PILL_PAD_L + GROUP_PILL_DOT + GROUP_PILL_DOT_GAP +
            ceilToInt(p.measureText(text)) + GROUP_PILL_PAD_R).coerceAtMost(GROUP_PILL_MAX_W)
    }

    /** 测量值向上取整：见 [layoutHeader] 里徽章宽度的注释（截断会吃掉 1px 内边距） */
    private fun ceilToInt(value: Float): Int = Math.ceil(value.toDouble()).toInt()

    private fun itemHeight(item: Item): Int = when (item) {
        is Item.Pill -> GROUP_PILL_H
        is Item.Card -> item.height
    }

    /** 绘制前的间距节奏：首块紧接顶部卡；分组 pill 前留大间距、后留小间距 */
    private fun gapBefore(items: List<Item>, index: Int): Int {
        val item = items[index]
        return when {
            index == 0 -> CARD_GAP
            item is Item.Pill -> GROUP_PILL_GAP
            items[index - 1] is Item.Pill -> GROUP_PILL_CARD_GAP
            else -> CARD_GAP
        }
    }

    // ==================================================================
    // 六、绘制
    // ==================================================================

    /**
     * 一页的几何：内容区间 [top + [headBand], contentBottom)，页高 = 内容 + 页脚收尾。
     *
     * [headBand] 只对多页导出的第 2 页起非零（续页页头留白带，见 [PAGE_HEAD_BAND_H]）：
     * 它把本页的**内容起点**下移，同时已经在 [paginate] 里从容量中扣掉，所以既不会压内容，
     * 也不会让任何一页超高。
     */
    private class Page(val top: Int, val contentBottom: Int, val height: Int, val headBand: Int = 0)

    /**
     * 导出（可能多页）。
     *
     * 流程固定为「先算几何 → 再分页 → 逐页绘制」：
     *  1. 第一遍只做几何（与单页时代完全相同的累加方式），得到内容总高与所有可断点；
     *  2. [paginate] 在可断点里挑页界 —— 优先落在卡片/行首，实在没有就硬切（内容仍连续）；
     *  3. 每一页单独分配位图、裁剪、平移后**重放同一套绘制代码**，因此分页不会改变观感，
     *     也不会出现半行文字被切的情况（行首断点保证）。
     *
     * 内存：单页位图 ≤ W × MAX_HEIGHT × 4B（由 init 里的 require 钉死 ≤ 120MB），
     * 且每页用完立即 recycle，多页不会叠加占用。
     */
    @Throws(Exception::class)
    private fun drawToFiles(
        stats: String,
        ai: String,
        sessionName: String,
        sessionWxid: String,
        period: String,
        dir: String,
        baseName: String,
    ): List<String> {
        val generated = "分析生成于 ${reportDateText()}"
        // 页脚元信息：会话名 · 时间范围 · 生成时间 · 数据来源。
        // 每一页都印一遍 —— 分页导出后，单张图脱离弹窗也能自证"是哪次分析、什么时段、数据从哪来"。
        val footerMeta = buildString {
            if (sessionName.isNotBlank()) append(sessionName)
            if (period.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(period)
            }
            if (isNotEmpty()) append(" · ")
            append(generated)
            append(" · 数据来源：本地消息数据库（仅统计纯文本消息）")
        }
        val bodyP = paint(FS_BODY, COLOR_BODY)

        // ---- 第一遍：纯几何（先把所有高度算准，再决定分页）----
        // 两套断点：preferred = 只在「条目边界」（卡片 / 分节条的分界）断开，
        // cuts = 全部合法断点（含卡片内部的行首）。分页优先用前者 —— 见 [paginate]。
        val header = layoutHeader(sessionName, sessionWxid, period, generated)
        val items = buildItems(stats, ai, bodyP)

        val itemTops = IntArray(items.size)
        var content = CANVAS_PAD + header.height
        for (i in items.indices) {
            content += gapBefore(items, i)
            itemTops[i] = content
            content += itemHeight(items[i])
        }
        val contentEnd = content.coerceAtLeast(CANVAS_PAD + header.height)
        val canvasHeight = (contentEnd + CARD_GAP + FOOTER_H + BOTTOM_PAD).coerceAtLeast(MIN_H)
        val preferred = itemCuts(itemTops, contentEnd)
        val pages = paginate(preferred, pageCuts(items, itemTops, contentEnd), contentEnd)
        val multi = pages.size > 1
        val paths = ArrayList<String>(pages.size)

        for ((index, page) in pages.withIndex()) {
            val label = "第 ${index + 1} / ${pages.size} 页"
            val path = if (multi) "$dir/${baseName}_第${index + 1}页.png" else "$dir/$baseName.png"

            // 跨页提示：本页顶部落在某张卡片内部（= 上一页没画完、这一页接着画）时，
            // 页脚要写清「接的是哪一节」，否则单张图不知道自己画的是谁的续页。
            val continues = continuedTitle(items, itemTops, page.top)

            // 单页时沿用「最小画布高度」（短报告不会被压成一条，观感与旧版一致）；
            // 多页时每页高度完全由分页几何决定，不额外加高。
            val pageHeight = if (multi) page.height else maxOf(page.height, MIN_H)

            // 本页的平移原点：第 2 页起把内容整体下移 [Page.headBand]，页顶让给续页页头横幅。
            // 于是"内容坐标 = page.top"正好落在横幅之下，横幅不会吞掉任何内容；
            // 而留白带的高度在分页时已经从容量里扣掉（见 [paginate]），页高也已含进去，
            // 所以页内总占用永远 ≤ MAX_HEIGHT，任何内容长度都不会画到画布之外。
            val pageOrigin = page.top - page.headBand

            // 本页内容区下边界：按分页几何裁，同时硬性不超过「页底 − 页脚保留带」。
            // 这一层是纯防御：即使将来常量改动让两者不自洽，内容也只会被裁短，
            // 绝不会画穿页脚、更不会画到画布之外（画布边界安全由此成为结构保证，而不是巧合）。
            val contentBottom = page.contentBottom
                .coerceAtMost(pageOrigin + page.height - (CARD_GAP + FOOTER_H + BOTTOM_PAD))
                .coerceAtLeast(page.top)

            // 页脚上边界：多页时紧接内容；单页时贴到画布底部（短报告不会在页脚下面拖一条空白）。
            // 单页且 pageHeight == page.height 时两者恰好相等，几何与旧版一致。
            val footerTop = if (multi) contentBottom + CARD_GAP
            else maxOf(contentBottom + CARD_GAP, pageOrigin + pageHeight - BOTTOM_PAD - FOOTER_H)

            // 本页内容是不是在卡片中途被打断（断点不是任何一条条目边界）
            val splits = splitsInsideItem(itemTops, contentBottom, contentEnd)

            val bmp = Bitmap.createBitmap(W, pageHeight, Bitmap.Config.ARGB_8888)
            try {
                val cv = Canvas(bmp)
                // 平移：之后所有绘制代码用的都是「整幅画布」的绝对坐标，
                // 与本页落在哪一段无关（绘制代码一行都不用改）。
                // 第 2 页起整体再多平移一个 headBand —— 见 [pageOrigin]。
                cv.translate(0f, -pageOrigin.toFloat())

                // ---- 背景：极浅的竖向渐变（上浅蓝 → 下纯白），跨页仍然连续 ----
                // 裁剪范围必须用「本页实际画布高」（pageHeight），不能用分页几何里的 page.height：
                // 单页导出且最短画布高度（MIN_H）生效时 pageHeight > page.height，
                // 旧写法会在画布底部留下一条没被任何绘制覆盖的**透明条带**（PNG 里就是"底部缺口"）。
                cv.save()
                cv.clipRect(0f, pageOrigin.toFloat(), W.toFloat(), (pageOrigin + pageHeight).toFloat())
                // 只画本页那一段：渐变是按整幅画布坐标算的（跨页连续），
                // 但绘制范围收窄到本页后，就不存在"每次导出都往画布外画一整幅矩形"的无谓绘制
                cv.drawRect(
                    0f, pageOrigin.toFloat(), W.toFloat(), (pageOrigin + pageHeight).toFloat(),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(
                            0f, 0f, 0f, canvasHeight.toFloat(),
                            intArrayOf(COLOR_BG_TOP, COLOR_BG_MID, COLOR_BG_BOTTOM),
                            floatArrayOf(0f, 0.35f, 1f),
                            Shader.TileMode.CLAMP,
                        )
                    },
                )
                cv.restore()

                // ---- 续页页头（第 2 页起）：留白带里的一条轻量横幅 ----
                // 页头信息卡只属于第 1 页；续页靠这条横幅自证身份，页顶不再是一块裸内容。
                if (page.headBand > 0) {
                    cv.save()
                    cv.clipRect(0f, pageOrigin.toFloat(), W.toFloat(), page.top.toFloat())
                    drawPageHead(cv, pageOrigin, sessionName, index + 1, pages.size)
                    cv.restore()
                }

                // ---- 内容区：严格裁到本页的内容边界 ----
                // 这里是「每张分页图底部总溢出」的根因修复点：
                // 旧实现整页只有一个裁剪矩形（裁到 page.height），而被分页切开的卡片
                // 会把内容边界**之下**的那半截行继续画出来 —— 于是内容越过 CARD_GAP
                // 压在页脚上、又从页脚下方（BOTTOM_PAD 那段空白）露出来；
                // 每张分页图的底部都长这样，看着就是"内容溢出画布边框"。
                // 现在内容区硬裁到 contentBottom：越界的半截行只会在下一页出现，
                // 一次不多、一次不少，卡片边界与页脚永远不会被内容侵占。
                cv.save()
                cv.clipRect(0f, page.top.toFloat(), W.toFloat(), contentBottom.toFloat())

                // ---- 第二遍：按同一份几何绘制 ----
                var y = CANVAS_PAD
                // 页头信息卡只画在第 1 页：其余页的页顶留给续页页头横幅，
                // 也顺手消掉了"每页重放页头卡片、再被页裁剪丢掉"的整批越界绘制
                if (page.top <= CANVAS_PAD) drawHeaderCard(cv, y, header)
                y += header.height

                for (i in items.indices) {
                    y += gapBefore(items, i)
                    val item = items[i]
                    val h = itemHeight(item)
                    // 完全落在本页内容区之上的：跳过（不画），但 y 必须继续累加
                    if (y + h <= page.top) {
                        y += h
                        continue
                    }
                    if (y >= contentBottom) break
                    when (item) {
                        is Item.Pill -> {
                            drawGroupPill(cv, item, y.toFloat())
                        }
                        is Item.Card -> {
                            drawCardGroup(cv, item, y, bodyP)
                        }
                    }
                    y += h
                }
                cv.restore()

                // ---- 页底收口：只要本页不是最后一页就画 ----
                // 位置取内容边界与页脚之间的 CARD_GAP 空档，不占内容高度、不动任何既有坐标。
                // 断在卡片中途（splits）时补一句「本节未完 · 见下页」，断在整卡片边界时
                // 只留一条渐隐细线 +「接下页」——两种情形都让"这是页边"一目了然，
                // 不会让人误读成内容溢出了画布。
                if (contentBottom < contentEnd) {
                    cv.save()
                    cv.clipRect(
                        0f,
                        contentBottom.toFloat(),
                        W.toFloat(),
                        (contentBottom + CARD_GAP).toFloat(),
                    )
                    drawContinuedCap(cv, contentBottom, splits)
                    cv.restore()
                }

                // ---- 页脚区：独立裁剪，与内容区互不侵占 ----
                cv.save()
                cv.clipRect(
                    0f,
                    footerTop.toFloat(),
                    W.toFloat(),
                    (pageOrigin + pageHeight).toFloat(),
                )
                drawFooter(
                    cv = cv,
                    top = footerTop,
                    pageLabel = label,
                    meta = footerMeta,
                    hint = if (continues.isBlank()) "" else "接上页 · $continues",
                    pageIndex = index + 1,
                    pageCount = pages.size,
                )
                cv.restore()

                writePng(bmp, path)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
            paths.add(path)
        }
        return paths
    }

    /** 一页的高度 = 内容高度 + 页脚收尾（[CARD_GAP] + [FOOTER_H] + [BOTTOM_PAD]）。 */
    private fun pageOf(top: Int, contentBottom: Int, headBand: Int = 0): Page {
        // 页高 = 续页页头留白带 + 内容高 + 页脚收尾：留白带把内容整体下移，必须计入页高，
        // 否则续页内容会被推出画布底边切成两半。
        val height = headBand + (contentBottom - top) + CARD_GAP + FOOTER_H + BOTTOM_PAD
        require(height <= MAX_HEIGHT) { "PNG 分页高度超限：${height}px" }
        return Page(top, contentBottom, height, headBand)
    }


    /** 只取「条目边界」（卡片 / 分节条的分界）：在这些位置分页，每页都以完整卡片收尾。 */
    private fun itemCuts(itemTops: IntArray, contentEnd: Int): IntArray {
        val cuts = java.util.TreeSet<Int>()
        cuts.add(contentEnd)
        for (t in itemTops) {
            if (t > 0 && t < contentEnd) cuts.add(t)
        }
        return cuts.toIntArray()
    }

    /**
     * 本页顶部落在哪张卡片内部 → 返回该卡片标题（页脚写「接上页 · 标题」用）。
     *
     * 正好卡在条目边界上、或落在分节条里，都返回空串（不需要提示）。
     * 只有分页真的做了一次"拦腰截断"才会有非空结果，所以这段逻辑对单页导出零开销。
     */
    private fun continuedTitle(items: List<Item>, itemTops: IntArray, top: Int): String {
        if (top <= 0) return ""
        for (i in items.indices) {
            val t = itemTops[i]
            if (t >= top) return ""
            if (top < t + itemHeight(items[i])) {
                val it = items[i]
                return if (it is Item.Card) it.title.orEmpty() else ""
            }
        }
        return ""
    }

    /** 本页内容是否在卡片内部被拦腰截断（断点不是任何一条条目边界，也不是整幅内容的末尾）。 */
    private fun splitsInsideItem(itemTops: IntArray, contentBottom: Int, contentEnd: Int): Boolean {
        if (contentBottom >= contentEnd) return false
        for (t in itemTops) {
            if (t == contentBottom) return false
        }
        return true
    }

    /**
     * 页底收口（不是最后一页时都会画）。
     *
     * 画在内容边界到页脚之间的 [CARD_GAP] 空档里：右侧一句提示 + 一条渐隐细线，
     * 明确告诉读者"这张图到此为止，后面还有"——断在整卡片边界时说「接下页」，
     * 断在卡片中途（[splits]）时补一句「本节未完 · 见下页」，
     * 免得看图的人把页边误读成"内容画漏了 / 溢出了画布"。
     * 不占内容高度、不改动任何既有坐标。
     */
    private fun drawContinuedCap(cv: Canvas, contentBottom: Int, splits: Boolean) {
        val lineY = contentBottom + 8f
        cv.drawRect(
            RectF(CARD_LEFT.toFloat(), lineY, CARD_RIGHT.toFloat(), lineY + 2f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    CARD_LEFT.toFloat(), 0f, CARD_RIGHT.toFloat(), 0f,
                    intArrayOf(
                        withAlpha(COLOR_ACCENT, 0x00),
                        withAlpha(COLOR_ACCENT, 0x59),
                        withAlpha(COLOR_ACCENT, 0x00),
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        val p = paint(FS_SMALL, COLOR_ACCENT, bold = true)
        val text = if (splits) CAP_SPLIT_TEXT else CAP_CONTINUE_TEXT
        val textW = p.measureText(text)
        val bandTop = lineY + 2f
        val bandH = (contentBottom + CARD_GAP - bandTop).coerceAtLeast(1f)
        val clip = RectF(CARD_RIGHT - textW - 8f, bandTop, CARD_RIGHT.toFloat(), bandTop + bandH)
        drawClipped(cv, text, clip.left, fitBaseline(bandTop, bandH, p, clip.bottom), clip, p)
    }

    /**
     * 续页页头（多页导出的第 2 页起，画在 [PAGE_HEAD_BAND_H] 留白带里）。
     *
     * 横幅克制到不能再克制：左侧一行小字「品牌 · 会话名」，右侧「第 N / M 页」，
     * 底部一条渐隐分隔线（与页脚顶部同款语言，浅到不抢内容）。
     * 它**不参与内容绘制**（内容裁剪从留白带底部开始），也不改变任何既有坐标 ——
     * 只是把"这一页是上页的续页"说清楚，让续页页顶不再是一块没有归属的裸内容。
     */
    private fun drawPageHead(cv: Canvas, top: Int, sessionName: String, index: Int, count: Int) {
        val bandTop = top.toFloat()
        val bandBottom = (top + PAGE_HEAD_BAND_H).toFloat()
        val textTop = bandTop + (PAGE_HEAD_BAND_H - PAGE_HEAD_TEXT_H) / 2f
        val textBottom = textTop + PAGE_HEAD_TEXT_H

        // 底部渐隐分隔线：把续页页头与正文轻轻分开
        cv.drawRect(
            RectF(CARD_LEFT.toFloat(), bandBottom - 2f, CARD_RIGHT.toFloat(), bandBottom),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    CARD_LEFT.toFloat(), 0f, CARD_RIGHT.toFloat(), 0f,
                    intArrayOf(
                        withAlpha(COLOR_ACCENT, 0x00),
                        withAlpha(COLOR_ACCENT, 0x33),
                        withAlpha(COLOR_ACCENT, 0x00),
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )

        // 左：品牌 + 会话名（截断在内容区一半宽以内，右侧永远留给页码）
        val nameP = paint(FS_SMALL, COLOR_META)
        val nameW = CONTENT_W * PAGE_HEAD_NAME_W_RATIO
        val nameText = truncateToWidth(
            if (sessionName.isBlank()) BRAND_TEXT else "$BRAND_TEXT · $sessionName",
            nameW,
            nameP,
        )
        val nameClip = RectF(CONTENT_LEFT.toFloat(), textTop, CONTENT_LEFT + nameW, textBottom)
        drawClipped(
            cv, nameText, nameClip.left,
            fitBaseline(textTop, PAGE_HEAD_TEXT_H.toFloat(), nameP, textBottom), nameClip, nameP,
        )

        // 右：第 N / M 页
        val pageP = paint(FS_SMALL, COLOR_ACCENT, bold = true)
        val pageText = "第 $index / $count 页"
        val pageW = pageP.measureText(pageText)
        val pageClip = RectF(CONTENT_RIGHT - pageW - 4f, textTop, CONTENT_RIGHT.toFloat(), textBottom)
        drawClipped(
            cv, pageText, pageClip.left,
            fitBaseline(textTop, PAGE_HEAD_TEXT_H.toFloat(), pageP, textBottom), pageClip, pageP,
        )
    }
    /**
     * 可断点集合（内容纵坐标）。
     *
     * 只允许在「不会被切坏」的位置分页：
     *  - 卡片/分节条的边界（[itemTops]）；
     *  - 卡片内部每一行的行首（正文、KPI、图表、标签云都是按行排的，行首断开不会切到文字）。
     */
    private fun pageCuts(items: List<Item>, itemTops: IntArray, contentEnd: Int): IntArray {
        val cuts = java.util.TreeSet<Int>()
        cuts.add(contentEnd)
        for (i in items.indices) {
            val top = itemTops[i]
            if (top > 0 && top < contentEnd) cuts.add(top)
            val item = items[i]
            if (item !is Item.Card) continue
            val bodyTop = top + CARD_PAD_V + (if (item.title != null) SECTION_HEADER_H else 0)
            for (row in item.rows) {
                val rowTop = bodyTop + row.top
                if (rowTop > 0 && rowTop < contentEnd) cuts.add(rowTop)
                // 正文块要额外把**块内每一个视觉行**的行首也登记成断点。
                // 一整块正文（AI 洞察、长段点评）可以换行成几十行、块高远超一页；只给块首
                // 登记断点的话，分页在 preferred / cuts 里都找不到落点，只能硬切 ——
                // 结果就是把某一行文字拦腰切断（实机上就是"底部内容溢出去半行"）。
                // row.lines 是布局阶段就算好的换行结果、绘制阶段原样复用，所以这里按
                // TEXT_LINE_H 步进是精确的，不会和绘制位置错开。
                if (row.unit is Block.TextLine) {
                    val n = row.lines.size
                    for (k in 1 until n) {
                        val y = rowTop + k * TEXT_LINE_H
                        if (y > 0 && y < contentEnd) cuts.add(y)
                    }
                }
            }
        }
        return cuts.toIntArray()
    }

    /**
     * 分页：一页一页地「尽量装满，但只停在整条目边界上」。
     *
     * 第 22 轮换掉了原来的「先按容量推算页数、再在区间里挑最接近理想位置的断点」策略。
     * 旧策略的致命问题是：它按容量把页数钉死，于是每一页的切点区间 [minCut, maxCut] 也被钉死；
     * 当这个区间里**恰好没有条目边界**时（报告越长越常见），切点只能落在**卡片内部**——
     * 于是每张分页图的页底都是一张被拦腰截断的卡片：没有下边框、没有圆角、KPI 单元只画了半格。
     * 这在实机上看就是"内容超出 PNG 的框架/组件 UI 边界""每张图片底部都溢出"。
     * （内容并没有真的画到画布外——最外层裁剪一直兜着底——但页底那张"半截卡片"在视觉上
     * 就是一次越界，而且它还会从上一页的下边框处直接断开。）
     *
     * 新策略把因果关系倒过来：**先决定切在哪，再看需要几页**——
     *   在本页容量上限 [limit] 之内取**最靠后的条目边界**（尽量装满、页数最少）；
     *   容量内没有条目边界，才退到行首断点（卡片内部的行首），再没有才硬切。
     * 于是每页（除"单张卡片本身就比一页高"这种不可抗情形外）都以**一张完整卡片**收尾，
     * 页与页之间是干净的卡片边界。
     * 页数因此不再由公式给出，而是装箱装出来的 —— 所以有 [MAX_PAGES] 作为显式终点。
     */
    private fun paginate(preferred: IntArray, cuts: IntArray, contentEnd: Int): List<Page> {
        val reserved = CARD_GAP + FOOTER_H + BOTTOM_PAD
        val usable = MAX_HEIGHT - reserved
        require(usable > 0) { "PNG 单页可用高度必须为正" }
        // 第 2 页起页顶要让出续页页头留白带，可放内容的高度随之减少
        val restUsable = usable - PAGE_HEAD_BAND_H
        require(restUsable > usable / 2) { "PNG 续页可用高度不足（续页页头占比过大）" }
        if (contentEnd <= usable) return listOf(pageOf(0, contentEnd))

        /** 第 k 页（0 基）的内容容量：首页用整页，续页扣掉续页页头 */
        fun capOf(k: Int): Int = if (k == 0) usable else restUsable

        val pages = ArrayList<Page>(4)
        var top = 0
        while (true) {
            val k = pages.size
            val cap = capOf(k)
            val headBand = if (k == 0) 0 else PAGE_HEAD_BAND_H
            val limit = top + cap

            // 本页就装得下全部剩余内容 —— 直接收尾，不再制造多余的页
            if (contentEnd <= limit) {
                pages.add(pageOf(top, contentEnd, headBand))
                return pages
            }

            // 本页最多切到 limit（再多就超出位图高度上限）
            val hi = minOf(limit, contentEnd - 1)

            // 切点优先级：整条目边界 → 行首断点 → 硬切（满载）。
            // 这里刻意**不**再要求"剩下的内容必须一页续页装得下"：那个约束会把 [top+1, hi]
            // 压成一条几十像素宽的窄缝（首页容量 19708、续页 19576，只差一个续页页头的高度），
            // 窄缝里通常一个条目边界都没有 —— 于是每页仍然从卡片中间断开。
            // 宁可让内容多分一页，也不要拦腰截断。
            var cut = lastCutAtMost(preferred, top + 1, hi)
                ?: lastCutAtMost(cuts, top + 1, hi)
                ?: hi

            // 尾页保护：切完只剩很矮的一截时往前退一个条目边界，
            // 免得最后一张图只有一行字 + 一大片页脚。
            if (contentEnd - cut < MIN_TAIL_CONTENT) {
                val back = lastCutAtMost(preferred, top + 1, cut - 1)
                if (back != null && contentEnd - back <= restUsable) cut = back
            }

            // 防御：hi > top 恒成立、故 cut 必 > top；真出现也只退化成"本页装满"。
            if (cut <= top) cut = hi
            pages.add(pageOf(top, cut, headBand))
            top = cut

            if (pages.size >= MAX_PAGES) {
                // 触顶：剩余内容并进最后一页，由 [pageOf] 的 require 兜住页高上限
                pages.add(pageOf(top, contentEnd, PAGE_HEAD_BAND_H))
                return pages
            }
        }
    }

    /**
     * [cuts] 里落在 (top, limit] 区间内、位置最靠后的断点；区间内没有就返回 null。
     *
     * [cuts] 由 TreeSet 产出，天然升序 —— 这里顺手用升序做短路，避免整表扫描。
     */
    private fun lastCutAtMost(cuts: IntArray, top: Int, limit: Int): Int? {
        var best: Int? = null
        for (c in cuts) {
            if (c <= top) continue
            if (c > limit) break
            best = c
        }
        return best
    }

    /** 落盘（含目录校验与 fsync），失败一律抛异常交给上层提示。 */
    @Throws(Exception::class)
    private fun writePng(bmp: Bitmap, path: String) {
        var fos: FileOutputStream? = null
        try {
            val out = File(path)
            val parent = out.parentFile
            if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.canWrite()) {
                throw Exception("导出目录不可写: $path")
            }
            fos = FileOutputStream(out, false)
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) throw Exception("PNG 编码失败")
            fos.flush()
            fos.fd.sync()
            if (!out.exists() || out.length() <= 0L) throw Exception("PNG 文件未落盘")
        } finally {
            fos?.close()
        }
    }

    /**
     * 统一的卡片外观：完全相同的左右边界、圆角、细描边、轻阴影、左侧渐变强调条。
     * 顶部信息卡额外叠一层装饰圆（glow=true），画在底色之后、描边之前，所以不会盖住卡片边线。
     */
    private fun drawCard(cv: Canvas, top: Int, bottom: Int, accent: Int, glow: Boolean = false) {
        val rect = RectF(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), bottom.toFloat())

        // 1) 投影：带阴影的圆角矩形（Bitmap 画布走软件渲染，阴影生效）
        val shadowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CARD
            style = Paint.Style.FILL
            setShadowLayer(CARD_SHADOW_RADIUS, 0f, CARD_SHADOW_DY, COLOR_SHADOW)
        }
        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, shadowP)
        shadowP.clearShadowLayer()

        // 2) 卡片底色：白 → 极浅强调色（同一强调色只做明度区分，整体更克制）
        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, top.toFloat(), 0f, bottom.toFloat(),
                intArrayOf(COLOR_CARD, blendOnWhite(accent, CARD_TINT_ALPHA)),
                null, Shader.TileMode.CLAMP,
            )
        })

        // 3) 顶部装饰圆：必须真的裁在卡片内。
        //    这里之前只写了注释「被卡片 clip 限制在卡内」，却没有配对 save/clipRect/restore ——
        //    半径 300 的圆从 (CARD_RIGHT-40, top+40) 铺开，右缘会越过卡片右边线（1368）
        //    一直压到画布右缘（1440）、下缘压过整张卡片头部；在实机上就是卡片右上角
        //    那一块"淡淡地糊出边框"的色斑 —— 用户反馈的"内容超出组件 UI 边界"。
        if (glow) {
            cv.save()
            cv.clipRect(rect)
            cv.drawCircle(
                CARD_RIGHT.toFloat() - 40f, top + 40f, HEADER_GLOW_R,
                shapePaint(blendOnWhite(COLOR_ACCENT2, 0x12)),
            )
            cv.restore()
        }

        // 4) 细描边：分享后被压暗也不糊
        cv.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_RULE
            style = Paint.Style.STROKE
            strokeWidth = CARD_STROKE_W
        })

        // 5) 顶部高光细线：两端内缩到圆角之后，避免和圆角打架
        cv.drawRect(
            RectF(CARD_LEFT + CARD_RADIUS, top + 1f, CARD_RIGHT - CARD_RADIUS, top + 1f + CARD_TOP_LIGHT_H),
            shapePaint(blendOnWhite(accent, CARD_TOP_LIGHT_ALPHA)),
        )

        // 6) 左侧强调条：竖向渐变（实色 → 提亮），clip 到卡片圆角内
        val barTop = top.toFloat() + CARD_RADIUS * 0.5f
        val barBottom = bottom.toFloat() - CARD_RADIUS * 0.5f
        if (barBottom > barTop) {
            cv.save()
            cv.clipRect(
                CARD_LEFT.toFloat(), top.toFloat(),
                CARD_LEFT + CARD_RADIUS, bottom.toFloat(),
            )
            cv.drawRoundRect(
                RectF(
                    CARD_LEFT.toFloat(), barTop,
                    (CARD_LEFT + CARD_ACCENT_W).toFloat(), barBottom,
                ),
                5f, 5f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, barTop, 0f, barBottom,
                        intArrayOf(accent, lighten(accent)),
                        null, Shader.TileMode.CLAMP,
                    )
                },
            )
            cv.restore()
        }
    }

    private fun drawHeaderCard(cv: Canvas, top: Int, spec: HeaderSpec) {
        val bottom = top + spec.height
        drawCard(cv, top, bottom, COLOR_ACCENT, glow = true)
        // 整卡硬裁剪：任何内容都不可能画到卡片之外
        cv.save()
        cv.clipRect(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), bottom.toFloat())

        val inner = (top + CARD_PAD_V).toFloat()
        val innerBottom = (bottom - CARD_PAD_V).toFloat()

        // ---- 品牌行：左侧品牌块，右侧范围徽章（互不挤占）----
        val brandP = paint(FS_BRAND, COLOR_ACCENT, bold = true)
        val brandTop = inner
        val brandRight = if (spec.badgeW > 0) {
            (CONTENT_RIGHT - spec.badgeW - COL_GAP).toFloat()
        } else {
            CONTENT_RIGHT.toFloat()
        }
        if (brandRight > CONTENT_LEFT + 160f) {
            val markTop = brandTop + (BRAND_LINE_H - BRAND_BOX) / 2f
            cv.drawRoundRect(
                RectF(
                    CONTENT_LEFT.toFloat(), markTop,
                    (CONTENT_LEFT + BRAND_BOX).toFloat(), markTop + BRAND_BOX,
                ),
                6f, 6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        CONTENT_LEFT.toFloat(), markTop,
                        (CONTENT_LEFT + BRAND_BOX).toFloat(), markTop + BRAND_BOX,
                        intArrayOf(COLOR_ACCENT, COLOR_ACCENT2),
                        null, Shader.TileMode.CLAMP,
                    )
                },
            )
            val brandClip = RectF(
                (CONTENT_LEFT + BRAND_BOX + 18).toFloat(), brandTop,
                brandRight, brandTop + BRAND_LINE_H,
            )
            val brand = truncateToWidth(BRAND_TEXT, brandClip.width(), brandP)
            drawClipped(
                cv, brand, brandClip.left,
                fitBaseline(brandTop, BRAND_LINE_H.toFloat(), brandP, minOf(brandClip.bottom, innerBottom)),
                brandClip, brandP,
            )
        }

        // ---- 右上角范围徽章 ----
        if (spec.badgeW > 0) {
            val bTop = brandTop + (BRAND_LINE_H - BADGE_H) / 2f
            val bRect = RectF(
                (CONTENT_RIGHT - spec.badgeW).toFloat(), bTop,
                CONTENT_RIGHT.toFloat(), bTop + BADGE_H,
            )
            cv.drawRoundRect(bRect, BADGE_H / 2f, BADGE_H / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    bRect.left, 0f, bRect.right, 0f,
                    intArrayOf(COLOR_ACCENT, COLOR_ACCENT2),
                    null, Shader.TileMode.CLAMP,
                )
            })
            val btP = paint(FS_BADGE, Color.WHITE, bold = true)
            val bt = truncateToWidth(spec.badgeText, (spec.badgeW - BADGE_PAD_H * 2).toFloat(), btP)
            drawClipped(
                cv, bt,
                bRect.centerX() - btP.measureText(bt) / 2f,
                fitBaseline(bRect.top, BADGE_H.toFloat(), btP, bRect.bottom),
                bRect, btP,
            )
        }

        // ---- 品牌行与标题之间的细分隔线（把封面分成"品牌"和"会话"两层）----
        val divY = inner + BRAND_LINE_H + HEADER_DIVIDER_GAP
        cv.drawRect(
            RectF(CONTENT_LEFT.toFloat(), divY, CONTENT_RIGHT.toFloat(), divY + 2f),
            shapePaint(COLOR_RULE),
        )

        // ---- 头像（渐变圆角方块 + 首字）----
        val avRect = RectF(
            CONTENT_LEFT.toFloat(), inner + spec.avatarTop,
            (CONTENT_LEFT + AVATAR_SIZE).toFloat(), inner + spec.avatarTop + AVATAR_SIZE,
        )
        cv.drawRoundRect(avRect, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                avRect.left, avRect.top, avRect.right, avRect.bottom,
                intArrayOf(COLOR_ACCENT, COLOR_ACCENT2),
                null, Shader.TileMode.CLAMP,
            )
        })
        val avP = paint(FS_AVATAR, Color.WHITE, bold = true)
        val avW = avP.measureText(spec.avatarChar)
        drawClipped(
            cv, spec.avatarChar,
            avRect.centerX() - avW / 2f,
            fitBaseline(avRect.top, AVATAR_SIZE.toFloat(), avP, avRect.bottom),
            avRect, avP,
        )

        // ---- 三行文字：标题 / 副标题 / 生成时间，各自矩形内硬裁剪 + 底边硬约束 ----
        val titleP = paint(FS_TITLE, COLOR_TITLE, bold = true)
        val metaP = paint(FS_META, COLOR_META)
        val smallP = paint(FS_SMALL, COLOR_META)
        drawTextLines(
            cv, spec.nameLines, spec.textLeft.toFloat(), inner + spec.nameTop,
            HEADER_TITLE_LINE_H, titleP, spec.textRight.toFloat(), innerBottom,
        )
        drawTextLines(
            cv, spec.subLines, spec.textLeft.toFloat(), inner + spec.subTop,
            HEADER_META_LINE_H, metaP, spec.textRight.toFloat(), innerBottom,
        )
        drawTextLines(
            cv, spec.genLines, spec.textLeft.toFloat(), inner + spec.genTop,
            HEADER_META_LINE_H, smallP, spec.textRight.toFloat(), innerBottom,
        )

        cv.restore()
    }

    private fun drawCardGroup(cv: Canvas, card: Item.Card, top: Int, bodyP: Paint) {
        drawCard(cv, top, top + card.height, card.accent)
        cv.save()
        cv.clipRect(
            CARD_LEFT.toFloat(), top.toFloat(),
            CARD_RIGHT.toFloat(), (top + card.height).toFloat(),
        )

        // 章节标题：先落成局部变量再判空，避免任何智能转换歧义
        val title = card.title
        if (title != null) drawCardHeader(cv, top, title, card.index, card.accent)
        val bodyTop = top + CARD_PAD_V + (if (title != null) SECTION_HEADER_H else 0)
        val innerBottom = (top + card.height - CARD_PAD_V).toFloat()
        for (row in card.rows) {
            val rowTop = (bodyTop + row.top).toFloat()
            when (val u = row.unit) {
                is Block.KpiGrid -> drawKpiGrid(cv, u.items, rowTop, card.accent, innerBottom)
                is Block.KeyValue -> drawKeyValueRow(cv, u, rowTop, row.height.toFloat(), innerBottom)
                is Block.BarLine -> drawBarRow(cv, u, rowTop, row.height.toFloat(), innerBottom, card.accent)
                is Block.Donut -> drawDonut(cv, u, rowTop, innerBottom)
                is Block.ColumnChart -> drawColumnChart(cv, u, rowTop, card.accent, innerBottom)
                is Block.ChipCloud -> drawChipCloud(cv, row.chipLines, rowTop, card.accent, innerBottom)
                is Block.Heatmap -> drawHeatmap(cv, u, rowTop, card.accent, innerBottom)
                is Block.TextLine -> drawTextLines(
                    cv, row.lines, CONTENT_LEFT.toFloat(), rowTop,
                    TEXT_LINE_H, bodyP, CONTENT_RIGHT.toFloat(), innerBottom,
                )
                is Block.Section -> Unit
                is Block.Gap -> Unit
            }
        }

        cv.restore()
    }

    /** 章节卡片头部：序号徽章（渐变底 + 白色数字）+ 标题 + 渐变下划线 + 右侧细分隔线 */
    private fun drawCardHeader(cv: Canvas, cardTop: Int, title: String, index: Int, accent: Int) {
        val headerTop = (cardTop + CARD_PAD_V).toFloat()
        val bandH = SECTION_BADGE_BOX.toFloat()
        val badgeW = if (index > 0) SECTION_BADGE_BOX else 0

        if (badgeW > 0) {
            val badge = RectF(
                CONTENT_LEFT.toFloat(), headerTop,
                (CONTENT_LEFT + badgeW).toFloat(), headerTop + bandH,
            )
            cv.drawRoundRect(badge, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    badge.left, badge.top, badge.right, badge.bottom,
                    intArrayOf(accent, lighten(accent)),
                    null, Shader.TileMode.CLAMP,
                )
            })
            val noP = paint(FS_SECTION_NO, Color.WHITE, bold = true)
            val noText = index.toString()
            drawClipped(
                cv, noText,
                badge.centerX() - noP.measureText(noText) / 2f,
                fitBaseline(badge.top, bandH, noP, badge.bottom),
                badge, noP,
            )
        }

        val titleP = paint(FS_SECTION, COLOR_TITLE, bold = true)
        val titleLeft = CONTENT_LEFT + badgeW + if (badgeW > 0) SECTION_TITLE_GAP else 0
        val titleClip = RectF(
            titleLeft.toFloat(), headerTop,
            CONTENT_RIGHT.toFloat(), headerTop + bandH,
        )
        val text = truncateToWidth(title, titleClip.width() - CELL_INSET, titleP)
        drawClipped(
            cv, text, (titleLeft + CELL_INSET).toFloat(),
            fitBaseline(headerTop, bandH, titleP, titleClip.bottom),
            titleClip, titleP,
        )

        // 下划线（强调色渐隐）+ 右侧细分隔线：一条水平视觉线把"标题带"收住
        val ulTop = headerTop + bandH + SECTION_UNDERLINE_GAP
        cv.drawRoundRect(
            RectF(
                CONTENT_LEFT.toFloat(), ulTop,
                (CONTENT_LEFT + SECTION_UNDERLINE_W).toFloat(), ulTop + SECTION_UNDERLINE_H,
            ),
            SECTION_UNDERLINE_H / 2f, SECTION_UNDERLINE_H / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    CONTENT_LEFT.toFloat(), 0f,
                    (CONTENT_LEFT + SECTION_UNDERLINE_W).toFloat(), 0f,
                    intArrayOf(accent, withAlpha(accent, 0x00)),
                    null, Shader.TileMode.CLAMP,
                )
            },
        )
        val ruleLeft = CONTENT_LEFT + SECTION_UNDERLINE_W + SECTION_RULE_GAP
        if (ruleLeft < CONTENT_RIGHT) {
            val ruleY = ulTop + (SECTION_UNDERLINE_H - 2f) / 2f
            cv.drawRect(
                RectF(ruleLeft.toFloat(), ruleY, CONTENT_RIGHT.toFloat(), ruleY + 2f),
                shapePaint(COLOR_RULE),
            )
        }
    }

    /** 分组 pill：左对齐、与卡片同左边界的圆角标签（本地统计报告 / AI 洞察报告） */
    private fun drawGroupPill(cv: Canvas, pill: Item.Pill, top: Float) {
        val rect = RectF(
            CARD_LEFT.toFloat(), top,
            (CARD_LEFT + pill.width).toFloat(), top + GROUP_PILL_H,
        )
        val radius = GROUP_PILL_H / 2f
        cv.drawRoundRect(rect, radius, radius, shapePaint(blendOnWhite(pill.accent, 0x1A)))
        cv.drawRoundRect(
            rect, radius, radius,
            shapePaint(blendOnWhite(pill.accent, 0x48), stroke = true, strokeWidth = 2f),
        )

        val dotLeft = (CARD_LEFT + GROUP_PILL_PAD_L).toFloat()
        val dotTop = top + (GROUP_PILL_H - GROUP_PILL_DOT) / 2f
        cv.drawOval(
            RectF(dotLeft, dotTop, dotLeft + GROUP_PILL_DOT, dotTop + GROUP_PILL_DOT),
            shapePaint(pill.accent),
        )

        val textLeft = CARD_LEFT + GROUP_PILL_PAD_L + GROUP_PILL_DOT + GROUP_PILL_DOT_GAP
        val p = paint(FS_GROUP, pill.accent, bold = true)
        val clip = RectF(
            textLeft.toFloat(), top,
            rect.right - GROUP_PILL_PAD_R + CELL_INSET, top + GROUP_PILL_H,
        )
        val text = truncateToWidth(pill.label, clip.width(), p)
        drawClipped(
            cv, text, textLeft.toFloat(),
            fitBaseline(top, GROUP_PILL_H.toFloat(), p, clip.bottom),
            clip, p,
        )
    }

    // ---- KPI 网格：两遍布局共用的分行/高度规则（布局与绘制必须用同一套，否则必然串位）----

    /** 该单元是否需要把单位另起一行（单位太长时同排会被省略号吃掉信息） */
    private fun needsUnitRow(kv: Block.KeyValue): Boolean =
        splitValueUnit(kv.value).second.length > KPI_UNIT_INLINE_MAX

    /**
     * 单个 KPI 单元高度：基础高度 + （单位另起一行）+ （百分比份额条）。
     * [drawKpiCell] 的纵向推进顺序与此函数严格一一对应。
     */
    private fun kpiCellHeight(kv: Block.KeyValue): Int {
        var h = KPI_CELL_H
        if (needsUnitRow(kv)) h += KPI_UNIT_GAP + KPI_UNIT_ROW_H
        if (shareFraction(kv.value) != null) h += KPI_SHARE_GAP + KPI_SHARE_BAR_H
        return h
    }

    /** 一行（最多两张单元）的高度：取两张里更高的那张，保证同一行底色等高 */
    private fun kpiRowCellH(row: List<Block.KeyValue>): Int =
        row.maxOf { kpiCellHeight(it) }

    /** KPI 网格整体高度：与 [drawKpiGrid] 的排布完全一致 */
    private fun kpiGridHeight(items: List<Block.KeyValue>): Int {
        val rows = items.chunked(2)
        return rows.sumOf { kpiRowCellH(it) } + (rows.size - 1).coerceAtLeast(0) * KPI_ROW_GAP
    }

    /** KPI 网格：每行两张浅色底卡片，标签小、数字大（语义色）、单位轻、百分比带份额条 */
    private fun drawKpiGrid(
        cv: Canvas,
        items: List<Block.KeyValue>,
        rowTop: Float,
        accent: Int,
        limitBottom: Float,
    ) {
        var cellTop = rowTop
        items.chunked(2).forEach { pair ->
            val cellH = kpiRowCellH(pair)
            pair.forEachIndexed { c, kv ->
                drawKpiCell(
                    cv, kv,
                    CONTENT_LEFT + c * (KPI_CELL_W + KPI_COL_GAP),
                    cellTop, cellH, accent, limitBottom,
                )
            }
            cellTop += cellH + KPI_ROW_GAP
        }
    }

    private fun drawKpiCell(
        cv: Canvas,
        kv: Block.KeyValue,
        left: Int,
        top: Float,
        cellH: Int,
        accent: Int,
        limitBottom: Float,
    ) {
        // 语义色：命中的关键词决定数字与底色的色调，没命中就用卡片主色
        val tint = semanticColor(kv.key) ?: accent
        val rect = RectF(left.toFloat(), top, (left + KPI_CELL_W).toFloat(), top + cellH)
        cv.drawRoundRect(rect, 26f, 26f, shapePaint(blendOnWhite(tint, 0x12)))
        cv.drawRoundRect(
            rect, 26f, 26f,
            shapePaint(blendOnWhite(tint, 0x3A), stroke = true, strokeWidth = 2f),
        )
        // 第 17 轮：左侧归属色细条（与弹窗 KPI 卡片的左条同款）。
        // 画在单元内部左侧的 34px 内边距里（KPI_PAD_H），不碰任何文字、不改任何几何。
        cv.drawRoundRect(
            RectF(rect.left, rect.top + 12f, rect.left + KPI_STRIP_W, rect.bottom - 12f),
            KPI_STRIP_W / 2f, KPI_STRIP_W / 2f,
            shapePaint(blendOnWhite(tint, 0x5A)),
        )

        val innerLeft = (left + KPI_PAD_H).toFloat()
        val innerRight = (left + KPI_CELL_W - KPI_PAD_H).toFloat()

        // ---- 标签（小字、次要色）----
        val labelP = paint(FS_KPI_LABEL, COLOR_META)
        val labelTop = top + KPI_PAD_V
        val labelClip = RectF(innerLeft, labelTop, innerRight, labelTop + KPI_LABEL_ROW_H)
        val labelText = truncateToWidth(kv.key, labelClip.width(), labelP)
        drawClipped(
            cv, labelText, innerLeft,
            fitBaseline(labelTop, KPI_LABEL_ROW_H.toFloat(), labelP, minOf(labelClip.bottom, limitBottom)),
            labelClip, labelP,
        )

        // ---- 数值（大数字 + 轻单位）。单位短就同排；长则另起一行，绝不因宽度不足丢字 ----
        val valueTop = labelTop + KPI_LABEL_ROW_H + KPI_LABEL_VALUE_GAP
        val valueClip = RectF(innerLeft, valueTop, innerRight, valueTop + KPI_VALUE_ROW_H)
        val numP = paint(FS_KPI_VALUE, tint, bold = true)
        val unitP = paint(FS_KPI_UNIT, COLOR_META)
        val (num, unit) = splitValueUnit(kv.value)
        val inlineUnit = unit.isNotEmpty() && unit.length <= KPI_UNIT_INLINE_MAX
        val unitW = if (inlineUnit) unitP.measureText(unit) + 12f else 0f
        val numText = truncateToWidth(num, (valueClip.width() - unitW).coerceAtLeast(0f), numP)
        val valueBaseline = fitBaseline(valueTop, KPI_VALUE_ROW_H.toFloat(), numP, minOf(valueClip.bottom, limitBottom))
        drawClipped(cv, numText, innerLeft, valueBaseline, valueClip, numP)
        if (inlineUnit) {
            val ux = innerLeft + numP.measureText(numText) + 12f
            val unitClip = RectF(ux, valueTop, innerRight, valueClip.bottom)
            if (unitClip.width() > 0f) {
                drawClipped(cv, unit, ux, valueBaseline, unitClip, unitP)
            }
        }
        var nextTop = valueTop + KPI_VALUE_ROW_H
        if (!inlineUnit && unit.isNotEmpty()) {
            // 单位行：整行给单位，最多一行（超长自动省略），永远落在单元内
            val unitTop = nextTop + KPI_UNIT_GAP
            val unitBottom = minOf(unitTop + KPI_UNIT_ROW_H, limitBottom)
            val unitClip = RectF(innerLeft, unitTop, innerRight, unitTop + KPI_UNIT_ROW_H)
            if (unitClip.width() > 0f && unitBottom > unitTop) {
                val unitText = truncateToWidth(unit, unitClip.width(), unitP)
                drawClipped(
                    cv, unitText, innerLeft,
                    fitBaseline(unitTop, KPI_UNIT_ROW_H.toFloat(), unitP, unitBottom),
                    unitClip, unitP,
                )
            }
            nextTop = unitTop + KPI_UNIT_ROW_H
        }

        // ---- 份额进度条：数值本身是百分比时才画（有参照才叫进度）----
        val share = shareFraction(kv.value)
        if (share != null) {
            val barTop = nextTop + KPI_SHARE_GAP
            val barH = KPI_SHARE_BAR_H.toFloat()
            val track = RectF(innerLeft, barTop, innerRight, barTop + barH)
            if (track.bottom <= limitBottom && track.width() > 0f) {
                cv.drawRoundRect(track, barH / 2f, barH / 2f, shapePaint(COLOR_TRACK))
                val fillW = (track.width() * share).coerceAtLeast(barH)
                val fill = RectF(innerLeft, barTop, innerLeft + fillW, barTop + barH)
                cv.drawRoundRect(fill, barH / 2f, barH / 2f, shapePaint(tint))
            }
        }
    }

    /**
     * 条形行：严格三列。
     * 1) 先铺满整列淡轨道（给所有条形一个 100% 参照，图表留白不贴边）
     * 2) 再画比例填充（只在 bar 列内，长度按比例、最小 BAR_MIN_W、最大不超过列宽）
     * 3) 再画标签（label 列内；排行行先画序号徽章，昵称去掉重复的 "1." 前缀）
     * 4) 再画数值（value 列内右对齐）
     */
    private fun drawBarRow(
        cv: Canvas,
        u: Block.BarLine,
        rowTop: Float,
        rowH: Float,
        limitBottom: Float,
        accent: Int,
    ) {
        val rowBottom = rowTop + rowH
        val labelP = paint(FS_ROW, COLOR_BODY)
        val valueP = paint(FS_ROW, valueColor(u.rank), bold = true)
        val baseline = fitBaseline(rowTop, rowH, labelP, minOf(rowBottom, limitBottom))
        val barTop = rowTop + (rowH - BAR_TRACK_H) / 2f

        // ---- 1) bar 列：整列淡轨道 + 渐变填充 ----
        cv.drawRoundRect(
            RectF(BAR_LEFT.toFloat(), barTop, BAR_RIGHT.toFloat(), barTop + BAR_TRACK_H),
            BAR_TRACK_H / 2f, BAR_TRACK_H / 2f,
            shapePaint(COLOR_TRACK),
        )
        if (u.ratio > 0f) {
            val fillW = (BAR_W * u.ratio).coerceIn(BAR_MIN_W, BAR_W.toFloat())
            val fill = RectF(BAR_LEFT.toFloat(), barTop, BAR_LEFT + fillW, barTop + BAR_TRACK_H)
            drawClippedRect(
                cv, fill, BAR_LEFT.toFloat(), BAR_RIGHT.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    // 条形颜色跟随所属卡片的主色（统计卡=蓝、AI 卡=青），并用渐变提亮右端
                    shader = LinearGradient(
                        BAR_LEFT.toFloat(), 0f, BAR_RIGHT.toFloat(), 0f,
                        intArrayOf(accent, lighten(accent)),
                        null, Shader.TileMode.CLAMP,
                    )
                },
            )
        }

        // ---- 2) label 列：排行行先画圆角序号徽章（金银铜），并去掉文本里重复的序号 ----
        var labelLeft = LABEL_LEFT + CELL_INSET
        if (u.rank > 0) {
            drawRankBadge(cv, u.rank, labelLeft, rowTop + rowH / 2f, accent)
            labelLeft += RANK_BOX + RANK_GAP
        }
        val labelClip = RectF(LABEL_LEFT.toFloat(), rowTop, LABEL_RIGHT.toFloat(), rowBottom)
        val pureLabel = if (u.rank > 0) u.label.replaceFirst(RANK_PREFIX, "").trim() else u.label
        val labelAvailW = (LABEL_RIGHT - CELL_INSET - labelLeft).toFloat()
        if (labelAvailW > 0f) {
            val labelText = truncateToWidth(pureLabel, labelAvailW, labelP)
            drawClipped(cv, labelText, labelLeft.toFloat(), baseline, labelClip, labelP)
        }

        // ---- 3) value 列（右对齐到列右边界）----
        if (u.value.isNotEmpty()) {
            val valueClip = RectF(VALUE_LEFT.toFloat(), rowTop, VALUE_RIGHT.toFloat(), rowBottom)
            val vx = (VALUE_RIGHT - CELL_INSET).toFloat() - valueP.measureText(u.value)
            drawClipped(cv, u.value, vx, baseline, valueClip, valueP)
        }
    }

    /**
     * 排行序号徽章：Top1-3 用金银铜实底 + 白字，其余用强调色浅底 + 强调色字
     * （与 App 内报告视图的 RankBadge 观感一致）。徽章只在自己的矩形内绘制。
     */
    private fun drawRankBadge(cv: Canvas, rank: Int, left: Int, centerY: Float, accent: Int) {
        val box = RANK_BOX.toFloat()
        val top = centerY - box / 2f
        val rect = RectF(left.toFloat(), top, left + box, top + box)
        val medal = rank in 1..3
        cv.drawRoundRect(
            rect, box / 2.6f, box / 2.6f,
            shapePaint(if (medal) valueColor(rank) else blendOnWhite(accent, 0x1C)),
        )
        val p = paint(FS_RANK, if (medal) Color.WHITE else accent, bold = true)
        val s = rank.toString()
        val tx = rect.centerX() - p.measureText(s) / 2f
        drawClipped(cv, s, tx, fitBaseline(top, box, p, rect.bottom), rect, p)
    }

    /** 单行键值（未聚合成 KPI 网格时）：key 左、value 右，两边都有限宽 */
    private fun drawKeyValueRow(
        cv: Canvas,
        u: Block.KeyValue,
        rowTop: Float,
        rowH: Float,
        limitBottom: Float,
    ) {
        val tint = semanticColor(u.key) ?: COLOR_TITLE
        val keyP = paint(FS_ROW, COLOR_META)
        val valueP = paint(FS_ROW, tint, bold = true)
        val baseline = fitBaseline(rowTop, rowH, keyP, minOf(rowTop + rowH, limitBottom))
        val half = (CONTENT_W - COL_GAP) / 2

        val keyClip = RectF(CONTENT_LEFT.toFloat(), rowTop, (CONTENT_LEFT + half).toFloat(), rowTop + rowH)
        val keyText = truncateToWidth(u.key, keyClip.width() - CELL_INSET * 2f, keyP)
        drawClipped(cv, keyText, (CONTENT_LEFT + CELL_INSET).toFloat(), baseline, keyClip, keyP)

        val valueClip = RectF(
            (CONTENT_LEFT + half + COL_GAP).toFloat(), rowTop,
            CONTENT_RIGHT.toFloat(), rowTop + rowH,
        )
        val valueText = truncateToWidth(u.value, valueClip.width() - CELL_INSET * 2f, valueP)
        val vx = valueClip.right - CELL_INSET - valueP.measureText(valueText)
        drawClipped(cv, valueText, vx, baseline, valueClip, valueP)
    }

    /**
     * 环形图 + 图例：环在左、图例在右（色块 + 名称 + 占比 + 原值）。
     * 环用 STROKE 弧线画，不建 Path、不建位图；扇区之间留小缝，比严丝合缝更像图表。
     */
    private fun drawDonut(cv: Canvas, d: Block.Donut, rowTop: Float, limitBottom: Float) {
        val total = d.slices.sumOf { it.count }
        if (total <= 0L) return

        val size = DONUT_SIZE.toFloat()
        val ringLeft = CONTENT_LEFT.toFloat()
        val ringTop = rowTop + (donutHeight(d) - DONUT_SIZE) / 2f
        val ring = RectF(ringLeft, ringTop, ringLeft + size, ringTop + size)

        val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = DONUT_RING_W
            strokeCap = Paint.Cap.BUTT
        }
        // 底轨：给不满的占比一个 100% 参照，也让环在任何数据下都完整
        ringP.color = COLOR_TRACK
        cv.drawArc(ring, 0f, 360f, false, ringP)

        var start = -90f
        d.slices.forEachIndexed { i, s ->
            val sweep = (s.count.toDouble() / total.toDouble() * 360.0).toFloat()
            val gap = if (d.slices.size > 1) DONUT_GAP_ANGLE else 0f
            ringP.shader = null
            ringP.color = DONUT_PALETTE[i % DONUT_PALETTE.size]
            cv.drawArc(ring, start + gap / 2f, (sweep - gap).coerceAtLeast(1f), false, ringP)
            start += sweep
        }

        // ---- 环心：合计（展示用的是报告给出的数值之和，不做任何改写）----
        val inner = RectF(
            ringLeft + DONUT_RING_W, ringTop + DONUT_RING_W,
            ringLeft + size - DONUT_RING_W, ringTop + size - DONUT_RING_W,
        )
        val sumP = paint(FS_KPI_VALUE, COLOR_TITLE, bold = true)
        val capP = paint(FS_KPI_LABEL, COLOR_META)
        val sumText = truncateToWidth(total.toString(), inner.width(), sumP)
        val sumH = FS_KPI_VALUE + 12f
        val capH = FS_KPI_LABEL + 8f
        val sumTop = inner.centerY() - (sumH + capH) / 2f
        val sumClip = RectF(inner.left, sumTop, inner.right, sumTop + sumH)
        drawClipped(
            cv, sumText, inner.centerX() - sumP.measureText(sumText) / 2f,
            fitBaseline(sumTop, sumH, sumP, minOf(sumClip.bottom, limitBottom)), sumClip, sumP,
        )
        val capClip = RectF(inner.left, sumClip.bottom, inner.right, sumClip.bottom + capH)
        val capText = DONUT_SUM_LABEL
        drawClipped(
            cv, capText, inner.centerX() - capP.measureText(capText) / 2f,
            fitBaseline(capClip.top, capH, capP, minOf(capClip.bottom, limitBottom)), capClip, capP,
        )

        // ---- 图例：色块 + 名称 + 占比 + 原值（右对齐成一条干净的数值列）----
        val legendLeft = (CONTENT_LEFT + DONUT_SIZE + DONUT_LEGEND_GAP).toFloat()
        val labelP = paint(FS_ROW, COLOR_BODY)
        val valueP = paint(FS_ROW, COLOR_TITLE, bold = true)
        val pctP = paint(FS_SMALL, COLOR_META)
        val swatchH = DONUT_LEGEND_SWATCH.toFloat()
        d.slices.forEachIndexed { i, s ->
            val legendTop = rowTop + i * DONUT_LEGEND_ROW_H
            val rowH = DONUT_LEGEND_ROW_H.toFloat()
            val centerY = legendTop + rowH / 2f

            cv.drawRoundRect(
                RectF(
                    legendLeft, centerY - swatchH / 2f,
                    legendLeft + swatchH, centerY + swatchH / 2f,
                ),
                10f, 10f, shapePaint(DONUT_PALETTE[i % DONUT_PALETTE.size]),
            )

            val pctText = formatPercent(s.count, total)
            val valueW = valueP.measureText(s.value)
            val pctW = pctP.measureText(pctText)
            val valueLeft = CONTENT_RIGHT.toFloat() - valueW
            val pctLeft = valueLeft - DONUT_LEGEND_VALUE_GAP - pctW
            val labelLeft = legendLeft + swatchH + DONUT_LEGEND_SWATCH_GAP
            val labelAvailW = pctLeft - CELL_INSET - labelLeft
            val legendBaseline = fitBaseline(legendTop, rowH, labelP, minOf(legendTop + rowH, limitBottom))
            if (labelAvailW > 0f) {
                val labelText = truncateToWidth(s.label, labelAvailW, labelP)
                drawClipped(
                    cv, labelText, labelLeft, legendBaseline,
                    RectF(labelLeft, legendTop, labelLeft + labelAvailW, legendTop + rowH), labelP,
                )
            }
            if (pctW > 0f) {
                drawClipped(
                    cv, pctText, pctLeft, legendBaseline,
                    RectF(pctLeft - 2f, legendTop, valueLeft - DONUT_LEGEND_VALUE_GAP, legendTop + rowH), pctP,
                )
            }
            drawClipped(
                cv, s.value, valueLeft, legendBaseline,
                RectF(valueLeft - 2f, legendTop, CONTENT_RIGHT.toFloat(), legendTop + rowH), valueP,
            )
        }
    }

    /**
     * 柱状图：左侧刻度槽 + 3 条网格线 + 柱体（顶亮底实的渐变，峰值加深描边并标数值）
     * + 均值虚线 + x 轴刻度。柱与标签都被限制在轴内/自己的槽宽内，绝不互相盖压。
     */
    private fun drawColumnChart(
        cv: Canvas,
        chart: Block.ColumnChart,
        rowTop: Float,
        accent: Int,
        limitBottom: Float,
    ) {
        val bars = chart.bars
        if (bars.isEmpty()) return
        val maxV = bars.maxOf { it.count }.coerceAtLeast(1L)
        val plotLeft = (CONTENT_LEFT + CHART_GUTTER_W).toFloat()
        val plotRight = CONTENT_RIGHT.toFloat()
        val plotTop = rowTop + CHART_TOP_PAD
        val baseY = rowTop + CHART_TOP_PAD + CHART_BAR_AREA_H
        val areaH = CHART_BAR_AREA_H.toFloat()

        cv.save()
        cv.clipRect(
            CONTENT_LEFT.toFloat(), rowTop,
            CONTENT_RIGHT.toFloat(), minOf(rowTop + COLUMN_CHART_H, limitBottom),
        )

        // ---- 网格线 + 左侧刻度（3 档：0 / 半 / 满）----
        val gridP = shapePaint(COLOR_RULE)
        val tickP = paint(FS_TICK, COLOR_META)
        for (k in 0..2) {
            val gy = baseY - areaH * (k / 2f)
            if (k > 0) cv.drawRect(RectF(plotLeft, gy, plotRight, gy + 2f), gridP)
            val tickText = ((maxV * k) / 2).toString()
            val tickW = tickP.measureText(tickText)
            val tickClip = RectF(CONTENT_LEFT.toFloat(), gy - 28f, plotLeft - 16f, gy + 28f)
            drawClipped(
                cv, tickText, plotLeft - 16f - tickW,
                fitBaseline(gy - 28f, 56f, tickP, minOf(tickClip.bottom, limitBottom)),
                tickClip, tickP,
            )
        }
        // 基线
        cv.drawRect(RectF(plotLeft, baseY, plotRight, baseY + 2f), gridP)

        // ---- 柱体 ----
        val n = bars.size
        val slot = (plotRight - plotLeft) / n
        val barW = (slot * 0.62f).coerceIn(CHART_BAR_MIN_W, CHART_BAR_MAX_W)
        val peakIndex = bars.indexOfFirst { it.count == maxV }.coerceAtLeast(0)

        // ---- 每列底轨（第 16 轮）----
        // 没有消息的列以前"什么都没有"，和图顶部留白混在一起；铺一条极淡的满高轨道后，
        // 列位是连续的：哪一段是空的、哪一段只是矮，一眼分得开。轨道永远画（含 0 值列），
        // 且固定从绘图区顶部到 0 轴，不随数值变化 —— 它只是参照线，不是数据。
        val railP = shapePaint(blendOnWhite(COLOR_TRACK, CHART_RAIL_ALPHA))
        for (i in 0 until n) {
            val cx = plotLeft + slot * i + slot / 2f
            cv.drawRoundRect(
                RectF(cx - barW / 2f, plotTop, cx + barW / 2f, baseY - CHART_BAR_BASE_GAP),
                barW / 2f,
                barW / 2f,
                railP,
            )
        }

        bars.forEachIndexed { i, b ->
            if (b.count <= 0L) return@forEachIndexed
            val h = areaH * (b.count.toFloat() / maxV.toFloat())
            val cx = plotLeft + slot * i + slot / 2f
            val rect = RectF(cx - barW / 2f, baseY - h, cx + barW / 2f, baseY - CHART_BAR_BASE_GAP)
            cv.drawRoundRect(rect, barW / 2f, barW / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, rect.top, 0f, rect.bottom,
                    intArrayOf(lighten(accent), accent),
                    null, Shader.TileMode.CLAMP,
                )
            })
            if (i == peakIndex) {
                cv.drawRoundRect(rect, barW / 2f, barW / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                })
            }
        }

        // ---- 趋势折线（第 16 轮）----
        // 柱子回答"每一段各有多少"，折线回答"整体在往上还是往下"，两者共用同一套
        // x 槽位与 y 比例，所以线天然穿过柱顶，不会出现"线和柱对不上"的错觉。
        // 柱数 < 3 不画（两点连线不构成趋势）；0 值的柱仍然落在折线上（它是一站），但不点节点。
        if (n >= 3) {
            val cxList = FloatArray(n)
            val cyList = FloatArray(n)
            for (i in 0 until n) {
                cxList[i] = plotLeft + slot * i + slot / 2f
                cyList[i] = baseY - areaH * (bars[i].count.toFloat() / maxV.toFloat())
            }
            val path = Path()
            path.moveTo(cxList[0], cyList[0])
            for (i in 1 until n) path.lineTo(cxList[i], cyList[i])
            cv.drawPath(
                path,
                shapePaint(blendOnWhite(accent, CHART_TREND_ALPHA), stroke = true, strokeWidth = CHART_TREND_LINE_W),
            )
            val nodeP = shapePaint(lighten(accent))
            for (i in 0 until n) {
                if (bars[i].count <= 0L) continue
                cv.drawCircle(cxList[i], cyList[i], CHART_TREND_NODE_R, nodeP)
            }
        }

        // ---- 数值标签（第 14 轮加强）----
        // 旧版只标峰柱；现在**每根能放下标签的柱子都标数值**（峰柱仍是强调色加粗），
        // 一眼就能读出每段的量级，不用再靠眼睛比高度。
        // 两个硬约束：标签夹在本柱的槽宽内（相邻标签不会互相压字），
        // 且整段夹在绘图区内（越不出画布）；槽太窄时只有峰柱保留标签（沿用旧版行为）。
        val peakP = paint(FS_SMALL, accent, bold = true)
        val sideP = paint(FS_SMALL, COLOR_META)
        bars.forEachIndexed { i, b ->
            if (b.count <= 0L) return@forEachIndexed
            val isPeak = i == peakIndex
            val p = if (isPeak) peakP else sideP
            val cx = plotLeft + slot * i + slot / 2f
            val fits = p.measureText(b.value) <= slot - 8f
            if (!fits && !isPeak) return@forEachIndexed
            val label = truncateToWidth(b.value, if (fits) slot - 8f else plotRight - plotLeft, p)
            if (label.isEmpty()) return@forEachIndexed
            val h = areaH * (b.count.toFloat() / maxV.toFloat())
            val labelTop = baseY - h - 12f - FS_SMALL
            drawClipped(
                cv, label, cx - p.measureText(label) / 2f,
                fitBaseline(labelTop, FS_SMALL + 12f, p, minOf(baseY - 4f, limitBottom)),
                if (fits) {
                    RectF(cx - slot / 2f + 2f, rowTop, cx + slot / 2f - 2f, baseY)
                } else {
                    RectF(plotLeft, rowTop, plotRight, baseY)
                },
                p,
            )
        }

        // ---- 均值虚线（手绘短划，不引入 DashPathEffect）----
        val avg = bars.sumOf { it.count }.toDouble() / n.toDouble()
        val avgY = baseY - areaH * (avg / maxV.toDouble()).toFloat()
        if (avgY > plotTop - 1f && avgY < baseY - 1f) {
            drawDashedHLine(cv, plotLeft, plotRight, avgY, shapePaint(withAlpha(COLOR_WARN, 0xC0)))
            // 标注贴着虚线、靠左放（左端通常是低柱，不会被柱体压住）；上方放不下就改放下方
            val avgP = paint(FS_TICK, COLOR_WARN)
            val avgText = truncateToWidth("$AVG_LABEL ${Math.round(avg)}", plotRight - plotLeft, avgP)
            val labelTop = if (avgY - CHART_AVG_LABEL_GAP >= plotTop) avgY - CHART_AVG_LABEL_GAP else avgY + 4f
            val avgClip = RectF(plotLeft, labelTop, plotRight, labelTop + CHART_AVG_LABEL_H)
            drawClipped(
                cv, avgText, plotLeft + 6f,
                fitBaseline(labelTop, CHART_AVG_LABEL_H, avgP, minOf(avgClip.bottom, limitBottom)),
                avgClip, avgP,
            )
        }

        // ---- x 轴刻度：每 CHART_LABEL_EVERY 根标一个，最后一根一定标 ----
        val xP = paint(FS_TICK, COLOR_META)
        val labelY = baseY + CHART_AXIS_GAP
        val labelH = (CHART_AXIS_H - CHART_AXIS_GAP).coerceAtLeast(20)
        bars.forEachIndexed { i, b ->
            if (i % CHART_LABEL_EVERY != 0 && i != n - 1) return@forEachIndexed
            val cx = plotLeft + slot * i + slot / 2f
            val text = truncateToWidth(b.label, slot - 8f, xP)
            drawClipped(
                cv, text, cx - xP.measureText(text) / 2f,
                fitBaseline(labelY, labelH.toFloat(), xP, minOf(labelY + labelH, limitBottom)),
                RectF(cx - slot / 2f, labelY, cx + slot / 2f, labelY + labelH), xP,
            )
        }

        cv.restore()
    }

    /**
     * 活跃热力：7 行（周一…周日）× 24 列（0…23 点）的圆角格阵。
     *
     * 为什么单独画一种图：168 个格子用条形行或柱状图都表达不了（168 根柱子在画布上既排不开也读不出），
     * 热力图才是这个维度的标准读法 —— 行=星期、列=小时、颜色越深消息越多，一眼看出活跃时段。
     *
     * 视觉规范（沿用画布其它图元的同一套语言）：
     *  - 颜色只用「卡片主色 + 固定透明度阶梯」，并且先 [blendOnWhite] 混白再画 ——
     *    半透明色叠在页面渐变背景上会随位置变色，混白后与背景无关（本文件的既有约定）；
     *  - 0 值画轨道色而不是白色：空时段也看得见格子，不会被误读成"漏画"；
     *  - 峰值格只描一圈主色细边，不引入第二种颜色；
     *  - 底部一行 0/6/12/18/23 的小时刻度 + 右侧「少→多」图例，把读法交代清楚。
     *
     * 所有绘制都硬裁剪在卡片正文区内（与其它绘制函数一致），任何测量偏差都不可能画到卡片外。
     */
    private fun drawHeatmap(cv: Canvas, h: Block.Heatmap, rowTop: Float, accent: Int, limitBottom: Float) {
        if (h.rows.isEmpty()) return
        val maxV = h.rows.maxOf { r -> r.values.maxOrNull() ?: 0 }.coerceAtLeast(1)
        val labelP = paint(FS_TICK, COLOR_META)
        val axisP = paint(FS_TICK, COLOR_META)
        val legendP = paint(FS_SMALL, COLOR_META)
        val trackP = shapePaint(COLOR_TRACK)
        val cellP = Paint(Paint.ANTI_ALIAS_FLAG)
        val peakP = shapePaint(accent, stroke = true, strokeWidth = HEAT_PEAK_STROKE)
        val gridLeft = (CONTENT_LEFT + HEAT_LABEL_W + HEAT_LABEL_GAP).toFloat()

        // 峰值格（第一个达到最大值的非 0 格）：只描边、不换色
        var peakRow = -1
        var peakCol = -1
        for ((ri, r) in h.rows.withIndex()) {
            val ci = r.values.indexOfFirst { it == maxV && it > 0 }
            if (ci >= 0) {
                peakRow = ri
                peakCol = ci
                break
            }
        }

        val rowH = HEAT_CELL_H.toFloat()
        var y = rowTop
        for (ri in h.rows.indices) {
            val r = h.rows[ri]
            drawClipped(
                cv, r.label, (CONTENT_LEFT + CELL_INSET).toFloat(),
                fitBaseline(y, rowH, labelP, minOf(y + rowH, limitBottom)),
                RectF(CONTENT_LEFT.toFloat(), y, (CONTENT_LEFT + HEAT_LABEL_W).toFloat(), y + rowH),
                labelP,
            )
            for (ci in 0 until HEAT_COLS) {
                val v = r.values.getOrElse(ci) { 0 }
                val left = gridLeft + ci * (HEAT_CELL_W + HEAT_CELL_GAP)
                val rect = RectF(left, y, left + HEAT_CELL_W, y + rowH)
                if (v <= 0) {
                    cv.drawRoundRect(rect, HEAT_CELL_RADIUS, HEAT_CELL_RADIUS, trackP)
                } else {
                    val f = (v.toFloat() / maxV.toFloat()).coerceIn(0f, 1f)
                    cellP.color = blendOnWhite(
                        accent,
                        HEAT_MIN_ALPHA + ((HEAT_MAX_ALPHA - HEAT_MIN_ALPHA) * f).toInt(),
                    )
                    cv.drawRoundRect(rect, HEAT_CELL_RADIUS, HEAT_CELL_RADIUS, cellP)
                }
                if (ri == peakRow && ci == peakCol) {
                    cv.drawRoundRect(rect, HEAT_CELL_RADIUS, HEAT_CELL_RADIUS, peakP)
                }
            }
            if (ri < h.rows.size - 1) y += rowH + HEAT_CELL_GAP
        }
        y += rowH

        // ---- 小时刻度：与格阵同一套列坐标（末位贴右，不会被裁掉）----
        val axisTop = y + HEAT_AXIS_GAP
        val axisRect = RectF(CONTENT_LEFT.toFloat(), axisTop, CONTENT_RIGHT.toFloat(), axisTop + HEAT_AXIS_H)
        for (hour in HEAT_AXIS_HOURS) {
            val text = hour.toString()
            val w = axisP.measureText(text)
            val cx = gridLeft + hour * (HEAT_CELL_W + HEAT_CELL_GAP) + HEAT_CELL_W / 2f
            val x = (cx - w / 2f).coerceIn(axisRect.left, axisRect.right - w)
            drawClipped(
                cv, text, x,
                fitBaseline(axisTop, HEAT_AXIS_H.toFloat(), axisP, minOf(axisTop + HEAT_AXIS_H, limitBottom)),
                axisRect, axisP,
            )
        }

        // ---- 图例：右对齐的「少 ▢▢▢▢▢ 多」（五级透明度与格阵同一算法）----
        val legendTop = axisTop + HEAT_AXIS_H + HEAT_LEGEND_GAP
        val swTop = legendTop + (HEAT_LEGEND_H - HEAT_LEGEND_SWATCH) / 2f
        val legendRect =
            RectF(CONTENT_LEFT.toFloat(), legendTop, CONTENT_RIGHT.toFloat(), legendTop + HEAT_LEGEND_H)
        val legendBase =
            fitBaseline(legendTop, HEAT_LEGEND_H.toFloat(), legendP, minOf(legendRect.bottom, limitBottom))
        var lx = CONTENT_RIGHT.toFloat() - legendP.measureText(HEAT_LEGEND_MORE)
        drawClipped(cv, HEAT_LEGEND_MORE, lx, legendBase, legendRect, legendP)
        lx -= HEAT_LEGEND_TEXT_GAP
        for (step in HEAT_LEGEND_STEPS - 1 downTo 0) {
            lx -= HEAT_LEGEND_SWATCH
            cellP.color = blendOnWhite(
                accent,
                HEAT_MIN_ALPHA + ((HEAT_MAX_ALPHA - HEAT_MIN_ALPHA) * step) / (HEAT_LEGEND_STEPS - 1),
            )
            cv.drawRoundRect(
                RectF(lx, swTop, lx + HEAT_LEGEND_SWATCH, swTop + HEAT_LEGEND_SWATCH),
                HEAT_LEGEND_SWATCH_RADIUS, HEAT_LEGEND_SWATCH_RADIUS, cellP,
            )
            lx -= HEAT_LEGEND_SWATCH_GAP
        }
        val lessW = legendP.measureText(HEAT_LEGEND_LESS)
        drawClipped(
            cv, HEAT_LEGEND_LESS, (lx - lessW).coerceAtLeast(legendRect.left),
            legendBase, legendRect, legendP,
        )
    }

    /** 标签云：把词频行排成一排排圆角小标签（换行结果由布局阶段算好，这里只负责画） */
    private fun drawChipCloud(
        cv: Canvas,
        chipRows: List<List<String>>,
        rowTop: Float,
        accent: Int,
        limitBottom: Float,
    ) {
        val p = paint(FS_ROW, COLOR_BODY)
        val fillP = shapePaint(blendOnWhite(accent, 0x14))
        val edgeP = shapePaint(blendOnWhite(accent, 0x48), stroke = true, strokeWidth = 2f)
        var lineTop = rowTop
        for (line in chipRows) {
            var x = CONTENT_LEFT.toFloat()
            for (item in line) {
                val w = chipWidth(item, p)
                val rect = RectF(x, lineTop, x + w, lineTop + CHIP_H)
                cv.drawRoundRect(rect, CHIP_H / 2f, CHIP_H / 2f, fillP)
                cv.drawRoundRect(rect, CHIP_H / 2f, CHIP_H / 2f, edgeP)
                val clip = RectF(rect.left + CHIP_PAD_H, rect.top, rect.right - CHIP_PAD_H, rect.bottom)
                if (clip.width() > 0f) {
                    val text = truncateToWidth(item, clip.width(), p)
                    drawClipped(
                        cv, text, clip.left,
                        fitBaseline(rect.top, CHIP_H.toFloat(), p, minOf(rect.bottom, limitBottom)),
                        clip, p,
                    )
                }
                x += w + CHIP_GAP
            }
            lineTop += CHIP_H + CHIP_LINE_GAP
        }
    }

    /**
     * 页脚：顶部渐隐细线 + 元信息行（会话名 · 时间范围 · 生成时间 · 数据来源）+ 左品牌 + 右页码，
     * 底部一条品牌渐变条当水印（分页导出时改成「页码进度条」）。
     *
     * [meta]：分页导出成多张图后，单张图脱离弹窗就"不知道自己是谁、什么时候生成的"，
     * 所以每页都带上归属信息；空串时整行不画（排版位置仍按常量预留，不会影响任何既有坐标）。
     * [hint]：第 20 轮新增。本页是"上一页的续页"时，在元信息行右侧补一句
     * "接上页 · 节名"，让单张图也能自证画的是哪一节的续页；
     * 提示占宽从元信息行里预留，元信息自身会先按剩余宽度省略 —— 两者永不重叠。
     * [pageIndex]/[pageCount]：多页时把底部渐变条换成"页码进度条"，
     * 一眼就能看出这是第几张、总共几张（单张图仍按老样子画整条渐变）。
     */
    private fun drawFooter(
        cv: Canvas,
        top: Int,
        pageLabel: String = FOOTER_PAGE_TEXT,
        meta: String = "",
        hint: String = "",
        pageIndex: Int = 1,
        pageCount: Int = 1,
    ) {
        cv.drawRect(
            RectF(CARD_LEFT.toFloat(), top.toFloat(), CARD_RIGHT.toFloat(), top + 3f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    CARD_LEFT.toFloat(), 0f, CARD_RIGHT.toFloat(), 0f,
                    intArrayOf(
                        withAlpha(COLOR_ACCENT, 0x00),
                        withAlpha(COLOR_ACCENT, 0x4D),
                        withAlpha(COLOR_ACCENT2, 0x00),
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )

        // 元信息行：左对齐的说明文字 + 右侧的「接上页」提示（提示存在时先给提示留宽）
        val metaP = paint(FS_SMALL, COLOR_META)
        val metaH = FOOTER_META_H.toFloat()
        var textTop = (top + FOOTER_TOP_GAP).toFloat()
        if (meta.isNotEmpty()) {
            val hintP = paint(FS_SMALL, COLOR_ACCENT, bold = true)
            val hintText = if (hint.isEmpty()) "" else truncateToWidth(hint, metaH * 9f, hintP)
            val hintW = if (hintText.isEmpty()) 0f else hintP.measureText(hintText) + COL_GAP
            val metaClip = RectF(CARD_LEFT.toFloat(), textTop, CARD_RIGHT.toFloat(), textTop + metaH)
            val metaText = truncateToWidth(meta, (metaClip.width() - hintW).coerceAtLeast(120f), metaP)
            drawClipped(
                cv, metaText, metaClip.left,
                fitBaseline(textTop, metaH, metaP, metaClip.bottom),
                metaClip, metaP,
            )
            if (hintText.isNotEmpty()) {
                val hintLeft = maxOf(metaClip.left, CARD_RIGHT - hintW + COL_GAP)
                val hintClip = RectF(hintLeft, textTop, CARD_RIGHT.toFloat(), textTop + metaH)
                if (hintClip.width() > 0f) {
                    drawClipped(
                        cv, hintText, hintClip.left,
                        fitBaseline(textTop, metaH, hintP, hintClip.bottom),
                        hintClip, hintP,
                    )
                }
            }
            textTop += metaH + FOOTER_META_GAP
        }

        // 页脚内部节奏全部来自常量：上留白 → 元信息行 → 间距 → 文字行 → 间距 → 品牌条，
        // 文字行与品牌条严格分离（避免文字下缘与渐变条压在一起）
        val textH = FOOTER_TEXT_H.toFloat()
        val limitBottom = textTop + textH
        val pageP = paint(FS_SMALL, COLOR_ACCENT, bold = true)
        val pageText = pageLabel
        val pageW = pageP.measureText(pageText)
        val pageClip = RectF(
            CARD_RIGHT - pageW - 8f, textTop,
            CARD_RIGHT.toFloat(), textTop + textH,
        )
        drawClipped(
            cv, pageText, pageClip.left,
            fitBaseline(textTop, textH, pageP, minOf(pageClip.bottom, limitBottom)),
            pageClip, pageP,
        )

        val brandP = paint(FS_SMALL, COLOR_META)
        val brandClip = RectF(
            CARD_LEFT.toFloat(), textTop,
            maxOf(CARD_LEFT.toFloat() + 80f, pageClip.left - COL_GAP), textTop + textH,
        )
        val brandText = truncateToWidth("$BRAND_TEXT · 由 WeKit 本地生成", brandClip.width(), brandP)
        drawClipped(
            cv, brandText, brandClip.left,
            fitBaseline(textTop, textH, brandP, minOf(brandClip.bottom, limitBottom)),
            brandClip, brandP,
        )

        // 底部：单张图 = 品牌渐变条（水印感，收住整张图的下边缘）；
        // 分页导出 = 页码进度条（已画到第几张一目了然，仍收在同一高度里）
        val stripTop = textTop + textH + FOOTER_STRIP_GAP
        if (pageCount > 1) {
            drawFooterProgress(cv, stripTop, pageIndex, pageCount)
        } else {
            cv.drawRoundRect(
                RectF(
                    CARD_LEFT.toFloat(), stripTop,
                    CARD_RIGHT.toFloat(), stripTop + FOOTER_STRIP_H,
                ),
                FOOTER_STRIP_H / 2f, FOOTER_STRIP_H / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        CARD_LEFT.toFloat(), 0f, CARD_RIGHT.toFloat(), 0f,
                        intArrayOf(COLOR_ACCENT, COLOR_ACCENT2, withAlpha(COLOR_SUCCESS, 0xB3)),
                        null, Shader.TileMode.CLAMP,
                    )
                },
            )
        }
    }

    /**
     * 页码进度条：底槽低透明度铺满，已画到的比例用品牌渐变填上。
     *
     * 高度仍取 [FOOTER_STRIP_H]（与单张图的品牌条同高），所以不会撑高页脚、
     * 也不会让 `init` 里的页脚排版断言失效。
     */
    private fun drawFooterProgress(cv: Canvas, top: Float, index: Int, count: Int) {
        val left = CARD_LEFT.toFloat()
        val right = CARD_RIGHT.toFloat()
        val r = FOOTER_STRIP_H / 2f
        cv.drawRoundRect(
            RectF(left, top, right, top + FOOTER_STRIP_H), r, r,
            shapePaint(withAlpha(COLOR_ACCENT, 0x26)),
        )
        val ratio = (index.toFloat() / count.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
        val fillRight = left + (right - left) * ratio
        if (fillRight - left >= FOOTER_STRIP_H) {
            cv.drawRoundRect(
                RectF(left, top, fillRight, top + FOOTER_STRIP_H), r, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        left, 0f, right, 0f,
                        intArrayOf(COLOR_ACCENT, COLOR_ACCENT2, withAlpha(COLOR_SUCCESS, 0xB3)),
                        null, Shader.TileMode.CLAMP,
                    )
                },
            )
        }
    }

    // ==================================================================
    // 七、绘制工具（文本永远不越界）
    // ==================================================================

    /**
     * 画笔缓存（文本用）。
     *
     * 一份报告可能有上百个单元格 / 行，逐个 new Paint 会产生大量临时对象；
     * 宿主堆只有 512MB，导出时还要一次性分配整张位图，所以这里按
     * (字号, 颜色, 是否加粗) 复用同一个 Paint —— 返回的实例只读，调用方不得修改。
     * 用 ConcurrentHashMap 是因为导出可能发生在后台线程。
     */
    private class PaintKey(val size: Float, val color: Int, val bold: Boolean)

    private val textPaints = ConcurrentHashMap<PaintKey, Paint>()

    private fun paint(size: Float, color: Int, bold: Boolean = false): Paint =
        textPaints.getOrPut(PaintKey(size, color, bold)) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                if (bold) typeface = Typeface.DEFAULT_BOLD
                isSubpixelText = true
            }
        }

    /** 纯色 / 描边形状画笔缓存（不含 shader 的形状才可共享） */
    private class ShapeKey(val color: Int, val stroke: Boolean, val strokeWidth: Float)

    private val shapePaints = ConcurrentHashMap<ShapeKey, Paint>()

    private fun shapePaint(color: Int, stroke: Boolean = false, strokeWidth: Float = 0f): Paint =
        shapePaints.getOrPut(ShapeKey(color, stroke, strokeWidth)) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                if (stroke) {
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                }
            }
        }

    /**
     * 行内垂直居中基线，并受容器底边硬约束：
     * 返回值保证 baseline + descent ≤ limitBottom（越界则上移，绝不溢出容器）。
     */
    private fun fitBaseline(rowTop: Float, rowH: Float, p: Paint, limitBottom: Float): Float {
        val fm = p.fontMetrics
        val centered = rowTop + (rowH - fm.descent + fm.ascent) / 2f - fm.ascent
        val maxBaseline = limitBottom - fm.descent
        return if (centered > maxBaseline) maxBaseline else centered
    }

    /** 连续多行文本：逐行推进 y（行高固定），每行硬裁剪在自己的行矩形内 */
    private fun drawTextLines(
        cv: Canvas,
        lines: List<String>,
        x: Float,
        firstTop: Float,
        lineH: Int,
        p: Paint,
        clipRight: Float,
        limitBottom: Float,
    ) {
        var top = firstTop
        for (line in lines) {
            val baseline = fitBaseline(top, lineH.toFloat(), p, minOf(top + lineH, limitBottom))
            val clip = RectF(CONTENT_LEFT.toFloat(), top, clipRight, top + lineH)
            drawClipped(cv, line, x, baseline, clip, p)
            top += lineH
        }
    }

    /** 硬裁剪绘制文本：clipRect 兜底，测量偏差也不可能画到别的列/卡片外 */
    private fun drawClipped(cv: Canvas, text: String, x: Float, baseline: Float, clip: RectF, p: Paint) {
        if (text.isEmpty() || clip.right <= clip.left || clip.bottom <= clip.top) return
        cv.save()
        cv.clipRect(clip)
        cv.drawText(text, maxOf(x, clip.left), baseline, p)
        cv.restore()
    }

    /** 硬裁剪绘制矩形（条形填充用），横向严格限制在列内 */
    private fun drawClippedRect(cv: Canvas, rect: RectF, clipLeft: Float, clipRight: Float, p: Paint) {
        if (clipRight <= clipLeft) return
        cv.save()
        cv.clipRect(clipLeft, rect.top, clipRight, rect.bottom)
        cv.drawRoundRect(rect, BAR_TRACK_H / 2f, BAR_TRACK_H / 2f, p)
        cv.restore()
    }

    /** 手绘虚线（不用 DashPathEffect）：逐段画短矩形，长度精确、不依赖渲染管线 */
    private fun drawDashedHLine(cv: Canvas, left: Float, right: Float, y: Float, p: Paint) {
        var x = left
        while (x < right) {
            val seg = minOf(CHART_DASH_W, right - x)
            cv.drawRect(x, y, x + seg, y + CHART_DASH_H, p)
            x += CHART_DASH_W + CHART_DASH_GAP
        }
    }

    /**
     * 二分求「从 start 起能塞进 maxW 的最长前缀末尾」（依赖 measureText 对 end 单调不减）。
     *
     * 原来是逐个字符回退：每个长段落 O(n) 次 measureText、每次 measureText 又是 O(n)，
     * AI 长段落会把导出明显拖慢。二分后是 O(log n)，结果与回退法完全一致
     * （都是「最长的仍然放得下的前缀」）。
     */
    private fun maxPrefixEnd(text: String, start: Int, maxW: Float, p: Paint): Int {
        var lo = start
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (p.measureText(text, start, mid) <= maxW) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** 按测量宽度换行（不丢字），空段落保留为空行，并对段落收尾做孤字控制 */
    private fun wrapLines(text: String, maxW: Float, p: Paint): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            if (para.isEmpty()) {
                out.add("")
                continue
            }
            val paraStart = out.size
            var start = 0
            while (start < para.length) {
                // 二分出本行能放下的最长前缀（原来是逐字回退，长段落会变慢）
                var end = maxPrefixEnd(para, start, maxW, p)
                if (end == start) end = start + 1
                // 不要把代理对（emoji 等）截成半截字符
                if (end < para.length && Character.isLowSurrogate(para[end])) end++
                out.add(para.substring(start, end))
                start = end
            }
            rebalanceOrphans(out, paraStart, maxW, p)
        }
        return out
    }

    /**
     * 段落收尾的「孤字」控制：末行只剩一两个字符时，从上一行尾部挪 1 个字符下来，
     * 让最后一行不再是孤零零的一个字（中文排版的老毛病，CJK 尤其扎眼）。
     * 只动相邻两行、只搬一个字符、绝不拆代理对，也绝不把上一行搬空。
     */
    private fun rebalanceOrphans(out: MutableList<String>, paraStart: Int, maxW: Float, p: Paint) {
        if (out.size - paraStart < 2) return
        val last = out[out.size - 1]
        if (last.length < 1 || last.length > ORPHAN_MAX_CHARS) return
        val prev = out[out.size - 2]
        var move = 1
        if (prev.length >= 2 && Character.isHighSurrogate(prev[prev.length - 1])) move = 2
        if (prev.length <= move) return
        val merged = prev.substring(prev.length - move) + last
        if (p.measureText(merged) > maxW) return
        out[out.size - 2] = prev.substring(0, prev.length - move)
        out[out.size - 1] = merged
    }

    /** 限定行数的换行：超出部分并入最后一行并按测量宽度省略 */
    private fun wrapLinesLimited(text: String, maxW: Float, p: Paint, maxLines: Int): List<String> {
        val all = wrapLines(text, maxW, p)
        if (maxLines <= 0 || all.size <= maxLines) return all
        val head = all.take(maxLines).toMutableList()
        val rest = all.drop(maxLines).joinToString("")
        head[maxLines - 1] = truncateToWidth(head[maxLines - 1] + rest, maxW, p)
        return head
    }

    /** 超宽则按 Paint.measureText 精确截断并补 "…"（二分定位，不猜宽度、不越出容器） */
    private fun truncateToWidth(text: String, maxW: Float, p: Paint): String {
        if (text.isEmpty() || maxW <= 0f) return if (maxW <= 0f) "" else text
        if (p.measureText(text) <= maxW) return text
        val ellW = p.measureText("…")
        var end = maxPrefixEnd(text, 0, maxW - ellW, p)
        // 不要把代理对（emoji 等）截成半截字符
        if (end in 1 until text.length &&
            Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])
        ) {
            end--
        }
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }

    /** 数值 / 单位拆分："12,345 条" → ("12,345", "条")；非数字开头则整串当数值 */
    private fun splitValueUnit(value: String): Pair<String, String> {
        val t = value.trim()
        if (t.isEmpty()) return "" to ""
        val m = VALUE_UNIT.find(t) ?: return t to ""
        val num = m.groupValues[1].trim()
        val unit = m.groupValues[2].trim()
        if (num.isEmpty()) return t to ""
        return num to unit
    }

    /** 占比文本（一位小数，例如 "68.2%"）：只用于展示，不参与任何判定 */
    private fun formatPercent(part: Long, total: Long): String {
        if (total <= 0L) return ""
        val tenth = Math.round(part.toDouble() * 1000.0 / total.toDouble())
        return "${tenth / 10}.${tenth % 10}%"
    }

    private fun firstGlyph(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return "聊"
        return try {
            String(Character.toChars(t.codePointAt(0)))
        } catch (e: Exception) {
            t.substring(0, 1)
        }
    }

    private fun valueColor(rank: Int): Int = when (rank) {
        1 -> COLOR_RANK_GOLD
        2 -> COLOR_RANK_SILVER
        3 -> COLOR_RANK_BRONZE
        else -> COLOR_ACCENT
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    /**
     * 把 color 以 alpha(0..255) 的强度混到纯白上，得到不透明色。
     * 卡片浅底、浅描边、标签云底色都用它：半透明色叠在页面渐变背景上会随位置变色，
     * 混白后再画就与背景无关，转发到不同 App 里观感一致。
     */
    private fun blendOnWhite(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val r = (255 - (255 - Color.red(color)) * a / 255).coerceIn(0, 255)
        val g = (255 - (255 - Color.green(color)) * a / 255).coerceIn(0, 255)
        val b = (255 - (255 - Color.blue(color)) * a / 255).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun lighten(color: Int): Int = Color.rgb(
        (Color.red(color) + 40).coerceAtMost(255),
        (Color.green(color) + 40).coerceAtMost(255),
        (Color.blue(color) + 40).coerceAtMost(255),
    )

    private fun reportDateText(): String =
        SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA).format(Date())
}
