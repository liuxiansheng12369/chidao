/*
 * Lyricon V3 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 Lyricon V3 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis

import com.liuxiansheng.lyricon.axis.core.AxisTokenizer
import com.liuxiansheng.lyricon.axis.ui.AxisState
import com.liuxiansheng.lyricon.axis.ui.DraftLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核对页可编辑 + 身份对齐的回归锁。
 *
 * 这几条都是"改完看着没问题、实际上会串行或命中不了"的地方，
 * 以后动 AxisState 先跑这一组。
 */
class ScreensStateTest {

    /** 搭一个 n 行的草稿，行首时间全空。 */
    private fun stateWith(lines: List<String>, durationMs: Long = 200_000L, playerTitle: String = "", playerArtist: String = ""): AxisState {
        val s = AxisState(playerTitle, playerArtist, durationMs)
        lines.forEach { s.draft.add(DraftLine(it)) }
        s.begins.clear()
        s.draft.indices.forEach { s.begins.add(null) }
        s.wordsDone.clear()
        s.draft.indices.forEach { i ->
            s.wordsDone.add(MutableList(AxisTokenizer.split(s.draft[i].text).size) { null })
        }
        return s
    }

    // —— 第 2 节：手改歌名不能让时长跟着跑掉 ——

    @Test
    fun `手改了歌名_时长仍然对齐到播放器`() {
        val s = stateWith(
            emptyList(),
            durationMs = 223_000L,
            playerTitle = "做不到的承诺就不要对我说（舒缓版）",
            playerArtist = "辉Hue",
        )
        // 用户在核对页把名字改成没括号的写法
        s.updateIdentity(newTitle = "做不到的承诺就不要对我说")
        assertEquals(223_000L, s.durationMs)

        // 播放器真实时长 202.405 秒（AxisState 构造时传的就是它，这里模拟"候选把时长改成 223"）
        val s2 = AxisState("做不到的承诺就不要对我说（舒缓版）", "辉Hue", 202_405L)
        s2.durationMs = 223_000L
        s2.updateIdentity(newTitle = "做不到的承诺就不要对我说")
        s2.alignToPlayer()
        // 关键：identityEdited 只挡名字，不挡时长；否则键里是 223，播放器是 202，永远 miss
        assertEquals(202_405L, s2.durationMs)
        assertEquals("做不到的承诺就不要对我说", s2.title)
    }

    @Test
    fun `没手改过名字_两个对齐函数都会换成播放器的名字`() {
        val s = stateWith(
            emptyList(),
            durationMs = 202_405L,
            playerTitle = "做不到的承诺就不要对我说（舒缓版）",
            playerArtist = "辉Hue",
        )
        s.title = "做不到的承诺就不要对我说(DJ版)"
        s.artist = "高高在这呢"
        s.durationMs = 223_000L
        assertTrue(s.alignToPlayer())
        assertEquals("做不到的承诺就不要对我说（舒缓版）", s.title)
        assertEquals("辉Hue", s.artist)
        assertEquals(202_405L, s.durationMs)
    }

    @Test
    fun `forceAlign 不管歌名像不像都换_歌词来自别人翻唱也能存对`() {
        val s = stateWith(
            emptyList(),
            durationMs = 202_405L,
            playerTitle = "做不到的承诺就不要对我说（舒缓版）",
            playerArtist = "辉Hue",
        )
        s.title = "完全不相干的歌名"
        s.artist = "别人"
        assertTrue(s.forceAlignToPlayer())
        assertEquals("做不到的承诺就不要对我说（舒缓版）", s.title)
        assertEquals("辉Hue", s.artist)
    }

    // —— 第 3 节：改字 / 删行 ——

    @Test
    fun `改行文字后_这一行的字级时间按新字数重建`() {
        val s = stateWith(listOf("一二三四"))
        assertEquals(4, s.wordsDone[0].size)
        s.updateLineText(0, "一二")
        assertEquals(2, s.wordsDone[0].size)
        assertEquals("一二", s.draft[0].text)
    }

    @Test
    fun `删一行_三个列表同步删_后面的时间不错位`() {
        val s = stateWith(listOf("一", "二", "三"))
        s.begins[0] = 1_000L
        s.begins[1] = 2_000L
        s.begins[2] = 3_000L
        s.removeLine(1)
        assertEquals(2, s.draft.size)
        assertEquals(2, s.begins.size)
        assertEquals(2, s.wordsDone.size)
        // 原来第三行现在在 index=1，时间必须还是 3000，不能变成 2000
        assertEquals("三", s.draft[1].text)
        assertEquals(3_000L, s.begins[1])
    }

    // —— 第 3.4 节：超出歌长的行自动丢 ——

    @Test
    fun `带时间的行超出歌长_自动取消勾选并提示`() {
        val s = stateWith(listOf("一", "二", "三"), durationMs = 90_000L)
        s.begins[0] = 1_000L
        s.begins[1] = 50_000L
        s.begins[2] = 100_000L      // 超出 90 秒
        s.dropLinesOutOfRange()
        assertTrue(s.draft[0].keep)
        assertTrue(s.draft[1].keep)
        assertFalse(s.draft[2].keep)
        assertTrue(s.dropNotice.contains("已自动舍弃 1 句"))
        assertTrue(s.dropNotice.contains("01:30"))
    }

    @Test
    fun `没时长的行判断不了_不误杀`() {
        val s = stateWith(listOf("一", "二", "三"), durationMs = 90_000L)
        s.dropLinesOutOfRange()
        assertTrue(s.draft.all { it.keep })
        assertEquals("", s.dropNotice)
    }

    // —— 第四节：保存截断到"最后一句敲过的" ——

    @Test
    fun `只存敲到最后一句_后面没敲的行不进轴`() {
        val s = stateWith(listOf("一", "二", "三", "四", "五"), durationMs = 200_000L)
        s.begins[0] = 1_000L
        s.begins[1] = 5_000L
        s.begins[2] = 9_000L
        // 四、五没敲
        val axis = s.buildAxis()
        assertEquals(3, axis!!.lines.size)
        assertEquals("三", axis.lines[2].text)
    }

    @Test
    fun `一句都没敲_buildAxis 会产出全 0 时间_所以必须由 saveTo 拦`() {
        val s = stateWith(listOf("一", "二"))
        val axis = s.buildAxis()
        // 这就是"废轴"长什么样：44 句全挤在第 0 秒。saveTo 的「一句都没敲」拦截专门挡这个，
        // 否则会把已经敲好的同首歌的轴覆盖掉。
        assertTrue(axis != null)
        assertTrue(axis!!.lines.all { it.beginMs == 0L })
    }

    @Test
    fun `全不勾选_不产出轴`() {
        val s = stateWith(listOf("一", "二"))
        s.draft.forEach { it.keep = false }
        s.begins[0] = 1_000L
        assertNull(s.buildAxis())
    }

    // —— 第五节：重打旧轴记下 editingKey ——

    @Test
    fun `新建时 editingKey 是空的_重打旧轴才填`() {
        val s = stateWith(listOf("一"))
        assertEquals("", s.editingKey)
        // openSaved 需要 Context 才能读盘，这里只锁字段默认值；
        // 真正的写回行为由 AxisStoreTest 的 replacingKey 用例覆盖。
    }
}
