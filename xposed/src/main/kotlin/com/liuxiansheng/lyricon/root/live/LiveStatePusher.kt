/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.hyper.root.live
import com.liuxiansheng.lyricon.hyper.root.utils.DiagnosticsLog

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge
import com.liuxiansheng.lyricon.hyper.root.source.LyriconSource
import com.liuxiansheng.lyricon.xposed.systemui.util.CurrentMediaInfo

/**
 * 把"状态栏/岛上正在播的真实内容"推给 App，并顺手结算听歌记录。
 *
 * 两条推送：
 *  1. 实时帧：歌名/歌手/当前歌词/下一句/播放状态变化时推（首页实时预览用）
 *  2. 播放结算：换歌或停播时，把上一首"开始时间 + 实际听了多久 + 总时长"推过去，
 *     App 按 Last.fm 口径决定这条算不算一次有效播放（听够一半或 4 分钟才算）。
 *
 * 方向：SystemUI（本进程）→ App 的 ContentProvider（App 侧 LiveStateProvider）。
 */
object LiveStatePusher {
    private const val TAG = "LiveStatePusher"
    private const val AUTHORITY = "io.github.liuxiansheng12369.chidao.live-state"
    private const val METHOD_PUBLISH = "publish_live"

    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_PLAYING = "playing"
    private const val KEY_PKG = "package"
    private const val KEY_LYRIC = "lyric"
    private const val KEY_NEXT = "next"
    private const val KEY_SONG = "song"
    private const val KEY_DURATION_MS = "duration_ms"
    private const val KEY_POSITION_MS = "position_ms"

    /** 歌词源运行态：给 App"歌词源"页的自检块显示当前生效源用。 */
    private const val KEY_PROVIDER_PKG = "provider_pkg"
    private const val KEY_PROVIDER_PLAYER = "provider_player"
    private const val KEY_PROVIDER_AT = "provider_at"

    /** 诊断探针：歌词源最近推来的原始 Song（"歌名#行数"），排查"没词"用。 */
    private const val KEY_SRC_SONG = "src_song"

    private const val KEY_FINALIZE = "finalize"
    private const val KEY_F_STARTED_AT = "f_started_at"
    private const val KEY_F_LISTENED_MS = "f_listened_ms"
    private const val KEY_F_DURATION_MS = "f_duration_ms"

    /** 心跳帧：证明模块活着，并带上 SystemUI 进程的启动时刻 */
    private const val KEY_HB = "hb"
    private const val KEY_SYSUI_START = "sysui_started_at"
    private const val HB_INTERVAL_MS = 3_000L

    /** 专辑封面（首页预览里的封面位）：换歌时推一次，160px JPEG 的 base64 */
    private const val KEY_COVER_B64 = "cover_b64"
    private const val KEY_COVER_TAG = "cover_tag"
    private const val COVER_PX = 160

    private const val NOT_PLAYING = "未在播放"

