/*
 * Joker - 微信莫奈取色引擎的 ARSC 结构扫描器
 */

package dev.joker.utils.monet

import dev.joker.utils.WeLogger
import java.io.File
import java.util.zip.ZipFile

/**
 * 极简 ARSC「类型 id 空间」扫描器（纯字节，不依赖 ARSCLib、不做全表解析）。
 *
 * ## 为什么必须有这个东西
 *
 * AOSP 的 `AssetManager2::FindEntryInternal` 在遍历同一个 package group 的每个包时，第一步就是：
 * ```
 *   auto entry_flags = type_spec->GetFlagsForEntryIndex(entry_idx);
 *   if (!entry_flags.has_value()) return base::unexpected(entry_flags.error());   // ← 整次查找直接失败
 * ```
 * 而 `GetFlagsForEntryIndex` 在 `entry_idx >= 本包 TypeSpec 声明的 entryCount` 时返回 `nullopt`。
 * 注意那是 **return**、不是 `continue`（只能「找不到条目」时才 `continue` 退回 base 表）——
 * 也就是说：**只要我们写出的覆盖包 TypeSpec 声明范围盖不住宿主的 entryId，宿主对该 id 的
 * 任何查找都会直接失败**（取名字抛 `Unable to find resource ID`、取 drawable 抛
 * `Resources$NotFoundException`），既不会退回 base 表，也不是「只是不生效」。
 *
 * ## 实机证据链（2026-09-27，joker-2026-09-27.log）
 *
 * - 写包：`runtime package written: aligned 406 entries, types 6=color, 8=drawable, 13=mipmap`，
 *   但 `spec typeId … entryCount … -> …` 一条都没有 —— TypeSpec 的 entryCount 从没被补过
 *   （ARSCLib 的 `SpecTypeFlagsArray.getFlag(id)` 在 `id >= size()` 时直接返回 null，我们的
 *   `markSpecFlags` 于是静默什么都没写，spec 的 entryCount 一直是新建时的 0）。
 * - 紧接着的冒烟校验：`0 条能按名字解析 … 另有 406 条查不到资源名（?/0x7f0607d3 …）`，
 *   而这些 id 在宿主表里都是正常条目（`0x7f0607d3 = color/wj`，已用 androguard 核对微信
 *   8.0.72 的 `resources.arsc`）。
 * - 宿主 8.0.72 真实的 TypeSpec 范围（同一把尺子量出来的）：type 6 = 3075、type 8 = 6544、type 13 = 11。
 *
 * 结论：覆盖包的 TypeSpec entryCount 远小于宿主范围 → 宿主资源查找被我们的包整体打断 →
 * 用户看到的「解析成功、包 applied，但取色 / 圆角 PRO / 角标全都不生效」，严重时直接闪退
 * （历史崩溃栈 `Unable to find resource ID #0x7f08116c` + `Resources$NotFoundException:
 * File res/drawable/ao1.xml`）。
 *
 * ## 因此本文件提供两件事
 *
 * 1. [hostTypeEntryCounts]：量出宿主每个 typeId 的声明范围，写包时把 TypeSpec 补到至少这么大；
 * 2. [inspectPackage]：写完包后**回读**自己写出的 bytes，逐条断言
 *    「spec 范围够大、flags 数组物理容量够大、我们想写的 id 真的在里面」——
 *    任何一个不成立就整包弃掉（宁可不莫奈化，也绝不把会打断宿主资源查找的包喂给微信）。
 */
object MonetArscScanner {

    private const val TAG = "MonetArscScanner"

    private const val CHUNK_TABLE = 0x0002
    private const val CHUNK_TABLE_PACKAGE = 0x0200
    private const val CHUNK_TYPE = 0x0201
    private const val CHUNK_TYPE_SPEC = 0x0202

    /** `ResTable_type.flags`：稀疏条目表（本扫描器不解析，遇到就跳过该 type 的条目枚举）。 */
    private const val TYPE_FLAG_SPARSE = 0x01

