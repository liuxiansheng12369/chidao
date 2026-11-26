/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 *
 * LyricSourceScanner：扫描系统内歌词 provider 插件。
 * - 识别：非系统应用且 metaData 声明 lyricon_module=true（兼容 ColorOS 实时歌词桥接前缀包）
 * - 外部 provider 白名单：词岛生态已知的外部歌词服务（如 proify 项目 lyricon central / 老词梦 provider /
 *   刘先生聚合 provider），未声明 lyricon_module 标记也直接识别展示
 * - 认证：签名 SHA-256 与受信任来源比对，标记是否官方认证
 * - 分组：按 category 分类，未声明的归"其他"（始终排最后）
 * 全部逻辑在调用线程（IO）完成，一次性产出 UI 可直接渲染的分类列表。
 */

package com.liuxiansheng.lyricon.app.lyricsource

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Process
import java.io.File
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale

object LyricSourceScanner {

    /** 受信任签名来源（与上游 provider 一致），集中管理便于安全审计。 */
    object TrustedSignatures {
        const val DEFAULT = "d75a43f76dbe80d816046f952b8d0f5f7abd71c9bd7b57786d5367c488bd5816"
        const val TRANTOR = "ba86f0c1f52d0f6a24e1b9a63eade0e8b80e7b9e20b8ef068da2e39c7b6e7b49"
        const val SALT_PLAYER = "8488d67a39978f84fd876510e0acb85e3a0504b90fbd56f11beb2123e285fa78"
        const val FLAMINGO = "4ca5ff4e5bf8418b45a8ecb46ddb91b6403ab7cc4b1a4de7e58fba12f54278e6"
        // 刘先生聚合版签名
        const val LIUXIANSHENG = "875AFCEC6B9826FBF5382AB110D3FDCE03984A4B938338E3417C251D5DADB1FC"

        val ALL_CERTIFIED = arrayOf(DEFAULT, TRANTOR, SALT_PLAYER, FLAMINGO, LIUXIANSHENG)
    }

    private const val COLOROS_PREFIX = "io.github.andrealtb.coloroslyrics.provider."

    /** su 读取 LSPosed 数据库的超时上限：真我上授权框没人点时会永不返回，超时就放弃。 */
    private const val SU_TIMEOUT_MS = 5_000L

    /**
     * 词岛生态已知的外部歌词服务（无 lyricon_module 标记的兜底白名单）：
     * 已安装即直接识别展示；描述优先取应用声明的 xposeddescription meta-data，
     * 其次取 manifest android:description，均无则留空。展示信息一律取应用自身真实元数据，不额外编造文字。
     */
    private val KNOWN_PROVIDERS = setOf(
        "io.github.proify.lyricon.core",
        "io.github.proify.lyricon.qishuiprovider",
        "liuxiansheng.lyricon.provider",
    )

    /** "系统里装了、但词岛看不见"的源归到这个分类，排在最后。 */
    private const val HIDDEN_CATEGORY = "被隐藏的源"

    /**
     * 扫描 + 认证 + 分组排序，一次性产出 UI 可直接渲染的分类列表。
     * @param otherLabel 未声明分类的兜底分类名（默认"其他"）
     */
    fun scan(context: Context, otherLabel: String = "其他"): List<LyricSourceCategory> =
        scanReport(context, otherLabel).categories

