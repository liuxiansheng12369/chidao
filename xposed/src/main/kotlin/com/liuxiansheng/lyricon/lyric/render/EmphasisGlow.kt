/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 * 随词岛整体以 GPL-3.0 发布。
 * 数据模型沿用 lyricon 系（LyricWord / RichLyricLine），绘制算法为本项目自有。
 *
 * 逐词强调辉光 —— Apple Music 式 emphasize 动画。
 *
 * 语义参考上游 lyricon-master 的 EmphasisGroup / EmphasizeGlowEffect，
 * 计算与求值按词岛自己的渲染模型（LyricRenderModel / WordRenderModel）重写，
 * 不引入上游的 DrawUnit / UnitTransform 体系。
 *
 * 规则：
 * 1. 一行歌词按词切组：CJK 词独组；连续非 CJK 词在「边界无空白粘连」时合并成一组；
 * 2. 组时长 >= 1000ms 才触发；非 CJK 组另需去空白后长度在 2..7；
 * 3. 组内字符按 du / 2.5 / 字数 错峰，依次播放「缩放 + 左右展开 + 上浮」；
 * 4. 行末组幅度与时长分别乘 1.6 / 1.2。
 */

package com.liuxiansheng.lyricon.lyric.render

import android.util.Log
import com.liuxiansheng.lyricon.lyric.render.model.LyricRenderModel
import com.liuxiansheng.lyricon.lyric.render.model.WordRenderModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal class EmphasisGlow {

    private class Group(
        val startMs: Long,
        val durationMs: Long,
        val amount: Float,
        val blur: Float,
        val charCount: Int
    )

    private val groups = ArrayList<Group>(8)
    private var charGroup = IntArray(0)
    private var charIndexInGroup = IntArray(0)
    private var textSize = 0f

    /** 每字横向位移（px）：正右负左 */
    var dxs = FloatArray(0)
        private set

    /** 每字纵向位移（px）：负值向上 */
    var dys = FloatArray(0)
        private set

    /** 每字缩放倍数：1 = 原样 */
    var scales = FloatArray(0)
        private set

    /** 每字辉光强度（0..0.8，直接当不透明度用） */
    var glows = FloatArray(0)
        private set

    /** 每字辉光半径（px，由 blur 乘字号换算，上限 0.3em） */
    var radii = FloatArray(0)
        private set

    /** 当前行是否存在强调组（无组时整段特效可直接跳过） */
    val hasAny: Boolean get() = groups.isNotEmpty()

    /** 行或字号变化时重建分组，并清零变换。 */
    fun bind(model: LyricRenderModel, textSize: Float) {
        this.textSize = textSize
        groups.clear()

        val words = model.words
        val total = words.sumOf { it.chars.size }
        if (total <= 0) {
            charGroup = IntArray(0)
            charIndexInGroup = IntArray(0)
            dxs = FloatArray(0)
            dys = FloatArray(0)
            scales = FloatArray(0)
            glows = FloatArray(0)
            radii = FloatArray(0)
            return
        }

        if (charGroup.size != total) {
            charGroup = IntArray(total) { -1 }
            charIndexInGroup = IntArray(total)
            dxs = FloatArray(total)
            dys = FloatArray(total)
            scales = FloatArray(total)
            glows = FloatArray(total)
            radii = FloatArray(total)
        } else {
            java.util.Arrays.fill(charGroup, -1)
            java.util.Arrays.fill(charIndexInGroup, 0)
        }
        resetTransforms()

        // 每个词在整行里的起始字索引
        val wordCharBase = IntArray(words.size)
        var acc = 0
        for (i in words.indices) {
            wordCharBase[i] = acc
            acc += words[i].chars.size
        }

        val lineBegin = model.begin
        val lastTextWord = words.lastOrNull { it.text.isNotBlank() }

        var i = 0
        while (i < words.size) {
            val first = words[i]
            if (first.text.isBlank()) {
                i++
                continue
            }

            // 收集组：CJK 词独组；连续非 CJK 且边界无空白粘连才合并
            val members = ArrayList<WordRenderModel>(4)
            members += first
            if (!first.containsCjk) {
                var j = i + 1
                while (j < words.size) {
                    val next = words[j]
                    if (next.text.isBlank() || next.containsCjk) break
                    if (members.last().text.lastOrNull()?.isWhitespace() == true) break
                    if (next.text.firstOrNull()?.isWhitespace() == true) break
                    members += next
                    j++
                }
            }
            val startWordIndex = i
            i += members.size

            val groupBegin = members.minOf { it.begin }
            val groupEnd = members.maxOf { it.end }
            val trimmed = members.joinToString("") { it.text }.trim()
            val isCjkGroup = members.size == 1 && members[0].containsCjk

            val anyEmphasized = members.any {
                shouldEmphasize(it.containsCjk, it.text.trim(), it.end - it.begin)
            }
            val mergedEmphasized = !isCjkGroup &&
                shouldEmphasize(false, trimmed, groupEnd - groupBegin)
            if (!anyEmphasized && !mergedEmphasized) continue

            val isLastGroup = members.last() === lastTextWord

            var du = max(1000L, groupEnd - groupBegin)
            var amount = powerCurve(du / 2000f) * 0.6f
            var blur = powerCurve(du / 3000f) * 0.5f
            if (isLastGroup) {
                amount *= 1.6f
                blur *= 1.5f
                du = (du * 1.2f).toLong()
            }
            amount = min(1.2f, max(MIN_AMOUNT, amount))
            blur = min(0.8f, max(MIN_BLUR, blur))

            val charCount = members.sumOf { it.chars.size }
            if (charCount <= 0) continue

            val g = groups.size
            groups += Group(groupBegin, du, amount, blur, charCount)

            var offset = 0
            for (k in members.indices) {
                val word = members[k]
                val base = wordCharBase[startWordIndex + k]
                for (c in 0 until word.chars.size) {
                    charGroup[base + c] = g
                    charIndexInGroup[base + c] = offset + c
                }
                offset += word.chars.size
            }
            // lineBegin 仅用于把组起点换算为绝对时间；Group 已存绝对 startMs
            if (groupBegin < lineBegin) {
                // 越界（数据异常）时按行首兜底，避免整组永远不触发
                groups[g] = Group(lineBegin, du, amount, blur, charCount)
            }
        }

        // 1.6.17：bind 诊断日志（每句一条）删除，逐词强调状态不再刷日志。
    }

    /** 按当前媒体位置求每字变换。无组时直接返回。 */
    fun update(positionMs: Long) {
        if (groups.isEmpty()) return
        val n = scales.size
        for (i in 0 until n) {
            val g = charGroup[i]
            if (g < 0) {
                scales[i] = 1f
                dxs[i] = 0f
                dys[i] = 0f
                glows[i] = 0f
                radii[i] = 0f
                continue
            }
            val group = groups[g]
            val within = charIndexInGroup[i]

            // 组内错峰：跨度 du / 2.5
            val stagger = group.durationMs.toFloat() / 2.5f * within / group.charCount
            val startMs = group.startMs + stagger.toLong()
            val elapsed = positionMs - startMs
            val du = group.durationMs

            // 未开始时保持第一帧（fill:both），完成后停在最终帧
            val x = when {
                elapsed <= 0L -> FIRST_FRAME_X
                elapsed >= du -> 1f
                else -> elapsed.toFloat() / du
            }
            val eased = EmpEasing.get(x)

            scales[i] = 1f + eased * SCALE_FACTOR * group.amount

            // 左右展开：离组中心越远的字位移越大
            val spread = group.charCount / 2f - within
            dxs[i] = -eased * SPREAD_FACTOR * group.amount * spread * textSize

            // 上浮
            dys[i] = -eased * LIFT_FACTOR * group.amount * textSize

            // 辉光：这里只给强度与半径，颜色由绘制层取该字自己的颜色
            glows[i] = eased * group.blur
            radii[i] = min(0.3f, group.blur * 0.3f) * textSize
        }
    }

    fun resetTransforms() {
        java.util.Arrays.fill(scales, 1f)
        java.util.Arrays.fill(dxs, 0f)
        java.util.Arrays.fill(dys, 0f)
        java.util.Arrays.fill(glows, 0f)
        java.util.Arrays.fill(radii, 0f)
    }

    private companion object {
        const val TAG = "LyriconEmphasis"

        /** 未开始时的第一帧进度（源项目 ANIMATION_FRAME_QUANTITY = 32） */
        const val FIRST_FRAME_X = 1f / 32f
        const val SCALE_FACTOR = 0.1f
        const val SPREAD_FACTOR = 0.03f
        const val LIFT_FACTOR = 0.025f
    }
}

