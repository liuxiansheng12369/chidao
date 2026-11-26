/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 *
 * HomeThemes：首页四套主题的独立布局。
 *   黑胶唱片（原 vinyl）  → 唱片机：大黑胶 + 唱臂 + 播放控制
 *   晨雾留白（原 mist）   → 晨雾概览：白纸留白 + 今日听歌数据
 *   赛博霓虹（原 cyber）  → 深空仪表：16 色频谱 + 今日时长环 + 近 7 天 + 最近播放
 *   歌词杂志（新 lyric）  → 整页歌词 + 玻璃面板
 * 这四套是"布局级"改动；默认（pref_home_skin=0）仍是原来的经典区块，不受影响。
 */

package com.liuxiansheng.lyricon.app.activity

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.liuxiansheng.lyricon.app.coui.CouiUiState
import com.liuxiansheng.lyricon.app.coui.couiCard
import com.liuxiansheng.lyricon.app.coui.couiEdgeGlow
import com.liuxiansheng.lyricon.axis.ui.BoardSwitch
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuxiansheng.lyricon.app.live.DailyStat
import com.liuxiansheng.lyricon.app.live.ListenRecord
import com.liuxiansheng.lyricon.app.live.ListenRecordsStore
import com.liuxiansheng.lyricon.app.live.LiveStateProvider
import com.liuxiansheng.lyricon.app.live.NowPlaying
import com.liuxiansheng.lyricon.app.live.NowPlayingReader
import com.liuxiansheng.lyricon.app.live.RecordTotals
import com.liuxiansheng.lyricon.app.util.MasterPrefs
import com.liuxiansheng.lyricon.app.util.StatusBarPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ===== 数据 =====

internal data class HomeLive(
    val now: NowPlaying,
    val records: List<ListenRecord>,
    val daily: List<DailyStat> = emptyList(),
    val totals: RecordTotals = RecordTotals.EMPTY,
)

/** 首页实时数据：播放状态 600ms 一刷，听歌记录 2.4s 一刷（够用且不费电）。 */
@Composable
internal fun rememberHomeLive(): HomeLive {
    val context = LocalContext.current
    var now by remember { mutableStateOf(NowPlayingReader.read(context)) }
    var records by remember { mutableStateOf<List<ListenRecord>>(emptyList()) }
    var daily by remember { mutableStateOf<List<DailyStat>>(emptyList()) }
    var totals by remember { mutableStateOf(RecordTotals.EMPTY) }
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            now = NowPlayingReader.read(context)
            if (tick % 4 == 0) {
                val loaded = withContext(Dispatchers.IO) {
                    Triple(
                        ListenRecordsStore.load(context),
                        ListenRecordsStore.loadDaily(context),
                        ListenRecordsStore.loadTotals(context),
                    )
                }
                records = loaded.first
                daily = loaded.second
                totals = loaded.third
            }
            tick++
            delay(600)
        }
    }
    return HomeLive(now, records, daily, totals)
}

private fun dayStartMs(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun clockLabel(ms: Long, pattern: String = "HH:mm"): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))

private fun minuteLabel(ms: Long): String {
    if (ms <= 0) return "0"
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h${(m % 60).toString().padStart(2, '0')}" else "$m"
}

private fun durationShort(ms: Long): String {
    if (ms <= 0) return "—"
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟"
}

// ===== 动作 =====

/** 播放控制走 su + input keyevent（和"重启状态栏"同一条通道，不需要额外权限）。 */
private fun sendMediaKey(scope: kotlinx.coroutines.CoroutineScope, keyCode: Int) {
    scope.launch {
        withContext(Dispatchers.IO) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent $keyCode")).waitFor()
            }
        }
    }
}

private const val KEY_PREV = 88
private const val KEY_PLAY_PAUSE = 85
private const val KEY_NEXT = 87

// ===== 入口 =====

@Composable
internal fun ThemedHome(
    skin: HomeSkin,
    skinId: Int,
    moduleActive: Boolean,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    val context = LocalContext.current
    val live = rememberHomeLive()
    when (skin.layout) {
        HomeSkins.LAYOUT_DECK -> DeckHome(skin, skinId, live, moduleActive, context, onOpenAxis, onOpenSuperIsland, onOpenStatusBarLyric, onOpenLyricSource, onOpenStore, onOpenAdvanced, onOpenRecords)
        HomeSkins.LAYOUT_MIST -> MistHome(skin, skinId, live, moduleActive, context, onOpenAxis, onOpenSuperIsland, onOpenStatusBarLyric, onOpenLyricSource, onOpenStore, onOpenAdvanced, onOpenRecords)
        HomeSkins.LAYOUT_DASH -> DashHome(skin, skinId, live, moduleActive, context, onOpenAxis, onOpenSuperIsland, onOpenStatusBarLyric, onOpenLyricSource, onOpenStore, onOpenAdvanced, onOpenRecords)
        HomeSkins.LAYOUT_BOARD -> if (CouiUiState.enabled.value) {
            BoardCouiHome(skin, skinId, live, moduleActive, context, onOpenAxis, onOpenSuperIsland, onOpenStatusBarLyric, onOpenLyricSource, onOpenStore, onOpenAdvanced, onOpenRecords)
        } else {
            BoardHome(skin, skinId, live, moduleActive, context, onOpenAxis, onOpenSuperIsland, onOpenStatusBarLyric, onOpenLyricSource, onOpenStore, onOpenAdvanced, onOpenRecords)
        }
        HomeSkins.LAYOUT_ART -> ArtHome(skin, skinId, live, moduleActive, context, onOpenAxis, onOpenSuperIsland, onOpenStatusBarLyric, onOpenLyricSource, onOpenStore, onOpenAdvanced, onOpenRecords, onOpenAbout)
        else -> LyricHome(skin, skinId, live, moduleActive, context, onOpenAxis, onOpenSuperIsland, onOpenStatusBarLyric, onOpenLyricSource, onOpenStore, onOpenAdvanced, onOpenRecords)
    }
}

// ===== 公共小件 =====

@Composable
private fun RebootChip(skin: HomeSkin, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    // 没 root 时说明弹窗。原来失败了什么都不显示，点击像没反应。
    var showNoRoot by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(skin.cardBg, RoundedCornerShape(14.dp))
            .border(1.dp, skin.cardBorder, RoundedCornerShape(14.dp))
            .clickable {
                SystemUiRestart.request(context, scope, onDenied = { showNoRoot = true })
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("↻", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = skin.textPrimary)
    }
    NoRootDialog(visible = showNoRoot, context = context, onDismiss = { showNoRoot = false })
}

/**
 * 封面位：有当前这首的封面就铺图，没有就退回原来的渐变底 + 一个字。
 *
 * 图是 SystemUI 侧抓下来、App 落到私有目录的那一张（160px），缩到几十 dp 足够清楚。
 */
@Composable
internal fun CoverTile(
    now: NowPlaying,
    size: Dp,
    shape: androidx.compose.ui.graphics.Shape,
    fallback: String,
    fontSize: TextUnit,
    skin: HomeSkin?,
    fallbackColor: Color? = null,
) {
    val context = LocalContext.current
    val tag = now.coverTag
    val accent = skin?.accent ?: Color(0xFF2C77F2)
    val bitmap = remember(tag) {
        NowPlayingReader.coverFile(context, now)?.let { file ->
            runCatching {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.82f), accent.copy(alpha = 0.28f))
                ),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (fallback.isNotBlank()) {
            Text(
                fallback,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                color = fallbackColor ?: skin?.textPrimary ?: Color.White,
            )
        }
    }
}

/** 胶囊岛（真实播放状态）：歌名/歌词 + 频谱律动 */
@Composable
private fun IslandCapsule(
    skin: HomeSkin,
    now: NowPlaying,
    barCount: Int = 6,
    onClick: (() -> Unit)? = null,
) {
    val transition = rememberInfiniteTransition(label = "capsule")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val borderMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(skin.islandBg, RoundedCornerShape(28.dp))
            .border(1.dp, skin.accent.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
            .then(borderMod)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverTile(
            now = now,
            size = 38.dp,
            shape = RoundedCornerShape(12.dp),
            fallback = "岛",
            fontSize = 13.sp,
            skin = skin,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                NowPlayingReader.mainLine(now),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = skin.islandLyric,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                NowPlayingReader.subLine(now),
                fontSize = 11.sp,
                color = skin.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 右侧小律动条：固定尺寸自绘，避免每帧改布局导致整卡高度跳动
        Canvas(
            modifier = Modifier
                .width(((barCount * 3) + ((barCount - 1) * 2)).dp)
                .height(24.dp)
        ) {
            val bw = 3.dp.toPx()
            val gp = 2.dp.toPx()
            repeat(barCount) { i ->
                val factor = when (i) {
                    0 -> 0.55f
                    2 -> 0.95f
                    4 -> 0.7f
                    else -> 0.85f
                }
                val h = if (now.playing) (7f + 16f * factor * pulse).dp.toPx() else 7.dp.toPx()
                val y = size.height - h
                drawRoundRect(
                    color = skin.accent.copy(alpha = if (now.playing) 1f else 0.4f),
                    topLeft = Offset(i * (bw + gp), y),
                    size = Size(bw, h),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
    }
}

/** 细进度条：有进度数据前用一条流动光带表示"在播"。 */
@Composable
private fun ShimmerLine(accent: Color, track: Color, playing: Boolean, height: Int = 3) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    Canvas(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        drawRect(color = track, size = size)
        if (playing) {
            val w = size.width * 0.34f
            val x = -w + (size.width + w) * t
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, accent.copy(alpha = 0.85f), Color.Transparent)
                ),
                topLeft = Offset(x, 0f),
                size = Size(w, size.height),
            )
        } else {
            drawRect(color = accent.copy(alpha = 0.55f), size = size)
        }
    }
}

