package dev.joker.loader.startup

import dev.joker.loader.abc.IHookBridge
import dev.joker.loader.abc.ILoaderService

object StartupInfo {

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
