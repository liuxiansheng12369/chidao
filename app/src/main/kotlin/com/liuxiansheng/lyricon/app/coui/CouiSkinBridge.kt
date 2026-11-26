/*
 * Lyricon V3 · 凝光视效换肤桥（COUI-Kit 测试分支）
 *
 * 做法：设置页原本的 5 个基础行组件（分组标题 / 分组卡片 / 开关行 / 箭头行 / 下拉行）
 * 在凝光模式下改用 COUI 原生组件渲染；业务逻辑、key、默认值、互拆全部沿用原来的。
 * 这样整页所有设置项都在，不会漏项，也不用改原有逻辑。
 */

package com.liuxiansheng.lyricon.app.coui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Item
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme

/** 当前是否处于凝光视效皮肤。设置页的基础行组件读它决定走 COUI 还是原 Miuix。 */
object CouiUiState {
    val enabled = mutableStateOf(false)
}

/**
 * 凝光内部统一的明暗判据：跟应用当前主题（MiuixTheme 背景明度）走，
 * 不再各自读系统深浅——否则「系统浅色 + 用户选了深色皮肤」会出现半浅半深。
 */
@Composable
fun couiDark(): Boolean =
    top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background.luminance() < 0.5f

/** 凝光页面底色（铺在最底层，盖掉原 Miuix 渐变背景）：取上游 pageColors.background */
@Composable
fun CouiPageBackdrop() {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        Box(
            Modifier
                .fillMaxSize()
                .background(CouiPageDefaults.themeColors(dark).background)
        )
    }
}

/** 凝光版分组标题（对应原 SectionTitle）：排版取上游 Group 的标题行规格 */
@Composable
fun CouiSectionTitle(
    text: String,
    subtitle: String? = null,

    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val colors = CouiPageDefaults.themeColors(dark)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, top = CouiPageDefaults.categoryTopSpacing, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Column {
                        CouiText(text = text, kind = CouiTextKind.GroupTitle, color = colors.summary)
                        if (!subtitle.isNullOrBlank()) {
                            CouiText(
                                text = subtitle,
                                kind = CouiTextKind.GroupFooter,
                                color = Color(0xFFE53935),
                            )
                        }
                    }
                }
                trailing?.invoke(this)
            }
        }
    }
}

/** 分组标题右侧的展开/收起箭头（上游 Next 图标，收起朝下、展开朝上） */
@Composable
fun CouiSectionChevron(expanded: Boolean) {
    val dark = couiDark()
    Icon(
        imageVector = CouiIcons.Filled.Next,
        contentDescription = if (expanded) "收起" else "展开",
        tint = CouiPageDefaults.themeColors(dark).summary,
        modifier = Modifier
            .size(20.dp)
            .rotate(if (expanded) -90f else 90f),
    )
}

/** 凝光版分组卡片（对应原 SectionCard）：形状与底色直接取上游 CouiPageDefaults.shape / pageColors.card */
@Composable
fun CouiSectionCard(content: @Composable () -> Unit) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val colors = CouiPageDefaults.themeColors(dark)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CouiPageDefaults.horizontalMargin)
                .clip(CouiPageDefaults.shape)
                .background(colors.card)
        ) {
            content()
        }
    }
}

/** 凝光版分隔线：与 COUI 行内分割同宽同色，代替 Material2 的 Divider */
@Composable
fun CouiPageDivider(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        com.itos.coui.layout.Divider(
            modifier = modifier,
            startIndent = 0.dp,
            endIndent = 0.dp,
        )
    }
}

