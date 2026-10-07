package dev.joker.features.items.beautify.home_screen_panel.calendar

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate

/**
 * 小米日历 `huangli.idf`（MIUI DirectIndexedFile v2）只读解析器 —— 黄历宜忌的唯一数据源。
 *
 * 文件布局（大端，全部由 [init] 从头部读出，**不写死**）：
 * ```
 *  0..3    "IDF " magic
 *  36..39  u32 fa       首行 key（自 1901-01-01 起的天偏移）
 *  40..43  u32 fb       行数（实测 73050 ⇒ 覆盖 1901-01-01 .. 2101-01-01）
 *  44..51  u64 fc       行区基址（每行 stride=2 字节，u16 记录号）
 *  52..55  u32 descCount
 *  57      u8  stride   行 stride
 *  58      u8  lenSize  字符串长度字段字节数
 *  59      u8  elemSize 描述符元素宽度
 *  60..67  u64 descBase 描述符基址
 *  descBase:      u32 count(=700)
 *  descBase + 4:  u32[count] 字符串偏移（相对 descBase + 4）
 * ```
 * 取数：`key = (date - 1901-01-01).days`；`rowOff = fc + (key - fa) * stride`；
 * `recIdx = u16(rowOff)`；`val = u32(descBase + 4 + elemSize * recIdx)`；
 * `text = UTF-8(descBase + 4 + val + lenSize, u16(descBase + 4 + val))`；
 * 文本形如 `"31、17、56、…,1、111、29、…"` —— 逗号前「宜」、逗号后「忌」，
 * 数字是 [CalendarAixiWords] 的索引。
 *
 * 与 mical `HuangliIdf.kt` 的三处差异（**都必须修**）：
 *  1. 词表修正：mical 的 `WORDS` 相对真机词表左旋 2 位 ⇒ 全部宜忌错位。这里用
 *     [CalendarAixiWords]（idx0 = 嫁娶、idx1 = 修造，已用真机 7 天逐词校验）。
 *  2. 越界检查：文件只覆盖 1901-01-01..2101-01-01，越界读会拿到垃圾记录 ⇒ 返回 `null`。
 *  3. 全链路 `runCatching`：资源缺失 / 头部非法 / 记录越界一律降级为 `null`，绝不抛异常
 *     （本模块注入宿主进程）。
 *
 * 资源位置：`app/src/main/assets/calendar/huangli.idf`（206 KB，随模块 APK 打包）。
 */
internal object HomeSidePanelHuangliIdf {

    private const val ASSET_PATH = "calendar/huangli.idf"

    /** 行区 key 的基准日（`key = keyOf(date)`）。 */
    private val BASE_DATE: LocalDate = LocalDate.of(1901, 1, 1)

    /** 头部 + 描述符区最小有效长度（magic 4 + 到 descBase 的字段 + count 4）。 */
    private const val MIN_SIZE = 72

    @Volatile
    private var data: ByteArray? = null

    @Volatile
    private var wordTable: Array<String> = CalendarAixiWords

    private var firstKey = 0
    private var rowCount = 0
    private var rowBase = 0L
    private var descBase = 0L
    private var descriptorDataBase = 0
    private var stride = 2
    private var lenSize = 2
    private var elemSize = 4

