/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 */

package com.liuxiansheng.lyricon.app.live

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.liuxiansheng.lyricon.axis.api.ManualAxisApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * 接收 SystemUI（模块侧 LiveStatePusher）推送的"正在播放"实时状态。
 *
 * 只允许 system uid / root 写入（其它应用即使知道 authority 也写不进来）。
 * 数据落到 App 自己的 prefs：
 *  - `live_state`：最新一帧（歌名/歌手/是否在播/包名/当前歌词/下一句）
 *  - `listen_records`：听歌记录（换歌时追加，最多 200 条）
 */
class LiveStateProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            // 先把 prefs 里的老明细导进 SQLite（prefs 原数据保留，留作回滚），再做老的按天聚合迁移
            runCatching { migrateRecordsToDbOnce(ctx) }
            runCatching { migrateLegacyOnce(ctx) }
        }
        return true
    }

    /**
     * 明细入库（只做一次）：把 prefs 里 `records` 的老明细整体导入 SQLite，按原样插、不合并。
     * prefs 里的原数据**不删**（回滚用），只是后续写入都走 SQLite。
     */
    private fun migrateRecordsToDbOnce(context: Context) {
        val prefs = recordsPrefs(context)
        if (prefs.getBoolean(KEY_DB_MIGRATED, false)) return
        val raw = prefs.getString(KEY_RECORDS, null)
        if (!raw.isNullOrBlank()) {
            val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            val list = ArrayList<ListenRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                list.add(
                    ListenRecord(
                        title = title,
                        artist = o.optString("artist"),
                        packageName = o.optString("package"),
                        time = o.optLong("time"),
                        lastTime = o.optLong("lastTime", o.optLong("time")),
                        listenedMs = o.optLong("listenedMs"),
                        durationMs = o.optLong("durationMs"),
                    )
                )
            }
            val n = runCatching { ListenRecordDb.importLegacy(context, list) }.getOrDefault(0)
            Log.d(TAG, "明细迁移到 SQLite: 读到 ${list.size} 条，入库 $n 条（prefs 原数据保留）")
        }
        prefs.edit().putBoolean(KEY_DB_MIGRATED, true).apply()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // 性能监控快照：SystemUI 侧 HookPerfMonitor 每 5 秒推一次，
        // 落 prefs 供设置页「调试 → 性能数据」直接看，不用接电脑抓日志。
        if (method == METHOD_PUBLISH_PERF) {
            if (!isTrustedCaller(Binder.getCallingUid())) return null
            val ctx = context ?: return null
            val json = extras?.getString("json").orEmpty()
            if (json.isNotBlank()) {
                livePrefs(ctx).edit()
                    .putString("perf_snapshot", json)
                    .putLong("perf_snapshot_at", extras?.getLong("at", System.currentTimeMillis()) ?: 0L)
                    .apply()
            }
            return Bundle().apply { putBoolean("accepted", true) }
        }
        // 打轴器查询：SystemUI 侧读不了 App 私有目录，由这里代读人工轴并回传 JSON。
        // arg = 命中键「标题|歌手|时长秒」，返回 extras["json"]。
        if (method == METHOD_AXIS_LOOKUP) {
            if (!isTrustedCaller(Binder.getCallingUid())) return null
            val context = context ?: return null
            val key = arg.orEmpty()
            val json = runCatching {
                if (key.isBlank()) null else ManualAxisApi.lookupJsonByKey(context, key)
            }.getOrNull() ?: return Bundle().apply { putBoolean("hit", false) }
            return Bundle().apply {
                putBoolean("hit", true)
                putString("json", json)
            }
        }
        if (method != METHOD_PUBLISH) return null
        val uid = Binder.getCallingUid()
        if (!isTrustedCaller(uid)) {
            Log.w(TAG, "拒绝非系统来源写入 uid=$uid")
            return null
        }
        val context = context ?: return null
        val title = extras?.getString(KEY_TITLE).orEmpty()
        val artist = extras?.getString(KEY_ARTIST).orEmpty()
        val playing = extras?.getBoolean(KEY_PLAYING, false) ?: false
        val pkg = extras?.getString(KEY_PKG).orEmpty()
        val lyric = extras?.getString(KEY_LYRIC).orEmpty()
        val next = extras?.getString(KEY_NEXT).orEmpty()
        val song = extras?.getString(KEY_SONG).orEmpty()
        val durationMs = extras?.getLong(KEY_DURATION_MS, 0L) ?: 0L
        val positionMs = extras?.getLong(KEY_POSITION_MS, 0L) ?: 0L
        // 歌词源运行态（由 SystemUI 侧 LyriconSource 提供）：给歌词源页的自检块用
        val providerPkg = extras?.getString(KEY_PROVIDER_PKG).orEmpty()
        val providerPlayer = extras?.getString(KEY_PROVIDER_PLAYER).orEmpty()
        val providerAt = extras?.getLong(KEY_PROVIDER_AT, 0L) ?: 0L

        val isFinalize = extras?.getBoolean(KEY_FINALIZE, false) ?: false

        // 心跳帧：模块每 3 秒推一次，只为证明"我还活着"+ 报 SystemUI 进程启动时刻，
        // 不覆盖实时状态（没歌在放时也要有心跳，否则主页会误判成未激活）
        if (extras?.getBoolean(KEY_HB, false) == true) {
            val editor = livePrefs(context).edit()
                .putLong(KEY_HB_AT, System.currentTimeMillis())
            val sysuiStart = extras.getLong(KEY_SYSUI_START, 0L)
            if (sysuiStart > 0L) editor.putLong(KEY_SYSUI_START, sysuiStart)
            editor.apply()
            return Bundle().apply { putBoolean("accepted", true) }
        }

        // 播放结算帧：只用来记听歌历史，不改实时状态（那一帧可能已经换歌了）
        if (isFinalize) {
            runCatching {
                acceptFinalize(
                    context = context,
                    title = title,
                    artist = artist,
                    pkg = pkg,
                    startedAt = extras?.getLong(KEY_F_STARTED_AT, 0L) ?: 0L,
                    listenedMs = extras?.getLong(KEY_F_LISTENED_MS, 0L) ?: 0L,
                    durationMs = extras?.getLong(KEY_F_DURATION_MS, 0L) ?: 0L,
                )
            }.onFailure { Log.w(TAG, "写入听歌记录失败", it) }
            return Bundle().apply { putBoolean("accepted", true) }
        }

        runCatching {
            val editor = livePrefs(context).edit()
                .putString(KEY_TITLE, title)
                .putString(KEY_ARTIST, artist)
                .putBoolean(KEY_PLAYING, playing)
                .putString(KEY_PKG, pkg)
                .putString(KEY_LYRIC, lyric)
                .putString(KEY_NEXT, next)
                .putString(KEY_SONG, song)
                .putLong(KEY_DURATION_MS, durationMs)
                .putLong(KEY_POSITION_MS, positionMs)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            // 只在推送帧真的带了 provider 字段时才覆盖：
            // 旧版模块推送的帧不带这几个 key，不能把已有值抹成空
            if (extras?.containsKey(KEY_PROVIDER_PKG) == true) {
                editor.putString(KEY_PROVIDER_PKG, providerPkg)
                    .putString(KEY_PROVIDER_PLAYER, providerPlayer)
                    .putLong(KEY_PROVIDER_AT, providerAt)
            }
            // 诊断探针：歌词源最近推来的原始 Song（"歌名#行数"），排查"没词"用
            extras?.getString("src_song")?.let { if (it.isNotBlank()) editor.putString("src_song", it) }
            // 封面的持有标签：每次推送都带，App 靠它判断手里的图还是不是这首
            val coverTag = extras?.getString(KEY_COVER_TAG).orEmpty()
            if (coverTag.isNotBlank()) editor.putString(KEY_COVER_TAG, coverTag)
            editor.apply()
        }.onFailure { Log.w(TAG, "写入实时状态失败", it) }

        // 封面图只在换歌那一帧带（160px JPEG base64），收到就落到私有目录，
        // 界面直接读文件，省得每次重建界面都跨进程取图
        extras?.getString(KEY_COVER_B64)?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val file = java.io.File(context.filesDir, COVER_FILE)
                java.io.FileOutputStream(file).use { it.write(bytes) }
            }.onFailure { Log.w(TAG, "写入封面失败", it) }
            // 换歌这一帧的封面顺手存档给听歌记录（同一首只存一张，md5 命名去重）
            runCatching { CoverStore.snapshot(context, title, artist) }
        }

        return Bundle().apply { putBoolean("accepted", true) }
    }

    /**
     * 播放结算：按 Last.fm 的 scrobble 口径判断"算不算一次有效播放"。
     *  - 曲长 < 30 秒的（短片/电台 jingle）不记
     *  - 实际听了 ≥ 一半，或 ≥ 4 分钟（长曲封顶）才算
     *  - 同一首 30 分钟内重复出现，只把记录移到最前并累加时长，不新增一条
     */
    private fun acceptFinalize(
        context: Context,
        title: String,
        artist: String,
        pkg: String,
        startedAt: Long,
        listenedMs: Long,
        durationMs: Long,
    ) {
        if (title.isBlank() || title == NOT_PLAYING) {
            return
        }
        if (!recordsEnabled(context)) {
            return
        }
        if (!ListenRecordsStore.isMusicApp(pkg)) {
            return
        }
        if (durationMs < MIN_TRACK_MS) {
            return
        }
        val need = minOf(durationMs / 2, MAX_COUNT_MS)
        // 位置回调偶尔会超过曲长（拖动进度条/网络续播），按曲长封顶
        val listened = if (durationMs > 0) minOf(listenedMs, durationMs) else listenedMs
        if (listened < need) {
            return
        }

        val now = System.currentTimeMillis()
        val prefs = recordsPrefs(context)
        val recordTime = if (startedAt > 0) startedAt else now

        // 明细进 SQLite（不清空、按天归档）：30 分钟内同曲合并，口径和原来一致
        val writeResult = ListenRecordDb.write(
            context, title, artist, pkg, recordTime, listened, durationMs, now
        )
        // 封面：结算这一刻若画面上还是这首歌就把封面存档（早存过一次的话直接复用）
        runCatching {
            val saved = CoverStore.snapshot(context, title, artist)
            val name = saved ?: CoverStore.nameOf(title, artist)
            if (CoverStore.file(context, name) != null) {
                ListenRecordDb.setCover(context, writeResult.id, name)
            }
        }.onFailure { Log.w(TAG, "封面存档失败", it) }
        // 汇总按这条记录自己的 time 落在哪天算（跨零点结算的那首算她那一天）
        bumpAggregates(
            prefs, context, dayKey(recordTime),
            if (writeResult.merged) 0 else 1, writeResult.msDelta
        )
        // 保留策略：单日最多 300 条（只截当天、不挤别的日期）、整体留 365 天
        val prunedRows = runCatching {
            ListenRecordDb.trimDay(context, dayStartMs(recordTime))
            ListenRecordDb.prune(context, now - ListenRecordDb.RETAIN_DAYS * ListenRecordDb.DAY_MS)
        }.onFailure { Log.w(TAG, "明细清理失败", it) }.getOrDefault(0)
        // 有记录被清掉，才需要顺手清掉没人引用的封面文件
        if (prunedRows > 0) {
            runCatching { CoverStore.prune(context, ListenRecordDb.coverNames(context)) }
        }
    }

    /**
     * 老数据迁移（只做一次）：把 records 里"今天之前"的历史按日期聚合进 daily 并累加 totals；
     * 顺便把上一版写进去的旧字段名（day/ms）统一成 date/listenedMs。
     */
    private fun migrateLegacyOnce(context: Context) {
        val prefs = recordsPrefs(context)
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            val dailyRaw = prefs.getString(KEY_DAILY, null)
            if (dailyRaw != null) {
                val arr = runCatching { JSONArray(dailyRaw) }.getOrDefault(JSONArray())
                val fixed = JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val date = o.optString("date").ifBlank { o.optString("day") }
                    if (date.isBlank()) continue
                    fixed.put(JSONObject().apply {
                        put("date", date)
                        put("count", o.optInt("count"))
                        put("listenedMs", o.optLong("listenedMs", o.optLong("ms")))
                        put("topArtist", o.optString("topArtist"))
                        put("topSong", o.optString("topSong"))
                    })
                }
                prefs.edit().putString(KEY_DAILY, fixed.toString()).apply()
            }

            val arr = runCatching { JSONArray(prefs.getString(KEY_RECORDS, null) ?: "[]") }
                .getOrDefault(JSONArray())
            val todayStart = dayStartMs(System.currentTimeMillis())
            val byDay = LinkedHashMap<String, LongArray>()
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optLong("time")
                if (t >= todayStart) {
                    kept.put(o)
                    continue
                }
                val slot = byDay.getOrPut(dayKey(t)) { LongArray(2) }
                slot[0] += 1L
                slot[1] += o.optLong("listenedMs")
            }
            for ((day, slot) in byDay) {
                bumpAggregates(prefs, context, day, slot[0].toInt(), slot[1])
            }

            prefs.edit()
                .putString(KEY_RECORDS, kept.toString())
                .putBoolean(KEY_MIGRATED, true)
                .apply()
        }
        ensureTotals(prefs)
    }

    /** 累计标量缺失时用每日汇总回填一次（老数据没有 totals 那一层）。 */
    private fun ensureTotals(prefs: android.content.SharedPreferences) {
        if (!prefs.getString(KEY_TOTALS, null).isNullOrBlank()) return
        val arr = runCatching { JSONArray(prefs.getString(KEY_DAILY, null) ?: "[]") }
            .getOrDefault(JSONArray())
        var count = 0L
        var ms = 0L
        var days = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            count += o.optInt("count").toLong()
            ms += o.optLong("listenedMs")
            days += 1L
        }
        if (count <= 0L && ms <= 0L) return
        prefs.edit().putString(KEY_TOTALS, JSONObject().apply {
            put("totalCount", count)
            put("totalListenedMs", ms)
            put("totalDays", days)
            put("firstAt", System.currentTimeMillis())
        }.toString()).apply()
    }

    /**
     * 结算一次后同时更新两层账：
     *  - daily：每日汇总（date / count / listenedMs / topArtist / topSong），留 730 天
     *  - totals：累计标量（totalCount / totalListenedMs / totalDays / firstAt），永不清空
     * 新收录一首 count +1；同曲合并只加时长增量，不重复计次。
     */
    private fun bumpAggregates(
        prefs: android.content.SharedPreferences,
        context: Context,
        day: String,
        songInc: Int,
        msInc: Long,
    ) {
        if (day.isBlank() || (songInc <= 0 && msInc <= 0L)) return
        // 当天最爱从明细表读（明细现在不清空，当天那批就在 SQLite 里）
        val dayStart = ListenRecordDb.dayStartOf(day)
        val dayRecords = if (dayStart > 0L) {
            runCatching {
                ListenRecordDb.listRange(context, dayStart, dayStart + ListenRecordDb.DAY_MS)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val topArtist = topOfRecords(dayRecords) { it.artist }
        val topSong = topOfRecords(dayRecords) { it.title }

        val arr = runCatching { JSONArray(prefs.getString(KEY_DAILY, null) ?: "[]") }
            .getOrDefault(JSONArray())
        val out = JSONArray()
        var hit = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!hit && o.optString("date") == day) {
                o.put("count", o.optInt("count") + songInc)
                o.put("listenedMs", o.optLong("listenedMs") + msInc)
                if (topArtist.isNotBlank()) o.put("topArtist", topArtist)
                if (topSong.isNotBlank()) o.put("topSong", topSong)
                out.put(o)
                hit = true
            } else {
                out.put(o)
            }
        }
        if (!hit) {
            out.put(JSONObject().apply {
                put("date", day)
                put("count", songInc)
                put("listenedMs", msInc)
                put("topArtist", topArtist)
                put("topSong", topSong)
            })
        }
        val sorted = (0 until out.length())
            .mapNotNull { out.optJSONObject(it) }
            .sortedByDescending { it.optString("date") }
        val trimmed = JSONArray()
        for (i in 0 until minOf(sorted.size, MAX_DAILY)) trimmed.put(sorted[i])
        prefs.edit().putString(KEY_DAILY, trimmed.toString()).apply()

        bumpTotals(prefs, songInc, msInc, isNewDay = !hit)
    }

    /** 累计标量：只增不减，不随日清、不随 300 条上限丢失。 */
    private fun bumpTotals(
        prefs: android.content.SharedPreferences,
        songInc: Int,
        msInc: Long,
        isNewDay: Boolean,
    ) {
        val o = runCatching { JSONObject(prefs.getString(KEY_TOTALS, null) ?: "{}") }
            .getOrDefault(JSONObject())
        o.put("totalCount", o.optLong("totalCount") + songInc)
        o.put("totalListenedMs", o.optLong("totalListenedMs") + msInc)
        o.put("totalDays", o.optLong("totalDays") + if (isNewDay) 1L else 0L)
        if (o.optLong("firstAt") <= 0L) o.put("firstAt", System.currentTimeMillis())
        prefs.edit().putString(KEY_TOTALS, o.toString()).apply()
    }

    /** 取数组里出现最多的字段值（当天最爱歌手 / 单曲）。 */
    private fun topOf(array: JSONArray, key: String): String {
        val counts = HashMap<String, Int>()
        for (i in 0 until array.length()) {
            val v = array.optJSONObject(i)?.optString(key).orEmpty()
            if (v.isBlank()) continue
            counts[v] = (counts[v] ?: 0) + 1
        }
        return counts.entries.maxByOrNull { it.value }?.key.orEmpty()
    }

    /** 取列表里出现最多的字段值（当天最爱歌手 / 单曲）。 */
    private fun topOfRecords(records: List<ListenRecord>, pick: (ListenRecord) -> String): String {
        if (records.isEmpty()) return ""
        val counts = HashMap<String, Int>()
        for (r in records) {
            val v = pick(r)
            if (v.isBlank()) continue
            counts[v] = (counts[v] ?: 0) + 1
        }
        return counts.entries.maxByOrNull { it.value }?.key.orEmpty()
    }

    private fun dayStartMs(epochMs: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = epochMs
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun dayKey(epochMs: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))

    private fun recordsEnabled(context: Context): Boolean =
        recordsPrefs(context).getBoolean(KEY_ENABLED, true)

    /**
     * 只认 root 或 SystemUI 进程写入。
     * 真机上 SystemUI 不是 uid 1000（澎湃 OS 里是普通应用 uid），
     * 所以按"调用 uid 是否属于 com.android.systemui"判断更可靠。
     */
    private fun isTrustedCaller(uid: Int): Boolean {
        if (uid == Process.ROOT_UID || uid == Process.SYSTEM_UID) return true
        val context = context ?: return false
        val packages = runCatching {
            context.packageManager.getPackagesForUid(uid)
        }.getOrNull() ?: return false
        return packages.any { it == "com.android.systemui" }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val TAG = "LiveStateProvider"
        private const val METHOD_PUBLISH = "publish_live"
        private const val METHOD_PUBLISH_PERF = "publish_perf"
        const val METHOD_AXIS_LOOKUP = "axis_lookup"

        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_PLAYING = "playing"
        const val KEY_PKG = "package"
        const val KEY_LYRIC = "lyric"
        const val KEY_NEXT = "next"
        const val KEY_SONG = "song"
        const val KEY_DURATION_MS = "duration_ms"
    const val KEY_POSITION_MS = "position_ms"
        const val KEY_UPDATED_AT = "updated_at"

        /** 歌词源运行态：当前注册上来的 provider / 它的播放器 / 变化时间（歌词源页自检用）。 */
        const val KEY_PROVIDER_PKG = "provider_pkg"
        const val KEY_PROVIDER_PLAYER = "provider_player"
        const val KEY_PROVIDER_AT = "provider_at"
        const val KEY_RECORDS = "records"
        const val KEY_ENABLED = "records_enabled"

        /** 记录列表所属日期（yyyy-MM-dd）。日清本身按每条 time 过滤，这个键只用于展示/排查。 */
        const val KEY_RECORDS_DAY = "records_day"

        /** 每日汇总 [{date,count,listenedMs,topArtist,topSong}]，留 730 天（两年）。 */
        const val KEY_DAILY = "daily"

        /** 累计标量 {totalCount,totalListenedMs,totalDays,firstAt}，永不清空。 */
        const val KEY_TOTALS = "totals"

        /** 老数据只迁移一次。 */
        const val KEY_MIGRATED = "records_migrated"

        /** 明细从 prefs 搬进 SQLite，只做一次；prefs 里的原数据保留（回滚用）。 */
        const val KEY_DB_MIGRATED = "records_db_migrated"

        const val KEY_FINALIZE = "finalize"
        const val KEY_F_STARTED_AT = "f_started_at"
        const val KEY_F_LISTENED_MS = "f_listened_ms"
        const val KEY_F_DURATION_MS = "f_duration_ms"

        /** 模块心跳（激活检测用）：帧标记、收到时刻、SystemUI 进程启动的 wall clock */
        const val KEY_HB = "hb"
        const val KEY_HB_AT = "hb_at"
        const val KEY_SYSUI_START = "sysui_started_at"

        /** 专辑封面：持有标签 + 落盘后的文件名（私有目录，覆盖写） */
        const val KEY_COVER_B64 = "cover_b64"
        const val KEY_COVER_TAG = "cover_tag"
        const val COVER_FILE = "live_cover.jpg"

        const val LIVE_PREFS = "live_state"
        const val RECORDS_PREFS = "listen_records"

        const val NOT_PLAYING = "未在播放"

        /** 当天明细上限：一天最多留 300 条（约 45KB），超出丢最旧。 */
        const val MAX_RECORDS = 300

        /** 每日汇总最多留 730 天（两年），每天一条，约 88KB。 */
        private const val MAX_DAILY = 730

        /** 短于 30 秒的音频不当成一首歌记。 */
        private const val MIN_TRACK_MS = 30_000L

        /** 一次播放最多按 4 分钟计（Last.fm 口径）。 */
        private const val MAX_COUNT_MS = 4 * 60 * 1000L

        /** 同一首歌两次出现在记录里、间隔小于 30 分钟，视为同一次播放。 */
        private const val SAME_TRACK_GAP_MS = 30 * 60 * 1000L

        fun livePrefs(context: Context) =
            context.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE)

        fun recordsPrefs(context: Context) =
            context.getSharedPreferences(RECORDS_PREFS, Context.MODE_PRIVATE)
    }
}