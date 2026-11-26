/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 *
 * HomeSkin：首页主题皮肤（1=黑胶唱片 / 2=晨雾留白 / 3=赛博霓虹）。
 * id=0 表示"默认"——完全走原有逻辑（流光背景 + 现有玻璃卡片配色），本文件不参与。
 * 皮肤只影响首页（MainActivity HomeScreen）与其中卡片；设置页仍用 Miuix 主题。
 */

package com.liuxiansheng.lyricon.app.activity

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 光晕：位置用画布宽高的比例，半径按宽度比例 */
data class HomeGlow(
    val color: Color,
    val cx: Float,
    val cy: Float,
    val radiusRatio: Float,
)

data class HomeSkin(
    val id: Int,
    val name: String,
    val desc: String,
    val dark: Boolean,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val glows: List<HomeGlow>,
    val cardBg: Color,
    val cardBorder: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val chips: List<Color>,
    val islandBg: Color,
    val islandLyric: Color,
    /** 首页整体布局：0=经典区块（原有四套皮肤），1=唱片机，2=晨雾概览，3=仪表盘，4=歌词杂志，5=纯净蓝，6=艺术主题（数据驱动） */
    val layout: Int = HomeSkins.LAYOUT_CLASSIC,
    /** 艺术主题（layout = LAYOUT_ART）用：主题包 id，对应 assets/themes/<id>/ 或 filesDir/themes/<id>/ */
    val artThemeId: String? = null,
) {
    fun chipFor(key: String): Color = chips[Math.floorMod(key.hashCode(), chips.size)]
}

object HomeSkins {

    const val KEY = "pref_home_skin"
    const val DEFAULT = 5
    private const val KEY_V5_MIGRATED = "pref_home_skin_v5_migrated"

    const val LAYOUT_CLASSIC = 0
    const val LAYOUT_DECK = 1
    const val LAYOUT_MIST = 2
    const val LAYOUT_DASH = 3
    const val LAYOUT_LYRIC = 4
    const val LAYOUT_BOARD = 5
    const val LAYOUT_ART = 6

    /** ① 唱片机（原「黑胶唱片」换新布局）：深棕 + 铜金，首页主体是黑胶大唱片 */
    val vinyl = HomeSkin(
        id = 1,
        name = "唱片机",
        desc = "深棕 + 铜金，首页即唱机",
        dark = true,
        bgTop = Color(0xFF14100C),
        bgMid = Color(0xFF1B1510),
        bgBottom = Color(0xFF241C14),
        glows = listOf(
            HomeGlow(Color(0x99C8934E), 0.86f, 0.12f, 0.72f),
            HomeGlow(Color(0x553A2A18), 0.12f, 0.74f, 0.60f),
        ),
        cardBg = Color(0xFF221B14).copy(alpha = 0.92f),
        cardBorder = Color(0xFFE9B872).copy(alpha = 0.32f),
        accent = Color(0xFFE9B872),
        textPrimary = Color(0xFFF6F0E4),
        textSecondary = Color(0xFFA79A88),
        chips = listOf(
            Color(0xFFE9B872), Color(0xFF8AB2A4), Color(0xFF9A92CE), Color(0xFFD79A82),
        ),
        islandBg = Color(0xFF2A2320).copy(alpha = 0.96f),
        islandLyric = Color(0xFFF7E1BC),
        layout = LAYOUT_DECK,
    )

    /** ② 晨雾概览（原「晨雾留白」换新布局）：白纸大留白 + 今日听歌数据 */
    val mist = HomeSkin(
        id = 2,
        name = "晨雾概览",
        desc = "白纸留白 + 今日数据",
        dark = false,
        bgTop = Color(0xFFFBFBFA),
        bgMid = Color(0xFFF4F5F6),
        bgBottom = Color(0xFFE7EBEE),
        glows = listOf(
            HomeGlow(Color(0xCCFFFFFF), 0.80f, 0.08f, 0.70f),
            HomeGlow(Color(0x55C0D2DE), 0.16f, 0.72f, 0.60f),
        ),
        cardBg = Color(0xFFFFFFFF).copy(alpha = 0.95f),
        cardBorder = Color(0xFF5A7E94).copy(alpha = 0.26f),
        accent = Color(0xFF5A7E94),
        textPrimary = Color(0xFF20242A),
        textSecondary = Color(0xFF7A8086),
        chips = listOf(
            Color(0xFF5A7E94), Color(0xFF87A6B5), Color(0xFF6E8CA0), Color(0xFFA8BAC4),
        ),
        islandBg = Color(0xFFEFF3F6).copy(alpha = 0.97f),
        islandLyric = Color(0xFF243340),
        layout = LAYOUT_MIST,
    )

    /** ③ 深空仪表（原「赛博霓虹」换新布局）：深色玻璃 + 16 色频谱 + 听歌数据 */
    val cyber = HomeSkin(
        id = 3,
        name = "深空仪表",
        desc = "16 色频谱 + 听歌数据",
        dark = true,
        bgTop = Color(0xFF070C10),
        bgMid = Color(0xFF0A1318),
        bgBottom = Color(0xFF0E1A1E),
        glows = listOf(
            HomeGlow(Color(0x6646E6C8), 0.88f, 0.10f, 0.66f),
            HomeGlow(Color(0x55FF3E8E), 0.10f, 0.70f, 0.58f),
        ),
        cardBg = Color(0xFF131C22).copy(alpha = 0.94f),
        cardBorder = Color(0xFF46E6C8).copy(alpha = 0.34f),
        accent = Color(0xFF46E6C8),
        textPrimary = Color(0xFFE9F5F3),
        textSecondary = Color(0xFF8CA2A6),
        chips = listOf(
            Color(0xFF46E6C8), Color(0xFFFF3E8E), Color(0xFF7C8CFF), Color(0xFFFFB454),
        ),
        islandBg = Color(0xFF060D10).copy(alpha = 0.96f),
        islandLyric = Color(0xFF9BFFF0),
        layout = LAYOUT_DASH,
    )

