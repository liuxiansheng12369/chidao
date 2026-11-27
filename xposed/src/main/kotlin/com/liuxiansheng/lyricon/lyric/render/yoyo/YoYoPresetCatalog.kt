/*
 * 词岛 · YoYo 换句动画预设表（移植自上游 lyric:view 的 yoyo 包）
 * 包名：com.liuxiansheng.chidao
 * Copyright 2026 liuxiansheng（刘先生）
 *
 * 上游 YoYoPresets 注册了 26 个预设，其中 "default" 与 "fade_out_left_fade_in_right"
 * 是同一个实现，这里去重后保留 25 个。
 *
 * 词岛的换句是"双行布局"：换行瞬间新句已经在主行、内容不用再换，
 * 所以只用预设里的「入场」那一半；「出场」那一半在原地换内容的单行结构里才有意义。
 * 因此看起来"不同"的预设，只要入场那一半完全相同（同技法 + 同时长 + 同插值器），
 * 在词岛播出来就是一模一样的动画，按这个口径再去重一次，每组保留上游最早的那一条：
 *   FadeInUp 450 + FastOutSlowIn  → 保留「上淡出·上淡入」(fade_out_up_fade_in_up)
 *   ZoomIn 400 + FastOutSlowIn     → 保留「左淡出·放大入场」(fade_out_left_zoom_in)
 *   LandingSoft 700 + FastOutSlowIn→ 保留「左淡出·落地弹入」(fade_out_left_landing)
 *   ZoomIn 450 + FastOutSlowIn     → 保留「左滑出·放大入场」(slide_out_left_zoom_in)
 * 共去掉 10 条，剩 15 条。
 *
 * 编号固定为上游原编号（100..124 去掉重复位），不重新排序，
 * 已经选过动画的用户不会因为删条目而跳到别的效果。
 */

package com.liuxiansheng.lyricon.lyric.render.yoyo

internal object YoYoPresetCatalog {

    /** 设置项里的编号起点：100 以上都是 YoYo 预设 */
    const val BASE = 100

    data class Entry(val value: Int, val id: String, val label: String)

    private val entries: List<Entry> = listOf(
        Entry(100, "default", "左淡出·右淡入"),
        Entry(101, "fade_out_fade_in", "淡出·淡入"),
        Entry(102, "fade_out_up_fade_in_up", "上淡出·上淡入"),
        Entry(103, "fade_out_down_fade_in_down", "下淡出·下淡入"),
        Entry(105, "fade_out_left_zoom_in", "左淡出·放大入场"),
        Entry(106, "fade_out_left_landing", "左淡出·落地弹入"),
        Entry(107, "fade_out_right_fade_in_left", "右淡出·左淡入"),
        Entry(111, "slide_out_left_slide_in_right", "左滑出·右滑入"),
        Entry(113, "slide_out_left_zoom_in", "左滑出·放大入场"),
        Entry(115, "slide_out_right_slide_in_left", "右滑出·左滑入"),
        Entry(119, "flip_out_x_flip_in_x", "横轴翻转"),
        Entry(120, "flip_out_y_flip_in_y", "纵轴翻转"),
        Entry(121, "rotate_out_rotate_in", "旋转切换"),
        Entry(123, "fade_out_left_zoom_in_right", "左淡出·右放大入场"),
        Entry(124, "fade_out_right_zoom_in_left", "右淡出·左放大入场")
    )

    fun all(): List<Entry> = entries

    fun idForValue(value: Int): String? =
        entries.firstOrNull { it.value == value }?.id

    fun labelForValue(value: Int): String? =
        entries.firstOrNull { it.value == value }?.label
}
