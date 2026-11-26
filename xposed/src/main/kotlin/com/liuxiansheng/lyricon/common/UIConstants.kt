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
 * UI 侧配置键与默认值。
 *
 * 这些键落在宿主 App 的设置页，模块进程读同一份 SharedPreferences 才能拿到用户选择。
 * 键名一旦发出去就不能改（老用户升级后设置会丢），所以这里全部写成字面量而不是拼接。
 */
object UIConstants {

    const val PREF_NAME = "com.liuxiansheng.lyricon_preferences"

    // ——— 核心开关 ———

    const val KEY_WORK_MODE = "key_work_mode"
    const val KEY_SETUP_COMPLETED = "key_setup_completed"
    const val KEY_THEME_MODE = "key_theme_mode"
    const val KEY_APP_LANGUAGE = "key_app_language"
    const val KEY_MONET_COLOR = "key_monet_color"
    const val KEY_PREDICTIVE_BACK_GESTURE = "key_predictive_back_gesture"
    const val KEY_FLOATING_NAV_BAR = "key_floating_nav_bar"
    const val KEY_EXCLUDE_FROM_RECENTS = "key_exclude_from_recents"
    const val KEY_ENABLE_BLUR = "key_enable_blur"
    const val KEY_LOG_LEVEL = "key_log_level"

    /** 设置页「打印日志」开关，默认关。正式版也靠它按需开日志，收完再关。 */
    const val KEY_DEBUG_LOGGING = "pref_debug_logging"

    const val KEY_LAST_SEEN_VERSION = "key_last_seen_version"

    // ——— 默认值 ———

    const val DEFAULT_WORK_MODE = 0
    const val DEFAULT_SETUP_COMPLETED = false
    const val DEFAULT_THEME_MODE = 0
    const val DEFAULT_APP_LANGUAGE = 0
    const val DEFAULT_MONET_COLOR = 0
    const val DEFAULT_PREDICTIVE_BACK_GESTURE = false
    const val DEFAULT_FLOATING_NAV_BAR = false
    const val DEFAULT_EXCLUDE_FROM_RECENTS = false
    const val DEFAULT_ENABLE_BLUR = true

    /** 0 = 一般日志（I/W/E），1 = 调试日志（D/I/W/E）。 */
    const val DEFAULT_LOG_LEVEL = 0
}
