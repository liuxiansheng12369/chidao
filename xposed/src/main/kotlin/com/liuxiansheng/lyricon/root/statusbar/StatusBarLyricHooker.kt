/*
 * 原实现来源：HyperLyric（作者 limczhh），GNU GPL v3.0
 * 源码文件头另标 Copyright 2026 Proify, Tomakino（其分叉自 Lyricon 时未改头，以仓库 LICENSE 为准）。
 * 原版权归原作者所有。
 *
 * 本文件由 词岛 重写实现（结构重做），对外行为与原实现等价。
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.statusbar

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.SystemClock
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.liuxiansheng.lyricon.hyper.common.LyricTextColorStylePolicy
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.root.HookEntry
import com.liuxiansheng.lyricon.hyper.root.island.content.IslandLyricContentAssembler
import com.liuxiansheng.lyricon.hyper.root.island.hooks.MediaIslandCreationGate
import com.liuxiansheng.lyricon.hyper.root.island.upstream.IslandBlurHook
import com.liuxiansheng.lyricon.hyper.common.media.MediaMetadataHelper
import com.liuxiansheng.lyricon.hyper.root.LyriconDataBridge
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandInjectionReconciler
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandPresentationCoordinator
import com.liuxiansheng.lyricon.hyper.root.island.renderer.BaseIslandRenderer
import com.liuxiansheng.lyricon.hyper.root.media.CurrentMediaInfoResolver
import com.liuxiansheng.lyricon.hyper.root.utils.CoverColorHelper
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import com.liuxiansheng.lyricon.hyper.root.utils.TranslationHelper
import com.liuxiansheng.lyricon.lyric.render.view.Highlight
import com.liuxiansheng.lyricon.lyric.render.view.LyricViewStyle
import com.liuxiansheng.lyricon.lyric.render.view.RichLyricLineView
import com.liuxiansheng.lyricon.lyric.render.view.TextLook
import android.content.res.Resources
import com.liuxiansheng.lyricon.lyric.render.view.WordMotion
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

object StatusBarLyricHooker {

    private const val TAG = "StatusBarLyricHooker"

    /**
     * 跑马灯默认值（不开放设置项）：
     * - 速度 44dp/s ≈ 120px/s：与词岛原来写死的 120px/s 一致，长句能在一句唱完前滑到尾；
     *   上游默认 35dp/s 偏慢，实测"快到唱完才滚完"，所以不照抄速度。
     * - 起滚等待 0ms：句子一出现就开始滚，不再等 300ms（上游是 300）。
     * - 循环间隔 0ms：滚完立刻接下一遍（与上游一致）。
     * - 重影间距 50dp：跟上游一致。
     */
    private const val MARQUEE_SPEED_DP = 44f
    private const val MARQUEE_SPACING_DP = 50f
    private const val MARQUEE_INITIAL_DELAY_MS = 0
    private const val MARQUEE_LOOP_DELAY_MS = 0L
    /** 恢复超级岛防抖窗口：切歌/过渡瞬间 media 短暂翻非播放时，稳定停播这么久才恢复岛 */
    private const val NOT_PLAYING_RESTORE_DEBOUNCE_MS = 500L
    /** 封面旋转一圈的时长（毫秒）：与超级岛、艺术主题唱片统一口径，都按秒算 */
    private const val ICON_ROTATION_DURATION_MS = 24_000L
    /** 每毫秒推进的角度 = 360° / 一圈时长；按时间算，不随刷新率变化 */
    private const val ICON_ROTATION_DEG_PER_MS = 360f / ICON_ROTATION_DURATION_MS.toFloat()

    /**
     * 锚点后备链（首选锚点之后按顺序尝试）。
     * 首选是用户配置的锚点名（默认 clock，对齐上游 lyricon）；
     * 颜色系统（ColorOS / realmeUI）上 clock 这个 id 未必存在，但这几个状态栏容器 id 一般都在，
     * 命中一个就把歌词插进它的父容器，不至于直接退化成根插入。
     */
    private val ANCHOR_FALLBACKS = listOf(
        "status_bar_contents",
        "notification_icon_area",
        "system_icon_area"
    )

    private var module: XposedModule? = null
    private var systemClassLoader: ClassLoader? = null
    private var statusBarLayoutId: Int = 0
    private var rootRef: WeakReference<ViewGroup>? = null
    private var lyricView: RichLyricLineView? = null
    private var wrapperRef: WeakReference<ViewGroup>? = null
    private var enabled = false
    private var loggedFontStyle = false
    private var prefs: android.content.SharedPreferences? = null
    // 主设置 prefs（与超级岛同一份）：状态栏颜色/彩虹/封面取色跟随它，保证两边一致
    private var masterPrefs: android.content.SharedPreferences? = null
    private var appContext: android.content.Context? = null
    private var touchObserverInstalled = false

    /** 设置配置源（独立状态栏 prefs），挂载时读取布局项 */
    fun setPrefs(p: android.content.SharedPreferences?) {
        prefs = p
    }

    /** 主设置 prefs（颜色样式、自定义文字颜色等与超级岛共用） */
    fun setMasterPrefs(p: android.content.SharedPreferences?) {
        masterPrefs = p
    }

    // ================= 帧驱动去重 =================
    private var lastLineRef: Any? = null
    private var lastPosition: Long = Long.MIN_VALUE
    private var framePosted = false
    /** 暂停后降频：不再每帧跑，改成 200ms 一次（省电，同时保证暂停/恢复时策略仍及时响应） */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val slowTick: Runnable = object : Runnable {
        override fun run() {
            frameCallback.doFrame(0L)
        }
    }
    private var density = 1f

    // ================= 切歌生命周期（版本校验） =================
    private var appliedVersion = -1
    // 颜色模式签名（彩虹/封面取色/固定色 + 颜色会话 revision + 固定色），
    // 会话/设置变化才重建样式；封面取色调色板异步就绪后由低频策略兜底刷新
    private var appliedColorSignature: String? = null
