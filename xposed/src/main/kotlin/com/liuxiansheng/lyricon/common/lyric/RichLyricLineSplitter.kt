/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.common.lyric

import android.graphics.Paint
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord
import com.liuxiansheng.lyricon.hyper.lyric.model.RichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.IRichLyricLine
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 分离模式下的富歌词行拆分。
 *
 * 一行太长时切成左右两栏。主行、副行、翻译、罗马音各按自己的画笔测宽，
 * 切点取整行宽度的一半——不按容器宽度算：左侧要避让摄像头、天然比右侧窄，
 * 按容器宽切会让左边装不下、分词失衡。
 *
 * 切点是「期望位置」不是硬上限：相邻字词边界允许略微超出，
 * 超宽部分由歌词视图滚动兜住。
 */
object RichLyricLineSplitter {

    /** 拆分结果：左栏行 + 右栏行。 */
    data class SplitLineResult(
        val left: IRichLyricLine,
        val right: IRichLyricLine
    )

    /**
     * 容器宽度策略。
     *
     * 当前实现按总宽度对半切，这个策略不参与切点计算；保留它是为了让调用方
     * 不必改签名。将来要做「按容器宽度切分」时，从这里接回去。
     */
    sealed interface ContainerWidthSpec {
        fun targetSplitWidthPx(totalTextWidthPx: Float): Float

        /** 固定模式：左右两栏各占固定宽度。 */
        data class Fixed(
            val leftWidthPx: Float,
            val rightWidthPx: Float
        ) : ContainerWidthSpec {
            override fun targetSplitWidthPx(totalTextWidthPx: Float): Float {
                val textWidth = totalTextWidthPx.coerceAtLeast(0f)
                if (textWidth <= 0f) return 0f

                val leftWidth = leftWidthPx.coerceAtLeast(0f)
                val totalWidth = (leftWidth + rightWidthPx.coerceAtLeast(0f)).coerceAtLeast(0f)
                if (totalWidth <= 0f) return textWidth / 2f

                return if (textWidth >= totalWidth) {
                    leftWidth
                } else {
                    (textWidth * leftWidth / totalWidth).coerceIn(0f, textWidth)
                }
            }
        }

        /** 动态模式：随歌词宽度从最小伸缩到最大。 */
        data class Dynamic(
            val leftMinWidthPx: Float,
            val leftMaxWidthPx: Float,
            val rightMinWidthPx: Float,
            val rightMaxWidthPx: Float
        ) : ContainerWidthSpec {
            private val minTotalWidthPx: Float
                get() = (leftMinWidthPx.coerceAtLeast(0f) +
                        rightMinWidthPx.coerceAtLeast(0f)).coerceAtLeast(0f)

            private val maxTotalWidthPx: Float
                get() = (leftMaxWidthPx.coerceAtLeast(0f) +
                        rightMaxWidthPx.coerceAtLeast(0f)).coerceAtLeast(minTotalWidthPx)

            override fun targetSplitWidthPx(totalTextWidthPx: Float): Float {
                val textWidth = totalTextWidthPx.coerceAtLeast(0f)
                if (textWidth <= 0f) return 0f

                // 歌词宽度到顶：左栏取满自己的最大宽度，剩下全留右栏。
                if (textWidth >= maxTotalWidthPx) {
                    return leftMaxWidthPx.coerceAtLeast(0f)
                }

                // 还没到最小总宽：按最小宽度比例分摊。
                if (textWidth <= minTotalWidthPx) {
                    if (minTotalWidthPx <= 0f) return textWidth / 2f
                    return (textWidth * leftMinWidthPx.coerceAtLeast(0f) / minTotalWidthPx)
                        .coerceIn(0f, textWidth)
                }

                // 最小与最大之间：按进度线性伸缩左栏。
                val range = (maxTotalWidthPx - minTotalWidthPx).coerceAtLeast(1f)
                val progress = (textWidth - minTotalWidthPx) / range
                return (leftMinWidthPx + (leftMaxWidthPx - leftMinWidthPx) * progress)
                    .coerceIn(0f, textWidth)
            }
        }
    }

    /** 单段文本的切分结果。 */
    private data class TextParts(
        val leftText: String? = null,
        val rightText: String? = null,
        val leftWords: List<LyricWord>? = null,
        val rightWords: List<LyricWord>? = null
    )

    /** 没有逐字轴时，按像素比例剖出来的切分时刻。 */
    private data class TimelineSplit(val atMs: Long, val untilMs: Long)

