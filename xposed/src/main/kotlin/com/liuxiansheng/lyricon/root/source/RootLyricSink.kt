/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.source
import com.liuxiansheng.lyricon.hyper.root.utils.DiagnosticsLog

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.common.media.MediaIdentity
import com.liuxiansheng.lyricon.hyper.common.media.MediaMetadataHelper
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricMediaMetadata
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import com.liuxiansheng.lyricon.hyper.lyric.model.RichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.IRichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.source.LyricSink
import com.liuxiansheng.lyricon.hyper.plugin.api.PluginMediaInfo
import com.liuxiansheng.lyricon.hyper.plugin.api.PluginProcessingContext
import com.liuxiansheng.lyricon.hyper.plugin.api.PluginSong
import com.liuxiansheng.lyricon.hyper.plugin.api.PluginSongField
import com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge
import com.liuxiansheng.lyricon.hyper.root.island.content.IslandSlotContentFacade
import com.liuxiansheng.lyricon.hyper.root.island.effects.color.IslandMusicWaveColorHooker
import com.liuxiansheng.lyricon.hyper.root.island.renderer.IslandRenderer
import com.liuxiansheng.lyricon.hyper.root.media.CurrentMediaInfoResolver
import com.liuxiansheng.lyricon.hyper.root.plugin.PluginProcessingRequestKey
import com.liuxiansheng.lyricon.hyper.root.plugin.PluginProcessingRequestTracker
import com.liuxiansheng.lyricon.hyper.root.plugin.PluginRuntime
import com.liuxiansheng.lyricon.hyper.root.plugin.PluginSongMapper
import com.liuxiansheng.lyricon.hyper.root.utils.CoverColorHelper
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import com.liuxiansheng.lyricon.xposed.systemui.util.CurrentMediaInfo
import io.github.proify.lyricon.xposed.systemui.util.ChineseConverter.toSimplified
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicLong

/**
 * 岛这边的歌词接收端。
 *
 * 三件事：
 * 1. **把事件转成岛的状态**：换歌、换行、位置、播放态，全部落到 `LyriconDataBridge`，
 *    再让渲染器刷；
 * 2. **位置不能等**：provider 推位置的间隔可能很长（有的歌一句唱完才推一次），
 *    岛里的逐字高亮必须一直走，所以本地按 `elapsed × speed` 外推，60ms 一帧；
 *    外推太久就说明数据真断了，改从 MediaSession 读真实位置重新锚定；
 * 3. **断了要能自己接回来**：播放中 5 秒查一次位置有没有推进，停滞超 10 秒先自锚，
 *    锚不回来就重连订阅端；连续 3 次还不行，弹一次"重启音乐 App"的提示。
 */
