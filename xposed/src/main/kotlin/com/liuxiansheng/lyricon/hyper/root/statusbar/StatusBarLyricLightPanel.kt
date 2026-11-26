/*
 * 本文件为 词岛 自研实现。
 * Copyright 2026 liuxiansheng
 *
 * 状态栏歌词「词岛卡片」控制面板（第三套样式）。
 *
 * 与前两套并列：经典卡片（StatusBarLyricControlPanel）、下拉媒体卡（StatusBarLyricMediaPanel）。
 * 由设置里「控制面板样式」切到第三档时启用，入口仍是原来的手势动作「控制面板」，
 * 点歌词即在歌词下方弹出，不改任何已有链路。
 *
 * 内容 = 歌曲信息 + 播放控制 + 四个高频设置（显示内容 / 字号 / 彩虹 / 避让超级岛）。
 *
 * 设置为什么不能直接写：面板跑在 SystemUI 进程，prefs 文件在词岛自己进程的私有目录。
 * 所以按钮发定向广播给词岛（StatusBarQuickSetReceiver），由它用 StatusBarPrefs 正规写入，
 * 写完 BridgeSender 再广播回来，SystemUI 侧 PrefHotReload 收到即重绘 —— 跟设置页里改一致。
 */

package com.liuxiansheng.lyricon.hyper.root.statusbar

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger

internal object StatusBarLyricLightPanel {

    private const val TAG = "LyricLightPanel"

    private const val UPDATE_INTERVAL_MS = 250L
    private const val PANEL_MAX_WIDTH_DP = 372
    private const val SCREEN_MARGIN_DP = 16
    private const val CORNER_DP = 26f

    private const val BLUE = 0xFF3D6BEF.toInt()
    private const val TEXT = 0xFF1A1B20.toInt()
    private const val SUB = 0xFF8A90A0.toInt()
    private const val LINE = 0xFFEEF1F8.toInt()
    private const val CHIP_BG = 0xFFF2F5FC.toInt()
    private const val CARD_BG = 0xFFF2F6FF.toInt()

    /** 词岛包名：广播钉住它，避免被别的应用收到 */
    private const val APP_PACKAGE = "com.liuxiansheng.chidao"
    private const val ACTION_QUICK_SET = "com.liuxiansheng.chidao.STATUS_BAR_QUICK_SET"

    private const val KEY_CONTENT_MODE = "key_hook_status_bar_lyric_content_mode"
    private const val KEY_TEXT_SIZE = "key_hook_status_bar_lyric_text_size"
    private const val KEY_RAINBOW = "key_hook_status_bar_lyric_rainbow_enabled"
    private const val KEY_COVER_COLOR = "key_hook_status_bar_lyric_cover_color_enabled"
    private const val KEY_TEXT_COLOR = "key_hook_status_bar_lyric_text_color"
    private const val KEY_FONT_FAMILY = "key_hook_status_bar_lyric_font_family"
    /** 主设置（全局歌词配色）的两个键：状态栏渲染里主色优先于状态栏自己的固定色 */
    private const val KEY_MASTER_COLOR_STYLE = "key_hook_text_color_style"
    private const val KEY_MASTER_COLOR = "key_hook_text_color"
    private const val MASTER_STYLE_COVER = 1
    private const val MASTER_STYLE_RAINBOW = 4
    private const val MASTER_STYLE_CUSTOM = 5

    private const val DEF_CONTENT_MODE = 2
    private const val DEF_TEXT_SIZE = 13
    private const val MIN_TEXT_SIZE = 8
    private const val MAX_TEXT_SIZE = 20

    /** 四种配色：彩虹优先于封面取色，两者都关时用固定颜色 */
    private const val COLOR_MODE_RAINBOW = 0
    private const val COLOR_MODE_COVER = 1
    private const val COLOR_MODE_WHITE = 2
    private const val COLOR_MODE_BLUE = 3
    private const val FIXED_WHITE = 0xFFFFFFFF.toInt()
    private const val FIXED_BLUE = 0xFF7EA6FF.toInt()

