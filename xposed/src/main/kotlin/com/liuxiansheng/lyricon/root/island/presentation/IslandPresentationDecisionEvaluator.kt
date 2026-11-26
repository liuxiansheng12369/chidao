/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.presentation

import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.root.HookEntry
import com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandProbeUtils

/**
 * 展示决策评估器（V3 自研）。
 */
internal class IslandPresentationDecisionEvaluator(
    private val presentationState: IslandPresentationState
) {
    /** 应用过滤日志去重（同一文案 3 秒内只打一条）。 */
    private var lastFilterLogText: String = ""
    private var lastFilterLogAt: Long = 0L
    fun ownerEvidence(data: Any?): IslandRenderPolicy.OwnerEvidence {
        if (data == null) return IslandRenderPolicy.OwnerEvidence.Pending
        val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(data)
        if (mediaInfo != null) {
            return IslandRenderPolicy.OwnerEvidence.Media(mediaInfo.packageName)
        }
        return if (IslandProbeUtils.isMediaIsland(data)) {
            IslandRenderPolicy.OwnerEvidence.Pending
        } else {
            IslandRenderPolicy.OwnerEvidence.NotMedia
        }
    }

    fun isCurrentLyricOwner(mediaInfo: IslandProbeUtils.MediaIslandInfo): Boolean {
        val lyricPackageName = LyriconDataBridge.currentLyricPackageName
            ?.takeIf(String::isNotEmpty)
            ?: return false
        return mediaInfo.packageName == lyricPackageName
    }

    /**
     * 长按仍绑定当前歌词岛（暂停时也如此）。展示策略可能暂停后隐藏注入视图，
     * 但不能让仍可见的原生岛失去拖拽分享/播放切换动作。
     */
    fun isCurrentLyricLongPressTarget(data: Any?): Boolean {
        if (!IslandProbeUtils.isSuperIslandEnabled()) return false
        if (!LyriconDataBridge.hasLyricsForPresentation()) return false
        val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(data) ?: return false
        return isCurrentLyricOwner(mediaInfo)
    }

    fun shouldRenderInjectedIsland(): Boolean {
        return IslandRenderPolicy.isPresentationAllowed(
            enabled = IslandProbeUtils.isSuperIslandEnabled(),
            playbackActive = presentationState.isPlaybackActive(),
            pauseBehavior = currentPauseBehavior()
        )
    }

    fun evaluate(owner: IslandRenderPolicy.OwnerEvidence): IslandRenderPolicy.Decision {
        // 应用过滤先判：被挡的应用连"歌词注入"这一步都不做，岛交还系统原生。
        filterDecision(owner)?.let { return it }

        val decision = IslandRenderPolicy.evaluate(
            IslandRenderPolicy.Input(
                owner = owner,
                lyricPackageName = LyriconDataBridge.currentLyricPackageName,
                hasLyricsForPresentation = LyriconDataBridge.hasLyricsForPresentation(),
                enabled = IslandProbeUtils.isSuperIslandEnabled(),
                playbackActive = presentationState.isPlaybackActive(),
                pauseBehavior = currentPauseBehavior()
            )
        )
        // 空壳保护：既没有歌词、也没有歌名歌手时，注入出去的只是一块空白，
        // 而原生内容已经被遮住 —— 表现就是"连歌名都不显示"。
        // 这种状态直接把岛交还原生渲染，等有内容再接管。
        if (decision == IslandRenderPolicy.Decision.TARGET ||
            decision == IslandRenderPolicy.Decision.PENDING
        ) {
            if (!hasAnyDisplayableText()) return IslandRenderPolicy.Decision.SUPPRESSED
        }
        return decision
    }

    /**
     * 应用过滤前置判定。
     *
     * 返回 null = 过滤不干预，继续走原有展示策略；
     * 返回 SUPPRESSED = 这个媒体岛的歌词不该由我们铺，交还系统原生。
     */
    private fun filterDecision(owner: IslandRenderPolicy.OwnerEvidence): IslandRenderPolicy.Decision? {
        if (!IslandLyricAppFilter.isEnabled()) return null
        val ownerPkg = (owner as? IslandRenderPolicy.OwnerEvidence.Media)?.packageName
            ?.takeIf(String::isNotEmpty) ?: return null
        val lyricPkg = LyriconDataBridge.currentLyricPackageName?.takeIf(String::isNotEmpty)

        // ① 正在出词的包自己被过滤（视频/工具类把歌词槽占了）→ 不注入
        if (lyricPkg != null && !IslandLyricAppFilter.isAllowed(lyricPkg)) {
            logFilterOnce("歌词包被过滤 pkg=$lyricPkg（${IslandLyricAppFilter.describe()}）→ 不注入歌词")
            return IslandRenderPolicy.Decision.SUPPRESSED
        }

        // ② 岛归这个媒体包，但它不允许出歌词 → 不注入。
        //    特例（对齐「词幕」）：边看视频边听歌时，歌词包是另一个被允许的包且有词，
        //    保留注入，避免把正在听的歌的歌词一起清掉。
        if (!IslandLyricAppFilter.isAllowed(ownerPkg)) {
            val keepMusicLyric = lyricPkg != null &&
                lyricPkg != ownerPkg &&
                IslandLyricAppFilter.isAllowed(lyricPkg) &&
                LyriconDataBridge.hasLyricsForPresentation()
            if (!keepMusicLyric) {
                logFilterOnce(
                    "媒体包被过滤 pkg=$ownerPkg（${IslandLyricAppFilter.describe()}）→ 不注入歌词"
                )
                return IslandRenderPolicy.Decision.SUPPRESSED
            }
        }
        return null
    }

    /** 过滤日志只按"文案变化或隔 3 秒"打一条，避免系统每次更新都刷屏。 */
    private fun logFilterOnce(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastFilterLogText && now - lastFilterLogAt < 3000L) return
        lastFilterLogText = message
        lastFilterLogAt = now
        com.liuxiansheng.lyricon.hyper.root.utils.HookLogger.i("IslandAppFilter", message)
    }

    /** 当前是否有任何能落到岛上的文字（歌词行或歌名歌手标题）。 */
    private fun hasAnyDisplayableText(): Boolean {        if (LyriconDataBridge.hasLyricsForPresentation()) return true
        if (!LyriconDataBridge.currentSongName.isNullOrBlank()) return true
        if (!LyriconDataBridge.currentLyricMediaMetadata?.title.isNullOrBlank()) return true
        if (!LyriconDataBridge.currentLyricMediaMetadata?.artist.isNullOrBlank()) return true
        return false
    }

    private fun currentPauseBehavior(): Int {
        return HookEntry.instance?.prefs?.getInt(
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
        ) ?: RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
    }
}
