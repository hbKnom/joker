package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
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

    val title = when (viewMode) {
        HomeSidePanelCalendarMode.MONTH ->
            monthCursor.atDay(1).format(HOME_SIDE_PANEL_CALENDAR_MONTH_TITLE)

        HomeSidePanelCalendarMode.WEEK -> {
            val end = weekCursor.plusDays(6)
            "${weekCursor.format(HOME_SIDE_PANEL_CALENDAR_MONTH_TITLE)} - ${
                end.format(HOME_SIDE_PANEL_CALENDAR_MONTH_TITLE)
            }"
        }

        HomeSidePanelCalendarMode.DAY -> selectedDate.format(HOME_SIDE_PANEL_CALENDAR_MONTH_TITLE)
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

            when (viewMode) {
                HomeSidePanelCalendarMode.MONTH -> MonthGrid(
                    month = monthCursor,
                    selectedDate = selectedDate,
                    onSelect = { selectedDate = it },
                )

                HomeSidePanelCalendarMode.WEEK -> WeekRow(
                    weekStart = weekCursor,
                    selectedDate = selectedDate,
                    onSelect = { selectedDate = it },
                )

                HomeSidePanelCalendarMode.DAY -> DayDetail(date = selectedDate)
            }

            SelectedDateDetail(date = selectedDate, showLunar = card.showLunarCalendar)
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