    /**
     * 扫描并同时产出诊断快照。
     *
     * 歌词源页的"自检"块直接吃这份数据上屏 —— 真我这类没有电脑、抓不了 logcat 的机器，
     * 打开页面就能看到"可见包几个 / 认出几个 / 权限开没开 / 到底卡在哪一步"。
     *
     * @param deep 是否抓"root 直读的全量安装包"并解析被隐藏的源。页面上恒为 true：
     *   源有没有装、装没装、卸载后有没有变化，靠的就是这条；自检开关只控制诊断面板显不显示。
     */
    fun scanReport(
        context: Context,
        otherLabel: String = "其他",
        deep: Boolean = true,
    ): LyricSourceScanReport {
        val pm = context.packageManager
        var diagnostics = LyricSourceDiagnostics(
            apiLevel = Build.VERSION.SDK_INT,
            brand = "${Build.BRAND}/${Build.MANUFACTURER}",
        )
        return try {
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(
                PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
            )

            // LSPosed 状态读取带超时：拿不到就算了，绝不允许拖住整页扫描。
            // 注意：激活状态必须一直读（否则"已激活/未激活"这两个词会消失）；
            // 自检关闭（deep=false）只是不再抓"root 直读的全量安装包"这一步。
            // root 直读安装列表始终抓（和自检开关无关）：不抓的话下面会去读上一次留下的
            // pkgs.txt 旧快照，"只装过、已卸载"的包就会一直从旧文件里冒出来当"被隐藏的源"。
            val lspd = loadLspdState(context, withRootPackages = true)

            val visibleNames = packages.mapTo(HashSet()) { it.packageName }

            // root 直读（pm list packages -u）里有、但 PackageManager 看不见的歌词源包：
            // 说明有别的模块 hook 了包查询。这类包拿不到图标/版本，但必须"显示出来"，
            // 否则用户装好的源在词岛里永远是一句"未检测到"。
            val hiddenNames = lspd.packages
                .filter { it != context.packageName }
                .filter { it !in visibleNames && looksLikeLyricSource(it) }
                .sorted()

            // 被隐藏的源再走一条路：root 把它的 APK 复制到私有目录，用 getPackageArchiveInfo 直解。
            // 被 hook 的是"包查询"，不是"文件读取"，所以这条路能拿到图标、版本、meta-data、签名。
            if (hiddenNames.isNotEmpty()) exportHiddenApks(context, hiddenNames)
            val hiddenValid = hiddenNames.mapNotNull { pkg ->
                readArchivedPackage(context, pkg)?.let { processPackage(pm, it, lspd.state) }
            }
            // APK 也读不出来的，才退回"只有包名"的最小条目
            val stillHidden = hiddenNames.filter { pkg -> hiddenValid.none { it.packageName == pkg } }

            val valid = packages
                .filter { isValidModule(it) }
                .mapNotNull { processPackage(pm, it, lspd.state) } +
                hiddenValid +
                stillHidden.map { hiddenModule(it) }

            // 自检关闭（deep=false）时不再采集诊断细项：疑似清单、第三方全量清单、诊断日志都不做。
            // 只保留源检测必需的部分（root 直读安装列表 + 解析被隐藏的源），那部分始终执行。
            val missed = if (deep) {
                packages.asSequence()
                    .map { it.packageName }
                    .filter { looksLikeLyricSource(it) && it != context.packageName }
                    // 只看第三方包里的"像歌词源却认不出"：系统包（com.coloros.* 一大串）刷屏没有意义
                    .filter { p -> valid.none { it.packageName == p } && !isSystemPackage(p) }
                    .toList()
            } else {
                emptyList()
            }

            diagnostics = LyricSourceDiagnostics(
                visiblePackages = packages.size,
                recognized = valid.size,
                suspectMissed = missed,
                queryAllGranted = runCatching {
                    context.checkSelfPermission("android.permission.QUERY_ALL_PACKAGES")
                }.getOrNull()?.let { it == PackageManager.PERMISSION_GRANTED },
                apiLevel = Build.VERSION.SDK_INT,
                brand = "${Build.BRAND}/${Build.MANUFACTURER}",
                lspdStateOk = lspd.state != null,
                lspdTimedOut = lspd.timedOut,
                lspdModules = lspd.state?.xposedModules
                    ?.filter { it != context.packageName }
                    ?.sorted()
                    ?: emptyList(),
                thirdPartyPackages = if (deep) {
                    packages.asSequence()
                        .map { it.packageName }
                        .filter { it != context.packageName && !isSystemPackage(it) }
                        .sorted()
                        .toList()
                } else {
                    emptyList()
                },
                rootPackages = lspd.packages.size,
                hiddenPackages = hiddenNames,
            )
            if (deep) logScanDiagnostics(diagnostics)

            if (valid.isEmpty()) return LyricSourceScanReport(emptyList(), diagnostics)

            val collator = Collator.getInstance(Locale.getDefault())
            // 认证优先，其次按标签首字母
            val sorted = valid.sortedWith { m1, m2 ->
                if (m1.isCertified != m2.isCertified) {
                    m2.isCertified.compareTo(m1.isCertified)
                } else {
                    collator.compare(m1.label, m2.label)
                }
            }
            // 分组：未声明分类归"其他"，"其他"始终排最后，各组内部按标签排序
            val categories = sorted.groupBy { it.category ?: otherLabel }
                .map { (name, items) ->
                    LyricSourceCategory(
                        name = name,
                        items = items.sortedWith { a, b -> collator.compare(a.label, b.label) },
                    )
                }
                .sortedWith { c1, c2 ->
                    when {
                        c1.name == otherLabel || c1.name == HIDDEN_CATEGORY -> 1
                        c2.name == otherLabel || c2.name == HIDDEN_CATEGORY -> -1
                        else -> collator.compare(c1.name, c2.name)
                    }
                }
            LyricSourceScanReport(categories, diagnostics)
        } catch (e: Exception) {
            android.util.Log.e(
                "LyricSourceDiag",
                "歌词源扫描异常: ${e.javaClass.simpleName}: ${e.message}",
                e
            )
            LyricSourceScanReport(
                emptyList(),
                diagnostics.copy(error = "${e.javaClass.simpleName}: ${e.message}"),
            )
        }
    }