    /**
     * 只对音乐类 App 做听歌记录（视频/短视频/听书这些不记）。
     * 必须与 App 侧 ListenRecordsStore.MUSIC_PACKAGES 逐条一致，否则这边先结算、
     * 那边拒收，用户看到的现象就是"某个播放器怎么听都不进记录"。
     */
    private val MUSIC_PACKAGES = setOf(
        "com.luna.music",                        // 汽水音乐
        "com.netease.cloudmusic",                // 网易云音乐
        "com.tencent.qqmusic",                   // QQ 音乐
        "com.kugou.android",                     // 酷狗音乐
        "com.kugou.android.lite",                // 酷狗音乐概念版
        "cn.kuwo.player",                        // 酷我音乐
        "com.migu.music",                        // 咪咕音乐
        "cmccwm.mobilemusic",                    // 咪咕音乐（另一包名）
        "cn.toside.music.mobile",                // 落雪音乐（lx-music-mobile）
        "cn.tinyhappydog.musicfree",             // MusicFree
        "com.salt.music",                        // Salt Player
        "cn.wenyu.bodian",                       // 波点音乐
        "com.xuncorp.qinalt.music",              // 青盐云听
        "com.spotify.music",                     // Spotify
        "com.apple.android.music",               // Apple Music
        "com.google.android.apps.youtube.music", // YouTube Music
        "com.amazon.mp3",                        // Amazon Music
        "com.ximalaya.ting.android",             // 喜马拉雅
        "com.qingting.fm",                       // 蜻蜓 FM
        "com.lizhi.fm",                          // 荔枝 FM
        "com.tencent.karaoke",                   // 全民 K 歌
        "com.miui.player",                       // 小米音乐（系统）
        "com.huawei.music",                      // 华为音乐（系统）
        "com.qianqian.music",                    // 千千音乐
        "com.leguandev.sollin.v2",               // Sollin（Flutter 聚合播放器）
        // —— 商店歌词源已经覆盖、但之前漏记的播放器（包名取自各源模块的 scope/常量）——
        "com.hihonor.cloudmusic",                // 荣耀云音乐（网易云源同源支持）
        "com.hihonor.music",                     // 荣耀音乐（ROM 播放器）
        "com.xiaomi.music",                      // 小米音乐（新版包名）
        "com.tencent.qqmusicpad",                // QQ 音乐 HD
        "com.tencent.freemusic",                 // QQ 音乐简洁版
        "com.maxmpz.audioplayer",                // Poweramp
        "ink.trantor.coneplayer",                // ConePlayer
        "ink.trantor.coneplayer.gp",             // ConePlayer GP
        "ink.trantor.coneplayer.go",             // ConePlayer Go
        "app.symfonik.music.player",             // Symfonium
        "org.akanework.gramophone",              // Gramophone
        "code.name.monkey.retromusic",           // Retro Music
        "top.imsyy.splayer.android",             // SPlayer
        "com.trim.music",                        // 飞牛音乐
        "com.meizu.media.music",                 // 魅族音乐
        "com.lxwalnut.music.mobile",             // 洛雪音乐（Walnut 分支）
        "com.ikunshare.music.mobile",            // 洛雪音乐（IKUN 分支）
        "com.lxnetease.music.mobile",            // 洛雪音乐（网易分支）
    )

    private val uri: Uri = Uri.parse("content://$AUTHORITY")

    private var started = false
    private var lastContext: Context? = null
    private var lastSignature = ""

    // 当前这一首的结算信息
    private var curTitle = ""
    private var curArtist = ""
    private var curPkg = ""
    private var curStartedAt = 0L
    private var curMaxPosition = 0L
    private var curDuration = 0L
    private var lastCoverTag = ""
    private var coverTries = 0

    /*
     * 状态推送必须跑在后台线程。
     *
     * 原来这里是 Handler(Looper.getMainLooper())，每 500ms 在 SystemUI 主线程上
     * 同步跨一次进程（ContentResolver.call → App 的 LiveStateProvider）。
     * App 侧一忙（打轴器敲轴、保存时读 59 KB 的轴文件、写 index.json），
     * binder 往返就被拉长，主线程陪着一起等，超过 5 秒交不出触摸事件，
     * 系统就报 DynamicIslandWindow "Input dispatching timed out" —— 用户看到的是超级岛卡死。
     *
     * 换成独立 HandlerThread 之后，某一次 call 再慢也只卡这条后台线程，
     * 主线程照常处理触摸，岛不再僵住。
     */
    private val handlerThread = HandlerThread("LyriconLivePusher").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val tick = object : Runnable {
        override fun run() {
            runCatching { poll() }
            // 500ms 一拍：打轴器要跟得上拖动进度条，1 秒太粗糙
            handler.postDelayed(this, 500)
        }
    }

    /**
     * 心跳：每 3 秒无条件推一帧，不带实时状态。
     *
     * 实时帧只在内容变化时才推（signature 相同就跳过），没歌在放时一直静默，
     * App 侧就没法区分"模块活着但没歌"和"模块根本没跑"。心跳补上这个空档：
     * App 6.5 秒内收到过心跳就算已激活；帧里的 sysui_started_at 用来判断
     * App 重装后状态栏里跑的是不是旧代码。
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            runCatching {
                val context = lastContext
                if (context != null) {
                    call(
                        context,
                        Bundle().apply {
                            putBoolean(KEY_HB, true)
                            putLong(KEY_SYSUI_START, sysuiStartedAt())
                        },
                    )
                }
            }
            handler.postDelayed(this, HB_INTERVAL_MS)
        }
    }

    /** SystemUI 进程启动的 wall clock 时间（拿不到就返回 0，App 侧会跳过重启判定） */
    private fun sysuiStartedAt(): Long = runCatching {
        val startElapsed = android.os.Process.getStartElapsedRealtime()
        System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() + startElapsed
    }.getOrDefault(0L)

