/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 *
 * MainActivity：首页（按老词幕 HomeTab 样式：状态卡 / 总开关卡 / 快捷入口 / 系统信息）。
 * 状态卡显示模块激活状态 + HyperOS 版本 + 模块版本；
 * 总开关卡直接控制"超级岛歌词"总开关（KEY_ENABLE_SUPER_ISLAND，写 MasterPrefs 并广播）；
 * 快捷入口 2×2 跳转设置页各入口；系统信息卡展示应用/系统/设备信息。
 */

package com.liuxiansheng.lyricon.app.activity

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import com.liuxiansheng.lyricon.app.compose.effect.AppBackground
import com.liuxiansheng.lyricon.app.compose.effect.BgEffectBackground
import com.liuxiansheng.lyricon.app.live.MediaSessions
import com.liuxiansheng.lyricon.app.live.NowPlayingReader
import com.liuxiansheng.lyricon.axis.ui.AxisApp
import com.liuxiansheng.lyricon.axis.ui.BoardSwitch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.liuxiansheng.lyricon.app.BuildConfig
import com.liuxiansheng.lyricon.app.LyriconApp
import com.liuxiansheng.lyricon.app.util.MasterPrefs
import com.liuxiansheng.lyricon.app.coui.CouiSkinPref
import com.liuxiansheng.lyricon.app.coui.CouiUiState
import com.liuxiansheng.lyricon.app.coui.couiCard
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.liuxiansheng.lyricon.app.util.SectionOrder
import com.liuxiansheng.lyricon.app.util.StatusBarPrefs
import com.liuxiansheng.lyricon.app.lyricsource.LyricSourceScreen
import com.liuxiansheng.lyricon.bridge.BridgeSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页：按老词幕 HomeTab 样式（状态卡 / 总开关卡 / 快捷入口 / 系统信息）。
 *
 * 内部导航（同一 Activity 三个页面，不产生新窗口 → SystemUI 不会因"新Activity"
 * 触发大岛重渲染 → 不会透明）：
 *   home          —— 首页
 *   superIsland   —— 超级岛设置（二级目录）
 *   statusBarLyric—— 状态栏歌词设置（二级目录）
 * 二级页返回用系统返回键。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeSender.initialize(applicationContext)
        // 手机锁竖屏（HyperOS 不再弹左下角旋转建议按钮）；平板放开四向，允许横屏重排
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= 600) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContent { MainNav() }
    }
}

@Composable
private fun MainNav() {
    // 全局主题控制器（受"主题模式"设置控制）
    val context = androidx.compose.ui.platform.LocalContext.current
    val pref = context.getSharedPreferences("com.liuxiansheng.lyricon_preferences", android.content.Context.MODE_PRIVATE)

    // 凝光视效开关：全局状态在进首页时从偏好初始化，
    // 否则只有进过设置页才会生效（其它页面一律走原皮肤）。
    com.liuxiansheng.lyricon.app.coui.CouiUiState.enabled.value =
        com.liuxiansheng.lyricon.app.coui.CouiSkinPref.isEnabled(context)

    // 记住上次停留的二级页：重开 App 直接回到原页面，不再每次都掉回首页
    val knownPages = setOf("home", "superIsland", "statusBarLyric", "lyricSource", "store", "about", "advanced", "records", "sponsors")
    var page by remember {
        mutableStateOf(
            (pref.getString("pref_last_page", "home") ?: "home").let { if (it in knownPages) it else "home" }
        )
    }
    LaunchedEffect(page) {
        runCatching { pref.edit().putString("pref_last_page", page).apply() }
    }
    // 二级页用系统返回键回首页
    BackHandler(enabled = page != "home") {
        page = "home"
    }

    var themeMode by remember { mutableStateOf(pref.getInt("pref_advanced_theme_mode", 0)) }

    // 首页主题皮肤（0=默认 / 1=唱片机 / 2=晨雾 / 3=深空仪表 / 4=歌词杂志）：全局提供，二级页一起套用
    var skinId by remember { mutableStateOf(pref.getInt(HomeSkins.KEY, HomeSkins.DEFAULT)) }
    val skin = HomeSkins.of(skinId)

    // 监听主题模式 + 皮肤变化。
    // 注意：listener 必须被 DisposableEffect 持有，否则注册完就被回收，切皮肤后二级页收不到通知。
    DisposableEffect(pref) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "pref_advanced_theme_mode" -> themeMode = pref.getInt(key, 0)
                HomeSkins.KEY -> skinId = pref.getInt(HomeSkins.KEY, HomeSkins.DEFAULT)
            }
        }
        pref.registerOnSharedPreferenceChangeListener(listener)
        onDispose { pref.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // 选了皮肤时，亮暗跟随皮肤本身（黑胶/赛博深色、晨雾浅色），其余仍按"主题模式"
    val colorSchemeMode = when {
        skin != null && skin.dark -> top.yukonga.miuix.kmp.theme.ColorSchemeMode.Dark
        skin != null -> top.yukonga.miuix.kmp.theme.ColorSchemeMode.System
        themeMode == 1 -> top.yukonga.miuix.kmp.theme.ColorSchemeMode.Light
        themeMode == 2 -> top.yukonga.miuix.kmp.theme.ColorSchemeMode.Dark
        else -> top.yukonga.miuix.kmp.theme.ColorSchemeMode.System
    }
    val themeController = remember(themeMode, skinId) {
        top.yukonga.miuix.kmp.theme.ThemeController(colorSchemeMode)
    }

    CompositionLocalProvider(
        LocalHomeSkin provides skin,
        LocalSkinThemeController provides themeController,
    ) {
        MiuixTheme(controller = themeController) {
            val axisDark = com.liuxiansheng.lyricon.app.coui.CouiUiState.enabled.value &&
                androidx.compose.foundation.isSystemInDarkTheme() ||
                MiuixTheme.colorScheme.background.luminance() < 0.5f
            com.liuxiansheng.lyricon.axis.ui.AxisTheme(dark = axisDark) {
            val pageContent: @Composable () -> Unit = {
            when (page) {
                "axis" -> AxisHomeScreen(onBack = { page = "home" })
                "superIsland" -> SettingsScreen()
                "statusBarLyric" -> StatusBarLyricSettingsScreen()
                "lyricSource" -> LyricSourceScreen(
                    onBack = { page = "home" },
                    onOpenStore = { page = "store" },
                )
                "store" -> com.liuxiansheng.lyricon.app.store.StoreScreen(
                    onBack = { page = "home" },
                    onOpenLyricSource = { page = "lyricSource" },
                )
                "about" -> AboutScreen(
                    onBack = { page = "home" },
                    onOpenSponsors = { page = "sponsors" },
                )
                "sponsors" -> SponsorListScreen(onBack = { page = "about" })
                "advanced" -> AdvancedSettingsScreen(onBack = { page = "home" }, onOpenAbout = { page = "about" })
                "records" -> ListenRecordsScreen(
                    onBack = { page = "home" },
                    onOpenSettings = { page = "advanced" },
                )
                else -> HomeScreen(
                    onOpenAxis = { page = "axis" },
                    onOpenSuperIsland = { page = "superIsland" },
                    onOpenStatusBarLyric = { page = "statusBarLyric" },
                    onOpenLyricSource = { page = "lyricSource" },
                    onOpenStore = { page = "store" },
                    onOpenAbout = { page = "about" },
                    onOpenAdvanced = { page = "advanced" },
                    onOpenRecords = { page = "records" },
                )
            }
            }
            // 凝光弹窗材质需要「同一窗口、不含弹窗自身」的采样源：只给二级页（设置 / 歌词 / 商店等）包；
            // 首页不包——首页三套皮肤（唱片机 / 深空仪表 / 歌词杂志）自己画背景，包了会把底色改掉。
            if (page == "home") {
                pageContent()
            } else {
                ProvideCouiMenuBackdrop(isolateContent = false) { rootModifier ->
                    androidx.compose.foundation.layout.Box(rootModifier) { pageContent() }
                }
            }
            }
        }
    }
}

