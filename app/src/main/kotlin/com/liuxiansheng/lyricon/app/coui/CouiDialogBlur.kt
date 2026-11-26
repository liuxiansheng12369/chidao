/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 */

package com.liuxiansheng.lyricon.app.coui

import android.os.Build
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.itos.coui.dialog.CouiDialogColors
import com.itos.coui.dialog.CouiDialogDefaults

/**
 * 凝光弹窗统一配色：真半透着色面（约 62% 不透明）+ 很低压暗，
 * 让系统跨窗口背景模糊真的能透出来。上游原生材质（CouiDialogMaterial + 窗口采样源）
 * 在本机实测会让 HWUI 渲染树成环、native 栈溢出，只能走系统模糊这条路。
 */
@Composable
internal fun couiGlassDialogColors(dark: Boolean): CouiDialogColors {
    return CouiDialogDefaults.themeColors(dark).copy(
        surface = if (dark) Color(0x9E1C1C1E) else Color(0x9EFFFFFF),
        dimAmount = if (dark) 0.20f else 0.06f,
    )
}

/**
 * 弹窗背景模糊：用系统（HyperOS / Android 12+）的跨窗口背景模糊，
 * 代替 COUI 依赖的 Compose 离屏窗口采样。
 *
 * 为什么必须换：
 * COUI 的凝光弹窗材质要宿主提供“窗口背景采样源”（ProvideCouiMenuBackdrop），
 * 本机实测该采样层与 HWUI 渲染树互相引用成环，启动即 native 栈溢出
 * （libhwui RenderNode::prepareTreeImpl 递归 500+ 帧）。系统模糊由 SurfaceFlinger
 * 在合成阶段完成，不进入本进程渲染树，所以稳定。
 *
 * COUI 的 Dialog 内部会主动 clearFlags(FLAG_BLUR_BEHIND) 并把模糊半径置 0
 * （它默认走自绘 Kawase 材质），因此这里挂 PreDraw 监听，在每帧绘制前重新写回，
 * 保证不被内部重置覆盖。
 */
@Composable
internal fun CouiDialogSystemBlur(radius: Dp = 68.dp) {
    if (Build.VERSION.SDK_INT < 31) return
    val view = LocalView.current
    val density = LocalDensity.current
    DisposableEffect(view, density, radius) {
        val px = with(density) { radius.toPx() }.toInt().coerceAtLeast(1)
        fun findWindow(): android.view.Window? {
            var node: android.view.View? = view
            while (node != null) {
                (node as? DialogWindowProvider)?.window?.let { return it }
                node = node.parent as? android.view.View
            }
            return null
        }
        fun applyBlur() {
            val window = findWindow() ?: return
            runCatching {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.setBackgroundBlurRadius(px)
            }
        }
        applyBlur()
        // 上游 CouiDialogWindow 在 SideEffect 里会清掉模糊，组合完成后再补两次
        view.post { applyBlur() }
        view.postDelayed({ applyBlur() }, 160L)
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            applyBlur()
            true
        }
        observer.addOnPreDrawListener(listener)
        onDispose {
            val current = if (observer.isAlive) observer else view.viewTreeObserver
            if (current.isAlive) current.removeOnPreDrawListener(listener)
        }
    }
}
