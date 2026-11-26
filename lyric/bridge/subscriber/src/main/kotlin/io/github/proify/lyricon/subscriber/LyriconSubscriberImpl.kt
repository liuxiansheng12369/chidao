
package io.github.proify.lyricon.subscriber

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 默认订阅端实现。
 *
 * 负责发送注册广播、处理连接超时重试、维护连接状态，并把远端服务交给
 * [SubscriberRemoteEndpoint] 管理。
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class LyriconSubscriberImpl(
    private val context: Context,
    override val subscriberInfo: SubscriberInfo,
) : LyriconSubscriber {
    private val destroyed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<ConnectionListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val remote =
        SubscriberRemoteEndpoint { disconnect(remote = true, notifyRemote = false) }
    private val registration = Registration()

    @Volatile
    var status = SubscriberStatus.DISCONNECTED
        private set

    override fun addConnectionListener(listener: ConnectionListener) {
        listeners.add(listener)
    }

    override fun removeConnectionListener(listener: ConnectionListener) {
        listeners.remove(listener)
    }

    override fun subscribeActivePlayer(listener: ActivePlayerListener): Boolean =
        remote.addActivePlayerListener(listener)

    override fun unsubscribeActivePlayer(listener: ActivePlayerListener): Boolean =
        remote.removeActivePlayerListener(listener)

    override fun register() {
        registration.start(manual = true)
    }

    override fun unregister() {
        if (!destroyed.get()) {
            registration.cancel()
            disconnect(remote = false)
        }
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        registration.close()
        disconnect(remote = false, destroy = true)
        scope.cancel()
        listeners.clear()
    }

    private fun onRegistered(service: IRemoteService?, reconnect: Boolean) {
        status = SubscriberStatus.CONNECTED
        remote.bind(service)
        listeners.forEach {
            if (reconnect) it.onReconnected(this) else it.onConnected(this)
        }
    }

    private fun disconnect(
        remote: Boolean,
        notifyRemote: Boolean = true,
        destroy: Boolean = false
    ) {
        registration.cancelTimeout()
        this.remote.disconnect(notifyRemote, destroy)
        status =
            if (remote) SubscriberStatus.DISCONNECTED_BY_REMOTE else SubscriberStatus.DISCONNECTED
        listeners.forEach { it.onDisconnected(this) }
        // 远端断开（SystemUI 重启、Binder 死亡）不等于用户不要歌词：
        // 只要还期望连接就安排一次恢复，否则重试耗尽后没有任何重启路径。
        if (remote && !destroy) registration.scheduleReconnect()
    }

    /** 管理注册广播、超时重试和中心服务重启后的恢复注册。 */
    private inner class Registration : CentralServiceReceiver.ServiceListener {
        private val binder = SubscriberBinder(subscriberInfo)
        private var timeoutJob: Job? = null
        private var reconnectJob: Job? = null
        private var retryCount = 0
        private var reconnect = false

        /**
         * 期望保持连接：register 时置位，unregister/destroy 时复位。
         *
         * 断开只代表「这一刻没连上」，只要意图还在就必须继续恢复；否则重试几次
         * 用尽之后这条链路就再没有任何自动重启的路径，后台播放会一直断着。
         */
        @Volatile
        private var wantsConnection = false

        private val callback = object : SubscriberBinder.RegistrationCallback {
            override fun onRegistered(service: IRemoteService?) {
                cancelTimeout()
                retryCount = 0
                if (!wantsConnection || destroyed.get()) return
                if (service == null) {
                    // 中心回了空服务：等同于没连上，退避后自己再试。
                    status = SubscriberStatus.DISCONNECTED
                    scheduleReconnect()
                    return
                }
                this@LyriconSubscriberImpl.onRegistered(service, reconnect)
            }
        }

        init {
            binder.addRegistrationCallback(callback)
            CentralServiceReceiver.addServiceListener(this)
        }

        fun start(manual: Boolean) {
            if (destroyed.get()) return
            cancelReconnect()
            if (status == SubscriberStatus.CONNECTED || status.isConnecting()) return
            wantsConnection = true
            reconnect = !manual || status.isDisconnectedByRemote()
            retryCount = 0
            send()
        }

        /**
         * 中心服务启动后补一次注册。
         *
         * 判据是「还期望连接且当前没连上」，不能只看「远端断开」——
         * 重试耗尽时状态落到 DISCONNECTED，按旧逻辑这里不会补注册，链路就永远断着。
         */
        override fun onServiceBootCompleted() {
            if (wantsConnection &&
                status != SubscriberStatus.CONNECTED &&
                !status.isConnecting()
            ) {
                start(manual = false)
            }
        }

        /** 断开后延迟重连，避免在 Binder 死亡回调栈里立刻发广播。 */
        fun scheduleReconnect() {
            if (destroyed.get() || !wantsConnection) return
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(RECONNECT_DELAY_MS)
                reconnectJob = null
                if (destroyed.get() || !wantsConnection) return@launch
                if (status == SubscriberStatus.CONNECTED || status.isConnecting()) return@launch
                Log.i(TAG, "连接已断开，重新发起注册")
                start(manual = false)
            }
        }

        fun cancelReconnect() {
            reconnectJob?.cancel()
            reconnectJob = null
        }

        fun cancelTimeout() {
            timeoutJob?.cancel()
            timeoutJob = null
        }

        /** 注销：清掉期望连接位（用户不再需要歌词）。 */
        fun cancel() {
            wantsConnection = false
            cancelReconnect()
            cancelTimeout()
        }

        fun close() {
            cancel()
            binder.removeRegistrationCallback(callback)
            CentralServiceReceiver.removeServiceListener(this)
        }

        private fun send() {
            if (destroyed.get()) return
            status = SubscriberStatus.CONNECTING
            context.sendBroadcast(Intent(SubscriberConstants.ACTION_REGISTER_SUBSCRIBER).apply {
                setPackage(SubscriberConstants.SYSTEM_UI_PACKAGE_NAME)
                putExtra(
                    SubscriberConstants.EXTRA_BUNDLE,
                    Bundle().apply {
                        putBinder(SubscriberConstants.EXTRA_BINDER, binder)
                    }
                )
            })
            scheduleTimeout()
        }

        private fun scheduleTimeout() {
            cancelTimeout()
            timeoutJob = scope.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (destroyed.get() || !status.isConnecting() || !wantsConnection) return@launch
                if (retryCount++ < MAX_RETRY_COUNT) {
                    send()
                } else {
                    retryCount = 0
                    status = SubscriberStatus.DISCONNECTED
                    listeners.forEach { it.onConnectTimeout(this@LyriconSubscriberImpl) }
                    // 超时不是终点：意图还在就退避一秒继续试。
                    scheduleReconnect()
                }
            }
        }
    }

    private companion object {
        private const val TAG = "LyriconSubscriber"
        private const val MAX_RETRY_COUNT = 3
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val RECONNECT_DELAY_MS = 1_000L
    }
}
