/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.lyricon.provider

/**
 * 常量定义类，用于 Provider 与中心服务交互。
 */
object ProviderConstants {

    /** 默认歌词更新间隔 */
    const val DEFAULT_POSITION_UPDATE_INTERVAL: Long = 1000L / 24

    internal const val DEBUG: Boolean = false

    /** 注册提供者广播动作 */
    internal const val ACTION_REGISTER_PROVIDER: String =
        "io.github.proify.lyricon.lyric.bridge.REGISTER_PROVIDER"

    /** 中心服务启动完成广播动作 */
    internal const val ACTION_CENTRAL_BOOT_COMPLETED: String =
        "io.github.proify.lyricon.lyric.bridge.CENTRAL_BOOT_COMPLETED"

    internal const val EXTRA_BUNDLE: String = "bundle"
    internal const val EXTRA_BINDER: String = "binder"

    const val SYSTEM_UI_PACKAGE_NAME: String = "com.android.systemui"
}