    /**
     * 取当前这首歌的封面，压成 160px JPEG 再 base64。
     *
     * 直接读 `MediaController.metadata` 是拿不到封面的（跨进程快照里 ALBUM_ART 被剔掉），
     * 这里走 MediaMetadataHelper 的会话回调缓存。
     */
    private fun encodeCover(context: Context, pkg: String): String? {
        if (pkg.isBlank()) return null
        val art = com.liuxiansheng.lyricon.hyper.root.media.CurrentMediaInfoResolver
            .getMediaInfo(context, pkg)
            .albumArt
            ?.takeUnless { it.isRecycled }
            ?: return null
        val scaled = android.graphics.Bitmap.createScaledBitmap(art, COVER_PX, COVER_PX, true)
        val bytes = java.io.ByteArrayOutputStream()
        if (!scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bytes)) return null
        if (scaled !== art) scaled.recycle()
        return android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun start(context: Context) {
        if (started) return
        started = true
        lastContext = context.applicationContext ?: context
        // 打轴器的遥控通道跟着一起起：App 发广播，这里执行真正的播放/暂停/跳转
        AxisMediaControl.register(lastContext!!)
        handler.post(tick)
        // 心跳立刻先发一帧，App 刚打开主页就能拿到"已激活"
        handler.post(heartbeat)
        DiagnosticsLog.i(TAG, "实时状态推送已启动")
    }

    private fun poll() {
        val context = lastContext ?: return
        // 歌名/歌手/时长优先取"真正驱动岛上渲染的那条 Song"。
        // CurrentMediaInfo 的字段只靠播放器回调 metadata 更新，酷狗这类播放器切歌时不一定回调，
        // 会一直卡在上一首（实测实际在放《军中绿花》，这里仍是《情网》+ 上一首的时长和歌手），
        // App 首页的实时预览就跟着一起不动。
        val curSong = LyriconDataBridge.currentSong
        val title = curSong?.name?.takeIf { it.isNotBlank() } ?: CurrentMediaInfo.title
        val artist = curSong?.artist?.takeIf { it.isNotBlank() } ?: CurrentMediaInfo.artist
        val pkg = CurrentMediaInfo.packageName
        val playing = CurrentMediaInfo.isPlayingNow()
        // 用实时外推的位置：拖了进度条立刻就是新位置，不留 1 秒滞后
        val position = CurrentMediaInfo.livePosition()
        val duration = curSong?.duration?.takeIf { it > 0L } ?: CurrentMediaInfo.duration

        // 只有音乐类 App 才参与听歌记录；视频/短视频/听书不进结算，也不打乱当前结算状态
        val musicSource = pkg.isBlank() || pkg in MUSIC_PACKAGES

        if (musicSource) {
            // 位置累积：只取最大值，避免拖动进度条把"听了多久"算成一次长播放
            if (position > curMaxPosition) curMaxPosition = position
            if (duration > 0) curDuration = duration

            val trackChanged = title != curTitle && title != NOT_PLAYING
            if (trackChanged) {
                // 上一首结算（第一首之前 curTitle 为空，不结算）
                if (curTitle.isNotBlank()) {
                    DiagnosticsLog.i(
                        TAG,
                        "换歌结算上一首: title=$curTitle listened=${curMaxPosition}ms dur=${curDuration}ms",
                    )
                    pushFinalize(context, curTitle, curArtist, curPkg, curStartedAt, curMaxPosition, curDuration)
                }
                curTitle = title
                curArtist = artist
                curPkg = pkg
                curStartedAt = System.currentTimeMillis()
                curMaxPosition = 0L
                curDuration = if (duration > 0) duration else 0L
                lastSignature = ""
            }

            // 停播：结算当前这首，然后把会话清空（下次重新开始算）
            if (!playing && curTitle.isNotBlank() && position <= 0L) {
                DiagnosticsLog.i(
                    TAG,
                    "停播结算: title=$curTitle listened=${curMaxPosition}ms dur=${curDuration}ms",
                )
                pushFinalize(context, curTitle, curArtist, curPkg, curStartedAt, curMaxPosition, curDuration)
                curTitle = ""
                curArtist = ""
                curPkg = ""
                curStartedAt = 0L
                curMaxPosition = 0L
                curDuration = 0L
            }
        }

        val lyric = LyriconDataBridge.currentLyricLine?.text.orEmpty()
        val next = LyriconDataBridge.currentNextLyricLine?.text.orEmpty()
        val song = LyriconDataBridge.currentSongName.orEmpty()

        // 专辑封面：换歌时取，最多试三拍（封面的 Bitmap 要等系统推 onMetadataChanged，
        // 换歌那一瞬间常常还是空的），取到就不再跑。封面位在主页上就几十 dp，压到 160px 足够。
        val coverTag = if (title.isBlank() || title == NOT_PLAYING) "" else "$title|$artist"
        var coverB64: String? = null
        if (coverTag.isNotEmpty()) {
            if (coverTag != lastCoverTag) {
                lastCoverTag = coverTag
                coverTries = 0
            }
            if (coverTries < 3) {
                coverTries++
                coverB64 = runCatching { encodeCover(context, pkg) }.getOrNull()
                if (coverB64 != null) coverTries = 3
            }
        }

        // 歌词源运行态：LyriconSource 在活跃 provider 变化时写下的进程内快照
        val providerPkg = LyriconSource.liveProviderPackageName.orEmpty()
        val providerPlayer = LyriconSource.livePlayerPackageName.orEmpty()
        val providerAt = LyriconSource.liveProviderUpdatedAt

        // 打轴器靠这个真实进度算时间，所以位置每变 0.5 秒也算一次更新
        val signature = "$title|$artist|$playing|$pkg|$lyric|$next|$song|$curDuration|" +
            "${position / 500}|$providerPkg|$providerPlayer|$providerAt|" +
            LyriconDataBridge.providerSongProbe
        if (signature == lastSignature) return
        lastSignature = signature

        val extras = Bundle().apply {
            putString(KEY_TITLE, title)
            putString(KEY_ARTIST, artist)
            putBoolean(KEY_PLAYING, playing)
            putString(KEY_PKG, pkg)
            putString(KEY_LYRIC, lyric)
            putString(KEY_NEXT, next)
            putString(KEY_SONG, song)
            // 打轴器要按时长算进度和命中键，这里补推整首时长
            putLong(KEY_DURATION_MS, curDuration)
            // 播放器真实进度：打轴器跟着它走，敲出来的时间才是准的
            putLong(KEY_POSITION_MS, position.coerceAtLeast(0L))
            // 歌词源运行态（App 的"歌词源"页自检用）
            putString(KEY_PROVIDER_PKG, providerPkg)
            putString(KEY_PROVIDER_PLAYER, providerPlayer)
            putLong(KEY_PROVIDER_AT, providerAt)
            putString(KEY_SRC_SONG, LyriconDataBridge.providerSongProbe.orEmpty())
            // 封面：tag 每帧都带（App 靠它判断手里的图还是不是这首），图本身只在换歌那帧带
            putString(KEY_COVER_TAG, coverTag)
            coverB64?.let { putString(KEY_COVER_B64, it) }
        }
        // 送不出去就把签名清掉：否则 App 进程被冻结/刚装完那几拍丢掉的帧，
        // 只要歌名和进度不再变化就永远不会补推，首页预览会一直停在旧歌
        if (!call(context, extras)) lastSignature = ""
    }

