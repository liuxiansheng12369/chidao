/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root
import com.liuxiansheng.lyricon.hyper.root.utils.DiagnosticsLog

import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.common.media.MediaMetadataHelper
import com.liuxiansheng.lyricon.hyper.lyric.model.RichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricMediaMetadata
import com.liuxiansheng.lyricon.hyper.lyric.model.extensions.TimingNavigator
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.IRichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.view.InterludeTracker
import com.liuxiansheng.lyricon.hyper.lyric.view.SongPreprocessor
import com.liuxiansheng.lyricon.hyper.lyric.view.TimedLine
import com.liuxiansheng.lyricon.hyper.lyric.view.TitleSlot
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import com.liuxiansheng.lyricon.hyper.root.manual.ManualAxisBridge
import com.liuxiansheng.lyricon.hyper.plugin.api.PluginSongField

object LyriconDataBridge {

    private const val TAG = "LyriconDataBridge"

    val versionCounter = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var currentSong: Song? = null

    @Volatile
    var currentSongName: String? = null

    /**
     * 诊断探针：歌词源最近一次推来的 Song 摘要（"歌名#行数"）。
     *
     * 用来区分"播放器压根没推 Song"和"推了但歌词是空的" —— 这两种现象在岛上长得一样，
     * 只能靠把原始事件记下来才分得清。
     */
    @Volatile
    var providerSongProbe: String? = null

    /** Latest media fields supplied by the active lyric source, before MediaSession fallback. */
    @Volatile
    var currentLyricMediaMetadata: LyricMediaMetadata? = null

    /**
     * 当前歌曲总时长（毫秒）。优先取歌词里的时长，其次取媒体元数据里的时长；都拿不到返回 0。
     * 状态栏播放进度条靠它算 progress = currentPosition / currentDuration。
     */
    val currentDuration: Long
        get() = currentSong?.duration?.takeIf { it > 0L }
            ?: currentLyricMediaMetadata?.duration?.takeIf { it > 0L }
            ?: 0L

    @Volatile
    var currentLyric: String? = null

    @Volatile
    var currentLyricLine: IRichLyricLine? = null

    @Volatile
    var currentNextLyricLine: IRichLyricLine? = null

    @Volatile
    var currentPosition: Long = 0L

    @Volatile
    var currentLyricPackageName: String? = null

    /** 是否处于纯文本模式（椒盐音乐等通过 onSendText 推送） */
    @Volatile
    var isTextMode: Boolean = false

    /**
     * Full-song lyric sources publish this state from [updateSong]. Streaming sources leave it
     * unknown and are considered ready only after a non-empty line or plain-text event arrives.
     */
    @Volatile
    private var fullSongLyricsAvailable: Boolean? = null

    @Volatile
    private var placeholderFormat = RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT

    /**
     * 尾段超时行为：歌已经唱完、最后一行还挂在岛上时怎么处理。
     * 0 = 保持（与不做超时完全一致，默认）；1 = 歌词槽换成音乐信息；2 = 交还原生渲染。
     * 语义对齐 HyperLyric 的 TAIL_TIMEOUT_BEHAVIOR_*，默认保持。
     */
    @Volatile
    var tailTimeoutBehavior: Int = RootConstants.DEFAULT_HOOK_TAIL_TIMEOUT_BEHAVIOR
        private set

    /** 尾段超时延迟秒数：从最后一行结束时刻起算，超过该秒数才判定进入尾段。 */
    @Volatile
    var tailTimeoutDelaySeconds: Int = RootConstants.DEFAULT_HOOK_TAIL_TIMEOUT_DELAY_SECONDS
        private set

    /** 由时间线最后一行推导出的尾段超时时刻；没有可用歌词行时是 Long.MAX_VALUE，即永不超时。 */
    @Volatile
    private var tailTimeoutDeadlineMs: Long = Long.MAX_VALUE

