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

        ParcelableFixer.init()

        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        JokerLocaleController.initializeInjectedHost(HostInfo.application)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }

    private const val TAG = "WeLauncher"
}
