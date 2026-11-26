/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.view

import android.content.Context
import android.util.Log
import android.widget.FrameLayout

/**
 * 限制最大测量宽度的 FrameLayout 容器。
 * 取代了原先以匿名类实现的测量逻辑，规避了 Xposed 对 maxWidthPx 的反射调用。
 */
class MaxWidthFrameLayout(context: Context) : FrameLayout(context) {

    /**
     * 最大宽度（像素）。设置为 -1（默认）则不限制。
     */
    var maxWidthPx: Int = -1

    /**
     * Used only by injected Super Island test blocks.
     */
    var keepVisible: Boolean = false

    override fun setVisibility(visibility: Int) {
        if (keepVisible && visibility != VISIBLE) {
            super.setVisibility(VISIBLE)
            return
        }
        super.setVisibility(visibility)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val givenWidth = MeasureSpec.getSize(widthMeasureSpec)
        // 动态宽度语义：wrapper 跟随父容器给宽（given），超过 maxWidthPx 才压到上限。
        // 回退 3.0.460 的"始终 maxWidthPx"——那会破坏动态宽度（歌词变宽岛不变）并导致
        // 长歌词被父容器裁剪显示不全。
        val newWidth =
            if (maxWidthPx > 0 && (givenWidth == 0 || givenWidth > maxWidthPx)) maxWidthPx else givenWidth
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.AT_MOST),
            heightMeasureSpec
        )
        if (maxWidthPx > 0 && measuredWidth > maxWidthPx) {
            setMeasuredDimension(maxWidthPx, measuredHeight)
        }
    }
}
