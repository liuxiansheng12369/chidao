/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 *
 * ArtThemeRenderer：数据驱动的"艺术主题"渲染器。
 *   - 主题包 = theme.json（元数据 + 配色）+ nodes.json（节点表）+ 底图 + 贴图
 *   - 节点按数组顺序绘制，后写的盖在先写的上面
 *   - 坐标全部归一化（0~1，相对 canvas），换算规则见主题设计标准 §2.3：
 *       x = 屏宽 × nx，y = 屏宽 × (canvasH/canvasW) × ny，字号 = 屏宽 × size
 *   - 文字/开关/歌词/进度都不是图片，是这里用真控件画出来的，所以换歌会变、开关能真点
 *
 * 现有六套主题完全不受影响：只有 layout = LAYOUT_ART 才会走到这里。
 */

package com.liuxiansheng.lyricon.app.activity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.AudioManager
import android.view.KeyEvent
import com.liuxiansheng.lyricon.app.live.NowPlaying
import com.liuxiansheng.lyricon.app.live.NowPlayingReader
import com.liuxiansheng.lyricon.app.util.MasterPrefs
import com.liuxiansheng.lyricon.app.util.StatusBarPrefs
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== 主题包数据模型 =====

internal data class ArtPalette(
    val cardBg: Color,
    val cardBgAlpha: Float,
    val cardBorder: Color,
    val cardBorderAlpha: Float,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val islandBg: Color,
    val islandLyric: Color,
)

internal data class ArtMeta(
    val id: String,
    val name: String,
    val desc: String,
    val dark: Boolean,
    val canvasW: Float,
    val canvasH: Float,
    val bgName: String,
    val assetDir: String,
    val palette: ArtPalette,
    /** 纵向适配方式：width=按设计画布比例（旧行为）；height=按屏幕可用高度铺满（长屏主题） */
    val fit: String = "width",
) {
    /** 纵向换算比例：1080×1920 基准即 1.7778 */
    val ratio: Float get() = if (canvasW > 0f) canvasH / canvasW else 1.7778f
}

internal data class ArtNode(
    val type: String,
    val rect: FloatArray,
    val props: JSONObject,
)

internal data class ArtThemePack(
    val meta: ArtMeta,
    val nodes: List<ArtNode>,
    val bg: Bitmap,
    val assets: Map<String, Bitmap>,
)

// ===== 加载器 =====

/**
 * 主题包读取：内置包在 `assets/themes/<id>/`，导入包在 `filesDir/themes/<id>/`。
 * assets 优先；任何一步失败都返回 null，由调用方退回渐变背景（不白屏、不崩）。
 */
internal object ArtThemeLoader {

    private val cache = HashMap<String, ArtThemePack?>()

    /** 导入 / 删除主题包后 +1，让 UI 里 remember 的包失效重读 */
    var revision by mutableStateOf(0)
        private set

    fun invalidate(id: String) {
        cache.remove(id)
        revision++
    }

    /** 内置包（assets）或外部导入包（filesDir/themes）任一存在即算已安装 */
    fun exists(context: Context, id: String): Boolean {
        val inApk = runCatching { context.assets.open("themes/$id/theme.json").close() }.isSuccess
        return inApk || File(context.filesDir, "themes/$id/theme.json").isFile
    }

    fun load(context: Context, id: String): ArtThemePack? {
        if (cache.containsKey(id)) return cache[id]
        val pack = runCatching { read(context, id) }.getOrNull()
        cache[id] = pack
        return pack
    }

