/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 *
 * StoreScreen：歌词源商店独立页面。
 * 按类型分组（专属播放器 / 通用播放器 / 动态封面），每张卡显示安装态、
 * 下载进度、失败原因和更新说明；安装走系统弹窗，过期目录只读。
 */

package com.liuxiansheng.lyricon.app.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.liuxiansheng.lyricon.app.activity.SectionCard
import com.liuxiansheng.lyricon.app.activity.SectionTitle
import com.liuxiansheng.lyricon.app.activity.SkinTheme
import com.liuxiansheng.lyricon.app.coui.CouiSettingsShell
import com.liuxiansheng.lyricon.app.coui.CouiUiState
import com.liuxiansheng.lyricon.app.coui.couiCard
import com.liuxiansheng.lyricon.app.compose.effect.AppBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ACCENT = Color(0xFF4F7CFF)

/** 副文本色：outline 在浅色背景上太淡，这里按明暗背景各给一档更实的中灰 */
@Composable
private fun mutedInk(): Color =
    if (MiuixTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFB3BCCB) else Color(0xFF4C5666)

/** 页面最上面的作者署名卡：这批歌词源不是词岛做的，写清是谁做的。 */
@Composable
private fun CreditCard() {
    val isDarkNow = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val coui = CouiUiState.enabled.value
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)
            .then(
                if (coui) {
                    Modifier
                        .couiCard(
                            dark = isDarkNow,
                            corner = 20.dp,
                            base = if (isDarkNow) Color(0xCC262626) else Color(0xA8FFFFFF),
                            glowTint = ACCENT,
                        )
                        .border(1.dp, ACCENT.copy(alpha = 0.30f), RoundedCornerShape(20.dp))
                } else {
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(ACCENT.copy(alpha = 0.12f))
                        .border(1.dp, ACCENT.copy(alpha = 0.34f), RoundedCornerShape(18.dp))
                }
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "歌词源由两位作者制作",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = ACCENT,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起 ▴" else "说明 ▾",
                    fontSize = 12.sp,
                    color = ACCENT.copy(alpha = 0.85f),
                )
            }
            if (expanded) {
                Text(
                    text = "这些歌词源都不是词岛做的：Tomakino 做了汽水、酷狗、酷我、QQ、网易云等一批，" +
                        "Andrea-lyz 做了酷我、QQ、网易云、Apple、Spotify、Salt、光锥等一批，两边都无偿开源，" +
                        "还一直跟着各家播放器的版本更新。词岛只负责编译、签名和放进商店。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = mutedInk(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            CreditAuthorRow("Tomakino", StoreSource.TOMAKINO.authorUrl, "歌词源作者")
            CreditAuthorRow("Andrea-lyz", StoreSource.ANDREA.authorUrl, "ColorOS 源的作者")
        }
    }
}

/** 署名卡里的一行作者：点名字去他主页。 */
@Composable
private fun CreditAuthorRow(author: String, url: String, note: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = author,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = ACCENT,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
        Text(
            text = " · $note · 看主页",
            fontSize = 12.sp,
            color = mutedInk(),
        )
    }
}

/** 分区标题下面一行：这批源由谁制作，点名字去主页。 */
@Composable
private fun SourceCreditLine(source: StoreSource) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 20.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "由 ", fontSize = 11.sp, color = mutedInk())
        Text(
            text = source.author,
            fontSize = 11.sp,
            color = ACCENT,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(source.authorUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
        Text(
            text = " 制作 · ${source.credit} · 看主页",
            fontSize = 11.sp,
            color = mutedInk(),
        )
    }
}

