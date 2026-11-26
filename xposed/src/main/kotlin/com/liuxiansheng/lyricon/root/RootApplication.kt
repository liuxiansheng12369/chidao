/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root

import android.app.Application
import android.content.Context
import com.liuxiansheng.lyricon.hyper.common.PrefsBridge
import com.liuxiansheng.lyricon.hyper.plugin.app.PluginRepository
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class RootApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        PrefsBridge.init(this)
        appContext = this

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                PluginRepository(this@RootApplication).syncAllRemote(service)
            }

            override fun onServiceDied(service: XposedService) {
                xposedService = null
            }
        })
    }

    companion object {
        private const val TAG = "RootApplication"

        @JvmStatic
        var xposedService: XposedService? = null
            private set

        @JvmStatic
        fun syncPreference(group: String, key: String, value: Any?) {
            val remotePrefs = try {
                xposedService?.getRemotePreferences(group)
            } catch (_: Exception) {
                null
            } ?: return

            remotePrefs.edit().apply {
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                }
                apply()
            }
        }

        private var appContext: Context? = null
    }
}
