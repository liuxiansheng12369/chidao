/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.config

import android.content.SharedPreferences
import com.liuxiansheng.lyricon.hyper.common.LyricTextColorStylePolicy
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandProbeUtils
import com.liuxiansheng.lyricon.hyper.root.island.sizing.IslandSlotGeometryConfig

/**
 * 槽位运行时配置（V3 自研）——不可变快照。
 * 覆盖显示模式、宽度几何、文字样式、动画/跑马灯、逐字、翻译、
 * 颜色、字体、词动效等全部渲染输入。
 */
internal data class IslandSlotRuntimeConfig(
    val activeMode: Int,
    val leftMode: Int,
    val rightMode: Int,
    val geometry: IslandSlotGeometryConfig,
    val dynamicWidthBasis: Int,
    val pauseBehavior: Int,
    val textSizeSp: Int,
    val textSizeRatio: Float,
    val fontWeight: Int,
    val fontItalic: Boolean,
    val gradientProgress: Boolean,
    val centerLyric: Boolean,
    val centerMusicInfo: Boolean,
    val rightLyric: Boolean,
    val lyricAnimationEnabled: Boolean,
    val lyricAnimationId: String,
    val syllableRelative: Boolean,
    val syllableHighlight: Boolean,
    val syllableLineDisplay: Boolean,
    val simulateUntimedWord: Boolean,
    val disableTranslation: Boolean,
    val translationOnly: Boolean,
    val swapTranslation: Boolean,
    val nextLyricLine: Boolean,
    val autoSwitchTranslation: Boolean,
    val textColorStyle: Int,
    val customFontPath: String,
    val narrowLatinFont: Boolean,
    val wordMotionEnabled: Boolean
) {
    /** 分离模式（左右双栏）：activeMode == 1。 */
    val isSplitMode: Boolean
        get() = activeMode == 1

    /** 是否从封面提取文字颜色。 */
    val extractCoverTextColor: Boolean
        get() = LyricTextColorStylePolicy.usesCoverColor(textColorStyle)

    /** 是否从封面提取渐变颜色。 */
    val extractCoverTextGradient: Boolean
        get() = LyricTextColorStylePolicy.usesCoverGradient(textColorStyle)

    /** 样式签名：任何影响样式的字段变化都会改变签名，触发样式重建（实时生效）。 */
    val styleSignature: String = listOf(
        activeMode,
        textSizeSp,
        textSizeRatio,
        fontWeight,
        fontItalic,
        gradientProgress,
        centerLyric,
        centerMusicInfo,
        rightLyric,
        lyricAnimationEnabled,
        lyricAnimationId,
        syllableRelative,
        syllableHighlight,
        syllableLineDisplay,
        simulateUntimedWord,
        disableTranslation,
        translationOnly,
        swapTranslation,
        nextLyricLine,
        autoSwitchTranslation,
        customFontPath,
        narrowLatinFont,
        wordMotionEnabled,
    ).joinToString("|")

    /** 按视图标签取对应侧的内容模式（左栏/右栏）。 */
    fun modeForTag(tag: String): Int {
        return if (tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG) leftMode else rightMode
    }

    /** 视图标签是否属于左栏。 */
    fun isLeftTag(tag: String): Boolean {
        return tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
    }

    companion object {
        /** 从偏好读取一次完整配置快照。 */
        fun from(prefs: SharedPreferences): IslandSlotRuntimeConfig =
            IslandSlotRuntimeConfigReader.read(prefs)
    }
}
