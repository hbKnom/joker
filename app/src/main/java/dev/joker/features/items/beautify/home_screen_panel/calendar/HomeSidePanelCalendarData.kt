package dev.joker.features.items.beautify.home_screen_panel.calendar

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.joker.utils.WeLogger
import java.time.LocalDate

/**
 * 负一屏侧边栏日历 · **数据层唯一门面**（小米日历 / mical 一比一复刻）。
 *
 * 其余 `calendar/` 下的文件都是 `internal`：UI 只应通过本对象取数。
 *
 * 数据来源（全部离线，随模块 APK 打包，不联网、不需要权限）：
 *  - 宜忌：`assets/calendar/huangli.idf`（覆盖 1901-01-01 .. 2101-01-01）
 *  - 节日 / 节气 / 法定假期：`assets/calendar/cn_festivals.json`（2026 + 2027）、
 *    `assets/calendar/cn_holiday_live.json`（2011..2026 休/班）
 *  - 农历 / 干支 / 胎神 / 彭祖 / 纳音 / 建除 / 值神 / 星宿 / 冲煞 / 十二时：纯算法（内联表）
 *
 * 使用约定：
 *  1. **必须在后台线程调用一次 [init]**（读 250 KB assets + 解析 JSON，幂等）；
 *     未 init 时 [yiJi] 返回 `null`、[huangliDetail] 的宜忌为空，其余纯算法字段照常可用。
 *  2. 所有函数**绝不抛异常**：资源缺失 / 解析失败 / 日期越界一律降级（`null` / 空列表 / 空串）。
 *  3. 越界口径：宜忌 1901-01-01..2101-01-01；农历表 1900-01-31 起。
 *
 * 与 mical 源码的 4 处必改差异见 `work/mical-inventory.md` §7，
 * 本包已全部修正：113 词表左旋 2 位、冲煞「虎日冲猴」、星宿按四象方位、戊/癸年五虎遁。
 */
object HomeSidePanelCalendarData {

    private const val TAG = "HomeSidePanelCalendarData"


    /** 倒计时最多向后找 180 天（与 mical `countdownCard` 一致）。 */
    private const val COUNTDOWN_MAX_DAYS = 180

    /** 节日名短于 2 字的不进倒计时（与 mical `nm.length < 2` 一致）。 */
    private const val COUNTDOWN_MIN_NAME_LENGTH = 2

    private val initLock = Any()

    @Volatile
    private var initialized = false

    /**
     * 幂等初始化：assets 只读一次（206 KB idf + 42 KB JSON），**请在后台线程调用**。
     *
     * 单项失败只影响该项（例如只有 idf 缺失时，节日/休班仍可用）。
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            runCatching { HomeSidePanelCalendarAssets.init(context) }
            runCatching { HomeSidePanelHuangliIdf.init(context, HomeSidePanelCalendarAssets.words()) }
            initialized = true
            // 【第 55 轮】把「就绪」变成可观察状态 + 打一条诊断。
            //
            // 为什么必须可观察：卡片是 Compose 写的，而 `HomeSidePanelMonthSummaryCard` / `DayView` /
            // `DetailView` 的入参全是稳定值 + Compose 会自动记忆化的 lambda ⇒ 数据晚一步就绪时，
            // 这些子组件会被 **skip**、不会重跑，首帧查到的 `null` 就被永久缓存住了 ——
            // 实机表现正是「月格里的休/班角标有（它的入参每帧都是新对象，会重跑），
            // 但摘要卡/日视图/详情页的**宜忌永远不显示**」。
            readyState.value = true
            val probe = runCatching { yiJi(LocalDate.now()) }.getOrNull()
            WeLogger.i(
                TAG,
                "日历数据就绪：assets=${HomeSidePanelCalendarAssets.isReady()} " +
                    "idf=${HomeSidePanelHuangliIdf.isReady()} 词表=${HomeSidePanelCalendarAssets.words().size} " +
                    "今日宜=${probe?.yi?.size ?: -1} 忌=${probe?.ji?.size ?: -1}",
            )
        }
    }

    /**
     * 数据是否就绪（**Compose 可观察**）。
     *
     * 视图层必须在自己的组合里读一次它：否则 init 完成时不会触发这些子组件重组（见 [init] 注释）。
     */
    val readyState: MutableState<Boolean> = mutableStateOf(false)

