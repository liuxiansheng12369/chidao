/*
 * 本文件基于 HyperLyric（作者 limczhh）移植，原项目：
 * https://github.com/limczhh/HyperLyric
 * 原版权归 limczhh 所有，原项目以 GPL-3.0 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */


@file:Suppress("unused")

package com.liuxiansheng.lyricon.hyper.root.lyricon.provider

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * 提供者元数据封装类。
 *
 * 使用键值对存储提供者相关信息
 *
 * @property map 内部存储的键值对
 */
@Parcelize
@Serializable
class ProviderMetadata(
    private val map: Map<String, String?> = emptyMap()
) : Map<String, String?> by map, Parcelable

/**
 * 创建 [ProviderMetadata] 的简便方法。
 *
 * @param pairs 键值对数组
 * @return 对应的 [ProviderMetadata] 实例
 */
fun providerMetadataOf(vararg pairs: Pair<String, String?>): ProviderMetadata =
    ProviderMetadata(mapOf(*pairs))