    private fun read(context: Context, id: String): ArtThemePack {
        val themeText = readText(context, id, "theme.json") ?: error("theme.json 缺失")
        val nodesText = readText(context, id, "nodes.json") ?: error("nodes.json 缺失")

        val metaObj = JSONObject(themeText)
        val canvas = metaObj.optJSONArray("canvas")
        val canvasW = canvas?.optDouble(0, 1080.0)?.toFloat() ?: 1080f
        val canvasH = canvas?.optDouble(1, 1920.0)?.toFloat() ?: 1920f
        val isDark = metaObj.optBoolean("dark", true)
        val bgObj = metaObj.optJSONObject("bg")
        val bgName = (bgObj?.optString(if (isDark) "dark" else "light").orEmpty())
            .ifBlank { bgObj?.optString("dark").orEmpty() }
            .ifBlank { "bg_dark.webp" }

        val meta = ArtMeta(
            id = metaObj.optString("id").ifBlank { id },
            name = metaObj.optString("name").ifBlank { id },
            desc = metaObj.optString("desc"),
            dark = isDark,
            canvasW = canvasW,
            canvasH = canvasH,
            bgName = bgName,
            assetDir = metaObj.optString("assetDir").ifBlank { "assets" },
            palette = parsePalette(metaObj.optJSONObject("palette")),
            fit = metaObj.optString("fit").ifBlank { "width" },
        )

        val bg = decode(context, id, bgName) ?: error("底图缺失：$bgName")

        val nodesArr = JSONObject(nodesText).optJSONArray("nodes") ?: error("nodes 为空")
        val nodes = ArrayList<ArtNode>(nodesArr.length())
        for (i in 0 until nodesArr.length()) {
            val o = nodesArr.optJSONObject(i) ?: continue
            val rectArr = o.optJSONArray("rect") ?: continue
            val rect = FloatArray(4)
            for (k in 0 until 4) {
                rect[k] = rectArr.optDouble(k, 0.0).toFloat().coerceIn(0f, 1f)
            }
            val type = o.optString("type").ifBlank { continue }
            nodes.add(ArtNode(type, rect, o))
        }

        // 贴图懒加载：先收集所有 src，再一次性解码（缺图不致命，画的时候跳过）
        val assets = HashMap<String, Bitmap>()
        for (n in nodes) {
            val src = n.props.optString("src").ifBlank { continue }
            if (assets.containsKey(src)) continue
            decode(context, id, "${meta.assetDir}/$src")?.let { assets[src] = it }
                ?: decode(context, id, src)?.let { assets[src] = it }
        }

        return ArtThemePack(meta, nodes, bg, assets)
    }

    private fun parsePalette(o: JSONObject?): ArtPalette = ArtPalette(
        cardBg = colorOf(o?.optString("cardBg"), 0xFF12141F),
        cardBgAlpha = o?.optDouble("cardBgAlpha", 0.58)?.toFloat() ?: 0.58f,
        cardBorder = colorOf(o?.optString("cardBorder"), 0xFF7080FF),
        cardBorderAlpha = o?.optDouble("cardBorderAlpha", 0.26)?.toFloat() ?: 0.26f,
        accent = colorOf(o?.optString("accent"), 0xFF3D7BFF),
        textPrimary = colorOf(o?.optString("textPrimary"), 0xFFEEF2FF),
        textSecondary = colorOf(o?.optString("textSecondary"), 0xFF95A0C0),
        islandBg = colorOf(o?.optString("islandBg"), 0xFF080A14),
        islandLyric = colorOf(o?.optString("islandLyric"), 0xFFE9EEFF),
    )

    private fun colorOf(hex: String?, fallback: Long): Color {
        val raw = hex.orEmpty().trim()
        if (raw.isEmpty()) return Color(fallback)
        return runCatching {
            val s = raw.removePrefix("#")
            val v = when (s.length) {
                6 -> 0xFF000000L or s.toLong(16)
                8 -> s.toLong(16)
                else -> return Color(fallback)
            }
            Color(v)
        }.getOrDefault(Color(fallback))
    }

