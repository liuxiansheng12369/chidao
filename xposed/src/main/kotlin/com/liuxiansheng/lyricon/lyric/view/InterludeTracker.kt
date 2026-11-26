/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.view

/**
 * 识别「两句歌词之间的长空段」，也就是间奏。
 *
 * 只有间距够长、并且当前播放位置正好落在这一段空隙里，才算进了间奏。
 * 一旦判定进了间奏，后续每次调用都会原样续用，直到位置离开这段区间 ——
 * 否则每次心跳都重新判一次，界面会反复在两个状态之间跳。
 *
 * 判定只看「当前句」和「下一句」，因为 [TimedLine] 本身是双向链表，
 * 不用在列表里反复查邻居。
 *
 * @property minGapMs 间距小于这个值不算间奏（默认 8 秒）
 */
internal class InterludeTracker(private val minGapMs: Long = DEFAULT_MIN_GAP_MS) {

    /**
     * 算出当前应不应该显示间奏。
     *
     * @param posMs 当前播放位置（毫秒）
     * @param matches 已匹配到的句列表，取最后一句当"当前句"
     * @param current 上一次返回的间奏，用于续接
     * @return 落在间奏里就返回区间，否则 `null`
     */
    fun evaluate(
        posMs: Long,
        matches: List<TimedLine>,
        current: Interlude?
    ): Interlude? {
        // 已经身处间奏内部：只要没走出去，直接沿用，避免每次心跳重算导致闪烁
        if (current != null && isInside(posMs, current)) return current

        val currentLine = matches.lastOrNull() ?: return null
        val nextLine = currentLine.next ?: return null

        // 间距不够长（含正好等于阈值），不是间奏，是正常换句
        if (nextLine.begin - currentLine.end <= minGapMs) return null
        // 位置还没播完当前句，或已越过下一句起点，都不算在空隙里
        if (posMs <= currentLine.end || posMs >= nextLine.begin) return null

        return Interlude(currentLine.end, nextLine.begin)
    }

    /** 位置是否严格落在区间内部（两端都算区间外）。 */
    private fun isInside(posMs: Long, interlude: Interlude): Boolean =
        posMs > interlude.start && posMs < interlude.end

    /** 一段间奏的起止时间。 */
    data class Interlude(val start: Long, val end: Long) {
        val duration: Long get() = end - start
    }

    companion object {
        /** 默认最短间距：8 秒。短于此的停顿仍按正常换句处理。 */
        const val DEFAULT_MIN_GAP_MS = 8000L
    }
}
