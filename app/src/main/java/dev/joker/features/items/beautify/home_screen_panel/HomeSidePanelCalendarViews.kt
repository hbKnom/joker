package dev.joker.features.items.beautify.home_screen_panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joker.R
import dev.joker.features.items.beautify.home_screen_panel.calendar.HomeSidePanelCalendarData
import dev.joker.features.items.beautify.home_screen_panel.calendar.HuangliDetail
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 负一屏「日历」卡片的 1:1 视图层（第 52 轮，按 mical 源码逐项复刻）。
 *
 * 与旧实现（[HomeSidePanelCalendar.kt] 里的私有 MonthGrid/WeekRow/DayDetail/DayCell/
 * SelectedDateDetail）的区别，全部来自用户自制日历源码逐项核对：
 *  - 月视图：固定 6×7、**格子 62dp**、左侧 **26dp 周号列**（ISO 周）、格内**三行**
 *    （日号 / 节日名或农历 / ≤4 字节日名）、今天**整格蓝底白字**、非本月日号变淡、
 *    行分隔线 1px、6 行；
 *  - 月摘要卡：农历月日 + **月柱·日柱** + **宜**（蓝标）+ **忌**（金标）+「查看黄历详情 ›」；
 *  - 日视图：大日期 + 农历月日 + 日柱 + 宜忌卡 + **倒计时卡（未来 0–180 天前 4 个有名字的节日）**；
 *  - 周视图：7 列日期头 + 时间轴（**渲染顺序照抄源码：9,10,…,23,0,1,…,8**）+ 7×24 空网格
 *    （源码里 `CalEvents` 是死代码，网格本来就是纯占位）；
 *  - 黄历详情：宜/忌竖排标签、72sp 大日号、农历月日、年月日三柱、胎神、彭祖两行、
 *    五行+执位、星宿+四象方位、冲煞、值神、**十二时（子..亥，吉绿/凶红）**。
 *
 * 配色：跟模块 MD3 令牌（用户第 52 轮决定）。只有**吉/凶**保留固定语义色 ——
 * 跟着主题变色就分不出吉凶了。
 */
private val HOME_SIDE_PANEL_CAL_AUSPICIOUS = Color(0xFF2E7D32)
private val HOME_SIDE_PANEL_CAL_INAUSPICIOUS = Color(0xFFCC3333)

/**
 * 农历「八月廿七」这类完整写法。
 *
 * 复用模块**既有**的 `homeSidePanelLunarDate()`（`android.icu` ChineseCalendar，系统农历、含闰月）
 * 与资源里的月份/日期名表 —— 不复刻源码的自研农历表（差一天的坑不值得冒）。
 */
@Composable
private fun homeSidePanelLunarLong(date: LocalDate): String {
    val text = HomeSidePanelLunarDateText(
        prefix = stringResource(R.string.home_side_panel_lunar_prefix),
        leapPrefix = stringResource(R.string.home_side_panel_lunar_leap_prefix),
        separator = stringResource(R.string.home_side_panel_lunar_separator),
        monthNames = stringArrayResource(R.array.home_side_panel_lunar_month_names).asList(),
        dayNames = stringArrayResource(R.array.home_side_panel_lunar_day_names).asList(),
    )
    val lunar = remember(date) { homeSidePanelLunarDate(date.atStartOfDay()) }
    return formatHomeSidePanelLunarDate(lunar, text)
}

/** 月历格子需要显示的全部信息（一次算好，避免每帧查表）。 */
internal data class HomeSidePanelCalendarCellInfo(
    val date: LocalDate,
    val lunarShort: String,
    val festivalName: String?,
    val holidayMark: String?,
    val isToday: Boolean,
    val inCurrentMonth: Boolean,
    val isWeekend: Boolean,
)

/**
 * 组装一个格子的信息（节日名 / 农历 / 休班标）。
 *
 * 节日名优先（与源码 `lshort()` 一致）：有节日就显示节日名，否则显示农历
 * （初一显示「八月」，其余显示「廿七」）。休/班角标独立一行，源码里它优先于第三行节日名。
 */
internal fun homeSidePanelCellInfoOf(
    date: LocalDate,
    month: YearMonth,
    today: LocalDate,
    showFestivalName: Boolean,
    showHolidayMark: Boolean,
): HomeSidePanelCalendarCellInfo {
    val festival = if (showFestivalName) {
        HomeSidePanelCalendarData.festivalName(date)
            ?: HomeSidePanelCalendarData.solarTerm(date)
    } else {
        null
    }
    return HomeSidePanelCalendarCellInfo(
        date = date,
        lunarShort = HomeSidePanelCalendarData.lunarShort(date),
        festivalName = festival,
        holidayMark = if (showHolidayMark) HomeSidePanelCalendarData.holidayMark(date) else null,
        isToday = date == today,
        inCurrentMonth = YearMonth.from(date) == month,
        isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY,
    )
}

