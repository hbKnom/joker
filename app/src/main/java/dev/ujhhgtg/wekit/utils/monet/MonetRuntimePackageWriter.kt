package dev.ujhhgtg.wekit.utils.monet

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.BlockInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.coder.ComplexUtil
import com.reandroid.arsc.coder.UnitDimension
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.util.zip.ZipEntry

/**
 * Writes the runtime resource package that [MonetEngine] hands to
 * `android.content.res.loader.ResourcesProvider.loadFromApk`.
 *
 * This replaces the 09-19 RRO/Magisk pipeline (`MonetOverlayApkWriter` + `MonetModulePackager` +
 * `MonetApkSigner`). Consequences that drove the rewrite:
 *
 *  - No `AndroidManifest.xml` and no `overlay` element: the package is not installed, it is loaded
 *    straight out of WeChat's cache dir by the same process that owns the `AssetManager`.
 *  - No signing: `ResourcesProvider` is not `PackageManager`, so v1/v2 signatures are dead weight.
 *  - The package must be named after the *host* (`com.tencent.mm`), not `monet.*`, otherwise the
 *    provider cannot bind the overriding entries to the base package.
 *  - Publish atomically through a sibling temp file: scoped storage refuses `unlink + create` on an
 *    inode owned by the storage holder, but `rename()` over it works.
 *
 * **铁律：覆盖条目必须写在宿主原来的资源 id 上。**
 *
 * `ResourcesProvider` 是**按 id** 合并的：包名（`com.tencent.mm`）对齐只决定「能不能绑上」，
 * 真正决定「替换哪一个资源」的是 `(packageId, typeId, entryId)` 三元组。早期实现用
 * `PackageBlock.getOrCreate(qualifiers, type, name)` 按**名字**建表，ARSCLib 会给这个全新表
 * 自己分配 typeId / entryId（从 1 开始连续分配），于是：
 *
 *  - 颜色写到了宿主 `typeId=1` 的条目上 —— 那通常是 `anim`/别的类型，颜色没落到该改的地方
 *    （用户看到的「莫奈不生效」），
 *  - 更糟的是把宿主的 `anim` 条目覆盖成 `COLOR_RGB8`，微信一取页面切换动画插值器就抛
 *    `Resources$NotFoundException: Resource ID #0x7f010092 type #0x1d is not valid`，
 *    直接闪退（2026-09-25 实机日志 `MonetResourceResolver: resolved 231 roles` 之后必崩）。
 *
 * 所以这里只走 [AlignedEntryWriter]：用 [MonetBinding.id]（解析阶段从宿主资源表读到的真实 id）
 * 拆出 typeId / entryId，在**同一个 typeId 与 entryId** 上建条目，并交叉校验同一个 typeId 不会
 * 对应两个不同的类型名（多 APK 合并时 id 撞车的情况）。名字为合成资源（id==0，例如自适应图标
 * 的三个图层）时按 [syntheticId] 分配宿主未占用的高 entryId。
 */
object MonetRuntimePackageWriter {

    private const val TAG = "MonetRuntimePackageWriter"

