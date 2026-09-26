package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Method

/**
 * 相册发送图片时自动勾选「原图」。
 *
 * 宿主实现（8.0.72 dex 实测，AlbumPreviewUI.initView）：
 * ```
 * this.<原始图开关字段> = intent.getBooleanExtra("key_send_raw_image", false)
 *                         || intent.getBooleanExtra("send_raw_img", false);
 * ...
 * applyRawImageState(该字段);   // 设置勾选图标 + 尺寸文案
 * ```
 * 也就是说**宿主自己就是用这两个 intent extra 决定初始勾选状态**的，我们只要在 onCreate 之前
 * 把两个 flag 都写进 activity.intent，宿主就会把「原图」勾上并画出选中态。
 *
 * ⚠️ 2026-09-22 定位到的历史遗留 bug（用户反馈"这功能从来没生效过"）：
 * 之前这里挂了一个 `methodUpdateSendAsMediaGroupViews` 的 dexMethod 钩子，但本 object
 * **没有实现 `IResolveDex`** —— `FeaturesLoader` 只对 `IResolveDex` 的 feature 做 DexKit 解析
 * （`featuresToStart.filterIsInstance<IResolveDex>()`），于是这个 delegate 永远不会被解析，
 * `descriptor` 保持 null；`isPlaceholder` 又是 `descriptor?.descriptor == PLACEHOLDER`，
 * 对 null 返回 false，保护判断形同虚设 → `hookBefore` 读 `method` 时抛
 * `IllegalStateException: Method not found for key: methodUpdateSendAsMediaGroupViews`，
 * 异常被 `enable()` 的 runCatching 捕获后执行 `unhookAll()`，**把前面刚装的
 * onCreate 钩子一起摘掉** —— 整条功能静默消失（真机日志：`failed to enable feature chat/自动启用发送原图`）。
 *
 * 另外那个钩子本身也是错的：`updateSendAsMediaGroupViews` 是「合并发送媒体」的回调
 * （写入的是它的 boolean 字段），跟「原图」无关，args[3] 强制 true 只会去动合并发送。
 * 因此这里彻底去掉 DexKit 依赖，只保留宿主自己的 intent-extra 通路，功能变成纯 View 层 hook、
 * 不可能再因为解析失败而整条挂掉。
 */
object AutoEnableSendOriginalMedia : SwitchFeature() {

    override val technicalId = "自动启用发送原图"
    override val nameRes = R.string.feature_auto_enable_send_original_media_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_enable_send_original_media_description

    /**
     * 相册预览页；发送原图的勾选状态在它的 iniView / 数量回调里被反复重建。
     * GalleryEntryUI 是「气泡 / 相机」路径的中转页，它把整个 intent 透传给
     * ImagePreviewUI，所以这两个 flag 必须由它一起带上，否则那条路径拿不到。
     */
    private val PICKER_PAGES = listOf(
        ALBUM_PREVIEW_UI,
        "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI",
        "com.tencent.mm.plugin.gallery.ui.GalleryEntryUI",
    )

    private const val ALBUM_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
    private const val TAG = "AutoSendOriginal"

    /**
     * 灰测的原生「半屏相册」（`clicfg_local_media_picker_chatting_opt`）走的是另一套 picker：
     * 勾选状态由 `ChattingLocalMediaPickerFeatureArguments#getInitialSendOriginal()` 决定，
     * 相册页也从 `MediaTabAlbumUI` 进入。旧的 `AlbumPreviewUI` / `ImagePreviewUI` 钩子覆盖不到它，
     * 所以那两个功能在这套 picker 上会「失效」。
     */
    private const val PICKER_ARGUMENTS =
        "com.tencent.mm.plugin.picker.scene.chatting.ChattingLocalMediaPickerFeatureArguments"
    private const val MEDIA_TAB_ALBUM_UI = "com.tencent.mm.plugin.gallery.ui.MediaTabAlbumUI"
    private const val GET_INITIAL_SEND_ORIGINAL = "getInitialSendOriginal"

    /** 「原图」开关在三语文案里的写法（宿主随系统语言切换）。 */
    private val ORIGINAL_LABELS = listOf("原图", "原圖", "Original")

    override fun onEnable() {
        PICKER_PAGES.forEach { name ->
            // toClassOrNull：宿主改名/缺失时不能抛 ClassNotFoundException 把整个 onEnable 打断
            // （一旦抛出，后面的 Hook 全部不会安装，表现就是"功能整条消失"）。
            name.toClassOrNull()?.hookBeforeOnCreate {
                val activity = thisObject as? Activity ?: return@hookBeforeOnCreate
                // 宿主 initView 里两个 key 是「或」的关系（见类注释里的反编译片段），
                // 但不同版本/不同入口只用其中一条，所以两条都写，保证任一实现都能命中。
                activity.intent.putExtra("send_raw_img", true)
                activity.intent.putExtra("key_send_raw_image", true)
                WeLogger.i(TAG, "${activity.javaClass.simpleName} raw flags set")

                // 只做诊断（绝不点选）：页面起来后看一眼「原图」行到底有没有渲染出来、
                // 勾选控件是什么类型。宿主自己会用上面的 extra 把状态设对，这里再点一次反而
                // 可能把已经勾上的状态切掉，所以只记录日志，供后续排查使用。
                diagnose(activity)
            }
        }

        installHalfScreenPickerHooks()
    }

