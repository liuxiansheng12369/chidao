/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.sizing

import android.view.View

/**
 * 槽位几何配置（V3 自研）——左右双栏的宽度/边距快照。
 * 与 View 相关的换算集中在这里，内容/生命周期代码不需要感知原始左右字段。
 */
internal data class IslandSlotGeometryConfig(
    val isDynamicWidth: Boolean,
    val showAlbum: Boolean,
    val showRhythm: Boolean,
    val leftPaddingLeftDp: Int,
    val leftPaddingRightDp: Int,
    val rightPaddingLeftDp: Int,
    val rightPaddingRightDp: Int,
    val leftMinWidthDp: Int,
    val rightMinWidthDp: Int,
    val leftMaxWidthDp: Int,
    val rightMaxWidthDp: Int
) {
    /** 左侧父容器识别：父容器名含 "1"。 */
    fun isLeftParent(parentName: String): Boolean {
        return parentName.contains("1")
    }

    fun maxWidthDp(parentName: String): Int {
        return if (isLeftParent(parentName)) leftMaxWidthDp else rightMaxWidthDp
    }

    fun minWidthDp(parentName: String): Int {
        return if (isLeftParent(parentName)) leftMinWidthDp else rightMinWidthDp
    }

    fun paddingLeftDp(parentName: String): Int {
        return if (isLeftParent(parentName)) leftPaddingLeftDp else rightPaddingLeftDp
    }

    fun paddingRightDp(parentName: String): Int {
        return if (isLeftParent(parentName)) leftPaddingRightDp else rightPaddingRightDp
    }

    fun widthPx(rootView: View, parentName: String): Int? {
        val maxWidthDp = maxWidthDp(parentName)
        if (maxWidthDp <= 0) return null
        return (maxWidthDp * rootView.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    fun paddingLeftPx(rootView: View, parentName: String): Int {
        return (paddingLeftDp(parentName) * rootView.resources.displayMetrics.density).toInt()
    }

    fun paddingRightPx(rootView: View, parentName: String): Int {
        return (paddingRightDp(parentName) * rootView.resources.displayMetrics.density).toInt()
    }
}