/** 16 色频谱条（深空仪表的视觉主角）。
 *  注意：必须在固定尺寸的 Canvas 里画，不能靠改 Box 的 height 做律动——
 *  那样每帧都要重新 measure/layout，整页内容会跟着上下抖。 */
@Composable
private fun Spectrum16(accent: Color, playing: Boolean, height: Int = 74) {
    val transition = rememberInfiniteTransition(label = "spec16")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "t",
    )
    val count = 16
    val barW = 4.dp
    val gap = 2.dp
    Canvas(
        modifier = Modifier
            .width(((count * 4) + ((count - 1) * 2)).dp)
            .height(height.dp)
    ) {
        val bw = barW.toPx()
        val gp = gap.toPx()
        repeat(count) { i ->
            val hue = (i * 360f / count)
            val base = Color.hsv(hue, 0.62f, 1f)
            val phase = i / count.toFloat()
            val k = if (playing) {
                val v = kotlin.math.abs(kotlin.math.sin((t + phase) * Math.PI * 2.0)).toFloat()
                0.22f + 0.78f * v
            } else {
                0.22f + 0.10f * (i % 3) / 2f
            }
            val h = (height.dp.toPx() * k).coerceIn(3.dp.toPx(), size.height)
            val x = i * (bw + gp)
            val y = size.height - h
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(base, base.copy(alpha = 0.55f)),
                    startY = y,
                    endY = size.height,
                ),
                topLeft = Offset(x, y),
                size = Size(bw, h),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color, trailing: String? = null, trailingColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, fontSize = 11.sp, color = trailingColor ?: color.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun rememberModuleHealth(serviceBound: Boolean): com.liuxiansheng.lyricon.app.live.ModuleHealth.Info {
    val ctx = LocalContext.current
    val info = remember { mutableStateOf(com.liuxiansheng.lyricon.app.live.ModuleHealth.read(ctx, serviceBound)) }
    androidx.compose.runtime.LaunchedEffect(serviceBound) {
        while (true) {
            info.value = com.liuxiansheng.lyricon.app.live.ModuleHealth.read(ctx, serviceBound)
            kotlinx.coroutines.delay(2_000L)
        }
    }
    return info.value
}

/** 三态配色：绿 = 已激活、黄 = 需重启、红 = 未激活 */
private fun moduleStateColor(state: com.liuxiansheng.lyricon.app.live.ModuleHealth.State): Color = when (state) {
    com.liuxiansheng.lyricon.app.live.ModuleHealth.State.ACTIVE -> Color(0xFF37BE5A)
    com.liuxiansheng.lyricon.app.live.ModuleHealth.State.NEED_RESTART -> Color(0xFFE0A030)
    com.liuxiansheng.lyricon.app.live.ModuleHealth.State.INACTIVE -> Color(0xFFE05A5A)
}

@Composable
private fun Footer(skin: HomeSkin, health: com.liuxiansheng.lyricon.app.live.ModuleHealth.Info) {
    Text(
        text = "模块" + health.shortTitle + " · " + health.detail +
            " · " + com.liuxiansheng.lyricon.app.BuildConfig.VERSION_NAME,
        fontSize = 11.sp,
        color = skin.textSecondary.copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 6.dp, bottom = 28.dp),
    )
}

/** 最近播放 3 条（深空仪表 / 歌词杂志共用）。 */
@Composable
private fun RecentList(skin: HomeSkin, records: List<ListenRecord>, limit: Int = 3, onOpenRecords: () -> Unit) {
    if (records.isEmpty()) {
        Text(
            "还没有听歌记录（听够一半才会记一条）",
            fontSize = 12.sp,
            color = skin.textSecondary,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        return
    }
    records.take(limit).forEachIndexed { index, record ->
        if (index > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(skin.cardBorder.copy(alpha = 0.35f))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRecords)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = skin.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    record.artist.ifBlank { ListenRecordsStore.appLabel(record.packageName) },
                    fontSize = 11.sp,
                    color = skin.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(clockLabel(record.time), fontSize = 11.sp, color = skin.textSecondary)
        }
    }
}

// ===== ① 唱片机 =====