/**
 * 首页「歌词打轴」独立页：把打轴器内嵌进来，不另开 Activity。
 *
 * 为什么不开独立 Activity：SystemUI 会感知到"新窗口"并触发大岛重渲染，
 * 历史上前几版就是被这个坑搞成大岛背景透明，所以入口一律走 MainActivity 内部二级页。
 */
@Composable
private fun AxisHomeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    /**
     * 打轴页要的三样东西分别从两条链路取：
     * - 位置 / 是否在播 → 系统媒体库（实时、跟播放器一致）
     * - 总时长 / 封面 → 岛写下来的 live_state（系统媒体库这条路常常给 0，时长一丢进度条就废）
     */
    val readLive: () -> com.liuxiansheng.lyricon.app.live.NowPlaying? = {
        val sys = runCatching { MediaSessions.snapshot(context) }.getOrNull()?.takeIf { it.hasTrack }
        val island = runCatching { NowPlayingReader.read(context) }.getOrNull()?.takeIf { it.hasTrack }
        when {
            sys != null && island != null -> sys.copy(
                durationMs = maxOf(sys.durationMs, island.durationMs),
                coverTag = island.coverTag,
            )
            else -> sys ?: island
        }
    }
    val seed = remember { readLive() }
    val mediaReady = remember { MediaSessions.isEnabled(context) }
    // 授权掉了就自己补：能 su 就直接写回系统设置，补不上才提示去手动开
    var granted by remember { mutableStateOf(mediaReady) }
    LaunchedEffect(Unit) {
        if (!granted) {
            granted = withContext(Dispatchers.IO) { runCatching { MediaSessions.tryRootGrant(context) }.getOrDefault(false) }
        } else {
            // 授权在、但服务可能还没被系统绑上（覆盖安装后会掉），进页面时主动要一次
            MediaSessions.requestRebind(context)
        }
        if (!granted) {
            val sp = context.getSharedPreferences("axis_media_hint", android.content.Context.MODE_PRIVATE)
            if (!sp.getBoolean("shown", false)) {
                sp.edit().putBoolean("shown", true).apply()
                Toast.makeText(
                    context,
                    "打轴现在从系统媒体库读歌，需要在系统设置里给词岛「通知使用权」",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    BackHandler(enabled = true) { onBack() }
    Box(modifier = Modifier.fillMaxSize()) {
        AxisApp(
            onClose = onBack,
            initialTitle = seed?.title.orEmpty(),
            initialArtist = seed?.artist.orEmpty(),
            initialDurationMs = seed?.durationMs ?: 0L,
            // 跟随系统播放器：位置现读系统媒体会话，时长同时看岛那份（谁有大用谁）
            liveState = {
                readLive()?.let { np ->
                    com.liuxiansheng.lyricon.axis.ui.LiveState(
                        np.positionMs,
                        np.playing,
                        np.durationMs,
                        // 有没有人在推词 —— 以 Pro provider 为主：
                        // 当前句/下一句是**真词**（「暂无歌词，请您欣赏」这类占位不算），
                        // 或者 Pro 正对着这个播放器供词（前奏空档也算有）。
                        // 有词时搜来的那份不自动用；只有整首真没词才轮到网上搜的顶上。
                        !com.liuxiansheng.lyricon.axis.core.LyricPlaceholder.isPlaceholder(np.lyric) ||
                            !com.liuxiansheng.lyricon.axis.core.LyricPlaceholder.isPlaceholder(np.next) ||
                            (np.providerPlayerPackage.isNotBlank() &&
                                np.providerPlayerPackage == np.packageName),
                    )
                }
            },
            // 封面只有岛那条链路有（按 "歌名|歌手" 落盘），标签对不上 coverFile 自己会返回 null
            coverFile = {
                runCatching {
                    NowPlayingReader.coverFile(context, NowPlayingReader.read(context))
                }.getOrNull()
            },
            // 打轴器的播放/暂停/跳转：一律走 SystemUI 那条遥控通道。
            // App 直连别的 App 的 MediaController 在澎湃 OS 上"读得到、控不动"（实测 seek 无声无息），
            // 主页那条能拖的进度条走的就是这条广播；两条都发也没意义，直连不生效还会拖慢一拍。
            onControl = { cmd, arg ->
                runCatching {
                    context.sendBroadcast(
                        Intent("io.github.liuxiansheng12369.chidao.AXIS_MEDIA").apply {
                            setPackage("com.android.systemui")
                            putExtra("cmd", cmd)
                            putExtra("arg", arg)
                        }
                    )
                }
            },
        )
    }
}

@SuppressLint("DiscouragedApi")
private fun getSystemProperty(key: String): String {
    return runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java)
        method.invoke(null, key) as? String ?: ""
    }.getOrDefault("")
}

/**
 * 当前 ROM 的系统名（品牌），按机器实际系统判定，不再写死小米。
 *
 * 判定顺序按各家系统特征属性的存在与否走一遍，命中即返回；
 * 全都没有时回退成 "Android"，保证任何机器都能显示出名字。
 */
private fun getOsName(): String {
    // 小米系（小米 / 红米 / POCO）
    if (getSystemProperty("ro.mi.os.version.name").isNotBlank()) return "HyperOS"
    if (getSystemProperty("ro.miui.ui.version.name").isNotBlank()) return "MIUI"
    // OPPO 系（OPPO / 一加 / realme）
    if (getSystemProperty("ro.build.version.opporom").isNotBlank()) return "ColorOS"
    if (getSystemProperty("ro.build.version.realmeui").isNotBlank()) return "realme UI"
    // vivo 系（vivo / iQOO）
    val vivoRom = getSystemProperty("ro.vivo.os.version")
    if (vivoRom.isNotBlank()) {
        return if (vivoRom.contains("Origin", ignoreCase = true)) "OriginOS" else "FuntouchOS"
    }
    // 华为 / 荣耀
    if (getSystemProperty("hw_sc.build.platform.version").isNotBlank()) return "HarmonyOS"
    if (getSystemProperty("ro.build.version.emui").isNotBlank()) return "EMUI"
    // 三星
    if (getSystemProperty("ro.build.version.oneui").isNotBlank()) return "One UI"
    // 魅族
    if (getSystemProperty("ro.build.flyme.version").isNotBlank()) return "Flyme"
    // 其他一律按 Android 显示
    return "Android"
}

/** 当前系统名 + 版本号，跟着 [getOsName] 走，取不到版本号就回退 Android 版本号。 */
private fun getOsVersionName(): String {
    val brand = getOsName()
    if (brand == "Android") return "Android ${Build.VERSION.RELEASE}"
    val version = when (brand) {
        "HyperOS" -> getSystemProperty("ro.mi.os.version.name").removePrefix("OS")
        "MIUI" -> getSystemProperty("ro.miui.ui.version.name").removePrefix("V")
        "ColorOS" -> getSystemProperty("ro.build.version.opporom").removePrefix("V")
        "realme UI" -> getSystemProperty("ro.build.version.realmeui").removePrefix("V")
        "OriginOS", "FuntouchOS" -> getSystemProperty("ro.vivo.os.version")
        "HarmonyOS" -> getSystemProperty("hw_sc.build.platform.version")
        "EMUI" -> getSystemProperty("ro.build.version.emui")
        "One UI" -> getSystemProperty("ro.build.version.oneui")
        "Flyme" -> getSystemProperty("ro.build.flyme.version")
        else -> Build.VERSION.RELEASE
    }
    return "$brand $version".trim()
}

private fun getSystemBuildVersion(): String {
    return getSystemProperty("ro.build.version.incremental")
        .ifBlank { Build.DISPLAY }
}

@Composable
private fun HomeScreen(
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
    val scope = rememberCoroutineScope()

    // 首页主题皮肤（0=默认，1=黑胶唱片，2=晨雾留白，3=赛博霓虹）
    var skinId by remember { mutableStateOf(HomeSkins.read(context)) }
    DisposableEffect(Unit) {
        val pref = context.getSharedPreferences(
            "com.liuxiansheng.lyricon_preferences", android.content.Context.MODE_PRIVATE
        )
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == HomeSkins.KEY) skinId = HomeSkins.read(context)
        }
        pref.registerOnSharedPreferenceChangeListener(listener)
        onDispose { pref.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // 艺术主题：皮肤 id 只是"容器"，真正渲染哪个包由 ArtThemeStore 决定（导入几个就能选几个）
    val artPackId = remember(skinId, ArtThemeLoader.revision) { ArtThemeStore.currentId(context) }
    val artPackName = remember(skinId, ArtThemeLoader.revision) { ArtThemeStore.currentName(context) }
    val skinBase = HomeSkins.of(skinId)
    val skin = if (skinBase?.layout == HomeSkins.LAYOUT_ART && artPackId != null) {
        skinBase.copy(artThemeId = artPackId, name = artPackName)
    } else {
        skinBase
    }

    // 重启状态栏没拿到 root 时弹这个。原来失败是静默吞掉的，用户只看到"点了没反应"。
    var showNoRoot by remember { mutableStateOf(false) }

    // 模块激活状态：XposedService 绑定 = 模块已激活。
    // 绑定是异步的，实时监听绑定完成/断开立即刷新，不依赖 ON_RESUME（解决主页激活状态反应慢）
    var moduleActive by remember { mutableStateOf(LyriconApp.xposedService != null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                moduleActive = LyriconApp.xposedService != null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val serviceListener = { active: Boolean -> moduleActive = active }
        LyriconApp.addServiceListener(serviceListener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            LyriconApp.removeServiceListener(serviceListener)
        }
    }

    NoRootDialog(visible = showNoRoot, context = context, onDismiss = { showNoRoot = false })

    CompositionLocalProvider(LocalHomeSkin provides skin) {
    MiuixTheme {
        // 背景：皮肤接管渐层（默认=原蓝紫流光背景，完全不变）
        val isDark = skin?.dark ?: (MiuixTheme.colorScheme.background.luminance() < 0.5f)
        // 状态栏图标（时钟/信号/WiFi/电量）按页面背景反色：深背景→浅图标，浅背景→深图标
        SideEffect {
            val window = (context as Activity).window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !isDark
        }
        // 应用全局背景（受"流光背景"开关控制；选了主题皮肤时由皮肤接管）
        AppBackground(
            isDarkTheme = isDark,
        ) {
            // 四套"布局级"主题（唱片机 / 晨雾概览 / 深空仪表 / 歌词杂志）走各自布局；
            // 默认（skin=null）与旧逻辑完全不变。
            if (skin != null && skin.layout != HomeSkins.LAYOUT_CLASSIC) {
                ThemedHome(
                    skin = skin,
                    skinId = skinId,
                    moduleActive = moduleActive,
                    onOpenAxis = onOpenAxis,
                    onOpenSuperIsland = onOpenSuperIsland,
                    onOpenStatusBarLyric = onOpenStatusBarLyric,
                    onOpenLyricSource = onOpenLyricSource,
                    onOpenStore = onOpenStore,
                    onOpenAbout = onOpenAbout,
                    onOpenAdvanced = onOpenAdvanced,
                    onOpenRecords = onOpenRecords,
                )
            } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 右上角重启按钮：与大卡片同款磨砂圆角卡片（半透明白底 + 白边 + ↻），不突兀
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 12.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
                    val btnBg = if (skin != null) {
                        skin.cardBg
                    } else if (isDark) {
                        Color(0xFF202024).copy(alpha = 0.80f)
                    } else {
                        Color.White.copy(alpha = 0.68f)
                    }
                    val btnBorder = if (skin != null) {
                        skin.cardBorder
                    } else if (isDark) {
                        Color.White.copy(alpha = 0.14f)
                    } else {
                        Color.White.copy(alpha = 0.80f)
                    }
                    // 有新版本时在右上角挂一枚提示，点开就是关于页（自动检查一天一次，失败不显示）
                    com.liuxiansheng.lyricon.app.util.UpdateChecker.result.value?.let { upd ->
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .background(
                                    (skin?.accent ?: Color(0xFF4F7CFF)).copy(alpha = 0.18f),
                                    RoundedCornerShape(14.dp)
                                )
                                .border(
                                    1.dp,
                                    (skin?.accent ?: Color(0xFF4F7CFF)).copy(alpha = 0.45f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { onOpenAbout() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "有新版本 ${upd.versionName}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = skin?.accent ?: Color(0xFF4F7CFF),
                            )
                        }
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
                            color = skin?.textPrimary ?: MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                // ===== 3.0.777：主页区块长按排序（模块状态/总开关/快捷入口/系统信息） =====
                val homeSectionIds = listOf("status", "master", "quick", "preview", "skin", "sysinfo")
                var homeOrder by remember {
                    mutableStateOf(
                        SectionOrder.read(context, "pref_home_section_order", homeSectionIds)
                    )
                }
                val moveHome: (Int, Int) -> Unit = { from, to ->
                    val list = homeOrder.toMutableList()
                    val id = list.removeAt(from)
                    list.add(to.coerceIn(0, list.size), id)
                    homeOrder = list
                    SectionOrder.write(context, "pref_home_section_order", list)
                }
                homeOrder.forEachIndexed { index, id ->
                    val canUp = index > 0
                    val canDown = index < homeOrder.size - 1
                    val reorder = SectionReorder(
                        canReorder = true,
                        onMoveUp = if (canUp) { { moveHome(index, index - 1) } } else null,
                        onMoveDown = if (canDown) { { moveHome(index, index + 1) } } else null,
                        onMoveTop = if (canUp) { { moveHome(index, 0) } } else null,
                        onMoveBottom = if (canDown) {
                            { moveHome(index, homeOrder.size - 1) }
                        } else null,
                    )
                    when (id) {
                        "status" -> {
                            SectionTitle(
                                "模块状态",
                                canReorder = reorder.canReorder,
                                onMoveUp = reorder.onMoveUp,
                                onMoveDown = reorder.onMoveDown,
                                onMoveTop = reorder.onMoveTop,
                                onMoveBottom = reorder.onMoveBottom,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val health = com.liuxiansheng.lyricon.app.live.ModuleHealth.read(context, moduleActive)
                                StatsCard(
                                    title = "模块状态",
                                    value = health.shortTitle,
                                    badge = when (health.state) {
                                        com.liuxiansheng.lyricon.app.live.ModuleHealth.State.ACTIVE -> "✓"
                                        com.liuxiansheng.lyricon.app.live.ModuleHealth.State.NEED_RESTART -> "↻"
                                        com.liuxiansheng.lyricon.app.live.ModuleHealth.State.INACTIVE -> "!"
                                    },
                                    badgeColor = when (health.state) {
                                        com.liuxiansheng.lyricon.app.live.ModuleHealth.State.ACTIVE -> Color(0xFF4CAF50)
                                        com.liuxiansheng.lyricon.app.live.ModuleHealth.State.NEED_RESTART -> Color(0xFFE0A030)
                                        com.liuxiansheng.lyricon.app.live.ModuleHealth.State.INACTIVE -> Color(0xFFE25B5B)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                StatsCard(
                                    title = getOsName(),
                                    value = getOsVersionName(),
                                    badge = "系",
                                    badgeColor = Color(0xFF2196F3),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        "master" -> {
                            SectionTitle(
                                "总开关",
                                canReorder = reorder.canReorder,
                                onMoveUp = reorder.onMoveUp,
                                onMoveDown = reorder.onMoveDown,
                                onMoveTop = reorder.onMoveTop,
                                onMoveBottom = reorder.onMoveBottom,
                            )
                            MasterSwitchCard(context)
                        }
                        "quick" -> {
                            SectionTitle(
                                "快捷入口",
                                canReorder = reorder.canReorder,
                                onMoveUp = reorder.onMoveUp,
                                onMoveDown = reorder.onMoveDown,
                                onMoveTop = reorder.onMoveTop,
                                onMoveBottom = reorder.onMoveBottom,
                            )
                            HomeQuickActions(
                                context = context,
                                onOpenAxis = onOpenAxis,
                                onOpenSuperIsland = onOpenSuperIsland,
                                onOpenStatusBarLyric = onOpenStatusBarLyric,
                                onOpenLyricSource = onOpenLyricSource,
                                onOpenStore = onOpenStore,
                                onOpenAbout = onOpenAbout,
                                onOpenAdvanced = onOpenAdvanced,
                            )
                        }
                        "preview" -> {
                            SectionTitle(
                                "实时预览",
                                canReorder = reorder.canReorder,
                                onMoveUp = reorder.onMoveUp,
                                onMoveDown = reorder.onMoveDown,
                                onMoveTop = reorder.onMoveTop,
                                onMoveBottom = reorder.onMoveBottom,
                            )
                            IslandPreviewCard(skin = skin, onClick = onOpenRecords)
                        }
                        "skin" -> {
                            SectionTitle(
                                "首页主题",
                                canReorder = reorder.canReorder,
                                onMoveUp = reorder.onMoveUp,
                                onMoveDown = reorder.onMoveDown,
                                onMoveTop = reorder.onMoveTop,
                                onMoveBottom = reorder.onMoveBottom,
                            )
                            HomeSkinPicker(
                                currentId = skinId,
                                onPick = { id ->
                                    HomeSkins.write(context, id)
                                    skinId = id
                                },
                            )
                        }
                        "sysinfo" -> {
                            SectionTitle(
                                "系统信息",
                                canReorder = reorder.canReorder,
                                onMoveUp = reorder.onMoveUp,
                                onMoveDown = reorder.onMoveDown,
                                onMoveTop = reorder.onMoveTop,
                                onMoveBottom = reorder.onMoveBottom,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                StatsCard(
                                    title = "应用版本",
                                    value = BuildConfig.VERSION_NAME,
                                    badge = "版",
                                    badgeColor = Color(0xFF7C4DFF),
                                    modifier = Modifier.weight(1f),
                                )
                                StatsCard(
                                    title = "Android",
                                    value = "Android ${Build.VERSION.RELEASE}",
                                    badge = "安",
                                    badgeColor = Color(0xFF3F51B5),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                StatsCard(
                                    title = "系统版本",
                                    value = getSystemBuildVersion(),
                                    badge = "统",
                                    badgeColor = Color(0xFF009688),
                                    modifier = Modifier.weight(1f),
                                )
                                StatsCard(
                                    title = "设备型号",
                                    value = run {
                                        // 多系统兼容读"真正型号"：小米系读市场名，华为/荣耀读 market_name，其余回退 Build.MODEL
                                        val candidates = listOf(
                                            "ro.product.marketname",          // 小米 / Redmi / POCO / 一加等
                                            "ro.config.market_name",          // 华为 / 荣耀
                                            "ro.product.vendor.marketname",   // 部分小米 vendor 层
                                            "ro.product.name",                // 通用兜底
                                        )
                                        val name = candidates
                                            .map { getSystemProperty(it) }
                                            .firstOrNull { it.isNotBlank() }
                                        if (name != null) {
                                            listOf(Build.MANUFACTURER, name)
                                        } else {
                                            listOf(Build.MANUFACTURER, Build.MODEL)
                                        }
                                    }
                                        .filter { it.isNotBlank() }
                                        .joinToString(" "),
                                    badge = "机",
                                    badgeColor = Color(0xFF607D8B),
                                    modifier = Modifier.weight(1f),
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
    }
}

/** 总开关卡：超级岛歌词总开关（标题 + 副文本 + Switch，风格统一） */
@Composable
internal fun MasterSwitchCard(context: android.content.Context) {
    var enabled by remember {
        mutableStateOf(
            MasterPrefs.getBoolean(
                context,
                MasterPrefs.KEY_ENABLE_SUPER_ISLAND,
                true
            )
        )
    }

    val skin = LocalHomeSkin.current
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val glassBg = if (skin != null) {
        skin.cardBg
    } else if (isDark) {
        Color(0xFF202024).copy(alpha = 0.45f)
    } else {
        Color.White.copy(alpha = 0.30f)
    }
    val glassBorder = if (skin != null) {
        skin.cardBorder
    } else if (isDark) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.70f)
    }
    val coui = CouiUiState.enabled.value
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .then(
                if (coui) {
                    Modifier.couiCard(isDark, base = glassBg, glowTint = skin?.accent)
                } else {
                    Modifier
                        .background(glassBg, RoundedCornerShape(16.dp))
                        .border(1.dp, glassBorder, RoundedCornerShape(16.dp))
                }
            )
            .padding(if (coui) 18.dp else 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "超级岛歌词",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = skin?.textPrimary ?: MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = if (enabled) "开启中（歌词显示在超级岛）" else "已关闭（歌词显示在状态栏）",
                fontSize = 13.sp,
                color = skin?.textSecondary ?: Color.Gray,
            )
        }
        BoardSwitch(
            checked = enabled,

            onCheckedChange = { value ->
                enabled = value
                // 互拆（双向）+ 流畅切换：切换时"先开目标套，再关当前套"，
                // 保证 SystemUI 任意时刻至少看到一套开着、共享歌词源不中断，
                // 两模式切换才流畅不卡、歌词不断档
                if (value) {
                    // 开超导：先开超导，再关状态栏
                    MasterPrefs.setBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true)
                    StatusBarPrefs.setBoolean(
                        context,
                        StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC,
                        false
                    )
                } else {
                    // 关超导（开状态栏）：先开状态栏，再关超导
                    StatusBarPrefs.setBoolean(
                        context,
                        StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC,
                        true
                    )
                    MasterPrefs.setBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, false)
                }
            }
        )
        }
    }
}

/** 快捷入口 2×2（照音乐 App 音乐库卡片样式，色块图标 + 标题 + 副文本）
 * 3.0.780：4 个卡片可长按拖动排序（按住卡片拖动到目标位置松手落位），持久化 pref_home_quick_order */
@Composable
private fun HomeQuickActions(
    context: android.content.Context,
    onOpenAxis: () -> Unit,
    onOpenSuperIsland: () -> Unit,
    onOpenStatusBarLyric: () -> Unit,
    onOpenLyricSource: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    // 第一行：超级岛设置 + 状态栏歌词——都是 MainActivity 内部二级页（不启动独立 Activity，
    // 独立 Activity 会让 SystemUI 感知"新窗口"并触发大岛重渲染 → 大岛/通知栏透明）
    val quickIds = listOf("axis", "super", "status", "source", "store", "more")
    var quickOrder by remember {
        // 老用户手里存的顺序没有"歌词源商店"这一项，读出来后按默认位置补进去，
        // 否则新入口永远不出现（排序结果仍然保留，不重置用户拖过的顺序）
        mutableStateOf(run {
            val saved = SectionOrder.read(context, "pref_home_quick_order", quickIds)
            val kept = saved.filter { it in quickIds }.toMutableList()
            quickIds.forEach { id ->
                if (id !in kept) {
                    val idx = quickIds.indexOf(id).coerceAtMost(kept.size)
                    kept.add(idx, id)
                }
            }
            kept
        })
    }
    // 拖拽排序等状态之外的另一个数：商店里有几个已装且能更新的源，给「歌词源商店」卡片挂角标
    var storeUpdates by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val quickLifecycle = LocalLifecycleOwner.current
    DisposableEffect(quickLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    storeUpdates = withContext(Dispatchers.IO) {
                        runCatching {
                            com.liuxiansheng.lyricon.app.store.StoreRepository.updateCount(context)
                        }.getOrDefault(0)
                    }
                }
            }
        }
        quickLifecycle.lifecycle.addObserver(observer)
        onDispose { quickLifecycle.lifecycle.removeObserver(observer) }
    }
    // 拖动状态（3.0.780：长按拖动实时换位）
    val cardBounds = remember { mutableStateMapOf<String, Rect>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragStartPos by remember { mutableStateOf(Offset.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    val rows = quickOrder.chunked(2)
    Column {
        rows.forEachIndexed { rowIndex, chunk ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = if (rowIndex == rows.size - 1) 16.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                chunk.forEach { id ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                cardBounds[id] = coords.boundsInRoot()
                            }
                            .graphicsLayer {
                                if (dragId == id) {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y
                                    scaleX = 1.04f
                                    scaleY = 1.04f
                                }
                            }
                            .zIndex(if (dragId == id) 2f else 0f)
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        dragId = id
                                        dragStartPos = offset
                                        dragOrigin = cardBounds[id]?.let { it.topLeft } ?: Offset.Zero
                                        dragOffset = Offset.Zero
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount
                                        // 手指在 root 坐标的位置 = 卡片左上 + 按下点相对位置 + 拖动增量
                                        val finger = dragOrigin + dragStartPos + dragOffset
                                        val target = cardBounds.entries
                                            .filter { it.key != dragId }
                                            .firstOrNull { it.value.contains(finger) }
                                            ?.key
                                        if (target != null) {
                                            val list = quickOrder.toMutableList()
                                            val a = list.indexOf(dragId!!)
                                            val b = list.indexOf(target)
                                            if (a != b) {
                                                // 交换后 dragId 卡会落到 target 原位置，以此为新的拖动起点
                                                dragOrigin = cardBounds[target]?.let { it.topLeft }
                                                    ?: dragOrigin
                                                list[a] = target
                                                list[b] = dragId!!
                                                quickOrder = list
                                                dragOffset = Offset.Zero
                                                // 交换落位立即落盘（不能只依赖 onDragEnd——小米系统手势
                                                // 可能把拖动拦截成 onDragCancel，导致顺序不持久化，退出即还原）
                                                SectionOrder.write(context, "pref_home_quick_order", list)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        dragId = null
                                        dragOffset = Offset.Zero
                                        SectionOrder.write(context, "pref_home_quick_order", quickOrder)
                                    },
                                    onDragCancel = {
                                        dragId = null
                                        dragOffset = Offset.Zero
                                        // 兜底：手势被系统拦截时也已交换，补一次持久化
                                        SectionOrder.write(context, "pref_home_quick_order", quickOrder)
                                    },
                                )
                            },
                    ) {
                        when (id) {
                            "axis" -> StatsCard(
                                title = "歌词打轴",
                                value = "没歌词自己敲时间",
                                badge = "轴",
                                badgeColor = Color(0xFF00A0A0),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onOpenAxis,
                            )
                            "super" -> StatsCard(
                                title = "超级岛设置",
                                value = "进入设置",
                                badge = "岛",
                                badgeColor = Color(0xFF2196F3),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onOpenSuperIsland,
                            )
                            "status" -> StatsCard(
                                title = "状态栏歌词",
                                value = "进入设置",
                                badge = "栏",
                                badgeColor = Color(0xFF3F51B5),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onOpenStatusBarLyric,
                            )
                            "source" -> StatsCard(
                                title = "歌词源",
                                value = "进入设置",
                                badge = "词",
                                badgeColor = Color(0xFF7C4DFF),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onOpenLyricSource,
                            )
                            "store" -> StatsCard(
                                title = "歌词源商店",
                                value = "装源 / 更新源",
                                badge = "店",
                                badgeColor = Color(0xFF2E9E5B),
                                cornerBadge = if (storeUpdates > 0) storeUpdates.toString() else null,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onOpenStore,
                            )
                            "more" -> StatsCard(
                                title = "更多设置",
                                value = "背景/主题/备份",
                                badge = "更",
                                badgeColor = Color(0xFF607D8B),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onOpenAdvanced,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 首页主题选择条（默认 / 唱片机 / 晨雾概览 / 深空仪表 / 歌词杂志，点一下立即换肤） */
@Composable
internal fun HomeSkinPicker(currentId: Int, onPick: (Int) -> Unit) {
    val current = LocalHomeSkin.current
    val context = LocalContext.current
    // 已导入的主题包：导入几个就在条上出现几个，没导入就不出现；排在内置皮肤前面，换主题一眼就看到
    val packs = remember(currentId, ArtThemeLoader.revision) { ArtThemeStore.list(context) }
    val currentPack = remember(currentId, ArtThemeLoader.revision) {
        ArtThemeStore.currentId(context)
    }
    val items = buildList<Triple<Int, String, String?>> {
        // ① 先放已导入的主题包（自己做的主题排最前）
        packs.forEach { add(Triple(HomeSkins.artDark.id, it.name, it.id)) }
        // ② 内置皮肤
        add(Triple(HomeSkins.board.id, HomeSkins.board.name, null))
        add(Triple(HomeSkins.vinyl.id, HomeSkins.vinyl.name, null))
        add(Triple(HomeSkins.mist.id, HomeSkins.mist.name, null))
        add(Triple(HomeSkins.cyber.id, HomeSkins.cyber.name, null))
        add(Triple(HomeSkins.lyric.id, HomeSkins.lyric.name, null))
        add(Triple(0, "经典区块", null))
    }
    val cardBg = current?.cardBg ?: if (MiuixTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF202024).copy(alpha = 0.45f)
    } else {
        Color.White.copy(alpha = 0.30f)
    }
    // 凝光模式下主题条也走凝光玻璃：不用当前皮肤的白卡底，否则深色页面上四个白胶囊会跳出来
    val couiMode = CouiUiState.enabled.value
    val sysDark = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background.luminance() < 0.5f
    val chipBase = if (couiMode) {
        if (sysDark) Color(0xCC262626) else Color(0xA8FFFFFF)
    } else {
        cardBg
    }
    val chipText = if (couiMode) {
        if (sysDark) Color(0xFFEAF0F8) else Color(0xFF14161C)
    } else {
        current?.textPrimary ?: MiuixTheme.colorScheme.onSurface
    }
    // 横向一行滑动（主题包多了往右滑）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (id, name, packId) ->
            val tint = HomeSkins.of(id)?.accent
                ?: if (MiuixTheme.colorScheme.background.luminance() < 0.5f) {
                    Color(0xFF8FB2FF)
                } else {
                    Color(0xFF4F7CFF)
                }
            val selected = id == currentId && (packId == null || packId == currentPack)
            val coui = couiMode
            val chipCorner = if (coui) 20.dp else 14.dp
            Column(
                modifier = Modifier
                    .width(78.dp)
                    .then(
                        if (coui) {
                            Modifier
                                .couiCard(
                                    sysDark,
                                    chipCorner,
                                    base = chipBase,
                                    glowTint = tint,
                                )
                                .then(
                                    if (selected) {
                                        Modifier.background(
                                            tint.copy(alpha = 0.18f),
                                            RoundedCornerShape(chipCorner),
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(chipCorner))
                                .background(
                                    if (selected) tint.copy(alpha = 0.22f) else chipBase,
                                    RoundedCornerShape(chipCorner),
                                )
                                .border(
                                    1.dp,
                                    if (selected) tint else tint.copy(alpha = 0.28f),
                                    RoundedCornerShape(chipCorner),
                                )
                        }
                    )
                    .clickable {
                        if (packId != null) {
                            ArtThemeStore.setCurrent(context, packId)
                        } else {
                            onPick(id)
                        }
                    }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(tint, CircleShape)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = name,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = chipText,
                )
            }
        }
    }
}
/**
 * 实时预览卡：跟状态栏/岛上正在播的真实内容同步（歌名、当前歌词、下一句、频谱律动）。
 * 数据来自模块侧推送的 LiveStateProvider 缓存；点击进入「听歌记录」。
 */
@Composable
private fun IslandPreviewCard(
    skin: HomeSkin?,
    onClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableStateOf(NowPlayingReader.read(context)) }

    // 播放中时 App 在前台，直接读模块推来的缓存（500ms 一次，开销很小）
    LaunchedEffect(Unit) {
        while (true) {
            now = NowPlayingReader.read(context)
            kotlinx.coroutines.delay(500)
        }
    }

    val isDark = skin?.dark ?: (MiuixTheme.colorScheme.background.luminance() < 0.5f)
    val cardBg = skin?.cardBg ?: if (isDark) {
        Color(0xFF202024).copy(alpha = 0.45f)
    } else {
        Color.White.copy(alpha = 0.30f)
    }
    val cardBorder = skin?.cardBorder ?: if (isDark) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.70f)
    }
    val accent = skin?.accent ?: Color(0xFF2196F3)
    val islandBg = skin?.islandBg ?: if (isDark) {
        Color(0xFF101012).copy(alpha = 0.92f)
    } else {
        Color.White.copy(alpha = 0.88f)
    }
    val lyricColor = skin?.islandLyric
        ?: if (isDark) Color(0xFFE8E4DC) else Color(0xFF2C3A46)
    val subColor = skin?.textSecondary
        ?: if (isDark) Color(0xFFA9A49B) else Color(0xFF7C8187)

    val transition = rememberInfiniteTransition(label = "previewBars")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val coui = CouiUiState.enabled.value
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .then(
                if (coui) {
                    Modifier.couiCard(isDark, base = cardBg, glowTint = accent)
                } else {
                    Modifier
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, cardBorder, RoundedCornerShape(16.dp))
                }
            )
            .clickable(onClick = onClick)
            .padding(if (coui) 18.dp else 16.dp),
    ) {
        Column {
            // 胶囊岛本体（内容 = 真实播放状态）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(islandBg, RoundedCornerShape(26.dp))
                    .border(
                        1.dp,
                        if (skin == null) Color.White.copy(alpha = 0.55f) else accent.copy(alpha = 0.65f),
                        RoundedCornerShape(26.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverTile(
                    now = now,
                    size = 40.dp,
                    shape = RoundedCornerShape(12.dp),
                    fallback = "",
                    fontSize = 0.sp,
                    skin = skin,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = NowPlayingReader.mainLine(now),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = lyricColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = NowPlayingReader.subLine(now),
                        fontSize = 11.sp,
                        color = subColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 频谱律动：播放中才跳，暂停/未播为静止细条
                Row(verticalAlignment = Alignment.Bottom) {
                    repeat(6) { i ->
                        val factor = when (i) {
                            0 -> 0.6f
                            1 -> 1f
                            2 -> 0.75f
                            3 -> 0.9f
                            4 -> 0.55f
                            else -> 0.8f
                        }
                        val barHeight = if (now.playing) 8f + 16f * factor * pulse else 8f
                        Box(
                            modifier = Modifier
                                .padding(start = 3.dp)
                                .width(3.dp)
                                .height(barHeight.dp)
                                .background(
                                    accent.copy(alpha = if (now.playing) 1f else 0.45f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        now.hasTrack && now.playing -> "${now.title} · 正在播放"
                        now.hasTrack -> "${now.title} · 已暂停"
                        else -> "未在播放 —— 打开音乐 App 即可实时显示"
                    },
                    fontSize = 11.sp,
                    color = subColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "听歌记录 ›",
                    fontSize = 11.sp,
                    color = accent,
                )
            }
        }
    }
}

/** 通用卡片（照音乐 App 音乐库样式：磨砂半透明底 + 左侧圆角色块 + 右侧标题/副文本），可选点击
 * 3.0.779：reorder 非空时支持长按弹排序菜单（上移/下移/置顶/置底）；点击波纹显式指定颜色更醒目 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatsCard(
    title: String,
    value: String,
    badge: String,
    badgeColor: Color,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    reorder: SectionReorder? = null,
    trailing: (@Composable () -> Unit)? = null,
    cornerBadge: String? = null,
) {
    val skin = LocalHomeSkin.current
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val glassBg = if (skin != null) {
        skin.cardBg
    } else if (isDark) {
        Color(0xFF202024).copy(alpha = 0.45f)
    } else {
        Color.White.copy(alpha = 0.30f)
    }
    val glassBorder = if (skin != null) {
        skin.cardBorder
    } else if (isDark) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.70f)
    }
    // 3.0.775：显式波纹（Miuix 主题 LocalIndication 无波纹，需显式 indication=ripple）+ 圆角裁剪
    // 3.0.779：波纹颜色显式指定（深色底上更明显），点击立即有涟漪反馈
    val interactionSource = remember { MutableInteractionSource() }
    val rippleColor = if (isDark) {
        Color.White.copy(alpha = 0.20f)
    } else {
        Color(0xFF2196F3).copy(alpha = 0.22f)
    }
    var menuOpen by remember { mutableStateOf(false) }
    val badgeTint = skin?.chipFor(title) ?: badgeColor
    val coui = CouiUiState.enabled.value
    val cardCorner = if (coui) 24.dp else 16.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (coui) {
                    Modifier.couiCard(isDark, cardCorner, base = glassBg, glowTint = skin?.accent)
                } else {
                    Modifier
                        .background(glassBg, RoundedCornerShape(16.dp))
                        .border(1.dp, glassBorder, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                }
            )
            .combinedClickable(
                enabled = onClick != null || reorder != null,
                onClick = { onClick?.invoke() },
                onLongClick = if (reorder != null) { { menuOpen = true } } else null,
                interactionSource = interactionSource,
                indication = ripple(color = rippleColor),
            )
            .padding(if (coui) 18.dp else 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(badgeTint.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeTint,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = skin?.textPrimary ?: MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = value,
                    fontSize = 13.sp,
                    color = skin?.textSecondary
                        ?: MiuixTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        // 右上角小圆角标：商店有可更新的源时显示数字
        if (!cornerBadge.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(Color(0xFFE07A2F), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = cornerBadge,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        if (reorder != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        reorder.onMoveUp?.invoke()
                    },
                    enabled = reorder.onMoveUp != null,
                ) {
                    Text("上移")
                }
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        reorder.onMoveDown?.invoke()
                    },
                    enabled = reorder.onMoveDown != null,
                ) {
                    Text("下移")
                }
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        reorder.onMoveTop?.invoke()
                    },
                    enabled = reorder.onMoveTop != null,
                ) {
                    Text("置顶")
                }
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        reorder.onMoveBottom?.invoke()
                    },
                    enabled = reorder.onMoveBottom != null,
                ) {
                    Text("置底")
                }
            }
        }
    }
}
