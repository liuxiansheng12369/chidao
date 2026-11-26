/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.common

/**
 * 模块与宿主共享的全部配置键、枚举与默认值。
 *
 * 这里是「数据字典」，不是逻辑：键名必须和设置页写进 SharedPreferences 的字符串逐字一致，
 * 枚举编号必须和设置页下拉索引一致，默认值必须对得上用户的实际期望。
 * 所以本文件只按用途分区、按依赖顺序摆放 —— 值一个字都不动。
 */
object RootConstants {


    // ==================== 配置键 ====================

    const val KEY_HOOK_ENABLE_SUPER_ISLAND = "key_hook_enable_super_island"

    const val KEY_HOOK_ENABLE_STATUS_BAR_LYRIC = "key_hook_enable_status_bar_lyric"

    const val KEY_HOOK_STATUS_BAR_LYRIC_INSERTION_ORDER = "key_hook_status_bar_lyric_insertion_order"

    /** 状态栏歌词锚点 view id 名。对齐上游 lyricon（默认 clock），找不到时退回状态栏根插入。 */
    const val KEY_HOOK_STATUS_BAR_LYRIC_ANCHOR = "key_hook_status_bar_lyric_anchor"

    /**
     * 1.5.134：挂靠锚点选择。0 = 状态栏根（现有行为，整条状态栏居中叠加，不参与系统图标排布）；
     * 1 = 时钟（插到时钟旁，跟随插入位置设置）。
     * 小米机型默认走根插入，只有这里显式选 1 才改走时钟锚点。
     */
    const val KEY_HOOK_STATUS_BAR_LYRIC_ANCHOR_TARGET = "key_hook_status_bar_lyric_anchor_target"

    const val ANCHOR_TARGET_ROOT = 0
    const val ANCHOR_TARGET_CLOCK = 1

    const val KEY_HOOK_STATUS_BAR_LYRIC_ICON_ENABLED = "key_hook_status_bar_lyric_icon_enabled"

    const val KEY_HOOK_STATUS_BAR_LYRIC_ICON_STYLE = "key_hook_status_bar_lyric_icon_style"

    const val KEY_HOOK_STATUS_BAR_LYRIC_ICON_ORDER = "key_hook_status_bar_lyric_icon_order"

    const val KEY_HOOK_STATUS_BAR_LYRIC_ICON_SIZE_DP = "key_hook_status_bar_lyric_icon_size_dp"

    const val KEY_HOOK_STATUS_BAR_LYRIC_ICON_SPACING_DP = "key_hook_status_bar_lyric_icon_spacing_dp"

    const val KEY_HOOK_STATUS_BAR_LYRIC_PORTRAIT_DYNAMIC_MAX_WIDTH = "key_hook_status_bar_lyric_portrait_dynamic_max_width"

    const val KEY_HOOK_STATUS_BAR_LYRIC_LANDSCAPE_DYNAMIC_MAX_WIDTH = "key_hook_status_bar_lyric_landscape_dynamic_max_width"

    const val KEY_HOOK_STATUS_BAR_LYRIC_PADDING_LEFT_DP = "key_hook_status_bar_lyric_padding_left_dp"

    const val KEY_HOOK_STATUS_BAR_LYRIC_PADDING_RIGHT_DP = "key_hook_status_bar_lyric_padding_right_dp"

    const val KEY_HOOK_STATUS_BAR_LYRIC_CLOCK_HIDE_BEHAVIOR = "key_hook_status_bar_lyric_clock_hide_behavior"

    const val KEY_HOOK_STATUS_BAR_LYRIC_NOTIFICATION_ICON_HIDE_BEHAVIOR = "key_hook_status_bar_lyric_notification_icon_hide_behavior"

    const val KEY_HOOK_STATUS_BAR_LYRIC_ISLAND_HIDE_BEHAVIOR = "key_hook_status_bar_lyric_island_hide_behavior"

    const val KEY_HOOK_STATUS_BAR_LYRIC_CONTENT_MODE = "key_hook_status_bar_lyric_content_mode"

    const val KEY_HOOK_STATUS_BAR_LYRIC_ADJUST_WIDTH_FOR_SUPER_ISLAND = "key_hook_status_bar_lyric_adjust_width_for_super_island"

    const val KEY_HOOK_STATUS_BAR_LYRIC_HIDE_ON_LOCK_SCREEN = "key_hook_status_bar_lyric_hide_on_lock_screen"

    const val KEY_HOOK_STATUS_BAR_LYRIC_DOUBLE_TAP_ACTION = "key_hook_status_bar_lyric_double_tap_action"

    /** 单击动作：默认无操作。配了动作立即执行，双击让位（单击设回无操作后双击恢复）。 */
    const val KEY_HOOK_STATUS_BAR_LYRIC_SINGLE_TAP_ACTION = "key_hook_status_bar_lyric_single_tap_action"

    /** 控制面板样式：0=经典卡片，1=下拉媒体卡，2=词岛浅色卡。 */
    const val KEY_HOOK_STATUS_BAR_LYRIC_CONTROL_PANEL_STYLE =
        "key_hook_status_bar_lyric_control_panel_style"

    const val CONTROL_PANEL_STYLE_CLASSIC = 0
    const val CONTROL_PANEL_STYLE_MEDIA = 1
    const val CONTROL_PANEL_STYLE_LIGHT = 2
    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_CONTROL_PANEL_STYLE = CONTROL_PANEL_STYLE_CLASSIC

    const val KEY_HOOK_STATUS_BAR_LYRIC_LONG_PRESS_ACTION = "key_hook_status_bar_lyric_long_press_action"

    const val KEY_HOOK_STATUS_BAR_LYRIC_SWIPE_LEFT_ACTION = "key_hook_status_bar_lyric_swipe_left_action"

    /** ColorOS 流体云顶层触摸拦截开关：默认开，出现状态栏触摸异常可关（改动需重启状态栏）。 */
    const val KEY_HOOK_STATUS_BAR_LYRIC_ROOT_TOUCH_INTERCEPT =
        "key_hook_status_bar_lyric_root_touch_intercept"

    const val KEY_HOOK_STATUS_BAR_LYRIC_SWIPE_RIGHT_ACTION = "key_hook_status_bar_lyric_swipe_right_action"

    const val KEY_HOOK_STATUS_BAR_LYRIC_GESTURE_HAPTIC_FEEDBACK = "key_hook_status_bar_lyric_gesture_haptic_feedback"

    const val KEY_HOOK_STATUS_BAR_LYRIC_TEXT_SIZE = "key_hook_status_bar_lyric_text_size"

    const val KEY_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR = "key_hook_status_bar_lyric_text_color"

    // ——— 状态栏歌词播放进度条 ———

    const val KEY_LYRIC_STYLE_BASE_SHOW_PROGRESS_BAR = "lyric_style_base_show_progress_bar"

    /** 进度条高度，单位 dp（默认 1dp，即一条细线）。 */
    const val KEY_LYRIC_STYLE_BASE_PROGRESS_BAR_HEIGHT = "lyric_style_base_progress_bar_height"

    /** 进度条纵向偏移，单位 dp，往上为正。 */
    const val KEY_LYRIC_STYLE_BASE_PROGRESS_BAR_OFFSET_Y = "lyric_style_base_progress_bar_offset_y"

    /** 0=跟随歌词色 1=彩虹渐变。 */
    const val KEY_LYRIC_STYLE_BASE_PROGRESS_BAR_COLOR_MODE = "lyric_style_base_progress_bar_color_mode"

