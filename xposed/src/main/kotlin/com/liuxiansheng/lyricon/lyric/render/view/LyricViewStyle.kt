/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.lyric.render.view

import android.graphics.Typeface

/**
 * 一套文字的观感参数，主行和副行共用同一套结构。
 *
 * `colors` 是色带：只给一个颜色就是纯色，给一串就走渐变。
 * `relativeProgress` / `relativeHighlight` 决定这一路文字参不参与逐字推进，
 * 副行（翻译、罗马音）通常两个都关掉，只跟着整行走。
 *
 * 阴影默认是关的（半径 0）——超级岛本身有背景层，再叠阴影会发脏。
 *
 * @property colors 色带，长度 1 即纯色
 * @property size 字号（sp）
 * @property typeface 字体，`null` 用系统默认
 * @property shadowRadius 可读性软阴影半径，0 = 关闭
 */
internal class TextLook(
    val colors: IntArray,
    val size: Float,
    val typeface: Typeface?,
    val fontVariationSettings: String? = null,
    val relativeProgress: Boolean = false,
    val relativeHighlight: Boolean = false,
    val shadowRadius: Float = 0f,
    val shadowDx: Float = 0f,
    val shadowDy: Float = DEFAULT_SHADOW_DY,
    val shadowColor: Int = DEFAULT_SHADOW_COLOR
) {
    /** 兼容别名：老调用点写的是单数 `color`。 */
    val color: IntArray get() = colors
}

/** 高亮的两条色带：`background` 是已唱区的底色，`foreground` 是已唱区的字色。 */
internal class Highlight(
    val background: IntArray,
    val foreground: IntArray
)

/** 逐字动效开关（字上跳）。 */
internal class WordMotion(
    val enabled: Boolean = true
)

/** 整行切换动画参数，目前由渲染端统一消费。 */
internal class AnimParams(
    val durationMs: Int = DEFAULT_TRANSITION_MS,
    val interpolator: Int = 0
)

/** 歌词还没到时占位显示什么：可以只显示文字，也可以带倒计时。 */
internal class Placeholder(
    val enabled: Boolean = false,
    val countdown: Boolean = false,
    val text: String? = null
)

/** 歌名占位槽：歌词缺席时在岛里顶一行歌名。 */
internal class TitleSlot(
    val enabled: Boolean = false
)

/**
 * 整套歌词样式，由挂载点构建完成后下发给歌词行视图。
 *
 * @property primary 主行（歌词原文）
 * @property secondary 副行（翻译 / 罗马音 / 下一句预览）
 * @property highlight 已唱区配色
 * @property gradient 已唱区是否走渐变
 * @property lineDisplay 整行时间轴模式（整句一起亮，不逐字）
 * @property transitionConfig 换句过渡配置，`null` 表示用默认
 * @property centerIfPossible 没有挖孔时优先居中
 * @property rightIfPossible 分离模式的右栏优先靠右
 * @property mirror 是否画镜像倒影
 * @property lyricEffect 歌词特效编号，0 = 静态
 * @property effectSolidColor 特效模式统一用的单色，跟随用户设置的歌词颜色
 */
internal data class LyricViewStyle(
    val primary: TextLook,
    val secondary: TextLook,
    val highlight: Highlight,
    val gradient: Boolean,
    val wordMotion: WordMotion,
    val lineDisplay: Boolean,
    val transitionConfig: String?,
    val centerIfPossible: Boolean,
    val rightIfPossible: Boolean,
    val mirror: Boolean = false,
    val lyricEffect: Int = 0,
    val effectSolidColor: Int = DEFAULT_EFFECT_COLOR,
    val placeholder: Placeholder = Placeholder(),
    val titleSlot: TitleSlot = TitleSlot(),
    val animParams: AnimParams = AnimParams(),
    /** 逐词强调（独立开关）：走正常逐字渲染并叠加强调，开启时整行动画特效让位 */
    val emphasisGlow: Boolean = false
) {
    companion object {

        /** 兜底样式：没内容、没配置时用它，保证视图不会因为空样式崩。 */
        fun defaultStyle(): LyricViewStyle {
            val white = DEFAULT_EFFECT_COLOR
            return LyricViewStyle(
                primary = TextLook(
                    colors = intArrayOf(white),
                    size = DEFAULT_PRIMARY_SIZE,
                    typeface = null
                ),
                secondary = TextLook(
                    colors = intArrayOf(DEFAULT_SECONDARY_COLOR),
                    size = DEFAULT_SECONDARY_SIZE,
                    typeface = null
                ),
                highlight = Highlight(
                    background = intArrayOf(white),
                    foreground = intArrayOf(DEFAULT_HIGHLIGHT_COLOR)
                ),
                gradient = true,
                wordMotion = WordMotion(),
                lineDisplay = false,
                transitionConfig = null,
                centerIfPossible = false,
                rightIfPossible = false
            )
        }
    }
}

/** 主行默认字号（sp）。 */
private const val DEFAULT_PRIMARY_SIZE = 16f

/** 副行默认字号（sp），比主行小一号半。 */
private const val DEFAULT_SECONDARY_SIZE = 13f

/** 副行默认色：浅灰。 */
private const val DEFAULT_SECONDARY_COLOR = 0xFFCCCCCC.toInt()

/** 已唱区默认字色。 */
private const val DEFAULT_HIGHLIGHT_COLOR = 0xFF69DB7C.toInt()

/** 特效单色默认值：纯白。 */
private const val DEFAULT_EFFECT_COLOR = 0xFFFFFFFF.toInt()

/** 阴影默认下沉 1.5px。 */
private const val DEFAULT_SHADOW_DY = 1.5f

/** 阴影默认半透明黑。 */
private const val DEFAULT_SHADOW_COLOR = 0xAA000000.toInt()

/** 整行切换默认时长（毫秒）。 */
private const val DEFAULT_TRANSITION_MS = 260