/** 主页同款自绘开关：蓝渐变轨道 + 上半高光 + 白钮投影；位移用弹簧，节奏对齐上游 Switch */
@Composable
fun CouiHomeSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "homeSwitch",
    )
    Canvas(
        modifier = modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(checked) { detectTapGestures { onCheckedChange(!checked) } }
    ) {
        val h = size.height
        val r = h / 2f
        val trackColors = if (checked) listOf(Color(0xFF5FA2FF), Color(0xFF2C77F2))
        else listOf(Color(0xFFDCE4F2), Color(0xFFC6D2E6))
        drawRoundRect(
            brush = Brush.horizontalGradient(trackColors),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(r, r),
        )
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
        val cx = r + (size.width - 2 * r) * progress
        drawCircle(Color(0x22000000), knobR, Offset(cx, h / 2f + 1.dp.toPx()))
        drawCircle(Color.White, knobR, Offset(cx, h / 2f))
    }
}
/** 凝光版开关行（对应原 SettingSwitchRow）：整行可点 */
@Composable
fun CouiSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    showDivider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val summarySlot: (@Composable () -> Unit)? =
            if (summary.isNullOrBlank()) null else ({ CouiText(text = summary, kind = CouiTextKind.ItemSummary) })
        Item(
            title = { CouiText(text = title, kind = CouiTextKind.ItemTitle) },
            summary = summarySlot,
            trailing = {
                // 外观照主页那颗（自绘蓝渐变轨道 + 白钮），位移用弹簧，点击行为与整行一致
                CouiHomeSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.padding(end = 2.dp),
                )
            },
            onClick = { onCheckedChange(!checked) },
            dark = dark,
        )
        if (showDivider) Divider()
    }
}

/** 凝光版箭头行（对应原 SettingValueRow） */
@Composable
fun CouiArrowRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit,
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
                    if (!value.isNullOrBlank()) {
                        CouiText(text = value, kind = CouiTextKind.ItemValue)
                    }
                    // 箭头用上游官方 coui-icons 的 Filled.Next（设置项标准右箭头）
                    Icon(
                        imageVector = CouiIcons.Filled.Next,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(start = 4.dp),
                        tint = CouiTheme.colorSchemeFor(dark).onSurfaceVariant,
                    )
                }
            },
            onClick = onClick,
            dark = dark,
        )
        if (showDivider) Divider()
    }
}

/** 凝光版下拉行（对应原 SettingDropdownRow）：点击弹出 COUI 二级选择弹窗（凝光材质 + 边缘光） */
@Composable
fun CouiDropdownRow(
    title: String,
    summary: String? = null,
    options: List<String>,
    selectedIndex: Int,
    showDivider: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    // 用 COUI 原生 Menu（贴行弹出的小菜单）：有几个选项就几行，不再是底部大弹窗。
    val anchor = rememberCouiMenuAnchor()
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val summarySlot: (@Composable () -> Unit)? =
            if (summary.isNullOrBlank()) null else ({ CouiText(text = summary, kind = CouiTextKind.ItemSummary) })
        Item(
            modifier = Modifier.couiMenuAnchor(anchor),
            title = { CouiText(text = title, kind = CouiTextKind.ItemTitle) },
            summary = summarySlot,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CouiText(
                        text = options.getOrNull(selectedIndex).orEmpty(),
                        kind = CouiTextKind.ItemValue,
                    )
                    // 展开标记同样用官方图标，向下展开
                    Icon(
                        imageVector = CouiIcons.Filled.Next,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(start = 4.dp).rotate(90f),
                        tint = CouiTheme.colorSchemeFor(dark).onSurfaceVariant,
                    )
                }
            },
            onClick = { anchor.open { showPicker = true } },
            dark = dark,
        )
        if (showDivider) Divider()
    }
    Menu(
        expanded = showPicker,
        onDismissRequest = { showPicker = false },
        items = options.mapIndexed { index, label ->
            CouiMenuItem(value = index, text = label, selected = index == selectedIndex)
        },
        onItemClick = { item ->
            showPicker = false
            onSelectedIndexChange(item.value)
        },
        anchor = anchor,
    )
}

/** 凝光版「界面风格」卡片：两侧 MIUI / 凝光 分段切换 */
@Composable
fun CouiStylePicker(coui: Boolean, onSwitch: (Boolean) -> Unit) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        CouiSectionTitle("界面风格")
        CouiSectionCard {
            Item(
                title = { CouiText(text = "当前风格", kind = CouiTextKind.ItemTitle) },
                summary = {
                    CouiText(
                        text = if (coui) "凝光视效：大圆角、连续边缘光、材质混色" else "原版 Miuix 皮肤",
                        kind = CouiTextKind.ItemSummary,
                    )
                },
                trailing = {
                    SegmentedControl(
                        modifier = Modifier.padding(end = 4.dp),
                        labels = listOf("MIUI", "凝光"),
                        selectedIndex = if (coui) 1 else 0,
                        onSelected = { index -> onSwitch(index == 1) },
                        dark = dark,
                        indicatorColor = CouiTheme.colorSchemeFor(dark).accent,
                    )
                },
                dark = dark,
            )
        }
    }
}
