/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.model.interfaces

/**
 * 深拷贝契约。
 *
 * 实现类返回一个与当前对象等价、但**不共用任何可变引用**的副本：嵌套的可变结构
 * （尤其是列表里的元素对象）也要重新创建。这样改副本不会影响原对象，反过来也一样。
 *
 * 这和 data class 自带的 `copy()` 不同 —— 那个是浅拷贝，嵌套引用仍然共用。
 *
 * @param T 实现类自身（递归泛型，保证深拷贝回来还是同一类型）
 */
interface DeepCopyable<T : DeepCopyable<T>> {
    /** 返回当前对象的深拷贝。 */
    fun deepCopy(): T
}