    /** 包名是否像歌词源（用于挑出"装了却没被识别"的包）。 */
    private fun looksLikeLyricSource(packageName: String): Boolean {
        val n = packageName.lowercase(Locale.US)
        return n.contains("lyric") || n.contains("coloros") || n.contains("provider") ||
            n.contains("proify") || n.contains("qishui")
    }

    /**
     * 系统 / 厂商应用前缀。
     *
     * 这些不算"第三方"，从候选清单里剔掉，否则 475 个包全铺到页面上没法看。
     */
    private val SYSTEM_PACKAGE_PREFIXES = listOf(
        "android",
        "com.android.",
        "com.coloros.",
        "com.oplus.",
        "com.oppo.",
        "com.realme.",
        "com.mediatek.",
        "com.qualcomm.",
        "com.qti.",
        "com.google.",
    )

    private fun isSystemPackage(packageName: String): Boolean =
        packageName == "android" || SYSTEM_PACKAGE_PREFIXES.any { packageName.startsWith(it) }

    /** 点对点探活：直接按包名问一次，不走全机枚举。（已随"已知源探活"一起停用，保留实现备查） */
    private fun isPackageVisible(pm: PackageManager, packageName: String): Boolean =
        runCatching { pm.getPackageInfo(packageName, 0) != null }.getOrDefault(false)

