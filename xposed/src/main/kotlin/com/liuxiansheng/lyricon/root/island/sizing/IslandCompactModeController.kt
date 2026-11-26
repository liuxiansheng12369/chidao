/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 *
 * 频谱封面隐藏控制器：
 * 完全照搬老词库 SuperIslandViewInjector.setCompactMode 的做法：
 * 一个函数管隐藏和显示，左侧找封面、右侧找除文本容器外的原生子视图，
 * 隐藏 = alpha 渐隐 200ms 后 GONE；显示 = alpha=0 → VISIBLE → 渐显 1。
 * 不做快照、不做二次恢复、不做边距动画、不做重隐藏——越简单越不会互相打架。
 */

package com.liuxiansheng.lyricon.hyper.root.island.sizing

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.root.island.config.IslandSlotRuntimeConfig
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandProbeUtils
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandViewHelper
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import java.lang.ref.WeakReference

/**
 * 频谱封面隐藏控制器（只由"频谱封面隐藏"总开关控制）。
 *
 * 隐藏目标：LEFT_PARENT 内文本容器以外的封面 ImageView + 媒体卡 album_art/album_art_image
 * + RIGHT_PARENT 内文本容器子树以外的原生子视图（频谱等）。
 * 隐藏 = alpha 渐隐 200ms 后 GONE + 文本容器边距让位；显示 = alpha 0→1 渐显 + 边距还原。
 * "歌词过长自动隐藏封面频谱"功能已于 1.2.16 删除。
 */
internal object IslandCompactModeController {

    private val TAG = "IslandCompactMode"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val compactRunnable = Runnable { applyPendingCompact() }

    @Volatile
    private var pendingHide = false

    @Volatile
    private var isCompactMode = false

    private var lastRoot: WeakReference<ViewGroup>? = null

    /**
     * 上一次真正应用过隐藏状态的根视图身份（identityHashCode）。
     * 锁屏/解锁、系统重建媒体卡后岛是新的 View 实例，可见性和边距都回到默认值，
     * 而 isCompactMode / pendingHide 仍是上次的 true —— 只靠布尔状态会认为"无需处理"，
     * 结果解锁后封面又露出来。这里按视图实例身份判定，避免弱引用被回收时误判成"又重建了"。
     */
    private var lastAppliedRootId = 0

    /** 防循环守卫：applyCompact → 刷新 → 回调用 maybeUpdate → applyCompact 会自己套自己。 */
    @Volatile
    private var compactApplying = false

    /** 换句/配置变更时调用：算应隐藏状态并防抖调度。
     *  immediate=true（设置项变更路径）跳过防抖立刻应用。 */
    fun maybeUpdate(
        rootView: ViewGroup,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        immediate: Boolean = false
    ) {
        lastRoot = WeakReference(rootView)
        val shouldHide = prefs.getBoolean(
            RootConstants.KEY_ISLAND_SPECTRUM_LINK_WIDTH,
            RootConstants.DEFAULT_ISLAND_SPECTRUM_LINK_WIDTH
        )
        HookLogger.i(
            TAG,
            "maybeUpdate alwaysHide=$shouldHide isCompact=$isCompactMode pending=$pendingHide"
        )
        // 根视图换了实例（锁屏解锁、系统重建媒体卡）：新 view 是默认态，静默重设一次。
        // 必须静默——播渐变动画会在每次重建时重放，表现为锁屏/开屏连续抖动。
        val rootId = System.identityHashCode(rootView)
        if (lastAppliedRootId != rootId && shouldHide) {
            HookLogger.i(TAG, "根视图已换实例，静默重设隐藏态 compact=true")
            applyNow(rootView, true, instant = true)
            lastAppliedRootId = rootId
            return
        }
        if (immediate) {
            mainHandler.removeCallbacks(compactRunnable)
            applyNow(rootView, shouldHide)
            return
        }
        if (shouldHide != pendingHide) {
            pendingHide = shouldHide
            mainHandler.removeCallbacks(compactRunnable)
            mainHandler.postDelayed(compactRunnable, 300)
        } else if (shouldHide == isCompactMode && !shouldHide) {
            // 显示态自愈：锁屏/系统重建媒体卡后边距可能仍是隐藏时的 0，
            // 每次媒体更新时检查，不一致就还原（无需切歌）。
            val healed = IslandViewHelper.selfHealTextContainerMargins(
                rootView, IslandProbeUtils.LEFT_PARENT_NAME
            ) or
                IslandViewHelper.selfHealTextContainerMargins(
                    rootView, IslandProbeUtils.RIGHT_PARENT_NAME
                )
            if (healed) {
                IslandDynamicWidthCoordinator.requestRefresh(rootView, force = true)
                runCatching { rootView.requestLayout() }
            }
        }
    }

    /** 配置读到开关状态后立刻应用（设置变更路径）。 */
    fun applyNow(rootView: ViewGroup, hide: Boolean, instant: Boolean = false) {
        lastRoot = WeakReference(rootView)
        lastAppliedRootId = System.identityHashCode(rootView)
        pendingHide = hide
        isCompactMode = hide
        mainHandler.removeCallbacks(compactRunnable)
        setCompactMode(rootView, hide, instant)
    }

    private fun applyPendingCompact() {
        if (pendingHide == isCompactMode) return
        val rootView = lastRoot?.get() ?: return
        isCompactMode = pendingHide
        lastAppliedRootId = System.identityHashCode(rootView)
        setCompactMode(rootView, isCompactMode)
    }

