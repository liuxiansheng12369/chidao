/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric

/** 一行最简单的歌词：只有一个开始时间和一句文本（逐行 LRC 的粒度）。 */
data class LrcLine(val startTimeMs: Long, val content: String)

/**
 * 向歌词提供方发起检索时带的全部上下文。
 *
 * 播放器、时长、专辑都要给：同一个歌名在不同专辑里可能是两首不同的歌，
 * 时长又能用来排除同名翻唱。
 */
data class LyricSearchParams(
    val title: String,
    val artist: String,
    val album: String,
    val packageName: String,
    val duration: Long
)

/** 歌词提供方。拿不到就返回 `null`，不要返回空列表来假装成功。 */
interface ILyricProvider {
    suspend fun fetchLyrics(params: LyricSearchParams): List<LrcLine>?
}
