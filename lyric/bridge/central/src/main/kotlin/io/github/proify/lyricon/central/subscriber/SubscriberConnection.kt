
package io.github.proify.lyricon.central.subscriber

import android.os.IBinder
import io.github.proify.lyricon.central.connection.RemoteConnection
import io.github.proify.lyricon.central.internal.connection.BinderDeathTracker
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import io.github.proify.lyricon.subscriber.SubscriberInfo
import java.util.concurrent.atomic.AtomicBoolean

internal class SubscriberConnection(
    private var binder: ISubscriberBinder?,
    val subscriberInfo: SubscriberInfo
) : RemoteConnection<SubscriberInfo> {

    override val key: SubscriberInfo get() = subscriberInfo

    val service = SubscriberServiceBinder(this)

    private val deathTracker = BinderDeathTracker()
    private val closed = AtomicBoolean(false)

    override fun setDeathRecipient(onDeath: (() -> Unit)?) {
        if (closed.get() && onDeath != null) return
        deathTracker.onDeath = onDeath?.let { callback -> { _: IBinder -> callback() } }
        deathTracker.track(if (onDeath == null) null else binder?.asBinder())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        setDeathRecipient(null)
        service.close()
        binder = null
    }

    override fun toString() = "SubscriberConnection{$subscriberInfo}"
}