    /** 非 Compose 侧（服务/日志）用的同步就绪判定。 */
    fun isReady(): Boolean = initialized

    /** 是否已 init（UI 可用来决定是否先显示占位）。 */
    fun isReady(): Boolean = initialized

    /**
     * 宜忌（`huangli.idf`）：`null` = 未 init 或日期越界（1901-01-01 .. 2101-01-01）。
     *
     * 词序 = 真机顺序（已修正 mical 的 113 词表左旋 2 位）。
     */
    fun yiJi(date: LocalDate): YiJi? = runCatching { HomeSidePanelHuangliIdf.query(date) }.getOrNull()

    /**
     * 黄历详情（详情页 13 项 + 日视图 / 月摘要卡需要的全部显示值，见 [HuangliDetail]）。
     *
     * 非空：农历表超范围时农历相关字段为空串，干支/胎神/纳音/建除/值神/星宿/冲煞/十二时照常给出。
     * 宜忌字段来自 [yiJi]（未 init / 越界时为空列表）。
     */
    fun huangliDetail(date: LocalDate): HuangliDetail =
        runCatching { homeSidePanelHuangliDetail(date, yiJi(date)) }.getOrElse { emptyHuangliDetail(date) }

    /**
     * 当天的节日 / 节气名（`cn_festivals.json` 精确命中当天，不做法定区间展开）。
     *
     * ⚠ 与 mical `Festivals.nameOfKey` 1:1：**同日后写覆盖**（20260621 得到「父亲节」而不是「夏至」）；
     *   节气请用 [solarTerm]（它会把「清明节」规范化成「清明」）。
     *
     * ⚠ 数据边界（实测）：`cn_festivals.json` 111 条 = **2026-04-01 .. 2026-12-31（68 条）
     *   ＋ 2027-01-01 .. 2027-09-23（43 条）**。因此 2026 年 1~3 月与 2027 年 10 月以后
     *   都没有节日名（元旦/春节/元宵等 2026 年头的节日也缺）⇒ 这里返回 `null`，月格第二行会退回农历。
     */
    fun festivalName(date: LocalDate): String? =
        runCatching { HomeSidePanelCalendarAssets.festivalName(dayKey(date)) }.getOrNull()
            // 【第 52 轮】静态表没有该日时用**算法兜底**（春节/中秋/除夕/元旦/国庆… ），
            // 覆盖静态表缺口：2026-01-01..2026-03-31 与 2027-09-24 之后的所有年份。
            // 顺序仍是「静态表（真机口径）优先」，所以 2026/2027 的显示与 mical 逐字一致。
            ?: HomeSidePanelLunarFestivals.festivalName(date)

    /**
     * 休 / 班 角标：`"休"` / `"班"` / `null`。
     *
     * `cn_holiday_live.json`（2011..2026，精确）→ `legalInfo` 法定区间（命中算「休」）→ `null`；
     * `date.year >= 2028` 一律 `null`（无数据、不联网）。
     *
     * ⚠ 2027 年 `cn_festivals.json` 的 43 条**全部 `legalType = 0`（无 `legalInfo`）**，
     *   且 `cn_holiday_live.json` 只到 2026 ⇒ **2027 全年 [holidayMark] 实际都为 `null`**
     *   （与「2027 用 legalInfo 区间」的初始设想不符，需主代理/用户知悉）。
     */
    fun holidayMark(date: LocalDate): String? =
        runCatching { HomeSidePanelCalendarAssets.holidayMark(date) }.getOrNull()

