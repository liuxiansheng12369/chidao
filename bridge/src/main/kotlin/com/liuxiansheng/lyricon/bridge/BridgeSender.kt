/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */


@file:Suppress("unused")

package com.liuxiansheng.lyricon.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.liuxiansheng.lyricon.common.config.ConfigCenter
import com.liuxiansheng.lyricon.common.log.YLog

/**
 * Bridge 发送端（APP 侧使用）
 *
 * 职责：APP 侧配置变更后，发送广播通知 SystemUI 侧更新配置。
 * 低频操作，不做高频推送。
 *
 * 使用方式：
 * ```
 * BridgeSender.initialize(context)
 * // 配置变更后调用
 * BridgeSender.sendConfigChanged()
 * ```
 */
object BridgeSender {
    private const val TAG = "BridgeSender"

    private var context: Context? = null

    fun initialize(ctx: Context) {
        this.context = ctx.applicationContext
        YLog.info(TAG, "initialized")
    }

    /**
     * 发送配置变更广播（携带当前配置 JSON）
     * SystemUI 侧的 BridgeReceiver 接收后调用 ConfigCenter.restoreConfig()
     */
    fun sendConfigChanged() {
        val ctx = context ?: run {
            YLog.warning(TAG, "sendConfigChanged: context is null, call initialize() first")
            return
        }
        val configJson = ConfigCenter.configJson()
        val intent = Intent(BridgeConstants.ACTION_CONFIG_CHANGED).apply {
            // 3.0.611 修复：不能 setPackage(自己包名)——那会把广播锁死在 APP 进程内，
            // SystemUI 侧 BridgeReceiver（RECEIVER_EXPORTED 动态注册）永远收不到，
            // 开关切换只能靠 remote prefs 跨进程监听（App 的 Xposed 连接断就丢）。
            // 去掉 setPackage → 隐式广播 → SystemUI 动态注册接收器可收到。
            putExtra(BridgeConstants.EXTRA_CONFIG_JSON, configJson)
        }
        ctx.sendBroadcast(intent)
        YLog.debug(TAG, "config changed broadcast sent")
    }

    /**
     * 发送配置响应（响应 SystemUI 的配置请求）
     */
    fun sendConfigResponse() {
        val ctx = context ?: return
        val configJson = ConfigCenter.configJson()
        val intent = Intent(BridgeConstants.ACTION_CONFIG_RESPONSE).apply {
            putExtra(BridgeConstants.EXTRA_CONFIG_JSON, configJson)
        }
        ctx.sendBroadcast(intent)
        YLog.debug(TAG, "config response broadcast sent")
    }

    // ===================== 单键变更广播（设置即时生效通道） =====================

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** 待发送的键值（键 → 打好类型的子 Bundle），滑杆高频写入时合并成一条广播 */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Bundle>()

    private val flushTask = Runnable { flushPending() }

    /**
     * 通知 SystemUI 某个 pref 键变了。
     *
     * 为什么需要这条通道：App 写 remote prefs 是跨进程、异步落盘，SystemUI 读到的
     * 可能还是旧值，表现就是"改完设置要重启状态栏，有时还得点两次才生效"。
     * 这里把「键 + 新值」直接用广播递过去，SystemUI 收到先写内存覆盖表，
     * 再触发一次设置刷新，跟 remote 的落盘快慢彻底解耦。
     *
     * 合并策略：150ms 内的多次写入合成一条广播，拖滑杆不会刷屏。
     */
    fun notifyPrefChanged(key: String, value: Any?) {
        if (key.isEmpty()) return
        if (context == null) return
        val packed = packValue(value) ?: return
        pending[key] = packed
        mainHandler.removeCallbacks(flushTask)
        mainHandler.postDelayed(flushTask, 150L)
    }

    /** 立刻把攒着的变更发出去（重启状态栏之前调用，避免丢最后几下改动）。 */
    fun flushPendingNow() {
        mainHandler.removeCallbacks(flushTask)
        flushPending()
    }

    private fun flushPending() {
        val ctx = context ?: return
        if (pending.isEmpty()) return
        val snapshot = HashMap(pending)
        pending.clear()
        val bundle = Bundle()
        snapshot.forEach { (key, value) -> bundle.putBundle(key, value) }
        val intent = Intent(BridgeConstants.ACTION_PREF_CHANGED).apply {
            putExtra(BridgeConstants.EXTRA_PREF_BUNDLE, bundle)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { YLog.warning(TAG, "pref changed broadcast failed: ${it.message}") }
        YLog.debug(TAG, "pref changed broadcast sent, keys=${snapshot.size}")
    }

    /** 把值打成 {t=类型, v=值} 的子 Bundle；不支持的类型返回 null（不广播）。 */
    private fun packValue(value: Any?): Bundle? {
        val b = Bundle()
        when (value) {
            null -> b.putString(TYPE, "clear")
            is Boolean -> { b.putString(TYPE, "boolean"); b.putBoolean(VAL, value) }
            is Int -> { b.putString(TYPE, "int"); b.putInt(VAL, value) }
            is Long -> { b.putString(TYPE, "long"); b.putLong(VAL, value) }
            is Float -> { b.putString(TYPE, "float"); b.putFloat(VAL, value) }
            is String -> { b.putString(TYPE, "string"); b.putString(VAL, value) }
            is Set<*> -> {
                val list = ArrayList<String>(value.size)
                value.forEach { if (it is String) list.add(it) }
                b.putString(TYPE, "string_set")
                b.putStringArrayList(VAL, list)
            }
            else -> return null
        }
        return b
    }

    private const val TYPE = "t"
    private const val VAL = "v"
}