    /**
     * 歌词源扫描诊断（真我 / ColorOS 上"装了歌词源却一直不显示"的定位用）。
     *
     * 输出内容：
     * - 包可见性：`getInstalledPackages` 实际能看到多少个包；
     * - 名字像歌词源（lyric / coloros / provider / proify / qishui）却**没**被识别出来的包名；
     * - 对已知 provider 包做逐个 `getPackageInfo` 试探，判断是包不可见还是元数据不匹配；
     * - QUERY_ALL_PACKAGES 的自检结果与机型信息。
     */
    /**
     * 歌词源扫描诊断（到 logcat，同时在页面上屏同一份数据）。
     *
     * 输出内容：
     * - 包可见性：`getInstalledPackages` 实际能看到多少个包；
     * - 名字像歌词源（lyric / coloros / provider / proify / qishui）却**没**被识别出来的包名；
     * - 对已知 provider 包做逐个 `getPackageInfo` 试探，判断是包不可见还是元数据不匹配；
     * - QUERY_ALL_PACKAGES 的自检结果、LSPosed 数据库读取是否超时、机型信息。
     */
    private fun logScanDiagnostics(d: LyricSourceDiagnostics) {
        runCatching {
            android.util.Log.i(
                "LyricSourceDiag",
                "扫描: 可见包=${d.visiblePackages} 识别=${d.recognized} " +
                    "| 疑似歌词源但未识别=[${d.suspectMissed.joinToString(",")}] " +
                    "| queryAllPerm=${d.queryAllGranted} api=${d.apiLevel} " +
                    "| lspd=${
                        when {
                            d.lspdStateOk -> "ok"
                            d.lspdTimedOut -> "timeout"
                            else -> "fail"
                        }
                    } brand=${d.brand} " +
                    "| lspd模块=[${d.lspdModules.joinToString(",")}] " +
                    "| root直读=${d.rootPackages} 被隐藏=[${d.hiddenPackages.joinToString(",")}] " +
                    "| 第三方包=[${d.thirdPartyPackages.joinToString(",")}]" +
                    (d.error?.let { " | error=$it" } ?: "")
            )
        }
    }

    /** 是否为合规的歌词 provider 插件（非系统应用且含 lyricon_module 标记 / 已知外部 provider / ColorOS 桥接前缀）。 */
    private fun isValidModule(packageInfo: PackageInfo): Boolean {
        val appInfo = packageInfo.applicationInfo ?: return false
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem || isUpdatedSystem) return false
        // 词岛生态已知外部 provider（无需 lyricon_module 标记）
        if (packageInfo.packageName in KNOWN_PROVIDERS) return true
        // 内置支持：ColorOS 实时歌词桥接系列（包名前缀匹配）
        if (packageInfo.packageName.startsWith(COLOROS_PREFIX)) return true
        return appInfo.metaData?.getBoolean("lyricon_module") == true
    }

    /** LSPosed 激活状态快照：哪些包已注册为 Xposed 模块、哪些已启用。 */
    private data class LspdState(
        val xposedModules: Set<String>,
        val enabledModules: Set<String>,
    )

    /**
     * LSPosed 读取结果。
     * state=null 表示没读到（模块没装 / su 不可用 / 读库失败），此时不展示激活角标，避免误标。
     * timedOut=true 表示 su 卡住、已被强行中断 —— 这不是"读取失败"，是"读取卡死"，要在页面上区分开。
     */
    private data class LspdSnapshot(
        val state: LspdState?,
        val timedOut: Boolean,
        /**
         * root 直读的安装包清单（`pm list packages -u`）。
         *
         * 为什么要有这一份：某些模块会 hook 掉 PackageManager，让我们的 getInstalledPackages
         * 看不见已经装好的 provider；但 hook 只作用于 App 进程，root 跑 pm 拿到的是真值。
         * 这是绕开一切 hook 的兜底检测路径。
         */
        val packages: List<String> = emptyList(),
    )

    /**
     * 读取 LSPosed 激活状态。
     * 新版 LSPosed 把激活状态存于 SQLite（modules_config.db 的 modules_state.enabled 字段）。
     * 设备通常无 sqlite3 可执行文件，因此通过 su 把数据库（含 WAL/SHM）复制到 app 私有目录，
     * 再用 SQLiteDatabase 查询。返回 null 表示检测失败（不展示激活提示，避免误标）。
     *
     * @param withRootPackages 是否顺带抓"root 直读的全量安装包"。
     *   "已激活/未激活"只依赖数据库，必须一直读（自检关掉也得读，否则那两个词就消失了）；
     *   全量安装包只有自检打开时才用得到，关掉就省这一步。
     */
    private fun loadLspdState(context: Context, withRootPackages: Boolean = true): LspdSnapshot {
        return try {
            val src = "/data/adb/lspd/config/modules_config.db"
            val dir = File(context.filesDir, "lspd")
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, "modules_config.db")
            // 主库必须复制成功；WAL/SHM 单独容错（未开启 WAL 时不存在）；
            // 注意 -wal/-shm 也需 chmod/chown，否则 app 进程打不开 WAL（SQLite 报 Permission denied）
            val uid = Process.myUid()
            val pkgTxt = File(dir, "pkgs.txt")
            val cmd = "cp '$src' '${dst.path}' && chmod 644 '${dst.path}' && chown $uid:$uid '${dst.path}' && " +
                "{ cp '$src-wal' '${dst.path}-wal' 2>/dev/null || true; } && " +
                "{ cp '$src-shm' '${dst.path}-shm' 2>/dev/null || true; } && " +
                "{ chmod 644 '${dst.path}-wal' '${dst.path}-shm' 2>/dev/null || true; } && " +
                "{ chown $uid:$uid '${dst.path}-wal' '${dst.path}-shm' 2>/dev/null || true; } && " +
                // 自检打开时，同一次 su 里把"全量安装包"也抓下来（只列真正还装着的包；
                // 不加 -u，因为 -u 会把"只装过、已卸载"的历史记录也列进来，那类包没有 APK、
                // 也没有版本号，混进来只会变成一条"包名尾段 + v未知 + 已激活"的假源）。
                // 这条走 root、不经过 App 进程的 PackageManager，被 hook 也躲不过去。
                (if (withRootPackages) {
                    "{ pm list packages > '${pkgTxt.path}' 2>/dev/null || true; } && " +
                        "{ chmod 644 '${pkgTxt.path}' 2>/dev/null || true; } && " +
                        "{ chown $uid:$uid '${pkgTxt.path}' 2>/dev/null || true; }"
                } else {
                    "true"
                })
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            // waitFor() 没有超时：真我上 su 授权框没人点、或 SELinux 拦着，这条会永不返回，
            // 整个扫描就永远停在"正在扫描歌词源…"。所以这里自己轮询，超时直接强杀。
            val deadline = System.currentTimeMillis() + SU_TIMEOUT_MS
            var exited = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue()
                    exited = true
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            if (!exited) {
                runCatching { proc.destroyForcibly() }
                android.util.Log.w(
                    "LyricSourceDiag",
                    "su 读取 LSPosed 数据库超时(${SU_TIMEOUT_MS}ms)，已强行中断（不影响歌词源列表）"
                )
                return LspdSnapshot(null, true)
            }
            if (proc.exitValue() != 0 || !dst.exists()) return LspdSnapshot(null, false)

            val db = SQLiteDatabase.openDatabase(dst.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val xposed = LinkedHashSet<String>()
                db.rawQuery("SELECT module_pkg_name FROM modules", null).use { c ->
                    while (c.moveToNext()) xposed.add(c.getString(0))
                }
                val enabled = LinkedHashSet<String>()
                db.rawQuery(
                    "SELECT module_pkg_name FROM modules_state WHERE enabled=1 AND user_id=0",
                    null
                ).use { c ->
                    while (c.moveToNext()) enabled.add(c.getString(0))
                }
                // root 直读的安装包清单（被 hook 隐藏也能看到真值）；读不到就空着，不影响列表。
                // 只有本次真的抓了才读文件 —— 否则读到的是上一次留下的旧快照，会把已经卸载的包
                // 当成"被隐藏的源"一直显示下去。
                val rootPkgs = if (withRootPackages) {
                    runCatching {
                        pkgTxt.readLines(Charsets.UTF_8)
                            .map { it.removePrefix("package:").trim() }
                            .filter { it.isNotEmpty() }
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                LspdSnapshot(LspdState(xposed, enabled), false, rootPkgs)
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            LspdSnapshot(null, false)
        }
    }

    /**
     * 计算单个模块的激活展示状态：
     * - 检测失败或模块未注册为 Xposed 模块（无激活概念）→ null（不展示提示）
     * - 已注册的 Xposed 模块 → enabled 库中命中即已激活，否则未激活
     */
    /**
     * 已知 provider 的能力标签（补声明用）。
     *
     * ColorOS 桥接系列与部分外部 provider 的 manifest 里没有 lyricon_module_tags，
     * 但实现里确实在写逐字时间轴（每行 ts 数组）与 translation 字段，
     * 卡片上不能因为"没声明"就显得什么都不支持，这里按包名补齐。
     */
    private val CAPABILITY_TAGS = mapOf(
        "io.github.andrealtb.coloroslyrics.provider.qishui" to listOf("\$syllable", "\$translation"),
        "io.github.andrealtb.coloroslyrics.provider.kuwo" to listOf("\$syllable", "\$translation"),
        "liuxiansheng.lyricon.provider" to listOf("\$syllable", "\$translation"),
    )

    /**
     * 已知 provider 的中文描述（补多语言用）。
     *
     * 部分 provider 的 manifest 只写了英文描述、没做多语言资源，系统中文环境下也只会显示英文。
     * 这里按包名给一份离线中文对照，命中就替换，未命中的原样显示。
     */
    private val DESCRIPTION_ZH = mapOf(
        "io.github.andrealtb.coloroslyrics.provider.kuwo" to "为酷我音乐提供 ColorOS 锁屏歌词",
        "io.github.proify.lyricon.qishuiprovider" to "为词幕与 ColorOS 实时歌词桥接提供汽水音乐歌词",
        "com.leguandev.sollin.v2" to "为词幕悬浮歌词提供歌词的 Sollin 歌词源",
    )

    /** 命中中文对照就用中文，否则保留 provider 自带描述。 */
    private fun zhDescription(packageName: String, raw: String?): String? =
        DESCRIPTION_ZH[packageName] ?: raw

    private fun activationOf(packageName: String, state: LspdState?): Boolean? {
        if (state == null) return null
        if (packageName !in state.xposedModules) return null
        return packageName in state.enabledModules
    }

    /**
     * 把被隐藏的源 APK 复制到私有目录。
     *
     * 一次 su 里把所有包一起处理，避免逐个弹授权；被挡的是 PackageManager 的查询结果，
     * `pm path` 由 root 执行照样能拿到真实路径。
     */
    private fun exportHiddenApks(context: Context, packages: List<String>) {
        if (packages.isEmpty()) return
        val dir = File(context.filesDir, "apk")
        if (!dir.exists()) dir.mkdirs()
        val uid = Process.myUid()
        val script = packages.joinToString("; ") { pkg ->
            val dst = File(dir, "$pkg.apk").path
            val mark = File(dir, "$pkg.src").path
            // 之前拷过、且源路径没变（路径里带版本目录，升级后就会变）→ 跳过，省掉每次进页面重拷十几兆
            "p=\$(pm path '$pkg' 2>/dev/null | head -n1 | sed 's/package://'); " +
                "[ -n \"\$p\" ] && { [ -s '$dst' ] && [ \"\$(cat '$mark' 2>/dev/null)\" = \"\$p\" ]; } || " +
                "{ cp \"\$p\" '$dst' 2>/dev/null && chmod 644 '$dst' && chown $uid:$uid '$dst' && echo \"\$p\" > '$mark'; }"
        }
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            val deadline = System.currentTimeMillis() + SU_TIMEOUT_MS
            var exited = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue()
                    exited = true
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            if (!exited) runCatching { proc.destroyForcibly() }
        }
    }

    /** 直接解析私有目录里那份 APK，绕开被 hook 的包查询拿完整包信息。 */
    private fun readArchivedPackage(context: Context, packageName: String): PackageInfo? {
        return runCatching {
            val apk = File(File(context.filesDir, "apk"), "$packageName.apk")
            if (!apk.exists() || apk.length() == 0L) return null
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(
                apk.path,
                PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: return null
            // 未安装的 APK 必须补上 sourceDir，否则加载图标 / 读资源都会失败
            info.applicationInfo?.let { ai ->
                ai.sourceDir = apk.path
                ai.publicSourceDir = apk.path
            }
            info
        }.getOrNull()
    }

    /**
     * 构造"系统里确实装了、但词岛拿不到包信息"的源条目。
     *
     * 这种情况只有包名可用（图标、版本、签名都要靠 PackageManager，而它被 hook 挡住了），
     * 所以给一份最小条目，让用户至少能在列表里看到这个源存在。
     */
    private fun hiddenModule(packageName: String): LyricSourceModule = LyricSourceModule(
        packageName = packageName,
        label = packageName.substringAfterLast('.'),
        versionName = null,
        author = null,
        description = "系统安装列表里有这个源，但词岛拿不到它的包信息：有模块在隐藏包查询。"
            + "把词岛加入那个隐藏类模块的放行名单后重新扫描即可读到图标和版本。",
        category = HIDDEN_CATEGORY,
        isCertified = false,
        tags = emptyList(),
        homeUrl = null,
        activation = true,
    )

    /** 处理单个安装包，提取模块元数据并进行签名认证。 */    private fun processPackage(pm: PackageManager, packageInfo: PackageInfo, state: LspdState?): LyricSourceModule? {
        return try {
            val appInfo = packageInfo.applicationInfo ?: return null
            val label = appInfo.loadLabel(pm).toString()
            val metaData = appInfo.metaData
            val activation = activationOf(packageInfo.packageName, state)

            // 1) 通用分支：声明 lyricon_module 标记的模块（外部 provider 自带真实作者/描述/标签/分类）
            if (metaData?.getBoolean("lyricon_module") == true) {
                return LyricSourceModule(
                    packageName = packageInfo.packageName,
                    label = label,
                    versionName = packageInfo.versionName,
                    author = readMetaString(pm, appInfo, metaData, "lyricon_module_author"),
                    description = zhDescription(
                        packageInfo.packageName,
                        readMetaString(pm, appInfo, metaData, "lyricon_module_description")
                    ),
                    category = readMetaString(pm, appInfo, metaData, "lyricon_module_category"),
                    isCertified = isCertified(packageInfo),
                    tags = extractTags(pm, appInfo, metaData).ifEmpty {
                        CAPABILITY_TAGS[packageInfo.packageName] ?: emptyList()
                    },
                    homeUrl = readMetaString(pm, appInfo, metaData, "lyricon_module_home"),
                    activation = activation,
                )
            }

            // 2) ColorOS 实时歌词桥接系列（前缀匹配）：无 lyricon_module metaData，描述取应用自身声明的 description
            if (packageInfo.packageName.startsWith(COLOROS_PREFIX)) {
                return LyricSourceModule(
                    packageName = packageInfo.packageName,
                    label = label,
                    versionName = packageInfo.versionName,
                    author = null,
                    description = zhDescription(packageInfo.packageName, loadDescription(pm, appInfo)),
                    category = null,
                    isCertified = isCertified(packageInfo),
                    tags = CAPABILITY_TAGS[packageInfo.packageName] ?: emptyList(),
                    homeUrl = "https://modules.lsposed.org/module/io.github.andrealtb.lockscreenlyrics/",
                    activation = activation,
                )
            }

            // 3) 已知外部 provider 兜底：无 lyricon_module 标记才走这里（描述取 xposeddescription 或 manifest description）
            if (packageInfo.packageName in KNOWN_PROVIDERS) {
                return LyricSourceModule(
                    packageName = packageInfo.packageName,
                    label = label,
                    versionName = packageInfo.versionName,
                    author = null,
                    description = zhDescription(
                        packageInfo.packageName,
                        readXposedDescription(pm, appInfo, metaData) ?: loadDescription(pm, appInfo)
                    ),
                    category = null,
                    isCertified = isCertified(packageInfo),
                    tags = CAPABILITY_TAGS[packageInfo.packageName] ?: emptyList(),
                    homeUrl = null,
                    activation = activation,
                )
            }

            null
        } catch (e: Exception) {
            // 单个包的元数据/签名读取失败不能静默：真我上"装了却不显示"往往就栽在这里
            android.util.Log.i(
                "LyricSourceDiag",
                "包处理失败: ${packageInfo.packageName} ${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    /** 读取 meta-data 字符串值：直接字符串原样返回，资源引用（Int）解析为文本。 */
    private fun readMetaString(
        pm: PackageManager,
        appInfo: ApplicationInfo,
        metaData: android.os.Bundle,
        key: String,
    ): String? {
        val value = metaData.get(key) ?: return null
        return when (value) {
            is Int -> if (value != 0) {
                runCatching { pm.getText(appInfo.packageName, value, appInfo)?.toString() }.getOrNull()
            } else {
                null
            }
            is String -> value.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** 读取 Xposed 模块风格的 xposeddescription meta-data（支持资源引用），未声明返回 null。 */
    private fun readXposedDescription(
        pm: PackageManager,
        appInfo: ApplicationInfo,
        metaData: android.os.Bundle?,
    ): String? {
        if (metaData == null) return null
        return readMetaString(pm, appInfo, metaData, "xposeddescription")
    }

    /** 读取应用在 manifest 中自己声明的 description（android:description 字符串资源），未声明返回 null。 */
    private fun loadDescription(pm: PackageManager, appInfo: ApplicationInfo): String? {
        val resId = appInfo.descriptionRes
        if (resId == 0) return null
        return runCatching { pm.getText(appInfo.packageName, resId, appInfo)?.toString() }.getOrNull()
    }

    /** 解析插件的标签元数据（支持 string 数组资源或逗号分隔字符串）。 */
    private fun extractTags(
        pm: PackageManager,
        appInfo: ApplicationInfo,
        metaData: android.os.Bundle,
    ): List<String> {
        val tagsResId = metaData.getInt("lyricon_module_tags")
        val rawTags = if (tagsResId != 0) {
            runCatching { pm.getResourcesForApplication(appInfo).getStringArray(tagsResId).toList() }
                .getOrDefault(emptyList())
        } else {
            metaData.getString("lyricon_module_tags")?.let { listOf(it) } ?: emptyList()
        }
        return rawTags.filter { it.isNotBlank() }
    }

    /** 签名 SHA-256 是否命中任一受信任来源。 */
    private fun isCertified(packageInfo: PackageInfo): Boolean {
        val signatures = getSignatures(packageInfo) ?: return false
        return signatures.any { sig ->
            val sha256 = sha256(sig)
            TrustedSignatures.ALL_CERTIFIED.any { it.equals(sha256, ignoreCase = true) }
        }
    }

    private fun getSignatures(packageInfo: PackageInfo): List<Signature>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.let { si ->
                    if (si.hasMultipleSigners()) si.apkContentsSigners.toList()
                    else si.signingCertificateHistory.toList()
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures?.toList()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }
}