    /**
     * 照老词库 setCompactMode：一次收集目标，一次切换。
     * @param hide true=隐藏封面与频谱，false=恢复显示
     */
    private fun setCompactMode(rootView: ViewGroup, hide: Boolean, instant: Boolean = false) {
        if (compactApplying) return
        compactApplying = true
        try {
            val leftParent = IslandViewHelper.findViewByName(
                rootView, IslandProbeUtils.LEFT_PARENT_NAME
            ) as? ViewGroup
            val rightParent = IslandViewHelper.findViewByName(
                rootView, IslandProbeUtils.RIGHT_PARENT_NAME
            ) as? ViewGroup

            val viewsToToggle = mutableListOf<View>()

            // 让位收缩：隐藏时把封面/频谱占的那点宽度还给歌词
            // （左文本容器 marginStart→0、右文本容器 marginEnd→0，220ms 平滑；
            //   恢复时还原原边距）。这是"频谱收起后岛变窄、歌词变长"的关键。
            if (hide) {
                leftParent?.let {
                    IslandViewHelper.recordTextContainerMarginSnapshot(
                        rootView, IslandProbeUtils.LEFT_PARENT_NAME
                    )
                    IslandViewHelper.animateTextContainerMargins(
                        rootView,
                        IslandProbeUtils.LEFT_PARENT_NAME,
                        targetStart = 0,
                        targetEnd = null,
                        instant = instant
                    )
                }
                rightParent?.let {
                    IslandViewHelper.recordTextContainerMarginSnapshot(
                        rootView, IslandProbeUtils.RIGHT_PARENT_NAME
                    )
                    IslandViewHelper.animateTextContainerMargins(
                        rootView,
                        IslandProbeUtils.RIGHT_PARENT_NAME,
                        targetStart = null,
                        targetEnd = 0,
                        instant = instant
                    )
                }
            } else {
                IslandViewHelper.animateRestoreTextContainerMargins(
                    rootView, IslandProbeUtils.LEFT_PARENT_NAME
                )
                IslandViewHelper.animateRestoreTextContainerMargins(
                    rootView, IslandProbeUtils.RIGHT_PARENT_NAME
                )
            }

            // 左侧封面（文本容器的兄弟 ImageView）
            leftParent?.let { parent ->
                findAlbumCoverView(parent)?.let { viewsToToggle.add(it) }
            }
            // 真机（HyperOS3）真正的专辑封面在媒体卡里，不在 LEFT_PARENT 内
            IslandViewHelper.findViewByName(rootView, "album_art")?.let { viewsToToggle.add(it) }
            IslandViewHelper.findViewByName(rootView, "album_art_image")?.let { viewsToToggle.add(it) }

            // 右侧原生内容（频谱等，排除文本容器整棵子树）
            rightParent?.let { parent ->
                val textContainer = IslandViewHelper.findViewByName(
                    parent, IslandProbeUtils.TEXT_CONTAINER_NAME
                )
                collectNativeChildren(parent, textContainer, viewsToToggle)
            }

            if (viewsToToggle.isEmpty()) {
                HookLogger.w(TAG, "compact=$hide 未找到封面/频谱视图")
                return
            }

            viewsToToggle.forEach { view ->
                view.animate().cancel()
                if (instant) {
                    // 视图重建后的补设：直接落终态，不播 200ms 渐隐/渐显，否则锁屏开屏会连续闪
                    view.alpha = if (hide) 0f else 1f
                    view.visibility = if (hide) View.GONE else View.VISIBLE
                } else if (hide) {
                    view.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { view.visibility = View.GONE }
                        .start()
                } else {
                    view.alpha = 0f
                    view.visibility = View.VISIBLE
                    view.animate().alpha(1f).setDuration(200).start()
                }
            }
            HookLogger.i(
                TAG,
                "compact=$hide views=${viewsToToggle.size} " +
                    "ids=${viewsToToggle.joinToString(",") { idName(it) ?: "?" }}"
            )
            // 歌词让位后重算宽度（只重算，不强制系统重建，避免把视图拉回来）
            runCatching { IslandDynamicWidthCoordinator.requestRefresh(rootView, force = true) }
        } finally {
            compactApplying = false
        }
    }

    /** 递归查找左侧父容器中的封面 ImageView（跳过文本容器，深层也找）。 */
    private fun findAlbumCoverView(parent: ViewGroup): ImageView? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val entryName = idName(child)
            if (entryName == IslandProbeUtils.TEXT_CONTAINER_NAME) continue
            if (child is ImageView) return child
            if (child is ViewGroup) {
                findAlbumCoverView(child)?.let { return it }
            }
        }
        return null
    }

    /** 收集父容器里除文本容器子树外的所有原生子视图（包含文本容器的分支往下钻）。 */
    private fun collectNativeChildren(
        parent: ViewGroup,
        textContainer: View?,
        out: MutableList<View>
    ) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child === textContainer) continue
            if (textContainer != null && containsView(child, textContainer)) {
                if (child is ViewGroup) collectNativeChildren(child, textContainer, out)
            } else {
                out.add(child)
            }
        }
    }

    private fun containsView(root: View, target: View): Boolean {
        if (root === target) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (containsView(root.getChildAt(i), target)) return true
            }
        }
        return false
    }

    private fun idName(view: View): String? = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrNull()
}
