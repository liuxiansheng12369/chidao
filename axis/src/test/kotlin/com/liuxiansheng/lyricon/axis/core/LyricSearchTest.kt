/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌词清洗与 LRCLIB 逐字文件解析都是纯文本逻辑，不联网也能测。 */
class LyricSearchTest {

    // ——— 清洗 ———

    @Test
    fun `去掉时间标签只留歌词文本`() {
        val raw = """
            [00:00.000] 作词 : 张三
            [00:01.000] 作曲 : 李四
            [00:12.500]风从窗缝里钻进来
            [00:15.200]灯光落在旧唱片上
        """.trimIndent()
        val lines = LyricSearch.clean(raw)
        assertEquals(listOf("风从窗缝里钻进来", "灯光落在旧唱片上"), lines)
    }

    @Test
    fun `QQ音乐的HTML实体会被还原`() {
        val raw = "[00:03.000]他说&#58; &quot;别走&quot; &amp; 留下"
        val lines = LyricSearch.clean(raw)
        assertEquals(listOf("他说: \"别走\" & 留下"), lines)
    }

    @Test
    fun `空歌词返回空列表不会崩`() {
        assertTrue(LyricSearch.clean("").isEmpty())
        assertTrue(LyricSearch.clean("\n\n\n").isEmpty())
    }

    // ——— LRCLIB lyricsfile（实测结构：version / metadata / lines - text/start_ms/end_ms/words） ———

    private val sampleYaml = """
        version: '1.0'
        metadata:
          title: Shape of You
          artist: Sublime Reggae Kings
          album: Vintage Reggae Beats
          duration_ms: 211000
          instrumental: false
          language: en
        lines:
        - text: The club isn't the best place to find a lover
          start_ms: 620
          end_ms: 3170
          words:
          - text: 'The '
            start_ms: 620
          - text: 'club '
            start_ms: 840
          - text: 'isn''t '
            start_ms: 1280
          - text: 'the '
            start_ms: 1540
          - text: 'best '
            start_ms: 1740
          - text: 'place '
            start_ms: 2080
          - text: 'to '
            start_ms: 2300
          - text: 'find '
            start_ms: 2500
          - text: 'a '
            start_ms: 2800
          - text: 'lover'
            start_ms: 3050
        - text: So the bar is where I go
          start_ms: 3170
          end_ms: 6000
          words:
          - text: 'So '
            start_ms: 3170
          - text: 'the '
            start_ms: 3400
          - text: 'bar '
            start_ms: 3700
          - text: 'is '
            start_ms: 4000
          - text: 'where '
            start_ms: 4300
          - text: 'I '
            start_ms: 4700
          - text: 'go'
            start_ms: 5100
    """.trimIndent()

    @Test
    fun `解析LRCLIB逐字文件的行与词时间`() {
        val lines = LrclibLyrics.parse(sampleYaml)
        assertEquals(2, lines.size)
        assertEquals("The club isn't the best place to find a lover", lines[0].text)
        assertEquals(620L, lines[0].startMs)
        assertEquals(3170L, lines[0].endMs)
        assertEquals(10, lines[0].words.size)
        // YAML 单引号里的 '' 还原成一个 '
        assertTrue(lines[0].words.any { it.first.startsWith("isn't") })
        assertEquals(3170L, lines[1].startMs)
    }

    @Test
    fun `词时间按字符位置对齐到逐字粒度`() {
        val lines = LrclibLyrics.parse(sampleYaml)
        val times = LrclibLyrics.toWordTimes(lines[0].text, lines[0].words)
        assertNotNull(times)
        // 英文按词切开，"The" 拿到 620，"club" 拿到 840
        assertEquals(620L, times!![0])
        assertEquals(840L, times[1])
    }

    @Test
    fun `文本对不上时不做逐字映射`() {
        val words = listOf("完全 " to 100L, "无关" to 200L)
        assertNull(LrclibLyrics.toWordTimes("另一句歌词", words))
        assertNull(LrclibLyrics.toWordTimes("随便", emptyList()))
    }

    @Test
    fun `空文件与无行文件都解析成空`() {
        assertTrue(LrclibLyrics.parse("").isEmpty())
        assertTrue(LrclibLyrics.parse("version: '1.0'\nmetadata:\n  title: x").isEmpty())
    }
}
