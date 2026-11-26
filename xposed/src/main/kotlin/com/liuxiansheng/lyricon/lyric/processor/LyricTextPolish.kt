/*
 * 思路对齐 lyricon-master（作者 Proify, Tomakino）的 executePreProcessing：
 * 先进屏蔽词过滤、再做简繁转换。本文件为词岛自有实现，未照搬其代码。
 *
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.processor

import android.content.SharedPreferences
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import com.liuxiansheng.lyricon.opencc.lite.OpenCCLite

/**
 * 歌词文本加工：屏蔽词过滤 + 简繁转换。
 *
 * 两步都在**解析出口**做，每次拿到的是原始解析结果，所以在设置里来回切换模式不会
 * 让文本被反复转换污染（繁转简再转繁回来，字形未必能还原，必须在源头重算）。
 *
 * 顺序也有讲究：先按正则丢行，再转换。反过来的话，正则里写的若是简体字，
 * 遇到繁体歌词就匹配不上。
 */
internal object LyricTextPolish {

    /**
     * 按当前设置加工一首歌。
     *
     * prefs 必须是模块侧注入的那份（`MergedPrefs`，remote 部分即 App 写入的值）。
     * 不能在这里用 `getSharedPreferences` 自己取 —— SystemUI 进程本地那份永远是空的，
     * 读了只会拿到默认值，表现就是「设置改了没反应」。
     */
    fun applyFromPrefs(song: Song, prefs: SharedPreferences?): Song {
        if (prefs == null) return song
        return runCatching {
            apply(
                song,
                prefs.getInt(
                    RootConstants.KEY_HOOK_CHINESE_CONVERSION_MODE,
                    RootConstants.DEFAULT_CHINESE_CONVERSION_MODE
                ),
                prefs.getString(
                    RootConstants.KEY_HOOK_BLOCKED_WORDS_REGEX,
                    RootConstants.DEFAULT_BLOCKED_WORDS_REGEX
                ),
                prefs.getBoolean(RootConstants.KEY_HOOK_HIDE_BRACKET_CONTENT, false)
            )
        }.getOrDefault(song)
    }

    /**
     * 加工入口。
     *
     * @param mode 0 关闭 / 1 转简体 / 2 转繁体
     * @param blockedRegex 命中片段会被删掉；整行被删空则该行不显示。空值或非法正则当未启用
     * @param hideBrackets 去括号：歌名 / 艺人 / 专辑和歌词行里的成对括号连内容一起隐藏，不需要写正则
     */
    fun apply(song: Song, mode: Int, blockedRegex: String?, hideBrackets: Boolean = false): Song {
        var result = song

        val pattern = buildPattern(blockedRegex)

        // 去括号：标题和歌词行一起处理
        if (hideBrackets) {
            result = stripEverywhere(result, BRACKET_PATTERN)
        }

        if (pattern != null) {
            result = stripEverywhere(result, pattern)
        }

        if (mode != RootConstants.CHINESE_CONVERSION_OFF) {
            result = convertChinese(result, mode)
        }

        return result
    }

    /** 模块侧 prefs 的全局引用：给「方法签名里没有 prefs」的显示侧调用点用。 */
    @Volatile
    private var sharedPrefs: SharedPreferences? = null

    /** HookEntry 注入；必须是 remote/MergedPrefs，不能是本进程本地那份（本地永远空）。 */
    fun bindPrefs(prefs: SharedPreferences?) {
        sharedPrefs = prefs
    }

    /** 用已绑定的 prefs 加工一段文本；没绑定就原样返回。 */
    fun applyToText(text: String): String =
        sharedPrefs?.let { applyToText(text, it) } ?: text

    /**
     * 只加工一段独立文本（岛上的歌名 / 艺人 / 专辑这种不走 [Song] 的字段）。
     *
     * 岛上的歌名取自 MediaMetadata 原文，不经过歌词解析链路，所以要在显示侧
     * 单独再走一遍同样的规则，否则会出现「日志里已经剥掉了、岛上还带着括号」。
     * 剥完变空则保留原文，避免岛上标题空着。
     */
    fun applyToText(text: String, prefs: SharedPreferences): String {
        var result = text

        if (prefs.getBoolean(RootConstants.KEY_HOOK_HIDE_BRACKET_CONTENT, false)) {
            result = BRACKET_PATTERN.replace(result, "")
        }
        buildPattern(prefs.getString(RootConstants.KEY_HOOK_BLOCKED_WORDS_REGEX, null))
            ?.let { result = it.replace(result, "") }

        val mode = prefs.getInt(
            RootConstants.KEY_HOOK_CHINESE_CONVERSION_MODE,
            RootConstants.DEFAULT_CHINESE_CONVERSION_MODE
        )
        if (mode != RootConstants.CHINESE_CONVERSION_OFF) {
            converterFor(mode)?.let { converter -> result = converter(result) }
        }

        return cleanEmptyBrackets(result).trim().ifBlank { text }
    }

