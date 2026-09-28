/*
 * WeFavoriteApi.kt — 收藏服务 【第 28 轮 WeKit1945 整合】
 *
 * 证据等级：A+（★ 未混淆 Kotlin 类，原名保留）
 * 来源：WeKit 1945 逆向包 `01_逆向源码/api/WeFavoriteApi.kt`
 * 对应日志：「修复: 获取收藏列表」
 *
 * ★ 5 个 DexKit 委托：classPluginFav + methodFavStorageGetByLocalId
 *   + methodFavStorageGetFirstPage + methodRestartItemDownload + methodSendFavItem
 *
 * ★★「获取收藏列表」修复要点：
 *   listFavorites / methodFavStorageGetFirstPage 的 DexKit 查找规则
 *   （类名 / 方法签名 / 返回值过滤）随微信版本变化失效了。
 *   本版本把委托改为显式 `find(dexKit) { matcher { … } }` 模式，让 DSL
 *   按微信实际签名匹配 com.tencent.mm.plugin.fav.storage.FavStorage.getFirstPage()。
 *
 * ★ 整合铁律（与上游脱钩）：
 *   - 包名 dev.joker.*（非 dev.ujhhgtg.wekit.*）
 *   - 引用本仓库自有反射工具与 DexKit DSL
 *   - 设置入口交由 WeChatForwardingFeature 等调用方决定，不绑死 Activity
 */
package dev.joker.features.api.core

import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexClass
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 收藏服务。
 *
 * 提供 **收藏读取** 与 **收藏发送** 能力，供「转发收藏」「收藏语音转发」等功能使用。
 * 读取链：`FavStorage.getFirstPage()` / `getByLocalId(long)`
 * 发送链：`FavItem.send(talker, localId)`
 */
object WeFavoriteApi : ApiFeature(), IResolveDex {

    // ── 功能元数据 ──────────────────────────────────────────────────
    override val technicalId = "收藏服务"
    override val nameRes = R.string.feature_we_favorite_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_favorite_api_description

    private const val LOG_TAG = "WeFavoriteApi"

    // ═══════════════════════════════════════════════════════════════
    //  ★★ 5 个 DexKit 委托
    // ═══════════════════════════════════════════════════════════════
    /** 微信插件收藏包根 `com.tencent.mm.plugin.fav`（用于发现 FavStorage 单例）*/
    private val classPluginFav by dexClass()

    /** `FavStorage.getByLocalId(long)` */
    private val methodFavStorageGetByLocalId by dexMethod()

    /** ★ `FavStorage.getFirstPage()` —— 获取收藏列表 */
    private val methodFavStorageGetFirstPage by dexMethod()

    /** `FavItem.restartDownload()` —— 重启下载 */
    private val methodRestartItemDownload by dexMethod()

    /** `FavItem.send(talker, localId)` —— 发送到指定会话 */
    private val methodSendFavItem by dexMethod()

    /** 正在下载中的 localId（防并发） */
    private val downloading = ConcurrentHashMap.newKeySet<Long>()

