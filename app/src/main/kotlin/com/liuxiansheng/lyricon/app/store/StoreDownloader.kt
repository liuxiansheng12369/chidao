/*
 * Lyricon V3 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 Lyricon V3 自研实现。
 *
 * StoreDownloader：把目录里的 APK 拉进私有缓存。
 * 先写 .part，体积和 SHA-256 都对上才改名成正式文件 —— 半截文件、别人换过的文件
 * 永远到不了安装器。镜像按目录顺序逐个试，每个跳转都必须落在目录的信任主机内。
 */

package com.liuxiansheng.lyricon.app.store

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface StoreDownload {
    /** 字节已与目录条目对上；身份（包名/版本/签名）还要另外查。 */
    data class Verified(val file: File) : StoreDownload

    data object NetworkFailed : StoreDownload

    /** 有镜像给了不一样的字节；不保留任何文件。 */
    data class Mismatch(val reason: String) : StoreDownload

    data object StorageFailed : StoreDownload

    data object Cancelled : StoreDownload
}

object StoreDownloader {

    private class CancelledException : IOException()
    private class StorageException(cause: IOException) : IOException(cause)

    fun cacheDir(context: Context): File = File(context.cacheDir, "store")

    fun fileName(entry: StoreEntry): String = "${entry.providerId}-${entry.versionCode}.apk"

    /**
     * 下载并校验。阻塞调用。
     *
     * @param trustedHosts 目录声明的信任主机，每个跳转的最终主机都要在里面
     * @param isCancelled 每次读块检查一次
     * @param onProgress 已下载字节 / 期望字节
     */
    fun download(
        context: Context,
        entry: StoreEntry,
        trustedHosts: Set<String>,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): StoreDownload {
        val dir = cacheDir(context)
        val target = File(dir, fileName(entry))
        val expectedBytes = entry.artifact.bytes

        // 上一次已经校验过的文件（比如用户取消了安装）直接复用
        val cached = if (target.isFile) measure(target, expectedBytes) else null
        if (cached != null && cached.bytes == expectedBytes && cached.sha256 == entry.artifact.sha256) {
            return StoreDownload.Verified(target)
        }
        if (!dir.isDirectory && !dir.mkdirs()) return StoreDownload.StorageFailed
        clean(context, keep = null)
        val part = File(dir, fileName(entry) + ".part")

        var mismatch: String? = null
        for (url in entry.artifact.urls) {
            if (isCancelled()) {
                part.delete()
                return StoreDownload.Cancelled
            }
            val measured = try {
                fetch(url, trustedHosts, part, expectedBytes, isCancelled, onProgress)
            } catch (_: CancelledException) {
                part.delete()
                return StoreDownload.Cancelled
            } catch (_: StorageException) {
                part.delete()
                return StoreDownload.StorageFailed
            } catch (_: Exception) {
                part.delete()
                continue
            }
            if (measured == null) {
                mismatch = mismatch ?: "体积超出目录声明"
                part.delete()
                continue
            }
            if (measured.bytes != expectedBytes) {
                mismatch = mismatch ?: "体积不符（${measured.bytes}/${expectedBytes}）"
                part.delete()
                continue
            }
            if (measured.sha256 != entry.artifact.sha256) {
                mismatch = mismatch ?: "SHA-256 不符"
                part.delete()
                continue
            }
            if (!part.renameTo(target)) {
                part.delete()
                return StoreDownload.StorageFailed
            }
            return StoreDownload.Verified(target)
        }
        return mismatch?.let { StoreDownload.Mismatch(it) } ?: StoreDownload.NetworkFailed
    }

    /** 清掉目录里除 [keep] 之外的缓存（安装完成或开始新任务时调用）。 */
    fun clean(context: Context, keep: File?) {
        cacheDir(context).listFiles()?.forEach { if (it != keep) it.delete() }
    }

    private data class Measured(val bytes: Long, val sha256: String)

    private fun fetch(
        url: String,
        trustedHosts: Set<String>,
        part: File,
        expected: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Measured? {
        val target = URL(url)
        val host = target.host?.lowercase()
        if (host == null || host !in trustedHosts) throw IOException("下载主机不受信：$host")
        val conn = (target.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LyriconV3-Store")
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val finalHost = conn.url?.host?.lowercase()
            if (finalHost == null || finalHost !in trustedHosts) {
                throw IOException("跳转后主机不受信：$finalHost")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val out = try {
                FileOutputStream(part)
            } catch (e: IOException) {
                throw StorageException(e)
            }
            out.use { stream ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > expected) return null
                        digest.update(buffer, 0, read)
                        try {
                            stream.write(buffer, 0, read)
                        } catch (e: IOException) {
                            throw StorageException(e)
                        }
                        onProgress(total, expected)
                    }
                }
                try {
                    stream.fd.sync()
                } catch (e: IOException) {
                    throw StorageException(e)
                }
            }
            return Measured(total, digest.digest().joinToString("") { "%02x".format(it) })
        } finally {
            conn.disconnect()
        }
    }

    private fun measure(file: File, limit: Long): Measured? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) return null
                digest.update(buffer, 0, read)
            }
        }
        Measured(total, digest.digest().joinToString("") { "%02x".format(it) })
    } catch (_: IOException) {
        null
    }
}
