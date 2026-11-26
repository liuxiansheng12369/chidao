/*
 * 凝光卡片材质
 *
 * 形状用 COUI 的 CouiShape（超椭圆，cornerWeight 2.5），与 CouiEdge 轮廓 shader 的几何完全一致；
 * 底色 + 对角混光 + 顶部反光带仍自绘（COUI 原生材质靠 backdrop 采样窗口背景，本机采样必崩），
 * 描边 / 内光晕交给 CouiEdge shader（见 CouiRealGlow.kt），不再用自绘亮边冒充。
 */

package com.liuxiansheng.lyricon.app.coui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.shape.CouiShape

/** 凝光卡片：超椭圆大圆角、皮肤底色、对角双色混光、顶部反光带、柔和投影 */
fun Modifier.couiCard(
    dark: Boolean,
    corner: Dp = 24.dp,
    base: Color? = null,
    glowTint: Color? = null,
): Modifier {
    val shape: Shape = CouiShape(corner, 2.5f)
    return this
        .shadow(
            elevation = if (dark) 14.dp else 10.dp,
            shape = shape,
            clip = false,
            ambientColor = (glowTint ?: Color.White).copy(alpha = if (dark) 0.22f else 0.16f),
            spotColor = Color(0x1F0B1A33),
        )
        .clip(shape)
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = (outline as? Outline.Generic)?.path
                ?: Path().apply {
                    addRoundRect(
                        RoundRect(
                            0f,
                            0f,
                            size.width,
                            size.height,
                            CornerRadius(corner.toPx()),
                        )
                    )
                }
            val fill = base ?: if (dark) Color(0xCC262626) else Color(0xA8FFFFFF)
            val seam = glowTint ?: Color.White

            // 1) 皮肤底色
            drawPath(path, fill)
            // 2) 对角双色混光：左上高光 → 右下透明
            drawPath(
                path,
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (dark) 0.26f else 0.46f),
                        Color.Transparent,
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 1.15f, size.height * 1.3f),
                ),
            )
            // 3) 顶部玻璃反光带（凝光最明显的特征：上沿亮、往下渐隐）
            drawPath(
                path,
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (dark) 0.30f else 0.58f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = size.height * 0.42f,
                ),
            )
            // 4) 一层极淡的强调色起笔（真描边由 CouiEdge 负责，这里只补底色氛围）
            drawPath(
                path,
                Brush.linearGradient(
                    colors = listOf(
                        seam.copy(alpha = if (dark) 0.16f else 0.10f),
                        Color.Transparent,
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
                style = Stroke(width = (if (dark) 1.2f else 1.0f).dp.toPx()),
            )
        }
}
