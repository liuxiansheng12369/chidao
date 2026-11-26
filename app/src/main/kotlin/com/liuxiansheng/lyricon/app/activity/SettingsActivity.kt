/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 */

package com.liuxiansheng.lyricon.app.activity

import android.app.Activity
import android.app.AlertDialog
import java.io.File
import org.json.JSONObject
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import com.liuxiansheng.lyricon.app.compose.effect.AppBackground
import com.liuxiansheng.lyricon.app.compose.effect.BgEffectBackground
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuxiansheng.lyricon.axis.ui.Dim
import com.liuxiansheng.lyricon.axis.ui.SettingCard
import com.liuxiansheng.lyricon.axis.ui.SettingChoiceRow
import com.liuxiansheng.lyricon.axis.ui.SettingDropdownRow
import com.liuxiansheng.lyricon.axis.ui.SettingSwitchRow
import com.liuxiansheng.lyricon.axis.ui.SettingValueRow
import com.liuxiansheng.lyricon.axis.ui.Font
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import com.liuxiansheng.lyricon.app.live.ListenRecordsStore
import com.liuxiansheng.lyricon.app.util.MasterPrefs
import com.liuxiansheng.lyricon.app.util.PrefsBridge
import com.liuxiansheng.lyricon.app.util.SectionOrder
import com.liuxiansheng.lyricon.app.util.SettingsBackup
import com.liuxiansheng.lyricon.bridge.BridgeSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.layout.Row
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.TextStyle
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import com.liuxiansheng.lyricon.app.coui.CouiSettingsScreen
import com.liuxiansheng.lyricon.app.coui.CouiSkinPref
import com.liuxiansheng.lyricon.app.coui.CouiUiState
import com.liuxiansheng.lyricon.app.coui.CouiSectionTitle
import com.liuxiansheng.lyricon.app.coui.CouiSectionChevron
import com.liuxiansheng.lyricon.app.coui.CouiSectionCard
import com.liuxiansheng.lyricon.app.coui.CouiSwitchRow
import com.liuxiansheng.lyricon.app.coui.CouiArrowRow
import com.liuxiansheng.lyricon.app.coui.CouiDropdownRow
import com.liuxiansheng.lyricon.app.coui.CouiStylePicker
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu as CouiMenu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.theme.CouiTheme
import com.liuxiansheng.lyricon.app.coui.couiDark
import com.liuxiansheng.lyricon.app.coui.CouiSettingsShell
import com.liuxiansheng.lyricon.app.coui.CouiTopBarTextButton
import com.liuxiansheng.lyricon.app.coui.CouiTopBarIconButton
import com.liuxiansheng.lyricon.app.coui.CouiTopBarBackButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.liuxiansheng.lyricon.app.coui.CouiPageBackdrop
import com.liuxiansheng.lyricon.app.coui.CouiChoiceRow
import com.liuxiansheng.lyricon.app.coui.CouiStepperRow
import com.liuxiansheng.lyricon.app.coui.CouiSubGroupTitle
import com.liuxiansheng.lyricon.app.coui.CouiConfirmDialog
import com.liuxiansheng.lyricon.app.coui.CouiInputDialog
import com.liuxiansheng.lyricon.app.coui.CouiOptionDialog
import com.liuxiansheng.lyricon.app.coui.CouiColorDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置界面（Miuix / Compose 重写版）。
 *
 * 结构按老词幕超级岛分组（基础设置/歌名歌曲动效/歌词效果/显示设置/超级岛特效/岛背景/歌词内容/字体设置），
 * 并补回 HyperLyric 移植的缺失设置项（布局样式/卡片主题/动画跑马灯/翻译/媒体卡片等）。
 * 所有设置写入 MasterPrefs（本地 + Xposed 远程 prefs），SystemUI 侧 HookEntry 监听生效。
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeSender.initialize(applicationContext)
        // 与首页一致：手机锁竖屏，平板才允许四向
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= 600) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContent { SkinHost { SettingsScreen() } }
    }
}

@Composable
internal fun SettingsScreen() {
    val context = LocalContext.current
    // 界面风格分流：COUI 凝光视效 / 原 Miuix 皮肤，两套共用同一份配置。
    // 在这里按状态切换，MainActivity 与 SettingsActivity 两处调用都生效，不必重建 Activity。
    var couiStyle by remember { mutableStateOf(CouiSkinPref.isEnabled(context)) }
    // 凝光皮肤不再走独立页：同一套设置内容、同一份配置，基础行组件按 CouiUiState 自动换皮
    CouiUiState.enabled.value = couiStyle
    val scope = rememberCoroutineScope()

    // 重启状态栏没拿到 root 时的说明弹窗（原来失败是静默吞掉的）
    var showNoRoot by remember { mutableStateOf(false) }

    SkinTheme {
        // 状态栏图标（时钟/信号/WiFi/电量）按页面背景反色：深背景→浅图标，浅背景→深图标
        val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
        SideEffect {
            val window = (context as Activity).window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !isDark
        }
        // 页面内容（原先 AppBackground 里的整块）。凝光皮肤下不铺动态着色器背景：
        // 动态背景每帧刷新图层，与窗口背景采样叠加会把渲染树刷成自引用环，
        // 表现为启动即 native 爆栈（libhwui RenderNode::prepareTreeImpl 递归 500+ 帧）。
        val settingsPageContent: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        // 凝光皮肤：铺一层 COUI 页面底色，盖掉原 Miuix 渐变背景
        if (CouiUiState.enabled.value) {
            CouiPageBackdrop()
        }
        NoRootDialog(visible = showNoRoot, context = context, onDismiss = { showNoRoot = false })

        // Miuix Scaffold 提供 Overlay 弹窗宿主（OverlayDialog/OverlayBottomSheet 才能弹出）
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent
        ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            // 启动同步：把"分离歌词模式"开关当前值落实到 HyperLyric 渲染键。
            // 旧版本（≤3.0.96）只写 island_split_lyric_mode，从未写 key_hook_lyric_mode，
            // 导致开关显示开但实际未生效（重启 SystemUI 后恢复默认不分段）。
            // 进入设置页即按开关真相补写一次渲染键，无需用户重拨开关。
            LaunchedEffect(Unit) {
                // 默认值初始化（对齐老词幕：App 是唯一写入者，SystemUI 只读 remote prefs）：
                // 首次启动（用户从未设置过）才写默认 true，绝不覆盖用户已设的开/关。
                if (!MasterPrefs.contains(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND)) {
                    MasterPrefs.setBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true)
                }
                if (!MasterPrefs.contains(context, MasterPrefs.KEY_ISLAND_DISABLE_WIDTH_LIMIT)) {
                    MasterPrefs.setBoolean(context, MasterPrefs.KEY_ISLAND_DISABLE_WIDTH_LIMIT, true)
                }
                val split = MasterPrefs.getBoolean(
                    context,
                    MasterPrefs.KEY_ISLAND_SPLIT_LYRIC_MODE,
                    true
                )
                MasterPrefs.setInt(
                    context,
                    MasterPrefs.KEY_LYRIC_MODE,
                    if (split) 1 else 0
                )
                MasterPrefs.setInt(
                    context,
                    MasterPrefs.KEY_ISLAND_CONTENT_LEFT,
                    if (split) 7 else 8
                ) // 7=lyric, 8=custom_music_info
                MasterPrefs.setInt(context, MasterPrefs.KEY_ISLAND_CONTENT_RIGHT, 7)
            }

            // 右上角重启按钮（仅 Miuix 皮肤；凝光视效改由 COUI 顶栏按钮承载）
            if (!CouiUiState.enabled.value) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
                val btnBg = if (isDark) {
                    Color(0xFF202024).copy(alpha = 0.80f)
                } else {
                    Color.White.copy(alpha = 0.68f)
                }
                val btnBorder = if (isDark) {
                    Color.White.copy(alpha = 0.14f)
                } else {
                    Color.White.copy(alpha = 0.80f)
                }
                Box(
                    modifier = Modifier
                        .background(btnBg, RoundedCornerShape(14.dp))
                        .border(1.dp, btnBorder, RoundedCornerShape(14.dp))
                        .clickable {
                            SystemUiRestart.request(context, scope, onDenied = { showNoRoot = true })
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "↻ 重启状态栏",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            }

            // 内容主体：各分组（凝光视效与 Miuix 皮肤共用同一份内容；界面风格入口在「更多设置」里）
            val settingsBody: @Composable ColumnScope.() -> Unit = {
                val localContext = LocalContext.current
                val settingsSectionIds = listOf(
                    "super", "basic", "effect", "display", "special",
                    "background", "content", "font", "extras",
                )
                var settingsOrder by remember {
                    mutableStateOf(
                        SectionOrder.read(
                            localContext,
                            "pref_settings_section_order",
                            settingsSectionIds,
                        )
                    )
                }
                val moveSettings: (Int, Int) -> Unit = { from, to ->
                    val list = settingsOrder.toMutableList()
                    val id = list.removeAt(from)
                    list.add(to.coerceIn(0, list.size), id)
                    settingsOrder = list
                    SectionOrder.write(localContext, "pref_settings_section_order", list)
                }
                settingsOrder.forEachIndexed { index, id ->
                    val canUp = index > 0
                    val canDown = index < settingsOrder.size - 1
                    val reorder = SectionReorder(
                        canReorder = true,
                        onMoveUp = if (canUp) { { moveSettings(index, index - 1) } } else null,
                        onMoveDown = if (canDown) { { moveSettings(index, index + 1) } } else null,
                        onMoveTop = if (canUp) { { moveSettings(index, 0) } } else null,
                        onMoveBottom = if (canDown) {
                            { moveSettings(index, settingsOrder.size - 1) }
                        } else null,
                    )
                    when (id) {
                        "super" -> SuperIslandSection(context, reorder)
                        "basic" -> BasicSettingsSection(context, reorder)
                        "effect" -> LyricEffectSection(context, reorder)
                        "display" -> DisplaySection(context, reorder)
                        "special" -> SpecialEffectsSection(context, reorder)
                        "background" -> IslandBackgroundSection(context, reorder)
                        "content" -> LyricContentSection(context, reorder)
                        "font" -> FontSection(context, reorder)
                        "extras" -> HyperlyricExtrasSection(context, reorder)
                    }
                }
            }

            if (CouiUiState.enabled.value) {
                // 凝光视效：COUI 原生页面壳（大标题折叠顶栏 + 顶栏按钮凝光材质 + 回弹滚动）。
                // 弹窗上游材质需要的窗口源在这里就地提供（非隔离模式，避免整窗强制离屏）
                ProvideCouiMenuBackdrop(isolateContent = false) { rootModifier ->
                    CouiSettingsShell(
                        title = "设置",
                        showTitle = false,
                        modifier = rootModifier,
                        actions = {
                            CouiTopBarIconButton(
                                icon = Icons.Filled.Refresh,
                                description = "重启状态栏",
                            ) {
                                SystemUiRestart.request(context, scope, onDenied = { showNoRoot = true })
                            }
                        },
                        content = settingsBody,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    settingsBody()
                }
            }
        }
        }
        }
        // 背景层：凝光皮肤走纯色（不引入动态着色器图层），Miuix 皮肤保持原流光背景
        if (CouiUiState.enabled.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDark) Color.Black else Color(0xFFE8F0FF)),
                content = settingsPageContent,
            )
        } else {
            AppBackground(isDarkTheme = isDark, content = settingsPageContent)
        }
    }
}

