/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.core

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 网上搜到的一首歌。id 是各家自己的编号，取歌词时要用。
 */
data class WebSong(
    val source: String,        // "netease" / "qq" / "lrclib"
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/**
 * 一份歌词。
 * - [lines] 一定要有（行级文本，敲轴的基础）
 * - [wordTimes] 只有逐字源命中时才有值：外层是行，内层是这一行每个字（AxisTokenizer 粒度）的开始毫秒
 * - [hasWordSync] 标记这份歌词是不是真带逐字时间
 */
data class LyricDoc(
    val source: String,
    val lines: List<String>,
    val wordTimes: List<List<Long?>?>? = null,
    val hasWordSync: Boolean = false,
    /**
     * 行级时间：每行开头 [mm:ss.xx] 的毫秒。null 表示这一行没有时间（纯文本文档）。
     *
     * 之前这里没有这个字段，[clean] 把时间标签整段剥掉了，所以网易云 / QQ 搜回来的词
     * 只能从头手敲。现在原样留下，搜到的词能直接铺成行级轴。
     */
    val lineTimes: List<Long?>? = null,
    /** LRC 头部的 [offset:±毫秒]，没写就是 0。 */
    val offsetTag: Long = 0L,
) {
    /** 这份文档能不能直接当轴用：有逐字，或者至少有行首时间。 */
    val hasAnyTiming: Boolean get() = hasWordSync || lineTimes?.any { it != null } == true
}

/** 一个歌词源。以后加源 / 关源只动这一层，UI 不感知具体接口。 */
interface LyricSource {
    val name: String

    /** 按关键词（歌名，可能带歌手）+ 时长搜候选。durationSec <= 0 表示不知道时长。 */
    suspend fun search(keyword: String, durationSec: Long): List<WebSong>

    /** 用候选里的 id 取歌词。 */
    suspend fun fetch(id: String): LyricDoc?
}

/** 源被限流或维护中：本次跳过，不要重试。 */
private class SourceBlocked(val source: String, val retryAfterSec: Long) : Exception("source blocked: $source")

/**
 * 网上搜词 / 取词的门面。所有接口地址都收在本文件里，UI 层不许拼 URL。
 *
 * 主流程：网易云 → QQ 音乐（快、中文覆盖好）
 * 可选升级：LRCLIB（带 User-Agent 才能通；命中 hasWordSync 就能直接拿到逐字轴）
 */
object LyricSearch {

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 15_000

    /** 30 分钟内同一命中键不重复请求。 */
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    private const val UA = "Lyricon/1.4.5 (Android; io.github.liuxiansheng12369.chidao)"

    private val primarySources: List<LyricSource> = listOf(NeteaseSource, QQSource)
    private val wordSyncSource: LyricSource = LrclibSource

    /** 命中键 →（时间戳, 文档）。落在内存里，进程结束就清。 */
    private val docCache = ConcurrentHashMap<String, Pair<Long, LyricDoc>>()

    /** 被限流的源：源名 → 解封时间。 */
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    // ——— 对外 ———

    /** 主流程搜候选：先「歌名 歌手」，不理想再退回纯歌名；两家结果去重。 */
    suspend fun search(title: String, artist: String): List<WebSong> {
        val out = mutableListOf<WebSong>()
        val kw1 = listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
        val kw2 = title.trim()

        suspendSearchPrimary(kw1)?.let { out += it }
        if (out.isEmpty() && kw2.isNotBlank() && kw2 != kw1) {
            suspendSearchPrimary(kw2)?.let { out += it }
        }

        return out
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}|${it.durationMs / 1000}" }
    }

    /** 取行级歌词：按 source 分派，失败返回空。 */
    suspend fun fetchLyric(song: WebSong): List<String> = fetchDoc(song)?.lines ?: emptyList()

    suspend fun fetchDoc(song: WebSong): LyricDoc? {
        val source = sourceByName(song.source) ?: return null
        if (isBlocked(source.name)) return null
        val cacheKey = key(song.title, song.artist, song.durationMs / 1000)
        cached(cacheKey)?.let { return it }
        val doc = runCatchingSuspend { source.fetch(song.id) } ?: return null
        if (doc.lines.isEmpty()) return null
        docCache[cacheKey] = System.currentTimeMillis() to doc
        return doc
    }

    /**
     * 查在线逐字轴（LRCLIB）。命中 hasWordSync=true 才返回带 wordTimes 的文档。
     * 这是「可选升级」，不放在主流程，避免每次搜词都白等。
     */
    suspend fun fetchWordSync(title: String, artist: String, durationMs: Long): LyricDoc? {
        if (title.isBlank() || isBlocked(wordSyncSource.name)) return null
        val cacheKey = key(title, artist, durationMs / 1000)
        cached(cacheKey)?.let { return it.takeIf { d -> d.hasWordSync } }

        var doc: LyricDoc? = null
        if (durationMs > 0L) {
            // 精确查询带时长，最能避免串轴
            doc = runCatchingSuspend { LrclibSource.fetchExact(title, artist, durationMs) }
        }
        if (doc == null || doc.lines.isEmpty()) {
            doc = runCatchingSuspend { LrclibSource.fetchByTrack(title, artist) }
        }
        if (doc == null || doc.lines.isEmpty()) return null
        docCache[cacheKey] = System.currentTimeMillis() to doc
        return doc.takeIf { it.hasWordSync }
    }

    /** 清缓存（换歌重打时用）。 */
    fun clearCache() {
        docCache.clear()
    }

    // ——— 内部 ———

    private suspend fun suspendSearchPrimary(keyword: String): List<WebSong>? {
        for (source in primarySources) {
            if (isBlocked(source.name)) continue
            val hits = runCatchingSuspend { source.search(keyword, 0L) }
            if (!hits.isNullOrEmpty()) return hits
        }
        return null
    }

    private fun sourceByName(name: String): LyricSource? =
        primarySources.firstOrNull { it.name == name } ?: wordSyncSource.takeIf { it.name == name }

    private fun isBlocked(name: String): Boolean {
        val until = blockedUntil[name] ?: return false
        if (System.currentTimeMillis() >= until) {
            blockedUntil.remove(name)
            return false
        }
        return true
    }

    private fun markBlocked(source: String, retryAfterSec: Long) {
        val seconds = if (retryAfterSec > 0) retryAfterSec else 60L
        blockedUntil[source] = System.currentTimeMillis() + seconds * 1000L
    }

    private fun cached(cacheKey: String): LyricDoc? {
        val entry = docCache[cacheKey] ?: return null
        if (System.currentTimeMillis() - entry.first > CACHE_TTL_MS) {
            docCache.remove(cacheKey)
            return null
        }
        return entry.second
    }

    private fun key(title: String, artist: String, durationSec: Long): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}|$durationSec"

    /** 把限流异常吞成 null，并把源标记为本次跳过。 */
    private suspend inline fun <T> runCatchingSuspend(crossinline action: suspend () -> T): T? = try {
        action()
    } catch (e: SourceBlocked) {
        markBlocked(e.source, e.retryAfterSec)
        null
    } catch (e: Exception) {
        null
    }

    // ——— 网络 ———

    internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    internal class Response(val code: Int, val body: String, val retryAfterSec: Long)

    /** 统一出口：超时 15s、超时直接失败不重试、429/503 抛出交给门面跳过。 */
    internal fun get(url: String, referer: String?, sourceName: String): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            referer?.let { setRequestProperty("Referer", it) }
        }
        try {
            val code = conn.responseCode
            if (code == 429 || code == 503) {
                val retry = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 60L
                throw SourceBlocked(sourceName, retry)
            }
            if (code !in 200..299) return Response(code, "", 0L)
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            return Response(code, body, 0L)
        } finally {
            conn.disconnect()
        }
    }

    // ——— 清洗 ———

    private val timeTag = Regex("""\[\d{1,2}:\d{1,2}([.:]\d{1,3})?]""")
    private val metaLine = Regex(
        """^(作词|作曲|编曲|制作|出品|监制|混音|母带|录音|和声|吉他|贝斯|鼓|词|曲|演唱|原唱|翻唱|策划|统筹|发行|企划)\s*[:：]"""
    )

    /** 去时间标签、去元信息行、去空行，并反转义 HTML 实体。 */
    fun clean(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val text = unescape(raw)
        return text.split('\n')
            .asSequence()
            .map { it.replace(timeTag, "").trim() }
            .filter { it.isNotBlank() }
            .filterNot { metaLine.containsMatchIn(it) }
            .toList()
    }

    // ——— 保留时间的解析（在线词直接成轴的基础）———

    /** 一行里所有 `[mm:ss.xx]`，也认 `[mm:ss]`。 */
    private val timeTagAll = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** LRC 头部 `[offset:+100]` / `[offset:-200]`。 */
    private val offsetTagAll = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    /**
     * 一份带行级时间的歌词。
     * [times] 和 [lines] 一一对应，null 表示这行没有时间（纯文本行）。
     */
    data class ParsedLrc(
        val lines: List<String>,
        val times: List<Long?>,
        val offsetMs: Long = 0L,
    )

    /**
     * 解析 LRC，**保留**每行的开始毫秒。
     *
     * 和 [clean] 的区别只有一个：clean 把时间剥了（打轴时代码只需要文本），
     * 这里留下时间，于是"搜到的词"可以直接铺成行级轴，不用从头再敲一遍。
     * 一行有多个时间标签时（`[00:10.00][00:20.00]同一句`）按标准摊成多行，
     * 否则这种重复句会丢一半。
     */
    fun parseLrc(raw: String): ParsedLrc {
        if (raw.isBlank()) return ParsedLrc(emptyList(), emptyList(), 0L)
        val text = unescape(raw)
        val outLines = ArrayList<String>()
        val outTimes = ArrayList<Long?>()
        var offset = 0L
        for (line in text.split('\n')) {
            offsetTagAll.find(line)?.let { m ->
                offset = m.groupValues[1].toLongOrNull() ?: offset
            }
            val stamps = timeTagAll.findAll(line).map { toMs(it) }.toList()
            val body = line.replace(timeTagAll, "").trim()
            if (body.isBlank()) continue
            if (metaLine.containsMatchIn(body)) continue
            if (stamps.isEmpty()) {
                outLines += body
                outTimes += null
            } else {
                // 同一句挂多个时间：按时间先后各算一行
                stamps.forEach { t ->
                    outLines += body
                    outTimes += t
                }
            }
        }
        return ParsedLrc(outLines, outTimes, offset)
    }

    private fun toMs(m: MatchResult): Long {
        val mm = m.groupValues[1].toLongOrNull() ?: 0L
        val ss = m.groupValues[2].toLongOrNull() ?: 0L
        val frac = m.groupValues.getOrNull(3).orEmpty()
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> (frac.toLongOrNull() ?: 0L) * 100L
            2 -> (frac.toLongOrNull() ?: 0L) * 10L
            else -> frac.take(3).toLongOrNull() ?: 0L
        }
        return mm * 60_000L + ss * 1000L + fracMs
    }

    /** 歌词里常见的 HTML 转义，统一在这里解掉。 */
    internal fun unescape(raw: String): String = raw
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&#58;", ":")
        .replace("&#40;", "(")
        .replace("&#41;", ")")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
}

