/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 */

package com.liuxiansheng.lyricon.app.live

import android.service.notification.NotificationListenerService

/**
 * 只为拿「读取系统媒体会话」的资格而存在，不处理任何通知。
 *
 * 系统规定：普通 App 想调 MediaSessionManager.getActiveSessions()，调用方必须
 * 是已启用的通知监听服务（或持有 MEDIA_CONTENT_CONTROL 系统权限）。
 * 打轴器要直接问系统媒体库"现在放的是哪首、放到了哪一秒"，
 * 所以这里注册一个空的 listener，用户/系统授权后即生效。
 */
class AxisNotifListener : NotificationListenerService()
