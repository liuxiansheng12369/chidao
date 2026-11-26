/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

enum class Precision { CHAR, LINE }

@Serializable
data class AxisWord(
    val text: String,
    val beginMs: Long,
)

@Serializable
data class AxisLine(
    val i: Int,
    val text: String,
    val beginMs: Long,
    val endMs: Long,
    val words: List<AxisWord> = emptyList(),   // 逐字时间；为空表示只有行级
)

@Serializable
data class ManualAxis(
    val schema: Int = 2,
    val songKey: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val leadMs: Int,                                   // 保持 Int，别改 Long
    /** 整轴调值：播放时所有行/字统一平移这么多毫秒，负=提前，正=延后。打点时的记录不变。 */
    val offsetMs: Int = 0,
    val precision: Precision = Precision.CHAR,
    val createdAt: Long,
    val lines: List<AxisLine>,
    /**
     * 这条轴是谁做的：
     * - `manual` —— 人手打的，**任何在线轴都不许覆盖它**；
     * - `online` —— 在线词一键生成的，只当底子用，命中不到人工轴时才顶上。
     */
    val origin: String = ORIGIN_MANUAL,
    /** 在线轴的来源（netease / qq / lrclib），手打为空。 */
    val originSource: String = "",
    /** 在线轴的匹配度分（0~100），手打为 0。 */
    val originScore: Int = 0,
) {
    val isManual: Boolean get() = origin == ORIGIN_MANUAL

    companion object {
        const val ORIGIN_MANUAL = "manual"
        const val ORIGIN_ONLINE = "online"
    }
}

@Serializable
data class AxisIndexEntry(    val songKey: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val lineCount: Int,
    val charCount: Int = 0,
    val fileName: String,
    val updatedAt: Long,
    /** 这份轴怎么来的：手打 / 在线词自动铺。老索引里没这个字段，按手打算。 */
    val origin: String = ManualAxis.ORIGIN_MANUAL,
)

/**
 * 占位词识别。
 *
 * 播放器没词的时候不给空数组，而是塞一句「暂无歌词，请您欣赏」——
 * 它满足「有文本」，会让本地轴被当成"已经有推送词"挡在门外。
 * 这里统一按关键词判掉，判掉之后这首就算没词，本地轴照常顶上。
 */
object LyricPlaceholder {
    private val re = Regex(
        "暂无歌词|暂无|无歌词|没有歌词|请您欣赏|歌词加载中|正在加载歌词|歌词获取失败",
        RegexOption.IGNORE_CASE
    )

    fun isPlaceholder(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        return t.isEmpty() || re.containsMatchIn(t)
    }
}

/** 命中键：标题（去括号去空格小写）| 歌手（第一个）| 时长整秒。 */
object SongKey {
    private val bracket = Regex("""[（(【\[].*?[)）】\]]""")

    fun normalizeTitle(raw: String): String = raw
        .replace(bracket, "")
        .replace(" ", "")
        .replace("\u3000", "")
        .trim()
        .lowercase()

    fun normalizeArtist(raw: String): String = raw
        .split(",", "/", "&", "、", "，")
        .firstOrNull()
        ?.trim()
        ?.lowercase()
        .orEmpty()

    fun of(title: String, artist: String, durationMs: Long): String =
        "${normalizeTitle(title)}|${normalizeArtist(artist)}|${durationMs / 1000}"

    /**
     * 两个歌名是不是同一首。
     *
     * 判定：归一化后完全一样，或一方包含另一方。
     * 这样「做不到的承诺就不要对我说」和「做不到的承诺就不要对我说（舒缓版）」
     * 「某某（DJ版）」、少打一两个虚词都能认成同一首；
     * 但《做不到的承诺》和《做不到》这种只是前缀相近的也不会误合并（长度差太多时不在此列，
     * 包含关系仍可能命中，故调用方还需要歌手/时长兜底）。
     */
    fun sameSong(titleA: String, titleB: String): Boolean {
        val a = normalizeTitle(titleA)
        val b = normalizeTitle(titleB)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }
}