// ————————————————————————————————————————————————
// 源一：网易云音乐
// ————————————————————————————————————————————————

object NeteaseSource : LyricSource {

    private const val SEARCH = "https://music.163.com/api/search/get/web"
    private const val LYRIC = "https://music.163.com/api/song/lyric"
    private const val REFERER = "https://music.163.com/"

    override val name: String = "netease"

    override suspend fun search(keyword: String, durationSec: Long): List<WebSong> {
        val url = "$SEARCH?s=${LyricSearch.enc(keyword)}&type=1&limit=10"
        val body = LyricSearch.get(url, REFERER, name).body
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val out = mutableListOf<WebSong>()
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val artists = o.optJSONArray("artists")
            val artist = if (artists != null && artists.length() > 0) {
                artists.optJSONObject(0)?.optString("name").orEmpty()
            } else ""
            out += WebSong(
                source = name,
                id = o.optLong("id").toString(),
                title = o.optString("name").orEmpty(),
                artist = artist,
                album = o.optJSONObject("album")?.optString("name").orEmpty(),
                durationMs = o.optLong("duration"),
            )
        }
        return out
    }

    override suspend fun fetch(id: String): LyricDoc? {
        val url = "$LYRIC?id=$id&lv=1&kv=1&tv=-1"
        val body = LyricSearch.get(url, REFERER, name).body
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // 一、klyric：网易云的逐字轴（仿 QRC：行首 [起始,时长]，句中 (偏移,时长)）。
        // 这个字段一直随 kv=1 请求回来，以前直接扔掉了，能解出来就优先用它 —— 尤其省事。
        val kwRaw = json.optJSONObject("klyric")?.optString("lyric").orEmpty()
        val qrc = runCatching { QrcParser.parse(kwRaw) }.getOrNull()
        if (qrc != null && qrc.lines.isNotEmpty()) {
            return LyricDoc(
                source = name,
                lines = qrc.lines,
                wordTimes = qrc.wordTimes,
                hasWordSync = qrc.wordTimes != null,
                lineTimes = qrc.lineTimes,
            )
        }
        // 二、lrc：退成行级，但把每行时间留下来，直接能铺成轴
        val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val parsed = LyricSearch.parseLrc(lrc)
        if (parsed.lines.isEmpty()) return null
        return LyricDoc(
            source = name,
            lines = parsed.lines,
            lineTimes = parsed.times,
            offsetTag = parsed.offsetMs,
        )
    }
}

