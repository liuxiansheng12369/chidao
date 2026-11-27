/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 *
 * SourceUsage：记录每个歌词源最近一次真正推词的时间与对应播放器。
 * 歌词源页只能拿到 SystemUI 侧报上来的"当前那一个源"，看不到全部连接列表，
 * 所以这里用"最近用过"的历史兜住其余源的状态：谁刚推过、谁一直没动静。
 */

package com.liuxiansheng.lyricon.app.lyricsource

import android.content.Context
import com.liuxiansheng.lyricon.app.util.MasterPrefs
import org.json.JSONObject

object SourceUsage {

    private const val KEY = "lyric_source_usage_v1"
    private const val MAX_ENTRIES = 30
    /** 同一源在这段时间内重复上报只记一次，避免每 2 秒写一次盘。 */
    const val THROTTLE_MS = 30_000L

    data class Entry(val lastSeenMs: Long, val playerPackage: String)

    /** 读出全部记录；坏数据一律当空表，绝不让页面崩。 */
    fun snapshot(context: Context): Map<String, Entry> {
        val raw = MasterPrefs.getString(context, KEY, "") ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val map = LinkedHashMap<String, Entry>()
            o.keys().forEach { pkg ->
                val e = o.optJSONObject(pkg) ?: return@forEach
                val t = e.optLong("t", 0L)
                if (t > 0L) map[pkg] = Entry(t, e.optString("player"))
            }
            map
        }.getOrDefault(emptyMap())
    }

    /** 记一次"这个源刚推过词"。 */
    fun recordActive(
        context: Context,
        providerPackage: String,
        playerPackage: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (providerPackage.isBlank()) return
        val map = LinkedHashMap(snapshot(context))
        val last = map[providerPackage]?.lastSeenMs ?: 0L
        if (nowMs - last < THROTTLE_MS) return
        map[providerPackage] = Entry(nowMs, playerPackage)
        if (map.size > MAX_ENTRIES) {
            val kept = map.entries.sortedByDescending { it.value.lastSeenMs }.take(MAX_ENTRIES)
            map.clear()
            kept.forEach { map[it.key] = it.value }
        }
        val o = JSONObject()
        map.forEach { (pkg, e) ->
            o.put(pkg, JSONObject().put("t", e.lastSeenMs).put("player", e.playerPackage))
        }
        MasterPrefs.setString(context, KEY, o.toString())
    }

    /** 清空历史（导出诊断/换机时可重置）。 */
    fun clear(context: Context) {
        MasterPrefs.setString(context, KEY, "")
    }
}
