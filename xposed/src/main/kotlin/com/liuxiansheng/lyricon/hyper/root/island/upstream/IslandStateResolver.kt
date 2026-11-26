/*
 * 本文件基于 HyperIsland（作者 1812z）移植，原项目：
 * https://github.com/1812z/HyperIsland
 * 原版权归 1812z 所有，原项目以 MIT 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.upstream

import android.view.View
import com.liuxiansheng.lyricon.hyper.root.island.upstream.SystemUiReflection.findMethod
import com.liuxiansheng.lyricon.hyper.root.island.upstream.IslandType

/** Centralizes SystemUI state names and concrete island View lookup. */
internal object IslandStateResolver {
    fun fromState(state: Any?): IslandType? {
        val name = state?.javaClass?.simpleName.orEmpty()
        return when {
            name.contains("SmallIsland") -> IslandType.SMALL
            name.contains("BigIsland") -> IslandType.BIG
            name.contains("Expanded") -> IslandType.EXPAND
            else -> null
        }
    }

    fun isNoContent(state: Any?): Boolean {
        val name = state?.javaClass?.simpleName.orEmpty()
        val text = state?.toString().orEmpty()
        if (name.contains("TempHidden", ignoreCase = true) ||
            text.contains("TempHidden", ignoreCase = true)
        ) return false
        return sequenceOf(name, text).any { value ->
            value.contains("Deleted", ignoreCase = true) ||
                value.contains("Hidden", ignoreCase = true) ||
                value.contains("Empty", ignoreCase = true) ||
                value.contains("Invisible", ignoreCase = true) ||
                value.contains("Idle", ignoreCase = true) ||
                value.contains("None", ignoreCase = true)
        }
    }

    fun forView(view: View): IslandType? {
        val resourceName = runCatching {
            if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
        }.getOrDefault("")
        // DynamicIslandWindowView.updateExpandedViewMaterial() refreshes this slot even while
        // its owner is BIG/Hidden. The target identity is authoritative: falling back to the
        // owner's transient state lets the stock EXPANDED_GLASS_TOKEN overwrite module glass.
        if (resourceName.contains("fake_expanded")) return IslandType.EXPAND
        val className = view.javaClass.name
        if (className.contains("ExpandedView")) return IslandType.EXPAND
        if (className.contains("BigIslandView")) return IslandType.BIG
        return when {
            resourceName.contains("small_island") -> IslandType.SMALL
            resourceName.contains("big_island") -> IslandType.BIG
            resourceName.contains("expanded") -> IslandType.EXPAND
            else -> null
        }
    }

    fun concreteView(contentView: Any, type: IslandType): View? {
        val getterName = when (type) {
            IslandType.SMALL -> "getSmallIslandView"
            IslandType.BIG -> "getBigIslandView"
            IslandType.EXPAND -> "getExpandedView"
        }
        return runCatching {
            findMethod(contentView.javaClass, getterName)?.invoke(contentView) as? View
        }.getOrNull()
    }
}