/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 Lyricon V3 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.hooks

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.root.HookEntry
import com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge
import com.liuxiansheng.lyricon.hyper.root.island.config.IslandSlotRuntimeConfig
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandProbeUtils
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandTextHookerSupport
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandTextHookerSupport.TAG
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandViewHelper
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandInjectionReconciler
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandNativeRefreshCoordinator
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandPresentationCoordinator
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandReconcileReason
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandRenderPolicy
import com.liuxiansheng.lyricon.hyper.root.island.renderer.BaseIslandRenderer
import com.liuxiansheng.lyricon.hyper.root.island.renderer.IslandContentUpdateCoordinator
import com.liuxiansheng.lyricon.hyper.root.island.structure.IslandSlotStructureInjector
import com.liuxiansheng.lyricon.hyper.root.island.upstream.IslandStateResolver
import com.liuxiansheng.lyricon.hyper.root.island.upstream.IslandType
import com.liuxiansheng.lyricon.hyper.root.island.upstream.UpstreamConfig
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

internal object RealIslandHooker {

    /**
     * 1.2.55：屏幕开关租约方案总开关。
     * true = 关屏不用超级岛、锁屏切状态栏歌词、解锁后主动重建（1.2.36~1.2.54 那套）；
     * false = 完全交还系统（当前值），用来单独验证"更新岛视图"这条路。
     */
    const val SCREEN_LEASE_MODE = false

    /** 最近一次系统媒体岛 update 捕获的宿主根 + 包名，供切换超导后主动重新注册宿主。 */
    @Volatile
    var lastMediaHost: ViewGroup? = null
    @Volatile
    var lastMediaPackage: String? = null

    /** 1.1.14：缓存最近一次媒体岛的 DynamicIslandData，重建时用它重新塞回系统。 */
    private var lastMediaData = java.lang.ref.WeakReference<Any>(null)

