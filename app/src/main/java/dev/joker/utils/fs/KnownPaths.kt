package dev.joker.utils.fs

import android.os.Environment
import dev.joker.BuildConfig
import dev.joker.constants.PackageNames
import dev.joker.utils.HostInfo
import java.nio.file.Path
import kotlin.io.path.div

object KnownPaths {

    val internalStorage: Path by lazy {
        Environment.getExternalStorageDirectory().asPath
    }

    val moduleData by lazy {
        val hostPackageName = runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT)
        // 品牌改名后 TAG 变了：先把旧目录（WeKit/）数据搬过来，保留用户既有设置。
        LegacyDataMigrator.migrateIfNeeded(hostPackageName)
        (internalStorage / "Android" / "data" / hostPackageName / BuildConfig.TAG).createDirsSafe()
    }

    val codeCacheDir: Path by lazy {
        HostInfo.application.codeCacheDir.asPath
    }

    val moduleCache by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT)
                / "cache" / BuildConfig.TAG).createDirsSafe()
    }

    val moduleAssets by lazy {
        (moduleData / "assets").createDirsSafe()
    }

    val userAssets by lazy {
        (moduleAssets / "user").createDirsSafe()
    }

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() / BuildConfig.TAG)
            .createDirsSafe()
    }
}
