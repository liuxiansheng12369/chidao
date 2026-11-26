package com.liuxiansheng.lyricon.app.activity

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 外部主题包导入。
 *
 * 用户从文件管理器选一个 zip（根目录直接是 theme.json / nodes.json / 底图 / assets/，
 * 允许外面多包一层文件夹），解压校验后落到 `filesDir/themes/<id>/`。
 * 内置包（assets）优先级更高，这里只写外部目录，不动 APK。
 */
internal object ArtThemeImporter {

    data class Imported(val id: String, val name: String)

    fun import(context: Context, uri: Uri): Imported? {
        val tmp = File(context.cacheDir, "theme_import")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) return null

        // 1. 解压到缓存目录（挡住 ../ 之类的越界条目）
        val opened = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(BufferedInputStream(ins)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && name.isNotBlank() && !name.contains("..")) {
                            val f = File(tmp, name)
                            f.parentFile?.mkdirs()
                            f.outputStream().use { out -> zis.copyTo(out) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!opened) {
            tmp.deleteRecursively()
            return null
        }

        // 2. 找到 theme.json 所在层（允许外层多套一层文件夹）
        val root = findRoot(tmp) ?: run {
            tmp.deleteRecursively()
            return null
        }
        val meta = runCatching { JSONObject(File(root, "theme.json").readText(Charsets.UTF_8)) }
            .getOrNull() ?: run {
            tmp.deleteRecursively()
            return null
        }

        val id = meta.optString("id").trim()
        if (!id.matches(Regex("[a-z0-9_]{2,32}"))) {
            tmp.deleteRecursively()
            return null
        }
        if (!File(root, "nodes.json").isFile) {
            tmp.deleteRecursively()
            return null
        }
        val bgObj = meta.optJSONObject("bg")
        val bgName = bgObj?.optString("dark").orEmpty().ifBlank { "bg_dark.webp" }
        if (!File(root, bgName).isFile) {
            tmp.deleteRecursively()
            return null
        }

        // 3. 落到 filesDir/themes/<id>，再让缓存失效
        val dest = File(context.filesDir, "themes/$id")
        runCatching {
            dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            root.copyRecursively(dest, overwrite = true)
        }.onFailure {
            tmp.deleteRecursively()
            return null
        }
        tmp.deleteRecursively()
        ArtThemeLoader.invalidate(id)

        return Imported(id, meta.optString("name").ifBlank { id })
    }

    fun uninstall(context: Context, id: String): Boolean {
        val dest = File(context.filesDir, "themes/$id")
        val ok = runCatching { !dest.exists() || dest.deleteRecursively() }.getOrDefault(false)
        if (ok) ArtThemeLoader.invalidate(id)
        return ok
    }

    private fun findRoot(dir: File, depth: Int = 0): File? {
        if (File(dir, "theme.json").isFile) return dir
        if (depth >= 2) return null
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            findRoot(sub, depth + 1)?.let { return it }
        }
        return null
    }
}
