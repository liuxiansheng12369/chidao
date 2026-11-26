/*
 * 原实现来源：HyperIsland（作者 1812z），MIT License
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.upstream

import android.content.SharedPreferences
import com.liuxiansheng.lyricon.hyper.root.HookEntry

/** 上游外观引擎的配置读取入口（V3 prefs 适配）。 */
internal object UpstreamConfig {
    private val prefs: SharedPreferences?
        get() = HookEntry.instance?.prefs

    fun getString(key: String, def: String = ""): String =
        prefs?.getString(key, def) ?: def

    fun getBoolean(key: String, def: Boolean): Boolean =
        prefs?.getBoolean(key, def) ?: def

    fun getInt(key: String, def: Int): Int =
        prefs?.getInt(key, def) ?: def

    fun contains(key: String): Boolean =
        prefs?.contains(key) ?: false
}