// ================= 通用组件 =================

/** 分组排序回调（3.0.777：主页区块 / 设置页分组 长按标题弹出排序菜单） */
data class SectionReorder(
    val canReorder: Boolean = false,
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
    val onMoveTop: (() -> Unit)? = null,
    val onMoveBottom: (() -> Unit)? = null,
)

/** 分组标题（3.0.777：canReorder=true 时长按标题弹 上移/下移/置顶/置底 排序菜单） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SectionTitle(
    text: String,
    subtitle: String? = null,
    canReorder: Boolean = false,
    collapsible: Boolean = false,
    expanded: Boolean = false,
    onToggleExpanded: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onMoveTop: (() -> Unit)? = null,
    onMoveBottom: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val skin = LocalHomeSkin.current
    // 凝光皮肤：标题换成 COUI 分组标题样式；长按排序菜单改用上游 Menu（贴标题弹出）
    val couiAnchor = rememberCouiMenuAnchor()
    if (CouiUiState.enabled.value) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    when {
                        // 可折叠 + 可排序：点击展开/收起，长按仍能弹排序菜单
                        collapsible && canReorder -> Modifier.combinedClickable(
                            onClick = { onToggleExpanded?.invoke() },
                            onLongClick = { couiAnchor.open { menuOpen = true } },
                        )
                        collapsible -> Modifier.clickable { onToggleExpanded?.invoke() }
                        canReorder -> Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { couiAnchor.open { menuOpen = true } },
                        )
                        else -> Modifier
                    }
                )
                .couiMenuAnchor(couiAnchor)
        ) {
            CouiSectionTitle(
                text = text,
                subtitle = subtitle,
                trailing = if (collapsible) {
                    { CouiSectionChevron(expanded) }
                } else {
                    null
                },
            )
            if (canReorder) {
                val dark = couiDark()
                CouiTheme(darkTheme = dark) {
                    CouiMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        items = listOf(
                            CouiMenuItem(value = "up", text = "上移", enabled = onMoveUp != null),
                            CouiMenuItem(value = "down", text = "下移", enabled = onMoveDown != null),
                            CouiMenuItem(value = "top", text = "置顶", enabled = onMoveTop != null),
                            CouiMenuItem(value = "bottom", text = "置底", enabled = onMoveBottom != null),
                        ),
                        onItemClick = { item ->
                            menuOpen = false
                            when (item.value) {
                            "up" -> onMoveUp?.invoke()
                            "down" -> onMoveDown?.invoke()
                            "top" -> onMoveTop?.invoke()
                            "bottom" -> onMoveBottom?.invoke()
                            }
                        },
                        anchor = couiAnchor,
                    )
                }
            }
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dim.padPageH, top = Dim.s5, end = Dim.padPageH, bottom = Dim.s3)
            .then(
                when {
                    collapsible && canReorder -> Modifier.combinedClickable(
                        onClick = { onToggleExpanded?.invoke() },
                        onLongClick = { menuOpen = true },
                    )
                    collapsible -> Modifier.clickable { onToggleExpanded?.invoke() }
                    canReorder -> Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { menuOpen = true },
                    )
                    else -> Modifier
                }
            )
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = text,
                    fontSize = Font.body,
                    fontWeight = FontWeight.Medium,
                    color = skin?.textPrimary ?: MiuixTheme.colorScheme.onSurface,
                )
                if (canReorder) {
                    Spacer(Modifier.width(Dim.s2))
                    Text(
                        text = "⇅",
                        fontSize = Font.label,
                        color = skin?.textSecondary ?: MiuixTheme.colorScheme.outline,
                    )
                }
                if (collapsible) {
                    Spacer(Modifier.width(Dim.s2))
                    Text(
                        text = if (expanded) "⌃" else "⌄",
                        fontSize = Font.label,
                        color = skin?.textSecondary ?: MiuixTheme.colorScheme.outline,
                    )
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = Font.label,
                    modifier = Modifier.padding(top = Dim.s1),
                    // 提醒性质，固定红色（不走皮肤色，深色/浅色皮肤下都要一眼看到）
                    color = Color(0xFFE53935),
                )
            }
        }
        if (canReorder) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        onMoveUp?.invoke()
                    },
                    enabled = onMoveUp != null,
                ) {
                    Text("上移")
                }
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        onMoveDown?.invoke()
                    },
                    enabled = onMoveDown != null,
                ) {
                    Text("下移")
                }
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        onMoveTop?.invoke()
                    },
                    enabled = onMoveTop != null,
                ) {
                    Text("置顶")
                }
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        onMoveBottom?.invoke()
                    },
                    enabled = onMoveBottom != null,
                ) {
                    Text("置底")
                }
            }
        }
    }
}

/** 分组卡片容器（自绘：圆角 18dp 走 Dim.rCard，与打轴器卡片同规格） */
@Composable
fun SectionCard(content: @Composable () -> Unit) {
    // 凝光皮肤：换成 COUI 的 24dp 大圆角成组卡片
    if (CouiUiState.enabled.value) {
        CouiSectionCard(content)
        return
    }
    SettingCard(modifier = Modifier.padding(horizontal = Dim.padPageH)) {
        content()
    }
}

