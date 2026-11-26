/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

@file:Suppress("unused")

package com.liuxiansheng.lyricon.hyper.lyric.model.extensions

import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.DeepCopyable
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.ILyricTiming
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.Normalize

/** 按 begin 升序排，返回新列表；begin 相同的元素保持原有先后。 */
fun <T : ILyricTiming> List<T>.normalizeSortByTime(): List<T> = sortedBy { it.begin }

/** 对每个元素深拷贝，返回新列表。 */
fun <T : DeepCopyable<T>> List<T>.deepCopy(): List<T> = map { it.deepCopy() }

/** 对每个元素规范化（结果本身已是深拷贝），返回新列表。 */
fun <T : Normalize<T>> List<T>.normalize(): List<T> = map { it.normalize() }
