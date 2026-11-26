/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.hooks

import com.liuxiansheng.lyricon.hyper.root.island.host.IslandProbeUtils
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandTextHookerSupport
import com.liuxiansheng.lyricon.hyper.root.island.host.IslandTextHookerSupport.TAG
import com.liuxiansheng.lyricon.hyper.root.island.presentation.IslandPresentationCoordinator
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

internal object IslandModuleRestoreHooker {

    /**
     * The first big-island build is asynchronous on newer SystemUI versions.
     * bindData is the first synchronous point after a module holder has been
     * created, added to the template, initialized, and bound.
     */
    class AdapterBindDataHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                val moduleType = chain.args.getOrNull(0) as? String
                val data = chain.args.getOrNull(1)
                val holderRoot = IslandProbeUtils.getHolderRootView(
                    IslandProbeUtils.getHolder(chain.thisObject, moduleType)
                ) ?: return@runCatching
                if (!IslandProbeUtils.isRealBigIslandModuleArea(holderRoot)) return@runCatching

                IslandPresentationCoordinator.onModuleBound(
                    holderRoot = holderRoot,
                    moduleType = moduleType,
                    owner = IslandPresentationCoordinator.ownerEvidence(data)
                )
            }.onFailure { e ->
                HookLogger.e(TAG, "adapter.bindData 后准备歌词视图失败", e)
            }

            return result
        }
    }

    class AdapterUpdateViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                val moduleType = chain.args.getOrNull(0) as? String
                val data = chain.args.getOrNull(2)
                val holderRoot = IslandProbeUtils.getHolderRootView(
                    IslandProbeUtils.getHolder(chain.thisObject, moduleType)
                ) ?: return@runCatching
                if (!IslandProbeUtils.isRealBigIslandModuleArea(holderRoot)) return@runCatching
                IslandPresentationCoordinator.onModuleUpdated(
                    holderRoot = holderRoot,
                    moduleType = moduleType,
                    owner = IslandPresentationCoordinator.ownerEvidence(data)
                )
            }.onFailure { e ->
                HookLogger.e(TAG, "adapter.updateView 后恢复歌词视图失败", e)
            }

            return result
        }
    }

    class UpdateModuleViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                val moduleType = chain.args.getOrNull(0) as? String
                val data = chain.args.getOrNull(2)
                val adapter =
                    IslandTextHookerSupport.findFieldValue(chain.thisObject, "islandAdapter")
                val holderRoot = IslandProbeUtils.getHolderRootView(
                    IslandProbeUtils.getHolder(adapter, moduleType)
                ) ?: return@runCatching
                if (!IslandProbeUtils.isRealBigIslandModuleArea(holderRoot)) return@runCatching
                IslandPresentationCoordinator.onModuleUpdated(
                    holderRoot = holderRoot,
                    moduleType = moduleType,
                    owner = IslandPresentationCoordinator.ownerEvidence(data)
                )
            }.onFailure { e ->
                HookLogger.e(TAG, "updateModuleView 后恢复歌词视图失败", e)
            }

            return result
        }
    }
}
