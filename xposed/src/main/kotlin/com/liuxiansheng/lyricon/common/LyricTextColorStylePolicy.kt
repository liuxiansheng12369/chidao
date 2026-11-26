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

import android.content.SharedPreferences

/**
 * 歌词文字配色方案。
 *
 * 设置页存的是一个编号，取出来先夹到合法区间再用 —— 老配置里可能残留越界值，
 * 直接用会让后面的 when 落空。
 */
object LyricTextColorStylePolicy {

    /** 已删除的「跟随状态栏」历史值：老配置里可能残留，读到就归一到默认色。 */
    private const val LEGACY_STYLE_FOLLOW_STATUS_BAR = 3

    /** 读当前配色编号。 */
    fun read(prefs: SharedPreferences): Int {
        val raw = prefs.getInt(
            RootConstants.KEY_HOOK_TEXT_COLOR_STYLE,
            RootConstants.DEFAULT_HOOK_TEXT_COLOR_STYLE
        )
        val normalized =
            if (raw == LEGACY_STYLE_FOLLOW_STATUS_BAR) {
                RootConstants.TEXT_COLOR_STYLE_DEFAULT
            } else {
                raw
            }
        return normalized.coerceIn(
            RootConstants.TEXT_COLOR_STYLE_DEFAULT,
            RootConstants.TEXT_COLOR_STYLE_CUSTOM
        )
    }

    /** 彩虹 7 色渐变。 */
    fun usesRainbow(style: Int): Boolean =
        style == RootConstants.TEXT_COLOR_STYLE_RAINBOW

    /** 颜色从封面取（含纯色与渐变两种）。 */
    fun usesCoverColor(style: Int): Boolean =
        style == RootConstants.TEXT_COLOR_STYLE_COVER_COLOR ||
            style == RootConstants.TEXT_COLOR_STYLE_COVER_GRADIENT

    /** 只取封面渐变。 */
    fun usesCoverGradient(style: Int): Boolean =
        style == RootConstants.TEXT_COLOR_STYLE_COVER_GRADIENT
}