/** 月视图：星期表头 + 周号列 + 6×7 格（格高 62dp，行间 1px 分隔线）。 */
@Composable
internal fun HomeSidePanelMonthGridView(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    showFestivalName: Boolean,
    showHolidayMark: Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
    val firstOfMonth = month.atDay(1)
    val leading = (firstOfMonth.dayOfWeek.value + 6) % 7 // ISO：周一是 0
    val gridStart = firstOfMonth.minusDays(leading.toLong())
    val scheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Box(modifier = Modifier.width(26.dp))
            weekdays.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        repeat(6) { row ->
            val rowStart = gridStart.plusDays((row * 7).toLong())
            Row(
                modifier = Modifier.fillMaxWidth().height(62.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 周号列（ISO `WEEK_OF_YEAR`，源码 10sp 灰）
                Text(
                    text = rowStart.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()).toString(),
                    modifier = Modifier.width(26.dp),
                    fontSize = 10.sp,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                for (col in 0 until 7) {
                    val date = rowStart.plusDays(col.toLong())
                    HomeSidePanelMonthCell(
                        info = homeSidePanelCellInfoOf(
                            date = date,
                            month = month,
                            today = today,
                            showFestivalName = showFestivalName,
                            showHolidayMark = showHolidayMark,
                        ),
                        selected = date == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(date) },
                    )
                }
            }
            if (row < 5) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.outlineVariant),
                )
            }
        }
    }
}

