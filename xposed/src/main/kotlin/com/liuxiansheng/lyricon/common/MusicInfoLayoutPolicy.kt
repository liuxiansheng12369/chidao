/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.common

import android.content.SharedPreferences

/**
 * 「音乐信息」两行文本的组成规则。
 *
 * 用户选的是字段（标题、歌手、专辑、时长……）和分隔符；这里负责把存储里的字符串
 * 解析成可用字段列表，并把分隔符 ID 翻成真正插进文本的字符。
 *
 * 非法字段一律丢掉，不去猜用户想选哪个 —— 设置页改过字段名以后，老配置里可能残留
 * 已经不存在的字段。
 */
object MusicInfoLayoutPolicy {

    // ——— 可用字段 ———

    const val FIELD_TITLE = "title"
    const val FIELD_ARTIST = "artist"
    const val FIELD_ALBUM = "album"
    const val FIELD_DURATION = "duration"
    const val FIELD_ELAPSED = "elapsed"
    const val FIELD_REMAINING = "remaining"
    const val FIELD_PROGRESS_PERCENT = "progress_percent"

    // ——— 分隔符 ID ———

    const val SEPARATOR_PLUS = "plus"
    const val SEPARATOR_SPACE = "space"
    const val SEPARATOR_COMMA = "comma"
    const val SEPARATOR_IDEOGRAPHIC_COMMA = "ideographic_comma"
    const val SEPARATOR_SLASH = "slash"
    const val SEPARATOR_HYPHEN = "hyphen"
    const val SEPARATOR_NONE = "none"

    /** 允许出现在配置里的字段。 */
    val supportedFields: Set<String> = setOf(
        FIELD_TITLE,
        FIELD_ARTIST,
        FIELD_ALBUM,
        FIELD_DURATION,
        FIELD_ELAPSED,
        FIELD_REMAINING,
        FIELD_PROGRESS_PERCENT
    )

    /** 允许出现在配置里的分隔符。 */
    private val supportedSeparators: Set<String> = setOf(
        SEPARATOR_PLUS,
        SEPARATOR_SPACE,
        SEPARATOR_COMMA,
        SEPARATOR_IDEOGRAPHIC_COMMA,
        SEPARATOR_SLASH,
        SEPARATOR_HYPHEN,
        SEPARATOR_NONE
    )

    /** 默认第一行只放标题。 */
    val defaultFirstLine: List<String> = listOf(FIELD_TITLE)

    /** 默认第二行只放歌手。 */
    val defaultSecondLine: List<String> = listOf(FIELD_ARTIST)

    /**
     * 读字段列表。
     *
     * 存储格式是逗号分隔的 ID 串。没有这个键就用默认值；有键但内容全非法，
     * 返回空列表（表示用户确实把字段都删了，不要偷偷塞回默认值）。
     */
    fun readFields(
        prefs: SharedPreferences,
        key: String,
        defaultValue: List<String>
    ): List<String> {
        val stored = prefs.getString(key, null) ?: return defaultValue
        return stored
            .split(',')
            .map { it.trim() }
            .filter { it in supportedFields }
            .distinct()
    }

    /** 分隔符设置项已按用户要求撤掉，固定用连字符。 */
    fun readSeparator(prefs: SharedPreferences): String = SEPARATOR_HYPHEN

    /** 分隔符 ID 翻成真正拼进文本的字符。未知 ID 落回连字符。 */
    fun separatorValue(separator: String): String = when (separator) {
        SEPARATOR_PLUS -> " + "
        SEPARATOR_SPACE -> " "
        SEPARATOR_COMMA -> ", "
        SEPARATOR_IDEOGRAPHIC_COMMA -> "、"
        SEPARATOR_SLASH -> " / "
        SEPARATOR_NONE -> ""
        else -> " - "
    }

    fun isSupportedSeparator(separator: String): Boolean = separator in supportedSeparators
}
