/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.model.interfaces

import com.liuxiansheng.lyricon.hyper.lyric.model.LyricMetadata

/**
 * 带时间轴的歌词单词契约。
 *
 * 在 [ILyricTiming] 的生命周期之外，再带单词文本和单词级元数据，
 * 用于需要精确到词的时间场景（逐字高亮）。时间单位毫秒。
 *
 * `text` 允许是空白字符串 —— 规范化时会把它当作词间分隔符保留，不能直接丢。
 *
 * @property text 单词文本
 * @property metadata 单词级元数据
 */
interface ILyricWord : ILyricTiming {
    var text: String?
    var metadata: LyricMetadata?
}
