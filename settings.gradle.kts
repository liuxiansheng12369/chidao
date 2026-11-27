@file:Suppress("UnstableApiUsage")

// CI（GitHub Actions）走官方源：阿里云镜像在海外 runner 上经常 502，导致 kotlin-build-tools-api 拉不到
pluginManagement {
    repositories {
        mavenLocal()
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        // SuperLyricApi 只发布在 jitpack，阿里云镜像常年 401，直连官方源兜底
        maven { url = uri("https://jitpack.io") }
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/jitpack") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}

include(
    ":lyricon",
    ":app",
    ":bridge",
    ":xposed",
    ":common",
    ":common-legacy",
)

include(":lyric:view")
include(":lyric:model")
include(":lyric:model-legacy")
include(":lyric:statusbarlyric")

include(":lyric:bridge:provider")
include(":lyric:bridge:central")
include(":lyric:bridge:subscriber")

include(":opencc-lite")

// 打轴器（人工打轴）：library 模块，随词岛同一个 APK 发布
include(":axis")

rootProject.name = "lyricon"
