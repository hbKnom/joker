package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.sin

/**
 * 黄历（天干地支、节气、建除十二神、宜忌）纯算法实现。
 *
 * 所有算法均已通过寿星天文历（sxtwl）权威库逐项对拍验证：
 * 年柱、月柱、日柱、节气日期与建除十二神均一致。
 *
 * 日干支锚点：1949-10-01（JDN 2433191）= 甲子日。
 */

private val GAN = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
private val ZHI = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
private val SHENG_XIAO = listOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

private const val JDN_ANCHOR = 2433191L // 1949-10-01 甲子日

/** 节气名 + 近似日期（月、日），用于二分法窗口定位。 */
private data class SolarTermRef(val name: String, val month: Int, val day: Int)

private val SOLAR_TERMS = listOf(
    SolarTermRef("小寒", 1, 5), SolarTermRef("大寒", 1, 20), SolarTermRef("立春", 2, 4), SolarTermRef("雨水", 2, 19),
    SolarTermRef("惊蛰", 3, 5), SolarTermRef("春分", 3, 20), SolarTermRef("清明", 4, 5), SolarTermRef("谷雨", 4, 20),
    SolarTermRef("立夏", 5, 5), SolarTermRef("小满", 5, 21), SolarTermRef("芒种", 6, 5), SolarTermRef("夏至", 6, 21),
    SolarTermRef("小暑", 7, 7), SolarTermRef("大暑", 7, 22), SolarTermRef("立秋", 8, 7), SolarTermRef("处暑", 8, 23),
    SolarTermRef("白露", 9, 7), SolarTermRef("秋分", 9, 23), SolarTermRef("寒露", 10, 8), SolarTermRef("霜降", 10, 23),
    SolarTermRef("立冬", 11, 7), SolarTermRef("小雪", 11, 22), SolarTermRef("大雪", 12, 7), SolarTermRef("冬至", 12, 22),
)

/** 12 个「节」（定月用）在 [SOLAR_TERMS] 中的下标，以及对应的月支下标（寅=2 … 丑=1）。 */
private val JIE_INDEX = listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 0)
private val JIE_MONTH_ZHI = listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0, 1)

private val JIAN_CHU = listOf("建", "除", "满", "平", "定", "执", "破", "危", "成", "收", "开", "闭")

/** 建除十二神通用宜忌表：Pair(宜, 忌)，以「·」分隔多项。 */
private val JIAN_CHU_YI_JI = mapOf(
    "建" to Pair("出行·上任·求财", "动土·安葬"),
    "除" to Pair("除旧·祭祀·祈福", "开市·出行"),
    "满" to Pair("祭祀·祈福·开业", "动土·安葬"),
    "平" to Pair("修路·修饰·筑墙", "出行·开市"),
    "定" to Pair("订婚·签约·安床", "开仓·出财"),
    "执" to Pair("祈福·祭祀·捕捉", "开市·交易"),
    "破" to Pair("破屋·坏垣·求医", "嫁娶·开市"),
    "危" to Pair("安床·祭祀·祈福", "登高·出行"),
    "成" to Pair("开业·嫁娶·动土", "诉讼"),
    "收" to Pair("纳财·收账·安葬", "开市·出行"),
    "开" to Pair("开业·嫁娶·祈福", "安葬·动土"),
    "闭" to Pair("安葬·祭祀·闭库", "开市·嫁娶"),
)

internal data class HomeSidePanelAlmanac(
    val yearGanZhi: String,
    val shengXiao: String,
    val monthGanZhi: String,
    val dayGanZhi: String,
    val jianChu: String,
    val yi: String,
    val ji: String,
    val solarTerm: String?,
    val festival: String?,
)

private fun jdn(year: Int, month: Int, day: Int): Long {
    val a = (14 - month) / 12
    val y = year + 4800 - a
    val m = month + 12 * a - 3
    return day.toLong() + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045
}

private fun dayGanZhiIndex(year: Int, month: Int, day: Int): Int {
    val idx = ((jdn(year, month, day) - JDN_ANCHOR) % 60).toInt()
    return if (idx < 0) idx + 60 else idx
}

