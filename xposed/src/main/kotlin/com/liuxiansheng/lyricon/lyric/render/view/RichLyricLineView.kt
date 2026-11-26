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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.core.graphics.withScale
import com.liuxiansheng.lyricon.hyper.lyric.model.RichLyricLine
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.IRichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.lyricMetadataOf
import com.liuxiansheng.lyricon.lyric.render.model.LyricRenderModel

/** 可以被外部整行换色的视图。 */
internal interface UpdatableColor {
    fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray)
}

/**
 * 双行歌词视图：上面主行（歌词原文），下面副行（翻译 / 罗马音 / 下一句预览）。
 *
 * 一次换行的流程：组装主副行 → 预检候选宽度（挂载点据此决定岛要不要变宽）
 * → 应用内容 → 需要时播放切换动画。
 *
 * 倒影只跟主行走：副行有内容时主行倒影会自动关掉，否则两行倒影会叠在一起；
 * 副行消失（回到普通歌词行）后自动恢复。
 */
internal open class RichLyricLineView(
    context: Context,
    var displayTranslation: Boolean = true,
    var enableRelativeProgress: Boolean = false,
    var enableRelativeProgressHighlight: Boolean = false,
    var displayRoma: Boolean = true,
    /** 没有逐字数据时是否按时长模拟逐字；关=整行直接全亮。 */
    var simulateUntimedWord: Boolean = false
) : LinearLayout(context), UpdatableColor {

    val main = LyricLineView(context)
    val secondary = LyricLineView(context).apply { visibleIfChanged = false }

    /** 「歌词左右两端」位置用的左右时间；默认隐藏，隐藏时主行独占整行。 */
    private val leftEndTime = LyricLineView(context).apply { visibleIfChanged = false }
    private val rightEndTime = LyricLineView(context).apply { visibleIfChanged = false }

    /** 主行所在的横向容器：左端时间 + 主行 + 右端时间。 */
    private val timeRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    var renderScale = 1.0f
        private set

    /** 用户设置里的倒影开关；最终画不画还要看副行有没有内容。 */
    private var mirrorDesired = false

    /** 副行显示整首剩余时间；副行被翻译 / 下一句 / 罗马音占用时自动让位。 */
    var showRemainingTime: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshLines(preserveDisplay = true)
        }

    /** 整首时长（毫秒），0 表示未知；变化时重算剩余时间行。 */
    var songDuration: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            refreshLines(preserveDisplay = true)
        }

    /** 剩余时间显示位置：主行下方（默认）、主行上方、左右两边。 */
    var remainingPosition: Int = RootConstants.REMAIN_POS_BELOW
        set(value) {
            if (field == value) {
                // 首次赋值即使没变化也留一条，便于核对组装器读到的值
                if (!positionLogOnce) {
                    positionLogOnce = true
                    HookLogger.i(
                        "RemainPos",
                        "pos=$value unchanged side=${splitRightSide()} order=${childOrder()}"
                    )
                }
                return
            }
            field = value
            positionLogOnce = true
            applySecondaryOrder()
            refreshLines(preserveDisplay = true)
        }

    /** 「歌词左右两端」时两端时间额外占的宽度；不是该位置时为 0。岛宽计算要把它算进去。 */
    val endsExtraWidth: Float
        get() {
            var extra = 0f
            if (leftEndTime.visibility == VISIBLE) {
                extra += leftEndTime.lineWidth + leftEndTime.paddingRight
            }
            if (rightEndTime.visibility == VISIBLE) {
                extra += rightEndTime.lineWidth + rightEndTime.paddingLeft
            }
            return extra
        }

    /** 位置日志是否已经打过（只打一次，避免每帧刷屏）。 */
    private var positionLogOnce = false

    /** 按位置把副行放到主行下方或上方。 */
    private fun applySecondaryOrder() {
        val targetIndex = if (remainingPosition == RootConstants.REMAIN_POS_ABOVE) 0 else 1
        if (indexOfChild(secondary) != targetIndex) {
            removeView(secondary)
            addView(secondary, targetIndex, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            invalidate()
        }
        // 打完重排再看顺序，便于用日志核对上下位置
        HookLogger.i(
            "RemainPos",
            "pos=$remainingPosition targetIndex=$targetIndex side=${splitRightSide()} order=${childOrder()}"
        )
    }

    /** 当前子视图顺序（timeRow / secondary），用于核对上下位置。 */
    private fun childOrder(): String {
        val names = (0 until childCount).map { i ->
            val child = getChildAt(i)
            when {
                child === timeRow -> "row"
                child === secondary -> "secondary"
                else -> "other"
            }
        }
        return names.joinToString(">")
    }

    /** 分离歌词模式下的容器侧位：true 右容器、false 左容器、null 表示未分栏。 */
    open fun splitRightSide(): Boolean? = null

    /** 换行动画编号：0 不播，1 副行放大（默认），2 淡入淡出 …… 11 模糊淡入。 */
    var lineSwitchAnimation: Int = ANIM_PROMOTION

    private val assembler = LyricLineAssembler(
        displayTranslation, displayRoma,
        enableRelativeProgress, enableRelativeProgressHighlight,
        displayLineByLine = false,
        simulateUntimedWord = simulateUntimedWord
    )

    private var displayLineByLine = false

    private var rawLine: IRichLyricLine? = null
    private var currentMainText: String? = null

    // 预检回调：宽度没就绪时先问一次，要求延迟就挂一帧重来
    private var pendingWillApply: ((Float) -> Boolean)? = null
    private var pendingApplied: (() -> Unit)? = null
    private var pendingCancelled: (() -> Unit)? = null
    private var preflightDeferred = false

    private var lastPosition = Long.MIN_VALUE
    private var lastPlaybackSpeed = 1f

    init {
        orientation = VERTICAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        leftEndTime.visibility = GONE
        rightEndTime.visibility = GONE
        // 两端时间与歌词之间留一点呼吸
        val gap = (8 * resources.displayMetrics.density).toInt()
        leftEndTime.setPadding(0, 0, gap, 0)
        rightEndTime.setPadding(gap, 0, 0, 0)
        timeRow.addView(leftEndTime, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        timeRow.addView(main, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(rightEndTime, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(timeRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(secondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    var line: IRichLyricLine?
        get() = rawLine
        set(value) {
            rawLine = value
            refreshLines()
        }

    /** 带回调设置行：预检会问「能不能现在就应用」，应用 / 取消各有回调。 */
    fun setLineWithCallbacks(
        value: IRichLyricLine?,
        onMainLineWillApply: ((Float) -> Boolean)? = null,
        onMainLineApplied: (() -> Unit)? = null,
        onMainLineCancelled: (() -> Unit)? = null
    ) {
        pendingWillApply = onMainLineWillApply
        pendingApplied = onMainLineApplied
        pendingCancelled = onMainLineCancelled
        rawLine = value
        refreshLines()
    }

    /** 元数据行（歌名之类）更新，保留当前显示状态，不触发切换动画。 */
    fun updateMetadataLine(value: IRichLyricLine?) {
        rawLine = value
        refreshLines(preserveDisplay = true)
    }

    // ——— 换行 ———

    private fun refreshLines(preserveDisplay: Boolean = false) {
        val mainResult = assembler.buildMain(rawLine)
        val secondaryResult = assembler.buildSecondary(rawLine)

        // 「歌词左右两端」位置要在同一行里塞两个时间，宽度得一起算，否则岛不加宽会被裁掉
        val endsMode = remainingPosition == RootConstants.REMAIN_POS_LYRIC_ENDS &&
            showRemainingTime && songDuration > 0L
        val endsLeft = if (endsMode) buildTotalDurationLine() else null
        val endsRight = if (endsMode) buildRemainLine() else null

        // 预检：取主副行里更宽的那个，交给挂载点判断要不要加宽岛
        val candidateWidth = maxOf(
            main.measureLineWidth(mainResult.line),
            secondary.measureLineWidth(secondaryResult.line)
        ) + (endsLeft?.let { leftEndTime.measureLineWidth(it) } ?: 0f) +
            (endsRight?.let { rightEndTime.measureLineWidth(it) } ?: 0f)
        if (pendingWillApply?.invoke(candidateWidth) == true && !preflightDeferred) {
            preflightDeferred = true
            post { refreshLines(preserveDisplay) }
            return
        }
        preflightDeferred = false
        pendingWillApply = null

        // 只有主行文本真的变了才播切换动画：样式刷新、跑马灯保留都不算换行
        val lineSwitched = !preserveDisplay && mainResult.line.text != currentMainText

        applyMain(mainResult, preserveDisplay)
        applySecondary(secondaryResult, preserveDisplay)
        applyRemainingTime(secondaryResult)
        applyEndsTime(endsLeft, endsRight)
        updateMirrorState()

        if (lineSwitched) {
            // 等一帧让布局落定，否则副行高度 / 主行宽度还是旧的
            post { animateLineSwitch() }
        }

        pendingApplied?.invoke()
        pendingApplied = null
        pendingCancelled = null
    }

    /**
     * 换行动画。
     *
     * 每次开始前先把两个视图的变换全部复位 —— 上一次动画如果被中途打断，
     * 旋转/缩放/透明度会留在视图上（尤其 rotationX 忘了归零时整行会侧对镜头，
     * 看起来就像歌词丢了）。
     */
    private fun animateLineSwitch() {
        if (lineSwitchAnimation == ANIM_NONE) return
        if (main.width <= 0) return

        main.animate().cancel()
        secondary.animate().cancel()
        resetTransforms()

        when (lineSwitchAnimation) {
            ANIM_PROMOTION -> playPromotion()
            ANIM_CROSS_FADE -> playCrossFade()
            ANIM_SLIDE_UP -> playSlide(up = true)
            ANIM_SLIDE_DOWN -> playSlide(up = false)
            ANIM_SCALE_FADE -> playScaleFade()
            ANIM_FLIP_Y -> playFlipVerticalAxis()
            ANIM_SLIDE_LEFT -> playSlideHorizontal(toLeft = true)
            ANIM_SLIDE_RIGHT -> playSlideHorizontal(toLeft = false)
            ANIM_ELASTIC -> playElasticScale()
            ANIM_FLIP_X -> playFlipHorizontalAxis()
            ANIM_BLUR_FADE -> playBlurFade()
            else -> Unit
        }
    }

    /** 模式 1：副行升格为主行 —— 主行淡出上移，副行移动到主行位置并放大。 */
    private fun playPromotion() {
        val targetTranslationY = (main.top - secondary.top).toFloat()
        val targetScale = (main.textSize / secondary.textSize).coerceIn(0.5f, 2f)

        // 副行最终要落在主行的文字起点上，所以横向位移要把两边的起点差补回来
        val translationX = (main.left - secondary.left).toFloat() +
            main.textStartX(currentMainText, false) - secondary.currentTextStartX()

        main.animate()
            .alpha(0f)
            .translationY(-main.height * 0.65f)
            .setDuration(PROMOTION_OUT_MS)
            .withLayer()
            .start()

        // 左栏朝左长、右栏朝右长：锚点跟着容器方向走
        secondary.pivotX = if (main.rightIfPossible) secondary.width.toFloat() else 0f
        secondary.pivotY = 0f
        secondary.animate()
            .translationX(translationX)
            .translationY(targetTranslationY)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(PROMOTION_OUT_MS)
            .withLayer()
            .withEndAction { resetTransforms() }
            .start()
    }

    /** 模式 2：两位都原地不动，主行淡出、副行淡入。 */
    private fun playCrossFade() {
        main.animate().alpha(0f).setDuration(SWITCH_MS).withLayer().start()
        secondary.alpha = 0f
        secondary.animate()
            .alpha(1f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction { resetTransforms() }
            .start()
    }

    /** 模式 3 / 4：主行往一个方向移出，副行从另一侧滑进来。 */
    private fun playSlide(up: Boolean) {
        val direction = if (up) -1f else 1f
        main.animate()
            .alpha(0f)
            .translationY(direction * main.height * 0.6f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .start()

        secondary.alpha = 0f
        secondary.translationY = -direction * secondary.height * 0.6f
        secondary.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction { resetTransforms() }
            .start()
    }

    /** 模式 5：主行缩小淡出，副行放大淡入。 */
    private fun playScaleFade() {
        main.pivotX = main.width / 2f
        main.pivotY = main.height / 2f
        main.animate()
            .alpha(0f)
            .scaleX(0.86f)
            .scaleY(0.86f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .start()

        secondary.pivotX = secondary.width / 2f
        secondary.pivotY = secondary.height / 2f
        secondary.alpha = 0f
        secondary.scaleX = 1.12f
        secondary.scaleY = 1.12f
        secondary.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction { resetTransforms() }
            .start()
    }

    /** 模式 6：绕纵轴翻出翻入。 */
    private fun playFlipVerticalAxis() {
        prepareCamera()
        main.pivotX = main.width / 2f
        main.pivotY = main.height / 2f
        main.animate()
            .alpha(0f)
            .rotationY(-90f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction { main.rotationY = 0f }
            .start()

        secondary.pivotX = secondary.width / 2f
        secondary.pivotY = secondary.height / 2f
        secondary.alpha = 0f
        secondary.rotationY = 90f
        secondary.animate()
            .alpha(1f)
            .rotationY(0f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction { resetTransforms() }
            .start()
    }

    /** 模式 7 / 8：左右横向滑出滑入。 */
    private fun playSlideHorizontal(toLeft: Boolean) {
        val direction = if (toLeft) -1f else 1f
        main.animate()
            .alpha(0f)
            .translationX(direction * main.width * 0.45f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .start()

        secondary.alpha = 0f
        secondary.translationX = -direction * secondary.width * 0.45f
        secondary.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction { resetTransforms() }
            .start()
    }

    /** 模式 9：副行从 0.7 带一点过冲弹到 1。 */
    private fun playElasticScale() {
        main.pivotX = main.width / 2f
        main.pivotY = main.height / 2f
        main.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .start()

        secondary.pivotX = secondary.width / 2f
        secondary.pivotY = secondary.height / 2f
        secondary.alpha = 0f
        secondary.scaleX = 0.7f
        secondary.scaleY = 0.7f
        secondary.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setInterpolator(OvershootInterpolator(1.6f))
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction { resetTransforms() }
            .start()
    }

    /** 模式 10：绕横轴上下翻出翻入。 */
    private fun playFlipHorizontalAxis() {
        prepareCamera()
        main.pivotX = main.width / 2f
        main.pivotY = main.height / 2f
        main.animate()
            .alpha(0f)
            .rotationX(-90f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction { main.rotationX = 0f }
            .start()

        secondary.pivotX = secondary.width / 2f
        secondary.pivotY = secondary.height / 2f
        secondary.alpha = 0f
        secondary.rotationX = 90f
        secondary.animate()
            .alpha(1f)
            .rotationX(0f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction { resetTransforms() }
            .start()
    }

    /**
     * 模式 11：模糊淡入。
     *
     * Android 12 起用 RenderEffect 真模糊；低版本只能退化成普通淡入淡出。
     * 模糊半径主行走到满，副行同步走到 65%，收尾时一定要清掉效果。
     */
    private fun playBlurFade() {
        val blurAnimator: android.animation.ValueAnimator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val maxRadius = BLUR_MAX_DP * resources.displayMetrics.density
                android.animation.ValueAnimator.ofFloat(0f, maxRadius).apply {
                    setDuration(BLUR_DURATION_MS)
                    addUpdateListener { anim ->
                        val radius = anim.animatedValue as Float
                        main.setRenderEffect(
                            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                        )
                        secondary.setRenderEffect(
                            RenderEffect.createBlurEffect(
                                radius * BLUR_SECONDARY_RATIO, radius * BLUR_SECONDARY_RATIO,
                                Shader.TileMode.CLAMP
                            )
                        )
                    }
                    start()
                }
            } else {
                null
            }

        main.animate()
            .alpha(0f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .start()

        secondary.alpha = 0f
        secondary.animate()
            .alpha(1f)
            .setDuration(SWITCH_MS)
            .withLayer()
            .withEndAction {
                blurAnimator?.cancel()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    main.setRenderEffect(null)
                    secondary.setRenderEffect(null)
                }
                resetTransforms()
            }
            .start()
    }

    /** 把所有变换 / 透明度 / 旋转恢复到初始态。 */
    private fun resetTransforms() {
        resetOne(main)
        resetOne(secondary)
    }

    private fun resetOne(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.rotationY = 0f
        view.rotationX = 0f
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
    }

    /** 3D 翻转需要合适的镜头距离，按屏幕密度换算。 */
    private fun prepareCamera() {
        val distance = CAMERA_DISTANCE_DP * resources.displayMetrics.density
        main.cameraDistance = distance
        secondary.cameraDistance = distance
    }

    // ——— 应用内容 ———

    private fun applyMain(result: LyricLineAssembler.MainResult, preserveDisplay: Boolean) {
        main.isSustainProgressEnabled = result.sustainAwareProgress
        main.isScrollOnly = result.isScrollOnly
        if (preserveDisplay) {
            main.setLyricPreservingScroll(result.line, result.isLineTimeline, result.fullHighlight)
        } else {
            main.setLyric(result.line, result.isLineTimeline, result.fullHighlight)
        }
        currentMainText = result.line.text
    }

    private fun applySecondary(
        result: LyricLineAssembler.SecondaryResult,
        preserveDisplay: Boolean
    ) {
        secondary.isSustainProgressEnabled = result.sustainAwareProgress
        secondary.isScrollOnly = result.isScrollOnly
        secondary.visibleIfChanged = result.alwaysShow
        secondary.isStaticPreview = result.isNextLinePreview
        if (preserveDisplay) {
            secondary.setLyricPreservingScroll(result.line, result.isLineTimeline)
        } else {
            secondary.setLyric(result.line, result.isLineTimeline)
        }
    }

    /**
     * 副行空闲时，用倒计时行在副行位置显示整首剩余。
     *
     * 副行已经被翻译 / 下一句预览 / 罗马音占用时让位，一个字都不抢。
     * 位置：主行下方 / 主行上方 / 左右两边（左整首时长、右剩余）。
     */
    private fun applyRemainingTime(secondaryResult: LyricLineAssembler.SecondaryResult) {
        if (!showRemainingTime || songDuration <= 0L) return
        // 「歌词左右两端」不在副行放时间，交给 applyEndsTime
        if (remainingPosition == RootConstants.REMAIN_POS_LYRIC_ENDS) return
        // 组装器判定副行有内容（翻译 / 下一句 / 罗马音）时让位，一个字都不抢
        if (secondaryResult.alwaysShow) return
        val rightSide = splitRightSide()
        val bothSides = remainingPosition == RootConstants.REMAIN_POS_BOTH_SIDES
        // 非「两边」模式下，分栏左容器不显示时间，避免左右各报一个重复的数
        if (!bothSides && rightSide == false) return
        // 左容器只报总时长；右容器和未分栏时报剩余
        val showTotalDuration = bothSides && rightSide == false
        // 对齐：贴向中间挖孔一侧（左容器靠右、右容器靠左），未分栏沿用右对齐
        val alignRight = rightSide != true
        val line = if (showTotalDuration) {
            buildTotalDurationLine(alignRight)
        } else {
            buildRemainLine(alignRight)
        }
        secondary.isSustainProgressEnabled = false
        secondary.isScrollOnly = false
        secondary.visibleIfChanged = false
        secondary.isStaticPreview = false
        secondary.setLyric(line, false)
    }

    /** 整首总时长行（静态，格式 mm:ss）。 */
    private fun buildTotalDurationLine(alignRight: Boolean = false): RichLyricLine = RichLyricLine(
        begin = 0L,
        end = songDuration,
        isAlignedRight = alignRight,
        metadata = lyricMetadataOf(),
        text = formatClock(songDuration)
    )

    /** 整首剩余行（倒计时，格式 -mm:ss）。 */
    private fun buildRemainLine(alignRight: Boolean = true): RichLyricLine = RichLyricLine(
        begin = 0L,
        end = songDuration,
        isAlignedRight = alignRight,
        metadata = lyricMetadataOf(
            LyricLineView.KEY_COUNTDOWN_LINE to "true",
            LyricLineView.KEY_COUNTDOWN_WHOLE_TRACK to "true"
        ),
        // 占位文本：倒计时行的文字不会被画出来，数字由 CountdownPainter 自己绘；
        // 但模型必须有个非空文本，否则 width=0，onDraw 会直接 return
        text = "-00:00"
    )

    /** 「歌词左右两端」：左端总时长、右端剩余；两端为空时隐藏，主行独占整行。 */
    private fun applyEndsTime(left: IRichLyricLine?, right: IRichLyricLine?) {
        // 分离歌词模式是两个容器：左容器只管左边总时长，右容器只管右边剩余，
        // 否则每侧各塞一对，同一个数会被报两遍、还挤掉歌词。
        val side = splitRightSide()
        val showLeft = left != null && side != true
        val showRight = right != null && side != false
        leftEndTime.visibility = if (showLeft) VISIBLE else GONE
        rightEndTime.visibility = if (showRight) VISIBLE else GONE
        leftEndTime.setLyric(if (showLeft) left else null, false)
        rightEndTime.setLyric(if (showRight) right else null, false)
        requestLayout()
    }

    /** 整首时长显示成 mm:ss。 */
    private fun formatClock(ms: Long): String {
        val totalSec = (ms + 999L) / 1000L
        val minutes = totalSec / 60L
        val seconds = totalSec % 60L
        val mm = if (minutes < 10L) "0$minutes" else minutes.toString()
        val ss = if (seconds < 10L) "0$seconds" else seconds.toString()
        return "$mm:$ss"
    }

    /** 副行只要有文本就把主行倒影关掉，避免两层倒影叠在一起。 */
    private fun updateMirrorState() {
        val hasSecondary = !secondary.model.text.isNullOrEmpty()
        main.mirrorEnabled = mirrorDesired && !hasSecondary
    }

    // ——— 播放驱动 ———

    fun setPosition(position: Long, playbackSpeed: Float = 1f) {
        val speed = if (playbackSpeed.isFinite() && playbackSpeed > 0f) playbackSpeed else 1f
        if (lastPosition == position && lastPlaybackSpeed == speed) return
        lastPosition = position
        lastPlaybackSpeed = speed
        main.updatePosition(position, speed)
        secondary.updatePosition(position, speed)
        leftEndTime.updatePosition(position, speed)
        rightEndTime.updatePosition(position, speed)
    }

    fun seekTo(position: Long) {
        lastPosition = position
        main.seekTo(position)
        secondary.seekTo(position)
    }

    fun setPlaybackActive(active: Boolean) {
        main.setPlaybackActive(active)
        secondary.setPlaybackActive(active)
    }

    /** 整句连续配色偏移：主副行一起设，保证色序接得上。 */
    var paletteOffset: Int = 0
        set(value) {
            field = value
            main.paletteOffset = value
            secondary.paletteOffset = value
        }

    // ——— 样式 ———

    open fun setStyle(style: LyricViewStyle) {
        displayLineByLine = style.lineDisplay
        assembler.updateFlags(
            displayTranslation, displayRoma,
            style.primary.relativeProgress, style.primary.relativeHighlight,
            displayLineByLine,
            simulateUntimedWord
        )
        mirrorDesired = style.mirror
        // 进度条「跟随」模式取主行首色（彩虹模式走固定渐变，不用这个）
        style.primary.colors.firstOrNull()?.let { if (it != 0) progressBarFollowColor = it }

        applyLineStyle(
            view = main,
            text = style.primary,
            highlight = style.highlight,
            gradient = style.gradient,
            wordMotion = style.wordMotion,
            centerIfPossible = style.centerIfPossible,
            rightIfPossible = style.rightIfPossible,
            mirror = style.mirror,
            lyricEffect = style.lyricEffect,
            effectSolidColor = style.effectSolidColor,
            emphasisGlow = style.emphasisGlow
        )
        applyLineStyle(
            view = secondary,
            text = style.secondary,
            highlight = style.highlight,
            gradient = style.gradient,
            wordMotion = style.wordMotion,
            centerIfPossible = style.centerIfPossible,
            rightIfPossible = style.rightIfPossible,
            // 副行不画倒影
            mirror = false,
            lyricEffect = style.lyricEffect,
            effectSolidColor = style.effectSolidColor,
            emphasisGlow = style.emphasisGlow
        )
        // 歌词左右两端的时间：左端贴左、右端贴右，不居中
        applyLineStyle(
            view = leftEndTime,
            text = style.secondary,
            highlight = style.highlight,
            gradient = style.gradient,
            wordMotion = style.wordMotion,
            centerIfPossible = false,
            rightIfPossible = false,
            mirror = false,
            lyricEffect = style.lyricEffect,
            effectSolidColor = style.effectSolidColor,
            emphasisGlow = style.emphasisGlow
        )
        applyLineStyle(
            view = rightEndTime,
            text = style.secondary,
            highlight = style.highlight,
            gradient = style.gradient,
            wordMotion = style.wordMotion,
            centerIfPossible = false,
            rightIfPossible = true,
            mirror = false,
            lyricEffect = style.lyricEffect,
            effectSolidColor = style.effectSolidColor,
            emphasisGlow = style.emphasisGlow
        )

        refreshLines()
        updateMirrorState()
    }

    private fun applyLineStyle(
        view: LyricLineView,
        text: TextLook,
        highlight: Highlight,
        gradient: Boolean,
        wordMotion: WordMotion,
        centerIfPossible: Boolean,
        rightIfPossible: Boolean,
        mirror: Boolean,
        lyricEffect: Int,
        effectSolidColor: Int,
        emphasisGlow: Boolean
    ) {
        // 单色必须在 configureWith 之前设，configureWith 会用它重建特效调色板
        view.effectSolidColor = effectSolidColor
        view.configureWith(text, highlight, gradient, centerIfPossible, rightIfPossible, wordMotion)
        view.mirrorEnabled = mirror
        view.emphasisGlow = emphasisGlow
        view.lyricEffectMode = lyricEffect
    }

    override fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray) {
        main.updateColor(primary, background, highlight)
        secondary.updateColor(primary, background, highlight)
        leftEndTime.updateColor(primary, background, highlight)
        rightEndTime.updateColor(primary, background, highlight)
    }

    // ——— 状态栏歌词播放进度条 ———

    /** 是否显示进度条。 */
    var progressBarEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 进度条高度（像素），上游默认 1dp。 */
    var progressBarHeightPx: Float = 3f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 距歌词边缘的偏移（像素）。 */
    var progressBarOffsetPx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** true=歌词上方，false=歌词下方（上游默认下方）。 */
    var progressBarAbove: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 0=跟随歌词色 1=彩虹渐变（上游默认彩虹）。 */
    var progressBarColorMode: Int = 1
        set(value) {
            if (field == value) return
            field = value
            progressBarShader = null
            invalidate()
        }

    private var progressFraction: Float = 0f
    private var progressBarFollowColor: Int = 0xFFFFFFFF.toInt()
    private val progressBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progressBarShader: Shader? = null

    /** 锚点日志只打一次（每次配置改动后重置）。 */
    private var progressBarAnchorLogged = false

    /** 批量下发进度条配置，避免多次 invalidate。 */
    fun applyProgressBar(
        enabled: Boolean,
        heightPx: Float,
        offsetPx: Float,
        above: Boolean,
        colorMode: Int
    ) {
        val changed = progressBarEnabled != enabled ||
            progressBarHeightPx != heightPx ||
            progressBarOffsetPx != offsetPx ||
            progressBarAbove != above ||
            progressBarColorMode != colorMode
        progressBarEnabled = enabled
        progressBarHeightPx = heightPx
        progressBarOffsetPx = offsetPx
        progressBarAbove = above
        if (progressBarColorMode != colorMode) {
            progressBarColorMode = colorMode
            progressBarShader = null
        }
        if (changed) {
            progressBarAnchorLogged = false
            invalidate()
        }
    }

    /** 播放进度（0~1）。变化小于 0.2% 不重绘，省状态栏刷新。 */
    fun setPlaybackProgress(fraction: Float) {
        if (progressBarEnabled.not()) return
        val f = when {
            fraction.isNaN() -> 0f
            fraction < 0f -> 0f
            fraction > 1f -> 1f
            else -> fraction
        }
        val delta = if (f > progressFraction) f - progressFraction else progressFraction - f
        if (delta < 0.002f && !(f == 0f && progressFraction > 0f) && !(f == 1f && progressFraction < 1f)) return
        progressFraction = f
        invalidate()
    }

    /** 彩虹配色（对齐上游 7 色）。 */
    private fun rainbowColors(): IntArray = intArrayOf(
        0xFFFF4D4F.toInt(), 0xFFFF9F43.toInt(), 0xFFFFD93D.toInt(), 0xFF2ED573.toInt(),
        0xFF1E90FF.toInt(), 0xFF5F27CD.toInt(), 0xFFA55EEA.toInt()
    )

    /** 歌词文字实际占的纵向范围（本视图坐标系）。没有可用来定位的子视图时返回 null。 */
    private fun lyricContentBand(): Pair<Float, Float>? {
        var top = Float.MAX_VALUE
        var bottom = -1f
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != VISIBLE || child.height <= 0) continue
            if (child.top < top) top = child.top.toFloat()
            if (child.bottom > bottom) bottom = child.bottom.toFloat()
        }
        return if (bottom > top) top to bottom else null
    }

    private fun drawProgressBar(canvas: Canvas) {
        if (!progressBarEnabled) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val barHeight = progressBarHeightPx.coerceIn(1f, h / 2f)
        val radius = barHeight / 2f
        // 左右各留 barHeight 的余量，圆角不被裁掉
        val left = barHeight / 2f
        val right = w - barHeight / 2f
        if (right <= left) return
        // 以歌词文字实际占的上下边为锚点，再留 1dp 基础间距 + 用户设置的距离，
        // 不再贴视图最上/最下边缘（贴边会离字太远，上方那条还会被状态栏顶边裁掉）
        val gap = resources.displayMetrics.density + progressBarOffsetPx
        val band = lyricContentBand()
        val rawTop = if (progressBarAbove) {
            (band?.first ?: (0f + paddingTop)) - barHeight - gap
        } else {
            (band?.second ?: (h - paddingBottom)) + gap
        }
        val top = rawTop.coerceIn(0f, h - barHeight)
        val filled = left + (right - left) * progressFraction
        if (filled <= left) return
        if (!progressBarAnchorLogged) {
            progressBarAnchorLogged = true
            HookLogger.i(
                "LyricProgressBar",
                "进度条锚点 bandTop=${band?.first} bandBottom=${band?.second} " +
                    "above=$progressBarAbove top=$top h=$h"
            )
        }

        progressBarPaint.style = Paint.Style.FILL
        if (progressBarColorMode == 1) {
            val shader = progressBarShader ?: LinearGradient(
                left, 0f, right, 0f,
                rainbowColors(), null, Shader.TileMode.CLAMP
            ).also { progressBarShader = it }
            progressBarPaint.shader = shader
            progressBarPaint.color = 0xFFFFFFFF.toInt()
        } else {
            progressBarPaint.shader = null
            progressBarPaint.color = progressBarFollowColor
        }
        progressBarPaint.alpha = 255
        canvas.drawRoundRect(left, top, filled, top + barHeight, radius, radius, progressBarPaint)
    }

    // ——— 岛缩放 ———

    fun setRenderScale(scale: Float) {
        if (renderScale == scale) return
        renderScale = scale
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (renderScale == 1.0f || renderScale <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // 按缩放比反向放大可用宽度再量，最后报回原始宽度
        val originalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val compensated = (originalWidth / renderScale).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(compensated, mode), heightMeasureSpec)
        setMeasuredDimension(originalWidth, measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (renderScale != 1.0f) {
            canvas.withScale(renderScale, renderScale, 0f, height / 2f) {
                super.dispatchDraw(this)
                drawProgressBar(this)
            }
        } else {
            super.dispatchDraw(canvas)
            drawProgressBar(canvas)
        }
    }

    // ——— 生命周期 ———

    fun reset() {
        rawLine = null
        renderScale = 1.0f
        currentMainText = null
        pendingWillApply = null
        pendingApplied = null
        pendingCancelled = null
        preflightDeferred = false
        lastPosition = Long.MIN_VALUE
        lastPlaybackSpeed = 1f
        main.reset()
        secondary.reset()
        leftEndTime.reset()
        rightEndTime.reset()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
    }

    private companion object {
        const val ANIM_NONE = 0
        const val ANIM_PROMOTION = 1
        const val ANIM_CROSS_FADE = 2
        const val ANIM_SLIDE_UP = 3
        const val ANIM_SLIDE_DOWN = 4
        const val ANIM_SCALE_FADE = 5
        const val ANIM_FLIP_Y = 6
        const val ANIM_SLIDE_LEFT = 7
        const val ANIM_SLIDE_RIGHT = 8
        const val ANIM_ELASTIC = 9
        const val ANIM_FLIP_X = 10
        const val ANIM_BLUR_FADE = 11

        /** 常规换行动画时长。 */
        const val SWITCH_MS = 320L

        /** 副行升格动画稍长一点，位移更大。 */
        const val PROMOTION_OUT_MS = 340L

        /** 3D 翻转的镜头距离（dp）。 */
        const val CAMERA_DISTANCE_DP = 8000f

        /** 模糊淡入的最大半径（dp）。 */
        const val BLUR_MAX_DP = 22f

        /** 副行模糊半径是主行的比例。 */
        const val BLUR_SECONDARY_RATIO = 0.65f

        /** 模糊动画时长。 */
        const val BLUR_DURATION_MS = 320L
    }
}
