/*
 * 本文件为词岛自研实现；随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root

import com.liuxiansheng.lyricon.hyper.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule

/**
 * 白名单 hook 的「类加载兜底桥」（1.4.3 新增）。
 *
 * 背景（真机实证，HyperOS 4.0）：
 * `miui.systemui.notification.NotificationSettingsManager` 与
 * `miui.systemui.notification.auth.AuthManager$AuthServiceCallback$onAuthResult$1`
 * 在插件 apk `/product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk` 的 classes2.dex 里，
 * 由插件自己的 ClassLoader 加载；主 SystemUI 的 defaultClassLoader 里只有同名「壳类」
 * （只有 canShowFocusState / getFocusState 这套，没有 mediaIslandSupportMiniWindow）。
 *
 * 原来两个白名单只挂 defaultClassLoader，所以永远找不到目标方法、静默无日志、开关打开等于没开。
 *
 * 这里做三件事：
 * 1) 挂 `ClassLoader.loadClass(String, boolean)`，任何 ClassLoader 加载到目标类名就回调；
 * 2) 挂插件框架的 `PluginInstance$PluginFactory.createClassLoader()` 与
 *    `PluginInstance$PluginContextWrapper.getClassLoader()`，拿到插件 ClassLoader 后主动补捞一次；
 * 3) 同一 Class 只投递一次，回调期间禁止重入，避免装 hook 时递归。
 */
internal object WhitelistClassLoadBridge {
    private const val TAG = "WhitelistBridge"

    private const val PLUGIN_FACTORY_CLASS =
        "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"
    private const val PLUGIN_CONTEXT_WRAPPER_CLASS =
        "com.android.systemui.shared.plugins.PluginInstance\$PluginContextWrapper"

    private val listeners = HashMap<String, MutableList<(Class<*>) -> Unit>>()
    private val delivered = HashSet<String>()
    private val reentrant = ThreadLocal<Boolean>()

    private var installed = false

    /** 安装类加载监听。应在 SystemUI 的 onPackageLoaded 里调用一次。 */
    fun install(module: XposedModule, baseLoader: ClassLoader) {
        if (installed) return
        installed = true

        runCatching {
            val method = ClassLoader::class.java.getDeclaredMethod(
                "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            module.deoptimize(method)
            module.hook(method).intercept(LoadClassHooker())
            HookLogger.i(TAG, "类加载监听已安装: ClassLoader.loadClass")
        }.onFailure {
            HookLogger.e(TAG, "类加载监听安装失败", it)
        }

        // 插件框架兜底：拿到插件 ClassLoader 后主动补捞（类若已在监听安装前加载过，只能靠这里）
        runCatching {
            val factory = baseLoader.loadClass(PLUGIN_FACTORY_CLASS)
            val create = factory.declaredMethods.find {
                it.name == "createClassLoader" && it.parameterTypes.isEmpty()
            }
            if (create != null) {
                module.deoptimize(create)
                module.hook(create).intercept(PluginLoaderHooker())
                HookLogger.i(TAG, "插件 ClassLoader 监听已安装: createClassLoader")
            } else {
                HookLogger.w(TAG, "$PLUGIN_FACTORY_CLASS 未找到 createClassLoader")
            }
        }.onFailure {
            HookLogger.w(TAG, "插件 ClassLoader 监听安装失败: ${it.message}")
        }

        runCatching {
            val wrapper = baseLoader.loadClass(PLUGIN_CONTEXT_WRAPPER_CLASS)
            val getLoader = wrapper.declaredMethods.find {
                it.name == "getClassLoader" && it.parameterTypes.isEmpty()
            }
            if (getLoader != null) {
                module.deoptimize(getLoader)
                module.hook(getLoader).intercept(PluginLoaderHooker())
                HookLogger.i(TAG, "插件 ClassLoader 监听已安装: getClassLoader")
            }
        }.onFailure {
            HookLogger.w(TAG, "PluginContextWrapper.getClassLoader 监听安装失败: ${it.message}")
        }
    }

    /** 监听某个类名的加载；命中后回调真实 Class。 */
    fun watch(className: String, callback: (Class<*>) -> Unit) {
        synchronized(listeners) {
            listeners.getOrPut(className) { mutableListOf() }.add(callback)
        }
    }

    private fun dispatch(clazz: Class<*>) {
        val name = clazz.name
        val callbacks = synchronized(listeners) { listeners[name]?.toList() } ?: return
        val key = name + "#" + System.identityHashCode(clazz)
        synchronized(delivered) { if (!delivered.add(key)) return }

        if (reentrant.get() == true) return
        reentrant.set(true)
        try {
            callbacks.forEach { cb ->
                runCatching { cb(clazz) }.onFailure {
                    HookLogger.e(TAG, "类加载回调失败: $name", it)
                }
            }
        } finally {
            reentrant.set(false)
        }
    }

    /** 主动让目标类走一次 loadClass，用于补捞「监听装上之前就已加载」的类。 */
    private fun pull(loader: ClassLoader) {
        val names = synchronized(listeners) { listeners.keys.toList() }
        names.forEach { name ->
            runCatching { loader.loadClass(name) }
        }
    }

    private class LoadClassHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (result is Class<*> && reentrant.get() != true) dispatch(result)
            return result
        }
    }

    private class PluginLoaderHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (result is ClassLoader) pull(result)
            return result
        }
    }
}
