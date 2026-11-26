# 本模块以 library 形态并入词岛主工程，最终不单独出 APK。
# 若主工程开启 R8，打轴器的模型类与 Composable 入口需保留。
-keep class com.liuxiansheng.lyricon.axis.core.** { *; }
-keep class com.liuxiansheng.lyricon.axis.ui.** { *; }
