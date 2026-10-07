package dev.joker.features.items.beautify.home_screen_panel.calendar

import android.content.Context
import dev.joker.loader.utils.ResourcesInjector
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.time.LocalDate

/**
 * 节日 / 节气 / 法定假期（休·班）数据层 —— 读随包 assets，运行期只解析一次、不联网。
 *
 * 资源（`app/src/main/assets/calendar/`）：
 *  - `cn_festivals.json`：111 条，覆盖 **2026 + 2027**（小米 `CN_zh_global_festival_config.json` 逐字节相同）
 *  - `cn_holiday_live.json`：`holiday.jsp` 本地快照，覆盖 **2011..2026**，
 *    `workday` / `freeday` 是**年积日**数组（1 月 1 日 = 1）
 *  - `aixi_words_113.json`：113 宜忌词表（与 [CalendarAixiWords] 同源，缺省时用常量兜底）
 *
 * 三处必须注意的口径（都来自 mical `Festivals.kt` 的实测行为）：
 *  1. **同名日「后写覆盖」**：同一天有多条（如 20260621 既是夏至又是父亲节）时，
 *     按 JSON 顺序**后一条生效** —— 与 mical `HashMap` 赋值语义 1:1；
 *     需要节气的场景请用 [solarTermName]，它独立成表。
 *  2. **节气 ≠ type=2**：`type=2` 里既有 24 节气，也有清明/端午/中秋/七夕/中元/重阳/腊八/
 *     小年/除夕/春节/元宵/龙抬头等传统节日。只有名字命中 24 节气名集合才算节气（[solarTermName]）。
 *  3. **休/班保质期**：`cn_holiday_live.json` 只到 2026 年；`cn_festivals.json` 的 2027 年条目
 *     **全部 `legalType = 0`（没有 `legalInfo` 法定区间）** ⇒ 2027 实际没有「休」可标；
 *     2028 年起一律 `null`（不联网、不猜测）。
 */
internal object HomeSidePanelCalendarAssets {

    private const val FESTIVALS_PATH = "calendar/cn_festivals.json"
    private const val HOLIDAY_LIVE_PATH = "calendar/cn_holiday_live.json"
    private const val WORDS_PATH = "calendar/aixi_words_113.json"

    /** 法定区间展开的最大天数（防御脏数据，正常最长 7~8 天）。 */
    private const val LEGAL_RANGE_LIMIT = 60

    /** 休/班角标数据保质期上限（含）；之后不标（用户决定：不联网）。 */
    private const val HOLIDAY_MARK_LAST_YEAR = 2026

    /** 2028 年起无数据 ⇒ 一律不标。 */
    private const val HOLIDAY_MARK_UNAVAILABLE_FROM = 2028

    private val initLock = Any()

    @Volatile
    private var initialized = false

    /** yyyyMMdd → 节日名（后写覆盖，与 mical 1:1）。 */
    @Volatile
    private var nameByDay: Map<Int, String> = emptyMap()

    /** yyyyMMdd → 法定假期名（`legalInfo.startDay..endDay` 展开）。 */
    @Volatile
    private var legalNameByDay: Map<Int, String> = emptyMap()

    /** yyyyMMdd → 节气名（只收 24 节气；后写覆盖）。 */
    @Volatile
    private var solarTermByDay: Map<Int, String> = emptyMap()

    /** yyyyMMdd → `休` / `班`（来自 `cn_holiday_live.json`）。 */
    @Volatile
    private var liveMarks: Map<Int, String> = emptyMap()

    @Volatile
    private var entries: List<FestivalEntry> = emptyList()

    @Volatile
    private var wordsTable: Array<String> = CalendarAixiWords

    /** `cn_festivals.json` 实际覆盖的公历年（用于判断「该年节日/节气是否以静态表为准」）。 */
    @Volatile
    private var coveredYears: Set<Int> = emptySet()

    /** 24 节气名集合（判别「是不是节气」用）。 */
    val solarTermNames: Set<String> = CalendarSolarTermNames24.toSet()

