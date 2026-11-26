/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.liuxiansheng.lyricon.hyper.common.RootConstants
import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule

object UnlockFocusWhitelist {
    private const val TAG = "UnlockFocusWhitelist"
    private const val TARGET_CLASS = "miui.systemui.notification.NotificationSettingsManager"
    private const val AUTH_CALLBACK_CLASS =
        $$"miui.systemui.notification.auth.AuthManager$AuthServiceCallback$onAuthResult$1"

    internal lateinit var module: XposedModule
    private val hookedClassLoaders =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())
    private val whitelistHandles = java.util.Collections.synchronizedList(mutableListOf<HookHandle>())
    private val hookedMethods = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val knownClassLoaders = java.util.Collections.synchronizedSet(mutableSetOf<ClassLoader>())
    /** 已见过的目标 Class（1.4.4：开关实时切换时用来重装 hook） */
    private val seenSettings =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())
    private val seenAuth =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun hook(xposedModule: XposedModule, defaultClassLoader: ClassLoader) {
        module = xposedModule
        val prefs = (module as HookEntry).prefs
        val prefKey = RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST

        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == prefKey) {
                val enabled =
                    prefs.getBoolean(prefKey, RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST)
                if (enabled) {
                    hookAllKnownClassLoaders()
                } else {
                    unhookWhitelist()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // 1.4.3：目标类（含 canShowFocus / canCustomFocus）与授权回调都在插件 apk 的 ClassLoader 里，
        // 用类加载监听兜底；先注册监听，再装桥（桥内会主动补捞一次 baseLoader）
        WhitelistClassLoadBridge.watch(TARGET_CLASS) { clazz -> installSettingsHooks(clazz) }
        WhitelistClassLoadBridge.watch(AUTH_CALLBACK_CLASS) { clazz -> installAuthHook(clazz) }
        WhitelistClassLoadBridge.install(module, defaultClassLoader)

        if (prefs.getBoolean(prefKey, RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST)) {
            doHookInClassLoader(defaultClassLoader)
        } else {
            hookedClassLoaders.add(defaultClassLoader)
            knownClassLoaders.add(defaultClassLoader)
        }
    }

    private fun doHookInClassLoader(cl: ClassLoader?) {
        if (cl == null || !hookedClassLoaders.add(cl)) return
        knownClassLoaders.add(cl)
        if (!isPrefEnabled()) return

        runCatching {
            installSettingsHooks(cl.loadClass(TARGET_CLASS))
        }.onFailure { e ->
            if (e !is ClassNotFoundException) {
                HookLogger.e(TAG, "注入焦点通知白名单失败: classLoader=$cl", e)
            }
        }
        runCatching {
            installAuthHook(cl.loadClass(AUTH_CALLBACK_CLASS))
        }.onFailure { e ->
            if (e !is ClassNotFoundException) {
                HookLogger.e(TAG, "注入焦点通知授权失败: classLoader=$cl", e)
            }
        }
    }

    /**
     * Hook 插件里 NotificationSettingsManager 的真实方法（1.4.3）。
     * 插件 apk classes2.dex 实测签名：
     *   canShowFocus(Landroid/content/Context; Ljava/lang/String;)Z
     *   canCustomFocus(Ljava/lang/String;)Z
     * 主 SystemUI 里的同名壳类只有 canShowFocusState(...)I 这套，找不到就静默跳过。
     */
    private fun installSettingsHooks(clazz: Class<*>) {
        if (!::module.isInitialized) return
        seenSettings.add(clazz)
        if (!isPrefEnabled()) return

        runCatching {
            val methods = clazz.declaredMethods.filter {
                (it.name == "canShowFocus" || it.name == "canCustomFocus") &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            if (methods.isEmpty()) return@runCatching

            methods.forEach { method ->
                val key = "${clazz.name}#${method.name}${method.parameterTypes.joinToString(",")}"
                synchronized(hookedMethods) {
                    if (!hookedMethods.add(key)) return@forEach
                }
                module.deoptimize(method)
                whitelistHandles.add(module.hook(method).intercept(ReturnTrueHooker()))
            }
            HookLogger.i(
                TAG,
                "焦点通知白名单 Hook 已安装: methods=${methods.joinToString { it.name }} loader=${clazz.classLoader}"
            )
        }.onFailure {
            HookLogger.e(TAG, "安装焦点白名单 Hook 失败: ${clazz.name}", it)
        }
    }

    private fun installAuthHook(clazz: Class<*>) {
        if (!::module.isInitialized) return
        seenAuth.add(clazz)
        if (!isPrefEnabled()) return

        runCatching {
            val method = clazz.declaredMethods.find { it.name == "invokeSuspend" } ?: return@runCatching
            val key = "${clazz.name}#invokeSuspend"
            synchronized(hookedMethods) {
                if (!hookedMethods.add(key)) return@runCatching
            }
            module.deoptimize(method)
            whitelistHandles.add(module.hook(method).intercept(AuthResultHooker()))
            HookLogger.i(TAG, "焦点通知授权 Hook 已安装: loader=${clazz.classLoader}")
        }.onFailure {
            HookLogger.e(TAG, "安装焦点授权 Hook 失败: ${clazz.name}", it)
        }
    }

    private fun isPrefEnabled(): Boolean {
        val prefs = (module as? HookEntry)?.prefs ?: return false
        return prefs.getBoolean(
            RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST,
            RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST
        )
    }

    private fun hookAllKnownClassLoaders() {
        // 1.4.4：先对已见过的目标 Class 重装（类已加载过、类加载监听不会再触发）
        seenSettings.toList().forEach { clazz -> installSettingsHooks(clazz) }
        seenAuth.toList().forEach { clazz -> installAuthHook(clazz) }
        knownClassLoaders.toList().forEach { cl ->
            doHookInClassLoader(cl)
        }
    }

    private fun unhookWhitelist() {
        synchronized(whitelistHandles) {
            whitelistHandles.forEach { it.unhook() }
            whitelistHandles.clear()
        }
        synchronized(hookedMethods) { hookedMethods.clear() }
        // 1.4.4：清掉 ClassLoader 去重，否则关掉再打开不会重新挂
        hookedClassLoaders.clear()
        HookLogger.i(TAG, "焦点通知白名单 Hook 已移除")
    }

    class ReturnTrueHooker : Hooker {
        override fun intercept(chain: Chain): Any {
            val pkg = chain.args.filterIsInstance<String>().lastOrNull() ?: "<empty>"
            HookLogger.i(TAG, "焦点通知白名单放行: package=$pkg")
            return true
        }
    }

    class AuthResultHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            // hook 存在即代表功能开启，直接执行注入逻辑
            runCatching {
                val thisObj = chain.thisObject
                thisObj.javaClass.declaredFields.forEach { f ->
                    if (f.name.contains("authBundle")) {
                        f.isAccessible = true
                        (f.get(thisObj) as? Bundle)?.putInt("result_code", 0)
                    }
                }
            }
            return chain.proceed()
        }
    }
}
