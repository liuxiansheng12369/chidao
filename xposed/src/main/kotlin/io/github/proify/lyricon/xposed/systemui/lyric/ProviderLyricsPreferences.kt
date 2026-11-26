/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * 由老词幕（lyricon-master）搬入，仅替换偏好来源：
 * 老版用 ModuleEntry.instance.getRemotePreferences(ProviderLyricPrefs.PREF_NAME)，
 * 词岛没有该入口，改为绑定词岛主 prefs，并把已有的「provider 延迟」设置映射进 policy。
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.SharedPreferences
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import io.github.proify.lyricon.app.bridge.ProviderLyricPolicy
import io.github.proify.lyricon.app.bridge.ProviderLyricPrefs

object ProviderLyricsPreferences {

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var listening = false

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            LyricDataHub.refreshProviderPolicy()
        }

    fun bind(preferences: SharedPreferences) {
        prefs = preferences
        if (!listening) {
            listening = true
            preferences.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    fun read(provider: String?): ProviderLyricPolicy {
        val preferences = prefs ?: return ProviderLyricPolicy()
        val base = ProviderLyricPrefs.read(preferences, provider)
        if (provider.isNullOrBlank()) return base
        // 词岛原有的「provider 延迟补偿」存在主 prefs 里，键名与老版不同，这里并进 policy，
        // 位置偏移统一由 ProviderLyricPolicy.lyricPosition() 处理。
        val delay = preferences.getInt(
            RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX + provider,
            RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
        ).coerceIn(
            RootConstants.MIN_HOOK_LYRICON_PROVIDER_DELAY,
            RootConstants.MAX_HOOK_LYRICON_PROVIDER_DELAY
        )
        return base.copy(delayMs = delay)
    }
}
