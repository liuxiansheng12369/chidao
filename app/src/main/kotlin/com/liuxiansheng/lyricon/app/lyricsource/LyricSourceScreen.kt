/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 *
 * LyricSourceScreen：歌词源管理页（MainActivity 内部二级页）。
 * - 扫描展示已安装歌词 provider 插件（图标 / 名称 / 版本 / 作者 / 描述 / 标签）
 * - 顶部切换列表视图模式（精简 / 完整）
 * - 底部"歌词服务"设置区：暂停防冻结开关（暂停时保持播放会话活跃防冻结，
 *   避免被系统冻结导致重启状态栏后歌词数据不通）+ 保持时长。
 */

package com.liuxiansheng.lyricon.app.lyricsource

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.liuxiansheng.lyricon.app.R
import com.liuxiansheng.lyricon.app.compose.icon.GeminiColor
import androidx.core.view.WindowCompat
import com.liuxiansheng.lyricon.app.activity.NumberInputPreference
import com.liuxiansheng.lyricon.app.activity.PrefSwitch
import com.liuxiansheng.lyricon.app.activity.SectionCard
import com.liuxiansheng.lyricon.app.activity.SectionTitle
import com.liuxiansheng.lyricon.app.coui.CouiSettingsShell
import com.liuxiansheng.lyricon.app.coui.CouiUiState
import com.liuxiansheng.lyricon.app.coui.couiCard
import com.liuxiansheng.lyricon.app.live.NowPlaying
import com.liuxiansheng.lyricon.app.live.NowPlayingReader
import com.liuxiansheng.lyricon.app.util.MasterPrefs
import com.liuxiansheng.lyricon.app.util.ProviderPrefsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 自检开关（默认关）：只影响歌词源页要不要做 su 读取 / root 直读这类深度诊断。 */
private const val KEY_DIAG = "lyricsource_diag_enabled"

/**
 * 歌词源管理页。
 */
