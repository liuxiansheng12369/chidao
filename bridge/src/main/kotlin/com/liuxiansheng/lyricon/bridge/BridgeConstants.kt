/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */


package com.liuxiansheng.lyricon.bridge

/**
 * Bridge 通信常量
 *
 * V3 架构决策：所有计算在 SystemUI 侧，Bridge 只做低频设置下发，不做高频数据推送。
 * 通信方式：Broadcast（简单可靠，无需绑定服务）。
 */
object BridgeConstants {
    /** 配置变更广播 action（APP → SystemUI） */
    const val ACTION_CONFIG_CHANGED = "io.github.liuxiansheng12369.chidao.CONFIG_CHANGED"

    /** 配置 JSON extra key */
    const val EXTRA_CONFIG_JSON = "config_json"

    /** 请求当前配置广播 action（SystemUI → APP，用于启动时同步） */
    const val ACTION_REQUEST_CONFIG = "io.github.liuxiansheng12369.chidao.REQUEST_CONFIG"

    /** 配置响应广播 action（APP → SystemUI） */
    const val ACTION_CONFIG_RESPONSE = "io.github.liuxiansheng12369.chidao.CONFIG_RESPONSE"

    /**
     * 单键配置变更广播 action（APP → SystemUI）。
     *
     * 与 [ACTION_CONFIG_CHANGED] 的区别：那条带的是整份 LyriconConfig JSON，
     * 只喂配置中心；这条带的是"哪个 pref 键、改成了什么值"，SystemUI 侧收到后
     * 直接写内存覆盖表，绕开 remote prefs 的落盘时机问题（3.0.934 把发送端删了，
     * 结果改完设置要重启状态栏、甚至点两次才生效）。
     */
    const val ACTION_PREF_CHANGED = "io.github.liuxiansheng12369.chidao.PREF_CHANGED"

    /** pref 键名 */
    const val EXTRA_PREF_KEY = "pref_key"

    /** 值类型：boolean / int / long / float / string / string_set / clear */
    const val EXTRA_PREF_TYPE = "pref_type"

    /**
     * 合并发送时整包携带的键值：外层 Bundle，每个键对应一个子 Bundle
     * `{ "t" = 类型, "v" = 值 }`。
     */
    const val EXTRA_PREF_BUNDLE = "pref_bundle"

    /** 广播权限（只允许本应用发送/接收） */
    const val PERMISSION = "io.github.liuxiansheng12369.chidao.permission.BRIDGE"
}
