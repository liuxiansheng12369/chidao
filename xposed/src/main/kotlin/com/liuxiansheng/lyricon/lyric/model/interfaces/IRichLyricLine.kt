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

import com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord

/**
 * 富歌词行契约。一行里同时承载多份文本，每份各带自己的逐词时间轴。
 *
 * 在 [ILyricLine] 之上加三组：
 * - **副行**：`secondary` / `secondaryWords` —— 主题句、伴奏提示这类附加字幕
 * - **翻译**：`translation` / `translationWords` —— 可以和主文本逐词对齐
 * - **罗马音**：`roma` —— 只有整串，没有逐词轴
 *
 * 每组文本和它的 `*Words` 互为冗余：规范化后由 `*Words` 重新生成，
 * 生成不出来就保留原文本。
 *
 * @property secondary 副行文本
 * @property secondaryWords 副行逐词
 * @property translation 翻译文本
 * @property translationWords 翻译逐词
 * @property roma 罗马音
 */
interface IRichLyricLine : ILyricLine {
    var secondary: String?
    var secondaryWords: List<LyricWord>?
    var translation: String?
    var translationWords: List<LyricWord>?
    var roma: String?
}
