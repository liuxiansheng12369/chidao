/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.app.live

/** 听歌统计（照网易云年度报告那套维度：总时长、曲目数、打卡天数、最爱歌手、最爱单曲、来源分布）。 */
data class ListenStats(
    val label: String,
    val playCount: Int,
    val uniqueSongs: Int,
    val totalMs: Long,
    val activeDays: Int,
    val topArtists: List<Pair<String, Int>>,
    val topSongs: List<Pair<String, Int>>,
    val topSources: List<Pair<String, Int>>,
) {
    val totalLabel: String
        get() {
            if (totalMs <= 0) return "—"
            val minutes = totalMs / 60_000
            val hours = minutes / 60
            val mins = minutes % 60
            return if (hours > 0) "${hours} 小时 ${mins} 分" else "${mins} 分钟"
        }

    companion object {
        const val RANGE_WEEK = 0
        const val RANGE_MONTH = 1
        const val RANGE_ALL = 2

        fun rangeLabel(range: Int): String = when (range) {
            RANGE_WEEK -> "最近 7 天"
            RANGE_MONTH -> "最近 30 天"
            else -> "全部"
        }

        fun build(records: List<ListenRecord>, range: Int): ListenStats {
            val now = System.currentTimeMillis()
            val cutoff = when (range) {
                RANGE_WEEK -> now - 7L * 24 * 60 * 60 * 1000
                RANGE_MONTH -> now - 30L * 24 * 60 * 60 * 1000
                else -> 0L
            }
            val filtered = records.filter { it.time >= cutoff }
            val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val days = filtered.map { dayFmt.format(java.util.Date(it.time)) }.distinct().size
            val artistCount = filtered
                .filter { it.artist.isNotBlank() }
                .groupingBy { it.artist }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key to it.value }
            val songCount = filtered
                .groupingBy { it.title }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key to it.value }
            val sourceCount = filtered
                .filter { it.packageName.isNotBlank() }
                .groupingBy { ListenRecordsStore.appLabel(it.packageName) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key to it.value }
            return ListenStats(
                label = rangeLabel(range),
                playCount = filtered.size,
                uniqueSongs = filtered.map { it.title }.distinct().size,
                totalMs = filtered.sumOf { it.listenedMs },
                activeDays = days,
                topArtists = artistCount,
                topSongs = songCount,
                topSources = sourceCount,
            )
        }

        /**
         * 三层口径：
         *  - 7 天 / 30 天：读每日汇总（daily），按天聚合过滤
         *  - 全部：读累计标量（totals），永不清零、不受明细上限影响
         *  - 歌手榜 / 单曲榜：优先用每日汇总里记录的当天最爱（覆盖面比当天明细大），
         *    汇总里没有时回退到当天明细
         */
        fun build(
            daily: List<DailyStat>,
            totals: RecordTotals,
            records: List<ListenRecord>,
            range: Int,
        ): ListenStats {
            val base = build(records, RANGE_ALL)
            if (range == RANGE_ALL) {
                val artistCount = daily
                    .filter { it.topArtist.isNotBlank() }
                    .groupingBy { it.topArtist }.eachCount()
                    .entries.sortedByDescending { it.value }.take(5)
                    .map { it.key to it.value }
                val songCount = daily
                    .filter { it.topSong.isNotBlank() }
                    .groupingBy { it.topSong }.eachCount()
                    .entries.sortedByDescending { it.value }.take(5)
                    .map { it.key to it.value }
                return base.copy(
                    label = rangeLabel(RANGE_ALL),
                    playCount = totals.totalCount.toInt(),
                    totalMs = totals.totalListenedMs,
                    activeDays = totals.totalDays.toInt(),
                    topArtists = artistCount.ifEmpty { base.topArtists },
                    topSongs = songCount.ifEmpty { base.topSongs },
                )
            }
            val now = System.currentTimeMillis()
            val cutoff = if (range == RANGE_WEEK) {
                now - 7L * 24 * 60 * 60 * 1000
            } else {
                now - 30L * 24 * 60 * 60 * 1000
            }
            val cutoffDay = dayKeyOf(cutoff)
            val filtered = daily.filter { it.day >= cutoffDay }
            val artistCount = filtered
                .filter { it.topArtist.isNotBlank() }
                .groupingBy { it.topArtist }.eachCount()
                .entries.sortedByDescending { it.value }.take(5)
                .map { it.key to it.value }
            val songCount = filtered
                .filter { it.topSong.isNotBlank() }
                .groupingBy { it.topSong }.eachCount()
                .entries.sortedByDescending { it.value }.take(5)
                .map { it.key to it.value }
            return base.copy(
                label = rangeLabel(range),
                playCount = filtered.sumOf { it.count },
                totalMs = filtered.sumOf { it.ms },
                activeDays = filtered.count { it.count > 0 || it.ms > 0L },
                topArtists = artistCount.ifEmpty { base.topArtists },
                topSongs = songCount.ifEmpty { base.topSongs },
            )
        }

        private fun dayKeyOf(epochMs: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date(epochMs))
    }
}
