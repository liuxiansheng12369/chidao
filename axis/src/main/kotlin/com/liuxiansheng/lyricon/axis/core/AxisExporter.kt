/*
 * 词岛 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 词岛 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.core

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ImportReport(
    val ok: Int,
    val skipped: Int,
    val reasons: List<String>,
) {
    fun label(): String = when {
        ok > 0 && skipped == 0 -> "导入成功 $ok 首"
        ok > 0 -> "导入 $ok 首，跳过 $skipped 首"
        else -> "没有导入：${reasons.firstOrNull() ?: "文件里没有可用的轴"}"
    }
}

object AxisExporter {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 导出单首。URI 来自 SAF（用户自己选存哪儿）。 */
    fun exportOne(context: Context, uri: Uri, axis: ManualAxis): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            out.write(json.encodeToString(ManualAxis.serializer(), axis).toByteArray())
        }
        true
    }.getOrDefault(false)

    /** 导出整个库（zip），给别人就是"我的全部轴"。 */
    fun exportAll(context: Context, uri: Uri): Boolean = runCatching {
        val dir = ManualAxisResolver.store(context).root
        if (!dir.isDirectory) return false
        context.contentResolver.openOutputStream(uri)!!.use { os ->
            ZipOutputStream(os).use { zos ->
                dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        true
    }.getOrDefault(false)

    /** 导入单首。 */
    fun importOne(context: Context, uri: Uri): ImportReport = runCatching {
        val text = context.contentResolver.openInputStream(uri)!!
            .use { it.readBytes().decodeToString() }
        mergeOne(context, json.decodeFromString(ManualAxis.serializer(), text))
    }.getOrElse {
        ImportReport(0, 1, listOf("文件解析失败：${it.message}"))
    }

    /** 导入整个 zip 库。 */
    fun importAll(context: Context, uri: Uri): ImportReport = runCatching {
        var ok = 0
        var skipped = 0
        val reasons = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)!!.use { ins ->
            ZipInputStream(ins).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.endsWith(".json") && name != "index.json") {
                        val one = runCatching {
                            mergeOne(
                                context,
                                json.decodeFromString(
                                    ManualAxis.serializer(),
                                    zis.readBytes().decodeToString()
                                )
                            )
                        }
                        val r = one.getOrNull()
                        if (r != null && r.ok > 0) ok++
                        else {
                            skipped++
                            reasons += "$name: " +
                                (one.exceptionOrNull()?.message ?: r?.reasons?.firstOrNull() ?: "跳过")
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        ImportReport(ok, skipped, reasons)
    }.getOrElse {
        ImportReport(0, 1, listOf("压缩包解析失败：${it.message}"))
    }

    /**
     * 落盘前必须过两道校验：
     * 1) 重算 songKey，和文件里的比。不一致直接拒——这是防串轴的唯一关口。
     * 2) 已存在更晚更新的同 key 轴，跳过，不覆盖别人的新版本。
     */
    private fun mergeOne(context: Context, axis: ManualAxis): ImportReport {
        val expect = SongKey.of(axis.title, axis.artist, axis.durationMs)
        if (expect != axis.songKey) {
            return ImportReport(
                0, 1,
                listOf("命中键不符：文件内 ${axis.songKey}，按标题/歌手/时长算应为 $expect")
            )
        }
        val store = ManualAxisResolver.store(context)
        val old = runCatching { store.load(axis.songKey) }.getOrNull()
        if (old != null && old.createdAt >= axis.createdAt) {
            return ImportReport(0, 1, listOf("已存在更新的轴，跳过"))
        }
        store.save(axis)
        return ImportReport(1, 0, emptyList())
    }
}
