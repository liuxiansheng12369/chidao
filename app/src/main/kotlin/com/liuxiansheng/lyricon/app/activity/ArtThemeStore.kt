package com.liuxiansheng.lyricon.app.activity

import android.content.Context
import org.json.JSONObject
import java.io.File

internal data class ImportedTheme(val id: String, val name: String)

/**
 * 已导入主题包的登记表（就是 `filesDir/themes/` 下有几个包）。
 * 皮肤 id 统一挂在 HomeSkins.artDark（6）上，具体渲染哪个包由这里的 currentId 决定，
 * 这样用户自己做的主题导入后能直接出现在首页主题条里，不用改代码。
 */
internal object ArtThemeStore {

    const val KEY = "pref_art_theme_id"

    private fun prefs(context: Context) = context.getSharedPreferences(
        "com.liuxiansheng.lyricon_preferences", Context.MODE_PRIVATE
    )

    fun list(context: Context): List<ImportedTheme> {
        val root = File(context.filesDir, "themes")
        val dirs = root.listFiles()?.filter { it.isDirectory && File(it, "theme.json").isFile }
            ?: return emptyList()
        return dirs.map { dir ->
            val name = runCatching {
                JSONObject(File(dir, "theme.json").readText(Charsets.UTF_8))
                    .optString("name")
            }.getOrDefault("")
            ImportedTheme(dir.name, name.ifBlank { dir.name })
        }.sortedBy { it.name }
    }

    /** 当前选中的包 id；一个包都没有时返回 null */
    fun currentId(context: Context): String? {
        val all = list(context)
        if (all.isEmpty()) return null
        val saved = prefs(context).getString(KEY, null)
        return if (saved != null && all.any { it.id == saved }) saved else all.first().id
    }

    fun currentName(context: Context): String {
        val id = currentId(context) ?: return "艺术主题"
        return list(context).firstOrNull { it.id == id }?.name ?: id
    }

    /** 切到某个已导入的包（会一并把首页皮肤切到艺术主题） */
    fun setCurrent(context: Context, packId: String) {
        prefs(context).edit().putString(KEY, packId).apply()
        HomeSkins.write(context, HomeSkins.artDark.id)
        ArtThemeLoader.invalidate(packId)
    }

    /** 删除一个包；删的是当前用的那个就清掉选择，让首页回默认 */
    fun remove(context: Context, packId: String): Boolean {
        val ok = ArtThemeImporter.uninstall(context, packId)
        if (ok && prefs(context).getString(KEY, null) == packId) {
            prefs(context).edit().remove(KEY).apply()
        }
        return ok
    }
}