    private fun readText(context: Context, id: String, name: String): String? {
        runCatching {
            context.assets.open("themes/$id/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let { return it }
        val f = File(context.filesDir, "themes/$id/$name")
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    private fun decode(context: Context, id: String, name: String): Bitmap? {
        runCatching {
            context.assets.open("themes/$id/$name").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.let { return it }
        val f = File(context.filesDir, "themes/$id/$name")
        return if (f.isFile) BitmapFactory.decodeFile(f.absolutePath) else null
    }
}

// ===== 开关绑定 =====

/** `bind` → 偏好键。对不上真实键的返回 null，该节点直接跳过并记日志（不崩）。 */
private fun bindKey(bind: String): String? = when (bind) {
    "superIsland" -> MasterPrefs.KEY_ENABLE_SUPER_ISLAND
    "statusBarLyric" -> StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC
    "lyricTranslation" -> MasterPrefs.KEY_SHOW_TRANSLATION
    "albumCoverRotation" -> MasterPrefs.KEY_ISLAND_ALBUM_COVER_ROTATION
    "axisPriority" -> MasterPrefs.KEY_MANUAL_AXIS_PRIORITY
    else -> null
}

private fun readBind(context: Context, bind: String): Boolean = when (bind) {
    "superIsland" -> MasterPrefs.getBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true)
    "statusBarLyric" -> StatusBarPrefs.getBoolean(
        context, StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC, false
    )
    "lyricTranslation" -> MasterPrefs.getBoolean(context, MasterPrefs.KEY_SHOW_TRANSLATION, false)
    "albumCoverRotation" -> MasterPrefs.getBoolean(
        context, MasterPrefs.KEY_ISLAND_ALBUM_COVER_ROTATION, false
    )
    // 这个键存的是 Int（0=人工轴优先，1=在线优先），不能用 getBoolean 读：
    // boolean 与 int 语义相反，会把「在线优先」显示成「人工轴优先」。
    "axisPriority" -> MasterPrefs.getInt(context, MasterPrefs.KEY_MANUAL_AXIS_PRIORITY, 1) == 0
    else -> false
}

/**
 * 写偏好。超级岛与状态栏歌词是"二选一"关系（和首页卡1、设置页同一套互拆逻辑）：
 * 打开一个就关掉另一个，保证 SystemUI 任意时刻至少一套开着。
 */
private fun writeBind(context: Context, bind: String, value: Boolean) {
    when (bind) {
        "superIsland" -> if (value) {
            MasterPrefs.setBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true)
            StatusBarPrefs.setBoolean(
                context, StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC, false
            )
        } else {
            StatusBarPrefs.setBoolean(
                context, StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC, true
            )
            MasterPrefs.setBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, false)
        }

        "statusBarLyric" -> if (value) {
            StatusBarPrefs.setBoolean(
                context, StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC, true
            )
            MasterPrefs.setBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, false)
        } else {
            MasterPrefs.setBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true)
            StatusBarPrefs.setBoolean(
                context, StatusBarPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC, false
            )
        }

        "lyricTranslation" -> MasterPrefs.setBoolean(
            context, MasterPrefs.KEY_SHOW_TRANSLATION, value
        )

        "albumCoverRotation" -> MasterPrefs.setBoolean(
            context, MasterPrefs.KEY_ISLAND_ALBUM_COVER_ROTATION, value
        )

        // 同上：写 Int，开=人工轴优先(0)，关=在线优先(1)；写 boolean 会让中枢读错档位。
        "axisPriority" -> MasterPrefs.setInt(
            context, MasterPrefs.KEY_MANUAL_AXIS_PRIORITY, if (value) 0 else 1
        )
    }
}

// ===== 页面动作 =====

private class ArtActions(
    val axis: () -> Unit,
    val superIsland: () -> Unit,
    val statusBarLyric: () -> Unit,
    val lyricSource: () -> Unit,
    val store: () -> Unit,
    val advanced: () -> Unit,
    val records: () -> Unit,
    val about: () -> Unit,
    val refresh: () -> Unit,
    val prev: () -> Unit,
    val next: () -> Unit,
    val playPause: () -> Unit,
) {
    fun run(action: String) {
        when (action) {
            "axis" -> axis()
            "superIsland" -> superIsland()
            "statusBarLyric" -> statusBarLyric()
            "lyricSource" -> lyricSource()
            "store" -> store()
            "advanced" -> advanced()
            "records" -> records()
            "about" -> about()
            "refresh" -> refresh()
            "prev" -> prev()
            "next" -> next()
            "playPause" -> playPause()
            else -> Unit // none / 未知：纯装饰
        }
    }
}

