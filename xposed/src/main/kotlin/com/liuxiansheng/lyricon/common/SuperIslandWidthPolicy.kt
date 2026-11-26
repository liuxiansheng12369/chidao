/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.common

/**
 * 超级岛宽度换算。
 *
 * 岛的左右两边各可能挂一个 22dp 的组件（封面、律动）。系统给的是「整岛宽」，
 * 但歌词实际能用的横向空间要先把左侧组件的占位补回来 —— 这一组函数就是在这两种
 * 坐标系之间来回换。
 */
object SuperIslandWidthPolicy {

    /** 单个侧边组件宽度。 */
    const val SIDE_COMPONENT_WIDTH_DP = 22

    /** 岛宽下限。 */
    const val MIN_ISLAND_WIDTH_DP = 22

    /** 解除系统限制后的岛宽上限。 */
    const val MAX_SIDE_WIDTH_DP = 170

    /** 系统限制下的岛宽上限。 */
    const val SYSTEM_LIMITED_MAX_SIDE_WIDTH_DP = 114

    /** 最小可用岛宽：左组件占位被右组件抵掉一部分后的下限。 */
    fun minIslandWidth(showAlbum: Boolean, showRhythm: Boolean): Int =
        (componentWidth(showAlbum) - componentWidth(showRhythm))
            .coerceAtLeast(MIN_ISLAND_WIDTH_DP)

    /** 最大可用岛宽：先取上限，再让出右组件占位。 */
    fun maxIslandWidth(showRhythm: Boolean, disableWidthLimit: Boolean): Int {
        val ceiling =
            if (disableWidthLimit) MAX_SIDE_WIDTH_DP else SYSTEM_LIMITED_MAX_SIDE_WIDTH_DP
        return ceiling - componentWidth(showRhythm)
    }

    /** 把岛宽夹进合法区间。 */
    fun normalizeIslandWidth(
        islandWidth: Int,
        showAlbum: Boolean,
        showRhythm: Boolean,
        disableWidthLimit: Boolean = false
    ): Int = islandWidth.coerceIn(
        minIslandWidth(showAlbum, showRhythm),
        maxIslandWidth(showRhythm, disableWidthLimit)
    )

    /** 左侧内容真正可用的宽度 = 归一化岛宽 + 左右组件占位差。 */
    fun leftContentWidth(
        islandWidth: Int,
        showAlbum: Boolean,
        showRhythm: Boolean,
        disableWidthLimit: Boolean = false
    ): Int {
        val normalized = normalizeIslandWidth(
            islandWidth = islandWidth,
            showAlbum = showAlbum,
            showRhythm = showRhythm,
            disableWidthLimit = disableWidthLimit
        )
        return normalized + leftContentWidthOffsetDp(showAlbum, showRhythm)
    }

    /** 偏移量：右组件占位减左组件占位。 */
    fun leftContentWidthOffsetDp(showAlbum: Boolean, showRhythm: Boolean): Int =
        componentWidth(showRhythm) - componentWidth(showAlbum)

    /** 由左侧内容宽反推岛宽，和 [leftContentWidth] 互逆。 */
    fun baseWidthFromLeftContentWidth(
        leftContentWidthDp: Float,
        showAlbum: Boolean,
        showRhythm: Boolean
    ): Float = leftContentWidthDp - leftContentWidthOffsetDp(showAlbum, showRhythm)

    private fun componentWidth(visible: Boolean): Int =
        if (visible) SIDE_COMPONENT_WIDTH_DP else 0
}
