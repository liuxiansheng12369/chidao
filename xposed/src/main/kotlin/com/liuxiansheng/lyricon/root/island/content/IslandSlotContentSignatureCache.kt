/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


package com.liuxiansheng.lyricon.hyper.root.island.content

import android.view.View
import java.util.WeakHashMap

/** 内容签名缓存（V3 自研）：按视图弱引用保存最近一次提交的内容签名。 */
internal object IslandSlotContentSignatureCache {
    private val signatures = WeakHashMap<View, String>()

    fun get(view: View): String? = signatures[view]

    fun set(view: View, signature: String) {
        signatures[view] = signature
    }

    fun invalidate(view: View? = null) {
        if (view == null) {
            synchronized(signatures) { signatures.clear() }
        } else {
            synchronized(signatures) { signatures.remove(view) }
        }
    }
}
