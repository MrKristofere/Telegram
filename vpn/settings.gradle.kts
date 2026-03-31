import com.android.build.api.dsl.SettingsExtension

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.settings") version "8.6.1" // "8.13.0"
}

configure<SettingsExtension> {
    buildToolsVersion = "35.0.0" // "36.0.0"
    compileSdk = 35 // 36
    minSdk = 21
    ndkVersion = "26.1.10909125"
}

rootProject.name = "vpn"

include(":base")
include(":utils")
include(":network")
include(":tunnel")
include(":sdk")