class RootLyricSink(
    private val renderer: IslandRenderer,
    private val context: Context,
    private val prefs: SharedPreferences? = null,
    private val pluginRuntime: PluginRuntime? = null,
    /** 断连后的重连动作，由 HookEntry 注入。 */
    private val onConnectionLost: (() -> Unit)? = null
) : LyricSink {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private var reconnectCheckStarted = false

    // --- 位置轴 ---
    private var lastPositionDispatchTimeMs = 0L
    private var pendingPosition: PositionSample? = null
    private var positionDispatchScheduled = false

    /** 最近一次收到位置回调的时间（重复值也算），只用于诊断。 */
    private var lastCallbackTimeMs = 0L

    private var positionTickScheduled = false
    private var playbackActive = false

    private var lastReceivedPosition = Long.MIN_VALUE
    private var lastReceivedPositionTimeMs = 0L
    private var lastReceivedPlaybackSpeed = Float.NaN

    private var lastDispatchedPosition = Long.MIN_VALUE
    private var lastDispatchedPlaybackSpeed = Float.NaN
    private var currentPlaybackSpeed = 1f

    // --- 媒体与来源 ---
    private var activeMediaIdentity: MediaIdentity? = null
    private var activeMediaSourceId: String? = null

    /** MediaSession 上报的当前曲名，用来校验歌词源推来的 Song 是不是同一首。 */
    private var lastMediaTitle: String? = null
    private var lastMediaArtist: String? = null
    private var lastMediaDurationMs: Long = 0L

    /** 被标题闸门挡下的歌词，按歌名暂存（最多 4 首），媒体标题切回来时补应用。 */
    private val gatedSongs = LinkedHashMap<String, Song>()

    /** 歌名归一后做键，和 isSameSongName 的口径保持一致。 */
    private fun songNameKey(name: String?): String =
        name?.trim()?.toSimplified().orEmpty()

    private fun rememberGatedSong(song: Song?) {
        val key = songNameKey(song?.name)
        if (key.isEmpty()) return
        gatedSongs[key] = song!!
        while (gatedSongs.size > 4) {
            gatedSongs.remove(gatedSongs.keys.first())
        }
    }

    /**
     * 推来的这首词到底是不是"现在正在播的那首"。
     *
     * 歌名必须对上（字形归一后相同）；名字对上后还要一条硬证据：
     * 歌手一致，或时长差 ≤ 5 秒。两边缺字段时退化为只看歌名，
     * 不因为缺信息就把正常歌词拦掉。
     */
    private fun songMatchesMedia(song: Song?): Boolean {
        val title = lastMediaTitle
        if (title.isNullOrBlank()) return true
        val name = song?.name
        if (name.isNullOrBlank()) return true
        if (songNameKey(name) != songNameKey(title)) return false

        val mArtist = lastMediaArtist?.trim().orEmpty()
        val sArtist = song.artist?.trim().orEmpty()
        val artistSame = mArtist.isNotEmpty() && sArtist.isNotEmpty() &&
            mArtist.toSimplified() == sArtist.toSimplified()

        val mDur = lastMediaDurationMs
        val sDur = song.duration
        val durClose = mDur > 0L && sDur > 0L && abs(mDur - sDur) <= 5_000L

        // 歌手和时长两边都有 → 至少要一条对得上
        if (mArtist.isNotEmpty() && sArtist.isNotEmpty() && mDur > 0L && sDur > 0L) {
            return artistSame || durClose
        }
        if (mArtist.isNotEmpty() && sArtist.isNotEmpty()) return artistSame
        if (mDur > 0L && sDur > 0L) return durClose
        return true
    }

    /** 媒体标题切到某首时，把之前被挡下的那首词补上。 */
    private fun flushGatedSongForMedia() {
        val key = songNameKey(lastMediaTitle)
        if (key.isEmpty()) return
        val pending = gatedSongs[key] ?: return
        gatedSongs.remove(key)
        if (LyriconDataBridge.currentSong?.name == pending.name) return
        DiagnosticsLog.i("V3LyricProbe", "apply gated song: ${pending.name}")
        applySongChange(pending)
    }

    /** 连续停滞重连次数，到阈值提示一次。 */
    private var consecutiveStallCount = 0
    private var stallHintShown = false

    // --- 插件 ---
    @Volatile
    private var latestPluginMediaInfo: PluginMediaInfo? = null
    private var sourcePluginSong: PluginSong? = null

    private var pendingRepeatedSourceSong: Song? = null
    private val pluginRequestTracker = PluginProcessingRequestTracker()
    private var activePluginRequestKey: PluginProcessingRequestKey? = null
    private var pluginStartScheduled = false

    /** 媒体会话列表变化监听：播放器在后台重新发布会话时，重解析歌名/封面并刷新岛。 */
    private val activeSessionsObserver: () -> Unit = { handleActiveSessionsChanged() }
    private var activeSessionsObserverRegistered = false
    private val pluginRequestGeneration = AtomicLong(0L)

    private val artworkColorRefreshRunnable = Runnable { renderer.updateTextColors() }

    private val pluginStartRunnable = Runnable {
        pluginStartScheduled = false
        startPluginProcessing()
    }

    private val positionDispatchRunnable = Runnable {
        positionDispatchScheduled = false
        val latest = pendingPosition ?: return@Runnable
        pendingPosition = null
        dispatchPosition(latest)
    }

    /**
     * 本地时间轴。provider 不推新位置时，用 elapsed × speed 把位置继续往前走，
     * 保证岛里的逐字高亮是连续的，而不是一次跳一段。
     */
    private val positionTickRunnable = object : Runnable {
        override fun run() {
            positionTickScheduled = false
            if (!playbackActive) return

            val base = lastReceivedPosition
            if (base == Long.MIN_VALUE || lastReceivedPositionTimeMs <= 0L) return

            val now = SystemClock.uptimeMillis()
            val elapsed = now - lastReceivedPositionTimeMs
            if (elapsed > EXTRAPOLATE_MAX_MS) {
                // 推了太久没新值：再外推就是在编造进度了。
                // 改向 MediaSession 要一次真实位置，把时间轴重新钉住。
                CurrentMediaInfo.currentPlaybackSample()?.let {
                    onPositionChanged(it.first, it.second)
                }
                schedulePositionTick()
                return
            }

            val speed = currentPlaybackSpeed
            dispatchPosition(
                PositionSample(base + (elapsed * speed).toLong(), speed),
                now
            )
            schedulePositionTick()
        }
    }

    private fun schedulePositionTick() {
        if (!playbackActive || positionTickScheduled) return
        if (lastReceivedPosition == Long.MIN_VALUE) return
        positionTickScheduled = true
        mainHandler.postDelayed(positionTickRunnable, POSITION_TICK_INTERVAL_MS)
    }

    private fun cancelPositionTick() {
        positionTickScheduled = false
        mainHandler.removeCallbacks(positionTickRunnable)
    }

    /**
     * 断连检测。只在播放态检查 —— 暂停、切歌间隙、长句停顿时位置本来就不动，
     * 按那个判断连会误伤。
     */
    private val reconnectCheckRunnable = object : Runnable {
        override fun run() {
            if (!reconnectCheckStarted) return

            if (playbackActive && lastReceivedPositionTimeMs > 0L) {
                val now = SystemClock.uptimeMillis()
                val stallMs = now - lastReceivedPositionTimeMs
                if (stallMs > RECONNECT_STALL_THRESHOLD_MS) {
                    val sample = CurrentMediaInfo.currentPlaybackSample()
                    if (sample != null) {
                        // 能自锚说明只是 provider 推得慢，不用重连
                        HookLogger.i(TAG, "位置值停滞 ${stallMs}ms，已从 MediaSession 重新锚定")
                        resetStallState()
                        onPositionChanged(sample.first, sample.second)
                    } else {
                        HookLogger.w(
                            TAG,
                            "检测到歌词数据停滞（播放中 ${stallMs}ms 无位置推进），触发自动重连"
                        )
                        // 先把基线挪到现在，避免 5 秒后立刻再触发一次
                        lastReceivedPositionTimeMs = now
                        consecutiveStallCount += 1
                        if (consecutiveStallCount >= STALL_HINT_THRESHOLD && !stallHintShown) {
                            stallHintShown = true
                            showStallHint()
                        }
                        runCatching { onConnectionLost?.invoke() }
                            .onFailure { e -> HookLogger.e(TAG, "自动重连动作执行失败", e) }
                    }
                }
            }

            reconnectHandler.postDelayed(this, RECONNECT_CHECK_INTERVAL_MS)
        }
    }

    /** 启动断连检测，创建 sink 后调用一次。 */
    fun startReconnectCheck() {
        if (reconnectCheckStarted) return
        reconnectCheckStarted = true
        reconnectHandler.post(reconnectCheckRunnable)
    }

    /** 停止断连检测，进程清理时调用。 */
    fun stopReconnectCheck() {
        reconnectCheckStarted = false
        reconnectHandler.removeCallbacks(reconnectCheckRunnable)
    }

    private fun resetStallState() {
        consecutiveStallCount = 0
        stallHintShown = false
    }

    /**
     * 重连多次仍拿不到歌词时的提示。
     *
     * 模块只能重连订阅通道；音乐 App 里那个注入点一旦脱钩，必须重启那个 App 才会重新注入。
     */
    private fun showStallHint() {
        HookLogger.w(
            TAG,
            "连续 $consecutiveStallCount 次重连仍无歌词，提示用户重启音乐 App（" +
                    "pkg=${LyriconDataBridge.currentLyricPackageName.orEmpty()}）"
        )
        mainHandler.post {
            runCatching {
                Toast.makeText(
                    context,
                    "词岛：歌词源已断开，岛里的歌词不会更新。请把音乐 App 强制停止后重新打开即可恢复",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { e -> HookLogger.e(TAG, "停滞提示 Toast 失败", e) }
        }
    }

    private companion object {
        const val TAG = "RootLyricSink"

        /** 合并高频位置推送，最短间隔。 */
        const val MIN_POSITION_DISPATCH_INTERVAL_MS = 33L

        const val MIN_VALID_PLAYBACK_SPEED = 0.1f
        const val MAX_VALID_PLAYBACK_SPEED = 4f
        const val SPEED_CHANGE_EPSILON = 0.01f

        /** 推断速度时新旧值的混合比例，避免测出来的速度剧烈抖动。 */
        const val INFERRED_SPEED_BLEND = 0.75f

        const val MAX_DEBUG_TEXT_LENGTH = 80
        const val ARTWORK_COLOR_REFRESH_DELAY_MS = 100L

        const val RECONNECT_CHECK_INTERVAL_MS = 5_000L
        const val RECONNECT_STALL_THRESHOLD_MS = 10_000L
        const val STALL_HINT_THRESHOLD = 3

        const val POSITION_TICK_INTERVAL_MS = 60L

        /** 位置超过这么久没更新就停止外推，改真实锚定。 */
        const val EXTRAPOLATE_MAX_MS = 2_000L
    }

    private data class PositionSample(val position: Long, val playbackSpeed: Float)

    /** 同一首歌的判定：只看歌名（字形归一后再比），别的一概不看。 */
    private fun isSameSongName(a: Song?, b: Song?): Boolean {
        val nameA = a?.name?.trim().orEmpty()
        val nameB = b?.name?.trim().orEmpty()
        if (nameA.isEmpty() || nameB.isEmpty()) return false
        return nameA.toSimplified() == nameB.toSimplified()
    }

    // ===== 综合上游做法：缓存重建 + 恢复重放 =====

    /**
     * 每首歌最近一次「有内容的歌词」，按歌名归一归档，最多 4 首。
     *
     * 思路来自 Lyricon-kifranei 的 `setSong(lastSong)`：歌词槽被清空后，
     * 同一首的词还能重建出来，不必等 provider 重推。
     * 只按歌名取，绝不跨歌使用 —— 不拿别的歌词顶。
     */
    private val songCache = LinkedHashMap<String, Song>()

    /** 每收到一次 Song 事件自增；日志里用它分辨「新到的包」和「补发的包」。 */
    private var songEventSeq = 0L

    private fun rememberFullSong(song: Song?) {
        val key = songNameKey(song?.name)
        if (key.isEmpty() || song?.lyrics.isNullOrEmpty()) return
        synchronized(songCache) {
            songCache[key] = song.deepCopy()
            while (songCache.size > 4) {
                songCache.remove(songCache.keys.first())
            }
        }
    }

    private fun cachedLyricsFor(song: Song?): List<RichLyricLine>? {
        val key = songNameKey(song?.name)
        if (key.isEmpty()) return null
        // 取一份整首深拷贝再摘出歌词：缓存本体保持只读，不会和上屏对象共享行对象。
        return synchronized(songCache) { songCache[key]?.deepCopy()?.lyrics }
    }

    /**
     * 恢复重放：媒体侧回到某首、当前歌词槽却是空的，用同一首的缓存重建上屏。
     *
     * 对应 HyperLyrics-Enhanced 的 `gate_recovered_snapshot_replay`：
     * 被丢弃 / 被清空的那段时间过后必须主动补一次，否则岛会一直空着或停在上一首。
     */
    private fun replayCachedLyricIfBlank(reason: String) {
        val current = LyriconDataBridge.currentSong ?: return
        if (!current.lyrics.isNullOrEmpty()) return
        val lines = cachedLyricsFor(current)?.takeIf { it.isNotEmpty() } ?: return
        DiagnosticsLog.i(
            "V3LyricProbe",
            "replay cache: name=${current.name} lines=${lines.size} reason=$reason"
        )
        LyriconDataBridge.updateSong(
            song = current.copy(lyrics = lines),
            placeholderFormat = placeholderFormat()
        )
        renderer.updateLyricLine()
    }

    override fun onSongChanged(song: Song?) {
        // 按上游 LyricDataHub.onSongChanged 语义：来什么收什么，直接落地。
        // 不做空包拦截、不查缓存重建、不按媒体标题比对 —— 这些是后加的，
        // 每多一层就多一次"歌词被自己清掉/顶替"的机会。
        ensureActiveSessionsObserver()
        songEventSeq += 1
        com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge.providerSongProbe =
            "${song?.name ?: "null"}#lines=${song?.lyrics?.size ?: 0}"
        DiagnosticsLog.i(
            "V3LyricProbe",
            "seq=$songEventSeq song name=${song?.name} lines=${song?.lyrics?.size ?: 0}"
        )
        applySongChange(song)
    }

    /** 真正落地一次 Song 变更（重建时间轴 / 换歌词）。 */
    private fun applySongChange(song: Song?) {
        cancelArtworkColorRefresh()
        cancelPendingPositionDispatch()
        cancelPositionTick()

        // 换歌算新会话：停滞计数和提示标记都重来
        resetStallState()
        lastReceivedPosition = Long.MIN_VALUE
        lastReceivedPositionTimeMs = 0L
        lastReceivedPlaybackSpeed = Float.NaN
        lastDispatchedPosition = Long.MIN_VALUE
        lastDispatchedPlaybackSpeed = Float.NaN
        currentPlaybackSpeed = 1f

        val ownedSong = song?.deepCopy()
        val incomingPluginSong = ownedSong?.let(PluginSongMapper::toPluginSong)

        // 只有真换歌才清歌词槽、重置插件；同一首歌换词就直接更新歌词，不清屏。
        val sameSong = isSameSongName(ownedSong, LyriconDataBridge.currentSong)

        if (!sameSong) {
            pendingRepeatedSourceSong = null
            cancelPendingPluginStart()
            invalidatePluginRequest(reason = if (song == null) "song_cleared" else "song_changed")

            // 切歌时只清岛上的歌词行、宽度收回最小；歌名和音乐信息层留着不动
            renderer.onSongSwitched()

            activeMediaIdentity = null
            activeMediaSourceId = null
            latestPluginMediaInfo = null
        }
        sourcePluginSong = incomingPluginSong

        LyriconDataBridge.updateSong(
            song = ownedSong,
            placeholderFormat = placeholderFormat()
        )

        if (song == null) {
            sourcePluginSong = null
            endColorSession()
        } else if (!sameSong) {
            schedulePluginProcessing()
        }

        // 没歌词的歌也要重画一次：上面 onSongSwitched() 已经把歌词槽清空，
        // 否则岛上只剩封面和波形。没有歌词时不再插「纯音乐，请欣赏」占位行，
        // 歌名由媒体信息层正常显示。
        renderer.updateLyricLine()
        renderer.updateMetadata()
    }

    /**
     * 换歌 / 换词后补一次位置锚定。
     *
     * 上面把位置轴清成了"没有基准"，就是要等 provider 下一次位置回调；
     * 但有的源推完整首歌词之后就不再推位置（或那份推送本身带着 0 行空壳），
     * 于是岛一直空着 —— 现场就是「切了简繁，岛上的歌词没了」。
     * 直接向 MediaSession 要一次真实位置把轴钉住，不等 provider。
     */
    override fun onLyricLine(line: IRichLyricLine) {
        DiagnosticsLog.i("V3LyricProbe", "line text=${line.text}")
        LyriconDataBridge.updateLyricLine(line)
        renderer.updateLyricLine()
    }

    override fun onPlainText(text: String?) {
        DiagnosticsLog.i("V3LyricProbe", "text=$text")
        // 空文本只是"这一拍没有新行"的心跳/清屏信号，不能当歌词清空使用。
        // 交给桥会被切成纯文本模式并清掉当前行，之后整首歌词的时间轴定位全部失效。
        if (text.isNullOrBlank()) return
        LyriconDataBridge.updateLyric(text)
        renderer.updateLyricLine()
    }

    /** 幂等登记会话变化监听。 */
    private fun ensureActiveSessionsObserver() {
        if (activeSessionsObserverRegistered) return
        MediaMetadataHelper.addActiveSessionsObserver(activeSessionsObserver)
        activeSessionsObserverRegistered = true
    }

    /** 注销会话变化监听，sink 停止时调用，避免回调泄漏。 */
    private fun releaseActiveSessionsObserver() {
        if (!activeSessionsObserverRegistered) return
        MediaMetadataHelper.removeActiveSessionsObserver(activeSessionsObserver)
        activeSessionsObserverRegistered = false
    }

    /**
     * 媒体会话列表变化：起播、停止、切歌、后台重连、系统重新上报会话。
     *
     * 只重解析媒体信息层（歌名/歌手/专辑/封面），不动歌词时间轴 —— 歌词由歌词源推送，
     * 这里不越权重建。但会话换了而歌词源还没重新上报时，歌名和封面不能停在上一首。
     */
    private fun handleActiveSessionsChanged() {
        val packageName = LyriconDataBridge.currentLyricPackageName
            ?.takeIf { it.isNotEmpty() }
            ?: return
        if (LyriconDataBridge.currentSong == null) return
        val mediaInfo = CurrentMediaInfoResolver.getMediaInfo(
            context = context,
            packageName = packageName,
            logger = HookLogger,
            sourceMetadata = LyriconDataBridge.currentLyricMediaMetadata
        )
        latestPluginMediaInfo = mediaInfo.toPluginMediaInfo(packageName)
        if (LyriconDataBridge.applyResolvedMediaInfo(mediaInfo)) {
            renderer.updateMetadata()
            renderer.updateTextColors()
        }
    }

    override fun onStop() {
        releaseActiveSessionsObserver()
        cancelArtworkColorRefresh()
        playbackActive = false
        cancelPendingPositionDispatch()
        cancelPositionTick()

        lastReceivedPosition = Long.MIN_VALUE
        lastReceivedPositionTimeMs = 0L
        lastReceivedPlaybackSpeed = Float.NaN
        lastDispatchedPosition = Long.MIN_VALUE
        lastDispatchedPlaybackSpeed = Float.NaN
        currentPlaybackSpeed = 1f
        lastCallbackTimeMs = 0L

        activeMediaIdentity = null
        activeMediaSourceId = null
        latestPluginMediaInfo = null
        sourcePluginSong = null
        pendingRepeatedSourceSong = null

        cancelPendingPluginStart()
        invalidatePluginRequest(reason = "stopped")
        endColorSession()

        renderer.clearAllViews()
        LyriconDataBridge.clearState()
    }

    /**
     * 媒体元数据事件。
     *
     * 这里干的事比其他回调多：要把"歌词源报的"和"MediaSession 里的"合成一份可信快照，
     * 判断媒体项是不是换了，还要决定插件请求要不要取消重发。
     */
    override fun onMetadata(metadata: LyricMediaMetadata?) {
        val normalized = metadata?.normalized()
        normalized?.packageName?.let(LyriconDataBridge::updateLyricPackage)

        // 只有一个包名、其余全空的快照：说明媒体信息还没到，先别覆盖已有状态
        if (normalized?.isPackageOnlySnapshot() == true) {
            HookLogger.dState(
                stateId = "RootLyricSink.metadata",
                tag = TAG,
                state = "pending|${normalized.sourceId}|${normalized.packageName}"
            ) {
                "媒体元数据暂不可用: source=${normalized.sourceId}, " +
                        "package=${normalized.packageName ?: "<empty>"}, reason=fields_missing"
            }
            return
        }

        LyriconDataBridge.updateMediaMetadata(normalized)

        if (normalized == null) {
            latestPluginMediaInfo = null
            activeMediaIdentity = null
            activeMediaSourceId = null
            pendingRepeatedSourceSong = null
            cancelPendingPluginStart()
            invalidatePluginRequest(reason = "metadata_cleared")
            endColorSession()
            renderer.updateMetadata()
            return
        }

        // 插件只能拿到本次歌词源自己给的包名。MediaSession 的包名、bridge 里留着的
        // 上一次歌词包名都不许漏进去 —— 所以下面单独捉一份 sourcePackageName。
        val sourcePackageName = normalized.packageName?.takeIf { it.isNotBlank() }

        val packageName = normalized.packageName
            ?: LyriconDataBridge.currentLyricPackageName
            ?: ""

        val mediaInfo = CurrentMediaInfoResolver.getMediaInfo(
            context = context,
            packageName = packageName,
            logger = HookLogger,
            sourceMetadata = normalized
        )

        val mediaChanged = activeMediaIdentity?.isCompatibleWith(mediaInfo.identity) == false
        val sourceChanged = activeMediaSourceId != null &&
                activeMediaSourceId != normalized.sourceId

        // 之前因为"重复 song 事件"挂起的那首，如果媒体身份或来源变了，就得撤销插件请求，
        // 把这首原始 Song 老老实实提交上去
        val repeatedSourceSong = pendingRepeatedSourceSong
        pendingRepeatedSourceSong = null
        if (repeatedSourceSong != null && (mediaChanged || sourceChanged)) {
            HookLogger.i(
                TAG,
                "plugin_request event=request_cancelled reason=source_media_identity_changed"
            )
            cancelPendingPluginStart()
            invalidatePluginRequest(reason = "source_media_identity_changed")
            activeMediaIdentity = null
            activeMediaSourceId = null
            latestPluginMediaInfo = null
            LyriconDataBridge.updateSong(
                song = repeatedSourceSong,
                placeholderFormat = placeholderFormat()
            )
            renderer.updateLyricLine()
        }

        LyriconDataBridge.applyResolvedMediaInfo(mediaInfo)
        latestPluginMediaInfo = mediaInfo.toPluginMediaInfo(sourcePackageName)

        if (mediaChanged && LyriconDataBridge.currentSong == null) {
            LyriconDataBridge.resetLyricContentForMediaChange()
            renderer.updateLyricLine()
        }

        activeMediaIdentity = mediaInfo.identity
        activeMediaSourceId = normalized.sourceId
        val titleBefore = lastMediaTitle
        lastMediaTitle = mediaInfo.title
        lastMediaArtist = mediaInfo.artist
        lastMediaDurationMs = mediaInfo.duration
        if (titleBefore != lastMediaTitle) {
            DiagnosticsLog.i(
                "V3LyricProbe",
                "media title: $titleBefore -> $lastMediaTitle (artist=$lastMediaArtist dur=$lastMediaDurationMs)"
            )
            // 标题切回来时，把之前被闸门挡下的那首词补上，否则会一直停在前一首
            flushGatedSongForMedia()
            // 恢复重放：换回某首但歌词槽是空的，用同一首的缓存重建
            // （对应 HyperLyrics-Enhanced 的 gate_recovered_snapshot_replay）
            replayCachedLyricIfBlank(reason = "media_title_changed")
        }
        LyriconDataBridge.currentSongName = LyriconDataBridge.currentSong?.name
            ?.takeIf { it.isNotBlank() }
            ?: mediaInfo.title.takeIf { it.isNotBlank() }

        invalidateIfPluginInputChanged()
        schedulePluginProcessing()

        val colorSessionReady = updateColorSession(mediaInfo, reason = "metadata_changed")
        renderer.updateMetadata()
        if (colorSessionReady) scheduleArtworkColorRefresh()
    }

    private fun placeholderFormat(): Int =
        prefs?.getInt(
            RootConstants.KEY_HOOK_PLACEHOLDER_FORMAT,
            RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
        ) ?: RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT

    // ========================= 插件 =========================

    /** 把同步的 onSongChanged → onMetadata 事件链并到主线程一次处理。 */
    private fun schedulePluginProcessing() {
        if (pluginRuntime == null || pluginStartScheduled) return
        pluginStartScheduled = true
        mainHandler.post(pluginStartRunnable)
    }

    /** 为当前 Core 持有的 Song 快照跑一轮插件。 */
    private fun startPluginProcessing() {
        val baseSong = LyriconDataBridge.currentSong ?: return
        val expectedVersion = LyriconDataBridge.versionCounter.get()
        val requestKey = currentPluginRequestKey() ?: return

        if (activePluginRequestKey != null && activePluginRequestKey != requestKey) {
            invalidatePluginRequest(reason = "effective_input_changed")
        }
        if (pluginRequestTracker.isDuplicate(requestKey)) {
            HookLogger.i(
                TAG,
                "plugin_request event=request_deduplicated " +
                        "fingerprint=${requestKey.hashCode()}"
            )
            return
        }

        pluginRequestTracker.markStarted(requestKey)
        activePluginRequestKey = requestKey
        val expectedRequest = pluginRequestGeneration.incrementAndGet()
        val pluginSnapshot = PluginSongMapper.toPluginSong(baseSong.deepCopy())
        val processingMediaInfo = latestPluginMediaInfo

        HookLogger.i(
            TAG,
            "plugin_request event=request_started " +
                    "fingerprint=${requestKey.hashCode()} " +
                    "generation=$expectedRequest"
        )

        pluginRuntime?.processSong(
            song = pluginSnapshot,
            processingContext = PluginProcessingContext(mediaInfo = processingMediaInfo)
        ) { processingResult ->
            mainHandler.post {
                // 结果回来时，一路上任何一环变了这个结果就作废：请求代次、版本号、
                // 甚至 Song 对象本身换了实例，都算过期
                if (pluginRequestGeneration.get() != expectedRequest) {
                    logStalePluginResult(requestKey, "generation_changed")
                    return@post
                }
                if (LyriconDataBridge.versionCounter.get() != expectedVersion ||
                    LyriconDataBridge.currentSong !== baseSong
                ) {
                    logStalePluginResult(requestKey, "song_snapshot_changed")
                    if (activePluginRequestKey == requestKey) activePluginRequestKey = null
                    return@post
                }
                if (processingResult == null) {
                    if (activePluginRequestKey == requestKey) activePluginRequestKey = null
                    HookLogger.i(
                        TAG,
                        "plugin_request event=request_completed status=no_result " +
                                "fingerprint=${requestKey.hashCode()}"
                    )
                    return@post
                }

                val enhancedSong = PluginSongMapper.toInternalSong(
                    base = baseSong,
                    result = processingResult.result
                )
                if (enhancedSong == null) {
                    if (activePluginRequestKey == requestKey) activePluginRequestKey = null
                    HookLogger.i(
                        TAG,
                        "plugin_request event=request_completed status=invalid_result " +
                                "fingerprint=${requestKey.hashCode()}"
                    )
                    return@post
                }

                val applied = LyriconDataBridge.applyPluginEnhancement(
                    enhancedSong = enhancedSong,
                    expectedVersion = expectedVersion,
                    expectedBaseSong = baseSong,
                    changedFields = processingResult.result.changedFields
                )

                if (applied) {
                    val changedFields = processingResult.result.changedFields
                    if (changedFields.any { it in MEDIA_SONG_FIELDS }) renderer.updateMetadata()
                    if (changedFields.any { it in LYRIC_RENDER_FIELDS }) renderer.updateLyricLine()
                    if (activePluginRequestKey == requestKey) activePluginRequestKey = null
                    HookLogger.i(
                        TAG,
                        "plugin_request event=request_completed status=applied " +
                                "fingerprint=${requestKey.hashCode()}"
                    )
                } else {
                    logStalePluginResult(requestKey, "writeback_rejected")
                    if (activePluginRequestKey == requestKey) activePluginRequestKey = null
                }
            }
        }
    }

    private fun currentPluginRequestKey(): PluginProcessingRequestKey? {
        val song = sourcePluginSong
            ?: LyriconDataBridge.currentSong
                ?.deepCopy()
                ?.let(PluginSongMapper::toPluginSong)
            ?: return null

        return PluginProcessingRequestKey(
            sourceSong = song,
            mediaIdentity = activeMediaIdentity?.normalized(),
            mediaInfo = latestPluginMediaInfo,
            processorSetFingerprint = pluginRuntime?.processingSetFingerprint().orEmpty()
        )
    }

    private fun invalidateIfPluginInputChanged() {
        val active = activePluginRequestKey ?: return
        val current = currentPluginRequestKey() ?: return
        if (active != current) invalidatePluginRequest(reason = "effective_input_changed")
    }

    private fun invalidatePluginRequest(reason: String) {
        val active = activePluginRequestKey
        if (active != null) {
            HookLogger.i(
                TAG,
                "plugin_request event=request_cancelled reason=$reason " +
                        "fingerprint=${active.hashCode()}"
            )
        }
        activePluginRequestKey = null
        pluginRequestTracker.reset()
        pluginRequestGeneration.incrementAndGet()
        pluginRuntime?.cancelActiveProcessing()
    }

    private fun cancelPendingPluginStart() {
        mainHandler.removeCallbacks(pluginStartRunnable)
        pluginStartScheduled = false
    }

    private fun logStalePluginResult(
        requestKey: PluginProcessingRequestKey,
        reason: String
    ) {
        HookLogger.i(
            TAG,
            "plugin_request event=stale_result_ignored reason=$reason " +
                    "fingerprint=${requestKey.hashCode()}"
        )
    }

    // ========================= 位置 =========================

    override fun onPlaybackStateChanged(isPlaying: Boolean, playbackSpeed: Float) {
        val wasPlaying = playbackActive
        playbackActive = isPlaying
        explicitPlaybackSpeed(playbackSpeed)?.let { currentPlaybackSpeed = it }

        if (isPlaying) {
            schedulePositionTick()
            // 暂停→恢复：歌词槽若在暂停期间被清空，用同一首的缓存重建
            // （对应 Lyricon-kifranei 的 setSong(lastSong) 恢复路径）
            if (!wasPlaying) {
                replayCachedLyricIfBlank(reason = "resume_play")
            }
        } else {
            cancelPendingPositionDispatch()
            cancelPositionTick()
        }
        renderer.onPlaybackStateChanged(isPlaying)
    }

    /**
     * 位置推送。
     *
     * 分三个层次处理：
     * - 值没变（只是 provider 又推了一次同样的数字）→ 不动外推基准，交给本地 tick 继续走；
     * - 值变了 → 更新基准、驱动歌词行切换，再节流下发给渲染器；
     * - 速度 → 优先用报上来的，报的值不可信时按位置差自己算。
     */
    override fun onPositionChanged(position: Long, playbackSpeed: Float) {
        val now = SystemClock.uptimeMillis()
        val resolvedSpeed = resolvePlaybackSpeed(position, playbackSpeed, now)
        lastCallbackTimeMs = now

        // 位置重新推进就说明源活着，停滞计数和提示标记都清掉
        if (consecutiveStallCount > 0 || stallHintShown) resetStallState()

        val unchanged = position == lastReceivedPosition &&
                abs(resolvedSpeed - lastReceivedPlaybackSpeed) < SPEED_CHANGE_EPSILON
        if (unchanged) {
            schedulePositionTick()
            return
        }

        lastReceivedPosition = position
        lastReceivedPositionTimeMs = now
        lastReceivedPlaybackSpeed = resolvedSpeed

        val lyricChanged = LyriconDataBridge.updatePosition(position)
        if (lyricChanged) renderer.updateLyricLine()

        val sample = PositionSample(position, resolvedSpeed)
        if (playbackActive) {
            dispatchPositionThrottled(sample, now)
        } else {
            dispatchPosition(sample, now)
        }
        schedulePositionTick()
    }

    /** 播放中把位置合并到 33ms 一帧，避免每来一个回调都触发一次重绘。 */
    private fun dispatchPositionThrottled(sample: PositionSample, now: Long) {
        val elapsed = now - lastPositionDispatchTimeMs
        if (elapsed >= MIN_POSITION_DISPATCH_INTERVAL_MS) {
            dispatchPosition(sample, now)
            return
        }

        pendingPosition = sample
        if (positionDispatchScheduled) return

        positionDispatchScheduled = true
        mainHandler.postDelayed(
            positionDispatchRunnable,
            MIN_POSITION_DISPATCH_INTERVAL_MS - elapsed
        )
    }

    private fun dispatchPosition(
        sample: PositionSample,
        now: Long = SystemClock.uptimeMillis()
    ) {
        // 和上次发出去的一样就不用再发
        if (sample.position == lastDispatchedPosition &&
            abs(sample.playbackSpeed - lastDispatchedPlaybackSpeed) < SPEED_CHANGE_EPSILON
        ) {
            return
        }
        lastPositionDispatchTimeMs = now
        lastDispatchedPosition = sample.position
        lastDispatchedPlaybackSpeed = sample.playbackSpeed
        pendingPosition = null
        renderer.updatePosition(sample.position, sample.playbackSpeed)
    }

    private fun cancelPendingPositionDispatch() {
        mainHandler.removeCallbacks(positionDispatchRunnable)
        pendingPosition = null
        positionDispatchScheduled = false
    }

    /**
     * 定播放速度。
     *
     * 报上来的值落在合理区间就直接用；不在区间就按两次位置差推算，
     * 推出来的值和旧值按 0.75 混合，防止单次测量抖动带着整条时间轴跳。
     */
    private fun resolvePlaybackSpeed(position: Long, reportedSpeed: Float, now: Long): Float {
        explicitPlaybackSpeed(reportedSpeed)?.let {
            currentPlaybackSpeed = it
            return it
        }

        if (playbackActive && lastReceivedPosition != Long.MIN_VALUE &&
            lastReceivedPositionTimeMs > 0L && now > lastReceivedPositionTimeMs &&
            position >= lastReceivedPosition
        ) {
            val elapsedMs = now - lastReceivedPositionTimeMs
            val inferred = (position - lastReceivedPosition).toFloat() / elapsedMs.toFloat()
            if (inferred in MIN_VALID_PLAYBACK_SPEED..MAX_VALID_PLAYBACK_SPEED) {
                currentPlaybackSpeed = currentPlaybackSpeed * (1f - INFERRED_SPEED_BLEND) +
                        inferred * INFERRED_SPEED_BLEND
            }
        }
        return currentPlaybackSpeed
    }

    private fun explicitPlaybackSpeed(speed: Float): Float? =
        speed.takeIf { it.isFinite() && it in MIN_VALID_PLAYBACK_SPEED..MAX_VALID_PLAYBACK_SPEED }

    private fun debugText(value: String): String {
        val normalized = value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .trim()
        if (normalized.isEmpty()) return "<empty>"
        return if (normalized.length > MAX_DEBUG_TEXT_LENGTH) {
            normalized.take(MAX_DEBUG_TEXT_LENGTH) + "…"
        } else {
            normalized
        }
    }

    // ========================= 颜色会话 =========================

    /**
     * 同步取色会话。返回 false 表示当前媒体信息不足以开一版配色
     * （比如只有包名没有歌名），这时直接结束旧会话，别拿上一首的颜色顶着。
     *
     * 会话 revision 变了才刷新岛的内容与波纹色 —— revision 没变说明还是同一套色。
     */
    private fun updateColorSession(
        mediaInfo: MediaMetadataHelper.MediaInfo,
        reason: String
    ): Boolean {
        val packageName = mediaInfo.identity.packageName
        val previousRevision = CoverColorHelper.currentSession()?.revision

        val current = CoverColorHelper.activateSession(mediaInfo = mediaInfo)
        if (current == null) {
            endColorSession()
            HookLogger.dState(
                stateId = "RootLyricSink.colorSession.invalid",
                tag = TAG,
                state = "$packageName|${mediaInfo.title}|${mediaInfo.artist}|${mediaInfo.identity}"
            ) {
                "颜色会话未更新: reason=$reason, package=${packageName.ifEmpty { "<empty>" }}, " +
                        "title=\"${debugText(mediaInfo.title)}\", " +
                        "artist=\"${debugText(mediaInfo.artist)}\", identity=${mediaInfo.identity}"
            }
            return false
        }

        HookLogger.dState(
            stateId = "RootLyricSink.colorSession",
            tag = TAG,
            state = "${current.revision}|${current.mediaKey}|${current.title}|${current.artist}"
        ) {
            "颜色会话已同步: reason=$reason, revision=${current.revision}, " +
                    "revisionChanged=${previousRevision != current.revision}, " +
                    "package=${packageName.ifEmpty { "<empty>" }}, " +
                    "title=\"${debugText(mediaInfo.title)}\", " +
                    "artist=\"${debugText(mediaInfo.artist)}\", " +
                    "album=\"${debugText(mediaInfo.album)}\", " +
                    "identity=${mediaInfo.identity}, mediaKeyHash=${current.mediaKey.hashCode()}"
        }

        if (previousRevision != current.revision) {
            IslandSlotContentFacade.invalidate()
            IslandMusicWaveColorHooker.refresh()
            renderer.updateTextColors()
        }
        return true
    }

    private fun endColorSession() {
        cancelArtworkColorRefresh()
        if (CoverColorHelper.endSession()) {
            IslandSlotContentFacade.invalidate()
            IslandMusicWaveColorHooker.refresh()
        }
    }

    /** 封面是异步取的，等它到位再刷文字色。 */
    private fun scheduleArtworkColorRefresh() {
        mainHandler.removeCallbacks(artworkColorRefreshRunnable)
        mainHandler.postDelayed(artworkColorRefreshRunnable, ARTWORK_COLOR_REFRESH_DELAY_MS)
    }

    private fun cancelArtworkColorRefresh() {
        mainHandler.removeCallbacks(artworkColorRefreshRunnable)
    }

    /** 整份快照只有包名，别的字段一个都没有。 */
    private fun LyricMediaMetadata.isPackageOnlySnapshot(): Boolean =
        songId == null &&
                title == null &&
                artist == null &&
                album == null &&
                duration == null &&
                sessionToken == null &&
                mediaId == null

    /** 改了这些字段要刷新音乐信息层。 */
    private val MEDIA_SONG_FIELDS = setOf(
        PluginSongField.ID,
        PluginSongField.NAME,
        PluginSongField.ARTIST,
        PluginSongField.ALBUM,
        PluginSongField.DURATION,
        PluginSongField.METADATA
    )

    /** 改了这些字段要重绘歌词区。 */
    private val LYRIC_RENDER_FIELDS = setOf(
        PluginSongField.NAME,
        PluginSongField.ARTIST,
        PluginSongField.DURATION,
        PluginSongField.METADATA,
        PluginSongField.LYRICS
    )
}

/**
 * 把 Core 解析好的媒体字段转成插件 DTO。
 *
 * `sourcePackageName` 单独传，不从这里面的 `identity` 取 —— 歌词源没报包名时，
 * MediaSession 的包名不能漏进插件。
 */
internal fun MediaMetadataHelper.MediaInfo.toPluginMediaInfo(
    sourcePackageName: String?
): PluginMediaInfo? = PluginMediaInfo(
    title = title.takeIf { it.isNotBlank() },
    artist = artist.takeIf { it.isNotBlank() },
    album = album.takeIf { it.isNotBlank() },
    duration = duration.takeIf { it > 0L },
    sourcePackageName = sourcePackageName?.takeIf { it.isNotBlank() }
).takeIf { info ->
    info.title != null ||
            info.artist != null ||
            info.album != null ||
            info.duration != null ||
            info.sourcePackageName != null
}
