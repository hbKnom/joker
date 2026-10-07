package dev.joker.features.items.beautify.home_screen_panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.joker.R
import dev.joker.features.items.beautify.home_screen_panel.calendar.HomeSidePanelCalendarData
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

internal enum class HomeSidePanelCalendarMode { MONTH, WEEK, DAY }

private val HOME_SIDE_PANEL_CALENDAR_MONTH_TITLE =
    DateTimeFormatter.ofPattern("yyyy/MM")

private val HOME_SIDE_PANEL_CALENDAR_FULL_DATE =
    DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE")

@Composable
internal fun HomeSidePanelCalendarCard(
    card: CalendarCardConfig,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
    backgroundImageFile: Path? = null,
) {
    var viewMode by remember { mutableStateOf(HomeSidePanelCalendarMode.MONTH) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var monthCursor by remember { mutableStateOf(YearMonth.now()) }
    var weekCursor by remember {
        mutableStateOf(LocalDate.now().with(DayOfWeek.MONDAY))
    }
    // 【第 52 轮】黄历详情：源码是独立 Activity，这里改成**卡内切换**（内容与顺序一致，
    // 不动既有 6 处路由注册点）。
    var detailDate by remember { mutableStateOf<LocalDate?>(null) }
    val context = LocalContext.current
    var assetsReady by remember { mutableStateOf(false) }
    // assets（黄历 idf / 节日表）只在后台线程读一次：模块铁律是主线程不做 IO。
    LaunchedEffect(context) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            HomeSidePanelCalendarData.init(context)
        }
        assetsReady = true
    }

    val navigatePrevious = {
        when (viewMode) {
            HomeSidePanelCalendarMode.MONTH -> monthCursor = monthCursor.minusMonths(1)
            HomeSidePanelCalendarMode.WEEK -> weekCursor = weekCursor.minusWeeks(1)
            HomeSidePanelCalendarMode.DAY -> selectedDate = selectedDate.minusDays(1)
        }
    }
    val navigateNext = {
        when (viewMode) {
            HomeSidePanelCalendarMode.MONTH -> monthCursor = monthCursor.plusMonths(1)
            HomeSidePanelCalendarMode.WEEK -> weekCursor = weekCursor.plusWeeks(1)
            HomeSidePanelCalendarMode.DAY -> selectedDate = selectedDate.plusDays(1)
        }
    }
    val goToday = {
        val today = LocalDate.now()
        selectedDate = today
        when (viewMode) {
            HomeSidePanelCalendarMode.MONTH -> monthCursor = YearMonth.from(today)
            HomeSidePanelCalendarMode.WEEK -> weekCursor = today.with(DayOfWeek.MONDAY)
            HomeSidePanelCalendarMode.DAY -> Unit
        }
    }
    val switchMode = { mode: HomeSidePanelCalendarMode ->
        viewMode = mode
        when (mode) {
            HomeSidePanelCalendarMode.MONTH -> monthCursor = YearMonth.from(selectedDate)
            HomeSidePanelCalendarMode.WEEK -> {
                weekCursor = selectedDate.with(DayOfWeek.MONDAY)
            }

            HomeSidePanelCalendarMode.DAY -> Unit
        }
    }

    // 【第 52 轮】标题格式照抄源码：月 `%d年%d月`、周 `%d年%d月 第%d周`、日 `%d-%02d-%02d`。
    val title = when (viewMode) {
        HomeSidePanelCalendarMode.MONTH -> "${monthCursor.year}年${monthCursor.monthValue}月"

        HomeSidePanelCalendarMode.WEEK -> {
            val weekOfMonth = (weekCursor.dayOfMonth - 1) / 7 + 1
            "${weekCursor.year}年${weekCursor.monthValue}月 第${weekOfMonth}周"
        }

        HomeSidePanelCalendarMode.DAY ->
            "%d-%02d-%02d".format(selectedDate.year, selectedDate.monthValue, selectedDate.dayOfMonth)
    }

    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
        // The background lives on the card frame, i.e. outside the `when (viewMode)` switch below,
        // so month / week / day all share one identical background and switching modes never
        // re-decodes or drops it.
        backgroundImageFile = backgroundImageFile,
        backgroundImageAlpha = card.backgroundImageAlpha,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CalendarNavButton("◀") { navigatePrevious() }
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                CalendarNavButton("▶") { navigateNext() }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = goToday)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.home_side_panel_calendar_today),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CalendarModeChip(
                    label = stringResource(R.string.home_side_panel_calendar_month),
                    selected = viewMode == HomeSidePanelCalendarMode.MONTH,
                    onClick = { switchMode(HomeSidePanelCalendarMode.MONTH) },
                    modifier = Modifier.weight(1f),
                )
                CalendarModeChip(
                    label = stringResource(R.string.home_side_panel_calendar_week),
                    selected = viewMode == HomeSidePanelCalendarMode.WEEK,
                    onClick = { switchMode(HomeSidePanelCalendarMode.WEEK) },
                    modifier = Modifier.weight(1f),
                )
                CalendarModeChip(
                    label = stringResource(R.string.home_side_panel_calendar_day),
                    selected = viewMode == HomeSidePanelCalendarMode.DAY,
                    onClick = { switchMode(HomeSidePanelCalendarMode.DAY) },
                    modifier = Modifier.weight(1f),
                )
            }

            val openDetail = { date: LocalDate -> detailDate = date }
            val shownDetail = detailDate
            if (shownDetail != null) {
                // 黄历详情页（源码 13 项；卡内切换，返回留在卡里）
                // 【第 55 轮】把 ready 作为入参传给详情页（不再是「就绪才渲染」）——
                // 数据晚一步就绪时子组件也能重组，宜忌/六格/十二时不会停在空值。
                HomeSidePanelHuangliDetailView(shownDetail, assetsReady) { detailDate = null }
            } else {
                when (viewMode) {
                    HomeSidePanelCalendarMode.MONTH -> {
                        HomeSidePanelMonthGridView(
                            month = monthCursor,
                            selected = selectedDate,
                            today = LocalDate.now(),
                            showFestivalName = card.showFestivalName,
                            showHolidayMark = card.showHolidayMark,
                            ready = assetsReady,
                            onSelect = { selectedDate = it },
                        )
                        HomeSidePanelMonthSummaryCard(
                            date = selectedDate,
                            showYiJi = card.showYiJi,
                            ready = assetsReady,
                            onOpenDetail = { openDetail(selectedDate) },
                        )
                    }

                    HomeSidePanelCalendarMode.WEEK -> HomeSidePanelWeekTimelineView(
                        weekStart = weekCursor,
                        selected = selectedDate,
                        today = LocalDate.now(),
                        showFestivalName = card.showFestivalName,
                        showHolidayMark = card.showHolidayMark,
                        showYiJi = card.showYiJi,
                        ready = assetsReady,
                        onSelect = { selectedDate = it },
                        onOpenDetail = { openDetail(it) },
                    )

                    HomeSidePanelCalendarMode.DAY -> HomeSidePanelDayView(
                        date = selectedDate,
                        showYiJi = card.showYiJi,
                        showCountdown = card.showCountdown,
                        ready = assetsReady,
                        onOpenDetail = { openDetail(selectedDate) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarNavButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CalendarModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun WeekdayHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        val days = listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY,
        )
        days.forEach { day ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = day.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        WeekdayHeader()
        val firstDay = month.atDay(1)
        val leadingBlanks = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value).mod(7)
        val daysInMonth = month.lengthOfMonth()
        val totalCells = leadingBlanks + daysInMonth
        val rows = (totalCells + 6) / 7
        repeat(rows) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val index = row * 7 + col
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (index >= leadingBlanks && index < leadingBlanks + daysInMonth) {
                            val date = firstDay.plusDays((index - leadingBlanks).toLong())
                            DayCell(
                                day = date.dayOfMonth,
                                selected = date == selectedDate,
                                isToday = date == LocalDate.now(),
                                isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY ||
                                    date.dayOfWeek == DayOfWeek.SUNDAY,
                                onClick = { onSelect(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekRow(
    weekStart: LocalDate,
    selectedDate: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            repeat(7) { offset ->
                val date = weekStart.plusDays(offset.toLong())
                val day = date.dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.NARROW,
                    java.util.Locale.getDefault(),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(date) }
                        .background(
                            if (date == selectedDate) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        )
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (date == selectedDate) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (date == selectedDate) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayDetail(date: LocalDate) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = date.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL,
                java.util.Locale.getDefault(),
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DayCell(
    day: Int,
    selected: Boolean,
    isToday: Boolean,
    isWeekend: Boolean,
    onClick: () -> Unit,
) {
    val cellBg = when {
        selected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primaryContainer
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val textColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        isWeekend -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(cellBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = day.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
        )
    }
}

@Composable
private fun SelectedDateDetail(
    date: LocalDate,
    showLunar: Boolean,
) {
    val lunarDate = if (showLunar) {
        remember(date) { homeSidePanelLunarDate(date.atStartOfDay()) }
    } else {
        null
    }
    val lunarText = lunarDate?.let {
        formatHomeSidePanelLunarDate(
            date = it,
            text = HomeSidePanelLunarDateText(
                prefix = stringResource(R.string.home_side_panel_lunar_prefix),
                leapPrefix = stringResource(R.string.home_side_panel_lunar_leap_prefix),
                separator = stringResource(R.string.home_side_panel_lunar_separator),
                monthNames = stringArrayResource(R.array.home_side_panel_lunar_month_names).asList(),
                dayNames = stringArrayResource(R.array.home_side_panel_lunar_day_names).asList(),
            ),
        )
    }
    val almanac = remember(date) { homeSidePanelAlmanac(date.atStartOfDay()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = date.format(HOME_SIDE_PANEL_CALENDAR_FULL_DATE),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (lunarText != null) {
            Text(
                text = lunarText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val tags = buildList {
            almanac.solarTerm?.let { add(it) }
            almanac.festival?.let { add(it) }
        }
        if (tags.isNotEmpty()) {
            Text(
                text = tags.joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            text = stringResource(
                R.string.home_side_panel_calendar_ganzhi,
                almanac.yearGanZhi,
                almanac.shengXiao,
                almanac.monthGanZhi,
                almanac.dayGanZhi,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.home_side_panel_calendar_jianchu,
                almanac.jianChu,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.home_side_panel_calendar_yiji,
                almanac.yi,
                almanac.ji,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}