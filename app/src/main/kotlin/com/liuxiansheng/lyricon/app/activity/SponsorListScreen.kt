/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.app.activity

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.liuxiansheng.lyricon.app.compose.effect.AppBackground
import com.liuxiansheng.lyricon.app.coui.CouiSettingsShell
import com.liuxiansheng.lyricon.app.coui.CouiTopBarTextButton
import com.liuxiansheng.lyricon.app.coui.CouiUiState
import com.liuxiansheng.lyricon.app.coui.couiCard
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/**
 * 一位赞助者。
 *
 * @param name    显示名（昵称 / @平台号 / 「匿名朋友」）
 * @param tier    分档：1 冠名支持 / 2 大力支持 / 3 友情支持
 * @param message 想说的话，留空就不显示
 * @param amount  金额文本（如 "¥100"），留空就不显示金额
 */
data class Sponsor(
    val name: String,
    val tier: Int,
    val message: String = "",
    val amount: String = "",
)

/**
 * 赞助名单（手动维护，不联网、不读服务器）。
 *
 * 新增一个人只加一行：
 *   Sponsor("某位酷友", SponsorBook.TIER_FRIEND, message = "词岛让我通勤有点回味", amount = "¥20")
 *
 * 几条约定：
 * - 不想露金额：amount 留空，页面只显示档位；
 * - 不愿具名：name 写「匿名朋友」或「某位酷友」；
 * - 档位：TIER_TITLE 冠名 / TIER_BIG 大力 / TIER_FRIEND 友情；
 * - 名单是本地常量，改完必须重新打包装机才生效，别人拿到 APK 就跟着更新。
 */
object SponsorBook {
    const val TIER_TITLE = 1
    const val TIER_BIG = 2
    const val TIER_FRIEND = 3

    fun tierName(tier: Int): String = when (tier) {
        TIER_TITLE -> "冠名支持"
        TIER_BIG -> "大力支持"
        else -> "友情支持"
    }

    /** ↓↓↓ 名单写在这里 ↓↓↓ */
    val list: List<Sponsor> = listOf(
        // 示例（复制这一行改成真实信息，然后取消注释）：
        // Sponsor("某位酷友", TIER_FRIEND, message = "字幕对得准，谢谢", amount = "¥20"),
        // Sponsor("匿名朋友", TIER_BIG),
    )
}

/** 赞助鸣谢页：完整名单，按档位分组 */
@Composable
internal fun SponsorListScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    SkinTheme {
        val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
        SideEffect {
            val window = (context as Activity).window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !isDark
        }

        AppBackground(isDarkTheme = isDark) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
            ) { _ ->
                val pageBack: @Composable () -> Unit = {
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isDark) Color(0xFF202024).copy(alpha = 0.80f)
                                    else Color.White.copy(alpha = 0.68f),
                                    RoundedCornerShape(14.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isDark) Color.White.copy(alpha = 0.14f)
                                    else Color.White.copy(alpha = 0.80f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { onBack() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "← 返回",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    val pageBody: @Composable () -> Unit = {
                        // 页头（凝光下标题由顶栏承担，这里只留一句说明）
                        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                            if (!CouiUiState.enabled.value) {
                                Text(
                                    "赞助鸣谢",
                                    style = MiuixTheme.textStyles.title1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(
                                "谢谢这些朋友，让词岛能一直做下去。",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            )
                        }

                        val sponsors = SponsorBook.list.sortedWith(
                            compareBy({ it.tier }, { it.name })
                        )

                        if (sponsors.isEmpty()) {
                            EmptySponsors()
                        } else {
                            Spacer(Modifier.height(4.dp))
                            SponsorCountLine(sponsors.size)
                            Spacer(Modifier.height(6.dp))

                            SponsorBook.TIER_TITLE.let { _ ->
                                listOf(SponsorBook.TIER_TITLE, SponsorBook.TIER_BIG, SponsorBook.TIER_FRIEND)
                                    .forEach { tier ->
                                        val group = sponsors.filter { it.tier == tier }
                                        if (group.isNotEmpty()) {
                                            SponsorTierHeader(SponsorBook.tierName(tier), group.size, isDark)
                                            group.forEach { s -> SponsorCard(s, isDark) }
                                            Spacer(Modifier.height(14.dp))
                                        }
                                    }
                            }
                        }

                        Spacer(Modifier.height(18.dp))
                        Divider(
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "名单为手动维护。想匿名、想撤名、改称呼或删掉金额，随时联系作者即可，" +
                                "不必等下一版。赞助是心意，不是义务，词岛对所有人一样开放全部功能。",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(32.dp))
                    }
                    if (CouiUiState.enabled.value) {
                        CouiSettingsShell(
                            title = "赞助鸣谢",
                            actions = { CouiTopBarTextButton("← 返回") { onBack() } },
                        ) { pageBody() }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) { pageBack() }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            ) { pageBody() }
                        }
                    }
            }
        }
    }
}

/** 名单为空时的提示 */
@Composable
private fun EmptySponsors() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "名单正在整理",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "赞助过的朋友私信作者，立刻加进来。",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/** 总人数一行 */
@Composable
private fun SponsorCountLine(count: Int) {
    val accent = accentOfSponsor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 3.dp)
        ) {
            Text(
                "共 $count 位",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}

/** 档位小标题 */
@Composable
private fun SponsorTierHeader(title: String, count: Int, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/** 一位赞助者：首字头像 + 名字/档位 + 留言 + 金额 */
@Composable
private fun SponsorCard(s: Sponsor, isDark: Boolean) {
    val accent = accentOfSponsor()
    val tint = avatarTint(s.name)
    val cardBg = if (isDark) Color(0xFF202024).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.55f)
    val border = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.75f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .then(
                if (CouiUiState.enabled.value) {
                    Modifier
                        .couiCard(
                            dark = isDark,
                            corner = 18.dp,
                            base = if (isDark) Color(0xCC262626) else Color(0xA8FFFFFF),
                            glowTint = accent,
                        )
                        .border(1.dp, border, RoundedCornerShape(18.dp))
                } else {
                    Modifier
                        .background(cardBg, RoundedCornerShape(14.dp))
                        .border(1.dp, border, RoundedCornerShape(14.dp))
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 首字头像（不拉网络图，避免加载失败留空）
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                s.name.take(1),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        SponsorBook.tierName(s.tier),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                }
            }
            if (s.message.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "“${s.message}”",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }
        }
        if (s.amount.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(
                s.amount,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}

/** 强调色跟随皮肤（黑胶金 / 晨雾蓝灰 / 赛博青），默认走原蓝 */
@Composable
private fun accentOfSponsor(): Color = LocalHomeSkin.current?.accent ?: Color(0xFF4F7CFF)

/** 首字头像底色：按名字哈希取，避免整页一个颜色 */
@Composable
private fun avatarTint(name: String): Color {
    val accent = accentOfSponsor()
    val palette = listOf(
        accent,
        Color(0xFFE9B872),
        Color(0xFF46E6C8),
        Color(0xFF7C8CFF),
        Color(0xFFFF8A8A),
        Color(0xFF9AD56B),
        Color(0xFF64B5F6),
    )
    return palette[abs(name.hashCode()) % palette.size]
}
