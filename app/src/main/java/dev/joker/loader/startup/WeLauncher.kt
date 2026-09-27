package dev.joker.loader.startup

import android.content.Context
import com.tencent.mm.boot.BuildConfig
import dev.joker.constants.PackageNames
import dev.joker.constants.Preferences
import dev.joker.dexkit.cache.DexCacheManager
import dev.joker.features.core.FeaturesLoader
import dev.joker.i18n.JokerLocaleController
import dev.joker.loader.utils.ActivityProxy
import dev.joker.loader.utils.ParcelableFixer
import dev.joker.loader.utils.ResourcesInjector
import dev.joker.utils.HostInfo
import dev.joker.utils.RuntimeConfig
import dev.joker.utils.TargetProcesses
import dev.joker.utils.WeLogger

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        // 【2026-09-27 加插桩】本函数里的每一步都是**主线程同步**执行、且在微信首帧之前；
        // 实机日志显示 `WeLauncher` 到同步批次开始之间还有 1.2~13.9 秒的「prelude」完全没有
        // 任何计时（`slow startup` / `loading all features took` 都不覆盖它），导致启动卡顿
        // 一直定不到具体步骤。这里逐步计时，只写日志、不改任何行为。
        val initStartedAt = android.os.SystemClock.uptimeMillis()
        var mark = initStartedAt
        fun step(name: String) {
            val now = android.os.SystemClock.uptimeMillis()
            val cost = now - mark
            mark = now
            if (cost >= PRELUDE_SLOW_MS || WeLogger.verboseEnabled) {
                WeLogger.i(TAG, "prelude $name took ${cost}ms（累计 ${now - initStartedAt}ms）")
            }
        }

        ParcelableFixer.init()
        step("ParcelableFixer.init")

        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )
        step("DexCacheManager.init")

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        step("ResourcesInjector.injectModuleRes")

        JokerLocaleController.initializeInjectedHost(HostInfo.application)
        step("JokerLocaleController.initializeInjectedHost")

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)
            step("ActivityProxy.init")

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs
            step("RuntimeConfig.mmPrefs")
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
        step("FeaturesLoader.loadFeatures")
        WeLogger.i(TAG, "prelude total ${android.os.SystemClock.uptimeMillis() - initStartedAt}ms")
    }

    /** prelude 里单步超过这个耗时就在日志里点名（正常都应在几十毫秒量级）。 */
    private const val PRELUDE_SLOW_MS = 80L

    private const val TAG = "WeLauncher"
}
