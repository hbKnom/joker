package dev.ujhhgtg.wekit.utils.monet

import com.reandroid.apk.ApkModule
import com.reandroid.apk.ResFile
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

object MonetApkResourceGraphLoader {

    private const val TAG = "MonetApkResourceGraphLoader"

    fun load(
        apkPaths: List<File>,
        targetPackage: String,
        onProgress: (detail: String, completed: Int, total: Int) -> Unit = { _, _, _ -> },
    ): MonetResourceGraph {
        val resources = linkedMapOf<Int, MutableResource>()
        val xmlDocuments = mutableListOf<OwnedXml>()

        apkPaths.forEachIndexed { index, apk ->
            onProgress("打开 ${apk.name}", index, apkPaths.size)
            ApkModule.loadApkFile(apk).apply { setLoadDefaultFramework(false) }.use { module ->
                // ABI/code-only splits have no resources.arsc. Check entry presence rather than
                // catching parser errors: an existing but malformed table must still fail.
                if (!module.hasTableBlock()) {
                    onProgress("跳过 ${apk.name}：不含资源表", index, apkPaths.size)
                    return@use
                }
                val resFiles = module.listResFiles().toList()
                // 全量预计算 fileStructure() 等于把整个 res 目录读一遍（PNG 还要解 IDAT 算统计），
                // 实机日志里单次解析 100 秒以上、启动期主线程卡到 2.6 秒，绝大部分耗在这里，
                // 而且会在解析线程上制造大量垃圾对象引发 GC 风暴。改成按需 + 记忆化：
                // 只有资源值真的引用到的文件才会被读。见 SKILL「莫奈解析：文件结构按需计算」。
                val structures = LazyFileStructures(resFiles)
                val tableStart = System.nanoTime()
                onProgress("解析 ${apk.name} 的资源表", index, apkPaths.size)
                // 后面解析二进制 XML 时要用宿主 PackageBlock 做值解析，所以在这里接出来。
                var hostPackage: PackageBlock? = null
                module.tableBlock.listPackages()
                    .filter { it.name == targetPackage }
                    .forEach { pkg ->
                        hostPackage = pkg
                        pkg.getResources().asSequence().forEach { resource ->
                            resources.merge(resource, apk, structures)
                        }
                    }
                WeLogger.i(
                    TAG,
                    "${apk.name}: ${resources.size} 个资源条目，表遍历 ${(System.nanoTime() - tableStart) / 1_000_000} ms",
                )

                val xmlStart = System.nanoTime()
                val binaryXmlCount = resFiles.count { it.isBinaryXml }
                onProgress("解析 ${apk.name} 的 $binaryXmlCount 个二进制 XML", index, apkPaths.size)
                val candidates = resFiles.mapNotNull { resFile ->
                    val owners = resFile.asSequence()
                        .filter {
                            it.packageBlock.name == targetPackage &&
                                it.typeName in MONET_XML_RESOURCE_TYPES
                        }
                        .map { entry ->
                            XmlIdentity(entry.resourceId, entry.resConfig.qualifiers, resFile.filePath)
                        }
                        .toList()
                    if (owners.isEmpty() || !resFile.isBinaryXml) null else resFile to owners
                }
                val stats = hostPackage?.let { parseXmlInto(candidates, it, xmlDocuments) } ?: XmlParseStats()
                WeLogger.i(
                    TAG,
                    "${apk.name}: $binaryXmlCount 个二进制 XML（候选 ${candidates.size}），" +
                        "解析 ${stats.parsed} 个、" +
                        "${(System.nanoTime() - xmlStart) / 1_000_000} ms" +
                        "，文件结构按需读了 ${structures.readCount} 个" +
                        if (stats.problems > 0) {
                            "，跳过非二进制 ${stats.nonBinary} 个/读取失败 ${stats.readFailed} 个/解析失败 ${stats.failures} 个"
                        } else {
                            ""
                        } +
                        if (stats.budgetExhausted) "，已达 ${XML_MAX_MILLIS} ms 预算上限（其余本次跳过）" else "" +
                        if (stats.lowMemory) "，剩余堆不足提前收工（其余本次跳过）" else "",
                )
            }
            onProgress("完成 ${apk.name}", index + 1, apkPaths.size)
        }

        val definitions = linkedMapOf<XmlIdentity, MonetXmlElement>()
        xmlDocuments.forEach { ownedXml ->
            val definition = ownedXml.xml.root
            val existing = definitions[ownedXml.identity]
            when {
                existing == null -> definitions[ownedXml.identity] = definition
                // 同一个 id 被多个 APK 重复定义且内容不同（厂商 overlay / 重复 split）。
                // 旧实现直接抛错，会让整次「莫奈解析」失败，用户看到的就是解析出问题。
                existing != definition -> WeLogger.w(
                    TAG,
                    "conflicting binary XML for ${ownedXml.identity}, keeping the first definition",
                )
            }
        }
        val xmlByOwner = definitions.entries.groupBy(
            keySelector = { it.key.ownerId },
            valueTransform = Map.Entry<XmlIdentity, MonetXmlElement>::value,
        )
        return MonetResourceGraph(resources.values.map(MutableResource::toNode), xmlByOwner)
    }

