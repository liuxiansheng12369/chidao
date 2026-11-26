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

import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import com.liuxiansheng.lyricon.lyric.render.model.LyricRenderModel
import com.liuxiansheng.lyricon.lyric.render.model.WordRenderModel

/**
 * 逐字高亮的进度中枢：把「媒体时间」翻译成「高亮宽度」。
 *
 * 两个输入源：
 * 1. [update] —— 播放器回调推进位置（低频，几十一百毫秒一次）；
 * 2. [step] —— 每帧推进（高频），在没有新位置回调时自己往前推时间。
 *
 * 只靠 ① 的话，词唱完动画就停在字尾，要等下一条位置回调才切下一个字，
 * 观感是「字尾停顿然后跳一下」。所以 ② 每帧用虚拟时间检查词边界，
 * 目标一变就立刻重锚动画，词与词之间无缝衔接。
 *
 * 行尾还有一个特判：位置越过行尾且目标宽度已经够整行，直接跳满、不做插值。
 * 否则刷新（开关切换会 reset 清零高亮）后，会用 1 毫秒从 0 扫到满，
 * 一帧内闪一遍 —— 就是「整行全亮时字抖一下」的来源。
 */
internal class WordProgressController {

    /** 高亮宽度的插值器，外部直接读它取当前宽度 / 动画状态。 */
    val animator = ProgressAnimator()

    /** 是否按长音强度做缓动。 */
    var sustainAware = false

    /** 上一次锚定的媒体位置；用来识别回退（拖动）。 */
    var lastPosition = Long.MIN_VALUE
        private set

    private var lastDiagLogMs = Long.MIN_VALUE

    private var activeWord: WordRenderModel? = null

    /** 最近一次 [update] 锚定的真实位置，帧推进以它为原点。 */
    private var anchorPosMs = 0L

    /** 自锚定以来帧推进累积的虚拟毫秒。 */
    private var advancePosMs = 0f

    /**
     * 直接跳到精确位置（拖动进度、回退）。
     *
     * @return 高亮宽度是否变了
     */
    fun seek(posMs: Long, model: LyricRenderModel): Boolean {
        val before = animator.currentWidth
        animator.jumpTo(exactTargetWidth(posMs, model))
        activeWord = null
        lastPosition = posMs
        anchorPosMs = posMs
        advancePosMs = 0f
        reconcileLifecycleFlags(model)
        return animator.currentWidth != before
    }

    /**
     * 用播放器给的权威位置推进。
     *
     * 位置比上次还小说明拖回去了，直接按 seek 处理。
     *
     * @return 高亮宽度是否变了
     */
    fun update(posMs: Long, playbackSpeed: Float, model: LyricRenderModel): Boolean {
        if (lastPosition != Long.MIN_VALUE && posMs < lastPosition) {
            return seek(posMs, model)
        }

        anchorPosMs = posMs
        advancePosMs = 0f
        val before = animator.currentWidth

        val word = model.navigator.first(posMs)
        logThrottled(posMs, word, model)

        val target = targetWidth(posMs, model, word)
        driveTarget(posMs, word, model, target)

        activeWord = word
        lastPosition = posMs
        reconcileLifecycleFlags(model)
        return animator.currentWidth != before
    }

    /**
     * 帧推进：虚拟时间往前走，顺便检查是否该切到下一个词。
     *
     * @param deltaNanos 距上一帧的纳秒数
     * @return 是否还需要继续下一帧
     */
    fun step(deltaNanos: Long, playbackSpeed: Float, model: LyricRenderModel): Boolean {
        val speed = if (playbackSpeed.isFinite() && playbackSpeed > 0f) playbackSpeed else 1f
        advancePosMs += deltaNanos.toFloat() / NANOS_PER_MILLI * speed
        val virtualPos = anchorPosMs + advancePosMs.toLong()

        val before = animator.currentWidth
        val word = model.navigator.first(virtualPos)
        val target = targetWidth(virtualPos, model, word)
        driveTarget(virtualPos, word, model, target)

        activeWord = word
        val animating = animator.step(deltaNanos)
        return animating || animator.currentWidth != before
    }

    /** 冻结在当前宽度，不再推进。 */
    fun freeze() {
        animator.stopAtCurrent()
        activeWord = null
    }

    /** 彻底回到初始态。 */
    fun reset() {
        animator.reset()
        activeWord = null
        lastPosition = Long.MIN_VALUE
        anchorPosMs = 0L
        advancePosMs = 0f
    }

