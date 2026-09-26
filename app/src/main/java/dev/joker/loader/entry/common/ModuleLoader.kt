package dev.joker.loader.entry.common

import dev.joker.loader.abc.IHookBridge
import dev.joker.loader.abc.ILoaderService
import dev.joker.loader.startup.UnifiedEntryPoint
import dev.joker.utils.WeLogger

object ModuleLoader {

    private const val TAG = "ModuleLoader"
    private val initLock = Any()

    @Volatile
    private var isInitialized = false

    @Suppress("unused")
    @JvmStatic
    fun init(
        hostDataDir: String,
        initialClassLoader: ClassLoader,
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        allowDynamicLoad: Boolean
    ): Boolean = synchronized(initLock) {
        if (isInitialized) return@synchronized true

        try {
            WeLogger.i(TAG, "loading in entry point ${loaderService.entryPointName}")
            UnifiedEntryPoint.entry(loaderService, hookBridge, initialClassLoader, modulePath)
            isInitialized = true
            true
        } catch (t: Throwable) {
            // Do not poison this process's loader state: a later lifecycle
            // callback may have a usable host class loader.
            WeLogger.e(TAG, "UnifiedEntryPoint failed", t)
            false
        }
    }
}
