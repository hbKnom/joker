package dev.joker.features.items.beautify.home_screen_panel.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sin

/**
 * 农历 / 干支 / 节气 —— 纯算法，零 IO（表数据全部来自 [HomeSidePanelCalendarTables] 的内联常量）。
 *
 * 来源：mical `Lunar.kt`（农历 CODES/SPRING）、`Almanac2.kt`（日干支 & 星宿锚点），
 * 并按 `work/mical-inventory.md` §7 修掉「照抄必错」的两处：
 *
 *  1. **戊/癸年五虎遁**：mical `Lunar.monthGz = (2*年干 + 月 + 1) % 10` 是近似式，戊/癸年正月会算错。
 *     这里用标准五虎遁（`甲己丙作首 / 乙庚戊为头 / 丙辛庚上起 / 丁壬壬位流 / 戊癸甲寅求`）
 *     ＋**节气定月**（月支由 12 个「节」的日期决定，不是由公历月推算）。
 *  2. 月支定月的**天粒度**：以节气时刻所在的**北京时间日历日**为界，当日即换月。
 *     （既有 `HomeSidePanelAlmanac.kt` 用「日期 00:00 视为 UTC」比较 ⇒ 节气当天若发生在
 *     北京时间 08:00 前，它会晚一天换月；本实现按日历日切换。）
 *
 * 已验证锚点（`tools/kt_logic_check.py`，ALL PASS）：
 *  - 2026-10-07 日干支 = 甲寅（`dayGanZhiIndex` 锚 2019-01-29 = 丙寅，序号 50）
 *  - 2026-10-07 农历 = 八月廿七（`CalendarLunarCodes` 走月）
 *  - 2026-10-07 月柱 = 丁酉（酉月；寒露 2026-10-08 14:25 CST 才换戌月）
 *  - 2026-10-08 = 寒露（节气名命中）
 *  - 农历表有效区间 1900-01-31 .. 2100 年春节后一年内
 */

/** 农历信息（纯算法结果）。 */
internal data class HomeSidePanelLunarInfo(
    /** 农历年（春节为界，如 2026-10-07 → 2026）。 */
    val lunarYear: Int,
    /** 农历月 1..12。 */
    val month: Int,
    /** 农历日 1..30。 */
    val day: Int,
    val isLeapMonth: Boolean,
    /** 月名（含「月」字与闰前缀）：`八月` / `闰八月`。 */
    val monthText: String,
    /** 日名：`廿七` / `初一`。 */
    val dayText: String,
    /** `八月廿七`。 */
    val text: String,
    /** 该农历月大小：`大`（30 天）/ `小`（29 天）。 */
    val monthSizeText: String,
    /** 农历年干支：`丙午`。 */
    val yearGanZhi: String,
    /** 日干支：`甲寅`。 */
    val dayGanZhi: String,
    /** 生肖：`马`。 */
    val shengXiao: String,
)

/** 农历表基准日（`CalendarLunarSpring[0]` = 1900-01-31）。 */
private val CALENDAR_LUNAR_BASE: LocalDate = LocalDate.of(1900, 1, 1)

/** 日干支锚点：2019-01-29 = 丙寅（序号 2）。 */
private val CALENDAR_DAY_ANCHOR: LocalDate = LocalDate.of(2019, 1, 29)

/** 二十八宿锚点：2019-01-17 = 角木蛟（序号 0）。 */
private val CALENDAR_STAR_ANCHOR: LocalDate = LocalDate.of(2019, 1, 17)

/** 节气按**北京时间**分日（黄历惯例）。 */
private val CALENDAR_TERM_ZONE: ZoneOffset = ZoneOffset.ofHours(8)

/** 12 个「节」在 [CalendarSolarTermNames24] 中的下标，按时间先后。 */
private val CALENDAR_JIE_TERM_INDEX = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22)

/** 上述 12 个「节」对应的月支下标（小寒→丑、立春→寅、…、大雪→子）。 */
private val CALENDAR_JIE_MONTH_ZHI = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0)

/** 五虎遁：年干 % 5 → 正月（寅月）天干的 [CalendarStems10] 下标。 */
private val CALENDAR_FIRST_MONTH_GAN = intArrayOf(2, 4, 6, 8, 0)

/** 按年缓存的 24 节气时刻（UTC 毫秒）。 */
private val CALENDAR_TERM_MILLIS = ConcurrentHashMap<Int, LongArray>()

/** 农历表某年的春节（正月初一）距 1900-01-01 的天数。 */
private fun calendarSpringDay(index: Int): Long {
    val packed = CalendarLunarSpring[index.coerceIn(0, CalendarLunarSpring.size - 1)]
    return LocalDate.of(packed / 10000, packed / 100 % 100, packed % 100).toEpochDay() -
        CALENDAR_LUNAR_BASE.toEpochDay()
}

/**
 * 农历日期；超出表覆盖范围（早于 1900-01-31 或晚于 2100 年春节后一年）返回 `null`。
 */