    // ═══════════════════════════════════════════════════════════════
    //  DexKit 解析（★ IResolveDex）
    // ═══════════════════════════════════════════════════════════════
    override fun resolveDex(dexKit: DexKitBridge) {
        // ① classPluginFav —— 找插件收藏根类（带 "FavInit" / "plugin fav" 字符串）
        classPluginFav.find(dexKit, allowFailure = true) {
            matcher {
                usingStrings("FavInit", "plugin fav")
            }
        }

        // ② classFavStorage —— 拿具体类名（允许失败，失败时用反射兜底）
        val classFavStorage = runCatching {
            dexKit.findClass {
                matcher {
                    className = "com.tencent.mm.plugin.fav.storage.FavStorage"
                }
            }.singleOrNull()?.name
        }.getOrNull() ?: "com.tencent.mm.plugin.fav.storage.FavStorage"

        val classFavItem = "com.tencent.mm.plugin.fav.plugin.FavItem"

        // ③ methodFavStorageGetByLocalId —— static (J)Object
        methodFavStorageGetByLocalId.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = classFavStorage
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramTypes("long")
            }
        }

        // ④ methodFavStorageGetFirstPage —— static ()Ljava.util.List;
        //    ★「修复: 获取收藏列表」的关键 —— 不强制返回值类型，过滤器只锁修饰符+类+参数量。
        //    微信 8.0.79 与 8.0.72 的签名略有不同；allowFailure 兜底。
        methodFavStorageGetFirstPage.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = classFavStorage
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramCount = 0
            }
        }

        // ⑥ methodRestartItemDownload
        methodRestartItemDownload.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = classFavItem
                modifiers = Modifier.PUBLIC
                paramCount = 0
            }
        }

        // ⑦ methodSendFavItem —— instance (String, J)Z
        methodSendFavItem.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = classFavItem
                modifiers = Modifier.PUBLIC
                paramCount = 2
                paramTypes("java.lang.String", "long")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  数据类
    // ═══════════════════════════════════════════════════════════════
    /**
     * 收藏项摘要。
     * @param localId      本地 id（微信 fav 表主键）
     * @param type         类型（1=文本 2=图片 3=语音 4=视频 5=链接 …）
     * @param title        标题
     * @param updateTimeMs 更新时间（毫秒）
     */
    data class FavoriteSummary(
        val localId: Long,
        val type: Int,
        val title: String,
        val updateTimeMs: Long,
    )

    /** 收藏语音（含文件路径、时长、字节数）。 */
    data class FavoriteVoice(
        val filePath: String,
        val durationMs: Int,
        val fileSize: Int,
    ) {
        /**
         * 文件是否下载完整。
         * 按文件大小严格比对，无大小信息则退化为「存在即可」。
         */
        fun isDownloaded(): Boolean {
            val f = File(filePath)
            return if (fileSize > 0) f.length() == fileSize.toLong() else f.isFile
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  公开 API
    // ═══════════════════════════════════════════════════════════════

    /** 按 localId 取单个收藏项对象（微信 FavItem 实例）。 */
    fun getFavItem(localId: Long): Any? {
        if (!isActive) return null
        return runCatching {
            methodFavStorageGetByLocalId.method.invoke(null, localId)
        }.onFailure {
            WeLogger.e(LOG_TAG, "getFavItem($localId) failed", it)
        }.getOrNull()
    }

    /**
     * ★★ 获取收藏列表（按更新时间倒序的摘要）。
     *
     * 走微信 `FavStorage.getFirstPage()`；`limit` 用于截断。
     * 调用方（如收藏语音转发）拿到 [FavoriteSummary.localId] 后再调 [getFavItem] / [voiceOf]。
     */
    fun listFavorites(limit: Int = 30): List<FavoriteSummary> {
        if (!isActive) return emptyList()
        val storage = pluginFavStorage() ?: return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val raw = methodFavStorageGetFirstPage.method.invoke(storage) as? List<Any>
                ?: return emptyList()
            raw.take(limit).mapNotNull { toSummary(it) }
        }.onFailure {
            WeLogger.e(LOG_TAG, "listFavorites failed", it)
        }.getOrDefault(emptyList())
    }

    /** 取收藏项里的语音（路径 + 时长 + 大小）。 */
    fun voiceOf(favItem: Any): FavoriteVoice? {
        val path = Reflect.stringField(favItem, "path", "filePath") ?: return null
        val duration = Reflect.intField(favItem, "duration", "durationMs") ?: 0
        val size = Reflect.intField(favItem, "size", "fileSize") ?: 0
        if (path.isEmpty()) return null
        return FavoriteVoice(path, duration, size)
    }

    /** 发送收藏项到指定会话。 */
    fun send(talker: String, localId: Long): Boolean {
        if (!isActive) return false
        val item = getFavItem(localId) ?: return false
        return runCatching {
            methodSendFavItem.method.invoke(item, talker, localId)
            true
        }.onFailure {
            WeLogger.e(LOG_TAG, "send($talker, $localId) failed", it)
        }.getOrDefault(false)
    }

    /** 重新下载收藏项（防并发，相同 localId 第二次直接返回 false）。 */
    fun restartDownload(localId: Long): Boolean {
        if (!isActive) return false
        if (!downloading.add(localId)) return false
        return runCatching {
            val item = getFavItem(localId) ?: return false
            methodRestartItemDownload.method.invoke(item)
            true
        }.onFailure {
            WeLogger.e(LOG_TAG, "restartDownload($localId) failed", it)
        }.getOrDefault(false)
    }

    // ── 内部 ────────────────────────────────────────────────────────
    /** 反射拿 FavStorage 单例。 */
    private fun pluginFavStorage(): Any? = runCatching {
        val cls = classPluginFav.clazz
        val storageField = cls.declaredFields.firstOrNull {
            it.type.simpleName.contains("FavStorage", ignoreCase = true)
        } ?: return null
        storageField.isAccessible = true
        storageField.get(null)
    }.getOrNull()

    /** 把微信 FavItem 转轻量摘要。 */
    private fun toSummary(item: Any): FavoriteSummary? {
        val id = Reflect.longField(item, "localId", "id") ?: return null
        return FavoriteSummary(
            localId = id,
            type = Reflect.intField(item, "type", "itemType") ?: 0,
            title = Reflect.stringField(item, "title", "desc") ?: "",
            updateTimeMs = Reflect.longField(item, "updateTimeMs", "updateTime") ?: 0L,
        )
    }

    /** 反射小工具：取 long/int/String 字段（按候选名依次尝试，命中即返回）。 */
    private object Reflect {
        fun longField(o: Any, vararg names: String): Long? = reflectByField<Long>(o, *names)
        fun intField(o: Any, vararg names: String): Int? = reflectByField<Int>(o, *names)
        fun stringField(o: Any, vararg names: String): String? = reflectByField<String>(o, *names)

        @Suppress("UNCHECKED_CAST")
        private fun <T> reflectByField(o: Any, vararg names: String): T? {
            for (name in names) {
                runCatching {
                    val f = o.javaClass.getDeclaredField(name)
                    f.isAccessible = true
                    val v = f.get(o) ?: return null
                    return v as? T
                }
            }
            return null
        }
    }
}