/** 内嵌分组：给一组相关设置项加组标题+内容左缩进，视觉上归成一个选项组
 * （如"下一句歌词"三件套：预显示下一句 / 下一句动画 / 文字大小比例）。
 * 3.0.638：去掉边框（用户反馈加框不好看），改用标题分隔+缩进。 */
@Composable
fun SubGroupBox(title: String, content: @Composable () -> Unit) {
    // 凝光皮肤：小标题换成 COUI 的组内说明样式
    if (CouiUiState.enabled.value) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CouiSubGroupTitle(title)
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                content()
            }
        }
        return
    }
    Column(
        modifier = Modifier
            .padding(horizontal = Dim.padPageH, vertical = Dim.s0)
            .fillMaxWidth()
    ) {
        Text(
            text = title,
            fontSize = Font.label,
            color = MiuixTheme.colorScheme.outline,
            modifier = Modifier.padding(start = Dim.s4, top = Dim.s3, bottom = Dim.s0)
        )
        Column(modifier = Modifier.padding(start = Dim.s1, end = Dim.s1)) {
            content()
        }
    }
}

/** 数字输入项（对话框输入，状态化：写 prefs + 更新 UI；inline=true 时行内直接输入，不弹第二层弹窗，避免嵌套 Dialog 点击失效） */
@Composable
fun NumberInputPreference(
    context: android.content.Context,
    key: String,
    default: Int,
    range: IntRange,
    unit: String = "",
    title: String,
    extraWrite: ((Int) -> Unit)? = null,
    bridge: PrefsBridge = MasterPrefs,
    inline: Boolean = false
) {
    var value by remember { mutableStateOf(bridge.getInt(context, key, default)) }
    var showDialog by remember { mutableStateOf(false) }

    // 凝光皮肤下不走行内输入（自绘输入框与 COUI 外观不统一），改用 COUI 箭头行 + COUI 输入弹窗
    if (inline && !CouiUiState.enabled.value) {
        // 行内数字输入：不弹第二层弹窗（嵌套 Dialog 点击失效），点文本框直接改（数字键盘），
        // 用字符串状态驱动显示：允许删空（删空不写 prefs、不回显旧值），非空数字即时写 prefs
        var text by remember { mutableStateOf(bridge.getInt(context, key, default).toString()) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) { }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = Font.body, color = MiuixTheme.colorScheme.onSurface)
                Text("${text.ifEmpty { "0" }}$unit", fontSize = Font.label, color = Color.Gray)
            }
            BasicTextField(
                value = text,
                onValueChange = { input ->
                    val cleaned = input.filter { it.isDigit() }.take(3)
                    text = cleaned
                    val parsed = cleaned.toIntOrNull()
                    if (parsed != null) {
                        val clamped = parsed.coerceIn(range.first, range.last)
                        if (clamped != value) {
                            value = clamped
                            bridge.setInt(context, key, clamped)
                            extraWrite?.invoke(clamped)
                        }
                    }
                    // 删空时 parsed == null：保留空显示，不写 prefs（避免 0 被 clamp 成 1）
                },
                textStyle = TextStyle(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .width(76.dp)
                    .background(Color(0xFFF2F3F5), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        return
    }

    PrefArrow(
        title = title,
        summary = "$value$unit",
        onClick = { showDialog = true }
    )

    if (showDialog) {
        MiuixInputDialog(
            title = title,
            initial = value.toString(),
            rangeLabel = "输入数值（${range.first}-${range.last}$unit）",
            onConfirm = { input ->
                val parsed = input.trim().toIntOrNull()
                if (parsed != null) {
                    val clamped = parsed.coerceIn(range.first, range.last)
                    value = clamped
                    bridge.setInt(context, key, clamped)
                    extraWrite?.invoke(clamped)
                }
            },
            onDismiss = { showDialog = false }
        )
    }
}

/** 文本输入项（对话框输入，状态化） */
@Composable
fun StringInputPreference(
    context: android.content.Context,
    key: String,
    default: String,
    title: String,
    summary: String,
    extraWrite: ((String) -> Unit)? = null,
    bridge: PrefsBridge = MasterPrefs
) {
    var value by remember { mutableStateOf(bridge.getString(context, key, default) ?: default) }
    var showDialog by remember { mutableStateOf(false) }

    PrefArrow(
        title = title,
        summary = summary,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        MiuixInputDialog(
            title = title,
            initial = value,
            rangeLabel = "输入内容",
            numericOnly = false,
            onConfirm = { input ->
                val newValue = input.trim()
                value = newValue
                bridge.setString(context, key, newValue)
                extraWrite?.invoke(newValue)
            },
            onDismiss = { showDialog = false }
        )
    }
}

/** 颜色输入项（预设色板对话框，状态化） */
@Composable
fun ColorInputPreference(
    context: android.content.Context,
    key: String,
    default: Int,
    title: String,
    summary: String,
    presetColors: List<Pair<Int, String>>,
    extraWrite: ((Int) -> Unit)? = null,
    bridge: PrefsBridge = MasterPrefs
) {
    var value by remember { mutableStateOf(bridge.getInt(context, key, default)) }
    var showDialog by remember { mutableStateOf(false) }

    PrefArrow(
        title = title,
        summary = summary,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        MiuixColorDialog(
            title = title,
            presetColors = presetColors,
            onSelected = { c ->
                value = c
                bridge.setInt(context, key, c)
                extraWrite?.invoke(c)
            },
            onDismiss = { showDialog = false }
        )
    }
}

/** 开启前的二次确认文案（试验性功能专用）。传了就表示"打开前必须先点同意"。
 *  [onceKey] 非空时表示"只第一次弹"：同意后把该键置 true，之后开启不再弹框。 */
data class SwitchConfirmSpec(
    val title: String,
    val message: String,
    val confirmText: String = "同意并开启",
    val dismissText: String = "取消",
    val onceKey: String? = null
)

/** 开关项（状态化：写 prefs + 更新 UI + 可选双写） */
@Composable
fun PrefSwitch(
    context: android.content.Context,
    key: String,
    default: Boolean,
    title: String,
    summary: String,
    extraWrite: ((Boolean) -> Unit)? = null,
    bridge: PrefsBridge = MasterPrefs,
    confirmOnEnable: SwitchConfirmSpec? = null
) {
    var checked by remember { mutableStateOf(bridge.getBoolean(context, key, default)) }
    var showConfirm by remember { mutableStateOf(false) }

    fun applyValue(value: Boolean) {
        checked = value
        bridge.setBoolean(context, key, value)
        extraWrite?.invoke(value)
    }

    val toggle: (Boolean) -> Unit = { value ->
        // 试验性功能：只拦"关→开"这一步；[onceKey] 有值且已同意过就不再弹（只第一次弹）。
        val spec = confirmOnEnable
        val alreadyAgreed = spec?.onceKey?.let { bridge.getBoolean(context, it, false) } ?: false
        if (value && !checked && spec != null && !alreadyAgreed) {
            showConfirm = true
        } else {
            applyValue(value)
        }
    }

    // 凝光皮肤：改用 COUI 的 Item + 凝光开关；确认弹窗逻辑原样沿用
    if (CouiUiState.enabled.value) {
        CouiSwitchRow(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = toggle
        )
    } else {
        SettingSwitchRow(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = toggle
        )
    }

    if (showConfirm) {
        val spec = confirmOnEnable!!
        MiuixConfirmDialog(
            title = spec.title,
            message = spec.message,
            confirmText = spec.confirmText,
            dismissText = spec.dismissText,
            onConfirm = {
                showConfirm = false
                // 记住"已同意"，之后每次开启都不再弹框（只第一次提示）
                spec.onceKey?.let { bridge.setBoolean(context, it, true) }
                applyValue(true)
            },
            onDismiss = { showConfirm = false }
        )
    }
}

/**
 * 箭头行（标题+副标题，右侧 ›，点进下一层）。
 * 统一走分量库的 SettingValueRow，字号行高由 Tokens 定，与打轴器同一套规格。
 */
@Composable
fun PrefArrow(
    title: String,
    summary: String? = null,
    onClick: () -> Unit
) {
    if (CouiUiState.enabled.value) {
        CouiArrowRow(title = title, summary = summary, onClick = onClick)
    } else {
        SettingValueRow(title = title, summary = summary, onClick = onClick)
    }
}

/** 分段选择行（两边/多边选择）：凝光皮肤下走 COUI 分段控件 */
@Composable
fun PrefChoiceRow(
    title: String,
    summary: String? = null,
    options: List<String>,
    selectedIndex: Int,
    showDivider: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    if (CouiUiState.enabled.value) {
        CouiChoiceRow(title, summary, options, selectedIndex, showDivider, onSelectedIndexChange)
    } else {
        SettingChoiceRow(title, summary, options, selectedIndex, showDivider, onSelectedIndexChange)
    }
}

/** 歌词时间偏移：±3 秒、0.1 秒步进；存储单位毫秒，点中间数值可归零。 */
@Composable
fun TimeOffsetPreference(
    context: android.content.Context,
    key: String,
    default: Int,
    title: String,
    summary: String
) {
    var value by remember { mutableStateOf(MasterPrefs.getInt(context, key, default)) }

    fun apply(next: Int) {
        val clamped = next.coerceIn(-3000, 3000)
        if (clamped == value) return
        value = clamped
        MasterPrefs.setInt(context, key, clamped)
    }

    // 凝光皮肤：改成 COUI 行 + 圆形步进钮
    if (CouiUiState.enabled.value) {
        CouiStepperRow(
            title = title,
            summary = "$summary｜当前 ${String.format("%+.1f", value / 1000f)} 秒",
            valueText = String.format("%+.1f", value / 1000f),
            onStep = { dir -> apply(value + dir * 100) },
            onReset = { apply(0) },
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = Font.body, color = MiuixTheme.colorScheme.onSurface)
            Text(
                "$summary｜当前 ${String.format("%+.1f", value / 1000f)} 秒",
                fontSize = Font.label,
                color = Color.Gray
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeOffsetStepButton("−") { apply(value - 100) }
            Text(
                String.format("%+.1f", value / 1000f),
                fontSize = Font.body,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .clickable { apply(0) }
            )
            TimeOffsetStepButton("+") { apply(value + 100) }
        }
    }
}

/** 步进小圆钮。 */
@Composable
private fun TimeOffsetStepButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xFFF2F3F5))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
    }
}

