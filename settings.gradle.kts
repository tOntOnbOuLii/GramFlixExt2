pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven(url = uri("https://jitpack.io"))
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.lagradost.cloudstream3.gradle") {
                useModule("com.github.recloudstream:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven(url = uri("https://jitpack.io"))
    }
}

rootProject.name = "gramflix-cloudstream-extensions"
include(":app")
