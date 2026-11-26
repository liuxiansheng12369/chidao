/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.host

import android.view.ViewGroup

/**
 * Reflection-only helpers shared by the thin SystemUI lifecycle adapters.
 *
 * Presentation policy and view mutations live in
 * [IslandPresentationCoordinator] and [IslandInjectionReconciler].
 */
internal object IslandTextHookerSupport {
    const val TAG = "IslandTextHooker"

    fun extractIslandDataFromContentOrReal(contentView: ViewGroup): Any? {
        val currentData = IslandProbeUtils.getCurrentIslandData(contentView)
        val realView = callNoArgMethodResult(contentView, "getRealView")
        val realData = IslandProbeUtils.getCurrentIslandData(realView)

        return when {
            IslandProbeUtils.extractMediaIslandInfo(currentData) != null -> currentData
            IslandProbeUtils.extractMediaIslandInfo(realData) != null -> realData
            currentData != null && IslandProbeUtils.isMediaIsland(currentData) -> currentData
            realData != null && IslandProbeUtils.isMediaIsland(realData) -> realData
            else -> currentData ?: realData
        }
    }

    fun callNoArgMethodResult(receiver: Any, name: String): Any? {
        return runCatching {
            receiver.javaClass.methods.find {
                it.name == name && it.parameterTypes.isEmpty()
            }?.invoke(receiver)
        }.getOrNull()
    }

    fun findFieldValue(receiver: Any?, name: String): Any? {
        val target = receiver ?: return null
        return runCatching {
            var current: Class<*>? = target.javaClass
            var value: Any? = null
            while (current != null && value == null) {
                val field = current.declaredFields.find { it.name == name }
                if (field != null) {
                    field.isAccessible = true
                    value = field.get(target)
                }
                current = current.superclass
            }
            value
        }.getOrNull()
    }
}
