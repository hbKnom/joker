plugins {
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm("desktop")

    sourceSets {
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(projects.sandbox.shared)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "top.yukonga.scripta.sandbox.MainKt"

        nativeDistributions {
            packageName = "scripta"
            packageVersion = "1.0.0"
        }
    }
}
