/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.renderer

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.lyric.render.view.RichLyricLineView
import com.liuxiansheng.lyricon.lyric.render.view.SpaceGateRichLyricLineView
import com.liuxiansheng.lyricon.hyper.root.HookEntry
import com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge
import com.liuxiansheng.lyricon.hyper.root.island.config.IslandSlotRuntimeConfig
import com.liuxiansheng.lyricon.hyper.root.island.effects.glow.IslandProgressGlowController
import com.liuxiansheng.lyricon.hyper.root.island.hooks.MediaIslandCreationGate
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandPresentationCoordinator
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandReconcileReason
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger

/**
 * 播放状态协调器（V3 自研）。
 *
 * 播放策略（暂停隐藏 / 暂停保留）由这里执行：
 * - 暂停且行为=0（暂停隐藏）：清空活动视图（冻结歌词）；
 * - 暂停且行为≠0：只置 playbackActive=false（保留显示）；
 * - 恢复播放：若曾被暂停清空，走宿主对账恢复并置 playbackActive=true。
 */
internal object IslandPlaybackStateCoordinator {
    /**
     * 1.5.156：暂停摘岛的确认窗口。
     *
     * 切歌/换源时播放器会先发一次 STATE_NONE/STOPPED 再回 PLAYING。1.5.155 是"立即摘岛"，
     * 于是切歌也把系统岛拆了重建 —— 新实例上的点击监听与旧实例对不上，表现就是
     * "换一首歌之后大岛点不动、进度条拖不动"。
     *
     * 现在改成等一下再摘：到点时只要已经回到播放（= 切歌），整条链路一律不动 ——
     * 岛、歌词、监听、进度全都不碰，避免重建；只有到点仍是暂停，才判定用户真按了暂停并摘岛。
     * 这里只判"有没有回到播放"，不比对歌名：歌名在换源/暂停瞬间会被清掉，拿它当判据会把
     * 真暂停误判成切歌，那正是之前"暂停后两个手电筒又回来"的原因。
     */
    private const val PAUSE_CONFIRM_MS = 500L

    private var clearedByPause = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var lastPlaying = true
    private var pendingPauseRemove: Runnable? = null

    fun markClearedByPause() {
        clearedByPause = true
    }

