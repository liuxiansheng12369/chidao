/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.host

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.liuxiansheng.lyricon.hyper.root.island.view.MaxWidthFrameLayout
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import java.util.WeakHashMap

/**
 * 小米超级岛视图管理
 * 负责处理超级岛内部组件的查找、显隐切换及布局刷新
 */
object IslandViewHelper {
    private const val TAG = "IslandViewHelper"


    private val SYSTEMUI_PKG_NAMES = arrayOf("miui.systemui.plugin", "com.android.systemui")
    private val originalMargins = WeakHashMap<View, MarginSnapshot>()
    // 3.0.609：按 parentName 缓存边距快照——系统重建文本容器对象后，
    // WeakHashMap 按对象 identity 取不到快照 → 边距无法还原（歌词占满遮挡封面/频谱）。
    // 按名缓存不受对象重建影响；恢复时优先对象快照，其次按名快照，最后按占位宽度兜底。
    private val originalMarginsByName = java.util.concurrent.ConcurrentHashMap<String, MarginSnapshot>()
    private val originalVisibilities = WeakHashMap<View, Int>()
    private val isRelayouting = ThreadLocal.withInitial { false }
    // 3.0.612：每个 parent 的边距动画引用——切换开关快速连续操作时取消旧动画，
    // 从当前中间值平滑过渡到新目标，避免动画叠加/跳变。
    private val marginAnimators = java.util.concurrent.ConcurrentHashMap<String, ValueAnimator>()