    /**
     * 拆一行歌词。
     *
     * @param containerWidthSpec 当前不参与切点计算，仅为保持调用方签名不变而保留。
     * @param partitionUntimedTimeline 没有逐字轴的行是否按左右像素宽度比例剖分时间轴。
     *   默认关闭，保持原行为；打开后左右两栏各有一段有效时间线，不会都从行首开始。
     */
    fun split(
        line: IRichLyricLine,
        primaryPaint: Paint,
        secondaryPaint: Paint,
        containerWidthSpec: ContainerWidthSpec,
        partitionUntimedTimeline: Boolean = false
    ): SplitLineResult {
        val text = line.text
            ?: return SplitLineResult(toRichLyricLine(line), RichLyricLine())

        val primary = splitText(text, line.words, primaryPaint)
        val secondary = splitText(line.secondary, line.secondaryWords, secondaryPaint)
        val translation = splitText(line.translation, line.translationWords, secondaryPaint)
        val roma = splitText(line.roma, null, secondaryPaint)

        val untimed = if (partitionUntimedTimeline && line.words.isNullOrEmpty()) {
            measureTimelineSplit(line, primary, primaryPaint)
        } else {
            null
        }

        // 左栏的下界取切点时刻：右半句第一个词的 begin 就是分界。
        // 用整行 end 会让左栏时间轴撑满全行，左栏最后一个词被拉长，
        // 高亮退化成「前半句慢慢走、后面一大段不亮」。
        val boundaryMs = untimed?.atMs ?: primary.rightWords?.firstOrNull()?.begin

        val leftLine = RichLyricLine(
            begin = line.begin,
            end = boundaryMs ?: line.end,
            duration = 0,
            isAlignedRight = false,
            metadata = line.metadata,
            text = primary.leftText.orEmpty(),
            words = primary.leftWords,
            secondary = secondary.leftText,
            secondaryWords = secondary.leftWords,
            translation = translation.leftText,
            translationWords = translation.leftWords,
            roma = roma.leftText
        )

        val rightLine = RichLyricLine(
            begin = untimed?.atMs ?: primary.rightWords?.firstOrNull()?.begin ?: line.begin,
            end = untimed?.untilMs ?: line.end,
            duration = 0,
            isAlignedRight = false,
            metadata = line.metadata,
            text = primary.rightText.orEmpty(),
            words = primary.rightWords,
            secondary = secondary.rightText,
            secondaryWords = secondary.rightWords,
            translation = translation.rightText,
            translationWords = translation.rightWords,
            roma = roma.rightText
        )

        return SplitLineResult(leftLine, rightLine)
    }

    /**
     * 拆一段文本。
     *
     * - 单个字符不拆，整句放左栏，避免拆出两截半字；
     * - 切点按总宽度一半算；
     * - 有逐字轴按 UTF-16 字符边界切，跨界词按时间比例劈开。
     */
    private fun splitText(
        text: String?,
        words: List<LyricWord>?,
        paint: Paint
    ): TextParts {
        if (text.isNullOrEmpty()) return TextParts()

        if (text.length <= 1) {
            return TextParts(leftText = text, leftWords = words)
        }

        val totalWidth = paint.measureText(text)
        val splitIndex = computeSplitIndex(text, paint, totalWidth / 2f)

        if (splitIndex <= 0) return TextParts(rightText = text, rightWords = words)
        if (splitIndex >= text.length) return TextParts(leftText = text, leftWords = words)

        if (words.isNullOrEmpty()) {
            return TextParts(
                leftText = text.substring(0, splitIndex),
                rightText = text.substring(splitIndex)
            )
        }

        val (leftWords, rightWords) = splitWordsAtCharIndex(words, splitIndex)
        return TextParts(
            leftText = leftWords.joinToString("") { it.text.orEmpty() }.takeIf { it.isNotEmpty() },
            rightText = rightWords.joinToString("") { it.text.orEmpty() }.takeIf { it.isNotEmpty() },
            leftWords = leftWords,
            rightWords = rightWords
        )
    }

