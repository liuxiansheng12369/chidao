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
 * 通知 / 超级岛相关配置键、歌词源编号与默认值。
 *
 * 歌词源编号对外有语义（设置页下拉的序号），不能重排：
 * 自动 → 在线 → lyricInfo → LRC → 标题。
 */
object ServiceConstants {

    // ——— 通知与岛 ———

    const val KEY_NOTIFICATION_WHITELIST = "key_notification_whitelist_packages"
    const val KEY_NOTIFICATION_TYPE = "key_notification_type"
    const val KEY_NOTIFICATION_FOCUS_STYLE = "key_focus_notification_type"
    const val KEY_ISLAND_LEFT_ICON = "key_island_left_icon"
    const val KEY_ISLAND_LEFT_ICON_NORMAL = "key_island_left_icon_normal"
    const val KEY_ISLAND_LEFT_ICON_FOCUS = "key_island_left_icon_focus"
    const val KEY_NOTIFICATION_TITLE_STYLE = "key_normal_notification_title_style"
    const val KEY_NOTIFICATION_CLICK_ACTION = "key_notification_click_action"

    // ——— 通知页里独立的岛式项 ———

    const val KEY_NOTIFICATION_SHOW_PROGRESS = "key_notification_show_progress"
    const val KEY_NOTIFICATION_PROGRESS_COLOR = "key_notification_progress_color"
    const val KEY_NOTIFICATION_ALBUM = "key_notification_album"
    const val KEY_NOTIFICATION_ISLAND_DISABLE_LYRIC_SPLIT =
        "key_notification_island_disable_lyric_split"
    const val KEY_NOTIFICATION_ISLAND_LIMIT_WIDTH = "key_notification_island_limit_width"
    const val KEY_NOTIFICATION_ISLAND_MAX_WIDTH = "key_notification_island_max_width"
    const val KEY_NOTIFICATION_HIGHLIGHT_COLOR = "key_notification_island_highlight_color"
    const val KEY_NOTIFICATION_SONG_INFO_HIGHLIGHT_COLOR =
        "key_notification_song_info_highlight_color"
    const val KEY_BYPASS_FOCUS_NOTIFICATION_LIMIT = "key_bypass_focus_notification_limit"
    const val KEY_SERVICE_LYRIC_SOURCE = "key_service_lyric_source"
    const val KEY_NOTIFICATION_FOCUS_SHOW = "key_notification_focus_show"

    // ——— 歌词源编号 ———

    /** 按可用性自动挑。 */
    const val LYRIC_SOURCE_AUTO = 0

    /** 在线搜索。 */
    const val LYRIC_SOURCE_ONLINE = 1

    /** 读播放器 lyricInfo（逐字）。 */
    const val LYRIC_SOURCE_LYRIC_INFO = 2

    /** 读本地 LRC 文件。 */
    const val LYRIC_SOURCE_LRC = 3

    /** 用标题反查。 */
    const val LYRIC_SOURCE_TITLE = 4

    // ——— 默认值 ———

    const val DEFAULT_NOTIFICATION_TYPE = 0
    const val DEFAULT_NOTIFICATION_FOCUS_STYLE = 0
    const val DEFAULT_NOTIFICATION_FOCUS_SHOW = true

    /** 0 = 音符，1 = 圆角专辑，2 = 圆形专辑，3 = 不显示。 */
    const val DEFAULT_ISLAND_LEFT_ICON = 0

    const val DEFAULT_NOTIFICATION_TITLE_STYLE = 4
    const val DEFAULT_NOTIFICATION_CLICK_ACTION = 0

    const val DEFAULT_NOTIFICATION_SHOW_PROGRESS = true
    const val DEFAULT_NOTIFICATION_PROGRESS_COLOR = true
    const val DEFAULT_NOTIFICATION_ALBUM = true
    const val DEFAULT_NOTIFICATION_ISLAND_DISABLE_LYRIC_SPLIT = false
    const val DEFAULT_NOTIFICATION_ISLAND_LIMIT_WIDTH = false
    const val DEFAULT_NOTIFICATION_ISLAND_MAX_WIDTH = 720
    const val DEFAULT_NOTIFICATION_HIGHLIGHT_COLOR = false
    const val DEFAULT_NOTIFICATION_SONG_INFO_HIGHLIGHT_COLOR = false
    const val DEFAULT_BYPASS_FOCUS_NOTIFICATION_LIMIT = false
    const val DEFAULT_SERVICE_LYRIC_SOURCE = 0
}
