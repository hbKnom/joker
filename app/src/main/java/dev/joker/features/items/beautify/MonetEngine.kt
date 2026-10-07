package dev.joker.features.items.beautify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.loader.ResourcesProvider
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.joker.reflekt.firstMethod
import dev.joker.reflekt.reflekt
import dev.joker.reflekt.utils.toClass
import dev.joker.R
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.preferences.WePrefs.Companion.prefOption
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.Button
import dev.joker.ui.content.TextButton
import dev.joker.ui.utils.showComposeDialog
import dev.joker.ui.utils.theme.MonetPaletteFactory
import dev.joker.ui.utils.theme.SeedResolver
import dev.joker.utils.HostInfo
import dev.joker.utils.WeLogger
import dev.joker.utils.fs.KnownPaths
import dev.joker.utils.monet.MonetApkResourceGraphLoader
import dev.joker.utils.monet.MonetArscScanner
import dev.joker.utils.monet.MonetBindings
import dev.joker.utils.monet.MonetBubbleStyle
import dev.joker.utils.monet.MonetColors
import dev.joker.utils.monet.MonetDexEvidenceCollector
import dev.joker.utils.monet.MonetDexEvidenceProvider
import dev.joker.utils.monet.MonetBadgeRecolor
import dev.joker.utils.monet.MonetResourceKey
import dev.joker.utils.monet.MonetResourceValue
import dev.joker.utils.monet.MonetResourceResolver
import dev.joker.utils.monet.MonetResolveProgress
import dev.joker.utils.monet.MonetResolveResult
import dev.joker.utils.monet.MonetResolveStage
import dev.joker.utils.monet.MonetRuntimePackageWriter
import dev.joker.utils.monet.MonetRuntimeState
import dev.joker.utils.monet.MonetStructureMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.joker.utils.serialization.DefaultJson
import dev.joker.loader.utils.ActivityResourceHooks
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import kotlin.concurrent.thread
import kotlin.io.path.div

/**
 * Recolours WeChat's own UI resources by loading a **runtime resource package** into WeChat's
 * `AssetManager`.
 *
 * Rebuilt for 09-25. The previous design generated signed RRO APKs and shipped them inside a Magisk
 * module, which meant rooting the device, a reboot, and a separate "module generator" feature. The
 * current one writes `runtime-<fingerprint>-<options>.apk` under the module cache and hands it to
 * `ResourcesProvider.loadFromApk`, so:
 *
 *  - no root, no Magisk/KernelSU/APatch, no reboot;
 *  - changing an option regenerates the package and re-injects it live;
 *  - we only need `android.content.res.loader`, i.e. Android 11 (API 30) — below that the feature
 *    reports [R.string.monet_unsupported] and stays off.
 *
 * The palette follows the Joker theme (see [R.string.monet_color_source_summary]); WeChat's own
 * `R.color`/`R.drawable` entries are overridden with references to the platform's Material You
 * colours, so the recolouring tracks the wallpaper the same way the system does.
 *
 * A handful of WeChat components (e.g. `MMSwitchBtn`) hold the brand green as a **compiled constant**
 * rather than a resource, so the legacy in-place swaps are still installed as a fallback.
 */
object MonetEngine : ClickableFeature() {

    override val technicalId = "莫奈引擎"
    override val nameRes = R.string.feature_monet_engine_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_monet_engine_description

    private const val TAG = "MonetEngine"

    /** WeChat's hardcoded brand green — the pixels a resource overlay cannot reach. */
    private const val DEFAULT_COLOR = -16268960 // 0xFF07C160

    /** 把品牌绿编译进 Java 字段/绘制调用的宿主组件。 */
    private const val SWITCH_BTN_CLASS = "com.tencent.mm.ui.widget.MMSwitchBtn"

    /** 注入后多久算「活过来了」（没活到这一刻就重启 = 疑似被注入的包搞崩）。 */
    private const val CONFIRM_DELAY_MS = 30_000L

    /**
     * 【第 52 轮】**首次安装/升级后**那次全量解析：等主线程**空闲**再开始。
     *
     * 实机日志（joker-2026-10-07.log，装完包后的第一次启动）：
     * ```
     * 缓存未命中（bindings=无，包存在=true），开始解析
     * base.apk: 11360 个二进制 XML（候选 10924），解析 10924 个、15144 ms
     * 资源特征扫描 分片让出 8 次（累计让出 9600ms）
     * resolved 231 roles，匹配用时 31048 ms
     * 解析并注入完成，用时 51109 ms
     * ```
     * 即：**装完包的第一次启动，主线程要和这 51 秒的解析抢 CPU**，正是「初加载卡顿、后面就不卡」
     * 的来源（第二次启动走缓存，日志里只有 771ms/3530ms）。
     *
     * 处置：不再用固定 20 秒硬延时，而是「最短 [FIRST_RUN_MIN_DELAY_MS]，之后等主线程空闲
     * （IdleHandler）立刻开始」，并留 [FIRST_RUN_MAX_DELAY_MS] 硬上限兜底。
     * 这样解析会在**首屏画完、用户没有在滑动**的间隙里跑，不再和首帧抢 CPU。
     * 取色本身的**时机与结果完全不变**（缓存命中的路径仍是 300ms 立即复用）。
     */
    private const val FIRST_RUN_MIN_DELAY_MS = 30_000L
    private const val FIRST_RUN_MAX_DELAY_MS = 120_000L

    private fun scheduleFirstRunResolve() {
        val handler = Handler(Looper.getMainLooper())
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        fun launch(why: String) {
            if (!started.compareAndSet(false, true)) return
            WeLogger.i(TAG, "首次全量解析开始（$why）")
            runCatching {
                if (isActive && isSupported) startResolve(force = false)
            }.onFailure { WeLogger.w(TAG, "first-run resolve failed", it) }
        }
        handler.postDelayed({
            runCatching {
                Looper.myQueue().addIdleHandler {
                    launch("主线程空闲")
                    false
                }
            }.onFailure { launch("IdleHandler 不可用") }
        }, FIRST_RUN_MIN_DELAY_MS)
        handler.postDelayed({ launch("到达 ${FIRST_RUN_MAX_DELAY_MS / 1000}s 硬上限") }, FIRST_RUN_MAX_DELAY_MS)
    }

