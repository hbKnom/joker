package dev.ujhhgtg.wekit.features.items.chat.jev.analysis

import dev.ujhhgtg.wekit.features.items.chat.jev.core.AnalysisInput
import dev.ujhhgtg.wekit.features.items.chat.jev.core.Mood
import dev.ujhhgtg.wekit.features.items.chat.jev.core.MoodLog
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Synchronous orchestration called on the IO dispatcher; transport is injected for offline verification. */
object ChatAnalysis {
    /**
     * 两轮问答：第一轮定场景/情绪/阶段（+事实），第二轮在候选卡与候选动作里挑一个。
     *
     * 合并后新增的鲁棒性约定（用户实测「有些能显示、有些一直失败」的主要来源）：
     * **第二轮失败不吞掉第一轮**。第一轮已经拿到情绪概率，就把概率作为降级结果显示出来，
     * 并把失败原因写在结果里；只有第一轮本身失败才算整条失败（可点击重试）。
     */
    fun analyze(input: AnalysisInput, model: String, exchange: (JSONObject) -> String,
        shouldContinue: () -> Boolean = { true }): Mood {
        fun checkActive() { if (!shouldContinue()) throw CancellationException("分析已停止或消息不再可见") }
        checkActive()
        val profile = JevProtocol.parseProfile(exchange(JevProtocol.payload(input.text, model, input.context, input.speaker)))
        checkActive()
        if (ChatTemplates.candidates(profile).isEmpty() && ChatActions.candidates(profile).isEmpty()) {
            return JevProtocol.fallback(profile)
        }
        val detail = runCatching { exchange(JevProtocol.detailPayload(input, model, profile)) }
            .getOrElse { failure ->
                // 第二轮打不动（超时 / 限流 / 额度用尽）：保留第一轮的情绪概率，不整条失败
                MoodLog.w("第二轮分析未完成，降级为情绪概率：${failure.message}")
                return safeFallback(profile, failure.message ?: "第二轮未完成")
            }
        checkActive()
        return runCatching { JevProtocol.parseDetail(detail, profile) }
            .getOrElse { failure ->
                MoodLog.w("第二轮返回不可用，降级为情绪概率：${failure.message}")
                safeFallback(profile, "解读不完整")
            }
    }

    /**
     * 降级结果本身也必须绝对安全。
     *
     * 用户实测：降级路径里的渲染步骤一旦抛错，异常会越过「第二轮失败」的 runCatching 直接
     * 变成整条失败卡（历史上正是 `Key 开心 is missing in the map` 的扩散路径）。降级只是
     * 少显示一点信息，永远不该把可用的第一轮结果丢掉。
     */
    private fun safeFallback(profile: ChatProfile, note: String): Mood =
        runCatching { JevProtocol.fallback(profile, note) }
            .getOrElse { failure ->
                MoodLog.w("降级结果渲染失败，改用最小结果：${failure.message}")
                Mood(
                    label = "情绪概率",
                    score = 0.0,
                    risk = 0,
                    raw = "",
                    detail = "${JevProtocol.header}\n情绪概率暂不可用（$note），点击卡片可重试",
                )
            }
}
