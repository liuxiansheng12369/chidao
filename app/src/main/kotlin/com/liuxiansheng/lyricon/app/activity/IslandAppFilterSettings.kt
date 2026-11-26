/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现（语义对齐「词幕」超级岛歌词应用过滤）。
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.app.activity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.liuxiansheng.lyricon.app.coui.CouiSlotDialog
import com.liuxiansheng.lyricon.app.coui.CouiUiState
import com.liuxiansheng.lyricon.app.util.MasterPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.Collator
import java.util.Locale

/** 应用过滤模式：0 关闭 / 1 白名单 / 2 黑名单（与 xposed 侧 IslandLyricAppFilter 一致）。 */
private const val FILTER_MODE_OFF = 0
private const val FILTER_MODE_WHITE = 1
private const val FILTER_MODE_BLACK = 2

/** 没有启动图标、但确实会播歌的系统应用，别在列表里漏掉。 */
private val EXTRA_MEDIA_PACKAGES = setOf(
    "com.miui.player",
    "com.huawei.music",
    "com.android.music",
    "com.google.android.music",
)

private data class AppEntry(val packageName: String, val label: String)

/** 包名串解析：支持中英文逗号/分号混用。 */
private fun parsePkgList(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(",", "，", ";", "；").map { it.trim() }.filter { it.isNotBlank() }.toSet()
}

private fun joinPkgList(list: Collection<String>): String = list.joinToString(",")

/**
 * 超级岛基础设置里的三行：过滤模式 +（按模式显示）白名单/黑名单入口。
 * 只负责写 prefs；xposed 侧每次判定都读同一份 prefs，改完即时生效。
 */
@Composable
fun IslandLyricAppFilterRows(context: Context) {
    var mode by remember {
        mutableStateOf(MasterPrefs.getInt(context, MasterPrefs.KEY_ISLAND_LYRIC_APP_FILTER_MODE, FILTER_MODE_OFF))
    }
    var whiteRaw by remember {
        mutableStateOf(MasterPrefs.getString(context, MasterPrefs.KEY_ISLAND_LYRIC_APP_FILTER_WHITE, "").orEmpty())
    }
    var blackRaw by remember {
        mutableStateOf(MasterPrefs.getString(context, MasterPrefs.KEY_ISLAND_LYRIC_APP_FILTER_BLACK, "").orEmpty())
    }
    var picking by remember { mutableStateOf(0) }

    PrefChoiceRow(
        title = "歌词应用过滤",
        summary = when (mode) {
            FILTER_MODE_WHITE -> "仅白名单内的应用能在超级岛显示歌词"
            FILTER_MODE_BLACK -> "黑名单内的应用不显示歌词"
            else -> "关闭：所有应用都按原规则出歌词"
        },
        options = listOf("关闭", "仅白名单", "黑名单"),
        selectedIndex = mode,
        onSelectedIndexChange = { i ->
            mode = i
            MasterPrefs.setInt(context, MasterPrefs.KEY_ISLAND_LYRIC_APP_FILTER_MODE, i)
        }
    )

    if (mode != FILTER_MODE_OFF) {
        PrefSwitch(
            context = context,
            key = MasterPrefs.KEY_ISLAND_LYRIC_APP_FILTER_NO_ISLAND,
            default = false,
            title = "不生成超级岛",
            summary = "被过滤的应用播媒体时连超级岛都不建；关着就只是不出歌词，系统原生岛照旧"
        )
    }

    if (mode == FILTER_MODE_WHITE) {
        val count = parsePkgList(whiteRaw).size
        PrefArrow(
            title = "白名单应用",
            summary = if (count == 0) "未选择 · 默认放行常见音乐/播放器" else "已选 $count 个 · 只有这些应用出歌词",
            onClick = { picking = FILTER_MODE_WHITE }
        )
    }
    if (mode == FILTER_MODE_BLACK) {
        val count = parsePkgList(blackRaw).size
        PrefArrow(
            title = "黑名单应用",
            summary = if (count == 0) "未选择 · 当前不屏蔽任何应用" else "已选 $count 个 · 这些应用不出歌词",
            onClick = { picking = FILTER_MODE_BLACK }
        )
    }

    when (picking) {
        FILTER_MODE_WHITE -> AppFilterPickerDialog(
            context = context,
            title = "选择白名单应用（仅这些显示歌词）",
            selectedRaw = whiteRaw,
            onToggle = { pkg ->
                val next = parsePkgList(whiteRaw).let { if (pkg in it) it - pkg else it + pkg }
                whiteRaw = joinPkgList(next)
                MasterPrefs.setString(context, MasterPrefs.KEY_ISLAND_LYRIC_APP_FILTER_WHITE, whiteRaw)
            },
            onDismiss = { picking = 0 }
        )
        FILTER_MODE_BLACK -> AppFilterPickerDialog(
            context = context,
            title = "选择黑名单应用（这些不显示歌词）",
            selectedRaw = blackRaw,
            onToggle = { pkg ->
                val next = parsePkgList(blackRaw).let { if (pkg in it) it - pkg else it + pkg }
                blackRaw = joinPkgList(next)
                MasterPrefs.setString(context, MasterPrefs.KEY_ISLAND_LYRIC_APP_FILTER_BLACK, blackRaw)
            },
            onDismiss = { picking = 0 }
        )
    }
}

