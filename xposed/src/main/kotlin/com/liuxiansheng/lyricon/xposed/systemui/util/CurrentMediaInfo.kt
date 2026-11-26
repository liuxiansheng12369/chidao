/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 *
 * 2026-11-22 单链路重构：本类不再生产歌词。
 * 歌词只有一条链路 —— provider → LyricDataHub → HubLyricRelay → 显示层，
 * 与上游 Lyricon 一致。这里只负责媒体信息（歌名/歌手/专辑/时长/位置/播放态）
 * 和进度时钟，任何 Song 都不从这里发出去。
 */

package com.liuxiansheng.lyricon.xposed.systemui.util
import com.liuxiansheng.lyricon.hyper.root.utils.DiagnosticsLog

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 当前播放媒体信息。
 *
 * 职责只有两件：盯住系统里正在播的那个媒体会话，把歌名 / 歌手 / 专辑 / 时长 / 进度
 * 记下来并推给上岛；歌词不归它管。
 *
 * 三条铁律，都是从踩过的坑里来的：
 * 1. **谁在播谁说了算**：暂停的会话不能顶替正在播的那个，否则起名、歌词、听歌记录
 *    会全部张冠李戴。
 * 2. **进度要自己推**：不能只依赖播放器回调，很多播放器（尤其 ColorOS 系）更新
 *    进度时不发通知，不自己按 100ms 外推，歌词会永远停在第一句。
 * 3. **不生产歌词**：歌词由 provider 经中枢下发。这里若再自己造 Song（读 lyricInfo、
 *    推空壳、切歌清屏），就会两边都以为自己是权威、互相覆盖 —— 实测表现为
 *    词刚到手 1.6 秒就被清掉，或固定挂着上一首。
 */
object CurrentMediaInfo {

    private const val TAG = "CurrentMediaInfo"

    /** 进度推送周期。 */
    private const val POSITION_TICK_MS = 100L

    /** 接管会话后先等一下再开始推，避开播放器自己的初始化。 */
    private const val POSITION_START_DELAY_MS = 300L

    /** 重启后重读 metadata 的延迟。 */
    private const val RESTORE_DELAY_MS = 1000L

    private const val EMPTY_TITLE = "未在播放"

    // ——— 对外状态 ———

