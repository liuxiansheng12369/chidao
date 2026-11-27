/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现（语义对齐「词幕」SuperIslandConfig.isPackageAllowed）。
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.presentation

import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.root.HookEntry

/**
 * 超级岛歌词应用过滤（按包名决定"这个应用能不能在岛上出歌词"）。
 *
 * 三档语义（与「词幕」一致）：
 *   - [MODE_OFF]：不干预，所有应用照旧出歌词（默认）；
 *   - [MODE_WHITE]：只有名单内的应用能出歌词，其余应用（视频/短视频/听书/工具类）
 *     的媒体岛保持系统原生，我们不注入歌词；
 *   - [MODE_BLACK]：名单内的应用不出歌词，其余照旧。
 *
 * 白名单为空时回落 [DEFAULT_WHITE_PACKAGES]（常见音乐/播放器），
 * 避免用户刚切到白名单、还没勾任何应用时"所有应用都不出歌词"。
 * 黑名单为空 = 不屏蔽任何应用。
 *
 * 只读配置，不做缓存：SystemUI 侧每次判定都从共享 prefs 取当前值，
 * 用户改完设置即时生效，不需要重启系统界面。
 */
object IslandLyricAppFilter {

    const val MODE_OFF = 0
    const val MODE_WHITE = 1
    const val MODE_BLACK = 2

    /**
     * 白名单兜底清单：常见音乐/播放器。
     * 与 LiveStatePusher.MUSIC_PACKAGES 口径一致（那边管听歌记录，这边管歌词上岛）。
     */
    val DEFAULT_WHITE_PACKAGES: Set<String> = setOf(
        "com.luna.music",                        // 汽水音乐
        "com.netease.cloudmusic",                // 网易云音乐
        "com.tencent.qqmusic",                   // QQ 音乐
        "com.kugou.android",                     // 酷狗音乐
        "com.kugou.android.lite",                // 酷狗音乐概念版
        "cn.kuwo.player",                        // 酷我音乐
        "com.migu.music",                        // 咪咕音乐
        "cmccwm.mobilemusic",                    // 咪咕音乐（另一包名）
        "cn.toside.music.mobile",                // 落雪音乐
        "cn.tinyhappydog.musicfree",             // MusicFree
        "com.salt.music",                        // Salt Player
        "cn.wenyu.bodian",                       // 波点音乐
        "com.xuncorp.qinalt.music",              // 青盐云听
        "com.spotify.music",                     // Spotify
        "com.apple.android.music",               // Apple Music
        "com.google.android.apps.youtube.music", // YouTube Music
        "com.amazon.mp3",                        // Amazon Music
        "com.ximalaya.ting.android",             // 喜马拉雅
        "com.qingting.fm",                       // 蜻蜓 FM
        "com.lizhi.fm",                          // 荔枝 FM
        "com.tencent.karaoke",                   // 全民 K 歌
        "com.miui.player",                       // 小米音乐（系统）
        "com.huawei.music",                      // 华为音乐（系统）
        "com.qianqian.music",                    // 千千音乐
        "com.leguandev.sollin.v2",               // Sollin
        // —— 商店歌词源已经覆盖、但之前漏记的播放器（包名取自各源模块的 scope/常量）——
        "com.hihonor.cloudmusic",                // 荣耀云音乐（网易云源同源支持）
        "com.hihonor.music",                     // 荣耀音乐（ROM 播放器）
        "com.xiaomi.music",                      // 小米音乐（新版包名）
        "com.tencent.qqmusicpad",                // QQ 音乐 HD
        "com.tencent.freemusic",                 // QQ 音乐简洁版
        "com.maxmpz.audioplayer",                // Poweramp
        "ink.trantor.coneplayer",                // ConePlayer
        "ink.trantor.coneplayer.gp",             // ConePlayer GP
        "ink.trantor.coneplayer.go",             // ConePlayer Go
        "app.symfonik.music.player",             // Symfonium
        "org.akanework.gramophone",              // Gramophone
        "code.name.monkey.retromusic",           // Retro Music
        "top.imsyy.splayer.android",             // SPlayer
        "com.trim.music",                        // 飞牛音乐
        "com.meizu.media.music",                 // 魅族音乐
        "com.lxwalnut.music.mobile",             // 洛雪音乐（Walnut 分支）
        "com.ikunshare.music.mobile",            // 洛雪音乐（IKUN 分支）
        "com.lxnetease.music.mobile",            // 洛雪音乐（网易分支）
    )

    /** 当前模式：0 关闭 / 1 白名单 / 2 黑名单；读不到按关闭处理（绝不误伤现有行为）。 */
    fun currentMode(): Int {
        val prefs = HookEntry.instance?.prefs ?: return MODE_OFF
        return runCatching {
            prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_LYRIC_APP_FILTER_MODE,
                RootConstants.DEFAULT_HOOK_ISLAND_LYRIC_APP_FILTER_MODE
            )
        }.getOrDefault(MODE_OFF)
    }

    fun isEnabled(): Boolean = currentMode() != MODE_OFF

    /** 被过滤的应用是否连超级岛都不生成（默认 false：只不出歌词，系统原生岛照旧）。 */
    fun isNoIslandEnabled(): Boolean {
        if (!isEnabled()) return false
        val prefs = HookEntry.instance?.prefs ?: return false
        return runCatching {
            prefs.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_LYRIC_APP_FILTER_NO_ISLAND,
                false
            )
        }.getOrDefault(false)
    }

    /**
     * 这个包名是否允许在超级岛显示歌词。
     * 包名为空、模式关闭、prefs 不可用时一律 true（放行），保证不影响原有逻辑。
     */
    fun isAllowed(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        val mode = currentMode()
        if (mode == MODE_OFF) return true

        val prefs = HookEntry.instance?.prefs ?: return true
        val raw = runCatching {
            when (mode) {
                MODE_WHITE -> prefs.getString(
                    RootConstants.KEY_HOOK_ISLAND_LYRIC_APP_FILTER_WHITE, null
                )
                MODE_BLACK -> prefs.getString(
                    RootConstants.KEY_HOOK_ISLAND_LYRIC_APP_FILTER_BLACK, null
                )
                else -> null
            }
        }.getOrNull()

        val list = parseList(raw).let { parsed ->
            if (mode == MODE_WHITE && parsed.isEmpty()) DEFAULT_WHITE_PACKAGES else parsed
        }

        return when (mode) {
            MODE_WHITE -> packageName in list     // 白名单：仅名单内显示
            MODE_BLACK -> packageName !in list    // 黑名单：名单内不显示
            else -> true
        }
    }

    /** 名单串解析：支持中英文逗号、分号混用，去空白。 */
    fun parseList(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(",", "，", ";", "；")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** 日志用：当前模式与名单规模。 */
    fun describe(): String {
        val mode = currentMode()
        val name = when (mode) {
            MODE_WHITE -> "白名单"
            MODE_BLACK -> "黑名单"
            else -> "关闭"
        }
        if (mode == MODE_OFF) return "过滤=$name"
        val prefs = HookEntry.instance?.prefs
        val raw = runCatching {
            when (mode) {
                MODE_WHITE -> prefs?.getString(
                    RootConstants.KEY_HOOK_ISLAND_LYRIC_APP_FILTER_WHITE, null
                )
                else -> prefs?.getString(
                    RootConstants.KEY_HOOK_ISLAND_LYRIC_APP_FILTER_BLACK, null
                )
            }
        }.getOrNull()
        val size = parseList(raw).size
        return "过滤=$name($size)"
    }
}
