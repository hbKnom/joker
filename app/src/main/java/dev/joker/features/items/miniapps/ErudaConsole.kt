package dev.joker.features.items.miniapps

import android.webkit.ValueCallback
import android.webkit.WebView
import dev.joker.reflekt.reflekt
import dev.joker.R
import dev.joker.features.api.ui.WeWebViewApi
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.loader.utils.ResourcesInjector
import dev.joker.utils.HostInfo
import dev.joker.utils.TargetProcess
import dev.joker.utils.WeLogger
import dev.joker.utils.reflection.BString

object ErudaConsole : SwitchFeature() {

    override val technicalId = "Eruda 调试面板"
    override val nameRes = R.string.feature_eruda_console_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_eruda_console_description

    private val erudaScript by lazy {
        val resources = HostInfo.application.resources
        ResourcesInjector.injectModuleRes(resources)
        resources.openRawResource(R.raw.eruda)
            .bufferedReader()
            .use { it.readText() }
    }

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.APPBRAND)

    override fun onEnable() {
        WeWebViewApi.xwebOnPageFinished.hookAfter {
            WeLogger.i(TAG, "injecting into xwebOnPageFinished: ${args[0]}")
            injectEruda(args[0]!!)
        }
        WeWebViewApi.androidOnPageFinished.hookAfter {
            WeLogger.i(TAG, "injecting into androidOnPageFinished: ${args[0]}")
            injectEruda(args[0]!!)
        }
    }

    private fun injectEruda(webView: Any) {
        try {
            when (webView) {
                is WebView -> {
                    webView.evaluateJavascript(erudaScript, null)
                    webView.evaluateJavascript("eruda.init();", null)
                }

                is com.tencent.xweb.WebView -> {
                    webView.evaluateJavascript(erudaScript, null)
                    webView.evaluateJavascript("eruda.init();", null)
                }

                else -> {
                    webView.reflekt().firstMethod {
                        name = "evaluateJavascript"
                        parameters(BString, ValueCallback::class)
                        superclass()
                    }.apply {
                        invoke(erudaScript, null)
                        invoke("eruda.init();", null)
                    }
                }
            }
            WeLogger.i(TAG, "injected eruda")
        } catch (e: Throwable) {
            WeLogger.w(TAG, "failed to inject eruda", e)
        }
    }

    private const val TAG = "ErudaConsole"
}