    /**
     * `cn_festivals.json` 里的名字 → 24 节气规范名；不是节气返回 `null`。
     *
     * 实测表内清明写作「**清明节**」（没有单字「清明」），别名表由生成脚本从资源推出
     * （见 [CalendarSolarTermAliases]）。
     */
    private fun canonicalSolarTerm(name: String): String? =
        if (solarTermNames.contains(name)) name else CalendarSolarTermAliases[name]

    /** 幂等：三个 assets 各读一次（**请在后台线程调用**）。单项失败只影响该项。 */
    fun init(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            loadFestivals(context)
            loadHolidayLive(context)
            loadWords(context)
            initialized = true
        }
    }

    fun isReady(): Boolean = initialized

    /** 宜忌词表（113 项）：`aixi_words_113.json` 优先，asset 缺失时用内联常量。 */
    fun words(): Array<String> = wordsTable

    /** 全部节日条目（111 条，含节日 + 节气 + 法定假期标记）。 */
    fun festivals(): List<FestivalEntry> = entries

    /** `cn_festivals.json` 覆盖到的公历年（当前为 2026 / 2027）；空集 = 资源缺失。 */
    fun coveredFestivalYears(): Set<Int> = coveredYears

    /** 该日的节日/节气名（`day = yyyyMMdd`，精确命中当天，不含法定区间展开）；无则 `null`。 */
    fun festivalName(day: Int): String? = nameByDay[day]

    /** 该日落在某个法定假期区间内时返回假期名（如 `国庆节`）；否则 `null`。 */
    fun legalHolidayName(day: Int): String? = legalNameByDay[day]

    /**
     * 休 / 班 角标：`休` / `班` / `null`。
     *
     * 顺序：`cn_holiday_live.json`（2011..2026 精确）→ `legalInfo` 区间（有则算「休」）→ `null`。
     * `date.year >= 2028` 直接返回 `null`（无数据，不联网）。
     */
    fun holidayMark(date: LocalDate): String? {
        if (date.year >= HOLIDAY_MARK_UNAVAILABLE_FROM) return null
        val day = dayKey(date)
        liveMarks[day]?.let { return it }
        if (date.year > HOLIDAY_MARK_LAST_YEAR) return null
        if (legalNameByDay.containsKey(day)) return "休"
        return null
    }

    /** 该日的节气名（`day = yyyyMMdd`）；不是节气返回 `null`。 */
    fun solarTermName(day: Int): String? = solarTermByDay[day]

    /** 该日是否 24 节气之一。 */
    fun isSolarTerm(day: Int): Boolean = solarTermByDay.containsKey(day)

    private fun loadFestivals(context: Context) {
        val text = calendarReadAssetText(context, FESTIVALS_PATH) ?: return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val array = root.optJSONArray("holidays") ?: return
        val names = HashMap<Int, String>(array.length() * 2)
        val legal = HashMap<Int, String>()
        val terms = HashMap<Int, String>()
        val list = ArrayList<FestivalEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val day = item.optString("day").toIntOrNull() ?: continue
            val name = item.optString("name")
            val type = item.optInt("type", 0)
            val legalType = item.optInt("legalType", 0)
            val icon = item.optString("icon").takeIf { it.isNotEmpty() }
            val legalInfo = item.optJSONObject("legalInfo")
            val legalStart = legalInfo?.optInt("startDay", 0)?.takeIf { it > 0 }
            val legalEnd = legalInfo?.optInt("endDay", 0)?.takeIf { it > 0 }
            val legalDays = legalInfo?.optInt("days", 0)?.takeIf { it > 0 }
            list.add(
                FestivalEntry(
                    day = day,
                    name = name,
                    type = type,
                    legalType = legalType,
                    legalStartDay = legalStart,
                    legalEndDay = legalEnd,
                    legalDays = legalDays,
                    icon = icon,
                ),
            )
            if (name.isEmpty()) continue
            names[day] = name
            if (type == 2) canonicalSolarTerm(name)?.let { terms[day] = it }
            if (legalType == 1 && legalStart != null && legalEnd != null && legalEnd >= legalStart) {
                expandLegalRange(legal, legalStart, legalEnd, name)
            }
        }
        entries = list
        nameByDay = names
        legalNameByDay = legal
        solarTermByDay = terms
        coveredYears = names.keys.mapNotNull { day -> day.takeIf { it >= 10000000 }?.div(10000) }.toSet()
    }

    private fun expandLegalRange(into: HashMap<Int, String>, start: Int, end: Int, name: String) {
        var cursor = calendarLocalDate(start) ?: return
        val last = calendarLocalDate(end) ?: return
        var guard = 0
        while (!cursor.isAfter(last) && guard < LEGAL_RANGE_LIMIT) {
            into[dayKey(cursor)] = name
            cursor = cursor.plusDays(1)
            guard++
        }
    }

    private fun loadHolidayLive(context: Context) {
        val text = calendarReadAssetText(context, HOLIDAY_LIVE_PATH) ?: return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val years = root.optJSONArray("holiday") ?: holidaysArrayAlias(root) ?: return
        val marks = HashMap<Int, String>(1024)
        for (index in 0 until years.length()) {
            val item = years.optJSONObject(index) ?: continue
            val year = item.optInt("year", 0)
            if (year <= 0) continue
            collectDayOfYear(item.optJSONArray("freeday"), year, "休", marks)
            collectDayOfYear(item.optJSONArray("workday"), year, "班", marks)
        }
        liveMarks = marks
    }

    /** 兼容 `holiday` / `holidays` 两种数组名（`holiday.jsp` 的两种写法）。 */
    private fun holidaysArrayAlias(root: JSONObject): JSONArray? = root.optJSONArray("holidays")

    private fun collectDayOfYear(array: JSONArray?, year: Int, mark: String, into: HashMap<Int, String>) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val dayOfYear = array.optInt(index, 0)
            if (dayOfYear < 1 || dayOfYear > 366) continue
            val date = runCatching { LocalDate.ofYearDay(year, dayOfYear) }.getOrNull() ?: continue
            into[dayKey(date)] = mark
        }
    }

    private fun loadWords(context: Context) {
        val text = calendarReadAssetText(context, WORDS_PATH) ?: return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val array = root.optJSONArray("words") ?: return
        if (array.length() != CalendarAixiWords.size) return
        val loaded = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val word = array.optString(index)
            if (word.isEmpty()) return
            loaded.add(word)
        }
        wordsTable = loaded.toTypedArray()
    }
}