internal fun homeSidePanelLunarInfo(date: LocalDate): HomeSidePanelLunarInfo? = runCatching {
    val offset = date.toEpochDay() - CALENDAR_LUNAR_BASE.toEpochDay()
    if (offset < calendarSpringDay(0) || offset >= calendarSpringDay(200) + 366) return null
    var lunarYear = 2100
    for (i in 0 until 200) {
        if (offset >= calendarSpringDay(i) && offset < calendarSpringDay(i + 1)) {
            lunarYear = 1900 + i
            break
        }
    }
    var rest = (offset - calendarSpringDay(lunarYear - 1900)).toInt()
    val code = CalendarLunarCodes[(lunarYear - 1900).coerceIn(0, CalendarLunarCodes.size - 1)]
    val leapMonth = code and 0xF
    var month = 0
    var isLeap = false
    var day = 0
    var candidate = 1
    while (candidate <= 12) {
        val monthSize = if ((code shr (15 - (candidate - 1))) and 1 == 1) 30 else 29
        if (rest < monthSize) {
            month = candidate
            isLeap = false
            day = rest + 1
            break
        }
        rest -= monthSize
        if (leapMonth == candidate) {
            if (rest < 29) {
                month = candidate
                isLeap = true
                day = rest + 1
                break
            }
            rest -= 29
        }
        candidate++
    }
    if (month == 0) {
        month = 12
        day = rest + 1
    }
    month = month.coerceIn(1, 12)
    day = day.coerceIn(1, 30)
    val monthSizeText = if ((code shr (15 - (month - 1))) and 1 == 1) "大" else "小"
    val monthName = CalendarLunarMonthNames[month - 1]
    val monthText = (if (isLeap) "闰" else "") + monthName + "月"
    val dayText = CalendarLunarDayNames[day]
    val yearGan = ((lunarYear - 4) % 10 + 10) % 10
    val yearZhi = ((lunarYear - 4) % 12 + 12) % 12
    val dayIndex = homeSidePanelDayGanZhiIndex(date)
    HomeSidePanelLunarInfo(
        lunarYear = lunarYear,
        month = month,
        day = day,
        isLeapMonth = isLeap,
        monthText = monthText,
        dayText = dayText,
        text = monthText + dayText,
        monthSizeText = monthSizeText,
        yearGanZhi = CalendarStems10[yearGan] + CalendarBranches12[yearZhi],
        dayGanZhi = CalendarStems10[dayIndex % 10] + CalendarBranches12[dayIndex % 12],
        shengXiao = CalendarZodiac12[yearZhi],
    )
}.getOrNull()

/**
 * 月格第二行用的农历简写：初一 → `八月`（含闰前缀），其余 → `廿七`；超范围返回 `""`。
 *
 * （与 mical `miniLunar()` 逐字一致。）
 */
internal fun homeSidePanelLunarShort(date: LocalDate): String {
    val info = homeSidePanelLunarInfo(date) ?: return ""
    return if (info.day == 1) info.monthText else info.dayText
}

/** 日干支序号 0..59（锚 2019-01-29 = 丙寅 ⇒ 该日序号 2）。任意日期均有定义。 */
internal fun homeSidePanelDayGanZhiIndex(date: LocalDate): Int {
    val apart = date.toEpochDay() - CALENDAR_DAY_ANCHOR.toEpochDay()
    return (((apart + 2) % 60 + 60) % 60).toInt()
}

/** 日干支：`甲寅`。 */
internal fun homeSidePanelDayGanZhi(date: LocalDate): String {
    val index = homeSidePanelDayGanZhiIndex(date)
    return CalendarStems10[index % 10] + CalendarBranches12[index % 12]
}

/** 二十八宿序号 0..27（锚 2019-01-17 = 角木蛟）。 */
internal fun homeSidePanelStar28Index(date: LocalDate): Int {
    val apart = date.toEpochDay() - CALENDAR_STAR_ANCHOR.toEpochDay()
    return (((apart % 28) + 28) % 28).toInt()
}

/**
 * 年干支（**立春定年**，用于月柱五虎遁）：立春前算上一年。任意日期均有定义。
 */
internal fun homeSidePanelGanZhiYearIndex(date: LocalDate): Int {
    var year = date.year
    if (date < homeSidePanelSolarTermDate(year, 2)) year -= 1
    val index = (year - 4) % 60
    return if (index < 0) index + 60 else index
}

/** 农历年干支（**春节定年**，与 mical `Lunar.Info.yearGz` / 小米详情页「丙午年」一致）；
 * 农历表超范围时降级为立春定年。
 */
internal fun homeSidePanelLunarYearGanZhi(date: LocalDate): String {
    val year = homeSidePanelGanZhiYear(date)
    val gan = ((year - 4) % 10 + 10) % 10
    val zhi = ((year - 4) % 12 + 12) % 12
    return CalendarStems10[gan] + CalendarBranches12[zhi]
}

/** 干支年号（春节定年；超表范围降级到立春定年）。 */
private fun homeSidePanelGanZhiYear(date: LocalDate): Int {
    homeSidePanelLunarInfo(date)?.let { return it.lunarYear }
    var year = date.year
    if (date < homeSidePanelSolarTermDate(year, 2)) year -= 1
    return year
}

