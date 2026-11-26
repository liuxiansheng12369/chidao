/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.common.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.liuxiansheng.lyricon.hyper.common.HyperLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 从系统 MediaSession 读出当前在播的歌名、歌手、专辑和封面。
 *
 * 只读 MediaSession，不碰音乐 App 本身。
 *
 * **封面为什么要单独缓存**：`MediaController.getMetadata()` 取到的快照里
 * `METADATA_KEY_ALBUM_ART` 之类字段是空的，拿不到 Bitmap。必须注册
 * `MediaController.Callback`，等系统推 `onMetadataChanged` 才有完整数据。
 * 所以这里给每个活动会话挂回调，把推送来的 metadata 存进缓存，读的时候先查缓存。
 *
 * **`getActiveSessions` 会换实例**：每次刷新可能返回全新的 `MediaController` 对象，
 * 旧实例的回调缓存对不上新实例的 key。这里按包名兜底 —— 同一个包的任一会话缓存都认。
 */
object MediaMetadataHelper {

    private const val TAG = "LyriconCover"
    private const val MAX_DEBUG_TEXT_LENGTH = 80

    private val sessionLock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 每个包上一次打过的诊断状态，用来避免同一条日志刷屏。 */
    private val debugStates = HashMap<String, String>()

    @Volatile
    private var mediaSessionManager: MediaSessionManager? = null

    @Volatile
    private var activeControllers: List<MediaController> = emptyList()

    /** 回调推送来的完整 metadata（含封面 Bitmap）。 */
    private val metadataCache = ConcurrentHashMap<MediaController, MediaMetadata>()

    /** 已注册回调的控制器，会话消失时要注销，不然回调泄漏。 */
    private val controllerCallbacks = ConcurrentHashMap<MediaController, MediaController.Callback>()