    /**
     * 当前位置是否已越过尾段超时点。
     *
     * 只在"没有任何正在唱的行"时才成立：对唱里前一行可能比最后一行结束得晚，
     * 只看超时点会把还在唱的那一行误判成尾段。
     */
    @Volatile
    var isInTailTimeout: Boolean = false
        private set

    /** 尾段超时配置变更入口；返回 true 表示配置确实变了（调用方据此决定是否重画）。 */
    fun updateTailTimeoutSettings(behavior: Int, delaySeconds: Int): Boolean {
        val normalizedBehavior = RootConstants.normalizeTailTimeoutBehavior(behavior)
        val normalizedDelay = RootConstants.normalizeTailTimeoutDelaySeconds(delaySeconds)
        if (normalizedBehavior == tailTimeoutBehavior && normalizedDelay == tailTimeoutDelaySeconds) {
            return false
        }
        tailTimeoutBehavior = normalizedBehavior
        tailTimeoutDelaySeconds = normalizedDelay
        currentSong?.let { rebuildTimeline(it, selectCurrentPosition = false) }
        HookLogger.d(TAG, "尾段超时配置: behavior=$normalizedBehavior delay=${normalizedDelay}s")
        return true
    }


    fun updateLyricPackage(packageName: String?) {
        currentLyricPackageName = packageName
    }

    private var timingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var interludeTracker = InterludeTracker(8_000L)

    fun updateSong(
        song: Song?,
        placeholderFormat: Int = RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
    ) {
        // 空歌词一律照原样收下（对齐上游 LyricDataHub.onSongChanged：空就空着，
        // 不拿上一份歌词顶上去）。能补的内容只从 provider 传来的、或本地人工轴里来，
        // 两边都没有就保持空，靠媒体信息层显示歌名，绝不串别的歌。
        // 人工轴：provider 有词且在线优先时不动；provider 没词才去本地镜像目录找轴。
        // 注意这里**不能阻塞**：本方法跑在 SystemUI 主线程（MediaController 回调链路），
        // 查轴是一次跨进程 call，App 冷启动时能把主线程按住 5 秒 → APP_SCOUT_HANG → 岛卡死。
        // 所以走 applyIfHit：缓存有就当场用，没有先按原歌词渲染，查到再回调。
        val effectiveSong = ManualAxisBridge.applyIfHit(song) { hit ->
            applyLateManualAxis(hit, song?.name)
        }
        HookLogger.d(TAG, "歌曲变更: ${effectiveSong?.name}")
        // 排错用：把 provider 原始推来的歌名 + 前 5 行词打出来，确认串词到底发生在哪一层
        runCatching {
            val head = song?.lyrics?.take(5)?.joinToString(" / ") { it.text.orEmpty() }.orEmpty()
            HookLogger.i(
                TAG,
                "收到词: name=${song?.name} artist=${song?.artist} dur=${song?.duration} " +
                    "lines=${song?.lyrics?.size} head=[$head]"
            )
        }
        isTextMode = false
        fullSongLyricsAvailable = effectiveSong?.lyrics?.any(::hasRenderableLine) == true
        currentLyricMediaMetadata = null
        currentSong = effectiveSong
        currentSongName = effectiveSong?.name
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        this.placeholderFormat = normalizePlaceholderFormat(placeholderFormat)

        versionCounter.incrementAndGet()

        if (effectiveSong != null) {
            // 整首一行词都没有时（provider 只推了歌名），SongPreprocessor 会补一行歌名占位。
            // 这种情况必须当场选中这一行：否则要等下一次 position 回调才出内容，
            // 而 provider 推空歌词时往往就不再有 position 事件，岛上会一直只剩封面。
            val hasRenderable = effectiveSong.lyrics?.any(::hasRenderableLine) == true
            rebuildTimeline(effectiveSong, selectCurrentPosition = !hasRenderable)
        } else {
            timingNavigator = TimingNavigator(emptyArray())
        }
    }

