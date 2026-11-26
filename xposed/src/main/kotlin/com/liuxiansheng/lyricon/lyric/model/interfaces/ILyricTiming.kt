/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.model.interfaces

/**
 * 歌词时间基元契约。凡是在时间轴上占一段时间的歌词元素（行、词）都实现它。
 *
 * **时间单位统一为毫秒。**
 *
 * 合法生命周期：`begin >= 0` 且 `end > begin`，此时 `duration == end - begin`。
 *
 * `duration == 0` 而 `end > begin` 时的自动兜底，只在具体数据类
 * （[com.liuxiansheng.lyricon.hyper.lyric.model.LyricLine]、
 * [com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord]）的初始化里做；
 * 纯时间类 [com.liuxiansheng.lyricon.hyper.lyric.model.LyricTiming] 不做兜底。
 *
 * @property begin 开始时间（毫秒）
 * @property end 结束时间（毫秒）
 * @property duration 持续时长（毫秒）
 */
interface ILyricTiming {
    var begin: Long
    var end: Long
    var duration: Long
}
