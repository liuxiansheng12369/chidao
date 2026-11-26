/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.statusbar

import android.graphics.Rect
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger

/** 原生超级岛区域：SystemUI 提交的岛 bounds 快照（写端为 hook 线程，读端为状态栏帧循环）。 */
internal object StatusBarLyricIslandRegionDispatcher {

    data class IslandBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    @Volatile
    private var islandBounds: IslandBounds? = null

    /** 接收 SystemUI 提交的岛 Rect；空/空矩形视为岛消失。 */
    fun onNativeIslandRegionChanged(rect: Rect?) {
        val snapshot = rect
            ?.takeUnless(Rect::isEmpty)
            ?.let { IslandBounds(it.left, it.top, it.right, it.bottom) }
        val previous = islandBounds
        islandBounds = snapshot
        if ((previous == null) != (snapshot == null)) {
            HookLogger.d(TAG, "原生超级岛状态变更: visible=${snapshot != null}")
        }
    }

    /** 当前岛 bounds；无岛时 null。 */
    fun visibleIslandBounds(): IslandBounds? = islandBounds

    fun hasIsland(): Boolean = islandBounds != null

    private const val TAG = "StatusBarLyricIslandRegion"
}
