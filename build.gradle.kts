import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

// ============ 版本号（正式版进位，测试版跟在正式版后面加一位小数） ============
// 正式版：1.1.0 →（下一次打正式版）1.2.0 → 1.3.0 …
// 测试版：跟在当前正式版号后面加一位小数 —— 正式版 1.1.0 之后是 1.1.1、1.1.2 …
//         正式版升到 1.2.0 后，测试版从 1.2.1 重新开始
// 两条规则共用一个小数计数：只在构建成功后 +1，配置阶段只计算、不改文件。
// 这样一次构建里配置被跑几遍，算出来的号都一样；不再需要回退点文件。
val selfVersionFile = rootProject.file("build.gradle.kts")
val versionCodeRe = """extra\["appVersionCode"\] = (\d+)""".toRegex()
val releaseNameRe = """extra\["appReleaseVersionName"\] = "(\d+)\.(\d+)\.(\d+)"""".toRegex()
val testPatchRe = """extra\["appTestPatch"\] = (\d+)""".toRegex()

// ↓ 下面是版本号存档（打包脚本自己改写它们），不是本次构建生效值，别手改
extra["appVersionCode"] = 2903
extra["appReleaseVersionName"] = "2.5.0"
extra["appTestPatch"] = 5

fun writeVersionToFile(code: Int, release: String, testPatch: Int) {
    var t = selfVersionFile.readText()
    t = versionCodeRe.replace(t, """extra["appVersionCode"] = $code""")
    t = releaseNameRe.replace(t, """extra["appReleaseVersionName"] = "$release"""")
    t = testPatchRe.replace(t, """extra["appTestPatch"] = $testPatch""")
    selfVersionFile.writeText(t)
}

run {
    var text = selfVersionFile.readText()
    var code = versionCodeRe.find(text)!!.groupValues[1].toInt()
    var rel = releaseNameRe.find(text)!!.groupValues.let { "${it[1]}.${it[2]}.${it[3]}" }
    var patch = testPatchRe.find(text)!!.groupValues[1].toInt()

    val names = gradle.startParameter.taskNames
    val isRelease = names.any { it.endsWith("Release") }
    val isTest = !isRelease && names.any { it.endsWith("Debug") }

    var buildName = rel
    if (isTest) {
        // 测试版：正式版号 + 加一位小数，例如 1.1.0 → 1.1.1
        patch += 1
        val p = rel.split(".")
        buildName = "${p[0]}.${p[1]}.$patch"
    } else if (isRelease) {
        // 正式版：进到下一个小版本，测试小数位归零，例如 1.1.0 → 1.2.0
        // 例外：打补丁正式版时用 -PforceVersionName=1.3.1 指定，号段按指定值记账
        val forced = (project.findProperty("forceVersionName") as String?)?.takeIf { it.isNotBlank() }
        if (forced != null) {
            buildName = forced
            // 补丁正式版不改基准号，只把补丁位记到当前值，后续测试版从它的下一位继续
            patch = forced.split(".").lastOrNull()?.toIntOrNull() ?: 0
        } else {
            val p = rel.split(".")
            buildName = "${p[0]}.${p[1].toInt() + 1}.0"
            rel = buildName
            patch = 0
        }
    }

    if (isRelease || isTest) {
        // 只计算本次要用的号，不写回文件；等构建成功由 commitVersion* 任务统一提交
        code += 1
        extra["pendingVersionCode"] = code
        extra["pendingReleaseName"] = rel
        extra["pendingTestPatch"] = patch
        println("本次打包版本: $buildName (code $code)  [${if (isTest) "测试版" else "正式版"}]")
    }

    extra["appPackageName"] = "io.github.liuxiansheng12369.chidao"
    extra["appVersionCode"] = code
    extra["appReleaseVersionName"] = rel
    extra["appTestPatch"] = patch
    extra["appVersionName"] = buildName
}
extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 37
extra["minSdkVersion"] = 33

extra["providerSdkVersion"] = "0.1.71"
extra["subscriberSdkVersion"] = "0.1.71"
extra["lyricModelVersion"] = "0.1.71"

// ============ 构建产物输出（跟随工程所在目录） ============
// 之前因工程根目录含中文（lyricon-v3-全新架构），AGP 测试 worker 的类路径经 @argfile 传递时
// 无法解析非 ASCII 路径，导致"测试类 ClassNotFoundException"，故曾把各模块 build 产物
// 重定向到纯 ASCII 路径 D:\Android\lyricon-build-3086。
// 现工程目录为纯 ASCII（D:\Android\lyricon-3.0.86），取消重定向，恢复 Gradle 默认：
// APK 生成在工程所在目录的 build/all-apks/debug 下，工程移到哪，APK 就在哪生成。
// 注意：若将来把工程放到含中文的目录，需恢复上面的重定向。

// ============ APK 自动导出 ============
val cleanApks: TaskProvider<Task> = tasks.register("cleanAllApks") {
    group = "build"
    doFirst {
        val outputDir = project.layout.buildDirectory.dir("all-apks").get().asFile
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
            println("--- [Clean] 已清理旧的 APK 导出目录 ---")
        }
    }
}