    /** 面板里只放常用的四档字体：系统 / 中等 / 衬线 / 黑体 */
    private val FONT_STEPS = listOf(0 to "系统", 1 to "中等", 3 to "衬线", 6 to "黑体")

    /** 字号和字体连点时节流，避免每点一下就跨进程写一次 prefs */
    private const val WRITE_DEBOUNCE_MS = 320L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var popup: PopupWindow? = null
    private var controllerProvider: (() -> MediaController?)? = null
    private var prefIntReader: ((String, Int) -> Int)? = null
    private var prefBoolReader: ((String, Boolean) -> Boolean)? = null

    private var coverView: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var seekBar: SeekBar? = null
    private var positionView: TextView? = null
    private var durationView: TextView? = null
    private var playButton: ImageButton? = null

    // 快速设置区
    private var contentChips: Map<Int, TextView> = emptyMap()
    private var colorChips: Map<Int, TextView> = emptyMap()
    private var sizeLabel: TextView? = null
    private var fontChip: TextView? = null

    private var contentMode = DEF_CONTENT_MODE
    private var textSize = DEF_TEXT_SIZE
    private var rainbowOn = false
    private var coverColorOn = false
    private var textColor = FIXED_WHITE
    private var fontFamily = 0
    private var colorMode = COLOR_MODE_RAINBOW

    private val pendingIntWrites = mutableMapOf<String, Int>()

    private val flushWrites = Runnable {
        val context = appContext
        if (context != null) {
            pendingIntWrites.forEach { (key, value) -> pushInt(context, key, value) }
        }
        pendingIntWrites.clear()
    }

    private var appContext: Context? = null

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

    fun toggle(
        anchor: View,
        provider: () -> MediaController?,
        prefInt: (String, Int) -> Int,
        prefBool: (String, Boolean) -> Boolean,
        masterInt: (String, Int) -> Int,
    ) {
        if (popup?.isShowing == true) {
            dismiss(animated = true)
            return
        }
        show(anchor, provider, prefInt, prefBool, masterInt)
    }