// ————————————————————————————————————————————————
// 源二：QQ 音乐
// ————————————————————————————————————————————————

object QQSource : LyricSource {

    private const val SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val LYRIC = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
    private const val REFERER = "https://y.qq.com/"

    override val name: String = "qq"

    override suspend fun search(keyword: String, durationSec: Long): List<WebSong> {
        val url = "$SEARCH?w=${LyricSearch.enc(keyword)}&format=json&n=10"
        val body = LyricSearch.get(url, REFERER, name).body
        val list = JSONObject(body).optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return emptyList()
        val out = mutableListOf<WebSong>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val singer = o.optJSONArray("singer")
            val artist = if (singer != null && singer.length() > 0) {
                singer.optJSONObject(0)?.optString("name").orEmpty()
            } else ""
            out += WebSong(
                source = name,
                id = o.optString("songmid").orEmpty(),
                title = o.optString("songname").orEmpty(),
                artist = artist,
                album = o.optString("albumname").orEmpty(),
                durationMs = o.optLong("interval") * 1000L,
            )
        }
        return out
    }

    override suspend fun fetch(id: String): LyricDoc? {
        if (id.isBlank()) return null
        val url = "$LYRIC?songmid=$id&format=json&nobase64=1"
        val body = LyricSearch.get(url, REFERER, name).body
        val raw = JSONObject(body).optString("lyric").orEmpty()
        // QQ 的 QRC（逐字）是加密的，要 QMC 解密才拿得到，代价太大；
        // 但普通 lrc 的时间标签是明文的，保留下来同样能直接成行级轴。
        val parsed = LyricSearch.parseLrc(raw)
        val lines = parsed.lines.ifEmpty { LyricSearch.clean(raw) }
        if (lines.isEmpty()) return null
        return LyricDoc(
            source = name,
            lines = lines,
            lineTimes = parsed.times.takeIf { it.any { t -> t != null } },
            offsetTag = parsed.offsetMs,
        )
    }
}

