/*
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 *
 * 本文件是「老词幕数据层」与词岛设置页之间的唯一桥。
 * 老版的 LyricPrefs 依赖完整的 style 模块（BasicStyle / PackageStyle / LyricStyle），
 * 词岛没有那套模块，设置页用的是 RootConstants 里的键；
 * 这里把老版加工引擎需要的少数几个值从词岛 prefs 里读出来，供原样搬入的加工链使用。
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.SharedPreferences
import com.liuxiansheng.lyricon.hyper.common.RootConstants

/** 老版 `style.packageStyle.text` 的等价物：只保留加工链真正用到的两个开关。 */
data class LyricTextStyle(
    val isDisableTranslation: Boolean = false,
    val isTranslationOnly: Boolean = false,
)

object LyricStyleBridge {

    @Volatile
    private var prefs: SharedPreferences? = null

    fun bind(preferences: SharedPreferences) {
        prefs = preferences
    }

    fun textStyle(): LyricTextStyle = LyricTextStyle(
        isDisableTranslation = prefs?.getBoolean(
            RootConstants.KEY_HOOK_DISABLE_TRANSLATION,
            RootConstants.DEFAULT_HOOK_DISABLE_TRANSLATION
        ) ?: RootConstants.DEFAULT_HOOK_DISABLE_TRANSLATION,
        isTranslationOnly = prefs?.getBoolean(
            RootConstants.KEY_HOOK_TRANSLATION_ONLY,
            RootConstants.DEFAULT_HOOK_TRANSLATION_ONLY
        ) ?: RootConstants.DEFAULT_HOOK_TRANSLATION_ONLY,
    )

    /** 屏蔽词正则；空串或非法正则都按「不屏蔽」处理。 */
    val blockedWordsRegex: Regex?
        get() {
            val raw = prefs?.getString(
                RootConstants.KEY_HOOK_BLOCKED_WORDS_REGEX,
                RootConstants.DEFAULT_BLOCKED_WORDS_REGEX
            )
            if (raw.isNullOrBlank()) return null
            return runCatching { Regex(raw) }.getOrNull()
        }

    val chineseConversionMode: Int
        get() = prefs?.getInt(
            RootConstants.KEY_HOOK_CHINESE_CONVERSION_MODE,
            RootConstants.DEFAULT_CHINESE_CONVERSION_MODE
        ) ?: RootConstants.DEFAULT_CHINESE_CONVERSION_MODE
}