    /**
     * Re-apply the source-event state without discarding an already accepted plugin enhancement.
     * Root uses this only for a repeated source snapshot whose media identity has not yet
     * changed; a later identity change calls [updateSong] with the new raw source Song instead.
     */
    fun refreshSongEvent(): Boolean {
        val song = currentSong ?: return false
        isTextMode = false
        fullSongLyricsAvailable = song.lyrics?.any(::hasRenderableLine) == true
        currentSongName = song.name
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        rebuildTimeline(song, selectCurrentPosition = false)
        return true
    }

    fun updateMediaMetadata(metadata: LyricMediaMetadata?) {
        currentLyricMediaMetadata = metadata?.normalized()
    }

    /**
     * 打轴器刚保存了新轴：原地重新查一次并换掉当前歌词。
     *
     * 和 [refreshSongEvent] 的区别：那个拿的是已经替换过的 currentSong，不会重新读盘，
     * 等于把旧轴再刷一遍；这个先让桥缓存失效，再走一次 [ManualAxisBridge.applyIfHit]。
     * 重建时必须 selectCurrentPosition = true，否则正在播的歌会出现岛上一片空白、
     * 下一拍才回来。
     */
    fun refreshManualAxis(): Boolean {
        // 先失效缓存再判断：保存的那一首未必正在播。旧的"这首没轴"结论要是留着，
        // 之后切回这首就一直查不到，只有重启状态栏才恢复 —— 用户看到的就是"切换没有、重启才有"。
        ManualAxisBridge.invalidateAxisCache()
        val base = currentSong ?: run {
            HookLogger.d(TAG, "打轴保存后当前没在放歌，已清缓存，切歌时再查")
            return false
        }
        // 这条路径 App 进程必定活着（刚保存完），同步查是毫秒级，可以当场生效。
        val refreshed = ManualAxisBridge.applyIfHitNow(base) ?: return false
        currentSong = refreshed
        currentSongName = refreshed.name
        isTextMode = false
        fullSongLyricsAvailable = refreshed.lyrics?.any(::hasRenderableLine) == true
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        versionCounter.incrementAndGet()
        rebuildTimeline(refreshed, selectCurrentPosition = true)
        return true
    }

    /**
     * 人工轴在后台查到之后，回主线程补一次。
     *
     * 只做一件事：如果现在还在放同一首，就把歌词换成人手轴并重建时间轴。
     * 已经切歌了就**丢弃** —— 迟到的结果不能盖到新歌头上，那正是"串到上一首"的老毛病。
     */
    private fun applyLateManualAxis(hit: Song, expectName: String?) {
        val cur = currentSong ?: return
        if (expectName.isNullOrBlank() || cur.name != expectName) return
        isTextMode = false
        fullSongLyricsAvailable = hit.lyrics?.any(::hasRenderableLine) == true
        currentLyricMediaMetadata = null
        currentSong = hit
        currentSongName = hit.name
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        versionCounter.incrementAndGet()
        rebuildTimeline(hit, selectCurrentPosition = true)
        HookLogger.d(TAG, "人工轴迟到命中，已重建: ${hit.name}")
    }

    /**
     * Merge resolved Core media fields into the current full Song without delaying its first
     * render. Source-owned Song fields win because only missing values are filled here.
     *
     * The identity context remains outside Song. A changed result starts a new processing
     * version so an earlier plugin callback cannot write over the enriched snapshot.
     */
    fun applyResolvedMediaInfo(mediaInfo: MediaMetadataHelper.MediaInfo): Boolean {
        val song = currentSong ?: return false
        val merged = song.copy(
            name = song.name.orMissingText(mediaInfo.title),
            artist = song.artist.orMissingText(mediaInfo.artist),
            album = song.album.orMissingText(mediaInfo.album),
            duration = if (song.duration > 0L) {
                song.duration
            } else {
                mediaInfo.duration.takeIf { it > 0L } ?: song.duration
            }
        )
        if (merged == song) return false

        currentSong = merged
        currentSongName = merged.name.orMissingText(mediaInfo.title)
        versionCounter.incrementAndGet()
        if (merged.name != song.name || merged.artist != song.artist) {
            rebuildTimeline(merged, selectCurrentPosition = true)
        }
        return true
    }