@Composable
fun StoreScreen(onBack: () -> Unit, onOpenLyricSource: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 先用进程内上一次的目录和列表铺出来：再进商店零等待，冷启动也有 Application 预热
    var state by remember { mutableStateOf(StoreRepository.cachedState()) }
    var items by remember { mutableStateOf(StoreRepository.cachedItems() ?: emptyList()) }
    // 进页面先吃本地目录，界面直接出，不转圈；后台刷新不碰这个状态，免得列表看着在"动"
    var refreshing by remember { mutableStateOf(false) }
    var booted by remember { mutableStateOf(StoreRepository.cachedState() != null) }
    var message by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(0) }
    var sourceTab by remember { mutableStateOf(StoreSource.TOMAKINO) }
    var titleExpanded by remember { mutableStateOf(false) }
    var expandedNotes by remember { mutableStateOf<String?>(null) }
    // 长按已装的源 → 弹卸载确认（系统弹窗最终确认，这里只负责把目标挑出来）
    var uninstallTarget by remember { mutableStateOf<StoreItem?>(null) }

    // 有几个已装且可更新的源：入口角标、置顶区块、卡片角标都用这一个数
    val updates = items.filter { it.installState == InstallState.UPDATE_AVAILABLE }
    val updateCount = updates.size

    val operations by StoreInstaller.operations.collectAsState()
    val confirmation by StoreInstaller.confirmation.collectAsState()

    // 先读本地目录再用；十分钟内不重复联网（跟上游同一档）
    // 联网刷新放后台静默做：成功不弹提示、不显示"刷新中"，只把结果替换过来；
    // 失败了才在底部目录区说一句，列表保持原样。
    LaunchedEffect(Unit) {
        val hadCache = state != null && items.isNotEmpty()
        val t0 = SystemClock.elapsedRealtime()
        val local = withContext(Dispatchers.IO) { StoreRepository.load(context) }
        state = local
        items = withContext(Dispatchers.IO) { StoreRepository.project(context, local) }
        StoreRepository.rememberUi(local, items)
        booted = true
        android.util.Log.d("StoreFlow", "进入商店 cache=$hadCache 首屏=${SystemClock.elapsedRealtime() - t0}ms 条目=${items.size} refreshing=false")
        if (StoreRepository.autoRefreshDue()) {
            val t1 = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { StoreRepository.refresh(context) }
            state = result.state
            items = withContext(Dispatchers.IO) { StoreRepository.project(context, result.state) }
            StoreRepository.rememberUi(result.state, items)
            if (result.state !is CatalogState.Current) message = result.message
            android.util.Log.d("StoreFlow", "后台刷新完成 ${SystemClock.elapsedRealtime() - t1}ms 条目=${items.size} 成功=${result.state is CatalogState.Current}")
        }
    }

    // 从系统设置/安装器回来：继续未完成的安装，清理孤儿会话，重算安装态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                StoreInstaller.continueIfAllowed(context)
                scope.launch {
                    val current = state ?: CatalogState.Unavailable
                    StoreInstaller.reconcile(context, current)
                    items = withContext(Dispatchers.IO) { StoreRepository.project(context, current) }
                    StoreRepository.rememberUi(current, items)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统安装确认：拿到就拉起一次
    LaunchedEffect(confirmation) {
        confirmation?.let {
            runCatching { context.startActivity(it) }
            StoreInstaller.confirmationLaunched()
        }
    }

    // 有操作成功就重算一遍安装态
    LaunchedEffect(operations) {
        if (operations.values.any { it is StoreOperation.Succeeded }) {
            items = withContext(Dispatchers.IO) {
                StoreRepository.project(context, state ?: CatalogState.Unavailable)
            }
            StoreRepository.rememberUi(state ?: CatalogState.Unavailable, items)
        }
    }

    BackHandler { onBack() }

    // 长按确认后走系统卸载界面；返回时 ON_RESUME 会重算安装态，卡片自己变回"安装"
    uninstallTarget?.let { target ->
        UninstallDialog(
            name = target.name,
            onDismiss = { uninstallTarget = null },
            onConfirm = {
                uninstallTarget = null
                val intent = Intent(
                    Intent.ACTION_DELETE,
                    Uri.parse("package:${target.entry.applicationId}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val launched = runCatching { context.startActivity(intent) }.isSuccess
                if (!launched) message = "系统没有可用的卸载界面，没能卸载"
            },
        )
    }

    SkinTheme {
        val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
        SideEffect {
            val window = (context as android.app.Activity).window
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDark
        }
        AppBackground(isDarkTheme = isDark) {
            val storeHeader: @Composable () -> Unit = {
                SmallTextButton(if (updateCount > 0) "已装源 · $updateCount" else "已装源") { onOpenLyricSource() }
                Spacer(Modifier.width(8.dp))
                SmallTextButton(if (refreshing) "刷新中" else "刷新", enabled = !refreshing) {
                    if (refreshing) return@SmallTextButton
                    scope.launch {
                        refreshing = true
                        message = null
                        val result = withContext(Dispatchers.IO) { StoreRepository.refresh(context) }
                        state = result.state
                        items = withContext(Dispatchers.IO) { StoreRepository.project(context, result.state) }
                        StoreRepository.rememberUi(result.state, items)
                        message = result.message
                        refreshing = false
                    }
                }
            }
            val storeBody: @Composable () -> Unit = {
                    CreditCard()

                    SourceTabRow(sourceTab) {
                        sourceTab = it
                        titleExpanded = false
                    }

                    FilterRow(filter) { filter = it }

                    val current = state as? CatalogState.Current
                    val visible = items.filter { item ->
                        val inTab = when (sourceTab) {
                            StoreSource.TOMAKINO -> item.entry.source == StoreSource.TOMAKINO
                            StoreSource.ANDREA -> item.entry.source == StoreSource.ANDREA
                            else -> false
                        }
                        inTab && when (filter) {
                            1 -> item.installState == InstallState.UPDATE_AVAILABLE
                            2 -> item.installState != InstallState.NOT_INSTALLED
                            else -> true
                        }
                    // 已安装的排前面：可更新 → 已装 → 本地更新 → 签名不符 → 未安装，
                    // 同档内保持目录原顺序（sortedWith 是稳定排序）
                    }.sortedBy { item ->
                        when (item.installState) {
                            InstallState.UPDATE_AVAILABLE -> 0
                            InstallState.INSTALLED -> 1
                            InstallState.LOCAL_NEWER -> 2
                            InstallState.SIGNATURE_MISMATCH -> 3
                            InstallState.NOT_INSTALLED -> 4
                        }
                    }

                    if (current == null) {
                        // 读取本地目录的瞬间不写任何字，避免闪一下"正在读取"；真读不到才提示
                        if (booted) StatusHint("商店目录不可用，点右上角刷新重试")
                    } else if (visible.isEmpty()) {
                        StatusHint(
                            when (filter) {
                                1 -> "没有可更新的源"
                                2 -> "还没有装过商店里的源"
                                else -> "目录里没有条目"
                            }
                        )
                    } else {
                        val hosts = current.catalog.trustedHosts
                        // 一张卡的渲染只写一次，可更新区块和正常列表共用，避免两处逻辑跑偏
                        val rowOf: @Composable (StoreItem) -> Unit = { item ->
                            StoreRow(
                                item = item,
                                operation = operations[item.providerId],
                                expanded = expandedNotes == item.providerId,
                                onToggleNotes = {
                                    expandedNotes = if (expandedNotes == item.providerId) null else item.providerId
                                },
                                onAction = {
                                    when (val op = operations[item.providerId]) {
                                        is StoreOperation.Downloading,
                                        StoreOperation.WaitingForUser,
                                        -> StoreInstaller.cancel(context, item.providerId)

                                        StoreOperation.NeedsInstallPermission -> {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:${context.packageName}")
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            runCatching { context.startActivity(intent) }
                                        }

                                        else -> StoreInstaller.start(context, item, hosts)
                                    }
                                },
                                onLongPress = {
                                    val busy = operations[item.providerId]?.active == true
                                    if (item.installed != null && !busy) uninstallTarget = item
                                },
                            )
                        }
                        // 一个作者一页；标题可折叠，点一下才展开作者那一行说明
                        SectionTitle(
                            text = sourceTab.sectionTitle ?: sourceTab.author,
                            collapsible = true,
                            expanded = titleExpanded,
                            onToggleExpanded = { titleExpanded = !titleExpanded },
                        )
                        if (titleExpanded) SourceCreditLine(sourceTab)
                        SectionCard {
                            Column {
                                visible.forEachIndexed { index, item ->
                                    rowOf(item)
                                    if (index != visible.lastIndex) RowDivider()
                                }
                            }
                        }
                    }

                    // 目录状态放到最下面，别占列表上面的位置
                    CatalogSummary(state, refreshing, message)

                    Text(
                        text = "上面两个标签是两批源，各自一页：Tomakino 做的和 Andrea-lyz 做的，谁做的就点谁的标签。" +
                            "所有安装包都必须与目录声明的 SHA-256 和签名证书完全一致，安装一律由系统弹窗确认。" +
                            "已安装的源长按卡片可直接卸载，卸载同样由系统弹窗确认。",
                        fontSize = 12.sp,
                        color = mutedInk(),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                    Spacer(Modifier.height(24.dp))
            }
            // 标题和两个按钮同一行：凝光外壳的大标题会把卡片压得很靠下，这里不用它的大标题
            val titleRow: @Composable () -> Unit = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "歌词源商店",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    storeHeader()
                }
            }
            if (CouiUiState.enabled.value) {
                CouiSettingsShell(
                    title = "歌词源商店",
                    showTitle = false,
                    actions = {},
                ) {
                    titleRow()
                    storeBody()
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                        titleRow()
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) { storeBody() }
                }
            }
        }
    }
}