    /**
     * 解析二进制 XML：整条解析链最贵的一步（实机 11000+ 个文件）。
     *
     * **必须串行 —— 这是 2026-09-26 实机原生崩溃的根因。**
     *
     * 第 14/15 轮把这一步改成 4 线程并行之后，实机日志表现为：每次「开始解析」后 1–10 秒内
     * 微信必崩原生 SIGBUS/SIGSEGV，故障帧落在 libhwui / libcso.so 这些**与解析毫无关系**的
     * 原生库里，故障地址里是 UTF-16 片段（`apk/res`、`Size`）＝ 典型的原生内存被写坏；
     * 同一批解析还报出大量 `ucnv_toUnicode failed: U_ILLEGAL_ARGUMENT_ERROR`
     * （ICURT 的 UTF-16 解码接口，同样的并发误用）。原因就是 ARSCLib 的 `PackageBlock` /
     * `ResXmlDocument` 会在**首次访问时惰性建缓存**（`getResource()` 的映射、字符串池解码），
     * 多个线程共享同一个 `hostPackage` 触发并发惰性初始化，内部结构被并发破坏。
     * 串行化之后既不再崩，解析失败数也回落到 0 量级。
     *
     * 另外三条保护（都是「解析出问题也绝不许影响微信」）：
     *  - **先校验二进制 XML 头**：明文 XML / 空流 / 截断流直接跳过，不喂给解析器；
     *  - **整个阶段有硬时间预算**：超预算就带着已完成的部分继续（缺席的角色如实统计、
     *    由调用方降级处理），不再无界地占用宿主进程；
     *  - **每 N 个文件让出一次 CPU**：长时间独占后台线程同样会被用户看成卡顿。
     */
    private fun parseXmlInto(
        candidates: List<Pair<ResFile, List<XmlIdentity>>>,
        hostPackage: PackageBlock,
        out: MutableList<OwnedXml>,
    ): XmlParseStats {
        if (candidates.isEmpty()) return XmlParseStats()
        val deadline = System.nanoTime() + XML_MAX_MILLIS * 1_000_000L
        var sliceStart = System.nanoTime()
        var parsed = 0
        var nonBinary = 0
        var readFailed = 0
        var failures = 0
        var exhausted = false
        var lowMemory = false
        var yields = 0
        for ((index, candidate) in candidates.withIndex()) {
            if (System.nanoTime() >= deadline) {
                exhausted = true
                break
            }
            // 内存护栏：解析结果（每个 XML 一棵树）全部留在宿主堆里，微信的堆上限是有限的。
            // 剩余堆不足就带着已完成的部分收工 —— 宁可少解析几个 XML，也不能把宿主搞 OOM。
            if (index % XML_HEAP_CHECK_EVERY == 0 && freeHeapBytes() < XML_MIN_FREE_HEAP_BYTES) {
                lowMemory = true
                break
            }
            // 占空比分片：连续跑 XML_SLICE_MILLIS 就让出 XML_REST_MILLIS。
            // 解析在宿主进程里跑，长时间 100% 占一个核同样会被用户看成卡顿；
            // 分片之后总耗时略长，但峰值占用明显更低，也不再和微信主线程抢 CPU。
            if (System.nanoTime() - sliceStart >= XML_SLICE_NANOS) {
                yields++
                runCatching { Thread.sleep(XML_REST_MILLIS) }
                sliceStart = System.nanoTime()
            }
            if (index % XML_YIELD_EVERY == 0) runCatching { Thread.sleep(XML_YIELD_MILLIS) }
            val (file, owners) = candidate
            val bytes = runCatching { file.inputSource.openStream().use { it.readBytes() } }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                readFailed++
                continue
            }
            if (!isBinaryXml(bytes)) {
                nonBinary++
                continue
            }
            val xml = runCatching {
                // apply 的接收者是 ResXmlDocument，这里的 packageBlock 是它的属性
                //（ARSCLib 的 setPackageBlock）；右边的 hostPackage 才是参数。
                val document = ResXmlDocument().apply { packageBlock = hostPackage }
                document.readBytes(ByteArrayInputStream(bytes))
                MonetBinaryXmlReader.read(document)
            }.getOrElse { t ->
                failures++
                if (failures <= XML_FAILURE_LOG_LIMIT) {
                    WeLogger.w(TAG, "二进制 XML 解析失败，已跳过：${t.message?.take(120)}")
                }
                null
            } ?: continue
            parsed++
            owners.forEach { identity -> out += OwnedXml(identity, xml) }
        }
        if (yields > 0) WeLogger.d(TAG, "XML 解析分片让出 $yields 次，避免长时间占用 CPU")
        if (lowMemory) WeLogger.w(TAG, "剩余堆不足，XML 解析提前收工（已解析 $parsed 个）")
        return XmlParseStats(parsed, failures, nonBinary, readFailed, exhausted, lowMemory)
    }

    /**
     * 二进制 XML 头校验：RES_XML_TYPE(0x0003) + headerSize 8 + chunkSize 落在缓冲区内。
     * 宿主 `res/` 里混着明文 XML 与截断条目，喂给二进制解析器只会制造异常噪声。
     */
    private fun isBinaryXml(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val type = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
        if (type != 0x0003) return false
        val headerSize = (bytes[2].toInt() and 0xff) or ((bytes[3].toInt() and 0xff) shl 8)
        if (headerSize != 8) return false
        val chunkSize = (bytes[4].toInt() and 0xff) or ((bytes[5].toInt() and 0xff) shl 8) or
            ((bytes[6].toInt() and 0xff) shl 16) or ((bytes[7].toInt() and 0xff) shl 24)
        return chunkSize in 8..bytes.size
    }

    private data class XmlParseStats(
        val parsed: Int = 0,
        val failures: Int = 0,
        val nonBinary: Int = 0,
        val readFailed: Int = 0,
        val budgetExhausted: Boolean = false,
        val lowMemory: Boolean = false,
    ) {
        val problems: Int get() = failures + nonBinary + readFailed
    }

    /** 当前可用堆（宿主微信进程的 Java 堆）。 */
    private fun freeHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    }

    /** XML 阶段总预算：超时就用已完成的部分继续解析角色，绝不影响微信。 */
    private const val XML_MAX_MILLIS = 120_000L

    /** 占空比分片：连续解析 [XML_SLICE_NANOS] 后让出 [XML_REST_MILLIS]。 */
    private const val XML_SLICE_NANOS = 1_500_000_000L
    private const val XML_REST_MILLIS = 800L

    /** 内存护栏：剩余堆低于这个值就停止解析（宿主堆有限，别把微信搞 OOM）。 */
    private const val XML_MIN_FREE_HEAP_BYTES = 64L * 1024 * 1024
    private const val XML_HEAP_CHECK_EVERY = 64

    private const val XML_YIELD_EVERY = 32
    private const val XML_YIELD_MILLIS = 1L
    private const val XML_FAILURE_LOG_LIMIT = 3

    private fun MutableMap<Int, MutableResource>.merge(
        resource: ResourceEntry,
        apk: File,
        structures: LazyFileStructures,
    ) {
        if (resource.isEmpty) return
        val id = resource.resourceId
        val type = resource.type ?: return
        val name = resource.name ?: return
        val key = MonetResourceKey(type = type, name = name)
        val merged = getOrPut(id) { MutableResource(id, key) }
        if (merged.key != key) {
            // 同一 id 在不同 APK 里换了身份：以先到者为准，保留旧身份继续合并值。
            WeLogger.w(
                TAG,
                "resource 0x${id.toUInt().toString(16)} changes identity from ${merged.key} to $key in $apk",
            )
        }
        resource.asSequence().forEach { entry ->
            val qualifiers = entry.resConfig.qualifiers
            val value = if (entry.isComplex) {
                val complex = entry.resTableMapEntry ?: return@forEach
                MonetResourceValue.Complex(
                    parentId = complex.parentId,
                    items = complex.iterator().asSequence().mapNotNull { item ->
                        item.toMonetValue(structures)?.let { MonetComplexValue(item.nameId, it) }
                    }.toList(),
                )
            } else {
                entry.resValue?.toMonetValue(structures) ?: return@forEach
            }
            val existing = merged.valuesByQualifiers[qualifiers]
            when {
                existing == null -> merged.valuesByQualifiers[qualifiers] = value
                // 同一个 id + qualifiers 在不同 APK 里给了不同的值：保留先到的，
                // 解析继续（判定规则只看「有没有这个值」，不看它被定义了两次）。
                existing != value -> WeLogger.w(
                    TAG,
                    "conflicting values for 0x${id.toUInt().toString(16)} ($key) qualifiers '$qualifiers' in $apk",
                )
            }
        }
    }

    /** 返回 null 表示这条 ARSC 值无法解读：调用方跳过它，而不是让整次解析中断。 */
    private fun ValueItem.toMonetValue(structures: LazyFileStructures): MonetResourceValue? {
        val valueType = valueType ?: return null
        if (valueType.isReference) return MonetResourceValue.Reference(data, valueType.name)
        if (valueType == ValueType.STRING) {
            val stringValue = valueAsString
            if (stringValue != null && (structures.contains(stringValue) || stringValue.startsWith("res/"))) {
                return MonetResourceValue.File(stringValue, structures[stringValue])
            }
            if (stringValue != null) return MonetResourceValue.Text(stringValue)
        }
        return MonetResourceValue.Literal(
            valueType = valueType.name,
            data = Integer.toUnsignedLong(data),
        )
    }

    /**
     * 按需、记忆化的 `res` 文件结构查询。
     *
     * [com.reandroid.apk.ResFile.fileStructure] 对 PNG 要读 4096 字节文件头、必要时还要解 IDAT 算
     * 像素统计；旧实现用 `resFiles.associate { it.filePath to it.fileStructure() }` 对所有文件预计算
     * （`associate` 是急切的），一次解析因此要多读上千个文件、并在解析线程上产生大量临时对象。
     * 解析阶段真正需要的只是「值引用到的那些文件」的结构，所以这里延后到第一次访问。
     */
    private class LazyFileStructures(resFiles: List<com.reandroid.apk.ResFile>) {

        private val byPath = resFiles.associateBy { it.filePath }
        private val cache = HashMap<String, MonetFileStructure?>()

        var readCount = 0
            private set

        fun contains(path: String): Boolean = byPath.containsKey(path)

        operator fun get(path: String): MonetFileStructure? {
            if (cache.containsKey(path)) return cache[path]
            readCount++
            val structure = byPath[path]?.fileStructure()
            cache[path] = structure
            return structure
        }
    }

    private fun com.reandroid.apk.ResFile.fileStructure(): MonetFileStructure {
        val extension = inputSource.extension.uppercase()
        if (!extension.endsWith("PNG")) return MonetFileStructure(extension)
        val format = if (extension.contains(".9.")) "9PNG" else "PNG"
        val header = inputSource.getBytes(4096)
        if (header.size < 26 || header[0].toInt() and 0xff != 0x89 || String(header, 1, 3) != "PNG") {
            return MonetFileStructure(format)
        }
        fun intAt(offset: Int): Int = header[offset].toInt() and 0xff shl 24 or
            (header[offset + 1].toInt() and 0xff shl 16) or
            (header[offset + 2].toInt() and 0xff shl 8) or
            (header[offset + 3].toInt() and 0xff)
        var offset = 8
        var firstDataLength: Int? = null
        var ninePatchLength: Int? = null
        val compressed = ByteArrayOutputStream()
        while (offset + 12 <= header.size) {
            val length = intAt(offset)
            if (length < 0) break
            val type = String(header, offset + 4, 4)
            if (type == "IDAT" && firstDataLength == null) firstDataLength = length
            if (type == "npTc") ninePatchLength = length
            if (offset + 12L + length > header.size) break
            if (type == "IDAT") compressed.write(header, offset + 8, length)
            offset += length + 12
            if (type == "IEND") break
        }
        val pixels = if (intAt(16).toLong() * intAt(20) <= 8192 && header[24].toInt() == 8) {
            pixelStatistics(intAt(16), intAt(20), header[25].toInt() and 0xff, compressed.toByteArray())
        } else null
        return MonetFileStructure(
            format,
            intAt(16),
            intAt(20),
            header[25].toInt() and 0xff,
            firstDataLength,
            ninePatchLength,
            pixels?.sampleSum,
            pixels?.alphaSum,
            pixels?.distinctSamples,
            pixels?.sha256,
        )
    }

    private fun pixelStatistics(
        width: Int,
        height: Int,
        colorType: Int,
        compressed: ByteArray,
    ): PixelStatistics? {
        val bytesPerPixel = when (colorType) {
            0 -> 1
            4 -> 2
            else -> return null
        }
        val stride = width * bytesPerPixel
        val inflated = runCatching {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        }.getOrNull() ?: return null
        if (inflated.size < (stride + 1) * height) return null
        val previous = ByteArray(stride)
        val current = ByteArray(stride)
        val samples = hashSetOf<Int>()
        val digest = MessageDigest.getInstance("SHA-256")
        var sampleSum = 0L
        var alphaSum = 0L
        var source = 0
        repeat(height) {
            val filter = inflated[source++].toInt() and 0xff
            for (column in 0 until stride) {
                val raw = inflated[source++].toInt() and 0xff
                val left = if (column >= bytesPerPixel) current[column - bytesPerPixel].toInt() and 0xff else 0
                val up = previous[column].toInt() and 0xff
                val upperLeft = if (column >= bytesPerPixel) previous[column - bytesPerPixel].toInt() and 0xff else 0
                current[column] = (raw + when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upperLeft)
                    else -> return null
                }).toByte()
            }
            for (pixel in 0 until width) {
                val sample = current[pixel * bytesPerPixel].toInt() and 0xff
                val alpha = if (bytesPerPixel == 2) current[pixel * bytesPerPixel + 1].toInt() and 0xff else 255
                sampleSum += sample
                alphaSum += alpha
                samples += sample shl 8 or alpha
            }
            digest.update(current)
            current.copyInto(previous)
        }
        return PixelStatistics(
            sampleSum,
            alphaSum,
            samples.size,
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val prediction = left + up - upperLeft
        val leftDistance = kotlin.math.abs(prediction - left)
        val upDistance = kotlin.math.abs(prediction - up)
        val upperLeftDistance = kotlin.math.abs(prediction - upperLeft)
        return if (leftDistance <= upDistance && leftDistance <= upperLeftDistance) left
        else if (upDistance <= upperLeftDistance) up else upperLeft
    }

    private data class PixelStatistics(
        val sampleSum: Long,
        val alphaSum: Long,
        val distinctSamples: Int,
        val sha256: String,
    )

    private data class MutableResource(
        val id: Int,
        val key: MonetResourceKey,
        val valuesByQualifiers: MutableMap<String, MonetResourceValue> = linkedMapOf(),
    ) {
        fun toNode() = MonetResourceNode(
            id = id,
            key = key,
            values = valuesByQualifiers.toSortedMap().map { (qualifiers, value) ->
                MonetConfiguredValue(qualifiers, value)
            },
        )
    }

    private data class XmlIdentity(
        val ownerId: Int,
        val qualifiers: String,
        val path: String,
    )

    private data class OwnedXml(
        val identity: XmlIdentity,
        val xml: MonetBinaryXml,
    )

    private val MONET_XML_RESOURCE_TYPES = setOf("color", "drawable", "layout")
}