/** 应用勾选弹窗：搜索 + 图标 + 名称 + 勾选态，点行即写 prefs（实时生效）。 */
@Composable
private fun AppFilterPickerDialog(
    context: Context,
    title: String,
    selectedRaw: String,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val selected = remember(selectedRaw) { parsePkgList(selectedRaw) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadCandidateApps(context) }
        loading = false
    }

    val collator = remember { Collator.getInstance(Locale.CHINA) }
    // 已勾选的排最前面（各自再按名称排），未勾选的跟在后面 —— 免得找个已选的还要往下翻。
    val shown = remember(apps, query, selectedRaw) {
        val q = query.trim()
        val filtered = apps.filter {
            q.isEmpty() || it.label.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        }
        val (picked, others) = filtered.partition { it.packageName in selected }
        picked.sortedWith(compareBy(collator) { it.label }) +
            others.sortedWith(compareBy(collator) { it.label })
    }

    val fg = MiuixTheme.colorScheme.onSurface
    val accent = Color(0xFF4F7CFF)

    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 搜索框
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .background(fg.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                if (query.isEmpty()) {
                    Text("搜索应用名或包名", fontSize = 13.sp, color = fg.copy(alpha = 0.45f))
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = fg),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (selected.isNotEmpty()) {
                Text(
                    text = "已选 ${selected.size} 个，排在最上面",
                    fontSize = 12.sp,
                    color = accent,
                    modifier = Modifier.padding(start = 18.dp, bottom = 4.dp)
                )
            }

            when {
                loading -> PickerHint("正在读取已安装应用…", fg)
                shown.isEmpty() -> PickerHint("没有匹配的应用", fg)
                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    shown.forEach { app ->
                        val checked = app.packageName in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(app.packageName) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app.packageName, Modifier.size(34.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    fontSize = 14.sp,
                                    color = fg,
                                    maxLines = 1
                                )
                                Text(
                                    app.packageName,
                                    fontSize = 11.sp,
                                    color = fg.copy(alpha = 0.45f),
                                    maxLines = 1
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        if (checked) accent else Color.Transparent,
                                        RoundedCornerShape(11.dp)
                                    )
                                    .then(
                                        if (!checked) Modifier.border(
                                            1.dp, fg.copy(alpha = 0.2f), RoundedCornerShape(11.dp)
                                        ) else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (checked) {
                                    Text("✓", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (CouiUiState.enabled.value) {
        CouiSlotDialog(title = title, onDismiss = onDismiss) { body() }
    } else {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            androidx.compose.material.Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 560.dp)
                    .padding(vertical = 24.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                elevation = 12.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 14.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            title,
                            color = Color.Black,
                            fontSize = 16.sp
                        )
                        Text(
                            "完成",
                            fontSize = 15.sp,
                            color = accent,
                            modifier = Modifier
                                .clickable { onDismiss() }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    body()
                }
            }
        }
    }
}

@Composable
private fun PickerHint(text: String, fg: Color) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = fg.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp)
    )
}

/** 应用图标（后台加载，拿不到时显示灰块）。 */
@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val drawable = context.packageManager.getApplicationIcon(packageName)
                    (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap()
                }.getOrNull()
            }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            painter = BitmapPainter(bmp.asImageBitmap()),
            contentDescription = packageName,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(
                MiuixTheme.colorScheme.surface,
                RoundedCornerShape(8.dp)
            )
        )
    }
}

/**
 * 候选应用：能启动的应用 + 少量无图标的系统音乐。
 * 用 getInstalledPackages 一次拿全（QUERY_ALL_PACKAGES 已声明），比 queryIntentActivities 全。
 */
@Suppress("DEPRECATION")
private fun loadCandidateApps(context: Context): List<AppEntry> {
    val appContext = context.applicationContext ?: context
    val pm = appContext.packageManager
    val out = ArrayList<AppEntry>()
    val packages = runCatching { pm.getInstalledPackages(0) }.getOrNull() ?: emptyList()

    packages.forEach { info ->
        val pkg = info.packageName ?: return@forEach
        val launchable = runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        if (!launchable && pkg !in EXTRA_MEDIA_PACKAGES) return@forEach
        val label = runCatching {
            info.applicationInfo?.loadLabel(pm)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        out.add(AppEntry(pkg, label))
    }

    // 兜底补一遍"能启动但 getInstalledPackages 没给 label"的边缘情况已在上面处理，
    // 这里只保留去重（同包名只出现一次）。
    return out.distinctBy { it.packageName }
}
