package dev.joker.application

import android.app.Application
import dev.joker.i18n.JokerLocaleController
import dev.joker.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        JokerLocaleController.initializeModuleProcess(this)
    }
}
