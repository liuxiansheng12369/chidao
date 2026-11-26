package io.github.proify.lyricon.lyric.model

import io.github.proify.lyricon.lyric.model.extensions.deepCopy
import io.github.proify.lyricon.lyric.model.extensions.normalizeSortByTime
import io.github.proify.lyricon.lyric.model.interfaces.DeepCopyable
import io.github.proify.lyricon.lyric.model.interfaces.Normalize
import kotlinx.serialization.Serializable

/**
 * 歌曲信息
 *
 * @property id 歌曲ID
 * @property name 歌曲名
 * @property artist 艺术家
 * @property duration 歌曲时长
 * @property metadata 元数据
 * @property lyrics 歌词列表
 */
@Serializable
data class Song(
    var id: String? = null,
    var name: String? = null,
    var artist: String? = null,
    var duration: Long = 0,
    var metadata: LyricMetadata? = null,
    var lyrics: List<RichLyricLine>? = null,
) : DeepCopyable<Song>, Normalize<Song> {

    override fun deepCopy(): Song = copy(lyrics = lyrics?.deepCopy())

    override fun normalize(): Song = deepCopy().apply {
        lyrics = lyrics?.mapNotNull { line ->
            if (line.duration <= 0) line.duration = line.end - line.begin

            // 兜底：有的 provider 只推逐字时间、不填整行文字（text）。
            // 这里用 words 把整行拼出来，否则下面的 !text.isNullOrBlank() 会把整行丢掉，表现为缺句。
            // 与 RichLyricLine.normalize() 里的 words.toText(text) 同一口径，只是 Song 这层没走到。
            if (line.text.isNullOrBlank()) {
                val words = line.words
                if (!words.isNullOrEmpty()) {
                    line.text = words.joinToString("") { it.text.orEmpty() }
                }
            }

            val isValid = line.begin >= 0
                    && line.begin < line.end
                    && line.duration > 0
                    && !line.text.isNullOrBlank()
            if (isValid) line else null
        }?.normalizeSortByTime()
    }
}