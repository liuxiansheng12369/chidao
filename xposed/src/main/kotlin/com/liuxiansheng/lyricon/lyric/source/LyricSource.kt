/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.source

/**
 * 一个歌词源的能力声明。
 *
 * 源自己决定 `isAvailable()` 怎么算（有的要看权限、有的要看账号、有的看包在不在），
 * [SourceManager] 只负责按这个结果挑源。
 */
interface LyricSource {

    /** 稳定标识，配置里存的就是它，不要随意改。 */
    val id: String

    /** 给用户看的名字。 */
    val displayName: String

    /** 开始推数据。实现里别阻塞太久，[SourceManager] 是同步调的。 */
    fun start(sink: LyricSink)

    /** 停止并释放资源；可以被重复调用。 */
    fun stop()

    /** 此刻能不能用。 */
    fun isAvailable(): Boolean
}
