/*
 * 思路对齐 lyricon-master（作者 Proify, Tomakino）的 executePreProcessing。
 * 本文件为词岛自有实现。
 *
 * Copyright 2026 liuxiansheng（刘先生）
 * 随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.lyric.processor

import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.lyric.model.RichLyricLine
import com.liuxiansheng.lyricon.hyper.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词文本加工的三个行为：简繁转换、屏蔽词丢行、关闭时零副作用。
 *
 * 简繁用例刻意挑「這/個/聽/見」这类高频字 —— 字表覆盖不到的冷僻字
 * 转换结果本来就不保证，拿它断言等于给测试埋钉子。
 */
class LyricTextPolishTest {

    private fun songOf(vararg texts: String): Song = Song(
        name = "測試",
        artist = "歌手",
        lyrics = texts.mapIndexed { index, text ->
            RichLyricLine(
                begin = index * 1000L,
                end = (index + 1) * 1000L,
                duration = 1000L,
                text = text
            )
        }
    )

    @Test
    fun convertsTraditionalToSimplified() {
        val source = songOf("這個人", "聽見了")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_SIMPLIFIED,
            null
        )

        assertEquals("这个人", result.lyrics!![0].text)
        assertEquals("听见了", result.lyrics!![1].text)
        assertEquals("测试", result.name)
    }

    @Test
    fun convertsSimplifiedToTraditional() {
        val source = songOf("这个人")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_TRADITIONAL,
            null
        )

        assertEquals("這個人", result.lyrics!![0].text)
    }

    @Test
    fun convertsWordTimingTextToo() {
        val line = RichLyricLine(
            begin = 0L,
            end = 2000L,
            duration = 2000L,
            text = "這個人",
            words = listOf(
                com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord(
                    begin = 0L, end = 1000L, duration = 1000L, text = "這個"
                ),
                com.liuxiansheng.lyricon.hyper.lyric.model.LyricWord(
                    begin = 1000L, end = 2000L, duration = 1000L, text = "人"
                )
            )
        )
        val source = Song(name = "測試", lyrics = listOf(line))

        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_SIMPLIFIED,
            null
        )

        // 主行文本和逐词文本必须都转，否则显示层读哪个都有一半是繁体
        assertEquals("这个人", result.lyrics!![0].text)
        assertEquals("这个", result.lyrics!![0].words!![0].text)
        assertEquals("人", result.lyrics!![0].words!![1].text)
    }

    @Test
    fun dropsLineThatBecomesEmptyAfterRemovingKeywords() {
        val source = songOf("作词", "第一句歌词", "作曲", "第二句歌词")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "作词，作曲"
        )

        // 整行只有这些字，删完为空 → 该行不显示
        assertEquals("只应留下两句真歌词", 2, result.lyrics!!.size)
        assertEquals("第一句歌词", result.lyrics!![0].text)
        assertEquals("第二句歌词", result.lyrics!![1].text)
    }

    @Test
    fun keepsTimingUntouchedWhenDroppingLines() {
        val source = songOf("作词", "第一句歌词")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "作词"
        )

        // 删行不改时间：留下那句的时间戳应与原句一致
        assertEquals(1000L, result.lyrics!![0].begin)
        assertEquals(2000L, result.lyrics!![0].end)
    }

    @Test
    fun returnsSameInstanceWhenDisabled() {
        val source = songOf("這個人")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            ""
        )

        assertSame("两项都关闭时不应产生新对象", source, result)
    }

    @Test
    fun treatsSpecialCharactersAsPlainText() {
        val source = songOf("a[b)c")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "[b"
        )

        // 用户输入的方括号当普通字符处理，不会变成字符类语法
        assertEquals("a)c", result.lyrics!![0].text)
    }

    @Test
    fun stripsOnlyMatchedWordAndKeepsRestOfLine() {
        val source = songOf("你为什么总是从者", "从者是懦弱的人")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "从者"
        )

        // 只删命中的两个字，句子其余部分必须留着 —— 不是整行丢
        assertEquals(2, result.lyrics!!.size)
        assertEquals("你为什么总是", result.lyrics!![0].text)
        assertEquals("是懦弱的人", result.lyrics!![1].text)
    }

    @Test
    fun stripsBracketedSuffixFromSongName() {
        val source = Song(
            name = "爱我的人和我爱的人（DJ女版）",
            artist = "歌手",
            lyrics = listOf(
                RichLyricLine(begin = 0L, end = 1000L, duration = 1000L, text = "第一句")
            )
        )
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "（"
        )

        // 只填一个括号，后台自动补成「括号+中间内容+括号」整段删；歌词行没括号不受影响
        assertEquals("爱我的人和我爱的人", result.name)
        assertEquals(1, result.lyrics!!.size)
        assertEquals("第一句", result.lyrics!![0].text)
    }

    @Test
    fun keepsSongNameWhenKeywordTargetsLyricLines() {
        val source = Song(
            name = "爱我的人和我爱的人",
            lyrics = listOf(
                RichLyricLine(begin = 0L, end = 1000L, duration = 1000L, text = "作词")
            )
        )
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "作词"
        )

        // 关键词只该删歌词行里的字，不能顺手把歌名也改了
        assertEquals("爱我的人和我爱的人", result.name)
        assertEquals(0, result.lyrics!!.size)
    }

    @Test
    fun hidesBracketContentInSongName() {
        val source = Song(
            name = "爱我的人和我爱的人（DJ女版）",
            artist = "歌手（翻唱）",
            lyrics = listOf(
                RichLyricLine(begin = 0L, end = 1000L, duration = 1000L, text = "（前奏）我们走吧")
            )
        )
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            null,
            hideBrackets = true
        )

        // 标题和歌词行一起剥
        assertEquals("爱我的人和我爱的人", result.name)
        assertEquals("歌手", result.artist)
        assertEquals("我们走吧", result.lyrics!![0].text)
    }

    @Test
    fun dropsLineThatBecomesEmptyAfterStrippingBrackets() {
        val source = songOf("第一句", "（间奏）", "第二句")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            null,
            hideBrackets = true
        )

        // 整行就是括号内容的，剥完为空，这一行不该显示
        assertEquals(2, result.lyrics!!.size)
        assertEquals("第一句", result.lyrics!![0].text)
        assertEquals("第二句", result.lyrics!![1].text)
    }

    @Test
    fun hidesBracketsWithoutEatingTextBetweenThem() {
        val source = Song(name = "歌（A）中间（B）")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            null,
            hideBrackets = true
        )

        // 两段括号各自剥掉，中间的正常文字不能被贪婪匹配吃掉
        assertEquals("歌中间", result.name)
    }

    @Test
    fun bracketKeywordHidesWholeBracketWithoutWritingRegex() {
        val source = songOf("英雄泪（Cover 鹏鹏音乐）", "第一句")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "（"
        )

        // 只填一个括号，后台自己补规则，把括号和里面的内容一起删
        assertEquals("英雄泪", result.lyrics!![0].text)
        assertEquals("第一句", result.lyrics!![1].text)
    }

    @Test
    fun matchesKeywordsIgnoringCase() {
        val source = songOf("这是dj版现场")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "DJ版"
        )

        // 用户填大写、歌词里是小写，也要命中
        assertEquals("这是现场", result.lyrics!![0].text)
    }

    @Test
    fun clearsEmptyBracketLeftByKeywordRemoval() {
        val source = songOf("孤独悲伤最寂寞(dj版)")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "DJ版"
        )

        // 删掉 dj版 之后剩下的空括号也一起清掉，不留「()」
        assertEquals("孤独悲伤最寂寞", result.lyrics!![0].text)
    }

    @Test
    fun removesEveryKeywordInOneInput() {
        val source = songOf("你个混蛋从者", "正常一句")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "从者，混蛋"
        )

        // 逗号隔开的多个词一起生效
        assertEquals("你个", result.lyrics!![0].text)
        assertEquals("正常一句", result.lyrics!![1].text)
    }

    @Test
    fun bookTitleMarkActsAsBracketPairToo() {
        val source = Song(
            name = "一起走过的日子 - 电影《至尊无上II之永霸天下》歌曲II",
            artist = "刘德华",
            lyrics = listOf(
                RichLyricLine(0, 1000, 1000, text = "如何面对")
            )
        )
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "（），dj版，《》"
        )

        // 填「《》」也要连书名号里面的内容一起隐藏，不能按字面找「《》」两个字
        assertEquals("一起走过的日子 - 电影歌曲II", result.name)
    }

    @Test
    fun hidesEveryBracketPairInOneLine() {
        val source = songOf("歌名（男版）《副题》【备注】「插话」")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "括号"
        )

        // 一行里出现多种括号，各自成对删掉，中间的正常文字保留
        assertEquals("歌名", result.lyrics!![0].text)
    }

    @Test
    fun anySingleBracketCharacterExpandsToItsPair() {
        // 只填一个闭括号「》」，也要把《…》整段删掉，而不是找「》」一个字
        val source = songOf("歌《副题》名")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "》"
        )

        assertEquals("歌名", result.lyrics!![0].text)
    }

    @Test
    fun mixedBracketTypesEachHideTheirOwnContent() {
        val source = songOf("A【备注】B〔插话〕C〖附注〗D")
        val result = LyricTextPolish.apply(
            source,
            RootConstants.CHINESE_CONVERSION_OFF,
            "【】"
        )

        // 只删用户指定的那一对，别的括号原样留着
        assertEquals("AB〔插话〕C〖附注〗D", result.lyrics!![0].text)
    }
}