@Composable
fun LyricSourceScreen(onBack: () -> Unit, onOpenStore: () -> Unit = {}) {
    val context = LocalContext.current
    var listStyle by remember {
        mutableStateOf(
            MasterPrefs.getInt(context, MasterPrefs.KEY_LYRIC_SOURCE_LIST_STYLE, LyricSourceViewMode.SHORT)
        )
    }
    var report by remember { mutableStateOf<LyricSourceScanReport?>(null) }
    var playing by remember { mutableStateOf<NowPlaying?>(null) }
    var usage by remember { mutableStateOf<Map<String, SourceUsage.Entry>>(emptyMap()) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    // 自检开关默认关闭：只管下方那块诊断面板显不显示。
    // 扫描能力（含 root 直读、解析被隐藏的源）始终打开 —— 源有没有、装没装、变没变，靠的就是它。
    var diagOn by remember { mutableStateOf(MasterPrefs.getBoolean(context, KEY_DIAG, false)) }

    // 扫描 + 认证 + 分组（后台线程）。
    // root 直读、被隐藏源解析始终执行；诊断细项（疑似清单/第三方清单/当前播放/诊断日志）只在自检打开时采集。
    LaunchedEffect(refreshKey, diagOn) {
        // 开关在「设置 - 调试」里，重扫前先对齐一次，避免还用着上次的值
        val saved = MasterPrefs.getBoolean(context, KEY_DIAG, false)
        if (saved != diagOn) {
            diagOn = saved
            return@LaunchedEffect
        }
        loading = true
        report = withContext(Dispatchers.IO) { LyricSourceScanner.scanReport(context, deep = diagOn) }
        playing = withContext(Dispatchers.IO) { NowPlayingReader.read(context) }
        usage = withContext(Dispatchers.IO) { SourceUsage.snapshot(context) }
        loading = false
    }

    // 页面开着时每 2 秒刷一次运行态：谁在推词、推的是哪首歌、这个源多久没动静了
    LaunchedEffect(Unit) {
        while (true) {
            val np = withContext(Dispatchers.IO) { NowPlayingReader.read(context) }
            val t = System.currentTimeMillis()
            playing = np
            nowTick = t
            val pkg = np.providerPackage
            if (pkg.isNotBlank() && np.playing) {
                val last = usage[pkg]?.lastSeenMs ?: 0L
                if (t - last >= SourceUsage.THROTTLE_MS) {
                    val player = np.providerPlayerPackage.takeIf { it.isNotBlank() } ?: np.packageName
                    withContext(Dispatchers.IO) { SourceUsage.recordActive(context, pkg, player, t) }
                    usage = withContext(Dispatchers.IO) { SourceUsage.snapshot(context) }
                }
            }
            delay(2000)
        }
    }
    val categories = report?.categories ?: emptyList()
    val modules = categories.flatMap { it.items }
    val activePkg = playing?.providerPackage.orEmpty()
    // 判断"还在不在推"，看播放器状态的新鲜度（在播时每 0.5 秒随进度刷新），
    // 不能拿 providerAt 当心跳——那个只在切换 provider 时更新，接上 15 分钟也会显示 15 分钟。
    val liveAt = playing?.updatedAt ?: 0L
    val activeFresh = activePkg.isNotBlank() &&
        playing?.playing == true &&
        liveAt > 0L &&
        nowTick - liveAt in 0..5 * 60 * 1000L
    val activeModule = modules.firstOrNull { it.packageName == activePkg }
    // 两个源在 1 分钟内都推过、而且推的是同一个播放器 → 大概率在抢词
    val conflicts = usage.entries
        .filter { nowTick - it.value.lastSeenMs in 0..60_000L }
        .groupBy { it.value.playerPackage }
        .filter { it.key.isNotBlank() && it.value.size > 1 }
        .map { (player, list) -> player to list.map { it.key } }

    BackHandler { onBack() }

    com.liuxiansheng.lyricon.app.activity.SkinTheme {
        val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
        // 状态栏图标按页面背景反色
        SideEffect {
            val window = (context as android.app.Activity).window
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDark
        }
        // 应用全局背景（受"流光背景"开关控制）
        com.liuxiansheng.lyricon.app.compose.effect.AppBackground(
            isDarkTheme = isDark,
        ) {
            val sourceBody: @Composable () -> Unit = {
                    // 照服务看板样式：先来一行"当前活跃 · N 个活跃"，绿点表示有源在推
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "当前活跃",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    if (activeFresh) Color(0xFF34A853) else Color(0xFF94A3B8),
                                    CircleShape,
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (activeFresh) "1 个活跃" else "0 个活跃",
                            fontSize = 12.sp,
                            color = if (isDark) Color.White.copy(alpha = 0.55f) else Color(0xFF64748B),
                        )
                    }
                    LiveSourceCard(
                        playing = playing,
                        module = activeModule,
                        fresh = activeFresh,
                        nowMs = nowTick,
                        context = context,
                    )
                    if (conflicts.isNotEmpty()) {
                        ConflictCard(conflicts = conflicts, modules = modules, nowMs = nowTick, context = context)
                    }
                    // 两种模式都平铺：精简只留名称和状态，完整在同一张卡里展开描述/标签
                    SectionTitle(if (modules.isEmpty()) "已安装歌词源" else "源状态 · ${modules.size}")
                    when {
                        loading -> StatusHint("正在扫描歌词源…")
                        categories.isEmpty() -> StatusHint("未检测到歌词源 provider（需安装并启用歌词模块）")
                        else -> {
                            // 按运行态排序（正在用 → 刚用过 → 用过 → 未推词），不再分组
                            val ordered = modules.sortedWith(
                                compareBy(
                                    { module ->
                                        when (stateOf(module.packageName, activePkg, activeFresh, usage, nowTick)) {
                                            SourceState.ACTIVE -> 0
                                            SourceState.JUST_USED -> 1
                                            SourceState.USED -> 2
                                            SourceState.IDLE -> 3
                                        }
                                    },
                                    { it.label },
                                )
                            )
                            FlatSourceList(
                                modules = ordered,
                                context = context,
                                activePackage = activePkg,
                                activeFresh = activeFresh,
                                usage = usage,
                                nowMs = nowTick,
                                showDetails = listStyle == LyricSourceViewMode.FULL,
                            )
                        }
                    }

                    // 诊断面板：显不显示由「设置 - 调试 - 歌词源自检」决定，页面这里不再放开关
                    if (diagOn) {
                        DiagnosticsCard(
                            report = report,
                            playing = playing,
                            onRefresh = { refreshKey++ },
                        )
                    }
                    ExportCard(
                        context = context,
                        report = report,
                        playing = playing,
                        usage = usage,
                    )
                    Spacer(Modifier.height(20.dp))
            }
            val modeSwitch: @Composable () -> Unit = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewModeSwitch(listStyle) {
                        listStyle = it
                        MasterPrefs.setInt(context, MasterPrefs.KEY_LYRIC_SOURCE_LIST_STYLE, it)
                    }
                    RefreshChip { refreshKey++ }
                }
            }
            if (CouiUiState.enabled.value) {
                CouiSettingsShell(title = "歌词提供者", compactTitle = true, actions = { modeSwitch() }) { sourceBody() }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶栏：标题 + 列表视图切换
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "歌词提供者",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        modeSwitch()
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) { sourceBody() }
                }
            }
        }
    }
}