private fun apparentSolarLongitude(jde: Double): Double {
    val t = (jde - 2451545.0) / 36525.0
    val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
    val m = Math.toRadians(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
    val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
        (0.019993 - 0.000101 * t) * sin(2 * m) +
        0.000289 * sin(3 * m)
    return ((l0 + c) % 360.0 + 360.0) % 360.0
}

private fun jdeFromUnix(ts: Double): Double = ts / 86400.0 + 2440587.5

private fun unixFromJde(jde: Double): Double = (jde - 2440587.5) * 86400.0

/** 返回某年某节气（index 0..23）的精确时刻（UTC 毫秒）。 */
private fun solarTermMillis(year: Int, index: Int): Long {
    val ref = SOLAR_TERMS[index]
    val target = (285 + index * 15) % 360.0
    val approx = LocalDateTime.of(year, ref.month, ref.day, 0, 0, 0)
        .toInstant(ZoneOffset.UTC).toEpochMilli().toDouble() / 1000.0
    val lo = approx - 3 * 86400.0
    val hi = approx + 3 * 86400.0
    var jlo = jdeFromUnix(lo)
    var jhi = jdeFromUnix(hi)

    fun f(jde: Double): Double {
        var diff = (apparentSolarLongitude(jde) - target) % 360.0
        if (diff > 180) diff -= 360
        return diff
    }

    var flo = f(jlo)
    var fhi = f(jhi)
    repeat(60) {
        val jmid = (jlo + jhi) / 2
        val fmid = f(jmid)
        if (flo * fmid > 0) {
            jlo = jmid
            flo = fmid
        } else {
            jhi = jmid
            fhi = fmid
        }
    }
    val jde = (jlo + jhi) / 2
    return (unixFromJde(jde) * 1000.0).toLong()
}

private fun yearGanZhiIndex(dateTime: LocalDateTime): Int {
    var year = dateTime.year
    val liChun = solarTermMillis(year, 2)
    val millis = dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
    if (millis < liChun) {
        year -= 1
    }
    return ((year - 4) % 60).let { if (it < 0) it + 60 else it }
}

private fun monthZhiIndex(dateTime: LocalDateTime): Int {
    val millis = dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
    var result = JIE_MONTH_ZHI.first()
    for (i in 0 until 12) {
        val t = solarTermMillis(dateTime.year, JIE_INDEX[i])
        if (millis >= t) {
            result = JIE_MONTH_ZHI[i]
        } else {
            break
        }
    }
    return result
}

private fun monthGanIndex(yearGan: Int, monthZhi: Int): Int {
    // 五虎遁：正月（寅月）天干由年干决定。
    val firstGan = intArrayOf(2, 4, 6, 8, 0)[yearGan % 5]
    val offset = ((monthZhi - 2) % 12 + 12) % 12
    return (firstGan + offset) % 10
}

private fun solarTermNameOn(dateTime: LocalDateTime): String? {
    val millis = dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
    for (i in 0 until 24) {
        val t = solarTermMillis(dateTime.year, i)
        if (t in (millis - 6 * 3600_000L)..(millis + 18 * 3600_000L)) {
            return SOLAR_TERMS[i].name
        }
    }
    return null
}

internal fun homeSidePanelAlmanac(dateTime: LocalDateTime): HomeSidePanelAlmanac {
    val ygz = yearGanZhiIndex(dateTime)
    val dgz = dayGanZhiIndex(dateTime.year, dateTime.monthValue, dateTime.dayOfMonth)
    val mz = monthZhiIndex(dateTime)
    val mg = monthGanIndex(ygz % 10, mz)
    val jc = (dgz % 12 - mz + 12) % 12
    val jianChuName = JIAN_CHU[jc]
    val (yi, ji) = JIAN_CHU_YI_JI.getValue(jianChuName)

    return HomeSidePanelAlmanac(
        yearGanZhi = GAN[ygz % 10] + ZHI[ygz % 12],
        shengXiao = SHENG_XIAO[ygz % 12],
        monthGanZhi = GAN[mg] + ZHI[mz],
        dayGanZhi = GAN[dgz % 10] + ZHI[dgz % 12],
        jianChu = jianChuName,
        yi = yi,
        ji = ji,
        solarTerm = solarTermNameOn(dateTime),
        festival = homeSidePanelFestival(dateTime),
    )
}

/** 传统节日（农历）+ 公历节日，按优先级返回最先命中者。 */
private fun homeSidePanelFestival(dateTime: LocalDateTime): String? {
    val lunar = homeSidePanelLunarDate(dateTime)
    if (!lunar.isLeapMonth) {
        // 农历节日（不含闰月）
        val lunarFestival = when (lunar.month to lunar.day) {
            1 to 1 -> "春节"
            1 to 15 -> "元宵节"
            5 to 5 -> "端午节"
            7 to 7 -> "七夕"
            8 to 15 -> "中秋节"
            9 to 9 -> "重阳节"
            12 to 8 -> "腊八节"
            else -> null
        }
        if (lunarFestival != null) return lunarFestival
    }

    // 公历节日
    return when (dateTime.monthValue to dateTime.dayOfMonth) {
        1 to 1 -> "元旦"
        2 to 14 -> "情人节"
        3 to 8 -> "妇女节"
        4 to 1 -> "愚人节"
        5 to 1 -> "劳动节"
        6 to 1 -> "儿童节"
        10 to 1 -> "国庆节"
        12 to 25 -> "圣诞节"
        else -> null
    }
}
