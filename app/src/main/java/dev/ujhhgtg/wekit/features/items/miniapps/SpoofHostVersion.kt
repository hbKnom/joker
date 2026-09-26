package dev.ujhhgtg.wekit.features.items.miniapps

import org.json.JSONObject
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object SpoofHostVersion : SwitchFeature(), IResolveDex {

    override val technicalId = "伪装宿主版本"
    override val nameRes = R.string.feature_spoof_host_version_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_spoof_host_version_description

    override fun onEnable() {
        ctorCgiLaunchWxaAppFunc1122.hookBefore {
            args[6] = 9999
        }

        // Upstream 09-12: also stop the official "please update WeChat" page from hijacking the
        // mini-program container. WeChat funnels it through its private URL opener; blanking the
        // url makes it a no-op while leaving every other private open untouched.
        //
        // Optional delegate: on a host build whose private opener cannot be located the feature
        // keeps working with only the launch-container hook above instead of failing resolution
        // for the whole feature.
        if (!methodPrivateOpenUrl.isPlaceholder) {
            methodPrivateOpenUrl.hookBefore {
                val json = args.getOrNull(1) as? JSONObject ?: return@hookBefore
                val url = json.optString("url")
                if (UPDATE_URLS.any { url.matchesUpdateUrl(it) }) {
                    json.put("url", "")
                }
            }
        }
    }

    private fun String.matchesUpdateUrl(base: String): Boolean =
        this == base || startsWith("$base/") || startsWith("$base?") || startsWith("$base#")

    private val UPDATE_URLS = listOf(
        "https://support.weixin.qq.com/update",
        "https://szsupport.weixin.qq.com/update",
    )

    /**
     * The private URL opener of the mini-program container.
     *
     * Only the trailing `(JSONObject, int)` pair is pinned: the leading parameter is the JsApi
     * context whose obfuscated class name changes between host builds (it happens to be
     * `com.tencent.mm.plugin.appbrand.jsapi.l` on 8.0.65 ~ 8.0.78, but that is not part of any
     * contract), so requiring it made resolution fail. The descriptor shape
     * `(jsapi context, JSONObject, int) -> void` plus the three strings is stable across every
     * host version we have descriptors for.
     */
    private val methodPrivateOpenUrl by dexMethod(allowFailure = true) {
        matcher {
            paramTypes(null, "org.json.JSONObject", "int")
            returnType = "void"
            usingEqStrings("private_openUrl", "rawUrl", "geta8key_open_webview_appid")
        }
    }

    private val ctorCgiLaunchWxaAppFunc1122 by dexConstructor {
        matcher {
            usingEqStrings(
                "MicroMsg.AppBrand.CgiLaunchWxaApp|func:1122",
                "<init> cgiHash[%d], username[%s] appId[%s] sync[%b] sessionId[%s] instanceId[%s] libVersion[%d], source:%s, launchMode:%d, migrate:%b, fallback:%b"
            )
        }
    }
}
