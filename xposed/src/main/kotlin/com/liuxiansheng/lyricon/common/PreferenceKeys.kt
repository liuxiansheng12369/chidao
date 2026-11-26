/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.common

/**
 * 模块自身偏好文件的键。
 *
 * 注意：这里只放「进程内自用」的键；跟宿主 App 设置页共享的键在 [UIConstants] /
 * [ServiceConstants] 里。两者故意不合并 —— 一个是模块私有配置，一个是跨进程可见的。
 */
object PreferenceKeys {
    const val PREF_NAME = "com.liuxiansheng.lyricon_preferences"

    const val KEY_LOG_LEVEL = "key_log_level"

    /** 0 = 只打 I/W/E，1 = 连 D 一起打。 */
    const val DEFAULT_LOG_LEVEL = 0
}