    /** 0=歌词下方 1=歌词上方。 */
    const val KEY_LYRIC_STYLE_BASE_PROGRESS_BAR_POSITION = "lyric_style_base_progress_bar_position"

    const val PROGRESS_BAR_COLOR_FOLLOW = 0
    const val PROGRESS_BAR_COLOR_RAINBOW = 1
    const val PROGRESS_BAR_POSITION_BELOW = 0
    const val PROGRESS_BAR_POSITION_ABOVE = 1

    const val DEFAULT_SHOW_PROGRESS_BAR = true
    const val DEFAULT_PROGRESS_BAR_HEIGHT_DP = 1f
    const val DEFAULT_PROGRESS_BAR_OFFSET_Y_DP = 0f
    const val DEFAULT_PROGRESS_BAR_COLOR_MODE = PROGRESS_BAR_COLOR_RAINBOW
    const val DEFAULT_PROGRESS_BAR_POSITION = PROGRESS_BAR_POSITION_BELOW
    const val MIN_PROGRESS_BAR_HEIGHT_DP = 1f
    const val MAX_PROGRESS_BAR_HEIGHT_DP = 8f
    const val MIN_PROGRESS_BAR_OFFSET_Y_DP = -10f
    const val MAX_PROGRESS_BAR_OFFSET_Y_DP = 10f

    const val KEY_HOOK_STATUS_BAR_LYRIC_RAINBOW_ENABLED = "key_hook_status_bar_lyric_rainbow_enabled"

    const val KEY_HOOK_STATUS_BAR_LYRIC_COVER_COLOR_ENABLED = "key_hook_status_bar_lyric_cover_color_enabled"

    const val KEY_HOOK_STATUS_BAR_LYRIC_INVERT_ENABLED = "key_hook_status_bar_lyric_invert_enabled"

    const val KEY_HOOK_STATUS_BAR_LYRIC_FONT_FAMILY = "key_hook_status_bar_lyric_font_family"

    const val KEY_HOOK_STATUS_BAR_LYRIC_BOLD = "key_hook_status_bar_lyric_bold"

    const val KEY_HOOK_STATUS_BAR_LYRIC_FONT_WEIGHT = "key_hook_status_bar_lyric_font_weight"

    const val KEY_HOOK_LYRIC_MODE = "key_hook_lyric_mode"

    const val KEY_HOOK_LYRIC_SOURCE = "key_hook_lyric_source"

    const val KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX = "key_hook_lyricon_provider_delay_"

    /** 副行显示整首剩余时间。 */
    const val KEY_HOOK_SHOW_REMAINING_TIME = "key_hook_show_remaining_time"

    /** 歌词时间偏移（毫秒）：正数让歌词提前，负数让歌词延后。 */
    const val KEY_HOOK_LYRIC_TIME_OFFSET_MS = "key_hook_lyric_time_offset_ms"

    /** 整首剩余时间的显示位置：0 主行下方、1 主行上方、2 左右两边。 */
    const val KEY_HOOK_REMAIN_POSITION = "key_hook_remain_position"

    const val KEY_HOOK_ISLAND_ALBUM_COVER_STYLE = "key_hook_island_album_cover_style"

    const val KEY_HOOK_ISLAND_CONTENT_LEFT = "key_hook_island_content_left"

    const val KEY_HOOK_ISLAND_CONTENT_RIGHT = "key_hook_island_content_right"

    const val KEY_ISLAND_LEFT_CONTENT_MODE = "island_left_content_mode"

    const val KEY_ISLAND_RIGHT_CONTENT_MODE = "island_right_content_mode"

    const val KEY_ISLAND_LEFT_ENABLED = "island_left_enabled"

    const val KEY_ISLAND_RIGHT_ENABLED = "island_right_enabled"

    const val KEY_HOOK_ISLAND_MUSIC_INFO_FIRST_LINE =  "key_hook_island_music_info_first_line"

    const val KEY_HOOK_ISLAND_MUSIC_INFO_SECOND_LINE =  "key_hook_island_music_info_second_line"

    const val KEY_HOOK_ISLAND_LEFT_PADDING_LEFT = "key_hook_island_left_padding_left"

    const val KEY_HOOK_ISLAND_LEFT_PADDING_RIGHT = "key_hook_island_left_padding_right"

    const val KEY_HOOK_ISLAND_RIGHT_PADDING_LEFT = "key_hook_island_right_padding_left"

    const val KEY_HOOK_ISLAND_RIGHT_PADDING_RIGHT = "key_hook_island_right_padding_right"

    const val KEY_HOOK_ISLAND_WIDTH_MODE = "key_hook_island_width_mode"

    const val KEY_HOOK_ISLAND_DYNAMIC_MIN_WIDTH = "key_hook_island_dynamic_min_width"

    const val KEY_HOOK_ISLAND_DYNAMIC_MAX_WIDTH = "key_hook_island_dynamic_max_width"

    const val KEY_HOOK_ISLAND_DYNAMIC_WIDTH_BASIS = "key_hook_island_dynamic_width_basis"

    const val KEY_HOOK_ISLAND_DISABLE_WIDTH_LIMIT = "key_hook_island_disable_width_limit"

    const val KEY_ISLAND_SPECTRUM_LINK_WIDTH = "island_spectrum_link_width_enabled"

    const val KEY_ISLAND_AUTO_HIDE_COVER_SPECTRUM = "island_auto_hide_cover_spectrum"

    const val KEY_ISLAND_AUTO_HIDE_THRESHOLD = "island_auto_hide_threshold"

    const val KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE = "key_hook_island_behavior_after_pause"

    const val KEY_HOOK_ISLAND_LONG_PRESS_BEHAVIOR = "key_hook_island_long_press_behavior"

    const val KEY_HOOK_REMOVE_FOCUS_WHITELIST = "key_hook_remove_focus_whitelist"

    const val KEY_HOOK_REMOVE_ISLAND_WHITELIST = "key_hook_remove_island_whitelist"

    // ——— 超级岛歌词应用过滤（按包名决定谁能上岛出歌词） ———

    /** 0 = 关闭（所有应用都显示歌词）；1 = 白名单（仅列表内显示）；2 = 黑名单（列表内不显示）。 */
    const val KEY_HOOK_ISLAND_LYRIC_APP_FILTER_MODE = "key_hook_island_lyric_app_filter_mode"

    /** 白名单包名，多个用中英文逗号/分号分隔。空列表时回落内置常见音乐清单。 */
    const val KEY_HOOK_ISLAND_LYRIC_APP_FILTER_WHITE = "key_hook_island_lyric_app_filter_white"

    /** 黑名单包名，多个用中英文逗号/分号分隔。默认空 = 不屏蔽任何应用。 */
    const val KEY_HOOK_ISLAND_LYRIC_APP_FILTER_BLACK = "key_hook_island_lyric_app_filter_black"

    /**
     * 被过滤的应用是否连超级岛都不生成。
     * true = 这些应用播媒体时系统媒体岛也不建（最干净）；
     * false = 只是不出我们的歌词，系统原生媒体岛照旧（默认）。
     */
    const val KEY_HOOK_ISLAND_LYRIC_APP_FILTER_NO_ISLAND = "key_hook_island_lyric_app_filter_no_island"

    const val KEY_HOOK_TEXT_SIZE = "key_hook_text_size"

    const val KEY_HOOK_TEXT_SIZE_RATIO = "key_hook_text_size_ratio"

    const val KEY_HOOK_FONT_WEIGHT = "key_hook_font_weight"