    /**
     * 灰测半屏相册（`clicfg_local_media_picker_chatting_opt`）适配。
     *
     * 分两处，都是「尽力而为」——宿主没开这个灰测、或版本里没有这些类时静默跳过：
     *  1. `ChattingLocalMediaPickerFeatureArguments#getInitialSendOriginal()`：宿主用它决定
     *     相册页初始是否勾上「原图」。直接让它在 before 阶段返回 true。
     *  2. `MediaTabAlbumUI#initView()`：半屏相册的入口页，同样在 onCreate 之前把两个 extra
     *     写进 intent，兼容宿主从 intent 读取的实现。
     */
    private fun installHalfScreenPickerHooks() {
        runCatching {
            PICKER_ARGUMENTS.toClassOrNull()
                ?.reflekt()
                ?.firstMethodOrNull {
                    name = GET_INITIAL_SEND_ORIGINAL
                    parameters()
                }
                ?.hookBefore { result = true }
        }.onFailure { WeLogger.w(TAG, "half-screen picker arguments hook failed", it) }

        runCatching {
            val albumClass = MEDIA_TAB_ALBUM_UI.toClassOrNull()
            if (albumClass == null) {
                WeLogger.w(TAG, "MediaTabAlbumUI 不存在，跳过该 hook")
            } else {
                // MediaTabAlbumUI **自身不一定声明 onCreate**（多数版本继承自相册基类），
                // 直接 hookBeforeOnCreate 会抛 NoSuchElementException —— 实机日志里每次都报
                // `MediaTabAlbumUI hook failed / No method matching conditions in
                // com.tencent.mm.plugin.gallery.ui.MediaTabAlbumUI`，于是半屏相册这条覆盖
                // 一直是「静默失效」。改为沿父类链找到**最近一个真正声明 onCreate 的类**再挂钩，
                // 并在回调里用 javaClass 精确过滤，只有 MediaTabAlbumUI 本身才写入 raw extras。
                var declaring: Class<*>? = albumClass
                var method: Method? = null
                while (declaring != null && method == null) {
                    method = declaring.declaredMethods
                        .firstOrNull { it.name == "onCreate" && it.parameterCount == 1 }
                    declaring = declaring.superclass
                }
                if (method == null) {
                    WeLogger.w(TAG, "MediaTabAlbumUI 及其父类都没有声明 onCreate，跳过该 hook")
                } else {
                    method.hookBefore {
                        val activity = thisObject as? Activity ?: return@hookBefore
                        if (activity.javaClass != albumClass) return@hookBefore
                        activity.intent.putExtra("send_raw_img", true)
                        activity.intent.putExtra("key_send_raw_image", true)
                        WeLogger.i(TAG, "MediaTabAlbumUI raw flags set")
                    }
                }
            }
        }.onFailure { WeLogger.w(TAG, "MediaTabAlbumUI hook failed", it) }
    }

    private fun diagnose(activity: Activity) {
        // 用主线程 Handler 而不是 decorView.postDelayed：hook 发生在 onCreate 之前，
        // 此时 decorView 可能还没 attach，挂在它上面的 runnable 会一直不执行；
        // 每次执行时再取当前 decorView，避免拿到被宿主重建掉的旧树。
        val handler = Handler(Looper.getMainLooper())
        listOf(600L, 2000L).forEach { delay ->
            runCatching {
                handler.postDelayed({
                    runCatching {
                        if (activity.isFinishing || activity.isDestroyed) return@runCatching
                        val root = activity.window?.decorView ?: return@runCatching
                        reportOriginalRow(root)
                    }
                }, delay)
            }
        }
    }

    private fun reportOriginalRow(root: View) {
        runCatching {
            val queue = ArrayDeque<View>()
            queue.add(root)
            var label: View? = null
            var checkedBox: Boolean? = null
            while (queue.isNotEmpty()) {
                val view = queue.removeFirst()
                if (view is TextView && label == null) {
                    val text = view.text?.toString().orEmpty()
                    if (ORIGINAL_LABELS.any { text.contains(it) }) label = view
                }
                if (view is CompoundButton && checkedBox == null && view === label) {
                    checkedBox = view.isChecked
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
                }
            }
            val parentChain = label?.let { node ->
                generateSequence(node.parent) { (it as? View)?.parent }
                    .take(3)
                    .joinToString(">") { it.javaClass.simpleName }
            }
            WeLogger.i(
                TAG,
                "original row: label=${label?.let { (it as TextView).text }} " +
                    "compoundChecked=${checkedBox ?: "n/a"} parents=$parentChain"
            )
        }.onFailure { WeLogger.w(TAG, "original row diagnose failed", it) }
    }
}
