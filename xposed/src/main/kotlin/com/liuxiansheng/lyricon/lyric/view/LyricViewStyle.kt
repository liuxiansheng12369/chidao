/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.view

import android.graphics.Color
import android.graphics.Typeface

/**
 * 歌词视图的完整外观参数。
 *
 * 主行、副行各用一套 [TextLook]；高亮用 [Highlight] 的「底色 / 前景色」两条渐变色带。
 * 所有颜色都是「色带」而不是单色 —— 一个词从头到尾可以走一段渐变。
 *
 * @property gradient 是否启用渐变着色
 * @property lineDisplay 是否是逐行（整句一起亮）模式
 * @property scaleMultiLine 多行布局时的缩放系数
 * @property placeholder 没有歌词时占位标题显示什么
 * @property transitionConfig 换句过渡风格
 * @property centerIfPossible / rightIfPossible 空间够时优先居中 / 靠右
 */
data class LyricViewStyle(
    val primary: TextLook = TextLook(),
    val secondary: TextLook = TextLook(size = 14f),
    val highlight: Highlight = Highlight(),
    val wordMotion: WordMotion = WordMotion(),
    val gradient: Boolean = true,
    val lineDisplay: Boolean = false,
    val scaleMultiLine: Float = 1f,
    val animation: AnimParams = AnimParams(),
    val placeholder: TitleSlot = TitleSlot.NAME_ARTIST,
    val transitionConfig: String = "smooth",
    val centerIfPossible: Boolean = false,
    val rightIfPossible: Boolean = false,
)

/**
 * 一套文字的观感参数。
 *
 * `color` 是色带（首色 → 尾色的采样），所以相等性必须按**内容**比，不能按引用比；
 * 这也是这里手写 `equals` / `hashCode` 的唯一原因。
 */
data class TextLook(
    val color: IntArray = intArrayOf(Color.WHITE),
    val size: Float = 16f,
    val typeface: Typeface = Typeface.DEFAULT,
    val fontVariationSettings: String? = null,
    val relativeProgress: Boolean = false,
    val relativeHighlight: Boolean = false,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val that = other as? TextLook ?: return false
        return size == that.size &&
            typeface == that.typeface &&
            fontVariationSettings == that.fontVariationSettings &&
            relativeProgress == that.relativeProgress &&
            relativeHighlight == that.relativeHighlight &&
            color.contentEquals(that.color)
    }

    override fun hashCode(): Int {
        var result = size.hashCode()
        result = result * HASH_MULTIPLIER + typeface.hashCode()
        result = result * HASH_MULTIPLIER + (fontVariationSettings?.hashCode() ?: 0)
        result = result * HASH_MULTIPLIER + relativeProgress.hashCode()
        result = result * HASH_MULTIPLIER + relativeHighlight.hashCode()
        result = result * HASH_MULTIPLIER + color.contentHashCode()
        return result
    }
}

/** 高亮的两条色带：底色（未唱部分用）和前景色（已唱部分用）。 */
data class Highlight(
    val background: IntArray = intArrayOf(Color.GRAY),
    val foreground: IntArray = intArrayOf(Color.WHITE),
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val that = other as? Highlight ?: return false
        return background.contentEquals(that.background) &&
            foreground.contentEquals(that.foreground)
    }

    override fun hashCode(): Int =
        HASH_MULTIPLIER * background.contentHashCode() + foreground.contentHashCode()
}

/** 逐字是否带轻微位移/弹性。 */
data class WordMotion(
    val enabled: Boolean = true,
)

/** 入场动画参数。`presetId` 对应动画预设，空预设走默认。 */
data class AnimParams(
    val enabled: Boolean = false,
    val presetId: String = "stack_flow",
)

/** 没有歌词时占位位上显示什么：歌名 + 歌手、只显示歌名、什么都不显示。 */
enum class TitleSlot { NAME_ARTIST, NAME, NONE }

/** 组合 hashCode 时用的乘数，取值与常见哈希算法保持一致。 */
private const val HASH_MULTIPLIER = 31
