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

import com.lagradost.cloudstream3.gradle.CloudstreamExtension

plugins {
    id("com.android.library")
    kotlin("android")
}

apply(plugin = "com.lagradost.cloudstream3.gradle")

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
    add("cloudstream", "com.github.recloudstream:cloudstream:master-SNAPSHOT")
    compileOnly("org.jsoup:jsoup:1.16.1")
    compileOnly("com.github.recloudstream.cloudstream:library-jvm:master-SNAPSHOT")
    compileOnly("com.github.recloudstream.cloudstream:library:master-SNAPSHOT")
    compileOnly("com.github.Blatzar:NiceHttp:0.4.13")
}

extensions.configure<CloudstreamExtension>("cloudstream") {
    setRepo("tOntOnbOuLii", "GramFlixExt2", "github")
    description = "GramFlix extensions pack"
    language = "fr"
    tvTypes = listOf("Movie", "TvSeries", "Anime")
}

kotlin {
    jvmToolchain(17)
}

// toolchain not required for AGP 7.4.2; use system JDK 11

// Optional Cloudstream configuration block (minimal)
// cloudstream {
//     // language.set("fr")
//     // description.set("GramFlix extensions pack")
// }
