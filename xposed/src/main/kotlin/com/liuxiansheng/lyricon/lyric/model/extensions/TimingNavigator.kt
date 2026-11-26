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

import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.ILyricTiming

/**
 * 毫秒级时间轴导航器，支持重叠歌词检索。
 *
 * 前置条件：`source` 必须按 [ILyricTiming.begin] 升序排列。
 *
 * 查询分两层：
 * 1. 先用 `maxEndSoFar`（前缀里最大的 end）二分，把"结束时间够不到当前位置"的
 *    记录整批排除；
 * 2. 再在剩下的小区间里逐条核对 `begin <= position <= end`。
 *
 * 顺序播放时走"短步长前向探测"：从上一次命中的位置往后挪，最多挪 4 条，
 * 超过就退回二分，保证整体还是 logN。
 */
class TimingNavigator<T : ILyricTiming>(
    val source: Array<T>
) {
    /** 记录条数。 */
    val size: Int = source.size

    /**
     * 前缀最大结束时间。单调不减，供 [resolveOverlapping] 二分用。
     */
    val maxEndSoFar: LongArray = LongArray(size)

    init {
        var runningMax = -1L
        for (i in source.indices) {
            if (source[i].end > runningMax) runningMax = source[i].end
            maxEndSoFar[i] = runningMax
        }
    }

    /** 上一次命中的下标，顺序播放时从这里往前探。 */
    var lastMatchedIndex: Int = -1
        private set

    /** 上一次查询的时间戳。 */
    var lastQueryPosition: Long = -1L
        private set

    /**
     * 取 [position] 处匹配的第一条记录；锚点条不覆盖当前位置时，
     * 再向前找真正覆盖它的重叠条。
     */
    fun first(position: Long): T? {
        val index = findTargetIndex(position)
        updateCache(position, index)
        if (index < 0) return null

        if (position <= source[index].end) return source[index]

        resolveOverlapping(position, index) { return it }
        return null
    }

    /**
     * 遍历 [position] 处所有覆盖它的记录（含重叠）。
     * @return 匹配条数
     */
    inline fun forEachAt(position: Long, action: (T) -> Unit): Int {
        if (size == 0) return 0

        val anchor = findTargetIndex(position)
        updateCache(position, anchor)

        if (anchor < 0) return 0

        return resolveOverlapping(position, anchor, action)
    }

    /**
     * 遍历 [position] 处的记录；当前位置没有任何记录时，退到最近的一条历史记录。
     */
    inline fun forEachAtOrPrevious(position: Long, action: (T) -> Unit): Int {
        val count = forEachAt(position, action)
        if (count > 0) return count

        val previous = findPreviousEntry(position) ?: return 0
        action(previous)
        return 1
    }

    /** 找 begin 小于等于 [position] 的最后一条。 */
    fun findPreviousEntry(position: Long): T? {
        val idx = findUpperBound(position)
        return if (idx >= 0) source[idx] else null
    }

    /** 手动跳进度 / 换歌后清掉顺序播放缓存。 */
    @Suppress("unused")
    fun resetCache() {
        lastMatchedIndex = -1
        lastQueryPosition = -1L
    }

    /**
     * 定位 begin 小于等于 [position] 的最后一个下标。
     * 顺序播放走短步长探测，超过 4 步改二分。
     */
    fun findTargetIndex(position: Long): Int {
        if (size == 0 || position < source[0].begin) return -1

        val last = lastMatchedIndex
        if (last >= 0 && position >= lastQueryPosition && position >= source[last].begin) {
            var idx = last
            var steps = 0
            while (idx + 1 < size && source[idx + 1].begin <= position) {
                idx++
                steps++
                if (steps > 4) return findUpperBound(position)
            }
            return idx
        }

        return findUpperBound(position)
    }

    /** 标准二分：找 begin <= position 的最后一个下标，找不到返回 -1。 */
    private fun findUpperBound(position: Long): Int {
        var low = 0
        var high = size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (source[mid].begin <= position) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * 重叠加载核心。靠 [maxEndSoFar] 的单调性二分出起点，再线性核对。
     */
    @PublishedApi
    internal inline fun resolveOverlapping(
        position: Long,
        anchorIndex: Int,
        action: (T) -> Unit
    ): Int {
        var low = 0
        var high = anchorIndex
        var start = anchorIndex

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (maxEndSoFar[mid] >= position) {
                start = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        var count = 0
        for (i in start..anchorIndex) {
            val entry = source[i]
            if (position >= entry.begin && position <= entry.end) {
                action(entry)
                count++
            }
        }
        return count
    }

    /** 更新顺序播放缓存。 */
    @PublishedApi
    internal fun updateCache(position: Long, index: Int) {
        lastQueryPosition = position
        lastMatchedIndex = index
    }
}