/** 顶部列表视图切换（精简 / 完整）。 */
@Composable
private fun ViewModeSwitch(current: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ViewModeButton("精简", current == LyricSourceViewMode.SHORT) {
            onChange(LyricSourceViewMode.SHORT)
        }
        ViewModeButton("完整", current == LyricSourceViewMode.FULL) {
            onChange(LyricSourceViewMode.FULL)
        }
    }
}

/** 视图切换小按钮（选中高亮）。 */
@Composable
private fun ViewModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val bg = if (selected) {
        if (isDark) Color(0xFF3D5AFE) else Color(0xFF4F7CFF)
    } else {
        if (isDark) Color(0xFF202024).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.30f)
    }
    val border = if (selected) Color.Transparent else {
        if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.70f)
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 平铺列表：所有源共用一张大卡，行与行用细分隔线，不再分"本地播放器 / 词幕 / 其他"；完整模式在行内展开描述和标签。 */
@Composable
private fun FlatSourceList(
    modules: List<LyricSourceModule>,
    context: Context,
    activePackage: String,
    activeFresh: Boolean,
    usage: Map<String, SourceUsage.Entry>,
    nowMs: Long,
    showDetails: Boolean,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFF0F172A).copy(alpha = 0.06f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(
                if (CouiUiState.enabled.value) {
                    Modifier.couiCard(
                        dark = isDark,
                        corner = 20.dp,
                        base = if (isDark) Color(0xCC262626) else Color(0xA8FFFFFF),
                        glowTint = Color(0xFF2C77F2),
                    )
                } else {
                    Modifier.background(
                        if (isDark) Color(0xFF202024).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.60f),
                        RoundedCornerShape(16.dp),
                    )
                }
            )
            .padding(vertical = 4.dp),
    ) {
        modules.forEachIndexed { index, module ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(dividerColor)
                )
            }
            ModuleCard(
                module = module,
                showDetails = showDetails,
                context = context,
                activePackage = activePackage,
                activeFresh = activeFresh,
                usage = usage,
                nowMs = nowMs,
                flat = true,
            )
        }
    }
}

/** 标签的展示形态：由原始 key 决定图标、中文标题、是否彩虹渐变。 */
private data class TagVisual(
    val title: String,
    val iconRes: Int? = null,
    val gemini: Boolean = false,
    val rainbow: Boolean = false,
)

/** 把 provider 声明的原始标签 key 映射成可读中文 + 图标（对齐老词幕的展示方式）。 */
private fun tagVisualOf(key: String): TagVisual = when (key) {
    "\$syllable" -> TagVisual("逐字歌词", gemini = true, rainbow = true)
    "\$translation" -> TagVisual("翻译", iconRes = R.drawable.translate_24px)
    else -> TagVisual(key)
}