    /**
     * 切换超级岛内部容器（如图标、文本容器）的可见性
     */
    @SuppressLint("DiscouragedApi")
    fun toggleContainer(root: ViewGroup, parentName: String, containerName: String, show: Boolean) {
        try {
            val res = root.resources
            val parent = findViewByName(root, parentName) as? ViewGroup

            if (parent != null) {
                for (pkg in SYSTEMUI_PKG_NAMES) {
                    val id = res.getIdentifier(containerName, "id", pkg)
                    if (id != 0) {
                        parent.findViewById<View>(id)?.let { container ->
                            setVisibilityForInjection(
                                container,
                                if (show) View.VISIBLE else View.GONE
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            HookLogger.e(TAG, "切换容器可见性失败: container=$containerName", e)
        }
    }

    /**
     * 系统超级岛背景层（DynamicIslandBackgroundView，id=island_container）是否可见。
     * 系统隐藏岛（息屏/锁屏/收起/清理）时会把背景层置 GONE；找不到则放行（不影响功能）。
     * 用于"背景GONE 时注入歌词必须同步隐藏"的统一守卫，防止大岛透明。
     */
    @SuppressLint("DiscouragedApi")
    fun isIslandBackgroundVisible(root: ViewGroup): Boolean {
        return runCatching {
            val containerId = root.resources.getIdentifier(
                "island_container", "id", root.context.packageName
            )
            if (containerId == 0) return true
            val bg = root.findViewById<View>(containerId) ?: return true
            bg.visibility == View.VISIBLE
        }.getOrDefault(true)
    }

    /**
     * 主动恢复被系统置 GONE 的岛背景层（DynamicIslandBackgroundView，id=island_container）。
     * 用于"媒体在播 + 窗口可见 + 背景 GONE"的异常状态（系统 checkError：背景GONE+窗口VISIBLE）
     * 纠偏——恢复背景后大岛不再透明，系统后续正常刷新会重新走正确状态。
     * 找不到背景视图则不操作（不影响任何正常流程）。
     */
    @SuppressLint("DiscouragedApi")
    fun restoreIslandBackground(root: ViewGroup) {
        runCatching {
            val containerId = root.resources.getIdentifier(
                "island_container", "id", root.context.packageName
            )
            if (containerId == 0) return
            val bg = root.findViewById<View>(containerId) ?: return
            if (bg.visibility != View.VISIBLE) {
                bg.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 递归设置本模块注入的歌词视图（HYPERLYRIC tag）可见性，不动任何系统视图。
     */
    fun setInjectedLyricVisibility(root: ViewGroup, visibility: Int) {
        setInjectedLyricVisibilityRecursive(root, visibility)
    }

    private fun setInjectedLyricVisibilityRecursive(view: View, visibility: Int) {
        val tag = view.tag as? String
        if (tag?.startsWith("HYPERLYRIC") == true) {
            view.visibility = visibility
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setInjectedLyricVisibilityRecursive(view.getChildAt(i), visibility)
            }
        }
    }

    /**
     * 清除超级岛文本容器的边距
     */
    @SuppressLint("DiscouragedApi")
    fun clearTextContainerMargin(
        root: ViewGroup,
        parentName: String,
        clearStart: Boolean,
        clearEnd: Boolean
    ) {
        try {
            val res = root.resources
            val parent = findViewByName(root, parentName) as? ViewGroup

            if (parent != null) {
                for (pkg in SYSTEMUI_PKG_NAMES) {
                    val id = res.getIdentifier("island_container_module_text", "id", pkg)
                    if (id != 0) {
                        val textContainer = parent.findViewById<View>(id)
                        if (textContainer != null) {
                            val lp = textContainer.layoutParams as? ViewGroup.MarginLayoutParams
                            if (lp != null) {
                                synchronized(originalMargins) {
                                    originalMargins.getOrPut(textContainer) {
                                        MarginSnapshot(lp.marginStart, lp.marginEnd)
                                    }
                                }
                                // 3.0.609：同时按 parentName 缓存（对象重建后按名兜底）
                                synchronized(originalMarginsByName) {
                                    originalMarginsByName.putIfAbsent(
                                        parentName,
                                        MarginSnapshot(lp.marginStart, lp.marginEnd)
                                    )
                                }
                                if (clearStart) lp.marginStart = 0
                                if (clearEnd) lp.marginEnd = 0
                                textContainer.layoutParams = lp
                                // 显式请求重排：让岛立即按新边距重新测量/布局，
                                // 不依赖下一句/系统刷新的重排。
                                textContainer.requestLayout()
                                parent.requestLayout()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            HookLogger.e(TAG, "清除边距失败: parent=$parentName", e)
        }
    }

    /**
     * 3.0.612：仅记录文本容器边距快照（不改变边距）——
     * 供动画隐藏前调用，保证恢复动画的目标值是"隐藏前的原始边距"。
     */
    @SuppressLint("DiscouragedApi")
    fun recordTextContainerMarginSnapshot(root: ViewGroup, parentName: String) {
        try {
            val parent = findViewByName(root, parentName) as? ViewGroup ?: return
            val container = findViewByName(parent, "island_container_module_text") ?: return
            val lp = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            synchronized(originalMargins) {
                originalMargins.getOrPut(container) {
                    MarginSnapshot(lp.marginStart, lp.marginEnd)
                }
            }
            synchronized(originalMarginsByName) {
                originalMarginsByName.putIfAbsent(
                    parentName,
                    MarginSnapshot(lp.marginStart, lp.marginEnd)
                )
            }
        } catch (e: Exception) {
            HookLogger.e(TAG, "记录边距快照失败: parent=$parentName", e)
        }
    }

    /**
     * 3.0.612：文本容器边距平滑动画（220ms 渐变 + 减速插值）——
     * 消除"切换频谱封面隐藏开关时歌词容器边距瞬间跳变"引起的抖动。
     * 快速连续切换时取消旧动画，从当前中间值过渡到新目标。
     * targetStart/targetEnd 为 null 表示该侧边距保持不变。
     */
    @SuppressLint("DiscouragedApi")
    fun animateTextContainerMargins(
        rootView: ViewGroup,
        parentName: String,
        targetStart: Int?,
        targetEnd: Int?,
        instant: Boolean = false
    ) {
        try {
            val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return
            val container = findViewByName(parent, "island_container_module_text") ?: return
            val lp = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val startStart = lp.marginStart
            val startEnd = lp.marginEnd
            val endStart = targetStart ?: startStart
            val endEnd = targetEnd ?: startEnd
            if (startStart == endStart && startEnd == endEnd) {
                container.requestLayout()
                parent.requestLayout()
                return
            }
            if (instant) {
                // 视图刚重建时直接落到终值：播 220ms 动画会在锁屏/解锁时被反复触发，看着连续抖动
                marginAnimators.remove(parentName)?.cancel()
                lp.marginStart = endStart
                lp.marginEnd = endEnd
                container.layoutParams = lp
                container.requestLayout()
                parent.requestLayout()
                return
            }
            marginAnimators.remove(parentName)?.cancel()
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val f = va.animatedFraction
                    lp.marginStart = (startStart + (endStart - startStart) * f).toInt()
                    lp.marginEnd = (startEnd + (endEnd - startEnd) * f).toInt()
                    container.layoutParams = lp
                    container.requestLayout()
                    parent.requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        marginAnimators.remove(parentName)
                        lp.marginStart = endStart
                        lp.marginEnd = endEnd
                        container.layoutParams = lp
                        container.requestLayout()
                        parent.requestLayout()
                        HookLogger.i(
                            TAG,
                            "marginAnim done parent=$parentName ${lp.marginStart}/${lp.marginEnd}"
                        )
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        marginAnimators.remove(parentName)
                    }
                })
            }
            marginAnimators[parentName] = anim
            anim.start()
            HookLogger.i(
                TAG,
                "marginAnim start parent=$parentName $startStart/$startEnd -> $endStart/$endEnd"
            )
        } catch (e: Exception) {
            HookLogger.e(TAG, "边距动画失败: parent=$parentName", e)
        }
    }

    /**
     * 3.0.612：恢复边距（动画版）——取值链与 restoreTextContainerMargins 一致
     * （对象快照 → 按名快照 → 占位宽度兜底），动画平滑还原，不瞬间跳变。
     */
    @SuppressLint("DiscouragedApi")
    fun animateRestoreTextContainerMargins(rootView: ViewGroup, parentName: String) {
        try {
            val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return
            val container = findViewByName(parent, "island_container_module_text") ?: return
            val lp = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val snapshot = resolveTextContainerMarginSnapshot(rootView, parentName, lp)
                ?: return
            HookLogger.i(
                TAG,
                "marginAnimRestore parent=$parentName before=${lp.marginStart}/${lp.marginEnd} " +
                    "use=${snapshot.marginStart}/${snapshot.marginEnd}"
            )
            animateTextContainerMargins(
                rootView, parentName, snapshot.marginStart, snapshot.marginEnd
            )
        } catch (e: Exception) {
            HookLogger.e(TAG, "恢复边距动画失败: parent=$parentName", e)
        }
    }

    /**
     * 3.0.613：解析文本容器应恢复的边距快照（对象快照 → 按名快照 → 占位宽度兜底）。
     * 供恢复动画与"显示模式自愈"共用同一取值链。
     */
    @SuppressLint("DiscouragedApi")
    private fun resolveTextContainerMarginSnapshot(
        rootView: ViewGroup,
        parentName: String,
        lp: ViewGroup.MarginLayoutParams,
        allowFallback: Boolean = true
    ): MarginSnapshot? {
        val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return null
        val container = findViewByName(parent, "island_container_module_text") ?: return null
        val objSnap = synchronized(originalMargins) { originalMargins[container] }
        val nameSnap = synchronized(originalMarginsByName) { originalMarginsByName[parentName] }
        if (objSnap != null) return objSnap
        if (nameSnap != null) return nameSnap
        return if (allowFallback) fallbackMarginSnapshot(rootView, parentName, lp) else null
    }

    /**
     * 3.0.613：显示模式自愈——当前边距与快照不一致（锁屏/系统重建媒体卡后，
     * 歌词容器边距可能仍是隐藏时的 0，封面频谱已显示 → 歌词占满遮挡）时，
     * 动画还原边距。返回是否已修复。
     */
    @SuppressLint("DiscouragedApi")
    fun selfHealTextContainerMargins(rootView: ViewGroup, parentName: String): Boolean {
        try {
            val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return false
            val container = findViewByName(parent, "island_container_module_text") ?: return false
            val lp = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
            // 3.0.638：只用真实快照自愈（隐藏前记录的系统原始边距），
            // 无快照（没隐藏过/系统默认）时跳过——fallback 推导值不可靠，
            // 会把系统正确的频谱占位边距误改掉（"右侧频谱显示不全"）。
            val snapshot = resolveTextContainerMarginSnapshot(
                rootView, parentName, lp, allowFallback = false
            ) ?: return false
            if (lp.marginStart != snapshot.marginStart || lp.marginEnd != snapshot.marginEnd) {
                HookLogger.i(
                    TAG,
                    "selfHeal parent=$parentName cur=${lp.marginStart}/${lp.marginEnd} " +
                        "expect=${snapshot.marginStart}/${snapshot.marginEnd}"
                )
                animateTextContainerMargins(
                    rootView, parentName, snapshot.marginStart, snapshot.marginEnd
                )
                return true
            }
            return false
        } catch (e: Exception) {
            HookLogger.e(TAG, "自愈边距失败: parent=$parentName", e)
            return false
        }
    }

    /**
     * 频谱封面释放语义（V3 自研）：隐藏/显示频谱封面时，直接设置文本容器宽度。
     * released=true（隐藏频谱）→ 文本容器 = 父容器全宽（22dp 释放给歌词）；
     * released=false（显示频谱）→ 文本容器 = 父容器宽 - 22dp（给频谱腾位）。
     * 只动容器宽度，不碰父级 wrapper/动态宽度/分离模式。
     */
    @SuppressLint("DiscouragedApi")
    fun setTextContainerReleased(
        root: ViewGroup,
        parentName: String,
        released: Boolean
    ) {
        try {
            val parent = findViewByName(root, parentName) as? ViewGroup ?: return
            val container = findViewByName(parent, "island_container_module_text")
                ?: return
            val parentWidth = parent.width.takeIf { it > 0 }
                ?: parent.measuredWidth
            if (parentWidth <= 0) return
            val density = container.resources.displayMetrics.density
            val compensatePx = (22f * density).toInt()
            val containerWidth = if (released) {
                parentWidth
            } else {
                (parentWidth - compensatePx).coerceAtLeast(0)
            }
            val lp = container.layoutParams
            if (lp?.width != containerWidth) {
                lp.width = containerWidth
                container.layoutParams = lp
                container.requestLayout()
                parent.requestLayout()
                HookLogger.i(
                    "IslandViewHelper",
                    "textContainer released=$released width=${container.width}->$containerWidth parentW=$parentWidth"
                )
            }
        } catch (e: Exception) {
            HookLogger.e("IslandViewHelper", "设置文本容器释放宽度失败: parent=$parentName", e)
        }
    }

    /**
     * 清理所有注入的视图并恢复系统原生组件
     */
    fun clearInjectedViews(rootView: ViewGroup): Boolean {
        val hasNativeState = hasRememberedNativeState(rootView)
        val hiddenCount =
            hideInjectedSlot(
                rootView,
                IslandProbeUtils.LEFT_TEST_WRAPPER_TAG,
                IslandProbeUtils.LEFT_TEST_VIEW_TAG
            ) +
                    hideInjectedSlot(
                        rootView,
                        IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG,
                        IslandProbeUtils.RIGHT_TEST_VIEW_TAG
                    ) +
                    hideInjectedView(rootView, "HYPERLYRIC_TEST_VIEW_WRAPPER_LEFT") +
                    hideInjectedView(rootView, "HYPERLYRIC_TEST_VIEW_WRAPPER_RIGHT")

        if (!hasNativeState && hiddenCount == 0) return false

        restoreContainerVisibility(
            rootView,
            IslandProbeUtils.LEFT_PARENT_NAME,
            "island_container_module_icon"
        )
        restoreContainerVisibility(
            rootView,
            IslandProbeUtils.RIGHT_PARENT_NAME,
            "island_container_module_icon"
        )
        restoreContainerVisibility(
            rootView,
            IslandProbeUtils.LEFT_PARENT_NAME,
            IslandProbeUtils.TEXT_CONTAINER_NAME
        )
        restoreContainerVisibility(
            rootView,
            IslandProbeUtils.RIGHT_PARENT_NAME,
            IslandProbeUtils.TEXT_CONTAINER_NAME
        )
        restoreTextContainerMargins(rootView, "island_container_module_image_text_1")
        restoreTextContainerMargins(rootView, "island_container_module_image_text_2")
        showOriginalTexts(rootView, "island_container_module_image_text_1")
        showOriginalTexts(rootView, "island_container_module_image_text_2")
        showOriginalNativeTexts(rootView, "island_container_module_image_text_1")
        showOriginalNativeTexts(rootView, "island_container_module_image_text_2")

        return true
    }

    fun showForInjection(view: View) {
        setVisibilityForInjection(view, View.VISIBLE)
    }

    fun hideNativeChildren(container: ViewGroup, keepView: View) {
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child === keepView) {
                child.visibility = View.VISIBLE
                continue
            }
            val tag = child.tag as? String
            if (tag?.startsWith("HYPERLYRIC") == true) continue
            rememberOriginalVisibility(child)
            // 1.5.153：按老版 3.0.86 逐行对齐（老版 :456 就是 visibility = View.GONE）。
            // 1.5.123 改成 alpha=0 是为了保"点岛展开/播放控件"的命中链路，代价是这些原生
            // 视图仍留 VISIBLE、仍占布局、仍算"这块有内容"——系统收壳时只清掉我们的歌词，
            // 封面位/频谱位这两格就留着，露出两个占位图标（用户说的"两个手电筒"）。
            child.visibility = View.GONE
        }
    }

    /**
     * 1.5.124：把文本容器里被我们透明化的原生子视图交还回来。
     * 展开态（大岛）里按钮、进度条、内容组都是系统原生控件，它们与小岛共用文本容器。
     * 小岛态为了不叠字把它们 alpha 置 0，展开时必须恢复，否则整张卡片看起来是空的、
     * 点不到任何控件。
     */
    fun showNativeChildren(container: ViewGroup, wrapperTag: String) {
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            val tag = child.tag as? String
            if (tag == wrapperTag || tag?.startsWith("HYPERLYRIC") == true) continue
            child.alpha = 1f
            // 1.5.153：隐藏方式回到 GONE（老版），展开态还原时也必须把可见性一起还给系统，
            // 否则大岛那张卡片上的按钮/进度条会一直是 GONE，点不到也看不见。
            if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
        }
    }

    // 1.6.16：describeTree（1.5.126 加的大岛子树描述器）随诊断日志一起移除，已无调用方。

    /**
     * 隐藏宿主模块区域内、文本容器之外的原生文本视图（系统歌名/歌手名等）。
     *
     * 系统原生歌名不在 island_container_module_text 内，而是在其父级模块区内，
     * 原有的 hideNativeChildren 只覆盖文本容器内部，导致模块歌词上岛后系统歌名
     * 仍然显示：颜色不随歌词变化、切歌时残留旧歌名、与模块歌词叠加闪烁。
     * 这里递归隐藏模块区内所有非文本容器的 TextView（封面是 ImageView、频谱是
     * 自定义 View，均不受影响），并在卸载时经 restoreVisibility 恢复。
     */
    @SuppressLint("DiscouragedApi")
    fun hideNativeTextViews(parent: ViewGroup, keepContainer: View) {
        try {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                if (child === keepContainer) continue
                hideNativeTextViewRecursive(child)
            }
        } catch (e: Exception) {
            HookLogger.e(TAG, "隐藏系统原生文本失败", e)
        }
    }

    private fun hideNativeTextViewRecursive(view: View) {
        val tag = view.tag as? String
        if (tag?.startsWith("HYPERLYRIC") == true) return
        if (view is android.widget.TextView) {
            rememberOriginalVisibility(view)
            // 1.5.153：按老版 3.0.86 逐行对齐（老版 :487 是 visibility = View.GONE）。
            view.visibility = View.GONE
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                hideNativeTextViewRecursive(view.getChildAt(i))
            }
        }
    }

    /**
     * 卸载模块时恢复模块区（文本容器之外）被隐藏的原生文本视图。
     *
     * 1.5.161：媒体不在播时不还原这两格。
     * 系统在无媒体空档会把封面位/频谱位换成自己的占位视图或快捷图标（用户说的"两个手电筒"），
     * 此时还原等于把这些图标放出来。有媒体时照旧还原，封面与频谱正常显示。
     * 本函数只作用于 image_text_1 / image_text_2 两个容器，大岛按钮与内容不在范围内，不受影响。
     */
    @SuppressLint("DiscouragedApi")
    fun showOriginalNativeTexts(rootView: ViewGroup, parentName: String) {
        try {
            val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                val tag = child.tag as? String ?: ""
                if (tag.startsWith("HYPERLYRIC")) continue
                restoreVisibilityRecursive(child, View.VISIBLE)
            }
        } catch (e: Exception) {
            HookLogger.e(TAG, "恢复系统原生文本失败: parent=$parentName", e)
        }
    }

    private fun restoreVisibilityRecursive(view: View, fallback: Int) {
        val tag = view.tag as? String ?: ""
        if (tag.startsWith("HYPERLYRIC")) return
        // 一、3.0.86 原始条件：图像控件一律不还原。
        // 老版注释写得很明白：封面是 ImageView、频谱是自定义 View，均不受影响。
        // 1.5.125 把"只还原 TextView/ViewGroup"扩成"所有类型都还原"，
        // 系统塞进 image_text 容器的占位/快捷图标（FlashLightIcon）才会被点亮成可见。
        // 2.0.93：图像控件（封面位/频谱位/系统占位图标）一律不碰、也不压，
        // 完全交还系统自己管——老的"压非位图图标"方案已删除（1.6.15 起就没再启用，
        // 手电筒图标问题的真正解法是"不干预"）。
        if (view is ImageView) return
        if (view is android.widget.TextView || view is ViewGroup) {
            restoreVisibility(view, fallback)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                restoreVisibilityRecursive(view.getChildAt(i), fallback)
            }
        }
    }


    // 1.6.16：isMusicActiveNow / dumpTwoSlotsIfDue（3 秒一次「两格子树」诊断）一并移除。

    private fun hideInjectedSlot(rootView: ViewGroup, wrapperTag: String, targetTag: String): Int {
        if (rootView.findViewWithTag<View>(wrapperTag) != null) {
            return hideInjectedView(rootView, wrapperTag)
        }
        return hideInjectedView(rootView, targetTag)
    }

    private fun hideInjectedView(rootView: ViewGroup, tag: String): Int {
        val view = rootView.findViewWithTag<View>(tag) ?: return 0
        val wrapper = view as? MaxWidthFrameLayout
        if (wrapper == null && view.javaClass.name == MaxWidthFrameLayout::class.java.name) {
            (view.parent as? ViewGroup)?.removeView(view)
            return 1
        }
        val changed = view.visibility != View.GONE || wrapper?.keepVisible == true
        wrapper?.keepVisible = false
        view.visibility = View.GONE
        return if (changed) 1 else 0
    }

    /**
     * 显示原本被隐藏的原生文本视图
     */
    @SuppressLint("DiscouragedApi")
    fun showOriginalTexts(rootView: ViewGroup, parentName: String) {
        try {
            val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return
            val container =
                findViewByName(parent, IslandProbeUtils.TEXT_CONTAINER_NAME) as? ViewGroup
                    ?: parent

            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val tag = child.tag as? String ?: ""
                if (!tag.startsWith("HYPERLYRIC")) {
                    restoreVisibility(child, View.VISIBLE)
                }
            }
        } catch (e: Exception) {
            HookLogger.e(TAG, "恢复原生文本失败: parent=$parentName", e)
        }
    }