    /**
     * 幂等：assets 只读一次，206 KB 常驻堆（**请在后台线程调用**）。
     *
     * [wordTable] 由 `HomeSidePanelCalendarAssets.words()` 提供（缺省用内联常量兜底）。
     */
    fun init(context: Context, wordTable: Array<String> = CalendarAixiWords) {
        if (data != null) return
        synchronized(this) {
            if (data != null) return
            val bytes = calendarReadAssetBytes(context, ASSET_PATH) ?: return
            if (bytes.size < MIN_SIZE) return
            if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() ||
                bytes[2] != 'F'.code.toByte() || bytes[3] != ' '.code.toByte()
            ) {
                return
            }
            val parsed = runCatching {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val fa = buffer.getInt(36)
                val fb = buffer.getInt(40)
                val fc = buffer.getLong(44)
                val base = buffer.getLong(60)
                val rowStride = bytes[57].toInt() and 0xFF
                val lengthSize = bytes[58].toInt() and 0xFF
                val elementSize = bytes[59].toInt() and 0xFF
                // 头部合法性：三个宽度字段与行区/描述符区都必须在文件内
                require(rowStride in 1..8 && lengthSize in 1..4 && elementSize in 1..8)
                require(fa >= 0 && fb > 0)
                require(fc >= MIN_SIZE && base >= MIN_SIZE)
                require(fc + fb.toLong() * rowStride <= bytes.size.toLong())
                require(elementSize * 1L + 4 <= bytes.size.toLong())
                longArrayOf(fa.toLong(), fb.toLong(), fc, base, rowStride.toLong(), lengthSize.toLong(), elementSize.toLong())
            }.getOrNull() ?: return
            firstKey = parsed[0].toInt()
            rowCount = parsed[1].toInt()
            rowBase = parsed[2]
            descBase = parsed[3]
            stride = parsed[4].toInt()
            lenSize = parsed[5].toInt()
            elemSize = parsed[6].toInt()
            descriptorDataBase = (descBase + 4L).toInt()
            if (wordTable.isNotEmpty()) this.wordTable = wordTable
            data = bytes
        }
    }

    fun isReady(): Boolean = data != null

    /** 文件覆盖的日期区间（含两端）；未 init 时 `null`。 */
    fun coveredRange(): ClosedRange<LocalDate>? {
        if (data == null) return null
        return BASE_DATE.plusDays(firstKey.toLong())..BASE_DATE.plusDays((firstKey + rowCount - 1).toLong())
    }

    /** 某天的宜忌；未 init / 越界 / 记录损坏一律返回 `null`。 */
    fun query(date: LocalDate): YiJi? {
        val bytes = data ?: return null
        val key = (date.toEpochDay() - BASE_DATE.toEpochDay()).toInt()
        if (key < firstKey || key >= firstKey + rowCount) return null
        return runCatching {
            val rowOffset = (rowBase + (key - firstKey).toLong() * stride).toInt()
            if (rowOffset < 0 || rowOffset + 1 >= bytes.size) return null
            val recordIndex = u16(bytes, rowOffset)
            val elementOffset = descriptorDataBase + elemSize * recordIndex
            if (elementOffset < 0 || elementOffset + 4 > bytes.size) return null
            val valueOffset = i32(bytes, elementOffset)
            val textOffset = descriptorDataBase + valueOffset
            if (textOffset < 0 || textOffset + lenSize > bytes.size) return null
            val length = if (lenSize == 2) u16(bytes, textOffset) else i32(bytes, textOffset)
            if (length <= 0 || textOffset + lenSize + length > bytes.size) return null
            decode(String(bytes, textOffset + lenSize, length, Charsets.UTF_8))
        }.getOrNull()
    }

    /** 原始记录文本（排错用）：`"31、17、56、…,1、111、29、…"`。 */
    fun rawText(date: LocalDate): String? {
        val bytes = data ?: return null
        val key = (date.toEpochDay() - BASE_DATE.toEpochDay()).toInt()
        if (key < firstKey || key >= firstKey + rowCount) return null
        return runCatching {
            val rowOffset = (rowBase + (key - firstKey).toLong() * stride).toInt()
            val recordIndex = u16(bytes, rowOffset)
            val elementOffset = descriptorDataBase + elemSize * recordIndex
            val valueOffset = i32(bytes, elementOffset)
            val textOffset = descriptorDataBase + valueOffset
            val length = if (lenSize == 2) u16(bytes, textOffset) else i32(bytes, textOffset)
            String(bytes, textOffset + lenSize, length, Charsets.UTF_8)
        }.getOrNull()
    }

    /** `"31、17、…,1、111、…"` → 宜 / 忌 词条列表（越界索引直接丢弃）。 */
    private fun decode(text: String): YiJi {
        val segments = text.split(',')
        return YiJi(yi = decodeSegment(segments.getOrNull(0)), ji = decodeSegment(segments.getOrNull(1)))
    }

    private fun decodeSegment(segment: String?): List<String> {
        if (segment.isNullOrEmpty()) return emptyList()
        val table = wordTable
        return segment.split('、').mapNotNull { token ->
            val index = token.trim().toIntOrNull() ?: return@mapNotNull null
            table.getOrNull(index)
        }
    }

    private fun u16(bytes: ByteArray, position: Int): Int =
        ((bytes[position].toInt() and 0xFF) shl 8) or (bytes[position + 1].toInt() and 0xFF)

    private fun i32(bytes: ByteArray, position: Int): Int =
        ((bytes[position].toInt() and 0xFF) shl 24) or
            ((bytes[position + 1].toInt() and 0xFF) shl 16) or
            ((bytes[position + 2].toInt() and 0xFF) shl 8) or
            (bytes[position + 3].toInt() and 0xFF)
}