    private fun pushFinalize(
        context: Context,
        title: String,
        artist: String,
        pkg: String,
        startedAt: Long,
        listenedMs: Long,
        durationMs: Long,
    ) {
        val extras = Bundle().apply {
            putBoolean(KEY_FINALIZE, true)
            putString(KEY_TITLE, title)
            putString(KEY_ARTIST, artist)
            putString(KEY_PKG, pkg)
            putLong(KEY_F_STARTED_AT, startedAt)
            putLong(KEY_F_LISTENED_MS, listenedMs)
            putLong(KEY_F_DURATION_MS, durationMs)
        }
        call(context, extras)
    }

    private fun call(context: Context, extras: Bundle): Boolean {
        // 性能监控（默认关）：跨进程推送给 App 也是一次 binder 往返，值得计时
        val perfStart = if (com.liuxiansheng.lyricon.hyper.root.perf.HookPerfMonitor.enabledFlag)
            System.nanoTime() else 0L
        try {
            return runCatching {
                context.contentResolver.call(uri, METHOD_PUBLISH, null, extras) != null
            }.onFailure {
                // 原来这里把异常吞掉了，App 没起来 / 被冻结时一次失败就静默丢帧，谁也不知道
                android.util.Log.w(TAG, "推送实时状态失败: ${it.javaClass.simpleName} ${it.message}")
            }.getOrDefault(false)
        } finally {
            if (perfStart != 0L) {
                com.liuxiansheng.lyricon.hyper.root.perf.HookPerfMonitor.recordSample(
                    "live.push",
                    System.nanoTime() - perfStart,
                )
            }
        }
    }
}
