/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 岛侧唯一的读取入口。
 *
 * 命中顺序：播放器/Provider 解析出的歌名 + 歌手 + 时长 -> 换算命中键 -> 查本地人工轴。
 * 查到就整首改用人工轴，查不到再回落到原来的歌词源。
 *
 * 调用点必须放在 IslandSlotStyleAssembler 那两个签名缓存 early return 之前，否则第一帧不生效。
 * 日志统一走 [Log.i]，tag 固定 ManualAxis，便于 logcat 过滤。
 */
object ManualAxisResolver {

    private const val TAG = "ManualAxis"

    /**
     * 读取根目录覆盖。
     *
     * SystemUI 进程读不到 App 私有目录，所以 App 保存轴时会把文件推一份过去，
     * SystemUI 存在自己的目录里，并把这里指过去 —— 之后播放直接读盘，不需要跨进程叫醒 App。
     * App 自己（打轴器、设置页）不设，仍读自己的 filesDir/lyrics/manual。
     */
    @Volatile
    private var rootOverride: java.io.File? = null

    fun setRootOverride(dir: java.io.File?) {
        rootOverride = dir
    }

    /** 只读存储目录，和打轴器写盘用的是同一处。 */
    fun store(context: Context): AxisStore =
        AxisStore(rootOverride ?: File(context.filesDir, "lyrics/manual"))

    /** 已经拿到命中键时直接查。 */
    fun find(context: Context, songKey: String): ManualAxis? {
        val axis = runCatching { store(context).load(songKey) }.getOrNull()
        if (axis != null) {
            Log.i(TAG, "hit key=$songKey lines=${axis.lines.size} lead=${axis.leadMs}")
            return axis
        }
        // 精确键没命中时再做一次容差匹配，主要救三种情况：
        // 1) 播放器不提供时长，命中键第三段是 0，跟敲轴时存的真实秒数永远对不上；
        // 2) 播放器给的时长跟搜索来的时长差一两秒；
        // 3) 歌手写的不一样（各家源、翻唱、大小写），但确实是同一首。
        // 放宽的是时长和歌手，歌名（括号前的正文）必须完全一致，不然会串到别的歌上。
        val loose = findLoose(context, songKey)
        if (loose != null) {
            Log.i(
                TAG,
                "hit(容差) key=$songKey 实际=${loose.key} delta=${loose.deltaMs}ms lines=${loose.axis.lines.size}"
            )
            return loose.axis
        }
        // 第三层兜底：歌名 + 歌手都对得上就认，完全不看时长。
        // 播放器报的时长不可靠 —— 同一首《情网》实测报过 243 / 246 / 271 / 0，
        // 271 那种是切歌瞬间还挂着上一首的时长。歌名和歌手都一致时，
        // 差几十秒只可能是播放器乱报，不是别的版本（真不同版本歌手通常也不同）。
        val byNameArtist = findByTitleArtist(context, songKey)
        if (byNameArtist != null) {
            Log.i(TAG, "hit(歌名+歌手) key=$songKey 实际=${byNameArtist.key} 时长=${byNameArtist.axis.durationMs}ms")
            return byNameArtist.axis
        }
        // 第四层：宽松兜底。provider 只报歌名（歌手空）、歌名带标点或 feat 尾巴时，前面三层都会 miss。
        val fuzzy = findFuzzy(context, songKey)
        if (fuzzy != null) {
            Log.i(TAG, "hit(宽松) key=$songKey 实际=${fuzzy.key} 时长=${fuzzy.axis.durationMs}ms")
            return fuzzy.axis
        }
        Log.i(TAG, "miss key=$songKey")
        return null
    }

    private class NameArtistHit(
        val key: String,
        val axis: ManualAxis,
    )

    /** 命中键拆成 歌名 / 歌手 / 秒数（歌名里不会出现竖线，从右往左拆）。 */
    private fun splitKey(songKey: String): Triple<String, String, Long>? {
        val last = songKey.lastIndexOf('|')
        if (last <= 0) return null
        val secondLast = songKey.lastIndexOf('|', last - 1)
        if (secondLast <= 0) return null
        val title = songKey.substring(0, secondLast)
        val artist = songKey.substring(secondLast + 1, last)
        val sec = songKey.substring(last + 1).toLongOrNull() ?: 0L
        return Triple(title, artist, sec)
    }