/** 下拉选择项（状态化：写 prefs + 更新 UI + 可选双写） */
@Composable
fun PrefSpinner(
    context: android.content.Context,
    key: String,
    default: Int,
    options: List<Pair<String, String>>,
    title: String,
    summary: String,
    extraWrite: ((Int) -> Unit)? = null,
    bridge: PrefsBridge = MasterPrefs,
    /** 选项对应的实际存储值（不传则存位置下标）。
     *  用于"从列表里删掉几个选项但老值不能错位"的场景（如歌词动画效果删了三点倒计时/歌名+三点）。 */
    values: List<Int>? = null,
    /** 写入前置守卫：返回 false 时本次选择不写 prefs、不改 UI（用于「总开关没开 → 写不进去」的场景）。 */
    guard: (() -> Boolean)? = null,
    /** 被守卫拦下时的回调（给用户一个可见提示）。 */
    onGuarded: (() -> Unit)? = null,
    /** 说明随当前选中项变化的场景：传入后优先于 summary，按当前下标返回对应说明。 */
    summaryOf: ((Int) -> String)? = null
) {
    val valueOf = { i: Int -> values?.getOrNull(i) ?: i }
    var index by remember {
        mutableStateOf(
            run {
                val cur = bridge.getInt(context, key, default)
                if (values == null) {
                    cur
                } else {
                    val pos = values.indexOf(cur)
                    if (pos >= 0) {
                        pos
                    } else {
                        // 老值已被删掉（如 2/3）：回落到第一项，并把存储值一并纠正
                        val fallback = values.indexOf(default).let { if (it >= 0) it else 0 }
                        bridge.setInt(context, key, valueOf(fallback))
                        fallback
                    }
                }
            }
        )
    }
    val onSel: (Int) -> Unit = onSel@{ i ->
        if (guard != null && !guard.invoke()) {
            onGuarded?.invoke()
            return@onSel
        }
        index = i
        bridge.setInt(context, key, valueOf(i))
        extraWrite?.invoke(valueOf(i))
    }
    val summaryText = summaryOf?.invoke(index) ?: summary
    val optionLabels = options.map { it.first }
    // 凝光皮肤：改用 COUI 行 + 行下方内联展开选项
    if (CouiUiState.enabled.value) {
        CouiDropdownRow(
            title = title,
            summary = summaryText,
            options = optionLabels,
            selectedIndex = index,
            onSelectedIndexChange = onSel
        )
    } else {
        // 统一走分量库的 SettingDropdownRow（右侧当前值 + 上下箭头，点开细长小列表）
        SettingDropdownRow(
            title = title,
            summary = summaryText,
            options = optionLabels,
            selectedIndex = index,
            onSelectedIndexChange = onSel
        )
    }
}

// ================= 自绘弹窗组件（不依赖 Miuix WindowDialog/OverlayDialog） =================

/** 二次确认弹窗（白色卡片 + 说明文字 + 取消/确认两键）。
 *  用于"试验性功能默认关闭，开启前必须点同意"的场景。 */
@Composable
fun MiuixConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "同意并开启",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // 凝光皮肤：换成 COUI 弹窗
    if (CouiUiState.enabled.value) {
        CouiConfirmDialog(
            title = title,
            message = message,
            confirmText = confirmText,
        dismissText = dismissText,
            onConfirm = onConfirm,
            onDismiss = onDismiss
        )
        return
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 480f),
        label = "confirmScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(200),
        label = "confirmAlpha"
    )
    val bg = androidx.compose.ui.graphics.Color.White
    val fg = androidx.compose.ui.graphics.Color(0xFF000000)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material.Surface(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .padding(vertical = 24.dp)
                .graphicsLayer { this.scaleX = scale; this.scaleY = scale; this.alpha = alpha },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = bg,
            elevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)) {
                Text(
                    text = title,
                    color = fg,
                    style = MiuixTheme.textStyles.title2
                )
                Text(
                    text = message,
                    color = fg.copy(alpha = 0.72f),
                    style = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.padding(top = 10.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    androidx.compose.material.TextButton(onClick = onDismiss) {
                        Text(text = dismissText, color = fg.copy(alpha = 0.6f))
                    }
                    androidx.compose.material.TextButton(onClick = onConfirm) {
                        Text(text = confirmText, color = androidx.compose.ui.graphics.Color(0xFF2196F3))
                    }
                }
            }
        }
    }
}

