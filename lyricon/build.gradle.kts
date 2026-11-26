import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 签名参数：优先环境变量，其次 local.properties（本机私有文件，不入库）。
// 口令一律不写进源码，仓库公开后源码里的口令等于永久留档。
// 四个参数齐备且密钥文件在位时才启用 V3 独立签名；否则回退 AGP 默认 debug 签名，
// 这样公开仓库任何人 clone 下来都能自己编出可安装的包，但签名不是作者的，
// 无法冒充官方包、也不能覆盖安装官方版。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingParam(key: String, fallback: String? = null): String? =
    System.getenv(key) ?: localProps.getProperty(key) ?: fallback

val storeFilePath: String = signingParam("RELEASE_STORE_FILE", "release-v3.jks") ?: "release-v3.jks"
val storePass: String? = signingParam("RELEASE_STORE_PASSWORD")
val aliasName: String = signingParam("RELEASE_KEY_ALIAS", "liuxiansheng") ?: "liuxiansheng"
val keyPass: String? = signingParam("RELEASE_KEY_PASSWORD")
val hasSigning: Boolean = storePass != null && keyPass != null &&
    rootProject.file(storeFilePath).exists()

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
        if (hasSigning) {
            create("release") {
                // V3 独立签名（与原版 lyricon123 不同，可与原版共存安装）
                storeFile = rootProject.file(storeFilePath)
                storePassword = storePass
                keyAlias = aliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // 测试版也签 V3 独立签名：这样 `install -r` 能直接覆盖手机上的包，不必卸载。
            // 包名不加后缀（与正式包同为 appPackageName），否则覆盖安装会变成另一个 App。
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            // 本地/CI 有密钥则用 V3 签名；他人 clone 无密钥时退回 debug 签名，保证能编出包
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