    /**
     * 触发超级岛系统的布局刷新
     *
     * 使用 ThreadLocal 防止重入：triggerSystemRelayout 调用的系统方法可能被
     * Hook 拦截后再次触发 triggerSystemRelayout，导致无限递归。
     */
    fun triggerSystemRelayout(islandView: ViewGroup) {
        if (isRelayouting.get() == true) return
        isRelayouting.set(true)
        try {
            runCatching {
                val viewClass = islandView.javaClass
                // 优先尝试 updateBigIslandViewWidth
                val updateWidthMethod =
                    viewClass.methods.find { it.name == "updateBigIslandViewWidth" }
                if (updateWidthMethod != null) {
                    updateWidthMethod.invoke(islandView)
                } else {
                    // 兜底尝试 calculateBigIslandWidth
                    viewClass.methods.find { it.name == "calculateBigIslandWidth" }
                        ?.invoke(islandView)
                }
            }.onFailure { e ->
                HookLogger.e(TAG, "超级岛布局刷新失败", e)
            }
        } finally {
            isRelayouting.set(false)
        }
    }

    /**
     * 根据名称寻找 View（支持多包名兜底）
     */
    @SuppressLint("DiscouragedApi")
    fun findViewByName(root: ViewGroup, name: String): View? {
        val res = root.resources
        for (pkg in SYSTEMUI_PKG_NAMES) {
            val id = res.getIdentifier(name, "id", pkg)
            if (id != 0) {
                val v = root.findViewById<View>(id)
                if (v != null) return v
            }
        }
        return null
    }