/** 中文按单字切，英文/数字按连续段成词。 */
object AxisTokenizer {
    /** 一个词连同它在原文里的字符区间，删字时按区间从原文里抠掉，才不会留半截。 */
    data class Token(val text: String, val start: Int, val end: Int)

    fun splitWithRange(text: String): List<Token> {
        val out = mutableListOf<Token>()
        val latin = StringBuilder()
        var latinStart = -1
        fun flush(end: Int) {
            if (latin.isNotEmpty()) {
                out += Token(latin.toString(), latinStart, end)
                latin.clear()
                latinStart = -1
            }
        }
        text.forEachIndexed { i, ch ->
            when {
                ch.code in 0x4E00..0x9FFF -> { flush(i); out += Token(ch.toString(), i, i + 1) }   // CJK 单字
                ch.isWhitespace() -> flush(i)
                else -> { if (latin.isEmpty()) latinStart = i; latin.append(ch) }
            }
        }
        flush(text.length)
        return out
    }

    fun split(text: String): List<String> = splitWithRange(text).map { it.text }
}

enum class IssueKind {
    NEGATIVE, OVER_DURATION, NOT_INCREASING, TOO_SHORT,
    CHAR_NOT_INCREASING, CHAR_TOO_SHORT,
}

data class AxisIssue(val index: Int, val kind: IssueKind, val message: String)

object AxisValidator {

    const val MIN_GAP_MS = 300L        // 行与行之间
    const val MIN_CHAR_GAP_MS = 80L    // 字与字之间

    /** 保存前的异常检查：不递增、间隔过短、越界。 */
    fun issues(lines: List<AxisLine>, durationMs: Long): List<AxisIssue> {
        val out = mutableListOf<AxisIssue>()
        lines.forEachIndexed { i, line ->
            when {
                line.beginMs < 0L ->
                    out += AxisIssue(i, IssueKind.NEGATIVE, "起始时间为负")
                line.beginMs > durationMs ->
                    out += AxisIssue(i, IssueKind.OVER_DURATION, "起始时间超出总时长")
                i > 0 && line.beginMs <= lines[i - 1].beginMs ->
                    out += AxisIssue(i, IssueKind.NOT_INCREASING, "没有比上一句晚")
                i > 0 && (line.beginMs - lines[i - 1].beginMs) < MIN_GAP_MS ->
                    out += AxisIssue(i, IssueKind.TOO_SHORT, "与上一句间隔太短")
            }
        }

        // —— 字级检查（只检查已有 words 的行）——
        lines.forEachIndexed { i, line ->
            if (line.words.size < 2) return@forEachIndexed
            if (line.beginMs < 0L) return@forEachIndexed      // 行本身已报过
            line.words.forEachIndexed { wi, word ->
                if (wi > 0 && word.beginMs <= line.words[wi - 1].beginMs) {
                    out += AxisIssue(i, IssueKind.CHAR_NOT_INCREASING, "第 ${wi + 1} 字没有比上一字晚")
                }
                if (wi > 0 && (word.beginMs - line.words[wi - 1].beginMs) < MIN_CHAR_GAP_MS) {
                    out += AxisIssue(i, IssueKind.CHAR_TOO_SHORT, "第 ${wi + 1} 字间隔太短")
                }
            }
            val last = line.words.last()
            if (last.beginMs > line.endMs) {
                out += AxisIssue(i, IssueKind.CHAR_TOO_SHORT, "行内末字晚于该行结束时间")
            }
        }
        return out
    }