/** 发一次系统媒体键，让正在播的播放器自己响应（上一曲 / 下一曲 / 播放暂停） */
private fun sendMediaKey(context: Context, keyCode: Int) {
    runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}

// ===== 首页 =====

@Composable
internal fun ArtHome(
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
    onOpenAbout: () -> Unit,
) {
    val pack = remember(skin.artThemeId, ArtThemeLoader.revision) {
        skin.artThemeId?.let { ArtThemeLoader.load(context, it) }
    }

    // 包读不出来：退回皮肤自带渐变 + 一行提示，但底部主题条必须留着，否则用户没法导入
    if (pack == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(skin.bgTop, skin.bgMid, skin.bgBottom)
                    )
                )
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("主题包未导入", fontSize = 16.sp, color = skin.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "到 设置 → 更多设置 → 主题设置 导入主题 zip",
                    fontSize = 13.sp,
                    color = skin.textSecondary,
                )
            }
            Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                HomeSkinPicker(
                    currentId = skinId,
                    onPick = { id -> HomeSkins.write(context, id) },
                )
            }
        }
        return
    }

    var islandOn by remember {
        mutableStateOf(
            MasterPrefs.getBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true)
        )
    }
    // 手动刷新：立刻读一次当前播放，1.2s 后交还给 600ms 的自动轮询
    var manualNow by remember { mutableStateOf<NowPlaying?>(null) }
    LaunchedEffect(manualNow) {
        if (manualNow != null) {
            delay(1200)
            manualNow = null
        }
    }
    val now = manualNow ?: live.now

    val actions = ArtActions(
        axis = onOpenAxis,
        superIsland = onOpenSuperIsland,
        statusBarLyric = onOpenStatusBarLyric,
        lyricSource = onOpenLyricSource,
        store = onOpenStore,
        advanced = onOpenAdvanced,
        records = onOpenRecords,
        about = onOpenAbout,
        refresh = { manualNow = NowPlayingReader.read(context) },
        prev = {
            sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            manualNow = NowPlayingReader.read(context)
        },
        next = {
            sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            manualNow = NowPlayingReader.read(context)
        },
        playPause = {
            sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            manualNow = NowPlayingReader.read(context)
        },
    )

    val palette = pack.meta.palette
    val moduleState = if (moduleActive) "模块已激活" else "模块未激活 · 需重启状态栏"
    val footer = moduleState +
        " · Android " + Build.VERSION.RELEASE

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06070C))
            .clipToBounds()
    ) {
        // 以短边为基准：竖屏 = 屏宽（与原来完全一致），横屏 = 屏高，同一套 nodes 两个方向都等比
        val boxW = maxWidth
        val boxH = maxHeight
        val w = if (boxW.value <= boxH.value) boxW else boxH
        val designH = (w * pack.meta.ratio).value
        // fit=height：按屏幕可用高度铺满（竖屏把底部主题条的位置留出来，横屏按高度整体缩放）
        val usable = if (pack.meta.fit == "height") (boxH.value - 84f) else designH
        val ratio = usable / w.value
        // 长屏上文字按屏宽算会偏小，纵向铺满时整体补一档
        val sc = if (pack.meta.fit == "height") 1.18f else 1f

        // ① 底图：铺满整屏，顶对齐（20:9 屏比 9:16 底图高，多出来的宽度在左右裁掉，不能露黑底）
        Image(
            bitmap = pack.bg.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )

        // ② 逐节点绘制（数组顺序即层叠顺序）；横屏时内容按短边等比并水平居中
        Box(
            modifier = Modifier
                .width(w)
                .fillMaxHeight()
                .align(Alignment.TopCenter),
        ) {
            pack.nodes.forEach { node ->
                when (node.type) {
                    "card" -> ArtCard(node, palette, w, ratio)
                    "text" -> ArtText(node, palette, w, ratio, now, islandOn, footer, moduleState, sc)
                    "chips" -> ArtChips(node, palette, w, ratio, actions, sc)
                    "button" -> ArtButton(node, palette, w, ratio, actions, sc)
                    "toggle" -> ArtToggle(node, palette, w, ratio, context, islandOn) { islandOn = it }
                    "island" -> ArtIsland(node, palette, w, ratio, now, sc)
                    "quick" -> ArtQuick(node, palette, w, ratio, actions, pack)
                    "rows" -> ArtRows(node, palette, w, ratio, actions, sc)
                    "disc" -> ArtImage(node, w, ratio, pack, rotateWhenPlaying = now.playing)
                    "icon" -> ArtImage(node, w, ratio, pack, rotateWhenPlaying = false)
                    "waveicon" -> ArtWave(node, palette, w, ratio)
                    "progress" -> Unit // ⏳ 主题设计标准里标为"待实现"，跳过不崩
                    else -> Unit
                }
            }
        }

        // ③ 主题选择条固定在最底（和其它五套一致，换主题不用先进设置页）
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            HomeSkinPicker(
                currentId = skinId,
                onPick = { id -> HomeSkins.write(context, id) },
            )
        }
    }
}

