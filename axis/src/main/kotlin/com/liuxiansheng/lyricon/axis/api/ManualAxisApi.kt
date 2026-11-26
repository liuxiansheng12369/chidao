/*
 * Lyricon V3 · 超级岛歌词（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * 模块：打轴器 :axis · 逐字打轴
 * Copyright 2026 liuxiansheng（刘先生）· 保留所有权利
 * 本文件为 Lyricon V3 自研实现，未经授权禁止复制、修改、分发。
 */

package com.liuxiansheng.lyricon.axis.api

import android.content.Context
import android.content.Intent
import com.liuxiansheng.lyricon.axis.core.AxisStore
import com.liuxiansheng.lyricon.axis.core.ManualAxis
import com.liuxiansheng.lyricon.axis.core.ManualAxisResolver
import com.liuxiansheng.lyricon.axis.core.SongKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 打轴器对外的唯一门面。
 *
 * 词岛 App 侧（设置页 / 设置项）直接用前三个方法；
 * 词岛 SystemUI 侧（:xposed）不能读 App 私有目录，走 [lookupJson]，
 * 由 App 的 ContentProvider 代为读盘并回传 JSON。
 *
 * 谁都不许直接读文件、也不许反射进 `:axis` 内部结构 —— 接触面只有这里。
 */
object ManualAxisApi {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 按歌名 + 歌手 + 总时长找人工轴；找不到返回 null（回落到原来的歌词源）。 */
    fun resolve(context: Context, title: String, artist: String, durationMs: Long): ManualAxis? =
        ManualAxisResolver.resolve(context, title, artist, durationMs)

    /** 已打轴条数，用于显示「已打轴 N 首」。 */
    fun summary(context: Context): Int =
        ManualAxisResolver.summary(context)

    /** 人工轴存放目录：filesDir/lyrics/manual，打轴器写、岛侧读的是同一处。 */
    fun axisFilesDir(context: Context): File = File(context.filesDir, "lyrics/manual")

    /** 已存的人工轴清单（设置页 / 最近打轴用）。 */
    fun list(context: Context) =
        runCatching { AxisStore(axisFilesDir(context)).list() }.getOrDefault(emptyList())

    /**
     * 给 SystemUI 侧用的查询：命中则返回整首轴的 JSON，未命中返回 null。
     * 跨进程只能传字符串，模型由两边共用同一份 schema。
     */
    fun lookupJson(context: Context, title: String, artist: String, durationMs: Long): String? {
        val axis = resolve(context, title, artist, durationMs) ?: return null
        return runCatching { json.encodeToString(axis) }.getOrNull()
    }

    /** 已经算好命中键时的查询（SystemUI 侧自己有 key 的情况下用）。 */
    fun lookupJsonByKey(context: Context, songKey: String): String? {
        val axis = ManualAxisResolver.find(context, songKey) ?: return null
        return runCatching { json.encodeToString(axis) }.getOrNull()
    }

    /** 命中键换算，供 SystemUI 侧打印诊断日志时对齐用。 */
    fun songKeyOf(title: String, artist: String, durationMs: Long): String =
        SongKey.of(title, artist, durationMs)

    /** 保存完通知 SystemUI 重新读盘，别等换歌才生效。 */
    const val ACTION_AXIS_SAVED = "com.liuxiansheng.lyricon.action.AXIS_SAVED"

    /**
     * 轴数据推送：把轴文件内容直接塞进广播，SystemUI 收下后存进自己的私有目录。
     *
     * 这样播放时 SystemUI 读自己的盘就够了，不必再跨进程叫醒 App ——
     * App 只负责做轴，做完把成品交出去，之后它死不死都不影响显示。
     */
    const val ACTION_AXIS_PUSH = "com.liuxiansheng.lyricon.action.AXIS_PUSH"

    /** 推送载荷：`[{"name":"axis-xxx.json","body":"..."}]`。 */
    const val EXTRA_FILES = "files"

    /**
     * 反向请求：状态栏发现手里没有轴副本时，喊 App 重推一次。
     *
     * 只在副本为空（刚装新版、清过数据）时才发，平时播放绝不触发。
     */
    const val ACTION_AXIS_SYNC_REQUEST = "com.liuxiansheng.lyricon.action.AXIS_SYNC_REQUEST"

