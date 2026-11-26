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
 * 超级岛两侧内容的显示样式：专辑封面、音乐律动。
 *
 * 每一项都是「编号」，编号含义固定（默认 / 封面纯色 / 封面渐变 / 隐藏）。
 * 读出来先夹区间，避免老配置越界导致后面的判定全部落空。
 */
object SuperIslandContentStylePolicy {

    // ——— 专辑封面 ———

    fun readAlbumCoverStyle(prefs: SharedPreferences): Int =
        prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE,
            RootConstants.DEFAULT_HOOK_ISLAND_ALBUM_COVER_STYLE
        ).coerceIn(
            RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT,
            RootConstants.ISLAND_ALBUM_COVER_STYLE_HIDDEN
        )

    fun isAlbumCoverVisible(style: Int): Boolean =
        style != RootConstants.ISLAND_ALBUM_COVER_STYLE_HIDDEN

    // ——— 律动 ———

    /**
     * 音乐律动样式：开关已移除（系统新版本把 setLottieColor 改成 getLottieColor，
     * 取色/渐变无处注入），统一恒返回「默认律动」，即律动常显、颜色交给系统原生。
     * 保留函数与常量，避免牵动宽度计算、槽位配置等既有调用链。
     */
    fun readMusicWaveStyle(prefs: SharedPreferences): Int =
        RootConstants.ISLAND_MUSIC_WAVE_STYLE_DEFAULT

    fun isMusicWaveVisible(style: Int): Boolean =
        style != RootConstants.ISLAND_MUSIC_WAVE_STYLE_HIDDEN

    /** 律动取封面色（纯色或渐变）。 */
    fun usesMusicWaveCoverColor(style: Int): Boolean =
        style == RootConstants.ISLAND_MUSIC_WAVE_STYLE_COVER_COLOR ||
            style == RootConstants.ISLAND_MUSIC_WAVE_STYLE_COVER_GRADIENT

    /** 律动取封面渐变。 */
    fun usesMusicWaveGradient(style: Int): Boolean =
        style == RootConstants.ISLAND_MUSIC_WAVE_STYLE_COVER_GRADIENT
}
