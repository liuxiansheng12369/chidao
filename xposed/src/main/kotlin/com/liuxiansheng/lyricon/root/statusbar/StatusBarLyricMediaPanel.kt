/*
 * 本文件为 Lyricon V3 自研实现。
 * Copyright 2026 liuxiansheng
 *
 * 状态栏歌词「玻璃媒体卡」控制面板（重做版）。
 *
 * 上一版做成通栏下拉卡，观感像通知栏，已整体废弃。这一版只做一张小尺寸
 * 悬浮玻璃卡：与经典卡同宽 380dp、圆角 28dp、烟熏毛玻璃、底图取当前壁纸做
 * 近似模糊，卡内一条斜向流光缓慢扫过。内容仍是封面、歌名/歌手、进度、
 * 时间、上一首/播放暂停/下一首、关闭。
 *
 * 原经典卡片 StatusBarLyricControlPanel 不动，由「控制面板样式」切换。
 */

package com.liuxiansheng.lyricon.hyper.root.statusbar

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger

internal object StatusBarLyricMediaPanel {

    private const val TAG = "LyricMediaPanel"

    private const val UPDATE_INTERVAL_MS = 250L
    /** 与经典卡同宽（380dp，两侧各留 16dp），不再做得又窄又小 */
    private const val CARD_WIDTH_DP = 380
    private const val SCREEN_MARGIN_DP = 16
    private const val CORNER_DP = 28f
    /** 固定高度，避免 WRAP 被子视图 MATCH_PARENT 撑到整屏 */
    private const val CARD_HEIGHT_DP = 196
    /** 真实背景模糊强度（与系统模糊同一套 0~100 档） */
    private const val BLUR_RADIUS = 80
    /** 模糊层上的极淡烟熏色，只压杂色，不遮背后的画面 */
    private const val BLUR_TINT = 0x1F0B0C11

    private val mainHandler = Handler(Looper.getMainLooper())

    private var popup: PopupWindow? = null
    private var controllerProvider: (() -> MediaController?)? = null

    private var glassBackdrop: ImageView? = null
    private var coverView: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var seekBar: SeekBar? = null
    private var positionView: TextView? = null
    private var durationView: TextView? = null
    private var playButton: ImageButton? = null
    private var flowLight: FlowLightView? = null

    private var dragging = false
    private var lastCoverKey: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            val window = popup ?: return
            if (!window.isShowing) return
            refresh()
            mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun toggle(anchor: View, provider: () -> MediaController?) {
        if (popup?.isShowing == true) {
            dismiss(animated = true)
            return
        }
        show(anchor, provider)
    }

