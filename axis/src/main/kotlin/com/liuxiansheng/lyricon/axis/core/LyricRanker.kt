/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * 模块：打轴器 :axis · 在线词的匹配度 / 完整度评估
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.core

/**
 * 给一份在线词打分：这份能不能直接用、缺没缺结尾、要不要手打。
 *
 * 参考 HyperLyrics Enhanced 的 `song_sources(source, score, wordTimed)` 思路，
 * 但分数按本项目的口径算 —— 只看四个能算得出来的东西：
 *
 *   1. 身份 30 分：候选是用户自己点的，身份由 forceAlignToPlayer 保证，默认给满；
 *      只有词太短（< 8 句）时才扣 —— 那多半是拿了别人的一两句。
 *   2. 逐字 20 分：有字级时间就能逐字高亮，一下都不用敲。
 *   3. 完整度 30 分：末行时间 ÷ 播放时长。0.85~1.0 满分，
 *      低于 0.6 判"疑似少了结尾"（很多 LRC 只写到副歌就断了）。
 *   4. 时间轴 20 分：时间必须单调不减，倒序 / 大量重复时间戳要扣。
 *
 * 这只是"要不要一键成轴"的建议，从不参与替换已有轴 —— 那一步在
 * ManualAxisBridge.applyAxis 里，有人工轴时一律以人工轴为准。
 */
object LyricRanker {

    enum class Level(val label: String) {
        /** 有字级时间 */
        WORD_SYNC("逐字轴"),

        /** 只有行首时间 */
        LINE_TIME("行时间"),

        /** 纯文本，没有时间 */
        TEXT_ONLY("仅文本"),
    }

    data class Report(
        val score: Int,
        val level: Level,
        /** 末行时间 ÷ 播放时长，0~1。时长为 0 时是 0（未知）。 */
        val coverage: Float,
        val lineCount: Int,
        /** 有时间标签的行数 */
        val timedCount: Int,
        /** 末行开始毫秒（判断截断用） */
        val lastMs: Long,
        /** 人话提示，空串表示没发现明显问题 */
        val note: String,
    ) {
        /** 能不能"直接成轴"：有时间轴且分数够。 */
        val canDirectSave: Boolean
            get() = level != Level.TEXT_ONLY && score >= 55

        /** 界面上那颗徽标文案：`逐字轴 · 92%`。 */
        val badge: String
            get() = "${level.label} · $score%"
    }

    fun rank(doc: LyricDoc?, durationMs: Long): Report {
        if (doc == null || doc.lines.isEmpty()) {
            return Report(0, Level.TEXT_ONLY, 0f, 0, 0, 0L, "没取到歌词")
        }
        val lineCount = doc.lines.size
        val lineTimes = doc.lineTimes
        val timedCount = lineTimes?.count { it != null } ?: 0
        val level = when {
            doc.hasWordSync && doc.wordTimes != null -> Level.WORD_SYNC
            timedCount > 0 -> Level.LINE_TIME
            else -> Level.TEXT_ONLY
        }

        // 末行时间：字级取最后一个字，行级取最后一行
        val lastMs = when (level) {
            Level.WORD_SYNC -> doc.wordTimes
                ?.flatMap { row -> row.orEmpty().mapNotNull { it } }
                ?.maxOrNull()
                ?: 0L
            Level.LINE_TIME -> lineTimes?.filterNotNull()?.maxOrNull() ?: 0L
            Level.TEXT_ONLY -> 0L
        }

        val coverage = if (durationMs > 0L && lastMs > 0L) {
            (lastMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1.2f)
        } else {
            0f
        }

        // 1) 身份
        var score = if (lineCount >= 8) 30 else maxOf(0, lineCount) * 30 / 8
        // 2) 逐字
        score += when (level) {
            Level.WORD_SYNC -> 20
            Level.LINE_TIME -> 12
            Level.TEXT_ONLY -> 0
        }
        // 3) 完整度
        score += when {
            durationMs <= 0L -> 15                       // 时长未知，给中位
            coverage >= 0.85f -> 30
            coverage >= 0.60f -> ((coverage - 0.60f) / 0.25f * 30f).toInt()
            else -> 0
        }
        // 4) 时间轴
        score += when (level) {
            Level.TEXT_ONLY -> 0
            else -> {
                val issues = timelineIssues(doc)
                when {
                    issues == 0 -> 20
                    issues * 10 <= lineCount -> 12
                    else -> 5
                }
            }
        }

        val note = buildString {
            if (level == Level.TEXT_ONLY) {
                append("只有文本、没有时间，得手打")
            } else if (durationMs > 0L && coverage < 0.60f) {
                if (isNotEmpty()) append("；")
                append("末行 ${fmt(lastMs)} / 全长 ${fmt(durationMs)}，疑似少了结尾")
            } else if (lineCount < 8) {
                if (isNotEmpty()) append("；")
                append("只有 $lineCount 句，词太少")
            }
            val issues = if (level == Level.TEXT_ONLY) 0 else timelineIssues(doc)
            if (issues > 0) {
                if (isNotEmpty()) append("；")
                append("时间轴有 $issues 处倒序或重复")
            }
        }

        return Report(
            score = score.coerceIn(0, 100),
            level = level,
            coverage = coverage,
            lineCount = lineCount,
            timedCount = timedCount,
            lastMs = lastMs,
            note = note,
        )
    }

    /** 有几处"没往后走"：倒序或和上一句同一时刻。 */
    private fun timelineIssues(doc: LyricDoc): Int {
        val times: List<Long?> = when {
            doc.hasWordSync && doc.wordTimes != null ->
                doc.lines.indices.map { i ->
                    doc.wordTimes.getOrNull(i)?.firstOrNull { it != null }
                }
            else -> doc.lineTimes.orEmpty()
        }
        var issues = 0
        var prev = -1L
        for (t in times) {
            if (t == null) continue
            if (t <= prev) issues++
            prev = t
        }
        return issues
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0L)
        return java.lang.String.format("%02d:%02d", total / 60, total % 60)
    }
}
