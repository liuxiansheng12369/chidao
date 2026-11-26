/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.model.extensions

import com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord

/**
 * 规范化一组歌词单词，返回新列表。
 *
 * 三件事：修时间、安顿坏时间的词、合并被拆开的英文词。
 *
 * 时间有效的标准是 `begin >= 0` 且 `end > begin`。有效词的 `duration <= 0` 时
 * 取 `end - begin` 兜底。
 *
 * 时间无效的词不直接丢，先攒着，等下一个有效词出现再按空隙决定去路：
 * - 前面有空隙（`begin - 上个词的 end > 0`）：用这段空隙给它们造一个词；
 * - 没空隙但有前一个词：文本接到前一个词后面；
 * - 没空隙也没有前一个词（坏词在最前面）：文本接到当前有效词前面。
 *
 * 末尾还剩下的坏词：有前词就接到最后一个词后面，一个有效词都没有就单独造
 * 一条 `[0, 100)` 的词兜住文本。
 *
 * 最后按 begin 排序，并合并相邻的无空格英文片段（见下方说明）。
 *
 * **不修改传进来的列表和原单词** —— 内部先逐个深拷贝。
 */
fun List<LyricWord>.normalize(): List<LyricWord> {
    // 先全量深拷贝，后续所有修正只落在副本上
    val words = map { it.deepCopy() }.filter { !it.text.isNullOrEmpty() }
    if (words.isEmpty()) return emptyList()

    val result = ArrayList<LyricWord>(words.size)
    val pending = ArrayList<LyricWord>()
    var cursor = 0L // 上一个有效词的 end

    for (word in words) {
        if (word.begin < 0L || word.end <= word.begin) {
            pending.add(word)
            continue
        }

        // 有效词到了，先安顿攒着的坏时间词
        if (pending.isNotEmpty()) {
            val carried = pending.joinToString("") { it.text.orEmpty() }
            val gap = word.begin - cursor
            when {
                gap > 0L -> result.add(
                    LyricWord(begin = cursor, end = word.begin, duration = gap, text = carried)
                )
                result.isNotEmpty() -> result.last().text =
                    result.last().text.orEmpty() + carried
                else -> word.text = carried + word.text.orEmpty()
            }
            pending.clear()
        }

        if (word.duration <= 0L) word.duration = word.end - word.begin
        result.add(word)
        cursor = word.end
    }

    // 末尾残留的坏时间词
    if (pending.isNotEmpty()) {
        val carried = pending.joinToString("") { it.text.orEmpty() }
        if (result.isNotEmpty()) {
            result.last().text = result.last().text.orEmpty() + carried
        } else {
            result.add(LyricWord(begin = 0L, end = 100L, duration = 100L, text = carried))
        }
    }

    return result.normalizeSortByTime().mergeAsciiFragments()
}

/**
 * 合并没有空格隔开的 ASCII 字母/数字片段。
 *
 * 有些歌词源把英文复合词按音节拆成多个带时间戳的片段（under + ground），
 * 中间没有独立的空格词，这时候要拼回一个词显示。
 *
 * 合并条件：两个片段都是纯 ASCII 字母数字，且前一个的 end 正好等于后一个的 begin。
 */
private fun List<LyricWord>.mergeAsciiFragments(): List<LyricWord> {
    if (size < 2) return this
    val merged = ArrayList<LyricWord>(size)
    for (word in this) {
        val prev = merged.lastOrNull()
        if (prev != null && prev.canJoinAsciiFragment(word)) {
            prev.text = prev.text.orEmpty() + word.text.orEmpty()
            prev.end = maxOf(prev.end, word.end)
            prev.duration += word.duration
        } else {
            merged.add(word)
        }
    }
    return merged
}

private fun LyricWord.canJoinAsciiFragment(next: LyricWord): Boolean =
    text.isAsciiFragment() && next.text.isAsciiFragment() && end == next.begin

private fun String?.isAsciiFragment(): Boolean =
    !isNullOrEmpty() && all { it.isLetterOrDigit() && it.code < 128 }
