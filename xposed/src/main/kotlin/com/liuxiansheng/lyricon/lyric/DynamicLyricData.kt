/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 常见音乐 App 包名 → 显示名。只用于界面显示和日志，不参与任何权限判定。 */
val commonMusicApps = mapOf(
    "com.salt.music" to "Salt Player",
    "com.netease.cloudmusic" to "网易云音乐",
    "com.tencent.qqmusic" to "QQ音乐",
    "cn.kuwo.player" to "酷我音乐",
    "com.kugou.android" to "酷狗音乐",
    "com.apple.android.music" to "Apple Music",
    "com.spotify.music" to "Spotify",
    "cmccwm.mobilemusic" to "咪咕音乐",
    "com.luna.music" to "汽水音乐",
    "com.kugou.android.lite" to "酷狗音乐概念版",
    "com.google.android.apps.youtube.music" to "YouTube Music",
    "cn.wenyu.bodian" to "波点音乐",
    "com.miui.player" to "小米音乐",
    "com.xuncorp.qinalt.music" to "青盐云听"
)

/**
 * 播放位置锚点。
 *
 * 系统只在某一次回调里给过位置，之后要按速度自己往前推 ——
 * 所以必须记住"那一刻的位置"（[position]）和"那一刻的墙上时间"（[timestamp]）。
 */
data class PlaybackAnchor(
    val position: Long = 0L,
    val timestamp: Long = 0L,
    val speed: Float = 1.0f,
    val isPlaying: Boolean = false
)

/**
 * 岛要显示的全部歌曲线程状态。
 *
 * 左栏放"谁在唱"（歌名/歌手/播放器），右栏放歌词；通知栏是另一套标题位。
 */
data class LyricState(
    val islandTitleLeft: String = "等待播放...",
    val islandTitleRight: String = "词岛",
    val notificationTitleLeft: String = "",
    val notificationTitleRight: String = "",
    val songLyric: String = "",
    val songInfo: String = "",
    val showIslandLeftAlbum: Boolean = false,
    val duration: Long = 100L,
    val isPlaying: Boolean = false,
    val targetPackageName: String = "",
    val albumColor: Int = Color.BLACK,
    val albumColorEnd: Int = Color.BLACK,
    val albumBitmap: Bitmap? = null,
    val notificationAlbumBitmap: Bitmap? = null,
    val notificationAlbumBitmapCircular: Bitmap? = null,
    val islandLeftIconStyle: Int = 0,

    val isFetchingLyrics: Boolean = false,
    val isLoadingAlbumArt: Boolean = false,
    val playbackAnchor: PlaybackAnchor = PlaybackAnchor()
)

/**
 * 歌词状态的唯一持有者。
 *
 * 所有更新都走 [MutableStateFlow.update]，读的人订阅 [musicState] 即可拿到最新快照；
 * 进度百分比另走 [progressFlow]，因为它是高频信号，不适合塞进状态对象里反复复制。
 */
object DynamicLyricData {

    private val musicStateFlow = MutableStateFlow(LyricState())
    val musicState = musicStateFlow.asStateFlow()

    val currentState: LyricState
        get() = musicStateFlow.value

    private val progressSink = MutableSharedFlow<Float>(extraBufferCapacity = 1)
    val progressFlow: SharedFlow<Float> = progressSink

    /** 推一次进度。缓冲区只有 1，来不及消费就丢掉旧值 —— 进度只要最新那个。 */
    fun emitProgress(progress: Float) {
        progressSink.tryEmit(progress)
    }

    fun updateFetchingLyrics(fetching: Boolean) {
        musicStateFlow.update { it.copy(isFetchingLyrics = fetching) }
    }

    fun updateLoadingAlbumArt(loading: Boolean) {
        musicStateFlow.update { it.copy(isLoadingAlbumArt = loading) }
    }

    /** 重设播放锚点：记下当前位置和当前时刻，供后续外推。 */
    fun updateAnchor(position: Long, isPlaying: Boolean, speed: Float = 1.0f) {
        val anchor = PlaybackAnchor(
            position = position,
            timestamp = SystemClock.elapsedRealtime(),
            speed = speed,
            isPlaying = isPlaying
        )
        musicStateFlow.update { it.copy(playbackAnchor = anchor, isPlaying = isPlaying) }
    }

    /** 左栏标题。空白会替换成一个空格，避免容器塌成 0 宽。 */
    fun updateLeftTitles(islandText: String, notificationText: String = "") {
        musicStateFlow.update {
            it.copy(
                islandTitleLeft = islandText.ifBlank { " " },
                notificationTitleLeft = notificationText
            )
        }
    }

    /**
     * 封面图。
     *
     * 主封面按传入值覆盖（传 `null` 就是清空）；两个通知栏封面传 `null` 表示"这次没给"，
     * 保留旧图，避免某次只拿到主图就把通知栏图冲掉。
     */
    fun updateBitmaps(
        albumBmp: Bitmap?,
        notificationAlbumBmp: Bitmap? = null,
        notificationAlbumBmpCircular: Bitmap? = null
    ) {
        musicStateFlow.update {
            it.copy(
                albumBitmap = albumBmp,
                notificationAlbumBitmap = notificationAlbumBmp ?: it.notificationAlbumBitmap,
                notificationAlbumBitmapCircular =
                    notificationAlbumBmpCircular ?: it.notificationAlbumBitmapCircular
            )
        }
    }

    fun updateIslandLeftIconStyle(style: Int) {
        musicStateFlow.update { it.copy(islandLeftIconStyle = style) }
    }

    fun updateColor(color: Int, colorEnd: Int) {
        musicStateFlow.update { it.copy(albumColor = color, albumColorEnd = colorEnd) }
    }

    /**
     * 右栏（歌词侧）一次性更新。
     *
     * 时长传非正数时保留旧值 —— 有的播放器在切歌瞬间会报 0。
     */
    fun updateRightTitles(
        islandText: String,
        notificationText: String = "",
        newSongLyric: String,
        newSongInfo: String,
        newDuration: Long,
        newIsPlaying: Boolean,
        newPackageName: String,
        newShowIslandLeftAlbum: Boolean = false
    ) {
        musicStateFlow.update { old ->
            old.copy(
                islandTitleRight = islandText,
                notificationTitleRight = notificationText,
                songLyric = newSongLyric,
                songInfo = newSongInfo,
                duration = if (newDuration > 0) newDuration else old.duration,
                isPlaying = newIsPlaying,
                targetPackageName = newPackageName,
                showIslandLeftAlbum = newShowIslandLeftAlbum
            )
        }
    }

    /** 按锚点外推当前位置；暂停时直接返回锚点位置。 */
    fun LyricState.getCurrentPosition(): Long {
        val anchor = playbackAnchor
        if (!anchor.isPlaying) return anchor.position
        val elapsed = SystemClock.elapsedRealtime() - anchor.timestamp
        return anchor.position + (elapsed * anchor.speed).toLong()
    }
}
