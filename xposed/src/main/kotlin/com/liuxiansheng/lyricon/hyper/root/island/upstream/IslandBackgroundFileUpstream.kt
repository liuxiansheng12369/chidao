/*
 * 本文件基于 HyperIsland（作者 1812z）移植，原项目：
 * https://github.com/1812z/HyperIsland
 * 原版权归 1812z 所有，原项目以 MIT 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.upstream

import java.io.File

internal object IslandBackgroundFile {
    private const val MAX_FILE_SIZE = 128L * 1024L * 1024L
    private val allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val allowedRoots = listOf(
        File("/sdcard/Pictures/HyperIsland"),
        File("/storage/emulated/0/Pictures/HyperIsland"),
        File("/sdcard/Android/data/io.github.hyperisland/files"),
        File("/storage/emulated/0/Android/data/io.github.hyperisland/files"),
        File("/sdcard/Pictures/Lyricon"),
        File("/storage/emulated/0/Pictures/Lyricon"),
        File("/sdcard/Android/data/com.liuxiansheng.chidao/files"),
        File("/storage/emulated/0/Android/data/com.liuxiansheng.chidao/files"),
    )

    fun resolve(path: String): File? {
        if (path.isBlank()) return null
        return runCatching {
            val file = File(path).canonicalFile
            if (!file.isFile || !file.canRead() || file.length() !in 1..MAX_FILE_SIZE) {
                return@runCatching null
            }
            if (file.extension.lowercase() !in allowedExtensions) return@runCatching null
            val insideAllowedRoot = allowedRoots.any { root ->
                val canonicalRoot = root.canonicalFile
                file.parentFile == canonicalRoot
            }
            file.takeIf { insideAllowedRoot }
        }.getOrNull()
    }
}