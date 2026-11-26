/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 */

package com.liuxiansheng.lyricon.hyper.root.manual

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.liuxiansheng.lyricon.hyper.root.utils.DiagnosticsLog
import com.liuxiansheng.lyricon.axis.core.ManualAxis
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * SystemUI 侧的轴镜像库（2.0.103）。
 *
 * 与 HyperLyrics Enhanced 的在线歌词缓存同构：**轴数据落在本进程自己的 sqlite 里**，
 * 播放时按 songKey 一次索引查询，不再每次换歌都读两个 json 文件、解析整份索引。
 * 目录里那份 json 镜像继续保留：它是 App 推过来的成品，也是首次导入 sqlite 的来源。
 *
 * 两张表：
 *   - axes(song_key, payload, line_count, updated_at, last_used)：轴正文 + 展示/统计字段；
 *   - misses(song_key, checked_at)：「这首没轴」的结论，跨 SystemUI 重启仍然有效。
 */
internal class AxisMirrorDb(context: Context, private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "ManualAxis"

    private val helper = object : SQLiteOpenHelper(
        context.applicationContext ?: context,
        File(dir, "axis_mirror.db").absolutePath,
        null,
        1,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE axes (song_key TEXT PRIMARY KEY, payload TEXT NOT NULL, " +
                    "line_count INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                    "last_used INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX idx_axes_last_used ON axes(last_used)")
            db.execSQL("CREATE TABLE misses (song_key TEXT PRIMARY KEY, checked_at INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 这张表是可重建的镜像：升级时直接丢掉，下次 reindex 从目录里的 json 补回来。
            db.execSQL("DROP TABLE IF EXISTS axes")
            db.execSQL("DROP TABLE IF EXISTS misses")
            onCreate(db)
        }
    }

    /** 按 key 取轴；命中会顺手记一次 last_used。 */
    fun get(songKey: String): ManualAxis? = runCatching {
        helper.readableDatabase.query(
            "axes", arrayOf("payload"), "song_key = ?", arrayOf(songKey), null, null, null,
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val payload = c.getString(0)
            json.decodeFromString<ManualAxis>(payload)
        }
    }.onFailure { e ->
        Log.w(tag, "读轴库失败 key=$songKey ${e.javaClass.simpleName}")
    }.getOrNull()?.also { touch(songKey) }

    /** 反射式 rollout：把目录里还没进库的轴补进来（首次启动、每次推送后各调一次）。 */
    fun reindex() {
        runCatching {
            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("axis-") && it.name.endsWith(".json") }
                ?: return
            if (files.isEmpty()) return
            val db = helper.writableDatabase
            var added = 0
            var refreshed = 0
            files.forEach { file ->
                val axis = runCatching {
                    json.decodeFromString<ManualAxis>(file.readText())
                }.getOrNull() ?: return@forEach
                val storedAt = storedUpdatedAt(db, axis.songKey)
                if (storedAt == null || storedAt != axis.createdAt) {
                    put(db, axis, file.readText(), axis.createdAt)
                    if (storedAt == null) added++ else refreshed++
                }
            }
            if (added > 0 || refreshed > 0) {
                DiagnosticsLog.i(tag, "轴库已对齐目录：新增 $added 条，更新 $refreshed 条")
            }
        }.onFailure { e ->
            Log.w(tag, "轴库对齐失败 ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun markMiss(songKey: String, atMs: Long) {
        runCatching {
            helper.writableDatabase.insertWithOnConflict(
                "misses", null,
                ContentValues().apply {
                    put("song_key", songKey)
                    put("checked_at", atMs)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            trimMisses()
        }
    }

    fun missAt(songKey: String): Long? = runCatching {
        helper.readableDatabase.query(
            "misses", arrayOf("checked_at"), "song_key = ?", arrayOf(songKey), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    fun clearMiss(songKey: String) {
        runCatching { helper.writableDatabase.delete("misses", "song_key = ?", arrayOf(songKey)) }
    }

    /** App 里删掉某一首：库里那条和「没轴」结论一起清掉，别让副本继续顶歌词。 */
    fun drop(songKey: String) {
        runCatching {
            helper.writableDatabase.delete("axes", "song_key = ?", arrayOf(songKey))
            helper.writableDatabase.delete("misses", "song_key = ?", arrayOf(songKey))
        }
    }

    /** 新轴写盘后整表清一次「没轴」结论，轴表本身不动。 */
    fun clearMisses() {
        runCatching { helper.writableDatabase.execSQL("DELETE FROM misses") }
    }

    /** 进程刚起时把库里还有效的 miss 结论一次性载入内存，免得同一个空窗反复查。 */
    fun missesSince(atMs: Long): List<Pair<String, Long>> = runCatching {
        val out = ArrayList<Pair<String, Long>>()
        helper.readableDatabase.query(
            "misses", arrayOf("song_key", "checked_at"), "checked_at >= ?", arrayOf(atMs.toString()),
            null, null, null,
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getLong(1)
        }
        out
    }.getOrDefault(emptyList())

    fun count(): Int = runCatching {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM axes", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }.getOrDefault(0)

    /** 上限：删最久没用到的，不整表清空。 */
    fun trim(max: Int) {
        if (max <= 0) return
        runCatching {
            val total = count()
            if (total <= max) return
            helper.writableDatabase.execSQL(
                "DELETE FROM axes WHERE song_key IN (" +
                    "SELECT song_key FROM axes ORDER BY last_used ASC LIMIT ?)",
                arrayOf<Any>(total - max),
            )
        }
    }

    fun clear() {
        runCatching {
            helper.writableDatabase.execSQL("DELETE FROM axes")
            helper.writableDatabase.execSQL("DELETE FROM misses")
        }
    }

    private fun touch(songKey: String) {
        runCatching {
            helper.writableDatabase.execSQL(
                "UPDATE axes SET last_used = ? WHERE song_key = ?",
                arrayOf<Any>(System.currentTimeMillis(), songKey),
            )
        }
    }

    private fun put(db: SQLiteDatabase, axis: ManualAxis, payload: String, updatedAt: Long) {
        db.insertWithOnConflict(
            "axes", null,
            ContentValues().apply {
                put("song_key", axis.songKey)
                put("payload", payload)
                put("line_count", axis.lines.size)
                put("updated_at", updatedAt)
                put("last_used", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun storedUpdatedAt(db: SQLiteDatabase, songKey: String): Long? =
        db.query("axes", arrayOf("updated_at"), "song_key = ?", arrayOf(songKey), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** miss 表只留最近 2000 条，防止无轴歌曲无限堆积。 */
    private fun trimMisses() {
        runCatching {
            helper.writableDatabase.execSQL(
                "DELETE FROM misses WHERE song_key NOT IN (" +
                    "SELECT song_key FROM misses ORDER BY checked_at DESC LIMIT 2000)"
            )
        }
    }
}