    /** 启动后按缓存状态分流延后解析；调度失败就立刻解析，绝不因此不解析。 */
    private fun scheduleInitialResolve() {
        if (!hasReusablePackage() && !hasBindingsOnly()) {
            // 首次/升级后：全量解析（分钟级）—— 等空闲，别和首屏抢 CPU。
            runCatching { scheduleFirstRunResolve() }.onFailure {
                WeLogger.w(TAG, "cannot schedule first-run resolve, resolving immediately", it)
                startResolve(force = false)
            }
            return
        }
        val delay = if (hasReusablePackage()) INITIAL_RESOLVE_FAST_DELAY_MS else INITIAL_RESOLVE_DELAY_MS
        if (delay == INITIAL_RESOLVE_FAST_DELAY_MS) {
            WeLogger.i(TAG, "发现可复用的莫奈运行时包，${delay}ms 后立即复用（不再等 $INITIAL_RESOLVE_DELAY_MS ms）")
        }
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    runCatching {
                        if (isActive && isSupported) startResolve(force = false)
                    }.onFailure { WeLogger.w(TAG, "delayed initial resolve failed", it) }
                },
                delay,
            )
        }.onFailure { error ->
            WeLogger.w(TAG, "cannot schedule initial resolve, resolving immediately", error)
            startResolve(force = false)
        }
    }

    override fun onDisable() {
        // 【2026-09-27】关掉莫奈必须**真的摘干净**：旧实现只清状态字段，覆盖包还挂在
        // application/各个 Activity 的 Resources 上，用户得重启微信才回得去。
        ActivityResourceHooks.unregister(activityResourceCallback)
        detachLastApplied()
        _progress.value = null
        _result.value = null
        // 注入界面的配色跟着一起熄火，避免「原生已经回到旧配色、Joker 组件还是莫奈色」的反向割裂
        MonetColors.applied.value = null
    }

    /**
     * 注册给 [ActivityResourceHooks] 的具名回调。
     *
     * 必须是**稳定的单个引用**（不能每次 `register { }` 传新 lambda），否则 `onDisable()`
     * 里的反注册摘不掉它 —— 用户每关开一次莫奈就会多留一个永久回调。
     */
    private val activityResourceCallback: (Activity) -> Unit = { activity ->
        val resources = activity.resources
        if (resources != null) {
            runCatching { attachRuntimeLoader(resources, activity.javaClass.name) }
                .onFailure { WeLogger.w(TAG, "attach on activity create failed", it) }
        }
    }

    override fun onClick(context: ComponentActivity) {
        if (!isSupported) {
            context.showUnsupportedDialog()
            return
        }
        showOptionsDialog(context as Activity)
    }

    private fun onOptionsChanged() {
        if (!isActive || !isSupported) return
        startResolve(force = true)
    }

    /**
     * Analyses WeChat's resources (or reuses the cached binding set when the APK set is unchanged)
     * and injects the resulting runtime package.
     */
    fun startResolve(force: Boolean) {
        if (!isSupported) {
            WeLogger.w(TAG, "ResourcesLoader needs API 30, not enabling")
            return
        }
        if (resolving) {
            WeLogger.d(TAG, "resolution already running, skipping")
            return
        }
        resolving = true
        if (force) {
            // 用户点「重新解析」= 从头来过：连黑名单一起清掉。新版解析本来就不再沿用历史黑名单
            //（见 startResolve 里的 staleBlacklist 处理），这里顺手删文件，避免手机上残留一份
            // 曾经把整批覆盖吃掉的旧名单（2026-09-27 实机 402 条）。
            runCatching { if (blacklistFile.exists()) blacklistFile.delete() }
                .onFailure { WeLogger.d(TAG, "cannot clear monet blacklist", it) }
        }
        val app = HostInfo.application
        val info = app.applicationInfo
        val paths = buildList {
            add(info.sourceDir)
            info.splitSourceDirs?.let(::addAll)
        }
        thread(name = "MonetResolverThread") {
            // 解析全程在后台线程、并降到后台优先级：解析要做 APK 资源表解析，
            // 不能跟微信启动抢 CPU（用户明确要求「不影响微信的流畅运行」）。
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                val startedAt = System.nanoTime()
                val fingerprint = MonetResourceResolver.fingerprint(
                    paths,
                    HostInfo.versionCode,
                    HostInfo.versionName,
                )
                val cached = cachedBindings()?.takeIf { !force && it.fingerprint == fingerprint }
                val packageFile = runtimeFile(fingerprint)
                // 解析前先过「解析期熔断」：上次解析没跑完就退出过，本次不再拿微信去试。
                if (!guardResolveAttempt(force)) return@thread
                if (!guardRuntimeInjection(force, packageFile)) return@thread
                if (packageFile.isFile && (cached != null || !force)) {
                    if (cached != null) {
                        WeLogger.i(
                            TAG,
                            "reusing cached bindings ${cached.roles.size} roles (unresolved ${cached.unresolved.size})",
                        )
                    } else {
                        // 绑定缓存丢了、但包还在：包名里已经编码了 fingerprint + 选项哈希，
                        // 同一份包没必要重算。实机重算一次是 3 分钟量级的纯 CPU
                        // （joker-2026-09-26.log 的「用时就绪 179018ms / 209816ms」），
                        // 期间整机被拖住 —— 用户反馈的「初加载特别卡顿」大半来自这里。
                        WeLogger.w(
                            TAG,
                            "绑定缓存缺失但运行时包已存在（${packageFile.name}），直接复用、跳过全量解析",
                        )
                    }
                    // 复用来的包同样要过冒烟校验：校验不过 = 这个包在**当前**微信上会让宿主
                    // 取资源时崩，必须就地销毁并拉黑那批 id，绝不能因为「包名里带着 fingerprint」
                    // 就默认它没问题（上一版就是这样把坏包反复注入的）。
                    //
                    // 【2026-09-27】校验对象改成「这个包里**真的写进去**的覆盖 id」（记在
                    // [MonetRuntimeState.appliedOverlayIds]），而不是语义角色表 —— 后者本来就存在于
                    // 微信自己的资源表里，包残缺时也照样「通过」。同时把残缺包直接判死：实机上
                    // 黑名单吃掉 402/406 个槽位后包内只剩 4 条覆盖，注入也没意义（取色/圆角/角标全不生效），
                    // 这种包必须删掉重新解析，而不是抱着「applied 成功」继续用。
                    // 【2026-09-27 修】校验对象必须是「包这个文件里**真的**有多少条覆盖」。
                    // 旧实现是 `recorded.ifEmpty { cached.roles.values }`：记录为空时回退到语义角色表，
                    // 而角色 id 本来就存在于微信自己的资源表里 —— 于是一个 **510 字节的空包**照样
                    // 「冒烟校验通过 231 条」，还被当成健康包一直复用下去。实机日志：
                    //   `复用已有运行时包（覆盖 0 条）` + `applied runtime-….apk (510 bytes)`
                    // 用户看到的就是「解析成功、包 applied，但取色 / 圆角 PRO / 角标全都不生效」。
                    // 现在先回读包自身（[MonetArscScanner.inspectPackage]，只认写出来的字节）：
                    //   * 一条覆盖都没有 / 结构读不出来 → 判死：摘 loader、删包、重新解析；
                    //   * 有覆盖 → 用「包里真实的 id」当冒烟校验集，并与角色数比覆盖率。
                    val recorded = readRuntimeState().appliedOverlayIds
                    val inspected = MonetArscScanner.inspectPackage(packageFile)
                    val packageIds = inspected?.resourceIds()
                        ?.filter { ((it ushr 24) and 0xff) == 0x7f }
                        ?: emptyList()
                    val overlayIds = if (recorded.isNotEmpty()) recorded else packageIds
                    val expectedRoles = cached?.roles?.size ?: 0
                    val crippled = overlayIds.isEmpty() ||
                        (expectedRoles > 0 &&
                            overlayIds.size * 100 / expectedRoles < MIN_OVERLAY_COVERAGE_PERCENT)
                    if (crippled) {
                        WeLogger.w(
                            TAG,
                            "缓存的运行时包不可用（包内覆盖 ${packageIds.size} 条、状态记录 ${recorded.size} 条、" +
                                "角色 $expectedRoles 个）：判定为空包/残缺包，删除并重新解析，" +
                                "避免「解析成功但取色/圆角/角标全不生效」",
                        )
                        detachLastApplied()
                        runCatching { if (packageFile.exists()) packageFile.delete() }
                    } else {
                        val reused = applyRuntimePackage(
                            packageFile,
                            overlayIds,
                            controlIdsFor(overlayIds),
                        )
                        if (reused.hostBroken) {
                            runCatching { if (packageFile.exists()) packageFile.delete() }
                            error(
                                "复用的运行时资源包会打断宿主的资源查找（没被覆盖的宿主资源也取不到资源名）：" +
                                    "已摘除 loader 并删除该包，本次不注入以免影响微信运行；可在设置里重新解析",
                            )
                        }
                        if (reused.unhealthy.isNotEmpty()) {
                            detachLastApplied()
                            persistBlacklist(fingerprint, loadBlacklist(fingerprint) + reused.unhealthy)
                            runCatching { if (packageFile.exists()) packageFile.delete() }
                            error(
                                "复用的运行时资源包冒烟校验未通过（${reused.unhealthy.size} 条覆盖资源不可用），" +
                                    "已回滚、拉黑并删除该包；下次启动会重新解析",
                            )
                        }
                        recordRuntimeApplied(packageFile, overlayIds)
                        publishPalette()
                        if (cached != null) {
                            _result.value = MonetResolveResult.Success(cached, packageFile)
                        }
                        WeLogger.i(
                            TAG,
                            "复用已有运行时包（覆盖 ${overlayIds.size} 条），本次启动未做资源解析，" +
                                "用时 ${(System.nanoTime() - startedAt) / 1_000_000} ms",
                        )
                        return@thread
                    }
                }
                WeLogger.i(
                    TAG,
                    "缓存未命中（bindings=${cached?.fingerprint ?: "无"} 期望=$fingerprint，包存在=${packageFile.isFile}），开始解析",
                )
                // 落一个「解析进行中」标记：进程若在解析期间崩掉，标记会留到下次启动，
                // 由 guardResolveAttempt 记一次「解析没跑完」，从而打破闪退死循环。
                writeRuntimeState(readRuntimeState().copy(resolveStartedAt = System.currentTimeMillis()))

                _progress.value = MonetResolveProgress(
                    MonetResolveStage.LOADING_APKS,
                    "WeChat resource APKs: ${paths.size}",
                    0,
                    paths.size,
                )
                val graph = MonetApkResourceGraphLoader.load(
                    apkPaths = paths.map(::File),
                    targetPackage = info.packageName,
                ) { detail, completed, total ->
                    _progress.value = MonetResolveProgress(
                        MonetResolveStage.BUILDING_RESOURCE_GRAPH,
                        detail,
                        completed.takeIf { total > 0 },
                        total.takeIf { it > 0 },
                    )
                }
                val resolution = MonetResourceResolver.resolve(
                    graph = graph,
                    resources = app.resources,
                    fingerprint = fingerprint,
                    bubbleStyle = bubbleStyle,
                    multiSceneCorners = multiSceneCorners,
                    errorColors = errorColors,
                    // 平台没有 Material You token 时（安卓 11 / 部分 ROM）用主题种子色板顶上，
                    // 旧实现在这种情况下直接抛错 = 整次解析失败。
                    fallbackPalette = runCatching { MonetPaletteFactory.fromTheme(app) }.getOrNull(),
                    // 「哪些角色该配对哪个资源」的歧义用 DEX 证据消歧（旧版成功运行时的做法）。
                    // DexKit 不可用／扫描失败都只退化成结构消歧，不影响其余角色。
                    dexProvider = dexEvidenceProvider,
                ) { completed, total, detail ->
                    _progress.value = MonetResolveProgress(
                        MonetResolveStage.RESOLVING_ROLES,
                        detail,
                        completed,
                        total,
                    )
                }
                _progress.value = MonetResolveProgress(
                    MonetResolveStage.BUILDING_PACKAGE,
                    "runtime-${packageFile.name}",
                    null,
                    null,
                )
                runtimeDir.mkdirs()
                if (resolution.plan.isEmpty) {
                    // 一个角色都没解析出来：如实报告，而不是写个空包说「成功」
                    error(
                        "没有解析出可应用的资源：微信资源结构可能与当前规则不匹配" +
                            "（可在设置里重新解析）",
                    )
                }
                // 写包时 XML 里的 `@type/name` 需要真实 id：先在本次计划里找（合成资源），
                // 再回到宿主资源图里找，最后才跳过该属性。绝不再用 requireNotNull —— 一个
                // 引用查不到就整包失败，等于莫奈完全失效。
                val hostReference: (String, String) -> Int? = { type, name ->
                    graph.node(MonetResourceKey(type, name))?.id
                }
                // drawable 覆盖的「别名闸门」：解析阶段只认得「角色 -> id」，不知道这个 id 背后
                // 是真实文件还是「指向别的资源的别名」。别名没有同名文件，把它的值改写成
                // `res/drawable/xxx.xml` 就等于让宿主去打开一个不存在的文件 —— 实机崩溃
                //   Resources$NotFoundException: File res/drawable/ao1.xml from drawable
                //   resource ID #0x7f08116c / Unable to find resource ID #0x7f08116c
                // （joker-crash-2026-09-26_13-33-02 / 13-43-03）正是这条。所以先把宿主各 APK 的
                // `res/**` 文件清单扫出来，只有「宿主真的自带该文件」的条目才允许覆盖。
                // 别名闸门：用宿主**资源表里的值类型**判断，而不是宿主 APK 的文件清单。
                //
                // 上一版拿「宿主 APK 里存在同名 `res/...` 文件」当判据，在实机上直接失效：
                // 微信的资源做过路径混淆（AndResGuard 一类），base.apk 里的条目根本不叫
                // `res/drawable/xxx.xml`，于是清单恒为「0 个」，**所有 drawable 覆盖被整批
                // 丢掉** —— 用户看到的就是「莫奈解析成功、色也生效了，但圆角 PRO 一个都没
                // 生效，标题栏分组栏角标、导航底栏、朋友圈、相册图标大量还是原生的」。
                //
                // 正确判据：值本身就是文件路径（[MonetResourceValue.Text] 一类）才允许覆盖；
                // 值是 REFERENCE 的别名要原样保留引用链，否则宿主会去打开一个不存在的文件
                //（Resources$NotFoundException: File res/drawable/ao1.xml from drawable
                //  resource ID #0x7f08116c，joker-crash-2026-09-26_13-33-02 / 13-43-03）。
                // 查不到的资源保守放行，写包之后的 missingResourceFiles 复核兜最后一层。
                val hostDrawableIsRealFile: (String) -> Boolean = realFileDrawable@{ path ->
                    val relative = path.removePrefix("res/")
                    val directory = relative.substringBefore('/', "")
                    val fileName = relative.substringAfter('/', "")
                    if (directory.isEmpty() || fileName.isEmpty() || fileName == relative) {
                        return@realFileDrawable true
                    }
                    val node = graph.node(
                        MonetResourceKey(directory.substringBefore('-'), fileName.substringBeforeLast('.', fileName)),
                    ) ?: return@realFileDrawable true
                    val value = node.values.firstOrNull { it.qualifiers == directory.substringAfter('-', "") }?.value
                        ?: node.values.firstOrNull { it.qualifiers.isEmpty() }?.value
                        ?: return@realFileDrawable true
                    value !is MonetResourceValue.Reference
                }
                // 写包 -> 注入 -> 逐条校验 -> 不通过就重写。
                //
                // 为什么不让「一个坏条目」蒙混过关：宿主的 drawable 取值路径会在**取到值之后**
                // 回查一次资源名，回查失败直接抛 Resources$NotFoundException 崩进程
                //（joker-crash-2026-09-26_13-33-02 / 13-43-03：File res/drawable/ao1.xml from
                // drawable resource ID #0x7f08116c）。所以判据只能是「每一条写进包里的资源，
                // 注入之后都既能取值、又能按名字解析」，达不到就把那几条剔出去重写。
                //
                // 【2026-09-27 根治「莫奈彻底不生效」】以前这里**从累积的黑名单开始**写包：
                //   val excluded = loadBlacklist(fingerprint)
                // 而冒烟校验遍历的是语义角色的 id（那些 id 本来就存在于微信自己的资源表里，
                // 一条都不写进去也照样「通过」），于是黑名单只涨不缩、永远不会被重新验证。
                // 实机上最后长到 402 条，把 406 个覆盖槽位吃掉 402 个：
                //   runtime package written: aligned 4 entries … skipped 冒烟校验未通过×402
                // 用户看到的就是「解析成功、包也 applied，但取色 / 圆角 PRO / 角标全都不生效」。
                // 现在改成**每轮解析都从零验证**（历史黑名单只打印，不再直接沿用），一轮之内最多
                // [MAX_PACKAGE_ATTEMPTS] 次尝试自然收敛到「把所有健康条目都写进去」。
                val staleBlacklist = loadBlacklist(fingerprint)
                if (staleBlacklist.isNotEmpty()) {
                    WeLogger.i(
                        TAG,
                        "历史黑名单 ${staleBlacklist.size} 条：本轮从零重新验证" +
                            "（不再直接沿用，避免整批覆盖被吃掉导致莫奈不生效）",
                    )
                }
                // 写包之前先量宿主每个 typeId 声明的 entryCount（见 [MonetArscScanner]）：
                // 我们写出的覆盖包必须为每个写到的 typeId 声明**至少这么大**的 TypeSpec 范围，
                // 否则宿主的资源查找会被我们的包整体打断（= 用户看到的「解析成功但全不生效」+ 闪退）。
                val hostTypeEntryCounts = MonetArscScanner.hostTypeEntryCounts(paths)
                val excluded = LinkedHashSet<Int>()
                var attempt = 0
                var appliedIds: List<Int> = emptyList()
                while (true) {
                    attempt++
                    val outcome = MonetRuntimePackageWriter.write(
                        packageFile,
                        info.packageName,
                        resolution.plan,
                        hostReference,
                        hostDrawableIsRealFile,
                        excluded,
                        hostTypeEntryCounts = hostTypeEntryCounts,
                    )
                    if (!outcome.ok) {
                        // 条目 id 校验没过 = 覆盖会落到别的资源上，写了就是闪退，宁可这次不注入。
                        error(
                            "运行时资源包构建失败（${outcome.reason ?: "资源 id 校验未通过"}），" +
                                "已中止本次注入以免影响微信运行（可在设置里重新解析）",
                        )
                    }
                    // 【2026-09-27 修】零覆盖 = 本轮白干，绝不能记成成功。
                    // 实机证据：写包轮次打印过 `写入率 0%（0/406）`，随后照样
                    // `applied runtime-….apk (510 bytes)` + `解析并注入完成`，
                    // 于是用户每次冷启动都重跑一遍 58 s 全量解析、却一直「取色不生效」。
                    // 这里直接判失败（走 catch → Failure），让 UI 说真话，也不再记录成功状态。
                    if (outcome.writtenIds.isEmpty()) {
                        error(
                            "本轮没有写出任何覆盖资源（${outcome.summary}）：判定为失败，不注入、不记录为成功" +
                                "（可在设置里重新解析）",
                        )
                    }
                    if (outcome.coveragePercent < MIN_OVERLAY_COVERAGE_PERCENT) {
                        WeLogger.w(
                            TAG,
                            "覆盖写入率偏低 ${outcome.coveragePercent}%" +
                                "（${outcome.writtenIds.size}/${outcome.plannedCount}）：${outcome.summary}",
                        )
                    } else {
                        WeLogger.i(
                            TAG,
                            "覆盖写入率 ${outcome.coveragePercent}%" +
                                "（${outcome.writtenIds.size}/${outcome.plannedCount}）",
                        )
                    }
                    val smoke = applyRuntimePackage(
                        packageFile,
                        outcome.writtenIds,
                        controlIdsFor(outcome.writtenIds),
                    )
                    if (smoke.hostBroken) {
                        // 对照组（没被覆盖的宿主资源）也取不到名字 = 问题在包结构上，不在个别条目上。
                        // 这时把 writtenIds 全拉黑等于永久关掉莫奈（实机 2026-09-27 就是这么走到
                        // 「只剩 0 条覆盖的 510 字节空包」的），所以：摘干净、不拉黑、写坏包直接删。
                        runCatching { if (packageFile.exists()) packageFile.delete() }
                        error(
                            "运行时资源包会打断宿主的资源查找（${outcome.writtenIds.size} 条覆盖写入后，" +
                                "连没被覆盖的宿主资源都取不到资源名）：已摘除 loader、删除该包，" +
                                "本次不注入也不拉黑，以免影响微信运行；可在设置里重新解析",
                        )
                    }
                    if (smoke.unhealthy.isEmpty()) {
                        appliedIds = outcome.writtenIds
                        break
                    }
                    // 系统性失败保护：一轮里几乎整包都不健康时，拉黑它们 = 把莫奈永久关掉。
                    if (outcome.writtenIds.isNotEmpty() &&
                        smoke.unhealthy.size * 100 / outcome.writtenIds.size >= SYSTEMIC_FAIL_PERCENT
                    ) {
                        runCatching { if (packageFile.exists()) packageFile.delete() }
                        error(
                            "运行时资源包 ${smoke.unhealthy.size}/${outcome.writtenIds.size} 条校验不通过" +
                                "（疑似整包结构问题，而非个别坏 id）：本次不注入、不拉黑任何 id，" +
                                "以免把莫奈永久关掉；可在设置里重新解析",
                        )
                    }
                    val unhealthy = smoke.unhealthy
                    detachLastApplied()
                    excluded += unhealthy
                    runCatching { if (packageFile.exists()) packageFile.delete() }
                    WeLogger.w(
                        TAG,
                        "第 $attempt 轮运行时包有 ${unhealthy.size} 条覆盖资源校验不通过，已拉黑并重写" +
                            "（累计拉黑 ${excluded.size} 条）",
                    )
                    if (attempt >= MAX_PACKAGE_ATTEMPTS) {
                        error(
                            "运行时资源包连续 $attempt 轮未通过冒烟校验，本次不注入以免影响微信运行" +
                                "（已拉黑 ${excluded.size} 条不可用覆盖，可在设置里重新解析）",
                        )
                    }
                }
                // 自愈：把「本轮实测不健康」的集合写回黑名单文件，覆盖掉历史累积的巨大名单。
                // 下次解析又从零验证，所以这张表只是诊断用，绝不会再变成「覆盖被整批吃掉」。
                persistBlacklist(fingerprint, excluded)
                persistBindings(resolution.bindings)
                recordRuntimeApplied(packageFile, appliedIds)
                // 解析成功 → 清零「解析未完成」计数，下次启动照常复用缓存。
                runCatching {
                    writeRuntimeState(
                        readRuntimeState().copy(resolveStartedAt = 0L, resolveFailStreak = 0),
                    )
                }
                publishPalette()
                _progress.value = null
                _result.value = MonetResolveResult.Success(resolution.bindings, packageFile)
                WeLogger.i(
                    TAG,
                    "解析并注入完成，用时 ${(System.nanoTime() - startedAt) / 1_000_000} ms",
                )
            } catch (error: Throwable) {
                val stage = _progress.value?.stage ?: MonetResolveStage.LOADING_APKS
                WeLogger.e(TAG, "resource analysis failed during $stage", error)
                _progress.value = null
                _result.value = MonetResolveResult.Failure(
                    MonetResolveProgress(stage, stage.label),
                    error.message ?: error.toString(),
                )
            } finally {
                // 能走到这里说明解析线程正常收尾（没把微信带崩）：清掉「解析进行中」标记。
                // 进程若在解析期被杀，finally 不会执行，标记留到下次启动触发熔断。
                runCatching { writeRuntimeState(readRuntimeState().copy(resolveStartedAt = 0L)) }
                resolving = false
            }
        }
    }

    private fun runtimeFile(fingerprint: String): File {
        val options = "${bubbleStyle.name}-$multiSceneCorners-$errorColors"
        val hash = options.hashCode().toUInt().toString(16)
        val name = "runtime-$fingerprint-$hash.apk"
        val primary = File(runtimeDir, name)
        if (primary.isFile) return primary
        // 旧位置（外部缓存目录）里已经有可用包就别重解析：能搬到新位置最好，搬不动就继续用旧的。
        val legacy = File(legacyRuntimeDir, name)
        if (legacy.isFile) {
            val migrated = runCatching {
                runtimeDir.mkdirs()
                legacy.renameTo(primary)
            }.onFailure { WeLogger.d(TAG, "cannot migrate legacy runtime package", it) }.getOrDefault(false)
            return if (migrated) primary else legacy
        }
        return primary
    }

    /**
     * 冒烟校验没通过的覆盖 id（永久拉黑），首行是资源指纹。
     *
     * 为什么必须持久化：坏 id 的判据（「值取得到、但 `getResourceTypeName(id)` 取不到」）
     * 只有把包注入之后才测得出，而注入是不能试错的 —— 试错的那一次就是用户手机上的一次闪退。
     * 所以第一次踩到的坏 id 记进这里，之后每次写包直接跳过它。微信一升级资源指纹就变了，
     * 指纹对不上整份作废（旧 id 表没有任何参考价值）。
     */
    private val blacklistFile: File by lazy { File(runtimeDir, "monet_blacklist.json") }

    private fun loadBlacklist(fingerprint: String): MutableSet<Int> {
        val result = LinkedHashSet<Int>()
        runCatching {
            if (!blacklistFile.isFile) return@runCatching
            val lines = blacklistFile.readLines()
            if (lines.firstOrNull()?.trim() != fingerprint) {
                WeLogger.i(TAG, "monet blacklist belongs to another WeChat build, ignoring")
                return@runCatching
            }
            lines.drop(1).forEach { line -> line.trim().toIntOrNull()?.let(result::add) }
        }.onFailure { WeLogger.d(TAG, "cannot read monet blacklist", it) }
        return result
    }

    private fun persistBlacklist(fingerprint: String, ids: Set<Int>) {
        runCatching {
            runtimeDir.mkdirs()
            blacklistFile.writeText(
                buildString {
                    appendLine(fingerprint)
                    ids.sorted().forEach { appendLine(it) }
                },
            )
        }.onFailure { WeLogger.w(TAG, "cannot persist monet blacklist", it) }
    }

    /** 最近一次成功挂上去的 (Resources, loader)，用于「重写包」时把上一轮摘干净。 */
    private var lastAppliedLoader: Pair<Resources, android.content.res.loader.ResourcesLoader>? = null

    private fun detachLastApplied() {
        val applied = lastAppliedLoader
        lastAppliedLoader = null
        _runtimePackage.value = null
        if (applied != null) {
            runCatching { applied.first.removeLoaders(applied.second) }
                .onFailure { WeLogger.w(TAG, "cannot detach runtime loader", it) }
        }
        // 同时摘掉挂在各个 Activity 的 Resources 上的 loader（重新解析前也必须先摘干净：
        // 同一个 Resources 上叠两个覆盖包时，其中一个声明范围盖不住宿主 entryId 就会打断查找）。
        detachAttachedLoaders()
    }

    /**
     * 注入运行时包并做冒烟校验。
     *
     * @param overlayIds 这个包里**真的写进去**的覆盖 id（不是语义角色表）。
     * @param controlIds 对照组：与覆盖 id 同类型、但**没被我们覆盖**的宿主 id。用来区分
     *   「个别条目坏」和「整个包把宿主的资源查找打断了」——后者绝不能拉黑（见 [SmokeResult]）。
     * @return [SmokeResult]；不健康时调用方必须 [detachLastApplied] 并按情况处理。
     */
    private fun applyRuntimePackage(
        file: File,
        overlayIds: Collection<Int>,
        controlIds: Collection<Int> = emptyList(),
    ): SmokeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return SmokeResult()
        // 换新包之前先把上一轮的 loader 摘干净：同一个 Resources 上叠两个覆盖包时，
        // 只要有一个包的 TypeSpec 声明范围盖不住宿主 entryId，宿主对该类型的查找就会整体失败。
        detachLastApplied()
        val resources = HostInfo.application.resources
        val loader = android.content.res.loader.ResourcesLoader()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            // 运行时包是「resources.arsc + res/*.xml」打出来的 APK（无 AndroidManifest、不安装），
            // 必须走 loadFromApk：loadFromTable 期望的是**裸 .arsc** fd，且要额外传 AssetsProvider。
            val provider = ResourcesProvider.loadFromApk(descriptor)
            loader.addProvider(provider)
        }
        resources.addLoaders(loader)
        // 冒烟校验：注入之后宿主资源必须还能正常取用。不通过就立刻摘掉 loader 并把**具体哪些
        // id**交回调用方 —— 宁可不莫奈化，也不把一个坏包留在微信进程里（实机教训：坏包 = 取资源就崩）。
        val smoke = runCatching { smokeTestRuntimePackage(resources, overlayIds, controlIds) }
            .onFailure { WeLogger.w(TAG, "smoke test failed to run", it) }
            .getOrDefault(SmokeResult())
        if (!smoke.healthy) {
            runCatching { resources.removeLoaders(loader) }
                .onFailure { WeLogger.e(TAG, "cannot roll back runtime loader", it) }
            _runtimePackage.value = null
            return smoke
        }
        lastAppliedLoader = resources to loader
        _runtimePackage.value = file
        WeLogger.i(TAG, "applied ${file.name} (${file.length()} bytes)")
        // 【2026-09-27 修「首屏不生效、必须去设置里关掉再打开」】事件式回调只对**订阅之后
        // 创建**的 Activity 有效，而首屏（LauncherUI / 会话列表）在我们 applied 之前就建好了，
        // 它们的 Resources 永远收不到回调 → 取色/圆角 PRO/角标在首屏一律原生。
        // 这里把当前还活着的 Activity 一次性补挂回来。
        ActivityResourceHooks.forEachLiveResources { activity ->
            val liveResources = activity.resources ?: return@forEachLiveResources
            runCatching { attachRuntimeLoader(liveResources, activity.javaClass.name) }
                .onFailure { WeLogger.w(TAG, "replay attach failed", it) }
        }
        return SmokeResult()
    }

    /**
     * 「系统性失败」阈值：一轮里被判不健康的覆盖比例 ≥ 这么多，就认定问题出在**包结构**上，
     * 而不是个别坏条目 —— 这时**绝不拉黑**（拉黑整包 = 把莫奈永久关掉）。
     */
    private const val SYSTEMIC_FAIL_PERCENT = 80

    /** 对照组探测的取样上限（每次注入只探这么多个没被覆盖的宿主 id）。 */
    private const val CONTROL_PROBE_LIMIT = 24

    /**
     * 已经挂过运行时包的 `Resources` → 挂上去的那个 loader（弱引用键，避免把宿主的
     * `Resources` 钉在内存里）。存 loader 是为了 `onDisable()` / 重新解析时能**真的摘干净**：
     * 旧实现只清状态字段，用户关掉莫奈后覆盖还在生效，得重启微信才回得去。
     */
    private val runtimeLoaderAttached:
        MutableMap<Resources, android.content.res.loader.ResourcesLoader> =
        Collections.synchronizedMap(
            WeakHashMap<Resources, android.content.res.loader.ResourcesLoader>(),
        )

    /** 已经打过「运行时包已挂上」日志的 Activity 标签，避免同一个 Activity 反复刷。 */
    private val attachedActivityLabels: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    /**
     * 把运行时资源包挂到**任意一个** `Resources` 实例上（幂等、失败只降级）。
     *
     * 由 [ActivityResourceHooks] 在每个 Activity 创建时调用，也会在包就绪后对**已经错过**
     * 的存活 Activity 补挂一次（[ActivityResourceHooks.forEachLiveResources]）。原因见 [onEnable] ③：
     * loader 之前只挂在 `application.resources` 上，而实机反馈「解析正常、包 applied、
     * 取色/圆角/角标一律没生效」，最可疑的就是宿主 UI 真正使用的那批 `Resources`
     * 并没有拿到这个 loader（`Resources.addLoaders` 只作用于调用它的那个实例/impl）。
     */
    private fun attachRuntimeLoader(resources: Resources, label: String = "?") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val file = _runtimePackage.value ?: return
        if (!file.isFile) return
        if (runtimeLoaderAttached.containsKey(resources)) return
        val startedAt = SystemClock.uptimeMillis()
        try {
            val loader = android.content.res.loader.ResourcesLoader()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                loader.addProvider(ResourcesProvider.loadFromApk(descriptor))
            }
            resources.addLoaders(loader)
            runtimeLoaderAttached[resources] = loader
        } catch (error: Throwable) {
            runtimeLoaderAttached.remove(resources)
            WeLogger.w(TAG, "cannot attach runtime package to activity resources", error)
            return
        }
        // 【2026-09-27】这里原来调的是 probeActivityResources()，它拿**语义角色 id** 去
        // `getResourceTypeName` —— 那些 id 本来就存在于微信自己的资源表里，无论 loader 有没有
        // 挂上都是 100% 可解析（实机 4 次全是「231 个里 231 个可解析」），零信息量。
        // 真正有用的信号是「哪个 Activity 的 Resources 挂上了、花了多久（loadFromApk 解析覆盖包）」。
        val elapsed = SystemClock.uptimeMillis() - startedAt
        if (attachedActivityLabels.add(label)) {
            WeLogger.i(TAG, "运行时包已挂到 Activity 的 Resources：$label（本次 ${elapsed}ms）")
        } else if (elapsed >= SLOW_ATTACH_MS) {
            WeLogger.w(TAG, "挂运行时包偏慢：$label 用了 ${elapsed}ms（loadFromApk 解析覆盖包）")
        }
    }

    /** 摘掉所有已挂的运行时 loader，并清空存活表（`onDisable()`／重新解析前用）。 */
    private fun detachAttachedLoaders() {
        val entries = synchronized(runtimeLoaderAttached) {
            val copy = runtimeLoaderAttached.entries.toList()
            runtimeLoaderAttached.clear()
            copy
        }
        attachedActivityLabels.clear()
        entries.forEach { (resources, loader) ->
            runCatching { resources.removeLoaders(loader) }
                .onFailure { WeLogger.w(TAG, "cannot detach runtime loader", it) }
        }
    }

    /** 单次「把覆盖包挂到某个 Resources」超过这个耗时就打一条 warn（loadFromApk 解析成本）。 */
    private const val SLOW_ATTACH_MS = 120L

    /**
     * 冒烟校验：把**写进包的每一条**覆盖资源都按它的真实类型取一次，全部成功才算健康。
     *
     * 旧实现只抽查 8 条颜色、且「至少一条成功就算通过」，所以一个「颜色都对、但某个 drawable
     * 条目指向了包内不存在的 XML」的坏包能毫无阻碍地留下来 —— 宿主随后取那个 drawable 时
     * 直接抛 `Resources$NotFoundException` 崩进程（实机 joker-crash-2026-09-26_13-33-02 /
     * 13-43-03 就是这个）。判据必须严到「任何一条我们自己写进去的资源取不出来 = 包是坏的」。
     *
     * 类型分流按 `Resources.getResourceTypeName`：drawable/mipmap 走 `getDrawable`（这条正是
     * 崩溃路径），color 走 `getColor`，string 走 `getString`；其它类型本轮不写入，取不到不算我们写坏。
     *
     * 返回 [SmokeResult] 而不是 Boolean：调用方既要拿「不健康的 id」去重写包，也要知道
     * 「是不是整个包把宿主的资源查找打断了」（后者绝不能拉黑，见 [SmokeResult.hostBroken]）。
     */
    private fun smokeTestRuntimePackage(
        resources: Resources,
        overlayIds: Collection<Int>,
        controlIds: Collection<Int> = emptyList(),
    ): SmokeResult {
        // 校验对象 = **真的写进包里的覆盖 id**（2026-09-27 事故后被调用方明确传入）。
        // 以前这里取的是语义角色表 `bindings.roles`，那些 id 本来就存在于微信自己的资源表里 ——
        // 包里一条覆盖都没有时也照样「通过」，于是黑名单只涨不缩、残缺包永远检测不出来。
        val ids = overlayIds.toList()
        // 【2026-09-27 修】零覆盖**不是健康**。旧写法 `return SmokeResult()`（healthy=true）让
        // 「一条覆盖都没写进去」的空包一路被当成成功包应用，实机结果就是
        // `applied runtime-….apk (510 bytes)` + UI 报「解析成功」，而取色/圆角/角标全不生效。
        // 现在把「零覆盖」显式判成 hostBroken（调用方语义=这个包不可用、不要写成功记录），
        // 调用方（写包轮次）另有更早的门禁直接拦截，这里是第二道保险。
        if (ids.isEmpty()) {
            WeLogger.w(TAG, "冒烟校验集为空（零覆盖包）：直接判定不可用")
            return SmokeResult(hostBroken = true)
        }
        // 对照组先探：这些 id 我们**没有**覆盖，它们必须永远可解析。若有任何一个取不到名字，
        // 说明这个包破坏了宿主整个类型的资源查找（AOSP `FindEntryInternal` 在
        // `GetFlagsForEntryIndex` 返回 nullopt 时是 return 而不是 continue），必须整包摘掉。
        val brokenControls = controlIds.filter { controlId ->
            runCatching { resources.getResourceTypeName(controlId) }.getOrNull() == null
        }
        if (brokenControls.isNotEmpty()) {
            WeLogger.e(
                TAG,
                "运行时资源包打断了宿主的资源查找：对照组（没被覆盖的宿主资源）" +
                    "${controlIds.size} 个里就有 ${brokenControls.size} 个取不到资源名 " +
                    brokenControls.take(6).joinToString { "0x" + it.toUInt().toString(16) } +
                    " —— 判定为包结构问题，整包摘除（不拉黑任何 id）",
            )
            return SmokeResult(hostBroken = true)
        }
        var named = 0
        var valueOk = 0
        var unnamed = 0
        var failed = 0
        val unhealthy = LinkedHashSet<Int>()
        val unnamedSamples = StringBuilder()
        val failedSamples = StringBuilder()
        fun sample(builder: StringBuilder, type: String, id: Int) {
            if (builder.length >= 240) return
            if (builder.isNotEmpty()) builder.append(", ")
            builder.append(type).append("/0x").append(id.toUInt().toString(16))
        }

        for (id in ids) {
            // ① 资源名必须先解析得出来。
            //
            // 宿主的 drawable 取值路径是「按 id 取出值 -> 值以 .xml 结尾 -> 回查
            // getResourceTypeName(id) -> 再去包里打开那个文件」。第 ② 步能取到值**不代表**
            // 第 ③ 步能过：13-33-02 / 13-43-03 两次实机崩溃里，宿主明明已经拿到了我们写的
            // `res/drawable/ao1.xml`，却在回查名字时抛
            //   Unable to find resource ID #0x7f08116c
            // 最后以 Resources$NotFoundException 崩掉整个进程。所以「名字查不出来」与
            // 「值取不出来」一样是硬门禁，这类 id 必须拉黑。
            val type = runCatching { resources.getResourceTypeName(id) }.getOrNull()
            if (type == null) {
                unnamed++
                unhealthy += id
                sample(unnamedSamples, "?", id)
                continue
            }
            named++
            val ok = runCatching {
                when (type) {
                    "color" -> {
                        resources.getColor(id, null)
                        true
                    }

                    "drawable", "mipmap" -> resources.getDrawable(id, null) != null
                    "string" -> {
                        resources.getString(id)
                        true
                    }

                    else -> true
                }
            }.getOrDefault(false)
            if (!ok) {
                failed++
                unhealthy += id
                sample(failedSamples, type, id)
                continue
            }
            valueOk++
        }

        if (unhealthy.isNotEmpty()) {
            WeLogger.e(
                TAG,
                "冒烟校验不通过：$named 条能按名字解析、其中 $failed 条取用异常（$failedSamples）；" +
                    "另有 $unnamed 条查不到资源名（$unnamedSamples）；" +
                    "合计 ${unhealthy.size}/${ids.size} 条" +
                    "（对照组 ${controlIds.size} 个宿主资源全部正常，说明是这些条目自身的问题）",
            )
            return SmokeResult(unhealthy = unhealthy)
        }
        WeLogger.i(
            TAG,
            "冒烟校验通过：$valueOk 条覆盖资源全部可取用、且都能按名字解析" +
                "（对照组 ${controlIds.size} 个宿主资源同样正常）",
        )
        return SmokeResult()
    }

    /**
     * 冒烟校验结果。
     *
     * @property unhealthy 我们写进去、但宿主取不到的 id（个别的坏条目）—— 拉黑这些 id 后重写包。
     * @property hostBroken 对照组（**没被我们覆盖**的宿主 id）也取不到资源名了 —— 说明这个包
     *   把宿主整个类型的资源查找打断了。这种情况**绝不能拉黑**：拉黑全部 id 等于把莫奈永久关掉。
     *   实机 2026-09-27 的 `0 条能按名字解析 … 另有 406 条查不到资源名` 就是这种状态，
     *   旧代码把 213 条 id 拉黑、重写出一个只有 0 条覆盖的 510 字节空包，
     *   用户看到的就是「解析成功、包 applied，但取色 / 圆角 PRO / 角标全都不生效」。
     */
    private data class SmokeResult(
        val unhealthy: Set<Int> = emptySet(),
        val hostBroken: Boolean = false,
    ) {
        val healthy: Boolean get() = unhealthy.isEmpty() && !hostBroken
    }

    /**
     * 对照组取样：当前绑定缓存里**没被这次覆盖**、且与覆盖 id 同类型的宿主 id。
     *
     * 用它们判断「坏的是个别条目」还是「整个包把宿主的资源查找打断了」。
     */
    private fun controlIdsFor(overlayIds: Collection<Int>): List<Int> {
        val roles = cachedBindings()?.roles?.values?.toList() ?: return emptyList()
        if (roles.isEmpty()) return emptyList()
        val written = overlayIds.toHashSet()
        val types = written.mapTo(HashSet()) { (it ushr 16) and 0xff }
        return roles.asSequence()
            .filter { it !in written && ((it ushr 16) and 0xff) in types }
            .distinct()
            .take(CONTROL_PROBE_LIMIT)
            .toList()
    }

    /**
     * 把引擎色板发布给「Joker 注入微信界面的组件」使用（见 [MonetColors]）。
     *
     * 色板里存的是 `android.R.color.system_*` 的**资源引用**，注入组件不能用引用，
     * 所以在这里统一解析成具体 ARGB。注入界面读的是 Compose 状态，发布后立即重组。
     */
    private fun publishPalette() {
        val app = HostInfo.application
        runCatching {
            val raw = MonetResourceResolver.overlayPalette(
                app.resources,
                runCatching { MonetPaletteFactory.fromTheme(app) }.getOrNull(),
            )
            MonetColors.applied.value = MonetResourceResolver.resolveArgb(app.resources, raw)
        }.onFailure { WeLogger.w(TAG, "cannot publish monet palette for injected UI", it) }
    }

    private fun cachedBindings(): MonetBindings? {
        val candidates = listOf(bindingsCacheFile, bindingsFile, legacyBindingsCacheFile).filter { it.isFile }
        if (candidates.isEmpty()) {
            WeLogger.d(
                TAG,
                "no cached bindings at ${bindingsCacheFile.absolutePath} / ${bindingsFile.absolutePath}",
            )
            return null
        }
        candidates.forEach { file ->
            runCatching {
                return DefaultJson.decodeFromString(MonetBindings.serializer(), file.readText())
            }.onFailure { WeLogger.w(TAG, "cannot read cached bindings ${file.absolutePath}", it) }
        }
        return null
    }

    private fun persistBindings(bindings: MonetBindings) {
        val json = runCatching {
            DefaultJson.encodeToString(MonetBindings.serializer(), bindings)
        }.onFailure { WeLogger.w(TAG, "cannot encode bindings", it) }.getOrNull() ?: return
        listOf(bindingsCacheFile, bindingsFile).forEach { file ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json)
            }.onFailure { WeLogger.w(TAG, "cannot persist bindings to ${file.absolutePath}", it) }
        }
    }

    /**
     * 注入前的自保闸门：**绝不允许出现「一启动就闪退、重启又应用同一个坏包」的死循环**。
     *
     * 判断依据见 [MonetRuntimeState]：上一个包应用后没等到确认就重启（窗口 [RESTART_WINDOW_MS]）
     * 记为一次疑似；连续 [MAX_FAIL_STREAK] 次就直接不注入，并如实告诉用户，等用户在设置里点
     * 「重新解析」（force=true）再放行。任何异常都当作「没有疑似」，绝不因为自保逻辑本身挡住功能。
     */
    private fun guardRuntimeInjection(force: Boolean, packageFile: File): Boolean {
        if (force) {
            writeRuntimeState(MonetRuntimeState())
            return true
        }
        val state = readRuntimeState()
        if (!state.suspiciousRestart(System.currentTimeMillis(), RESTART_WINDOW_MS, packageFile.name)) {
            return true
        }
        val streak = state.failStreak + 1
        writeRuntimeState(state.copy(failStreak = streak))
        if (streak < MAX_FAIL_STREAK) {
            WeLogger.w(TAG, "上次注入后微信很快就退出了（疑似 $streak 次），本次仍注入但会更谨慎")
            return true
        }
        WeLogger.e(TAG, "莫奈运行时包连续 $streak 次疑似导致微信异常退出，已自动停用注入")
        _progress.value = null
        _result.value = MonetResolveResult.Failure(
            MonetResolveProgress(MonetResolveStage.BUILDING_PACKAGE, "runtime injection suspended"),
            "莫奈取色已在本次启动停用：运行时资源包连续 $streak 次疑似导致微信异常退出。" +
                "请在设置里点「重新解析」重试；若仍然闪退，请先关闭莫奈引擎。",
        )
        return false
    }

    /**
     * 解析期熔断：**解析自己把微信搞崩过，就不许再解析。**
     *
     * 实机 2026-09-26 的日志链：每次出现「缓存未命中…开始解析」后 1–10 秒内微信必崩原生
     * （SIGBUS/SIGSEGV，故障帧在 libhwui / libcso.so 这些与解析无关的库里，故障地址是
     * UTF-16 片段）→ 进程重启 → 缓存仍然没有 → 又解析 → 又崩，四分钟四次。
     * 而 [guardRuntimeInjection] 只统计「应用后很快重启」，崩溃在应用之前，统计永远为空，
     * 于是死循环没有任何刹车。[resolveStartedAt] 标记 + 本函数就是这个刹车：
     * 连续 [MAX_RESOLVE_FAIL_STREAK] 次未跑完 → 本次启动直接跳过解析（微信照常可用），
     * 用户在设置里点「重新解析」（force）才重新放行。
     */
    private fun guardResolveAttempt(force: Boolean): Boolean {
        val state = readRuntimeState()
        if (force) {
            writeRuntimeState(state.copy(resolveStartedAt = 0L, resolveFailStreak = 0))
            return true
        }
        val now = System.currentTimeMillis()
        val interrupted = state.resolveStartedAt > 0 &&
            (now - state.resolveStartedAt) in 0..RESOLVE_INTERRUPT_WINDOW_MS
        val streak = if (interrupted) state.resolveFailStreak + 1 else state.resolveFailStreak
        if (interrupted) {
            WeLogger.w(
                TAG,
                "上次资源解析没有跑完就退出了（$streak/$MAX_RESOLVE_FAIL_STREAK）",
            )
            writeRuntimeState(state.copy(resolveFailStreak = streak, resolveStartedAt = 0L))
        }
        if (streak < MAX_RESOLVE_FAIL_STREAK) return true
        WeLogger.e(TAG, "资源解析连续 $streak 次未能跑完（疑似影响微信稳定性），本次跳过解析")
        _progress.value = null
        _result.value = MonetResolveResult.Failure(
            MonetResolveProgress(MonetResolveStage.LOADING_APKS, "resolve suspended"),
            "莫奈资源解析已连续 $streak 次未能跑完（疑似影响微信稳定性），本次启动已跳过解析，" +
                "微信可正常使用。需要重试请在设置里点「重新解析」。",
        )
        return false
    }

    /** 记录「刚应用了哪个包」，作为下次启动判断是否疑似崩溃的依据。 */
    private fun recordRuntimeApplied(packageFile: File, overlayIds: List<Int> = emptyList()) {
        // 把「这个包里真的写了哪些覆盖」一起落盘：下次启动复用这个包时才能校验**包的内容**
        // 而不是语义角色表，也才能识别出「只剩几条覆盖的残缺包」。见
        // [MonetRuntimeState.appliedOverlayIds] 与 startResolve 里的 crippled 判定。
        writeRuntimeState(
            MonetRuntimeState(
                packageName = packageFile.name,
                appliedAt = System.currentTimeMillis(),
                failStreak = readRuntimeState().failStreak,
                appliedOverlayIds = overlayIds,
            ),
        )
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    thread(name = "MonetConfirm") {
                        writeRuntimeState(
                            readRuntimeState().copy(confirmedAt = System.currentTimeMillis()),
                        )
                    }
                }
            }, CONFIRM_DELAY_MS)
        }.onFailure { WeLogger.w(TAG, "cannot schedule runtime confirm", it) }
    }

    private fun readRuntimeState(): MonetRuntimeState = runCatching {
        val file = runtimeStateFile.takeIf { it.isFile }
            ?: File(legacyRuntimeDir, "monet_runtime_state.json").takeIf { it.isFile }
        if (file == null) {
            MonetRuntimeState()
        } else {
            DefaultJson.decodeFromString(MonetRuntimeState.serializer(), file.readText())
        }
    }.getOrElse { MonetRuntimeState() }

    private fun writeRuntimeState(state: MonetRuntimeState) {
        runCatching {
            runtimeDir.mkdirs()
            runtimeStateFile.writeText(
                DefaultJson.encodeToString(MonetRuntimeState.serializer(), state),
            )
        }.onFailure { WeLogger.w(TAG, "cannot persist runtime state", it) }
    }

    // ------------------------------------------------------------------------------------------
    // Legacy fallback: components that compile the brand green in rather than reading a resource.
    // ------------------------------------------------------------------------------------------

    private fun installBrandColorHooks() {
        // MMSwitchBtn caches the brand green in plain int fields at construction time. 其中一部分字段
        // 声明在父类上（旧版引擎是遍历继承链查的，正是它让设置页开关跟着换色），所以这里也走继承链。
        runCatching {
            val switchClass = SWITCH_BTN_CLASS.toClass()
            switchClass.constructors.forEach { ctor ->
                ctor.hookAfter {
                    val btn = thisObject ?: return@hookAfter
                    var type: Class<*>? = switchClass
                    while (type != null && type != Any::class.java) {
                        type.declaredFields.forEach { field ->
                            if (field.type != Int::class.javaPrimitiveType) return@forEach
                            field.isAccessible = true
                            val current = runCatching { field.getInt(btn) }.getOrNull() ?: return@forEach
                            if (current == DEFAULT_COLOR) runCatching { field.setInt(btn, accentColor) }
                        }
                        type = type.superclass
                    }
                }
            }
        }.onFailure { WeLogger.w(TAG, "hook MMSwitchBtn failed", it) }

        // 【注意：这里**故意不** hook Paint.setColor】
        //
        // 第 13 轮曾把 Paint.setColor 当作「一处换掉所有编译进代码的品牌绿」的万能入口，代价是
        // 它在**每一帧每一次文字绘制**都会被调用（TextView.onDraw 里就有 mTextPaint.setColor(...)）。
        // 而本项目 hook 桥接的单次调用成本不低：Long 装箱查表 + MutableHookParam（含 IdentityHashMap）
        // + callbacks.toList() + 反射 Method.invoke —— 每帧几百上千次 = 全局卡顿、UI 线程抖动。
        // 实机反馈的「整个微信和 Joker 都有一点点卡顿、打开聊天卡顿」正是这条热路径的典型症状。
        //
        // 换成三个**调用频率低得多、覆盖面等价**的入口：
        //  ① PaintDrawable.setColor —— 代码里造的纯色 shape 底（原本走 Paint.setColor 的正是它）；
        //  ② TextView.setTextColor —— 文字颜色（每次绑定设一次，不是每帧）；
        //  ③ 上面/下面的 ColorDrawable / GradientDrawable（每次绑定设一次）。
        // 资源里的颜色由覆盖包负责，编译进代码的绿由这三处负责。
        // 注意：PaintDrawable **并没有声明** setColor(int)，颜色是它的构造函数里
        // `getPaint().setColor(color)` 写进去的。历史实现按名字 "setColor" 找方法，
        // 于是每次启动都抛 NoSuchElementException（实机日志：
        // `hook PaintDrawable.setColor failed / No method matching conditions in
        // android.graphics.drawable.PaintDrawable`），这条「代码里造的纯色 shape 底」
        // 覆盖链整条失效 —— 正是「Joker 改过/替换过的组件没被莫奈取色到位」的一部分。
        // 改为挂 PaintDrawable(int) 构造函数：构造完成后若画笔仍是默认色则替换成莫奈 accent。
        runCatching {
            android.graphics.drawable.PaintDrawable::class.java.declaredConstructors
                .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == Integer.TYPE }
                ?.hookAfter {
                    val drawable = thisObject as? android.graphics.drawable.PaintDrawable
                        ?: return@hookAfter
                    runCatching {
                        val paint = drawable.paint
                        if (paint.color == DEFAULT_COLOR) paint.color = accentColor
                    }
                } ?: error("PaintDrawable(int) constructor not found")
        }.onFailure { WeLogger.w(TAG, "hook PaintDrawable(int) failed", it) }

        // 文字：只拦「显式设置颜色」这一层，绝不拦每帧的绘制调用。
        runCatching {
            android.widget.TextView::class.java.reflekt().firstMethod {
                name = "setTextColor"
                parameters(Int::class)
            }.hookBefore {
                val color = args.getOrNull(0) as? Int ?: return@hookBefore
                if (color == DEFAULT_COLOR) args[0] = accentColor
            }
        }.onFailure { WeLogger.w(TAG, "hook TextView.setTextColor failed", it) }

        // ColorDrawable paints through Canvas.drawColor, so Paint.setColor never sees it.
        runCatching {
            ColorDrawable::class.java.reflekt().firstMethod {
                name = "setColor"
                parameters(Int::class)
            }.hookBefore {
                val color = args.getOrNull(0) as? Int ?: return@hookBefore
                if (color == DEFAULT_COLOR) args[0] = accentColor
            }
        }.onFailure { WeLogger.w(TAG, "hook ColorDrawable.setColor failed", it) }

        // GradientDrawable 承载微信绝大多数「按钮 / 开关 / 标签」底色，这些 shape 是代码里
        // setColor(品牌绿) 出来的，不是资源引用 —— 覆盖包碰不到，只能在这里换。
        runCatching {
            GradientDrawable::class.java.reflekt().firstMethod {
                name = "setColor"
                parameters(Int::class)
            }.hookBefore {
                val color = args.getOrNull(0) as? Int ?: return@hookBefore
                if (color == DEFAULT_COLOR) args[0] = accentColor
            }
        }.onFailure { WeLogger.w(TAG, "hook GradientDrawable.setColor failed", it) }
    }

    /** Accent applied to the compiled-in brand green; mirrors the primary the engine writes. */
    private val accentColor: Int
        get() = runCatching { HostInfo.application.resources.getColor(android.R.color.system_accent1_500, null) }
            .getOrDefault(0xFF07C160.toInt())

    // ------------------------------------------------------------------------------------------
    // Settings dialog
    // ------------------------------------------------------------------------------------------

    @SuppressLint("DiscouragedApi")
    private fun Context.showUnsupportedToast() =
        android.widget.Toast.makeText(this, R.string.monet_unsupported, android.widget.Toast.LENGTH_SHORT).show()

    private fun Context.showUnsupportedDialog() = showComposeDialog(this) {
        AlertDialogContent(
            title = { Text(stringResource(R.string.monet_options_title)) },
            text = { Text(stringResource(R.string.monet_unsupported)) },
            confirmButton = { Button(onDismiss) { Text(stringResource(R.string.action_close)) } },
        )
    }

    private fun showOptionsDialog(activity: Activity) = showComposeDialog(activity) {
        val progress by MonetEngine.progress.collectAsState()
        val result by MonetEngine.result.collectAsState()
        AlertDialogContent(
            title = {
                Text(stringResource(if (progress != null) R.string.monet_resolve_title else R.string.monet_options_title))
            },
            text = {
                Column {
                    Text(stringResource(R.string.feature_monet_engine_description), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))

                    Text(stringResource(R.string.monet_status), style = MaterialTheme.typography.titleSmall)
                    MonetStatusLine(progress, result)

                    Spacer(Modifier.height(12.dp))
                    RadioOption(stringResource(R.string.monet_bubble_modern), MonetEngine.bubbleStyle == MonetBubbleStyle.MODERN) {
                        MonetEngine.bubbleStyle = MonetBubbleStyle.MODERN
                    }
                    RadioOption(stringResource(R.string.monet_bubble_classic), MonetEngine.bubbleStyle == MonetBubbleStyle.CLASSIC) {
                        MonetEngine.bubbleStyle = MonetBubbleStyle.CLASSIC
                    }
                    RadioOption(stringResource(R.string.monet_bubble_pro), MonetEngine.bubbleStyle == MonetBubbleStyle.PRO) {
                        MonetEngine.bubbleStyle = MonetBubbleStyle.PRO
                    }

                    ToggleRow(stringResource(R.string.monet_multi_scene_corners), MonetEngine.multiSceneCorners) {
                        MonetEngine.multiSceneCorners = it
                    }
                    ToggleRow(stringResource(R.string.monet_error_colors), MonetEngine.errorColors) {
                        MonetEngine.errorColors = it
                    }

                    ToggleRow(stringResource(R.string.monet_badge_recolor), MonetEngine.badgeRecolor) {
                        MonetEngine.badgeRecolor = it
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.monet_color_source_summary), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button({ MonetEngine.startResolve(force = true) }) { Text(stringResource(R.string.monet_reresolve)) }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } },
        )
    }

    @Composable
    private fun MonetStatusLine(
        progress: MonetResolveProgress?,
        result: MonetResolveResult?,
    ) {
        when {
            progress != null -> {
                Text(stringResource(R.string.monet_status_running), style = MaterialTheme.typography.titleSmall)
                Text(progress.detail, style = MaterialTheme.typography.bodySmall)
                val completed = progress.completed
                val total = progress.total
                Spacer(Modifier.height(4.dp))
                if (completed != null && total != null) {
                    LinearProgressIndicator(
                        progress = { completed.toFloat() / total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$completed/$total",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            result is MonetResolveResult.Failure -> Text(
                stringResource(R.string.monet_resolve_failed, result.progress.stage.label, result.message),
                style = MaterialTheme.typography.bodySmall,
            )

            result is MonetResolveResult.Success -> Text(
                stringResource(
                    R.string.monet_status_summary,
                    MonetEngine.totalCount - result.bindings.unresolved.size,
                    MonetEngine.totalCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            else -> Text(stringResource(R.string.monet_status_missing), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun RadioOption(label: String, selected: Boolean, onSelect: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected, onSelect)
            Text(label)
        }
    }

    @Composable
    private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f))
            Switch(checked, onChange)
        }
    }
}
