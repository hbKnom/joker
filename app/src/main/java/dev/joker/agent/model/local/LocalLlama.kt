package dev.joker.agent.model.local

object LocalLlama {
    const val PROVIDER_ID = "local-llama"
    val BACKENDS = listOf("auto", "cpu", "vulkan", "opencl")
}
