/*
 * 本文件移植自 HyperIsland（作者 1812z）提交「柔光亮度自动压暗」(7254e586)，
 * 原项目：https://github.com/1812z/HyperIsland
 * 原版权归 1812z 所有，原项目以 MIT 授权。
 * 移植与修改：liuxiansheng，2026；本文件随词岛整体以 GPL-3.0 发布。
 */

package com.liuxiansheng.lyricon.hyper.root.island.upstream

/**
 * 场景级柔光亮度覆盖（临时值，不改各形态已保存的材质参数）。
 *
 * 白底场景（状态栏背景亮、图标深色）下系统柔光会整体发白，这里按配置把它压暗；
 * 其余场景返回 null，仍走材质里保存的原值，行为与此前一致。
 */
internal data class SoftGlassSceneBrightnessConfig(
    val enabled: Boolean = false,
    val whiteSceneBrightness: Long = DEFAULT_BRIGHTNESS,
) {
    fun softLightOverride(barLight: Boolean): Double? =
        if (enabled && barLight) brightness(whiteSceneBrightness).toDouble() else null

    companion object {
        const val ENABLED_KEY = "pref_soft_glass_scene_dim_enabled"
        const val BRIGHTNESS_KEY = "pref_soft_glass_scene_white_brightness"
        const val DEFAULT_BRIGHTNESS = -28L
        const val MIN_BRIGHTNESS = -50L
        const val MAX_BRIGHTNESS = 50L

        fun brightness(value: Long): Long = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)

        /** 读当前配置；键不存在时保持关闭，效果与旧版一致。 */
        fun read(): SoftGlassSceneBrightnessConfig = runCatching {
            SoftGlassSceneBrightnessConfig(
                enabled = UpstreamConfig.getBoolean(ENABLED_KEY, false),
                whiteSceneBrightness = brightness(
                    UpstreamConfig.getInt(BRIGHTNESS_KEY, DEFAULT_BRIGHTNESS.toInt()).toLong()
                ),
            )
        }.getOrDefault(SoftGlassSceneBrightnessConfig())
    }
}
