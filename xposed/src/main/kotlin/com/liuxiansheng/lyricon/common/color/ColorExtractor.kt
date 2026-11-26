/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.common.color

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

/**
 * 从封面位图提主题色，并给出「放在白底上」和「放在黑底上」两套可用色。
 *
 * 提取用的是 Palette 的原始代表色，按占比从高到低取前 N 个；两套适配色是在原始色上
 * 调亮/压暗到满足最小对比度，不重新算色相 —— 主色必须是封面自己的颜色。
 */
object ColorExtractor {

    /** 对比度最小目标（WCAG 3:1，正文级可读性的下限）。 */
    private const val MIN_CONTRAST = 3.0f

    /** 每次调整的步长。 */
    private const val SHIFT_STEP = 12

    /** 最多调这么多轮，防止极端色一直不达标时死循环。 */
    private const val MAX_SHIFT_ROUNDS = 24

    /** 提不到任何颜色时的三档灰。 */
    private val FALLBACK_COLORS = intArrayOf(
        Color.rgb(0x30, 0x30, 0x30),
        Color.rgb(0x66, 0x66, 0x66),
        Color.rgb(0x99, 0x99, 0x99)
    )

    /**
     * 提取主题色板。
     *
     * @param bitmap 封面
     * @param maxColors 最多保留多少个代表色
     */
    fun extractThemePalette(bitmap: Bitmap, maxColors: Int): ThemePalette {
        // 性能监控（默认关）：封面取色是换歌时最重的一步之一
        val perfStart = if (com.liuxiansheng.lyricon.hyper.root.perf.HookPerfMonitor.enabledFlag)
            System.nanoTime() else 0L
        try {
            val raw = Palette.Builder(bitmap)
                .maximumColorCount(maxColors)
                .generate()
                .swatches
                .sortedByDescending { it.population }
                .take(maxColors)
                .map { it.rgb }

            if (raw.isEmpty()) {
                val fallback = FALLBACK_COLORS.take(maxColors)
                return ThemePalette(fallback, fallback, fallback)
            }

            return ThemePalette(
                rawColors = raw,
                onWhiteBackground = raw.map { shiftUntilReadable(it, darkBackground = false) },
                onBlackBackground = raw.map { shiftUntilReadable(it, darkBackground = true) }
            )
        } finally {
            if (perfStart != 0L) {
                com.liuxiansheng.lyricon.hyper.root.perf.HookPerfMonitor.recordSample(
                    "color.palette",
                    System.nanoTime() - perfStart,
                )
            }
        }
    }

    /**
     * 把颜色往可读方向挪：深色底提亮、浅色底压暗，直到对比度达标或轮数用尽。
     * 每通道同步加减，避免偏色。
     */
    private fun shiftUntilReadable(color: Int, darkBackground: Boolean): Int {
        var r = Color.red(color)
        var g = Color.green(color)
        var b = Color.blue(color)

        repeat(MAX_SHIFT_ROUNDS) {
            val background = if (darkBackground) BLACK else WHITE
            if (contrastRatio(r, g, b, background) >= MIN_CONTRAST) {
                return Color.rgb(r, g, b)
            }
            if (darkBackground) {
                r = brighten(r)
                g = brighten(g)
                b = brighten(b)
            } else {
                r = darken(r)
                g = darken(g)
                b = darken(b)
            }
        }
        return Color.rgb(r, g, b)
    }

    private fun brighten(channel: Int): Int = (channel + SHIFT_STEP).coerceAtMost(255)

    private fun darken(channel: Int): Int = (channel - SHIFT_STEP).coerceAtLeast(0)

    /** 与纯黑 / 纯白比较的对比度。 */
    private fun contrastRatio(r: Int, g: Int, b: Int, background: Int): Float {
        val foregroundLum = luminance(r, g, b)
        val backgroundLum = if (background == BLACK) 0f else 1f
        val brighter = maxOf(foregroundLum, backgroundLum)
        val darker = minOf(foregroundLum, backgroundLum)
        return (brighter + 0.05f) / (darker + 0.05f)
    }

    /** sRGB 相对亮度。 */
    private fun luminance(r: Int, g: Int, b: Int): Float =
        0.2126f * linearize(r) + 0.7152f * linearize(g) + 0.0722f * linearize(b)

    /** 单通道去 gamma。 */
    private fun linearize(channel: Int): Float {
        val v = channel / 255f
        if (v <= 0.03928f) return v / 12.92f
        val shifted = (v + 0.055f) / 1.055f
        return shifted * shifted * shifted
    }

    private const val BLACK = 0
    private const val WHITE = 255
}

/**
 * 主题色板结果。
 *
 * @param rawColors 封面原始代表色
 * @param onWhiteBackground 放白底上够清楚的版本
 * @param onBlackBackground 放黑底上够清楚的版本
 */
data class ThemePalette(
    val rawColors: List<Int>,
    val onWhiteBackground: List<Int>,
    val onBlackBackground: List<Int>
)
