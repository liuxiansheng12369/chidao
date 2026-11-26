/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.app.live

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * 直接向「系统媒体库」要当前播放：歌名、歌手、时长、是否在播、放到第几毫秒。
 *
 * 和原来那条岛链路的区别：
 * - 岛链路 = SystemUI 侧每 500ms 拍一次写进 prefs，打轴器读的是"过去的快照"，
 *   模块没激活 / 岛没显示时还没有数据；
 * - 这里 = 直接问系统的 MediaSession，位置按 PlaybackState 的时间基准自己推算，
 *   读到的那一刻就是播放器此刻的位置，跟岛毫无关系。
 *
 * 拿不到（没授权通知使用权、或确实没在放）返回 null，调用方自己回退。
 */
object MediaSessions {

    /** 会话来多久没动静就不认它（毫秒）。 */
    private const val MAX_AGE_MS = 5 * 60 * 1000L

    /** 会话列表缓存多久再重新列举（毫秒）。位置仍每次现读。 */
    private const val CACHE_MS = 2000L

    private fun manager(context: Context): MediaSessionManager? =
        runCatching {
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }.getOrNull()

    private fun component(context: Context) =
        ComponentName(context, AxisNotifListener::class.java)

    /** 通知使用权是否已授予：没授予就只能走回退链路。 */
    fun isEnabled(context: Context): Boolean =
        runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)

    /**
     * 让系统重新绑定监听服务。
     * 覆盖安装 / 换机后，权限已经写进设置但服务可能还没被 bind，
     * 这时 getActiveSessions() 会是空的，主动请求一次即可，不用用户去设置里关开一遍。
     */
    fun requestRebind(context: Context) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                NotificationListenerService.requestRebind(component(context))
            }
        }
    }

    /**
     * 把自己追加进系统的通知使用权列表（保留已有的监听器，不覆盖别人）。
     * 部分 ROM 不认 `cmd notification allow_listener`，只能直接写 secure 设置，
     * 所以这里读原值再拼，读不到就只写自己。
     */
    private fun grantScript(context: Context): String {
        val self = "${context.packageName}/${AxisNotifListener::class.java.name}"
        return "v=\$(settings get secure enabled_notification_listeners); " +
            "case \":\$v:\" in *\":$self:\"*) ;; " +
            "*) settings put secure enabled_notification_listeners \"\$v:$self\" ;; esac"
    }

    /**
     * 有 root 时自己把「通知使用权」写回去。
     * 覆盖安装 / 换机后这条授权会掉（系统重扫监听器时清掉），
     * 不想让用户每次去设置里点，能 su 就 su 补，补完再让系统重绑。
     */
    fun tryRootGrant(context: Context): Boolean {
        if (isEnabled(context)) {
            requestRebind(context)
            return true
        }
        val ok = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", grantScript(context)))
            p.waitFor() == 0
        }.getOrDefault(false)
        if (ok) requestRebind(context)
        return ok
    }

    private fun controllers(context: Context): List<MediaController> =
        runCatching {
            manager(context)?.getActiveSessions(component(context)).orEmpty()
        }.getOrElse { emptyList() }

    /** 挑当前那个：优先正在播的，其次最近一次有位置更新的。 */
    private fun pick(list: List<MediaController>): MediaController? {
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
        val now = SystemClock.elapsedRealtime()
        return list
            .filter { c ->
                val ps = c.playbackState ?: return@filter c.metadata != null
                ps.state != PlaybackState.STATE_NONE && ps.state != PlaybackState.STATE_STOPPED
            }
            .filter { it.metadata != null || it.playbackState != null }
            .maxByOrNull { c ->
                val at = c.playbackState?.lastPositionUpdateTime ?: 0L
                if (at in (now - MAX_AGE_MS)..now) at else 0L
            }
    }

    /** 缓存当前会话：打轴时位置要 100ms 一问，不能每次都让 system_server 全量列举。 */
    private var cached: MediaController? = null
    private var cachedAt = 0L

    private fun current(context: Context): MediaController? {
        val now = SystemClock.elapsedRealtime()
        cached?.let { c ->
            if (now - cachedAt < CACHE_MS) return c
        }
        val picked = pick(controllers(context))
        cached = picked
        cachedAt = now
        return picked
    }

    /**
     * 当前这一帧。读不到返回 null。
     * 会话来没在播（或换歌）时靠 [current] 的短缓存兜住，读的过程本身不做重活。
     */
    fun snapshot(context: Context): NowPlaying? {
        val ctl = current(context) ?: return null
        val md: MediaMetadata = md(ctl) ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) return null

        val ps = ctl.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING ||
            ps?.state == PlaybackState.STATE_BUFFERING
        val position = ps?.let {
            val base = it.position.coerceAtLeast(0L)
            if (playing && it.lastPositionUpdateTime > 0L) {
                val dt = (SystemClock.elapsedRealtime() - it.lastPositionUpdateTime).coerceAtLeast(0L)
                base + (dt * it.playbackSpeed.coerceAtLeast(0f)).toLong()
            } else {
                base
            }
        } ?: 0L

        return NowPlaying(
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            playing = playing,
            packageName = ctl.packageName.orEmpty(),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            positionMs = position.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun md(ctl: MediaController): MediaMetadata? =
        runCatching { ctl.metadata }.getOrNull()

    /**
     * 控制真正的播放器。cmd 与打轴器一致：toggle / play / pause / next / prev /
     * seek_to（arg=目标毫秒）/ seek_by（arg=增量毫秒）。
     */
    fun control(context: Context, cmd: String, arg: Long) {
        val ctl = current(context) ?: return
        val tc = runCatching { ctl.transportControls }.getOrNull() ?: return
        runCatching {
            when (cmd) {
                "toggle" ->
                    if (ctl.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
                "play" -> tc.play()
                "pause" -> tc.pause()
                "next" -> tc.skipToNext()
                "prev" -> tc.skipToPrevious()
                "seek_to" -> tc.seekTo(arg.coerceAtLeast(0L))
                "seek_by" -> {
                    val cur = snapshot(context)?.positionMs ?: 0L
                    tc.seekTo((cur + arg).coerceAtLeast(0L))
                }
            }
        }
    }
}
