/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.app.activity

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import com.liuxiansheng.lyricon.app.coui.CouiSettingsShell
import com.liuxiansheng.lyricon.app.coui.CouiTopBarTextButton
import com.liuxiansheng.lyricon.app.coui.CouiUiState
import com.liuxiansheng.lyricon.app.coui.couiCard
import com.liuxiansheng.lyricon.app.live.CoverStore
import com.liuxiansheng.lyricon.app.live.DailyStat
import com.liuxiansheng.lyricon.app.live.ListenPoster
import com.liuxiansheng.lyricon.app.live.ListenRecord
import com.liuxiansheng.lyricon.app.live.ListenRecordsStore
import com.liuxiansheng.lyricon.app.live.ListenStats
import com.liuxiansheng.lyricon.app.live.RecordTotals
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 听歌记录页（按 docs\设计稿\听歌记录页-设计稿.html 实现，1dp = 3px / 1sp = 3px）。
 *
 * 结构：页头 → 今日卡（首数 / 时长 / 进度条）→ 累计总览卡（三数字 + 范围 + 七天柱状）
 *      → 按日期分组的明细列表（今天默认展开，其余点标题展开）
 *      → 底部大胶囊「生成听歌海报 ›」
 *
 * 口径（2026-11-16 拍板：明细不清空）：
 *  - 明细存 SQLite（ListenRecordDb），按天归档保留 365 天，单日最多 300 条
 *  - 每条记录都还在，所以历史分组能展开看逐条，不再是"只一行摘要"
 *  - 累计数字来自 totals / daily，只增不减；清空分「只清今天」和「连累计一起清」两个入口
 *  - 本页只读不控：记录条不接播放跳转
 *  - 颜色一律走 accentOf() / cardBgOf() / subColorOf()，跟随皮肤，不硬编紫色
 */
