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

import android.media.session.MediaSession

/**
 * 播放器会话 + 当前媒体项的身份标识。
 *
 * 会话靠 `sessionToken` 认，媒体项靠 `songId` / `mediaId` 认。
 * **故意不把歌名、歌手算进身份** —— 播放器会因为翻译、车机显示等原因随时改这两样，
 * 拿它们比会把同一首歌判成两首。
 *
 * @property packageName 音乐 App 包名
 * @property sessionToken 播放器会话 token
 * @property songId 歌词源给的歌曲标识
 * @property mediaId 播放器给的媒体项标识
 */
data class MediaIdentity(
    val packageName: String = "",
    val sessionToken: MediaSession.Token? = null,
    val songId: String? = null,
    val mediaId: String? = null
) {

    /** 归一化：包名去空，标识去空并把 "0" 当没填。 */
    fun normalized(): MediaIdentity = copy(
        packageName = packageName.trim(),
        songId = songId.cleanId(),
        mediaId = mediaId.cleanId()
    )

    /**
     * 只从另一个快照补未知字段。已知的字段一律以自己为准。
     */
    fun fillMissingFrom(fallback: MediaIdentity): MediaIdentity = copy(
        packageName = packageName.ifEmpty { fallback.packageName },
        sessionToken = sessionToken ?: fallback.sessionToken,
        songId = songId ?: fallback.songId,
        mediaId = mediaId ?: fallback.mediaId
    ).normalized()

    /**
     * 两边能不能证明是同一个会话。
     *
     * - 包名都非空且不同 → `false`
     * - 两边都有 token → 比 token
     * - 其余情况信息不足 → `null`（包名相同**从来不**单独作为同会话的证据）
     */
    fun sameSessionAs(other: MediaIdentity): Boolean? =
        sameSessionCore(normalized(), other.normalized())

    /**
     * 两边的所有权 / 条目身份有没有冲突。
     *
     * 只有双方都给出了值且值不同才算冲突；缺失字段一律当未知，允许弱来源后补。
     */
    fun isCompatibleWith(other: MediaIdentity): Boolean {
        val mine = normalized()
        val theirs = other.normalized()
        if (sameSessionCore(mine, theirs) == false) return false
        if (mine.songId != null && theirs.songId != null && mine.songId != theirs.songId) return false
        if (mine.mediaId != null && theirs.mediaId != null && mine.mediaId != theirs.mediaId) return false
        return true
    }

    /** 已归一化之后的会话比对，避免重复 normalize。 */
    private fun sameSessionCore(left: MediaIdentity, right: MediaIdentity): Boolean? {
        if (left.packageName.isNotEmpty() && right.packageName.isNotEmpty() &&
            left.packageName != right.packageName
        ) {
            return false
        }
        if (left.sessionToken != null && right.sessionToken != null) {
            return left.sessionToken == right.sessionToken
        }
        return null
    }

    /** 标识清洗：去空，"0" 和空串都算没填。 */
    private fun String?.cleanId(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "0" }
}
