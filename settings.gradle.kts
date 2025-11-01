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
                // Map Gradle plugin ID to Jitpack module hosting the plugin
                useModule("com.github.recloudstream:cloudstream:v4.6.0")
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
