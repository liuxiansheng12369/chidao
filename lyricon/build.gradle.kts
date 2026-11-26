import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 签名参数：优先环境变量，其次 local.properties（本机私有文件，不入库），都没有就直接报错，
// 避免把口令硬编码进源码——仓库公开后源码里的口令等于永久留档。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingParam(key: String, fallback: String? = null): String =
    System.getenv(key) ?: localProps.getProperty(key) ?: fallback
        ?: throw GradleException("缺少签名参数 $key：请写入 local.properties 或设为环境变量")

configure<ApplicationExtension> {
    namespace = rootProject.extra["appPackageName"] as String

    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }

    packaging {
        resources {
            excludes.addAll(
                listOf(
                    "META-INF/**/LICENSE*",
                    "META-INF/**/NOTICE*",
                    "META-INF/*.version",
                    "DebugProbesKt.bin"
                )
            )
        }
        dex {
            // 强制压缩 Dex
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = rootProject.extra["appPackageName"] as String
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        androidResources {
            @Suppress("UnstableApiUsage")
            localeFilters += listOf(
                "en",
                "zh-rCN",
                "zh-rTW",
                "zh-rHK"
            )
        }
    }

    flavorDimensions += "locale"
    productFlavors {
        create("standard") {
            dimension = "locale"
        }
    }

    signingConfigs {
        create("release") {
            // V3 独立签名（与原版 lyricon123 不同，可与原版共存安装）
            storeFile = rootProject.file(signingParam("RELEASE_STORE_FILE", "release-v3.jks"))
            storePassword = signingParam("RELEASE_STORE_PASSWORD")
            keyAlias = signingParam("RELEASE_KEY_ALIAS", "liuxiansheng")
            keyPassword = signingParam("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            // 测试版也签 V3 独立签名：这样 `install -r` 能直接覆盖手机上的包，不必卸载。
            // 包名不加后缀（与正式包同为 appPackageName），否则覆盖安装会变成另一个 App。
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            // R8 混淆开启以控制体积；hooker 接口由 proguard-rules.pro 的 keep 规则保护，
            // 避免 AbstractMethodError（hook 静默失效）导致岛/歌词不渲染。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
        }
    }
}

dependencies {
    implementation(project(":app"))
    implementation(project(":xposed"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