/** 通用选择弹窗（窗口级 Dialog + Miuix 卡片外观，替代 OverlayDialog/WindowDialog） */
@Composable
fun MiuixOptionDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedIndex: Int = -1,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // 凝光皮肤：换成 COUI 列表弹窗
    if (CouiUiState.enabled.value) {
        CouiOptionDialog(title, options, selectedIndex, onSelected, onDismiss)
        return
    }
    // 弹出动画：scale 由小放大 + 弹性回弹 + 渐显（对齐老词幕的弹出效果）
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 480f),
        label = "popScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(200),
        label = "popAlpha"
    )
    // 老词幕配色：白底、黑字、选中项蓝色
    val bg = androidx.compose.ui.graphics.Color.White
    val fg = androidx.compose.ui.graphics.Color(0xFF000000)
    val selectedColor = androidx.compose.ui.graphics.Color(0xFF2196F3)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material.Surface(
            modifier = Modifier
                .fillMaxWidth(0.70f)
                .heightIn(max = 460.dp)
                .padding(vertical = 24.dp)
                .graphicsLayer { this.scaleX = scale; this.scaleY = scale; this.alpha = alpha },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = bg,
            elevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                // 标题：黑色
                Text(
                    text = title,
                    color = fg,
                    style = MiuixTheme.textStyles.title2,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                )
                androidx.compose.material.Divider(
                    modifier = Modifier.padding(horizontal = Dim.s5),
                    color = fg.copy(alpha = 0.14f)
                )
                // 选项区：限高 + 可滚动（选项多如动画效果 26 项也能往下滑选）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(top = 4.dp, bottom = 4.dp)
                ) {
                    options.forEachIndexed { i, (label, summary) ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 选中项：整行浅蓝高亮背景（对齐 Miuix/老词幕选中态），未选中透明
                            val isSelected = i == selectedIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isSelected) {
                                            Modifier.background(androidx.compose.ui.graphics.Color(0xFFE3F2FD))
                                        } else Modifier
                                    )
                                    .clickable { onSelected(i); onDismiss() }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isSelected) "✓ $label" else label,
                                    color = if (isSelected) selectedColor else fg,
                                    style = MiuixTheme.textStyles.body1
                                )
                            }
                            if (summary.isNotEmpty()) {
                                Text(
                                    text = summary,
                                    style = MiuixTheme.textStyles.body2,
                                    color = fg.copy(alpha = 0.55f),
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                                )
                            }
                            // 每项分隔线，选项独立分开
                            androidx.compose.material.Divider(
                                modifier = Modifier.padding(horizontal = Dim.s5),
                                color = fg.copy(alpha = 0.08f)
                            )
                        }
                    }
                }
                androidx.compose.material.Divider(
                    modifier = Modifier.padding(horizontal = Dim.s5),
                    color = fg.copy(alpha = 0.14f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "取消",
                        color = fg.copy(alpha = 0.75f),
                        style = MiuixTheme.textStyles.body1
                    )
                }
            }
        }
    }
}

/**
 * 数值/文本输入弹窗（替代 Miuix WindowDialog）。
 *
 * @param numericOnly 数字键盘 + 只留数字/负号/小数点。默认 true 保持原有数值项行为；
 *                    文本类（如屏蔽词正则）必须传 false，否则中文、括号、反斜杠会被过滤掉，
 *                    表现就是「只能输入数字」。
 */
@Composable
fun MiuixInputDialog(
    title: String,
    initial: String,
    rangeLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    numericOnly: Boolean = true
) {
    // 凝光皮肤：换成 COUI 输入弹窗
    if (CouiUiState.enabled.value) {
        CouiInputDialog(
            title = title,
            initial = initial,
            hint = rangeLabel,
            numericOnly = numericOnly,
            onConfirm = onConfirm,
            onDismiss = onDismiss
        )
        return
    }
    var text by remember { mutableStateOf(initial) }
    // 老词幕同款：Miuix OverlayDialog 居中卡片 + 取消/确定（确定蓝色高亮、SpaceBetween 布局）
    OverlayDialog(
        show = true,
        title = title,
        summary = rangeLabel,
        backgroundColor = MiuixTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            TextField(
                value = text,
                onValueChange = { new ->
                    text = if (numericOnly) {
                        new.filter { it.isDigit() || it == '-' || it == '.' }
                    } else {
                        new
                    }
                },
                label = rangeLabel,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numericOnly) KeyboardType.Number else KeyboardType.Text,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "确定",
                    onClick = { onConfirm(text); onDismiss() },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 解析 #RRGGBB / #AARRGGBB（# 可省），失败返回 null。 */
internal fun parseHexColor(raw: String): Int? {
    val t = raw.trim().removePrefix("#")
    if (t.length != 6 && t.length != 8) return null
    if (t.any { it !in "0123456789abcdefABCDEF" }) return null
    return runCatching {
        val v = t.toLong(16)
        if (t.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }.getOrNull()
}

/** 颜色选择弹窗（替代 Miuix WindowDialog 色板） */
@Composable
fun MiuixColorDialog(
    title: String,
    presetColors: List<Pair<Int, String>>,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    summary: String = "点击色块选择歌词文字颜色",
    allowCustomHex: Boolean = false,
    initialHexColor: Int? = null,
) {
    var hexText by remember {
        mutableStateOf(
            initialHexColor?.let { "#%06X".format(it and 0xFFFFFF) } ?: "#"
        )
    }
    // 凝光皮肤：换成 COUI 色板弹窗（要手填 HEX 的那一档没有色板，仍走下面的 Miuix 弹窗）
    if (CouiUiState.enabled.value && !allowCustomHex) {
        CouiColorDialog(title, presetColors, onSelected, onDismiss)
        return
    }
    // 老词幕同款：Miuix 原生 OverlayDialog（白底黑字、Miuix 材质与弹出动画），内容为色块网格
    OverlayDialog(
        show = true,
        title = title,
        summary = summary,
        backgroundColor = MiuixTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            presetColors.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row.forEach { (c, name) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(c))
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                                    .clickable { onSelected(c); onDismiss() }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = name,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (allowCustomHex) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = hexText,
                        onValueChange = { new ->
                            hexText = new.filter { it.isDigit() || it in "abcdefABCDEF#" }.take(9)
                        },
                        label = "自定义颜色 #RRGGBB",
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(parseHexColor(hexText) ?: 0xFFFFFFFF.toInt()))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = "应用自定义色",
                        onClick = {
                            parseHexColor(hexText)?.let {
                                onSelected(it)
                                onDismiss()
                            }
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dim.s5),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss
                )
            }
        }
    }
}

