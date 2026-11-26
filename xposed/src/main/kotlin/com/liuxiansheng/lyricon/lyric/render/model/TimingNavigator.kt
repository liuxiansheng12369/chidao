/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.lyric.render.model

internal class TimingNavigator(private val words: List<WordRenderModel>) {

    /**
     * 返回 [posMs] 时刻正在播放的词（begin <= pos < end）。
     * 若 pos 落在词间空隙返回 null。
     */
    fun first(posMs: Long): WordRenderModel? {
        if (words.isEmpty()) return null
        var low = 0
        var high = words.size - 1
        var result: WordRenderModel? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val word = words[mid]
            if (word.begin <= posMs) {
                result = word
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result?.takeIf { posMs < it.end || it.end <= it.begin }
    }

    /**
     * 返回 [posMs] 之前（含等于）最近一个已开始的词。
     * 用于词间空隙：保持前一词的结束宽度。
     */
    fun findPreviousEntry(posMs: Long): WordRenderModel? {
        if (words.isEmpty()) return null
        var low = 0
        var high = words.size - 1
        var result: WordRenderModel? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val word = words[mid]
            if (word.begin <= posMs) {
                result = word
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /** 首个词 */
    val firstWord: WordRenderModel? get() = words.firstOrNull()
}
