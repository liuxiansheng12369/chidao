/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.lyric.render.view

import android.util.Log
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.IRichLyricLine

/** 标记「这一行是下一句预览」。 */
internal const val METADATA_NEXT_LINE_PREVIEW = "nextLinePreview"

/**
 * 把歌词模型拆成「主行 + 副行」两份可直接渲染的数据。
 *
 * 主行永远是歌词原文。副行按优先级挑一个内容来源：
 * 下一句预览 > secondary（原文副稿）> 翻译 > 罗马音。
 *
 * 逐字开关（[enableRelativeProgress]）决定主行走哪种时间轴：
 * - 开：有原始逐字就用原始逐字，没有就整行折叠成一个词（整行平滑推进）
 * - 关：一律整行折叠成一个词，唱到哪亮到哪
 *
 * 标题行（歌名占位）是例外，无论开关如何都保留原有拆词。
 */
internal class LyricLineAssembler(
    private var displayTranslation: Boolean = true,
    private var displayRoma: Boolean = true,
    private var enableRelativeProgress: Boolean = false,
    private var enableRelativeHighlight: Boolean = false,
    private var displayLineByLine: Boolean = false,
    private var simulateUntimedWord: Boolean = false
) {

    /** 主行组装结果。`isLineTimeline` 表示这一行走的是整行时间轴而不是逐字。 */
    data class MainResult(
        val line: LyricLine,
        val isScrollOnly: Boolean,
        val sustainAwareProgress: Boolean,
        val isLineTimeline: Boolean,
        val fullHighlight: Boolean = false
    )

    /** 副行组装结果。`alwaysShow` 表示这一行要常驻，不能等唱到一半才冒出来。 */
    data class SecondaryResult(
        val line: LyricLine,
        val alwaysShow: Boolean,
        val isScrollOnly: Boolean,
        val isNextLinePreview: Boolean,
        val sustainAwareProgress: Boolean,
        val isLineTimeline: Boolean
    )

    /** 样式重设时刷新五个开关。 */
    fun updateFlags(
        displayTranslation: Boolean,
        displayRoma: Boolean,
        enableRelativeProgress: Boolean,
        enableRelativeHighlight: Boolean,
        displayLineByLine: Boolean,
        simulateUntimedWord: Boolean = this.simulateUntimedWord
    ) {
        this.displayTranslation = displayTranslation
        this.displayRoma = displayRoma
        this.enableRelativeProgress = enableRelativeProgress
        this.enableRelativeHighlight = enableRelativeHighlight
        this.displayLineByLine = displayLineByLine
        this.simulateUntimedWord = simulateUntimedWord
    }

    // ——— 主行 ———

    fun buildMain(source: IRichLyricLine?): MainResult {
        if (source == null) return MainResult(LyricLine(), isScrollOnly = false,
            sustainAwareProgress = false, isLineTimeline = false)

        val hasOriginalWords = !source.words.isNullOrEmpty()
        // 要不要逐字推进：有原始逐字就看「逐字进度」，没有就看「模拟逐字」。
        // 两个开关都关 → 整行直接全亮，一个字都不推。
        val wantsWordProgress =
            if (hasOriginalWords) enableRelativeProgress else simulateUntimedWord
        // 标题行与空行不参与：它们本来就不是歌词正文。
        val fullHighlight = !wantsWordProgress &&
                !isTitleLine(source) && !source.text.isNullOrBlank()
        val useLineTimeline = !fullHighlight &&
                shouldUseLineTimeline(source, source.words, source.text)

        val words = when {
            fullHighlight -> emptyList()
            useLineTimeline -> emptyList()
            isTitleLine(source) -> source.words ?: emptyList()
            hasOriginalWords && enableRelativeProgress -> source.words
            else -> foldWholeLine(source) ?: emptyList()
        }

        val line = LyricLine(
            begin = source.begin,
            end = source.end,
            duration = source.duration,
            isAlignedRight = source.isAlignedRight,
            metadata = source.metadata,
            text = if (useLineTimeline) textForLine(source.text, source.words) else source.text,
            words = words
        )

        // 折叠整行和逐字都靠逐字推进画出来，所以 main 永远不是纯滚动
        return MainResult(
            line = line,
            isScrollOnly = false,
            sustainAwareProgress = hasOriginalWords && !useLineTimeline && !fullHighlight,
            isLineTimeline = useLineTimeline,
            fullHighlight = fullHighlight
        )
    }

    // ——— 副行 ———

    fun buildSecondary(source: IRichLyricLine?): SecondaryResult {
        if (source == null) return SecondaryResult(
            line = LyricLine(),
            alwaysShow = false,
            isScrollOnly = false,
            isNextLinePreview = false,
            sustainAwareProgress = false,
            isLineTimeline = false
        )

        val preview = isNextLinePreview(source)
        val content = resolveSecondaryContent(source, preview)

        val line = LyricLine().apply {
            begin = source.begin
            end = source.end
            duration = source.duration
            isAlignedRight = source.isAlignedRight
            text = content.text
            words = content.words
            // 只有预览行才把 metadata 带下去，普通副行不带，避免误判预览标记
            metadata = if (preview) source.metadata else null
        }

        val hasContent = line.text?.isNotBlank() == true || !line.words.isNullOrEmpty()
        val isPlain = line.words.isNullOrEmpty()
        // 纯文本立即常驻；带词的副行如果首词几乎和主行同时开始，也立刻常驻，
        // 否则会出现主行都唱了半句副行才冒出来。
        val alwaysShow = hasContent && (
            isPlain || line.words?.firstOrNull()?.let { (it.begin - source.begin) < 500L } == true
            )

        return SecondaryResult(
            line = line,
            alwaysShow = alwaysShow,
            isScrollOnly = content.generated && !enableRelativeHighlight,
            isNextLinePreview = preview,
            sustainAwareProgress = content.hasOriginalWords && !content.lineTimelineGenerated,
            isLineTimeline = content.lineTimelineGenerated
        )
    }

    /**
     * 挑副行内容。顺序固定：预览 > secondary > 翻译 > 罗马音 > 空。
     *
     * 预览行只给文本、不给词 —— 它属于下一句，不能套用当前句的时间轴。
     */
    private fun resolveSecondaryContent(
        source: IRichLyricLine,
        preview: Boolean
    ): SecondaryContent {
        if (preview && hasSecondary(source)) {
            return SecondaryContent(
                text = source.secondary,
                words = emptyList(),
                generated = false,
                hasOriginalWords = false,
                lineTimelineGenerated = false
            )
        }

        if (hasSecondary(source)) {
            return buildWordContent(source, source.secondary, source.secondaryWords)
        }

        if (displayTranslation && hasTranslation(source)) {
            return buildWordContent(source, source.translation, source.translationWords)
        }

        if (displayRoma && !source.roma.isNullOrBlank()) {
            return SecondaryContent(
                text = source.roma,
                words = null,
                generated = true,
                hasOriginalWords = false,
                lineTimelineGenerated = false
            )
        }

        return SecondaryContent(null, null, false, false, false)
    }

    /** secondary 有没有内容：文本或词任一个在就算。 */
    private fun hasSecondary(source: IRichLyricLine): Boolean =
        !source.secondary.isNullOrBlank() || !source.secondaryWords.isNullOrEmpty()

    private fun hasTranslation(source: IRichLyricLine): Boolean =
        !source.translation.isNullOrBlank() || !source.translationWords.isNullOrEmpty()

    /** 带词内容的副行（secondary 和翻译共用）：同样按整行时间轴规则决定要不要展开。 */
    private fun buildWordContent(
        source: IRichLyricLine,
        rawText: String?,
        rawWords: List<LyricWord>?
    ): SecondaryContent {
        val useLineTimeline = shouldUseLineTimeline(source, rawWords, rawText)
        val usableWords = !useLineTimeline && !rawWords.isNullOrEmpty()
        return SecondaryContent(
            text = if (useLineTimeline) textForLine(rawText, rawWords) else rawText,
            words = if (useLineTimeline) emptyList() else rawWords,
            generated = usableWords,
            hasOriginalWords = usableWords,
            lineTimelineGenerated = useLineTimeline
        )
    }

    /**
     * 要不要改走「整行时间轴」（整段一起亮）。
     *
     * 四个条件同时成立才走：逐字开关关闭、不是标题行、行时间有效、内容非空，
     * 并且词或行本身至少有一份逐字数据可用。
     */
    private fun shouldUseLineTimeline(
        source: IRichLyricLine,
        contentWords: List<LyricWord>?,
        contentText: String?
    ): Boolean {
        if (enableRelativeProgress) return false
        if (isTitleLine(source)) return false
        if (source.begin < 0) return false

        val lineDuration = (source.end - source.begin).takeIf { it > 0L } ?: source.duration
        if (lineDuration <= 0L) return false
        if (contentText.isNullOrBlank()) return false
        return !contentWords.isNullOrEmpty() || !source.words.isNullOrEmpty()
    }

    /** 没有逐字数据时，把整行压成一个从行首到行尾的词。 */
    private fun foldWholeLine(source: IRichLyricLine): List<LyricWord>? {
        val text = source.text?.takeIf { it.isNotBlank() } ?: return null
        if (source.begin >= source.end || source.begin < 0) return null
        return listOf(
            LyricWord(
                begin = source.begin,
                end = source.end,
                duration = source.duration,
                text = text
            )
        )
    }

    /** 行文本：优先用原文，没有就用词拼起来。 */
    private fun textForLine(text: String?, words: List<LyricWord>?): String? =
        text?.takeIf { it.isNotBlank() } ?: words?.joinToString("") { it.text.orEmpty() }

    private fun isTitleLine(source: IRichLyricLine): Boolean =
        source.metadata?.getBoolean(METADATA_TITLE_LINE, false) == true

    private fun isNextLinePreview(source: IRichLyricLine): Boolean =
        source.metadata?.getBoolean(METADATA_NEXT_LINE_PREVIEW, false) == true

    /** 一行诊断串，打印各候选来源的前 30 字和两个开关状态。 */
    private fun diagnostics(source: IRichLyricLine): String =
        "sec=${source.secondary?.take(30)} trans=${source.translation?.take(30)} " +
            "roma=${source.roma?.take(30)} displayTrans=$displayTranslation " +
            "displayRoma=$displayRoma"

    /** 副行解析结果。`generated` 表示词是按行时间生成的（不是原标题）。 */
    private data class SecondaryContent(
        val text: String?,
        val words: List<LyricWord>?,
        val generated: Boolean,
        val hasOriginalWords: Boolean,
        val lineTimelineGenerated: Boolean
    )

    private companion object {
        const val LOG_TAG = "LyricTrans"
        const val METADATA_TITLE_LINE = "TitleLine"
        /** 首词与行首的差小于这个值，副行就常驻显示。 */
        const val IMMEDIATE_SHOW_THRESHOLD_MS = 500L
    }
}