    /**
     * 节气名（24 节气规范名，如 `寒露` / `清明`）。
     *
     * 静态表（`cn_festivals.json`，真机口径）优先；该日不在表内时用**天文算法**补
     * （太阳黄经，按北京时间分日）—— 于是 2026 年 1~3 月（静态表从 2026-04-01 才开始）
     * 与 2028 年以后都能给出节气。不是节气日返回 `null`。
     */
    fun solarTerm(date: LocalDate): String? = runCatching {
        HomeSidePanelCalendarAssets.solarTermName(dayKey(date))
            ?: homeSidePanelSolarTermName(date)
    }.getOrNull()

    /**
     * 是否 24 节气之一（等价 `solarTerm(date) != null`）。
     */
    fun isSolarTerm(date: LocalDate): Boolean = solarTerm(date) != null

    /**
     * 月格第二行用的农历简写：初一 → `八月`（闰月带「闰」），其余 → `廿七`；超范围返回 `""`。
     *
     * 与 mical `miniLunar()` 逐字一致。月格整行取值口径 = `festivalName(date) ?: lunarShort(date)`
     * （有节日/节气显示名字，否则显示农历）。
     */
    fun lunarShort(date: LocalDate): String = runCatching { homeSidePanelLunarShort(date) }.getOrElse { "" }

    /**
     * 日干支，**裸值**：`甲寅`（带单位的 `甲寅日` 见 [HuangliDetail.dayGanZhiText]；
     * 月摘要卡 / 日视图那行由 UI 自己拼「…年 …月 …日」，所以这里不带单位）。
     */
    fun dayGanZhi(date: LocalDate): String = runCatching { homeSidePanelDayGanZhi(date) }.getOrElse { "" }

    /**
     * 月柱干支，**裸值**：`丁酉`（节气定月 + 标准五虎遁，已修 mical 戊/癸年缺陷）。
     */
    fun monthGanZhi(date: LocalDate): String = runCatching { homeSidePanelMonthGanZhi(date) }.getOrElse { "" }

    /** 年干支，**裸值**：`丙午`（农历年 / 春节定年，与小米详情页一致）。 */
    fun yearGanZhi(date: LocalDate): String =
        runCatching { homeSidePanelLunarYearGanZhi(date) }.getOrElse { "" }

    /**
     * 倒计时（日视图「未来 N 个节日」）：从 [from] 起 0..180 天内，取前 [count] 个**有名字**的
     * 节日（名字短于 2 字跳过），与 mical `countdownCard` 同口径（≤4 条）。
     *
     * [CountdownItem.label] = `今天` / `7天`；[CountdownItem.weekdayText] 用正确的周X
     * （mical 这里 `"一二三四五六日"[DAY_OF_WEEK-1]` 错位一天，本实现已修）。
     */
    fun countdown(from: LocalDate, count: Int): List<CountdownItem> {
        if (count <= 0) return emptyList()
        return runCatching {
            val result = ArrayList<CountdownItem>(count)
            var offset = 0
            while (offset <= COUNTDOWN_MAX_DAYS && result.size < count) {
                val date = from.plusDays(offset.toLong())
                val name = festivalName(date)
                if (name != null && name.length >= COUNTDOWN_MIN_NAME_LENGTH) {
                    result.add(
                        CountdownItem(
                            date = date,
                            name = name,
                            label = if (offset == 0) "今天" else offset.toString() + "天",
                            daysAhead = offset,
                            lunarText = homeSidePanelLunarInfo(date)?.text.orEmpty(),
                            weekdayText = weekdayText(date),
                            isSolarTerm = solarTerm(date) != null,
                        ),
                    )
                }
                offset++
            }
            result
        }.getOrElse { emptyList() }
    }

    private fun weekdayText(date: LocalDate): String {
        val names = "一二三四五六日"
        val index = date.dayOfWeek.value - 1
        return "周" + names[index.coerceIn(0, 6)]
    }
}

/**
 * 宜忌（`huangli.idf` 记录的两段词）。
 *
 * [yi] / [ji] 的顺序 = 真机顺序；任一段可为空列表（例如真机某天没有「忌」）。
 */