    /** Clear only streaming lyric content after the resolved media identity changes. */
    fun resetLyricContentForMediaChange() {
        isTextMode = false
        fullSongLyricsAvailable = null
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentPosition = 0L
        versionCounter.incrementAndGet()
    }

    /**
     * Apply a plugin enhancement only while the song generation that produced it is current.
     * The plugin never receives this bridge; Core remains the owner of the final Song and
     * renderer refresh.
     */
    fun applyPluginEnhancement(
        enhancedSong: Song,
        expectedVersion: Int,
        expectedBaseSong: Song,
        changedFields: Set<PluginSongField> = emptySet()
    ): Boolean {
        if (versionCounter.get() != expectedVersion || currentSong !== expectedBaseSong) return false
        currentSong = enhancedSong
        currentSongName = enhancedSong.name
        fullSongLyricsAvailable = enhancedSong.lyrics?.any(::hasRenderableLine) == true
        if (changedFields.any { it in MEDIA_SONG_FIELDS }) {
            currentLyricMediaMetadata?.let { metadata ->
                currentLyricMediaMetadata = metadata.copy(
                    songId = if (PluginSongField.ID in changedFields) {
                        enhancedSong.id
                    } else {
                        metadata.songId
                    },
                    title = if (PluginSongField.NAME in changedFields) {
                        enhancedSong.name
                    } else {
                        metadata.title
                    },
                    artist = if (PluginSongField.ARTIST in changedFields) {
                        enhancedSong.artist
                    } else {
                        metadata.artist
                    },
                    album = if (PluginSongField.ALBUM in changedFields) {
                        enhancedSong.album
                    } else {
                        metadata.album
                    },
                    duration = if (PluginSongField.DURATION in changedFields) {
                        enhancedSong.duration.takeIf { it > 0L }
                    } else {
                        metadata.duration
                    }
                )
            }
        }
        rebuildTimeline(enhancedSong, selectCurrentPosition = true)
        return true
    }

    fun updatePlaceholderFormat(format: Int): Boolean {
        val normalizedFormat = normalizePlaceholderFormat(format)
        if (placeholderFormat == normalizedFormat) return false
        placeholderFormat = normalizedFormat

        val song = currentSong ?: return false
        rebuildTimeline(song, selectCurrentPosition = true)
        return true
    }

