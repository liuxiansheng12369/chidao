plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.liuxiansheng.lyricon.axis"

    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }

    defaultConfig {
        minSdk = rootProject.extra.get("minSdkVersion") as Int
        consumerProguardFiles("consumer-rules.pro")
    }

    // 主工程 :app / :xposed 的 flavor dimension 是 locale，这里必须同名同值，
    // 否则 :app 的 standardRelease 找不到 :axis 的对应变体，依赖解析报错。
    flavorDimensions += "locale"
    productFlavors {
        create("standard") { dimension = "locale" }
        create("zh") { dimension = "locale" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // 主工程用 Compose M2（androidx.compose.material:material），不引 material3
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