/** 单个歌词源卡片（磨砂半透明底 + 图标 + 名称/版本/作者 + 描述 + 标签；认证的底部内嵌暂停防冻结设置）。 */
@Composable
private fun ModuleCard(
    module: LyricSourceModule,
    showDetails: Boolean,
    context: Context,
    modifier: Modifier = Modifier,
    activePackage: String = "",
    activeFresh: Boolean = false,
    usage: Map<String, SourceUsage.Entry> = emptyMap(),
    nowMs: Long = System.currentTimeMillis(),
    // 平铺模式：不画自己的卡片底，交给外面那张大卡，行与行用分隔线
    flat: Boolean = false,
) {
    var showCertSettings by remember { mutableStateOf(false) }
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    // LSPosed 未激活的模块：卡片整体调暗 + 红框红字；已激活的正相反，用绿框绿字（亮色深绿 / 暗色浅绿）
    val notActivated = module.activation == false
    val activated = module.activation == true
    val warnRed = if (isDark) Color(0xFFEF9A9A) else Color(0xFFC62828)
    val okGreen = if (isDark) Color(0xFF81C784) else Color(0xFF4CAF50)
    val glassBg = if (notActivated) {
        if (isDark) Color(0xFF202024).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.16f)
    } else if (isDark) {
        Color(0xFF202024).copy(alpha = 0.45f)
    } else {
        Color.White.copy(alpha = 0.30f)
    }
    val glassBorder = when {
        notActivated -> warnRed.copy(alpha = 0.50f)
        activated -> okGreen.copy(alpha = 0.50f)
        isDark -> Color.White.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.70f)
    }
    val coui = CouiUiState.enabled.value
    val stateTint = when {
        notActivated -> warnRed
        activated -> okGreen
        else -> null
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (flat) {
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                } else {
                    val cardBase = if (coui) {
                        Modifier
                            .couiCard(
                                dark = isDark,
                                corner = 20.dp,
                                base = if (isDark) Color(0xCC262626) else Color(0xA8FFFFFF),
                                glowTint = stateTint ?: Color(0xFF2C77F2),
                            )
                            .then(
                                if (stateTint != null) {
                                    Modifier.border(1.dp, stateTint.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                                } else {
                                    Modifier
                                }
                            )
                    } else {
                        Modifier
                            .background(glassBg, RoundedCornerShape(16.dp))
                            .border(1.dp, glassBorder, RoundedCornerShape(16.dp))
                    }
                    cardBase.padding(if (coui) 16.dp else 14.dp)
                }
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncAppIcon(packageName = module.packageName, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = module.label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    if (module.isCertified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "✓ 认证",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF4CAF50),
                        )
                    }
                }
                val secondary = buildString {
                    append("v${module.versionName ?: "未知"}")
                    module.author?.let { append(" · $it") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = secondary,
                        fontSize = 13.sp,
                        color = Color.Gray,
                    )
                    when {
                        notActivated -> {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "未激活",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = warnRed,
                            )
                        }
                        module.activation == true -> {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "已激活",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = okGreen,
                            )
                        }
                    }
                }
                if (showDetails && !module.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = module.description,
                        fontSize = 13.sp,
                        color = Color.Gray,
                    )
                }
                if (showDetails && module.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        module.tags.forEach { raw ->
                            val visual = tagVisualOf(raw)
                            Box(
                                modifier = Modifier
                                    .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (visual.gemini) {
                                        Image(
                                            imageVector = GeminiColor,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    } else if (visual.iconRes != null) {
                                        Image(
                                            painter = painterResource(visual.iconRes),
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                    if (visual.gemini || visual.iconRes != null) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    if (visual.rainbow) {
                                        Text(
                                            text = visual.title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            style = TextStyle(
                                                brush = Brush.horizontalGradient(
                                                    listOf(
                                                        Color(0xFF4285F4),
                                                        Color(0xFF9B72FF),
                                                        Color(0xFFEA4335),
                                                        Color(0xFFFBBC05),
                                                        Color(0xFF34A853),
                                                    )
                                                )
                                            ),
                                        )
                                    } else {
                                        Text(
                                            text = visual.title,
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // B1 暂停防冻结：认证歌词源的设置弹窗（⚙ 图标触发）
                if (showCertSettings) {
                    Dialog(onDismissRequest = { showCertSettings = false }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MiuixTheme.colorScheme.background, RoundedCornerShape(16.dp))
                                .padding(16.dp),
                        ) {
                            Column {
                                Text(
                                    text = "${module.label} · 暂停防冻结",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                PrefSwitch(
                                    context = context,
                                    key = MasterPrefs.KEY_LYRIC_SOURCE_KEEP_SESSION_ON_PAUSE,
                                    default = false,
                                    title = "暂停防冻结",
                                    summary = "音乐暂停时保持播放会话活跃，避免被系统冻结导致重启状态栏后歌词数据不通",
                                    bridge = ProviderPrefsBridge,
                                )
                                NumberInputPreference(
                                    context = context,
                                    key = MasterPrefs.KEY_LYRIC_SOURCE_KEEP_SESSION_MINUTES,
                                    default = 15,
                                    range = 1..60,
                                    unit = "分钟",
                                    title = "保持时长",
                                    bridge = ProviderPrefsBridge,
                                    inline = true,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    Text(
                                        text = "完成",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF4F7CFF),
                                        modifier = Modifier
                                            .clickable { showCertSettings = false }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 运行态标签：正在用 / 刚用过 / 用过 / 未推词，一眼看清谁在干活
            Spacer(modifier = Modifier.width(8.dp))
            SourceStateChip(
                state = stateOf(module.packageName, activePackage, activeFresh, usage, nowMs),
                usage = usage[module.packageName],
                nowMs = nowMs,
                isDark = isDark,
            )
            // 设置按钮：认证歌词源，卡片中间高度靠右；平铺模式用齿轮，省出横向空间
            if (module.isCertified) {
                Spacer(modifier = Modifier.width(if (flat) 4.dp else 10.dp))
                Text(
                    text = if (flat) "⚙" else "设置",
                    fontSize = if (flat) 17.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .background(
                            if (isDark) Color(0xFF3D5AFE) else Color(0xFF4F7CFF),
                            RoundedCornerShape(if (flat) 8.dp else 9.dp),
                        )
                        .clickable { showCertSettings = true }
                        .padding(
                            horizontal = if (flat) 7.dp else 12.dp,
                            vertical = if (flat) 2.dp else 6.dp,
                        ),
                )
            }
        }
    }
}

/** 异步加载应用图标（Drawable → Bitmap，后台线程 + 状态缓存）。 */
@Composable
private fun AsyncAppIcon(packageName: String, modifier: Modifier = Modifier) {
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
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(
                color = MiuixTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
            )
        )
    }
}

/** 简单提示文本（加载中 / 空态）。 */
@Composable
private fun StatusHint(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
    )
}

/**
 * 自检卡片：把扫描诊断和模块运行态直接显示在页面上。
 *
 * 目的很实际：真我这类机器没有电脑、抓不了 App 进程的 logcat，出问题时只能在手机上自己看。
 * 这里把"卡在哪一步"拆成几行能直读的文字 —— 包可见性、QUERY_ALL_PACKAGES 是否授予、
 * LSPosed 数据库读取是否被 su 卡住、已知 provider 包能不能看见，
 * 再加上 SystemUI 侧报上来的"当前生效源 + 播放器"。
 */
@Composable
private fun DiagnosticsCard(
    report: LyricSourceScanReport?,
    playing: NowPlaying?,
    onRefresh: () -> Unit,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val bg = if (isDark) Color(0xFF202024).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.72f)
    val labelColor = if (isDark) Color.White.copy(alpha = 0.65f) else Color(0xFF475569)
    val valueColor = MiuixTheme.colorScheme.onSurface

    val d = report?.diagnostics

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(
                if (CouiUiState.enabled.value) {
                    Modifier.couiCard(
                        dark = isDark,
                        corner = 18.dp,
                        base = if (isDark) Color(0xCC262626) else Color(0xA8FFFFFF),
                        glowTint = Color(0xFF2C77F2),
                    )
                } else {
                    Modifier.background(bg, RoundedCornerShape(14.dp))
                }
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiagRow("当前生效源", providerLine(playing), labelColor, valueColor)
        DiagRow(
            "扫描结果",
            if (d == null) "尚未扫描" else "${d.visiblePackages} 个可见包 / 认出 ${d.recognized} 个源",
            labelColor,
            valueColor,
        )
        DiagRow(
            "包可见权限",
            when (d?.queryAllGranted) {
                true -> "已授予"
                false -> "未授予（部分机型会看不见已装源）"
                else -> "无法查询"
            },
            labelColor,
            valueColor,
        )
        DiagRow(
            "LSPosed 读取",
            when {
                d == null -> "尚未扫描"
                d.lspdStateOk -> "正常"
                d.lspdTimedOut -> "读取超时（su 卡住，已中断）"
                else -> "未读到（只影响激活角标，不影响列表）"
            },
            labelColor,
            valueColor,
        )
        DiagRow(
            "root 直读",
            when {
                d == null -> "尚未扫描"
                d.rootPackages == 0 -> "没读到（su 未授权或被超时中断）"
                d.hiddenPackages.isEmpty() -> "${d.rootPackages} 个包，没发现被隐藏的源"
                else -> "${d.rootPackages} 个包，有 ${d.hiddenPackages.size} 个源被隐藏：" +
                    d.hiddenPackages.joinToString("，")
            },
            labelColor,
            valueColor,
        )
        DiagRow(
            "疑似未识别",
            d?.suspectMissed?.takeIf { it.isNotEmpty() }?.joinToString("，") ?: "无",
            labelColor,
            valueColor,
        )
        DiagRow(
            "LSPosed 模块",
            when {
                d == null -> "尚未扫描"
                d.lspdModules.isEmpty() -> "未读到（provider 也一定是模块，可对照这里）"
                else -> d.lspdModules.joinToString("\n")
            },
            labelColor,
            valueColor,
        )
        DiagRow(
            "第三方包",
            when {
                d == null -> "尚未扫描"
                d.thirdPartyPackages.isEmpty() -> "无"
                else -> d.thirdPartyPackages.joinToString("\n")
            },
            labelColor,
            valueColor,
        )
        DiagRow("机型", d?.let { "${it.brand} · API ${it.apiLevel}" } ?: "尚未扫描", labelColor, valueColor)
        d?.error?.let { message ->
            DiagRow("扫描异常", message, labelColor, Color(0xFFEF5350))
        }

        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .background(
                    if (isDark) Color(0xFF3D5AFE) else Color(0xFF4F7CFF),
                    RoundedCornerShape(10.dp),
                )
                .clickable(onClick = onRefresh)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "重新扫描", fontSize = 13.sp, color = Color.White)
        }
    }
}

/** 自检卡片的一行：左侧固定宽度标签，右侧可换行内容。 */
@Composable
private fun DiagRow(label: String, value: String, labelColor: Color, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = labelColor,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 运行态那行的文案：优先用 provider 注册上来的真实包名，没有就退回播放器包名。 */
private fun providerLine(playing: NowPlaying?): String {
    if (playing == null) return "未读取到运行态"
    val provider = playing.providerPackage.orEmpty()
    val player = playing.providerPlayerPackage.takeIf { it.isNotBlank() } ?: playing.packageName
    return when {
        provider.isBlank() && player.isBlank() -> "未注册任何 provider"
        provider.isBlank() -> "provider 未注册 · 播放器 $player"
        player.isBlank() -> provider
        else -> "$provider · 播放器 $player"
    }
}

// ==================== 运行态：谁在推词 / 谁刚推过 ====================

/** 单个源在页面上的运行态。 */
private enum class SourceState { ACTIVE, JUST_USED, USED, IDLE }

private fun stateOf(
    packageName: String,
    activePackage: String,
    activeFresh: Boolean,
    usage: Map<String, SourceUsage.Entry>,
    nowMs: Long,
): SourceState {
    if (activeFresh && packageName == activePackage) return SourceState.ACTIVE
    val last = usage[packageName]?.lastSeenMs ?: 0L
    val age = nowMs - last
    return when {
        last > 0L && age in 0..120_000L -> SourceState.JUST_USED
        last > 0L && age in 0..3_600_000L -> SourceState.USED
        else -> SourceState.IDLE
    }
}

/** 行尾的状态小标签。 */
@Composable
private fun SourceStateChip(
    state: SourceState,
    usage: SourceUsage.Entry?,
    nowMs: Long,
    isDark: Boolean,
) {
    val (text, bg, fg) = when (state) {
        SourceState.ACTIVE -> Triple(
            "正在用",
            Color(0xFF34A853).copy(alpha = if (isDark) 0.20f else 0.14f),
            if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32),
        )
        SourceState.JUST_USED -> Triple(
            "刚用过",
            Color(0xFF2C77F2).copy(alpha = if (isDark) 0.22f else 0.12f),
            if (isDark) Color(0xFF9CC0FF) else Color(0xFF2C77F2),
        )
        SourceState.USED -> {
            val minutes = ((nowMs - (usage?.lastSeenMs ?: nowMs)) / 60_000L).coerceAtLeast(1L)
            Triple(
                "用过 ${if (minutes >= 60) "${minutes / 60} 小时前" else "${minutes} 分钟前"}",
                Color(0xFF2C77F2).copy(alpha = if (isDark) 0.14f else 0.08f),
                if (isDark) Color(0xFF9CC0FF) else Color(0xFF4F7CFF),
            )
        }
        SourceState.IDLE -> Triple(
            "未推词",
            if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFF0F172A).copy(alpha = 0.06f),
            if (isDark) Color.White.copy(alpha = 0.55f) else Color(0xFF64748B),
        )
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

/** 顶栏那个「刷新」小按钮。 */
@Composable
private fun RefreshChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (MiuixTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF202024).copy(alpha = 0.45f)
                else Color.White.copy(alpha = 0.30f),
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "刷新", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
    }
}

/** 播放器包名 → 应用名，拿不到就退回包名本身。 */
private fun playerLabel(context: Context, packageName: String): String {
    if (packageName.isBlank()) return "未知播放器"
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/** 秒数转成人话：不到 1 分钟 / N 分钟 / N 小时 M 分钟。 */
private fun ageTextOf(seconds: Long): String = when {
    seconds < 60L -> "不到 1 分钟"
    seconds < 3600L -> "${seconds / 60L} 分钟"
    else -> "${seconds / 3600L} 小时 ${(seconds % 3600L) / 60L} 分钟"
}

/**
 * 顶部「正在用」卡片：当前在推词的源 + 播放器 + 歌名 + 进度 + 多久没更新。
 * 没有源在推时退化成一句提示，不占大块版面。
 */
@Composable
private fun LiveSourceCard(
    playing: NowPlaying?,
    module: LyricSourceModule?,
    fresh: Boolean,
    nowMs: Long,
    context: Context,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val np = playing
    val hasSource = np != null && np.providerPackage.isNotBlank()
    val bg = if (isDark) Color(0xFF202024).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.72f)
    val labelColor = if (isDark) Color.White.copy(alpha = 0.55f) else Color(0xFF64748B)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(
                if (CouiUiState.enabled.value) {
                    Modifier.couiCard(
                        dark = isDark,
                        corner = 20.dp,
                        base = if (isDark) Color(0xCC262626) else Color(0xA8FFFFFF),
                        glowTint = Color(0xFF2C77F2),
                    )
                } else {
                    Modifier.background(bg, RoundedCornerShape(16.dp))
                }
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!hasSource) {
            Text(text = "现在没有源在推词", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
            Text(
                text = "打开音乐 App 播放一下，这里会显示正在用的源和推词情况",
                fontSize = 12.sp,
                color = labelColor,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncAppIcon(packageName = np!!.providerPackage, modifier = Modifier.size(42.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        fresh -> "正在用的源"
                        !np.playing -> "当前源 · 已暂停"
                        else -> "上次用的源"
                    },
                    fontSize = 11.sp,
                    color = labelColor,
                )
                Text(
                    text = module?.label ?: np.providerPackage,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                val player = playerLabel(context, np.providerPlayerPackage.ifBlank { np.packageName })
                val song = when {
                    np.title.isBlank() -> "还没有歌"
                    np.artist.isBlank() -> np.title
                    else -> "${np.title} · ${np.artist}"
                }
                Text(
                    text = "$player · $song",
                    fontSize = 12.sp,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(
                        if (np.playing) Color(0xFF34A853).copy(alpha = if (isDark) 0.20f else 0.14f)
                        else if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFF0F172A).copy(alpha = 0.06f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = if (np.playing) "正在播放" else "已暂停",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (np.playing) (if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)) else labelColor,
                )
            }
        }

        if (np.durationMs > 0L) {
            val frac = (np.positionMs.toFloat() / np.durationMs.toFloat()).coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFF2C77F2).copy(alpha = 0.12f),
                            RoundedCornerShape(2.dp),
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac)
                            .height(4.dp)
                            .background(Color(0xFF2C77F2), RoundedCornerShape(2.dp))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${(frac * 100).toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF2C77F2),
                )
            }
        }

        val providerAgeSec = if (np.providerUpdatedAt > 0L) {
            ((nowMs - np.providerUpdatedAt) / 1000L).coerceAtLeast(0L)
        } else -1L
        val liveAgeSec = if (np.updatedAt > 0L) {
            ((nowMs - np.updatedAt) / 1000L).coerceAtLeast(0L)
        } else -1L
        val playingStale = np.playing && !fresh
        Text(
            text = when {
                np.playing && fresh ->
                    if (providerAgeSec >= 0L) "正在推词 · 接入 ${ageTextOf(providerAgeSec)}" else "正在推词"
                playingStale -> "播放器 ${ageTextOf(liveAgeSec)}没刷新 · 可能已断开"
                else ->
                    if (providerAgeSec >= 0L) "已暂停 · 接入 ${ageTextOf(providerAgeSec)}" else "已暂停"
            },
            fontSize = 11.sp,
            color = if (playingStale) (if (isDark) Color(0xFFE0A96D) else Color(0xFFB45309)) else labelColor,
        )
    }
}