    private fun show(
        anchor: View,
        provider: () -> MediaController?,
        prefInt: (String, Int) -> Int,
        prefBool: (String, Boolean) -> Boolean,
        masterInt: (String, Int) -> Int,
    ) {
        controllerProvider = provider
        prefIntReader = prefInt
        prefBoolReader = prefBool
        lastCoverKey = null

        // 初值：直接问 SystemUI 侧能读到的 prefs（remote prefs 只读，够显示状态）
        contentMode = runCatching { prefInt(KEY_CONTENT_MODE, DEF_CONTENT_MODE) }.getOrDefault(DEF_CONTENT_MODE)
        textSize = runCatching { prefInt(KEY_TEXT_SIZE, DEF_TEXT_SIZE) }.getOrDefault(DEF_TEXT_SIZE)
        rainbowOn = runCatching { prefBool(KEY_RAINBOW, true) }.getOrDefault(true)
        coverColorOn = runCatching { prefBool(KEY_COVER_COLOR, false) }.getOrDefault(false)
        textColor = runCatching { prefInt(KEY_TEXT_COLOR, FIXED_WHITE) }.getOrDefault(FIXED_WHITE)
        fontFamily = runCatching { prefInt(KEY_FONT_FAMILY, 0) }.getOrDefault(0)
        // 配色以主设置的颜色样式为准（状态栏渲染也是这套语义），读不到才退回状态栏自己的开关
        val masterStyle = runCatching { masterInt(KEY_MASTER_COLOR_STYLE, -1) }.getOrDefault(-1)
        val masterColor = runCatching { masterInt(KEY_MASTER_COLOR, FIXED_WHITE) }.getOrDefault(FIXED_WHITE)
        colorMode = when (masterStyle) {
            MASTER_STYLE_RAINBOW -> COLOR_MODE_RAINBOW
            MASTER_STYLE_COVER -> COLOR_MODE_COVER
            MASTER_STYLE_CUSTOM ->
                if (masterColor == FIXED_BLUE) COLOR_MODE_BLUE else COLOR_MODE_WHITE

            else -> when {
                rainbowOn -> COLOR_MODE_RAINBOW
                coverColorOn -> COLOR_MODE_COVER
                textColor == FIXED_BLUE -> COLOR_MODE_BLUE
                else -> COLOR_MODE_WHITE
            }
        }
        if (masterStyle == MASTER_STYLE_CUSTOM) textColor = masterColor
        rainbowOn = colorMode == COLOR_MODE_RAINBOW
        coverColorOn = colorMode == COLOR_MODE_COVER

        val context = anchor.context
        appContext = context.applicationContext
        val density = context.resources.displayMetrics.density
        val panel = buildPanel(context, density)

        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = minOf(
            screenWidth - (SCREEN_MARGIN_DP * density).toInt() * 2,
            (PANEL_MAX_WIDTH_DP * density).toInt(),
        )

        // 显式量一遍高度再写死：WRAP_CONTENT 交给 WindowManager 时，SystemUI 上窗口高度
        // 会被算成上半截（面板下半部分只是"画"在窗口外），表现就是"字号的 +/- 和开关点不动，
        // 手指反而点到桌面上"。量出真实高度，窗口触摸区和内容对齐。
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val panelHeight = panel.measuredHeight.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT

        val window = PopupWindow(panel, width, panelHeight).apply {
            isFocusable = false
            isTouchModal = false
            isOutsideTouchable = true
            isClippingEnabled = false
            elevation = 22f * density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
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
            HookLogger.w(TAG, "浅色面板弹出失败: ${e.message}")
            dismiss(animated = false)
            return
        }

        panel.alpha = 0f
        panel.translationY = -12f * density
        panel.animate().alpha(1f).translationY(0f).setDuration(170L).start()

        mainHandler.postDelayed(ticker, UPDATE_INTERVAL_MS)
        HookLogger.i(TAG, "浅色控制面板已弹出，内容=$contentMode 字号=${textSize}sp 字体=$fontFamily 配色=$colorMode")
    }

    internal fun dismiss(animated: Boolean) {
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(flushWrites)
        // 面板关掉前把还没下发的字号 / 字体补写一次，避免连点后最后一档丢失
        flushWrites.run()
        pendingIntWrites.clear()
        val window = popup ?: return
        popup = null
        val view = window.contentView
        if (animated && view != null) {
            view.animate()
                .alpha(0f)
                .translationY(-8f * view.resources.displayMetrics.density)
                .setDuration(130L)
                .withEndAction { runCatching { window.dismiss() } }
                .start()
        } else {
            runCatching { window.dismiss() }
        }
        coverView = null
        titleView = null
        artistView = null
        seekBar = null
        positionView = null
        durationView = null
        playButton = null
        contentChips = emptyMap()
        colorChips = emptyMap()
        sizeLabel = null
        fontChip = null
        controllerProvider = null
        prefIntReader = null
        prefBoolReader = null
        appContext = null
        dragging = false
    }

    internal fun isShowing(): Boolean = popup?.isShowing == true

    // ---------------------------------------------------------------------
    // 视图
    // ---------------------------------------------------------------------

