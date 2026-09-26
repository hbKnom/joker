pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://chaquo.com/maven")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { excludeGroup("dev.joker") } }
        mavenCentral { content { excludeGroup("dev.joker") } }
        val apiRepository = providers.gradleProperty("jokerPythonApiRepo")
            .orElse(System.getenv("JOKER_PYTHON_API_REPO") ?: "")
        if (apiRepository.isPresent && apiRepository.get().isNotBlank()) {
            maven {
                name = "JokerPythonApi"
                url = uri(apiRepository.get())
                content { includeGroup("dev.joker") }
            }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "joker-python-runtime"
include(":runtime")
