/*
 * 真·凝光轮廓光：直接调用 COUI-Kit 的 CouiEdge 轮廓 shader。
 *
 * COUI 凝光的"亮边"不是画一条渐变线，而是 RuntimeShader：
 *   · 背景感知描边（按边缘像素亮度提亮 / 压暗）
 *   · 内光晕（沿轮廓往内渐隐）
 *   · 轮廓发光（contourGlow）
 * 之前自写的 drawBehind 四层只是仿制，浅色底上会被底色吃掉，看起来"没变化"。
 *
 * 这里只做轮廓：mix / blend 全透明 → 卡片内容（文字）一个像素都不改；
 * 不采样窗口背景（backdrop），所以不会触发本机 HWUI 采样成环崩溃。
 */

package com.liuxiansheng.lyricon.app.coui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.itos.coui.effect.CouiEdge
import com.itos.coui.effect.CouiEdgeStyle

/**
 * 给一张卡叠 COUI 原生轮廓光。
 * 形状必须与 couiCard 的 CouiShape(corner, 2.5f) 一致，否则描边会偏出裁剪线。
 */
@Composable
fun Modifier.couiEdgeGlow(
    dark: Boolean,
    corner: Dp = 24.dp,
    lineAlpha: Float = if (dark) 0.5f else 0.55f,
    innerGlowAlpha: Float = if (dark) 0.10f else 0.16f,
): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this

    val density = LocalDensity.current
    val radiusPx = with(density) { corner.toPx() }
    val edge = remember(dark, radiusPx, lineAlpha, innerGlowAlpha) {
        CouiEdge(
            style = if (dark) CouiEdgeStyle.Dark else CouiEdgeStyle.Light,
            radiusPx = radiusPx,
            cornerWeight = 2.5f,
            // 全透明材质：只走轮廓与内晕，不动卡片内容
            mix = Color(0f, 0f, 0f, 0f),
            blend = Color(0f, 0f, 0f, 0f),
            innerGlowAlpha = innerGlowAlpha,
            contourGlowEnabled = true,
            lineAlpha = lineAlpha,
        )
    }

    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    return this
        .onSizeChanged { sizePx = it }
        .graphicsLayer {
            if (sizePx.width > 0 && sizePx.height > 0) {
                renderEffect = edge.renderEffect(
                    Size(sizePx.width.toFloat(), sizePx.height.toFloat()),
                    density.density,
                ).asComposeRenderEffect()
            }
        }
}