    const val KEY_HOOK_FONT_ITALIC = "key_hook_font_italic"

    const val KEY_HOOK_GRADIENT_PROGRESS = "key_hook_gradient_progress"

    const val KEY_HOOK_CENTER_LYRIC = "key_hook_center_lyric"

    const val KEY_HOOK_CENTER_MUSIC_INFO = "key_hook_center_music_info"

    const val KEY_HOOK_RIGHT_LYRIC = "key_hook_right_lyric"

    const val KEY_HOOK_PLACEHOLDER_FORMAT = "key_hook_placeholder_format"

    const val KEY_HOOK_ISLAND_MUSIC_WAVE_STYLE = "key_hook_island_music_wave_style"

    const val KEY_HOOK_ANIM_ENABLE = "key_hook_anim_enable"

    const val KEY_HOOK_ANIM_ID = "key_hook_anim_id"

    const val KEY_HOOK_SYLLABLE_RELATIVE = "key_hook_syllable_relative"

    const val KEY_HOOK_SYLLABLE_HIGHLIGHT = "key_hook_syllable_highlight"

    const val KEY_HOOK_SYLLABLE_LINE_DISPLAY = "key_hook_syllable_line_display"

    /** 无逐字数据时是否按时长模拟逐字推进；关=整行直接全亮。 */
    const val KEY_HOOK_SIMULATE_UNTIMED_WORD = "key_hook_simulate_untimed_word"

    const val KEY_SHOW_TRANSLATION = "v3_show_translation"

    const val KEY_HOOK_DISABLE_TRANSLATION = "key_hook_disable_translation"

    const val KEY_HOOK_TRANSLATION_ONLY = "key_hook_translation_only"

    const val KEY_HOOK_SWAP_TRANSLATION = "key_hook_swap_translation"

    const val KEY_HOOK_NEXT_LYRIC_LINE = "key_hook_next_lyric_line"

    const val KEY_HOOK_AUTO_SWITCH_TRANSLATION = "key_hook_auto_switch_translation"

    const val KEY_HOOK_LINE_SWITCH_ANIM = "key_hook_line_switch_anim"

    /** 尾段超时（歌已唱完、最后一行还挂着时）的行为，语义对齐 HyperLyric。 */
    const val KEY_HOOK_TAIL_TIMEOUT_BEHAVIOR = "key_hook_tail_timeout_behavior"

    /** 尾段超时延迟秒数：从最后一行结束时刻起算，超过该秒数才判定进入尾段。 */
    const val KEY_HOOK_TAIL_TIMEOUT_DELAY_SECONDS = "key_hook_tail_timeout_delay_seconds"

    const val KEY_HOOK_STATUS_BAR_LYRIC_SWITCH_ANIM = "key_hook_status_bar_lyric_switch_anim"

    const val KEY_HOOK_STATUS_BAR_LYRIC_EFFECT = "key_hook_status_bar_lyric_effect"

    const val KEY_HOOK_TEXT_COLOR_STYLE = "key_hook_text_color_style"

    const val KEY_HOOK_TEXT_COLOR = "key_hook_text_color"

    const val KEY_HOOK_RAINBOW_ENABLED = "key_hook_rainbow_enabled"

    const val KEY_HOOK_ISLAND_PROGRESS_GLOW = "key_hook_island_progress_glow"

    const val KEY_HOOK_ISLAND_PROGRESS_STYLE = "key_hook_island_progress_style"

    const val KEY_HOOK_ISLAND_PROGRESS_GRADIENT = "key_hook_island_progress_gradient"

    const val KEY_HOOK_ISLAND_PROGRESS_TRACK = "key_hook_island_progress_track"

    const val KEY_HOOK_ISLAND_PARTICLE_MOTION = "key_hook_island_particle_motion"

    const val KEY_HOOK_ISLAND_RING_GLOW = "key_hook_island_ring_glow"

    /** 环绕光圈颜色模式：0=跟随封面 1=固定色（16 色预设） 2=自定义色 3=彩虹（16 色环流动） */
    const val KEY_HOOK_ISLAND_RING_GLOW_MODE = "key_hook_island_ring_glow_mode"

    const val KEY_HOOK_ISLAND_RING_GLOW_COLOR = "key_hook_island_ring_glow_color"

    const val KEY_HOOK_ISLAND_RING_GLOW_CUSTOM_COLOR = "key_hook_island_ring_glow_custom_color"

    const val RING_GLOW_MODE_FOLLOW_COVER = 0

    const val RING_GLOW_MODE_FIXED = 1

    const val RING_GLOW_MODE_CUSTOM = 2

    /** 彩虹：沿岛一圈铺 16 色环，色带随时间缓慢绕岛转。 */
    const val RING_GLOW_MODE_RAINBOW = 3

    /** 光圈默认色：原来写死的冷蓝白（外层 140,205,255）。 */
    const val DEFAULT_RING_GLOW_COLOR = 0xFF8CCDFF.toInt()

    const val KEY_HOOK_ISLAND_OUTER_GLOW_ENABLED = "key_hook_island_outer_glow_enabled"

    const val KEY_HOOK_ISLAND_OUTER_GLOW_OUTER_ENABLED = "key_hook_island_outer_glow_outer_enabled"

    const val KEY_HOOK_ISLAND_OUTER_GLOW_INNER_ENABLED = "key_hook_island_outer_glow_inner_enabled"

    const val KEY_HOOK_ISLAND_OUTER_GLOW_OUTER_MODE = "key_hook_island_outer_glow_outer_mode"

    const val KEY_HOOK_ISLAND_OUTER_GLOW_OUTER_COLOR = "key_hook_island_outer_glow_outer_color"

    const val KEY_HOOK_ISLAND_OUTER_GLOW_INNER_MODE = "key_hook_island_outer_glow_inner_mode"

    const val KEY_HOOK_ISLAND_OUTER_GLOW_INNER_COLOR = "key_hook_island_outer_glow_inner_color"

    const val KEY_HOOK_ISLAND_OUTER_GLOW_OUTER_CUSTOM_COLOR =
        "key_hook_island_outer_glow_outer_custom_color"

    const val KEY_HOOK_CUSTOM_FONT_PATH = "key_hook_custom_font_path"

    // ——— 歌词文本加工（1.3.55）———

    /** 简繁转换模式：0 关闭 / 1 转简体 / 2 转繁体。 */
    const val KEY_HOOK_CHINESE_CONVERSION_MODE = "key_hook_chinese_conversion_mode"

    /** 屏蔽词正则：命中整行则整行丢弃。空串 = 不启用。 */
    const val KEY_HOOK_BLOCKED_WORDS_REGEX = "key_hook_blocked_words_regex"

    /**
     * 标题去括号：把歌名 / 艺人 / 专辑里的「（…）」「(…)」连同括号一起隐藏。
     * 不需要用户写正则，开一个开关即可。
     */
    const val KEY_HOOK_HIDE_BRACKET_CONTENT = "key_hook_hide_bracket_content"

    const val CHINESE_CONVERSION_OFF = 0
    const val CHINESE_CONVERSION_SIMPLIFIED = 1
    const val CHINESE_CONVERSION_TRADITIONAL = 2

    /** 默认不转换，保持与历史版本一致的观感。 */
    const val DEFAULT_CHINESE_CONVERSION_MODE = CHINESE_CONVERSION_OFF

    const val DEFAULT_BLOCKED_WORDS_REGEX = ""

    const val KEY_HOOK_NARROW_LATIN_FONT = "key_hook_narrow_latin_font"