/** 节日条目（字段与 `cn_festivals.json` 一一对应）。 */
internal data class FestivalEntry(
    val day: Int,
    val name: String,
    val type: Int,
    val legalType: Int,
    val legalStartDay: Int?,
    val legalEndDay: Int?,
    val legalDays: Int?,
    val icon: String?,
)

/** `yyyyMMdd` 整数键（与 mical `key(Calendar)` 同义）。 */
internal fun dayKey(date: LocalDate): Int = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

/** `yyyyMMdd` 整数 → [LocalDate]；非法返回 `null`。 */
internal fun calendarLocalDate(day: Int): LocalDate? = runCatching {
    LocalDate.of(day / 10000, day / 100 % 100, day % 100)
}.getOrNull()

/**
 * 读随模块打包的 assets（`app/src/main/assets/calendar/...`）。
 *
 * 宿主进程里 assets 需要先注入模块资源（沿用 `HomeSidePanelWeather` 约定）；
 * 若调用方本来就持有模块自己的 Resources，第一次 `open` 就会成功，不会重复注入。
 * 任何失败都返回 `null`，绝不抛异常。
 */
internal fun calendarReadAssetBytes(context: Context, path: String): ByteArray? {
    val stream = calendarOpenAsset(context, path) ?: return null
    return runCatching { stream.use { it.readBytes() } }.getOrNull()
}

internal fun calendarReadAssetText(context: Context, path: String): String? {
    val stream = calendarOpenAsset(context, path) ?: return null
    return runCatching { stream.use { it.bufferedReader().use { reader -> reader.readText() } } }.getOrNull()
}

private fun calendarOpenAsset(context: Context, path: String): InputStream? {
    runCatching { context.assets.open(path) }.getOrNull()?.let { return it }
    runCatching { ResourcesInjector.injectModuleRes(context.resources) }
    return runCatching { context.assets.open(path) }.getOrNull()
}