/** 单个日期格：三行（日号 / 节日或农历 / 休班或节日名）。 */
@Composable
private fun HomeSidePanelMonthCell(
    info: HomeSidePanelCalendarCellInfo,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dayColor = when {
        info.isToday -> scheme.onPrimary
        !info.inCurrentMonth -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
        info.isWeekend -> scheme.error
        else -> scheme.onSurface
    }
    Box(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (info.isToday) Modifier.background(scheme.primary) else Modifier)
            // 【第 54 轮】选中效果做重一点：以前只有一圈 1dp 描边，截图里几乎看不出来
            // （用户反馈「切换任意日期没有选中效果」）。现在 = 浅主色底 + 主色描边。
            .then(
                if (selected && !info.isToday) {
                    Modifier
                        .background(scheme.primary.copy(alpha = 0.12f))
                        .border(1.5.dp, scheme.primary, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = info.date.dayOfMonth.toString(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = dayColor,
                maxLines = 1,
            )
            // 【第 54 轮】第二行**固定显示农历**，第三行才放节日名。
            // 旧实现第二行是「有节日就显示节日、否则农历」，第三行又画一次节日名 ——
            // 于是 10/8 寒露、10/17 重阳节、10/31 万圣夜都**上下重复两遍**（用户截图指出），
            // 而且有节日的日子反而看不到农历几日。现在两行各司其职，两个问题一起消失。
            Text(
                text = info.lunarShort,
                fontSize = 8.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (info.holidayMark != null) {
                Text(
                    text = info.holidayMark,
                    fontSize = 8.sp,
                    color = scheme.onTertiary,
                    modifier = Modifier
                        .background(if (info.holidayMark == "休") scheme.error else scheme.tertiary)
                        .padding(horizontal = 3.dp),
                    maxLines = 1,
                )
            } else if (info.festivalName != null) {
                Text(
                    // 长节日名（如「辛亥革命纪念日」）也照旧显示，靠省略号收进一格
                    text = info.festivalName,
                    fontSize = 8.sp,
                    color = scheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 月摘要卡：农历月日 + 月柱·日柱 + 宜 + 忌 + 「查看黄历详情 ›」。 */
@Composable
internal fun HomeSidePanelMonthSummaryCard(
    date: LocalDate,
    showYiJi: Boolean,
    onOpenDetail: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val yiJi = HomeSidePanelCalendarData.yiJi(date)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable(onClick = onOpenDetail)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = homeSidePanelLunarLong(date),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
            Text(
                text = "  ${HomeSidePanelCalendarData.monthGanZhi(date)}月 ${HomeSidePanelCalendarData.dayGanZhi(date)}日",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "查看黄历详情 ›",
                fontSize = 12.sp,
                color = scheme.primary,
            )
        }
        if (showYiJi && yiJi != null) {
            HomeSidePanelYiJiLine("宜", yiJi.yi, scheme.primary)
            HomeSidePanelYiJiLine("忌", yiJi.ji, scheme.tertiary)
        }
    }
}

/** 「宜 / 忌」一行：标签 + 词条。 */
@Composable
private fun HomeSidePanelYiJiLine(label: String, words: List<String>, accent: Color) {
    if (words.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.width(18.dp),
        )
        Text(
            text = words.joinToString(" "),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 日视图：大日期 + 农历 + 日柱 + 宜忌卡 + 倒计时。 */
@Composable
internal fun HomeSidePanelDayView(
    date: LocalDate,
    showYiJi: Boolean,
    showCountdown: Boolean,
    onOpenDetail: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val yiJi = HomeSidePanelCalendarData.yiJi(date)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${date.year} / ${date.monthValue} / ${date.dayOfMonth}",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = homeSidePanelLunarLong(date),
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = "${HomeSidePanelCalendarData.dayGanZhi(date)}日",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (showYiJi && yiJi != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HomeSidePanelYiJiLine("宜", yiJi.yi, scheme.primary)
                HomeSidePanelYiJiLine("忌", yiJi.ji, scheme.tertiary)
                Text(
                    text = "查看黄历详情 ›",
                    fontSize = 12.sp,
                    color = scheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDetail),
                    textAlign = TextAlign.End,
                )
            }
        }
        // 【第 54 轮】日视图以前只有「大日期 + 农历 + 日柱 + 宜忌 + 倒计时」，
        // 用户反馈「日视图没有显示对应日期的全部黄历信息」。现在把详情页那 6 格
        // （胎神/彭祖/五行/星宿/冲煞/值神）与十二时吉凶**同步铺在日视图里**，
        // 不用点进详情也看得到完整内容。
        if (showYiJi) {
            HomeSidePanelHuangliFacts(HomeSidePanelCalendarData.huangliDetail(date))
            Text(
                text = "十二时",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
            HomeSidePanelTwoHourRow(HomeSidePanelCalendarData.huangliDetail(date))
        }
        if (showCountdown) {
            val items = HomeSidePanelCalendarData.countdown(date, 4)
            if (items.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.onSurface,
                                )
                                Text(
                                    text = "${HomeSidePanelCalendarData.lunarShort(item.date)} | ${weekdayLabelOf(item.date)}",
                                    fontSize = 12.sp,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = item.label,
                                fontSize = 13.sp,
                                color = scheme.primary,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(scheme.outlineVariant),
                        )
                    }
                }
            }
        }
    }
}

private fun weekdayLabelOf(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

/**
 * 周视图：7 列日期头 + 时间轴网格。
 *
 * 时间轴**渲染顺序照抄源码**：9,10,…,23,0,1,…,8（源码从 9 点起画，不是 0 点起）。
 * 网格里没有任何日程 —— 源码的 `CalEvents` 是死代码（只查 `Events` 未查 `Instances`），
 * 用户也明确「不需要日程功能」，所以这里就是 1:1 的空网格。
 */
@Composable
internal fun HomeSidePanelWeekTimelineView(
    weekStart: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    showFestivalName: Boolean,
    showHolidayMark: Boolean,
    showYiJi: Boolean,
    onSelect: (LocalDate) -> Unit,
    onOpenDetail: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val month = YearMonth.from(weekStart)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.width(46.dp))
            for (col in 0 until 7) {
                val date = weekStart.plusDays(col.toLong())
                val isToday = date == today
                val isSelected = date == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        // 【第 54 轮】周视图以前只高亮「今天」，用户点任何日期**没有任何反馈**。
                        // 现在：今天=实心 primary；选中=primary 描边 + 浅底（两者可同时成立）。
                        .then(if (isToday) Modifier.background(scheme.primary) else Modifier)
                        .then(
                            if (isSelected && !isToday) {
                                Modifier
                                    .background(scheme.primary.copy(alpha = 0.10f))
                                    .border(1.dp, scheme.primary, RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(date) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = date.dayOfMonth.toString(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isToday) scheme.onPrimary else scheme.onSurface,
                    )
                    val info = homeSidePanelCellInfoOf(
                        date = date,
                        month = month,
                        today = today,
                        showFestivalName = showFestivalName,
                        showHolidayMark = showHolidayMark,
                    )
                    Text(
                        text = info.lunarShort,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isToday) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                    if (info.holidayMark != null) {
                        Text(
                            text = info.holidayMark,
                            fontSize = 8.sp,
                            color = scheme.onTertiary,
                            modifier = Modifier
                                .background(if (info.holidayMark == "休") scheme.error else scheme.tertiary)
                                .padding(horizontal = 3.dp),
                        )
                    } else if (info.festivalName != null) {
                        Text(
                            text = info.festivalName,
                            fontSize = 8.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isToday) scheme.onPrimary else scheme.error,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            HOME_SIDE_PANEL_WEEK_HOURS.forEach { hour ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$hour:00",
                        modifier = Modifier.width(46.dp),
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    for (col in 0 until 7) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .border(0.5.dp, scheme.outlineVariant)
                                .clickable { onSelect(weekStart.plusDays(col.toLong())) },
                        )
                    }
                }
            }
        }
        // 【第 54 轮】周视图以前只有一行「选中：X月X日」的纯文字 —— 既没有该日期的农历/干支/宜忌，
        // 也没有进黄历详情的入口。现在与月视图共用同一张摘要卡：选中哪天就同步显示哪天，
        // 并且带「查看黄历详情 ›」入口（用户点名的两个缺口）。
        Text(
            text = "选中：${selected.monthValue}月${selected.dayOfMonth}日（${weekdayLabelOf(selected)}）",
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant,
        )
        HomeSidePanelMonthSummaryCard(
            date = selected,
            showYiJi = showYiJi,
            onOpenDetail = { onOpenDetail(selected) },
        )
    }
}

/** 源码 `axisRow` 的时间行顺序：9..23 再 0..8。 */
private val HOME_SIDE_PANEL_WEEK_HOURS: List<Int> =
    (9..23).toList() + (0..8).toList()

/**
 * 黄历详情页（源码 `HuangLiDetailActivity` 的 13 项）。
 *
 * 与源码的差异只有一处、且是刻意的：源码是**独立 Activity**，而我方负一屏卡片
 * 用**卡内切换**（返回按钮留在卡内）—— 内容与顺序完全一致，不新增跨页路由
 * （避免动 6 处既有路由注册点）。
 */
@Composable
internal fun HomeSidePanelHuangliDetailView(
    date: LocalDate,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val detail = HomeSidePanelCalendarData.huangliDetail(date)
    val yiJi = HomeSidePanelCalendarData.yiJi(date)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "‹",
                fontSize = 18.sp,
                color = scheme.primary,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp),
            )
            Text(
                text = "黄历 ${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
        }
        if (yiJi != null) {
            HomeSidePanelDetailYiJi("宜", yiJi.yi, scheme.primary)
            HomeSidePanelDetailYiJi("忌", yiJi.ji, scheme.tertiary)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = date.dayOfMonth.toString(),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = homeSidePanelLunarLong(date),
                fontSize = 26.sp,
                color = scheme.onSurface,
            )
        }
        Text(
            text = detail.threePillarsText,
            fontSize = 13.sp,
            color = scheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainer)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HomeSidePanelHuangliFacts(detail)
        }
        Text(
            text = "十二时",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
        )
        // 【第 54 轮】十二时改成**横排**：以前 12 行竖排要占满一屏（用户截图指出「竖着排列占据空间」）。
        // 现在一行 12 格（地支在上、吉/凶在下），整块高度约 34dp，一屏就能看全。
        HomeSidePanelTwoHourRow(detail)
    }
}