    const val KEY_HOOK_WORD_MOTION_ENABLED = "key_hook_word_motion_enabled"

    const val KEY_ISLAND_TRANSPARENT_ENABLED = "island_transparent_enabled"

    const val KEY_HOOK_ISLAND_LYRIC_MIRROR = "island_lyric_mirror"

    const val KEY_HOOK_LYRIC_EFFECT = "island_lyric_effect"

    /** 逐词强调（独立开关，不在歌词动画效果下拉里）：唱到的词缩放/展开/上浮并发光 */
    const val KEY_HOOK_LYRIC_EMPHASIS = "island_lyric_emphasis"

    const val KEY_ISLAND_MATERIAL_ENABLED = "pref_island_material_enabled"

    const val KEY_ISLAND_MATERIAL_TYPE = "pref_island_material_type"

    const val KEY_ISLAND_MATERIAL_BIG = "pref_island_material_big_config"

    const val KEY_ISLAND_MATERIAL_SMALL = "pref_island_material_small_config"

    const val KEY_ISLAND_MATERIAL_EXPAND = "pref_island_material_expand_config"

    const val KEY_ISLAND_MATERIAL_SMALL_FOLLOW_BIG = "pref_island_material_small_follow_big"

    const val KEY_ISLAND_MATERIAL_EXPAND_FOLLOW_BIG = "pref_island_material_expand_follow_big"

    const val KEY_ISLAND_BLUR_SMALL_ENABLED = "pref_island_blur_small_enabled"

    const val KEY_ISLAND_BLUR_SMALL_RADIUS = "pref_island_blur_small_radius"

    const val KEY_ISLAND_BLUR_SMALL_COLOR = "pref_island_blur_small_color"

    const val KEY_ISLAND_BLUR_BIG_ENABLED = "pref_island_blur_big_enabled"

    const val KEY_ISLAND_BLUR_BIG_RADIUS = "pref_island_blur_big_radius"

    const val KEY_ISLAND_BLUR_BIG_COLOR = "pref_island_blur_big_color"

    const val KEY_ISLAND_BLUR_EXPAND_ENABLED = "pref_island_blur_expand_enabled"

    const val KEY_ISLAND_BLUR_EXPAND_RADIUS = "pref_island_blur_expand_radius"

    const val KEY_ISLAND_BLUR_EXPAND_COLOR = "pref_island_blur_expand_color"

    const val KEY_ISLAND_BG_BIG = "pref_island_bg_big_path"

    const val KEY_ISLAND_BG_SMALL = "pref_island_bg_small_path"

    const val KEY_ISLAND_BG_EXPAND = "pref_island_bg_expand_path"

    const val KEY_ISLAND_GLASS_ENABLED = "pref_island_glass_enabled"

    const val KEY_ISLAND_GLASS_BIG_ENABLED = "pref_island_glass_big_enabled"

    const val KEY_ISLAND_GLASS_SMALL_ENABLED = "pref_island_glass_small_enabled"

    const val KEY_ISLAND_GLASS_EXPAND_ENABLED = "pref_island_glass_expand_enabled"

    const val KEY_ISLAND_REFRACTION_BIG_ENABLED = "pref_island_refraction_big_enabled"

    const val KEY_ISLAND_REFRACTION_SMALL_ENABLED = "pref_island_refraction_small_enabled"

    const val KEY_ISLAND_REFRACTION_EXPAND_ENABLED = "pref_island_refraction_expand_enabled"

    const val KEY_ISLAND_GLASS_EDGE_WIDTH = "pref_island_glass_edge_width"

    const val KEY_ISLAND_GLASS_REFRACTION = "pref_island_glass_refraction"

    const val KEY_ISLAND_GLASS_HIGHLIGHT = "pref_island_glass_highlight"

    const val KEY_ISLAND_GLASS_SHADOW = "pref_island_glass_shadow"

    const val KEY_ISLAND_GLASS_LIGHT_DIRECTION = "pref_island_glass_light_direction"

    const val KEY_ISLAND_GLASS_DISPERSION = "pref_island_glass_dispersion"

    const val KEY_ISLAND_GLASS_CAPTURE_FPS = "pref_island_glass_capture_fps"

    const val KEY_ISLAND_GLASS_CAPTURE_QUALITY = "pref_island_glass_capture_quality"

    const val KEY_ISLAND_GLASS_HDR_HIGHLIGHT = "pref_island_glass_hdr_highlight"

    const val KEY_ISLAND_GLASS_GYROSCOPE = "pref_island_glass_gyroscope"


    // ==================== 默认值 ====================

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_CONTENT_MODE = 7

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_INVERT_ENABLED = false

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_BOLD = true

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_ENABLED = false

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_SIZE_DP = 16

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_SPACING_DP = 3

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_PORTRAIT_DYNAMIC_MAX_WIDTH = 150

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_LANDSCAPE_DYNAMIC_MAX_WIDTH = 200

    const val DEFAULT_STATUS_BAR_LYRIC_TEXT_SIZE_SP = 13

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR = 0xFFFFFFFF.toInt()

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_RAINBOW_ENABLED = true

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_COVER_COLOR_ENABLED = false

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_PADDING_DP = 0f

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_ADJUST_WIDTH_FOR_SUPER_ISLAND = true

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_HIDE_ON_LOCK_SCREEN = true

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_GESTURE_HAPTIC_FEEDBACK = true

    const val DEFAULT_HOOK_LYRIC_SOURCE = "lyricon"

    const val DEFAULT_ISLAND_SPECTRUM_LINK_WIDTH = true

    const val DEFAULT_ISLAND_AUTO_HIDE_COVER_SPECTRUM = true

    const val DEFAULT_ISLAND_AUTO_HIDE_THRESHOLD = 12

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_EFFECT = 0

    const val DEFAULT_HOOK_ISLAND_PROGRESS_TRACK = true

    const val DEFAULT_HOOK_ISLAND_OUTER_GLOW_ENABLED = false

    const val DEFAULT_HOOK_ISLAND_OUTER_GLOW_OUTER_ENABLED = true

    const val DEFAULT_HOOK_ISLAND_OUTER_GLOW_INNER_ENABLED = true

    const val DEFAULT_HOOK_ISLAND_OUTER_GLOW_OUTER_COLOR = 0xFF4285F4.toInt() // Google 蓝

    const val DEFAULT_HOOK_ISLAND_OUTER_GLOW_INNER_COLOR = 0xFF4285F4.toInt() // Google 蓝

    /** 光效自定义色默认值（自定义档未选过颜色时用它，比固定色更中性）。 */
    const val DEFAULT_HOOK_ISLAND_OUTER_GLOW_CUSTOM_COLOR = 0xFFFFFFFF.toInt() // 纯白

    const val DEFAULT_HOOK_LYRIC_MODE = 0

    const val DEFAULT_HOOK_LYRICON_PROVIDER_DELAY = 0

    const val DEFAULT_HOOK_SHOW_REMAINING_TIME = false

    const val DEFAULT_HOOK_LYRIC_TIME_OFFSET_MS = 0

    /** 剩余时间显示位置：主行下方（默认）。 */
    const val REMAIN_POS_BELOW = 0

    /** 剩余时间显示位置：主行上方。 */
    const val REMAIN_POS_ABOVE = 1

    /** 剩余时间显示位置：左右两边（左整首时长、右剩余，仅分离歌词模式生效）。 */
    const val REMAIN_POS_BOTH_SIDES = 2