/** 生肖（与农历年干支同步）：`马`。 */
internal fun homeSidePanelShengXiao(date: LocalDate): String {
    val zhi = ((homeSidePanelGanZhiYear(date) - 4) % 12 + 12) % 12
    return CalendarZodiac12[zhi]
}

/**
 * 节气定月得到的月支下标（子 0 … 亥 11）。
 *
 * 规则：取本年内**最后一个不晚于 [date] 的「节」**对应的月支；若本年内还没有任何「节」
 * （即 1 月 1 日 ~ 小寒之间），则是上一年大雪之后的**子月**。
 */
internal fun homeSidePanelMonthZhiIndex(date: LocalDate): Int {
    var zhi = 0
    for (i in 0 until CALENDAR_JIE_TERM_INDEX.size) {
        if (date >= homeSidePanelSolarTermDate(date.year, CALENDAR_JIE_TERM_INDEX[i])) {
            zhi = CALENDAR_JIE_MONTH_ZHI[i]
        } else {
            break
        }
    }
    return zhi
}

/** 月柱干支（标准五虎遁 + 节气定月）：`丁酉`。 */
internal fun homeSidePanelMonthGanZhi(date: LocalDate): String {
    val monthZhi = homeSidePanelMonthZhiIndex(date)
    val firstGan = CALENDAR_FIRST_MONTH_GAN[homeSidePanelGanZhiYearIndex(date) % 5]
    val offset = ((monthZhi - 2) % 12 + 12) % 12
    return CalendarStems10[(firstGan + offset) % 10] + CalendarBranches12[monthZhi]
}

/**
 * 该日节气名（24 节气之一）；不是节气日返回 `null`。
 *
 * 这是**天文算法**结果（太阳黄经），任意年份可用，不依赖 assets 里 2026/2027 的静态表。
 */
internal fun homeSidePanelSolarTermName(date: LocalDate): String? = runCatching {
    val millis = calendarTermMillis(date.year)
    for (i in 0 until 24) {
        if (calendarTermDate(millis[i]) == date) return CalendarSolarTermNames24[i]
    }
    null
}.getOrNull()

/** 该年某节气（0 = 小寒 … 23 = 冬至，顺序同 [CalendarSolarTermNames24]）所在的北京日历日。 */
internal fun homeSidePanelSolarTermDate(year: Int, index: Int): LocalDate =
    calendarTermDate(calendarTermMillis(year)[index.coerceIn(0, 23)])

private fun calendarTermDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atOffset(CALENDAR_TERM_ZONE).toLocalDate()

private fun calendarTermMillis(year: Int): LongArray = CALENDAR_TERM_MILLIS.getOrPut(year) {
    LongArray(24) { index -> calendarSolarTermMillis(year, index) }
}

private fun calendarJdeFromUnix(seconds: Double): Double = seconds / 86400.0 + 2440587.5

private fun calendarUnixFromJde(jde: Double): Double = (jde - 2440587.5) * 86400.0

/** 视太阳黄经（度）。 */
private fun calendarSolarLongitude(jde: Double): Double {
    val t = (jde - 2451545.0) / 36525.0
    val meanLongitude = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
    val meanAnomaly = Math.toRadians(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
    val center = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(meanAnomaly) +
        (0.019993 - 0.000101 * t) * sin(2 * meanAnomaly) +
        0.000289 * sin(3 * meanAnomaly)
    return ((meanLongitude + center) % 360.0 + 360.0) % 360.0
}

/** 某年第 [index] 个节气的精确时刻（UTC 毫秒），二分法求解太阳黄经 = 285° + 15°·index。 */
private fun calendarSolarTermMillis(year: Int, index: Int): Long = runCatching {
    val target = (285 + index * 15) % 360.0
    val approximate = LocalDateTime.of(
        year,
        CalendarSolarTermRefMonth[index].coerceIn(1, 12),
        CalendarSolarTermRefDay[index].coerceIn(1, 28),
        0,
        0,
        0,
    ).toInstant(ZoneOffset.UTC).toEpochMilli().toDouble() / 1000.0
    var low = calendarJdeFromUnix(approximate - 3 * 86400.0)
    var high = calendarJdeFromUnix(approximate + 3 * 86400.0)
    var lowValue = calendarTermDelta(low, target)
    repeat(60) {
        val middle = (low + high) / 2
        val middleValue = calendarTermDelta(middle, target)
        if (lowValue * middleValue > 0) {
            low = middle
            lowValue = middleValue
        } else {
            high = middle
        }
    }
    (calendarUnixFromJde((low + high) / 2) * 1000.0).toLong()
}.getOrElse {
    // 极端年份（二分窗口失效）时退化为近似日期，保证「绝不抛异常」这条硬约束。
    LocalDateTime.of(
        year,
        CalendarSolarTermRefMonth[index].coerceIn(1, 12),
        CalendarSolarTermRefDay[index].coerceIn(1, 28),
        0,
        0,
    ).toInstant(ZoneOffset.UTC).toEpochMilli()
}

private fun calendarTermDelta(jde: Double, target: Double): Double {
    var difference = (calendarSolarLongitude(jde) - target) % 360.0
    if (difference > 180) difference -= 360
    return difference
}