    /**
     * 漏敲补齐：
     * 头部缺失按第一句已知时间往前推；中间缺失按前后均分；尾部缺失按平均句长往后推。
     */
    fun fillMissing(begins: List<Long?>, durationMs: Long): List<Long> {
        val n = begins.size
        if (n == 0) return emptyList()
        val work = begins.toMutableList()

        val firstKnown = work.indexOfFirst { it != null }
        if (firstKnown > 0) {
            val anchor = work[firstKnown]!!
            val step = (anchor / firstKnown).coerceAtLeast(1L)
            for (i in 0 until firstKnown) {
                work[i] = (anchor - step * (firstKnown - i)).coerceAtLeast(0L)
            }
        }

        var i = 0
        while (i < n) {
            if (work[i] == null) {
                val fromIdx = i - 1
                var toIdx = i
                while (toIdx < n && work[toIdx] == null) toIdx++
                if (fromIdx < 0 || toIdx >= n) {
                    i = toIdx
                    continue
                }
                val from = work[fromIdx]!!
                val to = work[toIdx]!!
                val gap = toIdx - fromIdx
                for (k in 1 until gap) {
                    work[fromIdx + k] = from + (to - from) * k / gap
                }
                i = toIdx
            } else {
                i++
            }
        }

        val lastKnown = work.indexOfLast { it != null }
        if (lastKnown >= 0 && lastKnown < n - 1) {
            val avg = if (lastKnown >= 1) {
                ((work[lastKnown]!! - work[0]!!) / lastKnown).coerceAtLeast(1000L)
            } else {
                3000L
            }
            for (k in lastKnown + 1 until n) {
                work[k] = (work[lastKnown]!! + avg * (k - lastKnown)).coerceAtMost(durationMs)
            }
        }

        return work.map { (it ?: 0L).coerceIn(0L, durationMs) }
    }

    /**
     * 字级补齐：把一行里的 null 填成时间。
     * 顺序：先补尾部 → 再补头部 → 最后补中间，三段不能颠倒，
     * 否则中间的均分会把尾部当成已知锚点算错。
     */
    fun fillWords(wordsDone: List<Long?>, lineBegin: Long, lineEnd: Long): List<Long> {
        val n = wordsDone.size
        if (n == 0) return emptyList()
        val work = wordsDone.toMutableList()

        // 整行都没敲：行内均分
        if (work.all { it == null }) {
            val span = (lineEnd - lineBegin).coerceAtLeast(n.toLong() * MIN_CHAR_GAP_MS)
            return (0 until n).map { lineBegin + span * it / n }
        }

        val lastKnown = work.indexOfLast { it != null }
        // 尾部
        if (lastKnown < n - 1) {
            val firstKnown = work.indexOfFirst { it != null }
            val avg = if (lastKnown > firstKnown) {
                ((work[lastKnown]!! - work[firstKnown]!!) / (lastKnown - firstKnown)).coerceAtLeast(MIN_CHAR_GAP_MS)
            } else {
                ((lineEnd - work[lastKnown]!!) / (n - lastKnown)).coerceAtLeast(MIN_CHAR_GAP_MS)
            }
            for (k in lastKnown + 1 until n) work[k] = work[lastKnown]!! + avg * (k - lastKnown)
        }

        // 头部
        val firstKnown = work.indexOfFirst { it != null }
        if (firstKnown > 0) {
            val avg = ((work[lastKnown]!! - work[firstKnown]!!) / (lastKnown - firstKnown).coerceAtLeast(1))
                .coerceAtLeast(MIN_CHAR_GAP_MS)
            for (k in 0 until firstKnown) {
                work[k] = (work[firstKnown]!! - avg * (firstKnown - k)).coerceAtLeast(lineBegin)
            }
        }

        // 中间
        var i = 0
        while (i < n) {
            if (work[i] == null) {
                var j = i
                while (j < n && work[j] == null) j++
                if (i > 0 && j < n) {
                    val from = work[i - 1]!!
                    val to = work[j]!!
                    for (k in i until j) work[k] = from + (to - from) * (k - i + 1) / (j - i + 1)
                }
                i = j
            } else i++
        }

        return work.map { (it ?: lineBegin).coerceIn(lineBegin, lineEnd) }
    }

    /** 由起始时间推出每行的结束时间：下一行起点，最后一行收在总时长。 */
    fun toAxisLines(texts: List<String>, begins: List<Long>, durationMs: Long): List<AxisLine> {
        if (texts.isEmpty()) return emptyList()
        return texts.indices.map { i ->
            val begin = begins[i]
            val end = if (i < begins.size - 1) begins[i + 1] else durationMs
            AxisLine(i = i, text = texts[i], beginMs = begin, endMs = end.coerceAtLeast(begin))
        }
    }