    private fun toRichLyricLine(line: IRichLyricLine): RichLyricLine =
        line as? RichLyricLine ?: RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            isAlignedRight = line.isAlignedRight,
            metadata = line.metadata,
            text = line.text,
            words = line.words,
            secondary = line.secondary,
            secondaryWords = line.secondaryWords,
            translation = line.translation,
            translationWords = line.translationWords,
            roma = line.roma
        )

    /**
     * 没有逐字轴的行，按左右两段的实际像素宽度比例把整行时长剖成两半。
     *
     * 左段占多宽就分走多长时间，两栏高亮速度才一致。
     * 时长缺失或太短时返回 null，让上层沿用整行时间轴。
     */
    private fun measureTimelineSplit(
        line: IRichLyricLine,
        primary: TextParts,
        paint: Paint
    ): TimelineSplit? {
        val leftText = primary.leftText?.takeIf { it.isNotEmpty() } ?: return null
        val rightText = primary.rightText?.takeIf { it.isNotEmpty() } ?: return null
        if (line.begin < 0L) return null

        val span = (line.end - line.begin).takeIf { it > 0L }
            ?: line.duration.takeIf { it > 0L }
            ?: return null
        if (span < 2L) return null

        val leftWidth = paint.measureText(leftText).coerceAtLeast(0f)
        val rightWidth = paint.measureText(rightText).coerceAtLeast(0f)
        val totalWidth = leftWidth + rightWidth
        if (!totalWidth.isFinite() || totalWidth <= 0f) return null

        val leftSpan = (span.toDouble() * leftWidth / totalWidth)
            .roundToLong()
            .coerceIn(1L, span - 1L)

        return TimelineSplit(
            atMs = line.begin + leftSpan,
            untilMs = line.begin + span
        )
    }

    /**
     * 按 UTF-16 字符边界切分逐字时间轴。
     * 跨边界的词按时间比例劈开，保证左右两栏各自拥有有效的时间线。
     */
    internal fun splitWordsAtCharIndex(
        words: List<LyricWord>,
        charIndex: Int
    ): Pair<List<LyricWord>, List<LyricWord>> {
        val leftWords = mutableListOf<LyricWord>()
        val rightWords = mutableListOf<LyricWord>()
        var cursor = 0

        for (word in words) {
            val wordText = word.text.orEmpty()
            val wordEnd = cursor + wordText.length

            when {
                wordEnd <= charIndex -> leftWords += word
                cursor >= charIndex -> rightWords += word
                else -> {
                    val headLength = charIndex - cursor
                    val tailLength = wordText.length - headLength
                    val span = word.end - word.begin
                    val cutMs = word.begin + (span * headLength) / wordText.length

                    if (headLength > 0) {
                        leftWords += LyricWord(
                            begin = word.begin,
                            end = cutMs,
                            duration = cutMs - word.begin,
                            text = wordText.substring(0, headLength),
                            metadata = word.metadata
                        )
                    }
                    if (tailLength > 0) {
                        rightWords += LyricWord(
                            begin = cutMs,
                            end = word.end,
                            duration = word.end - cutMs,
                            text = wordText.substring(headLength),
                            metadata = word.metadata
                        )
                    }
                }
            }
            cursor = wordEnd
        }
        return leftWords to rightWords
    }

    /**
     * 二分查找最接近目标像素宽度的可见字符边界。
     *
     * 文本宽度随字符增加单调不减，边界数组可以直接二分：
     * 找最后一个宽度不超过目标的边界当下界，紧邻的边界当上界，取更近的那个。
     */
    private fun computeSplitIndex(
        text: String,
        paint: Paint,
        targetWidthPx: Float
    ): Int {
        if (text.isEmpty()) return 0

        val totalWidth = paint.measureText(text)
        if (targetWidthPx <= 0f) return 0
        if (targetWidthPx >= totalWidth) return text.length

        val target = targetWidthPx.coerceIn(0f, totalWidth)
        val boundaries = characterBoundaries(text)

        var low = 0
        var high = boundaries.size - 1
        var lowerEdge = 0
        var lowerWidth = 0f
        var upperEdge = text.length
        var upperWidth = totalWidth

        while (low <= high) {
            val mid = (low + high) ushr 1
            val edge = boundaries[mid]
            if (edge <= 0) {
                low = mid + 1
                continue
            }
            val width = paint.measureText(text, 0, edge)
            if (width <= target) {
                lowerEdge = edge
                lowerWidth = width
                low = mid + 1
            } else {
                upperEdge = edge
                upperWidth = width
                high = mid - 1
            }
        }

        val nearest = if (abs(lowerWidth - target) <= abs(upperWidth - target)) {
            lowerEdge
        } else {
            upperEdge
        }
        return movePastEnglishWord(text, nearest)
    }

    /**
     * 切点落在 ASCII 英文单词中间时挪到该单词末尾。
     * 保证 "i love you" 拆成 "i love" + "you"，而不是从 love 中间切断。
     */
    private fun movePastEnglishWord(
        text: String,
        originalIndex: Int
    ): Int {
        if (originalIndex <= 0 || originalIndex >= text.length) return originalIndex

        val isAsciiAlnum = { c: Char -> c.isLetterOrDigit() && c.code < 128 }
        if (!isAsciiAlnum(text[originalIndex - 1]) || !isAsciiAlnum(text[originalIndex])) {
            return originalIndex
        }

        var forward = originalIndex
        while (forward < text.length && isAsciiAlnum(text[forward])) forward++
        return forward
    }

    /** Android 文本布局基于 UTF-16，拆分不得切断字素簇（emoji / 组合字符）。 */
    private fun characterBoundaries(text: String): List<Int> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val boundaries = ArrayList<Int>(text.length + 1)
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            boundaries.add(boundary)
            boundary = iterator.next()
        }
        return boundaries
    }
}
