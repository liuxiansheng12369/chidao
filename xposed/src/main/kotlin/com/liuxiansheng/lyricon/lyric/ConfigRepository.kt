/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric

import android.content.Context
import androidx.core.content.edit
import com.liuxiansheng.lyricon.hyper.common.PrefsBridge
import com.liuxiansheng.lyricon.hyper.common.ServiceConstants
import com.liuxiansheng.lyricon.hyper.common.UIConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知白名单的单一数据源。
 *
 * 内存里用 [MutableStateFlow] 挂着当前集合，界面直接订阅它就能实时刷新；
 * 落盘走两层：本 App 自己的 `SharedPreferences`，以及 SystemUI 进程要读的
 * [PrefsBridge]。两处必须同时写，否则重启 SystemUI 后白名单会丢。
 *
 * 只在**内容真的变了**的时候才写盘 —— 重复加同一个包不产生磁盘写入。
 */
object ConfigRepository {

    /** 当前白名单，供界面订阅。 */
    private val whitelistFlow = MutableStateFlow<Set<String>>(emptySet())
    val whitelistState = whitelistFlow.asStateFlow()

    /** 首次启动时把已存的白名单读回内存。 */
    fun initWhitelist(context: Context) {
        val saved = prefs(context)
            .getStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, emptySet())
            ?.toSet()
            .orEmpty()
        whitelistFlow.value = saved
    }

    /**
     * 加一个包。
     *
     * @return 真的加进去了返回 `true`；本来就在名单里返回 `false`（不写盘）
     */
    fun addPackageToWhitelist(context: Context, packageName: String): Boolean {
        val current = whitelistFlow.value
        if (packageName in current) return false
        commit(context, current + packageName)
        return true
    }

    /** 移除一个包；不在名单里就什么也不做。 */
    fun removePackageFromWhitelist(context: Context, packageName: String) {
        val current = whitelistFlow.value
        if (packageName !in current) return
        commit(context, current - packageName)
    }

    /** 一次提交：内存 → 本 App 存档 → SystemUI 侧桥接存档。 */
    private fun commit(context: Context, next: Set<String>) {
        whitelistFlow.value = next
        prefs(context).edit {
            putStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, next)
        }
        PrefsBridge.putStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, next)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
}
