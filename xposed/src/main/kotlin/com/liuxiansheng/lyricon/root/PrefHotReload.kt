/*
 * 词岛 · 超级岛歌词 Xposed 模块（小米澎湃 OS / HyperOS）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 * 本文件为 词岛 自研实现。
 */

package com.liuxiansheng.lyricon.hyper.root

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import com.liuxiansheng.lyricon.bridge.BridgeConstants
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 设置即时生效：单键广播 + 内存覆盖表。
 *
 * 背景：App 侧设置写在 libxposed 的 remote prefs 里（跨进程、异步落盘），
 * SystemUI 这边读到的可能是旧值。3.0.934 又把"广播 + 对账"整条通道拆了，
 * 只留 remote 的 SharedPreferences 监听兜底，于是出现"改完要重启状态栏、
 * 有时还得点两次才生效"。
 *
 * 做法（对齐 HyperLyrics-Enhanced 的 IslandRuntimePreferenceOverrides）：
 * 1. App 每写一个键，广播一条「键 + 新值」过来；
 * 2. 这里存进内存覆盖表，并回调通知 HookEntry 走一次设置刷新；
 * 3. 所有读设置的地方经 [wrap] 包一层：[OverrideFirstPrefs] 先查覆盖表，
 *    没有才回落到原来的 prefs（remote → SystemUI 本地文件，即 MergedPrefs）。
 *
 * 覆盖表只增不减会一直压住磁盘值，所以 App 重启/模块重装后清一次（[clear]）。
 */
object PrefHotReload {
    private const val TAG = "PrefHotReload"

    /** 键 → 最新值（null 表示已被删除，用 sentinel 记不住，这里直接移除键） */
    private val overrides = ConcurrentHashMap<String, Any>()

    /** 单个键变化后的刷新回调（由 ModuleEntry 挂到 HookEntry.onSettingsBroadcastReceived） */
    @Volatile
    var onPrefChanged: ((String) -> Unit)? = null

    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var appUid: Int = -1

    fun initialize(ctx: Context) {
        if (receiver != null) return
        appUid = runCatching {
            ctx.packageManager
                .getApplicationInfo(com.liuxiansheng.lyricon.xposed.BuildConfig.APP_PACKAGE_NAME, 0)
                .uid
        }.getOrDefault(-1)

        val filter = IntentFilter(BridgeConstants.ACTION_PREF_CHANGED)
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BridgeConstants.ACTION_PREF_CHANGED) return
                // 只认本应用发来的（API 34+ 才能拿到发送方 uid，拿不到就放行）
                if (Build.VERSION.SDK_INT >= 34) {
                    val sender = runCatching { sentFromUid }.getOrDefault(-1)
                    if (appUid >= 0 && sender >= 0 && sender != appUid) {
                        HookLogger.w(TAG, "拒绝非本应用的设置广播: sender=$sender expected=$appUid")
                        return
                    }
                }
                val bundle = runCatching {
                    intent.getBundleExtra(BridgeConstants.EXTRA_PREF_BUNDLE)
                }.getOrNull() ?: return

                val (changed, lastKey) = applyKeyedBundle(bundle)
                if (changed > 0) {
                    HookLogger.i(TAG, "收到 $changed 个设置的即时更新，末键=$lastKey")
                    runCatching { onPrefChanged?.invoke(lastKey ?: "") }
                        .onFailure { e -> HookLogger.e(TAG, "设置即时刷新回调失败", e) }
                }
            }
        }
        runCatching {
            ctx.applicationContext.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            receiver = r
            HookLogger.i(TAG, "registered, appUid=$appUid")
        }.onFailure { e ->
            HookLogger.e(TAG, "registerReceiver 失败", e)
            receiver = null
        }
    }

    /** 覆盖表里有没有这个键（有就说明刚被广播改过，应当优先） */
    fun containsOverride(key: String): Boolean = overrides.containsKey(key)

    fun overrideValue(key: String): Any? = overrides[key]

    /**
     * 解析 `{ 键 → {t=类型, v=值} }` 结构的键值包，写入覆盖表。
     * 单键广播与全量快照共用这一套类型约定。
     */
    private fun applyKeyedBundle(bundle: android.os.Bundle): Pair<Int, String?> {
        var changed = 0
        var lastKey: String? = null
        bundle.keySet().forEach { key ->
            val sub = bundle.getBundle(key) ?: return@forEach
            val type = sub.getString("t") ?: return@forEach
            val value: Any? = when (type) {
                "clear" -> null
                "boolean" -> sub.getBoolean("v", false)
                "int" -> sub.getInt("v", 0)
                "long" -> sub.getLong("v", 0L)
                "float" -> sub.getFloat("v", 0f)
                "string" -> sub.getString("v")
                "string_set" -> sub.getStringArrayList("v")?.toSet() ?: emptySet<String>()
                else -> return@forEach
            }
            if (value == null) overrides.remove(key) else overrides[key] = value
            changed++
            lastKey = key
        }
        return changed to lastKey
    }

    /** SystemUI 重启/模块重装后的清理入口 */
    fun clear() {
        overrides.clear()
        HookLogger.i(TAG, "overrides cleared")
    }

    /**
     * 把原 prefs 包成"覆盖表优先"的只读视图。
     * 写入、监听全部原样转发，行为与原来一致；只有被广播改过的键值不同。
     */
    fun wrap(base: SharedPreferences): SharedPreferences = OverrideFirstPrefs(base)

    private class OverrideFirstPrefs(private val base: SharedPreferences) : SharedPreferences {

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            (overrides[key] as? Boolean) ?: base.getBoolean(key, defValue)

        override fun getInt(key: String, defValue: Int): Int =
            (overrides[key] as? Int) ?: base.getInt(key, defValue)

        override fun getLong(key: String, defValue: Long): Long =
            (overrides[key] as? Long) ?: base.getLong(key, defValue)

        override fun getFloat(key: String, defValue: Float): Float =
            (overrides[key] as? Float) ?: base.getFloat(key, defValue)

        override fun getString(key: String, defValue: String?): String? =
            (overrides[key] as? String) ?: base.getString(key, defValue)

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
            val hit = overrides[key]
            if (hit is Set<*>) return hit.filterIsInstance<String>().toMutableSet()
            return base.getStringSet(key, defValues)
        }

        override fun contains(key: String): Boolean =
            overrides.containsKey(key) || base.contains(key)

        override fun getAll(): MutableMap<String, *> {
            val map = base.all.toMutableMap()
            overrides.forEach { (k, v) -> map[k] = v }
            return map
        }

        override fun edit(): SharedPreferences.Editor = base.edit()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = base.registerOnSharedPreferenceChangeListener(listener)

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = base.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
