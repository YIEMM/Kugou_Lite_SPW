pluginManagement {
    repositories {
        mavenLocal()
        maven("https://dl.google.com/dl/android/maven2")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://jitpack.io")
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.kapt") version "2.4.20"
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule("com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven("https://dl.google.com/dl/android/maven2")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "kugou-lite-spw"
