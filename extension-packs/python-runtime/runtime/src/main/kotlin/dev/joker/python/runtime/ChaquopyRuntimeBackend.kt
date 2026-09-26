package dev.joker.python.runtime

import com.chaquo.python.Python
import dev.joker.python.api.PythonPluginHost
import dev.joker.python.api.PythonPluginRequest
import dev.joker.python.api.PythonRuntimeBackend
import dev.joker.python.api.PythonRuntimeConfig

internal class ChaquopyRuntimeBackend : PythonRuntimeBackend {
    private lateinit var config: PythonRuntimeConfig

    @Synchronized
    override fun start(config: PythonRuntimeConfig) {
        if (this::config.isInitialized) return
        this.config = config
        check(!Python.isStarted()) { "Chaquopy Python was started outside Joker's runtime backend" }
        Python.start(JokerAndroidPlatform(config))
        withLookupLoader {
            Python.getInstance().getModule("java.chaquopy")
                .callAttrThrows("set_java_class_loader", config.lookupClassLoader)
            Python.getInstance().getModule("joker._bootstrap")
                .callAttrThrows("initialize", config)
        }
    }

    override fun activatePlugin(request: PythonPluginRequest, host: PythonPluginHost) = withLookupLoader {
        Python.getInstance().getModule("joker._bootstrap")
            .callAttrThrows("activate_plugin", request, host)
        Unit
    }

    override fun deactivatePlugin(pluginId: String) = withLookupLoader {
        Python.getInstance().getModule("joker._bootstrap").callAttrThrows("deactivate_plugin", pluginId)
        Unit
    }

    override fun reloadPlugin(request: PythonPluginRequest, host: PythonPluginHost) = withLookupLoader {
        Python.getInstance().getModule("joker._bootstrap")
            .callAttrThrows("reload_plugin", request, host)
        Unit
    }

    private inline fun <T> withLookupLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = config.lookupClassLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }
}
