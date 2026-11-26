@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
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
        maven { url = uri("https://maven.aliyun.com/repository/jitpack") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
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
