/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 *
 * LyricSourceModels：歌词源管理页数据模型。
 * 扫描系统内已安装的歌词 provider 插件（metaData lyricon_module 标记），
 * 展示其名称/版本/作者/描述/标签，并做签名认证标记。
 */

package com.liuxiansheng.lyricon.app.lyricsource

/**
 * 歌词源模块（一个已安装的歌词 provider 应用）。
 */
data class LyricSourceModule(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val author: String?,
    val description: String?,
    val category: String?,
    val isCertified: Boolean,
    val tags: List<String>,
    val homeUrl: String?,
    /** LSPosed 激活状态：false 表示 Xposed 模块未激活；null 表示无法检测（不展示激活提示）。 */
    val activation: Boolean? = null,
)

/**
 * 歌词源分类（未声明分类的归入"其他"，始终排最后）。
 */
data class LyricSourceCategory(
    val name: String,
    val items: List<LyricSourceModule>,
)

/**
 * 歌词源列表视图模式。
 */
object LyricSourceViewMode {
    const val SHORT = 0
    const val FULL = 1
}

/**
 * 歌词源扫描诊断快照（直接上屏，供没有电脑、抓不了 logcat 的机器自查）。
 */
data class LyricSourceDiagnostics(
    /** getInstalledPackages 实际能看见多少个包（0 或极小说明包可见性被收紧）。 */
    val visiblePackages: Int = 0,
    /** 认出多少个歌词源。 */
    val recognized: Int = 0,
    /** 名字像歌词源、但没被识别出来的包名。 */
    val suspectMissed: List<String> = emptyList(),
    /** QUERY_ALL_PACKAGES 是否已授予（null = 查询失败）。 */
    val queryAllGranted: Boolean? = null,
    val apiLevel: Int = 0,
    val brand: String = "",
    /** LSPosed 激活状态是否读到（false 只影响"未激活"角标，不影响列表）。 */
    val lspdStateOk: Boolean = false,
    /** su 读取 LSPosed 数据库是否超时（超时会被强行中断，不再拖住页面）。 */
    val lspdTimedOut: Boolean = false,
    /**
     * LSPosed 里已注册的模块包名（剔除本 App 自身）。
     *
     * 歌词 provider 一定是 LSPosed 模块，所以这份列表能直接回答"那台机器上到底装了什么"：
     * 列表里有没有歌词源，一眼就能看出来，不依赖扫安装包、也不依赖包名枚举。
     */
    val lspdModules: List<String> = emptyList(),
    /**
     * 可见包里的第三方包（剔除 android / com.android / com.coloros / com.oplus 等系统前缀与本 App）。
     *
     * 用来兜"认不出来的歌词源"：包名不叫 lyric 也能被看见，避免只盯着已知包名看不到真实安装情况。
     */
    val thirdPartyPackages: List<String> = emptyList(),
    /** root 直读（pm list packages -u）拿到的包总数；0 表示 su 那一步没成功。 */
    val rootPackages: Int = 0,
    /**
     * 系统里装了、但词岛通过 PackageManager 看不见的歌词源包。
     *
     * 出现非空就说明：有别的模块 hook 了包查询，把歌词源对词岛隐藏了。
     * 这些包会照样列进列表（标"被隐藏"），但拿不到图标和版本。
     */
    val hiddenPackages: List<String> = emptyList(),
    /** 扫描整体异常（正常为 null）。 */
    val error: String? = null,
)

/**
 * 一次扫描的完整结果：分类列表 + 诊断快照。
 */
data class LyricSourceScanReport(
    val categories: List<LyricSourceCategory> = emptyList(),
    val diagnostics: LyricSourceDiagnostics = LyricSourceDiagnostics(),
)
