/*
 * Lyricon V3 · COUI 凝光视效测试分支（独立于正式版）
 * 包名：com.liuxiansheng.chidao.couitest
 *
 * 这一页是用 COUI-Kit（ColorOS 17 凝光视效组件库）原生组件搭的设置页。
 * 与 Miuix 版设置页共用同一份 MasterPrefs 配置，只换外观，不动任何业务逻辑。
 */

package com.liuxiansheng.lyricon.app.coui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.layout.Scaffold
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.TopAppBar
import com.liuxiansheng.lyricon.app.util.MasterPrefs

/**
 * 界面风格偏好：false = Miuix 皮肤（原界面），true = COUI 凝光视效。
 * 单独放一个 prefs 文件，不写进 MasterPrefs，避免污染 SystemUI 侧的键。
 */
object CouiSkinPref {
    private const val FILE = "pref_ui_style"
    private const val KEY_COUI = "ui_style_coui"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_COUI, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COUI, enabled).apply()
    }
}

/**
 * COUI 风格设置页。
 *
 * @param onSwitchStyle true = 切到凝光视效，false = 切回 Miuix
 * @param onBack 返回上一级
 */
@Composable
fun CouiSettingsScreen(
    onSwitchStyle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dark = couiDark()

    var superIsland by remember {
        mutableStateOf(MasterPrefs.getBoolean(context, MasterPrefs.KEY_ENABLE_SUPER_ISLAND, true))
    }
    var statusBarLyric by remember {
        mutableStateOf(
            MasterPrefs.getBoolean(context, MasterPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC, false)
        )
    }
    var splitLyric by remember {
        mutableStateOf(MasterPrefs.getBoolean(context, MasterPrefs.KEY_ISLAND_SPLIT_LYRIC_MODE, true))
    }
    var disableWidthLimit by remember {
        mutableStateOf(MasterPrefs.getBoolean(context, MasterPrefs.KEY_ISLAND_DISABLE_WIDTH_LIMIT, true))
    }

    CouiTheme(darkTheme = dark) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { CouiText(text = "设置", kind = CouiTextKind.TopBar) },
                    navigationIcon = {
                        Box(
                            Modifier
                                .clickable(onClick = onBack)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            CouiText(text = "返回", kind = CouiTextKind.ItemValue)
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(bottom = 32.dp)
            ) {
                Group(
                    title = "界面风格",
                    footer = "两套 UI 组件库共用同一份配置，切风格只换外观，不改功能。",
                ) {
                    Item(
                        title = { CouiText(text = "当前风格", kind = CouiTextKind.ItemTitle) },
                        summary = {
                            CouiText(
                                text = "凝光视效：大圆角、连续边缘光、材质混色",
                                kind = CouiTextKind.ItemSummary,
                            )
                        },
                        trailing = {
                            SegmentedControl(
                                modifier = Modifier.width(150.dp),
                                labels = listOf("MIUI", "凝光"),
                                selectedIndex = 1,
                                onSelected = { index -> onSwitchStyle(index == 1) },
                                dark = dark,
                            )
                        },
                    )
                }

                Group(
                    title = "超级岛",
                    footer = "这两项直接决定 SystemUI 的接管行为，和 Miuix 版设置页是同一份开关。",
                ) {
                    Item(
                        title = { CouiText(text = "启用超级岛", kind = CouiTextKind.ItemTitle) },
                        summary = {
                            CouiText(text = "控制 SystemUI 是否接管焦点通知", kind = CouiTextKind.ItemSummary)
                        },
                        trailing = {
                            CouiSwitch(
                                checked = superIsland,
                                onCheckedChange = { on ->
                                    superIsland = on
                                    MasterPrefs.setBoolean(
                                        context,
                                        MasterPrefs.KEY_ENABLE_SUPER_ISLAND,
                                        on,
                                    )
                                },
                                dark = dark,
                            )
                        },
                    )
                    Divider()
                    Item(
                        title = { CouiText(text = "状态栏歌词", kind = CouiTextKind.ItemTitle) },
                        summary = {
                            CouiText(text = "状态栏里显示当前一句歌词", kind = CouiTextKind.ItemSummary)
                        },
                        trailing = {
                            CouiSwitch(
                                checked = statusBarLyric,
                                onCheckedChange = { on ->
                                    statusBarLyric = on
                                    MasterPrefs.setBoolean(
                                        context,
                                        MasterPrefs.KEY_HOOK_ENABLE_STATUS_BAR_LYRIC,
                                        on,
                                    )
                                },
                                dark = dark,
                            )
                        },
                    )
                }

                Group(title = "歌词显示") {
                    Item(
                        title = { CouiText(text = "歌词模式", kind = CouiTextKind.ItemTitle) },
                        summary = {
                            CouiText(
                                text = if (splitLyric) "双行：歌名在上，歌词在下" else "单行：只显示歌词",
                                kind = CouiTextKind.ItemSummary,
                            )
                        },
                        trailing = {
                            SegmentedControl(
                                modifier = Modifier.width(150.dp),
                                labels = listOf("单行", "双行"),
                                selectedIndex = if (splitLyric) 1 else 0,
                                onSelected = { index ->
                                    val split = index == 1
                                    splitLyric = split
                                    MasterPrefs.setBoolean(
                                        context,
                                        MasterPrefs.KEY_ISLAND_SPLIT_LYRIC_MODE,
                                        split,
                                    )
                                },
                                dark = dark,
                            )
                        },
                    )
                    Divider()
                    Item(
                        title = { CouiText(text = "不做宽度限制", kind = CouiTextKind.ItemTitle) },
                        summary = {
                            CouiText(text = "歌词多长，岛就多宽", kind = CouiTextKind.ItemSummary)
                        },
                        trailing = {
                            CouiSwitch(
                                checked = disableWidthLimit,
                                onCheckedChange = { on ->
                                    disableWidthLimit = on
                                    MasterPrefs.setBoolean(
                                        context,
                                        MasterPrefs.KEY_ISLAND_DISABLE_WIDTH_LIMIT,
                                        on,
                                    )
                                },
                                dark = dark,
                            )
                        },
                    )
                }

                Footer(
                    text = "这一页全部由 COUI 凝光视效的原生组件渲染：分组卡片、开关、分段选择器都来自 ColorOS 17 的库。切回 MIUI 就回到原来的 Miuix 界面。"
                )
            }
        }
    }
}
