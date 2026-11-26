/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * 由老词幕（lyricon-master）整文件搬入，控制流、状态机、两轮分发、纯文本模式、
 * provider 策略、版本号防过时全部保持原样。
 * 相对原文件只有三处替换：
 *   1. LyricPrefs.getLyricStyle()（style 模块）→ LyricStyleBridge.textStyle()（词岛 prefs）；
 *   2. LyricDataProcessor 用搬入的同名引擎（签名一致，style 类型为 LyricTextStyle）；
 *   3. ProviderLyricsPreferences 用词岛 prefs 版本（read 语义一致）。
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.util.Log
import io.github.proify.lyricon.app.bridge.ProviderLyricPolicy
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.xposed.systemui.lyric.processor.LyricDataProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * 歌词数据调度中枢
 * 管理歌词生命周期：获取原始数据 -> 后台加工 -> 第一次分发 -> 后台增强 -> 最终分发。
 */
object LyricDataHub : ActivePlayerListener {
    private const val TAG = "LyricDataHub"

    private val listeners = CopyOnWriteArraySet<ActivePlayerListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 状态版本号，用于在异步恢复后校验数据是否已过时 */
    private val versionCounter = AtomicInteger(0)

    /** 缓存当前的原始歌曲，用于配置变更时重走流程 */
    private var cachedRawSong: Song? = null

    /** 当前执行中的流水线任务 */
    private var activePipelineJob: Job? = null

    /** 当前活动的提供者信息 */
    private var providerInfo: ProviderInfo? = null
    @Volatile var providerPolicy = ProviderLyricPolicy()
        private set
    @Volatile private var lastPosition = 0L
    private var isTextMode = false
    private var cachedRawText: String? = null

    /**
     * 上一次加工用的「文本加工配置」指纹：简繁模式 + 屏蔽词正则。
     *
     * 简繁 / 屏蔽词只在加工流水线里改歌词，改了设置必须让加工重走一遍才生效；
     * 而设置刷新有两条到达路径（跨进程 prefs 监听 + App 的配置广播），
     * 用这个指纹做幂等，两条路都来也只重算一次。
     */
    @Volatile private var textProcessingKey: String? = null

    /**
     * 重算后是否要补发一次当前位置。
     *
     * 显示层收到新的 Song 会把「当前行」清掉、等着按位置重选；
     * 播放中位置回调很密看不出来，暂停或 provider 推位置稀疏时就一直空着 ——
     * 现场表现就是「切了简繁，岛上的歌词没了」。所以分发完补一次位置。
     */
    @Volatile private var resendPositionAfterDispatch = false

    private fun currentTextProcessingKey(): String =
        "${LyricStyleBridge.chineseConversionMode}|" +
                (LyricStyleBridge.blockedWordsRegex?.pattern ?: "")
    private var playing = false
    private val textScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var textGeneration = 0
    private var textSequence = 0L
    private var lastDeliveredTextSequence = 0L

    fun lyricPosition(position: Long): Long = providerPolicy.lyricPosition(position)

    @Synchronized fun refreshProviderPolicy() {
        val next = ProviderLyricsPreferences.read(providerInfo?.providerPackageName)
        if (next == providerPolicy) return
        providerPolicy = next
        reprocessCurrentSong()
        listeners.forEach { it.onSeekTo(lastPosition) }
    }

    private fun cancelPendingText() {
        textGeneration++
        textScope.coroutineContext.cancelChildren()
    }

    fun addListener(listener: ActivePlayerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ActivePlayerListener) {
        listeners.remove(listener)
    }

    /**
     * 启动后台加工流水线。
     * 所有歌词加工都在后台协程执行，避免阻塞播放器回调线程。
     * @param rawSong 待加工的原始歌曲
     */
    @Synchronized private fun runProcessingPipeline(rawSong: Song?) {
        val song = rawSong?.deepCopy()
        val policy = providerPolicy
        val currentVersion = versionCounter.incrementAndGet()
        activePipelineJob?.cancel()

        activePipelineJob = scope.launch {
            try {
                if (song == null || policy.blocksEverything) {
                    dispatchSong(null, currentVersion, policy)
                    return@launch
                }

                val style = LyricStyleBridge.textStyle()
                // 记下本次加工用的文本配置，供 reprocessIfTextProcessingChanged 做幂等判断
                textProcessingKey = currentTextProcessingKey()

                // 1. 后台前置加工：处理繁简、屏蔽词等基础显示数据
                val preProcessed = LyricDataProcessor.executePreProcessing(song)
                if (!isCurrentVersion(currentVersion)) return@launch

                // 第一次分发：基础处理完成后尽快刷新 UI
                dispatchSong(LyricDataProcessor.executeDisplayProcessing(preProcessed, style), currentVersion, policy)

                // 2. 后台后置流水线：处理 AI 翻译等耗时扩展
                val finalSong =
                    LyricDataProcessor.executePostProcessingPipeline(preProcessed, style)

                if (isCurrentVersion(currentVersion)) {
                    dispatchSong(LyricDataProcessor.executeDisplayProcessing(finalSong, style), currentVersion, policy)
                }
            } catch (e: CancellationException) {
                // 切歌 / 重算引起的取消属正常流程，不输出日志
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline $currentVersion error", e)
            }
        }
    }

    private fun isCurrentVersion(version: Int): Boolean = version == versionCounter.get()

