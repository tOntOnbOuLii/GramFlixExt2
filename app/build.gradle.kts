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
    // Ensure toolchain selection can auto-provision JDK 17 when missing
    @Suppress("UnstableApiUsage")
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
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

// Explicitly set Kotlin toolchain
kotlin {
    jvmToolchain(17)
}

// Optional Cloudstream configuration block (minimal)
// cloudstream {
//     // language.set("fr")
//     // description.set("GramFlix extensions pack")
// }

// Apply Cloudstream gradle plugin from buildscript classpath
apply(plugin = "com.lagradost.cloudstream3.gradle")
