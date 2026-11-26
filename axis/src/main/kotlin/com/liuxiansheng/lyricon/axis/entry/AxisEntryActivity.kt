/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.entry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.liuxiansheng.lyricon.axis.ui.AxisApp

/**
 * 打轴器入口窗口。
 *
 * 由词岛设置页那一行「歌词打轴」startActivity 进来；不导出、不注册图标，
 * 所以用户感知不到它是独立模块的一部分。
 *
 * 预填的歌曲信息由词岛侧用 Intent extra 带进来（:axis 不能 import 主工程取当前播放）：
 * - [EXTRA_TITLE] / [EXTRA_ARTIST] / [EXTRA_DURATION_MS]
 */
class AxisEntryActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "axis_title"
        const val EXTRA_ARTIST = "axis_artist"
        const val EXTRA_DURATION_MS = "axis_duration_ms"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val artist = intent?.getStringExtra(EXTRA_ARTIST).orEmpty()
        val durationMs = intent?.getLongExtra(EXTRA_DURATION_MS, 0L) ?: 0L
        setContent {
            AxisApp(
                onClose = { finish() },
                initialTitle = title,
                initialArtist = artist,
                initialDurationMs = durationMs
            )
        }
    }
}
