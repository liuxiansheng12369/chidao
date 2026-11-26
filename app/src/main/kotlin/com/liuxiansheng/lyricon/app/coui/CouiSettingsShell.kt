/*
 * 凝光视效页面外壳（严格照 COUI-Kit 上游 DemoSettingsPage 结构）
 *
 * 结构来源：COUI-Kit/demo/.../DemoSettingsPage.kt
 *   Scaffold → LargeTopAppBar（折叠大标题 + 官方顶栏按钮材质）→ couiVerticalScroll + CouiOverscrollEffect
 *   → CouiPageDefaults.groupSpacing 分组间距，配色取 CouiPageDefaults.themeColors(dark)。
 * 不再自绘顶栏按钮、不再自己算高度，全部走上游参数。
 */

package com.liuxiansheng.lyricon.app.coui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Scaffold
import com.itos.coui.scroll.couiVerticalScroll
import com.itos.coui.scroll.rememberCouiOverscrollEffect
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.CouiTopAppBarButtonDefaults
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.LargeTopAppBar
import com.itos.coui.topappbar.TopAppBar
import com.itos.coui.topappbar.TopAppBarButton

/**
 * 凝光设置页外壳：折叠大标题顶栏 + 官方回弹滚动 + 官方页面配色。
 * 返回键由宿主传入（首页没有返回就不传），右侧按钮走 [CouiTopBarTextButton]。
 * showTitle=false 时不显示大标题，改用上游紧凑顶栏 [TopAppBar]，只留右上角按钮。
 */
@Composable
fun CouiSettingsShell(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    showTitle: Boolean = true,
    /** true 时不用折叠大标题，改成紧凑顶栏：标题和右上角按钮同一行（歌词源页用）。 */
    compactTitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = couiDark()
    CouiTheme(darkTheme = dark) {
        val colors = CouiPageDefaults.themeColors(dark)
        val scrollState = rememberScrollState()
        val overscroll = rememberCouiOverscrollEffect()
        val behavior = CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            canScroll = { scrollState.maxValue > 0 }
        )
        val density = LocalDensity.current
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = colors.background,
            topBar = {
                if (compactTitle) {
                    // 紧凑顶栏：标题和右侧按钮挤在同一行，标题比默认紧凑栏再大一点
                    TopAppBar(
                        title = {
                            MaterialText(
                                title,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = navigationIcon,
                        actions = actions,
                        dark = dark,
                        colors = CouiTopAppBarDefaults.colors(
                            titleColor = colors.title,
                            dark = dark,
                        ),
                    )
                } else if (showTitle) {
                    LargeTopAppBar(
                        title = {
                            MaterialText(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = navigationIcon,
                        actions = actions,
                        scrollBehavior = behavior,
                        dark = dark,
                        colors = CouiTopAppBarDefaults.colors(
                            titleColor = colors.title,
                            dark = dark,
                        ),
                    )
                } else {
                    TopAppBar(
                        title = {},
                        navigationIcon = navigationIcon,
                        actions = actions,
                        dark = dark,
                        colors = CouiTopAppBarDefaults.colors(
                            titleColor = colors.title,
                            dark = dark,
                        ),
                    )
                }
            },
        ) { innerPadding ->
            val contentPadding = remember(behavior, innerPadding, density, showTitle) {
                if (showTitle) behavior.state.contentPadding(innerPadding, density) else innerPadding
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .couiVerticalScroll(scrollState, overscrollEffect = overscroll)
                    .padding(contentPadding)
                    .padding(bottom = CouiPageDefaults.groupSpacing),
                content = content,
            )
        }
    }
}

/**
 * 顶栏文字按钮：用上游 TopAppBarButton（text 模式）+ 官方 systemMaterial / shadow 材质，
 * 不再自绘胶囊底和描边。材质参数的写法照 DemoSettingsPage 的返回键。
 */
@Composable
fun CouiTopBarTextButton(text: String, onClick: () -> Unit) {
    val dark = couiDark()
    val density = LocalDensity.current.density
    val contour = LocalCouiContourGlowEnabled.current
    TopAppBarButton(
        onClick = onClick,
        text = true,
        style = CouiTopAppBarButtonDefaults.style(dark).copy(
            material = { CouiTopAppBarButtonDefaults.systemMaterial(dark, it) },
            shadow = { CouiTopAppBarButtonDefaults.shadow(dark, it, density, contour, alphaScale = 1f) },
        ),
        // alpha17 起上游把染色基准做成参数，示例传 CouiTheme.materialTintFor(dark)；
        // 我们上面覆写了 material，所以这里必须显式补上，否则仍按无色基准算。
        themeTint = CouiTheme.materialTintFor(dark),
    ) {
        CouiText(text = text, kind = CouiTextKind.TopBar)
    }
}

/**
 * 顶栏图标按钮：同一套官方材质，只放一个图标。
 * 上游示例在 SDK<34 时退回自绘，这里同样处理，避免低版本设备上材质缺失。
 */
@Composable
fun CouiTopBarIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    if (Build.VERSION.SDK_INT < 34) {
        CouiTopBarTextButton(text = description, onClick = onClick)
        return
    }
    val dark = couiDark()
    val density = LocalDensity.current.density
    val contour = LocalCouiContourGlowEnabled.current
    TopAppBarButton(
        onClick = onClick,
        style = CouiTopAppBarButtonDefaults.style(dark).copy(
            material = { CouiTopAppBarButtonDefaults.systemMaterial(dark, it) },
            shadow = { CouiTopAppBarButtonDefaults.shadow(dark, it, density, contour, alphaScale = 1f) },
        ),
        themeTint = CouiTheme.materialTintFor(dark),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 顶栏返回键：官方 coui-icons 的 Back 图标（SDK<34 退回文字） */
@Composable
fun CouiTopBarBackButton(onClick: () -> Unit) {
    CouiTopBarIconButton(icon = CouiIcons.Filled.Back, description = "返回", onClick = onClick)
}
