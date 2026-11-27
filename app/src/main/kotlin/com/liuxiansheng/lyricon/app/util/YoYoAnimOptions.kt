package com.liuxiansheng.lyricon.app.util

/**
 * YoYo 换句动画预设（移植上游 yoyo 包），编号沿用上游 100..124。
 * 顺序与编号必须与 xposed 侧 `YoYoPresetCatalog` 完全一致。
 *
 * 词岛只播预设的「入场」半段，所以入场半段完全相同的几条在词岛是同一段动画，
 * 每组保留上游最早的一条（详见 `YoYoPresetCatalog` 头部说明），25 → 15。
 */
object YoYoAnimOptions {

    private val entries = listOf(
        100 to "左淡出·右淡入",
        101 to "淡出·淡入",
        102 to "上淡出·上淡入",
        103 to "下淡出·下淡入",
        105 to "左淡出·放大入场",
        106 to "左淡出·落地弹入",
        107 to "右淡出·左淡入",
        111 to "左滑出·右滑入",
        113 to "左滑出·放大入场",
        115 to "右滑出·左滑入",
        119 to "横轴翻转",
        120 to "纵轴翻转",
        121 to "旋转切换",
        123 to "左淡出·右放大入场",
        124 to "右淡出·左放大入场",
    )

    val options: List<Pair<String, String>> = entries.map { (_, label) -> label to "" }

    val values: List<Int> = entries.map { (value, _) -> value }
}
