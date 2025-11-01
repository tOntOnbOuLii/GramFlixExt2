@file:Suppress("UnstableApiUsage")

buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = uri("https://jitpack.io"))
    }
    dependencies {
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
    }
}

plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.gramflix.extensions"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
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

// toolchain not required for AGP 7.4.2; use system JDK 11

// Optional Cloudstream configuration block (minimal)
// cloudstream {
//     // language.set("fr")
//     // description.set("GramFlix extensions pack")
// }

// Apply Cloudstream gradle plugin from buildscript classpath
apply(plugin = "com.lagradost.cloudstream3.gradle")
