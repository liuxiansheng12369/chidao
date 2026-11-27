/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.app.live

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条听歌记录（只统计"听够一半或 4 分钟"的有效播放）。明细存 SQLite，不清空、按天归档保留 365 天。 */
data class ListenRecord(
    val title: String,
    val artist: String,
    val packageName: String,
    val time: Long,
    val lastTime: Long,
    val listenedMs: Long,
    val durationMs: Long,
    /** 封面文件名（filesDir/covers 下），空表示没有封面，前端退回色块 */
    val cover: String = "",
)

/** 一天的汇总：收录首数 + 收听时长 + 当天最爱。按天累加，不受日清和明细上限影响。 */
data class DailyStat(
    val day: String,
    val count: Int,
    val ms: Long,
    val topArtist: String = "",
    val topSong: String = "",
)

/** 累计标量：永不清空。totalCount 按条算（同一首听 10 天算 10 条）。 */
data class RecordTotals(
    val totalCount: Long,
    val totalListenedMs: Long,
    val totalDays: Long,
    val firstAt: Long,
) {
    companion object {
        val EMPTY = RecordTotals(0L, 0L, 0L, 0L)
    }
}

object ListenRecordsStore {
    /** 今天的明细（首页卡片、今日卡用）：明细存在 SQLite 里，按今天 0 点之后查。 */
    fun load(context: Context): List<ListenRecord> =
        runCatching { ListenRecordDb.listSince(context, dayStartMs()) }.getOrDefault(emptyList())

    /** 全部明细（记录页按日期分组用）：明细不清空，按天归档保留 365 天。 */
    fun loadAll(context: Context): List<ListenRecord> =
        runCatching { ListenRecordDb.listAll(context) }.getOrDefault(emptyList())

    /** 明细总条数（排查用）。 */
    fun countAll(context: Context): Int =
        runCatching { ListenRecordDb.countAll(context) }.getOrDefault(0)


    /** 每日汇总：不受"列表每天清空"影响，按天一直累加，7 天 / 30 天统计和周柱状图都读它。 */
    fun loadDaily(context: Context): List<DailyStat> {
        val raw = LiveStateProvider.recordsPrefs(context)
            .getString(LiveStateProvider.KEY_DAILY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<DailyStat>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val day = o.optString("date").ifBlank { o.optString("day") }
            if (day.isBlank()) continue
            out.add(
                DailyStat(
                    day = day,
                    count = o.optInt("count"),
                    ms = o.optLong("listenedMs", o.optLong("ms")),
                    topArtist = o.optString("topArtist"),
                    topSong = o.optString("topSong"),
                )
            )
        }
        return out.sortedByDescending { it.day }
    }

    /** 累计标量（永不清空）：总条数、总时长、打卡天数、第一条时间。 */
    fun loadTotals(context: Context): RecordTotals {
        val raw = LiveStateProvider.recordsPrefs(context)
            .getString(LiveStateProvider.KEY_TOTALS, null) ?: return RecordTotals.EMPTY
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return RecordTotals.EMPTY
        return RecordTotals(
            totalCount = o.optLong("totalCount"),
            totalListenedMs = o.optLong("totalListenedMs"),
            totalDays = o.optLong("totalDays"),
            firstAt = o.optLong("firstAt"),
        )
    }

    /** 清当天明细：只删今天这一天的记录，累计（totals / daily）一概不动。 */
    fun clearToday(context: Context) {
        val start = dayStartMs()
        runCatching { ListenRecordDb.deleteRange(context, start, start + ListenRecordDb.DAY_MS) }
        // 明天还要看的封面别删，只清掉不再被任何记录引用的
        runCatching { CoverStore.prune(context, ListenRecordDb.coverNames(context)) }
    }

    /** 清空全部：明细 + 每日汇总 + 累计一起清，回到全新状态。 */
    fun clearAll(context: Context) {
        runCatching { ListenRecordDb.clear(context) }
        LiveStateProvider.recordsPrefs(context).edit()
            .remove(LiveStateProvider.KEY_RECORDS)
            .remove(LiveStateProvider.KEY_DAILY)
            .remove(LiveStateProvider.KEY_TOTALS)
            .remove(LiveStateProvider.KEY_RECORDS_DAY)
            .apply()
        runCatching { CoverStore.prune(context, emptySet()) }
    }

    /** 删除单条（按"歌名 + 歌手 + 播放时间"定位）。 */
    fun remove(context: Context, record: ListenRecord) {
        runCatching { ListenRecordDb.deleteOne(context, record.title, record.artist, record.time) }
        runCatching { CoverStore.prune(context, ListenRecordDb.coverNames(context)) }
    }

    fun isEnabled(context: Context): Boolean =
        LiveStateProvider.recordsPrefs(context).getBoolean(LiveStateProvider.KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        LiveStateProvider.recordsPrefs(context).edit()
            .putBoolean(LiveStateProvider.KEY_ENABLED, enabled)
            .apply()
    }

    /** 播放来源 App 的展示名（未知则回退包名）。 */
    fun appLabel(packageName: String): String = when (packageName) {
        "com.luna.music" -> "汽水音乐"
        "com.netease.cloudmusic" -> "网易云音乐"
        "com.tencent.qqmusic" -> "QQ 音乐"
        "com.kugou.android" -> "酷狗音乐"
        "com.kugou.android.lite" -> "酷狗音乐概念版"
        "cn.kuwo.player" -> "酷我音乐"
        "com.migu.music" -> "咪咕音乐"
        "cmccwm.mobilemusic" -> "咪咕音乐"
        "cn.toside.music.mobile" -> "落雪音乐"
        "cn.tinyhappydog.musicfree" -> "MusicFree"
        "com.salt.music" -> "Salt Player"
        "cn.wenyu.bodian" -> "波点音乐"
        "com.xuncorp.qinalt.music" -> "青盐云听"
        "com.spotify.music" -> "Spotify"
        "com.apple.android.music" -> "Apple Music"
        "com.google.android.apps.youtube.music" -> "YouTube Music"
        "com.amazon.mp3" -> "Amazon Music"
        "com.ximalaya.ting.android" -> "喜马拉雅"
        "com.qingting.fm" -> "蜻蜓 FM"
        "com.lizhi.fm" -> "荔枝 FM"
        "com.tencent.karaoke" -> "全民 K 歌"
        "com.miui.player" -> "小米音乐"
        "com.huawei.music" -> "华为音乐"
        "com.qianqian.music" -> "千千音乐"
        "com.leguandev.sollin.v2" -> "Sollin"
        else -> packageName
    }

    /**
     * 只记录"音乐类"App。
     * 视频、短视频、听书、浏览器网页播放这些也会在系统里登记媒体会话，
     * 不做过滤就会被当成"听歌"记进来。
     */
    val MUSIC_PACKAGES: Set<String> = setOf(
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

    fun isMusicApp(packageName: String): Boolean = packageName in MUSIC_PACKAGES

    /** "听了 2:13" 这种时长文案。 */
    fun listenedLabel(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "听了 $min:${sec.toString().padStart(2, '0')}"
    }

    /** 本地时区今天 0 点（与写入侧的懒清口径一致）。 */
    private fun dayStartMs(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