/**
 * 强调判定：CJK 词要求时长 >= [MIN_EMPHASIS_DURATION_MS]；非 CJK 词另要求去空白后长度 2..7。
 *
 * 注：AMLL 原阈值是 1000ms，但那是英文/慢歌语境。中文一句十来个字、每字 200~400ms，
 * 一个词常常只有 400~700ms，按 1000ms 判定会出现「整首歌一个强调组都没有」，
 * 也就是用户说的「看不到效果」。这里放宽到 600ms，并把幅度/光强加了下限（见 buildGroups）。
 */
internal fun shouldEmphasize(isCjk: Boolean, trimmedText: String, durationMs: Long): Boolean {
    if (isCjk) return durationMs >= MIN_EMPHASIS_DURATION_MS
    val length = trimmedText.length
    return durationMs >= MIN_EMPHASIS_DURATION_MS && length > 1 && length <= 7
}

/** 触发强调的最短词时长（AMLL 原值 1000，词岛场景放宽） */
private const val MIN_EMPHASIS_DURATION_MS = 600L

/** 幅度下限：否则短词按立方衰减到 0，放大不足 1%，肉眼看不出来 */
private const val MIN_AMOUNT = 0.55f

/** 光强下限：同上，短词的光晕会淡到看不见 */
private const val MIN_BLUR = 0.45f

