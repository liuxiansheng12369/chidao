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

/**
 * 规范化契约。
 *
 * 实现类返回一个**规范化后的深拷贝**：通常先 `deepCopy()`，再在副本上就地修正，
 * 原对象和它的嵌套结构都不会被改。
 *
 * 具体规范什么随类型而定 —— 修正时间戳和时长、丢掉非法行和无效词、合并碎片、
 * 填空隙、按词重算文本、按时间排序等。
 *
 * @param T 实现类自身（递归泛型）
 */
interface Normalize<T : Normalize<T>> {
    /** 返回规范化后的深拷贝。 */
    fun normalize(): T
}
