# LSPosed 模块仓库上架 · 提交说明

更新日期：2026-11-26

## 现状

- 原版 LSPosed 于 2024-01 归档停更，2026 年恢复维护并升到 2.x（支持 libxposed API 101/102）；现役分支 Vector 2.1+ 同样支持
- 官方模块仓库 `https://modules.lsposed.org` 一直在运营，目前约 1473 个模块，每天有新模块上架
- 仓库页面**由 GitHub Release 自动抓取生成**，所以真正的发布载体是「GitHub 仓库 + Release 里的 APK」

参考：<https://modules.lsposed.org/>

## 前置条件

1. 公开 GitHub 仓库（源码 + `README.md`），仓库名 `chidao`（已定）
2. 每次发版在 GitHub 上建 Release，**必须附 APK**（release 签名版，不是 debug 变体）
3. 模块 APK 内元数据齐全：本仓库已具备
   - `META-INF/xposed/module.prop`：`minApiVersion=101`、`targetApiVersion=102`、`staticScope=true`
   - `META-INF/xposed/scope.list`：`com.android.systemui`、`miui.systemui.plugin`
   - `META-INF/xposed/java_init.list`：`com.liuxiansheng.lyricon.xposed.ModuleEntry`
   - `AndroidManifest.xml` 里有 `android:label`（词岛）与 `android:description`
4. 版本号单调递增（本仓库由 `build.gradle.kts` 自动进位，正式版 2.2.0 → 2.3.0）
5. 包名保持 `com.liuxiansheng.chidao` 不变

## 提交流程

1. 把主项目推到 GitHub（`git init` → 首次提交 → push）
   - `.gitignore` 已排除 `build/`、`*.apk`、`.version_state.txt`
   - 是否提交 `release-v3.jks` 由你决定：公开仓库建议移出，改用 Secrets（见下）
2. 打第一个 Release：Actions → **Release APK** → Run workflow（或在本地 `assembleStandardRelease` 后手动上传 APK）
3. 打开 `https://modules.lsposed.org`，点 **Submit Module**，用 GitHub 账号授权登录，填仓库地址提交
4. 等审核（只查是否合规，不做代码审计），通过后模块页生成：`modules.lsposed.org/module/com.liuxiansheng.chidao`
5. 之后每发一个 Release，页面自动更新（不需要重新提交）

## 提交材料清单

| 项 | 词岛填写内容 |
| --- | --- |
| 模块名 | 词岛（Chidao） |
| 简介（一句话） | HyperOS 超级岛歌词与状态栏歌词模块 |
| 包名 | `com.liuxiansheng.chidao` |
| 作用域 | SystemUI（模块内固定，用户无需勾选） |
| 系统要求 | Android 13+，小米澎湃 OS / HyperOS |
| 框架要求 | **LSPosed 2.x 或 Vector 2.1+**（libxposed API 101/102） |
| 架构 | arm64-v8a |
| 图标 | `docs/图标-512.png`（512×512 PNG，已备好） |
| 源码 | GitHub 仓库地址 |
| 支持 | 仓库 Issues |
| 许可 | GPL-3.0 |

## 提交文案（可直接粘）

> 词岛（Chidao）把正在播放的歌词写进小米超级岛，也可以铺在状态栏上滚动。支持逐字高亮、彩虹歌词、封面取色、动态宽度、分离歌词模式；状态栏歌词点一下会弹出「词岛卡片」控制面板，可切歌、拖进度、快速改显示内容与字号。另含歌词源商店与人工打轴器。
>
> 需要 Android 13+ 的小米/澎湃 OS 设备，框架要求 LSPosed 2.x 或 Vector 2.1+（libxposed API 101/102）；仅支持旧版 XposedBridge API 的框架无法加载。
>
> 安装后重启一次系统界面即可生效。

## 审核与风险注意

- **签名密钥**：公开仓库若包含 `release-v3.jks` 与明文密码，任何人都能签出可覆盖安装的包。建议仓库只放源码，CI 通过 Secrets 注入：
  `RELEASE_JKS_BASE64`（jks 的 base64）、`RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`
- **歌词源**：仓库审核对版权内容敏感。README 已声明「不内置任何版权歌词、源由用户自建」，提交时不要写「免费听歌/破解」之类表述
- **API 版本**：页面与简介都要写清 LSPosed 2.x / Vector 2.1+，否则 1.9.x 用户装完会报「API 版本过低」
- **APK 体积**：arm64-v8a 单包约 23 MB，正常；不要附带多余 dex/资源
- **debug 包**：仓库只收正式签名包，`*-debug.apk` 不要上传到 Release

## 发版检查清单

- [ ] `build.gradle.kts` 版本号由构建自动进位，未手改
- [ ] `assembleStandardRelease` 构建通过，APK 在 `build/all-apks/release/`
- [ ] 真机覆盖安装 + 重启系统界面，功能自检
- [ ] Release 标题 `词岛 x.x.x`，正文按「增加/修改/修复」写，附 APK
- [ ] `README.md` 的版本相关描述无过时内容
- [ ] 提交模块仓库后 24 小时内看页面是否已抓取到新版本
