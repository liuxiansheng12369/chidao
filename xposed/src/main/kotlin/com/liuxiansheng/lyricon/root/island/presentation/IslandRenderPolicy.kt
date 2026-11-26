/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.presentation

/**
 * 渲染策略（V3 自研）——纯决策，无副作用。
 */
internal object IslandRenderPolicy {

    sealed interface OwnerEvidence {
        data class Media(val packageName: String) : OwnerEvidence
        data object Pending : OwnerEvidence
        data object NotMedia : OwnerEvidence
    }

    data class Input(
        val owner: OwnerEvidence,
        val lyricPackageName: String?,
        val hasLyricsForPresentation: Boolean,
        val enabled: Boolean,
        val playbackActive: Boolean,
        val pauseBehavior: Int
    )

    enum class Decision {
        TARGET,
        PENDING,
        OTHER_PACKAGE,
        SUPPRESSED,
        NOT_MEDIA
    }

    fun evaluate(input: Input): Decision {
        if (input.owner == OwnerEvidence.NotMedia) return Decision.NOT_MEDIA
        if (!input.enabled) return Decision.SUPPRESSED
        // 不再按"有没有歌词"抑制整块岛：这首歌一点词都没有时照样上岛，
        // 只显示歌名/歌手，歌词槽留空。
        // 原来这里有一句 `if (!hasLyricsForPresentation) return SUPPRESSED`，
        // 后果是"没歌词"直接等于"没岛"——Sollin 推 0 行的歌、汽水没带词的歌
        // 都会整块不注入，连歌名都看不到。歌词够不够留给歌词槽自己判断。

        val mediaOwner = input.owner as? OwnerEvidence.Media
            ?: return Decision.PENDING
        val lyricPackageName = input.lyricPackageName
            ?.takeIf(String::isNotEmpty)
            ?: return Decision.PENDING

        if (mediaOwner.packageName != lyricPackageName) {
            return Decision.OTHER_PACKAGE
        }
        // 0=暂停时隐藏；2=暂停时不生成超级岛 —— 暂停期间都不上岛（2 是连岛窗口都不建）。
        if (!input.playbackActive && (input.pauseBehavior == 0 || input.pauseBehavior == 2)) {
            return Decision.SUPPRESSED
        }
        return Decision.TARGET
    }

    fun isPresentationAllowed(
        enabled: Boolean,
        playbackActive: Boolean,
        pauseBehavior: Int
    ): Boolean {
        // 只有 1（暂停时保留）允许非播放态继续呈现。
        return enabled && (playbackActive || pauseBehavior == 1)
    }
}
