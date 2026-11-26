/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 *
 * SkinTheme：二级页（听歌记录 / 歌词源 / 更多设置 / 关于 / 状态栏歌词…）统一继承首页主题皮肤。
 * MainNav 会把当前皮肤的 ThemeController 放进 LocalSkinThemeController；
 * 二级页原来的 `MiuixTheme { }` 换成 `SkinTheme { }`，就不再各自回到默认浅色主题。
 *
 * 2026-11-25：深色皮肤（唱片机 / 深空仪表 / 歌词杂志）的二级页由 Miuix 默认暗色方案兜底，
 * 其中 onSurfaceVariantSummary 只有 0x80FFFFFF（50% 白）、onBackgroundVariant 是 0xFF787E96，
 * 压在这些肤色背景上就"淡到看不清"。这里在 SkinTheme 里统一提一档对比度：
 * 只调整次要文字三个 token，其它颜色原样不动，浅色方案不碰。
 */

package com.liuxiansheng.lyricon.app.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 当前全局主题控制器；null = 没有外层（独立 Activity 兜底走默认） */
val LocalSkinThemeController = staticCompositionLocalOf<ThemeController?> { null }

@Composable
fun SkinTheme(content: @Composable () -> Unit) {
    val controller = LocalSkinThemeController.current
    if (controller != null) {
        MiuixTheme(controller = controller) { ReadableSecondaryText(content) }
    } else {
        MiuixTheme { ReadableSecondaryText(content) }
    }
}

/**
 * 深色底上把「次要/说明文字」提亮一档，避免 50% 白压在深棕、深蓝、深灰上读不出来。
 * 浅色方案原样返回，不动。
 */
@Composable
private fun ReadableSecondaryText(content: @Composable () -> Unit) {
    val base = MiuixTheme.colorScheme
    if (base.background.luminance() >= 0.5f) {
        content()
        return
    }
    val boosted = remember(base) {
        base.copy(
            onSurfaceVariantSummary = Color(0xE0FFFFFF),
            onSurfaceVariantActions = Color(0xB3FFFFFF),
            onBackgroundVariant = Color(0xFFB9C0D0),
            onSurfaceContainerVariant = Color(0xFFA8A8A8),
        )
    }
    MiuixTheme(colors = boosted) { content() }
}
