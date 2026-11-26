/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.presentation

import android.view.View
import android.view.ViewGroup
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandViewRegistry
import java.util.WeakHashMap

/**
 * 宿主附着观察器（V3 自研）。
 */
internal class IslandHostAttachmentObserver(
    private val currentPresentationRevision: () -> Long,
    private val onHostAttached: (IslandViewRegistry.HostToken, Long) -> Unit
) {
    private val lock = Any()
    private val listeners = WeakHashMap<ViewGroup, View.OnAttachStateChangeListener>()

    fun observe(root: ViewGroup) {
        synchronized(lock) {
            if (listeners.containsKey(root)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val attachedRoot = view as? ViewGroup ?: return
                    val token = IslandViewRegistry.markAttached(attachedRoot) ?: return
                    val expectedRevision = currentPresentationRevision()
                    attachedRoot.post {
                        onHostAttached(token, expectedRevision)
                    }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    (view as? ViewGroup)?.let(IslandViewRegistry::markDetached)
                }
            }
            listeners[root] = listener
            root.addOnAttachStateChangeListener(listener)
        }
    }

    fun stop(root: ViewGroup) {
        val listener = synchronized(lock) {
            listeners.remove(root)
        } ?: return
        root.removeOnAttachStateChangeListener(listener)
    }
}