    /**
     * Creates the runtime package at [output]. Existing files are replaced atomically.
     *
     * @param packageName must be the host package (`com.tencent.mm`) for the provider to bind.
     */
    fun write(
        output: File,
        packageName: String,
        plan: MonetOverlayPlan,
        hostReference: ((type: String, name: String) -> Int?)? = null,
        hostDrawableIsRealFile: ((path: String) -> Boolean)? = null,
        excludedIds: Set<Int> = emptySet(),
    ): Boolean {
        if (plan.isEmpty) {
            // 一个角色都没解析出来时不该抛异常打断整条流程：调用方会把它当成
            // 「本次没有可应用的内容」处理（用户看到的是解析结果，而不是崩溃）。
            WeLogger.w(TAG, "runtime package skipped: empty plan")
            return false
        }
        val tmp = File(
            output.parentFile,
            ".${output.name}.tmp-${Thread.currentThread().id}-${System.nanoTime()}",
        )
        val writtenFiles = HashSet<String>()
        try {
            if (!writeTo(
                    tmp,
                    packageName,
                    plan,
                    hostReference,
                    hostDrawableIsRealFile,
                    excludedIds,
                    writtenFiles,
                )
            ) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(output)) {
                // rename rejected (some FUSE/MediaProvider layers do not permit it); a byte copy is
                // idempotent for our own file, so fall back to it.
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
            // 写包之后逐条复核「条目里引用的资源文件真的在包里」。
            //
            // 宿主取 drawable 的方式是：读条目值 -> 得到 `res/xxx/yyy.xml` 路径 -> 去（合并后的）
            // 资源包里找这个文件。只要文件缺失，宿主就抛
            //   Resources$NotFoundException: File res/drawable/ao1.xml from drawable resource ID #0x7f08116c
            // 并**直接崩进程**（实机 wekit-crash-2026-09-26_13-33-02 / 13-43-03 就是这条）。
            // ARSCLib 写包时是否真的落地这些非资源表文件不由我们掌控，所以这里不信它，
            // 用 zip 清单自己查一遍：任何一条缺文件就整包放弃（宁可不莫奈化，也不能喂坏包）。
            val missing = missingResourceFiles(output, writtenFiles)
            if (missing != null) {
                WeLogger.e(TAG, "runtime package rejected: $missing")
                output.delete()
                return false
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return true
    }

    /**
     * 为 WeKit 合成资源（自适应图标的背景/前景/单色图层）借一个宿主不可能占用的槽位。
     *
     * 槽位 = 宿主同类型里最高的 entryId 之后，所以宿主同类型不存在这个 entryId，
     * 覆盖它不可能碰到别人的资源（这是 [AlignedEntryWriter] 拒绝 id==0 之后唯一的合法来源）。
     */
    fun syntheticId(typeId: Int, hostHighestEntryId: Int, sequence: Int): Int {
        val entryId = (hostHighestEntryId + 1 + sequence).coerceIn(1, 0xfffe)
        return (HOST_PACKAGE_ID shl 24) or ((typeId and 0xff) shl 16) or (entryId and 0xffff)
    }

    /**
     * 复核 [expected] 里的每个路径都真的存在于刚写出的包 [apk] 中。
     *
     * 返回非 null = 必须放弃这个包（缺文件 = 宿主取资源必崩）。
     */
    private fun missingResourceFiles(apk: File, expected: Set<String>): String? {
        if (expected.isEmpty()) return null
        val absent = runCatching {
            java.util.zip.ZipFile(apk).use { zip ->
                expected.firstOrNull { zip.getEntry(it) == null }
            }
        }.getOrElse { t -> return "cannot inspect written package: ${t.message}" }
        return absent?.let { "resource file missing in package: $it" }
    }

    private fun writeTo(
        output: File,
        packageName: String,
        plan: MonetOverlayPlan,
        hostReference: ((type: String, name: String) -> Int?)?,
        hostDrawableIsRealFile: ((path: String) -> Boolean)?,
        excludedIds: Set<Int>,
        outFiles: MutableSet<String>,
    ): Boolean {
        val apk = ApkModule()
        val table = TableBlock()
        apk.setTableBlock(table)
        val pkg = table.newPackage(0x7f, packageName)

        // spec flag 以前按 (type, name) 记录、写包前再按名字回查条目。名字回查在
        // 「新建包里 typeId→类型名 的映射被 refreshFull() 重建」时会返回 null，
        // 于是 requireNotNull 把整包 build 掉 —— 用户看到的就是
        // 「building resource package 阶段分析失败：Required value was null.」。
        // 改成直接记条目自己的 resourceId：不可能回查为空，且与宿主「按 id 覆盖」的方式一致。
        val specFlags = HashMap<Int, Int>()
        // 每个 typeId 里我们真正写到过的**最大 entryId + 1**。写包最后用它把 TypeSpec 的
        // entryCount 补齐（见 freezeCanonicalTable）：AOSP 的 AssetManager2 是拿 TypeSpec 的
        // flags 数组按 entryId 索引的，spec 声明的范围盖不住条目时，宿主查得到值、却查不到
        // 资源名 —— 崩溃栈就是 wekit-crash-2026-09-26_13-33-02 的那条。
        val specEntryCounts = HashMap<Int, Int>()
        fun record(entry: Entry, qualifiers: String) {
            specFlags[entry.resourceId] =
                specFlags.getOrDefault(entry.resourceId, 0) or qualifierFlags(qualifiers)
            val entryTypeId = (entry.resourceId ushr 16) and 0xff
            val entryId = entry.resourceId and 0xffff
            val wanted = entryId + 1
            if (wanted > specEntryCounts.getOrDefault(entryTypeId, 0)) {
                specEntryCounts[entryTypeId] = wanted
            }
        }

        // 同一次计划里自己写的资源（自适应图标图层等）在写 XML 时可能还没建条目，
        // 先把「会写进去的 (type,name) → id」建表，保证 XML 里的具名引用不会解析成空。
        val plannedIds = HashMap<Pair<String, String>, Int>()
        fun plan(binding: MonetBinding) {
            if (binding.id != 0) {
                plannedIds[referenceKey(binding.type, binding.name)] = binding.id
            }
        }
        plan.colors.forEach { plan(it.binding) }
        plan.literalColors.forEach { plan(it.binding) }
        plan.strings.forEach { plan(it.binding) }
        plan.drawables.forEach { plan(it.binding) }

        // 单个条目/单个引用出错不允许毁掉整包：少替换一个资源只是观感问题，
        // 而「整包 build 失败」会让用户完全拿不到取色。
        var failures = 0
        fun onFailure(label: String, t: Throwable) {
            failures++
            if (failures <= 5) {
                WeLogger.w(TAG, "overlay $label failed, skipped: ${t.message}")
            }
        }

        val aligned = AlignedEntryWriter(pkg, excludedIds)

        plan.colors.forEach { color ->
            val binding = color.binding
            color.light?.let { value ->
                aligned.entry(binding, "")?.let { entry ->
                    entry.setColorValue(value)
                    record(entry, "")
                }
            }
            color.night?.let { value ->
                aligned.entry(binding, NIGHT_QUALIFIERS)?.let { entry ->
                    entry.setColorValue(value)
                    record(entry, NIGHT_QUALIFIERS)
                }
            }
        }
        plan.literalColors.forEach { color ->
            val binding = color.binding
            aligned.entry(binding, "")?.let { entry ->
                entry.setValueAsRaw(ValueType.COLOR_ARGB8, color.lightArgb)
                record(entry, "")
            }
            color.nightArgb?.let { argb ->
                aligned.entry(binding, NIGHT_QUALIFIERS)?.let { entry ->
                    entry.setValueAsRaw(ValueType.COLOR_ARGB8, argb)
                    record(entry, NIGHT_QUALIFIERS)
                }
            }
        }
        plan.strings.forEach { string ->
            val binding = string.binding
            aligned.entry(binding, string.qualifiers)?.let { entry ->
                entry.setValueAsString(string.value)
                record(entry, string.qualifiers)
            }
        }
        // drawable 条目的值必须是「我们刚写进去的那个 XML 的路径」：宿主按 id 取 drawable 时
        // 走的是 value=字符串路径 -> 文件，类型名保持不变，所以既能替换又不改变宿主的取用方式。
        //
        // drawable 覆盖（圆角 PRO、角标、底栏/相册/朋友圈图标等全靠它）。
        //
        // 【2026-09-26 修正】上一版这里因为「宿主 APK 文件清单」闸门恒为空，把 drawable 整批
        // 丢掉了（用户反馈「圆角一个都没生效、大量图标还是原生的」）。现在闸门改为按宿主资源表
        // 的值类型判断（见 [hostDrawableIsRealFile]），只有别名/引用型 drawable 会被跳过。
        if (WRITE_DRAWABLE_OVERLAYS) {
            var aliasSkipped = 0
            plan.drawables.forEach { drawable ->
                val binding = drawable.binding
                fun emit(node: XmlNode, qualifiers: String) {
                    val path = xmlPath(binding.type, qualifiers, binding.name)
                    // 别名 / 引用型 drawable：宿主这个 id 只是一条「指向别的资源」的别名。
                    // 把它的值改写成文件路径会破坏引用链，宿主去找一个不存在的文件就崩
                    //   Resources$NotFoundException: File res/drawable/ao1.xml from drawable
                    //   resource ID #0x7f08116c（wekit-crash-2026-09-26_13-33-02 / 13-43-03）。
                    // 判据由调用方给出（宿主资源表里该资源的值类型是否为 REFERENCE）。
                    if (hostDrawableIsRealFile != null && !hostDrawableIsRealFile(path)) {
                        aliasSkipped++
                        return
                    }
                    aligned.entry(binding, qualifiers)?.let { entry ->
                        val document = try {
                            buildXmlResource(pkg, node, hostReference, plannedIds)
                        } catch (t: Throwable) {
                            onFailure("${binding.type}/${binding.name} xml", t)
                            null
                        }
                        if (document != null) {
                            entry.setValueAsString(path)
                            apk.add(BlockInputSource(path, document))
                            outFiles += path
                            record(entry, qualifiers)
                        }
                    }
                }
                emit(drawable.light, drawable.lightQualifiers)
                drawable.night?.let { emit(it, drawable.nightQualifiers) }
            }
            if (aliasSkipped > 0) {
                WeLogger.i(
                    TAG,
                    "drawable overlays skipped: $aliasSkipped 条别名 drawable" +
                        "（资源表里是 REFERENCE 别名，改写会变成「打开不存在的文件」而崩）",
                )
            }
        } else if (plan.drawables.isNotEmpty()) {
            WeLogger.i(
                TAG,
                "drawable/mipmap overlays skipped: ${plan.drawables.size} 个角色（开关已关闭）",
            )
        }

        table.refreshFull()
        specFlags.forEach { (resourceId, flags) -> markSpecFlags(pkg, resourceId, flags) }
        apk.refreshTable()
        val mismatch = aligned.verify()
        if (mismatch != null) {
            // id 被 ARSCLib 重新分配过 = 覆盖会落到别的资源上（正是闪退的成因），宁可整包不写。
            WeLogger.e(TAG, "runtime package rejected: $mismatch")
            return false
        }
        freezeCanonicalTable(apk, table, specEntryCounts)
        output.parentFile?.mkdirs()
        apk.writeApk(output)
        apk.close()
        val failedNote = if (failures > 0) ", entry failures $failures" else ""
        WeLogger.i(TAG, "runtime package written: ${aligned.summary()}$failedNote")
        return true
    }

    private fun com.reandroid.arsc.value.Entry.setColorValue(value: ColorValue) {
        when (value) {
            is ColorValue.Reference -> setValueAsReference(value.id)
            is ColorValue.Literal -> setValueAsRaw(ValueType.COLOR_ARGB8, value.argb)
        }
    }

    private fun markSpecFlags(pkg: PackageBlock, resourceId: Int, flags: Int) {
        val typeId = (resourceId ushr 16) and 0xff
        val entryId = resourceId and 0xffff
        // 按 typeId 直查，不做「按名字回查条目」：新建包里名字映射不可靠，
        // 而 flag 只是给运行时读取器看的辅助信息，缺失就只跳过它，绝不打断整包写出。
        val specBlock = pkg.getSpecTypePair(typeId)?.specBlock
        if (specBlock == null) {
            WeLogger.w(TAG, "spec block missing for typeId $typeId, spec flags skipped")
            return
        }
        specBlock.getSpecFlag(entryId)?.setInteger(flags)
    }

    private fun referenceKey(type: String, name: String) = type.lowercase() to name.lowercase()

    /** 把 XML 里的 `@type/name` 解析成真实资源 id；解析不出来返回 null（调用方跳过该属性）。 */
    private fun resolveReferenceId(
        pkg: PackageBlock,
        reference: XmlValue.NamedReference,
        hostReference: ((type: String, name: String) -> Int?)?,
        plannedIds: Map<Pair<String, String>, Int>,
    ): Int? {
        pkg.getResource(reference.type, reference.name)?.let { return it.resourceId }
        plannedIds[referenceKey(reference.type, reference.name)]?.let { return it }
        return hostReference?.invoke(reference.type, reference.name)?.takeIf { it != 0 }
    }

    private fun qualifierFlags(qualifiers: String): Int {
        if (qualifiers.isEmpty()) return 0
        val parts = qualifiers.removePrefix("-").split('-')
        var result = 0
        if ("night" in parts) result = result or NATIVE_CONFIG_UI_MODE
        if (parts.any { it == "anydpi" || it == "nodpi" || it.endsWith("dpi") }) {
            result = result or NATIVE_CONFIG_DENSITY
        }
        if (parts.any { it.length > 1 && it[0] == 'v' && it.drop(1).all(Char::isDigit) }) {
            result = result or NATIVE_CONFIG_VERSION
        }
        if (parts.firstOrNull()?.matches(Regex("[a-z]{2,3}")) == true) {
            result = result or NATIVE_CONFIG_LOCALE
        }
        return result
    }

    /**
     * ARSCLib 1.4.0 leaves several aapt2 resource-table fields unset when a table is created
     * from scratch. Android's readers are not required to repair those fields. Freeze a canonical
     * byte source after the final refresh so a later BlockInputSource refresh cannot erase them.
     */
    private fun freezeCanonicalTable(
        apk: ApkModule,
        table: TableBlock,
        specEntryCounts: Map<Int, Int>,
    ) {
        val bytes = table.bytes
        val tableStrings = table.stringPool
        if (tableStrings.isEmpty) {
            val offset = table.countUpTo(tableStrings)
            putI32(bytes, offset + 20, tableStrings.headerBlock.headerSize)
        }
        table.listPackages().forEach { pkg ->
            val packageOffset = table.countUpTo(pkg)
            putI32(bytes, packageOffset + 0x110, 0)
            putI32(bytes, packageOffset + 0x118, 0)
            pkg.listSpecTypePairs().forEach { pair ->
                val specOffset = table.countUpTo(pair.specBlock)
                val typeId = bytes[specOffset + 8].toInt() and 0xff
                val current = getI32(bytes, specOffset + 12)
                val wanted = specEntryCounts[typeId] ?: 0
                putU16(bytes, specOffset + 10, pair.countTypeBlocks())
                if (wanted > current) {
                    // TypeSpec 的 entryCount（结构体偏移 12）必须覆盖它名下 TypeBlock 真正用到
                    // 的最大 entryId。ARSCLib 从零建表时这一栏不保证跟着条目涨：实机包里有
                    // typeId 8 写到 0x116c、别的 typeId 的 spec 却只声明很小一段。
                    // spec 盖不住条目时，宿主的
                    //   TypedArray.getDrawable -> ResourcesImpl.loadDrawableForCookie
                    // 会在**取到值之后**再回查一次资源名，回查失败抛
                    //   Resources$NotFoundException: File res/drawable/ao1.xml from
                    //   drawable resource ID #0x7f08116c
                    // 直接崩进程（wekit-crash-2026-09-26_13-33-02 / 13-43-03）。
                    // 补大不补小：多出来的 flags 全是 0，语义就是「该配置没有差异」，安全。
                    putI32(bytes, specOffset + 12, wanted)
                    WeLogger.i(
                        TAG,
                        "spec typeId $typeId entryCount $current -> $wanted（写到了高 entryId，spec 同步补大）",
                    )
                }
            }
        }

        apk.removeInputSource(TableBlock.FILE_NAME)
        apk.add(ByteInputSource(bytes, TableBlock.FILE_NAME).apply {
            method = ZipEntry.STORED
            sort = 1
        })
    }

    private fun getI32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
        putU16(bytes, offset, value)
        putU16(bytes, offset + 2, value ushr 16)
    }

    private fun buildXmlResource(
        pkg: PackageBlock,
        node: XmlNode,
        hostReference: ((type: String, name: String) -> Int?)?,
        plannedIds: Map<Pair<String, String>, Int>,
    ): ResXmlDocument {
        val document = ResXmlDocument().apply { packageBlock = pkg }
        document.newElement(node.name).write(node, pkg, hostReference, plannedIds)
        document.refreshFull()
        return document
    }

    private fun xmlPath(type: String, qualifiers: String, name: String) = "res/$type$qualifiers/$name.xml"

    /**
     * 按**宿主 id** 建表的写入口。
     *
     * * `binding.id != 0`：宿主真实资源 id，直接拆 typeId/entryId 写入同一个槽位。
     * * `binding.id == 0`：WeKit 自己合成的资源（自适应图标图层等）。这类条目宿主没有，
     *   但也不能随便挑一个 entryId —— 挑中宿主已有的 entryId 就等于覆盖了别人的资源。
     *   调用方（[MonetAssetInjector]）必须先用 [syntheticId] 从「宿主同类型最高 entryId 之上」
     *   借一个槽位；这里兜底拒绝 id==0，绝不回退到「顺序分配」。
     *
     * 失败的条目一律**跳过并计数**：少替换几个资源只是观感问题，写到错的地方是闪退。
     */
    private class AlignedEntryWriter(
        private val pkg: PackageBlock,
        private val excludedIds: Set<Int>,
    ) {

        private val typeNames = linkedMapOf<Int, String>()
        private var written = 0
        private val skipped = linkedMapOf<String, Int>()
        private val created = mutableListOf<Pair<Entry, Int>>()

        fun entry(binding: MonetBinding, qualifiers: String): Entry? {
            val id = binding.id
            // 拉黑表：曾经在实机上「值能取到、但 getResourceTypeName(id) 取不到名字」的 id。
            // 宿主的 TypedArray -> getDrawable 路径会在取到值之后再回查一次资源名，回查失败
            // 就抛 Resources$NotFoundException 直接崩进程（实机 wekit-crash-2026-09-26_13-33-02）。
            // 这些 id 永久不再写进包，是「宁可少莫奈化几个资源，也绝不让微信崩」的兜底。
            if (id in excludedIds) {
                return skip(binding, "冒烟校验未通过（已拉黑，见 monet_blacklist.json）")
            }
            if (id == 0) return skip(binding, "id==0（合成资源必须先用 syntheticId 借槽位）")
            if ((id ushr 24) and 0xff != HOST_PACKAGE_ID) {
                return skip(binding, "packageId 不是宿主 0x${HOST_PACKAGE_ID.toString(16)}")
            }
            val typeId = (id ushr 16) and 0xff
            val entryId = id and 0xffff
            if (typeId == 0 || entryId == 0) return skip(binding, "id 退化 (0x${id.toUInt().toString(16)})")
            val known = typeNames[typeId]
            if (known != null && !known.equals(binding.type, ignoreCase = true)) {
                // 同一个 typeId 出现在两个类型名下 = 多 APK 合并时 id 撞车，写下去就是乱盖。
                return skip(binding, "typeId $typeId 同时被 $known 与 ${binding.type} 使用")
            }
            typeNames[typeId] = binding.type
            pkg.getOrCreateSpecTypePair(typeId, binding.type)
            val entry = pkg.getOrCreateEntry(typeId.toByte(), entryId.toShort(), qualifiers)
                ?: return skip(binding, "ARSCLib 未能创建条目")
            if (entry.name != binding.name) entry.setName(binding.name)
            written++
            created += entry to id
            return entry
        }

        private fun skip(binding: MonetBinding, reason: String): Entry? {
            val key = reason.substringBefore('（')
            skipped[key] = skipped.getOrDefault(key, 0) + 1
            if (skipped[key] == 1) {
                WeLogger.w(
                    TAG,
                    "skip overlay 0x${binding.id.toUInt().toString(16)} ${binding.type}/${binding.name}: $reason",
                )
            }
            return null
        }

        /** 校验每个条目真的落在它该在的 id 上；返回非 null 表示必须放弃这个包。 */
        fun verify(): String? {
            created.forEach { (entry, expected) ->
                val id = entry.resourceId
                if ((id ushr 24) != HOST_PACKAGE_ID || id == 0) {
                    return "entry ${entry.name} landed on 0x${id.toUInt().toString(16)}"
                }
                if (id != expected) {
                    // 条目被挪了位置 = 覆盖会落到别的资源上（宿主动画被写成颜色就是这么来的），
                    // 这种情况必须整包放弃，绝不放行。
                    return "entry ${entry.name} expected 0x${expected.toUInt().toString(16)} " +
                        "but landed on 0x${id.toUInt().toString(16)}"
                }
                val entryTypeId = (id ushr 16) and 0xff
                val expectedType = typeNames[entryTypeId]
                if (expectedType != null && !entry.name.isNullOrBlank()) {
                    // 名字只是 overlay 校验用的辅助信息：查不到名字不能当作「包写错了」而整包放弃
                    //（新建包里 typeId→类型名 的映射在 refreshFull() 后不保证可查），
                    // 真正必须严格的是下面这条 —— id 不能被挪位。
                    if (pkg.getResource(expectedType, entry.name) == null) {
                        WeLogger.w(TAG, "entry ${entry.name} not reachable by name in $expectedType")
                    }
                }
            }
            return null
        }

        fun summary(): String = buildString {
            append("aligned $written entries")
            append(", types ")
            append(typeNames.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" })
            if (skipped.isNotEmpty()) {
                append(", skipped ")
                append(skipped.entries.joinToString { "${it.key}×${it.value}" })
            }
        }
    }

    private const val HOST_PACKAGE_ID = 0x7f
    private const val NIGHT_QUALIFIERS = "-night"

    /**
     * 是否把 drawable / mipmap 角色写成「XML 文件 + 条目值=文件路径」。
     *
     * **保持开启，但必须配合 [write] 的 `hostFileExists` 过滤。** 这条路径同时带来最大的观感收益
     * （聊天气泡、输入框、引用框这些「纯色 shape 底」全在 drawable 里，颜色角色碰不到它们）与最大
     * 的崩溃风险，所以两道闸门缺一不可：
     *
     *  1. **别名过滤**（`hostFileExists`）：改写前先问「宿主 base.apk 里真有这个文件吗」。别名 /
     *     引用型 drawable 没有同名文件，改写 = 宿主打开不存在的文件 = 必崩
     *     （`Resources$NotFoundException: File res/drawable/ao1.xml from drawable resource ID
     *     #0x7f08116c`，实机 wekit-crash-2026-09-26_13-33-02 / 13-43-03）。
     *  2. **写包后 zip 复核**（[missingResourceFiles]）+ **注入后冒烟取用**（`MonetEngine`）：
     *     前者保证 XML 文件真的落进包里，后者让宿主真的加载一次我们写的每个 drawable，
     *     任何一条取不出来就整体回滚并删除坏包。
     *
     * 历史对照：真正稳定生效的那版运行时包只有 36261 字节；上一轮未经过滤地写入 drawable 后
     * 涨到 143701 字节，随之出现「取资源就崩 + 全局变卡」—— 差额 ~107KB 全是这些 XML 文件。
     * 现在改的是「只写真实文件对应的 XML」，条目数与文件数都会显著收敛。
     */
    private const val WRITE_DRAWABLE_OVERLAYS = true

    private fun ResXmlElement.write(
        node: XmlNode,
        pkg: PackageBlock,
        hostReference: ((type: String, name: String) -> Int?)?,
        plannedIds: Map<Pair<String, String>, Int>,
    ) {
        node.attributes.forEach { attribute ->
            val value = attribute.value
            if (value is XmlValue.NamedReference) {
                // 具名引用解析不出来时**只跳过这个属性**：旧实现用 requireNotNull，
                // 一个引用查不到就把整包 build 掉（「building resource package 阶段分析失败：
                // Required value was null.」），表现为「解析成功但莫奈完全不生效」。
                val id = resolveReferenceId(pkg, value, hostReference, plannedIds)
                if (id == null) {
                    WeLogger.w(TAG, "unresolved @${value.type}/${value.name}, attribute skipped")
                    return@forEach
                }
                createAndroidAttribute(attribute.name, attribute.id).apply {
                    valueType = ValueType.REFERENCE
                    data = id
                }
                return@forEach
            }
            createAndroidAttribute(attribute.name, attribute.id).apply {
                when (value) {
                    is XmlValue.Reference -> {
                        // 悬空引用（id==0）写进 XML 就是 `@0x0`：宿主解析这张图时直接失败
                        // （旧版实机 `IllegalArgumentException: launcher.splash.background`），
                        // 或者退化成透明/全黑 —— 而设置页借宿主的 WeChatSplashActivity 当壳、
                        // 窗口底就是这个 drawable，于是「打开 WeKit 设置直接黑屏」。
                        // 与具名引用一致：**少一个属性也不要写一个坏引用**。
                        if (value.id == 0) {
                            WeLogger.w(
                                TAG,
                                "<${node.name}> 属性 ${attribute.name} 的引用 id 为 0（悬空），已跳过",
                            )
                            return@forEach
                        }
                        valueType = ValueType.REFERENCE
                        data = value.id
                    }
                    is XmlValue.Color -> {
                        valueType = ValueType.COLOR_ARGB8
                        data = value.argb
                    }
                    is XmlValue.Dimension -> {
                        valueType = ValueType.DIMENSION
                        data = ComplexUtil.encodeComplex(value.dp, UnitDimension.DP)
                    }
                    is XmlValue.Integer -> {
                        valueType = ValueType.DEC
                        data = value.value
                    }
                    is XmlValue.Boolean -> setValueAsBoolean(value.value)
                    is XmlValue.Float -> {
                        valueType = ValueType.FLOAT
                        data = java.lang.Float.floatToIntBits(value.value)
                    }
                    is XmlValue.String -> setValueAsString(value.value)
                    is XmlValue.NamedReference -> Unit
                }
            }
        }
        node.children.forEach { child ->
            newElement(child.name).write(child, pkg, hostReference, plannedIds)
        }
    }

    private const val NATIVE_CONFIG_LOCALE = 0x00000004
    private const val NATIVE_CONFIG_DENSITY = 0x00000100
    private const val NATIVE_CONFIG_VERSION = 0x00000400
    private const val NATIVE_CONFIG_UI_MODE = 0x00001000
}
