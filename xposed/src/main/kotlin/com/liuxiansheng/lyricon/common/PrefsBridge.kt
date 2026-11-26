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

import android.content.Context
import android.content.SharedPreferences
import com.liuxiansheng.lyricon.hyper.root.RootApplication

/**
 * 模块侧偏好读写入口。
 *
 * 读走本进程缓存；写除了落盘，还要通知 [RootApplication] 把同一份键值同步给宿主，
 * 否则设置页改了、模块进程还拿着旧值。
 *
 * 未初始化就访问会直接抛错 —— 这类问题只会出现在装配顺序错的时候，
 * 静默返回默认值反而更难查。
 */
object PrefsBridge {

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PreferenceKeys.PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getPrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("PrefsBridge not initialized. Call init() first.")

    // ——— 读 ———

    fun getBoolean(key: String, default: Boolean): Boolean = getPrefs().getBoolean(key, default)

    fun getInt(key: String, default: Int): Int = getPrefs().getInt(key, default)

    fun getString(key: String, default: String? = null): String? =
        getPrefs().getString(key, default)

    fun getLong(key: String, default: Long): Long = getPrefs().getLong(key, default)

    fun getFloat(key: String, default: Float): Float = getPrefs().getFloat(key, default)

    fun getStringSet(key: String, default: Set<String>? = null): Set<String>? =
        getPrefs().getStringSet(key, default)

    // ——— 写 ———

    fun putBoolean(key: String, value: Boolean) {
        openEditor().putBoolean(key, value).apply()
        sync(key, value)
    }

    fun putInt(key: String, value: Int) {
        openEditor().putInt(key, value).apply()
        sync(key, value)
    }

    fun putString(key: String, value: String?) {
        openEditor().putString(key, value).apply()
        sync(key, value)
    }

    fun putLong(key: String, value: Long) {
        openEditor().putLong(key, value).apply()
        sync(key, value)
    }

    fun putFloat(key: String, value: Float) {
        openEditor().putFloat(key, value).apply()
        sync(key, value)
    }

    fun putStringSet(key: String, value: Set<String>?) {
        openEditor().putStringSet(key, value).apply()
        sync(key, value)
    }

    private fun openEditor(): SharedPreferences.Editor = getPrefs().edit()

    /** 把刚写下的值推给宿主，保持两边一致。 */
    private fun sync(key: String, value: Any?) {
        RootApplication.syncPreference(PreferenceKeys.PREF_NAME, key, value)
    }
}