// ————————————————————————————————————————————————
// 源三：LRCLIB（独立歌词库，不要 key；必须带 User-Agent，否则被拒）
// ————————————————————————————————————————————————

object LrclibSource : LyricSource {

    private const val BASE = "https://lrclib.net"
    private const val SEARCH = "$BASE/api/search"
    private const val GET = "$BASE/api/get"

    override val name: String = "lrclib"

    override suspend fun search(keyword: String, durationSec: Long): List<WebSong> =
        searchByTrack(keyword, "")

    override suspend fun fetch(id: String): LyricDoc? {
        val url = "$GET?id=${LyricSearch.enc(id)}"
        val body = LyricSearch.get(url, null, name).body
        val records = runCatching { parseRecords(body) }.getOrDefault(emptyList())
        return records.firstOrNull()?.let { toDoc(it) }
    }

    /** 按歌名 + 歌手搜（时长在门面里再校验）。 */
    suspend fun searchByTrack(track: String, artist: String): List<WebSong> {
        val url = buildString {
            append(SEARCH).append("?track_name=").append(LyricSearch.enc(track))
            if (artist.isNotBlank()) append("&artist_name=").append(LyricSearch.enc(artist))
        }
        val body = LyricSearch.get(url, null, name).body
        return parseRecords(body).map {
            WebSong(
                source = name,
                id = it.id.toString(),
                title = it.trackName,
                artist = it.artistName,
                album = it.albumName,
                durationMs = (it.duration * 1000).toLong(),
            )
        }
    }