@Composable
fun ListenRecordsScreen(onBack: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<ListenRecord>>(emptyList()) }
    var daily by remember { mutableStateOf<List<DailyStat>>(emptyList()) }
    var totals by remember { mutableStateOf(RecordTotals.EMPTY) }
    var showClearDialog by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(ListenStats.RANGE_WEEK) }
    var poster by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var posterUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var toast by remember { mutableStateOf("") }
    // 长按浮出删除胶囊：记这一条的 key，同一时刻只有一条浮出
    var revealedKey by remember { mutableStateOf<String?>(null) }
    // 海报要在后台拉背景图，不能在点按钮那一刻卡主线程
    val scope = rememberCoroutineScope()
    // 海报「每天换背景」：默认关闭，关闭时永远用内置夜色
    var bgDaily by remember { mutableStateOf(ListenPoster.dailyBgEnabled(context)) }

    val stats = remember(records, daily, totals, range) {
        ListenStats.build(daily, totals, records, range)
    }
    val todayKey = remember { dayKeyNow() }
    val todayRecords = remember(records, todayKey) {
        records.filter { dayKeyOf(it.time) == todayKey }
    }
    val todayMs = remember(todayRecords) { todayRecords.sumOf { it.listenedMs } }
    val todayMinutes = (todayMs / 60_000L).toInt()
    val goalProgress = (todayMinutes.toFloat() / 60f).coerceIn(0f, 1f)
    // 明细不清空：按日期分组，新的在前；每组可展开看逐条
    val groups = remember(records) {
        records.groupBy { dayKeyOf(it.time) }.toList().sortedByDescending { it.first }
    }
    // 今天默认展开，其余默认收起
    var expandedDays by remember { mutableStateOf(setOf(todayKey)) }

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        // 明细不清空：这页读全量（按日期分组），首页那套"只看今天"的入口不受影响
        records = ListenRecordsStore.loadAll(context)
        daily = ListenRecordsStore.loadDaily(context)
        totals = ListenRecordsStore.loadTotals(context)
    }

    SkinTheme {
        val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
        androidx.compose.runtime.SideEffect {
            val window = (context as android.app.Activity).window
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !isDark
        }
        com.liuxiansheng.lyricon.app.compose.effect.AppBackground(isDarkTheme = isDark) {
            val recordsBody: @Composable () -> Unit = {
                // ——— 页头：返回 + 标题 + 副标题 + 清空 + 齿轮（凝光下由顶栏接管） ———
                if (!CouiUiState.enabled.value) Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("‹", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurface)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "听歌记录",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "今日 ${todayRecords.size} 首 · 累计不丢 · 明细按天留",
                            fontSize = 12.sp,
                            color = subColorOf(isDark),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (records.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        SmallPill(
                            text = "清空",
                            isDark = isDark,
                            onClick = { showClearDialog = true },
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenSettings),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⚙", fontSize = 17.sp, color = subColorOf(isDark))
                    }
                }

                // ——— 今日卡 ———
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                        .then(
                            if (CouiUiState.enabled.value) {
                                Modifier
                                    .couiCard(
                                        dark = isDark,
                                        corner = 20.dp,
                                        base = if (isDark) Color(0xCC262626) else Color(0xA8FFFFFF),
                                        glowTint = accentOf(),
                                    )
                                    .border(1.dp, borderOf(isDark), RoundedCornerShape(20.dp))
                            } else {
                                Modifier
                                    .background(cardBgOf(isDark), RoundedCornerShape(18.dp))
                                    .border(1.dp, borderOf(isDark), RoundedCornerShape(18.dp))
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "今日",
                                fontSize = 10.sp,
                                color = subColorOf(isDark),
                            )
                            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                                Text(
                                    text = "${todayRecords.size}",
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "首",
                                    fontSize = 12.sp,
                                    color = subColorOf(isDark),
                                    modifier = Modifier.padding(bottom = 7.dp),
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "$todayMinutes",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "分钟",
                                fontSize = 12.sp,
                                color = subColorOf(isDark),
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    // 进度条：目标 60 分钟
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(borderOf(isDark).copy(alpha = 0.5f)),
                    ) {
                        if (goalProgress > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(goalProgress)
                                    .fillMaxHeight()
                                    .background(accentOf(), RoundedCornerShape(999.dp)),
                            )
                        }
                    }
                    Text(
                        text = "目标 60 分钟 · ${(goalProgress * 100).toInt()}%",
                        fontSize = 10.sp,
                        color = subColorOf(isDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }

                // ——— 累计总览卡：三数字 + 范围胶囊 + 七天柱状 ———
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                        .then(
                            if (CouiUiState.enabled.value) {
                                Modifier
                                    .couiCard(
                                        dark = isDark,
                                        corner = 20.dp,
                                        base = if (isDark) Color(0xCC262626) else Color(0xA8FFFFFF),
                                        glowTint = accentOf(),
                                    )
                                    .border(1.dp, borderOf(isDark), RoundedCornerShape(20.dp))
                            } else {
                                Modifier
                                    .background(cardBgOf(isDark), RoundedCornerShape(18.dp))
                                    .border(1.dp, borderOf(isDark), RoundedCornerShape(18.dp))
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "累计总览",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = subColorOf(isDark),
                            modifier = Modifier.weight(1f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            RangeButton("7 天", range == ListenStats.RANGE_WEEK, isDark) {
                                range = ListenStats.RANGE_WEEK
                            }
                            RangeButton("30 天", range == ListenStats.RANGE_MONTH, isDark) {
                                range = ListenStats.RANGE_MONTH
                            }
                            RangeButton("全部", range == ListenStats.RANGE_ALL, isDark) {
                                range = ListenStats.RANGE_ALL
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        StatCell(stats.totalLabel, "累计收听", isDark, Modifier.weight(1.3f))
                        StatCell("${stats.playCount}", "累计播放", isDark, Modifier.weight(1f))
                        StatCell("${stats.activeDays}", "打卡天数", isDark, Modifier.weight(1f))
                    }
                    WeekBars(daily = daily, isDark = isDark)
                    if (stats.topSongs.isNotEmpty()) {
                        Text(
                            text = "单曲循环：" + stats.topSongs.first().first,
                            fontSize = 10.sp,
                            color = subColorOf(isDark),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (stats.topArtists.isNotEmpty()) {
                        Text(
                            text = "最爱歌手：" + stats.topArtists.take(2).joinToString(" · ") { it.first },
                            fontSize = 10.sp,
                            color = subColorOf(isDark),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }

                // ——— 明细列表：按日期分组，今天默认展开，其余点标题可展开 ———
                if (groups.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "还没有听歌记录",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "听够一半才记 · 明细按天保留 · 累计不丢",
                            fontSize = 11.sp,
                            color = subColorOf(isDark),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                } else {
                    // 明细区：最多露出 5 条的高度，剩下的在这里面滚；内容少时自动收缩
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                    groups.forEach { (day, dayRecords) ->
                        val expanded = day in expandedDays
                        val groupMs = dayRecords.sumOf { it.listenedMs }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clickable {
                                    expandedDays = if (expanded) expandedDays - day else expandedDays + day
                                }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = dayPrefixCn(day),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = subColorOf(isDark),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${dayRecords.size} 首 · ${durationCn(groupMs)}",
                                fontSize = 10.sp,
                                color = subColorOf(isDark),
                            )
                            Text(
                                text = if (expanded) "  ⌃" else "  ⌄",
                                fontSize = 10.sp,
                                color = subColorOf(isDark),
                            )
                        }
                        if (expanded) {
                            // 时间轴（细线 + 圆点）：每组各画一条
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.matchParentSize()) {
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 15.dp, top = 30.dp, bottom = 30.dp)
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(borderOf(isDark).copy(alpha = 0.55f)),
                                    )
                                }
                                Column {
                                    dayRecords.forEach { record ->
                                        val key = recordKey(record)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(30.dp)
                                                    .height(60.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .background(
                                                            subColorOf(isDark).copy(alpha = 0.55f),
                                                            CircleShape,
                                                        ),
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 16.dp),
                                            ) {
                                                RecordRow(
                                                    record = record,
                                                    isDark = isDark,
                                                    revealed = revealedKey == key,
                                                    onLongPress = { revealedKey = key },
                                                    onDismiss = { if (revealedKey == key) revealedKey = null },
                                                    onDelete = {
                                                        ListenRecordsStore.remove(context, record)
                                                        records = records.filterNot { recordKey(it) == key }
                                                        revealedKey = null
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                }

                if (toast.isNotBlank()) {
                    Text(
                        text = toast,
                        fontSize = 11.sp,
                        color = subColorOf(isDark),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }

                // ——— 底部大胶囊：生成听歌海报 ———
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp)
                        .height(48.dp)
                        .background(accentOf().copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                        .border(1.dp, accentOf().copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .clickable {
                            if (stats.playCount == 0) {
                                toast = "先听几首歌，再生成报告"
                            } else {
                                toast = "正在生成…"
                                scope.launch {
                                    val bmp = runCatching { ListenPoster.build(context, stats) }.getOrNull()
                                    if (bmp != null) {
                                        poster = bmp
                                        posterUri = null
                                        toast = ""
                                    } else {
                                        toast = "生成失败，请重试"
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "生成听歌海报 ›",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = accentOf(),
                    )
                }
            }
            if (CouiUiState.enabled.value) {
                CouiSettingsShell(
                    title = "听歌记录",
                    actions = {
                        CouiTopBarTextButton("‹ 返回") { onBack() }
                        if (records.isNotEmpty()) {
                            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                            CouiTopBarTextButton("清空") { showClearDialog = true }
                        }
                    },
                ) { recordsBody() }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) { recordsBody() }
            }
        }
    }

    // 清空两个入口：只清今天明细（累计保留）/ 连累计一起清
    if (showClearDialog) {
        Dialog(onDismissRequest = { showClearDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15171C), RoundedCornerShape(18.dp))
                    .padding(18.dp),
            ) {
                Text(
                    text = "清空听歌记录",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = "明细从改版起一直按天保留，清空分两种：",
                    fontSize = 11.sp,
                    color = Color(0xFF9AA0AC),
                    modifier = Modifier.padding(top = 6.dp),
                )
                ClearOption(
                    title = "只清今天的 ${todayRecords.size} 条明细",
                    desc = "累计首数、累计时长、每日汇总都保留",
                    onClick = {
                        ListenRecordsStore.clearToday(context)
                        records = ListenRecordsStore.loadAll(context)
                        revealedKey = null
                        expandedDays = setOf(todayKey)
                        showClearDialog = false
                        toast = "今天的明细已清空，累计保留"
                    },
                )
                ClearOption(
                    title = "清空全部（含累计）",
                    desc = "明细、每日汇总、累计数字一起清零，不可恢复",
                    danger = true,
                    onClick = {
                        ListenRecordsStore.clearAll(context)
                        records = emptyList()
                        daily = emptyList()
                        totals = RecordTotals.EMPTY
                        revealedKey = null
                        expandedDays = emptySet()
                        showClearDialog = false
                        toast = "已清空全部记录"
                    },
                )
                Text(
                    text = "取消",
                    fontSize = 12.sp,
                    color = Color(0xFF9AA0AC),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clickable { showClearDialog = false },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    poster?.let { bmp ->
        Dialog(onDismissRequest = { poster = null }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF101014).copy(alpha = 0.96f), RoundedCornerShape(18.dp))
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "听歌报告海报",
                    modifier = Modifier
                        .width(260.dp)
                        .aspectRatio(1080f / 1620f),
                )
                // 背景开关：点一下切换并立刻重画（默认关闭，用内置夜色）
                Text(
                    text = if (bgDaily) "每天换背景：开 · 点此关闭" else "每天换背景：关 · 点此开启",
                    fontSize = 10.sp,
                    color = Color(0xFF9AA0AC),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable {
                            val next = !bgDaily
                            bgDaily = next
                            ListenPoster.setDailyBgEnabled(context, next)
                            toast = "正在重新生成…"
                            scope.launch {
                                val nb = runCatching { ListenPoster.build(context, stats) }.getOrNull()
                                if (nb != null) {
                                    poster = nb
                                    posterUri = null
                                    toast = ""
                                } else {
                                    toast = "生成失败，请重试"
                                }
                            }
                        },
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PosterAction("关闭") { poster = null }
                    PosterAction("保存到相册") {
                        val uri = ListenPoster.saveToGallery(context, bmp, stats)
                        if (uri != null) {
                            posterUri = uri
                            toast = "已保存到相册 Pictures/词岛"
                            poster = null
                        } else {
                            toast = "保存失败，请重试"
                        }
                    }
                    PosterAction("分享") {
                        val uri = posterUri ?: ListenPoster.saveToGallery(context, bmp, stats)
                        if (uri != null) {
                            posterUri = uri
                            runCatching { ListenPoster.share(context, uri) }
                        } else {
                            toast = "分享失败，请重试"
                        }
                    }
                }
            }
        }
    }
}

/** 七天柱状：读 daily 最近 7 天（含今天），最高一根用强调色高亮；全 0 时不画柱、不除零。 */
@Composable
private fun WeekBars(daily: List<DailyStat>, isDark: Boolean) {
    val days = remember(daily) { weekSeries(daily) }
    val maxValue = days.maxOfOrNull { it.second } ?: 0L
    if (maxValue > 0L) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "最高 $maxValue 首",
                fontSize = 10.sp,
                color = subColorOf(isDark).copy(alpha = 0.7f),
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        days.forEach { (label, value) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ratio = if (maxValue <= 0L) 0f else value.toFloat() / maxValue.toFloat()
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (value > 0L) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .height((ratio * 48f).dp.coerceAtLeast(3.dp))
                                .background(
                                    if (value == maxValue) accentOf()
                                    else subColorOf(isDark).copy(alpha = 0.34f),
                                    RoundedCornerShape(
                                        topStart = 5.dp, topEnd = 5.dp,
                                        bottomStart = 3.dp, bottomEnd = 3.dp,
                                    ),
                                ),
                        )
                    }
                }
                Text(
                    text = label,
                    fontSize = 9.sp,
                    color = subColorOf(isDark),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun RangeButton(text: String, selected: Boolean, isDark: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) accentOf() else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .border(
                1.dp,
                if (selected) Color.Transparent else borderOf(isDark),
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = if (selected) Color.White else subColorOf(isDark),
        )
    }
}

@Composable
private fun StatCell(number: String, caption: String, isDark: Boolean, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = number,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = caption,
            fontSize = 10.sp,
            color = subColorOf(isDark),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 单条记录：44dp 封面色块（歌名哈希取 6 组渐变之一）+ 歌名 + 副行 + 时刻右对齐。
 * 长按 → 卡片上浮 + 强调色描边 + 右上浮出红色「删除」胶囊；点胶囊才真删。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordRow(
    record: ListenRecord,
    isDark: Boolean,
    revealed: Boolean,
    onLongPress: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val lift by animateFloatAsState(if (revealed) 4f else 0f, label = "recordLift")
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-lift).dp)
                .then(
                    if (CouiUiState.enabled.value) {
                        Modifier
                            .couiCard(
                                dark = isDark,
                                corner = 16.dp,
                                base = if (isDark) Color(0xCC262626) else Color(0xA8FFFFFF),
                                glowTint = accentOf(),
                            )
                    } else {
                        Modifier.background(cardBgOf(isDark), RoundedCornerShape(14.dp))
                    }
                )
                .border(
                    1.dp,
                    if (revealed) accentOf().copy(alpha = 0.65f) else borderOf(isDark),
                    RoundedCornerShape(if (CouiUiState.enabled.value) 16.dp else 14.dp),
                )
                .combinedClickable(
                    onClick = { if (revealed) onDismiss() },
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 12.dp)
                .height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverBlock(
                title = record.title,
                artist = record.artist,
                cover = record.cover,
                isDark = isDark,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                val sub = buildString {
                    if (record.artist.isNotBlank()) append(record.artist)
                    val app = ListenRecordsStore.appLabel(record.packageName)
                    if (record.packageName.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(app)
                    }
                    val heard = ListenRecordsStore.listenedLabel(record.listenedMs)
                    if (heard.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(heard)
                    }
                }
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        fontSize = 11.sp,
                        color = subColorOf(isDark),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = timeLabel(record.time),
                fontSize = 11.sp,
                color = subColorOf(isDark),
            )
        }
        if (revealed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-10).dp)
                    .background(Color(0xFFFF5A5F), RoundedCornerShape(999.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "删除", fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

/**
 * 封面：有真实封面存档（filesDir/covers）就显示专辑图，没有才退回"歌名哈希取色"的渐变占位。
 * 老记录虽然 DB 里没记文件名，只要这首歌后来又听过一次、封面存过，这里也能自动补上。
 */
@Composable
private fun CoverBlock(title: String, artist: String, cover: String, isDark: Boolean) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 44.dp.roundToPx() }
    val name = remember(cover, title, artist) {
        when {
            cover.isNotBlank() -> cover
            title.isNotBlank() -> CoverStore.nameOf(title, artist)
            else -> ""
        }
    }
    val bmp = remember(name, sizePx) {
        if (name.isBlank()) null else CoverStore.bitmap(context, name, sizePx)
    }
    val shape = RoundedCornerShape(10.dp)
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(shape),
        )
        return
    }
    val colors = remember(title) { coverGradient(title) }
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Brush.linearGradient(colors), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "♪", fontSize = 17.sp, color = Color.White.copy(alpha = 0.28f))
    }
}