    /**
     * 轴删除：App 的轴曲库里删掉某几首后，让 SystemUI 把对应副本也清掉。
     *
     * 不做这一步就只是"App 里看不见了" —— 岛手里还握着副本，照样拿它顶歌词。
     */
    const val ACTION_AXIS_DROP = "com.liuxiansheng.lyricon.action.AXIS_DROP"

    /** 删除载荷：命中键列表，用换行分隔。 */
    const val EXTRA_KEYS = "keys"

    /** 删掉这几首后喊 SystemUI 清副本。keys 传 songKey。 */
    fun notifyDropped(context: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_AXIS_DROP)
                    .setPackage("com.android.systemui")
                    .putExtra(EXTRA_KEYS, keys.joinToString("\n"))
            )
        }
    }

    /** 单批最多推这么多文件（仅作粗粒度切块，真正的保护见 PUSH_MAX_BYTES）。 */
    private const val PUSH_BATCH = 4

    /**
     * 单条广播载荷的字节上限。
     *
     * Binder 事务总量虽约 1MB，但广播还要复制给多个接收者，实测 695,788 字节
     * 就会抛 TransactionTooLargeException；而 AMS 一旦投递失败，会把接收进程
     * 判成「Can't deliver broadcast」直接强杀（SystemUI 因此被杀→回锁屏）。
     * 所以这里取远低于上限的 96KB 分批。
     */
    private const val PUSH_MAX_BYTES = 96 * 1024

    /**
     * 保存成功后调一次：显式广播给 SystemUI，让它重新查一遍人工轴并重建时间轴。
     * setPackage 必须写——这是显式广播，绕开 Android 8+ 对隐式广播的限制。
     */
    fun notifySaved(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_AXIS_SAVED).setPackage("com.android.systemui")
            )
        }
    }

    /**
     * 保存成功后调这个：带上这条轴的内容一起推给 SystemUI。
     *
     * 顺序不能反 —— 必须在 `store.save()` 之后调用，否则读到的是旧索引。
     */
    fun notifySaved(context: Context, axis: ManualAxis) {
        notifySaved(context)
        runCatching {
            val dir = axisFilesDir(context)
            val files = ArrayList<Pair<String, String>>()
            val store = AxisStore(dir)
            // 文件名靠索引查（重打旧轴会复用原文件名，不能按 key 自己算）
            store.loadIndex().firstOrNull { it.songKey == axis.songKey }?.let { entry ->
                val f = File(dir, entry.fileName)
                if (f.exists()) files.add(f.name to f.readText())
            }
            // 索引一起推：SystemUI 靠它按 key 定位文件，重打/改名后也能同步
            val index = File(dir, "index.json")
            if (index.exists()) files.add(index.name to index.readText())
            pushFiles(context, files)
        }
    }

    /** 把整个轴库推一遍。App 启动时调，保证 SystemUI 拿到升级前打过的旧轴。 */
    fun pushAllToSystemUi(context: Context) {
        runCatching {
            val dir = axisFilesDir(context)
            val all = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return
            all.sortedBy { it.name }.chunked(PUSH_BATCH).forEach { batch ->
                pushFiles(context, batch.map { it.name to it.readText() })
            }
        }
    }

    private fun pushFiles(context: Context, files: List<Pair<String, String>>) {
        if (files.isEmpty()) return
        val batch = ArrayList<Pair<String, String>>()
        var batchBytes = 0

        fun flush() {
            if (batch.isEmpty()) return
            val payload = JsonArray(
                batch.map { (name, body) ->
                    buildJsonObject {
                        put("name", name)
                        put("body", body)
                    }
                }
            ).toString()
            val target = context.applicationContext ?: context
            runCatching {
                target.sendBroadcast(
                    Intent(ACTION_AXIS_PUSH)
                        .setPackage("com.android.systemui")
                        .putExtra(EXTRA_FILES, payload)
                )
            }
            batch.clear()
            batchBytes = 0
        }

        for (f in files) {
            // JSON 转义会膨胀，按 "名字+正文+结构开销" 粗估
            val est = f.first.length + f.second.length + 48
            if (batch.isNotEmpty() && batchBytes + est > PUSH_MAX_BYTES) flush()
            batch.add(f)
            batchBytes += est
            if (batchBytes >= PUSH_MAX_BYTES) flush()
        }
        flush()
    }
}
