/*
 * 词岛 · 逐字同步渲染器（自研重写版）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 数据模型沿用 lyricon 系（LyricWord / RichLyricLine），绘制算法为本项目自有。
 *
 * 有词数据时使用：暗色层画未播、亮色层画已播（双层裁剪），
 * 高亮宽度由进度控制器驱动；超宽时同步推进滚动偏移。
 */

package com.liuxiansheng.lyricon.lyric.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.liuxiansheng.lyricon.lyric.render.anim.WordProgressController
import com.liuxiansheng.lyricon.lyric.render.draw.LyricCanvasPainter
import com.liuxiansheng.lyricon.lyric.render.draw.SustainGlow
import com.liuxiansheng.lyricon.lyric.render.model.LyricRenderModel

internal class ProgressivePainter : LineRenderer {

    val dimPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val litPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val tracker = WordProgressController()
    private val follower = ScrollStepper()
    private val painter = LyricCanvasPainter()

    /** 逐词强调辉光是否启用（由歌词效果「逐词强调」控制） */
    var emphasisOn = false

    private val emphasisGlow = EmphasisGlow()
    private var emphasisBoundModel: LyricRenderModel? = null

    var marqueeOnly = false
    var waveOn = true
    var fadeIn = true

    private var playSpeed = 1f

    var sustainAwareProgress: Boolean
        get() = tracker.sustainAware
        set(value) {
            tracker.sustainAware = value
        }

    override var centerIfPossible = false
    override var rightIfPossible = false

    override fun setPaletteOffset(offset: Int) {
        painter.paletteOffset = offset
    }

    override val lastPosition: Long get() = tracker.lastPosition
    override val isAnimating: Boolean get() = tracker.animator.isAnimating
    override val isStarted: Boolean get() = tracker.animator.hasStarted
    override val isFinished: Boolean get() = tracker.animator.hasFinished

    /** 逐字动效底部留白 */
    fun liftReserve(model: LyricRenderModel): Float =
        if (waveOn && !marqueeOnly) {
            painter.liftReserve(model, dimPaint.textSize)
        } else {
            0f
        }

    fun applyFontSettings(size: Float, tf: Typeface?, varSettings: String?) {
        dimPaint.textSize = size
        litPaint.textSize = size
        dimPaint.typeface = tf
        litPaint.typeface = tf
        painter.syncMetrics(dimPaint)
    }

    fun setColors(dim: IntArray, lit: IntArray) {
        if (dim.isNotEmpty()) dimPaint.color = dim[0]
        if (lit.isNotEmpty()) litPaint.color = lit[0]
        painter.applyColors(dim, lit)
    }

    /** 当前高亮宽度 */
    fun currentWidth(): Float = tracker.animator.currentWidth

    /**
     * 整行直接全亮：不按时间推进，直接跳到满宽。
     * 用于「没有逐字数据且关闭模拟逐字」的行——歌词一出现就整句是亮的。
     */
    fun jumpToFull(
        model: LyricRenderModel,
        state: LineRenderState,
        viewWidth: Int
    ) {
        tracker.animator.jumpTo(model.width)
        refreshScroll(model, state, viewWidth)
    }

    override fun updateLayout(
        model: LyricRenderModel,
        state: LineRenderState,
        viewWidth: Int,
        viewHeight: Int
    ) {
        painter.syncMetrics(dimPaint)
        if (tracker.animator.hasFinished && model.end > model.begin) {
            tracker.animator.jumpTo(model.width)
        }
        refreshScroll(model, state, viewWidth)
    }

    override fun seek(
        model: LyricRenderModel,
        state: LineRenderState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int
    ) {
        tracker.seek(posMs, model)
        updateEmphasis(model, posMs)
        refreshScroll(model, state, viewWidth)
    }

    override fun update(
        model: LyricRenderModel,
        state: LineRenderState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int,
        playSpeed: Float
    ) {
        this.playSpeed = if (playSpeed.isFinite() && playSpeed > 0f) playSpeed else 1f
        tracker.update(posMs, this.playSpeed, model)
        updateEmphasis(model, posMs)
        refreshScroll(model, state, viewWidth)
    }

    override fun step(
        deltaNanos: Long,
        model: LyricRenderModel,
        state: LineRenderState,
        viewWidth: Int
    ): Boolean {
        val changed = tracker.step(deltaNanos, playSpeed, model)
        if (changed) {
            refreshScroll(model, state, viewWidth)
            return true
        }
        return false
    }

    override fun draw(
        canvas: Canvas,
        model: LyricRenderModel,
        state: LineRenderState,
        viewWidth: Int,
        viewHeight: Int
    ) {
        painter.render(
            canvas, model, viewWidth, viewHeight,
            state.scrollOffset, model.width > viewWidth,
            tracker.animator.currentWidth,
            fadeIn, marqueeOnly, waveOn,
            centerIfPossible, rightIfPossible,
            dimPaint, litPaint, dimPaint,
            emptyList<SustainGlow>(),
            if (emphasisOn) emphasisGlow else null
        )
    }

    override fun reset(state: LineRenderState) {
        tracker.reset()
        state.reset()
        painter.dropGradientCache()
    }

    override fun freeze(model: LyricRenderModel, state: LineRenderState, viewWidth: Int) {
        tracker.freeze()
        refreshScroll(model, state, viewWidth)
    }

    override fun syncFrom(other: LineRenderer) {
        if (other is ProgressivePainter) {
            tracker.syncFrom(other.tracker)
            waveOn = other.waveOn
            fadeIn = other.fadeIn
        }
    }

    private fun updateEmphasis(model: LyricRenderModel, posMs: Long) {
        if (!emphasisOn) return
        if (emphasisBoundModel !== model) {
            emphasisGlow.bind(model, dimPaint.textSize)
            emphasisBoundModel = model
        }
        emphasisGlow.update(posMs)
    }

    private fun refreshScroll(model: LyricRenderModel, state: LineRenderState, viewWidth: Int) {
        state.scrollOffset = follower.compute(
            tracker.animator.currentWidth,
            model.width,
            viewWidth.toFloat(),
            tracker.animator.hasFinished,
            state.isScrollFinished
        )
        if (tracker.animator.hasFinished) {
            state.isScrollFinished = true
        }
    }
}
