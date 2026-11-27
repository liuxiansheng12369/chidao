/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis

import com.liuxiansheng.lyricon.axis.core.AxisLine
import com.liuxiansheng.lyricon.axis.core.AxisTokenizer
import com.liuxiansheng.lyricon.axis.core.AxisValidator
import com.liuxiansheng.lyricon.axis.core.IssueKind
import com.liuxiansheng.lyricon.axis.core.LrcWriter
import com.liuxiansheng.lyricon.axis.core.ManualAxis
import com.liuxiansheng.lyricon.axis.core.SongKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisCoreTest {

    @Test
    fun fillsMiddleGapEvenly() {
        val filled = AxisValidator.fillMissing(listOf(0L, null, null, 3000L), 10_000L)
        assertEquals(listOf(0L, 1000L, 2000L, 3000L), filled)
    }

    @Test
    fun fillsTailAfterLastKnown() {
        val filled = AxisValidator.fillMissing(listOf(0L, 2000L, null, null), 40_000L)
        assertEquals(0L, filled[0])
        assertEquals(2000L, filled[1])
        assertTrue(filled[2] > 2000L)
        assertTrue(filled[3] > filled[2])
    }

    @Test
    fun flagsShortGapAndNonIncreasing() {
        val lines = listOf(
            AxisLine(0, "a", 0L, 1000L),
            AxisLine(1, "b", 100L, 2000L),
            AxisLine(2, "c", 50L, 3000L),
        )
        val issues = AxisValidator.issues(lines, 10_000L)
        assertTrue(issues.any { it.kind == IssueKind.TOO_SHORT })
        assertTrue(issues.any { it.kind == IssueKind.NOT_INCREASING })
    }

    @Test
    fun lastLineEndsAtDuration() {
        val lines = AxisValidator.toAxisLines(listOf("a", "b"), listOf(0L, 1000L), 5000L)
        assertEquals(1000L, lines.first().endMs)
        assertEquals(5000L, lines.last().endMs)
    }

    @Test
    fun lrcStampFormatsTwoDigitMinutes() {
        assertEquals("[00:05.00]", LrcWriter.stamp(5000L))
        assertEquals("[01:23.45]", LrcWriter.stamp(83_450L))
    }

    @Test
    fun songKeyIgnoresBracketSuffixAndArtistSuffix() {
        val a = SongKey.of("一个人挺好 (Live)", "杨胖雨/某某", 186_813L)
        val b = SongKey.of("一个人挺好", "杨胖雨", 186_800L)
        assertEquals(a, b)
    }

    // —— 字级（新增）——

    @Test
    fun `字级补齐_整行未敲按行内均分`() {
        val words = AxisValidator.fillWords(listOf(null, null, null, null), 1000L, 2000L)
        assertEquals(4, words.size)
        assertTrue(words[0] == 1000L)
        assertTrue(words.all { it in 1000L..2000L })
        assertTrue(words.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `字级补齐_中间缺字按前后均分`() {
        val words = AxisValidator.fillWords(listOf(1000L, null, null, 1600L), 1000L, 2000L)
        assertEquals(1000L, words[0])
        assertEquals(1600L, words[3])
        assertTrue(words[1] in 1150L..1250L)
        assertTrue(words[2] in 1300L..1450L)
    }

    @Test
    fun `切字_中文按单字`() {
        assertEquals(listOf("风", "从", "窗", "缝"), AxisTokenizer.split("风从窗缝"))
    }

    @Test
    fun `切字_英文成词`() {
        assertEquals(listOf("hello", "world"), AxisTokenizer.split("hello world"))
    }

    @Test
    fun `旧数据兼容_无words按行级`() {
        val json = """{"schema":1,"songKey":"a|b|100","title":"a","artist":"b","durationMs":100000,
            "leadMs":180,"createdAt":1,"lines":[{"i":0,"text":"x","beginMs":0,"endMs":1000}]}"""
        val axis = Json { ignoreUnknownKeys = true }.decodeFromString<ManualAxis>(json)
        assertEquals(1, axis.lines.size)
        assertEquals(0, axis.lines[0].words.size)
    }

    @Test
    fun `同一首_版本后缀不算不同歌`() {
        // 实测踩过的组合：搜到的是 DJ 版，在播的是舒缓版
        assertTrue(
            SongKey.sameSong(
                "做不到的承诺就不要对我说(DJ版)",
                "做不到的承诺就不要对我说（舒缓版）"
            )
        )
        assertTrue(SongKey.sameSong("做不到的承诺就不要对我说", "做不到的承诺就不要对我说（舒缓版）"))
    }

    @Test
    fun `同一首_不含括号的空壳也对得上`() {
        assertTrue(SongKey.sameSong("Faded", "Faded (Remix)"))
        assertTrue(SongKey.sameSong("风从窗缝", "风从窗缝 "))
    }

    @Test
    fun `同一首_完全不相干的歌不会认错`() {
        assertTrue(!SongKey.sameSong("做不到的承诺就不要对我说", "你在他乡还好吗"))
        assertTrue(!SongKey.sameSong("", "任意歌名"))
    }
}
