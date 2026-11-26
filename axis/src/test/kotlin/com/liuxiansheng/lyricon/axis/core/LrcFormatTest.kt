package com.liuxiansheng.lyricon.axis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用 LRC 导出的格式门禁。
 *
 * 别人拿到 .lrc 是要丢进自己播放器用的，所以这里不测"能不能导出来"，
 * 只测"导出来的东西是不是干净的"：不带 # 注释、不带尖括号数字、时间戳两位小数。
 */
class LrcFormatTest {

    private fun axis(
        title: String = "情网",
        artist: String = "苏晚纾",
        durationMs: Long = 243212L,
    ): ManualAxis = ManualAxis(
        songKey = SongKey.of(title, artist, durationMs),
        title = title,
        artist = artist,
        durationMs = durationMs,
        leadMs = 0,
        createdAt = 0L,
        lines = listOf(
            AxisLine(i = 0, text = "第一句话", beginMs = 1000L, endMs = 3000L),
            AxisLine(i = 1, text = "第二句话", beginMs = 5000L, endMs = 7000L),
        ),
    )

    @Test
    fun `通用格式以标签开头 不带注释行`() {
        val text = LrcWriter.format(axis())
        val first = text.lineSequence().first()
        assertTrue("首行应该是 [ti: 标签，实际是 $first", first.startsWith("[ti:"))
        assertEquals("全文不应再有 # 注释", 0, text.count { it == '#' })
    }

    @Test
    fun `通用格式不含尖括号时间标签`() {
        val text = LrcWriter.format(axis())
        assertEquals("通用格式不应出现尖括号", 0, text.count { it == '<' })
        assertEquals("通用格式不应出现尖括号", 0, text.count { it == '>' })
    }

    @Test
    fun `时间戳是两位小数的标准写法`() {
        val text = LrcWriter.format(axis())
        val line = text.lineSequence().first { it.startsWith("[00:01") }
        assertTrue("时间戳应形如 [00:01.00]，实际是 $line", line.startsWith("[00:01.00]"))
    }

    @Test
    fun `歌名带方括号时标签不被截断`() {
        val text = LrcWriter.format(axis(title = "情网 [Live]"))
        val first = text.lineSequence().first().trim()
        assertEquals("[ti:情网 [Live)]", first)
        assertEquals("只应有一个 [ti: 标签", 1, text.lineSequence().count { it.startsWith("[ti:") })
    }

    @Test
    fun `逐字格式仍然保留尖括号 供需要的播放器使用`() {
        val words = ManualAxis(
            songKey = SongKey.of("情网", "苏晚纾", 243212L),
            title = "情网",
            artist = "苏晚纾",
            durationMs = 243212L,
            leadMs = 0,
            createdAt = 0L,
            lines = listOf(
                AxisLine(
                    i = 0,
                    text = "你好",
                    beginMs = 1000L,
                    endMs = 2000L,
                    words = listOf(
                        AxisWord(text = "你", beginMs = 1000L),
                        AxisWord(text = "好", beginMs = 1500L),
                    ),
                )
            ),
        )
        val text = LrcWriter.formatEnhanced(words)
        assertTrue("逐字格式应带 <mm:ss.xx>", text.contains("<00:01.00>你"))
    }
}
