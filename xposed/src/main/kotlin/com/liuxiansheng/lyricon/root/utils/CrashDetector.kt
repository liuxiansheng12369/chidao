/*
 * 本文件基于 Lyricon（作者 Proify / Tomakino）移植，原项目：
 * https://github.com/tomakino/lyricon
 * 原版权归原作者所有，原项目以 Apache-2.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */



@file:Suppress("unused")

package com.liuxiansheng.lyricon.hyper.root.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 闪退检测工具类
 * 连续崩溃 3 次以上进入安全模式
 */
class CrashDetector private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "crash_detector_prefs"
        private const val KEY_CRASH_COUNT = "crash_count"
        private const val KEY_FIRST_CRASH_TIME = "first_crash_time"

        private const val TIME_WINDOW_MS = 20 * 1000L
        private const val CRASH_THRESHOLD = 3

        @Volatile
        private var instance: CrashDetector? = null

        fun getInstance(context: Context): CrashDetector {
            return instance ?: synchronized(this) {
                instance ?: CrashDetector(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val lock = Any()

    /**
     * 记录一次潜在的崩溃事件
     */
    fun record() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val currentCount = prefs.getInt(KEY_CRASH_COUNT, 0)
            val firstCrashTime = prefs.getLong(KEY_FIRST_CRASH_TIME, 0L)

            val isOutOfWindow = (now - firstCrashTime) > TIME_WINDOW_MS

            val newCount: Int
            val newFirstCrashTime: Long

            if (isOutOfWindow || currentCount == 0) {
                newCount = 1
                newFirstCrashTime = now
            } else {
                newCount = currentCount + 1
                newFirstCrashTime = firstCrashTime
            }

            prefs.edit(commit = true) {
                putInt(KEY_CRASH_COUNT, newCount)
                putLong(KEY_FIRST_CRASH_TIME, newFirstCrashTime)
            }
        }
    }

    /**
     * 检查是否发生连续崩溃
     */
    fun isContinuousCrash(): Boolean {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val count = prefs.getInt(KEY_CRASH_COUNT, 0)
            val firstCrashTime = prefs.getLong(KEY_FIRST_CRASH_TIME, 0L)

            val inWindow = (now - firstCrashTime) <= TIME_WINDOW_MS
            val reachedThreshold = count >= CRASH_THRESHOLD

            return inWindow && reachedThreshold
        }
    }

    /**
     * 重置所有崩溃记录
     */
    fun reset() {
        synchronized(lock) {
            prefs.edit(commit = true) {
                clear()
            }
        }
    }
}
