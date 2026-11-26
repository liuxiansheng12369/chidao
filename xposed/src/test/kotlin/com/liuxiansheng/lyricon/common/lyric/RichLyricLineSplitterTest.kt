package com.liuxiansheng.lyricon.hyper.common.lyric

import com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class RichLyricLineSplitterTest {

    @Test
    fun `按字符索引切词_跨界词按时间比例劈开`() {
        val words = listOf(
            LyricWord(begin = 0L, end = 100L, text = "我爱"),
            LyricWord(begin = 100L, end = 200L, text = "北京"),
            LyricWord(begin = 200L, end = 300L, text = "天安门")
        )
        val (left, right) = RichLyricLineSplitter.splitWordsAtCharIndex(words, 3)

        assertEquals(listOf("我爱", "北"), left.map { it.text })
        assertEquals(listOf("京", "天安门"), right.map { it.text })
        assertEquals(150L, left.last().end)
        assertEquals(150L, right.first().begin)
        assertEquals(50L, left.last().duration)
        assertEquals(50L, right.first().duration)
    }

    @Test
    fun `切点落在词边界时整词归左侧`() {
        val words = listOf(
            LyricWord(begin = 0L, end = 100L, text = "我爱"),
            LyricWord(begin = 100L, end = 200L, text = "北京")
        )
        val (left, right) = RichLyricLineSplitter.splitWordsAtCharIndex(words, 2)

        assertEquals(listOf("我爱"), left.map { it.text })
        assertEquals(listOf("北京"), right.map { it.text })
        assertEquals(0L, left.first().begin)
        assertEquals(100L, left.first().end)
    }

    @Test
    fun `切点为 0 时整行归右侧`() {
        val words = listOf(LyricWord(begin = 0L, end = 100L, text = "我爱"))
        val (left, right) = RichLyricLineSplitter.splitWordsAtCharIndex(words, 0)

        assertEquals(0, left.size)
        assertEquals(listOf("我爱"), right.map { it.text })
    }

    @Test
    fun `切点等于总长度时整行归左侧`() {
        val words = listOf(LyricWord(begin = 0L, end = 100L, text = "我爱"))
        val (left, right) = RichLyricLineSplitter.splitWordsAtCharIndex(words, 2)

        assertEquals(listOf("我爱"), left.map { it.text })
        assertEquals(0, right.size)
    }

    @Test
    fun `空词轴切分不崩`() {
        val (left, right) = RichLyricLineSplitter.splitWordsAtCharIndex(emptyList(), 1)
        assertEquals(0, left.size)
        assertEquals(0, right.size)
    }

    @Test
    fun `Fixed 策略按左右宽度比例切分`() {
        val spec = RichLyricLineSplitter.ContainerWidthSpec.Fixed(300f, 100f)

        assertEquals(300.0, spec.targetSplitWidthPx(400f).toDouble(), 0.01)
        assertEquals(150.0, spec.targetSplitWidthPx(200f).toDouble(), 0.01)
        assertEquals(0.0, spec.targetSplitWidthPx(0f).toDouble(), 0.01)
    }

    @Test
    fun `Dynamic 策略在最大宽度处取左栏最大值`() {
        val spec = RichLyricLineSplitter.ContainerWidthSpec.Dynamic(
            leftMinWidthPx = 100f,
            leftMaxWidthPx = 500f,
            rightMinWidthPx = 100f,
            rightMaxWidthPx = 300f
        )

        assertEquals(500.0, spec.targetSplitWidthPx(800f).toDouble(), 0.01)
        assertEquals(100.0, spec.targetSplitWidthPx(200f).toDouble(), 0.01)
    }
}