    /**
     * 会话列表变化的外部观察者。
     *
     * 系统只在"有哪些播放器会话"变化时通知本类（起播、停止、切歌、后台重连、重新上报会话）。
     * 本类自己更新 `activeControllers` 就够了，但岛侧还要重解析歌名/歌手/封面/配色，
     * 所以这里把通知转出去一层；没有观察者时行为不变。
     */
    private val sessionObservers = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            activeControllers = controllers.orEmpty()
            syncCallbacks(controllers.orEmpty())
            notifyActiveSessionsChanged()
        }

    /** 登记会话变化观察者。同一个实例重复登记只保留一份。 */
    fun addActiveSessionsObserver(observer: () -> Unit) {
        if (sessionObservers.contains(observer)) return
        sessionObservers.add(observer)
    }

    /** 注销会话变化观察者。 */
    fun removeActiveSessionsObserver(observer: () -> Unit) {
        sessionObservers.remove(observer)
    }

    /** 把"会话变了"转给观察者；统一在主线程上回调。 */
    private fun notifyActiveSessionsChanged() {
        val dispatch: () -> Unit = {
            sessionObservers.forEach { observer ->
                runCatching { observer() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch()
        } else {
            mainHandler.post(dispatch)
        }
    }

    /** 一台播放器拼出来的媒体快照。 */
    data class MediaInfo(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArt: Bitmap? = null,
        val artworkSource: String = "none",
        val duration: Long = -1L,
        val identity: MediaIdentity = MediaIdentity()
    )

    /** 播放进度快照。`fraction` 算不出来时是 -1。 */
    data class PlaybackProgress(
        val position: Long = -1L,
        val duration: Long = -1L,
        val isPlaying: Boolean = false,
        val playbackSpeed: Float = 0f
    ) {
        val fraction: Float
            get() = if (position >= 0L && duration > 0L) {
                (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                -1f
            }
    }

    /**
     * 让回调注册表和当前会话列表对齐。
     *
     * 已经消失的会话：注销回调、丢掉缓存。新出现的会话：挂 `onMetadataChanged`，
     * 注册完系统会立刻推一次完整 metadata，封面因此能拿到。
     */
    private fun syncCallbacks(controllers: List<MediaController>) {
        val alive = controllers.toSet()

        controllerCallbacks.forEach { (controller, callback) ->
            if (controller !in alive) {
                runCatching { controller.unregisterCallback(callback) }
                controllerCallbacks.remove(controller)
                metadataCache.remove(controller)
            }
        }

        controllers.forEach { controller ->
            if (controllerCallbacks.containsKey(controller)) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (metadata != null) metadataCache[controller] = metadata
                }
            }
            runCatching {
                controller.registerCallback(callback, Handler(Looper.getMainLooper()))
            }
            controllerCallbacks[controller] = callback
        }
    }

    /** 读指定包名当前的媒体信息。 */
    fun getMediaInfo(
        context: Context,
        packageName: String,
        logger: HyperLogger? = null,
        preferredSessionToken: MediaSession.Token? = null
    ): MediaInfo {
        if (packageName.isEmpty()) return MediaInfo()

        return try {
            val controller = findController(context, packageName, preferredSessionToken)
            if (controller == null) {
                debug(logger, packageName, "no_controller") {
                    "媒体信息未读取: package=$packageName, reason=no_matching_controller"
                }
                return MediaInfo(identity = MediaIdentity(packageName = packageName))
            }

            val metadata = resolveMetadata(controller)
            if (metadata == null) {
                debug(logger, packageName, "no_metadata") {
                    "媒体信息未读取: package=$packageName, reason=controller_metadata_null"
                }
                return MediaInfo(
                    identity = MediaIdentity(
                        packageName = packageName,
                        sessionToken = controller.sessionToken
                    )
                )
            }

            val mediaInfo = metadata.toMediaInfo().copy(
                identity = MediaIdentity(
                    packageName = packageName,
                    sessionToken = controller.sessionToken,
                    mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                )
            )
            debug(
                logger,
                packageName,
                "ok|${mediaInfo.title.hashCode()}|${mediaInfo.artist.hashCode()}|" +
                        "${mediaInfo.albumArt?.width}x${mediaInfo.albumArt?.height}|${mediaInfo.artworkSource}"
            ) {
                "媒体信息已读取: package=$packageName, title=\"${debugText(mediaInfo.title)}\", " +
                        "artist=\"${debugText(mediaInfo.artist)}\", album=\"${debugText(mediaInfo.album)}\", " +
                        "albumArt=${mediaInfo.albumArt != null}, " +
                        "artworkSource=${mediaInfo.artworkSource}, " +
                        "bitmap=${mediaInfo.albumArt?.width}x${mediaInfo.albumArt?.height}"
            }
            mediaInfo
        } catch (e: Exception) {
            logger?.e("MediaMetadataHelper", "获取媒体信息失败 ($packageName)", e)
            MediaInfo(identity = MediaIdentity(packageName = packageName))
        }
    }

    /**
     * 取某个控制器可用的 metadata。
     *
     * 优先级：该控制器自己的回调缓存 → **同包名任一会话**的回调缓存 → 控制器自带快照。
     * 第二步是给 `getActiveSessions` 换实例兜底：新实例还没收到推送时，
     * 同包旧实例缓存里的封面照样能用。
     */
    private fun resolveMetadata(controller: MediaController): MediaMetadata? {
        metadataCache[controller]?.let { return it }
        metadataCache.entries
            .firstOrNull { it.key.packageName == controller.packageName }
            ?.let { return it.value }
        return controller.metadata
    }

    /** 指定包名当前播放位置（毫秒）。没在播或没控制器返回 -1。 */
    fun getPlaybackPosition(context: Context, packageName: String): Long {
        if (packageName.isEmpty()) return -1
        return try {
            estimatePlaybackPosition(findController(context, packageName)?.playbackState)
        } catch (_: Exception) {
            -1
        }
    }

    /** 指定包名的播放进度。 */
    fun getPlaybackProgress(context: Context, packageName: String): PlaybackProgress {
        if (packageName.isEmpty()) return PlaybackProgress()
        return try {
            val controller = findController(context, packageName) ?: return PlaybackProgress()
            val state = controller.playbackState
            PlaybackProgress(
                position = estimatePlaybackPosition(state),
                duration = controller.metadata?.extractDuration() ?: -1L,
                isPlaying = state?.state == PlaybackState.STATE_PLAYING,
                playbackSpeed = state?.playbackSpeed ?: 0f
            )
        } catch (_: Exception) {
            PlaybackProgress()
        }
    }

    /** 指定包名是否正在播放。 */
    fun isPackagePlaying(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        return try {
            ensureSessionSnapshot(context)
            activeControllers.any {
                it.packageName == packageName &&
                        it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 把 `PlaybackState` 里的位置推进到"现在"。
     *
     * 系统给的 `position` 只在 `lastPositionUpdateTime` 那一刻成立，播放中要按速度外推。
     */
    fun estimatePlaybackPosition(state: PlaybackState?): Long {
        state ?: return -1L
        val base = state.position
        if (base < 0L) return -1L
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0L) {
            return base
        }
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
            .coerceAtLeast(0L)
        return (base + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0L)
    }

    /** 先查现有快照；查不到就强刷一次再查。 */
    private fun findController(
        context: Context,
        packageName: String,
        preferredSessionToken: MediaSession.Token? = null
    ): MediaController? {
        ensureSessionSnapshot(context)
        pick(activeControllers, packageName, preferredSessionToken)?.let { return it }
        refreshSessionSnapshot()
        return pick(activeControllers, packageName, preferredSessionToken)
    }

    /**
     * 在同包名的控制器里挑一个。
     *
     * 给了 token 就只认 token 精确相等的。没给 token 时：先挑正在播的，
     * 都不在播就挑 `lastPositionUpdateTime` 最新的（说明它最近还活着）。
     */
    private fun pick(
        controllers: List<MediaController>,
        packageName: String,
        preferredSessionToken: MediaSession.Token? = null
    ): MediaController? {
        if (preferredSessionToken != null) {
            return controllers.firstOrNull {
                it.packageName == packageName && it.sessionToken == preferredSessionToken
            }
        }

        var fallback: MediaController? = null
        var fallbackTime = Long.MIN_VALUE
        controllers.forEach { controller ->
            if (controller.packageName != packageName) return@forEach
            val state = controller.playbackState
            if (state?.state == PlaybackState.STATE_PLAYING) return controller
            val updateTime = state?.lastPositionUpdateTime ?: 0L
            if (fallback == null || updateTime > fallbackTime) {
                fallback = controller
                fallbackTime = updateTime
            }
        }
        return fallback
    }

    /**
     * 首次使用时初始化会话快照。
     *
     * 双检锁保证只初始化一次。`addOnActiveSessionsChangedListener` 要在主线程注册，
     * 非主线程调用时挪到主线程，避免 SystemUI 报线程错误。
     */
    private fun ensureSessionSnapshot(context: Context) {
        if (mediaSessionManager != null) return
        synchronized(sessionLock) {
            if (mediaSessionManager != null) return
            val manager =
                context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mediaSessionManager = manager
            activeControllers = runCatching { manager.getActiveSessions(null) }
                .getOrDefault(emptyList())
            syncCallbacks(activeControllers)

            val register: () -> Unit = {
                runCatching {
                    manager.addOnActiveSessionsChangedListener(activeSessionsListener, null)
                }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                register()
            } else {
                mainHandler.post(register)
            }
        }
    }

    /**
     * 重取会话列表并让回调跟着换。
     *
     * 不重挂回调的话，新实例永远拿不到 `onMetadataChanged` 推送，
     * 缓存里只有旧实例的 key，封面取色就会一直是空的。
     */
    private fun refreshSessionSnapshot() {
        val manager = mediaSessionManager ?: return
        activeControllers = runCatching { manager.getActiveSessions(null) }
            .getOrDefault(activeControllers)
        syncCallbacks(activeControllers)
    }

    /**
     * 逐级兜底取封面：`ALBUM_ART` → `ART` → `DISPLAY_ICON`。
     */
    private fun MediaMetadata.extractAlbumArt(): Artwork {
        return try {
            val albumArt = getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (albumArt != null) return Artwork(albumArt, "album_art")
            val art = getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (art != null) return Artwork(art, "art")
            val icon = getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (icon != null) return Artwork(icon, "display_icon")
            Artwork(null, "none")
        } catch (_: Exception) {
            Artwork(null, "error")
        }
    }

    /**
     * metadata → `MediaInfo`。
     *
     * 三个文本字段各有自己的兜底链，最后都退到 `MediaDescription` 的对应字段：
     * - 标题：`TITLE` → `DISPLAY_TITLE` → `description.title`
     * - 歌手：`ARTIST` → `ALBUM_ARTIST` → `AUTHOR` → `DISPLAY_SUBTITLE` → `description.subtitle`
     * - 专辑：`ALBUM` → `DISPLAY_DESCRIPTION` → `description.description`
     */
    private fun MediaMetadata.toMediaInfo(): MediaInfo {
        val description = description
        val artwork = extractAlbumArt()
        return MediaInfo(
            title = getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: description.title?.toString().orEmpty(),
            artist = getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: description.subtitle?.toString().orEmpty(),
            album = getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
                ?: description.description?.toString().orEmpty(),
            albumArt = artwork.bitmap,
            artworkSource = artwork.source,
            duration = extractDuration()
        )
    }

    private data class Artwork(val bitmap: Bitmap?, val source: String)

    /** 时长，非正数一律当未知。 */
    private fun MediaMetadata.extractDuration(): Long = try {
        getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    /**
     * 状态变化才打日志。同一个包同一状态反复读时不重复输出，
     * 换歌时状态串（含 title/artist 的 hashCode）会变，所以照常打。
     */
    private fun debug(
        logger: HyperLogger?,
        packageName: String,
        state: String,
        message: () -> String
    ) {
        if (logger == null) return
        val changed = synchronized(debugStates) {
            if (debugStates[packageName] == state) {
                false
            } else {
                debugStates[packageName] = state
                true
            }
        }
        if (changed) logger.d("MediaMetadataHelper", message())
    }

    /** 日志里的长文本：换行压成空格，超过 80 字截断加省略号，空串显示 `<empty>`。 */
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
}
