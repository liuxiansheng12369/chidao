/*
 * 本文件由 Lyricon V3 新增。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.utils

/**
 * 诊断日志统一出口。
 *
 * **为什么不直接用 HookLogger**：SystemUI 里 HookLogger 的 tag 被日志门禁静默，
 * logcat 一条都看不到，排查时等于没有。这里固定走 `android.util.Log`、打自己的 tag，
 * logcat 一定能看到。
 *
 * **但开关统一受「设置 → 调试 → 打印日志」控制**：
 * - 测试版（debug）：恒开，方便随手抓日志
 * - 正式版：默认关；用户在设置里打开后 1 秒内生效，不用重启状态栏
 *
 * 也就是说：探针能不能看到，由用户那个开关说了算，不再由代码写死。
 * 错误日志（Log.e / 关键异常 Log.w）不走这里，见各文件原样保留。
 */
internal object DiagnosticsLog {

    /**
     * 是否允许输出诊断。
     *
     * 两条任一成立就输出：
     * 1. 本模块是测试版（构建期字段 `DIAG_LOGGING_DEFAULT`，debug 恒 true、release 恒 false）；
     * 2. 用户打开了设置页「打印日志」（`HookLogger.isLoggingEnabled`）。
     *
     * 不用 `HookLogger.isLoggingEnabled` 单打独斗的原因：它内部的 debug 判定是在
     * `module` 赋值时用包管理器反射探的，在 SystemUI 进程里经常判不到（结果是 false），
     * 于是测试版也跟着静默 —— 探针就没意义了。
     */
    private fun allowed(): Boolean =
        com.liuxiansheng.lyricon.xposed.BuildConfig.DIAG_LOGGING_DEFAULT ||
                HookLogger.isLoggingEnabled

    fun d(tag: String, message: String) {
        if (!allowed()) return
        android.util.Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (!allowed()) return
        android.util.Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (!allowed()) return
        android.util.Log.w(tag, message)
    }
}
