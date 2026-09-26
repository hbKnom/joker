package dev.ujhhgtg.wekit.features.items.system

import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.formatBytesSize
import dev.ujhhgtg.wekit.utils.formatEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.milliseconds

object AutoCleanCache : ClickableFeature() {

    override val technicalId = "清理缓存垃圾"
    override val nameRes = R.string.feature_auto_clean_cache_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_auto_clean_cache_description

    private const val TAG = "AutoCleanCache"
    private const val CLEAN_INTERVAL = 30 * 60 * 1000L // 每 30 分钟清理一次

    /**
     * 首次清理延后 3 分钟执行。
     *
     * 实机日志（2026-09-26）：`onEnable()` 里直接跑第一次清理，而清理目标包含
     * `Android/data/<宿主>/cache` —— 也就是 **WeKit 自己的缓存目录的父目录**。
     * 莫奈的运行时资源包、bindings 缓存、失败计数状态都落在
     * `KnownPaths.moduleCache`（= `.../cache/<BuildConfig.TAG>`）下，于是每次启动
     * 一开功能就把自己的缓存删个干净：下一次冷启动「缓存未命中 → 包存在=false →
     * 全量重解析」，既制造卡顿，又把用户反复推进最容易出问题的那条解析路径。
     * 现在：① 永远跳过我方目录（见 [protectedNames]）；② 首次清理延后 3 分钟。
     */
    private const val FIRST_CLEAN_DELAY = 3 * 60 * 1000L

    /**
     * 绝不允许被本功能删掉的顶层目录名 —— 这里放的是 **WeKit 自己**的缓存/数据目录
     * （`KnownPaths.moduleCache` / `moduleData` 都以 [BuildConfig.TAG] 命名）。
     * 清理的是宿主微信的临时垃圾，不是我们自己的资产。
     */
    private val protectedNames = setOf(BuildConfig.TAG)

    private var cleanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cleanPaths = run {
        val paths = mutableListOf<Path>()

        val dataDir = HostInfo.application.filesDir.parentFile!!.toPath()
        val storageDataDir = HostInfo.application.externalCacheDir!!.toPath().parent!!

        // NOTE: We deliberately do NOT delete tinker/, tinker_server/,
        // tinker_temp/, dataDir/cache (contains oat_primary ART image used by
        // the running process), appbrand/ or liteapp/ while WeChat is alive:
        // deleting those mid-run corrupts class loading / GC and produced the
        // 9/4 native crash storm (StackVisitor SIGSEGV). Only log-like and
        // external-cache paths are safe to remove periodically.
        paths.add(dataDir / "MicroMsg" / "crash")
        paths.add(storageDataDir / "cache")
        paths.add(storageDataDir / "files" / "xlog")
        paths.add(storageDataDir / "files" / "onelog")
        paths.add(storageDataDir / "files" / "tbslog")
        paths.add(storageDataDir / "files" / "Tencent" / "tbs_common_log")
        paths.add(storageDataDir / "files" / "Tencent" / "tbs_live_log")

        return@run paths
    }

    override fun onEnable() {
        startCleaningJob()
    }

    private fun startCleaningJob() {
        cleanJob?.cancel()
        cleanJob = scope.launch {
            delay(FIRST_CLEAN_DELAY.milliseconds)
            while (isActive) {
                performClean()
                delay(CLEAN_INTERVAL.milliseconds)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun performClean(): Long {
        var totalDeletedBytes = 0L
        cleanPaths.forEach { path ->
            try {
                if (!path.exists()) return@forEach
                // 目标本身若就是我方目录（或位于我方目录之下），整体跳过。
                if (isProtected(path)) {
                    WeLogger.i(TAG, "skip protected module path: $path")
                    return@forEach
                }
                WeLogger.d(TAG, "cleaning $path")
                // 逐个孩子删除：这样同级的我方目录（moduleCache 等）不会被连带删掉。
                path.toFile().listFiles()?.forEach childLoop@{ child ->
                    if (isProtected(child.toPath())) {
                        WeLogger.i(TAG, "keep module entry: ${child.name}")
                        return@childLoop
                    }
                    totalDeletedBytes += calculateSize(child.toPath())
                    runCatching { child.toPath().deleteRecursively() }
                        .onFailure { WeLogger.w(TAG, "delete failed: ${child.name}, ${it.message}") }
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "exception during cleaning: ${path.fileName}, ${e.message}")
            }
        }
        return totalDeletedBytes
    }

    /** 我方模块目录（含其任意上层/下层路径）一律不动。 */
    private fun isProtected(path: Path): Boolean {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null) {
            if (current.fileName?.toString() in protectedNames) return true
            current = current.parent
        }
        return false
    }

    private fun calculateSize(path: Path): Long {
        val file = path.toFile()
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()

        var size = 0L
        file.listFiles()?.forEach {
            size += if (it.isDirectory) calculateSize(it.toPath()) else it.length()
        }
        return size
    }

    override fun onClick(context: ComponentActivity) {
        scope.launch {
            val deletedSize = performClean()
            val sizeText = formatBytesSize(deletedSize)

            val timeText =
                if (isEnabled) context.localizedSystemString(
                    R.string.system_auto_clean_next,
                    formatEpoch(System.currentTimeMillis() + CLEAN_INTERVAL)
                )
                else ""

            showToastSuspend(
                context,
                context.localizedSystemString(R.string.system_auto_clean_complete, sizeText, timeText)
            )

            if (isEnabled) startCleaningJob()
        }
    }
}
