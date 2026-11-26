/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 */

package com.liuxiansheng.lyricon.app.live

import android.content.Context

/**
 * 模块在不在、要不要重启状态栏 —— 主页「模块状态」卡的三态判定。
 *
 * 两个事实来源：
 *  1. 模块每 3 秒推一次心跳帧（SystemUI 侧 LiveStatePusher → App 侧 LiveStateProvider），
 *     帧里带 SystemUI 进程的启动时刻；6.5 秒内收到过就算活着（模块一停，几秒内就翻红）。
 *  2. XposedService 绑定状态（LSPosed 注入成功后建立的通道），作为兜底判据。
 *
 * 「需要重启」= 模块活着，但 App 的安装时间晚于 SystemUI 进程的启动时间 ——
 * 说明状态栏里跑的还是上一版代码，重装 APK 后没重启状态栏。
 */
object ModuleHealth {

    enum class State { ACTIVE, NEED_RESTART, INACTIVE }

    data class Info(
        val state: State,
        val appVersion: String,
        val sysuiStartedAt: Long,
        val heartbeatAt: Long,
    ) {
        /** 卡片主文案 */
        val title: String
            get() = when (state) {
                State.ACTIVE -> "已激活"
                State.NEED_RESTART -> "需要重启状态栏"
                State.INACTIVE -> "未激活"
            }

        /** 徽标等窄位置用的短文案 */
        val shortTitle: String
            get() = when (state) {
                State.ACTIVE -> "已激活"
                State.NEED_RESTART -> "需重启"
                State.INACTIVE -> "未激活"
            }

        /** 副行说明 */
        val detail: String
            get() = when (state) {
                State.ACTIVE -> "已连上状态栏"
                State.NEED_RESTART -> "App 已更新，状态栏里仍是旧版本"
                State.INACTIVE -> "模块没在运行，检查 LSPosed 里是否勾选"
            }

        val needsRestart: Boolean get() = state == State.NEED_RESTART
    }

    /** 心跳有效的窗口：3 秒一拍 + 一拍半的容差 */
    private const val FRESH_MS = 6_500L

    fun read(context: Context, serviceBound: Boolean): Info {
        val prefs = LiveStateProvider.livePrefs(context)
        val hbAt = prefs.getLong(LiveStateProvider.KEY_HB_AT, 0L)
        val sysuiStart = prefs.getLong(LiveStateProvider.KEY_SYSUI_START, 0L)
        val fresh = hbAt > 0L && System.currentTimeMillis() - hbAt < FRESH_MS
        val alive = fresh || serviceBound

        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "-"
        val updatedAt = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrNull() ?: 0L

        val state = when {
            // 收到过心跳但断了：状态栏里的模块进程已经不干活了
            hbAt > 0L && !fresh -> State.INACTIVE
            // 从来没心跳、也没绑定：模块没跑
            !alive -> State.INACTIVE
            // 有绑定但一次心跳都没收到：状态栏里跑的是没有心跳的旧模块代码，重启后才有
            hbAt <= 0L -> State.NEED_RESTART
            sysuiStart > 0L && updatedAt > sysuiStart -> State.NEED_RESTART
            else -> State.ACTIVE
        }
        return Info(state, version, sysuiStart, hbAt)
    }
}
