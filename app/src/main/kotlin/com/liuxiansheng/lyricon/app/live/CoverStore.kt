/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.app.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

/**
 * 听歌记录的封面存档。
 *
 * 系统只给"当前这一首"的封面（LiveStateProvider 把它落成 filesDir/live_cover.jpg，
 * 换歌就覆盖）。所以结算一条记录时，如果当前封面标签正好是这首歌，就复制一份到
 * filesDir/covers/<歌名|歌手的 md5>.jpg —— 同一首歌只存一张，天然去重。
 *
 * 没有封面的记录不生成文件，前端按空名退回色块占位。
 */
object CoverStore {
    private const val DIR = "covers"

    /** 封面解码结果缓存（滚动列表时避免反复解同一张）。 */
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun coverDir(context: Context): File = File(context.filesDir, DIR)

    /** 歌名 + 歌手 → 稳定的封面文件名。 */
    fun nameOf(title: String, artist: String): String {
        val md = MessageDigest.getInstance("MD5").digest("$title|$artist".toByteArray())
        val sb = StringBuilder(md.size * 2)
        for (b in md) sb.append(String.format("%02x", b))
        return sb.toString() + ".jpg"
    }

    /**
     * 结算时调用：当前封面就是这首歌就存档，返回文件名；对不上或没图返回 null。
     * 已存过就直接复用（同一首多次听不重复写盘）。
     */
    fun snapshot(context: Context, title: String, artist: String): String? {
        if (title.isBlank()) return null
        val now = runCatching { NowPlayingReader.read(context) }.getOrNull() ?: return null
        if (now.coverTag != "$title|$artist") return null
        val src = File(context.filesDir, LiveStateProvider.COVER_FILE)
        if (!src.isFile || src.length() <= 0L) return null
        val name = nameOf(title, artist)
        val dst = File(coverDir(context), name)
        if (dst.isFile && dst.length() > 0L) return name
        val dir = coverDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return if (runCatching { src.copyTo(dst, overwrite = true) }.isSuccess) name else null
    }

    /** 按文件名取封面文件。 */
    fun file(context: Context, name: String): File? {
        if (name.isBlank()) return null
        val f = File(coverDir(context), name)
        return f.takeIf { it.isFile && it.length() > 0L }
    }

    /** 采样解码到约 [targetPx]（像素）大小，带内存缓存；解码失败返回 null。 */
    fun bitmap(context: Context, name: String, targetPx: Int): Bitmap? {
        if (name.isBlank() || targetPx <= 0) return null
        val key = "$name@$targetPx"
        cache.get(key)?.let { return it }
        val f = file(context, name) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** 清掉不再被任何记录引用的封面文件（记录清理/清空之后调用）。 */
    fun prune(context: Context, keep: Set<String>) {
        runCatching {
            val dir = coverDir(context)
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name !in keep) f.delete()
            }
        }
    }
}
