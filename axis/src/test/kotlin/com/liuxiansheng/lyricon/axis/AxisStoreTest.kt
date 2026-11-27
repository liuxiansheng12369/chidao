/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis

import com.liuxiansheng.lyricon.axis.core.AxisLine
import com.liuxiansheng.lyricon.axis.core.AxisStore
import com.liuxiansheng.lyricon.axis.core.ManualAxis
import com.liuxiansheng.lyricon.axis.core.Precision
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** AxisStore.save(replacingKey) —— 重打旧轴时写回同一份，不新增一条。 */
class AxisStoreTest {

    private fun tempStore(): AxisStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "axis-store-test-${System.nanoTime()}")
        dir.mkdirs()
        return AxisStore(dir)
    }

    private fun axis(key: String, durationMs: Long, createdAt: Long) = ManualAxis(
        songKey = key,
        title = "做不到的承诺就不要对我说（DJ版）",
        artist = "高高在这呢",
        durationMs = durationMs,
        leadMs = 180,
        precision = Precision.LINE,
        createdAt = createdAt,
        lines = listOf(AxisLine(0, "第一句", 1000L, 5000L)),
    )

    @Test
    fun `重打后时长变了_仍写回原文件_索引里只有一条`() {
        val store = tempStore()
        val first = store.save(axis("做不到的承诺就不要对我说|高高在这呢|223", 223_124L, 1L))
        assertEquals(1, store.loadIndex().size)

        // 用户重打时把时长对齐到播放器的 202 秒，songKey 跟着变了
        val edited = axis("做不到的承诺就不要对我说|高高在这呢|202", 202_405L, 2L)
        store.save(edited, replacingKey = first.songKey)

        val index = store.loadIndex()
        assertEquals(1, index.size)
        assertEquals(edited.songKey, index[0].songKey)
        // 同一个文件，不留 orphan
        assertEquals(first.fileName, index[0].fileName)
        assertEquals(202_405L, store.load(edited.songKey)!!.durationMs)
    }

    @Test
    fun `不传 replacingKey_正常新建一条`() {
        val store = tempStore()
        store.save(axis("a|b|100", 100_000L, 1L))
        store.save(axis("c|d|200", 200_000L, 2L))
        assertEquals(2, store.loadIndex().size)
    }

    @Test
    fun `重打时名字没变_还是原来那一条`() {
        val store = tempStore()
        val first = store.save(axis("a|b|100", 100_000L, 1L))
        store.save(axis("a|b|100", 100_000L, 3L), replacingKey = first.songKey)
        val index = store.loadIndex()
        assertEquals(1, index.size)
        assertEquals(3L, index[0].updatedAt)
    }
}