private fun coverGradient(title: String): List<Color> {
    val palettes = listOf(
        listOf(Color(0xFF3B2E7A), Color(0xFF6C4CF1)),
        listOf(Color(0xFF1F3A5F), Color(0xFF3E7BA8)),
        listOf(Color(0xFF123A36), Color(0xFF2F8E7C)),
        listOf(Color(0xFF4A1F3D), Color(0xFFA64B7A)),
        listOf(Color(0xFF2A2F45), Color(0xFF5B6A8C)),
        listOf(Color(0xFF3A2A1E), Color(0xFF8C6A44)),
    )
    val raw = title.hashCode()
    val index = (if (raw == Int.MIN_VALUE) 0 else abs(raw)) % palettes.size
    return palettes[index]
}

@Composable
private fun SmallPill(text: String, isDark: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(cardBgOf(isDark), RoundedCornerShape(10.dp))
            .border(1.dp, borderOf(isDark), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 清空弹窗里的一个选项（危险操作用红字）。 */
@Composable
private fun ClearOption(
    title: String,
    desc: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) Color(0xFF3A1D1F) else Color(0xFF23262E))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (danger) Color(0xFFFF5A5F) else Color.White,
        )
        Text(
            text = desc,
            fontSize = 10.sp,
            color = Color(0xFF9AA0AC),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun PosterAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 12.sp, color = Color(0xFFEDF1F7))
    }
}

