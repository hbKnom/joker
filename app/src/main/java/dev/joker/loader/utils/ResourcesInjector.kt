package dev.joker.loader.utils

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import androidx.annotation.RequiresApi
import dev.joker.R
import dev.joker.extensions.PackFs
import dev.joker.loader.startup.StartupInfo
import dev.joker.utils.WeLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class InjectionHandle constructor(
    val canonicalApk: File,
    val sha256: String,
    val cookie: Int,
    val keepAlive: Any?,
)

object ResourcesInjector {

    private const val TAG = "ResourcesInjector"
    private const val UNREGISTERED_RESOURCES_ERROR =
        "Cannot modify resource loaders of ResourcesImpl not registered with ResourcesManager"
    private val handles = ConcurrentHashMap<String, InjectionHandle>()

    /**
     * 载荷 SHA-256 的进程内缓存（第 24 轮性能修复）。
     *
     * 原实现每次都先 `PackFs.sha256(apk)` 再拿哈希去查 [handles] —— 也就是说**缓存命中时
     * 依然要先把 25 MB 载荷完整读一遍算哈希**。而 [injectModuleRes] 是**按 Resources 实例**
     * 调用的（每个 Activity 一份 Resources），于是每开一个 Activity 都要多算一次 25 MB 的
     * SHA-256（实机 prelude 里量到 `ResourcesInjector` 单次 0.31~4.58 s）。
     *
     * 载荷文件在本进程生命周期内不会被替换（替换走的是下次开机重新发布），所以按
     * 「绝对路径 + 长度 + mtime」做键、进程内只算一次即可；真被替换时键自然变化。
     * 键里带长度与 mtime 而不是只带路径，是为了不把"文件已经被换掉"的情况缓存成旧哈希。
     */
    private val digestCache = ConcurrentHashMap<String, String>()

    private fun cachedSha256(file: File): String {
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        digestCache[key]?.let { return it }
        val digest = PackFs.sha256(file)
        // 正常只会有 1~2 个条目（模块载荷 + 可选的莫奈运行时包）；异常增长时清一次，避免无界累积。
        if (digestCache.size > 32) digestCache.clear()
        digestCache[key] = digest
        return digest
    }

    fun injectModuleRes(resources: Resources?) {
        resources ?: return
        if (hasModuleRes(resources)) return

        val moduleFile = File(StartupInfo.modulePath)
        runCatching { injectApk(resources, moduleFile) }
            .onFailure { logInjectionFailure(moduleFile.absolutePath, it, 0) }

        if (hasModuleRes(resources)) {
            WeLogger.d(TAG, "successfully injected module resources")
        } else {
            WeLogger.e(TAG, "failed to inject module resources")
        }
    }

    private fun hasModuleRes(resources: Resources): Boolean = try {
        resources.getValue(R.string.res_inject_success, TypedValue(), true)
        true
    } catch (_: Resources.NotFoundException) {
        false
    }

    fun injectApk(resources: Resources, apk: File, expectedSha256: String? = null): InjectionHandle {
        val canonical = apk.canonicalFile
        require(canonical.isFile && canonical.canRead()) { "APK is not readable: $canonical" }
        val sha256 = expectedSha256 ?: cachedSha256(canonical)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return injectResLt30(resources, canonical, sha256)
        }
        val key = "${canonical.absolutePath}:$sha256"
        val handle = handles[key] ?: synchronized(handles) {
            handles[key] ?: createHandle(canonical, sha256).also { handles[key] = it }
        }
        if (handle.keepAlive is ResourcesLoader) {
            try {
                resources.addLoaders(handle.keepAlive)
            } catch (error: IllegalArgumentException) {
                if (error.message != UNREGISTERED_RESOURCES_ERROR) throw error
                return injectResLt30(resources, canonical, sha256)
            }
        }
        return handle
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createHandle(apk: File, sha256: String): InjectionHandle {
        ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val provider = ResourcesProvider.loadFromApk(descriptor)
            val loader = ResourcesLoader().apply { addProvider(provider) }
            return InjectionHandle(apk, sha256, 0, loader)
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    @Suppress("JavaReflectionMemberAccess")
    private fun injectResLt30(resources: Resources, apk: File, sha256: String): InjectionHandle {
        val addAssetPath = AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
        val cookie = addAssetPath.invoke(resources.assets, apk.absolutePath) as Int
        require(cookie != 0) { "AssetManager rejected ${apk.absolutePath}" }
        return InjectionHandle(apk, sha256, cookie, resources.assets)
    }

    private fun logInjectionFailure(path: String, error: Throwable, cookie: Int) {
        val moduleFile = File(path)
        WeLogger.e(
            TAG,
            "module resource injection failed: path=$path, cookie=$cookie, " +
                "loader=${ResourcesInjector::class.java.classLoader}, " +
                "exists=${moduleFile.exists()}, directory=${moduleFile.isDirectory}, " +
                "readable=${moduleFile.canRead()}, length=${moduleFile.length()}",
            error
        )
    }
}