    /** 从另一个控制器同步状态，用于切换样式/重建视图时接着画。 */
    fun syncFrom(other: WordProgressController) {
        animator.syncFrom(other.animator)
        sustainAware = other.sustainAware
        lastPosition = other.lastPosition
        activeWord = null
        anchorPosMs = other.anchorPosMs
        advancePosMs = other.advancePosMs
    }

    /**
     * 把目标宽度交给插值器。三条分支的顺序不能动：
     * 1. 行已唱完且目标够整行 → 直接跳满，不走 1ms 补间；
     * 2. 当前有词、且目标变了 → 按词的时长做插值；
     * 3. 其余（词间空隙）→ 什么都不做，保持当前宽度继续往上一词尾推进。
     */
    private fun driveTarget(
        posMs: Long,
        word: WordRenderModel?,
        model: LyricRenderModel,
        target: Float
    ) {
        if (model.end > model.begin && posMs >= model.end && target >= model.width) {
            animator.jumpTo(target)
        } else if (word != null && target != animator.targetWidth) {
            animator.animateTo(
                target,
                durationOf(word),
                if (sustainAware) word.sustainStrength else 0f
            )
        }
    }

    /**
     * 目标宽度。
     *
     * - 词内有有效时长：取词尾位置；末词取整行宽，避免字形溢出到不了满态
     * - 词内无有效时长：退回按行时间线性映射
     * - 已经过行尾：整行宽
     * - 还没到行首：0
     * - 其余（词间空隙）：保持当前宽度
     */
    private fun targetWidth(
        posMs: Long,
        model: LyricRenderModel,
        word: WordRenderModel?
    ): Float = when {
        word != null && (word.end - word.begin) > 0L ->
            if (word.end >= model.end) maxOf(word.endPosition, model.width) else word.endPosition

        word != null -> lineProgressWidth(model, posMs)
        posMs >= model.end && model.end > model.begin -> model.width
        posMs <= model.begin -> 0f
        else -> animator.currentWidth
    }

    /** 按行时间轴线性映射；行时间无效返回 0（整行不亮）。 */
    private fun lineProgressWidth(model: LyricRenderModel, posMs: Long): Float {
        if (model.end <= model.begin) return 0f
        val linear = ((posMs - model.begin).toFloat() /
            (model.end - model.begin).toFloat()).coerceIn(0f, 1f)
        return model.width * linear
    }

    /**
     * 精确宽度：当前词内的线性映射 + 可选缓动。
     * 词时长无效时退回行线性；行也无效则 0。
     */
    private fun exactTargetWidth(posMs: Long, model: LyricRenderModel): Float {
        val word = model.navigator.first(posMs) ?: return when {
            posMs >= model.end && model.end > model.begin -> model.width
            posMs <= model.begin -> 0f
            else -> animator.currentWidth
        }

        val duration = durationOf(word)
        if (duration <= 0L) return lineProgressWidth(model, posMs)

        val linear = ((posMs - word.begin).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val mapped = WordProgressMapper.map(
            linear,
            if (sustainAware) word.sustainStrength else 0f
        )
        return word.startPosition + word.textWidth * mapped
    }

    /** 词的有效时长：优先时间轴，退化到词自带时长。 */
    private fun durationOf(word: WordRenderModel): Long =
        (word.end - word.begin).takeIf { it > 0L } ?: word.duration

    /** 同步「是否开始 / 是否完成」两个状态位。 */
    private fun reconcileLifecycleFlags(model: LyricRenderModel) {
        if (animator.currentWidth < model.width) animator.hasFinished = false
        if (animator.currentWidth <= 0f) animator.hasStarted = false
    }

    /** 诊断日志节流；日志只用于排查，不参与计算。
     *  ★1.4.26：间隔 500ms → 3s。原来每秒 2 条，超级岛播放时 90 秒能刷 200+ 行，
     *  排查时 3 秒一条的粒度完全够用。 */
    private fun logThrottled(posMs: Long, word: WordRenderModel?, model: LyricRenderModel) {
        if (lastDiagLogMs != Long.MIN_VALUE && posMs - lastDiagLogMs < DIAG_INTERVAL_MS) return
        lastDiagLogMs = posMs
        HookLogger.i(
            TAG,
            "pos=$posMs word=${word?.text?.take(12) ?: "null"} " +
                "wBegin=${word?.begin} wEnd=${word?.end} " +
                "modelBegin=${model.begin} modelEnd=${model.end} " +
                "words=${model.words.size} width=${animator.currentWidth}"
        )
    }

    private companion object {
        const val TAG = "WordProgressController"
        const val NANOS_PER_MILLI = 1_000_000f
        const val DIAG_INTERVAL_MS = 3000L
    }
}
