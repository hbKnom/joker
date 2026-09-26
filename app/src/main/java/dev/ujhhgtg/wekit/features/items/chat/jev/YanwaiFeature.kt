package dev.ujhhgtg.wekit.features.items.chat.jev

import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.MoodMessageChannel
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.YanwaiBubble
import dev.ujhhgtg.wekit.features.items.chat.jev.hook.YanwaiScanner
import dev.ujhhgtg.wekit.features.items.chat.jev.core.ModulePrefs
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodStore
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger

/**
 * 潜语 · 聊天分析决策（合并后的唯一入口）。
 *
 * 合并前是两套独立实现、两条请求链路、两套配置与开关：
 *  - 「言外 · 潜台词助手」：在每条文字气泡下方挂一张分析卡；
 *  - 「Jev 聊天实时决策分析」：把结论以系统消息插回原会话。
 * 同一句话会被分析两遍，配置还要分别填。现在两者合成一个功能：
 * 一次分析、一份结果，同时喂给两个展示通道 ——
 * 通道 1 气泡卡（[YanwaiBubble]），通道 2 系统消息（[MoodMessageChannel]）。
 *
 * 分析维度沿用更深的这一套（8 类场景 / 32 套模板 / 7 个事实维度 / 20+ 候选动作）。
 *
 * 注意 [technicalId] 刻意保持合并前的值：它是功能开关在 WePrefs 里的键，
 * 改了会让老用户的开关状态与配置凭空丢失。
 *
 * 上游 wechatmood 是独立 APK，靠 ContentProvider 桥跨进程读设置、靠 DexKit 自己找绑定点；
 * 本实现落在 WeKit 内，直接复用 [dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi]
 * 的绑定表与 [ModulePrefs] 的 WePrefs 持久化。
 */
object YanwaiFeature : ClickableFeature() {

    override val technicalId = "言外潜台词分析"
    override val nameRes = R.string.feature_yanwai_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_yanwai_description

    override fun onEnable() {
        ModulePrefs.init()
        MoodLog.init(HostInfo.application)
        runCatching {
            // 扫描器负责「谁在屏幕上、该不该分析」；消息通道订阅同一份结果。
            YanwaiScanner.install()
            MoodMessageChannel.install()
            WeLogger.i(TAG, "潜语扫描器与会话消息通道已挂载")
        }.onFailure {
            WeLogger.e(TAG, "潜语挂载失败", it)
            MoodLog.e("SCANNER_INSTALL_FAILED", it)
        }
    }

    override fun onDisable() {
        runCatching { MoodMessageChannel.uninstall() }
        runCatching { YanwaiScanner.uninstall() }
        YanwaiBubble.clearAll()
        MoodStore.clear()
        WeLogger.i(TAG, "言外扫描器已卸载")
    }

    override fun onClick(context: ComponentActivity) {
        YanwaiSettings.show(context)
    }

    private const val TAG = "YanwaiFeature"
}
