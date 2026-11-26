/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.common.media

import com.liuxiansheng.lyricon.hyper.common.RootConstants

/**
 * 媒体卡封面色调策略。
 *
 * 配置项有三个取值：跟随系统、固定浅色、固定深色。跟随系统时按系统深浅色决定，
 * 认不出的配置值一律按深色兜底。
 */
object MediaCardTonePolicy {

    /** 把配置值解析成实际要用的色调。 */
    fun resolveSoftCoverTone(configuredTone: Int, isSystemDark: Boolean): Int =
        when (configuredTone) {
            // 跟随系统：系统深色就深色，浅色就浅色
            RootConstants.MEDIA_SOFT_COVER_TONE_FOLLOW_SYSTEM ->
                if (isSystemDark) {
                    RootConstants.MEDIA_SOFT_COVER_TONE_DARK
                } else {
                    RootConstants.MEDIA_SOFT_COVER_TONE_LIGHT
                }

            // 已经明确指定，原样用
            RootConstants.MEDIA_SOFT_COVER_TONE_LIGHT,
            RootConstants.MEDIA_SOFT_COVER_TONE_DARK -> configuredTone

            // 不认识的配置值 → 深色兜底
            else -> RootConstants.MEDIA_SOFT_COVER_TONE_DARK
        }

    /** 解析后的色调是不是深色。 */
    fun isSoftCoverDark(configuredTone: Int, isSystemDark: Boolean): Boolean =
        resolveSoftCoverTone(configuredTone, isSystemDark) ==
            RootConstants.MEDIA_SOFT_COVER_TONE_DARK
}
