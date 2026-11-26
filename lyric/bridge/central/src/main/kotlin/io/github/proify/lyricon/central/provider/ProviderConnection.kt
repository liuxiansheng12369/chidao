
package io.github.proify.lyricon.central.provider

import android.os.IBinder
import io.github.proify.lyricon.central.connection.RemoteConnection
import io.github.proify.lyricon.central.internal.connection.BinderDeathTracker
import io.github.proify.lyricon.central.provider.player.ActivePlayerCoordinator
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.ProviderInfo
import java.util.concurrent.atomic.AtomicBoolean

internal class ProviderConnection(
    private var binder: IProviderBinder?,
    val providerInfo: ProviderInfo,
    private val activePlayers: ActivePlayerCoordinator
) : RemoteConnection<ProviderInfo> {

    override val key: ProviderInfo get() = providerInfo

    val service = ProviderServiceBinder(this)

    private val deathTracker = BinderDeathTracker()
    private val closed = AtomicBoolean(false)

    override fun setDeathRecipient(onDeath: (() -> Unit)?) {
        deathTracker.onDeath = onDeath?.let { callback -> { _: IBinder -> callback() } }
        deathTracker.track(if (onDeath == null) null else binder?.asBinder())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        service.close()
        setDeathRecipient(null)
        binder = null
        activePlayers.notifyProviderInvalid(providerInfo)
    }

    override fun toString() = "ProviderConnection{$providerInfo}"
}