    fun updatePosition(position: Long): Boolean {
        currentPosition = position
        if (isTextMode) return false
        val song = currentSong ?: return false
        // 注意：不能因为 currentSong.lyrics 为空就提前返回。
        // 歌名/歌手占位行是 rebuildTimeline 插在副本上的，currentSong 本身仍是
        // provider 推来的空歌词；这里一 return，整首没歌词的歌就永远选不到那一行，
        // 岛上只剩封面。走下面的导航器即可，导航器为空时自然会得到 null。

        // 使用 TimingNavigator 高效定位当前歌词行
        var foundLine: TimedLine? = null
        timingNavigator.forEachAtOrPrevious(position) { timedLine ->
            foundLine = timedLine
        }

        val previousLine = currentLyricLine
        currentLyricLine = foundLine
        currentNextLyricLine = foundLine?.next

        // 尾段超时（移植 HyperLyric）：没有正在唱的行、且位置越过「最后一行结束 + 延迟」才算。
        // 之所以要求 foundLine 为空：对唱里前一行可能比最后一行结束得晚，只看超时点会截断它。
        val wasInTailTimeout = isInTailTimeout
        isInTailTimeout = foundLine == null && position >= tailTimeoutDeadlineMs
        if (isInTailTimeout) {
            if (!wasInTailTimeout) {
                HookLogger.i(
                    TAG,
                    "进入尾段超时: position=$position deadline=$tailTimeoutDeadlineMs " +
                            "behavior=$tailTimeoutBehavior"
                )
            }
            when (tailTimeoutBehavior) {
                RootConstants.TAIL_TIMEOUT_BEHAVIOR_MUSIC_INFO -> {
                    val musicInfoText = tailMusicInfoText()
                    if (musicInfoText.isNotEmpty() && musicInfoText != currentLyric) {
                        currentLyricLine = RichLyricLine(text = musicInfoText)
                        currentNextLyricLine = null
                        currentLyric = musicInfoText
                        return true
                    }
                    return false
                }

                RootConstants.TAIL_TIMEOUT_BEHAVIOR_RESTORE_DEFAULT -> {
                    // 近似"交还原生"：尾段清掉歌词槽，岛上只剩歌名/歌手/封面。
                    if (currentLyric != null || currentLyricLine != null) {
                        currentLyric = null
                        currentLyricLine = null
                        currentNextLyricLine = null
                        return true
                    }
                    return false
                }

                else -> {
                    // KEEP（默认）：最后一行继续挂着，行为与不做超时完全一致。
                    return false
                }
            }
        }

        // 间奏时保持最后一行歌词，不回退到歌名
        val newText = foundLine?.text ?: currentLyric ?: ""
        // 占位符圆点没有文本，不能只靠文本变化判断是否需要刷新。
        // 切歌或切换到同文本歌词时，新的歌词行仍然需要传给渲染器。
        val lineChanged = foundLine != null && foundLine !== previousLine

        if (lineChanged || newText != currentLyric) {
            currentLyric = newText
            return true
        }
        return false
    }

    fun updateLyric(text: String?) {
        // 空文本不是"清空歌词"的指令：保留当前行和当前模式。
        // 否则播放器发一次空串，就会把整首歌词模式打断成空文本模式，
        // 后续位置回调再也定位不到行，岛上表现为"有歌词却一直不显示"。
        if (text.isNullOrBlank()) return
        isTextMode = true
        fullSongLyricsAvailable = null
        currentLyric = text
        currentLyricLine = RichLyricLine(
            text = text.lines().first(),
            translation = text.lines().getOrNull(1)
        )
        currentNextLyricLine = null
    }

    fun updateLyricLine(line: IRichLyricLine) {
        isTextMode = false
        fullSongLyricsAvailable = null
        currentLyricLine = line
        currentNextLyricLine = null
        currentLyric = line.text
    }

    fun clearState() {
        currentSong = null
        currentSongName = null
        currentLyricMediaMetadata = null
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentPosition = 0L
        currentLyricPackageName = null
        isTextMode = false
        fullSongLyricsAvailable = null
        timingNavigator = TimingNavigator(emptyArray())
        tailTimeoutDeadlineMs = Long.MAX_VALUE
        isInTailTimeout = false

        versionCounter.incrementAndGet()
    }

    /**
     * Returns whether the current source has content that justifies owning the island text slots.
     * A whole-song source wins over the current line so an interlude cannot remove the view.
     */
    fun hasLyricsForPresentation(): Boolean {
        fullSongLyricsAvailable?.let { return it }
        return !currentLyric.isNullOrBlank() || hasRenderableLine(currentLyricLine)
    }

    /**
     * 当前**真的有一行可显示歌词**吗（整首歌源是否存在不算）。
     *
     * 给岛侧空壳判定用：间奏、刚换歌还没定位、数据未就绪时 currentLyricLine 就是 null，
     * 这时歌词 View 是空的属于正常，不该判“空壳丢了”去拆重建 ——
     * 否则每 3 秒拆一次、注进去还是空，形成死循环（OS 3 实测 84 次）。
     */
    fun hasCurrentRenderableLine(): Boolean = hasRenderableLine(currentLyricLine)

    private fun hasRenderableLine(line: IRichLyricLine?): Boolean {
        return line != null && (!line.text.isNullOrBlank() || !line.words.isNullOrEmpty())
    }