    fun onPlaybackStateChanged(
        isPlaying: Boolean,
        onRefreshRequested: () -> Unit,
        onClearRequested: () -> Unit
    ) {
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
            )
        ) {
            HookLogger.dState(
                stateId = "IslandPlaybackStateCoordinator.policy",
                tag = "IslandPlaybackStateCoordinator",
                state = "disabled|$isPlaying"
            ) {
                "播放策略未执行: reason=super_island_disabled, isPlaying=$isPlaying"
            }
            onClearRequested()
            return
        }

        val stateChanged = IslandPresentationCoordinator.updatePlaybackState(isPlaying)
        if (stateChanged) {
            IslandProgressGlowController.onPlaybackStateChanged(isPlaying)
        }
        val behavior = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
        )
        val wasClearedByPause = clearedByPause
        lastPlaying = isPlaying

        if (isPlaying) {
            if (behavior == 2) {
                // 1.5.156：切歌会瞬时落到暂停再回来，先把那次待摘撤掉——切歌不拆岛。
                cancelPendingPauseRemove()
                // 1.5.159：只有"上一轮确实被我们摘掉了"才建回来（暂停 → 用户点播放）。
                // 切歌这条路我们一次都没碰岛，就一律不建 —— 切歌瞬间不会冒出空壳。
                // 而且 forceRecreate 内部本来就会对"岛还在"的情况直接 return，这里是双保险。
                if (stateChanged && clearedByPause) {
                    clearedByPause = false
                    MediaIslandCreationGate.forceRecreate("恢复播放自动弹出")
                } else if (stateChanged) {
                    // 2.1.9：暂停摘岛的兜底。岛不一定是被我们摘的——宿主自己缩回隐藏、
                    // 壳层重建、或 removeCurrentIsland 返回 false，都会让 clearedByPause 没置上。
                    // 标记为 false 并不代表岛还在，所以这里以结果为准：恢复播放时若一个
                    // 注入宿主都没有，直接重建，不再只能靠重启音乐/重启模块救回来。
                    val fallbackPkg = LyriconDataBridge.currentLyricPackageName
                        ?.takeIf { it.isNotEmpty() }
                    val attached = IslandPresentationCoordinator
                        .snapshotAttachedInjectedHosts(fallbackPkg)
                    if (attached.isEmpty()) {
                        MediaIslandCreationGate.forceRecreate("恢复播放兜底重建")
                    }
                }
            } else if (clearedByPause) {
                clearedByPause = false
                restoreActiveViewsAfterPause(onRefreshRequested)
            } else if (stateChanged) {
                applyPlaybackStateToActiveViews(true)
            }
        } else if (behavior == 2) {
            // 暂停：等一下再摘。切歌换源也会瞬时落进这里，窗口内回到播放就整条撤销、什么都不动；
            // 到点仍是暂停，才判定用户真暂停并摘岛（摘掉后建岛由源头拦截，不会造空壳）。
            if (stateChanged) {
                schedulePauseRemove()
            }
        } else if (behavior == 0) {
            if (!clearedByPause) {
                clearActiveViewsForPauseInternal()
            }
        } else if (stateChanged) {
            applyPlaybackStateToActiveViews(false)
        }
        val action = when {
            behavior == 2 && !isPlaying && stateChanged -> "schedule_remove_on_pause"
            behavior == 2 && isPlaying && stateChanged -> "schedule_force_recreate_on_resume"
            isPlaying && wasClearedByPause -> "restore_after_pause"
            isPlaying && stateChanged -> "resume_active_views"
            !isPlaying && behavior == 0 && !wasClearedByPause -> "clear_on_pause"
            !isPlaying && stateChanged -> "freeze_active_views"
            else -> "no_change"
        }
        HookLogger.dState(
            stateId = "IslandPlaybackStateCoordinator.policy",
            tag = "IslandPlaybackStateCoordinator",
            state = "$isPlaying|$behavior|$stateChanged|$clearedByPause|$action"
        ) {
            "播放策略已处理: isPlaying=$isPlaying, pauseBehavior=$behavior, " +
                    "stateChanged=$stateChanged, clearedByPause=$clearedByPause, action=$action"
        }
    }

    /** 暂停后恢复：宿主对账 + 置 playbackActive=true。 */
    private fun restoreActiveViewsAfterPause(onRefreshRequested: () -> Unit) {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val expectedLyricVersion = LyriconDataBridge.versionCounter.get()
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()
        val activeViews = IslandPresentationCoordinator.snapshotAttachedHosts(lyricPkg)
        if (activeViews.isEmpty()) {
            HookLogger.dState(
                stateId = "IslandPlaybackStateCoordinator.restore",
                tag = "IslandPlaybackStateCoordinator",
                state = "no_hosts"
            ) {
                "暂停恢复未找到宿主: reason=no_attached_hosts, package=$lyricPkg"
            }
            onRefreshRequested()
            return
        }

        activeViews.forEach { token ->
            if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                return@forEach
            }
            val view = token.root
            view.post {
                if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                    !IslandPresentationCoordinator.shouldRenderInjectedIsland() ||
                    !IslandPresentationCoordinator.isCurrentPresentation(
                        expectedPresentationRevision
                    ) ||
                    LyriconDataBridge.versionCounter.get() != expectedLyricVersion ||
                    LyriconDataBridge.currentLyricPackageName != lyricPkg
                ) {
                    return@post
                }

                val result = IslandPresentationCoordinator.reconcileRegisteredHost(
                    token,
                    IslandReconcileReason.PLAYBACK_RESUME,
                    expectedPresentationRevision
                )
                if (!result.isTarget) return@post
                val currentPrefs = HookEntry.instance?.prefs ?: return@post
                val config = IslandSlotRuntimeConfig.from(currentPrefs)
                val expectsInjectedView =
                    config.leftMode != RootConstants.ISLAND_CONTENT_MODE_NONE ||
                            config.rightMode != RootConstants.ISLAND_CONTENT_MODE_NONE
                if (expectsInjectedView && result.mutation.injectedSlotsPresent == false) {
                    onRefreshRequested()
                    return@post
                }

                setPlaybackActiveRecursively(view, true)
            }
        }
    }

    private fun cancelPendingPauseRemove() {
        pendingPauseRemove?.let { mainHandler.removeCallbacks(it) }
        pendingPauseRemove = null
    }

    /**
     * 1.5.156：暂停摘岛的延迟确认。
     *
     * 判据只有一个：到点时还停在播放吗？回播了就是切歌（不摘、不重建），
     * 仍暂停就是用户按了暂停（摘岛，靠 MediaIslandCreationGate 源头拦住后续创建）。
     */
    private fun schedulePauseRemove() {
        cancelPendingPauseRemove()
        val r = Runnable {
            pendingPauseRemove = null
            if (lastPlaying) {
                HookLogger.i("IslandPlaybackStateCoordinator", "暂停确认：窗口内已回到播放（切歌换源）→ 不摘岛、不重建")
                return@Runnable
            }
            val removed = MediaIslandCreationGate.removeCurrentIsland("暂停不生成超级岛")
            // 1.5.159：记下"这次岛是我们自己摘掉的"。恢复播放分支只认这个标记——
            // 切歌时我们从头到尾没碰岛，就不该去 forceRecreate（那正是切歌瞬间冒出空壳的来源）。
            if (removed) clearedByPause = true
        }
        pendingPauseRemove = r
        mainHandler.postDelayed(r, PAUSE_CONFIRM_MS)
    }

    fun clearActiveViewsForPause() {
        if (clearedByPause) return
        clearActiveViewsForPauseInternal()
    }

    private fun clearActiveViewsForPauseInternal() {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()
        IslandPresentationCoordinator.snapshotAttachedHosts()
            .filter { token -> lyricPkg == null || token.packageName == lyricPkg }
            .forEach { token ->
                token.root.post {
                    IslandPresentationCoordinator.clearRegisteredHostIfSuppressed(
                        token,
                        expectedPresentationRevision
                    )
                }
            }
        clearedByPause = true
    }

    private fun applyPlaybackStateToActiveViews(isPlaying: Boolean) {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()
        IslandPresentationCoordinator.snapshotAttachedInjectedHosts(lyricPkg)
            .forEach { snapshot ->
                val token = snapshot.host
                if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                    return@forEach
                }
                val view = token.root
                val indexedViews = snapshot.injectedViews
                view.post {
                    val currentLyricPackage = LyriconDataBridge.currentLyricPackageName
                    if (!IslandPresentationCoordinator.isCurrentHost(token) ||
                        IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                        !IslandPresentationCoordinator.isCurrentPresentation(
                            expectedPresentationRevision
                        ) ||
                        IslandPresentationCoordinator.isPlaybackActive() != isPlaying ||
                        (currentLyricPackage != null &&
                                currentLyricPackage != token.packageName)
                    ) {
                        return@post
                    }
                    if (indexedViews.isEmpty()) {
                        setPlaybackActiveRecursively(view, isPlaying)
                        IslandPresentationCoordinator.refreshInjectedViewIndex(token)
                    } else {
                        indexedViews.forEach { injectedView ->
                            setPlaybackActive(injectedView, isPlaying)
                        }
                    }
                }
            }
    }

    private fun setPlaybackActive(view: View, isPlaying: Boolean) {
        when (view) {
            is RichLyricLineView -> view.setPlaybackActive(isPlaying)
            is SpaceGateRichLyricLineView -> view.setPlaybackActive(isPlaying)
        }
    }

    private fun setPlaybackActiveRecursively(view: View, isPlaying: Boolean) {
        when (view) {
            is RichLyricLineView,
            is SpaceGateRichLyricLineView -> setPlaybackActive(view, isPlaying)

            is ViewGroup -> {
                for (index in 0 until view.childCount) {
                    setPlaybackActiveRecursively(view.getChildAt(index), isPlaying)
                }
            }
        }
    }
}