/** 抢词告警：一分钟内两个源推同一个播放器，基本就是它们在互相覆盖。 */
@Composable
private fun ConflictCard(
    conflicts: List<Pair<String, List<String>>>,
    modules: List<LyricSourceModule>,
    nowMs: Long,
    context: Context,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val warn = if (isDark) Color(0xFFEF9A9A) else Color(0xFFC62828)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(warn.copy(alpha = if (isDark) 0.16f else 0.08f), RoundedCornerShape(14.dp))
            .border(1.dp, warn.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "源冲突：${conflicts.sumOf { it.second.size }} 个源同时推词",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = warn,
        )
        conflicts.forEach { (player, packages) ->
            val names = packages.map { pkg -> modules.firstOrNull { it.packageName == pkg }?.label ?: pkg }
            Text(
                text = "${playerLabel(context, player)}：${names.joinToString("、")}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "同一个播放器只留一个源，否则歌词会互相覆盖",
            fontSize = 11.sp,
            color = if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFF64748B),
        )
    }
}

/** 导出诊断卡片。 */
@Composable
private fun ExportCard(
    context: Context,
    report: LyricSourceScanReport?,
    playing: NowPlaying?,
    usage: Map<String, SourceUsage.Entry>,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val bg = if (isDark) Color(0xFF202024).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.66f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "诊断", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
        Text(
            text = "把已装源、当前推词状态、最近使用记录打包成一个文本文件，出问题时直接发出来就行",
            fontSize = 12.sp,
            color = if (isDark) Color.White.copy(alpha = 0.55f) else Color(0xFF64748B),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(if (isDark) Color(0xFF3D5AFE) else Color(0xFF4F7CFF), RoundedCornerShape(14.dp))
                .clickable {
                    val file = runCatching { writeDiagnostics(context, report, playing, usage) }.getOrNull()
                    val msg = if (file != null) "已导出：${file.absolutePath}" else "导出失败，检查存储权限"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "导出诊断文件 ↓", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}

/** 写一份纯文本诊断：源清单 + 运行态 + 最近使用，不依赖日志和电脑。 */
private fun writeDiagnostics(
    context: Context,
    report: LyricSourceScanReport?,
    playing: NowPlaying?,
    usage: Map<String, SourceUsage.Entry>,
): File {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")
    dir.mkdirs()
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val file = File(dir, "lyric-source-$stamp.txt")
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    val sb = StringBuilder()
    sb.appendLine("词岛 · 歌词源诊断")
    sb.appendLine("导出时间：$time")
    sb.appendLine("应用版本：${com.liuxiansheng.lyricon.app.BuildConfig.VERSION_NAME}")
    sb.appendLine("机型：${android.os.Build.BRAND} · Android ${android.os.Build.VERSION.SDK_INT}")
    sb.appendLine()
    sb.appendLine("【当前运行态】")
    sb.appendLine(providerLine(playing))
    playing?.let { np ->
        sb.appendLine("歌名：${np.title} / 歌手：${np.artist}")
        sb.appendLine("播放器：${np.packageName} / 推词源：${np.providerPackage}")
        sb.appendLine("进度：${np.positionMs}/${np.durationMs} ms · 播放中=${np.playing}")
        sb.appendLine("推词最后更新：${np.providerUpdatedAt}")
    }
    sb.appendLine()
    sb.appendLine("【最近使用过的源】")
    if (usage.isEmpty()) {
        sb.appendLine("（无记录）")
    } else {
        usage.entries.sortedByDescending { it.value.lastSeenMs }.forEach { (pkg, e) ->
            val dt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(e.lastSeenMs))
            sb.appendLine("$pkg · $dt · 播放器 ${e.playerPackage}")
        }
    }
    sb.appendLine()
    sb.appendLine("【已安装歌词源】")
    val modules = report?.categories?.flatMap { it.items }.orEmpty()
    if (modules.isEmpty()) {
        sb.appendLine("（未扫描到源）")
    } else {
        modules.forEach { m ->
            sb.appendLine(
                "- ${m.label} | ${m.packageName} | v${m.versionName ?: "?"} | 激活=${m.activation} | " +
                    "认证=${m.isCertified} | 标签=${m.tags.joinToString(",")}"
            )
        }
    }
    report?.diagnostics?.let { d ->
        sb.appendLine()
        sb.appendLine("【扫描诊断】")
        sb.appendLine("可见包：${d.visiblePackages} · 识别：${d.recognized} · API：${d.apiLevel}")
        sb.appendLine("QUERY_ALL_PACKAGES：${d.queryAllGranted}")
        sb.appendLine("root 读取：${d.rootPackages} 个包 · 被隐藏源：${d.hiddenPackages.joinToString(",")}")
        sb.appendLine("疑似未识别：${d.suspectMissed.joinToString(",")}")
        d.error?.let { sb.appendLine("扫描异常：$it") }
    }
    file.writeText(sb.toString())
    return file
}