    /** 精确查询：带时长，能有效避免串轴。 */
    suspend fun fetchExact(track: String, artist: String, durationMs: Long): LyricDoc? {
        val url = buildString {
            append(GET)
            append("?track_name=").append(LyricSearch.enc(track))
            append("&artist_name=").append(LyricSearch.enc(artist))
            append("&duration=").append(durationMs / 1000L)
        }
        val body = LyricSearch.get(url, null, name).body
        val record = runCatching { parseSingle(body) }.getOrNull() ?: return null
        return toDoc(record)
    }

    /** 按歌名 + 歌手搜，取第一条带逐字的（或第一条）。 */
    suspend fun fetchByTrack(track: String, artist: String): LyricDoc? {
        val url = buildString {
            append(SEARCH).append("?track_name=").append(LyricSearch.enc(track))
            if (artist.isNotBlank()) append("&artist_name=").append(LyricSearch.enc(artist))
        }
        val body = LyricSearch.get(url, null, name).body
        val records = runCatching { parseRecords(body) }.getOrDefault(emptyList())
        return records.firstOrNull { it.hasWordSync }?.let { toDoc(it) }
            ?: records.firstOrNull()?.let { toDoc(it) }
    }

    // —— 记录解析 ——

    private data class Record(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val duration: Double,
        val instrumental: Boolean,
        val hasWordSync: Boolean,
        val syncedLyrics: String,
        val plainLyrics: String,
        val lyricsFile: String,
    )

    private fun parseRecords(body: String): List<Record> {
        if (body.isBlank()) return emptyList()
        val arr = runCatching { org.json.JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Record>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out += toRecord(it) }
        }
        return out
    }

    private fun parseSingle(body: String): Record? {
        if (body.isBlank()) return null
        return runCatching { toRecord(JSONObject(body)) }.getOrNull()
    }

    private fun toRecord(o: JSONObject): Record = Record(
        id = o.optLong("id"),
        trackName = o.optString("trackName").orEmpty(),
        artistName = o.optString("artistName").orEmpty(),
        albumName = o.optString("albumName").orEmpty(),
        duration = o.optDouble("duration", 0.0),
        instrumental = o.optBoolean("instrumental", false),
        hasWordSync = o.optBoolean("hasWordSync", false),
        syncedLyrics = o.optString("syncedLyrics").orEmpty(),
        plainLyrics = o.optString("plainLyrics").orEmpty(),
        lyricsFile = o.optString("lyricsfile").orEmpty(),
    )

    private fun toDoc(r: Record): LyricDoc {
        val wordParsed = if (r.hasWordSync && r.lyricsFile.isNotBlank()) {
            LrclibLyrics.parse(r.lyricsFile)
        } else {
            emptyList()
        }
        if (wordParsed.isNotEmpty()) {
            val texts = wordParsed.map { it.text }
            val times = wordParsed.map { line ->
                LrclibLyrics.toWordTimes(line.text, line.words)
            }
            val allAligned = times.any { it != null }
            return LyricDoc(
                source = name,
                lines = texts,
                wordTimes = if (allAligned) times else null,
                hasWordSync = allAligned,
                // 逐字文件里每句也带 start_ms，行级时间同样留一份
                lineTimes = wordParsed.map { it.startMs },
            )
        }
        // 没有逐字就退成行级：syncedLyrics 优先，其次 plainLyrics，都保留行时间
        val synced = LyricSearch.parseLrc(r.syncedLyrics)
        if (synced.lines.isNotEmpty()) {
            return LyricDoc(
                source = name,
                lines = synced.lines,
                lineTimes = synced.times.takeIf { t -> t.any { v -> v != null } },
                offsetTag = synced.offsetMs,
            )
        }
        val plain = LyricSearch.parseLrc(r.plainLyrics)
        return LyricDoc(
            source = name,
            lines = plain.lines,
            lineTimes = plain.times.takeIf { t -> t.any { v -> v != null } },
        )
    }
}

