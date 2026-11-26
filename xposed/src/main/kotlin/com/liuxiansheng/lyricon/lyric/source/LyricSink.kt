/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.source

import com.liuxiansheng.lyricon.hyper.lyric.model.LyricMediaMetadata
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.IRichLyricLine

/**
 * 歌词源把数据推给下游的唯一出口。
 *
 * 一组事件的生命周期：`onSongChanged` →（`onMetadata`）→ 若干 `onLyricLine`
 * （或 `onPlainText`）→ `onStop`。播放状态和位置是并行的旁路信号，
 * 随时可能插进来，所以每个方法都必须幂等、不得在这里做重活。
 */
interface LyricSink {

    /** 换歌了。`null` 表示当前没有歌。 */
    fun onSongChanged(song: Song?)

    /** 当前应显示的一句（已经带好逐字时间轴）。 */
    fun onLyricLine(line: IRichLyricLine)

    /** 只有纯文本、没有逐字轴时走这里。 */
    fun onPlainText(text: String?)

    /** 本轮歌词结束。 */
    fun onStop()

    /** 媒体元数据（封面、专辑等）。 */
    fun onMetadata(metadata: LyricMediaMetadata?)

    /** 播放 / 暂停变化。`playbackSpeed` 未知时传 NaN。 */
    fun onPlaybackStateChanged(isPlaying: Boolean, playbackSpeed: Float = Float.NaN)

    /** 播放位置变化。 */
    fun onPositionChanged(position: Long, playbackSpeed: Float = Float.NaN)
}