@androidx.compose.runtime.Composable
private fun subColorOf(isDark: Boolean): Color {
    val skin = LocalHomeSkin.current
    if (skin != null) return skin.textSecondary
    return if (isDark) Color.White.copy(alpha = 0.55f) else Color(0xFF334155).copy(alpha = 0.75f)
}

@androidx.compose.runtime.Composable
private fun cardBgOf(isDark: Boolean): Color {
    val skin = LocalHomeSkin.current
    if (skin != null) return skin.cardBg
    return if (isDark) Color(0xFF202024).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.32f)
}

@androidx.compose.runtime.Composable
private fun borderOf(isDark: Boolean): Color {
    val skin = LocalHomeSkin.current
    if (skin != null) return skin.cardBorder
    return if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.70f)
}

/** 强调色：跟随皮肤（黑胶金 / 晨雾蓝灰 / 赛博青），默认走原蓝 */
@androidx.compose.runtime.Composable
private fun accentOf(): Color = LocalHomeSkin.current?.accent ?: Color(0xFF4F7CFF)

private fun recordKey(record: ListenRecord): String =
    record.title + "|" + record.artist + "|" + record.time

private fun dayKeyNow(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

/** 时间戳落在哪个本地日期（yyyy-MM-dd），分组用。 */
private fun dayKeyOf(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))