// ————————————————————————————————————————————————
// lyricsfile（LRCLIB 的逐字文件）解析
// 结构（实测）：
//   version: '1.0'
//   metadata:
//     title / artist / album / duration_ms / instrumental / language
//   lines:
//   - text: 一句歌词
//     start_ms: 620
//     end_ms: 3170
//     words:
//     - text: 'The '
//       start_ms: 620
// ————————————————————————————————————————————————

object LrclibLyrics {

    data class ParsedLine(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val words: List<Pair<String, Long>>,   // (词文本, 开始毫秒)
    )

    fun parse(yaml: String): List<ParsedLine> {
        if (yaml.isBlank()) return emptyList()
        val out = mutableListOf<ParsedLine>()
        var curText: String? = null
        var curStart = 0L
        var curEnd = 0L
        val words = mutableListOf<Pair<String, Long>>()
        var inLines = false
        var inWords = false
        var wordText: String? = null
        var wordStart = 0L

        fun flushWord() {
            val t = wordText
            if (t != null) words += t to wordStart
            wordText = null
            wordStart = 0L
        }

        fun flushLine() {
            flushWord()
            val t = curText
            if (t != null && t.isNotBlank()) {
                out += ParsedLine(t.trim(), curStart, curEnd, words.toList())
            }
            curText = null
            curStart = 0L
            curEnd = 0L
            words.clear()
            inWords = false
        }

        for (raw in yaml.lineSequence()) {
            if (raw.isBlank()) continue
            val indent = raw.length - raw.trimStart().length
            val body = raw.trimStart()

            when {
                !inLines -> if (body == "lines:") inLines = true

                // 行项：缩进 0 的 "- text:"
                indent == 0 && body.startsWith("- text:") -> {
                    flushLine()
                    curText = unquote(body.removePrefix("- text:").trim())
                }

                // 词项：缩进 >= 2 的 "- text:"
                body.startsWith("- text:") -> {
                    flushWord()
                    wordText = unquote(body.removePrefix("- text:").trim())
                }

                body == "words:" -> inWords = true

                body.startsWith("start_ms:") -> {
                    val v = body.removePrefix("start_ms:").trim().toLongOrNull() ?: 0L
                    if (indent >= 4 || inWords) wordStart = v else curStart = v
                }

                body.startsWith("end_ms:") -> {
                    if (indent < 4) curEnd = body.removePrefix("end_ms:").trim().toLongOrNull() ?: 0L
                }

                // 下一行 / 下一个顶层键
                body.startsWith("text:") || body.startsWith("metadata:") -> Unit
                indent == 0 && inWords -> inWords = false
            }
        }
        flushLine()
        return out
    }

