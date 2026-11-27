/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.hyper.root.live
import com.liuxiansheng.lyricon.hyper.root.utils.DiagnosticsLog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.liuxiansheng.lyricon.xposed.systemui.util.CurrentMediaInfo

/**
 * 打轴器的遥控通道：App 发广播，这里在 SystemUI 进程里转给真正的 MediaController。
 *
 * 为什么要绕广播：SystemUI 读不到 App 私有目录（SELinux 拦），App 也拿不到别的 App 的
 * MediaController（要系统级权限）。所以只能在 SystemUI 侧持着 controller 执行动作，
 * 由 App 发一条显式广播（setPackage=com.android.systemui）点一下。
 *
 * 动作：play / pause / toggle / seek_to(arg=毫秒) / seek_by(arg=±毫秒)
 */
object AxisMediaControl {

    private const val TAG = "AxisMediaControl"
    private const val ACTION = "io.github.liuxiansheng12369.chidao.AXIS_MEDIA"

    private var receiver: BroadcastReceiver? = null

    fun register(context: Context) {
        if (receiver != null) return
        val ctx = context.applicationContext ?: context
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val cmd = intent?.getStringExtra("cmd") ?: return
                val arg = intent.getLongExtra("arg", 0L)
                runCatching { handle(cmd, arg) }
                    .onFailure { Log.w(TAG, "控制失败 cmd=$cmd", it) }
            }
        }
        runCatching {
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(r, filter)
            }
            receiver = r
            DiagnosticsLog.i(TAG, "打轴遥控通道已注册")
        }.onFailure { Log.w(TAG, "注册广播失败", it) }
    }

    private fun handle(cmd: String, arg: Long) {
        // 句柄为空时现场重捞一次，别让"点了没反应"只是没拿到 controller
        val controller = CurrentMediaInfo.controller() ?: CurrentMediaInfo.refreshController()
        if (controller == null) {
            Log.w(TAG, "没有可控制的播放器 cmd=$cmd")
            return
        }
        val controls = controller.transportControls
        val playing = CurrentMediaInfo.isPlayingNow()
        val position = CurrentMediaInfo.livePosition()
        DiagnosticsLog.i(TAG, "执行 cmd=$cmd arg=$arg playing=$playing pos=$position")

        when (cmd) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "toggle" -> if (playing) controls.pause() else controls.play()
            "seek_to" -> controls.seekTo(arg.coerceAtLeast(0L))
            "seek_by" -> controls.seekTo((position + arg).coerceAtLeast(0L))
        }
    }
}
