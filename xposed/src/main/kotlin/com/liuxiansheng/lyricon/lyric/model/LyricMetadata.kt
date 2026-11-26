/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

@file:Suppress("unused")

package com.liuxiansheng.lyricon.hyper.lyric.model

import kotlinx.serialization.Serializable

/**
 * 歌词元数据：一个只读的 `String -> String?` 映射表。
 *
 * 通过委托直接当 Map 用，遍历和查询行为和普通 Map 一致。底层表不可变，
 * 所以对象可以放心共享。
 *
 * 另外提供几个类型转换取值：键不存在或转换失败时返回给定的默认值。
 *
 * @property map 底层映射表
 */
@Serializable
data class LyricMetadata(
    private val map: Map<String, String?> = emptyMap(),
) : Map<String, String?> by map {

    /** 取 Double，取不到用 default。 */
    fun getDouble(key: String, default: Double = 0.0): Double =
        map[key]?.toDoubleOrNull() ?: default

    /** 取 Boolean，取不到用 default。 */
    fun getBoolean(key: String, default: Boolean = false): Boolean =
        map[key]?.toBoolean() ?: default

    /** 取 Float，取不到用 default。 */
    fun getFloat(key: String, default: Float = 0f): Float = map[key]?.toFloatOrNull() ?: default

    /** 取 Long，取不到用 default。 */
    fun getLong(key: String, default: Long = 0): Long = map[key]?.toLongOrNull() ?: default

    /** 取 Int，取不到用 default。 */
    fun getInt(key: String, default: Int = 0): Int = map[key]?.toIntOrNull() ?: default

    /** 取 String，取不到用 default。 */
    fun getString(key: String, default: String? = null): String? = map[key] ?: default

    /** 按底层表内容比相等，和 Map 的语义一致。 */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LyricMetadata) return false
        return map == other.map
    }

    /** 和 equals 对齐，按底层表算哈希。 */
    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = "LyricMetadata(map=$map)"
}

/** 快捷构造：`lyricMetadataOf("ti" to "歌名", "ar" to "歌手")`。 */
fun lyricMetadataOf(vararg pairs: Pair<String, String?>): LyricMetadata =
    LyricMetadata(mapOf(*pairs))