    /** 删完词后可能留下「()」「《》」这种空壳，逐个清掉。 */
    private fun cleanEmptyBrackets(text: String): String {
        var result = text
        EMPTY_BRACKETS.forEach { result = result.replace(it, "") }
        return result
    }

    /** 所有成对出现的括号：开 → 闭。常见的中英文括号基本都在这里。 */
    private val BRACKET_PAIRS: List<Pair<Char, Char>> = listOf(
        '（' to '）', '(' to ')',
        '《' to '》', '〈' to '〉',
        '【' to '】', '〖' to '〗',
        '[' to ']', '［' to '］',
        '「' to '」', '『' to '』',
        '{' to '}', '｛' to '｝',
        '<' to '>', '＜' to '＞',
        '〔' to '〕', '﹝' to '﹞', '﹙' to '﹚'
    )

    /**
     * 一对括号的正则：开 + 非闭括号的任意内容 + 闭。
     *
     * 用 `[^闭]*` 而不是 `.*`。贪婪的 `.*` 碰到「（A）（B）」会从第一个括号一路吃到最后一个，
     * 把中间的正常文字也剥掉。
     */
    private fun pairRegex(open: Char, close: Char): String =
        Regex.escape(open.toString()) +
            "[^" + Regex.escape(close.toString()) + "]*" +
            Regex.escape(close.toString())

    /** 所有括号对拼成一条 alternation：「括号」和「隐藏标题里的括号」都用它。 */
    private val BRACKET_CONTENT_SOURCE: String =
        BRACKET_PAIRS.joinToString("|") { (open, close) -> pairRegex(open, close) }

    private val BRACKET_PATTERN = Regex(BRACKET_CONTENT_SOURCE)

    /** 所有括号字符，用来判断用户填的到底是不是括号。 */
    private val BRACKET_CHARS: Set<Char> =
        BRACKET_PAIRS.flatMap { listOf(it.first, it.second) }.toSet()

    /** 删词之后可能留下空壳，这些都要一起清掉。 */
    private val EMPTY_BRACKETS: List<String> =
        BRACKET_PAIRS.map { (open, close) -> "$open$close" }

    /**
     * 用户填的这段内容算不算「一对括号」的写法。
     *
     * 填开括号、闭括号、整对都行（「（」「）」都认），填「括号」两个字也认。
     * 只有整段都由括号字符组成、且能对上某一对，才展开成括号规则；
     * 其它内容一律按字面处理，不会因为含了一个符号就把整段变成正则。
     */
    private fun bracketRuleFor(entry: String): String? {
        if (entry == "括号") return BRACKET_CONTENT_SOURCE
        if (entry.isEmpty()) return null
        if (entry.any { it !in BRACKET_CHARS }) return null

        val pair = BRACKET_PAIRS.firstOrNull { (open, close) ->
            entry == open.toString() || entry == close.toString() || entry == "$open$close"
        } ?: return null
        return pairRegex(pair.first, pair.second)
    }

