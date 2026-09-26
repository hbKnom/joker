package dev.joker.loader.utils

import android.content.Intent
import android.os.Bundle
import dev.joker.reflekt.reflekt
import dev.joker.utils.HookCallback
import dev.joker.utils.HookParam
import dev.joker.utils.WeLogger
import dev.joker.utils.hookDirectly
import dev.joker.utils.reflection.BString
import dev.joker.utils.reflection.ClassLoaders

object ParcelableFixer {

    private const val TAG = "ParcelableFixer"

    lateinit var hybridClassLoader: ClassLoader
        private set

    private var initialized = false

    @Suppress("unused")
    fun init() {
        if (initialized) return
        initialized = true

        hybridClassLoader = object : ClassLoader(ClassLoaders.HOST) {
            override fun findClass(name: String): Class<*> = ClassLoaders.MODULE.loadClass(name)
        }

        hookIntentMethods()
    }

    private fun fixIntentExtrasClassLoader(intent: Intent?) {
        if (!isTargetIntent(intent)) return
        val cl = hybridClassLoader
        runCatching { intent?.setExtrasClassLoader(cl) }
    }

    private fun isTargetIntent(intent: Intent?): Boolean {
        intent ?: return false
        val className = intent.component?.className ?: return false
        return ActivityProxy.ActProxyMgr.isModuleProxyActivity(className)
    }

    private fun hookIntentMethods() {
        val hook = object : HookCallback() {
            override fun beforeHookedMethod(param: HookParam) {
                (param.thisObject as? Intent)?.let { fixIntentExtrasClassLoader(it) }
            }

            override fun afterHookedMethod(param: HookParam) {
                val intent = param.thisObject as? Intent ?: return
                if (!isTargetIntent(intent)) return
                val cl = hybridClassLoader
                (param.result as? Bundle)?.classLoader = cl
            }
        }

        runCatching {
            Intent::class.reflekt().apply {
                firstMethod {
                    name = "getExtras"
                    parameters()
                }.hookDirectly(hook)

                firstMethod {
                    name = "getBundleExtra"
                    parameters(BString)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getParcelableExtra"
                    parameters(BString)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getParcelableArrayListExtra"
                    parameters(BString)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getSerializableExtra"
                    parameters(BString)
                }.hookDirectly(hook)

                // Android 13+

                firstMethod {
                    name = "getParcelableExtra"
                    parameters(BString, Class::class)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getParcelableArrayListExtra"
                    parameters(BString, Class::class)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getSerializableExtra"
                    parameters(BString, Class::class)
                }.hookDirectly(hook)
            }
        }.onFailure { WeLogger.w(TAG, "failed to hook some Intent methods") }
    }
}
