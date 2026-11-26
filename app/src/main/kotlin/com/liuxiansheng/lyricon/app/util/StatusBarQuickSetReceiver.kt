/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * Copyright 2026 liuxiansheng（刘先生）
 *
 * 状态栏歌词面板的「快速设置」接收端。
 *
 * 背景：状态栏歌词面板运行在 SystemUI 进程里，能读设置（remote prefs 只读），
 * 但改不了——prefs 文件在词岛自己进程的私有目录。所以面板上的按钮发一条
 * 定向广播过来，由本接收器用 StatusBarPrefs 正规写入
 * （write → remote + 本地 + BridgeSender 广播），SystemUI 侧 PrefHotReload 收到后
 * 立即重绘，跟设置页里改是一样的效果。
 *
 * 只认状态栏歌词那几个键，别的键一律忽略，避免被别的应用乱写。
 */

package com.liuxiansheng.lyricon.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.liuxiansheng.lyricon.bridge.BridgeSender

class StatusBarQuickSetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 冷启动（被广播拉起进程）时 MainActivity 还没跑过，BridgeSender 里没有 context，
        // notifyPrefChanged 会被静默丢弃 —— 表现就是"面板点了没反应"。
        // initialize 幂等，这里补一次，保证「设置即时生效」广播一定发得出去。
        runCatching { BridgeSender.initialize(context.applicationContext) }

        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        if (key !in ALLOWED_KEYS) return
        // 主设置的颜色语义键要写进主 prefs 文件，其余写状态栏独立 prefs
        val target = if (key in MASTER_KEYS) MasterPrefs else StatusBarPrefs
        when (intent.getStringExtra(EXTRA_TYPE)) {
            TYPE_INT -> {
                if (!intent.hasExtra(EXTRA_INT)) return
                target.setInt(context, key, intent.getIntExtra(EXTRA_INT, 0))
            }
            TYPE_BOOL -> {
                if (!intent.hasExtra(EXTRA_BOOL)) return
                target.setBoolean(context, key, intent.getBooleanExtra(EXTRA_BOOL, false))
            }
            else -> Unit
        }
    }

    companion object {
        /** SystemUI 面板 → App 的写设置动作 */
        const val ACTION_QUICK_SET = "com.liuxiansheng.chidao.STATUS_BAR_QUICK_SET"

        const val EXTRA_KEY = "key"
        const val EXTRA_TYPE = "type"
        const val EXTRA_INT = "int"
        const val EXTRA_BOOL = "bool"

        const val TYPE_INT = "int"
        const val TYPE_BOOL = "bool"

        /** 只放行状态栏歌词这几项 */
        private val ALLOWED_KEYS = setOf(
            "key_hook_enable_status_bar_lyric",
            "key_hook_status_bar_lyric_content_mode",
            "key_hook_status_bar_lyric_text_size",
            "key_hook_status_bar_lyric_rainbow_enabled",
            "key_hook_status_bar_lyric_cover_color_enabled",
            "key_hook_status_bar_lyric_text_color",
            "key_hook_status_bar_lyric_font_family",
            "key_hook_status_bar_lyric_invert_enabled",
            "key_hook_status_bar_lyric_adjust_width_for_super_island",
            "key_hook_status_bar_lyric_hide_on_lock_screen",
            "key_hook_text_color_style",
            "key_hook_text_color",
        )

        /** 主设置的颜色语义键（写主 prefs 文件，状态栏与超级岛共用一套配色） */
        private val MASTER_KEYS = setOf(
            "key_hook_text_color_style",
            "key_hook_text_color",
        )
    }
}