    /** 带逐字时间的版本：wordsByLine 里 null 表示这行没敲过字，由 fillWords 均分补上。 */
    fun toAxisLines(
        texts: List<String>,
        begins: List<Long>,
        durationMs: Long,
        wordsByLine: List<List<Long?>?>,         // 每行每字的时间；行级 null=整行没敲，字级 null=该字没敲
    ): List<AxisLine> = texts.indices.map { i ->
        val begin = begins[i]
        val end = if (i < begins.size - 1) begins[i + 1] else durationMs
        val endSafe = end.coerceAtLeast(begin)
        val tokens = AxisTokenizer.split(texts[i])
        val words = wordsByLine.getOrNull(i)?.let { done ->
            val filled = AxisValidator.fillWords(done, begin, endSafe)
            tokens.mapIndexed { wi, t -> AxisWord(t, filled.getOrElse(wi) { begin }) }
        } ?: emptyList()
        AxisLine(i = i, text = texts[i], beginMs = begin, endMs = endSafe, words = words)
    }
}

object LrcWriter {

    fun stamp(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val minutes = total / 60_000L
        val seconds = (total % 60_000L) / 1000L
        val centis = (total % 1000L) / 10L
        return java.lang.String.format("[%02d:%02d.%02d]", minutes, seconds, centis)
    }

    /** 逐字标签，形如 `<00:15.20>`。Enhanced LRC 每个字前面挂一个。 */
    fun wordStamp(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val minutes = total / 60_000L
        val seconds = (total % 60_000L) / 1000L
        val centis = (total % 1000L) / 10L
        return java.lang.String.format("<%02d:%02d.%02d>", minutes, seconds, centis)
    }

    /** `[length:03:24.00]` 里冒号后面那段，不带方括号。 */
    fun lengthValue(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val minutes = total / 60_000L
        val seconds = (total % 60_000L) / 1000L
        val centis = (total % 1000L) / 10L
        return java.lang.String.format("%02d:%02d.%02d", minutes, seconds, centis)
    }

    /** 纯行级 LRC（通用格式）。无逐字标签、无注释行，任何播放器都能直接用。 */
    fun format(axis: ManualAxis): String {
        val sb = StringBuilder()
        // 标准元信息标签：播放器认，也会当成歌名歌手显示。必须先清洗，否则 ] 会把标签截断
        sb.append("[ti:").append(tagSafe(axis.title)).append("]\n")
        sb.append("[ar:").append(tagSafe(axis.artist)).append("]\n")
        sb.append("[by:").append(tagSafe("词岛 · 打轴器")).append("]\n")
        if (axis.durationMs > 0L) {
            sb.append("[length:").append(lengthValue(axis.durationMs)).append("]\n")
        }
        axis.lines.forEach { line ->
            sb.append(stamp(line.beginMs)).append(line.text.replace('\n', ' ')).append('\n')
        }
        return sb.toString()
    }

