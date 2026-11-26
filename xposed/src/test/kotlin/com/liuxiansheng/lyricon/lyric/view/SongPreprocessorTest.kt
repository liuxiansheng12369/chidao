package com.liuxiansheng.lyricon.hyper.lyric.view

import com.liuxiansheng.lyricon.hyper.lyric.model.RichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SongPreprocessorTest {

    @Test
    fun `无歌词时不插任何占位行`() {
        val song = Song(name = "无歌词", artist = "歌手", duration = 100_000L)
        val lines = SongPreprocessor(TitleSlot.NAME_ARTIST).prepare(song)

        assertEquals(0, lines.size)
    }

    @Test
    fun `首句不足一秒时不插占位行`() {
        val song = Song(
            name = "歌",
            duration = 10_000L,
            lyrics = listOf(RichLyricLine(begin = 500L, end = 3_000L, text = "第一句"))
        )
        val lines = SongPreprocessor(TitleSlot.NAME_ARTIST).prepare(song)

        assertEquals(1, lines.size)
        assertEquals("第一句", lines[0].text)
    }

    @Test
    fun `首句留白足够时插入歌名占位行`() {
        val song = Song(
            name = "歌",
            artist = "人",
            duration = 10_000L,
            lyrics = listOf(RichLyricLine(begin = 2_000L, end = 4_000L, text = "第一句"))
        )
        val lines = SongPreprocessor(TitleSlot.NAME_ARTIST).prepare(song)

        assertEquals(2, lines.size)
        assertEquals("歌 - 人", lines[0].text)
        assertEquals(1_999L, lines[0].end)
        assertEquals("第一句", lines[1].text)
        assertEquals(lines[0], lines[1].previous)
        assertEquals(lines[1], lines[0].next)
    }

    @Test
    fun `NONE 样式不插占位行`() {
        val song = Song(
            name = "歌",
            duration = 10_000L,
            lyrics = listOf(RichLyricLine(begin = 2_000L, end = 4_000L, text = "第一句"))
        )
        val lines = SongPreprocessor(TitleSlot.NONE).prepare(song)

        assertEquals(1, lines.size)
        assertEquals("第一句", lines[0].text)
    }

    @Test
    fun `无歌词且无歌名时不插入任何行`() {
        val song = Song(name = null, artist = null, duration = 10_000L)
        val lines = SongPreprocessor(TitleSlot.NAME_ARTIST).prepare(song)

        assertEquals(0, lines.size)
    }
}