    /**
     * 第四层宽松兜底。
     *
     * 场景：provider 只报了歌名（歌手为空），或歌名带标点、feat.、连字符而轴上写法不同，
     * 前三层全部 miss，但本地明显存着这首歌的轴。
     *
     * 规矩：**歌名必须宽松一致**（去掉括号内容、空格、标点、连接符后相同），
     * 歌手与时长只用来排序和设防 —— 歌手完全对不上、时长又差 20 秒以上的，宁可不用也不能串歌。
     */
    private fun findFuzzy(context: Context, songKey: String): NameArtistHit? {
        val (title, artist, sec) = splitKey(songKey) ?: return null
        if (title.isBlank()) return null
        val entries = runCatching { store(context).loadIndex() }.getOrNull() ?: return null
        if (entries.isEmpty()) return null
        val wantTitle = looseText(title)
        if (wantTitle.isEmpty()) return null
        var best: AxisIndexEntry? = null
        var bestScore = Int.MIN_VALUE
        var bestCount = 0
        val wantArtist = looseText(artist)
        for (e in entries) {
            if (looseText(e.title) != wantTitle) continue
            val hasArtist = looseText(e.artist)
            val titleExact = SongKey.normalizeTitle(title) == SongKey.normalizeTitle(e.title)
            val artistExact = wantArtist.isNotEmpty() && wantArtist == hasArtist
            val durDelta = if (sec > 0L && e.durationMs > 0L) {
                kotlin.math.abs(e.durationMs - sec * 1000L)
            } else {
                null
            }
            val durClose = durDelta != null && durDelta <= 5_000L
            // 证据门槛：光"歌名像"不算命中。必须至少有一条硬证据 ——
            // a) 歌名精确一致 + 歌手也对得上；b) 时长在 5 秒内。
            // 两个都没有（provider 只报了个歌名、既没歌手也没时长）就跳过：
            // 宁可岛上空着，也不能套上别的版本/别的歌。
            if (!((titleExact && artistExact) || durClose)) continue
            var score = 100
            score += when {
                wantArtist.isEmpty() -> 0
                artistExact -> 40
                hasArtist.isNotEmpty() && (wantArtist.contains(hasArtist) || hasArtist.contains(wantArtist)) -> 25
                else -> -20
            }
            if (durDelta != null) {
                if (durDelta > 20_000L) continue
                score -= (durDelta / 1000L).toInt()
            }
            if (score > bestScore) {
                bestScore = score
                best = e
                bestCount = 1
            } else if (score == bestScore) {
                bestCount++
            }
        }
        val entry = best ?: return null
        // 并列最高分说明无法区分（例如同名不同歌手、又没有时长），此时宁可不认也不串歌
        if (bestScore < 95 || bestCount > 1) return null
        val axis = runCatching { store(context).load(entry.songKey) }.getOrNull() ?: return null
        return NameArtistHit(entry.songKey, axis)
    }

    /** 宽松比较用：在原有归一化（去括号、去空格、转小写）之上，再去掉标点与连接符。 */
    private fun looseText(raw: String): String =
        SongKey.normalizeTitle(raw).replace(Regex("""[\s\-—–·•.,，。、|/\\!！?？:：;；'"“”‘’()（）\[\]【】_]"""), "")

    private fun findByTitleArtist(context: Context, songKey: String): NameArtistHit? {
        val (title, artist, _) = splitKey(songKey) ?: return null
        if (title.isBlank() || artist.isBlank()) return null
        val entries = runCatching { store(context).loadIndex() }.getOrNull() ?: return null
        val entry = entries.firstOrNull {
            SongKey.normalizeTitle(it.title) == title &&
                SongKey.normalizeArtist(it.artist) == artist
        } ?: return null
        val axis = runCatching { store(context).load(entry.songKey) }.getOrNull() ?: return null
        return NameArtistHit(entry.songKey, axis)
    }

    private class LooseHit(
        val key: String,
        val axis: ManualAxis,
        val deltaMs: Long,
    )

    private fun findLoose(context: Context, songKey: String): LooseHit? {
        val entries = runCatching { store(context).loadIndex() }.getOrNull() ?: return null
        val entry = pickLooseEntry(entries, songKey) ?: return null
        val axis = runCatching { store(context).load(entry.songKey) }.getOrNull() ?: return null
        val delta = durationDelta(songKey, entry.durationMs)
        return LooseHit(entry.songKey, axis, delta)
    }

    /**
     * 容差匹配的纯逻辑部分（不碰磁盘，方便单测）。
     *
     * 规则：括号前面的歌名必须完全一致；歌手不参与判定；
     * 都有时长时要求相差不超过 5 秒，多条命中取最接近的那条。
     *
     * 阈值从 2 秒放宽到 5 秒，是因为播放器报上来的时长在就绪前后会来回跳
     * （实测同一首报过 243 / 246，差 2.8 秒），2 秒的门槛会把用户自己敲好的轴判成"没有"。
     * 5 秒仍能挡住 DJ 版 / 舒缓版这类真正差十几秒的版本串用。
     */
    internal fun pickLooseEntry(entries: List<AxisIndexEntry>, songKey: String): AxisIndexEntry? {
        val last = songKey.lastIndexOf('|')
        if (last <= 0) return null
        val secondLast = songKey.lastIndexOf('|', last - 1)
        if (secondLast <= 0) return null
        val title = songKey.substring(0, secondLast)
        var best: AxisIndexEntry? = null
        var bestDelta = Long.MAX_VALUE
        for (entry in entries) {
            if (SongKey.normalizeTitle(entry.title) != title) continue
            val delta = durationDelta(songKey, entry.durationMs)
            if (delta > 5000L) continue
            if (delta < bestDelta) {
                best = entry
                bestDelta = delta
            }
        }
        return best
    }

    /** 命中键里的秒数与条目时长差多少毫秒；任一边没有时长返回 0。 */
    internal fun durationDelta(songKey: String, entryDurationMs: Long): Long {
        val last = songKey.lastIndexOf('|')
        if (last <= 0) return 0L
        val durSec = songKey.substring(last + 1).toLongOrNull() ?: 0L
        return if (durSec <= 0L || entryDurationMs <= 0L) {
            0L
        } else {
            kotlin.math.abs(entryDurationMs - durSec * 1000L)
        }
    }

    /** 只有歌名 / 歌手 / 总时长时，先换算命中键再查。 */
    fun resolve(context: Context, title: String, artist: String, durationMs: Long): ManualAxis? =
        find(context, SongKey.of(title, artist, durationMs))

    /** 已经保存过人工轴的曲目清单，给"最近打轴"用。 */
    fun recent(context: Context, limit: Int = 3): List<AxisIndexEntry> =
        runCatching { store(context).list().take(limit) }.getOrDefault(emptyList())

    /** 已打轴的条数，给主设置页那一行显示「已打轴 N 首」。 */
    fun summary(context: Context): Int =
        runCatching { store(context).loadIndex().size }.getOrDefault(0)
}
