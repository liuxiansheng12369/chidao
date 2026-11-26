/*
 * 词岛 · 倒计时渲染器（自研重写版）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 *
 * 占位倒计时行：显示 mm:ss.d，剩余过半白色、不足半黄色。
 */

package com.liuxiansheng.lyricon.lyric.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import com.liuxiansheng.lyricon.lyric.render.model.LyricRenderModel
import kotlin.math.max

internal class CountdownPainter : LineRenderer {

    val plainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override var centerIfPossible = true
    override var rightIfPossible = false

    /** 整首剩余模式：显示 -mm:ss（不带十分位），而不是行内倒计时 mm:ss.d */
    var wholeTrack = false

    override var lastPosition = Long.MIN_VALUE
        private set
    private var shownText = ""
    private var leftMs = 0L
    private var loggedBucket = -1L

    override val isAnimating: Boolean get() = false
    override val isStarted: Boolean get() = shownText.isNotEmpty()
    override val isFinished: Boolean get() = leftMs <= 0L

    fun applyFontSettings(size: Float, tf: Typeface?, varSettings: String?) {
        plainPaint.textSize = size
        plainPaint.typeface = tf
    }

    override fun updateLayout(
        model: LyricRenderModel,
        state: LineRenderState,
        viewWidth: Int,
        viewHeight: Int
    ) {
        state.reset()
        refresh(model, lastPosition)
    }

    override fun seek(
        model: LyricRenderModel,
        state: LineRenderState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int
    ) {
        lastPosition = posMs
        refresh(model, posMs)
    }

    override fun update(
        model: LyricRenderModel,
        state: LineRenderState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int,
        playbackSpeed: Float
    ) {
        if (posMs != lastPosition) {
            lastPosition = posMs
            refresh(model, posMs)
        }
    }

    override fun step(
        deltaNanos: Long,
        model: LyricRenderModel,
        state: LineRenderState,
        viewWidth: Int
    ): Boolean = false

    override fun draw(
        canvas: Canvas,
        model: LyricRenderModel,
        state: LineRenderState,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (shownText.isEmpty()) return
        // 整首剩余沿用副行样式色（已在 configureWith 设好）；行内占位倒计时用固定白 / 琥珀
        if (!wholeTrack) {
            plainPaint.color = if (leftMs <= AMBER_THRESHOLD_MS) {
                Color.parseColor("#FFC107")
            } else {
                Color.WHITE
            }
        }

        val fm = plainPaint.fontMetrics
        val baseY = (viewHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent
        val textW = plainPaint.measureText(shownText)
        val x = when {
            model.isAlignedRight -> viewWidth - textW
            centerIfPossible -> (viewWidth - textW) / 2f
            rightIfPossible -> viewWidth - textW
            else -> 0f
        }

        canvas.save()
        canvas.clipRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        canvas.drawText(shownText, x, baseY, plainPaint)
        canvas.restore()
    }

    override fun reset(state: LineRenderState) {
        lastPosition = Long.MIN_VALUE
        shownText = ""
        loggedBucket = -1L
        state.reset()
    }

    override fun freeze(model: LyricRenderModel, state: LineRenderState, viewWidth: Int) {
        // 倒计时无动画
    }

    override fun syncFrom(other: LineRenderer) {
        if (other is CountdownPainter) {
            lastPosition = other.lastPosition
            shownText = other.shownText
            leftMs = other.leftMs
        }
    }

    private fun refresh(model: LyricRenderModel, posMs: Long) {
        if (posMs == Long.MIN_VALUE) {
            shownText = ""
            leftMs = 0L
            return
        }
        leftMs = max(0L, model.end - posMs)
        shownText = format(leftMs)
        // 整首剩余每 10 秒打一条，便于用日志数字核对，不刷屏
        if (wholeTrack) {
            val bucket = leftMs / 10000L
            if (bucket != loggedBucket) {
                loggedBucket = bucket
                HookLogger.i(
                    "RemainCountdown",
                    "left_ms=$leftMs pos=$posMs end=${model.end} text='$shownText'"
                )
            }
        }
    }

    private fun format(ms: Long): String {
        // 整首剩余：-mm:ss，秒级向上取整，不带十分位
        if (wholeTrack) {
            val totalSec = (ms + 999) / 1000
            return "-%02d:%02d".format(totalSec / 60, totalSec % 60)
        }
        val tenths = (ms + 99) / 100
        val min = tenths / 600
        val sec = (tenths / 10) % 60
        val tenthsDigit = tenths % 10
        return "%02d:%02d.%d".format(min, sec, tenthsDigit)
    }

    companion object {
        private const val AMBER_THRESHOLD_MS = 1500L
    }
}
