package dev.joker.features.items.chat.jev.analysis

data class ChatDecision(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) {
    // Initial conservative UI thresholds; they are not a claim of calibrated relationship accuracy.
    val clear: Boolean get() = confidence >= 0.35 && (probabilities[choice] ?: 0.0) >= 0.55
}

data class ChatProfile(
    val scene: ChatDecision,
    val emotion: ChatDecision,
    val progress: ChatDecision,
    val facts: Map<String, ChatDecision> = emptyMap(),
) {
    val canSpecialize: Boolean get() = scene.clear && scene.choice != "other"
    fun has(key: String, vararg values: String): Boolean = facts[key]?.let {
        it.clear && it.choice in values
    } == true
    // 【第 51 轮 · 上游一比一】newTopic 同时看 `topic_relation`：
    // 「仍在同一件事 / 明确重提旧事」都不算新话题；否则「new_topic=yes」或
    // 「topic_relation=new」任一成立即为新话题。旧实现只认 new_topic 一个问题 → 误判更多。
    val newTopic: Boolean get() = !has("topic_relation", "continuing", "reopened") &&
        (has("new_topic", "yes") || has("topic_relation", "new"))
    val personalConflict: Boolean get() = has("target", "listener") && !newTopic
    val hasAgreement: Boolean get() = has("commitment", "pending", "accepted") && !newTopic
    val acceptsResponse: Boolean get() = !newTopic && progress.clear && progress.choice == "accepted" &&
        has("speech_act", "confirm") && has("commitment", "none", "pending", "accepted")
}
