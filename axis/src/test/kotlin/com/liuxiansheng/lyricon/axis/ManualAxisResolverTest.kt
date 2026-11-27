/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis

import com.liuxiansheng.lyricon.axis.core.AxisIndexEntry
import com.liuxiansheng.lyricon.axis.core.ManualAxisResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 人工轴容差命中：只认「括号前的歌名 + 时长」，歌手不参与。 */
class ManualAxisResolverTest {

    private fun entry(title: String, artist: String, durationMs: Long): AxisIndexEntry =
        AxisIndexEntry(
            songKey = "$title|$artist|${durationMs / 1000}",
            title = title,
            artist = artist,
            durationMs = durationMs,
            lineCount = 44,
            charCount = 474,
            fileName = "axis-test.json",
            updatedAt = 0L,
        )

    @Test
    fun `歌手不同_歌名时长对上_命中`() {
        // 实测那条：存的是「高高在这呢 / 223 秒」，在播的是「辉Hue / 202 秒」
        // 时长差 21 秒属于两首歌的版本差异，不该串
        val e = entry("做不到的承诺就不要对我说(DJ版)", "高高在这呢", 223_124L)
        val key = "做不到的承诺就不要对我说|辉hue|202"
        val hit = ManualAxisResolver.pickLooseEntry(listOf(e), key)
        // 差 21 秒 → 不命中
        assertNull(hit)
    }

    @Test
    fun `歌手不同_时长差1秒内_命中`() {
        val e = entry("做不到的承诺就不要对我说(DJ版)", "高高在这呢", 202_900L)
        val key = "做不到的承诺就不要对我说|辉hue|202"
        val hit = ManualAxisResolver.pickLooseEntry(listOf(e), key)
        assertTrue(hit != null)
        assertEquals("做不到的承诺就不要对我说(DJ版)", hit!!.title)
        assertEquals(900L, ManualAxisResolver.durationDelta(key, e.durationMs))
    }

    @Test
    fun `括号前后缀不同_不影响命中`() {
        val e = entry("做不到的承诺就不要对我说（舒缓版）", "辉Hue", 202_405L)
        val key = "做不到的承诺就不要对我说|某某|202"
        assertTrue(ManualAxisResolver.pickLooseEntry(listOf(e), key) != null)
    }

    @Test
    fun `播放器没给时长_只按歌名认`() {
        val e = entry("做不到的承诺就不要对我说(DJ版)", "高高在这呢", 223_124L)
        val key = "做不到的承诺就不要对我说|辉hue|0"
        assertTrue(ManualAxisResolver.pickLooseEntry(listOf(e), key) != null)
    }

    @Test
    fun `歌名对不上_绝不命中`() {
        val e = entry("你在他乡还好吗", "dj老绷带", 202_000L)
        val key = "做不到的承诺就不要对我说|辉hue|202"
        assertNull(ManualAxisResolver.pickLooseEntry(listOf(e), key))
    }

    @Test
    fun `多条命中取时长最接近的一条`() {
        val far = entry("风从窗缝", "a", 203_500L)
        val near = entry("风从窗缝", "b", 202_200L)
        val key = "风从窗缝|c|202"
        val hit = ManualAxisResolver.pickLooseEntry(listOf(far, near), key)
        assertEquals("b", hit!!.artist)
    }
}
