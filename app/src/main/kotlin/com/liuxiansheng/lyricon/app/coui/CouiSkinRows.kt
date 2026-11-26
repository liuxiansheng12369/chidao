/*
 * Lyricon V3 · 凝光视效行组件（COUI-Kit 测试分支）
 * 覆盖设置页其余控件类型：分段选择、± 步进、内嵌小标题、确认/输入/颜色三类弹窗。
 * 全部只在凝光模式下被调用，Miuix 分支保持原样。
 */

package com.liuxiansheng.lyricon.app.coui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionColors
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.Dialog
import com.itos.coui.dialog.InputDialog
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Item
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme

/** 凝光版分段选择行（对应原 SettingChoiceRow）：标题 + 下方等宽分段 */
@Composable
fun CouiChoiceRow(
    title: String,
    summary: String? = null,
    options: List<String>,
    selectedIndex: Int,
    showDivider: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val summarySlot: (@Composable () -> Unit)? =
            if (summary.isNullOrBlank()) null else ({ CouiText(text = summary, kind = CouiTextKind.ItemSummary) })
        Item(
            title = { CouiText(text = title, kind = CouiTextKind.ItemTitle) },
            summary = summarySlot,
            dark = dark,
        )
        if (options.size >= 2) {
            val accent = CouiTheme.colorSchemeFor(dark).accent
            // 宽度恢复原版整行；上下高度收到 28dp（原版紧凑预设是 32dp）
            SegmentedControl(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiPageDefaults.rowPadding)
                    .height(28.dp),
                labels = options,
                selectedIndex = selectedIndex.coerceIn(0, options.lastIndex),
                onSelected = onSelectedIndexChange,
                dark = dark,
                small = true,
                // 只把“左右选择框”（选中滑块）换成主题蓝，轨道底色保持原样
                indicatorColor = accent,
            )
        }
        if (showDivider) Divider()
    }
}

/** 凝光版步进行（对应歌词时间偏移那种 −  值  +） */
@Composable
fun CouiStepperRow(
    title: String,
    summary: String?,
    valueText: String,
    showDivider: Boolean = true,
    onStep: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val summarySlot: (@Composable () -> Unit)? =
            if (summary.isNullOrBlank()) null else ({ CouiText(text = summary, kind = CouiTextKind.ItemSummary) })
        Item(
            title = { CouiText(text = title, kind = CouiTextKind.ItemTitle) },
            summary = summarySlot,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CouiStepButton("−", dark) { onStep(-1) }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .clickable { onReset() },
                        contentAlignment = Alignment.Center,
                    ) {
                        CouiText(text = valueText, kind = CouiTextKind.ItemValue)
                    }
                    CouiStepButton("+", dark) { onStep(1) }
                }
            },
            dark = dark,
        )
        if (showDivider) Divider()
    }
}

@Composable
private fun CouiStepButton(symbol: String, dark: Boolean, onClick: () -> Unit) {
    val bg = if (dark) Color(0xFF2C2C2E) else Color(0xFFEFEFF2)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        CouiText(text = symbol, kind = CouiTextKind.ItemValue)
    }
}

/** 凝光版内嵌小标题（对应原 SubGroupBox 的标题行）：直接用上游 Footer */
@Composable
fun CouiSubGroupTitle(text: String) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        com.itos.coui.layout.Footer(
            text = text,
            color = CouiPageDefaults.themeColors(dark).summary,
        )
    }
}

/** 凝光版确认弹窗（对应原 MiuixConfirmDialog） */
@Composable
fun CouiConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "同意并开启",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val accent = CouiTheme.colorSchemeFor(dark).accent
        Dialog(
            material = CouiDialogDefaults.material(dark),
            onDismissRequest = onDismiss,
            title = title,
            // 弹窗玻璃观感改由系统跨窗口背景模糊提供（不再依赖 Compose 离屏窗口采样）
            colors = couiGlassDialogColors(dark),
            message = message,
            dark = dark,
            confirmAction = CouiDialogAction(
                text = confirmText,
                onClick = onConfirm,
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogActionColors(
                    surface = accent,
                    content = Color.White,
                    disabledSurface = accent.copy(alpha = 0.4f),
                    disabledContent = Color.White.copy(alpha = 0.6f),
                ),
            ),
            dismissAction = CouiDialogAction(text = dismissText, onClick = onDismiss),
            content = {},
        )
    }
}