    /** 剩余时间显示位置：歌词同一行的左右两端（左总时长、右剩余，会把岛宽撑开）。 */
    const val REMAIN_POS_LYRIC_ENDS = 3

    const val DEFAULT_HOOK_REMAIN_POSITION = REMAIN_POS_BELOW

    /** 「歌词左右两端」时，给两端时间额外放宽的岛宽（dp），避免时间被挤没。 */
    const val REMAIN_ENDS_EXTRA_WIDTH_DP = 40

    const val DEFAULT_HOOK_ENABLE_SUPER_ISLAND = false

    const val DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND = false

    const val DEFAULT_ISLAND_TRANSPARENT_ENABLED = false

    // ★ 1.4.2：与设置页 PrefSwitch(default = true) 对齐。
    //   原来是 false，导致「设置页显示已开启、模块侧按 false 不挂 hook」——
    //   两项白名单功能形同未生效。默认改 true，两端一致。
    const val DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST = true

    const val DEFAULT_HOOK_REMOVE_ISLAND_WHITELIST = true

    const val DEFAULT_HOOK_ISLAND_MUSIC_INFO_FIRST_LINE = "title"

    const val DEFAULT_HOOK_ISLAND_MUSIC_INFO_SECOND_LINE = "artist"

    const val DEFAULT_HOOK_ISLAND_LEFT_PADDING_LEFT = 2

    const val DEFAULT_HOOK_ISLAND_LEFT_PADDING_RIGHT = 0

    const val DEFAULT_HOOK_ISLAND_RIGHT_PADDING_LEFT = 0

    const val DEFAULT_HOOK_ISLAND_RIGHT_PADDING_RIGHT = 0

    const val DEFAULT_HOOK_ISLAND_DYNAMIC_MIN_WIDTH = 22

    const val DEFAULT_HOOK_ISLAND_DYNAMIC_MAX_WIDTH = 130

    const val DEFAULT_HOOK_ISLAND_DISABLE_WIDTH_LIMIT = false

    const val DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE = 0

    const val DEFAULT_HOOK_ISLAND_LYRIC_APP_FILTER_MODE = 0

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_CARD_SWITCHER_ENABLED = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_CARD_SWITCHER_MAX_COUNT = 3

    const val DEFAULT_HOOK_AOD_DISABLE_MEDIA_CARD_COLLAPSING = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SHADOW = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_TIME = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_CUSTOM_ACTIONS = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_DISABLE_COVER_FLIP = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_PROGRESS_HEAD_GLOW = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_ACTION_ALIGN_LEFT = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_BLUR = 10

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_COLOR_ANIMATION = false

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_AUTO_INVERT = false

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_COVER_SOURCE = false

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_DEVICE_SWITCH = false

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_TIME = false

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_CUSTOM_ACTIONS = false

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_DISABLE_COVER_FLIP = false

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_PROGRESS_HEAD_GLOW = true

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_ACTION_ALIGN_LEFT = false

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_BLUR = 10

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_COLOR_ANIMATION = false

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_AUTO_INVERT = false

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_COVER_SIZE = 50f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_COVER_TOP = 15f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_COVER_START = 15f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_TITLE_TOP = 19f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_INFO_START_GAP = 12f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_ARTIST_GAP = 3f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_MUSIC_WAVE_SIZE = 24f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_MUSIC_WAVE_TOP = 24f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_MUSIC_WAVE_END = 24f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_PROGRESS_TOP_GAP = 5f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_PROGRESS_HEIGHT = 38f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_PROGRESS_TIME_GAP = 0f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_TIME_OUTER_MARGIN = 12f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_ACTION_WIDTH = 60f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_ACTION_HEIGHT = 50f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_ACTION_BOTTOM = 12f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_ACTION_OUTER_MARGIN = 6f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_ACTION_SCALE_PERCENT = 100f

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_IOS_DEVICE_SWITCH_SCALE_PERCENT = 90f

    const val DEFAULT_HOOK_TEXT_SIZE = 12

    const val DEFAULT_HOOK_TEXT_SIZE_RATIO = 0.7f

    const val DEFAULT_HOOK_FONT_WEIGHT = 600

    const val DEFAULT_HOOK_FONT_ITALIC = false

    const val DEFAULT_HOOK_NARROW_LATIN_FONT = false

    const val DEFAULT_HOOK_GRADIENT_PROGRESS = true

    const val DEFAULT_HOOK_CENTER_LYRIC = false

    const val DEFAULT_HOOK_CENTER_MUSIC_INFO = false

    const val DEFAULT_HOOK_RIGHT_LYRIC = false

    const val DEFAULT_HOOK_ANIM_ENABLE = false

    const val DEFAULT_HOOK_ANIM_ID = "default"

    const val DEFAULT_HOOK_SYLLABLE_RELATIVE = true

    const val DEFAULT_HOOK_SYLLABLE_HIGHLIGHT = false

    const val DEFAULT_HOOK_SYLLABLE_LINE_DISPLAY = false

    /** 默认不模拟：没有逐字数据时整行直接全亮。 */
    const val DEFAULT_HOOK_SIMULATE_UNTIMED_WORD = false

    const val DEFAULT_HOOK_DISABLE_TRANSLATION = false

    const val DEFAULT_HOOK_TRANSLATION_ONLY = false

    const val DEFAULT_HOOK_SWAP_TRANSLATION = false

    const val DEFAULT_HOOK_NEXT_LYRIC_LINE = false

    const val DEFAULT_HOOK_AUTO_SWITCH_TRANSLATION = false

    const val DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR = false

    const val DEFAULT_HOOK_ISLAND_PROGRESS_GLOW = false

    const val DEFAULT_HOOK_ISLAND_PROGRESS_GRADIENT = false

    const val DEFAULT_HOOK_WORD_MOTION_ENABLED = false

    const val DEFAULT_HOOK_ISLAND_LYRIC_MIRROR = false

    const val DEFAULT_HOOK_LYRIC_EFFECT = 0

    const val DEFAULT_HOOK_LYRIC_EMPHASIS = false

    const val DEFAULT_ISLAND_MATERIAL_ENABLED = false

    const val DEFAULT_ISLAND_MATERIAL_TYPE = "default"

    const val DEFAULT_ISLAND_BLUR_RADIUS = 35

    const val DEFAULT_ISLAND_BLUR_COLOR = 0x14FFFFFF

    const val DEFAULT_ISLAND_GLASS_ENABLED = true

    const val DEFAULT_ISLAND_GLASS_EDGE_WIDTH = 16

    const val DEFAULT_ISLAND_GLASS_REFRACTION = 16

    const val DEFAULT_ISLAND_GLASS_HIGHLIGHT = 42

    const val DEFAULT_ISLAND_GLASS_SHADOW = 14

    const val DEFAULT_ISLAND_GLASS_LIGHT_DIRECTION = 243

    const val DEFAULT_ISLAND_GLASS_DISPERSION = 18

    const val DEFAULT_ISLAND_GLASS_CAPTURE_FPS = 60

    const val DEFAULT_ISLAND_GLASS_CAPTURE_QUALITY = 50

    const val DEFAULT_ISLAND_GLASS_HDR_HIGHLIGHT = false

    const val DEFAULT_ISLAND_GLASS_GYROSCOPE = false


    // ==================== 状态栏歌词枚举 ====================

    const val STATUS_BAR_LYRIC_FONT_SANS_SERIF = 0

    const val STATUS_BAR_LYRIC_FONT_SANS_SERIF_MEDIUM = 1

    const val STATUS_BAR_LYRIC_FONT_SANS_SERIF_LIGHT = 2