/** 幅度曲线：v > 1 开方，v <= 1 取立方。 */
private fun powerCurve(v: Float): Float = if (v > 1f) sqrt(v) else v.pow(3)

/**
 * 分段三次贝塞尔缓动（源项目 makeEmpEasing(0.5)）：
 * x < 0.5 取 bezIn(0.2, 0.4, 0.58, 1) 前半，x >= 0.5 取 1 - bezOut(0.3, 0, 0.58, 1) 后半。
 * 预计算 512 项查找表，[get] 为 O(1)：逐字特效每帧被调用多次，直接解方程会成为热路径。
 */
internal object EmpEasing {

    private const val MID = 0.5f
    private const val LUT_SIZE = 512

    private val lut: FloatArray = FloatArray(LUT_SIZE + 1).also { table ->
        for (i in 0..LUT_SIZE) {
            val x = i.toFloat() / LUT_SIZE
            table[i] = if (x < MID) {
                CubicBezierEasing.eval(x / MID, 0.2f, 0.4f, 0.58f, 1f)
            } else {
                1f - CubicBezierEasing.eval((x - MID) / (1f - MID), 0.3f, 0f, 0.58f, 1f)
            }
        }
    }

    fun get(x: Float): Float {
        if (x <= 0f) return lut[0]
        if (x >= 1f) return lut[LUT_SIZE]
        val pos = x * LUT_SIZE
        val i = pos.toInt()
        val frac = pos - i
        return lut[i] + (lut[i + 1] - lut[i]) * frac
    }
}

/**
 * 三次贝塞尔缓动求解器：以 x 求 y，Newton-Raphson 迭代 + 二分兜底，纯函数无分配。
 */
internal object CubicBezierEasing {

    private const val NEWTON_ITERATIONS = 4
    private const val NEWTON_MIN_SLOPE = 0.001f
    private const val SUBDIVISION_PRECISION = 0.0000001f
    private const val SUBDIVISION_MAX_ITERATIONS = 10

    fun eval(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        var t = x
        repeat(NEWTON_ITERATIONS) {
            val currentSlope = slope(t, x1, x2)
            if (abs(currentSlope) < NEWTON_MIN_SLOPE) return bezier(t, y1, y2)
            val currentX = bezier(t, x1, x2) - x
            t -= currentX / currentSlope
        }
        if (abs(bezier(t, x1, x2) - x) < SUBDIVISION_PRECISION) {
            return bezier(t, y1, y2)
        }
        var low = 0f
        var high = 1f
        repeat(SUBDIVISION_MAX_ITERATIONS) {
            val currentX = bezier(t, x1, x2)
            if (currentX > x) {
                high = t
            } else {
                low = t
            }
            t = (low + high) / 2f
        }
        return bezier(t, y1, y2)
    }

    private fun slope(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * a + 6f * u * t * (b - a) + 3f * t * t * (1f - b)
    }

    private fun bezier(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }
}
