/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root

import android.content.SharedPreferences
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object UnlockIslandWhitelist {
    private const val TAG = "UnlockIslandWhitelist"
    private const val TARGET_CLASS = "miui.systemui.notification.NotificationSettingsManager"
    private const val TARGET_METHOD = "mediaIslandSupportMiniWindow"

    internal lateinit var module: XposedModule
    private val hookedClassLoaders =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())
    private val hookHandles = java.util.Collections.synchronizedMap(LinkedHashMap<Method, HookHandle>())
    private val knownClassLoaders = java.util.Collections.synchronizedSet(mutableSetOf<ClassLoader>())
    /** 已见过的目标 Class（1.4.4：开关实时切换时用来重装 hook） */
    private val seenClasses =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun hook(xposedModule: XposedModule, defaultClassLoader: ClassLoader) {
        module = xposedModule
        val prefs = (module as HookEntry).prefs
        val prefKey = RootConstants.KEY_HOOK_REMOVE_ISLAND_WHITELIST

        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == prefKey) {
                val enabled =
                    prefs.getBoolean(prefKey, RootConstants.DEFAULT_HOOK_REMOVE_ISLAND_WHITELIST)
                if (enabled) {
                    hookAllKnownClassLoaders()
                } else {
                    unhookAll()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // 1.4.3：先注册类名监听，再装类加载桥（桥内会主动补捞一次 baseLoader）
        WhitelistClassLoadBridge.watch(TARGET_CLASS) { clazz -> installHookOnClass(clazz) }
        WhitelistClassLoadBridge.install(module, defaultClassLoader)

        if (prefs.getBoolean(prefKey, RootConstants.DEFAULT_HOOK_REMOVE_ISLAND_WHITELIST)) {
            doHookInClassLoader(defaultClassLoader)
        } else {
            hookedClassLoaders.add(defaultClassLoader)
            knownClassLoaders.add(defaultClassLoader)
        }
    }

    fun doHookInClassLoader(cl: ClassLoader?) {
        if (cl == null || !hookedClassLoaders.add(cl)) return
        knownClassLoaders.add(cl)

        if (!isPrefEnabled()) return

        runCatching {
            installHookOnClass(cl.loadClass(TARGET_CLASS))
        }.onFailure { e ->
            if (e !is ClassNotFoundException) {
                HookLogger.e(TAG, "超级岛下拉小窗白名单注入失败: classLoader=$cl", e)
            }
        }
    }

    /**
     * 在真实 Class 上装 hook（1.4.3）。
     * HyperOS 4.0 的目标类在插件 apk 的 classes2.dex 里，方法签名
     * `mediaIslandSupportMiniWindow(Ljava/lang/String;)Z`；主 SystemUI 里的同名壳类没有这个方法，
     * 所以这里按「名字 + 参数 + 返回类型」一起校验，找不到就静默跳过（壳类属于正常情况）。
     */
    private fun installHookOnClass(clazz: Class<*>) {
        if (!::module.isInitialized) return
        seenClasses.add(clazz)
        if (!isPrefEnabled()) return

        runCatching {
            val method = clazz.declaredMethods.find {
                it.name == TARGET_METHOD &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.returnType == Boolean::class.javaPrimitiveType
            } ?: return@runCatching

            synchronized(hookHandles) {
                if (hookHandles.containsKey(method)) return@runCatching
                module.deoptimize(method)
                val handle = module.hook(method).intercept(ReturnTrueHooker())
                hookHandles[method] = handle
            }
            HookLogger.i(TAG, "超级岛下拉小窗白名单 Hook 已安装: loader=${clazz.classLoader}")
        }.onFailure {
            HookLogger.e(TAG, "安装超级岛白名单 Hook 失败: ${clazz.name}", it)
        }
    }

    private fun isPrefEnabled(): Boolean {
        val prefs = (module as? HookEntry)?.prefs ?: return false
        return prefs.getBoolean(
            RootConstants.KEY_HOOK_REMOVE_ISLAND_WHITELIST,
            RootConstants.DEFAULT_HOOK_REMOVE_ISLAND_WHITELIST
        )
    }

    private fun hookAllKnownClassLoaders() {
        // 1.4.4：先对已见过的目标 Class 重装（类已加载过、类加载监听不会再触发）
        seenClasses.toList().forEach { clazz -> installHookOnClass(clazz) }
        knownClassLoaders.toList().forEach { cl ->
            doHookInClassLoader(cl)
        }
    }

    private fun unhookAll() {
        synchronized(hookHandles) {
            hookHandles.values.forEach { it.unhook() }
            hookHandles.clear()
        }
        // 1.4.4：清掉 ClassLoader 去重，否则关掉再打开不会重新挂
        hookedClassLoaders.clear()
        HookLogger.i(TAG, "超级岛下拉小窗白名单 Hook 已移除")
    }

    class ReturnTrueHooker : Hooker {
        override fun intercept(chain: Chain): Any {
            val pkg = chain.args.firstOrNull() as? String ?: "<empty>"
            HookLogger.i(TAG, "超级岛下拉小窗白名单放行: package=$pkg")
            return true
        }
    }
}
