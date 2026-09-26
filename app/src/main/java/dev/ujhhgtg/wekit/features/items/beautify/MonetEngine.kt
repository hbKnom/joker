package dev.ujhhgtg.wekit.features.items.beautify

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
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.ui.utils.theme.MonetPaletteFactory
import dev.ujhhgtg.wekit.ui.utils.theme.SeedResolver
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.monet.MonetApkResourceGraphLoader
import dev.ujhhgtg.wekit.utils.monet.MonetBindings
import dev.ujhhgtg.wekit.utils.monet.MonetBubbleStyle
import dev.ujhhgtg.wekit.utils.monet.MonetColors
import dev.ujhhgtg.wekit.utils.monet.MonetDexEvidenceCollector
import dev.ujhhgtg.wekit.utils.monet.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.utils.monet.MonetResourceKey
import dev.ujhhgtg.wekit.utils.monet.MonetResourceValue
import dev.ujhhgtg.wekit.utils.monet.MonetResourceResolver
import dev.ujhhgtg.wekit.utils.monet.MonetResolveProgress
import dev.ujhhgtg.wekit.utils.monet.MonetResolveResult
import dev.ujhhgtg.wekit.utils.monet.MonetResolveStage
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimeState
import dev.ujhhgtg.wekit.utils.monet.MonetStructureMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import dev.ujhhgtg.wekit.loader.utils.ActivityResourceHooks
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
 * The palette follows the WeKit theme (see [R.string.monet_color_source_summary]); WeChat's own
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
     * 应用后多少毫秒内又重启才算「疑似崩溃」。
     *
     * 【2026-09-26 修正】原来取 90 秒，实机上是**误伤**：用户在 90 秒内自己重开微信
     * （正常操作）会被记成一次「疑似被注入的包搞崩」。日志里那次熔断
     * （16:03:09「连续 2 次疑似导致微信异常退出，已自动停用注入」）真正的崩溃其实是
     * 会话列表 adapter 的 ISE（wekit-crash-2026-09-26_16-02-11-423，与莫奈毫无关系），
     * 结果莫奈被整段停用 —— 用户看到的就是「有的生效、有的没生效」。
     * 收到 20 秒：只有真正的「一注入就秒退」才会被记账。
     */
    private const val RESTART_WINDOW_MS = 20_000L

    /**
     * 连续这么多次疑似崩溃就自动停用注入（等用户重新解析再放行）。
     *
     * 3 次而不是 2 次：单次误判的代价是「莫奈整个启动都不生效」，比多试一次严重得多。
     */
    private const val MAX_FAIL_STREAK = 3

    /**
     * 连续这么多次「解析没跑完就退出」就跳过解析（等用户重新解析再放行）。
     *
     * 取 1：解析期崩溃是**每次启动必现**的（实机 2026-09-26 四分钟内四次原生崩溃），
     * 多试一次只是多闪退一次。用户随时可以在设置里点「重新解析」放行。
     */
    private const val MAX_RESOLVE_FAIL_STREAK = 1

    /**
     * 超过这个窗口的「解析进行中」标记视为陈旧，不再累计。
     *
     * 从 30 分钟收到 6 分钟：一次完整解析实机约 3 分钟（16:05:23 → 16:08:25 = 182070ms），
     * 用户在解析后十几分钟才重启微信属于正常使用，不该被算成「解析没跑完」。
     */
    private const val RESOLVE_INTERRUPT_WINDOW_MS = 6 * 60 * 1000L

    /** 启动后延后这么久才开始资源解析：把最重的一段挪出启动关键路径。 */
    private const val INITIAL_RESOLVE_DELAY_MS = 20_000L

    /**
     * 「写包 -> 注入 -> 逐条冒烟校验」最多重写几轮。
     *
     * 校验不通过的 id 会被拉黑（`monet_blacklist.json`）后重写包，直到整包健康；三轮仍不健康
     * 说明这批条目宿主根本不认，就放弃本次注入 —— 绝不把坏包留在微信进程里试错。
     */
    private const val MAX_PACKAGE_ATTEMPTS = 3

    /** 宿主 `res/` 下算作「可覆盖的真实文件」的扩展名（用于过滤别名 drawable）。 */

    const val KEY_BUBBLE_STYLE = "monet_bubble_style"
    const val KEY_MULTI_SCENE_CORNERS = "monet_multi_scene_corners"
    const val KEY_ERROR_COLORS = "monet_error_colors"

    private var bubbleStyleName by prefOption(KEY_BUBBLE_STYLE, MonetBubbleStyle.MODERN.name)
    private var multiSceneCornersPref by prefOption(KEY_MULTI_SCENE_CORNERS, false)
    private var errorColorsPref by prefOption(KEY_ERROR_COLORS, false)

    /**
     * DEX 证据提供者：歧义角色交给 [MonetDexEvidenceCollector]（旧版成功运行时就是这么消歧的）。
     *
     * DexKit 没起来（未 root / native 没加载）或扫描失败都只返回空表 —— 调用方
     * [MonetStructureMatcher.resolveCandidateIds] 拿到空表会退化成纯结构消歧，
     * 绝不会因为「拿不到证据」把整次解析打断成「解析出错」。
     */
    private val dexEvidenceProvider = MonetDexEvidenceProvider { candidates ->
        runCatching { MonetDexEvidenceCollector.collect(candidates) }
            .onFailure { WeLogger.w(TAG, "DEX 证据收集失败，改用结构消歧", it) }
            .getOrDefault(emptyList())
    }

    private val bindingsFile: File by lazy { (KnownPaths.moduleData / "monet_bindings.json").toFile() }

    /**
     * 运行时资源包 / 缓存目录。
     *
     * 用 `moduleData`（`Android/data/<宿主>/<TAG>`）而**不是** `moduleCache`（`.../cache/<TAG>`）：
     * 后者落在宿主的**外部缓存**目录里，既会被本模块自己的 AutoCleanCache 扫到（现已单独保护），
     * 也会被系统在存储紧张时整体清理。实机日志（2026-09-26）里「缓存未命中…包存在=false」
     * 每次启动都出现，正是「缓存被清 → 全量重解析（分钟级）→ 卡顿 + 反复进入易出错路径」的来源。
     */
    private val runtimeDir: File by lazy { (KnownPaths.moduleData / "monet").toFile() }

    /** 旧版（外部缓存目录）的落点：只用于兼容读取与迁移，不再写入。 */
    private val legacyRuntimeDir: File by lazy { (KnownPaths.moduleCache / "monet").toFile() }

    /**
     * 绑定缓存的第二份副本，和运行时包放在同一个目录。
     *
     * 实机日志（2026-09-25）里 `moduleData/monet_bindings.json` 每次启动都读不回来，导致**每次冷启动
     * 都全量重解析**（单次 100 秒以上，期间主线程被拖到 2.6 秒延迟，用户看到的就是卡顿）。
     * 运行时包写在 `runtimeDir` 里是能被写成功的，所以缓存也放这里，两边都写、任一份都能读。
     */
    private val bindingsCacheFile: File by lazy { File(runtimeDir, "monet_bindings.json") }

    /** 旧版绑定缓存（外部缓存目录），仅用于兼容读取。 */
    private val legacyBindingsCacheFile: File by lazy { File(legacyRuntimeDir, "monet_bindings.json") }

    /** 注入自保状态：见 [MonetRuntimeState]。 */
    private val runtimeStateFile: File by lazy { File(runtimeDir, "monet_runtime_state.json") }

    private val _progress = MutableStateFlow<MonetResolveProgress?>(null)
    val progress: StateFlow<MonetResolveProgress?> = _progress.asStateFlow()

    private val _result = MutableStateFlow<MonetResolveResult?>(null)
    val result: StateFlow<MonetResolveResult?> = _result.asStateFlow()

    private val _runtimePackage = MutableStateFlow<File?>(null)
    val runtimePackage: StateFlow<File?> = _runtimePackage.asStateFlow()

    @Volatile
    private var resolving = false

    /** Whether the platform exposes the runtime resource loader this engine is built on. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    var bubbleStyle: MonetBubbleStyle
        get() = MonetBubbleStyle.entries.firstOrNull { it.name == bubbleStyleName }
            ?: MonetBubbleStyle.MODERN
        set(value) {
            if (bubbleStyleName == value.name) return
            bubbleStyleName = value.name
            onOptionsChanged()
        }

    var multiSceneCorners: Boolean
        get() = multiSceneCornersPref
        set(value) {
            if (multiSceneCornersPref == value) return
            multiSceneCornersPref = value
            onOptionsChanged()
        }

    var errorColors: Boolean
        get() = errorColorsPref
        set(value) {
            if (errorColorsPref == value) return
            errorColorsPref = value
            onOptionsChanged()
        }

    /** `resolved / total` for the currently loaded binding set, or `null` when never analysed. */
    val resolvedCount: Int?
        get() = cachedBindings()?.let { MonetStructureMatcher.roleIds.size - it.unresolved.size }

    val totalCount: Int
        get() = MonetStructureMatcher.roleIds.size

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (!newState || isSupported) return true
        context.showUnsupportedToast()
        return false
    }

    override fun onEnable() {
        if (!isSupported) {
            WeLogger.w(TAG, "ResourcesLoader needs API 30, not enabling")
            return
        }
        WeLogger.i(
            TAG,
            "monet onEnable build=${HostInfo.versionName} (${HostInfo.versionCode}) " +
                "uid-owner=${Process.myUid() / 100000}",
        )
        runCatching { installBrandColorHooks() }
            .onFailure { WeLogger.w(TAG, "brand-colour fallback hooks unavailable", it) }
        // ③ 宿主每建一个 Activity，就把运行时包挂到它自己的 `Resources` 上（幂等）。
        //    实机反馈「解析正常、包 applied、取色/圆角/角标一律原生」的最可疑点就在这里：
        //    loader 只挂在 application.resources 上，而真正渲染界面的那批 Resources 未必
        //    与之共享同一个 ResourcesImpl（宿主的 Activity 会按自己的 config/主题另建 impl）。
        ActivityResourceHooks.register { attachRuntimeLoader(it) }
        // ① 先把色板发给「WeKit 自己注入进微信界面」的组件。这一步只读系统的 Material You
        //    token（android.R.color.system_*），**不依赖宿主资源解析**，所以即使解析失败、
        //    被熔断跳过、或用户在解析期间还在用微信，注入界面也能拿到莫奈色 ——
        //    不会再出现「微信原生取色了、WeKit 改过的底栏/标题栏还是旧配色」的割裂。
        thread(name = "MonetPalette") { publishPalette() }
        // ② 资源解析是整条链最贵的一步（实机 11000+ 个二进制 XML），落在启动瞬间会直接
        //    和微信首帧/首屏抢 CPU（实机反馈的卡顿来源之一），延后到启动稳定之后再跑。
        scheduleInitialResolve()
    }

    /** 启动后延后 [INITIAL_RESOLVE_DELAY_MS] 再解析；调度失败就立刻解析，绝不因此不解析。 */
    private fun scheduleInitialResolve() {
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    runCatching {
                        if (isActive && isSupported) startResolve(force = false)
                    }.onFailure { WeLogger.w(TAG, "delayed initial resolve failed", it) }
                },
                INITIAL_RESOLVE_DELAY_MS,
            )
        }.onFailure { error ->
            WeLogger.w(TAG, "cannot schedule initial resolve, resolving immediately", error)
            startResolve(force = false)
        }
    }

    override fun onDisable() {
        _progress.value = null
        _result.value = null
        _runtimePackage.value = null
        // 注入界面的配色跟着一起熄火，避免「原生已经回到旧配色、WeKit 组件还是莫奈色」的反向割裂
        MonetColors.applied.value = null
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
                        // （wekit-2026-09-26.log 的「用时就绪 179018ms / 209816ms」），
                        // 期间整机被拖住 —— 用户反馈的「初加载特别卡顿」大半来自这里。
                        WeLogger.w(
                            TAG,
                            "绑定缓存缺失但运行时包已存在（${packageFile.name}），直接复用、跳过全量解析",
                        )
                    }
                    // 复用来的包同样要过冒烟校验：校验不过 = 这个包在**当前**微信上会让宿主
                    // 取资源时崩，必须就地销毁并拉黑那批 id，绝不能因为「包名里带着 fingerprint」
                    // 就默认它没问题（上一版就是这样把坏包反复注入的）。
                    val reused = applyRuntimePackage(packageFile, cached)
                    if (reused.isNotEmpty()) {
                        detachLastApplied()
                        persistBlacklist(fingerprint, loadBlacklist(fingerprint) + reused)
                        runCatching { if (packageFile.exists()) packageFile.delete() }
                        error(
                            "复用的运行时资源包冒烟校验未通过（${reused.size} 条覆盖资源不可用），" +
                                "已回滚、拉黑并删除该包；下次启动会重新解析",
                        )
                    }
                    recordRuntimeApplied(packageFile)
                    publishPalette()
                    if (cached != null) {
                        _result.value = MonetResolveResult.Success(cached, packageFile)
                    }
                    WeLogger.i(
                        TAG,
                        "复用已有运行时包，本次启动未做资源解析，用时 ${(System.nanoTime() - startedAt) / 1_000_000} ms",
                    )
                    return@thread
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
                // （wekit-crash-2026-09-26_13-33-02 / 13-43-03）正是这条。所以先把宿主各 APK 的
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
                //  resource ID #0x7f08116c，wekit-crash-2026-09-26_13-33-02 / 13-43-03）。
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
                // 写包 -> 注入 -> 逐条校验 -> 不通过就拉黑重写。
                //
                // 为什么不让「一个坏条目」蒙混过关：宿主的 drawable 取值路径会在**取到值之后**
                // 回查一次资源名，回查失败直接抛 Resources$NotFoundException 崩进程
                //（wekit-crash-2026-09-26_13-33-02 / 13-43-03：File res/drawable/ao1.xml from
                // drawable resource ID #0x7f08116c）。所以判据只能是「每一条写进包里的资源，
                // 注入之后都既能取值、又能按名字解析」，达不到就把那几条剔出去重写。
                val excluded = loadBlacklist(fingerprint)
                var attempt = 0
                while (true) {
                    attempt++
                    if (!MonetRuntimePackageWriter.write(
                            packageFile,
                            info.packageName,
                            resolution.plan,
                            hostReference,
                            hostDrawableIsRealFile,
                            excluded,
                        )
                    ) {
                        // 条目 id 校验没过 = 覆盖会落到别的资源上，写了就是闪退，宁可这次不注入。
                        error(
                            "运行时资源包构建失败（资源 id 校验未通过），已中止本次注入以免影响微信运行" +
                                "（可在设置里重新解析）",
                        )
                    }
                    val unhealthy = applyRuntimePackage(packageFile, resolution.bindings)
                    if (unhealthy.isEmpty()) break
                    detachLastApplied()
                    excluded += unhealthy
                    persistBlacklist(fingerprint, excluded)
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
                persistBindings(resolution.bindings)
                recordRuntimeApplied(packageFile)
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
        val applied = lastAppliedLoader ?: return
        lastAppliedLoader = null
        _runtimePackage.value = null
        runCatching { applied.first.removeLoaders(applied.second) }
            .onFailure { WeLogger.w(TAG, "cannot detach runtime loader", it) }
    }

    /**
     * 注入运行时包并做冒烟校验。
     *
     * @return 校验**不通过**的覆盖资源 id；空集 = 包健康、已生效。拿到非空集合时调用方必须
     *   [detachLastApplied]，把这些 id 拉黑后重写包（见 [startResolve]）。
     */
    private fun applyRuntimePackage(file: File, bindings: MonetBindings?): Set<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
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
        val unhealthy = runCatching { smokeTestRuntimePackage(resources, bindings) }
            .onFailure { WeLogger.w(TAG, "smoke test failed to run", it) }
            .getOrDefault(emptySet())
        if (unhealthy.isNotEmpty()) {
            runCatching { resources.removeLoaders(loader) }
                .onFailure { WeLogger.e(TAG, "cannot roll back runtime loader", it) }
            _runtimePackage.value = null
            return unhealthy
        }
        lastAppliedLoader = resources to loader
        _runtimePackage.value = file
        WeLogger.i(TAG, "applied ${file.name} (${file.length()} bytes)")
        return emptySet()
    }

    /** 已经挂过运行时包的 `Resources`（弱引用，避免把宿主的 Resources 钉在内存里）。 */
    private val runtimeLoaderAttached: MutableSet<Resources> =
        Collections.newSetFromMap(WeakHashMap<Resources, Boolean>())

    private var activityResourceProbeLogged = false

    /**
     * 把运行时资源包挂到**任意一个** `Resources` 实例上（幂等、失败只降级）。
     *
     * 由 [ActivityResourceHooks] 在每个 Activity 创建时调用。原因见 [onEnable] ③：
     * loader 之前只挂在 `application.resources` 上，而实机反馈「解析正常、包 applied、
     * 取色/圆角/角标一律没生效」，最可疑的就是宿主 UI 真正使用的那批 `Resources`
     * 并没有拿到这个 loader（`Resources.addLoaders` 只作用于调用它的那个实例/impl）。
     */
    private fun attachRuntimeLoader(resources: Resources) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val file = _runtimePackage.value ?: return
        if (!file.isFile) return
        if (!runtimeLoaderAttached.add(resources)) return
        try {
            val loader = android.content.res.loader.ResourcesLoader()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                loader.addProvider(ResourcesProvider.loadFromApk(descriptor))
            }
            resources.addLoaders(loader)
        } catch (error: Throwable) {
            runtimeLoaderAttached.remove(resources)
            WeLogger.w(TAG, "cannot attach runtime package to activity resources", error)
            return
        }
        probeActivityResources(resources)
    }

    /**
     * 一次性诊断：宿主 Activity 自己的 `Resources` 上，我们写进包里的角色 id 到底能解析出几个。
     *
     * 与 `applyRuntimePackage` 里那次冒烟校验的计数对比，就能判定「loader 到底有没有挂到
     * 宿主界面真正用的 Resources 上」：两边数字接近 = 挂上了；这里为 0 而 application 那边
     * 非 0 = 之前根本没挂上（本轮修的正是这个）。只打一次日志，不在热路径刷。
     */
    private fun probeActivityResources(resources: Resources) {
        if (activityResourceProbeLogged) return
        activityResourceProbeLogged = true
        runCatching {
            val ids = cachedBindings()?.roles?.values?.toList() ?: return@runCatching
            if (ids.isEmpty()) return@runCatching
            var resolvable = 0
            for (id in ids) {
                if (runCatching { resources.getResourceTypeName(id) }.getOrNull() != null) {
                    resolvable++
                }
            }
            WeLogger.i(
                TAG,
                "Activity Resources 可见性探测：${ids.size} 个角色 id 中有 $resolvable 个可解析" +
                    "（与冒烟校验的计数对比可判定运行时包是否作用在宿主界面上）",
            )
        }.onFailure { WeLogger.d(TAG, "activity resources probe failed", it) }
    }

    /**
     * 冒烟校验：把**写进包的每一条**覆盖资源都按它的真实类型取一次，全部成功才算健康。
     *
     * 旧实现只抽查 8 条颜色、且「至少一条成功就算通过」，所以一个「颜色都对、但某个 drawable
     * 条目指向了包内不存在的 XML」的坏包能毫无阻碍地留下来 —— 宿主随后取那个 drawable 时
     * 直接抛 `Resources$NotFoundException` 崩进程（实机 wekit-crash-2026-09-26_13-33-02 /
     * 13-43-03 就是这个）。判据必须严到「任何一条我们自己写进去的资源取不出来 = 包是坏的」。
     *
     * 类型分流按 `Resources.getResourceTypeName`：drawable/mipmap 走 `getDrawable`（这条正是
     * 崩溃路径），color 走 `getColor`，string 走 `getString`；其它类型本轮不写入，取不到不算我们写坏。
     *
     * 返回「不健康的 id 集合」而不是 Boolean：调用方要拿这批 id 去拉黑 + 重写包。只知道
     * 「包坏了」是不够的 —— 坏 id 不带回来，下一轮写包还会原样写回去，等于每次开机崩一遍。
     */
    private fun smokeTestRuntimePackage(resources: Resources, bindings: MonetBindings?): Set<Int> {
        val ids = bindings?.roles?.values?.toList() ?: return emptySet()
        if (ids.isEmpty()) return emptySet()
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
                    "合计 ${unhealthy.size}/${ids.size} 条将被拉黑并重写包",
            )
            return unhealthy
        }
        WeLogger.i(TAG, "冒烟校验通过：$valueOk 条覆盖资源全部可取用、且都能按名字解析")
        return emptySet()
    }

    /**
     * 把引擎色板发布给「WeKit 注入微信界面的组件」使用（见 [MonetColors]）。
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
    private fun recordRuntimeApplied(packageFile: File) {
        writeRuntimeState(
            MonetRuntimeState(
                packageName = packageFile.name,
                appliedAt = System.currentTimeMillis(),
                failStreak = readRuntimeState().failStreak,
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
        // 实机反馈的「整个微信和 WeKit 都有一点点卡顿、打开聊天卡顿」正是这条热路径的典型症状。
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
        // 覆盖链整条失效 —— 正是「WeKit 改过/替换过的组件没被莫奈取色到位」的一部分。
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