data class YiJi(val yi: List<String>, val ji: List<String>)

/**
 * 黄历详情 —— 覆盖详情页 13 项 + 月摘要卡 / 日视图的全部显示值。
 *
 * 干支类字段有**裸值**（`甲寅`）与**带单位**（`甲寅日` / `丁酉月` / `丙午年`）两种，
 * 详情页那行「丙午年 丁酉月 甲寅日」直接用 [threePillarsText]。
 */
data class HuangliDetail(
    val date: LocalDate,
    /** 农历月日：`八月廿七`。 */
    val lunarText: String,
    /** 农历月（含「月」与闰前缀）：`八月`。 */
    val lunarMonthText: String,
    /** 农历日：`廿七`。 */
    val lunarDayText: String,
    /** 该农历月大小：`大`（30 天）/ `小`（29 天）。 */
    val lunarMonthSizeText: String,
    /** 生肖：`马`。 */
    val shengXiao: String,
    /** 年柱裸干支：`丙午`。 */
    val yearGanZhi: String,
    /** 月柱裸干支：`丁酉`。 */
    val monthGanZhi: String,
    /** 日柱裸干支：`甲寅`。 */
    val dayGanZhi: String,
    val yearGanZhiText: String,
    val monthGanZhiText: String,
    val dayGanZhiText: String,
    /** `丙午年 丁酉月 甲寅日`。 */
    val threePillarsText: String,
    /** 日干支序号 0..59（锚 2019-01-29 = 丙寅，该日 = 2）。 */
    val dayGanZhiIndex: Int,
    /** 纳音：`大溪水`。 */
    val naYin: String,
    /** 详情页「五行」格：`大溪水 执位`。 */
    val wuXingText: String,
    /** 建除十二神：`执`。 */
    val jianChu: String,
    /** 十二值神：`青龙`。 */
    val dayGod: String,
    /** 二十八宿：`参水猿`。 */
    val star28: String,
    /** 星宿四象方位：`西方`。 */
    val star28Direction: String,
    /** 详情页「星宿」格：`西方参水猿`。 */
    val star28Text: String,
    /** 彭祖百忌·日干：`甲不开仓 财物耗散`。 */
    val pengZuStem: String,
    /** 彭祖百忌·日支：`寅不祭祀 神鬼不尝`。 */
    val pengZuBranch: String,
    /** 胎神占方：`占门炉外东北`。 */
    val fetalGod: String,
    /** 冲煞（真机格式，两侧都用生肖）：`虎日冲猴`。 */
    val chongSha: String,
    /** 十二时地支名 12 项（子..亥）。 */
    val twoHourNames: List<String>,
    /** 十二时吉凶 12 项（子..亥），`true` = 吉。 */
    val twoHourLuck: List<Boolean>,
    /** 十二时吉凶文本 12 项：`吉` / `凶`。 */
    val twoHourText: List<String>,
    /** 宜（来自 `huangli.idf`，未 init / 越界为空）。 */
    val yi: List<String>,
    /** 忌（同上）。 */
    val ji: List<String>,
    /** 宜拼接成一行（`"安床 斋醮 …"`，便于直接上屏）。 */
    val yiText: String,
    /** 忌拼接成一行。 */
    val jiText: String,
)

/**
 * 倒计时条目（日视图「未来 N 个节日」）。
 *
 * 界面一行 = 左两行（[name] / `[lunarText] | [weekdayText]`）+ 右侧 [label]。
 */
data class CountdownItem(
    val date: LocalDate,
    /** 节日名。 */
    val name: String,
    /** `今天` / `7天`。 */
    val label: String,
    /** 距 [HomeSidePanelCalendarData.countdown] 起点天数（0 = 当天）。 */
    val daysAhead: Int,
    /** 农历月日：`八月廿七`。 */
    val lunarText: String,
    /** 周几：`周三`（周一为一周之始，已修 mical 的错位）。 */
    val weekdayText: String,
    /** 该日是否 24 节气之一。 */
    val isSolarTerm: Boolean,
)
