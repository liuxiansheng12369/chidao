
package io.github.proify.lyricon.central

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.proify.lyricon.central.util.ScreenStateMonitor

/**
 * 中央桥接管理对象。
 *
 * 负责初始化全局 Context，并管理核心广播通信：
 * - 注册 [CentralReceiver] 接收提供者注册请求；
 * - 向系统或其他组件发送启动完成广播。
 */
@SuppressLint("StaticFieldLeak")
object BridgeCentral {

    /** 全局应用 Context，用于广播和注册接收器 */
    private lateinit var context: Context

    /** 用于接收中央控制广播的接收器实例 */
    private val receiver = CentralReceiver

    /**
     * 初始化中央桥接。
     *
     * 仅在第一次调用时生效，后续调用将被忽略。
     *
     * @param appContext 应用级 Context
     */
    fun initialize(appContext: Context) {
        if (::context.isInitialized) return
        context = appContext.applicationContext
        ScreenStateMonitor.initialize(appContext)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Constants.ACTION_REGISTER_PROVIDER)
                addAction(Constants.ACTION_REGISTER_SUBSCRIBER)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    /**
     * 发送中央启动完成广播。
     *
     * 通知系统或其他组件中央模块已完成初始化。
     */
    fun sendBootCompleted() {
        if (!::context.isInitialized) return
        // 加前台优先级标志：尽量让被 Greezer 冻结的音乐App进程也能收到
        // 关键：必须同时带 FLAG_RECEIVER_INCLUDE_BACKGROUND（隐藏常量 0x01000000）。
        // Android 14 起隐式广播默认不投递给后台进程的静态 receiver，而音乐App
        // 恰恰是后台播放 —— 只加 FOREGROUND 的话 provider 根本收不到这个广播，
        // 就不会重新注册，链路一直断着，表现就是"岛上没词，重开播放器/重启才回来"。
        context.sendBroadcast(
            Intent(Constants.ACTION_CENTRAL_BOOT_COMPLETED)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or 0x01000000)
        )
    }
}