/*
 * 本文件基于 HyperIsland（作者 1812z）移植，原项目：
 * https://github.com/1812z/HyperIsland
 * 原版权归 1812z 所有，原项目以 MIT 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.upstream

import io.github.libxposed.api.XposedInterface

/**
 * R8 混淆修复：libxposed 的 XposedInterface.Hooker 是 compileOnly 依赖，
 * `.intercept { }` 生成的合成 lambda 类在 release(R8) 下被改名/精简，
 * 运行时报 AbstractMethodError（intercept 方法消失）。
 * 具名类显式实现 Hooker，R8 无法省略接口方法，release 行为与 debug 一致。
 */
class LambdaHooker(
    private val block: (XposedInterface.Chain) -> Any?,
) : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? = block(chain)
}
