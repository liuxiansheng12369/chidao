/*
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 *
 * 老词幕 LyricDataHub 与词岛显示层（RootLyricSink）之间的唯一适配器。
 *
 * Hub 的语言是 io.github.proify.lyricon.lyric.model.Song（协议原生类型），
 * 显示层用的是 com.liuxiansheng.lyricon.hyper.lyric.model.Song。
 * 两套类型只在这一点交汇，靠 JSON 做一次结构对齐，其它任何地方都不做转换。
 *
 * 位置偏移不在这里处理，统一走 LyricDataHub.lyricPosition(position)，
 * 也就是老版 ProviderLyricPolicy 的那套时钟。
 */

package com.liuxiansheng.lyricon.hyper.root.source

import com.liuxiansheng.lyricon.hyper.lyric.model.LyricMediaMetadata
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import com.liuxiansheng.lyricon.hyper.lyric.source.LyricSink
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import io.github.proify.lyricon.lyric.model.Song as EngineSong
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.xposed.systemui.lyric.LyricDataHub
import kotlinx.serialization.json.Json
import android.app.Application

class HubLyricRelay(
    private val sink: LyricSink,
    private val app: Application? = null,
) : ActivePlayerListener {

    private companion object {
        const val TAG = "HubLyricRelay"
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    @Volatile
    private var playerPackageName: String? = null

    @Volatile
    private var providerPackageName: String? = null

    private fun EngineSong.toLocalSong(): Song =
        json.decodeFromString(json.encodeToString(this))

    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        providerPackageName = providerInfo?.providerPackageName
        playerPackageName = providerInfo?.playerPackageName
        LyriconSource.publishLiveProvider(
            providerPackageName = providerInfo?.providerPackageName,
            playerPackageName = providerInfo?.playerPackageName
        )
        HookLogger.i(
            TAG,
            "活跃provider: pkg=${providerInfo?.providerPackageName} player=${providerInfo?.playerPackageName}"
        )
        // provider 断了不代表歌没了：老版同样不在这里清渲染，等新的 song 事件自然替换。
    }

    override fun onSongChanged(song: EngineSong?) {
        val local = song?.toLocalSong()
        HookLogger.i(
            TAG,
            "收到song: name=${song?.name} artist=${song?.artist} lyrics=${song?.lyrics?.size}"
        )
        sink.onSongChanged(local)
        sink.onMetadata(
            local?.let {
                LyricMediaMetadata(
                    sourceId = LyriconSource.SOURCE_ID,
                    packageName = playerPackageName,
                    songId = it.id,
                    title = it.name,
                    artist = it.artist,
                    album = it.album,
                    duration = it.duration.takeIf { value -> value > 0L }
                )
            }
        )
    }

    override fun onReceiveText(text: String?) {
        sink.onPlainText(text)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        sink.onPlaybackStateChanged(isPlaying)
        // 暂停时心跳保活，防止 SystemUI 进程被冻结；恢复播放或开关关闭就停掉。
        // 与歌词数据无关，只是运行期进程保护。
        app?.let { KeepSessionAlive.onPlaybackStateChanged(it, isPlaying) }
    }

    override fun onPositionChanged(position: Long) {
        sink.onPositionChanged(LyricDataHub.lyricPosition(position))
    }

    override fun onSeekTo(position: Long) {
        sink.onPositionChanged(LyricDataHub.lyricPosition(position))
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
}
