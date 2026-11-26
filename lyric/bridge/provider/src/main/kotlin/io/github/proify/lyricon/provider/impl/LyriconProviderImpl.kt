
package io.github.proify.lyricon.provider.impl

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import io.github.proify.lyricon.provider.CentralServiceReceiver
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.ConnectionStatus
import io.github.proify.lyricon.provider.LocalProviderService
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderBinder
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderConstants.ACTION_REGISTER_PROVIDER
import io.github.proify.lyricon.provider.ProviderConstants.EXTRA_BINDER
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.provider.ProviderService
import io.github.proify.lyricon.provider.RemotePlayer
import io.github.proify.lyricon.provider.isConnecting
import io.github.proify.lyricon.provider.service.RemoteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 默认提供端实现。
 *
 * 负责发送注册广播、处理连接超时、维护本地服务 Binder，并把远端服务交给
 * [ProviderRemoteEndpoint] 管理。
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class LyriconProviderImpl(
    private val context: Context,
    override val providerInfo: ProviderInfo,
    providerService: ProviderService? = null,
    private val centralPackageName: String,
) : LyriconProvider, ConnectionListener {
    private val destroyed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val localService = LocalProviderService(providerService)
    private val remote = ProviderRemoteEndpoint(this)
    private val registration = Registration()
    private val binder = ProviderBinder(this, localService, remote)

    override var providerService: ProviderService? = providerService
        set(value) {
            field = value
            localService.callback = value
        }

    override val service: RemoteService = remote
    override val player: RemotePlayer get() = service.player
    override var autoSync: Boolean = true

    init {
        service.addConnectionListener(this)
    }

    override fun register(): Boolean = registration.start()

    override fun unregister(): Boolean {
        if (destroyed.get()) return false
        registration.cancel()
        disconnect(ProviderRemoteEndpoint.DisconnectReason.USER)
        return true
    }

    override fun destroy(): Boolean {
        if (!destroyed.compareAndSet(false, true)) return false
        registration.close()
        disconnect(ProviderRemoteEndpoint.DisconnectReason.USER)
        service.removeConnectionListener(this)
        scope.cancel()
        return true
    }

    override fun onConnected(provider: LyriconProvider) {
        if (autoSync) remote.syncPlayer()
    }

    override fun onReconnected(provider: LyriconProvider) {
        if (autoSync) remote.syncPlayer()
    }

    override fun onDisconnected(provider: LyriconProvider) = Unit
    override fun onConnectTimeout(provider: LyriconProvider) = Unit

    private fun disconnect(reason: ProviderRemoteEndpoint.DisconnectReason) {
        registration.cancelTimeout()
        remote.disconnect(reason)
        // 远端断开（SystemUI 重启、Binder 死亡）不是终点：只要还期望连接就退避重试。
        if (reason == ProviderRemoteEndpoint.DisconnectReason.REMOTE) registration.scheduleRetry()
    }

    /** 管理注册广播、超时重试、中心服务重启恢复和连接健康度兜底。 */
    private inner class Registration : CentralServiceReceiver.ServiceListener {
        private var timeoutJob: Job? = null
        private var retryJob: Job? = null
        private var watchdogJob: Job? = null

        /** 期望保持连接：register 时置位，unregister/destroy 时复位。 */
        @Volatile
        private var wantsConnection = false

        /** 下一次失败重试的等待时长（指数退避）。 */
        @Volatile
        private var retryDelayMs = INITIAL_RETRY_DELAY_MS

        private val callback = object : ProviderBinder.OnRegistrationCallback {
            override fun onRegistered() {
                cancelTimeout()
                cancelRetry()
                retryDelayMs = INITIAL_RETRY_DELAY_MS
                binder.removeRegistrationCallback(this)
            }
        }

        init {
            CentralServiceReceiver.addServiceListener(this)
        }

        fun start(): Boolean {
            if (destroyed.get() || centralPackageName.isBlank()) return false
            cancelRetry()
            retryDelayMs = INITIAL_RETRY_DELAY_MS
            if (remote.connectionStatus in setOf(
                    ConnectionStatus.CONNECTED,
                    ConnectionStatus.CONNECTING
                )
            ) {
                return false
            }

            wantsConnection = true
            startWatchdog()
            remote.connectionStatus = ConnectionStatus.CONNECTING
            binder.addRegistrationCallback(callback)
            scheduleTimeout()
            context.sendBroadcast(Intent(ACTION_REGISTER_PROVIDER).apply {
                setPackage(centralPackageName)
                putExtra(
                    ProviderConstants.EXTRA_BUNDLE,
                    Bundle().apply {
                        putBinder(EXTRA_BINDER, binder)
                    }
                )
            })
            return true
        }

        /**
         * 中心服务启动后强制重走一次完整注册。
         *
         * 不能以「状态是 DISCONNECTED_REMOTE」为前提：中心重建后本地状态三种情况
         * 都可能存在 —— CONNECTED 时远端 Binder 往往仍然存活、死亡监听不会触发，
         * 本端会永久停在「自认为已连接」的僵死态；CONNECTING 时上一次广播可能已丢。
         */
        override fun onServiceBootCompleted() {
            if (!wantsConnection) return
            forceReregister()
        }

        /** 取消注册：清掉期望连接位、停掉重试与看门狗。 */
        fun cancel() {
            wantsConnection = false
            cancelTimeout()
            cancelRetry()
            stopWatchdog()
            binder.removeRegistrationCallback(callback)
        }

        fun close() {
            cancel()
            CentralServiceReceiver.removeServiceListener(this)
        }

        fun cancelTimeout() {
            timeoutJob?.cancel()
            timeoutJob = null
        }

        private fun cancelRetry() {
            retryJob?.cancel()
            retryJob = null
        }

        /**
         * 丢掉当前连接并重新发起注册。
         *
         * 这是「本地状态不可信」场景下唯一的可靠恢复路径：常规 [start] 在
         * CONNECTED / CONNECTING 时会被拒绝，恰好会在最需要恢复的时候失效。
         * 播放器缓存不清空，重连成功后 autoSync 会把当前 Song 补回去。
         */
        private fun forceReregister() {
            cancelTimeout()
            cancelRetry()
            retryDelayMs = INITIAL_RETRY_DELAY_MS
            binder.removeRegistrationCallback(callback)
            remote.disconnect(ProviderRemoteEndpoint.DisconnectReason.REPLACE)
            start()
        }

        /** 连接健康度看门狗：兜住「Binder 死亡通知丢失」这类僵死形态。 */
        private fun startWatchdog() {
            if (watchdogJob?.isActive == true) return
            watchdogJob = scope.launch {
                while (true) {
                    delay(WATCHDOG_INTERVAL_MS)
                    runCatching { checkConnectionHealth() }
                }
            }
        }

        private fun stopWatchdog() {
            watchdogJob?.cancel()
            watchdogJob = null
        }

        private fun checkConnectionHealth() {
            if (destroyed.get() || !wantsConnection) return
            val status = remote.connectionStatus
            when {
                // 本地认为已连接，但 Binder 实际已死：死亡通知丢了。
                status == ConnectionStatus.CONNECTED && !remote.isActive -> forceReregister()
                // 停在注册中但超时协程已经不在：广播丢失或协程没推进。
                status == ConnectionStatus.CONNECTING && timeoutJob?.isActive != true ->
                    forceReregister()
            }
        }

        /** 注册超时：结束本次尝试并退避重试，而不是直接判死。 */
        private fun scheduleTimeout() {
            cancelTimeout()
            timeoutJob = scope.launch {
                delay(CONNECTION_TIMEOUT_MS)
                if (destroyed.get() || !wantsConnection) return@launch
                if (!remote.connectionStatus.isConnecting()) return@launch
                remote.connectionStatus = ConnectionStatus.DISCONNECTED
                binder.removeRegistrationCallback(callback)
                remote.forEachConnectionListener { it.onConnectTimeout(this@LyriconProviderImpl) }
                scheduleRetry()
            }
        }

        /** 失败后按指数退避重试（1 秒起，封顶 30 秒），只要还期望连接就一直试。 */
        fun scheduleRetry() {
            if (destroyed.get() || !wantsConnection) return
            cancelRetry()
            retryJob = scope.launch {
                delay(retryDelayMs)
                retryJob = null
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                start()
            }
        }
    }

    private companion object {
        private const val CONNECTION_TIMEOUT_MS = 4_000L
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val WATCHDOG_INTERVAL_MS = 60_000L
    }
}
