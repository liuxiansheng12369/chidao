/*
 * Lyricon V3 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 Lyricon V3 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AxisColors {
    val Bg: Color @Composable get() = if (LocalAxisDark.current) Color(0xFF000000) else Color(0xFFF4F5F7)
    val Card: Color @Composable get() = if (LocalAxisDark.current) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val Primary: Color @Composable get() = if (LocalAxisDark.current) Color(0xFF6EA8FF) else Color(0xFF3B82F6)
    val PrimarySoft: Color @Composable get() = if (LocalAxisDark.current) Color(0xFF16243A) else Color(0xFFEAF2FF)
    val TextPrimary: Color @Composable get() = if (LocalAxisDark.current) Color(0xFFE9EBEF) else Color(0xFF14171C)
    // 次要文字：旧值 0x8B909A 在白卡上只有 3.2:1，小字号说明根本读不清；加深到 0x565B65（约 7:1）。
    // 深色侧同步提一档到 0xB3B9C4，压在 #1C1C1E / 黑底上都够。
    val TextSecondary: Color @Composable get() = if (LocalAxisDark.current) Color(0xFFB3B9C4) else Color(0xFF565B65)
    val Line: Color @Composable get() = if (LocalAxisDark.current) Color(0xFF2A2A2C) else Color(0xFFE7E9EE)
    val Danger: Color @Composable get() = if (LocalAxisDark.current) Color(0xFFFF6B6E) else Color(0xFFE5484D)
    val DangerSoft: Color @Composable get() = if (LocalAxisDark.current) Color(0xFF3A1D1E) else Color(0xFFFDECEE)
    val Success: Color @Composable get() = if (LocalAxisDark.current) Color(0xFF3DDC84) else Color(0xFF20B15A)

    /** 次要按钮底色：GhostButton / TinyButton 共用，比 Line 实、比 Card 沉。 */
    val ButtonGhost: Color @Composable get() = if (LocalAxisDark.current) Color(0xFF2A2A2C) else Color(0xFFEDEFF3)
}

/** 轴模块的深浅色标记：由 App 根主题按当前 Miuix/凝光外观注入，避免各处写死浅色。 */
val LocalAxisDark = staticCompositionLocalOf { false }

@Composable
fun AxisTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAxisDark provides dark, content = content)
}

@Composable
fun AxisScreen(
    title: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(AxisColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (title != null) {
            Column(Modifier.padding(start = Dim.padPageH, end = Dim.padPageH, top = Dim.padPageTop, bottom = Dim.padPageBottom)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        Text(
                            text = "‹",
                            fontSize = Font.back,
                            color = AxisColors.TextPrimary,
                            modifier = Modifier
                                .width(Dim.backWidth)
                                .clickable { onBack() }
                        )
                    }
                    Text(
                        text = title,
                        fontSize = Font.page,
                        fontWeight = FontWeight.SemiBold,
                        color = AxisColors.TextPrimary
                    )
                }
                if (subtitle != null) {
                    Spacer(Modifier.height(Dim.s1))
                    Text(text = subtitle, fontSize = Font.label, color = AxisColors.TextSecondary)
                }
                Spacer(Modifier.height(Dim.s3))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        if (bottom != null) {
            Surface(color = AxisColors.Bg, elevation = 0.dp) {
                Column(Modifier.padding(Dim.padCard)) { bottom() }
            }
        }
    }
}

@Composable
fun AxisCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.rCard))
            .background(AxisColors.Card)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(Dim.padCard),
        content = content
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = Font.label,
        color = AxisColors.TextSecondary,
        modifier = Modifier.padding(start = Dim.s1, top = Dim.s3, bottom = Dim.s2)
    )
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dim.hBtn)
            .clip(RoundedCornerShape(Dim.rBtn))
            .background(if (enabled) AxisColors.Primary else AxisColors.Line)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else AxisColors.TextSecondary,
            fontSize = Font.body,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 打轴专用的「按下即记时」按钮。
 * 普通 Button 的 onClick 是抬手才触发，抬手快慢每次都不同（50~200ms 的随机误差），
 * 打轴要的是"听到就按下的那一刻"，所以这里在手势的 awaitFirstDown 里直接回调。
 */