/** 关于页（独立页面，带流光背景动画） */
@Composable
internal fun AboutScreen(onBack: () -> Unit, onOpenSponsors: () -> Unit = {}) {
    val context = LocalContext.current
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
    val packageName = context.packageName

    SkinTheme {
        val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
        SideEffect {
            val window = (context as Activity).window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !isDark
        }

        // 应用全局背景（受"流光背景"开关控制）
        AppBackground(
            isDarkTheme = isDark,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent
            ) { _ ->
                    val pageBack: @Composable () -> Unit = {
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isDark) Color(0xFF202024).copy(alpha = 0.80f)
                                    else Color.White.copy(alpha = 0.68f),
                                    RoundedCornerShape(14.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isDark) Color.White.copy(alpha = 0.14f)
                                    else Color.White.copy(alpha = 0.80f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { onBack() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("← 返回", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                        }
                    }
                    val pageBody: @Composable () -> Unit = {
                        // 内容
                        // 应用名 + 副标题
                        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                            if (CouiUiState.enabled.value) {
                                com.itos.coui.theme.CouiTheme(
                                    darkTheme = MiuixTheme.colorScheme.background.luminance() < 0.5f
                                ) {
                                    com.itos.coui.text.Text(
                                        text = "词岛",
                                        kind = com.itos.coui.text.CouiTextKind.HomeTitle,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    com.itos.coui.text.Text(
                                        text = "超级岛歌词 · Xposed 模块",
                                        kind = com.itos.coui.text.CouiTextKind.ItemSummary,
                                    )
                                }
                            } else {
                                Text("词岛", style = MiuixTheme.textStyles.title1, color = MiuixTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "超级岛歌词 · Xposed 模块",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        if (CouiUiState.enabled.value) com.liuxiansheng.lyricon.app.coui.CouiPageDivider()
                        else androidx.compose.material.Divider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(Modifier.height(12.dp))

                        // 版本信息列表
                        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                            AboutItem("版本", versionName)
                            AboutItem("包名", packageName)
                            AboutItem("作者", "liuxiansheng（刘先生）")
                            AboutItem("适配", "小米澎湃 OS / HyperOS")
                            AboutItem("功能", "超级岛歌词 · 卡拉OK逐字 · 歌词源管理")
                        }

                        Spacer(Modifier.height(12.dp))
                        if (CouiUiState.enabled.value) com.liuxiansheng.lyricon.app.coui.CouiPageDivider()
                        else androidx.compose.material.Divider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(Modifier.height(12.dp))

                        // 赞助鸣谢入口（1.5.145）：点进去是完整名单页
                        val sponsorAccent = LocalHomeSkin.current?.accent ?: Color(0xFF4F7CFF)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSponsors() }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "赞助鸣谢",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "谢谢这些朋友，让词岛能一直做下去。",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            Text(
                                "›",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = sponsorAccent.copy(alpha = 0.85f),
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        if (CouiUiState.enabled.value) com.liuxiansheng.lyricon.app.coui.CouiPageDivider()
                        else androidx.compose.material.Divider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(Modifier.height(12.dp))

                        // 致谢（完整列表）
                        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                            Text(
                                "致谢与开源许可",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "本工程以 GPL-3.0 发布。下列代码版权归原作者，按各自许可证使用，点一下可打开项目主页。",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            )
                            Spacer(Modifier.height(10.dp))
                            AboutCredit(
                                name = "HyperLyric",
                                desc = "超级岛歌词注入、呈现协调、锁屏重建回填、行视图与逐字渲染",
                                url = "https://github.com/limczhh/HyperLyric",
                                author = "limczhh",
                                license = "GPL-3.0",
                            )
                            AboutCredit(
                                name = "HyperIsland",
                                desc = "岛背景、实时模糊、液态 / 柔光玻璃材质、HDR 高光钩子",
                                url = "https://github.com/1812z/HyperIsland",
                                author = "1812z",
                                license = "MIT",
                            )
                            AboutCredit(
                                name = "Lyricon",
                                desc = "歌词模型、LRC / ELRC 解析思路、行视图结构、Provider 与媒体信息对接",
                                url = "https://github.com/tomakino/lyricon",
                                author = "Proify / Tomakino",
                                license = "Apache-2.0",
                            )
                            AboutCredit(
                                name = "Miuix KMP",
                                desc = "设置页界面框架",
                                url = "https://github.com/kyantuto/miuix",
                                author = "kyantuto",
                                license = "",
                            )
                            AboutCredit(
                                name = "LSPosed / libxposed",
                                desc = "Xposed 模块 API 与框架",
                                url = "https://github.com/LSPosed/LSPosed",
                                author = "LSPosed",
                                license = "",
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "其余代码（画布绘制、歌词动画、皮肤、设置页、字体库、歌词源管理、逐字进度）均为本人独立编写。",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            )
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                    if (CouiUiState.enabled.value) {
                        CouiSettingsShell(
                            title = "关于",
                            navigationIcon = { CouiTopBarBackButton { onBack() } },
                        ) { pageBody() }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) { pageBack() }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            ) { pageBody() }
                        }
                    }
            }
        }
    }
}

/** 赞助按钮：主题色描边小胶囊 */
@Composable
private fun SponsorButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accent)
    }
}

/** 赞助收款码弹窗：显示二维码 + 保存到相册 */
@Composable
private fun SponsorQrDialog(target: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isWechat = target == 1
    val accent = if (isWechat) Color(0xFF07C160) else Color(0xFF1677FF)
    val title = if (isWechat) "微信赞助" else "支付宝赞助"
    val painter = painterResource(
        if (isWechat) com.liuxiansheng.lyricon.app.R.drawable.sponsor_wechat
        else com.liuxiansheng.lyricon.app.R.drawable.sponsor_alipay
    )
    if (CouiUiState.enabled.value) {
        com.liuxiansheng.lyricon.app.coui.CouiContentDialog(
            title = title,
            dismissText = "关闭",
            onDismiss = onDismiss,
        ) {
            val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painter,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (isWechat) "微信扫一扫，或用另一台手机扫这张图"
                    else "支付宝扫一扫，或用另一台手机扫这张图",
                    fontSize = Font.label,
                    color = (if (dark) Color.White else Color.Black).copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))
                SponsorButton("保存到相册", accent) { saveQrToGallery(context, target) }
            }
        }
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.background, RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MiuixTheme.textStyles.title2, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            Image(
                painter = painter,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(8.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (isWechat) "微信扫一扫，或用另一台手机扫这张图"
                else "支付宝扫一扫，或用另一台手机扫这张图",
                fontSize = Font.label,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SponsorButton("保存到相册", accent) { saveQrToGallery(context, target) }
                SponsorButton("关闭", MiuixTheme.colorScheme.onSurface.copy(alpha = 0.80f), onDismiss)
            }
        }
    }
}

