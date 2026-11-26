/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 *
 * 重启状态栏的权限门禁 + 无 root 提示。
 *
 * 为什么单独抽出来：App 里"↻ 重启状态栏"一共有四个入口（主页头部、设置页头部、
 * 状态栏歌词页头部、主题皮肤页的 RebootChip），原来每个入口各自
 * `Runtime.getRuntime().exec(arrayOf("su","-c","killall com.android.systemui"))`，
 * 失败时 runCatching 静默吞掉 —— 用户点了没反应，也不知道是没 root。
 *
 * 现在统一成：点之前先探一次 su，探不到就弹说明，让人知道去哪儿授权。
 */

package com.liuxiansheng.lyricon.app.activity

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object SystemUiRestart {

    private const val PROBE_TIMEOUT_MS = 6_000L
    private const val RESTART_TIMEOUT_MS = 10_000L

    /**
     * 探到过一次 su 可用之后就不再反复探（每探一次都要 fork 一个进程）。
     * 只缓存"成功"，失败不缓存 —— 用户可能刚去授权台点了允许，下次点击要能立刻用上。
     */
    @Volatile
    private var knownGranted = false

    /**
     * 点"重启状态栏"走这里。
     *
     * @param onDenied 没拿到 root 时回调（在主线程），由调用方弹说明
     * @param onDone   执行完成回调（在主线程），参数是命令有没有跑完
     */
    fun request(
        context: Context,
        scope: CoroutineScope,
        onDenied: () -> Unit,
        onDone: (Boolean) -> Unit = {},
    ) {
        val app = context.applicationContext ?: context
        scope.launch {
            val granted = withContext(Dispatchers.IO) { probe(knownGranted) }
            if (!granted) {
                onDenied()
                return@launch
            }
            knownGranted = true
            val ok = withContext(Dispatchers.IO) { restart() }
            onDone(ok)
        }
    }

    /** 只探不重启，给需要提前判断的地方用。 */
    suspend fun grantedNow(): Boolean = withContext(Dispatchers.IO) { probe(knownGranted) }

    private fun probe(cached: Boolean): Boolean {
        if (cached) return true
        return runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            // 有 root 管理器时第一次会弹授权框，用户不点就一直不返回，所以必须有超时
            if (!p.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return false
            }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            out.contains("uid=0")
        }.getOrDefault(false)
    }

    private fun restart(): Boolean = runCatching {
        // 重启前先把攒着的设置广播发干净：SystemUI 一死，没发出去的改动就收不到了，
        // 表现为"刚调完就重启，重启后还是旧样子，得再点一次"。
        runCatching { com.liuxiansheng.lyricon.bridge.BridgeSender.flushPendingNow() }
        val p = ProcessBuilder("su", "-c", "killall com.android.systemui").start()
        p.waitFor(RESTART_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }.getOrDefault(false)

    /** 这台机器上有没有装能授权 su 的管理器，用来决定要不要给"去授权"按钮。 */
    fun rootManagerPackage(context: Context): String? = ROOT_MANAGERS.firstOrNull { pkg ->
        runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        }.getOrDefault(false)
    }

    fun openRootManager(context: Context): Boolean {
        val pkg = rootManagerPackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 常见 root 方案的包名，按优先级排。 */
    private val ROOT_MANAGERS = listOf(
        "com.topjohnwu.magisk",      // Magisk
        "me.weishu.kernelsu",        // KernelSU
        "me.tool.passkey",           // APatch
        "eu.chainfire.supersu",      // SuperSU（老机器）
    )
}

/**
 * 没 root 时的说明弹窗。
 *
 * 语气按项目一贯的直白风格：说清楚"为什么点不动"和"去哪儿点允许"，
 * 不写"请检查权限"这种等于没说的话。
 */
@Composable
fun NoRootDialog(
    visible: Boolean,
    context: Context,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val canOpen = androidx.compose.runtime.remember { SystemUiRestart.rootManagerPackage(context) != null }
    val fg = Color(0xFF1A1A1A)
    val accent = Color(0xFF2196F3)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            elevation = 12.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                Text(
                    text = "没拿到 root 权限",
                    color = fg,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "重启状态栏要执行 su 命令，这一次没跑通，所以点了没反应。",
                    color = fg.copy(alpha = 0.72f),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "打开 Magisk / KernelSU / APatch 的「超级用户」列表，把词岛打开授权；" +
                        "列表里没有词岛的话，关掉再点一次这个按钮，系统会弹授权框，点「允许」就行。",
                    color = fg.copy(alpha = 0.58f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogTextButton(
                        text = "知道了",
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss,
                    )
                    if (canOpen) {
                        DialogTextButton(
                            text = "去授权",
                            filled = true,
                            accent = accent,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (!SystemUiRestart.openRootManager(context)) {
                                    Toast.makeText(context, "没打开 root 管理器，手动去桌面找一下", Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogTextButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF2196F3),
    onClick: () -> Unit,
) {
    val bg = if (filled) accent else Color(0xFFF2F2F5)
    val fgColor = if (filled) Color.White else Color(0xFF1A1A1A)
    Surface(
        modifier = modifier
            .height(40.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = bg,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                color = fgColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