/** 顶栏小按钮。 */
@Composable
private fun SmallTextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = if (enabled) ACCENT else MiuixTheme.colorScheme.outline,
        modifier = Modifier
            .background(
                MiuixTheme.colorScheme.surface.copy(alpha = 0.55f),
                RoundedCornerShape(9.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/** 只在真有事要说的时候才占位置：目录拉不到、目录过期、刷新有结果。平时完全不显示。 */
@Composable
private fun CatalogSummary(state: CatalogState?, refreshing: Boolean, message: String?) {
    val current = state as? CatalogState.Current
    val loading = state == null && refreshing
    val missing = state != null && current == null
    val expired = current != null && !current.installable
    if (!loading && !missing && !expired && message == null) return

    SectionTitle("目录")
    SectionCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            when {
                loading -> Text(
                    text = "正在读取目录…",
                    fontSize = 13.sp,
                    color = mutedInk(),
                )

                missing -> Text(
                    text = "没有可用的目录",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )

                expired && current != null -> Text(
                    text = "目录已过期（${expiresText(current.catalog.expiresAtEpochSeconds)}），只显示已装状态",
                    fontSize = 13.sp,
                    color = Color(0xFFE07A2F),
                )
            }
            message?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = if (it.contains("已更新")) mutedInk() else Color(0xFFE05555),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 顶部作者标签：两个作者各一页，切过去只列他做的源。 */
@Composable
private fun SourceTabRow(selected: StoreSource, onSelect: (StoreSource) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StoreSource.entries.filter { it.isOwnBuild }.forEach { source ->
            FilterChip(source.sectionTitle ?: source.author, selected == source) { onSelect(source) }
        }
    }
}

@Composable
private fun FilterRow(current: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip("全部", current == 0) { onChange(0) }
        FilterChip("可更新", current == 1) { onChange(1) }
        FilterChip("已安装", current == 2) { onChange(2) }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val bg = if (selected) {
        if (isDark) Color(0xFF3D5AFE) else ACCENT
    } else {
        if (isDark) Color(0xFF202024).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.45f)
    }
    Text(
        text = text,
        fontSize = 13.sp,
        color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (selected) Color.Transparent else MiuixTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private val STORE_ICON_SIZE = 44.dp

/**
 * 条目图标：目标播放器官方 launcher 图标的副本（与上游商店同一套素材）。
 * 自适应图标走平台 Drawable 绘制，Compose 的 painterResource 认不了它；
 * 没有图标的（Readify）用首字母块。
 */
@Composable
private fun StoreIcon(providerId: String, name: String, iconUrl: String? = null) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { STORE_ICON_SIZE.roundToPx() }

    // 图标优先用目录下发的地址（服务端换图不用重新打包客户端），失败再退本地表。
    var remote by remember(providerId, iconUrl, px) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(providerId, iconUrl, px) {
        if (iconUrl != null) {
            remote = withContext(Dispatchers.IO) {
                runCatching { loadRemoteStoreIcon(context, providerId, iconUrl, px) }.getOrNull()
            }
        }
    }

    val iconRes = STORE_ICONS[providerId] ?: 0
    val bitmap: ImageBitmap? = remote ?: remember(iconRes, px) {
        if (iconRes == 0) null else runCatching {
            ContextCompat.getDrawable(context, iconRes)?.let { drawable ->
                Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { out ->
                    drawable.setBounds(0, 0, px, px)
                    drawable.draw(Canvas(out))
                }.asImageBitmap()
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(STORE_ICON_SIZE)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(STORE_ICON_SIZE)
                .clip(RoundedCornerShape(12.dp))
                .background(ACCENT.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = ACCENT,
            )
        }
    }
}

/** 单个商店条目。 */
@Composable
private fun StoreRow(
    item: StoreItem,
    operation: StoreOperation?,
    expanded: Boolean,
    onToggleNotes: () -> Unit,
    onAction: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            // 长按整行：只有已安装的源才会被上层接收（未装的长按不弹任何东西）
            .pointerInput(item.providerId, item.installed?.versionCode) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val showUpdate = item.installState == InstallState.UPDATE_AVAILABLE
        Row(verticalAlignment = Alignment.Top) {
            StoreIcon(item.providerId, item.name, item.entry.iconUrl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (showUpdate) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "有新版本",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE07A2F),
                            modifier = Modifier
                                .background(Color(0xFFE07A2F).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (showUpdate) {
                            "v${item.installed?.versionName ?: "?"} → v${item.entry.versionName}"
                        } else {
                            "v${item.entry.versionName}"
                        },
                        fontSize = 12.sp,
                        color = if (showUpdate) Color(0xFFE07A2F) else mutedInk(),
                    )
                }
                Text(
                    text = "${item.entry.kind.label} · ${storeSizeText(item.entry.artifact.bytes)} · ${item.entry.providerId}",
                    fontSize = 12.sp,
                    color = mutedInk(),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = operationText(item, operation),
                    fontSize = 12.sp,
                    color = statusColor(item, operation),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            ActionButton(item, operation, onAction)
        }

        if (operation is StoreOperation.Downloading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(4.dp)
                    .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.18f), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(operation.fraction)
                        .height(4.dp)
                        .background(ACCENT, RoundedCornerShape(2.dp))
                )
            }
        }

        val notes = item.entry.releaseNotes
        if (!notes.isNullOrBlank()) {
            Text(
                text = if (expanded) "收起说明" else "更新说明",
                fontSize = 12.sp,
                color = ACCENT,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable(onClick = onToggleNotes),
            )
            if (expanded) {
                Text(
                    text = notes,
                    fontSize = 12.sp,
                    color = mutedInk(),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        noteText(item)?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = Color(0xFFE07A2F),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ActionButton(item: StoreItem, operation: StoreOperation?, onAction: () -> Unit) {
    val label = when (operation) {
        is StoreOperation.Downloading -> "取消"
        StoreOperation.Verifying, StoreOperation.Installing, StoreOperation.WaitingForUser -> "取消"
        StoreOperation.NeedsInstallPermission -> "去授权"
        is StoreOperation.Succeeded, is StoreOperation.Failed, null -> item.actionLabel
    }
    val enabled = when {
        operation is StoreOperation.Downloading -> true
        operation == StoreOperation.WaitingForUser -> true
        operation is StoreOperation.Verifying -> false
        operation == StoreOperation.Installing -> false
        operation == StoreOperation.NeedsInstallPermission -> true
        // 已装且目录里没有更新版本：按钮显示「已安装」，只作状态，不再点得动
        item.installState == InstallState.INSTALLED -> false
        else -> item.available
    }
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = if (enabled) ACCENT else MiuixTheme.colorScheme.outline,
        modifier = Modifier
            .padding(start = 10.dp)
            .background(
                if (enabled) ACCENT.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(9.dp),
            )
            .clickable(enabled = enabled, onClick = onAction)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

private fun operationText(item: StoreItem, operation: StoreOperation?): String = when (operation) {
    null -> item.statusText
    is StoreOperation.Downloading -> "下载中 ${(operation.fraction * 100).toInt()}%"
    StoreOperation.Verifying -> "校验安装包…"
    StoreOperation.Installing -> "正在安装…"
    StoreOperation.WaitingForUser -> "等系统安装确认"
    StoreOperation.NeedsInstallPermission -> "需要允许词岛安装应用"
    is StoreOperation.Succeeded -> "已安装 v${operation.versionName}"
    is StoreOperation.Failed -> operation.failure.text
}

@Composable
private fun statusColor(item: StoreItem, operation: StoreOperation?): Color = when (operation) {
    is StoreOperation.Failed -> Color(0xFFE05555)
    is StoreOperation.Succeeded -> Color(0xFF2E9E5B)
    null -> when (item.installState) {
        InstallState.INSTALLED -> Color(0xFF2E9E5B)
        InstallState.NOT_INSTALLED -> Color(0xFF3F7BE8)
        InstallState.UPDATE_AVAILABLE -> Color(0xFFE07A2F)
        InstallState.SIGNATURE_MISMATCH -> Color(0xFFE05555)
        else -> mutedInk()
    }
    else -> ACCENT
}

private fun noteText(item: StoreItem): String? = when (item.note) {
    StoreNote.SIGNATURE_MISMATCH -> "设备上的同名应用签名与目录不符，不能覆盖安装"
    StoreNote.LOCAL_NEWER -> "本地版本比目录里的新，商店不做降级"
    StoreNote.NEEDS_NEWER_STORE -> "这个源要求更新版的商店协议，当前版本暂不安装"
    StoreNote.UNSUPPORTED_SDK -> "这个源要求更高的系统版本"
    StoreNote.NONE -> when (item.availability) {
        StoreAvailability.CATALOG_EXPIRED -> "目录已过期，暂不安装"
        else -> null
    }
}

private fun expiresText(epochSeconds: Long): String {
    val millis = epochSeconds * 1000
    val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
    return format.format(java.util.Date(millis))
}

@Composable
private fun StatusHint(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
    )
}

/** 列表里卡片之间的细分隔线。 */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 16.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.12f))
    )
}

