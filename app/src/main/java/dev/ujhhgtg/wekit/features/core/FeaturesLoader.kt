package dev.ujhhgtg.wekit.features.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.features.items.system.SafeMode
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.DexResolver
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
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
        allFeatures.filterIsInstance<SwitchFeature>().forEach(SwitchFeature::loadPersistedState)

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

        if (allBrokenItems.isNotEmpty())
            handleBrokenItems(allBrokenItems)

        // 延后批次：只包含「用户在启动瞬间绝不可能用到」的按需功能（聊天内悬浮控件、相册选择器、
        // 朋友圈/激励广告等）。这些功能的 hook 安装实测每个 200~800ms，全部塞在启动同步阶段会让
        // 微信主线程在启动时连续阻塞十几秒（实机日志：一次冷缓存启动 `loading all features took
        // 16.379601036s`，全部落在 Instrumentation.callApplicationOnCreate 里，是「整个微信和
        // WeKit 都有一点点卡顿」的主要来源）。其余功能保持原有同步安装顺序不变，避免影响启动期
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

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            val context = LocalizedContextFactory.create(
                HostInfo.application,
                WeKitLocaleController.resolvedLocale,
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
        "半屏相册选择器",
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
        "圆角头像",              // 会话列表头像，首屏渲染就会用到 → 延后批次第一个
        "通知进化",              // 通知随时可能到达
        "反已读追踪",            // 聊天内
        "对话框窗口级背景模糊",   // 任意弹窗
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

    private fun runFeatureStartup(feature: BaseFeature, allBrokenItems: List<IResolveDex>) {
        val isBroken = feature is IResolveDex && allBrokenItems.contains(feature)

        if (isBroken) {
            WeLogger.w(TAG, "skipping ${feature.technicalId} — incomplete cache, awaiting re-resolution")
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // 不在主线程（理论上不会发生）就直接跑完，保持行为可预期。
            val queue = features.toMutableList()
            drainDeferredStartup(queue, allBrokenItems, SystemClock.uptimeMillis())
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val pending = features.toMutableList()
        val totalStartedAt = SystemClock.uptimeMillis()
        Looper.myQueue().addIdleHandler {
            handler.post {
                drainDeferredStartup(pending, allBrokenItems, totalStartedAt)
            }
            false
        }
        WeLogger.i(
            TAG,
            "deferred startup scheduled: ${features.size} feature(s) — ${features.joinToString { it.technicalId }}",
        )
    }

    private fun drainDeferredStartup(
        pending: MutableList<BaseFeature>,
        allBrokenItems: List<IResolveDex>,
        totalStartedAt: Long,
    ) {
        val sliceStartedAt = SystemClock.uptimeMillis()
        while (pending.isNotEmpty()) {
            runFeatureStartup(pending.removeAt(0), allBrokenItems)
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