    const val STATUS_BAR_LYRIC_FONT_SERIF = 3

    const val STATUS_BAR_LYRIC_FONT_MONOSPACE = 4

    const val STATUS_BAR_LYRIC_FONT_SANS_SERIF_CONDENSED = 5

    const val STATUS_BAR_LYRIC_FONT_SANS_SERIF_BLACK = 6

    const val STATUS_BAR_LYRIC_FONT_CURSIVE = 7

    const val STATUS_BAR_LYRIC_WEIGHT_THIN = 0       // 100

    const val STATUS_BAR_LYRIC_WEIGHT_LIGHT = 1      // 300

    const val STATUS_BAR_LYRIC_WEIGHT_NORMAL = 2     // 400

    const val STATUS_BAR_LYRIC_WEIGHT_MEDIUM = 3     // 500

    const val STATUS_BAR_LYRIC_WEIGHT_SEMI_BOLD = 4  // 600

    const val STATUS_BAR_LYRIC_WEIGHT_BOLD = 5       // 700

    const val STATUS_BAR_LYRIC_WEIGHT_BLACK = 6      // 900

    val STATUS_BAR_LYRIC_FONT_WEIGHT_VALUES = intArrayOf(100, 300, 400, 500, 600, 700, 900)

    const val STATUS_BAR_LYRIC_INSERTION_BEFORE_CLOCK = 0

    const val STATUS_BAR_LYRIC_INSERTION_AFTER_CLOCK = 1

    const val STATUS_BAR_LYRIC_ICON_MUSIC_COVER = 0

    const val STATUS_BAR_LYRIC_ICON_CIRCLE_COVER = 1

    const val STATUS_BAR_LYRIC_ICON_ROTATING_COVER = 2

    const val STATUS_BAR_LYRIC_ICON_APP = 3

    const val STATUS_BAR_LYRIC_ICON_MONOCHROME = 4

    const val STATUS_BAR_LYRIC_ICON_BEFORE_LYRIC = 0

    const val STATUS_BAR_LYRIC_ICON_AFTER_LYRIC = 1

    const val STATUS_BAR_LYRIC_ICON_MIN_SIZE_DP = 10

    const val STATUS_BAR_LYRIC_ICON_MAX_SIZE_DP = 24

    const val STATUS_BAR_LYRIC_ICON_MIN_SPACING_DP = 0

    const val STATUS_BAR_LYRIC_ICON_MAX_SPACING_DP = 16

    const val STATUS_BAR_LYRIC_PORTRAIT_MAX_WIDTH_DP = 200

    const val STATUS_BAR_LYRIC_LANDSCAPE_MAX_WIDTH_DP = 400

    const val STATUS_BAR_LYRIC_CLOCK_HIDE_NONE = 0

    const val STATUS_BAR_LYRIC_CLOCK_HIDE_WHILE_PLAYING = 1

    const val STATUS_BAR_LYRIC_CLOCK_HIDE_WHEN_ISLAND_PRESENT = 2

    const val STATUS_BAR_LYRIC_NOTIFICATION_ICON_HIDE_NONE = 0

    const val STATUS_BAR_LYRIC_NOTIFICATION_ICON_HIDE_WHILE_PLAYING = 1

    const val STATUS_BAR_LYRIC_NOTIFICATION_ICON_MOVE_RIGHT_WHILE_PLAYING = 2

    const val STATUS_BAR_LYRIC_ISLAND_HIDE_NONE = 0

    const val STATUS_BAR_LYRIC_ISLAND_HIDE_WHILE_PLAYING = 1

    const val STATUS_BAR_LYRIC_ISLAND_HIDE_ALWAYS = 2

    const val STATUS_BAR_LYRIC_GESTURE_ACTION_NONE = 0

    const val STATUS_BAR_LYRIC_GESTURE_ACTION_TOGGLE_PLAYBACK = 1

    const val STATUS_BAR_LYRIC_GESTURE_ACTION_TEMPORARY_CLOCK = 2

    const val STATUS_BAR_LYRIC_GESTURE_ACTION_OPEN_MEDIA_APP = 3

    const val STATUS_BAR_LYRIC_GESTURE_ACTION_TEMPORARY_HIDE_LYRIC = 4

    const val STATUS_BAR_LYRIC_GESTURE_ACTION_OPEN_MEDIA_APP_FREEFORM = 5

    /** 弹出歌词控制面板（封面/歌名/进度/上一首/播放暂停/下一首）。 */
    const val STATUS_BAR_LYRIC_GESTURE_ACTION_CONTROL_PANEL = 6

    const val STATUS_BAR_LYRIC_GESTURE_SWIPE_NONE = 0

    const val STATUS_BAR_LYRIC_GESTURE_SWIPE_PREVIOUS = 1

    const val STATUS_BAR_LYRIC_GESTURE_SWIPE_NEXT = 2

    const val STATUS_BAR_LYRIC_GESTURE_SWIPE_THRESHOLD_DP = 50


    // ==================== 超级岛枚举 ====================

    const val ISLAND_OUTER_GLOW_MODE_FOLLOW_COVER = 0

    const val ISLAND_OUTER_GLOW_MODE_FIXED = 1

    /** 自定义色：用户自己填 HEX，不受 16 色预设限制。 */
    const val ISLAND_OUTER_GLOW_MODE_CUSTOM = 2

    const val ISLAND_ALBUM_COVER_STYLE_DEFAULT = 0

    const val ISLAND_ALBUM_COVER_STYLE_CIRCLE = 1

    const val ISLAND_ALBUM_COVER_STYLE_APP_ICON = 2

    const val ISLAND_ALBUM_COVER_STYLE_ROTATING_CIRCLE = 3

    const val ISLAND_ALBUM_COVER_STYLE_HIDDEN = 4

    const val ISLAND_CONTENT_MODE_NONE = 0

    const val ISLAND_CONTENT_MODE_LYRIC = 7

    const val ISLAND_CONTENT_MODE_CUSTOM_MUSIC_INFO = 8

    const val ISLAND_WIDTH_MODE_FIXED = 0

    const val ISLAND_WIDTH_MODE_DYNAMIC = 1

    const val ISLAND_DYNAMIC_WIDTH_BASIS_ALL = 0

    const val ISLAND_DYNAMIC_WIDTH_BASIS_LYRIC_ONLY = 1

    const val ISLAND_LONG_PRESS_BEHAVIOR_LYRIC_SHARE = 0

    const val ISLAND_LONG_PRESS_BEHAVIOR_TOGGLE_PLAYBACK = 1

    const val ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DEFAULT = 0

    const val ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DISABLED = 1

    const val ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_COVER_COLOR = 2

    const val ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL = 3

    const val ISLAND_EXPANDED_MEDIA_LAYOUT_STYLE_SYSTEM = 0

    const val ISLAND_EXPANDED_MEDIA_LAYOUT_STYLE_IOS = 1

    const val ISLAND_EXPANDED_MEDIA_LAYOUT_STYLE_COLOROS = 2

    const val ISLAND_EXPANDED_MEDIA_LAYOUT_STYLE_ONEUI = 3

    const val ISLAND_EXPANDED_MEDIA_LAYOUT_STYLE_MIUI = 4

    const val ISLAND_EXPANDED_MEDIA_LAYOUT_STYLE_PIXEL = 5

    const val ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT = 0

    const val ISLAND_EXPANDED_MEDIA_COVER_STYLE_CIRCLE = 1

    const val ISLAND_EXPANDED_MEDIA_COVER_STYLE_ROTATING_CIRCLE = 2

