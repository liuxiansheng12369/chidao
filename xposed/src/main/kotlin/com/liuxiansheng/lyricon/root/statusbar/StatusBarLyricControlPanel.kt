/*
 * 本文件为 Lyricon V3 自研实现。
 * Copyright 2026 liuxiansheng
 *
 * 状态栏歌词控制面板：点歌词弹出的小卡片，提供封面 / 歌名 / 歌手 / 进度 / 上一首 /
 * 播放暂停 / 下一首。运行在 SystemUI 进程内，视图全部代码构建，不依赖 App 资源；
 * 数据直接读当前 MediaSession，操作走 transportControls。
 *
 * 与上游词幕的 LyricControlPopup 思路同源（都挂在状态栏歌词上），但为词岛重写：
 * 不带 AI 解读、不依赖上游的 PlaybackControl / NotificationCoverHelper，
 * 只保留播放控制必需的交互。
 */

package com.liuxiansheng.lyricon.hyper.root.statusbar

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.session.MediaController
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger

internal object StatusBarLyricControlPanel {

    private const val TAG = "LyricControlPanel"

    /** 进度刷新间隔：与状态栏歌词的位置推送节奏接近，够顺滑又不费电。 */
    private const val UPDATE_INTERVAL_MS = 250L

    private const val PANEL_MAX_WIDTH_DP = 380
    private const val SCREEN_MARGIN_DP = 16
    private const val CORNER_DP = 22

    private val mainHandler = Handler(Looper.getMainLooper())

    private var popup: PopupWindow? = null
    private var anchorView: View? = null
    private var controllerProvider: (() -> MediaController?)? = null

    private var coverView: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var seekBar: SeekBar? = null
    private var positionView: TextView? = null
    private var durationView: TextView? = null
    private var playButton: ImageButton? = null

    /** 用户正在拖进度条：暂停自动刷新，避免手指和定时器打架。 */
    private var dragging = false

    /** 上一次显示的封面标识，切歌/换封面时才重新解码。 */
    private var lastCoverKey: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            val window = popup ?: return
            if (!window.isShowing) return
            refresh()
            mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    /** 点歌词时切换：已显示就收起，否则在歌词下方弹出。 */
    fun toggle(anchor: View, provider: () -> MediaController?) {
        if (popup?.isShowing == true) {
            dismiss()
            return
        }
        show(anchor, provider)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(anchor: View, provider: () -> MediaController?) {
        anchorView = anchor
        controllerProvider = provider
        lastCoverKey = null

        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val panel = buildPanel(context, density)

        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = minOf(
            screenWidth - (SCREEN_MARGIN_DP * density).toInt() * 2,
            (PANEL_MAX_WIDTH_DP * density).toInt(),
        )

        val window = PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isFocusable = false
            isTouchModal = false
            isOutsideTouchable = true
            isClippingEnabled = false
            elevation = 24f * density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setTouchInterceptor { view, event ->
                val outside = event.action == MotionEvent.ACTION_DOWN &&
                    (event.x < 0f || event.x >= view.width ||
                        event.y < 0f || event.y >= view.height)
                if (outside || event.action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
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
            HookLogger.w(TAG, "控制面板弹出失败: ${e.message}")
            dismiss()
            return
        }
        mainHandler.postDelayed(ticker, UPDATE_INTERVAL_MS)
        HookLogger.i(TAG, "控制面板已弹出")
    }

    fun dismiss() {
        mainHandler.removeCallbacks(ticker)
        val window = popup ?: return
        popup = null
        runCatching { window.dismiss() }
        coverView = null
        titleView = null
        artistView = null
        seekBar = null
        positionView = null
        durationView = null
        playButton = null
        anchorView = null
        controllerProvider = null
        dragging = false
    }

    // ---------------------------------------------------------------------
    // 视图构建
    // ---------------------------------------------------------------------

    private fun buildPanel(context: android.content.Context, density: Float): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (18 * density).toInt(),
                (16 * density).toInt(),
                (18 * density).toInt(),
                (14 * density).toInt(),
            )
            background = roundedBackground(
                fill = 0xF21A1A1C.toInt(),
                stroke = 0x33FFFFFF,
                radiusDp = CORNER_DP * density,
            )
            // 半透明底 + 阴影，贴近系统媒体卡片的观感
            elevation = 18f * density
        }

        // ---- 头部：封面 + 歌名/歌手 ----
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(0x33FFFFFF, 0x22FFFFFF, 22f * density)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 10f * density)
                }
            }
        }
        coverView = cover
        header.addView(
            cover,
            LinearLayout.LayoutParams((46 * density).toInt(), (46 * density).toInt()),
        )

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val artist = TextView(context).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 11.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (3 * density).toInt(), 0, 0)
        }
        titleView = title
        artistView = artist
        texts.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        texts.addView(artist, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // ---- 进度条 ----
        val bar = SeekBar(context).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(0x2EFFFFFF)
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
                    val target = (duration * (seekBar?.progress ?: 0) / 1000L)
                    runCatching { ctl.transportControls?.seekTo(target) }
                    HookLogger.d(TAG, "控制面板跳转 -> ${target}ms")
                }
            })
        }
        seekBar = bar
        panel.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * density).toInt()
            },
        )

        // ---- 时间：左当前 / 右总长 ----
        val timeRow = FrameLayout(context)
        val current = TextView(context).apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 10f
            text = "00:00"
        }
        val total = TextView(context).apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 10f
            text = "00:00"
        }
        positionView = current
        durationView = total
        timeRow.addView(
            current,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START,
            ),
        )
        timeRow.addView(
            total,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END,
            ),
        )
        panel.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- 按钮：上一首 / 播放暂停 / 下一首 ----
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        val prev = iconButton(context, android.R.drawable.ic_media_previous, density) {
            runCatching { controllerProvider?.invoke()?.transportControls?.skipToPrevious() }
            HookLogger.d(TAG, "控制面板：上一首")
        }
        val play = iconButton(context, android.R.drawable.ic_media_pause, density) {
            val ctl = controllerProvider?.invoke() ?: return@iconButton
            val playing = ctl.playbackState?.state == PlaybackState.STATE_PLAYING
            runCatching {
                if (playing) ctl.transportControls?.pause() else ctl.transportControls?.play()
            }
            HookLogger.d(TAG, "控制面板：${if (playing) "暂停" else "播放"}")
        }
        playButton = play
        val next = iconButton(context, android.R.drawable.ic_media_next, density) {
            runCatching { controllerProvider?.invoke()?.transportControls?.skipToNext() }
            HookLogger.d(TAG, "控制面板：下一首")
        }
        controls.addView(prev, buttonParams(density))
        controls.addView(play, buttonParams(density).apply { leftMargin = (28 * density).toInt(); rightMargin = (28 * density).toInt() })
        controls.addView(next, buttonParams(density))
        panel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return panel
    }

    private fun iconButton(
        context: android.content.Context,
        resId: Int,
        density: Float,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(resId)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        background = roundedBackground(0x1FFFFFFF, 0, 18f * density)
        setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
        setOnClickListener { onClick() }
    }

    private fun buttonParams(density: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams((46 * density).toInt(), (46 * density).toInt())

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp
            setColor(fill)
            if (stroke != 0) setStroke(1, stroke)
        }

    // ---------------------------------------------------------------------
    // 数据刷新
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
        coverView?.setImageBitmap(bitmap)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val minutes = total / 60L
        val seconds = total % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    // 供调试/后续扩展：面板当前是否在显示
    internal fun isShowing(): Boolean = popup?.isShowing == true
}



