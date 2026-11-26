package com.liuxiansheng.lyricon.xposed.systemui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用汽水真实样本验证解析器行为契约。
 *
 * 样本：docs\样本\汽水-lyricInfo-一个人挺好-rawLyric.txt（逐字 ELRC 原文，51 行）
 *
 * 纯文本路径不经过 JSONObject，单测可直接跑核心算法。
 */
class LyricInfoParserTest {

    private fun sampleFile(): File {
        val direct = File("D:/Android/lyricon-self/docs/样本/汽水-lyricInfo-一个人挺好-rawLyric.txt")
        if (direct.exists()) return direct
        var root: File? = File(System.getProperty("user.dir"))
        while (root != null) {
            val candidate = File(root, "docs/样本/汽水-lyricInfo-一个人挺好-rawLyric.txt")
            if (candidate.exists()) return candidate
            root = root.parentFile
        }
        return direct
    }

    @Test
    fun parsesSodaSample_lineCountAndTimeline() {
        val file = sampleFile()
        assertTrue("样本文件不存在: ${file.absolutePath}", file.exists())
        val text = file.readText(Charsets.UTF_8)

        val payload = LyricInfoParser.parsePlainText(text)
        assertNotNull("纯文本兜底应能解析出结果", payload)
        val lines = payload!!.song.lyrics
        assertNotNull("歌词行不应为空", lines)
        assertEquals("可解析行数应为 48", 48, lines!!.size)

        lines.forEachIndexed { i, line ->
            assertTrue("第 ${i + 1} 行 end(${line.end}) 应大于 begin(${line.begin})", line.end > line.begin)
            assertEquals("第 ${i + 1} 行 duration 应等于 end-begin", line.end - line.begin, line.duration)
            assertTrue("第 ${i + 1} 行文本不应为空", !line.text.isNullOrBlank())
        }
    }

    @Test
    fun parsesSodaSample_lastLineWordTiming() {
        val text = sampleFile().readText(Charsets.UTF_8)
        val lines = LyricInfoParser.parsePlainText(text)!!.song.lyrics!!
        val last = lines.last()
        val words = last.words
        assertNotNull("末句应带逐字时间", words)

        val expected = listOf(
            Triple("就", 234950L, 235310L),
            Triple("这", 235310L, 235520L),
            Triple("样", 235520L, 236030L),
            Triple("吧", 236030L, 236360L),
            Triple("一", 236360L, 236750L),
            Triple("个", 236750L, 237080L),
            Triple("人", 237080L, 237430L),
            Triple("挺", 237430L, 237790L),
            Triple("好", 237790L, 240120L)
        )

        assertEquals("末句词数应为 9", expected.size, words!!.size)
        expected.forEachIndexed { i, (ch, b, e) ->
            val w = words[i]
            assertEquals("第 ${i + 1} 个字应为 '$ch'，实际 '${w.text}'", ch, w.text)
            assertEquals("'$ch' 的 begin 不符", b, w.begin)
            assertEquals("'$ch' 的 end 不符", e, w.end)
        }
        assertEquals("末行 end 应等于收尾标签 240120", 240120L, last.end)
    }

    @Test
    fun parsesSodaSample_noDegenerateWords() {
        val text = sampleFile().readText(Charsets.UTF_8)
        val lines = LyricInfoParser.parsePlainText(text)!!.song.lyrics!!
        var wordCount = 0
        lines.forEach { line ->
            line.words?.forEach { w ->
                wordCount++
                assertTrue("词 '${w.text}' 的 end 应不小于 begin", w.end >= w.begin)
                assertTrue("词 '${w.text}' 的文本不应为空", !w.text.isNullOrEmpty())
            }
        }
        assertTrue("逐字样本应解析出词，实际 $wordCount 个", wordCount > 100)
    }

    @Test
    fun normalizesOutOfOrderTimeline() {
        val src = """
            [00:20.000]第三句
            [00:05.000]第一句
            [00:30.000]第四句
            [00:10.000]第二句
        """.trimIndent()

        val lines = LyricInfoParser.parsePlainText(src)!!.song.lyrics!!
        assertEquals("乱序输入仍应解析出 4 行", 4, lines.size)

        assertEquals(
            "时间轴应被校正为单调递增",
            listOf(5000L, 10000L, 20000L, 30000L),
            lines.map { it.begin }
        )
        assertEquals(
            "文本应与时间戳重新对齐",
            listOf("第一句", "第二句", "第三句", "第四句"),
            lines.map { it.text }
        )
        lines.forEachIndexed { i, line ->
            assertTrue("第 ${i + 1} 行 end 应大于 begin", line.end > line.begin)
            assertEquals("第 ${i + 1} 行 duration 应等于 end-begin", line.end - line.begin, line.duration)
        }
    }
}
