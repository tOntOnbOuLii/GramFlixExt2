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
                // Map Gradle plugin ID to recloudstream gradle plugin on Jitpack
                val ver = requested.version ?: "master-SNAPSHOT"
                useModule("com.github.recloudstream:gradle:$ver")
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
