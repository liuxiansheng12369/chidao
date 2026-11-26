/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */



package com.liuxiansheng.lyricon.hyper.root.statusbar

import android.content.Context
import android.widget.LinearLayout

internal class MaxWidthLinearLayout(context: Context) : LinearLayout(context) {

    /** 最大宽度（像素）。<=0 表示不限制。 */
    var maxWidthPx: Int = -1

    /**
     * 上一次测得的非零宽度。
     * ColorOS/realmeUI 状态栏时钟每秒刷新，父容器（clock 所在的 LinearLayout）会在那一帧
     * 只给歌词 0 剩余空间；"不限制宽度"时若照着这个 0 去测，整块会被压成 0x0，
     * 屏幕上看就是歌词"消失了一下又回来"。这里用上一帧宽度兜底，避免零尺寸帧。
     */
    private var lastNonZeroWidth = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val givenWidth = MeasureSpec.getSize(widthMeasureSpec)
        val cap: Int = when {
            maxWidthPx > 0 -> if (givenWidth > 0) minOf(givenWidth, maxWidthPx) else maxWidthPx
            givenWidth > 0 -> givenWidth
            lastNonZeroWidth > 0 -> lastNonZeroWidth
            else -> 0
        }
        if (cap <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST),
            heightMeasureSpec
        )
        var width = measuredWidth
        if (width > cap) width = cap
        if (width <= 0 && lastNonZeroWidth > 0) width = lastNonZeroWidth
        if (width > 0) lastNonZeroWidth = width
        if (width != measuredWidth) {
            setMeasuredDimension(width, measuredHeight)
        }
    }
}