    /**
     * 把词级时间对齐到 AxisTokenizer 的粒度（中文按字、英文按词）。
     * 对齐不了（文本不一致 / 尾部没时间）就返回 null，这行退化为行级。
     */
    fun toWordTimes(lineText: String, words: List<Pair<String, Long>>): List<Long?>? {
        if (words.isEmpty()) return null
        val tokens = AxisTokenizer.split(lineText)
        if (tokens.isEmpty()) return null

        // 源里的词常带尾空格（"The "），tokenizer 的 token 不带，两边都压掉空格再比
        val compact = words
            .map { it.first.replace(" ", "") to it.second }
            .filter { it.first.isNotEmpty() }
        if (compact.isEmpty()) return null

        val joined = compact.joinToString("") { it.first }
        val plain = lineText.replace(" ", "").trim()
        if (joined != plain) return null

        val out = ArrayList<Long?>(tokens.size)
        var pos = 0
        for (token in tokens) {
            var acc = 0
            var hit: Long? = null
            for ((w, start) in compact) {
                if (pos >= acc && pos < acc + w.length) {
                    hit = start
                    break
                }
                acc += w.length
            }
            out += hit
            pos += token.replace(" ", "").length
        }
        return if (out.any { it != null }) out else null
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length - 1).replace("''", "'")
        }
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length - 1)
        }
        return t
    }
}

// ————————————————————————————————————————————————
// QRC / 仿 QRC 逐字格式（网易云 klyric 就是这个格式）
//   一行：[1234,2900] 我(0,300)爱(300,280)你(580,400)
//   行首 [起始毫秒,持续毫秒]；句中每段是「文本(相对行首毫秒,持续毫秒)」
// 解不出来（格式变了 / 字段为空）就返回空，调用方退回行级 LRC，不影响主流程。
// ————————————————————————————————————————————————

object QrcParser {

    /** [wordTimes] 为 null 表示这份格式里没有字级时间，只能当行级用。 */
    data class QrcDoc(
        val lines: List<String>,
        val lineTimes: List<Long?>,
        val wordTimes: List<List<Long?>?>?,
    )

    private val seg = Regex("""\((-?\d+),(-?\d+)\)""")
    private val meta = Regex(
        """^(作词|作曲|编曲|制作|出品|监制|混音|母带|录音|和声|吉他|贝斯|鼓|词|曲|演唱|原唱|翻唱|策划|统筹|发行|企划)\s*[:：]"""
    )

    fun parse(raw: String): QrcDoc {
        if (raw.isBlank()) return QrcDoc(emptyList(), emptyList(), null)
        val outLines = ArrayList<String>()
        val outLineTimes = ArrayList<Long?>()
        val outWordTimes = ArrayList<List<Long?>?>()
        var anyWord = false
        val headStrict = Regex("""^\[(\d+),(\d+)]""")

        for (rawLine in raw.lineSequence()) {
            val t = rawLine.trim()
            if (t.isEmpty()) continue
            val m = headStrict.find(t) ?: continue
            val start = m.groupValues[1].toLongOrNull() ?: continue
            val body = t.substring(m.range.last + 1)
            if (body.isBlank()) continue

            // 切成「文本段 + 该段起始的绝对毫秒」
            val frags = ArrayList<Triple<String, Int, Long>>() // (文本, 起始字符, 绝对毫秒)
            var charPos = 0
            var cursor = 0
            for (sm in seg.findAll(body)) {
                val textPart = body.substring(cursor, sm.range.first)
                if (textPart.isNotEmpty()) {
                    val off = sm.groupValues[1].toLongOrNull() ?: 0L
                    frags += Triple(textPart, charPos, (start + off).coerceAtLeast(0L))
                    charPos += textPart.length
                }
                cursor = sm.range.last + 1
            }
            val tail = body.substring(cursor)
            if (tail.isNotEmpty()) {
                frags += Triple(tail, charPos, start)
                charPos += tail.length
            }

            val joined = frags.joinToString("") { it.first }
            val text = joined.trim()
            if (text.isEmpty()) continue
            if (meta.containsMatchIn(text)) continue
            val leading = joined.length - joined.trimStart().length

            outLines += text
            outLineTimes += start

            val tokens = AxisTokenizer.splitWithRange(text)
            val times = ArrayList<Long?>(tokens.size)
            for (tk in tokens) {
                val pos = tk.start + leading
                val frag = frags.firstOrNull { pos >= it.second && pos < it.second + it.first.length }
                times += frag?.third
            }
            if (times.any { it != null }) anyWord = true
            outWordTimes += times
        }
        return QrcDoc(outLines, outLineTimes, if (anyWord) outWordTimes else null)
    }
}
