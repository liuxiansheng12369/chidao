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
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord

/**
 * 带时间轴和逐词数据的歌词行契约。
 *
 * 在 [ILyricTiming] 之上带渲染方向、行级元数据、整行文本和逐词列表。
 * 一般用于整行按词切分的逐字场景；一行要带多份文本（副行 / 翻译 / 罗马音）
 * 时用 [IRichLyricLine]。时间单位毫秒。
 *
 * `text` 与 `words` 互为冗余：规范化后以 `words` 为准重新生成 `text`，
 * 所以 `words` 非空时渲染优先读 `words`。
 *
 * @property isAlignedRight 是否右对齐渲染
 * @property metadata 行级元数据
 * @property text 整行文本
 * @property words 逐词列表
 */
interface ILyricLine : ILyricTiming {
    var isAlignedRight: Boolean
    var metadata: LyricMetadata?
    var text: String?
    var words: List<LyricWord>?
}
