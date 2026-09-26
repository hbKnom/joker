package dev.joker.features.core

abstract class ApiFeature : BaseFeature() {

    final override fun startup() {
        enable()
    }
}
