package dev.joker.features.items.beautify.home_screen_panel.calendar

import java.time.LocalDate

/**
 * 【第 52 轮】节日名的**算法兜底**（补静态表的边界）。
 *
 * 为什么需要：随包的 `cn_festivals.json` 实测只覆盖 `2026-04-01 .. 2026-12-31` 与
 * `2027-01-01 .. 2027-09-23`（见 [HomeSidePanelCalendarData.festivalName] 的说明）。
 * 于是 **2026 年 1–3 月（元旦/春节/元宵都在这段）与 2027-10 之后完全没有节日名**，
 * 月格第二行只能退回农历 —— 这不是「一比一」，而是「数据缺口」。
 *
 * 这里用**已经移植好的农历/节气算法**（[homeSidePanelLunarInfo] / [homeSidePanelSolarTermName]）
 * 补齐传统节日与固定公历节日，覆盖任意年份：
 *  - 农历：春节、元宵、龙抬头、端午、七夕、中元、中秋、重阳、腊八、小年、**除夕**；
 *  - 公历：元旦、情人节、妇女节、劳动节、儿童节、教师节、国庆节、平安夜、圣诞节；
 *  - 清明：走节气算法（节气名就是「清明」）。
 *
 * 顺序纪律：**静态表优先**（真机口径），只有静态表没有该日时才用算法兜底 ——
 * 所以 2026/2027 的显示与 mical 逐字一致，缺口年份才有算法值。
 */
internal object HomeSidePanelLunarFestivals {

    /** 农历固定节日：`(月, 日) -> 名称`。 */
    private val LUNAR_FESTIVALS = mapOf(
        1 to 1 to "春节",
        1 to 15 to "元宵节",
        2 to 2 to "龙抬头",
        5 to 5 to "端午节",
        7 to 7 to "七夕节",
        7 to 15 to "中元节",
        8 to 15 to "中秋节",
        9 to 9 to "重阳节",
        12 to 8 to "腊八节",
        12 to 23 to "小年",
    )

    /** 公历固定节日：`(月, 日) -> 名称`。 */
    private val SOLAR_FESTIVALS = mapOf(
        1 to 1 to "元旦",
        2 to 14 to "情人节",
        3 to 8 to "妇女节",
        5 to 1 to "劳动节",
        6 to 1 to "儿童节",
        9 to 10 to "教师节",
        10 to 1 to "国庆节",
        12 to 24 to "平安夜",
        12 to 25 to "圣诞节",
    )

    /**
     * 该日的节日名（算法口径）。没有则返回 `null`。
     *
     * 清明由节气算法覆盖（`homeSidePanelSolarTermName` 返回「清明」时即清明当天）。
     */
    fun festivalName(date: LocalDate): String? {
        runCatching {
            // 1) 农历节日（初一/十五这类固定农历日；闰月不算节日，与主流日历一致）
            val lunar = homeSidePanelLunarInfo(date)
            if (lunar != null && !lunar.isLeapMonth) {
                LUNAR_FESTIVALS[lunar.month to lunar.day]?.let { return it }
                // 除夕：腊月的最后一天（下一天是正月初一）
                if (lunar.month == 12) {
                    val next = homeSidePanelLunarInfo(date.plusDays(1))
                    if (next != null && !next.isLeapMonth && next.month == 1 && next.day == 1) {
                        return "除夕"
                    }
                }
            }
            // 2) 公历固定节日
            SOLAR_FESTIVALS[date.monthValue to date.dayOfMonth]?.let { return it }
            // 3) 节气（清明/冬至等，节气名直接用）
            homeSidePanelSolarTermName(date)?.let { return it }
        }
        return null
    }
}