@Composable
private fun DeckHome(
    skin: HomeSkin,
    skinId: Int,
    live: HomeLive,
    moduleActive: Boolean,
    context: Context,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val health = rememberModuleHealth(moduleActive)
    val now = live.now
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // 头部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("词岛", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = skin.textPrimary)
                Text(
                    "VINYL PLAYER · 唱片机",
                    fontSize = 10.sp,
                    color = skin.accent,
                )
            }
            RebootChip(skin, scope)
        }

        Spacer(Modifier.height(18.dp))

        // 黑胶唱机
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(288.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height * 0.54f
                val r = size.height * 0.42f
                // 唱片本体
                drawCircle(Color(0xFF15100B), radius = r, center = Offset(cx, cy))
                drawCircle(
                    skin.accent.copy(alpha = 0.29f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.6f),
                )
                // 纹路
                repeat(9) { i ->
                    val rr = r * (0.44f + i * 0.062f)
                    drawCircle(
                        Color.White.copy(alpha = 0.055f),
                        radius = rr,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.4f),
                    )
                }
                // 铜金弧线
                drawArc(
                    color = skin.accent.copy(alpha = 0.9f),
                    startAngle = 198f,
                    sweepAngle = 62f,
                    useCenter = false,
                    topLeft = Offset(cx - r - 1.5f, cy - r - 1.5f),
                    size = Size(r * 2 + 3f, r * 2 + 3f),
                    style = Stroke(width = 3f, cap = StrokeCap.Round),
                )
                // 唱臂
                val ax = cx + r * 0.74f
                val ay = cy - r * 0.96f
                drawLine(
                    color = skin.accent.copy(alpha = 0.75f),
                    start = Offset(ax, ay),
                    end = Offset(cx + r * 0.30f, cy - r * 0.42f),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
                drawCircle(skin.accent.copy(alpha = 0.85f), radius = 9f, center = Offset(ax, ay))
                drawCircle(Color(0xFFF1DDC0), radius = 4.5f, center = Offset(cx + r * 0.30f, cy - r * 0.42f))
            }
            // 唱片中心封面
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .border(1.dp, skin.accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CoverTile(
                    now = now,
                    size = 92.dp,
                    shape = RoundedCornerShape(10.dp),
                    fallback = now.title.take(4).ifBlank { "词岛" },
                    fontSize = 19.sp,
                    skin = skin,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (now.hasTrack) now.title else "未在播放",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = skin.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (now.hasTrack) {
                listOf(now.artist, ListenRecordsStore.appLabel(now.packageName)).filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                "打开音乐 App 播放即可实时显示"
            },
            fontSize = 12.sp,
            color = skin.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(16.dp))
        ShimmerLine(skin.accent, skin.cardBorder.copy(alpha = 0.5f), now.playing)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            Text(if (now.playing) "正在播放" else "已暂停", fontSize = 11.sp, color = skin.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(clockLabel(System.currentTimeMillis()), fontSize = 11.sp, color = skin.textSecondary)
        }

        Spacer(Modifier.height(14.dp))
        // 播放控制
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(skin, 44, onClick = { sendMediaKey(scope, KEY_PREV) }) {
                Text("⏮", fontSize = 15.sp, color = skin.textPrimary)
            }
            Spacer(Modifier.width(24.dp))
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .background(skin.accent, CircleShape)
                    .clickable { sendMediaKey(scope, KEY_PLAY_PAUSE) },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(22.dp)) {
                    val ink = Color(0xFF1A130C)
                    if (now.playing) {
                        val bw = size.width * 0.30f
                        drawRect(ink, topLeft = Offset(0f, 0f), size = Size(bw, size.height))
                        drawRect(ink, topLeft = Offset(size.width - bw, 0f), size = Size(bw, size.height))
                    } else {
                        val tri = Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(tri, ink)
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            RoundIconButton(skin, 44, onClick = { sendMediaKey(scope, KEY_NEXT) }) {
                Text("⏭", fontSize = 15.sp, color = skin.textPrimary)
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("岛内实时", skin.textSecondary, "听歌记录 ›", skin.accent)
        Spacer(Modifier.height(8.dp))
        IslandCapsule(skin, now, onClick = onOpenRecords)

        Spacer(Modifier.height(18.dp))
        ThemedQuickGrid(
            skin = skin,
            onOpenAxis = onOpenAxis,
            onOpenSuperIsland = onOpenSuperIsland,
            onOpenStatusBarLyric = onOpenStatusBarLyric,
            onOpenLyricSource = onOpenLyricSource,
            onOpenStore = onOpenStore,
            onOpenAdvanced = onOpenAdvanced,
            onOpenRecords = onOpenRecords,
        )

        Spacer(Modifier.height(14.dp))
        MasterSwitchCard(context)

        Spacer(Modifier.height(16.dp))
        SectionLabel("首页主题", skin.textSecondary, skin.name, skin.accent)
        Spacer(Modifier.height(8.dp))
        HomeSkinPicker(currentId = skinId, onPick = { id -> HomeSkins.write(context, id) })

        Footer(skin, health)
    }
}

@Composable
private fun RoundIconButton(skin: HomeSkin, size: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(skin.cardBg, CircleShape)
            .border(1.dp, skin.cardBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ===== ② 晨雾概览 =====

@Composable
private fun MistHome(
    skin: HomeSkin,
    skinId: Int,
    live: HomeLive,
    moduleActive: Boolean,
    context: Context,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val health = rememberModuleHealth(moduleActive)
    val now = live.now
    val todayStart = remember { dayStartMs() }
    val today = live.records.filter { it.time >= todayStart }
    val todayMs = today.sumOf { it.listenedMs }
    val totalListenMs = live.totals.totalListenedMs
    val topArtist = today.filter { it.artist.isNotBlank() }
        .groupingBy { it.artist }.eachCount().entries.maxByOrNull { it.value }?.key ?: "—"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("词岛", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = skin.textPrimary)
                Text("超级岛歌词 · 晨雾概览", fontSize = 13.sp, color = skin.textSecondary)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .border(1.dp, skin.assertAccent(0.45f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text("晨雾 · 概览", fontSize = 11.sp, color = skin.accent)
                }
            }
            RebootChip(skin, scope)
        }

        Spacer(Modifier.height(20.dp))

        // 主卡：正在播放
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(skin.cardBg, RoundedCornerShape(24.dp))
                .border(1.dp, skin.cardBorder, RoundedCornerShape(24.dp))
                .padding(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (now.hasTrack) "正在播放 · " + ListenRecordsStore.appLabel(now.packageName) else "未在播放",
                        fontSize = 12.sp,
                        color = skin.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (now.playing) Color(0xFF4CAF50) else skin.textSecondary,
                                CircleShape,
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (now.playing) "在线" else "暂停",
                        fontSize = 11.sp,
                        color = if (now.playing) Color(0xFF4CAF50) else skin.textSecondary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverTile(
                        now = now,
                        size = 72.dp,
                        shape = CircleShape,
                        fallback = "♪",
                        fontSize = 30.sp,
                        skin = skin,
                        fallbackColor = Color.White,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (now.hasTrack) now.title else "未在播放",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = skin.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            NowPlayingReader.mainLine(now).let { if (it == now.title) now.artist else it },
                            fontSize = 12.sp,
                            color = skin.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Spectrum16(skin.accent, now.playing, height = 34)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (now.hasTrack) now.artist.ifBlank { "—" } else "打开音乐 App 播放",
                        fontSize = 11.sp,
                        color = skin.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(clockLabel(System.currentTimeMillis()), fontSize = 11.sp, color = skin.textSecondary)
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("今日", skin.textPrimary, "听歌概览", skin.textSecondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TodayCell("${today.size}", "首 曲目", skin, Modifier.weight(1f))
            TodayCell(
                minuteLabel(todayMs),
                if (todayMs >= 60L * 60 * 1000) "小时 时长" else "分钟 时长",
                skin, Modifier.weight(1f),
            )
            TodayCell(topArtist.take(5), "最爱歌手", skin, Modifier.weight(1f))
        }

        Spacer(Modifier.height(22.dp))
        ThemedQuickGrid(
            skin = skin,
            onOpenAxis = onOpenAxis,
            onOpenSuperIsland = onOpenSuperIsland,
            onOpenStatusBarLyric = onOpenStatusBarLyric,
            onOpenLyricSource = onOpenLyricSource,
            onOpenStore = onOpenStore,
            onOpenAdvanced = onOpenAdvanced,
            onOpenRecords = onOpenRecords,
        )

        Spacer(Modifier.height(14.dp))
        MasterSwitchCard(context)

        Spacer(Modifier.height(16.dp))
        SectionLabel("首页主题", skin.textPrimary, skin.name, skin.accent)
        Spacer(Modifier.height(8.dp))
        HomeSkinPicker(currentId = skinId, onPick = { id -> HomeSkins.write(context, id) })

        Footer(skin, health)
    }
}

@Composable
private fun TodayCell(value: String, label: String, skin: HomeSkin, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(skin.cardBg, RoundedCornerShape(18.dp))
            .border(1.dp, skin.cardBorder, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = if (value.length > 3) 17.sp else 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = skin.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(label, fontSize = 11.sp, color = skin.textSecondary)
        }
    }
}

// ===== ③ 深空仪表 =====

@Composable
private fun DashHome(
    skin: HomeSkin,
    skinId: Int,
    live: HomeLive,
    moduleActive: Boolean,
    context: Context,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val health = rememberModuleHealth(moduleActive)
    val now = live.now
    val todayStart = remember { dayStartMs() }
    val today = live.records.filter { it.time >= todayStart }
    val todayMs = today.sumOf { it.listenedMs }
    val week = remember(live.daily) { weekBuckets(live.daily) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("词岛", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = skin.textPrimary)
                Text("LIVE DASHBOARD · 深空仪表", fontSize = 10.sp, color = skin.accent)
            }
            Box(
                modifier = Modifier
                    .border(1.dp, skin.cardBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(moduleStateColor(health.state), CircleShape)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(health.shortTitle, fontSize = 12.sp, color = skin.textPrimary)
                }
            }
            Spacer(Modifier.width(8.dp))
            RebootChip(skin, scope)
        }

        Spacer(Modifier.height(16.dp))

        // 实时歌词 + 16 色频谱
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(skin.cardBg, RoundedCornerShape(24.dp))
                .border(1.dp, skin.cardBorder, RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (now.hasTrack) "实时歌词 · " + ListenRecordsStore.appLabel(now.packageName) else "实时歌词 · 未在播放",
                        fontSize = 12.sp,
                        color = skin.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text("16 色频谱", fontSize = 11.sp, color = skin.accent)
                }
                Spacer(Modifier.height(12.dp))
                IslandCapsule(skin, now, barCount = 0, onClick = onOpenRecords)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (now.playing) "播放中" else "已暂停", fontSize = 12.sp, color = skin.accent)
                        Text(
                            "今日已听 " + durationShort(todayMs) + " · 记录 " + today.size + " 首",
                            fontSize = 11.sp,
                            color = skin.textSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spectrum16(skin.accent, now.playing, height = 64)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 今日时长环
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(skin.cardBg, RoundedCornerShape(20.dp))
                    .border(1.dp, skin.cardBorder, RoundedCornerShape(20.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Text("今日时长", fontSize = 12.sp, color = skin.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                            TodayRing(skin, todayMs)
                            Text(
                                minuteLabel(todayMs) + "'",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = skin.textPrimary,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${today.size} 首曲目", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = skin.textPrimary)
                            Text(
                                if (today.isNotEmpty() && live.records.isNotEmpty()) "共 ${live.records.size} 首记录" else "今天还没记",
                                fontSize = 10.sp,
                                color = skin.textSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            // 近 7 天
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(skin.cardBg, RoundedCornerShape(20.dp))
                    .border(1.dp, skin.cardBorder, RoundedCornerShape(20.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Row {
                        Text("近 7 天", fontSize = 12.sp, color = skin.textSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(durationShort(week.sumOf { it.second }), fontSize = 10.sp, color = skin.accent)
                    }
                    Spacer(Modifier.height(12.dp))
                    WeekBars(skin, week, height = 54)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("最近播放", skin.textPrimary, "${live.records.size} 首", skin.textSecondary)
        Spacer(Modifier.height(4.dp))
        RecentList(skin, live.records, 3, onOpenRecords)

        Spacer(Modifier.height(14.dp))
        ThemedQuickRows(
            skin = skin,
            onOpenAxis = onOpenAxis,
            onOpenSuperIsland = onOpenSuperIsland,
            onOpenStatusBarLyric = onOpenStatusBarLyric,
            onOpenLyricSource = onOpenLyricSource,
            onOpenStore = onOpenStore,
            onOpenAdvanced = onOpenAdvanced,
            onOpenRecords = onOpenRecords,
        )

        Spacer(Modifier.height(14.dp))
        MasterSwitchCard(context)

        Spacer(Modifier.height(16.dp))
        SectionLabel("首页主题", skin.textPrimary, skin.name, skin.accent)
        Spacer(Modifier.height(8.dp))
        HomeSkinPicker(currentId = skinId, onPick = { id -> HomeSkins.write(context, id) })

        Footer(skin, health)
    }
}

private fun weekBuckets(daily: List<DailyStat>): List<Pair<String, Long>> {
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val today = cal.timeInMillis
    val out = ArrayList<Pair<String, Long>>(7)
    for (i in 6 downTo 0) {
        val dayMs = today - i * 24L * 60 * 60 * 1000
        val key = dayFmt.format(Date(dayMs))
        // 明细每天清空，周柱状图改读每日汇总（留 730 天，不会只剩今天一根）
        val sum = daily.filter { it.day == key }.sumOf { it.ms }
        val c = Calendar.getInstance()
        c.timeInMillis = dayMs
        val idx = when (c.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
        out.add(labels[idx] to sum)
    }
    return out
}

@Composable
private fun TodayRing(skin: HomeSkin, todayMs: Long) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = size.minDimension * 0.12f
        val target = 2L * 60 * 60 * 1000  // 2 小时算一整圈
        val fraction = (todayMs.toFloat() / target).coerceIn(0f, 1f)
        drawCircle(skin.cardBorder.copy(alpha = 0.55f), radius = (size.minDimension - stroke) / 2f, style = Stroke(width = stroke))
        drawArc(
            brush = Brush.sweepGradient(listOf(skin.accent, skin.accent.copy(alpha = 0.45f), skin.accent)),
            startAngle = -90f,
            sweepAngle = 360f * fraction,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun WeekBars(skin: HomeSkin, data: List<Pair<String, Long>>, height: Int) {
    val max = (data.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    Row(
        modifier = Modifier.fillMaxWidth().height((height + 16).dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        data.forEachIndexed { i, (label, ms) ->
            val k = (ms.toFloat() / max).coerceIn(0.12f, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .height((height * k).dp)
                        .background(
                            if (i == data.size - 1) skin.accent else skin.accent.copy(alpha = 0.38f),
                            RoundedCornerShape(5.dp),
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 9.sp, color = skin.textSecondary)
            }
        }
    }
}

// ===== ④ 歌词杂志 =====

@Composable
private fun LyricHome(
    skin: HomeSkin,
    skinId: Int,
    live: HomeLive,
    moduleActive: Boolean,
    context: Context,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val health = rememberModuleHealth(moduleActive)
    val now = live.now
    val todayStart = remember { dayStartMs() }
    val today = live.records.filter { it.time >= todayStart }
    val todayMs = today.sumOf { it.listenedMs }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("词岛", fontSize = 14.sp, color = skin.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                "更多 ›",
                fontSize = 12.sp,
                color = skin.textSecondary,
                modifier = Modifier
                    .clickable(onClick = onOpenAdvanced)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            RebootChip(skin, scope)
        }

        Spacer(Modifier.height(40.dp))

        Text(
            if (now.hasTrack) "正在播放 · " + ListenRecordsStore.appLabel(now.packageName) else "未在播放",
            fontSize = 11.sp,
            color = skin.accent,
        )
        Text(
            if (now.hasTrack) listOf(now.title, now.artist).filter { it.isNotBlank() }.joinToString(" / ") else "打开音乐 App 播放",
            fontSize = 12.sp,
            color = skin.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(22.dp))
        // 当前歌词（大字）
        Text(
            text = if (now.hasTrack) NowPlayingReader.mainLine(now) else "还没有在播放的歌",
            fontSize = 30.sp,
            lineHeight = 42.sp,
            fontWeight = FontWeight.Bold,
            color = if (now.hasTrack) skin.accent else skin.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (now.next.isNotBlank()) now.next else if (now.hasTrack) now.title else "",
            fontSize = 24.sp,
            lineHeight = 34.sp,
            color = skin.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(20.dp))
        ShimmerLine(skin.accent, skin.cardBorder.copy(alpha = 0.55f), now.playing, height = 2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            Text(if (now.playing) "正在播放" else "已暂停", fontSize = 11.sp, color = skin.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(clockLabel(System.currentTimeMillis()), fontSize = 11.sp, color = skin.textSecondary)
        }

        Spacer(Modifier.height(14.dp))
        IslandCapsule(skin, now, onClick = onOpenRecords)

        Spacer(Modifier.height(22.dp))
        // 玻璃面板
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(skin.cardBg, RoundedCornerShape(28.dp))
                .border(1.dp, skin.cardBorder, RoundedCornerShape(28.dp))
                .padding(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverTile(
                        now = now,
                        size = 64.dp,
                        shape = RoundedCornerShape(16.dp),
                        fallback = now.title.take(1).ifBlank { "岛" },
                        fontSize = 17.sp,
                        skin = skin,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (now.hasTrack) now.title else "未在播放",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = skin.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (now.hasTrack) listOf(now.artist, ListenRecordsStore.appLabel(now.packageName)).filter { it.isNotBlank() }.joinToString(" · ") else "—",
                            fontSize = 11.sp,
                            color = skin.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "今日已听 " + durationShort(todayMs) + " · " + today.size + " 首",
                    fontSize = 11.sp,
                    color = skin.accent,
                )

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelAction("岛设置", "歌词 / 材质", skin, Modifier.weight(1f), onOpenSuperIsland)
                    PanelAction("听歌记录", "历史 / 海报", skin, Modifier.weight(1f), onOpenRecords)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelAction("歌词源商店", "装源 / 更新源", skin, Modifier.weight(1f), onOpenStore)
                    PanelAction("更多", "歌词源 / 关于", skin, Modifier.weight(1f), onOpenAdvanced)
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("最近播放", skin.textPrimary, "${live.records.size} 首", skin.textSecondary)
                RecentList(skin, live.records, 3, onOpenRecords)
            }
        }

        Spacer(Modifier.height(14.dp))
        MasterSwitchCard(context)

        Spacer(Modifier.height(16.dp))
        SectionLabel("首页主题", skin.textPrimary, skin.name, skin.accent)
        Spacer(Modifier.height(8.dp))
        HomeSkinPicker(currentId = skinId, onPick = { id -> HomeSkins.write(context, id) })

        Footer(skin, health)
    }
}

@Composable
private fun PanelAction(
    title: String,
    sub: String,
    skin: HomeSkin,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(skin.cardBg.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
            .border(1.dp, skin.cardBorder.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = skin.textPrimary)
            Text(sub, fontSize = 10.sp, color = skin.textSecondary, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

// ===== 快捷入口两种排布 =====

@Composable
private fun ThemedQuickGrid(
    skin: HomeSkin,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    SectionLabel("快捷入口", skin.textPrimary)
    Spacer(Modifier.height(10.dp))
    val items = listOf(
        Triple("歌词打轴", "没歌词自己敲时间", onOpenAxis),
        Triple("超级岛设置", "歌词 / 布局 / 材质", onOpenSuperIsland),
        Triple("状态栏歌词", "独立样式与动画", onOpenStatusBarLyric),
        Triple("歌词源", "扫描 / 白名单", onOpenLyricSource),
        Triple("歌词源商店", "装源 / 更新源", onOpenStore),
        Triple("听歌记录", "历史 / 统计 / 海报", onOpenRecords),
    )
    items.chunked(2).forEachIndexed { rowIndex, chunk ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (rowIndex == 0) 10.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            chunk.forEach { (title, sub, action) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(skin.cardBg, RoundedCornerShape(18.dp))
                        .border(1.dp, skin.cardBorder, RoundedCornerShape(18.dp))
                        .clickable(onClick = action)
                        .padding(14.dp)
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(skin.chipFor(title).copy(alpha = 0.22f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("›", fontSize = 13.sp, color = skin.chipFor(title))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = skin.textPrimary)
                        Text(sub, fontSize = 10.sp, color = skin.textSecondary, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "更多设置 ›",
        fontSize = 11.sp,
        color = skin.textSecondary,
        modifier = Modifier
            .clickable(onClick = onOpenAdvanced)
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun ThemedQuickRows(
    skin: HomeSkin,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    SectionLabel("快捷入口", skin.textPrimary)
    Spacer(Modifier.height(8.dp))
    val items = listOf(
        Triple("歌词打轴", "没歌词自己敲时间", onOpenAxis),
        Triple("超级岛设置", "歌词 / 布局 / 材质 / 避让", onOpenSuperIsland),
        Triple("状态栏歌词", "独立样式 / 动画 / 图标", onOpenStatusBarLyric),
        Triple("歌词源", "扫描 / 白名单 / 优先级", onOpenLyricSource),
        Triple("歌词源商店", "下载 / 校验 / 更新", onOpenStore),
        Triple("听歌记录", "历史 / 统计 / 海报", onOpenRecords),
    )
    items.forEachIndexed { index, (title, sub, action) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(skin.cardBg, RoundedCornerShape(18.dp))
                .border(1.dp, skin.cardBorder, RoundedCornerShape(18.dp))
                .clickable(onClick = action)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(skin.chipFor(title).copy(alpha = 0.22f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("●", fontSize = 10.sp, color = skin.chipFor(title))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = skin.textPrimary)
                Text(sub, fontSize = 10.sp, color = skin.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            Text("›", fontSize = 15.sp, color = skin.textSecondary)
        }
    }
    Text(
        "更多设置 ›",
        fontSize = 11.sp,
        color = skin.textSecondary,
        modifier = Modifier
            .clickable(onClick = onOpenAdvanced)
            .padding(vertical = 4.dp),
    )
}

// ===== ⑤ 纯净蓝（新版默认）：浅蓝底 + 白色圆角卡 + 黑色岛胶囊 =====
// 按效果图 1:1 复刻的比例落成 Compose：卡片圆角 22dp、列间距 12dp、
// 方块 44dp、标签 11sp、词岛 26sp。所有卡片高度用内容撑开，避免大字号被裁。

@Composable
private fun BoardHome(
    skin: HomeSkin,
    skinId: Int,
    live: HomeLive,
    moduleActive: Boolean,
    context: Context,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val health = rememberModuleHealth(moduleActive)
    val now = live.now
    val todayStart = remember { dayStartMs() }
    val today = live.records.filter { it.time >= todayStart }
    val todayMs = today.sumOf { it.listenedMs }
    val totalListenMs = live.totals.totalListenedMs

    var recordsExpanded by remember { mutableStateOf(false) }

    // 商店里可更新的源数量，挂在「歌词源商店」那个方块右上角
    var storeUpdates by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        storeUpdates = withContext(Dispatchers.IO) {
            // 顺手刷一次目录，不然刚发的更新要等进了商店才认得到
            runCatching { com.liuxiansheng.lyricon.app.store.StoreRepository.refresh(context) }
            runCatching {
                com.liuxiansheng.lyricon.app.store.StoreRepository.updateCount(context)
            }.getOrDefault(0)
        }
        if (com.liuxiansheng.lyricon.app.BuildConfig.DEBUG) {
            android.util.Log.i("StoreBadge", "首页商店角标 updates=$storeUpdates")
        }
    }

    var islandOn by remember {
        mutableStateOf(
            MasterPrefs.getBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true)
        )
    }
    var showNoRoot by remember { mutableStateOf(false) }
    NoRootDialog(visible = showNoRoot, context = context, onDismiss = { showNoRoot = false })

    // 背景：纯净蓝原版竖向渐变（顶部近白 → 底部浅蓝），底部再极轻压暗
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFFF7FBFF),
                        0.34f to Color(0xFFF0F5FE),
                        0.70f to Color(0xFFE8F0FD),
                        1.00f to Color(0xFFE2EBFA),
                    )
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.58f to Color(0x002C77F2),
                        1.00f to Color(0x0E2C77F2),
                    )
                )
            )
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp),
    ) {
        // ---- 品牌行：白底 logo + 词岛 + 重启状态栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoardLogo(skin)
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "词岛",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = skin.textPrimary,
                )
                Text(
                    "超级岛歌词",
                    fontSize = 12.sp,
                    color = skin.textSecondary,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Row(
                modifier = Modifier
                    .shadow(
                        elevation = 5.dp,
                        shape = RoundedCornerShape(19.dp),
                        clip = false,
                        ambientColor = Color(0x302C77F2),
                        spotColor = Color(0x302C77F2),
                    )
                    .clip(RoundedCornerShape(19.dp))
                    .background(Color.White)
                    .clickable {
                        SystemUiRestart.request(context, scope, onDenied = { showNoRoot = true })
                    }
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "重启状态栏",
                    tint = skin.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // ---- 卡1 模块状态 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (CouiUiState.enabled.value) {
                        Modifier.couiCard(
                            skin.dark,
                            24.dp,
                            base = if (skin.dark) skin.cardBg else Color.White.copy(alpha = 0.76f),
                            glowTint = skin.accent,
                        )
                    } else {
                        Modifier
                            .shadow(9.dp, RoundedCornerShape(22.dp), clip = false)
                            .background(skin.cardBg, RoundedCornerShape(22.dp))
                            .border(1.dp, skin.cardBorder, RoundedCornerShape(22.dp))
                    }
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "模块状态",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = skin.textPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(if (islandOn) "超级岛歌词" else "状态栏歌词", fontSize = 11.5.sp, color = skin.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    BoardSwitch(
                        checked = islandOn,
                        onCheckedChange = { value ->
                            islandOn = value
                            // 与总开关卡同一套互拆逻辑：先开目标套再关当前套，
                            // 保证 SystemUI 任意时刻至少一套开着。
                            if (value) {
                                MasterPrefs.setBoolean(
                                    context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true
                                )
                                StatusBarPrefs.setBoolean(
                                    context,
                                    StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC,
                                    false,
                                )
                            } else {
                                StatusBarPrefs.setBoolean(
                                    context,
                                    StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC,
                                    true,
                                )
                                MasterPrefs.setBoolean(
                                    context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, false
                                )
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(moduleStateColor(health.state), CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        health.title,
                        fontSize = 12.sp,
                        color = moduleStateColor(health.state),
                    )
                }
                // 需重启 / 未激活时补一行原因，点这一行直接重启状态栏
                if (health.state != com.liuxiansheng.lyricon.app.live.ModuleHealth.State.ACTIVE) {
                    Text(
                        text = health.detail + if (health.state == com.liuxiansheng.lyricon.app.live.ModuleHealth.State.INACTIVE) "" else "（点此重启）",
                        fontSize = 10.5.sp,
                        color = moduleStateColor(health.state).copy(alpha = 0.85f),
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clickable {
                                if (health.state == com.liuxiansheng.lyricon.app.live.ModuleHealth.State.NEED_RESTART) {
                                    SystemUiRestart.request(context, scope, onDenied = { showNoRoot = true })
                                }
                            },
                    )
                }
                Spacer(Modifier.height(7.dp))
                BoardDotWave(skin)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- 卡2 实时预览：黑岛胶囊（真实歌词 + 频谱） ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (CouiUiState.enabled.value) {
                        Modifier.couiCard(
                            skin.dark,
                            24.dp,
                            base = if (skin.dark) skin.cardBg else Color.White.copy(alpha = 0.76f),
                            glowTint = skin.accent,
                        )
                    } else {
                        Modifier
                            .shadow(9.dp, RoundedCornerShape(22.dp), clip = false)
                            .background(skin.cardBg, RoundedCornerShape(22.dp))
                            .border(1.dp, skin.cardBorder, RoundedCornerShape(22.dp))
                    }
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoardWaveIcon(skin)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "实时预览",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = skin.textPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (now.hasTrack) "进行中" else "未在播放",
                        fontSize = 11.sp,
                        color = skin.textSecondary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                IslandCapsule(skin, now, barCount = 6, onClick = { openPlayingApp(context, now) { onOpenSuperIsland() } })
                Spacer(Modifier.height(10.dp))
                BoardProgress(context, skin, now)
                Spacer(Modifier.height(8.dp))
                BoardWave(skin)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- 卡3 快捷入口：3 列方块 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (CouiUiState.enabled.value) {
                        Modifier.couiCard(
                            skin.dark,
                            24.dp,
                            base = if (skin.dark) skin.cardBg else Color.White.copy(alpha = 0.76f),
                            glowTint = skin.accent,
                        )
                    } else {
                        Modifier
                            .shadow(9.dp, RoundedCornerShape(22.dp), clip = false)
                            .background(skin.cardBg, RoundedCornerShape(22.dp))
                            .border(1.dp, skin.cardBorder, RoundedCornerShape(22.dp))
                    }
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Column {
                Text(
                    "快捷入口",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = skin.textPrimary,
                )
                Spacer(Modifier.height(13.dp))
                val tiles = listOf(
                    Triple("歌词打轴", 0, onOpenAxis),
                    Triple("超级岛设置", 1, onOpenSuperIsland),
                    Triple("状态栏歌词", 2, onOpenStatusBarLyric),
                    Triple("歌词源", 3, onOpenLyricSource),
                    Triple("歌词源商店", 6, onOpenStore),
                    Triple("更多设置", 5, onOpenAdvanced),
                )
                tiles.chunked(3).forEachIndexed { rowIndex, chunk ->
                    if (rowIndex > 0) Spacer(Modifier.height(13.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        chunk.forEach { (label, kind, action) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(onClick = action),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .shadow(
                                            elevation = 4.dp,
                                            shape = RoundedCornerShape(13.dp),
                                            clip = false,
                                            ambientColor = Color(0x242C77F2),
                                            spotColor = Color(0x242C77F2),
                                        )
                                        .background(Color(0xFFE2EDFE), RoundedCornerShape(13.dp))
                                        .border(1.dp, Color(0xFFD0E1FB), RoundedCornerShape(13.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BoardTileIcon(kind, skin)
                                    if (kind == 6 && storeUpdates > 0) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 2.dp, y = 2.dp)
                                                .size(17.dp)
                                                .background(Color(0xFFE07A2F), CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = storeUpdates.toString(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    color = skin.textSecondary,
                                    modifier = Modifier.padding(top = 6.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // 最后一行不满 3 个时补空槽，保持与上一行同一列对齐
                        repeat(3 - chunk.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- 卡4 听歌记录：折叠=一行入口，展开=今日首数 + 累计时长 + 最近三首 ----
        Box(
            modifier = (if (recordsExpanded) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth())
                .then(
                    if (CouiUiState.enabled.value) {
                        Modifier.couiCard(
                            skin.dark,
                            24.dp,
                            base = if (skin.dark) skin.cardBg else Color.White.copy(alpha = 0.76f),
                            glowTint = skin.accent,
                        )
                    } else {
                        Modifier
                            .shadow(9.dp, RoundedCornerShape(22.dp), clip = false)
                            .clip(RoundedCornerShape(22.dp))
                            .background(skin.cardBg)
                            .border(1.dp, skin.cardBorder, RoundedCornerShape(22.dp))
                    }
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Column(modifier = if (recordsExpanded) Modifier.fillMaxSize() else Modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenRecords)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "听歌记录",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = skin.textPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        if (!recordsExpanded && live.records.isNotEmpty()) {
                            Text(
                                "今日 ${today.size} 首 · 累计 ${live.totals.totalCount} 首 ${durationShort(totalListenMs)}",
                                fontSize = 10.5.sp,
                                color = skin.textSecondary,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Text(
                        if (recordsExpanded) "收起 ⌃" else "展开 ⌄",
                        fontSize = 11.5.sp,
                        color = skin.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { recordsExpanded = !recordsExpanded }
                            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
                if (!recordsExpanded) {
                    // 折叠态：两条最精简的记录，纯展示；点整块进听歌记录页
                    if (live.records.isEmpty()) {
                        Text(
                            "还没有听歌记录（听够一半才会记一条）",
                            fontSize = 11.sp,
                            color = skin.textSecondary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    } else {
                        live.records.take(2).forEach { record ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onOpenRecords)
                                    .padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    record.title,
                                    fontSize = 12.sp,
                                    color = skin.textPrimary,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    clockLabel(if (record.lastTime > 0) record.lastTime else record.time),
                                    fontSize = 10.sp,
                                    color = skin.textSecondary,
                                )
                            }
                        }
                    }
                }
                if (recordsExpanded) {
                    if (live.records.isEmpty()) {
                        Text(
                            "还没有听歌记录（听够一半才会记一条）",
                            fontSize = 11.sp,
                            color = skin.textSecondary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    } else {
                        Row(
                            modifier = Modifier.padding(top = 9.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        today.size.toString(),
                                        fontSize = 27.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = skin.textPrimary,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "首",
                                        fontSize = 11.sp,
                                        color = skin.textSecondary,
                                        modifier = Modifier.padding(bottom = 3.dp),
                                    )
                                }
                                Text("今日", fontSize = 10.5.sp, color = skin.textSecondary)
                            }
                            Box(
                                Modifier
                                    .padding(horizontal = 14.dp)
                                    .width(1.dp)
                                    .height(32.dp)
                                    .background(skin.cardBorder)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    durationShort(totalListenMs),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = skin.accent,
                                    maxLines = 1,
                                )
                                Text(
                                    "累计时长 · 共 ${live.totals.totalCount} 首",
                                    fontSize = 10.5.sp,
                                    color = skin.textSecondary,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 9.dp, bottom = 3.dp)
                                .height(1.dp)
                                .background(skin.cardBorder)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            live.records.take(3).forEachIndexed { index, record ->
                                RecentListenRow(skin, record, index, context, onOpenRecords)
                            }
                            Text(
                                "查看全部记录 ›",
                                fontSize = 11.sp,
                                color = skin.accent,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onOpenRecords)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }                    }
                }
            }
        }

        // ---- 主题切换条（与其他四套布局一致，放页脚上方） ----
        Spacer(Modifier.height(10.dp))
        HomeSkinPicker(currentId = skinId, onPick = { id -> HomeSkins.write(context, id) })

        // ---- 页脚 ----
        Text(
            "应用版本 " + com.liuxiansheng.lyricon.app.BuildConfig.VERSION_NAME + " · Android",
            fontSize = 10.sp,
            color = skin.textSecondary.copy(alpha = 0.85f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
    }
}

// ===== ⑤-B 纯净蓝的凝光版：结构照纯净蓝原样，外观整套换成凝光 =====
// 主页是自己画的，不能靠给原卡叠一层材质（浅色白卡上叠了也看不出来），
// 所以这里按原结构重做一套：同样的品牌行 + 四张卡 + 主题条 + 页脚，
// 配色换成凝光（深蓝底 / 深灰卡 / 浅字），卡片走 couiCard 玻璃材质。

@Composable
private fun BoardCouiHome(
    skin: HomeSkin,
    skinId: Int,
    live: HomeLive,
    moduleActive: Boolean,
    context: Context,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val health = rememberModuleHealth(moduleActive)
    val now = live.now
    val todayStart = remember { dayStartMs() }
    val today = live.records.filter { it.time >= todayStart }
    val todayMs = today.sumOf { it.listenedMs }
    val totalListenMs = live.totals.totalListenedMs

    var recordsExpanded by remember { mutableStateOf(false) }

    var islandOn by remember {
        mutableStateOf(
            MasterPrefs.getBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true)
        )
    }
    var showNoRoot by remember { mutableStateOf(false) }
    NoRootDialog(visible = showNoRoot, context = context, onDismiss = { showNoRoot = false })

    // 商店里可更新的源数量，挂在「歌词源商店」那个方块右上角
    var storeUpdates by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        storeUpdates = withContext(Dispatchers.IO) {
            // 顺手刷一次目录，不然刚发的更新要等进了商店才认得到
            runCatching { com.liuxiansheng.lyricon.app.store.StoreRepository.refresh(context) }
            runCatching {
                com.liuxiansheng.lyricon.app.store.StoreRepository.updateCount(context)
            }.getOrDefault(0)
        }
        if (com.liuxiansheng.lyricon.app.BuildConfig.DEBUG) {
            android.util.Log.i("StoreBadge", "凝光首页商店角标 updates=$storeUpdates")
        }
    }

    // 凝光皮肤：与纯净蓝同结构，只把配色换成凝光那一套
    // 明暗跟应用主题（界面风格/皮肤）走，不再读系统深浅，保证首页与设置页同一套黑底
    val dark = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cs = skin.copy(
        dark = dark,
        bgTop = if (dark) Color.Black else Color(0xFFEAF1FF),
        bgMid = if (dark) Color.Black else Color(0xFFE3EEFF),
        bgBottom = if (dark) Color.Black else Color(0xFFDDEDFD),
        cardBg = if (dark) Color(0xB31C1C1E) else Color(0xD9F2F6FF),
        cardBorder = if (dark) Color(0x1FFFFFFF) else Color(0x1A2C77F2),
        accent = if (dark) Color(0xFF6E9BFF) else Color(0xFF2C77F2),
        textPrimary = if (dark) Color(0xFFEAF0F8) else Color(0xFF14161C),
        textSecondary = if (dark) Color(0xFFA1B4CC) else Color(0xFF6C7790),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to cs.bgTop,
                        0.55f to cs.bgMid,
                        1.00f to cs.bgBottom,
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 18.dp),
        ) {
            // ---- 品牌行 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardLogo(cs)
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "词岛",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = cs.textPrimary,
                    )
                    Text(
                        "超级岛歌词",
                        fontSize = 12.sp,
                        color = cs.textSecondary,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .couiCard(
                            dark,
                            19.dp,
                            base = if (dark) Color(0xCC262626) else Color.White.copy(alpha = 0.72f),
                            glowTint = cs.accent,
                        )
                        .clickable {
                            SystemUiRestart.request(context, scope, onDenied = { showNoRoot = true })
                        }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "重启状态栏",
                        tint = cs.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ---- 卡1 模块状态 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .couiCard(dark, 24.dp, base = cs.cardBg, glowTint = cs.accent)
                    .couiEdgeGlow(dark, 24.dp, lineAlpha = if (dark) 0.58f else 0.55f, innerGlowAlpha = if (dark) 0.18f else 0.22f)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "模块状态",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.textPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (islandOn) "超级岛歌词" else "状态栏歌词",
                            fontSize = 11.5.sp,
                            color = cs.textSecondary,
                        )
                        Spacer(Modifier.width(8.dp))
                        BoardSwitch(
                            checked = islandOn,
                            onCheckedChange = { value ->
                                islandOn = value
                                if (value) {
                                    MasterPrefs.setBoolean(
                                        context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true
                                    )
                                    StatusBarPrefs.setBoolean(
                                        context,
                                        StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC,
                                        false,
                                    )
                                } else {
                                    StatusBarPrefs.setBoolean(
                                        context,
                                        StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC,
                                        true,
                                    )
                                    MasterPrefs.setBoolean(
                                        context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, false
                                    )
                                }
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(moduleStateColor(health.state), CircleShape)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            health.title,
                            fontSize = 12.sp,
                            color = moduleStateColor(health.state),
                        )
                    }
                    if (health.state != com.liuxiansheng.lyricon.app.live.ModuleHealth.State.ACTIVE) {
                        Text(
                            text = health.detail + if (health.state == com.liuxiansheng.lyricon.app.live.ModuleHealth.State.INACTIVE) "" else "（点此重启）",
                            fontSize = 10.5.sp,
                            color = moduleStateColor(health.state).copy(alpha = 0.85f),
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .clickable {
                                    if (health.state == com.liuxiansheng.lyricon.app.live.ModuleHealth.State.NEED_RESTART) {
                                        SystemUiRestart.request(context, scope, onDenied = { showNoRoot = true })
                                    }
                                },
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    BoardDotWave(cs)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- 卡2 实时预览 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .couiCard(dark, 24.dp, base = cs.cardBg, glowTint = cs.accent)
                    .couiEdgeGlow(dark, 24.dp, lineAlpha = if (dark) 0.58f else 0.55f, innerGlowAlpha = if (dark) 0.18f else 0.22f)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BoardWaveIcon(cs)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "实时预览",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.textPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (now.hasTrack) "进行中" else "未在播放",
                            fontSize = 11.sp,
                            color = cs.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    IslandCapsule(cs, now, barCount = 6, onClick = { openPlayingApp(context, now) { onOpenSuperIsland() } })
                    Spacer(Modifier.height(10.dp))
                    BoardProgress(context, cs, now)
                    Spacer(Modifier.height(8.dp))
                    BoardWave(cs)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- 卡3 快捷入口 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .couiCard(dark, 24.dp, base = cs.cardBg, glowTint = cs.accent)
                    .couiEdgeGlow(dark, 24.dp, lineAlpha = if (dark) 0.58f else 0.55f, innerGlowAlpha = if (dark) 0.18f else 0.22f)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Column {
                    Text(
                        "快捷入口",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.textPrimary,
                    )
                    Spacer(Modifier.height(13.dp))
                    val tiles = listOf(
                        Triple("歌词打轴", 0, onOpenAxis),
                        Triple("超级岛设置", 1, onOpenSuperIsland),
                        Triple("状态栏歌词", 2, onOpenStatusBarLyric),
                        Triple("歌词源", 3, onOpenLyricSource),
                        Triple("歌词源商店", 6, onOpenStore),
                        Triple("更多设置", 5, onOpenAdvanced),
                    )
                    tiles.chunked(3).forEachIndexed { rowIndex, chunk ->
                        if (rowIndex > 0) Spacer(Modifier.height(13.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            chunk.forEach { (label, kind, action) ->
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(onClick = action),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .couiCard(
                                                dark,
                                                13.dp,
                                                base = if (dark) Color(0xCC262626) else Color(0xB8FFFFFF),
                                                glowTint = cs.accent,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        BoardTileIcon(kind, cs)
                                        if (kind == 6 && storeUpdates > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 2.dp, y = 2.dp)
                                                    .size(17.dp)
                                                    .background(Color(0xFFE07A2F), CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = storeUpdates.toString(),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        label,
                                        fontSize = 11.sp,
                                        color = cs.textSecondary,
                                        modifier = Modifier.padding(top = 6.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            repeat(3 - chunk.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- 卡4 听歌记录 ----
            Box(
                modifier = (if (recordsExpanded) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth())
                    .couiCard(dark, 24.dp, base = cs.cardBg, glowTint = cs.accent)
                    .couiEdgeGlow(dark, 24.dp, lineAlpha = if (dark) 0.58f else 0.55f, innerGlowAlpha = if (dark) 0.18f else 0.22f)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Column(modifier = if (recordsExpanded) Modifier.fillMaxSize() else Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenRecords)
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "听歌记录",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.textPrimary,
                            )
                            Spacer(Modifier.weight(1f))
                            if (!recordsExpanded && live.records.isNotEmpty()) {
                                Text(
                                    "今日 ${today.size} 首 · 累计 ${live.totals.totalCount} 首 ${durationShort(totalListenMs)}",
                                    fontSize = 10.5.sp,
                                    color = cs.textSecondary,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        Text(
                            if (recordsExpanded) "收起 ⌃" else "展开 ⌄",
                            fontSize = 11.5.sp,
                            color = cs.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { recordsExpanded = !recordsExpanded }
                                .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                    if (!recordsExpanded) {
                        if (live.records.isEmpty()) {
                            Text(
                                "还没有听歌记录（听够一半才会记一条）",
                                fontSize = 11.sp,
                                color = cs.textSecondary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        } else {
                            live.records.take(2).forEach { record ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(onClick = onOpenRecords)
                                        .padding(top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        record.title,
                                        fontSize = 12.sp,
                                        color = cs.textPrimary,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        clockLabel(if (record.lastTime > 0) record.lastTime else record.time),
                                        fontSize = 10.sp,
                                        color = cs.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                    if (recordsExpanded) {
                        if (live.records.isEmpty()) {
                            Text(
                                "还没有听歌记录（听够一半才会记一条）",
                                fontSize = 11.sp,
                                color = cs.textSecondary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        } else {
                            Row(
                                modifier = Modifier.padding(top = 9.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            today.size.toString(),
                                            fontSize = 27.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = cs.textPrimary,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "首",
                                            fontSize = 11.sp,
                                            color = cs.textSecondary,
                                            modifier = Modifier.padding(bottom = 3.dp),
                                        )
                                    }
                                    Text("今日", fontSize = 10.5.sp, color = cs.textSecondary)
                                }
                                Box(
                                    Modifier
                                        .padding(horizontal = 14.dp)
                                        .width(1.dp)
                                        .height(32.dp)
                                        .background(cs.cardBorder)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        durationShort(totalListenMs),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = cs.accent,
                                        maxLines = 1,
                                    )
                                    Text(
                                        "累计时长 · 共 ${live.totals.totalCount} 首",
                                        fontSize = 10.5.sp,
                                        color = cs.textSecondary,
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 9.dp, bottom = 3.dp)
                                    .height(1.dp)
                                    .background(cs.cardBorder)
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                live.records.take(3).forEachIndexed { index, record ->
                                    RecentListenRow(cs, record, index, context, onOpenRecords)
                                }
                                Text(
                                    "查看全部记录 ›",
                                    fontSize = 11.sp,
                                    color = cs.accent,
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(top = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(onClick = onOpenRecords)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HomeSkinPicker(currentId = skinId, onPick = { id -> HomeSkins.write(context, id) })

            Text(
                "应用版本 " + com.liuxiansheng.lyricon.app.BuildConfig.VERSION_NAME + " · Android",
                fontSize = 10.sp,
                color = cs.textSecondary.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}


/** 累计时长文案：不足 10 小时保留一位小数（8.4），超过就取整（26）。 */
private fun hoursText(ms: Long): String {
    if (ms <= 0) return "0"
    val h = ms / 3_600_000.0
    return if (h >= 10.0) java.lang.String.format(java.util.Locale.US, "%.0f", h)
    else java.lang.String.format(java.util.Locale.US, "%.1f", h)
}


/** 按包名打开 App（听歌记录每一行点一下回那个播放器）。 */
private fun openAppByPackage(context: Context, pkg: String, fallback: () -> Unit) {
    val intent = if (pkg.isNotBlank()) {
        context.packageManager.getLaunchIntentForPackage(pkg)
    } else null
    if (intent != null) {
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    fallback()
}

/** 听歌记录卡里的一行：渐变封面 + 歌名 + 歌手（空则回退来源 App）+ 右侧时间。 */
@Composable
private fun RecentListenRow(
    skin: HomeSkin,
    record: ListenRecord,
    index: Int,
    context: Context,
    onOpenRecords: () -> Unit,
) {
    val tones = listOf(
        Color(0xFF7CACFF) to Color(0xFF2C60DC),
        Color(0xFFA6C6FF) to Color(0xFF6E9EF0),
        Color(0xFFC6B0FF) to Color(0xFF8E74E8),
    )
    val tone = tones[index % tones.size]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onOpenRecords)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(tone.first, tone.second)))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                record.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = skin.textPrimary,
                maxLines = 1,
            )
            Text(
                record.artist.ifBlank { ListenRecordsStore.appLabel(record.packageName) },
                fontSize = 10.5.sp,
                color = skin.textSecondary,
                maxLines = 1,
            )
        }
        Text(
            clockLabel(if (record.lastTime > 0) record.lastTime else record.time),
            fontSize = 10.5.sp,
            color = skin.textSecondary,
        )
    }
}

/** 取当前正在播放的播放器包名：先用实时状态里的包名，取不到再从媒体会话里找活跃的那个。 */
private fun currentPlayerPackage(context: Context, now: NowPlaying): String {
    val fromState = now.packageName.ifBlank { now.providerPlayerPackage }
    if (fromState.isNotBlank()) return fromState
    return try {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
            as? android.media.session.MediaSessionManager
        val cn = android.content.ComponentName(
            context,
            com.liuxiansheng.lyricon.app.live.AxisNotifListener::class.java,
        )
        val sessions = msm?.getActiveSessions(cn).orEmpty()
        sessions.firstOrNull {
            it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        }?.packageName ?: sessions.firstOrNull()?.packageName.orEmpty()
    } catch (e: Throwable) {
        ""
    }
}

/** 点实时预览：直接进"当前在用"的那个播放器（汽水、网易云、QQ 音乐、抖音…都跟着走）。
 *  没有正在播放的、或取不到播放器时，才回退到打开超级岛设置。 */
private fun openPlayingApp(context: Context, now: NowPlaying, fallback: () -> Unit) {
    val pkg = currentPlayerPackage(context, now)
    if (pkg.isNotBlank()) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }
    fallback()
}
/** 蓝色渐变 App 图标（58dp）：白色音频柱 + 底部三条横线。 */
@Composable
private fun BoardLogo(skin: HomeSkin) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .background(
                Brush.linearGradient(listOf(Color(0xFF5C9BFF), Color(0xFF2A6BEA))),
                RoundedCornerShape(16.dp),
            )
            .padding(11.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val c = Color.White
            val bar = w * 0.075f
            // 中间高、两侧递减的竖条（音频柱）
            drawLine(c, Offset(w * 0.50f, h * 0.05f), Offset(w * 0.50f, h * 0.58f), bar, StrokeCap.Round)
            drawLine(c, Offset(w * 0.31f, h * 0.15f), Offset(w * 0.31f, h * 0.52f), bar, StrokeCap.Round)
            drawLine(c, Offset(w * 0.69f, h * 0.15f), Offset(w * 0.69f, h * 0.52f), bar, StrokeCap.Round)
            drawLine(c, Offset(w * 0.14f, h * 0.25f), Offset(w * 0.14f, h * 0.45f), bar, StrokeCap.Round)
            drawLine(c, Offset(w * 0.86f, h * 0.25f), Offset(w * 0.86f, h * 0.45f), bar, StrokeCap.Round)
            // 底部三条横线，逐行收窄
            drawLine(c, Offset(w * 0.11f, h * 0.70f), Offset(w * 0.89f, h * 0.70f), bar, StrokeCap.Round)
            drawLine(c, Offset(w * 0.22f, h * 0.83f), Offset(w * 0.78f, h * 0.83f), bar, StrokeCap.Round)
            drawLine(c, Offset(w * 0.34f, h * 0.96f), Offset(w * 0.66f, h * 0.96f), bar, StrokeCap.Round)
        }
    }
}

/** 卡1 声波点阵：中间鼓、两端收的包络。 */
@Composable
private fun BoardDotWave(skin: HomeSkin) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(17.dp)
    ) {
        val cols = 54
        for (i in 0 until cols) {
            val t = (i + 0.5f) / cols
            val env = kotlin.math.sin(t * Math.PI.toFloat())
            val dots = when {
                env > 0.78f -> 3
                env > 0.42f -> 2
                else -> 1
            }
            val x = size.width * (i + 0.5f) / cols
            for (d in 0 until dots) {
                val y = size.height / 2f + (d - (dots - 1) / 2f) * size.height / 3.4f
                drawCircle(
                    color = skin.accent.copy(alpha = 0.16f + 0.42f * env),
                    radius = 1.3f,
                    center = Offset(x, y),
                )
            }
        }
    }
}

/** 卡2 标题左侧小波形图标。 */
@Composable
private fun BoardWaveIcon(skin: HomeSkin) {
    Canvas(Modifier.size(16.dp)) {
        val bars = 4
        for (i in 0 until bars) {
            val x = size.width * (i + 0.5f) / bars
            val amp = size.height * when (i) {
                0 -> 0.35f
                1 -> 0.72f
                2 -> 0.52f
                else -> 0.28f
            }
            drawLine(
                color = skin.accent,
                start = Offset(x, size.height / 2f - amp / 2f),
                end = Offset(x, size.height / 2f + amp / 2f),
                strokeWidth = 2.0f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 卡2 进度：轨道 + 已播 + 圆点 + 两端时间。 */
@Composable
private fun BoardProgress(
    context: Context,
    skin: HomeSkin,
    now: NowPlaying,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
    heightPx: Float = 0f,
    factor: Float = 0f,
) {
    val total = now.durationMs
    val baseFrac = if (total > 0L) {
        (now.positionMs.toFloat() / total).coerceIn(0f, 1f)
    } else {
        0f
    }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    val frac = if (dragging) dragFrac else baseFrac
    val shownMs = if (dragging && total > 0L) (dragFrac * total).toLong() else now.positionMs

    fun seekTo(f: Float) {
        if (total <= 0L) return
        val ms = (f * total).toLong().coerceIn(0L, total)
        runCatching {
            context.sendBroadcast(
                Intent("com.liuxiansheng.chidao.AXIS_MEDIA").apply {
                    setPackage("com.android.systemui")
                    putExtra("cmd", "seek_to")
                    putExtra("arg", ms)
                }
            )
        }
    }

    Column(modifier = modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (heightPx > 0f && factor > 0f) (heightPx * factor).dp else 26.dp)
                .pointerInput(total) {
                    if (total <= 0L) return@pointerInput
                    detectTapGestures { off ->
                        val f = (off.x / size.width).coerceIn(0f, 1f)
                        dragFrac = f
                        seekTo(f)
                    }
                }
                .pointerInput(total) {
                    if (total <= 0L) return@pointerInput
                    detectDragGestures(
                        onDragStart = { off ->
                            dragging = true
                            dragFrac = (off.x / size.width).coerceIn(0f, 1f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val f = dragFrac
                            dragging = false
                            seekTo(f)
                        },
                        onDragCancel = { dragging = false },
                    )
                }
        ) {
            val barH = 4.dp.toPx()
            val cy = size.height / 2f
            val top = cy - barH / 2f
            val knobX = (size.width * frac).coerceIn(barH, size.width)
            // 轨道投影（落在轨道下方 1.6px，带淡蓝）
            drawRoundRect(
                color = Color(0x1F2C77F2),
                topLeft = Offset(0f, top + 2f),
                size = Size(size.width, barH + 1f),
                cornerRadius = CornerRadius((barH + 1f) / 2f),
            )
            // 轨道本体
            drawRoundRect(
                color = Color(0xFFE4EAF6),
                topLeft = Offset(0f, top),
                size = Size(size.width, barH),
                cornerRadius = CornerRadius(barH / 2f),
            )
            if (frac > 0f) {
                val fw = (size.width * frac).coerceAtLeast(barH)
                val glowR = barH * (if (dragging) 7.5f else 6f)
                // 末端柔光：径向渐变从芯到边缘平滑衰减到全透明，是一团光，不是圆圈
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to Color(0x6E5FA2FF),
                            0.30f to Color(0x3D5FA2FF),
                            0.62f to Color(0x145FA2FF),
                            1.00f to Color(0x005FA2FF),
                        ),
                        center = Offset(knobX, cy),
                        radius = glowR,
                    ),
                    radius = glowR,
                    center = Offset(knobX, cy),
                )
                // 进度条本体：蓝渐变 + 上下柔光（发光管，不是圆圈）
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFF5FA2FF), Color(0xFF2C77F2)),
                        startX = 0f,
                        endX = size.width,
                    ),
                    topLeft = Offset(0f, top),
                    size = Size(fw, barH),
                    cornerRadius = CornerRadius(barH / 2f),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x005FA2FF),
                            Color(0x335FA2FF),
                            Color(0x005FA2FF),
                        ),
                        startY = top - barH * 2.2f,
                        endY = top + barH * 3.2f,
                    ),
                    topLeft = Offset(0f, top - barH * 2.2f),
                    size = Size(fw, barH * 5.4f),
                    cornerRadius = CornerRadius(barH * 2.7f),
                )
                // 手柄：白圆点 + 蓝芯
                drawCircle(color = Color.White, radius = barH * (if (dragging) 1.9f else 1.6f), center = Offset(knobX, cy))
                drawCircle(color = skin.accent, radius = barH * (if (dragging) 1.15f else 1.0f), center = Offset(knobX, cy))
            }
        }
        if (showTime) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp)
            ) {
                Text(
                    boardTime(shownMs),
                    fontSize = 9.sp,
                    color = if (dragging) skin.accent else skin.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                Text(boardTime(now.durationMs), fontSize = 9.sp, color = skin.textSecondary)
            }
        }
    }
}

/** 卡2 底部双波浪。 */
@Composable
private fun BoardWave(skin: HomeSkin) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
    ) {
        val steps = 60
        val p1 = Path()
        val p2 = Path()
        for (i in 0..steps) {
            val x = size.width * i / steps
            val y1 = size.height * 0.52f + size.height * 0.30f * kotlin.math.sin(i * 0.26f + 0.9f)
            val y2 = size.height * 0.52f + size.height * 0.26f * kotlin.math.sin(i * 0.26f + 2.5f)
            if (i == 0) {
                p1.moveTo(x, y1)
                p2.moveTo(x, y2)
            } else {
                p1.lineTo(x, y1)
                p2.lineTo(x, y2)
            }
        }
        drawPath(p1, skin.accent.copy(alpha = 0.48f), style = Stroke(width = 1.6f))
        drawPath(p2, skin.accent.copy(alpha = 0.20f), style = Stroke(width = 1.6f))
    }
}

/** 卡3 六个方块图标，0..5。 */
@Composable
private fun BoardTileIcon(kind: Int, skin: HomeSkin) {
    Canvas(Modifier.size(25.dp)) {
        val w = size.width
        val h = size.height
        val c = Color(0xFF1F63E5)
        val thin = Stroke(width = 2.9f, cap = StrokeCap.Round)
        when (kind) {
            // 麦克风 + 立柱
            0 -> {
                drawRoundRect(
                    color = c,
                    topLeft = Offset(w * 0.30f, h * 0.06f),
                    size = Size(w * 0.40f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.20f),
                )
                drawArc(
                    color = c,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.26f),
                    size = Size(w * 0.68f, h * 0.44f),
                    style = thin,
                )
                drawLine(c, Offset(w * 0.5f, h * 0.70f), Offset(w * 0.5f, h * 0.90f), 2.9f, StrokeCap.Round)
            }
            // 三条推子
            1 -> {
                for (i in 0 until 3) {
                    val y = h * (0.22f + i * 0.28f)
                    drawLine(c.copy(alpha = 0.62f), Offset(w * 0.14f, y), Offset(w * 0.86f, y), 2.9f, StrokeCap.Round)
                    val knobX = w * (if (i == 0) 0.62f else if (i == 1) 0.38f else 0.72f)
                    drawCircle(c, radius = w * 0.11f, center = Offset(knobX, y))
                }
            }
            // 状态栏 + 波形
            2 -> {
                drawRoundRect(
                    color = c.copy(alpha = 0.8f),
                    topLeft = Offset(w * 0.10f, h * 0.14f),
                    size = Size(w * 0.80f, h * 0.30f),
                    cornerRadius = CornerRadius(h * 0.14f),
                    style = thin,
                )
                for (i in 0 until 4) {
                    val x = w * (0.24f + i * 0.17f)
                    val amp = h * (if (i == 1 || i == 2) 0.22f else 0.13f)
                    drawLine(c, Offset(x, h * 0.72f - amp), Offset(x, h * 0.72f + amp), 2.9f, StrokeCap.Round)
                }
            }
            // 放大镜
            3 -> {
                drawCircle(
                    color = c,
                    radius = w * 0.28f,
                    center = Offset(w * 0.44f, h * 0.42f),
                    style = thin,
                )
                drawLine(c, Offset(w * 0.64f, h * 0.62f), Offset(w * 0.88f, h * 0.88f), 2.9f, StrokeCap.Round)
            }
            // 唱片 + 播放
            4 -> {
                drawCircle(c.copy(alpha = 0.75f), radius = w * 0.40f, center = Offset(w * 0.5f, h * 0.5f), style = thin)
                drawCircle(c, radius = w * 0.10f, center = Offset(w * 0.5f, h * 0.5f))
            }
            // 商店：向下箭头 + 底托（下载 / 安装）
            6 -> {
                drawLine(c, Offset(w * 0.5f, h * 0.10f), Offset(w * 0.5f, h * 0.60f), 2.9f, StrokeCap.Round)
                drawLine(c, Offset(w * 0.26f, h * 0.38f), Offset(w * 0.5f, h * 0.62f), 2.9f, StrokeCap.Round)
                drawLine(c, Offset(w * 0.74f, h * 0.38f), Offset(w * 0.5f, h * 0.62f), 2.9f, StrokeCap.Round)
                drawLine(c, Offset(w * 0.18f, h * 0.84f), Offset(w * 0.82f, h * 0.84f), 2.9f, StrokeCap.Round)
            }
            // 更多：三点
            else -> {
                for (i in 0 until 3) {
                    drawCircle(c, radius = w * 0.10f, center = Offset(w * (0.24f + i * 0.26f), h * 0.5f))
                }
            }
        }
    }
}

private fun boardTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
}

// 便于在 composable 里做透明度派生
private fun HomeSkin.assertAccent(alpha: Float): Color = accent.copy(alpha = alpha)