    /**
     * 把用户输入编译成一条正则。
     *
     * **用户填的是「想删掉的内容」，不是正则**：
     * - 普通文字按字面处理，填 `从者` 就只删「从者」这两个字
     * - 填「（」「()」「括号」这类，自动换成成对括号规则，连括号里的内容一起删
     * - 多个内容用逗号或换行隔开，任一命中都删
     *
     * 这样就不需要教用户写 `[（(].*[）)]` 这种语法了。
     */
    private fun buildPattern(raw: String?): Regex? {
        val entries = raw
            ?.split('\n', '，', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return null
        if (entries.isEmpty()) return null

        val parts = entries.map { entry ->
            bracketRuleFor(entry) ?: Regex.escape(entry)
        }
        // 忽略大小写：歌名里常写成小写 `(dj版)`，用户填「DJ版」也得命中
        return runCatching {
            Regex(parts.joinToString("|"), RegexOption.IGNORE_CASE)
        }.getOrNull()
    }

    /**
     * 删掉命中正则的片段，标题和歌词行一起处理。
     *
     * - 标题（歌名 / 艺人 / 专辑）：删命中片段，剥完变空则保留原值，避免岛上标题空白
     * - 歌词行：删命中片段；整行被删空（比如整行就是「（间奏）」）则该行不显示
     *
     * 这样「屏蔽哪个字就删哪个字」和「整行都不要」能共存 ——
     * 前者直接写词本身（`从者`），后者写 `^(作词|作曲).*` 把整行吃干净。
     *
     * 行文本改写后尽量保住 `words`（按命中的词逐个剔除），拼不回新文本就置空，
     * 让显示层退回整行高亮，避免逐词时间轴与文本错位。
     */
    private fun stripEverywhere(song: Song, pattern: Regex): Song {
        val result = stripMetadata(song, pattern)
        val lyrics = result.lyrics ?: return result

        val mapped = lyrics.mapNotNull { line ->
            val raw = line.text ?: return@mapNotNull line
            val replaced = cleanEmptyBrackets(
                pattern.replace(raw, "")
            ).trim()
            when {
                replaced.isBlank() -> null
                replaced == raw -> line
                else -> {
                    val keptWords = line.words?.filterNot { word ->
                        word.text?.let { pattern.containsMatchIn(it) } ?: false
                    }
                    // 逐词文本拼起来必须和新行文本一致，否则时间轴对不上
                    val wordsOk = keptWords != null &&
                            keptWords.joinToString("") { it.text.orEmpty() } == replaced
                    line.copy(text = replaced, words = if (wordsOk) keptWords else null)
                }
            }
        }

        // 一行都没动就返回原对象，别让下游哈希无辜变化
        if (mapped.size == lyrics.size && mapped.indices.all { mapped[it] === lyrics[it] }) {
            return result
        }
        return result.copy(lyrics = mapped)
    }

    /**
     * 从歌名 / 艺人 / 专辑里删掉命中屏蔽词的片段。
     *
     * 歌词行是整行丢，但歌名不能整行丢 —— 比如「爱我的人和我爱的人（DJ女版）」，
     * 用 `[（(].*[）)]` 应该只剩「爱我的人和我爱的人」，而不是整个歌名变空。
     * 所以这里走 replace，且剥完变空就保留原值（避免岛上标题空白）。
     */
    private fun stripMetadata(song: Song, pattern: Regex): Song {
        val strip: (String?) -> String? = { raw ->
            raw?.let { cleanEmptyBrackets(pattern.replace(it, "")) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        val name = strip(song.name)
        val artist = strip(song.artist)
        val album = strip(song.album)

        // 三样都没变就返回原对象，别让下游以为内容换了
        if (name == song.name && artist == song.artist && album == song.album) return song

        return song.copy(
            name = name ?: song.name,
            artist = artist ?: song.artist,
            album = album ?: song.album
        )
    }

    /**
     * 简繁转换。
     *
     * `text` 和 `words` 两套都要转：主行文本是独立字段，只转词不转文本的话，
     * 显示层读 `text` 就等于没转。
     */
    private fun convertChinese(song: Song, mode: Int): Song {
        val convert = converterFor(mode) ?: return song
        val convertWord = { word: LyricWord -> word.copy(text = word.text?.let(convert)) }

        return song.copy(
            name = song.name?.let(convert),
            artist = song.artist?.let(convert),
            album = song.album?.let(convert),
            lyrics = song.lyrics?.map { line ->
                line.copy(
                    text = line.text?.let(convert),
                    words = line.words?.map(convertWord),
                    translation = line.translation?.let(convert),
                    translationWords = line.translationWords?.map(convertWord),
                    secondary = line.secondary?.let(convert),
                    secondaryWords = line.secondaryWords?.map(convertWord),
                    roma = line.roma?.let(convert)
                )
            }
        )
    }

    /**
     * 取转换器。首次调用才会构建字典树，模式关闭时这条路径根本不进，
     * 所以默认设置下没有额外开销。
     */
    private fun converterFor(mode: Int): ((String) -> String)? = runCatching {
        when (mode) {
            RootConstants.CHINESE_CONVERSION_SIMPLIFIED -> { text: String ->
                OpenCCLite.T2S.convert(text)
            }

            RootConstants.CHINESE_CONVERSION_TRADITIONAL -> { text: String ->
                OpenCCLite.S2T.convert(text)
            }

            else -> null
        }
    }.getOrNull()
}