    private fun show(anchor: View, provider: () -> MediaController?) {
        controllerProvider = provider
        lastCoverKey = null
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val card = buildCard(context, density)

        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = minOf(
            screenWidth - (SCREEN_MARGIN_DP * density).toInt() * 2,
            (CARD_WIDTH_DP * density).toInt(),
        )

        val height = (CARD_HEIGHT_DP * density).toInt()
        val window = PopupWindow(card, width, height).apply {
            isFocusable = false
            isTouchModal = false
            isOutsideTouchable = true
            isClippingEnabled = false
            elevation = 22f * density
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setTouchInterceptor { view, event ->
                val outside = event.action == MotionEvent.ACTION_DOWN &&
                    (event.x < 0f || event.x >= view.width ||
                        event.y < 0f || event.y >= view.height)
                if (outside || event.action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss(animated = true)
                    true
                } else {
                    false
                }
            }
        }
        popup = window

        refresh()
        runCatching {
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val x = ((screenWidth - width) / 2).coerceAtLeast(0)
            val y = loc[1] + anchor.height + (8 * density).toInt()
            window.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
        }.onFailure { e ->
            HookLogger.w(TAG, "玻璃卡弹出失败: ${e.message}")
            dismiss(animated = false)
            return
        }

        card.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170L).start()
        card.post { applyBackdropBlur(card) }
        mainHandler.postDelayed(ticker, UPDATE_INTERVAL_MS)
        HookLogger.i(TAG, "玻璃媒体卡已弹出 widthDp=$CARD_WIDTH_DP")
    }

    internal fun dismiss(animated: Boolean) {
        mainHandler.removeCallbacks(ticker)
        val window = popup ?: return
        popup = null
        flowLight?.stop()
        flowLight = null
        val view = window.contentView
        if (animated && view != null) {
            view.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(130L)
                .withEndAction { runCatching { window.dismiss() } }
                .start()
        } else {
            runCatching { window.dismiss() }
        }
        glassBackdrop = null
        coverView = null
        titleView = null
        artistView = null
        seekBar = null
        positionView = null
        durationView = null
        playButton = null
        controllerProvider = null
        dragging = false
    }

    // ---------------------------------------------------------------------
    // 视图
    // ---------------------------------------------------------------------

    private fun buildCard(context: android.content.Context, density: Float): View {
        val root = FrameLayout(context).apply {
            // 背景留给实时模糊 Drawable（attach 后设置），这里只负责圆角裁剪
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, CORNER_DP * density)
                }
            }
        }

        // 玻璃底：由窗口的 BackgroundBlurDrawable 实时模糊卡片背后的真实画面，
        // 这里先放一层占位壁纸（仅当系统模糊不可用时才显出来）
        val backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        glassBackdrop = backdrop
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

        // 极淡烟熏层：只压杂色，不能遮住背后的模糊画面
        root.addView(
            View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x2A15161C, 0x1F0C0D11),
                )
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )

        // 玻璃质感层：顶部白色反光 + 左上高光 + 底部内阴影 + 细噪点
        root.addView(GlassSurfaceView(context, density), FrameLayout.LayoutParams(MATCH, MATCH))

        // 卡内流光
        val flow = FlowLightView(context, density)
        flowLight = flow
        root.addView(flow, FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (16 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt(),
            )
        }

        // 头部
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = 11f * density
                setColor(0x2EFFFFFF)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 11f * density)
                }
            }
        }
        coverView = cover
        header.addView(cover, LinearLayout.LayoutParams((54 * density).toInt(), (54 * density).toInt()))

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((11 * density).toInt(), 0, (4 * density).toInt(), 0)
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(1f, 0f, 1f, 0x66000000)
        }
        val artist = TextView(context).apply {
            setTextColor(0xA8FFFFFF.toInt())
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (3 * density).toInt(), 0, 0)
        }
        titleView = title
        artistView = artist
        texts.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        texts.addView(artist, LinearLayout.LayoutParams(MATCH, WRAP))
        header.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        val close = TextView(context).apply {
            text = "×"
            setTextColor(0xBFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x24FFFFFF)
            }
            setOnClickListener { dismiss(animated = true) }
        }
        header.addView(close, LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()))
        content.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // 进度
        val bar = SeekBar(context).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x38FFFFFF)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    dragging = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    val ctl = controllerProvider?.invoke() ?: return
                    val duration = ctl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    if (duration <= 0L) return
                    val target = duration * (seekBar?.progress ?: 0) / 1000L
                    runCatching { ctl.transportControls?.seekTo(target) }
                }
            })
        }
        seekBar = bar
        content.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = (9 * density).toInt()
        })

        val timeRow = FrameLayout(context)
        val current = TextView(context).apply {
            setTextColor(0x8AFFFFFF.toInt())
            textSize = 9.5f
            text = "00:00"
        }
        val total = TextView(context).apply {
            setTextColor(0x8AFFFFFF.toInt())
            textSize = 9.5f
            text = "00:00"
        }
        positionView = current
        durationView = total
        timeRow.addView(current, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START))
        timeRow.addView(total, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END))
        content.addView(timeRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // 控制键
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        val prev = iconButton(context, android.R.drawable.ic_media_previous, density, 38) {
            runCatching { controllerProvider?.invoke()?.transportControls?.skipToPrevious() }
        }
        val play = iconButton(context, android.R.drawable.ic_media_pause, density, 48, primary = true) {
            val ctl = controllerProvider?.invoke() ?: return@iconButton
            val playing = ctl.playbackState?.state == PlaybackState.STATE_PLAYING
            runCatching {
                if (playing) ctl.transportControls?.pause() else ctl.transportControls?.play()
            }
        }
        playButton = play
        val next = iconButton(context, android.R.drawable.ic_media_next, density, 38) {
            runCatching { controllerProvider?.invoke()?.transportControls?.skipToNext() }
        }
        controls.addView(prev, LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt()))
        controls.addView(play, LinearLayout.LayoutParams((52 * density).toInt(), (52 * density).toInt()).apply {
            leftMargin = (36 * density).toInt()
            rightMargin = (36 * density).toInt()
        })
        controls.addView(next, LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt()))
        content.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))

        // 1px 亮边压在最上层，勾出玻璃厚度
        root.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = CORNER_DP * density
                    setColor(0x00000000)
                    setStroke(1, 0x4DFFFFFF)
                }
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        return root
    }

    /**
     * 真玻璃核心：取窗口的 BackgroundBlurDrawable，让系统对卡片背后的真实画面做高斯模糊，
     * 并给它设置圆角与极淡的着色。这样模糊的是桌面/应用本体，会随背景变化，
     * 而不是拿壁纸糊一层假底。
     */
    private fun applyBackdropBlur(card: View) {
        val density = card.resources.displayMetrics.density
        val drawable = runCatching {
            val viewRootImpl = findMethod(card.javaClass, "getViewRootImpl", 0)
                ?.invoke(card)
                ?: error("拿不到 ViewRootImpl")
            val method = findMethod(viewRootImpl.javaClass, "createBackgroundBlurDrawable", 0)
                ?: error("该窗口不支持背景模糊")
            method.invoke(viewRootImpl) as? android.graphics.drawable.Drawable
                ?: error("模糊 Drawable 为空")
        }.onFailure {
            HookLogger.w(TAG, "真背景模糊不可用: ${it.message}")
        }.getOrNull()

        if (drawable == null) {
            // 回退：老办法（壁纸近似），至少不让卡片变成透明片
            glassBackdrop?.let { iv ->
                iv.setImageBitmap(wallpaperBlur(card.context))
                iv.alpha = 1f
            }
            return
        }

        val radius = CORNER_DP * density
        val ok = runCatching {
            findMethod(drawable.javaClass, "setBlurRadius", 1)
                ?.invoke(drawable, (BLUR_RADIUS * density / 3f).toInt().coerceAtLeast(40))
            findMethod(drawable.javaClass, "setCornerRadius", 4)
                ?.invoke(drawable, radius, radius, radius, radius)
            findMethod(drawable.javaClass, "setColor", 1)
                ?.invoke(drawable, BLUR_TINT)
            card.background = drawable
            true
        }.onFailure { HookLogger.w(TAG, "背景模糊参数设置失败: ${it.message}") }.getOrDefault(false)

        if (!ok) {
            glassBackdrop?.let { iv ->
                iv.setImageBitmap(wallpaperBlur(card.context))
                iv.alpha = 1f
            }
        } else {
            glassBackdrop?.alpha = 0f
            HookLogger.i(TAG, "真玻璃背景模糊已启用 radius=$BLUR_RADIUS")
        }
    }

    /** 在类层级里按名字+参数个数找方法（系统隐藏 API 只能反射调） */
    private fun findMethod(start: Class<*>, name: String, paramCount: Int): java.lang.reflect.Method? {
        var cls: Class<*>? = start
        while (cls != null) {
            for (m in cls.declaredMethods) {
                if (m.name == name && m.parameterCount == paramCount) {
                    runCatching { m.isAccessible = true }
                    return m
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun iconButton(
        context: android.content.Context,
        resId: Int,
        density: Float,
        sizeDp: Int,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = if (primary) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x2EFFFFFF)
                setStroke(1, 0x26FFFFFF)
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x14FFFFFF)
            }
        }
        val pad = if (primary) 14 else 11
        setPadding(
            (pad * density).toInt(), (pad * density).toInt(),
            (pad * density).toInt(), (pad * density).toInt(),
        )
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------------
    // 数据
    // ---------------------------------------------------------------------

    private fun refresh() {
        val ctl = controllerProvider?.invoke()
        if (ctl == null) {
            titleView?.text = "没有正在播放的媒体"
            artistView?.text = ""
            positionView?.text = "00:00"
            durationView?.text = "00:00"
            seekBar?.progress = 0
            coverView?.setImageDrawable(null)
            return
        }
        val metadata = ctl.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "未知歌曲"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = ctl.playbackState
        val position = state?.position ?: 0L

        titleView?.text = title
        artistView?.text = artist
        durationView?.text = formatTime(duration)
        if (!dragging) {
            positionView?.text = formatTime(position)
            seekBar?.progress = if (duration > 0L) {
                ((position.coerceIn(0L, duration) * 1000L) / duration).toInt()
            } else {
                0
            }
        }
        playButton?.setImageResource(
            if (state?.state == PlaybackState.STATE_PLAYING) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            },
        )
        refreshCover(metadata, title, artist)
    }

    private fun refreshCover(metadata: MediaMetadata?, title: String, artist: String) {
        val key = "$title|$artist|${metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()}"
        if (key == lastCoverKey) return
        lastCoverKey = key
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: run {
                coverView?.setImageDrawable(null)
                return
            }
        coverView?.setImageBitmap(bitmap)
    }

    /** 取当前壁纸做缩小再放大的近似模糊，作为玻璃卡的环境底。 */
    private fun wallpaperBlur(context: android.content.Context): Bitmap? = runCatching {
        val wm = WallpaperManager.getInstance(context)
        val drawable = wm.drawable ?: wm.peekDrawable() ?: return null
        val w = 720
        val h = (w * 1.6f).toInt()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(raw)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        // 缩小再放大，得到柔和雾面；两次降采样让模糊更重，接近真实高斯
        val small = Bitmap.createScaledBitmap(raw, 20, 32, true)
        val mid = Bitmap.createScaledBitmap(small, 90, 144, true)
        val blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = Canvas(blurred)
        out.drawBitmap(mid, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        blurred
    }.getOrNull()

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val minutes = total / 60L
        val seconds = total % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** 玻璃表面：顶部反光、左上柔光、底部内阴影、细噪点，做出「玻璃」而不是「透明」。 */
    private class GlassSurfaceView(
        context: android.content.Context,
        private val density: Float,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val noise: Bitmap? = runCatching {
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val rnd = java.util.Random(7L)
            val px = IntArray(size * size)
            for (i in px.indices) {
                val a = 10 + rnd.nextInt(22)
                val v = rnd.nextInt(256)
                px[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
            }
            bmp.setPixels(px, 0, size, 0, 0, size, size)
            bmp
        }.getOrNull()
        private val noiseShader: Shader? = noise?.let {
            android.graphics.BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // 顶部白色反光带
            paint.shader = LinearGradient(
                0f, 0f, 0f, h * 0.55f,
                intArrayOf(0x33FFFFFF, 0x14FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            // 左上角柔光
            paint.shader = android.graphics.RadialGradient(
                w * 0.18f, h * 0.02f, w * 0.9f,
                intArrayOf(0x26FFFFFF, 0x0DFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            // 底部内阴影，托住卡片
            paint.shader = LinearGradient(
                0f, h * 0.55f, 0f, h,
                intArrayOf(0x00000000, 0x29000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            // 细噪点，避免大块渐变看起来像塑料
            val shader = noiseShader
            if (shader != null) {
                paint.shader = shader
                paint.alpha = 40
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.alpha = 255
            }
            paint.shader = null
        }
    }

    /** 斜向流光：一条半透明白光带缓慢扫过卡片。 */
    private class FlowLightView(
        context: android.content.Context,
        private val density: Float,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        init {
            animator.start()
        }

        fun stop() {
            runCatching { animator.cancel() }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val band = 74f * density
            val x = -band + (w + band * 2f) * phase
            paint.shader = LinearGradient(
                x, 0f, x + band, h,
                intArrayOf(0x00FFFFFF, 0x20FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    internal fun isShowing(): Boolean = popup?.isShowing == true
}



