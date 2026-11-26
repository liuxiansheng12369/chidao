/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.presentation

/** 对账原因（V3 自研）。 */
internal enum class IslandReconcileReason {
    PRE_SYSTEM_UPDATE,
    SYSTEM_UPDATE_COMPLETE,
    VISIBILITY_CHANGED,
    MODULE_FIRST_BIND,
    MODULE_UPDATED,
    STABLE_REFRESH,
    LYRIC_SELF_HEAL,
    PLAYBACK_RESUME,
    FAKE_SNAPSHOT,
    FAKE_FINISHED
}