/** 把收款码写进相册 Pictures/词岛（Q 以上无需存储权限） */
private fun saveQrToGallery(context: Context, target: Int) {
    val isWechat = target == 1
    val resId = if (isWechat) com.liuxiansheng.lyricon.app.R.drawable.sponsor_wechat
    else com.liuxiansheng.lyricon.app.R.drawable.sponsor_alipay
    val fileName = if (isWechat) "cid_ao_wechat.png" else "cid_ao_alipay.png"
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/词岛")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建相册文件")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            context.resources.openRawResource(resId).use { input -> input.copyTo(out) }
        } ?: error("无法写入相册文件")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        }
        Toast.makeText(context, "已保存到相册（Pictures/词岛）", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 致谢项：项目名 + 许可证标签 + 作者 + 用途，可点击打开对应链接 */
@Composable
private fun AboutCredit(
    name: String,
    desc: String,
    url: String = "",
    author: String = "",
    license: String = "",
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .clickable(enabled = url.isNotEmpty()) {
                if (url.isNotEmpty()) {
                    runCatching {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)
                        )
                        context.startActivity(intent)
                    }
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                fontSize = Font.body,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (license.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(
                            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        license,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    )
                }
            }
        }
        if (author.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                "作者　$author",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            desc,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
        if (url.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                url,
                fontSize = Font.label,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 关于信息行：左标签 + 右值 */
@Composable
private fun AboutItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

// 各组实现见 SettingsContent.kt

/** 性能监控快照（调试区块）：把模块推来的 JSON 转成能直接看的几行 */
@Composable
private fun PerfSnapshotDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(renderPerfSnapshot(context)) }
    if (CouiUiState.enabled.value) {
        com.liuxiansheng.lyricon.app.coui.CouiContentDialog(
            title = "性能数据",
            dismissText = "关闭",
            onDismiss = onDismiss,
        ) {
            val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Text(
                    text,
                    fontSize = Font.label,
                    color = (if (dark) Color.White else Color.Black).copy(alpha = 0.85f),
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SponsorButton("刷新", (if (dark) Color.White else Color.Black).copy(alpha = 0.80f)) {
                        text = renderPerfSnapshot(context)
                    }
                    SponsorButton("复制", Color(0xFF1677FF)) {
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("词岛性能数据", text)
                            )
                            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.background, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Text("性能数据", style = MiuixTheme.textStyles.title2, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            Text(
                text,
                fontSize = Font.label,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SponsorButton("刷新", MiuixTheme.colorScheme.onSurface.copy(alpha = 0.80f)) {
                    text = renderPerfSnapshot(context)
                }
                SponsorButton("复制", Color(0xFF1677FF)) {
                    runCatching {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("词岛性能数据", text)
                        )
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                }
                SponsorButton("关闭", MiuixTheme.colorScheme.onSurface.copy(alpha = 0.60f), onDismiss)
            }
        }
    }
}

private fun renderPerfSnapshot(context: Context): String {
    val prefs = context.getSharedPreferences("live_state", Context.MODE_PRIVATE)
    val raw = prefs.getString("perf_snapshot", null)
        ?: return "还没有数据。\n\n先打开上面的「性能监控」，放一首歌，等 5 秒后再进来刷新。"
    return runCatching {
        val o = org.json.JSONObject(raw)
        val at = prefs.getLong("perf_snapshot_at", 0L)
        val sb = StringBuilder()
        if (at > 0L) {
            sb.append("更新时间 ").append(
                android.text.format.DateFormat.format("HH:mm:ss", at)
            ).append('\n')
        }
        sb.append("采样 ").append(o.optLong("uptimeMs") / 1000).append(" 秒\n\n")
        val f = o.optJSONObject("frames")
        if (f != null) {
            sb.append("帧：共 ").append(f.optLong("total"))
                .append("，超 20ms ").append(f.optLong("slow"))
                .append("，超 50ms ").append(f.optLong("slow50"))
                .append("，超 100ms ").append(f.optLong("slow100"))
                .append("，最大间隔 ").append(f.optLong("maxGapMs")).append(" ms\n\n")
        }
        val pss = o.optLong("pssKb", 0L)
        val javaUsed = o.optLong("javaUsedKb", 0L)
        val javaMax = o.optLong("javaMaxKb", 0L)
        if (pss > 0L) {
            sb.append("内存：整进程 ").append(pss / 1024).append(" MB")
                .append("，Java 堆 ").append(javaUsed / 1024)
                .append(" / ").append(javaMax / 1024).append(" MB\n\n")
        }
        val hooks = o.optJSONArray("hooks")
        if (hooks == null || hooks.length() == 0) {
            sb.append("这一轮没有 hook 采样（可能没在放歌）。")
        } else {
            sb.append("hook 耗时（平均 / 最大）：\n")
            for (i in 0 until minOf(hooks.length(), 12)) {
                val h = hooks.optJSONObject(i) ?: continue
                sb.append("· ").append(h.optString("name")).append("  ")
                    .append(fmtMicros(h.optDouble("avgUs", 0.0))).append(" / ")
                    .append(fmtMicros(h.optDouble("maxUs", 0.0))).append("  ×")
                    .append(h.optLong("count")).append('\n')
            }
        }
        sb.toString()
    }.getOrDefault("数据解析失败：$raw")
}

private fun fmtMicros(v: Double): String =
    if (v >= 1000) String.format(java.util.Locale.CHINA, "%.1fms", v / 1000)
    else String.format(java.util.Locale.CHINA, "%.0fus", v)

/** 扫 filesDir/themes 下已导入的主题包，返回 (id, 显示名) */
private fun importedThemes(context: Context): List<Pair<String, String>> =
    ArtThemeStore.list(context).map { it.id to it.name }

/** 更多设置页（背景设置 / 主题设置 / 备份） */
@Composable
internal fun AdvancedSettingsScreen(onBack: () -> Unit, onOpenAbout: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 已导入主题包数量：导入/删除后 revision 变化会自动刷新
    val themeCount = remember(ArtThemeLoader.revision) { ArtThemeStore.list(context).size }
    // 1.5.143：赞助从「关于词岛」搬到这里；0=未打开 1=微信 2=支付宝
    var sponsorTarget by remember { mutableStateOf(0) }
    // 性能监控快照弹窗（调试区块）
    var perfSnapshotOpen by remember { mutableStateOf(false) }
    // 调试项总开关：关着时调试里只留这一个开关，其余排障项整体收起
    var debugPanelOn by remember {
        val masterPrefs = com.liuxiansheng.lyricon.app.util.MasterPrefs
        val on = masterPrefs.getBoolean(context, "pref_debug_panel_enabled", false)
        // 总开关关着时子项一律复位成关：防止上一次留下的 true 在后台继续生效
        if (!on) {
            masterPrefs.setBoolean(context, "pref_debug_logging", false)
            masterPrefs.setBoolean(context, "lyricsource_diag_enabled", false)
            masterPrefs.setBoolean(context, "pref_debug_perf_monitor", false)
        }
        mutableStateOf(on)
    }

    // —— 主题包：从用户选择的 zip 导入到 filesDir/themes/<id>（不占 APK 体积） ——
    val themeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { ArtThemeImporter.import(context, uri) }
                if (r == null) {
                    Toast.makeText(
                        context, "导入失败：不是有效的主题包", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context, "已导入主题：${r.name}，可到首页主题条切换", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // —— 备份：导出到用户选择的位置（SAF，无需存储权限） ——
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val json = SettingsBackup.exportJson(context)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选文件")
            }.onSuccess {
                android.widget.Toast.makeText(
                    context, "设置已导出，可拷到其它设备导入", android.widget.Toast.LENGTH_SHORT
                ).show()
            }.onFailure { e ->
                android.widget.Toast.makeText(
                    context, "导出失败：${e.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // —— 备份：从用户选择的文件恢复 ——
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: error("无法读取所选文件")
                SettingsBackup.importJson(context, text)
            }.onSuccess { count ->
                android.widget.Toast.makeText(
                    context,
                    "已恢复 $count 项设置，点右上角「重启状态栏」后全部生效",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }.onFailure { e ->
                android.widget.Toast.makeText(
                    context, "导入失败：${e.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    SkinTheme {
        val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
        SideEffect {
            val window = (context as Activity).window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !isDark
        }

        // 应用全局背景（受"流光背景"开关控制）
        AppBackground(
            isDarkTheme = isDark,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent
            ) { _ ->
                    val pageBack: @Composable () -> Unit = {
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isDark) Color(0xFF202024).copy(alpha = 0.80f)
                                    else Color.White.copy(alpha = 0.68f),
                                    RoundedCornerShape(14.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isDark) Color.White.copy(alpha = 0.14f)
                                    else Color.White.copy(alpha = 0.80f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { onBack() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("← 返回", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                        }
                    }
                    val pageBody: @Composable () -> Unit = {
                        // 内容
                        // 听歌记录（开关从听歌记录页移到这里）
                        SectionTitle("听歌记录")
                        SectionCard {
                            var recordEnabled by remember {
                                mutableStateOf(ListenRecordsStore.isEnabled(context))
                            }
                            val onRecordToggle: (Boolean) -> Unit = { value ->
                                recordEnabled = value
                                ListenRecordsStore.setEnabled(context, value)
                            }
                            val recordSummary = "由词岛模块在系统里抓取，不需要保持 App 打开；" +
                                "关掉后不再新增记录，已有的今日记录和累计数字都保留"
                            // 凝光模式要走 COUI 行：axis 的标题色是写死的深色，黑底下不可读
                            if (CouiUiState.enabled.value) {
                                CouiSwitchRow(
                                    title = "自动记录听歌",
                                    summary = recordSummary,
                                    checked = recordEnabled,
                                    onCheckedChange = onRecordToggle,
                                )
                            } else {
                                SettingSwitchRow(
                                    title = "自动记录听歌",
                                    summary = recordSummary,
                                    checked = recordEnabled,
                                    onCheckedChange = onRecordToggle,
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 背景设置
                        SectionTitle("背景设置")
                        SectionCard {
                            PrefSwitch(
                                context = context,
                                key = "pref_advanced_流光背景",
                                default = true,
                                title = "流光背景",
                                summary = "开启后所有页面背景显示流光动画效果",
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // 界面风格：凝光视效 / 原 Miuix 皮肤，两套共用同一份配置，切了立刻换外观
                        SectionTitle("界面风格")
                        SectionCard {
                            var couiNow by remember { mutableStateOf(CouiSkinPref.isEnabled(context)) }
                            val styleOptions = listOf("MIUI", "凝光")
                            val onStyle: (Int) -> Unit = { i ->
                                val on = i == 1
                                couiNow = on
                                CouiSkinPref.setEnabled(context, on)
                                CouiUiState.enabled.value = on
                            }
                            val styleSummary = if (couiNow) {
                                "凝光视效：大圆角、连续边缘光、材质混色"
                            } else {
                                "原版 Miuix 皮肤"
                            }
                            if (CouiUiState.enabled.value) {
                                CouiDropdownRow(
                                    title = "界面风格",
                                    summary = styleSummary,
                                    options = styleOptions,
                                    selectedIndex = if (couiNow) 1 else 0,
                                    onSelectedIndexChange = onStyle,
                                )
                            } else {
                                SettingDropdownRow(
                                    title = "界面风格",
                                    summary = styleSummary,
                                    options = styleOptions,
                                    selectedIndex = if (couiNow) 1 else 0,
                                    onSelectedIndexChange = onStyle,
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 主题设置（占位）
                        SectionTitle("主题设置")
                        SectionCard {
                            PrefSpinner(
                                context = context,
                                key = "pref_advanced_theme_mode",
                                default = 0,
                                options = listOf(
                                    "跟随主题" to "跟随系统主题",
                                    "浅色" to "强制浅色主题",
                                    "深色" to "强制深色主题",
                                ),
                                title = "主题模式",
                                summary = "选择应用主题模式",
                            )
                            PrefArrow(
                                title = "导入主题包",
                                summary = "选一个主题 zip 导入（如「星云·深空」），导入后回首页主题条切换",
                                onClick = {
                                    runCatching {
                                        themeLauncher.launch(
                                            arrayOf(
                                                "application/zip",
                                                "application/octet-stream",
                                                "*/*"
                                            )
                                        )
                                    }.onFailure { e ->
                                        Toast.makeText(
                                            context,
                                            "无法打开文件选择器：${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                            )
                            PrefArrow(
                                title = "已导入主题",
                                summary = "已导入 $themeCount 个，点一项单独删除（不影响其它主题）",
                                onClick = {
                                    val list = importedThemes(context)
                                    if (list.isEmpty()) {
                                        Toast.makeText(
                                            context, "还没有导入任何主题", Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        val using = ArtThemeStore.currentId(context)
                                        val labels = list.map { (id, name) ->
                                            if (id == using) "$name（使用中）" else name
                                        }.toTypedArray()
                                        AlertDialog.Builder(context)
                                            .setTitle("已导入主题")
                                            .setItems(labels) { _, which ->
                                                val (id, name) = list[which]
                                                AlertDialog.Builder(context)
                                                    .setTitle("删除「$name」？")
                                                    .setMessage("只删这一个包，其它主题保留。若正在使用，会切到其它主题或回默认皮肤。")
                                                    .setPositiveButton("删除") { _, _ ->
                                                        val ok = ArtThemeStore.remove(context, id)
                                                        if (HomeSkins.read(context) == HomeSkins.artDark.id &&
                                                            ArtThemeStore.currentId(context) == null
                                                        ) {
                                                            HomeSkins.write(context, HomeSkins.DEFAULT)
                                                        }
                                                        Toast.makeText(
                                                            context,
                                                            if (ok) "已删除「$name」" else "删除失败",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                    .setNegativeButton("取消", null)
                                                    .show()
                                            }
                                            .setNegativeButton("关闭", null)
                                            .show()
                                    }
                                },
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // 备份（3.0.1117 起真正可用：JSON 导出/导入，覆盖主设置 + 状态栏设置）
                        SectionTitle("备份")
                        SectionCard {
                            PrefArrow(
                                title = "导出设置",
                                summary = "将当前所有设置导出为 JSON 备份文件",
                                onClick = {
                                    runCatching { exportLauncher.launch(SettingsBackup.suggestedFileName()) }
                                        .onFailure { e ->
                                            android.widget.Toast.makeText(
                                                context, "无法打开文件选择器：${e.message}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                },
                            )
                            PrefArrow(
                                title = "导入设置",
                                summary = "从备份文件恢复设置（会覆盖当前全部设置）",
                                onClick = {
                                    runCatching { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
                                        .onFailure { e ->
                                            android.widget.Toast.makeText(
                                                context, "无法打开文件选择器：${e.message}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                },
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // 调试（总开关默认关：打开才显示下面这些排障项，关掉就整体收起）
                        SectionTitle("调试")
                        SectionCard {
                            PrefSwitch(
                                context = context,
                                key = "pref_debug_panel_enabled",
                                default = false,
                                title = "调试项",
                                summary = "默认关闭。打开后下面才出现打印日志、歌词源自检、性能监控和性能数据；" +
                                    "平时关着，这几项一起收起，界面上不占地方",
                                extraWrite = { on ->
                                    debugPanelOn = on
                                    // 收起调试项时把子项全部复位成默认关：
                                    // 下次再打开调试，三个开关都是关的，不会留上次"开着也不知道"的状态
                                    if (!on) {
                                        val mp = com.liuxiansheng.lyricon.app.util.MasterPrefs
                                        mp.setBoolean(context, "pref_debug_logging", false)
                                        mp.setBoolean(context, "lyricsource_diag_enabled", false)
                                        mp.setBoolean(context, "pref_debug_perf_monitor", false)
                                    }
                                },
                            )
                        }
                        if (debugPanelOn) {
                            SectionCard {
                                PrefSwitch(
                                    context = context,
                                    key = "pref_debug_logging",
                                    default = false,
                                    title = "打印日志",
                                    summary = "默认关闭。排障时打开，日志写进 LSPosed 模块日志——" +
                                        "手机上打开 LSPosed → 日志 直接就能看（搜 Lyricon），不用接电脑；收完记得关掉",
                                )
                                PrefSwitch(
                                    context = context,
                                    key = "lyricsource_diag_enabled",
                                    default = false,
                                    title = "歌词源自检",
                                    summary = "默认关闭。打开后歌词源页会显示扫描诊断（可见包数、认出几个源、" +
                                        "包可见权限、被隐藏的源等）；只影响诊断信息显不显示，源检测本身一直在跑",
                                )
                                PrefSwitch(
                                    context = context,
                                    key = "pref_debug_perf_monitor",
                                    default = false,
                                    title = "性能监控",
                                    summary = "默认关闭。打开后模块统计 hook 热路径耗时和掉帧数，" +
                                        "每 5 秒更新一次，结果在下面「性能数据」里看，不用接电脑；看完记得关",
                                )
                                PrefArrow(
                                    title = "性能数据",
                                    summary = "掉帧数、最慢的 hook 耗时；没开性能监控时这里是空的",
                                    onClick = { perfSnapshotOpen = true },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 赞助（1.5.143：从「关于词岛」挪到这里）
                        SectionTitle("赞助")
                        SectionCard {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    "词岛完全免费、无广告、无内购。如果它帮到了你，可以扫码请我喝杯水，纯属自愿。",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SponsorButton(
                                        label = "微信赞助",
                                        accent = Color(0xFF07C160),
                                        onClick = { sponsorTarget = 1 },
                                    )
                                    SponsorButton(
                                        label = "支付宝赞助",
                                        accent = Color(0xFF1677FF),
                                        onClick = { sponsorTarget = 2 },
                                    )
                                }
                            }
                        }
                        if (sponsorTarget != 0) {
                            SponsorQrDialog(
                                target = sponsorTarget,
                                onDismiss = { sponsorTarget = 0 },
                            )
                        }
                        if (perfSnapshotOpen) {
                            PerfSnapshotDialog(onDismiss = { perfSnapshotOpen = false })
                        }

                        Spacer(Modifier.height(12.dp))

                        // 关于（入口）
                        SectionTitle("关于")
                        SectionCard {
                            PrefArrow(
                                title = "关于词岛",
                                summary = "版本信息、致谢、开源许可",
                                onClick = onOpenAbout,
                            )
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                    if (CouiUiState.enabled.value) {
                        CouiSettingsShell(
                            title = "更多设置",
                            navigationIcon = { CouiTopBarBackButton { onBack() } },
                        ) { pageBody() }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) { pageBack() }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            ) { pageBody() }
                        }
                    }
            }
        }
    }
}
