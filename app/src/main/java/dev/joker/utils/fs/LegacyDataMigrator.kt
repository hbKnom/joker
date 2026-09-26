package dev.joker.utils.fs

import android.os.Environment
import dev.joker.BuildConfig
import dev.joker.utils.WeLogger
import java.io.File

/**
 * 一次性数据迁移：把旧品牌时期的模块数据目录搬到当前目录。
 *
 * 数据目录位于外部存储 `Android/data/<宿主包名>/<BuildConfig.TAG>`。品牌改名后
 * TAG 由 `WeKit` 变为 `Joker`，若不迁移，用户的全部模块设置（SQLite 里的偏好、
 * 结构化数据、Agent 数据库等）看起来会「消失」。
 *
 * 只在当前目录不存在或为空、且旧目录存在时复制一次；任何失败都只记日志，
 * 不影响模块启动。
 */
internal object LegacyDataMigrator {

    /** 旧品牌目录名；仅用于兼容旧数据，不要用于其它用途。 */
    private const val LEGACY_DIR_NAME = "WeKit"

    private const val TAG = "LegacyDataMigrator"

    fun migrateIfNeeded(hostPackageName: String) {
        val currentName = BuildConfig.TAG
        if (LEGACY_DIR_NAME == currentName) return
        runCatching {
            val base = File(Environment.getExternalStorageDirectory(), "Android/data/$hostPackageName")
            val legacy = File(base, LEGACY_DIR_NAME)
            val current = File(base, currentName)
            if (!legacy.isDirectory) return
            if (legacy.canonicalPath == current.canonicalPath) return
            if (current.isDirectory && current.list()?.isNotEmpty() == true) return
            current.mkdirs()
            var copied = 0
            legacy.walkTopDown().forEach { source ->
                val target = File(current, source.relativeTo(legacy).path)
                if (source.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    if (!target.exists()) {
                        source.copyTo(target, overwrite = false)
                        copied++
                    }
                }
            }
            WeLogger.i(TAG, "migrated $copied legacy data files: ${legacy.path} -> ${current.path}")
        }.onFailure { WeLogger.w(TAG, "legacy module data migration skipped", it) }
    }
}
