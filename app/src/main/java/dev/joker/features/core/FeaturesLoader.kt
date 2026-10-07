package dev.joker.features.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.tencent.mm.ui.LauncherUI
import dev.joker.R
import dev.joker.constants.Preferences
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.cache.DexCacheManager
import dev.joker.features.items.system.SafeMode
import dev.joker.i18n.LocaleResourceMode
import dev.joker.i18n.LocalizedContextFactory
import dev.joker.i18n.JokerLocaleController
import dev.joker.ui.content.DexResolver
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.HostInfo
import dev.joker.utils.TargetProcesses
import dev.joker.utils.WeLogger
import dev.joker.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    /** 单个功能 startup() 超过这个耗时就在日志里单独点名（排查「启动特别卡」用）。 */
    private const val SLOW_STARTUP_MS = 200L

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_FEATURES

        val safeMode = SafeMode.isEnabled
        val featuresToStart = if (safeMode) {
            allFeatures.filterIsInstance<ApiFeature>()
        } else {
            allFeatures
        }
        if (safeMode) {
            WeLogger.i(
                TAG,
                "safe mode active: loading only ${featuresToStart.size} ApiFeature(s), " +
                    "skipping ${allFeatures.size - featuresToStart.size} feature(s)",
            )
        }
        // 非主进程（appbrand0/1、xweb_privileged_process、push…）只保留「声明了在本进程生效」
        // 的功能。BaseFeature.enable() 本来就用同一个条件挡掉其余功能，这里提前过滤不会改变
        // 任何功能的生效范围，只是省掉它们在这些进程里的缓存反序列化与 startup 开销 ——
        // 实机日志里每个小程序/内置浏览器子进程都要花 0.5~1.2s 走一遍全量加载，而这类进程
        // 每分钟会启停好几次（微信打开小程序、公众号文章都会拉起）。
        val processScopedFeatures = if (TargetProcesses.isInMain) {
            featuresToStart
        } else {
            val currentProcess = TargetProcesses.currentType
            featuresToStart.filter { feature ->
                feature is ApiFeature || currentProcess in feature.targetProcesses
            }
        }

        // 【2026-09-27 修启动卡顿】读持久化开关原本在**进程过滤之前**、对全部 ~238 个
        // SwitchFeature 各做一次 SQLite 查询（每进程、且 28 个进程里有 21 个是非主进程，
        // 它们只用得上极少数功能）。挪到进程过滤之后，只读本进程真正会用的那些功能。
        // 顺序安全性：进程过滤只看 targetProcesses，不看 isEnabled；DexCache 相关的几行
        // （getOutdatedItems / loadDescriptorsFromCache）也不读开关，所以挪动不改变语义。
        processScopedFeatures.filterIsInstance<SwitchFeature>().forEach(SwitchFeature::loadPersistedState)

        val allDexItems = processScopedFeatures.filterIsInstance<IResolveDex>()

        // 铁律守卫：只有 IResolveDex 的 feature 会被送去 DexKit 解析（见上面的 filterIsInstance）。
        // 若某个 feature 声明了 DexKit 委托却没实现该接口，它的委托永远停在「未解析」状态：
        // descriptor 为 null，而 isPlaceholder 只在 descriptor == PLACEHOLDER 时为 true，
        // 于是 `if (!delegate.isPlaceholder)` 这类保护形同虚设，访问 delegate 会抛
        // IllegalStateException("Method not found for key: …")，被 enable() 的 runCatching 吃掉后
        // 执行 unhookAll()，把该 feature 已装好的 hook 全部摘掉 —— 表现就是「开关打开却完全没生效」。
        // （AutoEnableSendOriginalMedia 2026-09-22 的真实线上故障，这里加日志守卫防止再犯。）
        processScopedFeatures.forEach { feature ->
            if (feature !is IResolveDex && (feature as BaseFeature).dexDelegates.isNotEmpty()) {
                WeLogger.e(
                    TAG,
                    "守卫：${feature.technicalId} 声明了 ${feature.dexDelegates.size} 个 DexKit 委托" +
                        "却没有实现 IResolveDex —— 解析永远不会发生，访问委托会抛异常并 unhook 整条功能" +
                        "（keys=${feature.dexDelegates.map { it.key }}）",
                )
            }
        }

        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // Load what we can from cache. Items with *some* missing keys are still partially loaded —
        // their valid delegates work immediately; only the item itself is queued for re-resolution.
        val cacheFailedItems = loadDescriptorsFromCache(validItems)
        val allBrokenItems = (outdatedItems + cacheFailedItems).distinct()
        brokenDexItems = allBrokenItems

        if (allBrokenItems.isNotEmpty())
            handleBrokenItems(allBrokenItems)

        // 延后批次：只包含「用户在启动瞬间绝不可能用到」的按需功能（聊天内悬浮控件、相册选择器、
        // 朋友圈/激励广告等）。这些功能的 hook 安装实测每个 200~800ms，全部塞在启动同步阶段会让
        // 微信主线程在启动时连续阻塞十几秒（实机日志：一次冷缓存启动 `loading all features took
        // 16.379601036s`，全部落在 Instrumentation.callApplicationOnCreate 里，是「整个微信和
        // Joker 都有一点点卡顿」的主要来源）。其余功能保持原有同步安装顺序不变，避免影响启动期
        // 就需要生效的功能（启动页/开屏广告/莫奈/首页 UI 等）。
        val (syncFeatures, deferredFeatures) = processScopedFeatures.partition {
            safeMode || it.technicalId !in DEFERRED_STARTUP_IDS
        }.let { (sync, deferred) ->
            // 延后批次按「用户多久会用到」排序：会话列表头像、通知这类几秒内就会出现在屏幕上
            // 的排最前，debug 工具排最后。总耗时不变，但用户感知到的延迟最小。
            sync to deferred.sortedBy { feature ->
                val index = DEFERRED_STARTUP_ORDER.indexOf(feature.technicalId)
                if (index < 0) Int.MAX_VALUE else index
            }
        }

        val elapsed = measureTime {
            syncFeatures.forEach { feature -> runFeatureStartup(feature, allBrokenItems) }
        }
        WeLogger.i(TAG, "loading all features took $elapsed")

        // 主线程空闲（首帧画完）后再用「切片 + 让出」的方式装延后批次，不再阻塞启动关键路径。
        if (deferredFeatures.isNotEmpty()) {
            scheduleDeferredStartup(deferredFeatures, allBrokenItems)
        }
        // 【2026-09-27 修「开关开着却没生效、得去设置里关掉再打开」】以前这行只在**没有延后
        // 批次**时才执行（else 分支里），而实机日志里 7 次「20 feature(s)」批次**0 次**跑到
        // `deferred feature startup finished` —— 延后批次没跑完 → rearmDeadFeatures 从未执行 →
        // 启动期 onEnable 抛过异常、被 unhookAll() 摘干净的功能永远自愈不了。
        // 现在无条件兜一次；正在延后队列里排队的会被 deferredPending 跳过，
        // 因此不会把延后批次拉回同步阶段（那会重新变成启动卡顿）。
        rearmDeadFeatures(1)

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            val context = LocalizedContextFactory.create(
                HostInfo.application,
                JokerLocaleController.resolvedLocale,
                LocaleResourceMode.InjectedHost,
            )
            showToast(context, context.getString(R.string.noncompose_features_loaded))
        }
    }

    /**
     * 启动同步阶段结束后才安装的功能（按需触发，启动瞬间用不到）。
     *
     * 判定标准：该功能的效果只会在「用户主动进入聊天 / 打开相册 / 刷朋友圈」等动作之后出现，
     * 微信启动过程中（首页首帧之前）不可能被调用到。**不要**把首页/会话列表/启动页相关功能
     * 放进来 —— 那些必须在启动同步阶段装好，否则首帧就不生效。
     */
    private val DEFERRED_STARTUP_ORDER = listOf(
        "@所有人",
        "WeAgent",
        "去除菜单限制",
        "快捷回底",
        "悬浮输入框",
        "朋友圈评论防撤回",
        "跳过激励广告",
        "移除通话时聊天限制",
        // ------------------------------------------------------------------
        // 以下是从「启动同步阶段」挪进来的。实机日志里它们的 startup() 各占
        // 200ms~1.7s（圆角头像 1680ms、允许领取私聊红包 1069ms、上传原图 1060ms…），
        // 加起来 8 秒以上，全部堵在微信首帧之前的 Instrumentation.callApplicationOnCreate
        // 里 —— 这正是用户反复反馈的「初加载特别卡顿」。它们的生效时机都晚于
        // 「用户主动进入聊天 / 相册 / 朋友圈 / 我的页 / 设置页」，首屏不可能用到，
        // 所以挪到首帧之后的切片批次（见 scheduleDeferredStartup）。
        //
        // 排序 = 用户可能多快用到它，越靠前越先装：
        //
        // 【2026-09-27 回移】「圆角头像 / 通知进化 / 半屏相册选择器」已挪回**同步批次**。
        // 实机日志（joker-2026-09-27.log）显示延后批次挂在 IdleHandler 上：
        // 「09:05:10.112 排入 23 个功能 → 09:05:18.910 才跑完」，也就是说主线程空闲了
        // **8.8 秒**之后才轮到它们。而这三个功能的生效时机都早于那一刻：
        //   · 圆角头像：会话列表第一帧就把头像 View 建好了，钩子晚装只能等下次重建；
        //   · 通知进化：通知随时可能到达（甚至就在微信刚起来的那几秒）；
        //   · 半屏相册选择器：钩的是宿主 Activity 的 onCreate，装晚了就整整漏掉一次。
        // 用户看到的就是「重启微信后这三个功能失效，得去设置里关掉再打开才生效」。
        // 三者实测 startup() 合计不到 1 秒（圆角头像 <200ms、半屏相册 <200ms、通知进化 ~450ms），
        // 换回「重启即生效」比省这几百毫秒重要。
        "反已读追踪",            // 聊天内
        "对话框窗口级背景模糊",   // 任意弹窗
        // ------------------------------------------------------------------
        // 【第 52 轮】依据实机日志（joker-2026-10-07.log）再挪一批进延后批次。
        //
        // 日志里这些功能**都在同步批次**、且各自 200~760ms：
        //   「移除嵌入广告」755ms、「主页侧滑面板」722/520/322ms、「视频号分享菜单扩展」307ms、
        //   「联系人页面扩展」226ms、「悬浮标题栏」274ms、「朋友圈评论防撤回」425ms、
        //   「禁用评论长度限制」216ms、「下载媒体」256ms、「转发收藏语音」224ms、
        //   「隐藏模块应用」218ms、「重定向微信日志」286ms —— 合计 3 秒以上，
        //   加上原本就在同步批次的那些，微信首帧前的主线程被占用 10 秒上下。
        // 它们的生效时机都晚于「用户主动进入对应页面/手势」，所以放在延后批次**前排**
        // （IdleHandler 一旦空闲就装，另有 3s 超时兜底），不影响「重启即生效」的可用时机。
        //
        // 有意**不挪**的：移除开屏广告（启动第一屏就要用）、美化首页底部导航栏（首页即见）、
        // 应用全局背景（一进界面就可见，挪走会像「背景丢了」）、各「服务」类（随时可能被调用）。
        "主页侧滑面板",
        "悬浮标题栏",
        "联系人页面扩展",
        "移除嵌入广告",
        "视频号分享菜单扩展",
        "朋友圈评论防撤回",
        "禁用评论长度限制",
        "下载媒体",
        "转发收藏语音",
        "隐藏模块应用",
        "重定向微信日志",
        "解除消息多选数量限制",   // 聊天内
        "解除单个表情数量上限",   // 聊天内
        "定时发送",              // 聊天内
        "底部详细信息",          // 朋友圈
        "移除个性签名限制",      // 我的页
        "显示隐藏朋友设置项",     // 设置页
        "允许领取私聊红包",       // 收到红包时
        "捡漏历史红包",          // 打开红包时
        "上传原图",              // 发送图片时
        "Eruda 调试面板",        // 诊断工具，最后装
    )

    private val DEFERRED_STARTUP_IDS = DEFERRED_STARTUP_ORDER.toSet()

    /**
     * 延后批次单片最多占用主线程多久，超过就 postDelayed 让出一次消息循环。
     *
     * 从 120ms 收紧到 24ms：这是「装完一个功能后回头检查」的上限，单个功能自身最长可达
     * 1.7s，所以它不是保证；但压到 24ms 能让两个功能之间的空隙更大，首帧之后的滑动、
     * 点击不会被连续挤占。
     */
    private const val DEFERRED_SLICE_BUDGET_MS = 24L

    /** 两次切片之间让给 UI 的一帧时长（毫秒）。 */
    private const val DEFERRED_SLICE_GAP_MS = 16L

    /** 自愈重挂的两次尝试之间的间隔。 */
    private const val REARM_DELAY_MS = 4_000L

    /** 自愈重挂的最大尝试次数（之后只留一行日志，绝不无限重试）。 */
    private const val REARM_MAX_ATTEMPTS = 3

    /**
     * 延后批次的「无论如何也要开始装」兜底时限。
     *
     * IdleHandler 只在主线程空闲时触发，而微信启动前后主线程长期不空闲 →
     * 实机上 7 次 20 功能的延后批次一次都没跑到收尾日志（相当于这 20 个功能没装）。
     */
    private const val DEFERRED_START_TIMEOUT_MS = 3_000L

    /** 延后批次超过这个时长仍未装完就打一条 warn，暴露「到底装没装上」。 */
    private const val DEFERRED_STALL_WARN_MS = 30_000L

    /** 延后批次是否已经开始（IdleHandler 与超时兜底两条路只允许一条真正启动）。 */
    private val deferredDrainStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 【第 52 轮】本进程内已经 startup() 过的功能 id（防重复安装；跨进程互不影响）。 */
    private val startedFeatureIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet<String>())

    private val startupDuplicateLogged: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet<String>())

    private fun logOnceStartupDuplicate(id: String) {
        if (startupDuplicateLogged.add(id)) {
            WeLogger.i(TAG, "跳过重复安装（本进程内已 startup 过）：$id")
        }
    }

    /** 最近一次解析里「缓存不完整、正等 DexKit 重新解析」的功能（自愈重挂必须跳过它们）。 */
    private var brokenDexItems: List<IResolveDex> = emptyList()

    /**
     * 还在延后批次里排队、尚未执行 `startup()` 的功能 id。
     *
     * [rearmDeadFeatures] 必须跳过它们：这些功能「开关是开的、但还没轮到装」，
     * 不跳过就会被自愈重挂当成「开关开着却没装」而立刻同步装上 —— 延后批次就白排了。
     */
    private val deferredPending: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * 自愈重挂：把「开关是开的、但钩子实际没装上」的功能重新挂一遍。
     *
     * 为什么需要（用户第 0 条反馈的根因之一）：`BaseFeature.enable()` 里 `onEnable()` 抛出的
     * 异常会被 `runCatching` 吃掉并 `unhookAll()`，功能从此停在「开关开着、什么都没装」的状态，
     * 而**启动阶段不会重试** —— 用户看到的就是「重启微信后这个功能失效，得去设置里关掉再打开
     * 才生效」（圆角头像 / 通知进化 / 半屏相册选择器都踩过这一类）。
     *
     * 只重挂 `SwitchFeature`（含 `ClickableFeature`）里「按配置本该启用、且当前确实没装」的：
     *  · 已装的（`isActive`）直接跳过 → 天然幂等，重复调用没有副作用；
     *  · 缓存不完整、还在等 DexKit 重新解析的跳过 → 委托没解析，重挂只会再炸一次；
     *  · `startup()` 被功能自己重写过的（非 SwitchFeature）不碰 → 避免重复安装。
     */
    private fun rearmDeadFeatures(attempt: Int) {
        if (!TargetProcesses.isInMain) return
        val current = TargetProcesses.currentType
        val dead: List<BaseFeature> = FeaturesProvider.ALL_FEATURES.filter { feature ->
            feature is SwitchFeature &&
                current in feature.targetProcesses &&
                !feature.isActive &&
                (feature.isEnabled || (feature as? ClickableFeature)?.alwaysEnabled == true) &&
                feature.technicalId !in deferredPending &&
                brokenDexItems.none { it === feature }
        }
        if (dead.isEmpty()) return
        dead.forEach { feature ->
            WeLogger.i(TAG, "自愈重挂（第 $attempt 次）：${feature.technicalId}")
            feature.enable()
        }
        if (attempt < REARM_MAX_ATTEMPTS) {
            Handler(Looper.getMainLooper()).postDelayed({ rearmDeadFeatures(attempt + 1) }, REARM_DELAY_MS)
        } else {
            WeLogger.w(
                TAG,
                "自愈重挂 $REARM_MAX_ATTEMPTS 次后仍未生效：${dead.map { it.technicalId }}",
            )
        }
    }

    private fun runFeatureStartup(feature: BaseFeature, allBrokenItems: List<IResolveDex>) {
        val isBroken = feature is IResolveDex && allBrokenItems.contains(feature)

        if (isBroken) {
            WeLogger.w(TAG, "skipping ${feature.technicalId} — incomplete cache, awaiting re-resolution")
            return
        }

        // 【第 52 轮】幂等护栏：同一个功能在同一次进程生命周期里只 startup() 一次。
        // 实机日志里「通知进化」出现 582ms + 369ms 两条、「移除开屏广告」3 条、
        // 「主页侧滑面板」3 条 —— 同名功能重复走了一遍完整安装（重复装钩子 = 白花的主线程时间）。
        // 这里只挡「同一进程内重复」，跨进程各自安装仍然照旧。
        if (!startedFeatureIds.add(feature.technicalId)) {
            logOnceStartupDuplicate(feature.technicalId)
            return
        }

        // 逐个功能计时：用户反馈「特别卡」时，日志里只有一条总的
        // "loading all features took N s"，定位不到是哪个功能拖慢启动 —— 慢的单独打出来。
        val startedAt = SystemClock.uptimeMillis()
        feature.startup()
        val cost = SystemClock.uptimeMillis() - startedAt
        if (cost >= SLOW_STARTUP_MS) {
            WeLogger.w(TAG, "slow startup: ${feature.technicalId} took ${cost}ms")
        }
    }

    private fun scheduleDeferredStartup(
        features: List<BaseFeature>,
        allBrokenItems: List<IResolveDex>,
    ) {
        features.forEach { deferredPending.add(it.technicalId) }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // 不在主线程（理论上不会发生）就直接跑完，保持行为可预期。
            val queue = features.toMutableList()
            drainDeferredStartup(queue, allBrokenItems, SystemClock.uptimeMillis())
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val pending = features.toMutableList()
        val totalStartedAt = SystemClock.uptimeMillis()
        deferredDrainStarted.set(false)
        Looper.myQueue().addIdleHandler {
            startDeferredDrain(handler, pending, allBrokenItems, totalStartedAt)
            false
        }
        // 【2026-09-27 修】超时兜底。原来的延后批次**只**挂在 IdleHandler 上，主线程一旦长期
        // 不空闲就永远不开始 —— 实机证据：18 次 `deferred startup scheduled`，只有 1 次
        // 走到 `deferred feature startup finished`；7 次「20 feature(s)」批次全部没跑完，
        // 于是那 20 个功能的 hook 到底装没装、日志完全答不上来。3 秒后无论如何开始装。
        handler.postDelayed(
            { startDeferredDrain(handler, pending, allBrokenItems, totalStartedAt) },
            DEFERRED_START_TIMEOUT_MS,
        )
        // 兜底观测：30 秒还没装完就点名剩下的功能，避免「静默丢失」。
        handler.postDelayed(
            {
                val left = synchronized(pending) { pending.map { it.technicalId } }
                if (left.isNotEmpty()) {
                    WeLogger.w(
                        TAG,
                        "延后批次 30s 仍未装完，剩余 ${left.size} 个：$left" +
                            "（若持续出现，说明主线程长期不空闲）",
                    )
                }
            },
            DEFERRED_STALL_WARN_MS,
        )
        WeLogger.i(
            TAG,
            "deferred startup scheduled: ${features.size} feature(s) — ${features.joinToString { it.technicalId }}",
        )
    }

    /** IdleHandler 与超时兜底两条路都汇到这里，用 CAS 保证只真正开始一次。 */
    private fun startDeferredDrain(
        handler: Handler,
        pending: MutableList<BaseFeature>,
        allBrokenItems: List<IResolveDex>,
        totalStartedAt: Long,
    ) {
        if (!deferredDrainStarted.compareAndSet(false, true)) return
        handler.post { drainDeferredStartup(pending, allBrokenItems, totalStartedAt) }
    }

    private fun drainDeferredStartup(
        pending: MutableList<BaseFeature>,
        allBrokenItems: List<IResolveDex>,
        totalStartedAt: Long,
    ) {
        val sliceStartedAt = SystemClock.uptimeMillis()
        while (pending.isNotEmpty()) {
            val feature = pending.removeAt(0)
            // 【2026-09-27 修】逐个功能兜异常：以前这里没有 try/catch，任何一个功能抛出来都会
            // 让**剩余所有**功能静默丢失（而且连收尾日志都打不出来）。
            runCatching { runFeatureStartup(feature, allBrokenItems) }
                .onFailure { WeLogger.w(TAG, "延后启用失败（已跳过）：${feature.technicalId}", it) }
            deferredPending.remove(feature.technicalId)
            if (SystemClock.uptimeMillis() - sliceStartedAt >= DEFERRED_SLICE_BUDGET_MS) {
                Handler(Looper.getMainLooper()).postDelayed({
                    drainDeferredStartup(pending, allBrokenItems, totalStartedAt)
                }, DEFERRED_SLICE_GAP_MS)
                return
            }
        }
        WeLogger.i(
            TAG,
            "deferred feature startup finished in ${SystemClock.uptimeMillis() - totalStartedAt}ms",
        )
        // 延后批次跑完后再兜一次「开关开着、钩子没挂上」的功能（见 rearmDeadFeatures 注释）。
        rearmDeadFeatures(1)
    }

    // ---------------------------------------------------------------------------

    /**
     * 逐委托从缓存恢复状态。
     *
     * - 某个委托的 key 缺失 → 其他委托不受影响，仍正常加载。
     * - 有任意 key 缺失的 item 加入返回列表，等待 DexKit 重新扫描。
     * - 缓存文件整体读取失败 → 删除损坏文件，整个 item 加入返回列表。
     */
    private fun loadDescriptorsFromCache(items: List<IResolveDex>): List<IResolveDex> {
        val failedItems = mutableListOf<IResolveDex>()

        for (item in items) {
            val path = (item as BaseFeature).technicalPath
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache == null) {
                    WeLogger.w(TAG, "cache missing for $path")
                    failedItems += item
                    continue
                }

                // loadFromCache 逐委托加载；返回未命中的 key 集合
                val missingKeys = item.loadFromCache(cache)
                if (missingKeys.isNotEmpty()) {
                    val total = item.dexDelegates.size
                    val loaded = total - missingKeys.size
                    WeLogger.w(TAG, "$path: loaded $loaded/$total delegates from cache, missing: $missingKeys")
                    failedItems += item
                    // 已命中的委托此时已经可用；hook 仍然跳过（见 loadFeatures），
                    // 等 DexKit 把缺失的部分补齐、cache 更新后下次启动即完整。
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                runCatching { DexCacheManager.deleteCache((item as BaseFeature).technicalId) }
                failedItems += item
            }
        }

        return failedItems
    }

    private fun handleBrokenItems(brokenItems: List<IResolveDex>) {
        if (Preferences.noDexResolve) return
        if (!TargetProcesses.isInMain) return

        WeLogger.i(TAG, "launching background coroutine to repair ${brokenItems.size} items")

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var activity = LauncherUI.getInstance()
            var waited = 0L
            while (activity == null && waited < 30_000L) {
                delay(1_000.milliseconds)
                waited += 1_000
                activity = LauncherUI.getInstance()
            }

            if (activity == null) {
                WeLogger.w(TAG, "no LauncherUI available for dex-repair dialog; skipping")
                return@launch
            }

            val boundActivity = activity
            withContext(Dispatchers.Main) {
                showComposeDialog(boundActivity, directlyDismissable = false) {
                    DexResolver(
                        boundActivity,
                        brokenItems,
                        MainScope(),
                        onDismiss
                    )
                }
            }
        }
    }
}
