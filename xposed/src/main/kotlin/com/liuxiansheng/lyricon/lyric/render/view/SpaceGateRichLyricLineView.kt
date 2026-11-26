/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.lyric.render.view

import android.content.Context

internal class SpaceGateRichLyricLineView(
    context: Context,
    displayTranslation: Boolean = true,
    enableRelativeProgress: Boolean = false,
    enableRelativeProgressHighlight: Boolean = false,
    displayRoma: Boolean = true,
    simulateUntimedWord: Boolean = false
) : RichLyricLineView(
    context,
    displayTranslation,
    enableRelativeProgress,
    enableRelativeProgressHighlight,
    displayRoma,
    simulateUntimedWord
) {

    /** 是否为右侧容器（右侧歌词右对齐） */
    var isRightSide = false
        private set

    /** 分离歌词模式侧位：让基类知道自己在左容器还是右容器（右侧显示剩余，左侧显示总时长）。 */
    override fun splitRightSide(): Boolean? = isRightSide

    /** 设置侧位并应用对齐（右侧：主副行右对齐、禁居中） */
    fun setSpaceGateConfig(isRightSide: Boolean, sibling: SpaceGateRichLyricLineView?) {
        if (this.isRightSide == isRightSide) return
        this.isRightSide = isRightSide
        applySideAlignment()
    }

    /** 挂载点别名（渐变配置当前无独立行为，仅记录侧位） */
    fun setSplitGradientConfig(isRightSide: Boolean, sibling: SpaceGateRichLyricLineView?) {
        setSpaceGateConfig(isRightSide, sibling)
    }

    private fun applySideAlignment() {
        // 3.0.792：分离模式对齐方向——左侧容器右对齐（歌词贴右/靠中间挖孔侧）、
        // 右侧容器左对齐（歌词贴左/靠中间挖孔侧），两侧歌词都往中间靠。
        // 用户开"歌词居中"时渲染层 centerIfPossible 优先 → 两侧往中间去。
        main.rightIfPossible = !isRightSide
        secondary.rightIfPossible = !isRightSide
        requestLayout()
        invalidate()
    }

    override fun setStyle(style: LyricViewStyle) {
        setStyle(style, isLeftSplitSide = !isRightSide)
    }

    /** 挂载点调用：isLeftSplitSide=true 左容器，false 右容器（仅记录侧位，对齐由设置决定） */
    fun setStyle(style: LyricViewStyle, isLeftSplitSide: Boolean) {
        this.isRightSide = !isLeftSplitSide
        // 3.0.790：右侧不再强制右对齐/禁居中——直接下发 buildStyle 的设置，
        // 让"歌词居中/右对齐"在两侧一致生效（此前右侧被 copy 覆盖 → 用户反馈右侧无效）
        super.setStyle(style)
        applySideAlignment()
    }
}