    /** `ResTable_type.flags`：偏移表用 16 位（NO_ENTRY = 0xffff）。 */
    private const val TYPE_FLAG_OFFSET16 = 0x02

    /** 单个 APK 里 resources.arsc 的读取上限：宿主最大也就 10MB 量级，超了说明不对劲。 */
    private const val MAX_ARSC_BYTES = 16 * 1024 * 1024

    /**
     * 一个 typeId 的扫描结果。
     *
     * @property specEntryCount TypeSpec 声明的 entryCount（= AOSP 按 entryId 索引 flags 的合法上界）。
     * @property specFlagsCapacity flags 数组的**物理容量**（按 chunk 长度算）。
     *   小于 [specEntryCount] 说明这个包本身是坏的：AOSP 会以
     *   `RES_TABLE_TYPE_SPEC_TYPE too small to hold entries` 丢掉整个包（= 静默不生效）。
     * @property entryIds 这个 type 里真实存在条目的 entryId（升序）；仅在
     *   `collectEntryIds = true` 时填充。
     */
    data class TypeRange(
        val specEntryCount: Int,
        val specFlagsCapacity: Int,
        val entryIds: List<Int>,
        /** TypeSpec chunk 在被扫描字节里的偏移（`-1` = 这个 type 没有 TypeSpec）；用于原地修正 entryCount。 */
        val specOffset: Int = -1,
    )

