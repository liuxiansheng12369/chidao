/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.media

import android.content.Context
import com.liuxiansheng.lyricon.hyper.common.HyperLogger
import com.liuxiansheng.lyricon.hyper.common.media.MediaMetadataHelper
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricMediaMetadata
import com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge

/**
 * 把"歌词源给的元数据"和"MediaSession 的元数据"合成一份给岛用的媒体快照。
 *
 * 三条规矩：
 * 1. **逐字段优先用源**，源没给的才回落到 MediaSession；
 * 2. 源报的包名和请求包名不一致时，这次事件属于别的播放器 —— 只返回源的数据，
 *    绝不把当前包的 MediaSession 掺进去；
 * 3. 包名一致但会话对不上时，仍然用同包会话的封面（取色要用），
 *    但 `identity` 必须以源为准，避免跨包名封面串味。
 */
internal object CurrentMediaInfoResolver {

    fun getMediaInfo(
        context: Context,
        packageName: String,
        logger: HyperLogger? = null,
        sourceMetadata: LyricMediaMetadata? = null
    ): MediaMetadataHelper.MediaInfo {
        val pkg = packageName.trim()
        val source = (sourceMetadata ?: LyriconDataBridge.currentLyricMediaMetadata)?.normalized()
        val sourcePkg = source?.packageName
        // 源没报包名，或报的就是请求包 → 视为匹配
        val sourcePkgOk = sourcePkg.isNullOrEmpty() || sourcePkg == pkg

        val session = MediaMetadataHelper.getMediaInfo(
            context = context,
            packageName = pkg,
            logger = logger,
            preferredSessionToken = source?.sessionToken
        )

        // 完全没有源快照：直接交会话数据
        if (source == null) return session.cleaned()

        // 源属于别的包：这份快照只作它自己的事件，不掺入当前包的会话
        if (!sourcePkgOk) {
            return MediaMetadataHelper.MediaInfo(
                title = source.title.orEmpty(),
                artist = source.artist.orEmpty(),
                album = source.album.orEmpty(),
                duration = source.duration ?: -1L,
                identity = source.toIdentity()
            )
        }

        val sessionOk = belongsToSource(source, session, pkg)
        // 会话对得上就用它；对不上也留着封面，identity 换成源的
        val base = if (sessionOk) {
            session
        } else {
            session.copy(identity = source.toIdentity(pkg))
        }

        return base.copy(
            title = source.title ?: base.title.cleaned(),
            artist = source.artist ?: base.artist.cleaned(),
            album = source.album ?: base.album.cleaned(),
            duration = source.duration ?: base.duration,
            identity = if (sessionOk) {
                source.toIdentity(pkg).fillMissingFrom(session.identity)
            } else {
                source.toIdentity(pkg)
            }
        )
    }

    /**
     * 判断这个 MediaSession 是不是源事件对应的那个。
     *
     * 光有包名不算证据。优先精确 token，其次 mediaId，最后才用专辑名兜底。
     * 歌名和歌手**不参与**判定 —— 播放器会为翻译、车机显示改它们。
     */
    private fun belongsToSource(
        source: LyricMediaMetadata,
        session: MediaMetadataHelper.MediaInfo,
        packageName: String
    ): Boolean {
        val sid = session.identity.normalized()
        if (packageName.isEmpty() || sid.packageName != packageName) return false

        // 有 token：token 必须对上；源还给了 mediaId 就再比 mediaId
        source.sessionToken?.let { token ->
            if (sid.sessionToken != token) return false
            source.mediaId?.let { mediaId ->
                sid.mediaId?.let { current -> return current == mediaId }
            }
            return true
        }

        // 只有 mediaId：会话也报了 mediaId 才能比
        source.mediaId?.let { mediaId ->
            if (sid.mediaId != null) return sid.mediaId == mediaId
        }

        // 都没给：拿专辑名做最后兜底
        val sourceAlbum = source.album?.let { it.cleaned() }.orEmpty()
        val sessionAlbum = session.album.cleaned()
        if (sourceAlbum.isEmpty() || sessionAlbum.isEmpty()) return false
        return sourceAlbum.equals(sessionAlbum, ignoreCase = true)
    }

    /** 整份快照的文本归一。 */
    private fun MediaMetadataHelper.MediaInfo.cleaned(): MediaMetadataHelper.MediaInfo = copy(
        title = title.cleaned(),
        artist = artist.cleaned(),
        album = album.cleaned(),
        identity = identity.normalized()
    )

    /** 多空白压一个空格，首尾去空。 */
    private fun String.cleaned(): String = replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
}
