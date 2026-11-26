/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.common

/**
 * 模块统一的日志出口。
 *
 * 分成四个等级：[d] 调试、[i] 信息、[w] 警告、[e] 错误。
 * 具体落到哪里、要不要按开关过滤，由实现方决定；
 * 调用方只管给 tag 和正文，不要自己判断开关。
 */
interface HyperLogger {
    fun d(tag: String, msg: String)

    fun i(tag: String, msg: String)

    fun w(tag: String, msg: String, e: Throwable? = null)

    fun e(tag: String, msg: String, e: Throwable? = null)
}
