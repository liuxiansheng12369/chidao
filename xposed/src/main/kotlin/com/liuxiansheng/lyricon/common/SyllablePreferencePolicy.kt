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

import android.content.SharedPreferences

/** 逐字相关的三项偏好。 */
data class SyllablePreferenceState(
    val relativeProgress: Boolean,
    val relativeHighlight: Boolean,
    val lineDisplay: Boolean
)

/**
 * 逐字偏好：相对进度、相对高亮、整行显示。
 *
 * 「整行显示」和「相对进度」互相矛盾 —— 整行显示时词级进度没有意义，
 * 所以只要整行显示打开，相对进度一律压成 false。这条规则在读取和应用两处都要走，
 * 否则设置页展示的值和实际渲染行为会对不上。
 */
object SyllablePreferencePolicy {

    /** 读偏好并归一化。 */
    fun read(prefs: SharedPreferences): SyllablePreferenceState = normalize(
        relativeProgress = prefs.getBoolean(
            RootConstants.KEY_HOOK_SYLLABLE_RELATIVE,
            RootConstants.DEFAULT_HOOK_SYLLABLE_RELATIVE
        ),
        relativeHighlight = prefs.getBoolean(
            RootConstants.KEY_HOOK_SYLLABLE_HIGHLIGHT,
            RootConstants.DEFAULT_HOOK_SYLLABLE_HIGHLIGHT
        ),
        lineDisplay = prefs.getBoolean(
            RootConstants.KEY_HOOK_SYLLABLE_LINE_DISPLAY,
            RootConstants.DEFAULT_HOOK_SYLLABLE_LINE_DISPLAY
        )
    )

    /** 归一化：整行显示时相对进度强制关掉。 */
    fun normalize(
        relativeProgress: Boolean,
        relativeHighlight: Boolean,
        lineDisplay: Boolean
    ): SyllablePreferenceState {
        val progress = if (lineDisplay) false else relativeProgress
        return SyllablePreferenceState(
            relativeProgress = progress,
            relativeHighlight = relativeHighlight,
            lineDisplay = lineDisplay
        )
    }

    fun write(editor: SharedPreferences.Editor, state: SyllablePreferenceState) {
        editor.putBoolean(RootConstants.KEY_HOOK_SYLLABLE_LINE_DISPLAY, state.lineDisplay)
        editor.putBoolean(RootConstants.KEY_HOOK_SYLLABLE_RELATIVE, state.relativeProgress)
        editor.putBoolean(RootConstants.KEY_HOOK_SYLLABLE_HIGHLIGHT, state.relativeHighlight)
    }

    /** 把盘上已经非法的组合改回合法值；已经合法就一次都不写。 */
    fun normalizeInPlace(prefs: SharedPreferences) {
        val current = read(prefs)
        val normalized = normalize(
            relativeProgress = current.relativeProgress,
            relativeHighlight = current.relativeHighlight,
            lineDisplay = current.lineDisplay
        )
        if (current == normalized) return

        val editor = prefs.edit()
        write(editor, normalized)
        editor.apply()
    }
}
