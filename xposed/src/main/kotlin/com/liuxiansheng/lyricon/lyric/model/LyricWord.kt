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

import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.DeepCopyable
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.ILyricWord
import kotlinx.serialization.Serializable

/**
 * 歌词单词，带独立时间轴的最小文本单元。一般是 [LyricLine.words] 里的元素。
 *
 * 时间单位毫秒。初始化时若 `duration == 0` 且 `end > begin`，
 * 自动按 `end - begin` 补上。
 *
 * `text` 允许是空白串 —— 规范化时它被当作词间分隔符保留，不会当成空词丢掉。
 *
 * @property begin 开始时间（毫秒）
 * @property end 结束时间（毫秒）
 * @property duration 持续时长（毫秒）
 * @property text 单词文本
 * @property metadata 单词级元数据
 */
@Serializable
data class LyricWord(
    override var begin: Long = 0,
    override var end: Long = 0,
    override var duration: Long = 0,
    override var text: String? = null,
    override var metadata: LyricMetadata? = null,
) : ILyricWord, DeepCopyable<LyricWord> {

    init {
        if (duration == 0L && end > begin) duration = end - begin
    }

    /**
     * 深拷贝。
     *
     * 本类字段全是值类型，`metadata` 也是只读不变的，所以直接 `copy()` 就是深拷贝，
     * 副本和原对象不共用任何可变引用。
     */
    override fun deepCopy(): LyricWord = copy()
}
