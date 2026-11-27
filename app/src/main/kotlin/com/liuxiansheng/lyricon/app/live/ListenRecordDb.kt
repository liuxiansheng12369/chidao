/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.app.live

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/**
 * 听歌明细表（SQLite）。
 *
 * 口径：明细**不清空**，按天归档保留 365 天；单日最多 300 条（超了只截当天，不影响别的日期）。
 * 换成 SQLite 的原因：一天 50 首 × 365 天约 1.8 万条，SharedPreferences 每次 apply 全量重写、
 * 读取整串解析，会拖主线程；按 `time` 建索引后按天查询是毫秒级。
 *
 * 每日汇总（daily）和累计标量（totals）体量小，继续留在 prefs。
 */
object ListenRecordDb {
    private const val DB_NAME = "listen_records.db"
    private const val DB_VERSION = 2
    private const val TABLE = "listen_records"

    /** 单日条数上限（超了只截当天，按 time 最新优先）。 */
    const val MAX_PER_DAY = 300

    /** 明细保留天数。 */
    const val RETAIN_DAYS = 365

    /** 同曲合并窗口：30 分钟内重复播放合成一条、时长累加。 */
    const val SAME_TRACK_GAP_MS = 30L * 60L * 1000L

    const val DAY_MS = 24L * 60L * 60L * 1000L

    /** 一次写入的结果：是否合并到已有条目、本次时长增量、落库那一行的 id。 */
    data class WriteResult(val merged: Boolean, val msDelta: Long, val id: Long)