    private fun rebuildTimeline(song: Song, selectCurrentPosition: Boolean) {
        val processor = SongPreprocessor(resolveTitleSlot(placeholderFormat))
        val prepared = song.deepCopy()
        val lines = processor.prepare(prepared)
        HookLogger.i(
            TAG,
            "时间轴重建: song=${song.name} artist=${song.artist} " +
                    "placeholder=$placeholderFormat lines=${lines.size} " +
                    "first=\"${lines.firstOrNull()?.text}\""
        )
        timingNavigator = TimingNavigator(lines.toTypedArray())
        interludeTracker = InterludeTracker(8_000L)
        // 尾段超时基准跟着时间线重算：换歌、补歌词、切行都会走到这里。
        tailTimeoutDeadlineMs = computeTailTimeoutDeadline(lines)
        isInTailTimeout = false


        if (selectCurrentPosition) {
            currentLyric = null
            currentLyricLine = null
            currentNextLyricLine = null
            updatePosition(currentPosition)
        }
        // 诊断探针：链上行数 + 选中后的当前行，用来判断"有歌名没歌词"到底断在哪一环
        providerSongProbe = "${providerSongProbe}#chain=${lines.size}#cur=${currentLyricLine?.text}"
    }

    /**
     * 由时间线推导尾段超时时刻。
     *
     * 取最后一行结束时刻再加延迟；只有开始时间、没有结束时间的行（end <= begin）
     * 按「开始 + 延迟 + TAIL_TIMEOUT_BEGIN_ONLY_EXTRA_SECONDS」估一个结束点。
     * 没有可用歌词行时返回 Long.MAX_VALUE，即永不超时 —— 没词时本来就该一直挂着歌名。
     */
    private fun computeTailTimeoutDeadline(lines: List<TimedLine>): Long {
        val last = lines.lastOrNull() ?: return Long.MAX_VALUE
        val end = if (last.end > last.begin) {
            last.end
        } else {
            last.begin + RootConstants.TAIL_TIMEOUT_BEGIN_ONLY_EXTRA_SECONDS * 1000L
        }
        return end + tailTimeoutDelaySeconds * 1000L
    }

    /** 尾段要显示的音乐信息文本：「歌名 - 歌手」，缺项时退回单项。 */
    private fun tailMusicInfoText(): String {
        val title = currentSongName?.takeIf(String::isNotBlank)
            ?: currentSong?.name?.takeIf(String::isNotBlank)
        val artist = currentLyricMediaMetadata?.artist?.takeIf(String::isNotBlank)
        return listOfNotNull(title, artist).joinToString(" - ")
    }

    private fun normalizePlaceholderFormat(format: Int): Int {
        return when (format) {
            RootConstants.PLACEHOLDER_FORMAT_NONE,
            RootConstants.PLACEHOLDER_FORMAT_TITLE_ARTIST,
            RootConstants.PLACEHOLDER_FORMAT_TITLE,
            RootConstants.PLACEHOLDER_FORMAT_COUNTDOWN -> format

            else -> RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
        }
    }

    private fun resolveTitleSlot(format: Int): TitleSlot {
        return when (format) {
            RootConstants.PLACEHOLDER_FORMAT_NONE -> TitleSlot.NONE
            RootConstants.PLACEHOLDER_FORMAT_TITLE -> TitleSlot.NAME
            // 倒计时功能已删除：即使选了倒计时，也显示歌名
            RootConstants.PLACEHOLDER_FORMAT_COUNTDOWN -> TitleSlot.NAME
            else -> TitleSlot.NAME_ARTIST
        }
    }

    private fun String?.orMissingText(fallback: String): String? =
        this?.takeIf { it.isNotBlank() } ?: fallback.takeIf { it.isNotBlank() }

    private val MEDIA_SONG_FIELDS = setOf(
        PluginSongField.ID,
        PluginSongField.NAME,
        PluginSongField.ARTIST,
        PluginSongField.ALBUM,
        PluginSongField.DURATION,
        PluginSongField.METADATA
    )

}