// ★1.4.26：岛容器扫描缓存 + 日志去噪（详见 islandContainersThrottled / findIslandContainers）
private var islandScanCache: List<View> = emptyList()
private var islandScanCacheAt = 0L
private var lastIslandContainersSignature: String? = null
    /** 封面取色重试限制：同一首无封面时最多试 3 次，之后退避 60s，避免每 125ms 无限取色 */
    private var coverAttemptKey: String? = null
    private var coverAttemptCount = 0
    private var coverBackoffUntil = 0L

    // ================= 播放检测（位置推进） =================
    private var lastPos = Long.MIN_VALUE
    private var lastPosTime = 0L
    private var isPlaying = false

    // ================= 时钟隐藏 =================
    private var clockRef: WeakReference<View>? = null
    private var clockOriginalVisibility: Int? = null

    // ================= 通知图标隐藏 =================
    private var notifIconRef: WeakReference<View>? = null
    private var notifIconNativeVisibility: Int? = null

    // ================= 锁屏 =================
    private var keyguardService: KeyguardManager? = null
    private var keyguardCheckCounter = 0
    private var lastKeyguardLocked = false

    // ================= 岛避让 =================
    private var configMaxWidthPx = -1
    private var islandAvoidLogCounter = 0
    private val wrapperLocation = IntArray(2)
    private var islandSpacingDensityDpi = 0
    private var cachedIslandSpacingPx = 0

    // ================= 封面图标 =================
    private var iconView: ImageView? = null
    private var iconEnabled = false
    private var iconStyle = RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_STYLE
    private var iconSizePx = 0
    private var iconSpacingPx = 0
    private var iconOrder = RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_ORDER
    private var lastMetadataRef: Any? = null
    private var iconRotating = false
    // 状态栏封面旋转的时间基准（按毫秒推进角度，与刷新率无关）
    private var lastIconRotateTime = 0L
    // 内容模式（对齐 HyperLyric：index 语义 0=无 1=音乐信息 2=歌词）
    private var contentMode = 2
    // 音乐信息 TextView（CUSTOM_MUSIC_INFO 模式显示 标题 - 歌手）
    private var musicInfoView: android.widget.TextView? = null
    // 通知图标"播放时右移"（对齐 HyperLyric，简化版：overlay 摘除重插 + 右侧定位）
    private var notificationIconPosition: StatusBarNotificationIconPosition? = null

    // ================= 手势临时状态 =================
    private var temporarilyShowClockUntil = 0L
    private var temporarilyHideUntil = 0L

    // ================= 节流 =================
    private var policyCheckCounter = 0
    private var visDiagCounter = 0

    // 播放状态查询按 500ms 缓存（原来是每帧一次 binder 往返）
    private val PLAYING_QUERY_TTL_MS = 500L
    private var playingQueryAtMs = 0L
    private var playingQueryValue = false

    /**
     * 位置推进的"停摆判定"窗口：超过这个时长既没有新位置、媒体会话也不是播放态，才判暂停。
     * 位置推送实测 3 秒一档（最长见过 6.9 秒），原来卡 1.5 秒会把正常播放判成暂停，
     * 歌词跟着一闪一没；放宽到 4 秒，并且以媒体会话真实播放态为主判据。
     */
    private val PLAYING_IDLE_TIMEOUT_MS = 4000L

    // 播放期间的绘制节流：两次真正绘制之间至少隔 16ms（60Hz）
    private val FRAME_MIN_INTERVAL_MS = 16L
    private var lastDrawFrameMs = 0L

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!framePosted || !enabled) return
            val view = lyricView
            if (view == null) {
                framePosted = false
                return
            }
            // ★性能：滚动/播放期间把帧循环压到 60Hz。
            // 120Hz 屏上每帧都整行 invalidate + 重算高亮，等于每秒 120 次全行重绘；
            // 歌词滚动与逐字推进用不到 120Hz，隔一帧刷一次肉眼无差，CPU 直接砍一半。
            val playingNow = stablePlaying()
            val frameNowMs = SystemClock.elapsedRealtime()
            if (playingNow && !iconRotating &&
                frameNowMs - lastDrawFrameMs < FRAME_MIN_INTERVAL_MS
            ) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            lastDrawFrameMs = frameNowMs
            // 1) 切歌版本校验：版本变了 → 立即清上一首残留，待新行到达再显示
            val version = LyriconDataBridge.versionCounter.get()
            if (version != appliedVersion) {
                appliedVersion = version
                lastLineRef = null
                invalidatePlayingCache()
                view.line = null
                view.visibility = android.view.View.GONE
                // 3.0.957：切歌后重新应用样式（字体/粗体/字重 typeface）。
                // 否则换歌后的新行用渲染器默认 typeface，字体/粗体/字重设置"看起来变回默认"，
                // 只有再触发一次重建（动开关）才生效。
                runCatching {
                    val st = buildStatusBarStyle()
                    HookLogger.i(
                        TAG, "切歌重排样式: typefaceStyle=${st.primary.typeface?.style} " +
                            "fontVariation=${st.primary.fontVariationSettings}"
                    )
                    view.setStyle(st)
                }
                HookLogger.i(TAG, "切歌版本变化分支触发（appliedVersion=$appliedVersion）")
            }
            // 2) 歌词行/进度更新
            val line = LyriconDataBridge.currentLyricLine
            if (line !== lastLineRef) {
                lastLineRef = line
                if (line != null) {
                    // 3.0.623：状态栏副行也支持"下一句预览"（翻译优先、无翻译才预览，
                    // 与超级岛一致）——nextLinePreviewOverride 由状态栏自行判定
                    val p0 = prefs
                    view.line = if (p0 != null) {
                        IslandLyricContentAssembler.processedRawLine(
                            p0, null, statusBarNextPreview()
                        )
                    } else {
                        line
                    }
                } else {
                    view.line = null
                }
            }
            val pos = LyriconDataBridge.currentPosition
            if (pos != lastPosition) {
                lastPosition = pos
                view.setPosition(pos, 1f)
                // 状态栏歌词进度条：position/duration → 0~1
                var duration = LyriconDataBridge.currentDuration
                if (duration <= 0L) {
                    // 歌词源没给时长时，退回媒体会话里读一次
                    val ctx = appContext
                    val pkg = LyriconDataBridge.currentLyricPackageName
                    if (ctx != null && !pkg.isNullOrBlank()) {
                        duration = MediaMetadataHelper.getPlaybackProgress(ctx, pkg).duration
                    }
                }
                if (duration > 0L) {
                    val frac = (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    view.setPlaybackProgress(frac)
                    if (!loggedProgressBarTick) {
                        loggedProgressBarTick = true
                        HookLogger.i(
                            TAG,
                            "状态栏进度条开始推进 pos=$pos dur=$duration frac=" +
                                "%.3f".format(frac)
                        )
                    }
                } else if (!loggedProgressBarTick) {
                    loggedProgressBarTick = true
                    HookLogger.w(TAG, "状态栏进度条拿不到时长（duration=$duration），本条不推进")
                }
            }
            // 3) 可见性：有歌词 && 播放中 && 未临时隐藏 && 未锁屏
            // 暂停/取消播放 → 歌词消失（老词幕行为：不依赖歌词可见性，按播放状态显示/隐藏）
            val temporarilyHidden = SystemClock.elapsedRealtime() < temporarilyHideUntil
            val shouldShow = line != null && isPlaying && !temporarilyHidden && !lastKeyguardLocked
            // 歌词显示判定诊断（真我消失排查）：5 个因子任一变化就打一行，
            // 直接区分"歌词源没内容"与"被判成暂停/临时隐藏/锁屏"。
            if (lastShouldShow != shouldShow) {
                lastShouldShow = shouldShow
                HookLogger.i(
                    TAG,
                    "歌词显示判定: show=$shouldShow | 无歌词=${line == null} " +
                        "播放中=$isPlaying 临时隐藏=$temporarilyHidden 锁屏=$lastKeyguardLocked " +
                        "| pkg=${LyriconDataBridge.currentLyricPackageName} " +
                        "songLines=${LyriconDataBridge.currentSong?.lyrics?.size ?: -1} " +
                        "textMode=${LyriconDataBridge.isTextMode} version=$version pos=$pos " +
                        "line=[${line?.text?.take(20)}]"
                )
            }
            if (view.visibility != if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE) {
                view.visibility = if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE
            }
            // 4) 播放检测：媒体会话真实播放态为主，位置推进只做兜底
            val now = SystemClock.elapsedRealtime()
            val mediaPlayingNow = stablePlaying()
            if (mediaPlayingNow || (pos > lastPos && now - lastPosTime < PLAYING_IDLE_TIMEOUT_MS)) {
                if (!isPlaying) {
                    isPlaying = true
                    invalidatePlayingCache()
                }
            } else if (now - lastPosTime > PLAYING_IDLE_TIMEOUT_MS) {
                if (isPlaying) {
                    isPlaying = false
                    invalidatePlayingCache()
                    // 暂停/停止：封面图标立即与歌词一起隐藏（不等低频 15 帧兜底）
                    iconView?.visibility = android.view.View.GONE
                }
            }
            if (pos != lastPos) {
                lastPos = pos
                lastPosTime = now
            }
            // 时钟策略每帧检查：mediaActuallyPlaying() 暂停立即 false → 及时释放时钟
            // （放 15 帧节流里会延迟 ~250ms，暂停后时钟"没及时回来"）
            updateClockPolicy()
            // 5) 低频策略（通知图标/锁屏/岛避让/图标/旋转），每 15 帧一次
            if (++policyCheckCounter >= 15) {
                policyCheckCounter = 0
                updateNotificationIconPolicy()
                notificationIconPosition?.onLayoutUpdated()
                updateKeyguardPolicy()
                updateIslandAvoidance()
                updateIslandHidePolicy()
                updateIcon()
                updateLyricColorStyle()
                // 状态栏换句动画：设置改了下一轮低频即生效
                updateLineSwitchAnimation()
                // 播放进度条：设置改了下一轮低频即生效
                lyricView?.let { updateProgressBarConfig(it) }
                // 实时重读竖屏/横屏最大宽度并应用（原来只在挂载时读一次，改设置无效）
                refreshWidthConfig()
            }
            if (iconRotating) {
                // 按时间驱动，不再按帧累加 0.6°：按帧会让高刷屏（120Hz）转得比 60Hz 快一倍。
                // 统一口径：一圈 ICON_ROTATION_DURATION_MS，与超级岛封面旋转保持一致。
                val iv = iconView
                if (iv != null) {
                    val lastT = lastIconRotateTime
                    lastIconRotateTime = now
                    if (lastT != 0L) {
                        val dtMs = (now - lastT).coerceIn(0L, 500L)
                        iv.rotation = (iv.rotation + dtMs * ICON_ROTATION_DEG_PER_MS) % 360f
                    }
                }
            }
            // 超级岛强制隐藏：每帧对岛容器应用隐藏状态（幂等，已隐藏时无动作），
            // 防止切歌/岛动画时系统重建或重排岛容器 → 透明闪几下再消失。
            // ★3.0.1102：状态栏模式走源头拦截后，这里也不压容器了——它压的是整岛窗口的
            //   共享背景，会把充电岛等其他岛一起压掉（用户实测：充电岛出来一会儿就没了）。
            if (islandHiddenFlag &&
                !runCatching { MediaIslandCreationGate.isStatusBarIslandMode() }.getOrDefault(false)
            ) {
                runCatching {
                    islandContainersThrottled().forEach { applyIslandHideState(it) }
                }
            }
            if (framePosted) {
                mainHandler.removeCallbacks(slowTick)
                // 暂停且没有常驻旋转图标时，把 60/120Hz 帧循环降成 5Hz 慢轮询：
                // 歌词/时钟/避让这些低频策略照旧生效，只是不再白白吃满帧。
                if (!playingNow && !iconRotating) {
                    mainHandler.postDelayed(slowTick, 200L)
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            // 状态栏歌词消失诊断：每 15 帧采一次（原来是每帧取屏幕坐标+拼字符串，
            // 120Hz 下每秒上百次 getLocationOnScreen 和临时对象，纯属白烧主线程）
            if (++visDiagCounter >= 15) {
                visDiagCounter = 0
                runCatching { logLyricVisibilityChange() }
            }
        }
    }

    /** 注册 inflate hook（每次 SystemUI 进程启动调用一次，可重复调用） */
    fun hook(xposedModule: XposedModule, classLoader: ClassLoader, context: Context) {
        module = xposedModule
        systemClassLoader = classLoader
        density = context.resources.displayMetrics.density
        appContext = context.applicationContext
        if (statusBarLayoutId == 0) {
            statusBarLayoutId = runCatching {
                context.resources.getIdentifier("status_bar", "layout", context.packageName)
            }.getOrDefault(0)
            if (statusBarLayoutId == 0) {
                HookLogger.w(TAG, "未找到 status_bar 布局 id，状态栏歌词挂载点不可用")
                return
            }
        }
        runCatching {
            val inflaterClass = classLoader.loadClass("android.view.LayoutInflater")
            val method = inflaterClass.getDeclaredMethod(
                "inflate", Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType
            )
            xposedModule.deoptimize(method)
            xposedModule.hook(method).intercept(InflateHooker())
        }.onFailure { e ->
            HookLogger.e(TAG, "安装状态栏布局 Hook 失败", e)
        }
        // 原生超级岛区域 hook（岛避让 + 时钟隐藏 WHEN_ISLAND_PRESENT）
        StatusBarLyricIslandRegionHooker.hook(xposedModule, classLoader)
        // 状态栏背景亮度反色检测（歌词黑白背景反色）：hook 系统 DarkIconDispatcher，
        // 拿"状态栏背景明暗"，亮背景→深色字、暗背景→浅色字；类缺失回退深色模式
        StatusBarInvertTracker.hook(xposedModule, classLoader)
        // 时钟可见性强制隐藏 hook（借鉴老词幕 ViewVisibilityTracker：hook View.setFlags，
        // 从根源拦截系统对时钟可见性的重显示，避免下拉阴影时"表面轮询打架"闪一下）
        // ColorOS 流体云胶囊顶层触摸拦截（仅 ColorOS 启用）：在事件被流体云抢走前，
        // 命中歌词手势区就补发给状态栏根容器，让现有 TouchDispatchObserver 手势照常触发
        hookColorOSTouch(xposedModule, classLoader)
        installTouchObserver(xposedModule)
    }

    /** 是否 ColorOS/流体云系统：仅此类系统需要顶层触摸拦截（小米走现有观察式手势，不受影响） */
    private fun isColorOS(): Boolean {
        val fp = (android.os.Build.FINGERPRINT + " " +
            android.os.Build.BRAND + " " + android.os.Build.MANUFACTURER).lowercase()
        return fp.contains("oppo") || fp.contains("oneplus") ||
            fp.contains("realme") || fp.contains("oplus")
    }

    private var colorOSTouchHooked = false

    /** 状态栏歌词消失诊断的上一次快照（只在变化时打日志） */
    private var lastLyricVisSig: String? = null

    // 廉价前置快照：先用基本类型/引用比对，没变化就直接返回，
    // 避免每次采样都 getLocationOnScreen + 拼一长串字符串
    private var lastVisKinds = 0L
    private var lastLyricVisAlpha = -1
    private var lastParentRef: java.lang.ref.WeakReference<android.view.ViewGroup>? = null

    /** 上一次"是否显示"的判定结果（只在变化时打日志） */
    private var lastShouldShow: Boolean? = null

    /**
     * 状态栏歌词消失诊断（真我 ColorOS 16）。
     *
     * 采样歌词视图与 wrapper 的 可见性 / 附着状态 / 尺寸 / 透明度 / 父容器，
     * 与上一次快照比较，**只在发生变化时输出一行**。
     * 判定顺序：先比基本类型，没变就直接 return；只有变了才去取屏幕坐标拼日志。
     */
    private fun logLyricVisibilityChange() {
        val lyric = lyricView ?: return
        val wrapper = wrapperRef?.get()
        val parent = lyric.parent as? android.view.ViewGroup
        // 4 个布尔/枚举压进一个 Long，引用另比，全部零分配
        val kinds = (lyric.visibility.toLong() and 0xFFFF) or
                (if (lyric.isAttachedToWindow) 1L shl 16 else 0L) or
                (if (wrapper?.visibility == android.view.View.VISIBLE) 1L shl 17 else 0L) or
                (if (wrapper?.isAttachedToWindow == true) 1L shl 18 else 0L) or
                (lyric.width.toLong() shl 24) or
                (lyric.height.toLong() shl 44)
        val parentChanged = parent !== lastParentRef?.get()
        val alphaChanged = (lyric.alpha * 100f).toInt() != lastLyricVisAlpha
        if (kinds == lastVisKinds && !parentChanged && !alphaChanged) return
        lastVisKinds = kinds
        lastLyricVisAlpha = (lyric.alpha * 100f).toInt()
        lastParentRef = parent?.let { java.lang.ref.WeakReference(it) }

        val loc = IntArray(2)
        val located = runCatching {
            lyric.getLocationOnScreen(loc)
            true
        }.getOrDefault(false)
        if (!located) {
            loc[0] = -1
            loc[1] = -1
        }
        val parentName = parent?.javaClass?.simpleName ?: "null"
        val sig = lyric.visibility.toString() + '|' +
                lyric.isAttachedToWindow + '|' +
                lyric.width + 'x' + lyric.height + '|' +
                lyric.alpha + '|' +
                loc[0] + ',' + loc[1] + '|' +
                parentName + '|' +
                wrapper?.visibility + '|' +
                wrapper?.isAttachedToWindow + '|' +
                wrapper?.width + 'x' + wrapper?.height + '|' +
                wrapper?.alpha
        if (sig == lastLyricVisSig) return
        val prev = lastLyricVisSig ?: "init"
        lastLyricVisSig = sig
        HookLogger.i(
            TAG,
            "歌词视图变化: vis=${visibilityName(lyric.visibility)} " +
                "attached=${lyric.isAttachedToWindow} size=${lyric.width}x${lyric.height} " +
                "alpha=${lyric.alpha} at=(${loc[0]},${loc[1]}) parent=$parentName " +
                "| wrapper vis=${wrapper?.let { visibilityName(it.visibility) }} " +
                "attached=${wrapper?.isAttachedToWindow} " +
                "size=${wrapper?.width}x${wrapper?.height} alpha=${wrapper?.alpha} " +
                "| playing=${mediaActuallyPlaying()} enabled=$enabled " +
                "islandHidden=$islandHiddenFlag rootAlive=${rootRef?.get() != null} " +
                "maxW=$configMaxWidthPx " +
                "prev=[$prev]"
        )
    }

    private fun visibilityName(v: Int): String = when (v) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        else -> "GONE"
    }

    /**
     * ColorOS 流体云胶囊顶层触摸拦截：
     * ColorOS 16 在状态栏顶层挂流体云胶囊根容器（CapsuleContainerRoot），铺满状态栏并消费
     * 全部触摸，导致注入在状态栏根容器上的手势（TouchDispatchObserver 观察 root.dispatchTouchEvent）
     * 收不到事件 → 单击/滑动切歌失效。
     * 这里 hook 状态栏根窗口 StatusBarWindowView.dispatchTouchEvent（在胶囊容器之上），
     * 命中歌词手势区时把事件补发给状态栏根容器，让现有 TouchDispatchObserver 照常触发手势；
     * 竖向主导（下拉通知栏意图）让渡回系统。不动任何现有手势判定逻辑。
     */
    private fun hookColorOSTouch(xposedModule: XposedModule, classLoader: ClassLoader) {
        if (colorOSTouchHooked) return
        val interceptEnabled = prefs?.getBoolean(
            RootConstants.KEY_HOOK_STATUS_BAR_LYRIC_ROOT_TOUCH_INTERCEPT,
            RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_ROOT_TOUCH_INTERCEPT,
        ) ?: RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_ROOT_TOUCH_INTERCEPT
        if (!interceptEnabled) {
            HookLogger.i(TAG, "ColorOS 顶层触摸拦截已在设置里关闭，跳过")
            return
        }
        if (!isColorOS()) {
            HookLogger.d(TAG, "非 ColorOS 系统，跳过流体云顶层触摸拦截")
            return
        }
        val windowClass = runCatching {
            classLoader.loadClass("com.android.systemui.statusbar.window.StatusBarWindowView")
        }.recoverCatching {
            classLoader.loadClass("com.android.systemui.statusbar.phone.StatusBarWindowView")
        }.getOrNull()
        if (windowClass == null) {
            HookLogger.w(TAG, "未找到 StatusBarWindowView，ColorOS 触摸拦截不可用")
            return
        }
        runCatching {
            // ROM 可能只在某个中间父类覆写 dispatchTouchEvent，沿继承链向上找
            val method = findDispatchTouchEvent(windowClass)
                ?: throw NoSuchMethodException("%s 继承链上未定义 dispatchTouchEvent")
            xposedModule.deoptimize(method)
            xposedModule.hook(method).intercept(ColorOSTouchHooker())
            colorOSTouchHooked = true
            HookLogger.i(TAG, "ColorOS 流体云顶层触摸拦截已安装")
        }.onFailure { e ->
            HookLogger.e(TAG, "安装 ColorOS 触摸拦截失败", e)
        }
    }

    /** 沿继承链向上查找 dispatchTouchEvent(MotionEvent)（真我 ColorOS 16 可能不在声明类覆写） */
    private fun findDispatchTouchEvent(clazz: Class<*>): java.lang.reflect.Method? {
        var current: Class<*>? = clazz
        while (current != null && current != android.view.View::class.java) {
            current.declaredMethods.firstOrNull {
                it.name == "dispatchTouchEvent" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == android.view.MotionEvent::class.java
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    /**
     * ColorOS 顶层触摸拦截 Hook（仅 ColorOS 启用）：
     * 命中歌词手势区 → 补发给状态栏根容器（触发现有 TouchDispatchObserver 手势）并消费防胶囊抢；
     * 竖向主导 → 让渡系统下拉。不消费/不修改现有手势判定。
     */
    internal class ColorOSTouchHooker : Hooker {
        private var tracking = false
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var touchSlop = 0
        /** 主动派发（重放 DOWN）会再次进入本 Hook，必须直接透传，否则无限递归导致栈溢出 */
        private var reentry = false

        override fun intercept(chain: Chain): Any? {
            if (reentry) return chain.proceed()
            val event = chain.args?.firstOrNull() as? android.view.MotionEvent ?: return chain.proceed()
            val root = rootRef?.get() ?: return chain.proceed()
            val window = chain.thisObject as? android.view.ViewGroup
            if (!mediaActuallyPlaying()) {
                tracking = false
                return chain.proceed()
            }
            try {
                return when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> onDown(root, event, chain)
                    android.view.MotionEvent.ACTION_MOVE -> onMove(window, root, event, chain)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> onUp(root, event, chain)
                    else -> chain.proceed()
                }
            } catch (t: Throwable) {
                tracking = false
                return chain.proceed()
            }
        }

        private fun onDown(root: android.view.View, event: android.view.MotionEvent, chain: Chain): Any? {
            tracking = false
            if (!inGestureZone(root, event.rawX, event.rawY)) return chain.proceed()
            if (touchSlop <= 0) {
                touchSlop = android.view.ViewConfiguration.get(root.context).scaledTouchSlop
            }
            tracking = true
            downX = event.rawX
            downY = event.rawY
            downTime = event.eventTime
            dispatchToRoot(root, event)
            return true
        }

        private fun onMove(
            window: android.view.ViewGroup?,
            root: android.view.View,
            event: android.view.MotionEvent,
            chain: Chain
        ): Any? {
            if (!tracking) return chain.proceed()
            val dx = event.rawX - downX
            val dy = event.rawY - downY
            // 竖向主导（下拉 / 收起通知栏意图）：让渡回系统，歌词不接管。
            // ★关键：DOWN 是被本 Hook 消费掉的，系统侧从未收到过 DOWN。
            // 只放行当前 MOVE，系统的手势识别器没有起点 → 下拉永远不成立。
            // 因此先向窗口重放一次 DOWN，再放行当前 MOVE，序列才完整（与上游
            // StatusBarTouchHooker 的 replayDownToSystem 同一处理）。
            if (shouldYieldToSystem(dx, dy, event.eventTime - downTime)) {
                dispatchCancelToRoot(root)
                tracking = false
                replayDownToSystem(window, event)
                return chain.proceed()
            }
            dispatchToRoot(root, event)
            return true
        }

        /** 竖向让渡判定：沿用上游 GestureArbitration 口径（touchSlop / 1.15 倍 / 650ms 窗口） */
        private fun shouldYieldToSystem(dx: Float, dy: Float, elapsedMs: Long): Boolean {
            if (elapsedMs > 650L) return false
            val vertical = Math.abs(dy)
            if (vertical < Math.max(1f, touchSlop.toFloat())) return false
            return vertical > Math.abs(dx) * 1.15f
        }

        /**
         * 向窗口重放一次 DOWN，补齐系统侧缺失的手势起点。
         * 该调用会再次进入本 Hook，由 [reentry] 直接透传，否则会无限递归。
         */
        private fun replayDownToSystem(
            window: android.view.ViewGroup?,
            event: android.view.MotionEvent
        ) {
            if (window == null) return
            val down = android.view.MotionEvent.obtain(
                event.downTime,
                android.os.SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_DOWN,
                event.x,
                event.y,
                0
            )
            reentry = true
            try {
                window.dispatchTouchEvent(down)
            } finally {
                reentry = false
                down.recycle()
            }
        }

        private fun onUp(root: android.view.View, event: android.view.MotionEvent, chain: Chain): Any? {
            if (!tracking) return chain.proceed()
            tracking = false
            dispatchToRoot(root, event)
            return true
        }

        /** 复用双状态手势区判定：歌词可见 → 歌词 wrapper；否则播放中 → 状态栏根区 */
        private fun inGestureZone(root: android.view.View, x: Float, y: Float): Boolean {
            val lyric = lyricView
            val wrapper = wrapperRef?.get()
            if (lyric != null && lyric.visibility == android.view.View.VISIBLE &&
                wrapper != null && wrapper.isLaidOut && wrapper.width > 0
            ) {
                return hitRect(wrapper, x, y)
            }
            // 1.5.129：歌词层不可见（展开大岛时被隐藏 / 切歌瞬间被清）时，不要退化成
            // “整个状态栏窗口都算歌词手势区”。那会把超级岛上的暂停、上一曲、下一曲、
            // 进度条全部当歌词手势吞掉 —— 现象就是“换首歌之后点哪都没反应”。
            // 精确边界没了就放行给系统，手势只认歌词层的真实范围。
            return false
        }

        private fun hitRect(v: android.view.View, x: Float, y: Float): Boolean {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return x >= loc[0] && x <= loc[0] + v.width &&
                y >= loc[1] && y <= loc[1] + v.height
        }

        /** 把事件转状态栏根容器局部坐标后补发，触发现有 TouchDispatchObserver 手势 */
        private fun dispatchToRoot(root: android.view.View, event: android.view.MotionEvent) {
            val copy = android.view.MotionEvent.obtain(event)
            try {
                val loc = IntArray(2)
                root.getLocationOnScreen(loc)
                copy.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
                root.dispatchTouchEvent(copy)
            } finally {
                copy.recycle()
            }
        }

        /** 让渡下拉时向根容器补发 CANCEL，清理 TouchDispatchObserver 的手势内部状态 */
        private fun dispatchCancelToRoot(root: android.view.View) {
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0
            )
            try {
                root.dispatchTouchEvent(cancel)
            } finally {
                cancel.recycle()
            }
        }
    }

    /** 触摸观察（不消费）：hook View.dispatchTouchEvent，在歌词区域内识别手势触发动作，
     *  但从不拦截/修改分发，保证状态栏下拉通知栏与系统交互始终可用。 */
    private fun installTouchObserver(xposedModule: XposedModule) {
        if (touchObserverInstalled) return
        runCatching {
            val m = android.view.View::class.java.getMethod(
                "dispatchTouchEvent", android.view.MotionEvent::class.java
            )
            xposedModule.hook(m).intercept(TouchDispatchObserver())
            touchObserverInstalled = true
            HookLogger.i(TAG, "状态栏歌词触摸观察已安装（观察不消费，下拉通知栏不受影响）")
        }.onFailure { e ->
            HookLogger.e(TAG, "安装触摸观察失败", e)
        }
    }

    /** 启用：挂载歌词视图并启动帧循环（幂等） */
    fun enable(context: Context) {
        if (enabled) return
        enabled = true
        density = context.resources.displayMetrics.density
        appContext = context.applicationContext
        val root = rootRef?.get()
        if (root == null) {
            HookLogger.w(TAG, "状态栏根容器尚未就绪，启用后等待下一次 inflate 自动挂载")
            return
        }
        attach(root, context)
    }

    /** 禁用：卸载歌词视图、恢复时钟/通知图标并停止帧循环（幂等） */
    fun disable() {
        enabled = false
        framePosted = false
        mainHandler.removeCallbacks(slowTick)
        restoreClockVisibility()
        restoreNotificationIconVisibility()
        // 关闭状态栏模式必须把超级岛释放回系统原样（否则之前"隐藏超级岛"压下去的
        // 岛容器宽高0+GONE 永远卡住，导致打开超级岛无效、超级岛歌词不显示）
        releaseIslandToSystem()
        // 关闭状态栏后若超级岛歌词开启（互拆切换场景），立即强制重排注入超导歌词：
        // 否则"先开超导注入→后关状态栏 release 清空"会把刚注入的超导歌词清掉，
        // 要等换句/换歌（系统灵动岛 update）才重新注入 → 切换灵动岛延迟。
        runCatching {
            if (HookEntry.instance?.prefs?.getBoolean(
                    RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                    RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
                ) == true
            ) {
                // 切换超导时主动重新注册宿主（不依赖系统媒体岛 update）：
                // 否则宿主没进 registry → refreshActiveIsland 空跑 → 歌词不过、必须换歌才恢复。
                IslandPresentationCoordinator.reRegisterLastMediaHost()
                BaseIslandRenderer.refreshActiveIsland()
            }
        }
        runCatching {
            // 移除歌词 wrapper（挂在状态栏父容器）
            val wrapper = wrapperRef?.get()
            if (wrapper != null) {
                (wrapper.parent as? ViewGroup)?.removeView(wrapper)
            } else {
                lyricView?.let { v ->
                    (v.parent as? ViewGroup)?.removeView(v)
                }
            }
        }
        wrapperRef = null
        lyricView = null
        iconView = null
        lastLineRef = null
        lastPosition = Long.MIN_VALUE
        appliedVersion = -1
        lastMetadataRef = null
    }

    /** 重新挂载：按最新配置重新挂载（设置变更即生效，无需重启 SystemUI） */
    fun remount(context: Context) {
        if (!enabled) {
            enable(context)
            return
        }
        framePosted = false
        mainHandler.removeCallbacks(slowTick)
        restoreClockVisibility()
        restoreNotificationIconVisibility()
        // rootRef 指向的状态栏根容器在 SystemUI 重启/窗口重建后会失效（视图已被系统换掉），
        // 此时 remount 会悄悄跳过重建，表现就是"改了设置没反应，得重启状态栏"。
        // 兜底：拿旧 wrapper 当前的父容器当挂载点，保证配置变更能真正重建。
        val oldWrapper = wrapperRef?.get()
        val fallbackRoot = oldWrapper?.parent as? ViewGroup
        runCatching { fallbackRoot?.removeView(oldWrapper) }
        wrapperRef = null
        lyricView = null
        iconView = null
        lastLineRef = null
        lastPosition = Long.MIN_VALUE
        lastMetadataRef = null
        val root = rootRef?.get() ?: fallbackRoot
        HookLogger.i(
            TAG,
            "remount: rootRefAlive=${rootRef?.get() != null} fallbackRoot=${fallbackRoot != null}"
        )
        if (root != null) attach(root, context)
    }

    /** 状态栏"下一句预览"判定：开关开 + 非文本模式 + lyricon/lyricinfo 源 + 无翻译（翻译优先） */
    private fun statusBarNextPreview(): Boolean {
        val p = prefs ?: return false
        if (!p.getBoolean(
                RootConstants.KEY_HOOK_NEXT_LYRIC_LINE,
                RootConstants.DEFAULT_HOOK_NEXT_LYRIC_LINE
            )
        ) return false
        if (LyriconDataBridge.isTextMode) return false
        val source = p.getString(
            RootConstants.KEY_HOOK_LYRIC_SOURCE,
            RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
        )
        if (source != "lyricon" && source != "lyricinfo") return false
        val currentLine = LyriconDataBridge.currentLyricLine
        val hasSongTrans =
            LyriconDataBridge.currentSong?.lyrics?.any { !it.translation.isNullOrBlank() } == true
        val hasLineTrans = !currentLine?.translation.isNullOrBlank()
        return !hasSongTrans && !hasLineTrans
    }

    private fun attach(root: ViewGroup, context: Context) {
        HookLogger.i(TAG, "手势-诊断: attach 被调用 root=${root.javaClass.name} lyricView=${lyricView != null}")
        if (lyricView != null) return
        val rc = RootConstants
        val p = prefs
        // 3.0.620：状态栏双行——副行（中文翻译）读翻译开关，不再硬编码关闭
        val transDisabled = p?.let { TranslationHelper.isTranslationDisabled(it) } ?: false
        val transOnly = p?.let { TranslationHelper.isTranslationOnly(it) } ?: false
        // 3.0.796：自动切换翻译——当前歌无翻译时回退策略，保留副行罗马音降级
        val autoSwitch = p?.let { TranslationHelper.isAutoSwitchTranslation(it) } ?: false
        val songHasTranslation = TranslationHelper.songHasTranslation()
        val effectiveTransOnly = transOnly && (!autoSwitch || songHasTranslation)
        val view = RichLyricLineView(
            context.applicationContext,
            displayTranslation = !transDisabled,
            displayRoma = !transDisabled && !effectiveTransOnly,
            simulateUntimedWord = p?.getBoolean(
                rc.KEY_HOOK_SIMULATE_UNTIMED_WORD,
                rc.DEFAULT_HOOK_SIMULATE_UNTIMED_WORD
            ) ?: rc.DEFAULT_HOOK_SIMULATE_UNTIMED_WORD
        )
        // 3.0.620：状态栏显示副行（中文翻译），不再 GONE
        view.secondary.visibility = android.view.View.VISIBLE
        // 3.0.1106：状态栏有自己的换句动画配置（与超级岛分开，各读各的键），
        //   共用同一套动画实现（RichLyricLineView.lineSwitchAnimation）。
        view.lineSwitchAnimation = readLineSwitchAnim()
        // 状态栏小字样式：与超级岛同一渲染内核（逐字渐进），仅字号/动效适配状态栏
        view.setStyle(buildStatusBarStyle())
        // 播放进度条：挂载时按当前设置应用一次
        applyProgressBarConfig(view, force = true)
        // 浮层方案：不参与状态栏布局流（不挤时钟/网速/通知图标，也不被它们挤）。
        // 在状态栏根容器上叠加一个独立 FrameLayout 浮层，歌词放浮层里，
        // 用 gravity（左/中/右）+ 边距自由定位；浮层不拦截触摸，空白区域穿透到系统图标。
        val statusBarId = root.resources.getIdentifier("status_bar", "id", root.context.packageName)
        if (statusBarId != 0 && root.id != statusBarId) {
            HookLogger.w(TAG, "状态栏歌词跳过：root.id=${root.id} != status_bar($statusBarId)")
            return
        }
        val clockId = root.resources.getIdentifier("clock", "id", root.context.packageName)
        val clock = if (clockId != 0) {
            root.findViewById<android.view.View>(clockId)
        } else {
            null
        }
        if (clock != null) clockRef = WeakReference(clock)
        val insertionOrder = p?.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_INSERTION_ORDER,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_INSERTION_ORDER
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_INSERTION_ORDER
        // 插入位置 → 浮层定位依据："时钟之后"= 歌词紧跟时钟右边（不靠屏幕最右/信号区）
        // 布局项：边距（右侧避让图标区）
        val leftPadDp = p?.getInt(rc.KEY_HOOK_STATUS_BAR_LYRIC_PADDING_LEFT_DP, 0) ?: 0
        val rightPadDp = p?.getInt(rc.KEY_HOOK_STATUS_BAR_LYRIC_PADDING_RIGHT_DP, 0) ?: 0
        // 歌词 wrapper 挂载：插入状态栏父容器贴时钟（参考上游 ensureViews/layoutParams）。
        // 不构建独立 overlay 浮层——那套需 hook 系统触摸中转（installWindowTouchHook），已废弃。
        val isPortrait = root.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        val maxWidthDp = if (isPortrait) {
            p?.getInt(rc.KEY_HOOK_STATUS_BAR_LYRIC_PORTRAIT_DYNAMIC_MAX_WIDTH, rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_PORTRAIT_DYNAMIC_MAX_WIDTH)
                ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_PORTRAIT_DYNAMIC_MAX_WIDTH
        } else {
            p?.getInt(rc.KEY_HOOK_STATUS_BAR_LYRIC_LANDSCAPE_DYNAMIC_MAX_WIDTH, rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_LANDSCAPE_DYNAMIC_MAX_WIDTH)
                ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_LANDSCAPE_DYNAMIC_MAX_WIDTH
        }
        configMaxWidthPx = if (maxWidthDp > 0) (maxWidthDp * density).toInt() else -1

        // 封面图标设置
        iconEnabled = p?.getBoolean(rc.KEY_HOOK_STATUS_BAR_LYRIC_ICON_ENABLED, false) ?: false
        iconStyle = p?.getInt(rc.KEY_HOOK_STATUS_BAR_LYRIC_ICON_STYLE, rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_STYLE)
            ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_STYLE
        iconSizePx = (p?.getInt(rc.KEY_HOOK_STATUS_BAR_LYRIC_ICON_SIZE_DP, rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_SIZE_DP)
            ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_SIZE_DP).coerceIn(
            rc.STATUS_BAR_LYRIC_ICON_MIN_SIZE_DP,
            rc.STATUS_BAR_LYRIC_ICON_MAX_SIZE_DP
        ).let { (it * density).toInt() }
        iconOrder = p?.getInt(rc.KEY_HOOK_STATUS_BAR_LYRIC_ICON_ORDER, rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_ORDER)
            ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_ORDER
        val prevRotating = iconRotating
        iconRotating = iconEnabled && iconStyle == rc.STATUS_BAR_LYRIC_ICON_ROTATING_COVER
        // 旋转开关刚打开时重置时间基准，避免用上一段旋转留下的旧时间戳算出一次大跳角
        if (iconRotating && !prevRotating) lastIconRotateTime = 0L
        if (!iconRotating) lastIconRotateTime = 0L

        // 图标间距（对齐 HyperLyric：读键，默认3dp，clamp 0..16）
        iconSpacingPx = (p?.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_ICON_SPACING_DP,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_SPACING_DP
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ICON_SPACING_DP)
            .coerceIn(rc.STATUS_BAR_LYRIC_ICON_MIN_SPACING_DP, rc.STATUS_BAR_LYRIC_ICON_MAX_SPACING_DP)
            .let { (it * density).toInt() }
        // 内容模式（对齐 HyperLyric：index 语义 0=无 1=音乐信息 2=歌词）
        contentMode = p?.getInt(rc.KEY_HOOK_STATUS_BAR_LYRIC_CONTENT_MODE, 2) ?: 2

        // 通知图标容器（隐藏时 INVISIBLE）
        notifIconRef = findNotificationIconContainer(root)?.let { WeakReference(it) }
        // 通知图标"播放时右移"：独立控制器（overlay 摘除重插 + 右侧定位）
        notificationIconPosition?.release()
        notificationIconPosition = StatusBarNotificationIconPosition(root)
        // Keyguard（锁屏隐藏）
        keyguardService = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? KeyguardManager

        // 容器：wrapper（限宽 + 跑马灯）→ 内容行（水平，垂直居中）→ [图标] + 歌词（weight=1）
        // 左右边距用 wrapper.setPadding 内边距（对齐 HyperLyric LayoutConfig.setPadding），
        // 右边距直接进 paddingRight 控制右边界，不再走浮层 margin。
        val wrapper = MaxWidthLinearLayout(context.applicationContext).apply {
            maxWidthPx = configMaxWidthPx
            setPadding((leftPadDp * density).toInt(), 0, (rightPadDp * density).toInt(), 0)
            clipChildren = true
            clipToPadding = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = android.widget.LinearLayout(context.applicationContext).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            clipChildren = true
            clipToPadding = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val icon = ImageView(context.applicationContext).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (iconEnabled) android.view.View.VISIBLE else android.view.View.GONE
            applyIconShape(this, iconStyle)
            // 布局完成后重算裁剪轮廓（未布局前 width/height=0，setOval 为空）
            post { invalidateOutline() }
        }
        iconView = icon
        val iconLp = android.widget.LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
            rightMargin = iconSpacingPx
        }
        val lyricLp = android.widget.LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        )
        // 音乐信息 TextView（内容模式=音乐信息时替代歌词行显示 标题 - 歌手，对齐 HyperLyric CUSTOM_MUSIC_INFO）
        val musicInfo = android.widget.TextView(context.applicationContext).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            textSize = (p?.getInt(
                rc.KEY_HOOK_STATUS_BAR_LYRIC_TEXT_SIZE,
                rc.DEFAULT_STATUS_BAR_LYRIC_TEXT_SIZE_SP
            ) ?: rc.DEFAULT_STATUS_BAR_LYRIC_TEXT_SIZE_SP).coerceIn(
                rc.MIN_HOOK_STATUS_BAR_LYRIC_TEXT_SIZE_SP,
                rc.MAX_HOOK_STATUS_BAR_LYRIC_TEXT_SIZE_SP
            ).toFloat()
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (contentMode == 1) android.view.View.VISIBLE else android.view.View.GONE
        }
        musicInfoView = musicInfo
        val musicInfoLp = android.widget.LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        )
        // 内容模式（index 语义）：0=无 1=音乐信息 2=歌词
        view.visibility = if (contentMode == 2) android.view.View.VISIBLE else android.view.View.GONE
        if (iconEnabled && iconOrder == rc.STATUS_BAR_LYRIC_ICON_BEFORE_LYRIC) {
            row.addView(icon, iconLp)
            row.addView(view, lyricLp)
            row.addView(musicInfo, musicInfoLp)
        } else {
            row.addView(view, lyricLp)
            row.addView(musicInfo, musicInfoLp)
            if (iconEnabled) row.addView(icon, iconLp)
        }
        wrapper.addView(
            row,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // layoutParams 在插入点确定后按「目标父容器」类型生成（锚点父容器可能不是 root）
        HookLogger.i(
            TAG,
            "状态栏歌词边距（setPadding）：left=${(leftPadDp * density).toInt()}px " +
                    "right=${(rightPadDp * density).toInt()}px（设置 ${leftPadDp}/${rightPadDp} dp）"
        )
        // 手势改为观察系统分发（hook 根 dispatchTouchEvent）触发，歌词永不消费触摸，
        // 因此状态栏下拉通知栏/交互永远可用（解决"遮挡下拉打不开通知栏"痛点）。
        // 不再挂会消费 DOWN 的 view 级 touch listener。
        wrapperRef = WeakReference(wrapper)
        // 插入位置：对齐上游的「锚点」思路，但按 ROM 分流，两边都不牺牲。
        //   非小米（ColorOS / realmeUI 等）：套上游 lyricon 的写法 —— 找锚点（默认 clock）→ 取父容器
        //     → 在锚点前/后 addView。这是上游为跨 ROM 设计的做法。
        //     真我原来不显示，正是因为旧代码用 root.indexOfChild(clock) 在「clock 不是 root 直接子视图」
        //     的 ROM 上得到 -1，歌词被插到索引 0、压在挖孔左侧，宽度 0 或整块不见。
        //   小米：维持「根插入 + 挖孔右侧」。因为小米 clock 的父容器就是状态栏的水平内容区，
        //     WiFi / 信号 / 电池这些系统图标都在里面，插一个歌词进去会把它们挤出屏幕（1.3.40 实测踩到）。
        //     root 本身是 FrameLayout 系，歌词以 WRAP_CONTENT + CENTER_VERTICAL 居中叠加，不参与水平排布。
        //   判断依据是 root 的类名（小米恒为 MiuiPhoneStatusBarView）。
        // 1.5.134：挂靠锚点可选「状态栏根 / 时钟」。默认根（0），小米维持原有根插入不受影响；
        // 只有显式选「时钟」才让小米也走锚点插入，此时只认 clock 本身，不套后备容器。
        val anchorTarget = try {
            p?.getInt(rc.KEY_HOOK_STATUS_BAR_LYRIC_ANCHOR_TARGET, rc.ANCHOR_TARGET_ROOT)
                ?: rc.ANCHOR_TARGET_ROOT
        } catch (t: Throwable) {
            rc.ANCHOR_TARGET_ROOT
        }
        val wantClockAnchor = anchorTarget == rc.ANCHOR_TARGET_CLOCK
        val preferredAnchor = if (wantClockAnchor) {
            "clock"
        } else {
            (p?.getString(
                rc.KEY_HOOK_STATUS_BAR_LYRIC_ANCHOR,
                rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ANCHOR
            ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ANCHOR).trim().ifEmpty {
                rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ANCHOR
            }
        }
        // 候选链：显式选「时钟」时只认 clock（不套后备容器，避免插进 system_icon_area 把系统图标挤出屏幕）；
        // 其余情况沿用「首选锚点 → 后备容器」，全不中才退回根插入。
        var anchorName = preferredAnchor
        var anchorId = 0
        var anchorView: android.view.View? = null
        var anchorParent: ViewGroup? = null
        val anchorTrace = StringBuilder()
        val anchorCandidates = if (wantClockAnchor) {
            listOf("clock")
        } else {
            (listOf(preferredAnchor) + ANCHOR_FALLBACKS).distinct()
        }
        for (candidate in anchorCandidates) {
            val candidateId = root.resources.getIdentifier(candidate, "id", root.context.packageName)
            if (candidateId == 0) {
                anchorTrace.append(candidate).append(":no_id ")
                continue
            }
            val candidateView = root.findViewById<android.view.View>(candidateId)
            val candidateParent = candidateView?.parent as? ViewGroup
            if (candidateView != null && candidateParent != null) {
                anchorName = candidate
                anchorId = candidateId
                anchorView = candidateView
                anchorParent = candidateParent
                anchorTrace.append(candidate).append(":hit ")
                break
            }
            anchorTrace.append(candidate).append(":no_parent ")
        }
        val rootLooksMiui = root.javaClass.simpleName.contains("Miui", ignoreCase = true)
        val hitView = anchorView
        val hitParent = anchorParent
        val insertedVia: String
        val insertedIndex: Int
        if ((!rootLooksMiui || wantClockAnchor) && hitView != null && hitParent != null) {
            wrapper.layoutParams = layoutParams(hitParent)
            val anchorIndex = hitParent.indexOfChild(hitView)
            insertedIndex = (if (insertionOrder == rc.STATUS_BAR_LYRIC_INSERTION_BEFORE_CLOCK) {
                anchorIndex
            } else {
                anchorIndex + 1
            }).coerceIn(0, hitParent.childCount)
            hitParent.addView(wrapper, insertedIndex)
            insertedVia = "anchor"
        } else {
            wrapper.layoutParams = layoutParams(root)
            insertedIndex = run {
                val cutoutId = root.resources.getIdentifier("cutout_space_view", "id", root.context.packageName)
                val cutoutIndex = if (cutoutId != 0) {
                    val cv = root.findViewById<android.view.View>(cutoutId)
                    if (cv != null) root.indexOfChild(cv) else -1
                } else -1
                val base = if (clock != null) {
                    val ci = root.indexOfChild(clock)
                    if (insertionOrder == rc.STATUS_BAR_LYRIC_INSERTION_BEFORE_CLOCK) ci else ci + 1
                } else {
                    root.childCount
                }
                // 无论如何不得插到挖孔左侧
                if (cutoutIndex >= 0) base.coerceAtLeast(cutoutIndex + 1) else base
            }.coerceIn(0, root.childCount)
            root.addView(wrapper, insertedIndex)
            insertedVia = "root"
        }
        HookLogger.i(
            TAG,
            "状态栏歌词插入点：via=$insertedVia rom=${if (rootLooksMiui) "miui" else "other"} " +
                    "anchorTarget=$anchorTarget wantClock=$wantClockAnchor " +
                    "anchor=$anchorName anchorId=$anchorId tried=[$anchorTrace] " +
                    "anchorParent=${anchorParent?.javaClass?.simpleName ?: "null"} " +
                    "index=$insertedIndex rootType=${root.javaClass.simpleName}"
        )
        // 一次性调试：等待状态栏填充完成后打印根的所有直接子视图（类名 + resId 名 + 索引），用于定位插入点
        root.postDelayed({
            runCatching {
                val sb = StringBuilder("状态栏根子视图_延迟[${root.childCount}]:")
                for (i in 0 until root.childCount) {
                    val c = root.getChildAt(i)
                    val idName = runCatching { root.resources.getResourceEntryName(c.id) }.getOrDefault("noId#${c.id}")
                    sb.append("\n  [$i] ${c.javaClass.simpleName} id=$idName")
                }
                HookLogger.i(TAG, sb.toString())
            }
        }, 2000)
        // 布局后：限宽避让右侧图标区（不盖信号/WiFi/电量）。
        // 歌词已插到摄像头右侧主区，右边界用通知图标容器左边缘（右侧常驻内容），
        // 绝不推挤右侧系统图标。超级岛避让由帧回调低频策略负责。
        wrapper.post {
            val w = wrapperRef?.get() as? MaxWidthLinearLayout ?: return@post
            if (!w.isAttachedToWindow) return@post
            // 右边距已由 wrapper.setPadding 的 paddingRight 直接控制，
            // 限宽用配置宽度（对齐 HyperLyric），避让右侧图标区/超级岛由 updateIslandAvoidance 低频策略负责。
            if (configMaxWidthPx > 0) w.maxWidthPx = configMaxWidthPx
            w.requestLayout()
            // 实测宽度诊断：布局后读取 wrapper/row/view 真实宽度，用于校准"跟唱字滚动"的判定宽度
            w.post {
                val v = lyricView
                val rowW = if (w.childCount > 0) w.getChildAt(0).width else -1
                HookLogger.i(
                    TAG,
                    "状态栏歌词实测宽度：wrapper=${w.width} row=$rowW view=${v?.width} maxWidthPx=${w.maxWidthPx}"
                )
            }
        }
        lyricView = view
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        HookLogger.i(
            TAG,
            "状态栏歌词已挂载（插入状态栏布局流，参考上游），rootType=${root.javaClass.simpleName}, " +
                    "via=$insertedVia index=$insertedIndex, maxWidthDp=$maxWidthDp, iconEnabled=$iconEnabled, iconStyle=$iconStyle"
        )
    }

    /** 挂载 layoutParams：按状态栏父容器类型生成（参考上游思路，自写） */
    private fun layoutParams(parent: ViewGroup): ViewGroup.LayoutParams = when (parent) {
        is android.widget.LinearLayout -> android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { gravity = android.view.Gravity.CENTER_VERTICAL }

        is android.widget.FrameLayout -> android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.Gravity.CENTER_VERTICAL
        )

        else -> ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // ================= 时钟隐藏策略 =================
    /** 实时播放态：直接查 MediaSession（不靠进度推进猜，重启状态栏/数据流断也能识别） */
    private fun mediaActuallyPlaying(): Boolean {
        // ★性能：这个方法原来每帧都跨 binder 走一次 getActiveSessions，还要为每个会话新建
        // MediaController，120Hz 下等于每秒上百次 IPC + 上百个临时对象，全部落在 SystemUI 主线程。
        // 现在加 500ms 缓存，播放/暂停切换由下面的 invalidatePlayingCache() 主动打洞。
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - playingQueryAtMs < PLAYING_QUERY_TTL_MS) return playingQueryValue
        playingQueryAtMs = nowMs
        playingQueryValue = queryMediaActuallyPlaying()
        return playingQueryValue
    }

    /** 已知播放状态发生跳变（位置推进/停止、切歌）时清缓存，下一次查询立刻回源。 */
    private fun invalidatePlayingCache() {
        playingQueryAtMs = 0L
    }

    private fun queryMediaActuallyPlaying(): Boolean {
        return runCatching {
            val context = appContext ?: return@runCatching false
            val msm = context.getSystemService(android.content.Context.MEDIA_SESSION_SERVICE)
                    as? android.media.session.MediaSessionManager ?: return@runCatching false
            val sessions = msm.getActiveSessions(null)
            sessions.any { s ->
                runCatching {
                    val controller = android.media.session.MediaController(context, s.sessionToken)
                    val ps = controller.playbackState
                    ps != null && ps.state == android.media.session.PlaybackState.STATE_PLAYING && ps.isActive
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    /** 稳定播放判定：播放中，或刚停止仍在防抖窗口内（避免切歌瞬间 media 状态短暂翻非播放导致闪烁） */
    private fun stablePlaying(): Boolean {
        if (mediaActuallyPlaying()) {
            mediaNotPlayingSince = 0L
            return true
        }
        val now = SystemClock.elapsedRealtime()
        if (mediaNotPlayingSince == 0L) mediaNotPlayingSince = now
        return now - mediaNotPlayingSince < NOT_PLAYING_RESTORE_DEBOUNCE_MS
    }

    private fun updateClockPolicy() {
        val rc = RootConstants
        val behavior = prefs?.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_CLOCK_HIDE_BEHAVIOR,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_CLOCK_HIDE_BEHAVIOR
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_CLOCK_HIDE_BEHAVIOR
        var shouldHide = when (behavior) {
            rc.STATUS_BAR_LYRIC_CLOCK_HIDE_WHILE_PLAYING -> mediaActuallyPlaying()
            rc.STATUS_BAR_LYRIC_CLOCK_HIDE_WHEN_ISLAND_PRESENT ->
                StatusBarLyricIslandRegionDispatcher.hasIsland()
            else -> false
        }
        // 手势"临时显示时钟"覆盖
        if (SystemClock.elapsedRealtime() < temporarilyShowClockUntil) shouldHide = false
        val clock = clockRef?.get() ?: return
        if (shouldHide) {
            if (clock.visibility != android.view.View.GONE) {
                if (clockOriginalVisibility == null) {
                    clockOriginalVisibility = clock.visibility
                }
                clock.visibility = android.view.View.GONE
            }
        } else {
            restoreClockVisibility()
        }
    }

    private fun restoreClockVisibility() {
        val clock = clockRef?.get()
        if (clock != null && clock.visibility == android.view.View.GONE) {
            // 用记录的原始可见性恢复；未记录时（极端路径）也恢复为可见，避免"暂停后一直藏着"
            clock.visibility = clockOriginalVisibility ?: android.view.View.VISIBLE
        }
        clockOriginalVisibility = null
    }

    // ================= 通知图标隐藏策略 =================
    private fun updateNotificationIconPolicy() {
        val rc = RootConstants
        val behavior = prefs?.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_NOTIFICATION_ICON_HIDE_BEHAVIOR,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_NOTIFICATION_ICON_HIDE_BEHAVIOR
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_NOTIFICATION_ICON_HIDE_BEHAVIOR
        val playing = mediaActuallyPlaying()
        // 播放时右移：通知图标移到系统图标旁（优先级最高，不参与隐藏）
        if (behavior == rc.STATUS_BAR_LYRIC_NOTIFICATION_ICON_MOVE_RIGHT_WHILE_PLAYING) {
            notificationIconPosition?.setEnabled(playing)
            // 右移模式不隐藏：恢复系统原可见性
            restoreNotificationIconVisibility()
            return
        }
        // 非右移模式：确保已还原
        notificationIconPosition?.setEnabled(false)
        val shouldHide = behavior == rc.STATUS_BAR_LYRIC_NOTIFICATION_ICON_HIDE_WHILE_PLAYING &&
                playing
        val container = notifIconRef?.get() ?: return
        if (shouldHide) {
            if (container.visibility == android.view.View.VISIBLE) {
                notifIconNativeVisibility = android.view.View.VISIBLE
                container.visibility = android.view.View.INVISIBLE
            }
        } else {
            restoreNotificationIconVisibility()
        }
    }

    private fun restoreNotificationIconVisibility() {
        val container = notifIconRef?.get()
        val nativeVisibility = notifIconNativeVisibility
        if (container != null && nativeVisibility == android.view.View.VISIBLE &&
            container.visibility == android.view.View.INVISIBLE
        ) {
            container.visibility = android.view.View.VISIBLE
        }
        notifIconNativeVisibility = null
    }

    // ================= 锁屏策略 =================
    private fun updateKeyguardPolicy() {
        if (!(prefs?.getBoolean(RootConstants.KEY_HOOK_STATUS_BAR_LYRIC_HIDE_ON_LOCK_SCREEN, true) ?: true)) {
            lastKeyguardLocked = false
            return
        }
        keyguardCheckCounter++
        if (keyguardCheckCounter % 2 == 0) {
            lastKeyguardLocked = keyguardService?.isKeyguardLocked == true
        }
    }

    // ================= 超级岛避让 =================
    private fun updateIslandAvoidance() {
        val adjust = prefs?.getBoolean(
            RootConstants.KEY_HOOK_STATUS_BAR_LYRIC_ADJUST_WIDTH_FOR_SUPER_ISLAND,
            true
        ) ?: true
        val wrapper = wrapperRef?.get() as? MaxWidthLinearLayout ?: return
        if (!adjust) {
            // 关闭岛避让 → 释放宽度回配置值（否则之前避让收窄的 maxWidthPx 不会恢复）
            if (configMaxWidthPx > 0 && wrapper.maxWidthPx != configMaxWidthPx) {
                wrapper.maxWidthPx = configMaxWidthPx
                wrapper.requestLayout()
            }
            return
        }
        if (configMaxWidthPx <= 0) return
        val bounds = StatusBarLyricIslandRegionDispatcher.visibleIslandBounds() ?: return
        if (!wrapper.isLaidOut || wrapper.height <= 0) return
        wrapper.getLocationInWindow(wrapperLocation)
        val left = wrapperLocation[0]
        val top = wrapperLocation[1]
        if (bounds.bottom <= top || bounds.top >= top + wrapper.height) return
        if (bounds.right <= left) return
        val spacing = islandSpacingPx(wrapper)
        // 避让补偿 paddingRight：歌词内容右边界 = wrapper右缘 - paddingRight，
        // maxWidthPx 限制的是 wrapper 总宽（含 padding），故可用宽度要加回 paddingRight，
        // 否则右边距会让歌词内容被挤偏，避让收缩不足（歌词没缩窄）。
        val rightPadPx = wrapper.paddingRight
        val available = (bounds.left - left - spacing + rightPadPx).coerceAtLeast(1)
        val target = minOf(configMaxWidthPx, available)
        // 诊断日志（节流，每 8 次低频≈1s）
        if (++islandAvoidLogCounter % 8 == 0) {
            HookLogger.d(
                TAG,
                "ISL_AVOID adjust=$adjust configMax=$configMaxWidthPx left=$left top=$top " +
                        "bounds=$bounds spacing=$spacing padR=$rightPadPx avail=$available " +
                        "target=$target cur=${wrapper.maxWidthPx} laid=${wrapper.isLaidOut} h=${wrapper.height}"
            )
        }
        if (wrapper.maxWidthPx != target) {
            wrapper.maxWidthPx = target
            wrapper.requestLayout()
        }
    }

    private fun islandSpacingPx(wrapper: View): Int {
        val resources = wrapper.resources
        val densityDpi = resources.displayMetrics.densityDpi
        if (islandSpacingDensityDpi == densityDpi) return cachedIslandSpacingPx
        val fallback = (4f * resources.displayMetrics.density).toInt()
        val spacing = runCatching {
            val resourceId = resources.getIdentifier(
                "status_bar_island_padding",
                "dimen",
                "com.android.systemui"
            )
            if (resourceId != 0) resources.getDimensionPixelSize(resourceId) else fallback
        }.getOrDefault(fallback)
        islandSpacingDensityDpi = densityDpi
        cachedIslandSpacingPx = spacing
        return spacing
    }

    // ================= 超级岛隐藏策略 =================
    /** 状态栏歌词显示时对超级岛（灵动岛）的处理：清超级岛歌词注入 + 按老词幕方式压岛容器（宽高0+GONE） */
    private var islandHiddenFlag = false

    /** 岛容器原始状态（对齐老词幕 XiaomiIslandHooker：保存完整状态，恢复时还原，无残留） */
    private data class IslandViewState(
        val width: Int,
        val height: Int,
        val visibility: Int,
        val alpha: Float,
        val clickable: Boolean,
        val enabled: Boolean,
        val focusable: Boolean
    )

    private val islandStateMap = java.util.WeakHashMap<View, IslandViewState>()

    /** 恢复防抖：切歌/过渡瞬间 media 会短暂翻成非播放，需稳定停播这么久才恢复岛（避免换歌透明闪几下） */
    private var mediaNotPlayingSince = 0L

    private fun updateIslandHidePolicy() {
        val rc = RootConstants
        val behavior = prefs?.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_ISLAND_HIDE_BEHAVIOR,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ISLAND_HIDE_BEHAVIOR
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_ISLAND_HIDE_BEHAVIOR
        // 暂停/取消播放（无活跃播放会话）→ 超级岛恢复到系统原状（不注入歌词）。
        // 歌词按播放状态显示：暂停时歌词消失（clearAllViews），不 refresh 拉回歌词。
        // 用户要求：不管设的是"播放时隐藏"还是"始终隐藏"，没有在播时岛都不藏着，
        // 但歌词也不能一直挂在岛上。
        if (!stablePlaying()) {
            IslandInjectionReconciler.statusBarLyricIslandForcedHidden = false
            runCatching {
                BaseIslandRenderer.clearAllViews()
            }
            restoreIslandVisibility(refreshLyric = false)
            return
        }
        if (behavior == rc.STATUS_BAR_LYRIC_ISLAND_HIDE_NONE) {
            restoreIslandVisibility(refreshLyric = true)
            return
        }
        // 播放中：WHILE_PLAYING / ALWAYS → 一律隐藏超级岛，
        // 不再依赖状态栏歌词可见性（否则歌词暂不可见时"始终隐藏"会失效）。
        hideIslandViews()
    }

    /** 找系统岛容器（id=island_content / container / island_container）：从注入宿主 root 向上沿父链定位。
     *  3.0.888 修正：当前 SystemUI 层级是 DynamicIslandBackgroundView → DynamicIslandContentView(id=island_content)
     *  → small/big/expanded + container(id=container)。旧名 island_container 在 OS4 上找不到 →
     *  隐藏超级岛压不到容器 → 玻璃模式切状态栏模式玻璃岛清不掉。
     *  找到内容容器后连同其父 DynamicIslandBackgroundView（岛外形/玻璃绘制根）一起返回，一压全藏。 */
    private fun findIslandContainers(): List<View> {
        val found = mutableListOf<View>()
        runCatching {
            IslandPresentationCoordinator.snapshotAttachedHosts().forEach { token ->
                var v: View? = token.root
                while (v != null) {
                    if (v.id != View.NO_ID) {
                        val name = runCatching {
                            v.resources.getResourceEntryName(v.id)
                        }.getOrNull()
                        if (name == "island_content" ||
                            name == "container" ||
                            name == "island_container"
                        ) {
                            found += v
                            // 内容容器的父 = DynamicIslandBackgroundView（岛外形/玻璃绘制根），一并压。
                            // ★3.0.1099：这个父层是整块岛窗口的共享根，压它会连充电岛一起压没；
                            //   现在源头拦截已生效，走不到这里（见 hideIslandViews 的提前返回）。
                            val parent = v.parent as? View
                            if (parent != null && parent !== v) {
                                found += parent
                            }
                            break
                        }
                    }
                    v = v.parent as? View
                }
            }
        }
        return found.distinct().also { list ->
            // ★1.4.26：原实现每次调用都无条件打一行，并对每个容器做 getResourceEntryName 反射。
            // 调用方是每帧的岛隐藏逻辑，真我实测 30 秒 7236 次（≈241 次/秒）→ 一天上百万行日志 + 等量反射。
            // 改成"扫描结果变化时才打一行"，稳态零日志，反射也不再参与日志拼接。
            val sig = list.joinToString(";") {
                "${it.javaClass.simpleName}#${System.identityHashCode(it)}:${it.visibility}"
            }
            if (sig != lastIslandContainersSignature) {
                lastIslandContainersSignature = sig
                HookLogger.i(TAG, "findIslandContainers: found=${list.size} $sig")
            }
        }
    }

    /** ★1.4.26：岛容器扫描限频到 5 次/秒，期间沿用上次结果。
     *  applyIslandHideState 是幂等的（已隐藏时直接返回），所以"每帧应用隐藏"照旧保留，
     *  只是不再每帧去沿父链遍历 View 树 + 反射取名——真我上这一处曾是最大热点。 */
    private fun islandContainersThrottled(): List<View> {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - islandScanCacheAt < 200L) {
            val cached = islandScanCache
            return if (cached.isEmpty()) cached else cached.filter { it.isAttachedToWindow }
        }
        val fresh = runCatching { findIslandContainers() }.getOrDefault(emptyList())
        islandScanCache = fresh
        islandScanCacheAt = now
        return fresh
    }

    /** 隐藏超级岛：清模块注入歌词 + 压系统岛容器（保存状态→宽高0+alpha0+GONE+禁交互），并置位守卫标记防恢复 */
    private fun hideIslandViews() {
        if (!islandHiddenFlag) {
            islandHiddenFlag = true
            IslandInjectionReconciler.statusBarLyricIslandForcedHidden = true
            runCatching {
                BaseIslandRenderer.clearAllViews()
            }
            // ★ 3.0.893：状态栏模式 → 卸载全部已挂玻璃/材质（高斯/高光/液态/柔光），
            //   岛回到系统默认外观；材质挂载入口也因标记置位而透传，不再重挂。
            runCatching {
                IslandBlurHook.unloadAll("状态栏隐藏")
            }
            // 3.0.1094：从源头管音乐岛——已经在显示的那颗立即摘掉（removeDynamicIslandView），
            // 后续创建由 MediaIslandCreationGate 直接阻断，不再只靠"压容器"这种事后手段。
            runCatching {
                MediaIslandCreationGate.removeCurrentIsland("状态栏模式隐藏超级岛")
            }
            HookLogger.i(TAG, "超级岛隐藏：已清超级岛歌词注入 + 卸载玻璃材质 + 强制隐藏标记置位")
        }
        runCatching {
            // 源头拦截已在生效（音乐岛建不出来 + 已显示那颗已摘 → 没有要压的对象）：
            // 干脆一个容器都不压。island_content/container 这些 id 在整岛窗口里是共用实例，
            // 压它们会把充电岛等其他岛一起压没（用户实测：充电时岛没了）。
            if (runCatching { MediaIslandCreationGate.isStatusBarIslandMode() }.getOrDefault(false)) {
                HookLogger.d(TAG, "状态栏模式：音乐岛已源头拦截，跳过容器压制（其他岛照常）")
                return@runCatching
            }
            findIslandContainers().forEach { v -> applyIslandHideState(v) }
        }
    }

    private fun applyIslandHideState(v: View) {
        val lp = v.layoutParams ?: return
        if (islandStateMap[v] == null) {
            islandStateMap[v] = IslandViewState(
                width = lp.width,
                height = lp.height,
                visibility = v.visibility,
                alpha = v.alpha,
                clickable = v.isClickable,
                enabled = v.isEnabled,
                focusable = v.isFocusable
            )
        }
        if (lp.width != 0 || lp.height != 0) {
            lp.width = 0
            lp.height = 0
            v.layoutParams = lp
        }
        if (v.alpha != 0f) v.alpha = 0f
        if (v.visibility != View.GONE) v.visibility = View.GONE
        if (v.isClickable) v.isClickable = false
        if (v.isEnabled) v.isEnabled = false
        if (v.isFocusable) v.isFocusable = false
        v.requestLayout()
    }

    /** 恢复超级岛：还原岛容器原始状态（宽高/可见性/alpha/交互）→ 无残留；
     *  refreshLyric=true 时恢复模块注入的超级岛歌词显示；false 时清空注入（暂停场景，歌词消失）
     *
     *  V3 修复（消除无歌词时超级岛每 ~125ms 强制刷新导致岛闪）：
     *  之前 restoreIslandVisibility 在 refreshLyric=true 时无条件调 refreshActiveIsland()，
     *  而 frameCallback 每 15 帧（≈125ms）调一次 updateIslandHidePolicy()，
     *  行为=不隐藏(NONE)时每次走这里 → 每 125ms 整岛强制刷新 → 无歌词时岛闪。
     *  现改为：仅当岛确实处于隐藏标记(islandHiddenFlag)需要还原时才 refresh/clear；
     *  岛未被隐藏时不每帧强制刷新（歌词行本就走 updatePosition 每帧更新，不依赖此整岛刷新）。 */
    private fun restoreIslandVisibility(refreshLyric: Boolean) {
        if (!islandHiddenFlag) {
            // 岛未被隐藏：无需还原，也不每帧强制刷新（避免无限 refresh 循环 → 岛闪）
            return
        }
        islandHiddenFlag = false
        IslandInjectionReconciler.statusBarLyricIslandForcedHidden = false
        runCatching {
            islandStateMap.keys.forEach { v ->
                val s = islandStateMap[v] ?: return@forEach
                val lp = v.layoutParams ?: return@forEach
                if (lp.width != s.width || lp.height != s.height) {
                    lp.width = s.width
                    lp.height = s.height
                    v.layoutParams = lp
                }
                if (v.alpha != s.alpha) v.alpha = s.alpha
                if (v.visibility != s.visibility) v.visibility = s.visibility
                v.isClickable = s.clickable
                v.isEnabled = s.enabled
                v.isFocusable = s.focusable
                v.requestLayout()
            }
            islandStateMap.clear()
        }
        HookLogger.i(TAG, "超级岛恢复：岛还原原尺寸/可见性")
        if (refreshLyric) {
            // ★ 3.0.893：状态栏模式结束 → 按配置重新挂载玻璃/材质（隐藏期间已卸载）
            runCatching {
                IslandBlurHook.onConfigChanged()
            }
            // 恢复模块注入的超级岛歌词显示（播放中 + 未隐藏行为）
            runCatching {
                BaseIslandRenderer.refreshActiveIsland()
            }
            HookLogger.i(TAG, "超级岛恢复：refreshActiveIsland")
        } else {
            // 暂停/未播放：确保岛上无模块歌词（歌词消失）
            runCatching {
                BaseIslandRenderer.clearAllViews()
            }
            HookLogger.i(TAG, "超级岛恢复：暂停，清空注入歌词（歌词消失）")
        }
    }

    /** 关闭状态栏模式/停止帧循环时：把超级岛彻底释放回系统原样（还原容器状态 + 清注入歌词 + 清隐藏标记）。
     *  必须做：否则之前"隐藏超级岛"压下去的岛容器（宽高0+alpha0+GONE+禁交互）在停止帧循环后
     *  不再有 updateIslandHidePolicy 兜底，会永远卡在隐藏态 → 打开超级岛无效、超级岛歌词不显示。 */
    private fun releaseIslandToSystem() {
        IslandInjectionReconciler.statusBarLyricIslandForcedHidden = false
        // V3 修复（3.0.547）：互拆切换时，若超级岛正在开启（切超导），不能 clearAllViews
        // 清掉超导宿主/歌词——否则宿主窗口被 triggerSystemRelayout 搞到 detach，
        // 随后 refreshActiveIsland 只对 attached 宿主生效 → 找不到宿主 → 超导歌词空跑，
        // 必须换歌/重启系统重建媒体岛宿主才恢复（"切超导歌词不过去，换下一首歌才正常"）。
        // 仅纯状态栏模式（超导关闭）才 clearAllViews 释放超级岛回系统原样。
        val islandOn = HookEntry.instance?.prefs?.getBoolean(
            RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
            RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
        ) == true
        // 3.0.557：切回状态栏不再 clearAllViews 清超导宿主（用户指出不该清）。
        // 宿主保留 attached，歌词由 hideIslandLayout 隐藏，再切超导 refreshActiveIsland 直接注入。
        runCatching {
            islandStateMap.keys.forEach { v ->
                val s = islandStateMap[v] ?: return@forEach
                val lp = v.layoutParams ?: return@forEach
                if (lp.width != s.width || lp.height != s.height) {
                    lp.width = s.width
                    lp.height = s.height
                    v.layoutParams = lp
                }
                if (v.alpha != s.alpha) v.alpha = s.alpha
                if (v.visibility != s.visibility) v.visibility = s.visibility
                v.isClickable = s.clickable
                v.isEnabled = s.enabled
                v.isFocusable = s.focusable
                v.requestLayout()
            }
            islandStateMap.clear()
        }
        islandHiddenFlag = false
        HookLogger.i(TAG, "关闭状态栏模式：超级岛已释放回系统原样")
    }

    // ================= 封面图标 =================
    private fun updateIcon() {
        val icon = iconView ?: return
        val metadata = LyriconDataBridge.currentLyricMediaMetadata
        if (metadata !== lastMetadataRef) {
            lastMetadataRef = metadata
            updateMusicInfo()
        }
        if (!iconEnabled || !isPlaying) {
            // 图标开关关闭或未播放（暂停）→ 图标与歌词一起隐藏
            icon.visibility = android.view.View.GONE
            return
        }
        val context = appContext ?: return
        val pkg = metadata?.packageName?.takeIf { it.isNotBlank() }
            ?: LyriconDataBridge.currentLyricPackageName?.takeIf { it.isNotBlank() }
        if (pkg == null) {
            icon.visibility = android.view.View.GONE
            return
        }
        val rc = RootConstants
        val bitmap: android.graphics.Bitmap?
        val drawable: Drawable?
        if (iconStyle == rc.STATUS_BAR_LYRIC_ICON_APP) {
            drawable = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
            bitmap = null
        } else {
            val info = MediaMetadataHelper.getMediaInfo(context, pkg, null, metadata?.sessionToken)
            bitmap = info.albumArt?.takeUnless { it.isRecycled }
            drawable = null
        }
        if (bitmap == null && drawable == null) {
            icon.visibility = android.view.View.GONE
            return
        }
        if (bitmap != null) icon.setImageBitmap(bitmap) else icon.setImageDrawable(drawable)
        if (iconStyle == rc.STATUS_BAR_LYRIC_ICON_MONOCHROME) {
            icon.setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        } else {
            icon.clearColorFilter()
        }
        icon.visibility = android.view.View.VISIBLE
    }

    // ================= 音乐信息（内容模式=CUSTOM_MUSIC_INFO，对齐 HyperLyric 显示 标题-歌手） =================
    private fun updateMusicInfo() {
        val view = musicInfoView ?: return
        if (contentMode != 1) return
        val metadata = LyriconDataBridge.currentLyricMediaMetadata
        val title = metadata?.title?.takeIf { it.isNotBlank() }
        val artist = metadata?.artist?.takeIf { it.isNotBlank() }
        val text = when {
            title != null && artist != null -> "$title - $artist"
            title != null -> title
            artist != null -> artist
            else -> ""
        }
        view.text = text
    }

    private fun applyIconShape(icon: ImageView, style: Int) {
        val rc = RootConstants
        val radius = (8f * density).toInt().coerceAtLeast(1)
        val outline = when (style) {
            rc.STATUS_BAR_LYRIC_ICON_CIRCLE_COVER,
            rc.STATUS_BAR_LYRIC_ICON_ROTATING_COVER -> object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val size = minOf(view.width, view.height)
                    outline.setOval(0, 0, size, size)
                }
            }
            else -> object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
                }
            }
        }
        icon.outlineProvider = outline
        icon.clipToOutline = true
    }

    // ================= 手势（重写：参考上游 StatusBarLyricGestureController 思路，代码自写） =================
    /** 是否有任一启用的手势动作（双击/长按/左滑/右滑），无则放行触摸（不占下拉通知栏） */
    private fun hasGestureEnabled(): Boolean {
        // 3.0.637：状态栏模式关闭（enabled=false）必须连同手势一起禁用——
        // 否则全局 TouchDispatchObserver hook 仍在，关掉状态栏歌词后
        // 点按/滑动状态栏还会触发切歌等手势动作（"关闭后手势还在"）。
        if (!enabled) return false
        val rc = RootConstants
        val p = prefs ?: return false
        return p.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_DOUBLE_TAP_ACTION,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_DOUBLE_TAP_ACTION
        ) != rc.STATUS_BAR_LYRIC_GESTURE_ACTION_NONE ||
                p.getInt(
                    rc.KEY_HOOK_STATUS_BAR_LYRIC_LONG_PRESS_ACTION,
                    rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_LONG_PRESS_ACTION
                ) != rc.STATUS_BAR_LYRIC_GESTURE_ACTION_NONE ||
                p.getInt(
                    rc.KEY_HOOK_STATUS_BAR_LYRIC_SWIPE_LEFT_ACTION,
                    rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_SWIPE_LEFT_ACTION
                ) != rc.STATUS_BAR_LYRIC_GESTURE_SWIPE_NONE ||
                p.getInt(
                    rc.KEY_HOOK_STATUS_BAR_LYRIC_SWIPE_RIGHT_ACTION,
                    rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_SWIPE_RIGHT_ACTION
                ) != rc.STATUS_BAR_LYRIC_GESTURE_SWIPE_NONE
    }

    /** 手势控制器：GestureDetector 直接挂歌词视图（参考上游思路），无手势设置时放行触摸 */
    private class LyricGestureController(
        private val hooker: StatusBarLyricHooker,
        private val touchView: View,
    ) : View.OnTouchListener {
        private val detector = GestureDetector(
            touchView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = hooker.hasGestureEnabled()
                override fun onDoubleTap(event: MotionEvent): Boolean {
                    hooker.performGestureAction(hooker.doubleTapAction(), haptic = true)
                    return true
                }
                override fun onDoubleTapEvent(event: MotionEvent): Boolean = true
                override fun onLongPress(event: MotionEvent) {
                    longPressRecognized = true
                    hooker.performGestureAction(hooker.longPressAction(), haptic = true)
                }
            }
        )
        private var downX = 0f
        private var downY = 0f
        private var ignoreSequence = false
        private var longPressRecognized = false
        // 纵向主导（下拉通知栏意图）→ 歌词放行触摸给系统，不拦截下拉
        private var verticalPassThrough = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!hooker.hasGestureEnabled()) return false
                    downX = event.x
                    downY = event.y
                    ignoreSequence = false
                    longPressRecognized = false
                    verticalPassThrough = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> ignoreSequence = true
                MotionEvent.ACTION_MOVE -> {
                    // 纵向主导（下拉通知栏意图）：放行触摸给系统，歌词不拦截下拉
                    if (!ignoreSequence && !verticalPassThrough) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (Math.abs(dy) > Math.abs(dx) * HORIZONTAL_DOMINANCE_RATIO) {
                            verticalPassThrough = true
                            return false
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!ignoreSequence && !longPressRecognized && !verticalPassThrough) {
                        dispatchHorizontalSwipe(event)
                    }
                }
                MotionEvent.ACTION_CANCEL -> ignoreSequence = true
            }
            val handledByDetector = detector.onTouchEvent(event)
            // 纵向下拉放行后不再消费触摸；其余按手势是否启用消费
            val handled = (hooker.hasGestureEnabled() && !verticalPassThrough) || handledByDetector
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                ignoreSequence = false
                longPressRecognized = false
                verticalPassThrough = false
            }
            return handled
        }

        private fun dispatchHorizontalSwipe(event: MotionEvent): Boolean {
            val deltaX = event.x - downX
            val deltaY = event.y - downY
            val threshold = (8f * touchView.resources.displayMetrics.density).toInt()
            if (Math.abs(deltaX) < threshold ||
                Math.abs(deltaX) <= Math.abs(deltaY) * HORIZONTAL_DOMINANCE_RATIO
            ) {
                return false
            }
            hooker.performSwipeAction(
                if (deltaX < 0f) hooker.swipeLeftAction() else hooker.swipeRightAction()
            )
            return true
        }

        fun release() {
            ignoreSequence = true
            longPressRecognized = false
        }

        private companion object {
            const val HORIZONTAL_DOMINANCE_RATIO = 1.35f
        }
    }

    // ================= 手势观察（不消费触摸，通知栏永远可用） =================
    /** hook View.dispatchTouchEvent：仅在歌词区域内识别双击/长按/左右滑，触发对应动作；
     *  从不消费/修改分发（chain.proceed 结果原样返回），故状态栏下拉通知栏与系统交互始终可用。 */
    internal class TouchDispatchObserver : Hooker {
        private val HORIZONTAL_DOMINANCE_RATIO = 1.35f
        /** 双击判定窗口：300 → 350ms（人实际双击大多 250~330ms，300 卡太紧漏判） */
        private val DOUBLE_TAP_WINDOW_MS = 350L
        /** 两次点击允许的位置漂移（60dp），指腹抖动比手指移动大 */
        private val DOUBLE_TAP_SLOP_DP = 60f
        /** 起滑判定：抬起时兜底用 8dp（原来 24dp 会让 10~24dp 的小拨动被当成轻点） */
        private val SWIPE_MIN_DP = 8f
        /** 拖动中提前触发滑动动作的门槛（≈16dp），不等抬手，手感跟手 */
        private val SWIPE_FIRE_DP = 16f

        private var downX = 0f
        private var downY = 0f
        private var downInGestureZone = false
        private var moved = false
        private var longPressFired = false
        private var swipeFired = false
        private var gestureArmed = false
        private var moveCancelPx = 0f
        private var touchSlopPx = 0f
        private var longPressTask: Runnable? = null
        private var longPressHandler: android.view.View? = null
        private var lastTapTime = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f
        private var inSequence = false


        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val args = chain.args
            val root = rootRef?.get() ?: return result
            if (args.isEmpty() || chain.thisObject !== root) return result
            val ev = args[0] as? android.view.MotionEvent ?: return result
            runCatching { observe(ev, root) }
            return result
        }

        private fun ensureMetrics(root: android.view.View) {
            if (touchSlopPx > 0f) return
            val vc = android.view.ViewConfiguration.get(root.context)
            touchSlopPx = vc.scaledTouchSlop.toFloat()
            val dm = root.resources.displayMetrics.density
            // 长按取消：允许指腹轻微抖动，取 24dp 与 touchSlop 的较大值
            moveCancelPx = Math.max(touchSlopPx, 24f * dm)
        }

        private fun observe(ev: android.view.MotionEvent, root: android.view.View) {
            val action = ev.actionMasked
            // 只在按下判定一次（原来每个 MOVE 都要读 5 次 prefs）
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                gestureArmed = hasGestureEnabled()
            }
            if (!gestureArmed) return
            val wrapper = wrapperRef?.get()
            ensureMetrics(root)
            val dm = root.resources.displayMetrics.density
            when (action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (!inSequence) {
                        inSequence = true
                        downX = ev.rawX
                        downY = ev.rawY
                        moved = false
                        swipeFired = false
                        longPressFired = false
                        // 双状态手势区：歌词可见 → 歌词 wrapper；否则播放中 → 状态栏根区
                        downInGestureZone = gestureHitTest(wrapper, root, ev.rawX, ev.rawY)
                        if (downInGestureZone) scheduleLongPress(root)
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (inSequence && downInGestureZone && !longPressFired) {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (Math.abs(dx) > moveCancelPx || Math.abs(dy) > moveCancelPx) moved = true
                        // 横滑跟手：一过门槛立刻执行，不再等抬手
                        if (!swipeFired) {
                            val firePx = Math.max(touchSlopPx * 1.5f, SWIPE_FIRE_DP * dm)
                            if (Math.abs(dx) > firePx &&
                                Math.abs(dx) > Math.abs(dy) * HORIZONTAL_DOMINANCE_RATIO
                            ) {
                                swipeFired = true
                                cancelLongPress()
                                performSwipeAction(if (dx < 0f) swipeLeftAction() else swipeRightAction())
                            }
                        }
                        if (moved || swipeFired) cancelLongPress()
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (inSequence) {
                        if (downInGestureZone && !longPressFired && !swipeFired) {
                            if (moved) {
                                dispatchSwipe(ev)
                            } else {
                                // 轻点：配了单击动作就立即执行，双击让位；没配才走双击判定
                                val single = singleTapAction()
                                if (single != RootConstants.STATUS_BAR_LYRIC_GESTURE_ACTION_NONE) {
                                    lastTapTime = 0
                                    performGestureAction(single, haptic = true)
                                } else {
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastTapTime < DOUBLE_TAP_WINDOW_MS &&
                                        Math.abs(ev.rawX - lastTapX) < (DOUBLE_TAP_SLOP_DP * dm) &&
                                        Math.abs(ev.rawY - lastTapY) < (DOUBLE_TAP_SLOP_DP * dm)
                                    ) {
                                        lastTapTime = 0
                                        performGestureAction(doubleTapAction(), haptic = true)
                                    } else {
                                        lastTapTime = now
                                        lastTapX = ev.rawX
                                        lastTapY = ev.rawY
                                    }
                                }
                            }
                        }
                        cancelLongPress()
                        inSequence = false
                        longPressFired = false
                        swipeFired = false
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    inSequence = false
                    longPressFired = false
                    swipeFired = false
                }
            }
        }

        /** 双状态手势区判定：歌词可见 → 歌词 wrapper；否则播放中 → 状态栏根区（没歌词也能切歌/播放暂停） */
        private fun gestureHitTest(
            wrapper: android.view.View?,
            root: android.view.View,
            x: Float,
            y: Float
        ): Boolean {
            val lyricOn = lyricView?.visibility == android.view.View.VISIBLE
            if (lyricOn && wrapper != null && wrapper.isLaidOut && wrapper.width > 0) {
                return hitRect(wrapper, x, y)
            }
            if (mediaActuallyPlaying()) {
                return hitRect(root, x, y)
            }
            return false
        }

        private fun hitRect(v: android.view.View, x: Float, y: Float): Boolean {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return x >= loc[0] && x <= loc[0] + v.width &&
                    y >= loc[1] && y <= loc[1] + v.height
        }

        private fun scheduleLongPress(handler: android.view.View) {
            cancelLongPress()
            longPressHandler = handler
            val runnable = Runnable {
                if (inSequence && downInGestureZone && !moved) {
                    longPressFired = true
                    performGestureAction(longPressAction(), haptic = true)
                }
            }
            longPressTask = runnable
            handler.postDelayed(runnable, 500)
        }

        private fun cancelLongPress() {
            longPressTask?.let { longPressHandler?.removeCallbacks(it) }
            longPressTask = null
            longPressHandler = null
        }



        private fun dispatchSwipe(ev: android.view.MotionEvent) {
            if (swipeFired) return
            val dx = ev.rawX - downX
            val dy = ev.rawY - downY
            val threshold = SWIPE_MIN_DP * density
            if (Math.abs(dx) < threshold || Math.abs(dx) <= Math.abs(dy) * HORIZONTAL_DOMINANCE_RATIO) return
            swipeFired = true
            performSwipeAction(if (dx < 0f) swipeLeftAction() else swipeRightAction())
        }
    }

    private fun singleTapAction(): Int =
        prefs?.getInt(
            RootConstants.KEY_HOOK_STATUS_BAR_LYRIC_SINGLE_TAP_ACTION,
            RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_SINGLE_TAP_ACTION
        ) ?: RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_SINGLE_TAP_ACTION

    private fun doubleTapAction(): Int =
        prefs?.getInt(
            RootConstants.KEY_HOOK_STATUS_BAR_LYRIC_DOUBLE_TAP_ACTION,
            RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_DOUBLE_TAP_ACTION
        ) ?: RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_DOUBLE_TAP_ACTION

    private fun longPressAction(): Int =
        prefs?.getInt(
            RootConstants.KEY_HOOK_STATUS_BAR_LYRIC_LONG_PRESS_ACTION,
            RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_LONG_PRESS_ACTION
        ) ?: RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_LONG_PRESS_ACTION

    private fun swipeLeftAction(): Int =
        prefs?.getInt(
            RootConstants.KEY_HOOK_STATUS_BAR_LYRIC_SWIPE_LEFT_ACTION,
            RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_SWIPE_LEFT_ACTION
        ) ?: RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_SWIPE_LEFT_ACTION

    private fun swipeRightAction(): Int =
        prefs?.getInt(
            RootConstants.KEY_HOOK_STATUS_BAR_LYRIC_SWIPE_RIGHT_ACTION,
            RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_SWIPE_RIGHT_ACTION
        ) ?: RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_SWIPE_RIGHT_ACTION

    private fun performSwipeAction(swipeAction: Int) {
        val rc = RootConstants
        when (swipeAction) {
            rc.STATUS_BAR_LYRIC_GESTURE_SWIPE_PREVIOUS -> {
                currentController()?.transportControls?.skipToPrevious()
                HookLogger.d(TAG, "手势：上一首")
            }
            rc.STATUS_BAR_LYRIC_GESTURE_SWIPE_NEXT -> {
                currentController()?.transportControls?.skipToNext()
                HookLogger.d(TAG, "手势：下一首")
            }
            else -> Unit
        }
    }

    private fun performGestureAction(action: Int, haptic: Boolean) {
        val rc = RootConstants
        if (haptic && (prefs?.getBoolean(rc.KEY_HOOK_STATUS_BAR_LYRIC_GESTURE_HAPTIC_FEEDBACK, true) ?: true)) {
            wrapperRef?.get()?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        when (action) {
            rc.STATUS_BAR_LYRIC_GESTURE_ACTION_TOGGLE_PLAYBACK -> {
                val controller = currentController()
                val playing = controller?.playbackState?.isActive == true
                if (playing) controller?.transportControls?.pause() else controller?.transportControls?.play()
                HookLogger.d(TAG, "手势：播放/暂停 -> ${if (playing) "暂停" else "播放"}")
            }
            rc.STATUS_BAR_LYRIC_GESTURE_ACTION_TEMPORARY_CLOCK -> {
                temporarilyShowClockUntil = SystemClock.elapsedRealtime() + 3000L
                updateClockPolicy()
                HookLogger.d(TAG, "手势：临时显示时钟 3s")
            }
            rc.STATUS_BAR_LYRIC_GESTURE_ACTION_TEMPORARY_HIDE_LYRIC -> {
                temporarilyHideUntil = SystemClock.elapsedRealtime() + 3000L
                HookLogger.d(TAG, "手势：临时隐藏歌词 3s")
            }
            rc.STATUS_BAR_LYRIC_GESTURE_ACTION_OPEN_MEDIA_APP,
            rc.STATUS_BAR_LYRIC_GESTURE_ACTION_OPEN_MEDIA_APP_FREEFORM -> {
                val pkg = LyriconDataBridge.currentLyricMediaMetadata?.packageName
                    ?: LyriconDataBridge.currentLyricPackageName
                if (pkg != null) launchMediaApp(pkg)
            }
            rc.STATUS_BAR_LYRIC_GESTURE_ACTION_CONTROL_PANEL -> {
                val anchor = wrapperRef?.get()
                if (anchor == null) {
                    HookLogger.w(TAG, "手势：控制面板缺少锚点视图")
                } else {
                    val style = prefs?.getInt(
                        rc.KEY_HOOK_STATUS_BAR_LYRIC_CONTROL_PANEL_STYLE,
                        rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_CONTROL_PANEL_STYLE,
                    ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_CONTROL_PANEL_STYLE
                    when (style) {
                        rc.CONTROL_PANEL_STYLE_MEDIA -> {
                            HookLogger.i(TAG, "手势：控制面板样式=媒体卡")
                            StatusBarLyricControlPanel.dismiss()
                            StatusBarLyricLightPanel.dismiss(false)
                            StatusBarLyricMediaPanel.toggle(anchor) { currentController() }
                        }
                        rc.CONTROL_PANEL_STYLE_LIGHT -> {
                            HookLogger.i(TAG, "手势：控制面板样式=浅色卡")
                            StatusBarLyricControlPanel.dismiss()
                            StatusBarLyricMediaPanel.dismiss(false)
                            StatusBarLyricLightPanel.toggle(
                                anchor = anchor,
                                provider = { currentController() },
                                prefInt = { key, def -> prefs?.getInt(key, def) ?: def },
                                prefBool = { key, def -> prefs?.getBoolean(key, def) ?: def },
                                masterInt = { key, def -> masterPrefs?.getInt(key, def) ?: def },
                            )
                        }
                        else -> {
                            HookLogger.i(TAG, "手势：控制面板样式=经典（读到的值=$style）")
                            StatusBarLyricMediaPanel.dismiss(false)
                            StatusBarLyricLightPanel.dismiss(false)
                            StatusBarLyricControlPanel.toggle(anchor) { currentController() }
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun launchMediaApp(pkg: String) {
        val context = appContext ?: return
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }.getOrNull()
        if (intent != null) {
            runCatching { context.startActivity(intent) }.onFailure { e ->
                HookLogger.w(TAG, "启动媒体应用失败: package=$pkg", e)
            }
        } else {
            HookLogger.w(TAG, "媒体应用没有可用的启动入口: package=$pkg")
        }
    }

    // ================= 播放控制器（MediaSession 解析） =================

    private fun currentController(): MediaController? {
        val context = appContext
        if (context == null) { HookLogger.d(TAG, "手势控制器: appContext 空"); return null }
        val metadata = LyriconDataBridge.currentLyricMediaMetadata
        val pkg = LyriconDataBridge.currentLyricPackageName
            ?.trim()?.takeIf(String::isNotEmpty)
            ?: metadata?.packageName
        if (pkg == null) { HookLogger.d(TAG, "手势控制器: 包名空"); return null }
        val manager = context.getSystemService(android.content.Context.MEDIA_SESSION_SERVICE)
            as? android.media.session.MediaSessionManager
        if (manager == null) { HookLogger.d(TAG, "手势控制器: MediaSessionManager 空"); return null }
        // 按包名取活动会话（对齐超级岛 IslandPlaybackControllerResolver）
        val controllers = runCatching { manager.getActiveSessions(null) }.getOrNull().orEmpty()
            .filter { it.packageName == pkg }
        if (controllers.isEmpty()) { HookLogger.d(TAG, "手势控制器: 无活动会话 pkg=$pkg"); return null }
        // 优先播放中的会话
        val pool = controllers.filter {
            it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        }.ifEmpty { controllers }
        // 命中歌词源 sessionToken
        val sourceToken = metadata?.sessionToken
        if (sourceToken != null) {
            pool.firstOrNull { it.sessionToken == sourceToken }?.let { return it }
        }
        // 命中 mediaId
        val sourceMediaId = metadata?.mediaId
        if (sourceMediaId != null) {
            pool.firstOrNull { controllerMediaId(it) == sourceMediaId }?.let { return it }
        }
        return pool.first()
    }

    private fun controllerMediaId(controller: MediaController): String? =
        runCatching {
            controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?.trim()?.takeIf(String::isNotEmpty)
        }.getOrNull()

    // ================= 通知图标容器查找 =================
    private fun findNotificationIconContainer(root: ViewGroup): View? {
        val candidates = listOf(
            "notification_icon_area",
            "status_bar_notification_area",
            "system_icon_area"
        )
        for (name in candidates) {
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            if (id != 0) {
                val v = root.findViewById<View>(id)
                if (v != null) {
                    HookLogger.d(TAG, "通知图标容器: $name")
                    return v
                }
            }
        }
        return null
    }

    private fun buildStatusBarStyle(): LyricViewStyle {
        // 字体大小/颜色从状态栏独立设置读取（默认与现状一致：13sp 白字），
        // 对齐超级岛 key_hook_text_size / key_hook_text_color 机制但独立控制。
        // 设置变更由 HookEntry 的 prefs 监听自动触发 remount，实时生效。
        val rc = RootConstants
        val p = prefs
        val textSizeSp = (p?.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_TEXT_SIZE,
            rc.DEFAULT_STATUS_BAR_LYRIC_TEXT_SIZE_SP
        ) ?: rc.DEFAULT_STATUS_BAR_LYRIC_TEXT_SIZE_SP).coerceIn(
            rc.MIN_HOOK_STATUS_BAR_LYRIC_TEXT_SIZE_SP,
            rc.MAX_HOOK_STATUS_BAR_LYRIC_TEXT_SIZE_SP
        )
        val textColor = p?.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR
        // ============ 字体样式：字体族 / 粗体 / 字重（3.0.950 新增，直连 key_hook_* 独立控制） ============
        // 字体族档位 → family 名（与设置页选项顺序一致）
        val fontFamilyIndex = p?.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_FONT_FAMILY,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_FONT_FAMILY
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_FONT_FAMILY
        val boldEnabled = p?.getBoolean(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_BOLD,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_BOLD
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_BOLD
        val fontWeightIndex = p?.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_FONT_WEIGHT,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_FONT_WEIGHT
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_FONT_WEIGHT
        // 字体族名（Android 系统可变字体，支持 wght 字重）
        val fontFamilies = arrayOf(
            "sans-serif", "sans-serif-medium", "sans-serif-light", "serif",
            "monospace", "sans-serif-condensed", "sans-serif-black", "cursive"
        )
        val familyName = fontFamilies.getOrElse(fontFamilyIndex) { "sans-serif" }
        // 字重：粗体开关兜底（开启则至少 700），字重档位细化到可变字体 wght（100~900）
        val weightValue = rc.STATUS_BAR_LYRIC_FONT_WEIGHT_VALUES
            .getOrElse(fontWeightIndex) { rc.STATUS_BAR_LYRIC_FONT_WEIGHT_VALUES[rc.STATUS_BAR_LYRIC_WEIGHT_BOLD] }
        val effectiveWeight = if (boldEnabled) maxOf(weightValue, 700) else weightValue
        // 3.0.963：完全对齐超级岛 FontHelper.loadBaseTypeface —— 用 Typeface.DEFAULT 为基础，
        // Typeface.create(DEFAULT, weight, italic) 让中文 fallback 继承 wght，逐级变粗。
        // 超级岛"能设定住"就是这个原因；之前状态栏用 "sans-serif" 族为基础，
        // 中文 fallback 不继承 wght，导致字重档（500/600/700/900）看不出逐级变化。
        // 用户选了特定字体族（非默认）时按族名建基础，再叠 wght。
        val sbTypeface = if (fontFamilyIndex == 0) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                Typeface.create(Typeface.DEFAULT, effectiveWeight, false)
            } else {
                Typeface.create(Typeface.DEFAULT, if (effectiveWeight >= 700) Typeface.BOLD else Typeface.NORMAL)
            }
        } else {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val baseTf = Typeface.create(familyName, Typeface.NORMAL)
                Typeface.create(baseTf, effectiveWeight, false)
            } else {
                Typeface.create(familyName, if (effectiveWeight >= 700) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        // 完全照超级岛 FontHelper.loadFont：fontVariationSettings 返回 null，
        // 字重只靠 typeface 内含的 wght（Typeface.create(Typeface.DEFAULT, weight, false)），
        // 不额外设置 wght 轴（避免强制 axis 干扰中文 fallback，之前 700 不粗就是这个原因）。
        val sbFontVariation: String? = null
        // 诊断：每次 buildStatusBarStyle 打印字体设置读到的原始值 + prefs 是否含这些键（定位"下一首变回默认"）
        HookLogger.i(
            TAG, "buildStyle 字体: familyIdx=$fontFamilyIndex bold=$boldEnabled weightIdx=$fontWeightIndex " +
                "hasFamily=${p?.contains(rc.KEY_HOOK_STATUS_BAR_LYRIC_FONT_FAMILY)} " +
                "hasBold=${p?.contains(rc.KEY_HOOK_STATUS_BAR_LYRIC_BOLD)} " +
                "hasWeight=${p?.contains(rc.KEY_HOOK_STATUS_BAR_LYRIC_FONT_WEIGHT)}"
        )
        // ============ 颜色模式：彩虹（默认开）> 封面取色 > 固定色 ============
        // 对齐超级岛 LyricStyleHelper 的优先级与配色（RAINBOW_COLORS / 封面调色板）
        // 用户自定义过文字颜色（非默认白）时，固定色优先于彩虹/封面取色，让所选颜色即时生效
        // 3.0.1116：状态栏颜色跟随主设置（与超级岛同一份颜色语义），不再自成一套默认白。
        //   主设置里有颜色样式（彩虹/封面/固定）→ 按它来；主设置没有该键时才用状态栏自己的开关。
        val master = masterPrefs
        val masterStyle = master?.let { LyricTextColorStylePolicy.read(it) }
        val masterRainbow = masterStyle != null && LyricTextColorStylePolicy.usesRainbow(masterStyle)
        val masterCover = masterStyle != null && LyricTextColorStylePolicy.usesCoverColor(masterStyle)
        val masterFixed = master?.takeIf {
            it.contains(RootConstants.KEY_HOOK_TEXT_COLOR)
        }?.getInt(RootConstants.KEY_HOOK_TEXT_COLOR, android.graphics.Color.WHITE)

        val fixedCustomized = if (masterFixed != null) true
        else textColor != rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR
        // 状态栏颜色独立（2026-11-12）：状态栏页的「封面取色」一旦打开就生效，
        // 不再被主设置的颜色样式（默认 4=彩虹）或主设置里残留的 key_hook_text_color 否死。
        // 之前 coverColorEnabled 里有 !rainbowEnabled，而 masterRainbow 恒为 true（默认值就是彩虹），
        // 于是封面取色永远进不去——真我上"封面取色无效"就是这个原因。
        val sbCoverOn = p?.getBoolean(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_COVER_COLOR_ENABLED,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_COVER_COLOR_ENABLED
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_COVER_COLOR_ENABLED
        val sbRainbowOn = p?.getBoolean(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_RAINBOW_ENABLED,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_RAINBOW_ENABLED
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_RAINBOW_ENABLED
        // 状态栏页明确把彩虹关掉了（键存在且为 false）→ 以这一页为准
        val sbRainbowExplicitOff =
            p?.contains(rc.KEY_HOOK_STATUS_BAR_LYRIC_RAINBOW_ENABLED) == true && !sbRainbowOn

        // 彩虹的"本来意愿"：不受封面开关压制，封面拿不到主色时用它回落
        val rainbowDesired = when {
            sbRainbowExplicitOff -> false
            else -> masterRainbow || (!fixedCustomized && !masterCover && sbRainbowOn)
        }
        val coverColorEnabled = sbCoverOn
        val rainbowEnabled = !coverColorEnabled && rainbowDesired

        val primaryColors: IntArray
        val bgColors: IntArray
        val hlColors: IntArray
        // 黑白背景反色开关：开启后歌词字色跟随状态栏背景亮度（亮→深字，暗→浅字），
        // 优先级最高，覆盖彩虹/封面取色/固定色
        val invertEnabled = p?.getBoolean(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_INVERT_ENABLED,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_INVERT_ENABLED
        ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_INVERT_ENABLED
        if (invertEnabled) {
            val light = StatusBarInvertTracker.isLight(appContext)
            val invertColor = if (light) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            // 未唱：同色系压暗（半透明），已唱：纯色 → 保留逐字高亮对比与装饰（逐字上跳）效果
            val invertDim = if (light) 0x88000000.toInt() else 0x88FFFFFF.toInt()
            primaryColors = intArrayOf(invertColor)
            bgColors = intArrayOf(invertDim)
            hlColors = intArrayOf(invertColor)
        } else {
        when {
            rainbowEnabled -> {
                // 彩虹 7 色 + 半透明背景 + 同色高亮（与超级岛一致）
                primaryColors = RAINBOW_COLORS.copyOf()
                // 老词幕：未唱的字 80% 不透明（0xCC）+ 亮度调低（RGB 统一 ×0.5 压暗），
                // 已唱的字保持亮，形成"暗的暗、亮的亮"，无黑底也能看清
                bgColors = RAINBOW_COLORS.map {
                    val alpha = 0xCC shl 24
                    val r = ((it shr 16 and 0xFF) * 0.5f).toInt()
                    val g = ((it shr 8 and 0xFF) * 0.5f).toInt()
                    val b = ((it and 0xFF) * 0.5f).toInt()
                    alpha or (r shl 16) or (g shl 8) or b
                }.toIntArray()
                hlColors = RAINBOW_COLORS.copyOf()
            }
            coverColorEnabled -> {
                // 封面取色：优先用状态栏自取封面主色（不依赖超级岛会话），
                // 未就绪回退超级岛共享 palette，再回退固定色（低频策略继续重试）
                val session = CoverColorHelper.currentSession()
                val sharedPalette = session?.let {
                    CoverColorHelper.getCachedColors(useGradient = false, session = it)
                }
                val sharedDominant = sharedPalette?.first?.firstOrNull() ?: 0
                val dominant = statusBarCoverDominant.takeIf { it != 0 } ?: sharedDominant
                val backdrop = when {
                    statusBarCoverDominant != 0 -> statusBarCoverBackdrop
                    sharedPalette?.second?.firstOrNull() != null ->
                        sharedPalette.second.firstOrNull()!!
                    else -> 0
                }
                HookLogger.i(
                    TAG, "封面取色应用: selfDominant=${statusBarCoverDominant} " +
                        "sharedDominant=$sharedDominant dominant=$dominant"
                )
                if (dominant != 0) {
                    primaryColors = intArrayOf(dominant)
                    // 老词幕封面取色：未唱 = 封面主色 HSL 亮度×0.35（压暗保留色彩），已唱 = 主色全亮
                    bgColors = intArrayOf(darkenHsl(dominant, 0.35f))
                    hlColors = intArrayOf(dominant)
                } else if (rainbowDesired) {
                    // 封面还没取到主色 → 回落彩虹，避免"封面无效 + 彩虹被关"两头空
                    primaryColors = RAINBOW_COLORS.copyOf()
                    bgColors = RAINBOW_COLORS.map {
                        val alpha = 0xCC shl 24
                        val r = ((it shr 16 and 0xFF) * 0.5f).toInt()
                        val g = ((it shr 8 and 0xFF) * 0.5f).toInt()
                        val b = ((it and 0xFF) * 0.5f).toInt()
                        alpha or (r shl 16) or (g shl 8) or b
                    }.toIntArray()
                    hlColors = RAINBOW_COLORS.copyOf()
                } else {
                    // 封面未就绪且彩虹也没开：先用固定色（主设置的自定义色优先），低频策略自提取就绪后刷新
                    val fb = masterFixed ?: textColor
                    primaryColors = intArrayOf(fb)
                    bgColors = intArrayOf(darkenHsl(fb, 0.35f))
                    hlColors = intArrayOf(fb)
                }
            }
            else -> {
                // 固定色：未唱背景=主色半透明，高亮=主色（主设置的自定义色优先）
                val fixedColor = masterFixed ?: textColor
                primaryColors = intArrayOf(fixedColor)
                bgColors = intArrayOf(fadeAlpha(fixedColor, 128))
                hlColors = intArrayOf(fixedColor)
            }
        }
        }
        val textSize = (textSizeSp * density).coerceAtLeast(8f)
        // 状态栏自己的持续动画效果（0=静态；编号与超级岛共用的 LyricEffectRenderer 一致）
        val lyricEffect = prefs?.getInt(
            RootConstants.KEY_HOOK_STATUS_BAR_LYRIC_EFFECT,
            RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_EFFECT
        ) ?: RootConstants.DEFAULT_HOOK_STATUS_BAR_LYRIC_EFFECT
        return LyricViewStyle(
            primary = TextLook(
                colors = primaryColors,
                size = textSize,
                // 字体/粗体/字重由设置动态构建（3.0.950 新增），默认保持加粗
                typeface = sbTypeface,
                fontVariationSettings = sbFontVariation,
                // 对齐老词幕：取消软阴影（半透明黑发虚难看），纯靠"未唱调暗+已唱亮"对比度
                shadowRadius = 0f,
                shadowDx = 0f,
                shadowDy = 0f,
                shadowColor = 0x00000000,
                // 对齐超级岛滚动逻辑：relativeProgress/relativeHighlight 开启后，
                // 无逐字数据的歌词也由 RelativeWordBuilder 按时间比例生成逐字，
                // 走 syncRenderer（ScrollStepper 高亮过半滚动），不再掉进 isPlainText
                // 跑马灯分支（时机/滚法与超级岛两套，导致"突字突然偏移"）。
                relativeProgress = true,
                relativeHighlight = true
            ),
            secondary = TextLook(
                colors = intArrayOf(0xFFCCCCCC.toInt()),
                size = (12f * density).coerceAtLeast(7f),
                // 3.0.959：副行（翻译/下一句预览）也套用同一粗体字体，避免副行回到默认细体
                typeface = sbTypeface,
                fontVariationSettings = sbFontVariation,
                relativeProgress = true,
                relativeHighlight = true
            ),
            highlight = Highlight(
                background = bgColors,
                foreground = hlColors
            ),
            gradient = false,
            wordMotion = WordMotion(enabled = false),
            lineDisplay = false,
            transitionConfig = null,
            centerIfPossible = false,
            rightIfPossible = false,
            lyricEffect = lyricEffect,
            // 状态栏的效果颜色也跟"状态栏歌词文字颜色"设置走，不再用彩虹/封面独立配色
            effectSolidColor = textColor,
            // 跑马灯（超长行、无逐字数据）参数：直接照上游默认值写死，不开放设置项
            // 上游 Defaults：速度 35dp/s、重影间距 50dp、起滚等待 300ms、循环间隔 0ms、无限循环、末尾不停
            marqueePacePxPerSec = MARQUEE_SPEED_DP * Resources.getSystem().displayMetrics.density,
            marqueeStartDelayMs = MARQUEE_INITIAL_DELAY_MS,
            marqueeLoopDelayMs = MARQUEE_LOOP_DELAY_MS,
            marqueeSpacingPx = MARQUEE_SPACING_DP * Resources.getSystem().displayMetrics.density
        )
    }

    /** 彩虹 16 色（对齐超级岛 LyricStyleHelper.RAINBOW_COLORS；1.0.9 从 7 色扩到 16 色） */
    private val RAINBOW_COLORS = intArrayOf(
        android.graphics.Color.parseColor("#FF6B6B"), // 亮红
        android.graphics.Color.parseColor("#FF8787"), // 珊瑚红
        android.graphics.Color.parseColor("#FFA94D"), // 亮橙
        android.graphics.Color.parseColor("#FFC078"), // 蜜橙
        android.graphics.Color.parseColor("#FFE066"), // 亮黄
        android.graphics.Color.parseColor("#FFEC99"), // 奶油黄
        android.graphics.Color.parseColor("#C0EB75"), // 黄绿
        android.graphics.Color.parseColor("#69DB7C"), // 亮绿
        android.graphics.Color.parseColor("#38D9A9"), // 青绿
        android.graphics.Color.parseColor("#66D9E8"), // 青蓝
        android.graphics.Color.parseColor("#74C0FC"), // 亮蓝
        android.graphics.Color.parseColor("#4DABF7"), // 天蓝
        android.graphics.Color.parseColor("#9775FA"), // 亮靛
        android.graphics.Color.parseColor("#B197FC"), // 淡紫
        android.graphics.Color.parseColor("#DA77F2"), // 亮紫
        android.graphics.Color.parseColor("#F783AC"), // 粉红
    )

    /** 主色按 alpha 半透明（固定色/封面兜底用）。 */
    private fun fadeAlpha(color: Int, alpha: Int): Int = android.graphics.Color.argb(
        alpha,
        android.graphics.Color.red(color),
        android.graphics.Color.green(color),
        android.graphics.Color.blue(color)
    )

    /** 老词幕取色调暗：RGB 亮度 × factor（保留色相/饱和度，只压暗亮度，等价 HSL-L×0.35） */
    private fun darkenHsl(color: Int, factor: Float): Int {
        val r = (android.graphics.Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (android.graphics.Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (android.graphics.Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(android.graphics.Color.alpha(color), r, g, b)
    }

    // ================= 封面取色（状态栏自取，不依赖超级岛 CoverColorHelper 会话） =================
    // 状态栏封面取色模式开启时，从自己拿到的专辑封面 bitmap 直接提取主色/暗化背景，
    // 灵动岛关闭（会话未激活/palette 无人填充）也能独立取色。
    private var statusBarCoverDominant = 0   // ARGB 主色，0 = 未就绪
    private var statusBarCoverBackdrop = 0  // 主色暗化后的背景色，0 = 未就绪

    /** 封面主色提取（与超级岛 CoverColorHelper.dominantColor 同算法：24x24 采样平均，忽略透明像素）。
     *  关键：HARDWARE 位图不支持 getPixels，必须先拷贝为 ARGB_8888（超级岛 extractColors 同款处理），
     *  否则 SystemUI 的 MediaSession 封面（常为 HARDWARE config）提取必然抛异常返回 0。 */
    private fun extractCoverDominant(src: android.graphics.Bitmap): Int {
        return try {
            if (src.width <= 0 || src.height <= 0) return 0
            val readable = if (src.config == android.graphics.Bitmap.Config.HARDWARE) {
                src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return 0
            } else {
                src
            }
            // 真·老词幕取色：自研 k-means 聚类（ColorExtractorImpl），取图片原始代表色，
            // 不用 onWhite（为白底压暗的人工暗色）——亮红就是亮红
            val palette = io.github.proify.lyricon.colorextractor.palette.ColorExtractorImpl
                .extractThemePalette(readable, 4)
            if (readable !== src) readable.recycle()
            palette.rawColors.firstOrNull() ?: 0
        } catch (e: Exception) {
            HookLogger.i(
                TAG, "封面取色提取异常: ${e.javaClass.simpleName} ${e.message}"
            )
            0
        }
    }


    /** 主色暗化（保留色相，压暗亮度，对齐超级岛 CoverColorHelper.darken DARKEN_FACTOR=0.5）。 */
    private fun darkenCoverColor(color: Int, factor: Float = 0.5f): Int {
        if (color == 0) return 0
        return android.graphics.Color.argb(
            255,
            (android.graphics.Color.red(color) * factor).toInt().coerceIn(0, 255),
            (android.graphics.Color.green(color) * factor).toInt().coerceIn(0, 255),
            (android.graphics.Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    // ================= 换句动画（状态栏独立配置） =================
    /** 读状态栏自己的换句动画设置（Int：0=无动画 / 1=副行放大）。
     *  与超级岛共用同一套动画实现，但配置键各自独立（互不影响）。 */
    private fun readLineSwitchAnim(): Int {
        val rc = RootConstants
        val raw = prefs?.all?.get(rc.KEY_HOOK_STATUS_BAR_LYRIC_SWITCH_ANIM)
        return when (raw) {
            is Int -> raw
            is String -> raw.toIntOrNull() ?: rc.DEFAULT_HOOK_LINE_SWITCH_ANIM
            else -> rc.DEFAULT_HOOK_LINE_SWITCH_ANIM
        }
    }

    /** 低频策略调用：换句动画设置改了立即生效（无需重启 SystemUI / 切歌才生效）。 */
    private fun updateLineSwitchAnimation() {
        val view = lyricView ?: return
        val anim = readLineSwitchAnim()
        if (view.lineSwitchAnimation != anim) {
            view.lineSwitchAnimation = anim
            HookLogger.i(TAG, "状态栏换句动画=$anim（已实时生效）")
        }
    }

    // ================= 播放进度条 =================

    private data class ProgressBarConfig(
        val enabled: Boolean,
        val heightDp: Float,
        val offsetDp: Float,
        val above: Boolean,
        val colorMode: Int
    )

    private var appliedProgressBarSig: String? = null

    /** 进度条推进日志只打一次，供排查「有没有真的在跑」。 */
    private var loggedProgressBarTick = false

    private fun readProgressBarConfig(): ProgressBarConfig {
        val rc = RootConstants
        val all = prefs?.all
        val enabled = when (val raw = all?.get(rc.KEY_LYRIC_STYLE_BASE_SHOW_PROGRESS_BAR)) {
            is Boolean -> raw
            is String -> raw == "true" || raw == "1"
            else -> rc.DEFAULT_SHOW_PROGRESS_BAR
        }
        fun num(key: String, def: Float): Float = when (val raw = all?.get(key)) {
            is Float -> raw
            is Int -> raw.toFloat()
            is Long -> raw.toFloat()
            is String -> raw.toFloatOrNull() ?: def
            else -> def
        }
        val height = num(rc.KEY_LYRIC_STYLE_BASE_PROGRESS_BAR_HEIGHT, rc.DEFAULT_PROGRESS_BAR_HEIGHT_DP)
            .coerceIn(rc.MIN_PROGRESS_BAR_HEIGHT_DP, rc.MAX_PROGRESS_BAR_HEIGHT_DP)
        val offset = num(rc.KEY_LYRIC_STYLE_BASE_PROGRESS_BAR_OFFSET_Y, rc.DEFAULT_PROGRESS_BAR_OFFSET_Y_DP)
            .coerceIn(0f, 8f)
        val colorMode = num(
            rc.KEY_LYRIC_STYLE_BASE_PROGRESS_BAR_COLOR_MODE,
            rc.DEFAULT_PROGRESS_BAR_COLOR_MODE.toFloat()
        ).toInt()
        val position = num(
            rc.KEY_LYRIC_STYLE_BASE_PROGRESS_BAR_POSITION,
            rc.DEFAULT_PROGRESS_BAR_POSITION.toFloat()
        ).toInt()
        return ProgressBarConfig(
            enabled = enabled,
            heightDp = height,
            offsetDp = offset,
            above = position == rc.PROGRESS_BAR_POSITION_ABOVE,
            colorMode = colorMode
        )
    }

    private fun applyProgressBarConfig(view: RichLyricLineView, force: Boolean = false) {
        val cfg = readProgressBarConfig()
        val sig = "${cfg.enabled}/${cfg.heightDp}/${cfg.offsetDp}/${cfg.above}/${cfg.colorMode}"
        if (!force && sig == appliedProgressBarSig) return
        appliedProgressBarSig = sig
        val density = view.resources.displayMetrics.density
        view.applyProgressBar(
            enabled = cfg.enabled,
            heightPx = cfg.heightDp * density,
            offsetPx = cfg.offsetDp * density,
            above = cfg.above,
            colorMode = cfg.colorMode
        )
        HookLogger.i(
            TAG,
            "状态栏进度条 enabled=${cfg.enabled} heightDp=${cfg.heightDp} " +
                "offsetDp=${cfg.offsetDp} above=${cfg.above} colorMode=${cfg.colorMode}"
        )
    }

    private fun updateProgressBarConfig(view: RichLyricLineView) = applyProgressBarConfig(view)

    // ================= 颜色模式刷新（彩虹/封面取色/固定色） =================
    /**
     * 低频策略调用：彩虹/封面取色开关或颜色会话变化时重建歌词样式。
     * 封面取色自给自足：从状态栏自己拿到的专辑封面提取主色（不依赖超级岛会话/调色板），
     * dominant 值纳入签名 → 换歌/封面就绪自动触发刷新。
     */
    private fun updateLyricColorStyle() {
        val view = lyricView ?: return
        val rc = RootConstants
        val p = prefs ?: return
        // 颜色模式与 buildStatusBarStyle 保持同一套优先级（主设置优先）
        val master = masterPrefs
        val masterStyle = master?.let { LyricTextColorStylePolicy.read(it) }
        val masterRainbow = masterStyle != null && LyricTextColorStylePolicy.usesRainbow(masterStyle)
        val masterCover = masterStyle != null && LyricTextColorStylePolicy.usesCoverColor(masterStyle)
        val masterFixed = master?.takeIf {
            it.contains(rc.KEY_HOOK_TEXT_COLOR)
        }?.getInt(rc.KEY_HOOK_TEXT_COLOR, android.graphics.Color.WHITE)
        val fixedCustomized = if (masterFixed != null) true
        else p.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR
        ) != rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR
        // 与 buildStatusBarStyle 同一套判定（2026-11-12 补）：状态栏页的「封面取色」开关直接生效。
        // 旧写法里 coverColorEnabled 带 !rainbowEnabled，而 masterRainbow 恒 true（主设置默认 4=彩虹），
        // 于是这里的取色动作从未执行过，statusBarCoverDominant 永远是 0，
        // buildStatusBarStyle 的封面分支只能回落单色——真我与本机"封面取色无效"都是这一处。
        val sbCoverOn = p.getBoolean(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_COVER_COLOR_ENABLED,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_COVER_COLOR_ENABLED
        )
        val sbRainbowOn = p.getBoolean(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_RAINBOW_ENABLED,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_RAINBOW_ENABLED
        )
        val coverColorEnabled = sbCoverOn
        val rainbowEnabled = !coverColorEnabled && when {
            p.contains(rc.KEY_HOOK_STATUS_BAR_LYRIC_RAINBOW_ENABLED) && !sbRainbowOn -> false
            else -> masterRainbow || (!fixedCustomized && !masterCover && sbRainbowOn)
        }
        val session = CoverColorHelper.currentSession()
        // 固定色也取主设置优先，签名才会跟着主设置改色一起变
        val fixed = masterFixed ?: p.getInt(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_TEXT_COLOR
        )
        // 黑白背景反色：开启时歌词字色跟随状态栏背景亮度（与 buildStatusBarStyle 一致），
        // 背景明暗/开关变化纳入签名触发刷新
        val invertEnabled = p.getBoolean(
            rc.KEY_HOOK_STATUS_BAR_LYRIC_INVERT_ENABLED,
            rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_INVERT_ENABLED
        )
        val invertLight = invertEnabled && StatusBarInvertTracker.isLight(appContext)
        val mode = if (invertEnabled) {
            if (invertLight) "I-L" else "I-D"
        } else if (rainbowEnabled) "R" else if (coverColorEnabled) "C" else "F"
        val signature = "$mode|${session?.revision ?: -1}|$fixed|" +
                (if (coverColorEnabled) statusBarCoverDominant else "") +
                // 持续动画效果编号纳入签名：改效果 → 重建样式 → 立即换动画
                "|E" + p.getInt(
                    rc.KEY_HOOK_STATUS_BAR_LYRIC_EFFECT,
                    rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_EFFECT
                )
        if (signature == appliedColorSignature) return
        // 同一首取不到封面，试满 3 次后进 60s 退避（不再每轮 125ms 反复 k-means）
        if (coverColorEnabled && SystemClock.elapsedRealtime() < coverBackoffUntil &&
            signature == coverAttemptKey
        ) return
        // 封面取色：从当前歌曲专辑封面提取主色（CurrentMediaInfoResolver 与歌词源同源身份）
        var coverReady = false
        if (coverColorEnabled) {
            runCatching {
                val metadata = LyriconDataBridge.currentLyricMediaMetadata
                val pkg = metadata?.packageName?.takeIf { it.isNotBlank() }
                    ?: LyriconDataBridge.currentLyricPackageName?.takeIf { it.isNotBlank() }
                val context = appContext
                if (pkg != null && context != null) {
                    val info = CurrentMediaInfoResolver.getMediaInfo(
                        context = context,
                        packageName = pkg,
                        sourceMetadata = metadata
                    )
                    // 优先 MediaSession 封面；兜底状态栏图标正在显示的 drawable
                    // （灵动岛关闭时，状态栏自己的封面图标就是最可靠的取色源）
                    val albumBitmap = info.albumArt?.takeUnless { it.isRecycled }
                    val iconBitmap = (iconView?.drawable as? android.graphics.drawable.BitmapDrawable)
                        ?.bitmap?.takeUnless { it.isRecycled }
                    val bitmap = albumBitmap ?: iconBitmap
                    if (bitmap != null) {
                        statusBarCoverDominant = extractCoverDominant(bitmap)
                        statusBarCoverBackdrop = darkenCoverColor(statusBarCoverDominant)
                        coverReady = statusBarCoverDominant != 0
                        HookLogger.i(
                            TAG, "封面取色自提取: dominant=" +
                                String.format("#%06X", statusBarCoverDominant and 0xFFFFFF) +
                                " src=${if (albumBitmap != null) "media" else "icon"}" +
                                " config=${bitmap.config} pkg=$pkg title=${info.title}"
                        )
                    } else {
                        HookLogger.i(
                            TAG, "封面取色: 封面缺失 albumArt=${info.albumArt != null} " +
                                "iconDrawable=${iconView?.drawable?.javaClass?.simpleName} " +
                                "pkg=$pkg title=${info.title} source=${info.artworkSource}"
                        )
                    }
                }
            }
        }
        val st = buildStatusBarStyle()
        // 一次性日志：验证 BOLD 是否真正应用（Typeface.style=1 即 BOLD）
        if (!loggedFontStyle) {
            loggedFontStyle = true
            HookLogger.i(
                TAG,
                "状态栏样式日志：typefaceStyle=${st.primary.typeface?.style} (0=正常 1=BOLD 2=斜体 3=粗斜) " +
                    "shadowRadius=${st.primary.shadowRadius} bgAlpha=${(st.highlight.background.firstOrNull()?.ushr(24))}"
            )
        }
        view.setStyle(st)
        // 封面未就绪保持未固化 → 低频策略持续重试，主色就绪后才固化
        if (!coverColorEnabled || coverReady) {
            // 必须与上面的 signature 完全同构（含 |E 动画编号），
            // 否则两边永远不相等 → 每 125ms 重跑一次 k-means 取色（刷屏 + 卡顿 + 耗电）
            appliedColorSignature = "$mode|${session?.revision ?: -1}|$fixed|" +
                    (if (coverColorEnabled) statusBarCoverDominant else "") + "|E" +
                    p.getInt(
                        rc.KEY_HOOK_STATUS_BAR_LYRIC_EFFECT,
                        rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_EFFECT
                    )
            coverAttemptCount = 0
            coverAttemptKey = null
        } else {
            // 没取到封面：同一首累计 3 次就退避 60 秒（换歌/换签名重新计数）
            if (signature == coverAttemptKey) {
                coverAttemptCount++
            } else {
                coverAttemptKey = signature
                coverAttemptCount = 1
            }
            if (coverAttemptCount >= 3) {
                coverBackoffUntil = SystemClock.elapsedRealtime() + 60_000L
                HookLogger.i(TAG, "封面取色暂停重试 60s（已试 $coverAttemptCount 次仍无封面）")
            }
        }
    }

    /** 实时重读竖屏/横屏最大宽度并应用到 wrapper（原只在挂载时读一次，改设置无效） */
    private fun refreshWidthConfig() {
        val w = wrapperRef?.get() as? MaxWidthLinearLayout ?: return
        val root = w.rootView ?: return
        val density = w.resources.displayMetrics.density
        val rc = RootConstants
        val isPortrait = root.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        val maxWidthDp = if (isPortrait) {
            prefs?.getInt(
                rc.KEY_HOOK_STATUS_BAR_LYRIC_PORTRAIT_DYNAMIC_MAX_WIDTH,
                rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_PORTRAIT_DYNAMIC_MAX_WIDTH
            ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_PORTRAIT_DYNAMIC_MAX_WIDTH
        } else {
            prefs?.getInt(
                rc.KEY_HOOK_STATUS_BAR_LYRIC_LANDSCAPE_DYNAMIC_MAX_WIDTH,
                rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_LANDSCAPE_DYNAMIC_MAX_WIDTH
            ) ?: rc.DEFAULT_HOOK_STATUS_BAR_LYRIC_LANDSCAPE_DYNAMIC_MAX_WIDTH
        }
        val target = if (maxWidthDp > 0) (maxWidthDp * density).toInt() else -1
        if (target > 0 && w.maxWidthPx != target) {
            configMaxWidthPx = target
            w.maxWidthPx = target
            w.requestLayout()
        }
    }

    internal class InflateHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val args = chain.args
            if (args.isEmpty()) return result
            val layoutId = (args[0] as? Int) ?: 0
            if (layoutId != statusBarLayoutId) return result
            val root = result as? ViewGroup ?: return result
            rootRef = WeakReference(root)
            HookLogger.i(TAG, "状态栏布局已 inflate，根=$root")
            if (enabled && lyricView == null) {
                attach(root, root.context)
            }
            return result
        }
    }
}

