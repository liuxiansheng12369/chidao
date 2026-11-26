/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.host

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import com.liuxiansheng.lyricon.hyper.common.SuperIslandContentStylePolicy
import com.liuxiansheng.lyricon.hyper.common.media.MediaMetadataHelper
import com.liuxiansheng.lyricon.hyper.root.island.effects.glow.IslandProgressGlowController
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger

internal object IslandHostFacade {
    fun applyHostSettings(rootView: ViewGroup, prefs: SharedPreferences) {
        val albumStyle = SuperIslandContentStylePolicy.readAlbumCoverStyle(prefs)
        val showAlbum = SuperIslandContentStylePolicy.isAlbumCoverVisible(
            albumStyle
        )
        val rhythmStyle = SuperIslandContentStylePolicy.readMusicWaveStyle(prefs)
        val showRhythm = SuperIslandContentStylePolicy.isMusicWaveVisible(
            rhythmStyle
        )
        val rootId = System.identityHashCode(rootView)
        HookLogger.dState(
            stateId = "IslandHostFacade:$rootId",
            tag = "IslandHostFacade",
            state = "$albumStyle|$rhythmStyle|$showAlbum|$showRhythm"
        ) {
            "宿主显示配置: root=$rootId, albumStyle=$albumStyle, showAlbum=$showAlbum, " +
                    "musicWaveStyle=$rhythmStyle, showRhythm=$showRhythm"
        }

        IslandViewHelper.toggleContainer(
            rootView,
            IslandProbeUtils.LEFT_PARENT_NAME,
            "island_container_module_icon",
            showAlbum
        )
        IslandViewHelper.toggleContainer(
            rootView,
            IslandProbeUtils.RIGHT_PARENT_NAME,
            "island_container_module_icon",
            showRhythm
        )
        IslandViewHelper.toggleContainer(
            rootView,
            IslandProbeUtils.LEFT_PARENT_NAME,
            IslandProbeUtils.TEXT_CONTAINER_NAME,
            true
        )
        IslandViewHelper.toggleContainer(
            rootView,
            IslandProbeUtils.RIGHT_PARENT_NAME,
            IslandProbeUtils.TEXT_CONTAINER_NAME,
            true
        )

        if (!showAlbum) {
            IslandViewHelper.clearTextContainerMargin(
                rootView,
                IslandProbeUtils.LEFT_PARENT_NAME,
                clearStart = true,
                clearEnd = false
            )
        }
        if (!showRhythm) {
            IslandViewHelper.clearTextContainerMargin(
                rootView,
                IslandProbeUtils.RIGHT_PARENT_NAME,
                clearStart = false,
                clearEnd = true
            )
        }
    }

    fun clearInjectedViews(rootView: ViewGroup): Boolean {
        val changed = IslandViewHelper.clearInjectedViews(rootView)
        IslandProgressGlowController.clear(rootView)
        return changed
    }

    fun triggerSystemRelayout(rootView: ViewGroup) {
        IslandViewHelper.triggerSystemRelayout(rootView)
    }

    fun showFrozenSnapshot(rootView: ViewGroup) {
        rootView.alpha = 1f
    }

    fun showRealHost(rootView: ViewGroup) {
        rootView.visibility = View.VISIBLE
        (IslandTextHookerSupport.callNoArgMethodResult(
            rootView,
            "getBackgroundView"
        ) as? View)?.visibility = View.VISIBLE
    }

    fun updateProgressGlow(
        rootView: ViewGroup,
        packageName: String,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        prefs: SharedPreferences
    ) {
        IslandProgressGlowController.update(rootView, packageName, mediaInfo, prefs)
    }

    fun updateProgressGlow(
        rootView: ViewGroup,
        packageName: String,
        prefs: SharedPreferences
    ) {
        IslandProgressGlowController.update(rootView, packageName, null, prefs)
    }
}
