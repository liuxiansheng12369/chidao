/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.model

import com.liuxiansheng.lyricon.hyper.lyric.model.extensions.deepCopy
import com.liuxiansheng.lyricon.hyper.lyric.model.extensions.normalize
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.DeepCopyable
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.IRichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.Normalize
import kotlinx.serialization.Serializable

/**
 * 富歌词行：一行歌词的完整形态，也是 [Song.lyrics] 的元素类型。
 *
 * 一行里同时装多份文本，各带自己的逐词时间轴：
 * - 主文本：[text] / [words]
 * - 副行：[secondary] / [secondaryWords] —— 主题句、伴奏提示这类附加字幕
 * - 翻译：[translation] / [translationWords] —— 可以和主文本逐词对齐
 * - 罗马音：[roma] —— 整串，没有逐词轴
 *
 * 时间单位毫秒。初始化时若 `duration == 0` 且 `end > begin`，自动按 `end - begin` 补上。
 *
 * 每组文本和它的 `*Words` 互为冗余：规范化时逐组处理 `*Words` 并回写文本，
 * 哪组 `*Words` 生成不出文本就保留该组原文本。
 */
@Serializable
data class RichLyricLine(
    override var begin: Long = 0,
    override var end: Long = 0,
    override var duration: Long = 0,
    override var isAlignedRight: Boolean = false,
    override var metadata: LyricMetadata? = null,
    override var text: String? = null,
    override var words: List<LyricWord>? = null,
    override var secondary: String? = null,
    override var secondaryWords: List<LyricWord>? = null,
    override var translation: String? = null,
    override var translationWords: List<LyricWord>? = null,
    override var roma: String? = null
) : IRichLyricLine, DeepCopyable<RichLyricLine>, Normalize<RichLyricLine> {

    init {
        if (duration == 0L && end > begin) duration = end - begin
    }

    /**
     * 深拷贝。除基础字段外，`words`、`secondaryWords`、`translationWords`
     * 三个列表都逐元素重新创建。
     */
    override fun deepCopy(): RichLyricLine = copy(
        words = words?.deepCopy(),
        secondaryWords = secondaryWords?.deepCopy(),
        translationWords = translationWords?.deepCopy(),
    )

    /**
     * 规范化后的深拷贝，原对象不受影响。
     *
     * 先深拷贝自己，再逐组规范化三份 `*Words`，并用结果回写对应文本；
     * 某组 `*Words` 为空就去拼不出来，保留该组原文本。
     */
    override fun normalize(): RichLyricLine = deepCopy().apply {
        words = words?.normalize()
        text = words.toText(text)
        secondaryWords = secondaryWords?.normalize()
        secondary = secondaryWords.toText(secondary)
        translationWords = translationWords?.normalize()
        translation = translationWords.toText(translation)
    }

    /** 有词就拼，没词或拼出来是空的就用原文本。 */
    private fun List<LyricWord>?.toText(fallback: String?): String? =
        if (isNullOrEmpty()) fallback else joinToString("") { it.text.orEmpty() }
}
