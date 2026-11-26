/*
 * 原实现来源：HyperIsland（作者 1812z），MIT License
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.renderer

/** 超级岛渲染器接口（V3 自研）。 */
interface IslandRenderer {
    fun refreshActiveIsland()
    fun updateMetadata()
    fun updateLyricLine()
    fun updateTextColors()
    fun updatePosition(position: Long, playbackSpeed: Float = 1f)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun clearAllViews()

    /** 歌曲切换时调用：清空残留歌词行并触发宽度刷新（宽度回最小，歌名层不受影响）。 */
    fun onSongSwitched() {}
}
