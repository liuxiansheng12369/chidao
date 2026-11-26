/*
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 *
 * 本文件只做一件事：把老词幕的 LyricDataHub 挂到词幕协议订阅端上。
 *
 * 老版（lyricon-master）的接法就是 subscriber.subscribeActivePlayer(LyricDataHub)，
 * 数据加工、状态机、纯文本模式、provider 策略全在 Hub 里，源本身不含任何业务逻辑。
 * 这里照此重写：不再有重推广播、空词重试、延迟补偿、跨源判断这些自造补丁，
 * 只保留「创建订阅端 → 延迟注册 → 断连重注册」这一段连接层。
 */

package com.liuxiansheng.lyricon.hyper.root.source

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.liuxiansheng.lyricon.hyper.lyric.source.LyricSink
import com.liuxiansheng.lyricon.hyper.lyric.source.LyricSource
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.xposed.systemui.lyric.LyricDataHub
import io.github.proify.lyricon.xposed.systemui.lyric.LyricStyleBridge
import io.github.proify.lyricon.xposed.systemui.lyric.ProviderLyricsPreferences

class LyriconSource : LyricSource {

    companion object {
        private const val TAG = "LyriconSource"

        const val SOURCE_ID = "lyricon"

        /**
         * 订阅端注册延迟。
         *
         * 对齐老词幕：SystemUI 刚起来时中枢与 provider 都还没就位，
         * 立刻注册会挂在一个空的中枢上，拿不到当前歌快照，之后只能等对方主动推。
         * 等 2 秒让中枢 / provider 就绪后再注册，一挂上就有内容。
         */
        private const val REGISTER_DELAY_MS = 2_000L

        /**
         * 当前生效 provider 的进程内静态快照，供 App 的「歌词源」页自检。
         * 与数据链路无关，只是把运行态写回 live_state 给 App 读。
         */
        @Volatile
        var liveProviderPackageName: String? = null
            private set

        @Volatile
        var livePlayerPackageName: String? = null
            private set

        @Volatile
        var liveProviderUpdatedAt: Long = 0L
            private set

        fun publishLiveProvider(providerPackageName: String?, playerPackageName: String?) {
            liveProviderPackageName = providerPackageName
            livePlayerPackageName = playerPackageName
            liveProviderUpdatedAt = System.currentTimeMillis()
        }
    }

    override val id = SOURCE_ID
    override val displayName = "Lyricon"

    private var app: Application? = null
    private var prefs: SharedPreferences? = null

    @Volatile
    private var subscriber: LyriconSubscriber? = null

    /** 上次尝试重注册的时间，用于给断连自愈限频。 */
    @Volatile
    private var lastRestartMs = 0L

    override fun isAvailable(): Boolean = true

    fun initialize(app: Application, prefs: SharedPreferences?) {
        this.app = app
        this.prefs = prefs
    }

    override fun start(sink: LyricSink) {
        if (subscriber != null) {
            HookLogger.d(TAG, "跳过重复启动: reason=already_running")
            return
        }
        val application = app
        if (application == null) {
            HookLogger.w(TAG, "数据源启动延后: reason=application_unavailable")
            return
        }

        // 偏好桥：老版加工链读的是词岛 prefs，绑定一次即可。
        prefs?.let {
            LyricStyleBridge.bind(it)
            ProviderLyricsPreferences.bind(it)
        }

        // 唯一出口（HubLyricRelay）由 HookEntry 一次性创建并挂在 Hub 上，与源的生命周期无关。
        // 这里只记下 sink 供 stop 时清屏，不再自己造第二个出口。
        sinkHolder = sink

        connect(application)
        HookLogger.i(TAG, "数据源已启动")
    }

    override fun stop() {
        try {
            subscriber?.unsubscribeActivePlayer(LyricDataHub)
            subscriber?.unregister()
            subscriber?.destroy()
        } catch (e: Exception) {
            HookLogger.e(TAG, "清理歌词订阅连接失败", e)
        } finally {
            KeepSessionAlive.stop()
            subscriber = null
            liveProviderPackageName = null
            livePlayerPackageName = null
            liveProviderUpdatedAt = System.currentTimeMillis()
            sinkHolder?.onStop()
            sinkHolder = null
        }
        HookLogger.d(TAG, "数据源已停止")
    }

    /**
     * 断连后重连：只重注册订阅端，**不碰 sink**。
     *
     * 走 `stop()` 会连带 `onStop()` 把歌名、歌词、进度全清掉，重连时画面就空了。
     * 重注册后库会走 onReconnected，数据流自己续上，期间显示端保留原内容。
     */
    fun restart() {
        val sub = subscriber ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartMs < 5_000L) return
        lastRestartMs = now
        runCatching {
            sub.unregister()
            sub.register()
            HookLogger.i(TAG, "订阅端已重新注册（自动重连）")
        }.onFailure { e ->
            HookLogger.e(TAG, "订阅端重新注册失败", e)
        }
    }

    private var sinkHolder: LyricSink? = null

    private fun connect(application: Application) {
        val sub = LyriconFactory.createSubscriber(application)
        subscriber = sub
        sub.addConnectionListener(connectionListener)
        // 老版接法：中枢本身就是活跃播放器监听器，两路都直接灌它。
        sub.subscribeActivePlayer(LyricDataHub)
        // 延迟注册：等中枢与 provider 就位后再挂上去（对齐老词幕的 delay(2000)）。
        Handler(Looper.getMainLooper()).postDelayed({
            if (subscriber === sub) sub.register()
        }, REGISTER_DELAY_MS)
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(subscriber: LyriconSubscriber) {
            HookLogger.i(TAG, "订阅连接已建立")
        }

        override fun onReconnected(subscriber: LyriconSubscriber) {
            HookLogger.i(TAG, "订阅连接已恢复")
        }

        override fun onDisconnected(subscriber: LyriconSubscriber) {
            HookLogger.w(TAG, "订阅连接已断开")
        }

        override fun onConnectTimeout(subscriber: LyriconSubscriber) {
            HookLogger.w(TAG, "订阅连接超时")
        }
    }
}
