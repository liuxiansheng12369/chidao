package com.liuxiansheng.lyricon.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.liuxiansheng.lyricon.axis.api.ManualAxisApi

/**
 * 状态栏的"补推请求"接收器。
 *
 * 平时不会走到这里：轴是保存那一刻主动推过去的。
 * 只有状态栏那边发现手里一份副本都没有（刚装新版、清过数据）才会喊一声，
 * 这里把整个轴库重推一遍就完事 —— 一次性的，不会常驻。
 */
class AxisSyncRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ManualAxisApi.ACTION_AXIS_SYNC_REQUEST) return
        Log.i("ManualAxis", "收到补推请求，重推整个轴库")
        val app = context.applicationContext ?: context
        Thread {
            runCatching { ManualAxisApi.pushAllToSystemUi(app) }
                .onFailure { e ->
                    Log.w("ManualAxis", "补推失败: ${e.javaClass.simpleName}: ${e.message}")
                }
        }.start()
    }
}
