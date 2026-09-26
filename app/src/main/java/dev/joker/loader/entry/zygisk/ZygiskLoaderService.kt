package dev.joker.loader.entry.zygisk

import android.util.Log
import androidx.annotation.Keep
import dev.joker.R
import dev.joker.i18n.HostLocalizedStrings
import dev.joker.loader.abc.IClassLoaderHelper
import dev.joker.loader.abc.ILoaderService

@Keep
internal class ZygiskLoaderService(
    private val modulePath: String,
    private val versionName: String,
    private val versionCode: Int,
) : ILoaderService {

    override var classLoaderHelper: IClassLoaderHelper? = null

    override val loaderName: String get() = HostLocalizedStrings.get(R.string.loader_zygisk_name)

    override val entryPointName: String = "dev.joker.loader.entry.zygisk.ZygiskEntry"

    override val loaderVersionName: String get() = versionName

    override val loaderVersionCode: Int get() = versionCode

    override val mainModulePath: String get() = modulePath

    override fun log(msg: String) {
        Log.i(TAG, msg)
    }

    override fun log(tr: Throwable) {
        Log.e(TAG, tr.toString(), tr)
    }

    override fun queryExtension(key: String, vararg args: Any?): Any? = null

    companion object {
        private const val TAG = "ZygiskLoaderService"
    }
}
