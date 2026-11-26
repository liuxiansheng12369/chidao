/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.lyric.render.anim

/**
 * 逐字高亮的"长音缓动"映射。
 *
 * 播放器给的是线性时间进度，但一个字拖两秒时，线性高亮看着像卡住不动；
 * 把进度换成一条两端速度固定的曲线，头一个字起得快、尾巴收得慢，观感就顺了。
 *
 * 曲线取三次多项式，端点条件：
 * `f(0)=0`、`f(1)=1`、起点斜率 [HEAD_SLOPE]、终点斜率 0.5。
 * 解出来就是 `f(t) = t³ - 2.5t² + 2.5t`，约 26% 的时间走完 50% 的宽度。
 *
 * 强度用来混合曲线和直线：强度 0 完全按线性走，强度 1 完全按曲线走。
 */
internal object WordProgressMapper {

    /** 起点斜率：越大越"抢跑"。 */
    private const val HEAD_SLOPE = 2.5f

    /**
     * 线性进度 → 高亮宽度进度。
     *
     * @param progress 媒体时间进度，超出 [0,1] 会被夹回来
     * @param sustainStrength 长音强度，0 表示关闭缓动
     */
    fun map(progress: Float, sustainStrength: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        val strength = sustainStrength.coerceIn(0f, 1f)
        // 不需要缓动的三种情况：没开强度、还在最左端、已经到最右端
        if (strength <= 0f || t <= 0f || t >= 1f) return t
        return t + (easeInOutSustain(t) - t) * strength
    }

    /**
     * 两端斜率固定的三次曲线。
     *
     * 由四个端点条件解出 `f(t) = t³ - [HEAD_SLOPE]·t² + [HEAD_SLOPE]·t`，
     * 写成 Horner 形式少一次乘法。
     */
    private fun easeInOutSustain(t: Float): Float =
        (t * t - HEAD_SLOPE * t + HEAD_SLOPE) * t
}
