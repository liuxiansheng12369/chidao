/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.model

import com.liuxiansheng.lyricon.hyper.lyric.model.extensions.deepCopy
import com.liuxiansheng.lyricon.hyper.lyric.model.extensions.normalize
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.DeepCopyable
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.ILyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.Normalize
import kotlinx.serialization.Serializable

/**
 * 歌词行：一行歌词的时间轴 + 渲染属性 + 逐词数据。
 *
 * 时间单位毫秒。初始化时若 `duration == 0` 且 `end > begin`，自动按 `end - begin` 补上。
 *
 * `text` 和 `words` 互为冗余：规范化后以 `words` 拼出 `text`；
 * `words` 为空或拼出来是空串时，保留原来的 `text`。
 *
 * `isAlignedRight` 表示这行渲染时右对齐（副歌、伴唱行常用）。
 *
 * @property begin 开始时间（毫秒）
 * @property end 结束时间（毫秒）
 * @property duration 持续时长（毫秒）
 * @property isAlignedRight 是否右对齐渲染
 * @property metadata 行级元数据
 * @property text 整行文本
 * @property words 逐词列表；非空时优先作为渲染文本来源
 */
@Serializable
data class LyricLine(
    override var begin: Long = 0,
    override var end: Long = 0,
    override var duration: Long = 0,
    override var isAlignedRight: Boolean = false,
    override var metadata: LyricMetadata? = null,
    override var text: String? = null,
    override var words: List<LyricWord>? = null,
) : ILyricLine, DeepCopyable<LyricLine>, Normalize<LyricLine> {

    init {
        if (duration == 0L && end > begin) duration = end - begin
    }

    /**
     * 深拷贝。除基础字段外，`words` 列表也逐元素重新创建，
     * 结果和原对象不共用任何可变引用。
     */
    override fun deepCopy(): LyricLine = copy(
        words = words?.deepCopy()
    )

    /**
     * 规范化后的深拷贝，原对象不受影响。
     *
     * 先深拷贝自己，再把 `words` 逐词规范化（丢坏时间词、修时长、合并碎片、
     * 填空隙），最后用规范化后的 `words` 拼回 `text`。
     */
    override fun normalize(): LyricLine = deepCopy().apply {
        words = words?.normalize()
        text = words
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("") { it.text.orEmpty() }
            ?: text
    }
}
