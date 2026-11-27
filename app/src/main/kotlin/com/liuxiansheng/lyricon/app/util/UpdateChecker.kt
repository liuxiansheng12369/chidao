/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：io.github.liuxiansheng12369.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 *
 * UpdateChecker：检查新版本。
 * 主源取 modules.lsposed.org 的模块 JSON（/module/<包名>.json）：国内可直达、无限流，
 * 里面 Release 的 tag 就是 <versionCode>-<versionName>，直接跟本地 versionCode 比。
 * 主源还没被站点抓取（404）时退回 GitHub API 的 releases 列表，字段名不同，单独解析。
 * 两个入口：启动时自动查（24 小时节流）、关于页手动点。任何失败都静默返回 null。
 */

package com.liuxiansheng.lyricon.app.util

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val MODULE_PKG = "io.github.liuxiansheng12369.chidao"
    private const val MODULE_JSON = "https://modules.lsposed.org/module/$MODULE_PKG.json"
    private const val MODULE_PAGE = "https://modules.lsposed.org/module/$MODULE_PKG"
    private const val GITHUB_RELEASES = "https://api.github.com/repos/Xposed-Modules-Repo/$MODULE_PKG/releases"
    private const val PREFS = "update_check"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val AUTO_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val CONNECT_TIMEOUT = 6000
    private const val READ_TIMEOUT = 8000

    data class Update(
        /** 远端 versionCode（tag 里连字符前那一段） */
        val versionCode: Int,
        val versionName: String,
        /** Release 正文清洗后的摘要，可能为空 */
        val notes: String,
        /** 站内 CDN 直链（带签名，会过期） */
        val downloadUrl: String,
        /** GitHub 原始下载地址，签名链接失效时兜底 */
        val originalUrl: String,
        /** Release 页面，最终兜底入口 */
        val pageUrl: String,
        val sizeBytes: Long,
    )

    /** 最近一次查询是否真的拿到了一份可用数据（用来决定要不要记节流时间） */
    @Volatile
    private var lastFetchOk = false

    /** 最近一次查到的可用更新；首页/关于页直接读它，为 null 即表示"当前就是最新或没查到" */
    val result = mutableStateOf<Update?>(null)

    fun localVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode
    }.getOrDefault(0)

    fun localVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    fun lastCheckTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)

    /** 启动自动检查用：距上次成功检查超过 24 小时才查 */
    fun shouldAutoCheck(context: Context): Boolean {
        val last = lastCheckTime(context)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= AUTO_INTERVAL_MS
    }

    /**
     * 查询新版本。force=true 表示手动点「检查更新」，不看节流。
     * 返回 null = 没有更新或查询失败（两种情况对用户都表现为"已是最新"或静默）。
     */
    fun check(context: Context, force: Boolean): Update? {
        val localCode = localVersionCode(context)
        lastFetchOk = false
        val remote = runCatching { fetchLatest(localCode) }.getOrNull()
        if (remote != null || lastFetchOk) {
            // 拿到可用数据（有新版本，或确认已是最新）才记时间；
            // 网络失败 / 站点还没抓取都不记，下次启动还能再试
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        }
        result.value = remote
        Log.i(
            TAG,
            if (remote == null) "检查更新：无可用新版本（本地 $localCode）"
            else "检查更新：发现 ${remote.versionName}（${remote.versionCode}），本地 $localCode"
        )
        return remote
    }

    /** 解析结果：parsed=false 表示这份数据读不了（404 / 结构不对），可以换源再试 */
    private class Parsed(val ok: Boolean, val update: Update?)

    private fun fetchLatest(localCode: Int): Update? {
        // 源 1：模块仓库站点 JSON（含 tag / CDN 直链）
        get(MODULE_JSON)?.let { json ->
            val parsed = parseModuleJson(json, localCode)
            if (parsed.ok) {
                lastFetchOk = true
                Log.i(TAG, "主源解析成功：${if (parsed.update == null) "无新版本" else "新版 ${parsed.update.versionName}"}（本地 $localCode）")
                return parsed.update
            }
            Log.w(TAG, "主源 JSON 结构不可用，转 GitHub API")
        }

        // 源 2：GitHub API（站点还没抓取到时靠它兜底，字段名不同）
        get(GITHUB_RELEASES, accept = "application/vnd.github+json")?.let { json ->
            val parsed = parseGithubJson(json, localCode)
            if (parsed.ok) {
                lastFetchOk = true
                Log.i(TAG, "GitHub 兜底解析成功：${if (parsed.update == null) "无新版本" else "新版 ${parsed.update.versionName}"}（本地 $localCode）")
                return parsed.update
            }
            Log.w(TAG, "GitHub 兜底 JSON 结构不可用")
        }
        return null
    }

    private fun parseModuleJson(json: String, localCode: Int): Parsed {
        return runCatching {
            val root = JSONObject(json)
            val releases = root.optJSONArray("releases") ?: return@runCatching Parsed(false, null)
            var best: Update? = null
            for (i in 0 until releases.length()) {
                val r = releases.optJSONObject(i) ?: continue
                if (r.optBoolean("isDraft") || r.optBoolean("isPrerelease")) continue
                val tag = r.optString("tagName").trim()
                val code = tag.substringBefore('-').toIntOrNull() ?: continue
                if (code <= localCode) continue
                val name = tag.substringAfter('-', "").ifBlank { r.optString("name") }.ifBlank { code.toString() }

                var download = ""
                var original = ""
                var size = 0L
                val assets = r.optJSONArray("releaseAssets")
                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val a = assets.optJSONObject(j) ?: continue
                        val assetName = a.optString("name")
                        if (!assetName.endsWith(".apk", ignoreCase = true)) continue
                        if (assets.length() > 1 && !assetName.contains("arm64", ignoreCase = true)) continue
                        download = a.optString("downloadUrl")
                        original = a.optString("originalDownloadUrl")
                        size = a.optLong("size", 0L)
                        break
                    }
                }
                if (download.isBlank() && original.isBlank()) continue

                val candidate = Update(
                    versionCode = code,
                    versionName = name,
                    notes = cleanHtml(r.optString("descriptionHTML")),
                    downloadUrl = download,
                    originalUrl = original,
                    pageUrl = r.optString("url").ifBlank { MODULE_PAGE },
                    sizeBytes = size,
                )
                if (best == null || candidate.versionCode > best.versionCode) best = candidate
            }
            Parsed(true, best)
        }.getOrElse { t ->
            Log.w(TAG, "主源解析异常：${t.message}")
            Parsed(false, null)
        }
    }

    private fun parseGithubJson(json: String, localCode: Int): Parsed {
        return runCatching {
            val arr = org.json.JSONArray(json)
            var best: Update? = null
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val tag = r.optString("tag_name").trim()
                val code = tag.substringBefore('-').toIntOrNull() ?: continue
                if (code <= localCode) continue
                val name = tag.substringAfter('-', "").ifBlank { r.optString("name") }.ifBlank { code.toString() }

                var download = ""
                var size = 0L
                val assets = r.optJSONArray("assets")
                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val a = assets.optJSONObject(j) ?: continue
                        val assetName = a.optString("name")
                        if (!assetName.endsWith(".apk", ignoreCase = true)) continue
                        if (assets.length() > 1 && !assetName.contains("arm64", ignoreCase = true)) continue
                        download = a.optString("browser_download_url")
                        size = a.optLong("size", 0L)
                        break
                    }
                }
                if (download.isBlank()) continue

                val candidate = Update(
                    versionCode = code,
                    versionName = name,
                    notes = cleanHtml(r.optString("body")),
                    downloadUrl = "",
                    originalUrl = download,
                    pageUrl = r.optString("html_url").ifBlank { MODULE_PAGE },
                    sizeBytes = size,
                )
                if (best == null || candidate.versionCode > best.versionCode) best = candidate
            }
            Parsed(true, best)
        }.getOrElse { t ->
            Log.w(TAG, "GitHub 兜底解析异常：${t.message}")
            Parsed(false, null)
        }
    }

    private fun get(url: String, accept: String = "application/json"): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Chidao-UpdateCheck")
                setRequestProperty("Accept", accept)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "请求 $url 返回 ${conn.responseCode}")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "请求 $url 失败：${t.javaClass.simpleName} ${t.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Release 正文是 HTML / Markdown，这里压成一行纯文本，只留前 160 字给界面用 */
    private fun cleanHtml(html: String): String {
        if (html.isBlank()) return ""
        var s = html.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.length > 160) s.take(160) + "…" else s
    }
}