/**
 * 目录下发的图标：带磁盘缓存（cacheDir/store-icons/<providerId>.png），
 * 下载失败或文件异常就返回 null，调用方自动回退本地图标或首字母块。
 */
private fun loadRemoteStoreIcon(
    context: android.content.Context,
    providerId: String,
    url: String,
    px: Int,
): ImageBitmap? {
    val dir = java.io.File(context.cacheDir, "store-icons").apply { mkdirs() }
    // 文件名带 URL 指纹：远端换图后 URL 变了就重新下载，避免旧图永久占坑。
    val file = java.io.File(dir, "$providerId-${Integer.toHexString(url.hashCode())}.png")
    val bytes: ByteArray = if (file.isFile && file.length() in 1L..(1024 * 1024)) {
        file.readBytes()
    } else {
        val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LyriconStore")
        }
        try {
            conn.inputStream.use { input ->
                input.readBytes().also { raw ->
                    if (raw.size in 1..(1024 * 1024)) file.writeBytes(raw)
                }
            }
        } finally {
            conn.disconnect()
        }
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = maxOf(1, minOf(bounds.outWidth / px, bounds.outHeight / px))
    val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
    val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    canvas.drawBitmap(src, null, android.graphics.Rect(0, 0, px, px), paint)
    if (src !== out) src.recycle()
    return out.asImageBitmap()
}

/**
 * 卸载确认弹窗。
 * 只负责说清楚"卸载会失去什么"，最后那一下由系统卸载界面确认，
 * 跟安装保持同一个口径：词岛不代替系统做卸载。
 */
@Composable
private fun UninstallDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.surface)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(
                text = "卸载「$name」？",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "卸载后这个歌词源就失效：正在播的歌不再出词，要用得回商店重装。" +
                    "卸载由系统弹窗做最后确认。",
                fontSize = 13.sp,
                color = mutedInk(),
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "取消",
                    fontSize = 14.sp,
                    color = mutedInk(),
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "卸载",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE5484D),
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}