@Composable
fun PressButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPress: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dim.hBtn)
            .clip(RoundedCornerShape(Dim.rBtn))
            .background(
                when {
                    !enabled -> AxisColors.Line
                    pressed -> AxisColors.Primary.copy(alpha = State.pressedAlpha)
                    else -> AxisColors.Primary
                }
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPress()
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else AxisColors.TextSecondary,
            fontSize = Font.body,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(Dim.hBtn)
            .clip(RoundedCornerShape(Dim.rBtn))
            .background(AxisColors.ButtonGhost)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = AxisColors.TextPrimary, fontSize = Font.body)
    }
}

/** 校对页逐字操作用的小胶囊按钮（26dp 高，比 GhostButton 窄）。 */
@Composable
fun TinyButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(Dim.hChip)
            .clip(RoundedCornerShape(Dim.rChip))
            .background(AxisColors.ButtonGhost)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = AxisColors.TextPrimary,
            fontSize = Font.caption,
            modifier = Modifier.padding(horizontal = Dim.s2)
        )
    }
}

@Composable
fun EntryCard(
    title: String,
    desc: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AxisCard(modifier = modifier, onClick = onClick) {
        Box(
            Modifier
                .width(Dim.tileSize)
                .height(Dim.tileSize)
                .clip(RoundedCornerShape(Dim.rTile))
                .background(AxisColors.PrimarySoft)
        )
        Spacer(Modifier.height(Dim.s4))
        Text(title, fontSize = Font.body, fontWeight = FontWeight.Medium, color = AxisColors.TextPrimary)
        Spacer(Modifier.height(Dim.s0))
        Text(desc, fontSize = Font.caption, color = AxisColors.TextSecondary)
    }
}

@Composable
fun ThinLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(Dim.hLine).background(AxisColors.Line))
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Dim.hBar)
            .clip(RoundedCornerShape(Dim.rBar))
            .background(AxisColors.Line)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(Dim.hBar)
                .background(AxisColors.Primary)
        )
    }
}

/*
 * ────────────────────────────────────────────────────────────
 * 设置行：设置页里所有"标题 + 副标题 + 右侧控件"的行都走这里。
 * 字号行高由 Tokens 定，不再各页各写一套，所以整站的行长得一样。
 * ────────────────────────────────────────────────────────────
 */

/** 二选一/多选一的选段行：标题下面一排等宽小胶囊，选中那颗用主色实底白字。
 *  用来替代「一个选项一个开关」的写法——那样点一个另一个不一定跟着关，看着就不联动。 */
@Composable
fun SettingChoiceRow(
    title: String,
    summary: String? = null,
    options: List<String>,
    selectedIndex: Int,
    showDivider: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Dim.padCard, vertical = Dim.s4)) {
            Text(
                text = title,
                fontSize = Font.body,
                fontWeight = FontWeight.Medium,
                color = AxisColors.TextPrimary
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(Dim.s0))
                Text(text = summary, fontSize = Font.caption, color = AxisColors.TextSecondary)
            }
            Spacer(Modifier.height(Dim.s3))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dim.s2)
            ) {
                options.forEachIndexed { i, label ->
                    val selected = i == selectedIndex
                    Box(
                        Modifier
                            .weight(1f)
                            .height(Dim.hChip)
                            .clip(RoundedCornerShape(Dim.rChip))
                            .background(if (selected) AxisColors.Primary else AxisColors.ButtonGhost)
                            .clickable { onSelectedIndexChange(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = Font.label,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) Color.White else AxisColors.TextPrimary
                        )
                    }
                }
            }
        }
        if (showDivider) ThinLine(Modifier.padding(start = Dim.padCard))
    }
}

/** 设置分组卡：只负责圆角与底色，内边由行自己带（行内已有 padCard），首尾各留一格。 */
@Composable
fun SettingCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.rCard))
            .background(AxisColors.Card)
            .padding(vertical = Dim.s1),
        content = content
    )
}

/** 设置行：左侧标题+副标题，右侧可放数值、箭头或任意控件。 */
@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    showArrow: Boolean = false,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            Modifier.padding(horizontal = Dim.padCard, vertical = Dim.s4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = Font.body,
                    fontWeight = FontWeight.Medium,
                    color = AxisColors.TextPrimary
                )
                if (!summary.isNullOrBlank()) {
                    Spacer(Modifier.height(Dim.s0))
                    Text(text = summary, fontSize = Font.caption, color = AxisColors.TextSecondary)
                }
            }
            if (value != null) {
                Spacer(Modifier.width(Dim.s4))
                Text(text = value, fontSize = Font.label, color = AxisColors.TextSecondary)
            }
            if (trailing != null) {
                Spacer(Modifier.width(Dim.s4))
                trailing()
            }
            if (showArrow) {
                Spacer(Modifier.width(Dim.s2))
                Text(text = "›", fontSize = Font.body, color = AxisColors.TextSecondary)
            }
        }
        if (showDivider) ThinLine(Modifier.padding(start = Dim.padCard))
    }
}

