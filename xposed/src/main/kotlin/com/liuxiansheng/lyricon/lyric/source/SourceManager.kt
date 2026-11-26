/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.source

import android.content.SharedPreferences
import com.liuxiansheng.lyricon.hyper.common.HyperLogger
import com.liuxiansheng.lyricon.hyper.lyric.model.LyricMediaMetadata
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import com.liuxiansheng.lyricon.hyper.lyric.model.interfaces.IRichLyricLine

/**
 * 歌词源的总开关：决定用哪个 [LyricSource]，并且保证同一时刻只有一个源在跑。
 *
 * 换源时先把旧源停掉再启新的，中间所有回调都会被 [SessionGate] 掐断，
 * 避免旧源在停止过程中甩出来的半截数据污染下游。
 *
 * 选源规则：优先用户配置的那个；它不存在或此刻不可用，就退到第一个可用的源。
 *
 * @param prefKey 用户选择的源 id 存在哪个 key
 * @param defaultSourceId 没配置过时用哪个源
 */
class SourceManager(
    private val sources: List<LyricSource>,
    private val prefs: SharedPreferences,
    private val sink: LyricSink,
    private val prefKey: String,
    private val defaultSourceId: String,
    private val logger: HyperLogger
) {

    private var activeSource: LyricSource? = null

    @Volatile
    private var activeGate: SessionGate? = null

    /** 按用户配置启动。重复调用无副作用。 */
    fun start() {
        if (activeSource != null) return

        val wantedId = prefs.getString(prefKey, defaultSourceId) ?: defaultSourceId
        val source = pickUsable(wantedId) ?: fallbackUsable()
        if (source == null) {
            logger.w(TAG, "没有可用的歌词源")
            return
        }
        if (source.id != wantedId) {
            logger.d(TAG, "歌词源配置回退: requested=$wantedId, actual=${source.id}, reason=unavailable")
        }
        logger.i(TAG, "启动歌词源: ${source.displayName}")
        launch(source)
    }

    /** 切到指定源；本来就是它则忽略。 */
    fun switchSource(sourceId: String) {
        if (activeSource?.id == sourceId) return
        teardown()
        val source = pickUsable(sourceId)
        if (source == null) {
            logger.w(TAG, "歌词源不可用: $sourceId")
            return
        }
        logger.i(TAG, "切换歌词源: ${source.displayName}")
        launch(source)
    }

    fun getActiveSource(): LyricSource? = activeSource

    /** 停止当前源并清空回调。 */
    fun stop() {
        teardown()
    }

    /** 指定 id 且此刻可用的源。 */
    private fun pickUsable(sourceId: String): LyricSource? =
        sources.firstOrNull { it.id == sourceId && it.isAvailable() }

    /** 配置的源不可用时，退到任意一个可用的源。 */
    private fun fallbackUsable(): LyricSource? =
        sources.firstOrNull { it.isAvailable() }

    /** 建立门禁并启动源；启动失败要把状态回滚干净。 */
    private fun launch(source: LyricSource) {
        val gate = SessionGate(sink)
        activeSource = source
        activeGate = gate
        try {
            source.start(gate)
        } catch (e: Exception) {
            gate.revoke()
            activeGate = null
            activeSource = null
            runCatching { source.stop() }
            sink.onStop()
            logger.e(TAG, "歌词源启动失败: source=${source.id}", e)
        }
    }

    /**
     * 停源。
     *
     * 先把门禁作废再调 `stop()` —— 这样源在停止过程中回吐的任何数据都进不来，
     * 下游只会收到一次干净的 `onStop()`。
     */
    private fun teardown() {
        val source = activeSource
        val gate = activeGate
        if (source == null && gate == null) return
        activeSource = null
        activeGate = null
        gate?.revoke()

        try {
            source?.stop()
        } catch (e: Exception) {
            logger.e(TAG, "歌词源停止失败: source=${source?.id}", e)
        } finally {
            sink.onStop()
        }
    }

    /**
     * 每个源独占一道门禁：源停了以后它的迟到回调一律丢弃。
     */
    private class SessionGate(private val downstream: LyricSink) : LyricSink {

        private val lock = Any()

        @Volatile
        private var open = true

        /** 关门：后续回调全部静默丢弃。 */
        fun revoke() {
            synchronized(lock) { open = false }
        }

        private fun forward(action: (LyricSink) -> Unit) {
            synchronized(lock) {
                if (open) action(downstream)
            }
        }

        override fun onSongChanged(song: Song?) = forward { it.onSongChanged(song) }

        override fun onLyricLine(line: IRichLyricLine) = forward { it.onLyricLine(line) }

        override fun onPlainText(text: String?) = forward { it.onPlainText(text) }

        override fun onStop() = forward { it.onStop() }

        override fun onMetadata(metadata: LyricMediaMetadata?) = forward { it.onMetadata(metadata) }

        override fun onPlaybackStateChanged(isPlaying: Boolean, playbackSpeed: Float) =
            forward { it.onPlaybackStateChanged(isPlaying, playbackSpeed) }

        override fun onPositionChanged(position: Long, playbackSpeed: Float) =
            forward { it.onPositionChanged(position, playbackSpeed) }
    }

    private companion object {
        const val TAG = "SourceManager"
    }
}
