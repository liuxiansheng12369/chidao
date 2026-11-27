/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.core

/**
 * 本地歌词导入：把 .lrc / .txt 变成「文本行 + 可选已有时间」。
 *
 * 只做解析，不碰存储、不碰 UI。UI 侧拿到 [ImportedLyric] 再决定进核对还是敲轴。
 */
data class ImportedLyric(
    val title: String,
    val artist: String,
    /** 文件里没写时长时就是 −1，交给调用方用当前播放的时长。 */
    val durationMs: Long,
    val lines: List<String>,
    /** 每行对应的时间；纯文本文件全是 null。 */
    val begins: List<Long?>,
    /**
     * 每行的逐字时间（Enhanced LRC 里的 `<mm:ss.xx>`）。
     * 长度与 [lines] 一致；某行是 null 表示那行只有行级时间，没有逐字。
     */
    val wordsByLine: List<List<Long?>?> = emptyList(),
) {
    val hasAnyTiming: Boolean get() = begins.any { it != null }
    val allTimed: Boolean get() = lines.isNotEmpty() && begins.all { it != null }
}

object LocalLyricImport {

    private val metaRe = Regex(
        """^\s*\[(ti|ar|al|by|offset|length|re|ve)\s*[:：]\s*(.*?)\s*]""",
        RegexOption.IGNORE_CASE
    )

    /** 开头的时间标签，形如 `[00:12.34]`、`[1:02]`、`[00:12,34]`。 */
    private val timeTagRe = Regex("""^\[\s*(\d+):(\d+(?:[.,]\d{1,3})?)\s*]""")

    /**
     * @param fallbackTitle / fallbackArtist / fallbackDurationMs 文件里没写时用当前播放的值兜底。
     */
    fun parse(
        raw: String,
        fallbackTitle: String = "",
        fallbackArtist: String = "",
        fallbackDurationMs: Long = 0L,
    ): ImportedLyric {
        var title = ""
        var artist = ""
        var durationMs = -1L

        val lines = ArrayList<String>()
        val begins = ArrayList<Long?>()
        val wordsByLine = ArrayList<List<Long?>?>()

        raw.lineSequence().forEach { line ->
            var rest = line.trim()
            if (rest.isEmpty()) return@forEach

            // 元信息标签：[ti:歌名] [ar:歌手] …，同一行可能连着好几个
            while (true) {
                val m = metaRe.find(rest)
                if (m == null || m.range.first != 0) break
                val key = m.groupValues[1].lowercase()
                val value = m.groupValues[2].trim()
                when (key) {
                    "ti" -> if (title.isBlank()) title = value
                    "ar" -> if (artist.isBlank()) artist = value
                    "length" -> {
                        val d = parseLengthTag(value)
                        if (d > 0L) durationMs = d
                    }
                }
                rest = rest.substring(m.range.last + 1).trim()
            }
            if (rest.isEmpty()) return@forEach

            // 行首可能叠着多个时间标签（一句歌词重复出现时），取第一个
            var time: Long? = null
            while (true) {
                val m = timeTagRe.find(rest)
                if (m == null || m.range.first != 0) break
                if (time == null) time = toMillis(m.groupValues[1], m.groupValues[2])
                rest = rest.substring(m.range.last + 1).trim()
            }

            val text = rest.trim()
            if (text.isEmpty()) return@forEach
            // Enhanced LRC：行内每个字前面挂着 <mm:ss.xx>，先把时间抽出来再剥掉标签
            val words = parseWordTags(text)
            lines.add(if (words == null) text else stripWordTags(text))
            begins.add(time)
            wordsByLine.add(words)
        }

        val finalTitle = title.ifBlank { fallbackTitle.trim() }
        val finalArtist = artist.ifBlank { fallbackArtist.trim() }
        val finalDuration = if (durationMs > 0L) {
            durationMs
        } else if (fallbackDurationMs > 0L) {
            fallbackDurationMs
        } else {
            // 推断：最后一句时间 + 2 秒，够岛上把最后一句唱完
            begins.lastOrNull { it != null }?.let { it + 2000L } ?: 0L
        }

        return ImportedLyric(
            title = finalTitle,
            artist = finalArtist,
            durationMs = finalDuration,
            lines = lines,
            begins = begins,
            wordsByLine = wordsByLine,
        )
    }

    /** 逐字标签，形如 `<00:15.20>`，Enhanced LRC 用这个。 */
    private val wordTagRe = Regex("""<(\d+):(\d+(?:[.,]\d{1,3})?)>""")

    /**
     * 把 `[00:12.34]<00:12.34>不<00:12.80>让<00:13.10>你` 里的 `<...>` 逐个抽出来，
     * 换算成「每个分词 token 的起始时间」。
     *
     * 返回 null 表示这行没有逐字标签（普通 LRC / 纯文本），调用方按行级处理。
     * 返回的列表长度可能和本地 [AxisTokenizer] 切出来的 token 数不完全一致，
     * 差的那几个由 AxisValidator.fillWords 均分补上，不会越界。
     */
    private fun parseWordTags(text: String): List<Long?>? {
        val tags = wordTagRe.findAll(text).toList()
        if (tags.isEmpty()) return null
        val out = ArrayList<Long?>()
        tags.forEachIndexed { idx, m ->
            val start = m.range.last + 1
            val end = if (idx + 1 < tags.size) tags[idx + 1].range.first else text.length
            if (start >= end) return@forEachIndexed
            val piece = text.substring(start, end)
            val tokenCount = AxisTokenizer.split(piece).size
            if (tokenCount <= 0) return@forEachIndexed
            val timeMs = toMillis(m.groupValues[1], m.groupValues[2]) ?: return@forEachIndexed
            out.add(timeMs)
            // 一个小片段里如果不止一个词，后面的词没有独立时间，留 null 交给均分补齐
            repeat(tokenCount - 1) { out.add(null) }
        }
        return if (out.isEmpty()) null else out
    }

    /** 剥掉所有 `<mm:ss.xx>`，只留能显示的字。 */
    private fun stripWordTags(text: String): String = wordTagRe.replace(text, "")

    private fun toMillis(min: String, sec: String): Long? {
        val m = min.toLongOrNull() ?: return null
        val s = sec.replace(',', '.').toDoubleOrNull() ?: return null
        return m * 60_000L + (s * 1000.0).toLong()
    }

    /** `[length:03:24.00]` 或 `[length:03:24]`。 */
    private fun parseLengthTag(value: String): Long {
        val parts = value.split(':')
        if (parts.size < 2) return -1L
        val m = parts[0].toLongOrNull() ?: return -1L
        val s = parts[1].replace(',', '.').toDoubleOrNull() ?: return -1L
        return m * 60_000L + (s * 1000.0).toLong()
    }
}
