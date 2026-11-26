/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现的单元测试。
 */

package com.liuxiansheng.lyricon.hyper.lyric.model

import com.liuxiansheng.lyricon.hyper.lyric.model.extensions.TimingNavigator
import com.liuxiansheng.lyricon.hyper.lyric.model.extensions.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricModelTest {

    // ---------- 词规范化 ----------

    @Test
    fun `坏时间词前面有空隙时填成独立词`() {
        val out = listOf(
            LyricWord(begin = 0, end = 100, text = "我"),
            LyricWord(begin = -1, end = -1, text = "爱"),
            LyricWord(begin = 200, end = 300, text = "你")
        ).normalize()

        assertEquals(3, out.size)
        assertEquals(listOf("我", "爱", "你"), out.map { it.text })
        assertEquals(100L, out[1].begin)
        assertEquals(200L, out[1].end)
        assertEquals(100L, out[1].duration)
    }

    @Test
    fun `坏时间词前面没空隙时接到前词后面`() {
        val out = listOf(
            LyricWord(begin = 0, end = 100, text = "我"),
            LyricWord(begin = -1, end = -1, text = "爱"),
            LyricWord(begin = 100, end = 200, text = "你")
        ).normalize()

        assertEquals(2, out.size)
        assertEquals("我爱", out[0].text)
        assertEquals("你", out[1].text)
    }

    @Test
    fun `末尾残留的坏时间词接到最后一个词后面`() {
        val out = listOf(
            LyricWord(begin = 0, end = 100, text = "我"),
            LyricWord(begin = -1, end = -1, text = "的")
        ).normalize()

        assertEquals(1, out.size)
        assertEquals("我的", out[0].text)
    }

    @Test
    fun `空文本词被丢弃`() {
        val out = listOf(
            LyricWord(begin = 0, end = 100, text = "我"),
            LyricWord(begin = 100, end = 200, text = ""),
            LyricWord(begin = 200, end = 300, text = "你")
        ).normalize()

        assertEquals(listOf("我", "你"), out.map { it.text })
    }

    @Test
    fun `规范化不改动传入的原单词对象`() {
        val first = LyricWord(begin = 0, end = 100, text = "我")
        val bad = LyricWord(begin = -1, end = -1, text = "爱")
        val words = listOf(first, bad, LyricWord(begin = 100, end = 200, text = "你"))

        words.normalize()

        assertEquals("我", first.text)
        assertEquals("爱", bad.text)
        assertEquals(-1L, bad.begin)
    }

    // ---------- 行与歌 ----------

    @Test
    fun `Song 剔除非法行并按开始时间排序`() {
        val song = Song(lyrics = listOf(
            RichLyricLine(begin = 300, end = 400, text = "第三"),
            RichLyricLine(begin = -5, end = 100, text = "负时间"),
            RichLyricLine(begin = 100, end = 200, text = "  "),
            RichLyricLine(begin = 100, end = 200, text = "第二"),
            RichLyricLine(begin = 200, end = 200, text = "零时长")
        ))

        val out = song.normalize()

        assertEquals(2, out.lyrics?.size)
        assertEquals(listOf("第二", "第三"), out.lyrics?.map { it.text })
        assertEquals(300L, song.lyrics?.first()?.begin) // 原歌未被排序
    }

    @Test
    fun `RichLyricLine 规范化后由词拼回文本`() {
        val line = RichLyricLine(
            begin = 0, end = 500,
            text = "旧文本",
            words = listOf(
                LyricWord(begin = 0, end = 100, text = "我"),
                LyricWord(begin = 100, end = 200, text = "爱"),
                LyricWord(begin = 200, end = 300, text = "你")
            )
        )

        val out = line.normalize()

        assertEquals("我爱你", out.text)
        assertEquals("旧文本", line.text) // 原行不受影响
    }

    // ---------- 元数据 ----------

    @Test
    fun `元数据按类型取值且缺键回落默认值`() {
        val m = lyricMetadataOf("a" to "123", "b" to "x")

        assertEquals(123, m.getInt("a"))
        assertEquals(999, m.getInt("缺失", 999))
        assertEquals(123L, m.getLong("a"))
        assertEquals("x", m.getString("b"))
        assertNull(m.getString("缺失"))
        assertEquals(false, m.getBoolean("缺失"))
    }

    // ---------- 时间轴导航 ----------

    @Test
    fun `导航器命中区间并在越界时回落历史条`() {
        val nav = TimingNavigator(arrayOf(
            LyricTiming(0, 100, 100),
            LyricTiming(100, 200, 100),
            LyricTiming(200, 300, 100)
        ))

        assertEquals(100L, nav.first(150)?.begin)
        assertEquals(200L, nav.first(250)?.begin)
        assertNull(nav.first(-1))
        assertEquals(200L, nav.findPreviousEntry(999)?.begin)
    }

    @Test
    fun `重叠区间能被完整遍历`() {
        val nav = TimingNavigator(arrayOf(
            LyricTiming(0, 500, 500),
            LyricTiming(100, 200, 100),
            LyricTiming(150, 600, 450)
        ))

        val hits = mutableListOf<Long>()
        val count = nav.forEachAt(160) { hits.add(it.begin) }

        assertEquals(3, count)
        assertEquals(listOf(0L, 100L, 150L), hits)
    }
}