    const val ISLAND_EXPANDED_MEDIA_COVER_STYLE_HIDDEN = 3

    const val ISLAND_EXPANDED_MEDIA_PROGRESS_STYLE_DEFAULT = 0

    const val ISLAND_EXPANDED_MEDIA_PROGRESS_STYLE_WAVE = 1

    const val ISLAND_EXPANDED_MEDIA_THUMB_STYLE_DEFAULT = 0

    const val ISLAND_EXPANDED_MEDIA_THUMB_STYLE_VERTICAL = 1

    const val ISLAND_EXPANDED_MEDIA_THUMB_STYLE_HIDDEN = 2

    const val ISLAND_EXPANDED_MEDIA_ACTION_ORDER_DEFAULT = 0

    const val ISLAND_EXPANDED_MEDIA_ACTION_ORDER_CUSTOM_RIGHT = 1

    const val ISLAND_EXPANDED_MEDIA_ACTION_ORDER_PLAY_LEFT = 2

    const val ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_DEFAULT = 0

    const val ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_COVER_ART = 1

    const val ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_BLURRED_COVER = 2

    const val ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_RADIAL_GRADIENT = 3

    const val ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_LINEAR_GRADIENT = 4

    const val ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_SOFT_COVER = 5

    const val ISLAND_MUSIC_WAVE_STYLE_DEFAULT = 0

    const val ISLAND_MUSIC_WAVE_STYLE_COVER_COLOR = 1

    const val ISLAND_MUSIC_WAVE_STYLE_COVER_GRADIENT = 2

    const val ISLAND_MUSIC_WAVE_STYLE_HIDDEN = 3

    const val ISLAND_PROGRESS_STYLE_TOP_CLOCKWISE = 0

    const val ISLAND_PROGRESS_STYLE_RIGHT_CLOCKWISE = 1

    const val ISLAND_PROGRESS_STYLE_BOTTOM_CLOCKWISE = 2

    const val ISLAND_PROGRESS_STYLE_LEFT_CLOCKWISE = 3

    const val ISLAND_PROGRESS_STYLE_LEFT_BIDIRECTIONAL = 4

    const val ISLAND_PROGRESS_STYLE_TOP_BIDIRECTIONAL = 5

    const val ISLAND_PROGRESS_STYLE_BOTTOM_BIDIRECTIONAL = 6


    // ==================== 通知媒体卡片枚举 ====================

    const val NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DISABLED = 0

    const val NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DYNAMIC = 1

    const val NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_COVER_COLOR = 2

    const val NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL = 3

    const val NOTIFICATION_MEDIA_CARD_SWITCHER_MODE_SINGLE = 0

    const val NOTIFICATION_MEDIA_CARD_SWITCHER_MODE_MULTI = 1

    const val NOTIFICATION_MEDIA_LAYOUT_STYLE_SYSTEM = 0

    const val NOTIFICATION_MEDIA_LAYOUT_STYLE_IOS = 1

    const val NOTIFICATION_MEDIA_LAYOUT_STYLE_COLOROS = 2

    const val NOTIFICATION_MEDIA_LAYOUT_STYLE_ONEUI = 3

    const val NOTIFICATION_MEDIA_LAYOUT_STYLE_MIUI = 4

    const val NOTIFICATION_MEDIA_LAYOUT_STYLE_PIXEL = 5

    const val NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT = 0

    const val NOTIFICATION_MEDIA_COVER_STYLE_CIRCLE = 1

    const val NOTIFICATION_MEDIA_COVER_STYLE_ROTATING_CIRCLE = 2

    const val NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN = 3

    const val NOTIFICATION_MEDIA_PROGRESS_STYLE_DEFAULT = 0

    const val NOTIFICATION_MEDIA_PROGRESS_STYLE_WAVE = 1

    const val NOTIFICATION_MEDIA_THUMB_STYLE_DEFAULT = 0

    const val NOTIFICATION_MEDIA_THUMB_STYLE_VERTICAL = 1

    const val NOTIFICATION_MEDIA_THUMB_STYLE_HIDDEN = 2

    const val NOTIFICATION_MEDIA_ACTION_ORDER_DEFAULT = 0

    const val NOTIFICATION_MEDIA_ACTION_ORDER_CUSTOM_RIGHT = 1

    const val NOTIFICATION_MEDIA_ACTION_ORDER_PLAY_LEFT = 2

    const val NOTIFICATION_MEDIA_BACKGROUND_STYLE_DEFAULT = 0

    const val NOTIFICATION_MEDIA_BACKGROUND_STYLE_COVER_ART = 1

    const val NOTIFICATION_MEDIA_BACKGROUND_STYLE_BLURRED_COVER = 2

    const val NOTIFICATION_MEDIA_BACKGROUND_STYLE_RADIAL_GRADIENT = 3

    const val NOTIFICATION_MEDIA_BACKGROUND_STYLE_LINEAR_GRADIENT = 4

    const val NOTIFICATION_MEDIA_BACKGROUND_STYLE_SOFT_COVER = 5


    // ==================== 媒体通用枚举 ====================

    const val MEDIA_CARD_THEME_FOLLOW_SYSTEM = 0

    const val MEDIA_CARD_THEME_ALWAYS_LIGHT = 1

    const val MEDIA_CARD_THEME_ALWAYS_DARK = 2

    const val MEDIA_SOFT_COVER_TONE_LIGHT = 0

    const val MEDIA_SOFT_COVER_TONE_DARK = 1

    const val MEDIA_SOFT_COVER_TONE_FOLLOW_SYSTEM = 2


    // ==================== 文字与动画枚举 ====================

    const val LINE_SWITCH_ANIM_NONE = 0

    const val LINE_SWITCH_ANIM_SCALE_UP = 1

    const val PLACEHOLDER_FORMAT_NONE = 0

    const val PLACEHOLDER_FORMAT_TITLE_ARTIST = 1

    const val PLACEHOLDER_FORMAT_TITLE = 2

    const val PLACEHOLDER_FORMAT_COUNTDOWN = 3

    const val TEXT_COLOR_STYLE_DEFAULT = 0

    const val TEXT_COLOR_STYLE_COVER_COLOR = 1

    const val TEXT_COLOR_STYLE_COVER_GRADIENT = 2

    const val TEXT_COLOR_STYLE_RAINBOW = 4

    const val TEXT_COLOR_STYLE_CUSTOM = 5


    // ==================== 其它枚举 ====================

    const val PREF_NAME_STATUS_BAR_LYRIC = "com.liuxiansheng.lyricon_status_bar_preferences"

    const val MIN_HOOK_STATUS_BAR_LYRIC_TEXT_SIZE_SP = 8

    const val MAX_HOOK_STATUS_BAR_LYRIC_TEXT_SIZE_SP = 20

    const val MIN_HOOK_STATUS_BAR_LYRIC_PADDING_DP = -32f

    const val MAX_HOOK_STATUS_BAR_LYRIC_PADDING_DP = 32f

    const val MIN_HOOK_LYRICON_PROVIDER_DELAY = -5000

    const val MAX_HOOK_LYRICON_PROVIDER_DELAY = 5000

    const val MIN_HOOK_NOTIFICATION_MEDIA_CARD_SWITCHER_MAX_COUNT = 2

    const val MAX_HOOK_NOTIFICATION_MEDIA_CARD_SWITCHER_MAX_COUNT = 6


