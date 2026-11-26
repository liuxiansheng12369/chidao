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

import com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord
import com.liuxiansheng.lyricon.hyper.lyric.model.RichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.IRichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.lyricMetadataOf
import com.liuxiansheng.lyricon.hyper.lyric.processor.LyricTextPolish

/**
 * 把一首歌补成可直接排布的歌词序列。
 *
 * 只做两件事：
 * 1. 首句前留白超过 [MIN_PLACEHOLDER_DURATION_MS] 时，在开头插一行歌名占位；
 * 2. 整首没有歌词时**不插任何占位行**：歌词槽留空，歌名交给媒体信息层正常显示。
 *
 * 产出的 [TimedLine] 用双向链表串起来，上下游可以直接取前后句。
 */
internal class SongPreprocessor(private val placeholder: TitleSlot) {

    companion object {
        /** 首句前至少留这么久才值得插占位行；不到 1 秒没有空窗，插了反而多一行。 */
        internal const val MIN_PLACEHOLDER_DURATION_MS = 1_000L

        /** 标记这一行是歌名占位。 */
        internal const val METADATA_TITLE_LINE = "TitleLine"

        /** 标记这一行是倒计时行。 */
        internal const val METADATA_COUNTDOWN_LINE = "CountdownLine"

        /** 没词时那一行歌名的时长：取够长，让它在整首歌里都不会被判成"唱完"。 */
        internal const val EMPTY_PLACEHOLDER_DURATION_MS = 6L * 60L * 60L * 1000L

    }

    fun prepare(song: Song): List<TimedLine> {
        fillGap(song)
        val chain = ArrayList<TimedLine>()
        var prev: TimedLine? = null
        song.lyrics.orEmpty().forEach { lyric ->
            val node = TimedLine(lyric)
            node.previous = prev
            prev?.next = node
            chain += node
            prev = node
        }
        return chain
    }

    /** 必要时往 song.lyrics 里插占位行；不需要就不动它。 */
    private fun fillGap(song: Song) {
        val lines = song.lyrics?.toMutableList() ?: mutableListOf()

        if (lines.isEmpty()) {
            // 整首没歌词（纯音乐 / 源还没取到词）：往歌词槽放一行歌名，
            // 否则左右槽都是歌词内容时，岛上就只剩封面和波形、连歌名都没了。
            // 这一行不拆字、时长按整个播放周期给足 —— 它不该被时间轴判成"唱完了"而消失。
            if (placeholder == TitleSlot.NONE) return
            val title = displayTitle(song) ?: return
            song.lyrics = mutableListOf(
                RichLyricLine(
                    end = EMPTY_PLACEHOLDER_DURATION_MS,
                    duration = EMPTY_PLACEHOLDER_DURATION_MS,
                    text = title
                ).apply {
                    metadata = lyricMetadataOf(METADATA_TITLE_LINE to "true")
                }
            )
            return
        }

        val first = lines.first()
        if (first.begin < MIN_PLACEHOLDER_DURATION_MS) return
        if (placeholder == TitleSlot.NONE) return

        val title = displayTitle(song) ?: return
        val at = if (first.begin > 1) first.begin - 1 else first.begin
        lines.add(0, titleLine(at, at, title))
        song.lyrics = lines
    }

    /**
     * 造一行占位。
     *
     * 歌名按字符拆词、时长均分：这样占位行也走逐字推进，
     * 而不是整行一次性点亮（整行折叠词是线性渐变，跟歌词不一个手感）。
     */
    private fun titleLine(
        end: Long,
        duration: Long,
        text: String
    ): RichLyricLine = RichLyricLine(
        end = end,
        duration = duration,
        text = text
    ).apply {
        metadata = lyricMetadataOf(METADATA_TITLE_LINE to "true")

        if (duration > 0L && text.isNotBlank()) {
            val count = text.length
            val step = duration / count
            val start = (end - duration).coerceAtLeast(0L)
            words = text.mapIndexed { i, ch ->
                val b = start + i * step
                LyricWord(
                    begin = b,
                    end = (b + step).coerceAtMost(end),
                    duration = step,
                    text = ch.toString()
                )
            }
        }
    }

    /** 按占位样式拼标题文本。 */
    private fun displayTitle(song: Song): String? {
        // 歌名/艺人来自 MediaMetadata 原文时没经过歌词链路的加工，这里再走一遍
        val name = song.name?.let { LyricTextPolish.applyToText(it) }
        val artist = song.artist?.let { LyricTextPolish.applyToText(it) }
        return when (placeholder) {
            TitleSlot.NONE -> null
            TitleSlot.NAME_ARTIST -> when {
                !name.isNullOrBlank() && !artist.isNullOrBlank() -> "$name - $artist"
                !name.isNullOrBlank() -> name
                else -> null
            }
            TitleSlot.NAME -> name?.takeIf { it.isNotBlank() }
        }
    }
}

/** 带前后指针的歌词行包装。 */
internal class TimedLine(val line: IRichLyricLine) : IRichLyricLine by line {
    var previous: TimedLine? = null
    var next: TimedLine? = null

    /**
     * 行结束时刻取「行 end / 主词最大 end / 副词最大 end」的最大值。
     *
     * provider 给的 `line.end` 经常早于最后一个字自己的 end（逐字数据先到、行时间
     * 标在原句结尾），按原值切行会在最后一个字还没唱完时就把这行判成结束 → 表现
     * 就是「唱到词尾歌词提前消失/跳走」。老版 lyricon 用这层 max() 兜住，V3 移植
     * 时漏了，这里补回。
     */
    override var end: Long
        get() {
            val mainEnd = maxOf(line.end, line.words?.maxOfOrNull { it.end } ?: 0L)
            val secondaryEnd = line.secondaryWords?.maxOfOrNull { it.end } ?: 0L
            return maxOf(mainEnd, secondaryEnd)
        }
        set(value) {
            line.end = value
        }
}
