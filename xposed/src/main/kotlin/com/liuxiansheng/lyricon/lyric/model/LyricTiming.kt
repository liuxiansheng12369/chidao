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

import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.ILyricTiming
import kotlinx.serialization.Serializable

/**
 * 纯时间三元组，只有 开始 / 结束 / 持续 三个字段，不带文本。
 *
 * 时间单位毫秒，合法生命周期是 `begin >= 0` 且 `end > begin`。
 *
 * 与 [LyricLine]、[LyricWord] 不同，**本类不做 duration 兜底**：
 * `duration` 完全按传入值保留，`duration == 0` 也不会自动算成 `end - begin`。
 */
@Serializable
data class LyricTiming(
    override var begin: Long,
    override var end: Long,
    override var duration: Long
) : ILyricTiming