    /**
     * 重走加工流程
     * 当配置（繁简、AI 开关、翻译模式）变更时调用，无需切歌即可应用新设置。
     */
    @Synchronized fun reprocessCurrentSong() {
        if (isTextMode) {
            cancelPendingText()
            listeners.forEach { it.onReceiveText(null) }
            scheduleText(cachedRawText)
        } else {
            // 重算会换掉整份 Song，显示层会把当前行清掉等着按位置重选，
            // 所以分发完要补发一次位置，否则暂停时守在那儿一直是空的。
            resendPositionAfterDispatch = true
            runProcessingPipeline(cachedRawSong)
        }
    }

    /**
     * 只在「文本加工配置」真的变了时重走加工。
     *
     * 设置变更同时有两条到达路径（跨进程 prefs 监听 + App 配置广播），而且广播对
     * 任何设置键都会来一次；靠这个指纹做幂等，避免每次改别的东西都白白重算整首歌词。
     *
     * @return 本次是否真的重算过
     */
    @Synchronized fun reprocessIfTextProcessingChanged(): Boolean {
        val key = currentTextProcessingKey()
        if (key == textProcessingKey) return false
        reprocessCurrentSong()
        return true
    }

    // --- ActivePlayerListener 触发点 ---

    @Synchronized override fun onSongChanged(song: Song?) {
        cancelPendingText()
        isTextMode = false
        cachedRawText = null
        this.cachedRawSong = song?.deepCopy()
        resendPositionAfterDispatch = false
        runProcessingPipeline(song)
    }

    private var lastDispatchSongId: Int? = null

    @Synchronized private fun dispatchSong(song: Song?, version: Int, policy: ProviderLyricPolicy) {
        if (!isCurrentVersion(version)) return
        val normalize = filterProviderLyrics(song?.normalize(), policy)

        val hashCode = normalize?.hashCode() ?: 0
        if (hashCode == lastDispatchSongId) {
            // 内容没变就不用重发；但要补的位置还是补上（重算请求可能正是因为别的原因来的）
            if (resendPositionAfterDispatch) {
                resendPositionAfterDispatch = false
                listeners.forEach { it.onSeekTo(lastPosition) }
            }
            return
        }
        lastDispatchSongId = hashCode

        listeners.forEach { it.onSongChanged(normalize) }
        if (resendPositionAfterDispatch) {
            resendPositionAfterDispatch = false
            listeners.forEach { it.onSeekTo(lastPosition) }
        }
    }

    // --- 纯状态透传 (不涉及加工) ---

    @Synchronized override fun onReceiveText(text: String?) {
        if (!isTextMode) {
            versionCounter.incrementAndGet()
            activePipelineJob?.cancel()
            cancelPendingText()
            isTextMode = true
            cachedRawSong = null
            lastDispatchSongId = null
            listeners.forEach { it.onSongChanged(null) }
        }
        cachedRawText = text
        if (text.isNullOrBlank()) cancelPendingText()
        scheduleText(text)
    }

    private fun scheduleText(text: String?) {
        val policy = providerPolicy
        // 纯文本模式同样要遵守「隐藏翻译 / 仅显示翻译」：第二行即翻译行。
        val filtered = applyTextDisplay(policy.filterText(text))
        val wait = if (playing && filtered != null) policy.delayMs.coerceAtLeast(0).toLong() else 0L
        val sequence = ++textSequence
        if (wait == 0L) {
            lastDeliveredTextSequence = sequence
            listeners.forEach { it.onReceiveText(filtered) }
            return
        }
        val generation = textGeneration
        // Canceling on every update could starve a delayed fast stream. Never overwrite a newer line.
        textScope.launch {
            delay(wait)
            synchronized(this@LyricDataHub) {
                if (generation == textGeneration && isTextMode && sequence > lastDeliveredTextSequence) {
                    lastDeliveredTextSequence = sequence
                    listeners.forEach { it.onReceiveText(filtered) }
                }
            }
        }
    }

    private fun applyTextDisplay(text: String?): String? = runCatching {
        LyricDataProcessor.executeTextDisplayProcessing(text, LyricStyleBridge.textStyle())
    }.getOrElse {
        Log.e(TAG, "Text display processing failed", it)
        text
    }

    @Synchronized override fun onPlaybackStateChanged(isPlaying: Boolean) {
        playing = isPlaying
        if (!isPlaying) cancelPendingText()
        listeners.forEach { it.onPlaybackStateChanged(isPlaying) }
    }

    override fun onPositionChanged(position: Long) {
        lastPosition = position
        listeners.forEach { it.onPositionChanged(position) }
    }

    @Synchronized override fun onSeekTo(position: Long) {
        lastPosition = position
        cancelPendingText()
        listeners.forEach { it.onSeekTo(position) }
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        listeners.forEach { it.onDisplayTranslationChanged(isDisplayTranslation) }
    }

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
        listeners.forEach { it.onDisplayRomaChanged(isDisplayRoma) }
    }

    @Synchronized override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        if (this.providerInfo != providerInfo) {
            versionCounter.incrementAndGet()
            activePipelineJob?.cancel()
            cancelPendingText()
            cachedRawSong = null
            cachedRawText = null
            isTextMode = false
            lastDispatchSongId = null
            resendPositionAfterDispatch = false
            lastPosition = 0
        }
        this.providerInfo = providerInfo
        providerPolicy = ProviderLyricsPreferences.read(providerInfo?.providerPackageName)
        listeners.forEach { it.onActiveProviderChanged(providerInfo) }
    }
}