    fun restoreTextContainerMargins(rootView: ViewGroup, parentName: String) {
        val parent = findViewByName(rootView, parentName) as? ViewGroup
        if (parent == null) {
            HookLogger.i(TAG, "restoreMargin parentNotFound=$parentName")
            return
        }
        val container = findViewByName(parent, "island_container_module_text")
        if (container == null) {
            HookLogger.i(TAG, "restoreMargin containerNotFound parent=$parentName")
            return
        }
        val lp = container.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp == null) {
            HookLogger.i(TAG, "restoreMargin lpNull parent=$parentName")
            return
        }

        // 3.0.609 快照链：对象快照（原逻辑）→ 按名快照（系统重建容器对象后兜底）
        // → 占位宽度兜底（从未 clear / 全部失效时：用封面/频谱实际宽度，找不到用默认值）。
        val objSnap = synchronized(originalMargins) { originalMargins[container] }
        val nameSnap = synchronized(originalMarginsByName) { originalMarginsByName[parentName] }
        val snapshot = objSnap ?: nameSnap ?: fallbackMarginSnapshot(rootView, parentName, lp)
        HookLogger.i(
            TAG,
            "restoreMargin parent=$parentName before=${lp.marginStart}/${lp.marginEnd} " +
                "objSnap=${objSnap?.let { "${it.marginStart}/${it.marginEnd}" } ?: "null"} " +
                "nameSnap=${nameSnap?.let { "${it.marginStart}/${it.marginEnd}" } ?: "null"} " +
                "use=${snapshot.marginStart}/${snapshot.marginEnd}"
        )
        if (lp.marginStart != snapshot.marginStart || lp.marginEnd != snapshot.marginEnd) {
            lp.marginStart = snapshot.marginStart
            lp.marginEnd = snapshot.marginEnd
            container.layoutParams = lp
            // 显式请求重排：关开关的瞬间立即恢复宽度，不等下一句。
            container.requestLayout()
            parent.requestLayout()
            HookLogger.i(TAG, "restoreMargin applied=${lp.marginStart}/${lp.marginEnd}")
        } else {
            // 3.0.609：边距一致也强制重排一次——防"边距已被系统改回但视图未重排"的遮挡。
            container.requestLayout()
            parent.requestLayout()
            HookLogger.i(TAG, "restoreMargin sameValue forceRelayout")
        }
    }

    /** 3.0.609：快照完全缺失时按当前封面/频谱占位宽度兜底（找不到用合理默认值）。 */
    private fun fallbackMarginSnapshot(
        rootView: ViewGroup,
        parentName: String,
        lp: ViewGroup.MarginLayoutParams
    ): MarginSnapshot {
        val density = rootView.resources.displayMetrics.density
        val spacingPx = (8 * density).toInt()
        val isLeft = parentName == IslandProbeUtils.LEFT_PARENT_NAME
        return if (isLeft) {
            // 左侧：marginStart = 封面宽度 + 间距（默认 36dp 封面占位）
            val coverW = findFirstImageViewWidth(rootView, parentName)
                .takeIf { it > 0 } ?: (36 * density).toInt()
            MarginSnapshot(coverW + spacingPx, lp.marginEnd)
        } else {
            // 右侧：marginEnd = 频谱宽度 + 间距（默认 22dp 频谱占位，同老词幕 spectrumReservedDp）
            val spectrumW = findFirstImageViewWidth(rootView, parentName)
                .takeIf { it > 0 } ?: (22 * density).toInt()
            MarginSnapshot(lp.marginStart, spectrumW + spacingPx)
        }
    }

    /** 递归找文本容器外的第一个 ImageView 宽度（封面/频谱占位）。 */
    private fun findFirstImageViewWidth(rootView: ViewGroup, parentName: String): Int {
        val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return 0
        fun scan(v: View): Int {
            if (v is ImageView && v !== null) {
                val w = v.width.takeIf { it > 0 } ?: v.measuredWidth.takeIf { it > 0 }
                if (w != null && w > 0) return w
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val child = v.getChildAt(i)
                    val entryName = runCatching {
                        child.resources.getResourceEntryName(child.id)
                    }.getOrNull()
                    if (entryName == IslandProbeUtils.TEXT_CONTAINER_NAME) continue
                    scan(child).takeIf { it > 0 }?.let { return it }
                }
            }
            return 0
        }
        return scan(parent)
    }

    private fun restoreContainerVisibility(
        rootView: ViewGroup,
        parentName: String,
        containerName: String
    ) {
        val parent = findViewByName(rootView, parentName) as? ViewGroup ?: return
        val container = findViewByName(parent, containerName) ?: return
        restoreVisibility(container, View.VISIBLE)
    }

    private fun setVisibilityForInjection(view: View, visibility: Int) {
        rememberOriginalVisibility(view)
        view.visibility = visibility
    }

    private fun rememberOriginalVisibility(view: View) {
        synchronized(originalVisibilities) {
            originalVisibilities.getOrPut(view) { view.visibility }
        }
    }

    /**
     * 2.0.93：只还原"我们确实改过"的视图。
     *
     * 旧逻辑取不到记录时按 fallback=VISIBLE 强制点亮，等于见一个亮一个。
     * image_text_1/2 是整个岛窗口的共享根，充电岛（ChargerView / PowerSaveView）
     * 和媒体岛共用这套容器，于是系统自己设成 GONE 的那份百分比也被点亮，
     * 岛右侧就叠出两份「75%」（用户截图里的「75%5%+」）。
     * 现在未记录原状的一律不动，交还系统。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun restoreVisibility(view: View, fallback: Int) {
        val visibility = synchronized(originalVisibilities) {
            originalVisibilities.remove(view)
        } ?: return
        view.visibility = visibility
        // 1.5.123：透明隐藏是 alpha 做的，恢复时一并还原。
        view.alpha = 1f
    }

    private fun hasRememberedNativeState(rootView: ViewGroup): Boolean {
        for (parentName in arrayOf(
            IslandProbeUtils.LEFT_PARENT_NAME,
            IslandProbeUtils.RIGHT_PARENT_NAME
        )) {
            val parent = findViewByName(rootView, parentName) as? ViewGroup ?: continue
            val icon = findViewByName(parent, "island_container_module_icon")
            val text = findViewByName(parent, IslandProbeUtils.TEXT_CONTAINER_NAME)
            if (icon != null && hasRememberedVisibility(icon)) return true
            if (text != null) {
                if (hasRememberedVisibility(text) || hasRememberedMargin(text)) return true
                if (text is ViewGroup) {
                    for (index in 0 until text.childCount) {
                        if (hasRememberedVisibility(text.getChildAt(index))) return true
                    }
                }
            }
        }
        return false
    }

    private fun hasRememberedVisibility(view: View): Boolean {
        return synchronized(originalVisibilities) {
            originalVisibilities.containsKey(view)
        }
    }

    private fun hasRememberedMargin(view: View): Boolean {
        return synchronized(originalMargins) {
            originalMargins.containsKey(view)
        }
    }

    private data class MarginSnapshot(
        val marginStart: Int,
        val marginEnd: Int
    )
}

