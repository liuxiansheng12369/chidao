/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.model

import com.liuxiansheng.lyricon.hyper.lyric.model.extensions.deepCopy
import com.liuxiansheng.lyricon.hyper.lyric.model.extensions.normalizeSortByTime
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.DeepCopyable
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.Normalize
import kotlinx.serialization.Serializable

/**
 * 歌曲信息，歌词数据的顶层容器。
 *
 * 层次是 `Song → lyrics → [RichLyricLine] → [LyricWord]`，时间单位毫秒。
 *
 * 规范化的三件事（返回新深拷贝，原对象不动）：
 * 1. 剩余行 `duration <= 0` 时按 `end - begin` 补；
 * 2. 剔掉非法行 —— `begin < 0`、`begin >= end`、`duration <= 0` 或文本为空白的；
 * 3. 按 `begin` 升序稳定排序。
 *
 * @property id 歌曲唯一标识
 * @property name 歌名
 * @property artist 歌手
 * @property album 专辑
 * @property duration 歌曲时长（毫秒）
 * @property metadata 歌曲级元数据
 * @property lyrics 歌词行列表
 */
@Serializable
data class Song(
    var id: String? = null,
    var name: String? = null,
    var artist: String? = null,
    var album: String? = null,
    var duration: Long = 0,
    var metadata: LyricMetadata? = null,
    var lyrics: List<RichLyricLine>? = null,
) : DeepCopyable<Song>, Normalize<Song> {
    companion object {
        /** 本渲染侧提示：该 provider 规则关闭了主行（含歌名占位）。 */
        const val KEY_HIDE_PRIMARY = "lyricon_hide_primary"
    }

    /** 深拷贝。`lyrics` 里每行及其逐词数据都重新创建。 */
    override fun deepCopy(): Song = copy(lyrics = lyrics?.deepCopy())

    /**
     * 规范化后的深拷贝，原对象不受影响。
     *
     * 补时长 → 剔非法行 → 按开始时间稳定排序。剔完可能变短甚至为空。
     */
    override fun normalize(): Song = deepCopy().apply {
        lyrics = lyrics?.mapNotNull { line ->
            if (line.duration <= 0L) line.duration = line.end - line.begin

            val keep = line.begin >= 0L &&
                    line.begin < line.end &&
                    line.duration > 0L &&
                    !line.text.isNullOrBlank()
            line.takeIf { keep }
        }?.normalizeSortByTime()
    }
}