/** 开关行：SettingRow + 右侧开关。整行可点，点哪都能切换。 */
/** 开关（全局唯一一份）：iOS 风格，44×24dp 轨道 + 18dp 白滑块。
 *  轨道是左右渐变（开=蓝、关=浅灰蓝），上半部压一层白高光，滑块带一点投影。
 *  首页「模块状态」那颗和设置页所有开关都用这一颗，保证颜色、尺寸、圆角完全一致。
 *  这里刻意不画任何阴影/底框：阴影层的矩形边界按下时露出来就是一块方框。 */
@Composable
fun BoardSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(checked) {
                detectTapGestures { onCheckedChange(!checked) }
            }
    ) {
        val h = size.height
        val r = h / 2f
        val trackColors = if (checked) {
            listOf(Color(0xFF5FA2FF), Color(0xFF2C77F2))
        } else {
            listOf(Color(0xFFDCE4F2), Color(0xFFC6D2E6))
        }
        drawRoundRect(
            brush = Brush.horizontalGradient(trackColors),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(r, r),
        )
        // 轨道内高光：只在上半部铺一层白
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0x4DFFFFFF), Color(0x00FFFFFF)),
                startY = 0f,
                endY = h * 0.62f,
            ),
            topLeft = Offset(1.5f, 1.2f),
            size = Size(size.width - 3f, h * 0.58f),
            cornerRadius = CornerRadius(h * 0.29f),
        )
        val knobR = r - 2.dp.toPx()
        val cx = if (checked) size.width - r else r
        drawCircle(
            color = Color(0x22000000),
            radius = knobR,
            center = Offset(cx, h / 2f + 1.dp.toPx()),
        )
        drawCircle(
            color = Color.White,
            radius = knobR,
            center = Offset(cx, h / 2f),
        )
    }
}
/** 开关行：整行可点，点标题/副标题/空白都能切。 */
@Composable
fun SettingSwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    showDivider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        summary = summary,
        showDivider = showDivider,
        onClick = { onCheckedChange(!checked) }
    ) {
        BoardSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** 箭头行：右侧显示当前值 + ›，点进下一层。 */
@Composable
fun SettingValueRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    SettingRow(
        title = title,
        summary = summary,
        value = value,
        showArrow = true,
        showDivider = showDivider,
        onClick = onClick
    )
}

/** 下拉行：右侧当前值 + 上下箭头，点开细长小列表选一项。 */
@Composable
fun SettingDropdownRow(
    title: String,
    summary: String? = null,
    options: List<String>,
    selectedIndex: Int,
    showDivider: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints {
        // 下拉列表靠右对齐、固定 208dp 宽，与"右侧当前值"那一列上下对得齐，不从整行左边弹出
        val menuWidth = 208.dp
        SettingRow(
            title = title,
            summary = summary,
            value = options.getOrNull(selectedIndex).orEmpty(),
            showDivider = showDivider,
            onClick = { expanded = true },
            trailing = {
                Text(
                    text = if (expanded) "⌃" else "⌄",
                    fontSize = Font.label,
                    color = AxisColors.TextSecondary
                )
            }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth),
            offset = DpOffset((maxWidth - menuWidth).coerceAtLeast(0.dp), 0.dp)
        ) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(onClick = {
                    expanded = false
                    onSelectedIndexChange(i)
                }) {
                    // 弹出层用的是 Material 的浅色菜单容器（白底），这里不能跟 AxisColors.TextPrimary 走：
                    // 深色皮肤下 TextPrimary 是近白色，白底上就成了"白字白底"看不见。
                    // 所以弹出项固定用深色字；选中项仍用主色蓝。
                    Text(
                        text = label,
                        fontSize = Font.body,
                        color = if (i == selectedIndex) AxisColors.Primary else Color(0xFF22262C)
                    )
                }
            }
        }
    }
}