/** "2026-11-16" → "11 月 16 日" */
private fun dateCn(day: String): String {
    val parts = day.split("-")
    if (parts.size < 3) return day
    val month = parts[1].toIntOrNull() ?: return day
    val date = parts[2].toIntOrNull() ?: return day
    return "${month} 月 ${date} 日"
}

/** 时长文案：不足 1 小时给分钟，超过给「X 小时 Y 分」，整点省略分钟 */
private fun durationCn(ms: Long): String {
    val minutes = ms / 60_000L
    if (minutes <= 0L) return "0 分钟"
    if (minutes < 60L) return "$minutes 分钟"
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (rest == 0L) "$hours 小时" else "$hours 小时 $rest 分"
}

/** 历史摘要一行：昨天/今天前缀 · 日期 · 首数 · 时长 · 最爱《歌》；没有 topSong 就省略最后一段，不留空括号 */
private fun historySummary(stat: DailyStat): String {
    val builder = StringBuilder()
    builder.append(dayPrefixCn(stat.day))
    if (stat.count > 0) builder.append(" · ${stat.count} 首")
    builder.append(" · ${durationCn(stat.ms)}")
    if (stat.topSong.isNotBlank()) builder.append(" · 最爱《${stat.topSong}》")
    return builder.toString()
}

/** 「昨天 · 11 月 15 日」/「11 月 13 日」 */
private fun dayPrefixCn(day: String): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val body = dateCn(day)
    val today = fmt.format(Date())
    val yesterday = fmt.format(Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000))
    return when (day) {
        today -> "今天 · $body"
        yesterday -> "昨天 · $body"
        else -> body
    }
}

/** daily 最近 7 天（含今天）→ (星期标签, 首数)，缺失的日期记 0 */
private fun weekSeries(daily: List<DailyStat>): List<Pair<String, Long>> {
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val out = ArrayList<Pair<String, Long>>(7)
    for (i in 6 downTo 0) {
        val dayMs = today.timeInMillis - i * 24L * 60 * 60 * 1000
        val key = fmt.format(Date(dayMs))
        val cal = Calendar.getInstance().apply { timeInMillis = dayMs }
        val index = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
        var count = 0L
        for (stat in daily) {
            if (stat.day == key) count += stat.count.toLong()
        }
        out.add(labels[index] to count)
    }
    return out
}

private fun timeLabel(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
