package com.liuxiansheng.lyricon.app

import android.app.Application
import android.util.Log
import com.liuxiansheng.lyricon.app.util.MasterPrefs
import com.liuxiansheng.lyricon.app.util.StatusBarPrefs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

class LyriconApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 崩溃落盘：写到 files/crash/，事后 adb 直接取，不依赖 logcat
        runCatching { com.liuxiansheng.lyricon.app.util.CrashLogger.install(this) }
        Log.i(TAG, "Chidao Application created")

        // 商店目录预热：开进程就把本地目录读进内存，点开"歌词源商店"直接铺列表，不出现"读取目录"
        runCatching {
            Thread {
                runCatching { com.liuxiansheng.lyricon.app.store.StoreRepository.load(this@LyriconApp) }
            }.start()
        }

        // 通知使用权已授权时，App 一启动就把监听服务重新绑上。
        // 覆盖安装会清掉系统的绑定（settings 里条目还在，但服务没绑），
        // 不主动 requestRebind 的话，读系统媒体会话会一直失败，
        // 打轴器就会一直停在"独立计时"——记的是它自己的时钟，怎么打都不准。
        runCatching {
            Thread {
                runCatching { com.liuxiansheng.lyricon.app.live.MediaSessions.tryRootGrant(this) }
            }.start()
        }

        // 把打轴库整份推给 SystemUI 一份（后台线程，几十 KB，不影响启动）
        // 目的：播放时 SystemUI 读自己的盘就能拿到人工轴，不必为了一首歌去叫醒本进程
        runCatching {
            Thread {
                runCatching {
                    com.liuxiansheng.lyricon.axis.api.ManualAxisApi.pushAllToSystemUi(this)
                }
            }.start()
        }

        // 检查更新：一天一次，后台线程，失败静默；结果挂在 UpdateChecker.result 上给首页/关于页读
        runCatching {
            if (com.liuxiansheng.lyricon.app.util.UpdateChecker.shouldAutoCheck(this)) {
                Thread {
                    runCatching { com.liuxiansheng.lyricon.app.util.UpdateChecker.check(this@LyriconApp, force = false) }
                }.apply { isDaemon = true }.start()
            }
        }

        // 绑定 XposedService：设置页写入的开关通过 remote prefs 同步给 SystemUI 进程
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                // 服务可用后：先按上游做法把本地全量灌回 remote（状态栏读的就是它），
                // 再用 remote 回填本地，保证"文件里存的就是 SystemUI 实际生效值"。
                runCatching { MasterPrefs.syncAllToRemote(this@LyriconApp) }
                runCatching { StatusBarPrefs.syncAllToRemote(this@LyriconApp) }
                runCatching { MasterPrefs.syncFromRemote(this@LyriconApp) }
                runCatching { StatusBarPrefs.syncFromRemote(this@LyriconApp) }
                notifyServiceChanged(true)
                Log.i(TAG, "XposedService bound")
            }

            override fun onServiceDied(service: XposedService) {
                xposedService = null
                notifyServiceChanged(false)
            }
        })
    }

    private fun notifyServiceChanged(active: Boolean) {
        serviceListeners.forEach { runCatching { it(active) } }
    }

    companion object {
        private const val TAG = "LyriconApp"

        @JvmStatic
        var xposedService: XposedService? = null
            private set

        /** 模块激活状态实时监听（绑定完成/断开时立即通知 UI，不依赖 ON_RESUME） */
        private val serviceListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

        @JvmStatic
        fun addServiceListener(listener: (Boolean) -> Unit) {
            serviceListeners.add(listener)
            // 立即回放当前状态，避免注册前后竞态漏更新
            runCatching { listener(xposedService != null) }
        }

        @JvmStatic
        fun removeServiceListener(listener: (Boolean) -> Unit) {
            serviceListeners.remove(listener)
        }
    }
}