/** 凝光版输入弹窗：自绘输入行（上游 InputDialog 没有内容槽，挂不上系统模糊），保留自动聚焦、清除与底部焦点线 */
@Composable
fun CouiInputDialog(
    title: String,
    initial: String,
    hint: String = "输入内容",
    numericOnly: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val accent = CouiTheme.colorSchemeFor(dark).accent
        val onSurface = CouiTheme.colorSchemeFor(dark).onSurface
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var focused by remember { mutableStateOf(false) }
        Dialog(
            material = CouiDialogDefaults.material(dark),
            onDismissRequest = onDismiss,
            title = title,
            message = hint,
            colors = couiGlassDialogColors(dark),
            dark = dark,
            confirmAction = CouiDialogAction(
                text = "确定",
                // 与 Miuix 版一致：确定 = 提交 + 关闭（调用方的 onConfirm 只负责写值）
                onClick = {
                    onConfirm(value.text)
                    onDismiss()
                },
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogDefaults.primaryActionColors(accent),
            ),
            dismissAction = CouiDialogAction(text = "取消", onClick = onDismiss),
            content = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { newValue ->
                            val text = if (numericOnly) {
                                newValue.text.filter { it.isDigit() }.take(12)
                            } else {
                                newValue.text.take(200)
                            }
                            value = TextFieldValue(text, TextRange(text.length))
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 17.sp, color = onSurface),
                        cursorBrush = SolidColor(accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (numericOnly) KeyboardType.Number else KeyboardType.Text
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused }
                            .drawBehind {
                                val y = size.height - 0.5f.dp.toPx()
                                val lineColor = if (focused) accent else onSurface.copy(alpha = 0.2f)
                                drawLine(
                                    color = lineColor,
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1f.dp.toPx(),
                                )
                            }
                            .padding(vertical = 10.dp),
                    )
                    if (value.text.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clickable {
                                    value = TextFieldValue("", TextRange.Zero)
                                    focusRequester.requestFocus()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            CouiText(text = "×", kind = CouiTextKind.ItemValue, color = onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            },
        )
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
}

/** 凝光通用内容弹窗：需要塞自定义内容（数字行、开关行等）的设置项用它，带系统模糊 */
@Composable
fun CouiSlotDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val accent = CouiTheme.colorSchemeFor(dark).accent
        Dialog(
            material = CouiDialogDefaults.material(dark),
            onDismissRequest = onDismiss,
            title = title,
            colors = couiGlassDialogColors(dark),
            dark = dark,
            confirmAction = CouiDialogAction(
                text = "关闭",
                onClick = onDismiss,
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogActionColors(accent, Color.White, accent.copy(alpha = 0.4f), Color.White.copy(alpha = 0.6f)),
            ),
            content = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
            },
        )
    }
}

/** 凝光版通用内容弹窗：自定义内容区 + 单个关闭动作（性能数据、收款码这类） */
@Composable
fun CouiContentDialog(
    title: String,
    dismissText: String = "关闭",
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val accent = CouiTheme.colorSchemeFor(dark).accent
        Dialog(
            material = CouiDialogDefaults.material(dark),
            onDismissRequest = onDismiss,
            title = title,
            colors = couiGlassDialogColors(dark),
            dark = dark,
            confirmAction = CouiDialogAction(
                text = dismissText,
                onClick = onDismiss,
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogActionColors(
                    surface = accent,
                    content = Color.White,
                    disabledSurface = accent.copy(alpha = 0.4f),
                    disabledContent = Color.White.copy(alpha = 0.6f),
                ),
            ),
            content = {
                content()
            },
        )
    }
}

/** 凝光版列表选择弹窗（对应原 MiuixOptionDialog） */
@Composable
fun CouiOptionDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedIndex: Int = -1,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val accent = CouiTheme.colorSchemeFor(dark).accent
        Dialog(
            material = CouiDialogDefaults.material(dark),
            onDismissRequest = onDismiss,
            title = title,
            // 玻璃观感由系统跨窗口背景模糊提供
            colors = couiGlassDialogColors(dark),
            dark = dark,
            confirmAction = CouiDialogAction(
                text = "取消",
                onClick = onDismiss,
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogActionColors(accent, Color.White, accent.copy(alpha = 0.4f), Color.White.copy(alpha = 0.6f)),
            ),
            content = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEachIndexed { i, (label, desc) ->
                        val summarySlot: (@Composable () -> Unit)? =
                            if (desc.isBlank()) null else ({ CouiText(text = desc, kind = CouiTextKind.ItemSummary) })
                        Item(
                            title = {
                                CouiText(
                                    text = label,
                                    kind = CouiTextKind.ItemTitle,
                                    color = if (i == selectedIndex) accent else Color.Unspecified,
                                )
                            },
                            summary = summarySlot,
                            trailing = {
                                if (i == selectedIndex) CouiText(text = "✓", kind = CouiTextKind.ItemValue)
                            },
                            onClick = {
                                onSelected(i)
                                onDismiss()
                            },
                            dark = dark,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        )
                        if (i != options.lastIndex) Divider()
                    }
                }
            },
        )
    }
}

/** 凝光版颜色选择弹窗（对应原 MiuixColorDialog） */
@Composable
fun CouiColorDialog(
    title: String,
    presetColors: List<Pair<Int, String>>,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val accent = CouiTheme.colorSchemeFor(dark).accent
        Dialog(
            material = CouiDialogDefaults.material(dark),
            onDismissRequest = onDismiss,
            title = title,
            // 玻璃观感由系统跨窗口背景模糊提供
            colors = couiGlassDialogColors(dark),
            dark = dark,
            confirmAction = CouiDialogAction(
                text = "取消",
                onClick = onDismiss,
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogActionColors(accent, Color.White, accent.copy(alpha = 0.4f), Color.White.copy(alpha = 0.6f)),
            ),
            content = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    presetColors.chunked(4).forEach { rowItems ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowItems.forEach { (colorInt, label) ->
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            onSelected(colorInt)
                                            onDismiss()
                                        }
                                        .padding(vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        Modifier
                                            .size(width = 44.dp, height = 30.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(colorInt)),
                                    )
                                    CouiText(
                                        text = label,
                                        kind = CouiTextKind.ItemSummary,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