// ===== 各类节点 =====

@Composable
private fun ArtCard(node: ArtNode, palette: ArtPalette, w: androidx.compose.ui.unit.Dp, ratio: Float) {
    val r = node.rect
    val radius = w * node.props.optDouble("radius", 0.0204).toFloat()
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .size(width = w * r[2], height = w * ratio * r[3])
            .shadow(
                elevation = if (node.props.optBoolean("glow", false)) 10.dp else 2.dp,
                shape = shape,
                clip = false,
                ambientColor = palette.cardBorder.copy(alpha = 0.28f),
                spotColor = palette.cardBorder.copy(alpha = 0.28f),
            )
            .background(
                palette.cardBg.copy(alpha = palette.cardBgAlpha), shape
            )
            .border(
                1.dp,
                palette.cardBorder.copy(alpha = palette.cardBorderAlpha),
                shape,
            )
    )
}

@Composable
private fun ArtText(
    node: ArtNode,
    palette: ArtPalette,
    w: androidx.compose.ui.unit.Dp,
    ratio: Float,
    now: NowPlaying,
    islandOn: Boolean,
    footer: String,
    moduleState: String,
    sc: Float = 1f,
) {
    val r = node.rect
    val p = node.props
    val text = resolvePlaceholders(
        p.optString("text"), now, islandOn, footer, moduleState
    )
    val size = (w.value * p.optDouble("size", 0.026).toFloat() * sc).sp
    val weight = if (p.optString("weight") == "bold") FontWeight.Bold else FontWeight.Normal
    val color = paletteColorOr(p.optString("color"), palette.textPrimary)
    val align = when (p.optString("align")) {
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> TextAlign.Start
    }
    val horizontal = when (p.optString("align")) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }
    val bottomAnchor = p.optString("anchor") == "bottom"
    val lh = p.optDouble("lineHeight", 0.0).toFloat()
    Column(
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .size(width = w * r[2], height = w * ratio * r[3]),
        horizontalAlignment = horizontal,
        verticalArrangement = if (bottomAnchor) Arrangement.Bottom else Arrangement.Top,
    ) {
        Text(
            text = text,
            fontSize = size,
            fontWeight = weight,
            color = color,
            textAlign = align,
            maxLines = p.optInt("maxLines", 1).coerceAtLeast(1),
            overflow = if (p.optBoolean("ellipsis", true)) TextOverflow.Ellipsis
            else TextOverflow.Clip,
            lineHeight = if (lh > 0f) (size.value * lh).sp else TextUnit.Unspecified,
        )
    }
}