    // ==================== 默认值 ====================

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_FONT_FAMILY = STATUS_BAR_LYRIC_FONT_SANS_SERIF

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_FONT_WEIGHT = STATUS_BAR_LYRIC_WEIGHT_BOLD

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_INSERTION_ORDER = STATUS_BAR_LYRIC_INSERTION_AFTER_CLOCK

    /** 默认锚点：状态栏时钟。小米与 ColorOS 状态栏均存在 clock id。 */
    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_ANCHOR = "clock"

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_STYLE = STATUS_BAR_LYRIC_ICON_MUSIC_COVER

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_ORDER = STATUS_BAR_LYRIC_ICON_BEFORE_LYRIC

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_CLOCK_HIDE_BEHAVIOR = STATUS_BAR_LYRIC_CLOCK_HIDE_WHILE_PLAYING

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_NOTIFICATION_ICON_HIDE_BEHAVIOR = STATUS_BAR_LYRIC_NOTIFICATION_ICON_HIDE_WHILE_PLAYING

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_ISLAND_HIDE_BEHAVIOR = STATUS_BAR_LYRIC_ISLAND_HIDE_WHILE_PLAYING

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_DOUBLE_TAP_ACTION = STATUS_BAR_LYRIC_GESTURE_ACTION_NONE

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_SINGLE_TAP_ACTION = STATUS_BAR_LYRIC_GESTURE_ACTION_NONE

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_LONG_PRESS_ACTION = STATUS_BAR_LYRIC_GESTURE_ACTION_TOGGLE_PLAYBACK

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_SWIPE_LEFT_ACTION = STATUS_BAR_LYRIC_GESTURE_SWIPE_NEXT

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_SWIPE_RIGHT_ACTION = STATUS_BAR_LYRIC_GESTURE_SWIPE_PREVIOUS

    const val DEFAULT_HOOK_STATUS_BAR_LYRIC_ROOT_TOUCH_INTERCEPT = true

    const val DEFAULT_HOOK_ISLAND_ALBUM_COVER_STYLE = ISLAND_ALBUM_COVER_STYLE_DEFAULT

    const val DEFAULT_HOOK_ISLAND_CONTENT_LEFT = ISLAND_CONTENT_MODE_CUSTOM_MUSIC_INFO

    const val DEFAULT_HOOK_ISLAND_CONTENT_RIGHT = ISLAND_CONTENT_MODE_LYRIC

    const val DEFAULT_HOOK_ISLAND_WIDTH_MODE = ISLAND_WIDTH_MODE_DYNAMIC

    const val DEFAULT_HOOK_ISLAND_DYNAMIC_WIDTH_BASIS = ISLAND_DYNAMIC_WIDTH_BASIS_ALL

    const val DEFAULT_HOOK_ISLAND_LONG_PRESS_BEHAVIOR =  ISLAND_LONG_PRESS_BEHAVIOR_LYRIC_SHARE

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE =  NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DISABLED

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_CARD_SWITCHER_MODE =  NOTIFICATION_MEDIA_CARD_SWITCHER_MODE_MULTI

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE =  ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DEFAULT

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_CARD_THEME = MEDIA_CARD_THEME_FOLLOW_SYSTEM

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME = MEDIA_CARD_THEME_ALWAYS_DARK

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_LAYOUT_STYLE =  NOTIFICATION_MEDIA_LAYOUT_STYLE_SYSTEM

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_LAYOUT_STYLE =  ISLAND_EXPANDED_MEDIA_LAYOUT_STYLE_SYSTEM

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_COVER_STYLE =  NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_PROGRESS_STYLE = NOTIFICATION_MEDIA_PROGRESS_STYLE_DEFAULT

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_THUMB_STYLE = NOTIFICATION_MEDIA_THUMB_STYLE_DEFAULT

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_ACTION_ORDER = NOTIFICATION_MEDIA_ACTION_ORDER_DEFAULT

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_STYLE =  NOTIFICATION_MEDIA_BACKGROUND_STYLE_DEFAULT

    const val DEFAULT_HOOK_NOTIFICATION_MEDIA_SOFT_COVER_TONE = MEDIA_SOFT_COVER_TONE_DARK

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_COVER_STYLE =  ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_PROGRESS_STYLE = ISLAND_EXPANDED_MEDIA_PROGRESS_STYLE_DEFAULT

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_THUMB_STYLE = ISLAND_EXPANDED_MEDIA_THUMB_STYLE_DEFAULT

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_ACTION_ORDER = ISLAND_EXPANDED_MEDIA_ACTION_ORDER_DEFAULT

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE =  ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_DEFAULT

    const val DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_SOFT_COVER_TONE = MEDIA_SOFT_COVER_TONE_DARK

    const val DEFAULT_HOOK_PLACEHOLDER_FORMAT = PLACEHOLDER_FORMAT_TITLE

    const val DEFAULT_HOOK_ISLAND_MUSIC_WAVE_STYLE = ISLAND_MUSIC_WAVE_STYLE_DEFAULT

    const val DEFAULT_HOOK_LINE_SWITCH_ANIM = LINE_SWITCH_ANIM_SCALE_UP

    const val DEFAULT_HOOK_TEXT_COLOR_STYLE = TEXT_COLOR_STYLE_RAINBOW

    const val DEFAULT_HOOK_ISLAND_PROGRESS_STYLE = ISLAND_PROGRESS_STYLE_TOP_CLOCKWISE

    /* ---------------- 尾段超时（移植 HyperLyric） ---------------- */

    /** 保持当前显示（默认）：超时后最后一行继续挂着，行为与不做超时一致。 */
    const val TAIL_TIMEOUT_BEHAVIOR_KEEP = 0

    /** 超时后把歌词槽换成音乐信息（歌名 / 歌手）。 */
    const val TAIL_TIMEOUT_BEHAVIOR_MUSIC_INFO = 1

    /** 超时后把歌词槽交还系统原生渲染（不再由本模块接管）。 */
    const val TAIL_TIMEOUT_BEHAVIOR_RESTORE_DEFAULT = 2

    const val DEFAULT_HOOK_TAIL_TIMEOUT_BEHAVIOR = TAIL_TIMEOUT_BEHAVIOR_KEEP

    const val DEFAULT_HOOK_TAIL_TIMEOUT_DELAY_SECONDS = 3

    const val MIN_HOOK_TAIL_TIMEOUT_DELAY_SECONDS = 0

    const val MAX_HOOK_TAIL_TIMEOUT_DELAY_SECONDS = 30

    /**
     * 只有开始时间、没有结束时间的歌词行，按「开始 + DEFAULT 延迟 + 本值」触发超时。
     * 与 HyperLyric 的 TAIL_TIMEOUT_BEGIN_ONLY_EXTRA_SECONDS 一致。
     */
    const val TAIL_TIMEOUT_BEGIN_ONLY_EXTRA_SECONDS = 5

    fun normalizeTailTimeoutBehavior(value: Int): Int =
        value.takeIf { it in TAIL_TIMEOUT_BEHAVIOR_KEEP..TAIL_TIMEOUT_BEHAVIOR_RESTORE_DEFAULT }
            ?: DEFAULT_HOOK_TAIL_TIMEOUT_BEHAVIOR

    fun normalizeTailTimeoutDelaySeconds(value: Int): Int =
        value.coerceIn(MIN_HOOK_TAIL_TIMEOUT_DELAY_SECONDS, MAX_HOOK_TAIL_TIMEOUT_DELAY_SECONDS)



    // ==================== 状态栏歌词枚举 ====================

    const val STATUS_BAR_LYRIC_ICON_SPACING_DP = DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_SPACING_DP

}
