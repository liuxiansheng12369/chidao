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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.view.Choreographer
import android.view.View
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.ILyricLine
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import com.liuxiansheng.lyricon.lyric.render.CountdownPainter
import com.liuxiansheng.lyricon.lyric.render.LineRenderState
import com.liuxiansheng.lyricon.lyric.render.LineRenderer
import com.liuxiansheng.lyricon.lyric.render.LineTimelinePainter
import com.liuxiansheng.lyricon.lyric.render.LyricEffectRenderer
import com.liuxiansheng.lyricon.lyric.render.MarqueePainter
import com.liuxiansheng.lyricon.lyric.render.ProgressivePainter
import com.liuxiansheng.lyricon.lyric.render.model.LyricRenderModel

/**
 * 一行歌词的渲染视图。
 *
 * 四个渲染器各管一种画法，按行的形态二选一：
 * - [LineTimelinePainter] 整行时间轴（整段一起亮）
 * - [ProgressivePainter] 逐字推进
 * - [MarqueePainter] 纯滚动（没有时间轴时）
 * - [CountdownPainter] 倒计时行
 *
 * 帧驱动分两条：主帧负责逐字/时间轴/特效动画；特效另有自己的推进。
 * 两者共用同一个 [Choreographer] 回调，只要还有动画在跑就继续挂帧，
 * 这样歌名行（不在播放态）也能平滑滚动，不必等低频的位置回调。
 */
internal class LyricLineView(context: Context) : View(context) {

    // ——— 渲染器 ———

    private val progressive = ProgressivePainter()
    private val timeline = LineTimelinePainter()
    private val marquee = MarqueePainter()
    private val countdownPainter = CountdownPainter()
    private var activeRenderer: LineRenderer = marquee

    // ——— 特效 ———

    private val effectRenderer = LyricEffectRenderer()

    /** 未唱部分的色带。 */
    private var effectBasePalette: IntArray = IntArray(0)

    /** 已唱部分的色带。 */
    private var effectHighPalette: IntArray = IntArray(0)

    /** 只有样式没给色带时才用这个单色兜底。 */
    var effectSolidColor: Int = DEFAULT_WHITE

    /** 特效编号，[LyricEffectRenderer.EFFECT_STATIC] 表示关闭。 */
    var lyricEffectMode = LyricEffectRenderer.EFFECT_STATIC
        set(value) {
            if (field == value) return
            field = value
            if (value == LyricEffectRenderer.EFFECT_STATIC) {
                effectRenderer.reset()
            }
            invalidate()
        }

