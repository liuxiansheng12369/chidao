/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * 模块：打轴器 :axis · 界面规格（组件库第一层）
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 *
 * ────────────────────────────────────────────────────────────
 * 这一份是全工程唯一的数值来源。
 * 任何页面的圆角、高度、间距、字号不再手写 dp/sp，一律从这里取。
 *
 * 当前取值 = 现有界面的真实值，所以要改也只能改这里一个地方：
 * 改一个数，所有用到它的界面同时变，不会出现"这页 18、那页 20"。
 *
 * 改值不改名。名字是语义，数值可调：例如把卡片圆角统一成 16，
 * 改 rCard 的值即可，不用去翻 Screens.kt。
 *
 * 当前档位（刻意很少，选得少才自然统一）：
 *   圆角 5 档：卡片 18 / 按钮 23 / 小胶囊 13 / 图标块 9 / 进度条 2
 *   高度 4 档：按钮 46 / 小胶囊 26 / 分割线 1 / 进度条 3
 *   间距 7 档：3 / 4 / 6 / 8 / 12 / 16 / 20
 *   字号 5 档：24 / 26（返回箭头）/ 15 / 12 / 11
 * ────────────────────────────────────────────────────────────
 */

package com.liuxiansheng.lyricon.axis.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 尺寸：圆角、高度、间距、内边。1dp 网格。 */
object Dim {
    // ── 圆角 ──
    val rCard = 18.dp      // 卡片（AxisCard）
    val rBtn = 23.dp       // 46dp 高按钮 = 半高，天然胶囊
    val rChip = 13.dp      // 26dp 小胶囊 = 半高（TinyButton）
    val rTile = 9.dp       // 图标块
    val rBar = 2.dp        // 进度条

    // ── 高度 ──
    val hBtn = 46.dp
    val hChip = 26.dp
    val hLine = 1.dp
    val hBar = 3.dp

    // ── 间距阶梯 ──
    val s0 = 3.dp
    val s1 = 4.dp
    val s2 = 6.dp
    val s3 = 8.dp
    val s4 = 12.dp
    val s5 = 16.dp
    val s6 = 20.dp

    // ── 内边 ──
    val padCard = 16.dp
    val padPageH = 20.dp
    val padPageTop = 12.dp
    val padPageBottom = 4.dp

    // ── 固定尺寸 ──
    val tileSize = 30.dp
    val backWidth = 28.dp
}

/** 字号。页标题 / 正文 / 分组标签 / 说明，四档够用。 */
object Font {
    val page = 24.sp       // 页标题
    val back = 26.sp       // 返回箭头 ‹
    val body = 15.sp       // 正文、按钮文字、行标题
    val label = 12.sp      // 分组标签、副标题
    val caption = 11.sp    // 说明文字、小胶囊按钮
}

/** 状态：只有按下与禁用两种，别处不再各写一套 alpha。 */
object State {
    const val pressedAlpha = 0.72f
}
