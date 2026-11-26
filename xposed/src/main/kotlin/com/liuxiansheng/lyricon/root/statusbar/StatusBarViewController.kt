/*
 * 本文件基于 Lyricon（作者 Proify / Tomakino）移植，原项目：
 * https://github.com/tomakino/lyricon
 * 原版权归原作者所有，原项目以 Apache-2.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */



package com.liuxiansheng.lyricon.hyper.root.statusbar

/**
 * 状态栏视图控制器接口
 * 每个状态栏视图实现这个接口，由 StatusBarViewManager 统一管理生命周期。
 */
interface StatusBarViewController {
    /** 创建时回调 */
    fun onCreate() {}

    /** 销毁时回调 */
    fun onDestroy() {}
}