    /**
     * 1.1.14：锁屏时系统每 4~5 秒对媒体岛调一次 `IslandTemplateFactory.hideView(prop 2)`，
     * 把封面/歌名/歌词三个模块藏掉，亮屏后没人再 show → 大岛小岛全空。
     * 这里对媒体岛这条 hideView **直接不执行**：内容一直挂着，只是锁屏期间窗口不可见，
     * 亮屏时窗口一恢复内容就在，不需要重建。
     */
    class KeepContentHideViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val data = chain.args.getOrNull(0)
            val key = runCatching {
                data?.javaClass?.getMethod("getKey")?.invoke(data) as? String
            }.getOrNull()
            if (isMediaIslandKey(key)) {
                logKeepContent("拦下 hideView：$key")
                return null
            }
            return chain.proceed()
        }
    }

    /**
     * 1.1.14：同上，`hideIslandLayout()` 是"锁屏前把整块内容布局收起"的入口，
     * 有媒体岛在播时跳过它，内容布局不被收起。
     */
    class KeepContentHideLayoutHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (lastMediaPackage != null) {
                logKeepContent("拦下 hideIslandLayout")
                return null
            }
            return chain.proceed()
        }
    }

    // 2.0.95：删掉 1.1.15 的 requestRebuildMediaIsland —— 用缓存 data 反射调
    // addDynamicIslandData(data,false,true) 让系统"重新挂一遍"。实测在本机要么打不到窗口，
    // 要么只是把岛拆成一次重建空窗（大岛按钮点不动、露出系统占位图标），老问题没修好。

    /** 1.2.45：把 DynamicIslandWindowView 强引用保住，供解锁后 "主动生成" 使用 */
    private fun rememberIslandWindow(view: android.view.View?) {
        var v: android.view.View? = view
        var found: android.view.View? = null
        while (v != null) {
            if (v.javaClass.name.contains("DynamicIslandWindow")) {
                found = v
                break
            }
            v = v.parent as? android.view.View
        }
        if (found != null && found !== cachedIslandWindow) {
            cachedIslandWindow = found
            val clsName = found.javaClass.simpleName
            HookLogger.dState("islandWindow", TAG, clsName) { "已记住岛窗口对象: $clsName" }
        }
    }

    private var cachedIslandWindow: android.view.View? = null
    private var lastMediaDataStrong: Any? = null

    // 2.0.95：删掉 1.2.54 的 requestSystemUpdateIslandView（用缓存 data 重放
    // controller/view 两层 updateDynamicIslandView）。作者自己在 1.5.143 里已写明这条
    // "在本机一直不可用，真正生效的只剩副作用：把岛切成一次重建空窗"。

    private fun isMediaIslandKey(key: String?): Boolean {
        if (key == null) return false
        val pkg = lastMediaPackage
        if (pkg != null && key.contains(pkg)) return true
        return key.contains("music", ignoreCase = true)
    }

    private var lastKeepLogAt = 0L

    private fun logKeepContent(action: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastKeepLogAt < 10000L) return
        lastKeepLogAt = now
        HookLogger.i(TAG, "保内容：$action")
    }

    class UpdateBigIslandViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            var owner: IslandRenderPolicy.OwnerEvidence? = null
            runCatching {
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                val data = chain.args.getOrNull(0)
                owner = IslandPresentationCoordinator.ownerEvidence(data)
                val mediaOwner = owner as? IslandRenderPolicy.OwnerEvidence.Media
                if (mediaOwner != null) {
                    lastMediaHost = contentView
                    lastMediaPackage = mediaOwner.packageName
                    (data as? Any)?.let {
                        lastMediaData = java.lang.ref.WeakReference(it)
                        lastMediaDataStrong = it
                    }
                    rememberIslandWindow(contentView)
                    IslandPresentationCoordinator.onRealBeforeSystemUpdate(
                        contentView,
                        mediaOwner
                    )
                }
            }.onFailure { e ->
                HookLogger.e(TAG, "预恢复歌词视图失败", e)
            }

            val result = chain.proceed()
            // The old two-argument API returns Boolean directly. In the newer suspend API,
            // the extra continuation argument returns a non-Boolean marker while creation waits.
            if (chain.args.size >= 3 && result !is Boolean) return result

            runCatching {
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                val argumentData = chain.args.getOrNull(0)
                // Newer SystemUI versions resume this suspend method by invoking it again with
                // a null data argument. Resolve the data held by the content view before treating
                // the call as a real island clear.
                val argumentOwner = owner
                    ?: IslandPresentationCoordinator.ownerEvidence(argumentData)
                val fallbackData = if (
                    argumentData == null ||
                    argumentOwner == IslandRenderPolicy.OwnerEvidence.Pending
                ) {
                    IslandTextHookerSupport.extractIslandDataFromContentOrReal(contentView)
                } else {
                    null
                }
                val fallbackOwner = fallbackData?.let(
                    IslandPresentationCoordinator::ownerEvidence
                )
                val resolvedOwner = when {
                    argumentOwner is IslandRenderPolicy.OwnerEvidence.Media -> argumentOwner
                    fallbackOwner is IslandRenderPolicy.OwnerEvidence.Media -> fallbackOwner
                    argumentData != null -> argumentOwner
                    fallbackData != null -> fallbackOwner
                        ?: IslandRenderPolicy.OwnerEvidence.Pending
                    // 3.0.771：数据瞬时提取不到但该宿主之前是媒体岛（lastMediaHost）
                    // → 降级 Pending 不清。此前判 NotMedia → removeRealHost 清岛
                    // → 宿主 unregister、歌词回不来（锁屏后随机清岛根因）。
                    // 真非媒体岛数据稳定时会带 argumentData 继续走 NotMedia 分支，不受影响。
                    contentView === lastMediaHost -> IslandRenderPolicy.OwnerEvidence.Pending

                    else -> IslandRenderPolicy.OwnerEvidence.NotMedia
                }
                if (resolvedOwner is IslandRenderPolicy.OwnerEvidence.Media) {
                    lastMediaHost = contentView
                    lastMediaPackage = resolvedOwner.packageName
                }
                IslandPresentationCoordinator.onRealSystemUpdateComplete(
                    root = contentView,
                    owner = resolvedOwner
                )
                IslandNativeRefreshCoordinator.onSystemUpdateComplete(contentView)
            }.onFailure { e ->
                HookLogger.e(TAG, "注入歌词视图失败", e)
            }

            return result
        }
    }

    class WidthUpdateVisibilityGuardHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            runCatching {
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                // 无条件纠偏（与超级岛总开关 gate 无关）：
                // 系统重算大岛宽度（updateBigIslandViewWidth）后，会在同步调用栈里继续走
                // UpdateDynamicIslandWidth 事件链并把背景层置 GONE（checkError 异常态）。
                // 因此这里 postDelayed 延迟，等事件链完成背景置 GONE 之后再检查并恢复，
                // 消除"背景GONE+窗口VISIBLE"的大岛透明。
                // 注意：不依赖 isShown/windowVisible（背景 GONE 时视图树父节点可能同时 GONE
                // 导致 isShown=false 误跳过恢复）；媒体在播实时查 MediaSession（不依赖模块
                // gate/播放记录，超级岛关闭时也生效）。
                contentView.postDelayed({
                    runCatching {
                        // 用户主动开透明模式时跳过纠偏（背景透明是用户要的效果）
                        if (isTransparentModeEnabled()) return@runCatching
                        if (!isMediaPlaying(contentView.context)) return@runCatching
                        if (IslandViewHelper.isIslandBackgroundVisible(contentView)) return@runCatching
                        IslandViewHelper.restoreIslandBackground(contentView)
                        IslandViewHelper.setInjectedLyricVisibility(
                            contentView, android.view.View.VISIBLE
                        )
                        HookLogger.i(
                            TAG,
                            "宽度重算后检测到 背景GONE+媒体在播 → 已恢复背景（防透明纠偏）"
                        )
                    }.onFailure { e ->
                        HookLogger.e(TAG, "宽度重算背景恢复失败", e)
                    }
                }, 50L)
            }.onFailure { e ->
                HookLogger.e(TAG, "宽度重算背景恢复失败", e)
            }
            return result
        }
    }

    /**
     * 实时查询系统媒体会话是否在播（不依赖模块自己的播放状态记录——
     * 超级岛总开关关闭时模块不记录播放态，但纠偏仍须生效）。
     */
    fun isMediaPlaying(context: android.content.Context?): Boolean {
        if (context == null) return false
        return runCatching {
            val msm = context.getSystemService(
                android.content.Context.MEDIA_SESSION_SERVICE
            ) as? android.media.session.MediaSessionManager ?: return false
            msm.getActiveSessions(null).any { session ->
                runCatching {
                    session.playbackState?.state ==
                            android.media.session.PlaybackState.STATE_PLAYING
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    /**
     * 用户是否主动开启了"岛透明模式"。
     * 透明模式开启时，背景保持透明是用户要的效果，所有防透明纠偏必须跳过。
     * 键与将来设置页透明开关共用（RootConstants.KEY_ISLAND_TRANSPARENT_ENABLED）。
     */
    fun isTransparentModeEnabled(): Boolean {
        return runCatching {
            HookEntry.instance?.prefs?.getBoolean(
                RootConstants.KEY_ISLAND_TRANSPARENT_ENABLED,
                RootConstants.DEFAULT_ISLAND_TRANSPARENT_ENABLED
            ) == true
        }.getOrDefault(false)
    }

    /**
     * 监听系统把岛背景层（DynamicIslandBackgroundView）置 GONE 的动作——
     * 无论触发来源（宽度重算事件/系统隐藏流程/其他），只要最终落点是
     * "背景GONE + 窗口可见 + 媒体在播"，就延迟一帧把背景恢复 VISIBLE，
     * 消除大岛透明。与超级岛总开关 gate 无关。
     *
     * 3.0.701（锁屏恢复歌词）：OS4 锁屏/亮屏恢复岛走 IslandTempHiddenChanged +
     * UpdateDynamicIslandWidth，**不走 showIslandLayout / UpdateBigIslandView**，
     * 歌词恢复 reconcile 链不触发 → 锁屏后歌词停在隐藏态（重进播放器才回来）。
     * 背景 setVisibility 是锁屏/亮屏必经之路：GONE=防透明纠偏（原有）；
     * VISIBLE=亮屏/解锁/岛恢复 → 媒体在播时主动触发歌词恢复 reconcile。
     */
    class BackgroundVisibilityGuardHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            runCatching {
                val bgView = chain.thisObject as? android.view.View ?: return@runCatching
                val newVisibility = chain.args.getOrNull(0) as? Int ?: return@runCatching
                val parent = bgView.parent as? ViewGroup ?: return@runCatching
                lastBgView = bgView
                lastParent = parent
                startWatchdog()
                if (newVisibility == android.view.View.GONE) {
                    // 【事前拦截】透明条件：窗口可见 + 媒体在播 + 未开透明模式 →
                    // 禁止系统把背景隐藏（返回 null 跳过原方法，背景保持 VISIBLE）。
                    // 窗口真隐藏（息屏/锁屏）时放行，系统正常隐藏。
                    // ★ 3.0.893：状态栏隐藏超级岛标记置位时放行（隐藏是我们要的效果，
                    //   不能拦）——否则"隐藏超级岛"压 GONE 被这里拦成保持 VISIBLE → 玻璃清不掉。
                    val forcedHidden = IslandInjectionReconciler.statusBarLyricIslandForcedHidden
                    if (!isTransparentModeEnabled() && !forcedHidden) {
                        val windowVisible = parent.visibility == android.view.View.VISIBLE &&
                                parent.isShown
                        if (windowVisible && isMediaPlaying(parent.context)) {
                            HookLogger.i(
                                TAG,
                                "拦截系统隐藏背景：窗口可见+媒体在播 → 背景保持VISIBLE"
                            )
                            return null
                        }
                    }
                }
            }.onFailure { e ->
                HookLogger.e(TAG, "背景状态监听拦截失败", e)
            }
            val result = chain.proceed()
            runCatching {
                val bgView = chain.thisObject as? android.view.View ?: return@runCatching
                val newVisibility = chain.args.getOrNull(0) as? Int ?: return@runCatching
                val parent = bgView.parent as? ViewGroup ?: return@runCatching
                if (newVisibility == android.view.View.GONE) {
                    // ★ 状态栏隐藏标记置位：放行 GONE（隐藏是目的），跳过"窗口可见+媒体在播→恢复"。
                    if (IslandInjectionReconciler.statusBarLyricIslandForcedHidden) {
                        return result
                    }
                    // 原有：系统正常隐藏（息屏/锁屏/收起）时窗口同步隐藏，靠"窗口可见"条件挡住；
                    // 只有窗口仍可见（异常透明）才恢复。
                    parent.postDelayed({
                        runCatching {
                            // 用户主动开透明模式时跳过纠偏（背景透明是用户要的效果）
                            if (isTransparentModeEnabled()) return@runCatching
                            val windowVisible = parent.visibility == android.view.View.VISIBLE &&
                                    parent.isShown
                            if (!windowVisible) return@runCatching
                            if (!isMediaPlaying(parent.context)) return@runCatching
                            if (bgView.visibility == android.view.View.VISIBLE) return@runCatching
                            bgView.visibility = android.view.View.VISIBLE
                            HookLogger.i(
                                TAG,
                                "背景被系统置GONE但窗口可见+媒体在播 → 已恢复背景（防透明监听）"
                            )
                        }.onFailure { e ->
                            HookLogger.e(TAG, "背景状态监听恢复失败", e)
                        }
                    }, 50L)
                } else if (newVisibility == android.view.View.VISIBLE) {
                    // 3.0.701 新增：背景从 GONE/INVISIBLE 恢复 VISIBLE（亮屏/解锁/岛恢复）。
                    // 系统走 IslandTempHiddenChanged + UpdateDynamicIslandWidth 恢复岛，
                    // 不触发 showIslandLayout/UpdateBigIslandView → 歌词 reconcile 缺失 →
                    // 歌词停留 GONE。这里补触发：媒体在播 + 找到内容视图 → 恢复歌词 + reconcile。
                    // ★ 3.0.893：状态栏隐藏标记置位时系统恢复 VISIBLE → 强制压回 GONE
                    //   （玻璃岛清不掉元凶：隐藏后系统/守卫把岛拉回可见）。
                    if (IslandInjectionReconciler.statusBarLyricIslandForcedHidden) {
                        // ★3.0.1100：音乐岛已被源头拦截（状态栏模式下根本建不出来）时，
                        // 不再把这张共享背景强制压回 GONE——它属于"整块岛窗口"，
                        // 压它会把充电岛/其他应用岛一起藏掉（用户实测：充电时岛没了）。
                        if (MediaIslandCreationGate.isStatusBarIslandMode()) {
                            return result
                        }
                        parent.postDelayed({
                            runCatching {
                                if (bgView.visibility == android.view.View.VISIBLE) {
                                    bgView.visibility = android.view.View.GONE
                                    HookLogger.i(
                                        TAG,
                                        "状态栏隐藏期间系统恢复背景VISIBLE → 已强制压回GONE"
                                    )
                                }
                            }.onFailure { e ->
                                HookLogger.e(TAG, "状态栏隐藏强制GONE失败", e)
                            }
                        }, 30L)
                        return result
                    }
                    parent.postDelayed({
                        runCatching {
                            if (!isMediaPlaying(parent.context)) return@runCatching
                            val contentView = IslandViewHelper.findViewByName(
                                parent, "island_content"
                            ) as? ViewGroup ?: return@runCatching
                            val currentData = IslandProbeUtils.getCurrentIslandData(contentView)
                            val owner = IslandPresentationCoordinator.ownerEvidence(currentData)
                            if (owner !is IslandRenderPolicy.OwnerEvidence.Media) return@runCatching
                            IslandViewHelper.setInjectedLyricVisibility(
                                contentView, android.view.View.VISIBLE
                            )
                            IslandPresentationCoordinator.onRealVisibilityChanged(contentView, owner)
                            // 3.0.746+：锁屏/亮屏重建媒体岛后，歌词内容可能被系统清空，
                            // reconcile 只恢复可见性、不重注入歌词文字。这里补全量重注入：
                            // 重注册宿主 + 直注入。锁屏重建时机不确定（宿主可能还没就绪），
                            // 防重启动多段重试（最多 3 次），确保宿主重建后注入成功。
                            scheduleLyricRestore()
                            HookLogger.i(
                                TAG,
                                "背景恢复VISIBLE+媒体在播 → 已触发歌词恢复 reconcile+直注入"
                            )
                        }.onFailure { e ->
                            HookLogger.e(TAG, "背景恢复后歌词恢复失败", e)
                        }
                    }, 80L)
                }
            }.onFailure { e ->
                HookLogger.e(TAG, "背景状态监听恢复失败", e)
            }
            return result
        }

        // ---- 周期兜底（3.0.753+）：开屏后若系统不再发背景事件（背景仍 GONE），
        // 窗口可见+媒体在播时每 2 秒检查一次，强制恢复背景+歌词，杜绝"时间长了透明"。----
        private var lastBgView: android.view.View? = null
        private var lastParent: ViewGroup? = null
        private var watchdogStarted = false
        private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private val watchdogRunnable = Runnable { watchdogCheck() }

        private fun startWatchdog() {
            if (watchdogStarted) return
            watchdogStarted = true
            watchdogHandler.postDelayed(watchdogRunnable, 2000L)
        }

        private fun watchdogCheck() {
            try {
                val bg = lastBgView
                val parent = lastParent
                if (bg != null && parent != null && !isTransparentModeEnabled()) {
                    val windowVisible = parent.visibility == android.view.View.VISIBLE &&
                            parent.isShown
                    if (windowVisible && isMediaPlaying(bg.context)) {
                        if (bg.visibility != android.view.View.VISIBLE) {
                            bg.visibility = android.view.View.VISIBLE
                            HookLogger.i(
                                TAG,
                                "周期兜底：窗口可见+媒体在播+背景非VISIBLE → 恢复背景"
                            )
                        }
                        // 同步保证歌词显示层 VISIBLE；歌词活着则不再拉恢复链
                        // （恢复链含结构协调，每2秒跑=反复 remove/add → 系统清 → 自激循环）
                        val contentView = IslandViewHelper.findViewByName(
                            parent, "island_content"
                        ) as? ViewGroup
                        if (contentView != null) {
                            IslandViewHelper.setInjectedLyricVisibility(
                                contentView, android.view.View.VISIBLE
                            )
                            val lyricAlive = runCatching {
                                val prefs = HookEntry.instance?.prefs ?: return@runCatching false
                                val config = IslandSlotRuntimeConfig.from(prefs)
                                val needLeft = config.leftMode != RootConstants.ISLAND_CONTENT_MODE_NONE
                                val needRight = config.rightMode != RootConstants.ISLAND_CONTENT_MODE_NONE
                                val left = contentView.findViewWithTag<android.view.View>(
                                    IslandProbeUtils.LEFT_TEST_VIEW_TAG
                                )
                                val right = contentView.findViewWithTag<android.view.View>(
                                    IslandProbeUtils.RIGHT_TEST_VIEW_TAG
                                )
                                (!needLeft || (left?.isAttachedToWindow == true && left.parent != null)) &&
                                    (!needRight || (right?.isAttachedToWindow == true && right.parent != null))
                            }.getOrElse { false }
                            if (lyricAlive) {
                                HookLogger.dState("watchdogLyricAlive", TAG, "alive") {
                                    "周期兜底：歌词活着，仅保持VISIBLE，不拉恢复链"
                                }
                            } else {
                                scheduleLyricRestore()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                HookLogger.e(TAG, "周期兜底检查失败", e)
            }
            watchdogHandler.postDelayed(watchdogRunnable, 2000L)
        }
    }

    /**
     * 打断系统 DynamicIslandEventCoordinator.checkError 恢复循环（3.0.754+）。
     *
     * 实锤日志（repro_capture.log 33MB）：系统每 300ms~2s 打印
     * `E/DynamicIslandEventCoordinator checkError true 背景GONE+窗口VISIBLE`，
     * 触发"恢复背景 → hide/清内容 → V3 守卫恢复 → 又报错"死循环；
     * 歌词 View 正是在循环的 hide/清内容阶段被系统从岛容器移除（View removed from parent）。
     * 拦截方式：直接跳过原方法（void），系统永不判定"布局异常"，
     * 循环源头被截断；透明纠偏与歌词恢复由 BackgroundVisibilityGuardHook
     * （事前拦截+事后恢复+2s watchdog）兜底，不依赖系统自愈。
     */
    class CheckErrorBlockHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            return null
        }
    }

    /**
     * 新插件(miui.systemui.plugin 18.3.2.57.0+)黑底拦截（3.0.769+）。
     *
     * 插件全量方法级对比已实证：新版 DynamicIslandWindowView.dispatchUpdateEvent
     * 比老版(18.2.1.88.0)多一段 densityChanged 处理——岛缩回（尺寸/密度变化）时
     * 读 DynamicIslandData.extras 的 "miui.island.densityChanged" 为 true，把 key
     * 记入 islandOrderAppliedKeys 并 setUpdateOrder → 触发岛重建 → 系统垫默认
     * 深色背景（黑底）。老插件没有这段，缩回后不碰背景，所以透明。
     * 用户观察"缩的过程中透明、缩完加层"与此时序吻合。
     *
     * 拦截方式：before 阶段把 data.extras 里的 densityChanged 置 false，跳过重建分支。
     * 老插件无此 key，getBoolean 恒 false，零副作用；新插件下该分支被永久跳过，
     * 缩回不再触发重建垫底，黑底不出现。
     */
    class DensityChangedBlockHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            runCatching {
                val windowView = chain.thisObject ?: return@runCatching
                var params: Any? = null
                for (f in windowView.javaClass.declaredFields) {
                    if (f.type.name.contains("DynamicIslandParams")) {
                        f.isAccessible = true
                        params = f.get(windowView)
                        break
                    }
                }
                if (params == null) return@runCatching
                val getIslandData = params.javaClass.methods.firstOrNull {
                    it.name == "getIslandData" && it.parameterCount == 0
                } ?: return@runCatching
                val data = getIslandData.invoke(params) ?: return@runCatching
                val getExtras = data.javaClass.methods.firstOrNull {
                    it.name == "getExtras" && it.parameterCount == 0
                } ?: return@runCatching
                val extras = getExtras.invoke(data) as? android.os.Bundle ?: return@runCatching
                if (extras.getBoolean("miui.island.densityChanged", false)) {
                    extras.putBoolean("miui.island.densityChanged", false)
                    HookLogger.i(TAG, "拦截新插件 densityChanged 岛重建（防黑底）")
                }
            }.onFailure { e ->
                HookLogger.e(TAG, "densityChanged 拦截异常", e)
            }
            return chain.proceed()
        }
    }

    // ===== 歌词恢复链（3.0.755+）：锁屏/亮屏/岛窗口根重建 attach 共用 =====
    private val lyricRestoreHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lyricRestoreAttempts = 0
    private val lyricRestoreRunnable = Runnable { performLyricRestore() }

    /**
     * 触发歌词恢复 reconcile+直注入。每次触发先取消旧链重置计数，最多重试 3 次，
     * 避免高频触发时多链叠加、无限空转。
     *
     * 不走 refreshActiveIsland —— 其 invalidatePresentation 制造 presentation
     * revision 风暴，高频刷新下每次 post 注入执行时 revision 已过期，
     * reconcile 判 PENDING → 永远不注入 = 歌词永远回不来（此前死循环根因）。
     * 这里直接对当前宿主 reconcile + 注入（reconcile 不传 revision，
     * 只按 isCurrent 判定，revision 风暴不再作废注入）。
     */
    internal fun scheduleLyricRestore() {
        lyricRestoreHandler.removeCallbacks(lyricRestoreRunnable)
        lyricRestoreAttempts = 3
        lyricRestoreHandler.postDelayed(lyricRestoreRunnable, 100L)
    }

    private fun performLyricRestore() {
        runCatching {
            IslandPresentationCoordinator.reRegisterLastMediaHost()
            val pkg = LyriconDataBridge.currentLyricPackageName
                ?.takeIf { it.isNotEmpty() } ?: return@runCatching
            val hosts = IslandPresentationCoordinator.snapshotAllHosts(pkg)
                .ifEmpty { IslandPresentationCoordinator.snapshotAllHosts() }
            hosts.forEach { token ->
                if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                    IslandPresentationCoordinator.unfreezeHostForFakeTransition(token)
                }
                val cv = token.root
                cv.post {
                    // 幂等（3.0.761+）：歌词 View 还活着 → 只更新文本，绝不碰结构。
                    // reconcile/结构协调会 remove/add → requestLayout → 系统清内容，
                    // 与恢复链互相打架 = "歌词回来又被清"。活着就不动结构，从根上断循环。
                    val prefs = HookEntry.instance?.prefs
                    if (prefs != null) {
                        val config = IslandSlotRuntimeConfig.from(prefs)
                        val needLeft = config.leftMode != RootConstants.ISLAND_CONTENT_MODE_NONE
                        val needRight = config.rightMode != RootConstants.ISLAND_CONTENT_MODE_NONE
                        val left = cv.findViewWithTag<android.view.View>(
                            IslandProbeUtils.LEFT_TEST_VIEW_TAG
                        )
                        val right = cv.findViewWithTag<android.view.View>(
                            IslandProbeUtils.RIGHT_TEST_VIEW_TAG
                        )
                        // 3.0.xxx：判定只看“结构还在不在”，不看“行数据有没有”。
                        // 旧版还要求 line != null —— 空壳时 line 恰好是 null，于是被判成
                        // “不存活”走 reconcile，remove/add 重建结构，期间歌词消失，
                        // 表现就是“唱着唱着词丢了又回来”。
                        // 只要 View 还 attach 在树上，就只补内容 + 重新测量，绝不碰结构。
                        val leftStruct = left != null && left.isAttachedToWindow
                        val rightStruct = right != null && right.isAttachedToWindow
                        val alive = (!needLeft || leftStruct) && (!needRight || rightStruct)
                        if (alive) {
                            // 尺寸被系统量成 0×0 时，光写文本还是看不见：连父容器一起重新测量
                            left?.let { v ->
                                v.requestLayout()
                                (v.parent as? android.view.View)?.requestLayout()
                            }
                            right?.let { v ->
                                v.requestLayout()
                                (v.parent as? android.view.View)?.requestLayout()
                            }
                            IslandViewHelper.setInjectedLyricVisibility(
                                cv, android.view.View.VISIBLE
                            )
                            IslandContentUpdateCoordinator.updateContentForView(
                                view = cv,
                                packageName = pkg,
                                prefs = prefs,
                                config = config,
                                playbackActive = true
                            )
                            HookLogger.dState("lyricIdemRefresh", TAG, "idempotent") {
                                "歌词活着：仅刷新文本，不碰结构（幂等）"
                            }
                            return@post
                        }
                    }
                    val result = IslandPresentationCoordinator.reconcileRegisteredHost(
                        token, IslandReconcileReason.STABLE_REFRESH
                    )
                    if (!result.isTarget) return@post
                    IslandViewHelper.setInjectedLyricVisibility(cv, android.view.View.VISIBLE)
                    val postPrefs = HookEntry.instance?.prefs ?: return@post
                    IslandContentUpdateCoordinator.updateContentForView(
                        view = cv,
                        packageName = pkg,
                        prefs = postPrefs,
                        config = IslandSlotRuntimeConfig.from(postPrefs),
                        playbackActive = true
                    )
                }
            }
            HookLogger.i(TAG, "歌词恢复直注入 remaining=$lyricRestoreAttempts")
        }.onFailure { e ->
            HookLogger.e(TAG, "歌词恢复直注入失败", e)
        }
        lyricRestoreAttempts -= 1
        if (lyricRestoreAttempts > 0) {
            lyricRestoreHandler.postDelayed(lyricRestoreRunnable, 300L)
        }
    }

    // ===== 自动重建检测（3.0.756+）：检测到"岛窗口活着但歌词 View 被系统清掉"
    // → 自动重注入（等效手动切换超级岛，无需重建窗口） =====
    private val autoRebuildHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoRebuildStarted = false
    private val autoRebuildRunnable = Runnable { autoRebuildCheck() }

    // ===== 1.2.36 租约：关屏交还 / 亮屏重挂 =====
    // 关屏（含锁屏）后停止自动重建：锁屏期间不再"原生刷新"和系统抢状态，
    // 避免抢出一个 alpha=0 的空壳岛；亮屏解锁由 HookEntry 的亮屏监听放行并重挂。
    @Volatile private var leaseSuspended = false

    internal fun suspendForScreenOff() {
        if (leaseSuspended) return
        leaseSuspended = true
        // 1.2.41：关屏时「不使用超级岛」——摘掉注入层、停掉一切待办，岛回系统原样
        releaseHandler.removeCallbacksAndMessages(null)
        lyricRestoreHandler.removeCallbacksAndMessages(null)
        runCatching {
            IslandPresentationCoordinator.snapshotAllHosts().forEach { token ->
                val r = token.root ?: return@forEach
                rememberIslandWindow(r)
                r.post { runCatching { IslandSlotStructureInjector.detachInjected(r) } }
            }
        }
        HookLogger.i(TAG, "租约：关屏 → 摘掉歌词层、交还系统，暂停自动重建")
    }

    internal fun resumeForScreenOn(allowRelease: Boolean = true) {
        val wasSuspended = leaseSuspended
        leaseSuspended = false
        if (wasSuspended) {
            HookLogger.i(
                TAG,
                if (allowRelease) "租约：解锁 → 释放超级岛（主动生成 + 重挂歌词）"
                else "租约：亮屏（仍锁屏）→ 只放行，不生成岛"
            )
        }
        if (!allowRelease) return
        scheduleLyricRestore()
        releaseIslandForScreenOn()
    }

    // ===== 1.2.38 主动释放超级岛（用户方案：关屏不用，亮屏主动生成）=====
    // 等价"状态切换时重新生成岛"：用缓存的 DynamicIslandData 让系统再 add 一遍，
    // 岛不存在/被系统清掉时都能长回来；最多试 2 次，不做轮询。
    private var releaseAttempt = 0
    private val releaseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val releaseRetryRunnable = Runnable { releaseIfMissing("解锁兜底 2.2s") }

    private fun isIslandAlive(): Boolean {
        val hosts = IslandPresentationCoordinator.snapshotAllHosts()
        return hosts.any { token ->
            val r = token.root ?: return@any false
            r.isAttachedToWindow && IslandViewHelper.isIslandBackgroundVisible(r)
        }
    }

    /**
     * 1.2.39：View 是否在**真正上屏的那棵树**里。
     * 旧判定只看 isAttachedToWindow + visibility，解析锁屏重建后旧树残留的 View 仍然 attached，
     * 于是判"歌词活着"→不做重注入，实际屏上那棵新树里一个我们的 View 都没有 = 空胶囊壳。
     * 这里补三条：同根视图、宽高>0、整条祖先链可见且 alpha 不为 0。
     */
    private fun viewInShownTree(
        view: android.view.View?,
        root: android.view.ViewGroup,
        ignoreAncestorAlpha: Boolean = false
    ): Boolean {
        if (view == null || !view.isAttachedToWindow) return false
        if (root.rootView !== view.rootView) return false
        if (view.width <= 0 || view.height <= 0) return false
        if (view.visibility != android.view.View.VISIBLE) return false
        var p: android.view.ViewParent? = view.parent
        while (p is android.view.View) {
            if (p.visibility != android.view.View.VISIBLE) return false
            // 系统做形态切换（胶囊↔展开）时会把容器 alpha 打 0，这是它自己的隐藏手段，
            // 不等于歌词丢了。此前把它当成"不可见"，导致明明岛上有词也判丢、反复触发原生刷新。
            if (!ignoreAncestorAlpha && p.alpha == 0f) return false
            p = p.parent
        }
        return true
    }

    /**
     * 按 tag 找歌词 View，优先返回当前真正显示的那一份。
     * 胶囊与大岛是两套并列容器，同名 tag 可能同时存在两份。
     */
    private fun findShownByTag(root: ViewGroup, tag: String): android.view.View? {
        val all = ArrayList<android.view.View>(4)
        fun walk(v: android.view.View) {
            if (tag == v.tag) all.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return all.firstOrNull { viewInShownTree(it, root, true) } ?: all.firstOrNull()
    }

    /**
     * 诊断：viewInShownTree 为什么判否。
     * 只用于日志，不改判定本身 —— "有行数据、attached、VISIBLE 却判 alive=false" 时，
     * 必须知道卡在尺寸还是祖先链，否则只能猜。
     */
    private fun whyNotShown(
        view: android.view.View?,
        root: android.view.ViewGroup,
        ignoreAncestorAlpha: Boolean = false
    ): String {
        if (view == null) return "null"
        if (!view.isAttachedToWindow) return "detached"
        if (root.rootView !== view.rootView) return "diffRoot"
        if (view.width <= 0 || view.height <= 0) return "size=${view.width}x${view.height}"
        if (view.visibility != android.view.View.VISIBLE) return "vis=${view.visibility}"
        var p: android.view.ViewParent? = view.parent
        var depth = 0
        while (p is android.view.View) {
            if (p.visibility != android.view.View.VISIBLE) {
                return "anc$depth=${p.javaClass.simpleName}vis${p.visibility}"
            }
            if (!ignoreAncestorAlpha && p.alpha == 0f) {
                return "anc$depth=${p.javaClass.simpleName}alpha0"
            }
            p = p.parent
            depth++
        }
        return "ok"
    }

    /** 歌词是否"真的看得见"：在当前宿主的上屏树里、有行数据、宽高>0、祖先链不被隐藏 */
    private fun hasVisibleLyric(): Boolean {
        val hosts = IslandPresentationCoordinator.snapshotAllHosts()
        if (hosts.isEmpty()) return false
        return hosts.any { token ->
            val r = token.root ?: return@any false
            val left = r.findViewWithTag<android.view.View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG)
            val right = r.findViewWithTag<android.view.View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
            fun ok(v: android.view.View?): Boolean =
                viewInShownTree(v, r) &&
                    (v as? com.liuxiansheng.lyricon.lyric.render.view.RichLyricLineView)?.line != null
            ok(left) || ok(right)
        }
    }

    private fun forceReinjectAllHosts(reason: String) {
        val hosts = IslandPresentationCoordinator.snapshotAllHosts()
        if (hosts.isEmpty()) {
            HookLogger.w(TAG, "重建歌词层跳过：没有任何岛宿主（$reason）")
            return
        }
        hosts.forEach { token ->
            val r = token.root ?: return@forEach
            r.post {
                runCatching {
                    IslandSlotStructureInjector.forceReinject(r)
                }.onFailure { e ->
                    HookLogger.e(TAG, "重建歌词层失败（$reason）", e)
                }
            }
        }
    }

    private fun releaseIslandForScreenOn() {
        releaseAttempt = 0
        releaseHandler.removeCallbacksAndMessages(null)
        // 1.2.41：解锁后先把歌词层拆掉、按当前上屏树重建一次（等价手动来回切状态），
        // 再走一次内容刷新；仅在仍看不到时才升级到"重建岛数据"。
        releaseHandler.postDelayed({
            HookLogger.i(
                TAG,
                "释放超级岛：解锁 600ms → 拆除并重建歌词层 lyricOn=${hasVisibleLyric()} islandOn=${isIslandAlive()}"
            )
            forceReinjectAllHosts("解锁 600ms")
            scheduleLyricRestore()
        }, 600L)
        // 2.0.95：删掉解锁 1.0s / 3.2s 的"重放更新岛视图"和 1.4s 的"自动切下一首"
        //（原 1.2.49 / 1.2.54 / 1.2.57）。这三步在本机都没修好老问题，只留下副作用：
        // 岛被拆成一次重建空窗，大岛播放按钮那一刻点不动，还会自己切一首歌。
        // 岛常常是 2 秒后才被系统建回来：到那时再重建一次歌词层（只按上屏树建，不轮询）
        releaseHandler.postDelayed({ releaseIfMissing("解锁 2.2s") }, 2200L)
        releaseHandler.postDelayed({ releaseIfMissing("解锁 4.5s 兜底") }, 4500L)
    }

    private fun releaseIfMissing(reason: String, force: Boolean = false) {
        if (hasVisibleLyric()) {
            HookLogger.i(TAG, "释放超级岛：歌词已在上屏树（$reason），跳过")
            return
        }
        if (releaseAttempt >= 3) {
            HookLogger.i(TAG, "释放超级岛：重试已达上限 3 次（$reason），停止")
            return
        }
        releaseAttempt++
        val islandOn = isIslandAlive()
        HookLogger.i(
            TAG,
            "释放超级岛：第${releaseAttempt}次重注入（$reason）islandOn=$islandOn"
        )
        forceReinjectAllHosts(reason)
        scheduleLyricRestore()
        // 2.0.95：删掉这里的"重放更新岛视图 + 重挂岛数据 + 自动切下一首"
        //（原 1.2.44 / 1.2.47 / 1.2.54）。岛真没回来时只保留上面两步：
        // 重建我们自己的歌词层（forceReinjectAllHosts）、按当前树恢复歌词，
        // 不再拿缓存 data 去重建系统那颗岛。
    }

    // 2.0.95：删掉 pokeMediaNext（原 1.2.51）—— 靠注入 KEYCODE_MEDIA_NEXT / AudioManager
    // / su input keyevent 87 强行"切一首"来叫醒系统建岛。代价是真的会切歌，而且三条路径
    // 在本机都没验证成功（1.2.49 NPE、1.2.50 uid 不匹配）。

    /** 1.5.74：“该有词却没有”的起算时间，用于 8 秒后请 provider 重推（0 = 未起算） */
    private var lastNoLineStamp = 0L

    internal fun startAutoRebuildWatchdog() {
        if (autoRebuildStarted) return
        autoRebuildStarted = true
        scheduleNextCheck()
    }

    // ===== 1.2.37 防风暴（修「解锁卡住」）=====
    // 现象：日志里 `自动重建检查` 每秒跑 1100~1500 次（正常 1.25 次/秒），14 秒写出 34MB 日志，
    // SystemUI 堆 268MB 被打满 → OutOfMemoryError → 进程崩 → 解锁时屏幕卡住。
    // 触发链：亮屏重挂后歌词 View 仍 vis=0 / 大岛 alpha=0.0 → 每轮都判成"内容丢了"
    // → 调原生刷新 → 刷新又引来下一轮检查 → 正反馈。
    // 三重刹车：① 队列去重 + 700ms 最小间隔；② 原生刷新 3s 冷却；③ 诊断日志按状态变化/5s 节流。

    private var lastCheckStamp = 0L
    private var lastNativeRefreshStamp = 0L
    private var lastShellReinjectStamp = 0L
    private val throttledLogStamps = HashMap<String, Long>()
    private val throttledLogTexts = HashMap<String, String>()

    private fun scheduleNextCheck(delayMs: Long = AUTO_REBUILD_ACTIVE_MS) {
        autoRebuildHandler.removeCallbacks(autoRebuildRunnable)
        autoRebuildHandler.postDelayed(autoRebuildRunnable, delayMs)
    }

    private fun logThrottled(key: String, msg: String, intervalMs: Long = 30000L) {
        val now = android.os.SystemClock.uptimeMillis()
        val lastText = throttledLogTexts[key]
        val lastStamp = throttledLogStamps[key] ?: 0L
        if (msg == lastText && now - lastStamp < intervalMs) return
        throttledLogTexts[key] = msg
        throttledLogStamps[key] = now
        HookLogger.i(TAG, msg)
    }

    /**
     * 1.6.9：懒构造版节流日志——先判时间窗，真的该打时才调用 [build]。
     * 用于「大岛子树」「大岛内容检查」这类要遍历整棵视图树、拼长字符串的诊断：
     * 旧写法是先拼好再交给 logThrottled 按「内容相同 + 5 秒」丢弃，有歌时每 800ms
     * 白做一遍整树遍历 + String.format，纯属 SystemUI 主线程上的 GC 噪音。
     */
    private fun logThrottledLazy(key: String, intervalMs: Long = 30000L, build: () -> String) {
        val now = android.os.SystemClock.uptimeMillis()
        val lastStamp = throttledLogStamps[key] ?: 0L
        if (now - lastStamp < intervalMs) return
        val msg = runCatching(build).getOrNull() ?: return
        throttledLogStamps[key] = now
        throttledLogTexts[key] = msg
        HookLogger.i(TAG, msg)
    }

    private fun findIslandViewByClass(root: ViewGroup, keyword: String): android.view.View? {
        val stack = ArrayDeque<ViewGroup>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val group = stack.removeFirst()
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child.javaClass.simpleName.contains(keyword)) return child
                if (child is ViewGroup) stack.add(child)
            }
        }
        return null
    }

    private fun findRealContentView(root: ViewGroup): Any? {
        // host.root 自身就是 DynamicIslandContentView（自动重建检查 root=DynamicIslandContentView），
        // 先判断自身，再 BFS 子节点兜底。
        if (root.javaClass.name ==
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
        ) return root
        val stack = ArrayDeque<ViewGroup>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val group = stack.removeFirst()
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child.javaClass.name ==
                    "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
                ) return child
                if (child is ViewGroup) stack.add(child)
            }
        }
        return null
    }

    private fun autoRebuildCheck() {
        if (leaseSuspended) {
            scheduleNextCheck()
            return
        }
        val stamp = android.os.SystemClock.uptimeMillis()
        if (stamp - lastCheckStamp < 700L) {
            // 队列里出现了重复/提前的唤起：直接顺延，不再做一次全树扫描
            scheduleNextCheck()
            return
        }
        lastCheckStamp = stamp
        // 1.6.8：空闲档。pkg 为空说明此刻没有任何歌词源在推（没放歌 / 播放器未连），
        // 岛上不可能有词可丢，再每 800ms 扫一遍全树只是空转（日志里
        // `自动重建检查: pkg=null allHosts=0` 每几秒一条就是这么来的）。
        // 这里直接降到低频巡检，一旦有歌立即回到 800ms 档。
        val pkgNow = LyriconDataBridge.currentLyricPackageName?.takeIf { it.isNotEmpty() }
        if (pkgNow == null) {
            logThrottled("scan", "自动重建检查: pkg=null 空闲 → 巡检放慢")
            scheduleNextCheck(AUTO_REBUILD_IDLE_MS)
            return
        }
        var nextDelay = AUTO_REBUILD_ACTIVE_MS
        runCatching {
            val pkg = pkgNow
            val allHosts = IslandPresentationCoordinator.snapshotAllHosts()
            logThrottled(
                "scan",
                "自动重建检查: pkg=$pkg allHosts=${allHosts.size}"
            )
            // 有歌但没有宿主岛（岛收起/未创建/系统没给视图）时，全树扫描同样拿不到东西，
            // 也降一档，避免 800ms 空扫。
            if (allHosts.isEmpty()) nextDelay = AUTO_REBUILD_HOSTLESS_MS
            val prefs = HookEntry.instance?.prefs ?: return@runCatching
            val config = IslandSlotRuntimeConfig.from(prefs)
            val needLeft = config.leftMode != RootConstants.ISLAND_CONTENT_MODE_NONE
            val needRight = config.rightMode != RootConstants.ISLAND_CONTENT_MODE_NONE
            if (!needLeft && !needRight) return@runCatching
            val hosts = IslandPresentationCoordinator.snapshotAllHosts(pkg)
                .ifEmpty { allHosts }
            logThrottled(
                "hosts",
                "自动重建检查: pkgHosts=${hosts.size} needLeft=$needLeft needRight=$needRight"
            )
            for (token in hosts) {
                val root = token.root ?: continue
                if (!root.isAttachedToWindow) {
                    logThrottled("root", "自动重建检查: root未attach 跳过")
                    continue
                }
                val bgVisible = IslandViewHelper.isIslandBackgroundVisible(root)
                // 胶囊 / 大岛各可能有一份带同一 tag 的歌词 View，优先取真正显示的那份，
                // 否则会一直拿到不可见的大岛副本，误判"丢词"反复触发原生刷新。
                val left = findShownByTag(root, IslandProbeUtils.LEFT_TEST_VIEW_TAG)
                val right = findShownByTag(root, IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
                // 3.0.767+：内容活性——View 活着+VISIBLE 但歌词行数据（line）为 null
                // （内容被系统清空，只剩空壳 View）也算"丢"，触发等效切换重注入。
                val leftLineAlive = (left as? com.liuxiansheng.lyricon.lyric.render.view.RichLyricLineView)
                    ?.line != null
                val rightLineAlive = (right as? com.liuxiansheng.lyricon.lyric.render.view.RichLyricLineView)
                    ?.line != null
                // 3.0.764+：alive 判定必须含 VISIBLE——歌词 View 被置 GONE/INVISIBLE 时
                // isAttachedToWindow 仍为 true（View 还挂在树上），旧判定误判"活着"
                // → 不触发重注入，但用户看不到歌词（"没了"）。
                // 判定忽略祖先 alpha（系统形态切换会打 0，不是歌词丢失）；
                // 尺寸为 0、未挂树、行数据为空仍然算丢。
                val leftAlive = needLeft && viewInShownTree(left, root, true) && leftLineAlive
                val rightAlive = needRight && viewInShownTree(right, root, true) && rightLineAlive
                logThrottled(
                    "detail",
                    "自动重建检查: root=${root.javaClass.simpleName} bgVisible=$bgVisible " +
                        "leftAttached=${left?.isAttachedToWindow == true} leftVis=${left?.visibility} leftLine=$leftLineAlive leftAlive=$leftAlive leftWhy=${whyNotShown(left, root, true)} " +
                        "rightAttached=${right?.isAttachedToWindow == true} rightVis=${right?.visibility} rightLine=$rightLineAlive rightAlive=$rightAlive rightWhy=${whyNotShown(right, root, true)}"
                )
                if (!bgVisible) continue
                // ===== 大岛（BigIslandView/ExpandedView）内容活性检查（3.0.886 修正）=====
                // 大岛=系统媒体信息界面（上一曲/下一曲/封面/进度条），不是 Lyricon 歌词注入层——
                // 3.0.883-885 误用 Lyricon tag 判活性（大岛本来就无 tag）→ 每 800ms 误触发。
                // 改为容器内容判定：大岛容器内有无可见内容子视图。
                var bigAlive = true
                var bigAlphaZero = false
                runCatching {
                    val contentView = findRealContentView(root)
                    // 1.6.14：大岛 getter 探测原本只为诊断日志存在，日志关闭后一并去掉（省每轮反射遍历）。
                    val bigView = contentView?.let {
                        IslandStateResolver.concreteView(it, IslandType.BIG)
                    }
                    val expandView = contentView?.let {
                        IslandStateResolver.concreteView(it, IslandType.EXPAND)
                    }
                    // 1.6.14：vDesc 只服务诊断日志，随日志一起移除。
                    val treeBig = findIslandViewByClass(root, "BigIslandView")
                    val treeExpand = findIslandViewByClass(root, "ExpandedView")
                    val activeBig = sequenceOf(
                        expandView, bigView, treeExpand, treeBig
                    ).firstOrNull { it?.isAttachedToWindow == true && it.visibility == android.view.View.VISIBLE } as? ViewGroup
                    if (activeBig != null) {
                        // 容器内容活性：存在 VISIBLE 且有内容的子视图（空壳时内容卡消失/变空）
                        val hasContentChild = (0 until activeBig.childCount).any { i ->
                            val c = activeBig.getChildAt(i)
                            c.visibility == android.view.View.VISIBLE &&
                                (c !is ViewGroup || c.childCount > 0)
                        }
                        // 1.2.37：alpha=0.0 是系统自己的隐藏手段（锁屏/收起），不是内容丢失。
                        // 旧逻辑把它当"空壳"→ 每轮触发原生刷新 → 与系统抢状态 → 检查风暴 → OOM。
                        bigAlphaZero = activeBig.alpha == 0f
                        bigAlive = hasContentChild || bigAlphaZero
                        // 1.6.14：大岛内容检查 / 大岛子树两条诊断日志整体移除（大岛点击问题已闭环，
                        // 保留只会每轮遍历子树拼字符串；活性判定 bigAlive 本身照常工作）。
                    }
                }.onFailure { e ->
                    HookLogger.e(TAG, "大岛内容检查失败", e)
                }
                if (leftAlive && rightAlive && bigAlive) continue
                // 1.2.37：系统整层隐藏（alpha=0 / 内容视图不可见）时不要抢刷新
                if (bigAlphaZero) continue
                val now = android.os.SystemClock.uptimeMillis()
                // 1.5.74：没有"当前可显示行"（没歌在播 / 间奏 / 数据未到）时，
                // 歌词 View 不存在本来就是正常状态。旧版把它当成"被系统清"，
                // 每 2~3 秒触发一次等效切换，岛跟着闪 —— 实测没歌时段日志在刷这条。
                val hasLineToShow = com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge
                    .hasCurrentRenderableLine()
                if (!hasLineToShow) {
                    // 1.5.74：该有词却没有 —— 歌名在、播放器包名在，但一直拿不到可显示行，
                    // 说明 provider 侧连接已断（它不会自己重推）。用户"重开播放器/重启就好"
                    // 正是 provider 重新注册后才补发。这里 8 秒后主动发一次中枢启动广播，
                    // provider 收到会重新注册并把缓存的 Song 补发回来，不用等用户重开 App。
                    val songName = com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge.currentSongName
                    val pkgName = com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge
                        .currentLyricPackageName
                    if (!songName.isNullOrBlank() && !pkgName.isNullOrBlank()) {
                        if (lastNoLineStamp == 0L) lastNoLineStamp = now
                        if (now - lastNoLineStamp >= 8000L) {
                            lastNoLineStamp = now
                            HookLogger.i(
                                TAG,
                                "该有词却没有超过 8s（song=$songName pkg=$pkgName）→ 老词幕链路无重推，等 provider 自己推"
                            )
                        }
                    } else {
                        lastNoLineStamp = 0L
                    }
                    continue
                }
                lastNoLineStamp = 0L
                // 1.5.70：空壳分流。非 0 尺寸问题在"View 还在树上但被量成 0x0 / 行数据为 null"，
                // 本地拆重建歌词层就能修，不该动系统。实测 14:25-14:26 的 60 次原生刷新
                // 左证 why 全是 size=0x0 —— 用"等效切换超级岛"去治空壳，代价是岛体闪一下。
                val leftWhy = whyNotShown(left, root, true)
                val rightWhy = whyNotShown(right, root, true)
                // 该显示却没显示：View 整个不见了（why=null）、被量成 0×0、或行数据为空。
                // 三种都由恢复链修：结构在 → 只补内容；结构不在 → reconcile 重建。
                val viewMissing = (needLeft && left == null) || (needRight && right == null)
                val shellBroken = viewMissing ||
                    leftWhy.startsWith("size=") || rightWhy.startsWith("size=") ||
                    (needLeft && !leftLineAlive) || (needRight && !rightLineAlive)
                if (shellBroken) {
                    if (now - lastShellReinjectStamp < 3000L) continue
                    lastShellReinjectStamp = now
                    HookLogger.i(
                        TAG,
                        "歌词空壳（leftWhy=$leftWhy rightWhy=$rightWhy leftLine=$leftLineAlive rightLine=$rightLineAlive）→ 就地补内容（不拆结构、不触发原生刷新）"
                    )
                    // 3.0.xxx：只走恢复链。恢复链内部会判“结构还在不在”，在就只重写文本 +
                    // 重新测量。旧版这里先 forceReinjectAllHosts 拆一遍结构，拆的这段时间
                    // 歌词就是空的 —— 正是“唱着唱着词丢了又回来”。
                    scheduleLyricRestore()
                    continue
                }
                // 1.2.37：3s 冷却，杜绝"判丢→恢复→再判丢"的正反馈
                if (now - lastNativeRefreshStamp < 3000L) continue
                lastNativeRefreshStamp = now
                // 1.5.143：这里不再调"等效切换超级岛"（updateDynamicIslandView 在本机一直不可用，
                // 这条请求真正生效的只剩副作用：把岛切成一次重建空窗，重建期间系统露出自己的
                // 占位视图——正是"暂停后恢复，岛左右两边各一个手电筒图标"的来源）。
                // 走到这里说明结构还在（left/right 仍 attach、有行数据、尺寸正常），只是被藏了，
                // 对齐老版：就地恢复，交给下一次 push 自然补齐，绝不为了刷一棵树去重建整颗岛。
                HookLogger.i(
                    TAG,
                    "检测到歌词 View 被系统隐藏（leftWhy=$leftWhy rightWhy=$rightWhy bigAlive=$bigAlive）→ 就地恢复（不触发原生刷新）"
                )
                scheduleLyricRestore()
                return@runCatching
            }
        }.onFailure { e ->
            HookLogger.e(TAG, "自动重建检测失败", e)
        }
        // 1.2.37：无论上面从哪个分支 return，都必须续排下一轮（旧版在 pkg==null 等分支直接 return，
        // 会把 watchdog 永久停掉，之后再丢歌词就没人管了）
        scheduleNextCheck(nextDelay)
    }

    /**
     * 第三重保险（3.0.755+）：hook 全局 View.onAttachedToWindow——
     * 岛窗口根 DynamicIslandContentView（island_content，全屏窗口根）重建 attach
     * （锁屏/窗口重建/岛恢复必经）时，媒体在播则立即触发歌词恢复 reconcile+直注入。
     * 老词幕同款机制：attach 后延迟注入，覆盖事件守卫覆盖不到的"窗口重建"场景。
     * 触摸动画期间档位容器（BigIsland/Expanded）反复重建不触发本 hook（island_content
     * 不重建），由 updateBigIslandView 事件守卫兜底，两层互补。
     */
    class ReattachLyricInjectorHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            runCatching {
                val view = chain.thisObject as? android.view.View ?: return@runCatching
                if (!view.javaClass.name.startsWith("miui.systemui.dynamicisland")) return@runCatching
                if (view.javaClass.name != "miui.systemui.dynamicisland.window.content.DynamicIslandContentView") return@runCatching
                if (!isMediaPlaying(view.context)) return@runCatching
                HookLogger.i(TAG, "岛窗口根重建 attach + 媒体在播 → 触发歌词恢复（第三重保险）")
                scheduleLyricRestore()
            }.onFailure { e ->
                HookLogger.e(TAG, "attach 重注入失败", e)
            }
            return result
        }
    }

    class LayoutVisibilityHook(
        private val eventName: String
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val currentData = IslandProbeUtils.getCurrentIslandData(contentView)
                val owner = IslandPresentationCoordinator.ownerEvidence(currentData)
                if (owner !is IslandRenderPolicy.OwnerEvidence.Media) return@runCatching
                if (eventName == "hideIslandLayout") {
                    // 系统正在隐藏岛布局（息屏/锁屏/岛收起）。**不再清歌词显示层**：
                    // ① 收起动画中间态会触发 hide，但岛仍在显示，歌词应该保留；
                    // ② 真隐藏（息屏/锁屏）时窗口整体不可见，歌词 VISIBLE 也看不到，无害；
                    // ③ 唯一风险"背景GONE+歌词VISIBLE=大岛透明"由 BackgroundVisibilityGuard
                    //    兜底（背景被藏但窗口可见+媒体在播 → 自动恢复背景 VISIBLE），
                    //    不需要 hide 联动把歌词清掉。
                    HookLogger.i(TAG, "hideIslandLayout 事件 → 歌词保持显示（不再清显示层）")
                } else {
                    // showIslandLayout：先恢复注入歌词可见，再走正常 reconcile，
                    // 避免结构已存在时 reconcile 为 NO_OP、歌词滞留 GONE。
                    IslandViewHelper.setInjectedLyricVisibility(contentView, android.view.View.VISIBLE)
                    IslandPresentationCoordinator.onRealVisibilityChanged(contentView, owner)
                    HookLogger.i(TAG, "showIslandLayout 事件 → 歌词置VISIBLE")
                }
            }.onFailure { e ->
                HookLogger.e(TAG, "$eventName 后恢复歌词视图失败", e)
            }

            return result
        }
    }
}

/**
 * 1.6.8：自动重建巡检三档间隔。
 * 有歌且有宿主岛 800ms（保持原来的自愈灵敏度）；
 * 有歌但无宿主岛 2.5s（扫也扫不到东西）；没歌 4s（岛上根本没有词可丢）。
 */
private const val AUTO_REBUILD_ACTIVE_MS = 800L
private const val AUTO_REBUILD_HOSTLESS_MS = 2500L
private const val AUTO_REBUILD_IDLE_MS = 4000L
