/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.hyper.root.perf

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量性能监控：hook 热路径耗时 + 掉帧统计。
 *
 * 默认关闭（设置 → 调试 → 性能监控），关闭时 `trace` 走空分支，只剩一次
 * volatile 读，对 SystemUI 没有可感知开销。开启后每 5 秒把快照推给 App，
 * 设置页「性能数据」直接看，不用接电脑抓 logcat。
 *
 * 只统计自己埋了点的函数，不做全量 hook 包装：全量包装会把每次 hook 的
 * 反射参数都复制一遍，反而把性能测脏。
 */
object HookPerfMonitor {

    private const val AUTHORITY = "com.liuxiansheng.chidao.live-state"
    private const val METHOD_PUBLISH_PERF = "publish_perf"
    private const val KEY_ENABLED = "pref_debug_perf_monitor"
    private const val PUSH_INTERVAL_MS = 5_000L
    private const val SLOW_FRAME_MS = 20L
    private const val RECENT_SAMPLES = 64
    private const val MAX_HOOKS = 32

    @PublishedApi
    internal @Volatile
    var enabledFlag: Boolean = false
        private set

    private class Stat {
        var count = 0L
        var totalNs = 0L
        var maxNs = 0L
        private val recent = LongArray(RECENT_SAMPLES)
        private var cursor = 0

        fun add(ns: Long) {
            count++
            totalNs += ns
            if (ns > maxNs) maxNs = ns
            recent[cursor % RECENT_SAMPLES] = ns
            cursor++
        }

        fun avgNs(): Long = if (count == 0L) 0L else totalNs / count

        fun p95Ns(): Long {
            val n = minOf(cursor, RECENT_SAMPLES)
            if (n == 0) return 0L
            val arr = LongArray(n) { recent[it] }
            arr.sort()
            return arr[(n * 95 / 100).coerceAtMost(n - 1)]
        }
    }

    private val hooks = ConcurrentHashMap<String, Stat>()

    @Volatile private var context: Context? = null
    @Volatile private var startedAtMs: Long = 0L
    @Volatile private var frames = 0L
    @Volatile private var slowFrames = 0L
    @Volatile private var slow50 = 0L
    @Volatile private var slow100 = 0L
    @Volatile private var maxGapMs = 0L
    @Volatile private var lastVsyncNs = 0L
    @Volatile private var frameLoopOn = false

    private var choreographer: Choreographer? = null
    private var pushHandler: Handler? = null

    /** SystemUI 启动时挂上来：读开关、准备推送线程。 */
    fun attach(ctx: Context, prefs: SharedPreferences) {
        context = ctx.applicationContext ?: ctx
        setEnabled(prefs.getBoolean(KEY_ENABLED, false))
        val thread = HandlerThread("LyriconPerfPush").also { it.start() }
        pushHandler = Handler(thread.looper)
        if (enabledFlag) startPushLoop()
    }

    /** 设置项变化时调用（App 侧改完 prefs，SystemUI 侧靠监听刷新）。 */
    fun setEnabled(value: Boolean) {
        if (enabledFlag == value) return
        enabledFlag = value
        hooks.clear()
        frames = 0L
        slowFrames = 0L
        slow50 = 0L
        slow100 = 0L
        maxGapMs = 0L
        startedAtMs = if (value) SystemClock.elapsedRealtime() else 0L
        if (value) {
            startFrameLoop()
            startPushLoop()
        } else {
            stopFrameLoop()
            pushHandler?.removeCallbacksAndMessages(null)
        }
    }

    /** 热路径埋点：关闭时只多一次 volatile 读。 */
    inline fun <T> trace(name: String, block: () -> T): T {
        if (!enabledFlag) return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            recordSample(name, System.nanoTime() - t0)
        }
    }

    @PublishedApi
    internal fun recordSample(name: String, ns: Long) {
        if (hooks.size >= MAX_HOOKS && !hooks.containsKey(name)) return
        val stat = hooks.getOrPut(name) { Stat() }
        stat.add(ns)
    }

    // ---- 帧 ----

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!enabledFlag) {
                frameLoopOn = false
                return
            }
            val prev = lastVsyncNs
            lastVsyncNs = frameTimeNanos
            if (prev != 0L) {
                val gapMs = (frameTimeNanos - prev) / 1_000_000L
                frames++
                if (gapMs > maxGapMs) maxGapMs = gapMs
                if (gapMs >= SLOW_FRAME_MS) slowFrames++
                if (gapMs >= 50L) slow50++
                if (gapMs >= 100L) slow100++
            }
            choreographer?.postFrameCallback(this)
        }
    }

    private fun startFrameLoop() {
        if (frameLoopOn) return
        frameLoopOn = true
        lastVsyncNs = 0L
        Handler(Looper.getMainLooper()).post {
            runCatching {
                if (choreographer == null) choreographer = Choreographer.getInstance()
                choreographer?.postFrameCallback(frameCallback)
            }.onFailure { frameLoopOn = false }
        }
    }

    private fun stopFrameLoop() {
        frameLoopOn = false
        runCatching { choreographer?.removeFrameCallback(frameCallback) }
    }

    // ---- 快照与推送 ----

    fun snapshot(): JSONObject = JSONObject().apply {
        put("enabled", enabledFlag)
        put("uptimeMs", if (startedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - startedAtMs)
        put("frames", JSONObject().apply {
            put("total", frames)
            put("slow", slowFrames)
            put("slow50", slow50)
            put("slow100", slow100)
            put("maxGapMs", maxGapMs)
        })
        // SystemUI 进程内存：PSS 是整进程（含 native/图形），Java 堆单独列
        runCatching {
            put("pssKb", android.os.Debug.getPss().toLong())
        }
        val rt = Runtime.getRuntime()
        put("javaUsedKb", (rt.totalMemory() - rt.freeMemory()) / 1024)
        put("javaMaxKb", rt.maxMemory() / 1024)
        val arr = JSONArray()
        hooks.entries.sortedByDescending { it.value.avgNs() }.forEach { (name, stat) ->
            arr.put(JSONObject().apply {
                put("name", name)
                put("count", stat.count)
                put("avgUs", stat.avgNs() / 1_000.0)
                put("p95Us", stat.p95Ns() / 1_000.0)
                put("maxUs", stat.maxNs / 1_000.0)
            })
        }
        put("hooks", arr)
    }

    private val pushTask = object : Runnable {
        override fun run() {
            if (!enabledFlag) return
            val ctx = context
            if (ctx != null) {
                runCatching {
                    val extras = Bundle().apply {
                        putString("json", snapshot().toString())
                        putLong("at", System.currentTimeMillis())
                    }
                    ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), METHOD_PUBLISH_PERF, null, extras)
                }
            }
            pushHandler?.postDelayed(this, PUSH_INTERVAL_MS)
        }
    }

    private fun startPushLoop() {
        pushHandler?.removeCallbacks(pushTask)
        pushHandler?.postDelayed(pushTask, PUSH_INTERVAL_MS)
    }
}