    data class ScanResult(
        val packageId: Int,
        val types: Map<Int, TypeRange>,
    ) {
        /** 这个包里所有真实存在条目的完整资源 id。 */
        fun resourceIds(): List<Int> = buildList {
            types.forEach { (typeId, range) ->
                range.entryIds.forEach { entryId ->
                    add((packageId shl 24) or (typeId shl 16) or entryId)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------------------------------

    /** 读 APK 里的 `resources.arsc` 原始字节；读不到（或过大）返回 null。 */
    fun readArsc(apkPath: String): ByteArray? = runCatching {
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("resources.arsc") ?: return@use null
            if (entry.size > MAX_ARSC_BYTES) return@use null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }.onFailure { WeLogger.d(TAG, "cannot read resources.arsc from $apkPath", it) }.getOrNull()

    /**
     * 宿主各 typeId 声明的 entryCount（多个 APK 之间取最大值：split 共享同一套 id 空间）。
     *
     * 空 map = 一个都没量出来（调用方必须据此**放弃本次写包**，不能拿一个盖不住宿主范围的
     * 包去注入）。
     */
    fun hostTypeEntryCounts(apkPaths: Collection<String>): Map<Int, Int> {
        val result = HashMap<Int, Int>()
        var scanned = 0
        apkPaths.forEach { path ->
            val bytes = readArsc(path) ?: return@forEach
            val scan = scan(bytes, collectEntryIds = false) ?: return@forEach
            scanned++
            scan.types.forEach { (typeId, range) ->
                if (range.specEntryCount > result.getOrDefault(typeId, 0)) {
                    result[typeId] = range.specEntryCount
                }
            }
        }
        if (scanned == 0) {
            WeLogger.w(
                TAG,
                "宿主资源表的类型范围一个都没量出来（${apkPaths.size} 个 APK）：" +
                    "本次不会写覆盖包 —— 盖不住宿主 entryId 的 TypeSpec 会打断宿主的资源查找",
            )
        } else {
            WeLogger.i(
                TAG,
                "宿主类型 id 空间：" +
                    result.entries.sortedBy { it.key }
                        .joinToString { "${it.key}=${it.value}" } +
                    "（写包时 TypeSpec 至少要声明这么大）",
            )
        }
        return result
    }

    /** 回读一个「我们刚写出来」的运行时包（含逐条 entryId）。 */
    fun inspectPackage(file: File): ScanResult? = runCatching {
        if (!file.isFile) return@runCatching null
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("resources.arsc") ?: return@use null
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            scan(bytes, collectEntryIds = true)
        }
    }.onFailure { WeLogger.d(TAG, "cannot inspect runtime package ${file.name}", it) }.getOrNull()

    // ------------------------------------------------------------------------------------------
    // 结构遍历
    // ------------------------------------------------------------------------------------------

    /**
     * 遍历一个 ARSC（`RES_TABLE_TYPE`）。
     *
     * 全程防越界：任何一处结构对不上就返回 null（调用方按「读不出来」处理），绝不抛异常。
     */
    fun scan(bytes: ByteArray, collectEntryIds: Boolean): ScanResult? {
        if (bytes.size < 12) return null
        if (u16(bytes, 0) != CHUNK_TABLE) return null
        val rootHeaderSize = u16(bytes, 2)
        if (rootHeaderSize < 12) return null
        val rootSize = u32(bytes, 4).coerceAtMost(bytes.size)
        var cursor = rootHeaderSize
        while (cursor + 8 <= rootSize) {
            val type = u16(bytes, cursor)
            val headerSize = u16(bytes, cursor + 2)
            val chunkSize = u32(bytes, cursor + 4)
            if (chunkSize < 8 || headerSize < 8 || cursor + chunkSize > rootSize) return null
            if (type == CHUNK_TABLE_PACKAGE) {
                return scanPackage(bytes, cursor, headerSize, chunkSize, collectEntryIds)
            }
            cursor += chunkSize
        }
        return null
    }

    private fun scanPackage(
        bytes: ByteArray,
        packageOffset: Int,
        packageHeaderSize: Int,
        packageSize: Int,
        collectEntryIds: Boolean,
    ): ScanResult? {
        if (packageHeaderSize < 12) return null
        val packageId = u32(bytes, packageOffset + 8) and 0xff
        if (packageId == 0) return null
        val end = packageOffset + packageSize
        val specEntryCounts = HashMap<Int, Int>()
        val specFlagsCapacity = HashMap<Int, Int>()
        val specOffsets = HashMap<Int, Int>()
        val entryIds = HashMap<Int, MutableList<Int>>()
        var cursor = packageOffset + packageHeaderSize
        while (cursor + 8 <= end) {
            val type = u16(bytes, cursor)
            val headerSize = u16(bytes, cursor + 2)
            val chunkSize = u32(bytes, cursor + 4)
            if (chunkSize < 8 || headerSize < 8 || cursor + chunkSize > end) return null
            when (type) {
                CHUNK_TYPE_SPEC -> {
                    if (headerSize >= 16) {
                        val typeId = bytes[cursor + 8].toInt() and 0xff
                        val count = u32(bytes, cursor + 12)
                        if (typeId != 0 && count > specEntryCounts.getOrDefault(typeId, 0)) {
                            specEntryCounts[typeId] = count
                            specFlagsCapacity[typeId] = (chunkSize - headerSize) / 4
                            specOffsets[typeId] = cursor
                        }
                    }
                }

                CHUNK_TYPE -> {
                    if (collectEntryIds && headerSize >= 20) {
                        val typeId = bytes[cursor + 8].toInt() and 0xff
                        val flags = bytes[cursor + 9].toInt() and 0xff
                        val entryCount = u32(bytes, cursor + 12)
                        if (typeId != 0 && flags and TYPE_FLAG_SPARSE == 0 && entryCount in 1..0xffff) {
                            val sixteen = flags and TYPE_FLAG_OFFSET16 != 0
                            val absent = if (sixteen) 0xffff else -1
                            val list = entryIds.getOrPut(typeId) { ArrayList() }
                            for (index in 0 until entryCount) {
                                val offset = if (sixteen) {
                                    val at = cursor + headerSize + index * 2
                                    if (at + 2 > cursor + chunkSize) break
                                    u16(bytes, at)
                                } else {
                                    val at = cursor + headerSize + index * 4
                                    if (at + 4 > cursor + chunkSize) break
                                    u32(bytes, at)
                                }
                                if (offset != absent) list.add(index)
                            }
                        }
                    }
                }
            }
            cursor += chunkSize
        }
        val types = LinkedHashMap<Int, TypeRange>()
        val allTypeIds = LinkedHashSet<Int>()
        allTypeIds += specEntryCounts.keys
        allTypeIds += specFlagsCapacity.keys
        allTypeIds += entryIds.keys
        allTypeIds.forEach { typeId ->
            types[typeId] = TypeRange(
                specEntryCount = specEntryCounts.getOrDefault(typeId, 0),
                specFlagsCapacity = specFlagsCapacity.getOrDefault(typeId, 0),
                entryIds = entryIds[typeId]?.sorted() ?: emptyList(),
                specOffset = specOffsets.getOrDefault(typeId, -1),
            )
        }
        return ScanResult(packageId, types)
    }

    /**
     * 把 [bytes] 里每个 TypeSpec 的声明范围补到 [required]，**必要时物理插入零填充的 flags**。
     *
     * 为什么不能只用 ARSCLib 的 `SpecBlock.setEntryCount()`：实测（kotlinc + ARSCLib 1.4.0 本地跑）
     * `setEntryCount(3075)` 之后调用 `ApkModule.writeApk()`，写出来的包仍然是 2829 ——
     * `writeApk`/`refreshTable` 会用 TypeBlock 里的最大 entryId 把 spec 范围**重算回去**。
     * 而 AOSP 的校验是 `headerSize + entryCount*4 <= chunkSize`，光把数字改大而不加长 flags
     * 数组会让整个包被丢掉（静默不生效）。所以这里直接做字节手术：
     *
     *  1. 对每个需要变大的 TypeSpec：在它末尾插入 `(want - capacity) * 4` 个 0 字节；
     *  2. 同步更新 spec chunk size、package chunk size、table chunk size 三个长度字段；
     *  3. 改写 spec 的 entryCount。
     *
     * 之所以插入是安全的：ARSC 里所有内部偏移都是**相对**的（`ResTable_type.entriesStart` 相对
     * type chunk、string pool 的 offset 相对 string pool、`ResTable_map_entry.parent` 是资源 id），
     * 没有任何绝对文件偏移，所以中间插字节不会打乱其它 chunk 的解析。
     *
     * @return 新的字节数组；`null` = 有 type 缺 TypeSpec 或结构对不上（调用方必须弃包）。
     */
    fun growSpecFlags(bytes: ByteArray, required: Map<Int, Int>): ByteArray? {
        if (bytes.size < 12 || u16(bytes, 0) != CHUNK_TABLE) return null
        val tableHeaderSize = u16(bytes, 2)
        val tableSize = u32(bytes, 4)
        if (tableHeaderSize < 12 || tableSize > bytes.size) return null

        var packageOffset = -1
        var packageHeaderSize = 0
        var packageSize = 0
        var cursor = tableHeaderSize
        while (cursor + 8 <= tableSize) {
            val type = u16(bytes, cursor)
            val headerSize = u16(bytes, cursor + 2)
            val chunkSize = u32(bytes, cursor + 4)
            if (chunkSize < 8 || headerSize < 8 || cursor + chunkSize > tableSize) return null
            if (type == CHUNK_TABLE_PACKAGE) {
                packageOffset = cursor
                packageHeaderSize = headerSize
                packageSize = chunkSize
                break
            }
            cursor += chunkSize
        }
        if (packageOffset < 0 || packageHeaderSize < 12) return null

        val specs = ArrayList<SpecChunk>()
        val packageEnd = packageOffset + packageSize
        var at = packageOffset + packageHeaderSize
        while (at + 8 <= packageEnd) {
            val type = u16(bytes, at)
            val headerSize = u16(bytes, at + 2)
            val chunkSize = u32(bytes, at + 4)
            if (chunkSize < 8 || headerSize < 8 || at + chunkSize > packageEnd) return null
            if (type == CHUNK_TYPE_SPEC && headerSize >= 16) {
                specs += SpecChunk(
                    typeId = bytes[at + 8].toInt() and 0xff,
                    offset = at,
                    headerSize = headerSize,
                    size = chunkSize,
                    declaredCount = u32(bytes, at + 12),
                )
            }
            at += chunkSize
        }

        val grow = HashMap<SpecChunk, Int>()
        var totalDelta = 0
        for ((typeId, want) in required) {
            if (want <= 0) continue
            val spec = specs.firstOrNull { it.typeId == typeId } ?: return null
            val capacity = (spec.size - spec.headerSize) / 4
            val need = want - capacity
            if (need > 0) {
                grow[spec] = need * 4
                totalDelta += need * 4
            }
        }

        val out: ByteArray
        val newOffsets = HashMap<SpecChunk, Int>()
        if (totalDelta == 0) {
            out = bytes.copyOf()
            specs.forEach { newOffsets[it] = it.offset }
        } else {
            out = ByteArray(bytes.size + totalDelta)
            var srcPos = 0
            var dstPos = 0
            var accumulated = 0
            specs.sortedBy { it.offset }.forEach { spec ->
                val end = spec.offset + spec.size
                val len = end - srcPos
                if (len > 0) {
                    System.arraycopy(bytes, srcPos, out, dstPos, len)
                    srcPos = end
                    dstPos += len
                }
                newOffsets[spec] = spec.offset + accumulated
                val delta = grow[spec] ?: 0
                if (delta > 0) {
                    java.util.Arrays.fill(out, dstPos, dstPos + delta, 0)
                    dstPos += delta
                    accumulated += delta
                }
            }
            System.arraycopy(bytes, srcPos, out, dstPos, bytes.size - srcPos)

            putI32(out, 4, tableSize + totalDelta)
            putI32(out, packageOffset + 4, packageSize + totalDelta)
        }

        for ((typeId, want) in required) {
            if (want <= 0) continue
            val spec = specs.firstOrNull { it.typeId == typeId } ?: return null
            val offset = newOffsets[spec] ?: return null
            val delta = grow[spec] ?: 0
            if (delta > 0) putI32(out, offset + 4, spec.size + delta)
            val target = maxOf(want, spec.declaredCount)
            if (u32(out, offset + 12) != target) putI32(out, offset + 12, target)
        }
        return out
    }

    /** ARSC 里一个 TypeSpec chunk 的布局。 */
    private class SpecChunk(
        val typeId: Int,
        val offset: Int,
        val headerSize: Int,
        val size: Int,
        val declaredCount: Int,
    )

    /**
     * 原地把 TypeSpec 的 entryCount 改写成 [wanted]（**只改数字，不动任何 size**）。
     *
     * 前提：`TypeSpec` chunk 的物理容量 >= wanted（否则 AOSP 会以 `too small to hold entries`
     * 丢掉整包 —— 这种情况一律返回失败，让调用方弃包，绝不能写出一个「数字大、数组小」的包）。
     *
     * 为什么要在序列化之后再自己扫一遍：ARSCLib 从零建表时并不保证把 spec 的 entryCount
     * 跟着条目一起涨（[SpecTypeFlagsArray.getFlag] 在 `id >= size()` 时直接返回 null，
     * 于是「补 flag」静默失败、entryCount 停在新建时的值）。这个函数是最后一道保险：
     * 不看任何库的内部状态，只认写出来的字节。
     *
     * @return 修正过的 typeId→entryCount；`null` = 有 type 的容量不够，必须弃包。
     */
    fun patchSpecEntryCounts(
        bytes: ByteArray,
        wanted: Map<Int, Int>,
    ): Map<Int, Int>? {
        val scan = scan(bytes, collectEntryIds = false) ?: return null
        val applied = LinkedHashMap<Int, Int>()
        wanted.forEach { (typeId, count) ->
            val range = scan.types[typeId]
            if (range == null || range.specOffset < 0) {
                if (count > 0) return null
                return@forEach
            }
            if (range.specEntryCount >= count) {
                applied[typeId] = range.specEntryCount
                return@forEach
            }
            if (range.specFlagsCapacity < count) return null
            putI32(bytes, range.specOffset + 12, count)
            applied[typeId] = count
        }
        return applied
    }

    // ------------------------------------------------------------------------------------------
    // 小工具（全部小端、全部带边界检查）
    // ------------------------------------------------------------------------------------------

    private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
        if (offset < 0 || offset + 4 > bytes.size) return
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }
}
