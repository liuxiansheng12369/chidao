/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricImportTest {

    @Test
    fun readsLrcTimeTagsAndMeta() {
        val raw = """
            [ti:一个人挺好]
            [ar:杨宗纬]
            [00:12.34]第一句
            [00:15,50]第二句
            [1:02]第三句
        """.trimIndent()
        val r = LocalLyricImport.parse(raw)
        assertEquals("一个人挺好", r.title)
        assertEquals("杨宗纬", r.artist)
        assertEquals(listOf("第一句", "第二句", "第三句"), r.lines)
        assertEquals(listOf(12_340L, 15_500L, 62_000L), r.begins)
        assertTrue(r.allTimed)
    }

    @Test
    fun plainTextHasNoTimingAndFallsBackToPlaying() {
        val r = LocalLyricImport.parse(
            raw = "第一句\n\n第二句\n  第三句  ",
            fallbackTitle = "当前歌",
            fallbackArtist = "当前歌手",
            fallbackDurationMs = 200_000L,
        )
        assertEquals("当前歌", r.title)
        assertEquals("当前歌手", r.artist)
        assertEquals(200_000L, r.durationMs)
        assertEquals(listOf("第一句", "第二句", "第三句"), r.lines)
        assertEquals(listOf(null, null, null), r.begins)
        assertFalse(r.hasAnyTiming)
    }

    @Test
    fun lengthTagWinsOverInference() {
        val r = LocalLyricImport.parse("[length:03:24.00]\n[00:01.00]a\n[00:05.00]b")
        assertEquals(204_000L, r.durationMs)
    }

    @Test
    fun infersDurationFromLastLineWhenNothingGiven() {
        val r = LocalLyricImport.parse("[00:01.00]a\n[00:05.00]b")
        assertEquals(7_000L, r.durationMs)
    }

    /** Enhanced LRC：行内 `<mm:ss.xx>` 抽成逐字时间，正文里不能留下尖括号。 */
    @Test
    fun readsEnhancedLrcWordTags() {
        val r = LocalLyricImport.parse(
            """
            [00:15.20]<00:15.20>不<00:15.50>让<00:15.80>你
            [00:19.80]我早就该走了
            """.trimIndent()
        )
        assertEquals(listOf("不让你", "我早就该走了"), r.lines)
        assertEquals(listOf(15_200L, 19_800L), r.begins)
        assertEquals(listOf(15_200L, 15_500L, 15_800L), r.wordsByLine[0])
        // 没挂尖括号的行保持整行没逐字，不能凭空编出时间
        assertEquals(null, r.wordsByLine[1])
    }

    /** 导出去再读回来，逐字时间要一模一样 —— 这是「把轴交给别人」的前提。 */
    @Test
    fun enhancedLrcRoundTripsThroughWriter() {
        val words = listOf(
            AxisWord("不", 15_200L),
            AxisWord("让", 15_500L),
            AxisWord("你", 15_800L),
        )
        val axis = ManualAxis(
            songKey = "不让你|某人|200",
            title = "不让你",
            artist = "某人",
            durationMs = 200_000L,
            leadMs = 180,
            createdAt = 0L,
            lines = listOf(
                AxisLine(i = 0, text = "不让你", beginMs = 15_200L, endMs = 19_800L, words = words),
                AxisLine(i = 1, text = "我早就该走了", beginMs = 19_800L, endMs = 200_000L),
            ),
        )
        val r = LocalLyricImport.parse(LrcWriter.formatEnhanced(axis))
        assertEquals("不让你", r.title)
        assertEquals("某人", r.artist)
        assertEquals(200_000L, r.durationMs)
        assertEquals(listOf("不让你", "我早就该走了"), r.lines)
        assertEquals(listOf(15_200L, 19_800L), r.begins)
        assertEquals(listOf(15_200L, 15_500L, 15_800L), r.wordsByLine[0])
        assertEquals(null, r.wordsByLine[1])
    }

    /** 一行里多个拉丁词：只有开头有时间，剩下的留 null 交给 fillWords 均分。 */
    @Test
    fun multiWordPieceLeavesRestNull()  {
        val r = LocalLyricImport.parse("[00:10.00]<00:10.00>hello world")
        assertEquals(listOf("hello world"), r.lines)
        assertEquals(listOf(10_000L, null), r.wordsByLine[0])
    }
}
