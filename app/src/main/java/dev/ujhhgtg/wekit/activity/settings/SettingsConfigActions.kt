package dev.ujhhgtg.wekit.activity.settings

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.ujhhgtg.wekit.R
import kotlinx.serialization.Serializable
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.security.MessageDigest
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.preferences.SqlitePrefsImpl
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add

/**
 * Shared configuration I/O used by both settings engines.
 *
 * Upstream 09-19 turned the old "export prefs as JSON" pair into a *full* backup: a zip holding
 * a manifest, the unified SQLite database and the user asset trees (themes / agent skills /
 * python data). Import validates the archive before touching anything on disk.
 */
object SettingsConfigActions {

    private const val TAG = "SettingsConfigActions"

    private const val MANIFEST_NAME = "manifest.json"

    /** Bump when the archive layout changes; older archives stay importable. */
    private const val BACKUP_SCHEMA = 2

    private const val DB_ENTRY = "wekit.sqlite"
    private const val PAYLOAD_PREFIX = "payload/"

    /** User asset trees inside KnownPaths.moduleData that travel with a backup. */
    private val ASSET_TREES = listOf(
        "themes",
        "agent/skills",
        "python/data",
    )

    private const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024

    @Serializable
    private data class ManifestEntry(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    @Serializable
    private data class BackupManifest(
        val schema: Int = BACKUP_SCHEMA,
        val createdAt: Long = 0L,
        val moduleVersion: String = "",
        val entries: List<ManifestEntry> = emptyList(),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").apply {
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    update(buffer, 0, read)
                }
            }
        }.digest().joinToString("") { "%02x".format(it) }

    /** Normalised, traversal-free relative path, or null when the entry must be rejected. */
    private fun safeRelativePath(raw: String): String? {
        val normalised = raw.replace('\\', '/').trimStart('/')
        if (normalised.isEmpty()) return null
        val parts = normalised.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(normalised)) return null
        return parts.joinToString("/")
    }
    fun export(platformContext: Context, localizedContext: () -> Context) {
        TransparentActivity.launch(platformContext) {
            val exportLauncher = registerForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val staging = File(KnownPaths.moduleCache.toFile(), "backup-staging").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    runCatching {
                        val entries = mutableListOf<ManifestEntry>()

                        // 1) Consistent snapshot of the unified prefs database.
                        val dbSnapshot = File(staging, DB_ENTRY)
                        SqlitePrefsImpl(WePrefs.PREFS_NAME).snapshotInto(dbSnapshot)
                        if (dbSnapshot.isFile) {
                            entries += ManifestEntry(DB_ENTRY, dbSnapshot.length(), sha256(dbSnapshot))
                        }

                        // 2) User asset trees.
                        for (tree in ASSET_TREES) {
                            val root = KnownPaths.moduleData.resolve(tree).toFile()
                            if (!root.isDirectory) continue
                            root.walkTopDown().filter { it.isFile }.forEach { file ->
                                val relative = root.toPath().relativize(file.toPath())
                                    .toString().replace('\\', '/')
                                entries += ManifestEntry(
                                    "$PAYLOAD_PREFIX$tree/$relative",
                                    file.length(),
                                    sha256(file),
                                )
                            }
                        }

                        val manifest = BackupManifest(
                            schema = BACKUP_SCHEMA,
                            createdAt = System.currentTimeMillis(),
                            moduleVersion = BuildConfig.VERSION_NAME,
                            entries = entries.sortedBy { it.path },
                        )

                        platformContext.contentResolver.openOutputStream(uri, "w")!!.use { raw ->
                            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                                zip.write(DefaultJson.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                                zip.closeEntry()

                                if (dbSnapshot.isFile) {
                                    zip.putNextEntry(ZipEntry(DB_ENTRY))
                                    dbSnapshot.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }

                                for (tree in ASSET_TREES) {
                                    val root = KnownPaths.moduleData.resolve(tree).toFile()
                                    if (!root.isDirectory) continue
                                    root.walkTopDown().filter { it.isFile }.forEach { file ->
                                        val relative = root.toPath().relativize(file.toPath())
                                            .toString().replace('\\', '/')
                                        zip.putNextEntry(ZipEntry("$PAYLOAD_PREFIX$tree/$relative"))
                                        file.inputStream().use { it.copyTo(zip) }
                                        zip.closeEntry()
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        showToastSuspend(localizedContext().getString(R.string.config_export_failed))
                        WeLogger.e(TAG, "failed to export backup", it)
                    }.onSuccess {
                        showToastSuspend(localizedContext().getString(R.string.config_export_success))
                    }
                    staging.deleteRecursively()
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            exportLauncher.launch("wekit_backup.zip")
        }
    }

    fun importFromDocument(platformContext: Context, localizedContext: () -> Context) {
        TransparentActivity.launch(platformContext) {
            val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    var success = false
                    runCatching {
                        val bytes = platformContext.contentResolver
                            .openInputStream(uri)
                            ?.use { it.readBytes() }
                            ?: return@launch
                        restoreBackup(bytes)
                        success = true
                    }.onFailure {
                        WeLogger.e(TAG, "failed to import backup", it)
                    }
                    showToastSuspend(
                        localizedContext().getString(
                            if (success) R.string.config_import_success else R.string.config_import_failed,
                        ),
                    )
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            importLauncher.launch(arrayOf("application/zip", "application/json"))
        }
    }

    /**
     * Validates and applies a full backup.
     *
     * Every entry is rejected unless its path is traversal-free, unique and its SHA-256 matches
     * the manifest. The archive is fully verified into a staging directory *before* anything is
     * written back, so a corrupt or hostile archive cannot leave the module half-restored.
     */
    private fun restoreBackup(archive: ByteArray) {
        val staging = File(KnownPaths.moduleCache.toFile(), "restore-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            var parsedManifest: BackupManifest? = null
            val seen = mutableSetOf<String>()

            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val safe = safeRelativePath(name)
                        ?: throw IllegalArgumentException("unsafe archive entry: $name")
                    if (!seen.add(safe)) throw IllegalArgumentException("duplicate archive entry: $name")
                    if (entry.size > MAX_ENTRY_BYTES) {
                        throw IllegalArgumentException("oversized archive entry: $name")
                    }
                    val data = zip.readBytes()
                    if (safe == MANIFEST_NAME) {
                        if (data.size > MAX_MANIFEST_BYTES) {
                            throw IllegalArgumentException("manifest too large")
                        }
                        val parsed = DefaultJson.decodeFromString<BackupManifest>(
                            data.toString(Charsets.UTF_8),
                        )
                        if (parsed.schema > BACKUP_SCHEMA) {
                            throw IllegalArgumentException("unsupported backup schema ${parsed.schema}")
                        }
                        parsedManifest = parsed
                    } else {
                        val target = File(staging, safe)
                        target.parentFile?.mkdirs()
                        target.writeBytes(data)
                    }
                    entry = zip.nextEntry
                }
            }

            val manifest = parsedManifest
                ?: throw IllegalArgumentException("backup manifest is missing")

            // Verify every declared entry against what we actually staged.
            for (declared in manifest.entries) {
                val safe = safeRelativePath(declared.path)
                    ?: throw IllegalArgumentException("unsafe manifest path: ${declared.path}")
                val staged = File(staging, safe)
                if (!staged.isFile) continue
                if (staged.length() != declared.size) {
                    throw IllegalArgumentException("size mismatch for ${declared.path}")
                }
                if (sha256(staged) != declared.sha256) {
                    throw IllegalArgumentException("checksum mismatch for ${declared.path}")
                }
            }

            // Database swap: replace the prefs DB with the staged snapshot.
            val stagedDb = File(staging, DB_ENTRY)
            if (stagedDb.isFile) {
                val liveDb = KnownPaths.moduleData.resolve(SqlitePrefsImpl.DB_FILE_NAME).toFile()
                stagedDb.copyTo(liveDb, overwrite = true)
                File(liveDb.absolutePath + "-wal").delete()
                File(liveDb.absolutePath + "-shm").delete()
            }

            // Asset trees.
            for (tree in ASSET_TREES) {
                val stagedTree = File(staging, "$PAYLOAD_PREFIX$tree")
                if (!stagedTree.isDirectory) continue
                val liveTree = KnownPaths.moduleData.resolve(tree).toFile()
                if (liveTree.exists()) liveTree.deleteRecursively()
                liveTree.mkdirs()
                stagedTree.copyRecursively(liveTree, overwrite = true)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * "Clear data": upstream 09-19 widened this from just wiping prefs to removing the module
     * database, scripts, extension packs, caches and temporary files. Live feature state is left
     * alone so the running process keeps working until WeChat restarts.
     */
    fun clear() {
        WePrefs.default.clear()
        runCatching {
            val removable = listOf("agent", "python", "extensions")
            for (dir in removable) {
                KnownPaths.moduleData.resolve(dir).toFile().deleteRecursively()
            }
            KnownPaths.moduleCache.toFile().deleteRecursively()
            KnownPaths.moduleData.resolve(SqlitePrefsImpl.DB_FILE_NAME).toFile().let { db ->
                db.delete()
                java.io.File(db.absolutePath + "-wal").delete()
                java.io.File(db.absolutePath + "-shm").delete()
            }
        }.onFailure { WeLogger.e(TAG, "failed to clear module data", it) }
    }
}