/**
 * 黄历六格（胎神 / 彭祖 / 五行 / 星宿 / 冲煞 / 值神）。
 *
 * 抽出来给**详情页**与**日视图**共用 —— 两处显示内容必须完全一致（用户要求日视图也能看到全部信息）。
 */
@Composable
internal fun HomeSidePanelHuangliFacts(detail: HuangliDetail) {
    HomeSidePanelDetailRow("胎神", detail.fetalGod)
    HomeSidePanelDetailRow("彭祖", "${detail.pengZuStem}　${detail.pengZuBranch}")
    HomeSidePanelDetailRow("五行", "${detail.naYin} ${detail.jianChu}位")
    HomeSidePanelDetailRow("星宿", "${detail.star28Direction}${detail.star28}")
    HomeSidePanelDetailRow("冲煞", detail.chongSha)
    HomeSidePanelDetailRow("值神", detail.dayGod)
}

/**
 * 十二时吉凶：**横排** 12 格（子..亥），每格上下两行（地支 + 吉/凶）。
 *
 * 吉/凶沿用固定语义色（跟主题变色就分不出吉凶）。
 */
@Composable
internal fun HomeSidePanelTwoHourRow(detail: HuangliDetail) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        detail.twoHourNames.forEachIndexed { index, branch ->
            val auspicious = detail.twoHourLuck.getOrNull(index) == true
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = branch,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = detail.twoHourText.getOrNull(index) ?: if (auspicious) "吉" else "凶",
                    fontSize = 11.sp,
                    color = if (auspicious) HOME_SIDE_PANEL_CAL_AUSPICIOUS else HOME_SIDE_PANEL_CAL_INAUSPICIOUS,
                )
            }
        }
    }
}

/** 详情页的「宜/忌」：竖排标签 + 词条。 */
@Composable
private fun HomeSidePanelDetailYiJi(label: String, words: List<String>, accent: Color) {
    if (words.isEmpty()) return
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.12f))
                .padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = words.joinToString(" "),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 10.dp, top = 6.dp),
        )
    }
}

@Composable
private fun HomeSidePanelDetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
