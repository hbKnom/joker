package dev.joker.features.items.beautify.home_screen_panel.calendar

import java.time.LocalDate

/**
 * 黄历详情 —— 纯算法（表数据来自 [HomeSidePanelCalendarTables]，零 IO），
 * 对应 mical `Almanac2.kt` + `AlmanacTables.kt`，并按 `work/mical-inventory.md` §7 修掉两处缺陷：
 *
 *  1. **冲煞格式**：mical `Almanac2.chongsha` 拼的是「寅日冲猴」（天干地支混用），
 *     真机是「**虎日冲猴**」（两侧都用生肖）。这里用
 *     `CalendarZodiac12[日支] + "日冲" + CalendarZodiac12[(日支 + 6) % 12]`。
 *  2. **星宿方位**：mical 详情页把方位硬编码成「西方」。这里按四象分组取
 *     [CalendarStar28Directions]（角亢氐房心尾箕 = 东方，斗牛女虚危室壁 = 北方，
 *     奎娄胃昴毕觜参 = 西方，井鬼柳星张翼轸 = 南方）。
 *
 * 建除 / 值神按**农历月**起（与 cnlunar、小米一致，已对拍 2026-10-07 = 执 / 青龙）；
 * 农历表超范围时降级用节气月支。
 */

/** 建除十二神的月偏移表（农历月 → 起建位）：`(农历月 - 1 + 2) % 12`。 */
private const val CALENDAR_OFFICER_MONTH_SHIFT = 2

/** 十二值神的月偏移表（索引 = 建除月偏移后的月支下标 → 值神起点）。 */
private val CALENDAR_DAY_GOD_MONTH_OFFSET = intArrayOf(8, 10, 0, 2, 4, 6, 8, 10, 0, 2, 4, 6)

/**
 * 组装黄历详情。
 *
 * [yiJi] 为 `null`（idf 未 init / 越界）时 [HuangliDetail.yi] / [HuangliDetail.ji] 为空列表，
 * 其余字段照常给出（干支 / 胎神 / 纳音 / 建除 / 值神 / 星宿 / 冲煞 / 十二时都是纯算法，任意日期可用）。
 */
internal fun homeSidePanelHuangliDetail(date: LocalDate, yiJi: YiJi?): HuangliDetail {
    val lunar = homeSidePanelLunarInfo(date)
    val dayIndex = homeSidePanelDayGanZhiIndex(date)
    val dayZhi = dayIndex % 12
    val monthZhi = homeSidePanelMonthZhiIndex(date)
    // 建除「起建」用的月支：优先农历月（cnlunar / 小米口径），无农历数据时用节气月支。
    val officerMonthZhi = lunar?.let { (it.month - 1 + CALENDAR_OFFICER_MONTH_SHIFT) % 12 } ?: monthZhi

    val officerIndex = ((dayZhi - officerMonthZhi) % 12 + 12) % 12
    val dayGodIndex = ((dayZhi - CALENDAR_DAY_GOD_MONTH_OFFSET[officerMonthZhi]) % 12 + 12) % 12
    val starIndex = homeSidePanelStar28Index(date)
    val twoHourBitmap = CalendarTwoHourLuckBitmaps[dayIndex]

    val yearGanZhi = homeSidePanelLunarYearGanZhi(date)
    val monthGanZhi = homeSidePanelMonthGanZhi(date)
    val dayGanZhi = homeSidePanelDayGanZhi(date)
    val naYin = CalendarNaYin30[(dayIndex / 2) % CalendarNaYin30.size]
    val jianChu = CalendarOfficers12[officerIndex].toString()
    val star = CalendarStars28[starIndex]
    val direction = CalendarStar28Directions[starIndex]

    val twoHourLuck = (1..12).map { position -> (twoHourBitmap and (1 shl (12 - position))) == 0 }

    return HuangliDetail(
        date = date,
        lunarText = lunar?.text.orEmpty(),
        lunarMonthText = lunar?.monthText.orEmpty(),
        lunarDayText = lunar?.dayText.orEmpty(),
        lunarMonthSizeText = lunar?.monthSizeText.orEmpty(),
        shengXiao = homeSidePanelShengXiao(date),
        yearGanZhi = yearGanZhi,
        monthGanZhi = monthGanZhi,
        dayGanZhi = dayGanZhi,
        yearGanZhiText = yearGanZhi + "年",
        monthGanZhiText = monthGanZhi + "月",
        dayGanZhiText = dayGanZhi + "日",
        threePillarsText = yearGanZhi + "年 " + monthGanZhi + "月 " + dayGanZhi + "日",
        dayGanZhiIndex = dayIndex,
        naYin = naYin,
        wuXingText = naYin + " " + jianChu + "位",
        jianChu = jianChu,
        dayGod = CalendarDayGods12[dayGodIndex],
        star28 = star,
        star28Direction = direction,
        star28Text = direction + star,
        pengZuStem = CalendarPengZuTaboo[dayIndex % 10],
        pengZuBranch = CalendarPengZuTaboo[dayIndex % 12 + 10],
        fetalGod = CalendarFetalPositions[dayIndex],
        chongSha = CalendarZodiac12[dayZhi] + "日冲" + CalendarZodiac12[(dayZhi + 6) % 12],
        twoHourNames = CalendarBranches12.toList(),
        twoHourLuck = twoHourLuck,
        twoHourText = twoHourLuck.map { luck -> if (luck) "吉" else "凶" },
        yi = yiJi?.yi.orEmpty(),
        ji = yiJi?.ji.orEmpty(),
        yiText = yiJi?.yi.orEmpty().joinToString(" "),
        jiText = yiJi?.ji.orEmpty().joinToString(" "),
    )
}

/**
 * 兜底详情：极端情况下（表越界 / 算法失败）也不让 UI 拿到 `null`。
 * 只保证「不会崩」：农历与黄历档位字段为空串，仅日干支用不可能失败的整数运算给出。
 */
internal fun emptyHuangliDetail(date: LocalDate): HuangliDetail {
    val dayIndex = homeSidePanelDayGanZhiIndex(date)
    val dayGanZhi = CalendarStems10[dayIndex % 10] + CalendarBranches12[dayIndex % 12]
    return HuangliDetail(
        date = date,
        lunarText = "",
        lunarMonthText = "",
        lunarDayText = "",
        lunarMonthSizeText = "",
        shengXiao = "",
        yearGanZhi = "",
        monthGanZhi = "",
        dayGanZhi = dayGanZhi,
        yearGanZhiText = "",
        monthGanZhiText = "",
        dayGanZhiText = dayGanZhi + "日",
        threePillarsText = dayGanZhi + "日",
        dayGanZhiIndex = dayIndex,
        naYin = "",
        wuXingText = "",
        jianChu = "",
        dayGod = "",
        star28 = "",
        star28Direction = "",
        star28Text = "",
        pengZuStem = "",
        pengZuBranch = "",
        fetalGod = "",
        chongSha = "",
        twoHourNames = emptyList(),
        twoHourLuck = emptyList(),
        twoHourText = emptyList(),
        yi = emptyList(),
        ji = emptyList(),
        yiText = "",
        jiText = "",
    )
}