    /** ④ 歌词杂志：整页歌词 + 玻璃面板 */
    val lyric = HomeSkin(
        id = 4,
        name = "歌词杂志",
        desc = "整页歌词，大字高亮",
        dark = true,
        bgTop = Color(0xFF05161A),
        bgMid = Color(0xFF072025),
        bgBottom = Color(0xFF0A2A2D),
        glows = listOf(
            HomeGlow(Color(0x6646C8BC), 0.82f, 0.14f, 0.72f),
            HomeGlow(Color(0x55306E78), 0.18f, 0.72f, 0.60f),
        ),
        cardBg = Color(0xFF0B2429).copy(alpha = 0.72f),
        cardBorder = Color(0xFF7CF0E2).copy(alpha = 0.30f),
        accent = Color(0xFF7CF0E2),
        textPrimary = Color(0xFFE6F6F4),
        textSecondary = Color(0xFF8FA6A8),
        chips = listOf(
            Color(0xFF7CF0E2), Color(0xFF69B8C8), Color(0xFF9AA8D0), Color(0xFFD6A98C),
        ),
        islandBg = Color(0xFF041012).copy(alpha = 0.98f),
        islandLyric = Color(0xFFD9FFFA),
        layout = LAYOUT_LYRIC,
    )

    /** ⑤ 纯净蓝（新版默认）：浅蓝系统风 + 圆角白卡 + 黑岛胶囊，按效果图 1:1 复刻 */
    val board = HomeSkin(
        id = 5,
        name = "纯净蓝",
        desc = "浅蓝白卡 · 新版默认",
        dark = false,
        bgTop = Color(0xFFF6FAFF),
        bgMid = Color(0xFFEDF3FD),
        bgBottom = Color(0xFFE3EBF9),
        glows = listOf(
            HomeGlow(Color(0x66FFFFFF), 0.90f, 0.05f, 0.80f),
            HomeGlow(Color(0x2E4C8CF8), 0.10f, 0.96f, 0.72f),
        ),
        cardBg = Color(0xFFFDFDFF).copy(alpha = 0.98f),
        cardBorder = Color(0xFF2C77F2).copy(alpha = 0.10f),
        accent = Color(0xFF2C77F2),
        textPrimary = Color(0xFF1A1B22),
        textSecondary = Color(0xFF8A90A6),
        chips = listOf(
            Color(0xFF2C77F2), Color(0xFF4D90FF), Color(0xFF37BE5A), Color(0xFF8A90A6),
        ),
        islandBg = Color(0xFF07080C),
        islandLyric = Color(0xFF4D90FF),
        layout = LAYOUT_BOARD,
    )

    /** ⑥ 星云·深空（第 7 套）：艺术主题，界面由主题包 nodes.json 数据驱动，不走写死的 Compose 布局 */
    val artDark = HomeSkin(
        id = 6,
        name = "星云·深空",
        desc = "星云底图 · 数据驱动",
        dark = true,
        bgTop = Color(0xFF070A14),
        bgMid = Color(0xFF0B1020),
        bgBottom = Color(0xFF05070E),
        glows = listOf(
            HomeGlow(Color(0x554C6FFF), 0.80f, 0.10f, 0.72f),
            HomeGlow(Color(0x333D7BFF), 0.14f, 0.86f, 0.60f),
        ),
        cardBg = Color(0xFF12141F).copy(alpha = 0.58f),
        cardBorder = Color(0xFF7080FF).copy(alpha = 0.26f),
        accent = Color(0xFF3D7BFF),
        textPrimary = Color(0xFFEEF2FF),
        textSecondary = Color(0xFF95A0C0),
        chips = listOf(
            Color(0xFF3D7BFF), Color(0xFF7080FF), Color(0xFF9B7BFF), Color(0xFF7CE0D0),
        ),
        islandBg = Color(0xFF080A14),
        islandLyric = Color(0xFFE9EEFF),
        layout = LAYOUT_ART,
        artThemeId = "art_dark",
    )

    val all = listOf(board, vinyl, mist, cyber, lyric, artDark)

    fun of(id: Int): HomeSkin? = all.firstOrNull { it.id == id }

    fun read(context: Context): Int {
        val pref = context.getSharedPreferences(
            "com.liuxiansheng.lyricon_preferences", Context.MODE_PRIVATE
        )
        val stored = pref.getInt(KEY, DEFAULT)
        // 新版主页换成"纯净蓝"。升级后首次读取做一次性迁移：
        // 不管用户停在旧版哪套皮肤，都先带到新主页；迁移只做一次，
        // 之后用户在主题选择器里手动切回任意一套都不会再被改掉。
        if (!pref.getBoolean(KEY_V5_MIGRATED, false)) {
            pref.edit()
                .putInt(KEY, DEFAULT)
                .putBoolean(KEY_V5_MIGRATED, true)
                .apply()
            return DEFAULT
        }
        return stored
    }

    fun write(context: Context, id: Int) {
        context.getSharedPreferences(
            "com.liuxiansheng.lyricon_preferences", Context.MODE_PRIVATE
        ).edit().putInt(KEY, id).apply()
    }
}

/** 首页当前皮肤；null = 默认（走原有配色分支） */
val LocalHomeSkin = staticCompositionLocalOf<HomeSkin?> { null }