    private fun buildPanel(context: Context, density: Float): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt(),
            )
            background = GradientDrawable().apply {
                cornerRadius = CORNER_DP * density
                setColor(Color.WHITE)
                setStroke(1, 0xFFE4EBF9.toInt())
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, CORNER_DP * density)
                }
            }
        }

        // 抓手
        root.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 2f * density
                    setColor(0xFFDDE3F0.toInt())
                }
            },
            LinearLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (2 * density).toInt()
            },
        )

        // 标题行
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        titleRow.addView(
            TextView(context).apply {
                text = "状态栏歌词"
                setTextColor(TEXT)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        val close = TextView(context).apply {
            text = "×"
            setTextColor(SUB)
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CHIP_BG)
            }
            setOnClickListener { dismiss(animated = true) }
        }
        titleRow.addView(close, LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt()))
        root.addView(titleRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // ===== 歌曲区 =====
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (12 * density).toInt(), (12 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt(),
            )
            background = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(CARD_BG)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = 13f * density
                setColor(0xFF2B3140.toInt())
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 13f * density)
                }
            }
        }
        coverView = cover
        header.addView(cover, LinearLayout.LayoutParams((52 * density).toInt(), (52 * density).toInt()))

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((11 * density).toInt(), 0, 0, 0)
        }
        val title = TextView(context).apply {
            setTextColor(TEXT)
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        }
        val artist = TextView(context).apply {
            setTextColor(SUB)
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (3 * density).toInt(), 0, 0)
        }
        titleView = title
        artistView = artist
        texts.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        texts.addView(artist, LinearLayout.LayoutParams(MATCH, WRAP))
        header.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // 进度
        val bar = SeekBar(context).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(BLUE)
            thumbTintList = ColorStateList.valueOf(BLUE)
            progressBackgroundTintList = ColorStateList.valueOf(0xFFD5E0F6.toInt())
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
        card.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (8 * density).toInt() })

        val timeRow = FrameLayout(context)
        val current = TextView(context).apply {
            setTextColor(SUB)
            textSize = 10.5f
            text = "00:00"
        }
        val total = TextView(context).apply {
            setTextColor(SUB)
            textSize = 10.5f
            text = "00:00"
        }
        positionView = current
        durationView = total
        timeRow.addView(current, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START))
        timeRow.addView(total, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END))
        card.addView(timeRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // 控制键
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val prev = roundIconButton(context, android.R.drawable.ic_media_previous, density, 0xFFF0F4FE.toInt(), 0xFF2B3140.toInt()) {
            runCatching { controllerProvider?.invoke()?.transportControls?.skipToPrevious() }
        }
        val play = roundIconButton(context, android.R.drawable.ic_media_pause, density, BLUE, Color.WHITE) {
            val ctl = controllerProvider?.invoke() ?: return@roundIconButton
            val playing = ctl.playbackState?.state == PlaybackState.STATE_PLAYING
            runCatching {
                if (playing) ctl.transportControls?.pause() else ctl.transportControls?.play()
            }
        }
        playButton = play
        val next = roundIconButton(context, android.R.drawable.ic_media_next, density, 0xFFF0F4FE.toInt(), 0xFF2B3140.toInt()) {
            runCatching { controllerProvider?.invoke()?.transportControls?.skipToNext() }
        }
        controls.addView(prev, LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()))
        controls.addView(
            play,
            LinearLayout.LayoutParams((54 * density).toInt(), (54 * density).toInt()).apply {
                leftMargin = (30 * density).toInt()
                rightMargin = (30 * density).toInt()
            },
        )
        controls.addView(next, LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()))
        card.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (8 * density).toInt() })

        // ===== 分隔线 + 快速设置 =====
        root.addView(
            View(context).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(MATCH, (1 * density).toInt().coerceAtLeast(1)).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (10 * density).toInt()
            },
        )

        root.addView(
            TextView(context).apply {
                text = "快速设置"
                setTextColor(TEXT)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

        // 显示内容：三档
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val chips = LinkedHashMap<Int, TextView>()
        listOf(2 to "歌词", 1 to "音乐信息", 0 to "不显示").forEach { (value, label) ->
            val chip = chipView(context, density, label, value == contentMode) {
                contentMode = value
                pushInt(context, KEY_CONTENT_MODE, value)
                applyChipState(contentChips, value)
            }
            chips[value] = chip
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(0, (38 * density).toInt(), 1f).apply {
                    if (chips.isNotEmpty()) leftMargin = (8 * density).toInt()
                },
            )
        }
        contentChips = chips
        root.addView(chipRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // 字号：− / 值 / +
        val sizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        sizeRow.addView(
            TextView(context).apply {
                text = "歌词字号"
                setTextColor(TEXT)
                textSize = 13.5f
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        val minus = stepButton(context, density, "−") {
            if (textSize > MIN_TEXT_SIZE) {
                textSize -= 1
                sizeLabel?.text = sizeText()
                queueInt(context, KEY_TEXT_SIZE, textSize)
            }
        }
        val label = TextView(context).apply {
            text = sizeText()
            setTextColor(BLUE)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        sizeLabel = label
        val plus = stepButton(context, density, "＋") {
            if (textSize < MAX_TEXT_SIZE) {
                textSize += 1
                sizeLabel?.text = sizeText()
                queueInt(context, KEY_TEXT_SIZE, textSize)
            }
        }
        sizeRow.addView(minus, LinearLayout.LayoutParams((30 * density).toInt(), (30 * density).toInt()))
        sizeRow.addView(label, LinearLayout.LayoutParams((52 * density).toInt(), (30 * density).toInt()))
        sizeRow.addView(plus, LinearLayout.LayoutParams((30 * density).toInt(), (30 * density).toInt()))

        // 字体：点一下切下一档，四档循环（系统 / 中等 / 衬线 / 黑体）
        val font = chipView(context, density, currentFontLabel(), selected = false) {
            val index = FONT_STEPS.indexOfFirst { it.first == fontFamily }.let { if (it < 0) 0 else it }
            val next = FONT_STEPS[(index + 1) % FONT_STEPS.size]
            fontFamily = next.first
            fontChip?.text = next.second
            queueInt(context, KEY_FONT_FAMILY, fontFamily)
        }
        fontChip = font
        sizeRow.addView(
            font,
            LinearLayout.LayoutParams((60 * density).toInt(), (30 * density).toInt()).apply {
                leftMargin = (10 * density).toInt()
            },
        )
        root.addView(sizeRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // 歌词配色：彩虹 / 封面取色 / 白 / 蓝（替换原「避让超级岛」开关）
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        colorRow.addView(
            TextView(context).apply {
                text = "歌词配色"
                setTextColor(TEXT)
                textSize = 13.5f
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        val colorChipsMap = LinkedHashMap<Int, TextView>()
        listOf(
            COLOR_MODE_RAINBOW to "彩虹",
            COLOR_MODE_COVER to "封面",
            COLOR_MODE_WHITE to "白",
            COLOR_MODE_BLUE to "蓝",
        ).forEach { (mode, name) ->
            val chip = chipView(context, density, name, mode == colorMode) {
                applyColorMode(context, mode)
            }
            colorChipsMap[mode] = chip
            colorRow.addView(
                chip,
                LinearLayout.LayoutParams((46 * density).toInt(), (30 * density).toInt()).apply {
                    leftMargin = (6 * density).toInt()
                },
            )
        }
        colorChips = colorChipsMap
        root.addView(colorRow, LinearLayout.LayoutParams(MATCH, WRAP))

        return root
    }

    private fun currentFontLabel(): String =
        FONT_STEPS.firstOrNull { it.first == fontFamily }?.second ?: FONT_STEPS[0].second

    /** 切配色：主设置的颜色样式 + 状态栏自己的开关一起写，避免主设置把状态栏固定色压掉 */
    private fun applyColorMode(context: Context, mode: Int) {
        colorMode = mode
        applyChipState(colorChips, mode)
        when (mode) {
            COLOR_MODE_RAINBOW -> {
                rainbowOn = true
                coverColorOn = false
                pushInt(context, KEY_MASTER_COLOR_STYLE, MASTER_STYLE_RAINBOW)
                pushBool(context, KEY_RAINBOW, true)
                pushBool(context, KEY_COVER_COLOR, false)
            }

            COLOR_MODE_COVER -> {
                rainbowOn = false
                coverColorOn = true
                pushInt(context, KEY_MASTER_COLOR_STYLE, MASTER_STYLE_COVER)
                pushBool(context, KEY_RAINBOW, false)
                pushBool(context, KEY_COVER_COLOR, true)
            }

            else -> {
                rainbowOn = false
                coverColorOn = false
                textColor = if (mode == COLOR_MODE_BLUE) FIXED_BLUE else FIXED_WHITE
                // 主设置切到「自定义色」并写入同一个颜色：状态栏渲染优先取主设置的自定义色，
                // 只改状态栏那份 key 会被主设置覆盖（白/蓝点了像没反应就是这个原因）。
                pushInt(context, KEY_MASTER_COLOR_STYLE, MASTER_STYLE_CUSTOM)
                pushInt(context, KEY_MASTER_COLOR, textColor)
                pushBool(context, KEY_RAINBOW, false)
                pushBool(context, KEY_COVER_COLOR, false)
                pushInt(context, KEY_TEXT_COLOR, textColor)
            }
        }
    }

    private fun sizeText(): String = "$textSize sp"

    private fun chipView(
        context: Context,
        density: Float,
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        applyChipLook(this, selected)
    }

    private fun applyChipLook(view: TextView, selected: Boolean) {
        val density = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(if (selected) 0xFFE4ECFF.toInt() else CHIP_BG)
        }
        view.setTextColor(if (selected) BLUE else SUB)
        view.setTypeface(view.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun applyChipState(chips: Map<Int, TextView>, selected: Int) {
        chips.forEach { (value, view) -> applyChipLook(view, value == selected) }
    }

    private fun stepButton(
        context: Context,
        density: Float,
        label: String,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        background = GradientDrawable().apply {
            cornerRadius = 15f * density
            setColor(CHIP_BG)
        }
        setOnClickListener { onClick() }
    }

    private fun roundIconButton(
        context: Context,
        resId: Int,
        density: Float,
        bgColor: Int,
        iconColor: Int,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(iconColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
        }
        val pad = 11
        setPadding(
            (pad * density).toInt(), (pad * density).toInt(),
            (pad * density).toInt(), (pad * density).toInt(),
        )
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------------
    // 快速设置下发
    // ---------------------------------------------------------------------

    /** 字号 / 字体这类连点项先记下来，停手 320ms 后再写一次，避免连点连写 prefs */
    private fun queueInt(context: Context, key: String, value: Int) {
        appContext = context.applicationContext
        pendingIntWrites[key] = value
        mainHandler.removeCallbacks(flushWrites)
        mainHandler.postDelayed(flushWrites, WRITE_DEBOUNCE_MS)
    }

    private fun pushInt(context: Context, key: String, value: Int) {
        val intent = Intent(ACTION_QUICK_SET).setPackage(APP_PACKAGE)
        intent.putExtra("key", key)
        intent.putExtra("type", "int")
        intent.putExtra("int", value)
        runCatching { context.sendBroadcast(intent) }
            .onFailure { HookLogger.w(TAG, "下发 $key=$value 失败: ${it.message}") }
    }

    private fun pushBool(context: Context, key: String, value: Boolean) {
        val intent = Intent(ACTION_QUICK_SET).setPackage(APP_PACKAGE)
        intent.putExtra("key", key)
        intent.putExtra("type", "bool")
        intent.putExtra("bool", value)
        runCatching { context.sendBroadcast(intent) }
            .onFailure { HookLogger.w(TAG, "下发 $key=$value 失败: ${it.message}") }
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
        refreshArtwork(metadata, title, artist)
    }

    private fun refreshArtwork(metadata: MediaMetadata?, title: String, artist: String) {
        val key = "$title|$artist|${metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()}"
        if (key == lastCoverKey) return
        lastCoverKey = key
        val bitmap: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
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

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