@Composable
private fun ArtChips(
    node: ArtNode,
    palette: ArtPalette,
    w: androidx.compose.ui.unit.Dp,
    ratio: Float,
    actions: ArtActions,
    sc: Float = 1f,
) {
    val r = node.rect
    val p = node.props
    val items = p.optJSONArray("items") ?: return
    val size = (w.value * p.optDouble("size", 0.022).toFloat() * sc).sp
    val gap = w * p.optDouble("gap", 0.008).toFloat()
    val h = w * ratio * p.optDouble("height", 0.024).toFloat()
    val radius = p.optDouble("radius", 0.0065).toFloat()
    // 放不下的胶囊整颗跳过（文档："总宽不够会自动省略后面的"），不能切半个
    val availDp = (w * r[2]).value
    val padDp = w.value * 0.0111f
    var usedDp = 0f
    Row(
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .width(w * r[2])
            .height(h)
            .clipToBounds(),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until items.length()) {
            val item = items.opt(i)
            val label = if (item is JSONObject) item.optString("label") else item?.toString().orEmpty()
            if (label.isBlank()) continue
            // 中文按 1em/字估宽，宁可少画一颗也不画半颗
            val chipDp = label.length * size.value + padDp * 2f
            if (usedDp + chipDp > availDp) break
            usedDp += chipDp + gap.value
            val action = if (item is JSONObject) item.optString("action") else ""
            Box(
                modifier = Modifier
                    .width(chipDp.dp)
                    .height(h)
                    .background(
                        palette.accent.copy(alpha = 0.14f),
                        RoundedCornerShape(w * radius),
                    )
                    .border(
                        1.dp,
                        palette.accent.copy(alpha = 0.28f),
                        RoundedCornerShape(w * radius),
                    )
                    .then(
                        if (action.isNotBlank() && action != "none")
                            Modifier.clickable { actions.run(action) }
                        else Modifier
                    )
                    .padding(horizontal = padDp.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = size,
                    color = palette.textPrimary.copy(alpha = 0.92f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ArtButton(
    node: ArtNode,
    palette: ArtPalette,
    w: androidx.compose.ui.unit.Dp,
    ratio: Float,
    actions: ArtActions,
    sc: Float = 1f,
) {
    val r = node.rect
    val p = node.props
    val filled = p.optBoolean("filled", false)
    val radius = w * p.optDouble("radius", 0.02).toFloat()
    val size = (w.value * p.optDouble("size", 0.02).toFloat() * sc).sp
    val action = p.optString("action", "none")
    val icon = p.optString("icon")
    Row(
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .size(width = w * r[2], height = w * ratio * r[3])
            .background(
                if (filled) palette.accent else palette.accent.copy(alpha = 0.12f),
                RoundedCornerShape(radius),
            )
            .border(
                1.dp,
                palette.accent.copy(alpha = if (filled) 0.9f else 0.34f),
                RoundedCornerShape(radius),
            )
            .then(
                if (action != "none") Modifier.clickable { actions.run(action) } else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon == "heart") {
            Text("♡ ", fontSize = size, color = palette.textPrimary.copy(alpha = 0.9f))
        } else if (icon == "refresh") {
            Text("↻ ", fontSize = size, color = palette.textPrimary.copy(alpha = 0.9f))
        }
        Text(
            p.optString("text"),
            fontSize = size,
            color = if (filled) Color.White else palette.textPrimary.copy(alpha = 0.9f),
            maxLines = 1,
        )
    }
}

@Composable
private fun ArtToggle(
    node: ArtNode,
    palette: ArtPalette,
    w: androidx.compose.ui.unit.Dp,
    ratio: Float,
    context: Context,
    islandOn: Boolean,
    onIslandChange: (Boolean) -> Unit,
) {
    val r = node.rect
    val p = node.props
    val bind = p.optString("bind")
    if (bindKey(bind) == null) return
    // 超级岛这一路要跟首页卡1、状态栏互斥，读同一个状态位保证视觉与真实偏好一致
    val checked = if (bind == "superIsland") islandOn else readBind(context, bind)
    ArtSwitch(
        checked = checked,
        accent = palette.accent,
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .size(width = w * r[2], height = w * ratio * r[3]),
    ) { value ->
        writeBind(context, bind, value)
        if (bind == "superIsland") onIslandChange(value)
    }
}

@Composable
private fun ArtIsland(
    node: ArtNode,
    palette: ArtPalette,
    w: androidx.compose.ui.unit.Dp,
    ratio: Float,
    now: NowPlaying,
    sc: Float = 1f,
) {
    val r = node.rect
    val p = node.props
    val radius = w * p.optDouble("radius", 0.02).toFloat()
    val size = (w.value * p.optDouble("size", 0.023).toFloat() * sc).sp
    val lyric = resolvePlaceholders(p.optString("text"), now, true, "", "").ifBlank { "暂无歌词" }
    Row(
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .size(width = w * r[2], height = w * ratio * r[3])
            .background(palette.islandBg, RoundedCornerShape(radius))
            .border(1.dp, palette.accent.copy(alpha = 0.45f), RoundedCornerShape(radius))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    if (now.playing) palette.accent else palette.textSecondary.copy(alpha = 0.5f),
                    CircleShape,
                )
        )
        Spacer(Modifier.width(9.dp))
        Text(
            lyric,
            fontSize = size,
            color = palette.islandLyric,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtQuick(
    node: ArtNode,
    palette: ArtPalette,
    w: androidx.compose.ui.unit.Dp,
    ratio: Float,
    actions: ArtActions,
    pack: ArtThemePack,
) {
    val r = node.rect
    val p = node.props
    val src = p.optString("src")
    val action = p.optString("action", "none")
    val label = p.optString("label")
    val labelY = p.optDouble("labelY", (r[1] + r[3] + 0.006).toDouble()).toFloat()
    val labelSize = (w.value * p.optDouble("labelSize", 0.023).toFloat()).sp
    val bitmap = pack.assets[src]

    Box(
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .size(width = w * r[2], height = w * ratio * r[3])
            .then(
                if (action != "none") Modifier.clickable { actions.run(action) } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    if (label.isNotBlank()) {
        Box(
            modifier = Modifier
                .offset(x = w * r[0], y = w * ratio * labelY)
                .width(w * r[2]),
        ) {
            Text(
                label,
                fontSize = labelSize,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ArtRows(
    node: ArtNode,
    palette: ArtPalette,
    w: androidx.compose.ui.unit.Dp,
    ratio: Float,
    actions: ArtActions,
    sc: Float = 1f,
) {
    val r = node.rect
    val p = node.props
    val items = p.optJSONArray("items") ?: return
    val rowH = w * ratio * p.optDouble("rowHeight", 0.0396).toFloat()
    val size = (w.value * p.optDouble("size", 0.0269).toFloat() * sc).sp
    val rightSize = (w.value * p.optDouble("rightSize", 0.0241).toFloat() * sc).sp

    Column(
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .width(w * r[2])
    ) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val action = item.optString("action", "none")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowH)
                    .then(
                        if (action != "none") Modifier.clickable { actions.run(action) } else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.optString("label"),
                    fontSize = size,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    item.optString("right"),
                    fontSize = rightSize,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text("›", fontSize = rightSize, color = palette.textSecondary)
            }
            if (i < items.length() - 1) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.cardBorder.copy(alpha = 0.18f))
                )
            }
        }
    }
}

@Composable
private fun ArtImage(
    node: ArtNode,
    w: androidx.compose.ui.unit.Dp,
    ratio: Float,
    pack: ArtThemePack,
    rotateWhenPlaying: Boolean,
) {
    val r = node.rect
    val p = node.props
    val src = p.optString("src")
    val bitmap = pack.assets[src] ?: return
    val wanted = p.optBoolean("rotate", false) && rotateWhenPlaying
    val angle = if (wanted) {
        val transition = rememberInfiniteTransition(label = "disc")
        val a by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 24000, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "discAngle",
        )
        a
    } else {
        0f
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .size(width = w * r[2], height = w * ratio * r[3])
            .then(if (wanted) Modifier.rotate(angle) else Modifier),
    )
}

@Composable
private fun ArtWave(
    node: ArtNode,
    palette: ArtPalette,
    w: androidx.compose.ui.unit.Dp,
    ratio: Float,
) {
    val r = node.rect
    Canvas(
        modifier = Modifier
            .offset(x = w * r[0], y = w * ratio * r[1])
            .size(width = w * r[2], height = w * ratio * r[3])
    ) {
        val d = minOf(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(color = palette.accent, radius = d / 2f, center = Offset(cx, cy))
        val bars = 6
        val barW = d * 0.055f
        for (i in 0 until bars) {
            val t = (i + 0.5f) / bars
            val amp = d * (0.20f + 0.34f * kotlin.math.sin(t * Math.PI.toFloat()))
            val x = cx - d * 0.28f + d * 0.56f * (i.toFloat() / (bars - 1))
            drawLine(
                color = Color.White,
                start = Offset(x, cy - amp),
                end = Offset(x, cy + amp),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 自绘开关：深色主题用，轨道跟 palette.accent。 */
@Composable
private fun ArtSwitch(
    checked: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Canvas(modifier = modifier.pointerInput(checked) { detectTapGestures { onCheckedChange(!checked) } }) {
        val h = size.height
        val r = h / 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(
                if (checked) listOf(accent.copy(alpha = 0.85f), accent)
                else listOf(Color(0xFF3A4155), Color(0xFF2B3140))
            ),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(r, r),
        )
        val knobR = r - 2.dp.toPx()
        val cx = if (checked) size.width - r else r
        drawCircle(Color(0x33000000), knobR, Offset(cx, h / 2f + 1.dp.toPx()))
        drawCircle(Color.White, knobR, Offset(cx, h / 2f))
    }
}

// ===== 占位符 =====

private fun resolvePlaceholders(
    template: String,
    now: NowPlaying,
    islandOn: Boolean,
    footer: String,
    moduleState: String,
): String {
    if (template.isBlank()) return ""
    var s = template
        .replace("{title}", if (now.hasTrack) now.title else "暂无播放")
        .replace("{artist}", now.artist.ifBlank { "—" })
        .replace("{lyric}", lyricLine(now))
        .replace("{next}", now.next)
        .replace("{islandState}", if (islandOn) "歌词显示在超级岛 · 开启中" else "歌词显示在状态栏")
        .replace("{moduleState}", moduleState)
        .replace("{footer}", footer)
        .replace("{time}", clockText("HH:mm"))
        .replace("{date}", clockText("M月d日"))
    if (s.contains("{progress}")) {
        val pct = if (now.durationMs > 0L) {
            (now.positionMs.toFloat() / now.durationMs * 100f).toInt().coerceIn(0, 100)
        } else 0
        s = s.replace("{progress}", "$pct%")
    }
    if (s.contains("{remain}")) {
        val remain = (now.durationMs - now.positionMs).coerceAtLeast(0L)
        s = s.replace("{remain}", mmss(remain))
    }
    return s
}

private fun clockText(pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

/**
 * 岛里显示的歌词行。
 * SystemUI 拿不到真歌词时会把「歌名 - 歌手」塞进 lyric 字段，直接显示就穿帮，
 * 这种情况退回下一句歌词，再退回歌名。
 */
private fun lyricLine(now: NowPlaying): String {
    val l = now.lyric.trim()
    if (l.isEmpty()) return if (now.hasTrack) now.title else "暂无歌词"
    if (now.artist.isNotBlank() && l == "${now.title} - ${now.artist}") {
        return now.next.trim().ifBlank { now.title }
    }
    return l
}

private fun mmss(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

private fun paletteColorOr(hex: String, fallback: Color): Color {
    val raw = hex.trim()
    if (raw.isEmpty()) return fallback
    return runCatching {
        val s = raw.removePrefix("#")
        val v = when (s.length) {
            6 -> 0xFF000000L or s.toLong(16)
            8 -> s.toLong(16)
            else -> return fallback
        }
        Color(v)
    }.getOrDefault(fallback)
}