val copyApksAll: TaskProvider<Task> = tasks.register("copyApks") {
    group = "build"
    description = "收集所有 APK 并按 BuildType 分开打包 ZIP"
}

val buildTypeZipTasks = mutableSetOf<String>()

subprojects {
    plugins.withId("com.android.application") {
        val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()

        androidComponents.onVariants { variant ->
            val variantName = variant.name
            val moduleName = project.name
            val buildType = variant.buildType ?: "others"
            val versionName =
                variant.outputs.firstOrNull()?.versionName?.getOrElse(project.version.toString())
                    ?: "1.0"

            val zipTaskName = "zip${buildType.replaceFirstChar { it.uppercase() }}Apks"
            val typeZipTask = rootProject.tasks.maybeCreate(zipTaskName, Zip::class.java).apply {
                group = "build"
                archiveFileName.set("${rootProject.name}-all-$buildType.zip")
                destinationDirectory.set(rootProject.layout.buildDirectory.dir("distributions"))
                from(rootProject.layout.buildDirectory.dir("all-apks/$buildType"))
                // all-apks 里会一直累积历史 APK，几版之后 zip 就会超过 4G 的普通 zip 上限
                isZip64 = true
            }

            val copyTask =
                tasks.register<Copy>("copy${variantName.replaceFirstChar { it.uppercase() }}Apk") {
                    // 只复制、不清理：清理改成单独跑 cleanAllApks，
                    // 这样上一版 APK 能留下来做对比，复制本身也能增量。
                    from(variant.artifacts.get(SingleArtifact.APK))
                    into(rootProject.layout.buildDirectory.dir("all-apks/$buildType"))
                    include("*.apk")

                    eachFile { relativePath = RelativePath(true, name) }

                    rename { fileName ->
                        val abiSuffix = when {
                            fileName.contains("arm64-v8a") -> "-arm64-v8a"
                            fileName.contains("armeabi-v7a") -> "-armeabi-v7a"
                            fileName.contains("x86_64") -> "-x86_64"
                            else -> ""
                        }
                        "词岛-${versionName}-${buildType}${abiSuffix}.apk"
                    }
                    duplicatesStrategy = DuplicatesStrategy.INCLUDE
                    finalizedBy(typeZipTask)
                }

            copyApksAll.configure { dependsOn(copyTask) }

            tasks.matching { it.name == "assemble${variantName.replaceFirstChar { c -> c.uppercase() }}" }
                .configureEach { finalizedBy(copyTask) }

            // 版本号提交挂在这条流水线的最后一环（zip）之后：
            // 编译失败、copy 失败、zip 失败时都不会写号，只有整条链路跑通才提交。
            rootProject.tasks.named(zipTaskName).configure {
                finalizedBy(if (buildType == "release") ":commitVersionRelease" else ":commitVersionTest")
            }
        }
    }
}

// ============ 版本号策略 ============
// 配置阶段只计算本次要用的号（幂等：配置跑几遍都是同一个号）；
// 构建成功后才由 commitVersion* 把号写回 build.gradle.kts。
// 构建失败/中断 → 文件没被改，天然不涨，不需要回退点。

/** 本构建里是否已有任何任务失败（编译、打包、copy、zip 都算）。 */
fun anyTaskFailed(): Boolean =
    rootProject.gradle.taskGraph.allTasks.any { it.state.failure != null }

/** 构建真正跑完且没出错，才把本次算出的号写回文件。 */
fun commitVersionIfSuccess(assembleSuffix: String, label: String) {
    val assembles = rootProject.allprojects.flatMap { p ->
        p.tasks.toList().filter { it.name.startsWith("assemble") && it.name.endsWith(assembleSuffix) }
    }
    val anyExecuted = assembles.any { it.state.executed }
    if (!anyTaskFailed() && anyExecuted) {
        val pendingCode = rootProject.extra["pendingVersionCode"] as? Int
        val pendingRel = rootProject.extra["pendingReleaseName"] as? String
        val pendingPatch = rootProject.extra["pendingTestPatch"] as? Int
        if (pendingCode != null && pendingRel != null && pendingPatch != null) {
            writeVersionToFile(pendingCode, pendingRel, pendingPatch)
            println("$label 构建成功，版本号已提交: $pendingRel / 补位 $pendingPatch / code $pendingCode")
        } else {
            println("$label 构建成功，本次没有版本号变更")
        }
    } else {
        println("$label 构建未成功（有任务失败），版本号未改动")
    }
}

tasks.register("commitVersionTest") {
    doLast { commitVersionIfSuccess("Debug", "测试版") }
}

tasks.register("commitVersionRelease") {
    doLast { commitVersionIfSuccess("Release", "正式版") }
}

// 版本号提交统一走上面的 zip → commit 链路，这里不再给每个 assemble 任务挂 finalizedBy，
// 否则编译失败/后续任务失败时也会误提交版本号。

