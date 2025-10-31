@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle") version "0.1.4"
}

android {
    namespace = "com.gramflix.extensions"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets {
        getByName("main").assets.srcDirs("src/main/assets")
    }
}

repositories {
    google()
    mavenCentral()
    maven(url = uri("https://jitpack.io"))
}

dependencies {
    // The Cloudstream gradle plugin pulls required deps during packaging.
}

// Optional Cloudstream configuration block (minimal; the plugin works without too)
// cloudstream {
//     // language.set("fr")
//     // description.set("GramFlix extensions pack")
// }
