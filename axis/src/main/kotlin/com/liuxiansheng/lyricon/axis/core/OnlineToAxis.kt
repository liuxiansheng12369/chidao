/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * 模块：打轴器 :axis · 在线词 → 轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.core

import kotlin.math.abs

/**
 * 把一份在线歌词直接铺成一条轴。
 *
 * 这是"网上搜词 + 打轴"合起来的那一步：搜到的词只要带时间，
 * 就不用从头敲，直接变成岛上能用的轴，用户只需要整轴微调。
 *
 * 三件事必须做对，否则"能用"会变成"整条偏"：
 *
 * 1. **补空行**：LRC 里常有没时间标签的行（纯文本行、间奏），
 *    按前后两行线性插值，不能全塞 0，否则一眼看过去像歌词丢了。
 * 2. **线性缩放**：LRC 时间是按它那一版音频算的，玩家这版前奏/尾奏不同就会整条漂。
 *    用 `(播放时长 - 尾差) / LRC末行时间` 缩放一次，绝大多数跨版本偏移能直接消掉，
 *    剩下的再看 `offsetMs` 手动微调。
 * 3. **末行收尾**：最后一句用播放时长兜底，不让它无限延长。
 */
object OnlineToAxis {

    fun build(
        doc: LyricDoc?,
        title: String,
        artist: String,
        durationMs: Long,
        score: Int = 0,
    ): ManualAxis? {
        if (doc == null || doc.lines.isEmpty()) return null
        if (title.isBlank()) return null
        val lines = doc.lines
        val wordTimes = doc.wordTimes?.takeIf { doc.hasWordSync }

        // 每行的基准时间：行首时间优先，其次这一行第一个字的时间
        val base = ArrayList<Long?>(lines.size)
        for (i in lines.indices) {
            val lt = doc.lineTimes?.getOrNull(i)
            if (lt != null) {
                base += lt
            } else {
                base += wordTimes?.getOrNull(i)?.firstOrNull { it != null }
            }
        }
        // 一个时间都没有 = 纯文本，做不成轴
        if (base.none { it != null }) return null

        // 线性缩放：只在离谱值之内用（0.8~1.25），避免把时长识别错的数据放大成灾难
        val lastBase = base.filterNotNull().maxOrNull() ?: 0L
        val rawScale = if (durationMs > 2000L && lastBase > 500L) {
            (durationMs - 2000L).toDouble() / lastBase.toDouble()
        } else {
            1.0
        }
        val useScale = rawScale in 0.8..1.25 && abs(rawScale - 1.0) > 0.01

        fun fix(ms: Long): Long =
            if (useScale) (ms * rawScale).toLong().coerceAtLeast(0L) else ms.coerceAtLeast(0L)

        val filled = fillGaps(base, durationMs)
        val starts = filled.map { fix(it) }

        val axisLines = lines.indices.map { i ->
            val begin = starts[i]
            val next = starts.getOrNull(i + 1)
            val end = when {
                next != null -> (next - 20L).coerceAtLeast(begin + 50L)
                durationMs > 0L -> durationMs.coerceAtLeast(begin + 50L)
                else -> begin + 3000L
            }
            val wt = wordTimes?.getOrNull(i)
            val tokens = AxisTokenizer.splitWithRange(lines[i])
            val words = if (wt != null && wt.any { it != null }) {
                tokens.mapIndexed { k, tk ->
                    AxisWord(text = tk.text, beginMs = fix(wt.getOrNull(k) ?: begin))
                }
            } else {
                emptyList()
            }
            AxisLine(i = i, text = lines[i], beginMs = begin, endMs = end, words = words)
        }

        return ManualAxis(
            schema = 2,
            songKey = SongKey.of(title, artist, durationMs),
            title = title,
            artist = artist,
            durationMs = durationMs,
            leadMs = 0,
            // LRC 的 offset 正值表示歌词提前显示，本项目的 offsetMs 正值表示延后，符号相反
            offsetMs = (-doc.offsetTag).toInt(),
            precision = if (axisLines.any { it.words.isNotEmpty() }) Precision.CHAR else Precision.LINE,
            createdAt = System.currentTimeMillis(),
            lines = axisLines,
            origin = ManualAxis.ORIGIN_ONLINE,
            originSource = doc.source,
            originScore = score,
        )
    }

    /**
     * 没时间的行按前后插值补上。
     * 前面的全空就按平均句距往前倒推（不小于 0）；尾部的往后顺延，不超过播放时长。
     */
    private fun fillGaps(base: List<Long?>, durationMs: Long): List<Long> {
        val known = base.indices.filter { base[it] != null }
        if (known.isEmpty()) return List(base.size) { 0L }
        val avg = if (known.size >= 2) {
            val span = (base[known.last()] ?: 0L) - (base[known.first()] ?: 0L)
            (span / (known.size - 1)).coerceAtLeast(300L)
        } else {
            2500L
        }
        val out = ArrayList<Long>(base.size)
        var last: Long? = null
        for (i in base.indices) {
            val v = base[i]
            if (v != null) {
                // 倒序值不认：按上一句 + 平均句距走，不让时间往回跳
                val prev = last
                last = if (prev != null && v < prev) prev + avg else v
                out += last!!
            } else {
                val next = known.firstOrNull { it > i }
                val gap = when {
                    last != null && next != null -> {
                        val step = (((base[next] ?: 0L) - last!!) / (next - i)).coerceAtLeast(300L)
                        last!! + step
                    }
                    last != null -> {
                        val t = last!! + avg
                        if (durationMs > 0L) t.coerceAtMost(durationMs) else t
                    }
                    next != null -> {
                        ((base[next] ?: 0L) - avg * (next - i)).coerceAtLeast(0L)
                    }
                    else -> 0L
                }
                out += gap
                last = gap
            }
        }
        return out
    }
}
