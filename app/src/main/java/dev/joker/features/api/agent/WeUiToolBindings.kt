package dev.joker.features.api.agent

import dev.joker.agent.jvm.JvmValueBridge
import dev.joker.features.core.AgentTool
import dev.joker.features.core.AgentTool.Companion.BUILTIN_UI
import dev.joker.features.core.AgentToolParam
import dev.joker.utils.android.getTopMostActivity

/**
 * The `builtin-ui` tool provider: helpers over WeChat's live UI. Objects are returned as handles
 * (see [JvmValueBridge]) so the model can drill into them with the `builtin-jvm` reflection tools
 * (e.g. read the current activity's intent, walk its view tree, …).
 */
object WeUiToolBindings {

    @AgentTool(
        name = "ui-get-topmost-activity",
        description = "Get the current top-most (foreground) Activity of WeChat as an object handle. " +
                "Use the builtin-jvm tools (jvm-invoke-method / jvm-get-field on the returned ref) to " +
                "inspect or manipulate it. `allowPaused` (default false) also considers paused activities.",
        sideEffect = false,
        group = BUILTIN_UI,
    )
    fun uiGetTopmostActivity(
        @AgentToolParam("Also consider paused activities (defaults false)") allowPaused: Boolean?,
    ): String {
        val activity = getTopMostActivity(allowPaused ?: false)
            ?: return "No top-most activity found."
        return JvmValueBridge.render(activity)
    }
}