    private class Helper(context: Context) : SQLiteOpenHelper(
        context.applicationContext, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "title TEXT NOT NULL," +
                    "artist TEXT," +
                    "pkg TEXT," +
                    "time INTEGER NOT NULL," +
                    "lastTime INTEGER NOT NULL," +
                    "listenedMs INTEGER NOT NULL DEFAULT 0," +
                    "durationMs INTEGER NOT NULL DEFAULT 0," +
                    "cover TEXT)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_listen_time ON $TABLE(time)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 → v2：补封面文件名列（老记录留空，前端退回色块）
            if (oldVersion < 2) {
                runCatching { db.execSQL("ALTER TABLE $TABLE ADD COLUMN cover TEXT") }
            }
        }
    }

    @Volatile
    private var helper: Helper? = null

    private fun open(context: Context): SQLiteDatabase {
        val existing = helper
        if (existing != null) return existing.writableDatabase
        synchronized(this) {
            val created = helper ?: Helper(context).also { helper = it }
            return created.writableDatabase
        }
    }

    private val COLUMNS = arrayOf(
        "title", "artist", "pkg", "time", "lastTime", "listenedMs", "durationMs", "cover"
    )

    /**
     * 写入一条（30 分钟内同曲合并）。
     * @param time 这条记录归属的播放起点，决定它落在哪一天
     * @param now  本次结算时刻，合并窗口按它算
     */
    fun write(
        context: Context,
        title: String,
        artist: String,
        pkg: String,
        time: Long,
        listenedMs: Long,
        durationMs: Long,
        now: Long,
    ): WriteResult {
        val db = open(context)
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT id, listenedMs FROM $TABLE WHERE title = ? AND artist = ? AND lastTime >= ? " +
                    "ORDER BY lastTime DESC LIMIT 1",
                arrayOf(title, artist, (now - SAME_TRACK_GAP_MS).toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val before = c.getLong(1)
                    // 位置回调偶尔超过曲长，合并后仍按曲长封顶
                    val after = if (durationMs > 0L) minOf(before + listenedMs, durationMs) else before + listenedMs
                    val cv = ContentValues().apply {
                        put("time", now)
                        put("lastTime", now)
                        put("listenedMs", after)
                        put("durationMs", durationMs)
                        if (pkg.isNotBlank()) put("pkg", pkg)
                    }
                    db.update(TABLE, cv, "id = ?", arrayOf(id.toString()))
                    db.setTransactionSuccessful()
                    return WriteResult(true, after - before, id)
                }
            }
            val cv = ContentValues().apply {
                put("title", title)
                put("artist", artist)
                put("pkg", pkg)
                put("time", time)
                put("lastTime", now)
                put("listenedMs", listenedMs)
                put("durationMs", durationMs)
            }
            val rowId = db.insert(TABLE, null, cv)
            db.setTransactionSuccessful()
            return WriteResult(false, listenedMs, rowId)
        } finally {
            db.endTransaction()
        }
    }

    /** 给某条记录挂封面文件名（存在 filesDir/covers 下）。 */
    fun setCover(context: Context, id: Long, cover: String) {
        if (id <= 0 || cover.isBlank()) return
        runCatching {
            open(context).update(
                TABLE,
                ContentValues().apply { put("cover", cover) },
                "id = ?",
                arrayOf(id.toString()),
            )
        }
    }

    /** 全量封面文件名（清理孤儿文件用）。 */
    fun coverNames(context: Context): Set<String> {
        val out = HashSet<String>()
        runCatching {
            open(context).rawQuery("SELECT cover FROM $TABLE WHERE cover IS NOT NULL AND cover != ''", null)
                .use { c -> while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { out.add(it) } }
        }
        return out
    }

    fun listAll(context: Context): List<ListenRecord> = query(context, null, null)

    fun listSince(context: Context, fromMs: Long): List<ListenRecord> = query(context, fromMs, null)

    fun listRange(context: Context, fromMs: Long, toMs: Long): List<ListenRecord> =
        query(context, fromMs, toMs)

    fun countAll(context: Context): Int = count(context, null, null)

    private fun count(context: Context, fromMs: Long?, toMs: Long?): Int {
        val where = buildWhere(fromMs, toMs)
        open(context).rawQuery(
            "SELECT COUNT(*) FROM $TABLE" + if (where.isBlank()) "" else " WHERE $where", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun query(context: Context, fromMs: Long?, toMs: Long?): List<ListenRecord> {
        val where = buildWhere(fromMs, toMs)
        val out = ArrayList<ListenRecord>()
        open(context).query(
            TABLE, COLUMNS, if (where.isBlank()) null else where, null, null, null, "time DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0) ?: continue
                if (title.isBlank()) continue
                out.add(
                    ListenRecord(
                        title = title,
                        artist = c.getString(1).orEmpty(),
                        packageName = c.getString(2).orEmpty(),
                        time = c.getLong(3),
                        lastTime = c.getLong(4),
                        listenedMs = c.getLong(5),
                        durationMs = c.getLong(6),
                        cover = c.getString(7).orEmpty(),
                    )
                )
            }
        }
        return out
    }

    private fun buildWhere(fromMs: Long?, toMs: Long?): String {
        val parts = ArrayList<String>(2)
        if (fromMs != null) parts.add("time >= $fromMs")
        if (toMs != null) parts.add("time < $toMs")
        return parts.joinToString(" AND ")
    }

    /** 删单条（按"歌名 + 歌手 + 播放时间"定位）。 */
    fun deleteOne(context: Context, title: String, artist: String, time: Long): Int =
        open(context).delete(
            TABLE, "title = ? AND artist = ? AND time = ?",
            arrayOf(title, artist, time.toString())
        )

    /** 删某一天的全部明细（清当天）。 */
    fun deleteRange(context: Context, fromMs: Long, toMs: Long): Int =
        open(context).delete(TABLE, "time >= ? AND time < ?", arrayOf(fromMs.toString(), toMs.toString()))

    /** 清空全部明细（累计不动，由调用方决定要不要连累计一起清）。 */
    fun clear(context: Context): Int = open(context).delete(TABLE, null, null)

    /** 按天整段清理：早于 [beforeMs] 的整条删掉（保留策略，不是按条挤）。 */
    fun prune(context: Context, beforeMs: Long): Int =
        open(context).delete(TABLE, "time < ?", arrayOf(beforeMs.toString()))

    /** 单日裁剪：只截这一天超出 [MAX_PER_DAY] 的旧条目，其他日期不动。 */
    fun trimDay(context: Context, dayStartMs: Long): Int {
        val dayEndMs = dayStartMs + DAY_MS
        val args = arrayOf(
            dayStartMs.toString(), dayEndMs.toString(),
            dayStartMs.toString(), dayEndMs.toString(),
        )
        val db = open(context)
        db.execSQL(
            "DELETE FROM $TABLE WHERE time >= ? AND time < ? AND id NOT IN (" +
                "SELECT id FROM $TABLE WHERE time >= ? AND time < ? ORDER BY time DESC LIMIT $MAX_PER_DAY)",
            args
        )
        return 0
    }

    /** 老数据一次性导入（不合并，按原样插）。 */
    fun importLegacy(context: Context, records: List<ListenRecord>): Int {
        if (records.isEmpty()) return 0
        val db = open(context)
        var inserted = 0
        db.beginTransaction()
        try {
            for (r in records) {
                if (r.title.isBlank()) continue
                val cv = ContentValues().apply {
                    put("title", r.title)
                    put("artist", r.artist)
                    put("pkg", r.packageName)
                    put("time", r.time)
                    put("lastTime", r.lastTime)
                    put("listenedMs", r.listenedMs)
                    put("durationMs", r.durationMs)
                    if (r.cover.isNotBlank()) put("cover", r.cover)
                }
                db.insert(TABLE, null, cv)
                inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    /** 本地日期 key（yyyy-MM-dd）→ 那天 0 点毫秒。 */
    fun dayStartOf(dayKey: String): Long {
        val parts = dayKey.split("-")
        if (parts.size != 3) return 0L
        val y = parts[0].toIntOrNull() ?: return 0L
        val m = parts[1].toIntOrNull() ?: return 0L
        val d = parts[2].toIntOrNull() ?: return 0L
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, m - 1)
            set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
