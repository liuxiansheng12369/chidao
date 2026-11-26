/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.model

import android.media.session.MediaSession
import com.liuxiansheng.lyricon.hyper.common.media.MediaIdentity

/**
 * 跟着歌词一起来的媒体信息快照，与具体歌词源无关。
 *
 * 歌词源可能只知道其中一部分，不知道的字段留 null，由 root 侧解析器
 * 从当前 MediaSession 补齐。
 *
 * @property sourceId 来源标识
 * @property packageName 音乐 App 包名
 * @property songId 歌曲标识
 * @property title 歌名
 * @property artist 歌手
 * @property album 专辑
 * @property duration 时长（毫秒）
 * @property sessionToken 源已持有控制器时的精确会话标识
 * @property mediaId 播放器定义的当前媒体项标识
 */
data class LyricMediaMetadata(
    val sourceId: String,
    val packageName: String? = null,
    val songId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val duration: Long? = null,
    val sessionToken: MediaSession.Token? = null,
    val mediaId: String? = null
) {

    /**
     * 归一化源侧文本：多空白压成一个空格、首尾去空、空串归 null。
     * 不做任何播放器特有的解析猜测。时长非正数也归 null。
     */
    fun normalized(): LyricMediaMetadata = copy(
        packageName = packageName.normalizeMediaText(),
        songId = songId.normalizeMediaText(),
        title = title.normalizeMediaText(),
        artist = artist.normalizeMediaText(),
        album = album.normalizeMediaText(),
        duration = duration?.takeIf { it > 0L },
        mediaId = mediaId.normalizeMediaText()
    )

    /** 转成 [MediaIdentity]；包名为空时用 [defaultPackageName] 兜。 */
    fun toIdentity(defaultPackageName: String = packageName.orEmpty()): MediaIdentity =
        MediaIdentity(
            packageName = packageName ?: defaultPackageName,
            sessionToken = sessionToken,
            songId = songId,
            mediaId = mediaId
        ).normalized()
}

private val mediaTextWhitespace = Regex("\\s+")

private fun String?.normalizeMediaText(): String? = this
    ?.replace(mediaTextWhitespace, " ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