    /**
     * 逐词强调开关（「歌词效果」里的独立开关，不再作为歌词动画效果的下拉项）。
     * 开启时走正常逐字渲染并叠加强调，整行动画特效让位（二选一，强调优先）。
     */
    var emphasisGlow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            progressive.emphasisOn = value
            if (value) effectRenderer.reset()
            invalidate()
        }

    /** 特效开启且当前真的有歌词文本时才算激活。逐词强调开启时整行特效让位，不走 effectRenderer。 */
    private val effectActive: Boolean
        get() = !emphasisGlow &&
            lyricEffectMode != LyricEffectRenderer.EFFECT_STATIC &&
            !model.text.isNullOrEmpty()

    /**
     * 整句连续配色的起点偏移。
     *
     * 超级岛把一句歌词拆到左右两个视图里画，如果两边都从色带第一个颜色开始，
     * 看上去就是两套彩虹。右段用左段的字数做偏移，色序才能从左贯到右。
     */
    var paletteOffset: Int = 0
        set(value) {
            if (field == value) return
            field = value
            HookLogger.dState(
                "palette:$PALETTE_TAG",
                PALETTE_TAG,
                "$value|${activeRenderer.javaClass.simpleName}"
            ) {
                "LyricLineView.paletteOffset=$value renderer=${activeRenderer.javaClass.simpleName}"
            }
            progressive.setPaletteOffset(value)
            timeline.setPaletteOffset(value)
            marquee.setPaletteOffset(value)
            countdownPainter.setPaletteOffset(value)
            effectRenderer.paletteOffset = value
            invalidate()
        }

    // ——— 模型 ———

    var model: LyricRenderModel = LyricRenderModel.empty()
        private set

    private val renderState = LineRenderState()

    // ——— 播放状态 ———

    private var playbackActive = false
    private var lastPosition = Long.MIN_VALUE
    private var playbackSpeed = 1f

    // ——— 倒影 ———

    var mirrorEnabled = false
        set(value) {
            if (field == value) return
            field = value
            // 倒影会撑高视图，必须重新布局
            requestLayout()
            invalidate()
        }

    // ——— 帧驱动 ———

    private var frameScheduled = false
    private var lastFrameNanos = 0L

    /** 上次 invalidate 的时刻，用于按帧率节流。 */
    private var lastInvalidateMs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!isAttachedToWindow) return
            stepFrame(frameTimeNanos)
        }
    }

    // ——— 行为开关 ———

    var isSustainProgressEnabled: Boolean
        get() = progressive.sustainAwareProgress
        set(value) {
            progressive.sustainAwareProgress = value
        }

    var isScrollOnly: Boolean
        get() = progressive.marqueeOnly
        set(value) {
            progressive.marqueeOnly = value
        }

    /** 分离模式右栏：优先靠右。 */
    var rightIfPossible: Boolean
        get() = progressive.rightIfPossible
        set(value) {
            progressive.rightIfPossible = value
            timeline.rightIfPossible = value
            marquee.rightIfPossible = value
            countdownPainter.rightIfPossible = value
        }

    /** 空间够时居中。 */
    var centerIfPossible: Boolean
        get() = progressive.centerIfPossible
        set(value) {
            progressive.centerIfPossible = value
            timeline.centerIfPossible = value
            marquee.centerIfPossible = value
            countdownPainter.centerIfPossible = value
        }

    var visibleIfChanged = true
    var isStaticPreview = false
    var playListener: LyricPlayListener? = null

    /** 整行直接全亮（没有逐字数据且关闭模拟逐字）：不跑推进动画。 */
    private var fullHighlightFlag = false

    /** 上一次真正应用过的全亮状态：开关切换时文本没变也必须重建。 */
    private var appliedFullHighlight = false

    /** 当前渲染器使用的文字画笔，挂载点测量时读它。 */
    val textPaint: TextPaint
        get() = when (activeRenderer) {
            is ProgressivePainter -> progressive.dimPaint
            is LineTimelinePainter -> timeline.dimPaint
            is MarqueePainter -> marquee.plainPaint
            else -> countdownPainter.plainPaint
        }

    /** 当前行渲染宽度。 */
    val lineWidth: Float
        get() = model.width

    /** 对侧行宽度，分离模式协调用；目前只记录不参与计算。 */
    var peerLineWidth: Float = 0f
        private set

    var spaceGateEnabled = false

    fun setPeerLineWidth(width: Float) {
        peerLineWidth = width
    }

    // ——— 设置内容 ———

    fun setLyric(line: ILyricLine?, lineTimeline: Boolean, fullHighlight: Boolean = false) {
        fullHighlightFlag = fullHighlight
        applyLine(line, lineTimeline, keepScroll = false)
    }

    /** 换行但保留滚动位置，避免长行在滚动中被重置到行首。 */
    fun setLyricPreservingScroll(
        line: ILyricLine?,
        lineTimeline: Boolean,
        fullHighlight: Boolean = false
    ) {
        fullHighlightFlag = fullHighlight
        applyLine(line, lineTimeline, keepScroll = true)
    }

    private fun applyLine(rawLine: ILyricLine?, lineTimeline: Boolean, keepScroll: Boolean) {
        // 与上游 setLyricPreservingScroll 对齐：空文本行（倒计时行除外）按 null 处理。
        // 否则空文本照样进渲染器（日志里 MarqueePainter text='' 出现过 88 次），占掉槽位，
        // 表现为"半句有、半句空"或者整槽不显示。
        val rawIsCountdown =
            rawLine?.metadata?.getBoolean(KEY_COUNTDOWN_LINE, false) == true
        val line =
            if (rawLine != null && !rawIsCountdown && rawLine.text.isNullOrBlank()) {
                null
            } else {
                rawLine
            }
        if (line == null) {
            model = LyricRenderModel.empty()
            countdownMeasureContent = false
            activeRenderer.reset(renderState)
            lastPosition = Long.MIN_VALUE
            invalidate()
            return
        }

        val isCountdown = line.metadata?.getBoolean(KEY_COUNTDOWN_LINE, false) == true
        countdownMeasureContent = isCountdown
        if (isCountdown) {
            // 整首剩余用 -mm:ss；行内占位倒计时保留 mm:ss.d
            countdownPainter.wholeTrack =
                line.metadata?.getBoolean(KEY_COUNTDOWN_WHOLE_TRACK, false) == true
        }
        val isTitle = line.metadata?.getBoolean(KEY_TITLE_LINE, false) == true

        val newModel = LyricRenderModel.build(
            paint = textPaint,
            line = line,
            lineTimeline = lineTimeline,
            isTitleLine = isTitle,
            isCountdownLine = isCountdown
        )

        // 内容没变就不重建。倒计时行例外：它的文本可能和空行一样，
        // 但必须按新内容走一遍，否则占位永远出不来。
        if (visibleIfChanged && !keepScroll && !isCountdown &&
            model.fullText == newModel.fullText && model.width == newModel.width &&
            appliedFullHighlight == fullHighlightFlag
        ) {
            return
        }

        appliedFullHighlight = fullHighlightFlag
        model = newModel
        activeRenderer = chooseRenderer(isCountdown, lineTimeline)
        // 内容变了高度可能也变，必须重新测量，否则副行会被主行挤成 0 高
        requestLayout()
        // 换行必须清掉上一句的动画状态，否则新句会从旧宽度往回插值
        activeRenderer.reset(renderState)
        HookLogger.dState(
            "renderer:$VIEW_TAG",
            VIEW_TAG,
            "${activeRenderer.javaClass.simpleName}|$lineTimeline|$fullHighlightFlag|$isCountdown"
        ) {
            "渲染器选择: ${activeRenderer.javaClass.simpleName} " +
                "words=${model.words.size} lineTimeline=$lineTimeline " +
                "fullHighlight=$fullHighlightFlag " +
                "countdown=$isCountdown text='${model.fullText?.take(30)}' " +
                "begin=${model.begin} end=${model.end} dur=${model.duration}"
        }
        activeRenderer.updateLayout(model, renderState, width, height)
        // 全亮模式：换行即跳满，不依赖位置推进
        if (fullHighlightFlag) {
            progressive.jumpToFull(model, renderState, width)
        }
        if (!keepScroll) lastPosition = Long.MIN_VALUE
        invalidate()
        ensureFrameScheduled()
    }

    /** 按行的形态挑渲染器。 */
    private fun chooseRenderer(isCountdown: Boolean, lineTimeline: Boolean): LineRenderer = when {
        fullHighlightFlag -> progressive
        isCountdown -> countdownPainter
        lineTimeline -> timeline
        model.words.isNotEmpty() -> progressive
        else -> marquee
    }

    // ——— 样式 ———

    fun configureWith(
        text: TextLook,
        highlight: Highlight,
        gradient: Boolean,
        centerIfPossible: Boolean,
        rightIfPossible: Boolean,
        wordMotion: WordMotion? = null
    ) {
        // 特效直接用样式的两条色带，和普通渲染同源
        effectBasePalette = highlight.background
        effectHighPalette = highlight.foreground

        val typeface = text.typeface
        progressive.applyFontSettings(text.size, typeface, null)
        timeline.applyFontSettings(text.size, typeface, null)
        marquee.applyFontSettings(text.size, typeface, null)
        countdownPainter.applyFontSettings(text.size, typeface, null)

        applyShadowToAllPaints(text)


        progressive.setColors(highlight.background, highlight.foreground)
        timeline.setColors(highlight.background, highlight.foreground)
        val primaryColor = highlight.foreground.firstOrNull() ?: DEFAULT_WHITE
        marquee.setColor(primaryColor)
        // 无逐字数据时会走跑马灯；把整条高亮色带也交给它，
        // 这样彩虹 16 色能按字铺开，而不是整行只取第一个颜色。
        marquee.setBandColors(highlight.foreground)
        countdownPainter.plainPaint.color = primaryColor

        progressive.fadeIn = gradient
        progressive.centerIfPossible = centerIfPossible
        progressive.rightIfPossible = rightIfPossible
        timeline.centerIfPossible = centerIfPossible
        timeline.rightIfPossible = rightIfPossible
        marquee.centerIfPossible = centerIfPossible
        marquee.rightIfPossible = rightIfPossible

        if (wordMotion != null) progressive.waveOn = wordMotion.enabled
    }

    /** 阴影：半径 > 0 才设置，否则清掉（清掉时不会残留上一套样式的阴影）。 */
    private fun applyShadowToAllPaints(text: TextLook) {
        allTextPaints().forEach { paint ->
            if (text.shadowRadius > 0f) {
                paint.setShadowLayer(
                    text.shadowRadius,
                    text.shadowDx,
                    text.shadowDy,
                    text.shadowColor
                )
            } else {
                paint.clearShadowLayer()
            }
        }
    }

    private fun allTextPaints(): List<Paint> = listOf(
        progressive.dimPaint, progressive.litPaint,
        timeline.dimPaint, timeline.litPaint,
        marquee.plainPaint, countdownPainter.plainPaint
    )

    // ——— 播放驱动 ———

    fun updatePosition(posMs: Long, playbackSpeed: Float) {
        this.playbackSpeed =
            if (playbackSpeed.isFinite() && playbackSpeed > 0f) playbackSpeed else 1f
        // 全亮模式：位置只是个时钟，高亮始终满宽，不用逐帧推进
        if (fullHighlightFlag) {
            if (posMs == lastPosition) return
            lastPosition = posMs
            progressive.jumpToFull(model, renderState, width)
            invalidate()
            return
        }
        // 位置没变且没有动画在跑，就没什么可画的
        if (posMs == lastPosition && !activeRenderer.isAnimating) return
        lastPosition = posMs
        activeRenderer.update(model, renderState, posMs, width, height, this.playbackSpeed)
        invalidate()
        ensureFrameScheduled()
    }

    fun seekTo(posMs: Long) {
        lastPosition = posMs
        if (fullHighlightFlag) {
            progressive.jumpToFull(model, renderState, width)
            invalidate()
            return
        }
        activeRenderer.seek(model, renderState, posMs, width, height)
        invalidate()
    }

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        if (active) {
            lastFrameNanos = 0L
            ensureFrameScheduled()
        } else {
            removeFrameCallback()
            if (model.words.isNotEmpty() || model.lineTimeline) {
                activeRenderer.freeze(model, renderState, width)
            }
            invalidate()
        }
    }

    // ——— 换色 ———

    fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray) {
        progressive.setColors(background, highlight)
        timeline.setColors(background, highlight)
        val color = highlight.firstOrNull() ?: DEFAULT_WHITE
        marquee.setColor(color)
        countdownPainter.plainPaint.color = color
        invalidate()
    }

    /** 预检某行的渲染宽度，动态宽度决策用。 */
    fun measureLineWidth(line: ILyricLine?): Float {
        if (line == null) return 0f
        return LyricRenderModel.build(textPaint, line, false, false, false).width
    }

    /** 当前文字起点 X，行切换动画对齐用。 */
    fun currentTextStartX(): Float =
        resolveTextStartX(model.width, model.isAlignedRight)

    /** 指定文本的起点 X。 */
    fun textStartX(text: String?, isAlignedRight: Boolean): Float =
        resolveTextStartX(textPaint.measureText(text.orEmpty()), isAlignedRight)

    /**
     * 文字起点：放得下时按对齐方式定，放不下就贴左（交给滚动处理）。
     */
    private fun resolveTextStartX(textWidth: Float, isAlignedRight: Boolean): Float {
        val availableWidth = width.toFloat()
        return when {
            textWidth >= availableWidth -> 0f
            isAlignedRight -> availableWidth - textWidth
            else -> 0f
        }
    }

    val textSize: Float get() = textPaint.textSize

    // ——— 帧 ———

    private fun stepFrame(frameTimeNanos: Long) {
        val delta = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
        lastFrameNanos = frameTimeNanos

        // 全亮模式不参与逐帧推进：仅特效动画还在跑时才驱动
        if (fullHighlightFlag) {
            if (effectActive) {
                effectRenderer.step(delta, lyricEffectMode)
                invalidate()
                ensureFrameScheduled()
            } else {
                removeFrameCallback()
            }
            return
        }

        val changed = if (effectActive) {
            effectRenderer.step(delta, lyricEffectMode)
            // 特效模式下逐字高亮也要推进，特效字靠它决定用高亮色还是底色
            activeRenderer.step(delta, model, renderState, width)
            true
        } else {
            activeRenderer.step(delta, model, renderState, width)
        }

        // 节流：进度每帧都算（不然会跳），但重绘按帧率走。
        // 有逐字上跳动画时 30fps，否则 20fps 省电。
        val motionActive = (activeRenderer as? ProgressivePainter)?.waveOn == true
        val frameInterval = if (motionActive) FAST_FRAME_MS else SLOW_FRAME_MS
        val now = System.currentTimeMillis()
        if (changed && now - lastInvalidateMs >= frameInterval) {
            invalidate()
            lastInvalidateMs = now
        }

        // 动画还在跑就继续挂帧；不看 playbackActive，歌名行也要靠帧驱动才平滑
        if (isAttachedToWindow && (activeRenderer.isAnimating || effectActive)) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
            frameScheduled = true
        } else {
            if (changed) invalidate()
            lastFrameNanos = 0L
            lastInvalidateMs = 0L
        }
    }

    private fun ensureFrameScheduled() {
        if (!isAttachedToWindow) return
        if (!activeRenderer.isAnimating && !effectActive) return
        if (frameScheduled) return
        frameScheduled = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun removeFrameCallback() {
        if (!frameScheduled) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    /** 倒计时行文本可能为空，但必须保留高度，画倒计时数字。 */
    private var countdownMeasureContent = false

    // ——— 测量 ———

    /**
     * 内容高度。
     *
     * 没有文本、没有词、也不是倒计时行时返回 0 —— 没有翻译的歌，副行不占高度，
     * 主行不会被顶高。
     */
    private fun measureTextHeight(): Int {
        val hasContent = !model.fullText.isNullOrEmpty() ||
            model.words.isNotEmpty() || countdownMeasureContent
        if (!hasContent) return 0

        val fm = textPaint.fontMetrics
        // 开倒影时多留一条倒影带：文字本身仍在视图正中，多出来的部分只给倒影用
        val mirrorBand = if (mirrorEnabled) (fm.descent - fm.ascent) else 0f
        return (fm.descent - fm.ascent + CONTENT_PADDING + mirrorBand).toInt()
    }

    /** 内容宽度；没有文本就是 0。 */
    private fun measureTextWidth(): Int {
        val text = model.fullText
        if (text.isNullOrEmpty()) return 0
        return textPaint.measureText(text).toInt()
    }

    /**
     * 岛宽度变化时重算滚动偏移。
     *
     * 唱完之后动画停了，滚动偏移会冻结在旧值；岛一伸缩，歌词还停在旧位置，
     * 看着就是「唱完的行莫名横向抖动」。所以宽度一变就重新布局。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw) return
        if (model.width <= 0f) return
        activeRenderer.updateLayout(model, renderState, w, h)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val contentW = measureTextWidth()
        val contentH = measureTextHeight()

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthLimit = MeasureSpec.getSize(widthMeasureSpec)
        val measuredW = when (widthMode) {
            MeasureSpec.EXACTLY -> widthLimit
            MeasureSpec.AT_MOST -> minOf(contentW, widthLimit)
            else -> contentW
        }

        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightLimit = MeasureSpec.getSize(heightMeasureSpec)
        val measuredH = when (heightMode) {
            MeasureSpec.EXACTLY -> heightLimit
            MeasureSpec.AT_MOST -> minOf(contentH, heightLimit)
            else -> contentH
        }

        setMeasuredDimension(measuredW, measuredH)
    }

    // ——— 绘制 ———

    override fun onDraw(canvas: Canvas) {
        // 性能监控（默认关）：只统计歌词行每次重绘的耗时，进来先读一次开关，关着就零成本
        val perfStart = if (com.liuxiansheng.lyricon.hyper.root.perf.HookPerfMonitor.enabledFlag)
            System.nanoTime() else 0L
        try {
            super.onDraw(canvas)
            if (model.width <= 0f) return

            // 文字永远按视图整高居中；倒影带加在下方，不会把歌词顶上去
            val textHeight = height

            if (effectActive) {
                drawEffectLine(canvas, textHeight)
                return
            }

            activeRenderer.draw(canvas, model, renderState, width, textHeight)
            if (mirrorEnabled) {
                drawMirrored(canvas, textHeight) {
                    activeRenderer.draw(canvas, model, renderState, width, textHeight)
                }
            }
        } finally {
            if (perfStart != 0L) {
                com.liuxiansheng.lyricon.hyper.root.perf.HookPerfMonitor.recordSample(
                    "lyric.line.onDraw",
                    System.nanoTime() - perfStart,
                )
            }
        }
    }

    /** 特效模式：整行走动画渲染，超长时跟着滚动，并对接一圈幽灵副本。 */
    private fun drawEffectLine(canvas: Canvas, textHeight: Int) {
        val text = model.text ?: return
        val highlightWidth = progressive.currentWidth()
        val scroll = if (model.width > width) renderState.scrollOffset else 0f
        val ghostGap = marquee.ghostGapPx

        val paintEffect: (Canvas) -> Unit = { c ->
            val save = c.save()
            c.translate(scroll, 0f)
            effectRenderer.draw(
                c, text, progressive.dimPaint, progressive.litPaint,
                width, textHeight, lyricEffectMode, highlightWidth,
                effectBasePalette, effectHighPalette
            )
            // 无缝循环：本体滚出去后把下一圈接在后面
            if (scroll != 0f && model.width + ghostGap < width * 2f &&
                model.width + scroll < width.toFloat()
            ) {
                c.translate(model.width + ghostGap, 0f)
                effectRenderer.draw(
                    c, text, progressive.dimPaint, progressive.litPaint,
                    width, textHeight, lyricEffectMode, highlightWidth,
                    effectBasePalette, effectHighPalette
                )
            }
            c.restoreToCount(save)
        }

        paintEffect(canvas)
        if (mirrorEnabled) {
            drawMirrored(canvas, textHeight) { paintEffect(canvas) }
        }
    }

    /**
     * 当前视图在祖先可见区域里最低能画到的 y（视图坐标）。
     *
     * 岛的内容高度是系统限死的，视图自己的高度只够一行字；倒影必须按祖先真正露出的
     * 高度压缩，否则会被岛底边裁掉。
     */
    private fun visibleBottomInViewSpace(): Float {
        var bottom = height.toFloat()
        var child: View = this
        while (true) {
            val parent = child.parent as? View ?: break
            if (parent.height <= 0) break
            val topInParent = child.top - parent.scrollY
            val room = (parent.height - topInParent).toFloat()
            if (room > 0f) bottom = minOf(bottom, room)
            child = parent
        }
        return bottom
    }

    /**
     * 镜像倒影：以文字基线为轴翻折，按剩余空间压缩，一路铺到可见底边。
     *
     * 不压缩到 0.2 以下、也不放大到 0.8 以上 —— 太低看不见，太高会盖过本体。
     */
    private fun drawMirrored(canvas: Canvas, textHeight: Int, drawContent: () -> Unit) {
        val paint = progressive.dimPaint
        val textY = textHeight / 2f - (paint.descent() + paint.ascent()) / 2f
        val gap = paint.textSize * MIRROR_GAP_RATIO
        val glyphHeight = (paint.descent() - paint.ascent()).coerceAtLeast(1f)
        val visibleBottom = minOf(height.toFloat(), visibleBottomInViewSpace())
        val spaceBelow = (visibleBottom - textY - gap).coerceAtLeast(1f)
        val squash = (spaceBelow / glyphHeight).coerceIn(MIRROR_MIN_SCALE, MIRROR_MAX_SCALE)

        val saveCount = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), visibleBottom, MIRROR_ALPHA)
        try {
            canvas.clipRect(0f, textY, width.toFloat(), visibleBottom)
            canvas.scale(1f, -squash, 0f, textY + gap)
            drawContent()
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    // ——— 生命周期 ———

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureFrameScheduled()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeFrameCallback()
        activeRenderer.reset(renderState)
        lastPosition = Long.MIN_VALUE
        model = LyricRenderModel.empty()
    }

    /** 彻底复位。 */
    fun reset() {
        removeFrameCallback()
        activeRenderer.reset(renderState)
        lastPosition = Long.MIN_VALUE
        playbackSpeed = 1f
        model = LyricRenderModel.empty()
        invalidate()
    }

    /** 把「开始 / 结束 / 进度」推给外部监听（容器层统一派发）。 */
    fun notifyProgressIfChanged() {
        val listener = playListener ?: return
        val renderer = activeRenderer
        if (renderer.isStarted) listener.onPlayStarted(this)
        if (renderer.isFinished) listener.onPlayEnded(this)
        listener.onPlayProgress(this, model.width, progressOf())
    }

    private fun progressOf(): Float = when (activeRenderer) {
        is ProgressivePainter -> progressive.currentWidth()
        is LineTimelinePainter -> timeline.currentWidth()
        else -> 0f
    }

    internal companion object {
        const val VIEW_TAG = "LyricLineView"
        const val PALETTE_TAG = "IslandColorOffset"
        const val KEY_COUNTDOWN_LINE = "CountdownLine"

        /** 倒计时行是否表示「整首剩余」，区别于行内占位倒计时。 */
        const val KEY_COUNTDOWN_WHOLE_TRACK = "CountdownWholeTrack"
        const val KEY_TITLE_LINE = "TitleLine"

        /** 不透明白。 */
        const val DEFAULT_WHITE = 0xFFFFFFFF.toInt()

        /** 有逐字上跳时的重绘间隔：30fps。 */
        const val FAST_FRAME_MS = 33L

        /** 无上跳时的重绘间隔：20fps。 */
        const val SLOW_FRAME_MS = 50L

        /** 测量时给字形留的纵向余量。 */
        const val CONTENT_PADDING = 4f

        /** 倒影与本体之间的间隙，按字号比例。 */
        const val MIRROR_GAP_RATIO = 0.02f

        /** 倒影最小压缩比。 */
        const val MIRROR_MIN_SCALE = 0.2f

        /** 倒影最大压缩比。 */
        const val MIRROR_MAX_SCALE = 0.8f

        /** 倒影整体透明度（0-255）。 */
        const val MIRROR_ALPHA = 80
    }
}

/** 歌词行的播放进度监听。 */
internal interface LyricPlayListener {
    fun onPlayStarted(view: LyricLineView)
    fun onPlayEnded(view: LyricLineView)
    fun onPlayProgress(view: LyricLineView, total: Float, progress: Float)
}
