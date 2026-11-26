/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * 由老词幕（lyricon-master）原样搬入。
 * 相对原文件只有两处替换：
 *   1. LyricPrefs.baseStyle（style 模块）→ LyricStyleBridge（词岛 prefs）；
 *   2. YLog → android.util.Log。
 * 加工逻辑、执行顺序、两轮分发语义均未改动。
 * AI 翻译后置插件未搬入（词岛不提供该功能），流水线保留空集合遍历结构。
 */

package io.github.proify.lyricon.xposed.systemui.lyric.processor

import android.util.Log
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.xposed.systemui.lyric.LyricStyleBridge
import io.github.proify.lyricon.xposed.systemui.lyric.LyricTextStyle
import io.github.proify.lyricon.xposed.systemui.util.ChineseConverter.toSimplified
import io.github.proify.lyricon.xposed.systemui.util.ChineseConverter.toTraditional
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌词数据加工引擎
 * 负责执行前置清洗、后置增强与显示层变换。
 */
object LyricDataProcessor {

    private const val TAG = "LyricDataProcessor"

    /**
     * 执行前置加工。
     * 职责：处理繁简转换、屏蔽词等基础数据清洗。
     */
    fun executePreProcessing(song: Song): Song {
        return song
            .let(::filterBlockedWords)
            .let(::convertChineseCharacters)
    }

    /**
     * 执行后置加工流水线 (异步)
     * 职责：顺序等待所有已启用的耗时插件处理完毕，合并结果后统一返回。
     *
     * 词岛没有注册任何后置插件（AI 翻译未搬入），因此这里等价于原样返回，
     * 但保留协程切换与签名，保证调用方的执行时序与老版一致。
     */
    suspend fun executePostProcessingPipeline(song: Song, style: LyricTextStyle): Song =
        withContext(Dispatchers.Default) {
            song
        }

    /**
     * 执行显示层加工。
     * 职责：应用只影响展示、不应参与 AI 等数据增强步骤的变换。
     *
     * 「隐藏翻译」与「仅显示翻译」互斥：同时开启时以隐藏翻译为准。
     */
    fun executeDisplayProcessing(song: Song, style: LyricTextStyle): Song {
        return when {
            style.isDisableTranslation -> TranslationDisplayRules.hideTranslation(song)
            style.isTranslationOnly -> TranslationDisplayRules.translationOnly(song)
            else -> song
        }
    }

    /**
     * 纯文本模式（sendText）的显示层加工：第二行即翻译，同样遵守隐藏 / 仅显示翻译。
     */
    fun executeTextDisplayProcessing(text: String?, style: LyricTextStyle): String? {
        return TranslationDisplayRules.applyToText(
            text,
            hideTranslation = style.isDisableTranslation,
            translationOnly = style.isTranslationOnly
        )
    }

    // --- 内部基础处理方法 ---

    private fun filterBlockedWords(song: Song): Song {
        val regex = LyricStyleBridge.blockedWordsRegex ?: return song
        return song.copy(lyrics = song.lyrics?.filter { line ->
            line.text?.let { !regex.containsMatchIn(it) } ?: true
        })
    }

    private fun convertChineseCharacters(song: Song): Song {
        val mode = LyricStyleBridge.chineseConversionMode
        if (mode == RootConstants.CHINESE_CONVERSION_OFF) return song

        val convert: (String?) -> String? = when (mode) {
            RootConstants.CHINESE_CONVERSION_TRADITIONAL -> { v -> v?.toTraditional() }
            RootConstants.CHINESE_CONVERSION_SIMPLIFIED -> { v -> v?.toSimplified() }
            else -> { v -> v }
        }

        val convertWord: (List<LyricWord>?) -> List<LyricWord>? = { words ->
            words?.map { word ->
                word.copy(text = convert(word.text))
            }
        }

        return song.copy(
            name = convert(song.name),
            artist = convert(song.artist),
            lyrics = song.lyrics?.map { line ->
                line.copy(
                    words = convertWord(line.words),
                    translationWords = convertWord(line.translationWords),
                    secondaryWords = convertWord(line.secondaryWords),
                )
            }
        ).normalize()
    }
}