    /**
     * 打轴器用的实时进度：直接按 playbackState 外推，不看缓存的 position。
     * 用户拖进度条后这里立刻是新位置，不会有 1 秒滞后。
     */
    fun livePosition(): Long {
        val state = currentController?.playbackState ?: return position
        if (isAdvancingState(state.state)) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            return (state.position + (elapsed * state.playbackSpeed).toLong()).coerceAtLeast(0L)
        }
        return state.position.coerceAtLeast(0L)
    }

    /** 现在是不是真的在播（暂停/停止都算没在播）。 */
    fun isPlayingNow(): Boolean =
        currentController?.playbackState?.state == PlaybackState.STATE_PLAYING

    /**
     * 取一次真实播放位置（位置, 速度）。没在播或拿不到会话返回 null。
     * 显示层位置停滞时用它把时间轴钉回真实进度。
     */
    fun currentPlaybackSample(): Pair<Long, Float>? {
        val state = currentController?.playbackState ?: return null
        if (!isAdvancingState(state.state)) return null
        return livePosition() to state.playbackSpeed
    }

    /** 拿去控制播放器的句柄。 */
    fun controller(): MediaController? = currentController

    private var appContext: Context? = null

    /**
     * 控制前的兜底：缓存的 controller 为空（刚开机还没收到会话回调、或刚切歌）时，
     * 现场重新捞一次活跃会话，别让"暂停无效"只是因为句柄没拿到。
     */
    fun refreshController(): MediaController? {
        currentController?.let { return it }
        val ctx = appContext ?: return null
        val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = runCatching { manager.getActiveSessions(null) }.getOrNull() ?: return null
        val picked = sessions.firstOrNull { isActiveState(it.playbackState?.state ?: PlaybackState.STATE_NONE) }
            ?: sessions.firstOrNull()
        return picked?.also {
            DiagnosticsLog.d(TAG, "控制前补捞会话: pkg=${it.packageName}")
            currentController = it
        }
    }

    @Volatile
    var title: String = EMPTY_TITLE
        private set

    @Volatile
    var artist: String = ""
        private set

    @Volatile
    var album: String = ""
        private set

    @Volatile
    var duration: Long = 0L
        private set

    @Volatile
    var position: Long = 0L
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var packageName: String = ""
        private set

    // ——— 内部状态 ———

    private val listeners = CopyOnWriteArraySet<OnMediaInfoChangeListener>()

    private var initialized = false

    /** 最后一个处于播放态的包名。 */
    @Volatile
    private var lastPlayingPackage: String = ""

    @Volatile
    private var currentController: MediaController? = null

    /** 已经注册过回调的会话 token，防止重复注册越积越多。 */
    private val registeredTokens = ConcurrentHashMap<android.media.session.MediaSession.Token, Boolean>()

    /** 上一次报给显示层的歌词包名，避免每次 metadata 抖动都重复写。 */
    private var lastLyricPackage: String = ""

    private val handler = Handler(Looper.getMainLooper())

    /** 按墙上时间外推当前位置并推给上岛。 */
    private val positionTicker = object : Runnable {
        override fun run() {
            val state = currentController?.playbackState
            // 只有时钟真的在推进时才外推位置；缓冲/跳转期间冻结，避免歌词位置跑飞。
            if (state != null && isAdvancingState(state.state)) {
                val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                val advanced = state.position + (elapsed * state.playbackSpeed).toLong()
                val current = advanced.coerceAtLeast(0L)
                position = current
                // 位置是独立数据，不属于歌词链路，继续汇入中枢给显示层用。
                io.github.proify.lyricon.xposed.systemui.lyric.LyricDataHub
                    .onPositionChanged(current)
            }
            handler.postDelayed(this, POSITION_TICK_MS)
        }
    }

    // ——— 初始化 ———

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext ?: context
        DiagnosticsLog.d(TAG, "=== CurrentMediaInfo 初始化（只做媒体信息） ===")

        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val activeSessions = manager.getActiveSessions(null) ?: emptyList()
        DiagnosticsLog.d(TAG, "活跃媒体会话数: ${activeSessions.size}")

        manager.addOnActiveSessionsChangedListener({ controllers ->
            DiagnosticsLog.d(TAG, "活跃媒体会话变化: ${controllers?.size ?: 0}")
            controllers?.forEach { registerController(it) }
        }, null)

        activeSessions.forEach { registerController(it) }

        handler.postDelayed({ restoreAfterRestart() }, RESTORE_DELAY_MS)
    }

    /** 进程重启后补一次：很多播放器不会主动重发，得自己去读。 */
    private fun restoreAfterRestart() {
        DiagnosticsLog.d(TAG, "=== 重启后重新读 metadata ===")
        val controller = currentController
        if (controller == null) {
            DiagnosticsLog.d(TAG, "currentController 为 null")
            return
        }

        // 媒体监听不一定触发，这里确保进度推送一定在跑
        handler.removeCallbacks(positionTicker)
        handler.postDelayed(positionTicker, POSITION_START_DELAY_MS)

        val metadata = controller.metadata
        if (metadata == null) {
            DiagnosticsLog.d(TAG, "metadata 为 null")
            return
        }
        handleMetadata(controller, metadata, force = true)
    }

    // ——— 会话注册 ———

    private fun registerController(controller: MediaController) {
        val token = controller.sessionToken
        if (registeredTokens.containsKey(token)) {
            // 同一个会话又回调了一次：只要它处在活动态（含缓冲/跳转）就重新接管
            if (isActiveState(controller.playbackState?.state ?: PlaybackState.STATE_NONE)) takeOver(controller)
            return
        }
        registeredTokens[token] = true
        DiagnosticsLog.d(TAG, "注册 controller: pkg=${controller.packageName}")

        controller.registerCallback(object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (metadata != null) handleMetadata(controller, metadata)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state != null) handlePlaybackState(controller, state)
            }
        }, handler)

        // 会话一建立就把进度推送拉起来，否则歌词会停在第一句
        restartPositionTicker()

        val state = controller.playbackState
        if (state != null && isActiveState(state.state)) {
            takeOver(controller)
        } else if (currentController == null) {
            // 还没有主会话，先用它兜底 —— 暂停态也要能显示歌名
            DiagnosticsLog.d(TAG, "兜底 controller: pkg=${controller.packageName}")
            currentController = controller
            controller.metadata?.let { handleMetadata(controller, it, force = true) }
        }
    }

    private fun restartPositionTicker() {
        handler.removeCallbacks(positionTicker)
        handler.postDelayed(positionTicker, POSITION_START_DELAY_MS)
    }

    /** 把正在播的会话设为主会话，并重读它的 metadata。 */
    private fun takeOver(controller: MediaController) {
        if (currentController !== controller) {
            DiagnosticsLog.d(TAG, "主会话切换 -> pkg=${controller.packageName}")
            currentController = controller
        }
        restartPositionTicker()
        controller.metadata?.let { handleMetadata(controller, it, force = true) }

        val state = controller.playbackState
        if (state != null && state.state == PlaybackState.STATE_PLAYING) {
            position = state.position
            isPlaying = true
            lastPlayingPackage = controller.packageName ?: ""
        }
    }

    // ——— metadata ———

    private fun handleMetadata(
        controller: MediaController,
        metadata: MediaMetadata,
        force: Boolean = false
    ) {
        val playingNow = isActiveState(controller.playbackState?.state ?: PlaybackState.STATE_NONE)
        // 不是主会话又没在播，不许覆盖当前信息
        if (!force && controller !== currentController && !playingNow) return
        if (playingNow && controller !== currentController) currentController = controller

        val newTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val newArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val newAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val newDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 } ?: 0L
        val newPackage = controller.packageName ?: ""

        DiagnosticsLog.d(TAG, "=== onMetadataChanged ===")
        DiagnosticsLog.d(TAG, "title=$newTitle, artist=$newArtist, pkg=$newPackage")

        // 换歌判定只用真信号：歌名变 / 时长跳变 ≥1 秒 / 换播放器。
        val durationJump = newDuration > 0L && duration > 0L &&
                kotlin.math.abs(newDuration - duration) >= 1000L
        val packageChanged = newPackage.isNotBlank() && packageName.isNotBlank() &&
                newPackage != packageName
        val trackChanged = newTitle.isNotBlank() &&
                (newTitle != title || durationJump || packageChanged)

        if (trackChanged || newAlbum != album || newDuration != duration) {
            title = newTitle
            artist = newArtist
            album = newAlbum
            duration = newDuration
            packageName = newPackage
            listeners.forEach { it.onMediaInfoChanged(newTitle, newArtist, newPackage) }
        }

        // 显示层的歌词槽按包名取内容，这条赋值与"是否生产歌词"无关，必须保留。
        // 例外：开启歌词应用过滤时，被过滤的包不写歌词槽 —— 否则视频/工具类的媒体
        // 会顶掉正在放的歌，岛上就冒出"暂无歌词"或别人歌的歌词。
        if (newPackage.isNotBlank() && newPackage != lastLyricPackage) {
            if (com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandLyricAppFilter.isAllowed(newPackage)) {
                lastLyricPackage = newPackage
                com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge.updateLyricPackage(newPackage)
            } else {
                DiagnosticsLog.d(
                    TAG,
                    "应用过滤：$newPackage 不出歌词，歌词槽保持 $lastLyricPackage"
                )
            }
        }
    }

    // ——— 播放状态 ———

    private fun handlePlaybackState(controller: MediaController, state: PlaybackState) {
        val pkg = controller.packageName ?: ""
        val active = isActiveState(state.state)

        // 把「活动」和「时钟推进」拆开：切歌缓冲（BUFFERING / CONNECTING / SKIPPING_TO_*）
        // 时播放器会短暂离开 PLAYING，一律当暂停会把岛清掉、歌词闪一下就没了。
        // 缓冲期仍算「活动」：岛保留、歌词不清、只有位置时钟冻结。
        if (active) {
            if (controller !== currentController) takeOver(controller)
            lastPlayingPackage = pkg
            position = state.position
            if (!isPlaying) {
                DiagnosticsLog.d(TAG, "缓冲/跳转中仍算活动: state=${stateName(state.state)} pkg=$pkg")
                isPlaying = true
                io.github.proify.lyricon.xposed.systemui.lyric.LyricDataHub
                    .onPlaybackStateChanged(true)
                listeners.forEach { it.onPlaybackStateChanged(position, true) }
            }
            return
        }

        // 真正的暂停 / 停止 / 结束：只认主会话或刚刚还在播的那个
        if (controller === currentController || pkg == lastPlayingPackage) {
            position = state.position
            isPlaying = false
            io.github.proify.lyricon.xposed.systemui.lyric.LyricDataHub
                .onPlaybackStateChanged(false)
            listeners.forEach { it.onPlaybackStateChanged(position, false) }
        }
    }

    /** 时钟真正在走的态：只有这些才外推进度。 */
    private fun isAdvancingState(code: Int): Boolean = when (code) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> true
        else -> false
    }

    /** 属于「这一首还在进行中」的态：含缓冲、连接、跳转，用来决定岛和歌词保不保留。 */
    private fun isActiveState(code: Int): Boolean = when (code) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private fun stateName(code: Int): String = when (code) {
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "REWINDING"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
        else -> "UNKNOWN($code)"
    }

    // ——— 监听器 ———

    fun registerListener(listener: OnMediaInfoChangeListener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: OnMediaInfoChangeListener) {
        listeners.remove(listener)
    }

    fun interface OnMediaInfoChangeListener {
        fun onMediaInfoChanged(title: String, artist: String, packageName: String)

        fun onPlaybackStateChanged(position: Long, isPlaying: Boolean) = Unit
    }
}