    /** LRC 标签值里不能出现 ] 和换行，否则标签被提前截断。 */
    private fun tagSafe(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace(']', ')').trim()

    /**
     * 逐字 Enhanced LRC：行首 `[mm:ss.xx]`，行内每个字前面再挂一个 `<mm:ss.xx>`。
     *
     * 网易云、QQ 音乐、Poweramp、Aegisub 都认这个格式，是「把轴交给别人」的首选。
     * 没敲过字的行退化成纯行级输出，读的人照样能显示，只是不逐字高亮。
     */
    fun formatEnhanced(axis: ManualAxis, withMeta: Boolean = true): String {
        val sb = StringBuilder()
        if (withMeta) {
            sb.append("[ti:").append(axis.title).append("]\n")
            sb.append("[ar:").append(axis.artist).append("]\n")
            sb.append("[by:词岛 · 打轴器]\n")
            if (axis.durationMs > 0L) {
                sb.append("[length:").append(lengthValue(axis.durationMs)).append("]\n")
            }
        }
        axis.lines.forEach { line ->
            sb.append(stamp(line.beginMs))
            if (line.words.isEmpty()) {
                sb.append(line.text)
            } else {
                line.words.forEach { w ->
                    sb.append(wordStamp(w.beginMs)).append(w.text)
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}

/** 本地轴存储：filesDir/lyrics/manual/，一份索引 + 每首歌一份 JSON。 */
class AxisStore(val root: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val indexFile: File get() = File(root, "index.json")

    fun ensureDir() {
        if (!root.exists()) root.mkdirs()
    }

    fun loadIndex(): List<AxisIndexEntry> {
        ensureDir()
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<AxisIndexEntry>>(indexFile.readText())
        }.getOrDefault(emptyList())
    }

    fun list(): List<AxisIndexEntry> = loadIndex().sortedByDescending { it.updatedAt }

    fun saveIndex(entries: List<AxisIndexEntry>) {
        ensureDir()
        indexFile.writeText(json.encodeToString(entries))
    }

    private fun fileNameFor(songKey: String): String {
        val safe = songKey.hashCode().toUInt().toString(16)
        return "axis-$safe.json"
    }

    /**
     * 保存一条轴。
     *
     * @param replacingKey 正在编辑的原条目 key（重打旧轴时传）。传了就用原条目的文件名覆盖写入，
     *   即使改完名字/时长后 songKey 变了也不新开文件，只把索引里那条搬过去。
     *   不传就是普通新建。
     */
    fun save(axis: ManualAxis, replacingKey: String? = null): AxisIndexEntry {
        ensureDir()
        // 在线自动生成的轴不许覆盖手打的轴：手打的才是"我一句句敲出来的"，
        // 被一份网络上抓来的词顶掉，就是之前"打了轴反而丢歌词"的另一条路。
        if (axis.origin == ManualAxis.ORIGIN_ONLINE) {
            val clash = loadIndex().firstOrNull { it.songKey == axis.songKey && it.songKey != replacingKey }
            if (clash != null) {
                val old = runCatching {
                    json.decodeFromString<ManualAxis>(File(root, clash.fileName).readText())
                }.getOrNull()
                if (old?.isManual == true) return clash
            }
        }
        val existing = replacingKey
            ?.takeIf { it.isNotBlank() }
            ?.let { key -> loadIndex().firstOrNull { it.songKey == key } }
        val fileName = existing?.fileName ?: fileNameFor(axis.songKey)
        val target = File(root, fileName)
        // 覆盖写之前留一份上一次的备份：万一改坏了，磁盘上还有 .bak 能捞回来
        if (target.exists()) {
            runCatching { target.copyTo(File(root, "$fileName.bak"), overwrite = true) }
        }
        target.writeText(json.encodeToString(axis))
        val entry = AxisIndexEntry(
            songKey = axis.songKey,
            title = axis.title,
            artist = axis.artist,
            durationMs = axis.durationMs,
            lineCount = axis.lines.size,
            charCount = axis.lines.sumOf { it.words.size },
            fileName = fileName,
            updatedAt = axis.createdAt,
            origin = axis.origin,
        )
        // 新旧 key 一起剔掉再写回，避免同一条轴在索引里出现两次
        val others = loadIndex().filterNot {
            it.songKey == axis.songKey || it.songKey == replacingKey
        }
        saveIndex(others + entry)
        return entry
    }

    fun load(songKey: String): ManualAxis? {
        val entry = loadIndex().firstOrNull { it.songKey == songKey } ?: return null
        val file = File(root, entry.fileName)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<ManualAxis>(file.readText()) }.getOrNull()
    }

    fun delete(songKey: String): Boolean {
        val entry = loadIndex().firstOrNull { it.songKey == songKey }
        if (entry != null) {
            File(root, entry.fileName).delete()
        }
        val remain = loadIndex().filterNot { it.songKey == songKey }
        saveIndex(remain)
        return entry != null
    }
}
