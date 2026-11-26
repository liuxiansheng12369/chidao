/*
 * 本文件基于 HyperIsland（作者 1812z）移植，原项目：
 * https://github.com/1812z/HyperIsland
 * 原版权归 1812z 所有，原项目以 MIT 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.upstream

import com.liuxiansheng.lyricon.hyper.root.island.upstream.IslandMaterialConfigStore
import com.liuxiansheng.lyricon.hyper.root.island.upstream.NativeBlurRenderer
import com.liuxiansheng.lyricon.hyper.root.island.upstream.OuterBlurRegistry
import com.liuxiansheng.lyricon.hyper.root.island.upstream.TransitionBlurController

/** Shared renderer graph used by the hook entry and transition visual hook. */
internal object IslandBlurRuntime {
    val configStore = IslandMaterialConfigStore()
    val nativeBlurRenderer = NativeBlurRenderer(configStore)
    val outerBlurRegistry = OuterBlurRegistry(configStore, nativeBlurRenderer)
    val transitionBlurController = TransitionBlurController(configStore, nativeBlurRenderer)
}