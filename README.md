# 词岛 Chidao

HyperOS 上的超级岛歌词 + 状态栏歌词模块。基于 LSPosed（libxposed API）注入 SystemUI，把正在播放的歌词写进小米超级岛，或者铺在状态栏上滚动；不修改系统分区，不做常驻前台服务。

- 作者：liuxiansheng（刘先生）
- 包名：`io.github.liuxiansheng12369.chidao`（2.5.3 起；旧的 `com.liuxiansheng.chidao` 不再发布）
- 许可：GPL-3.0（上游组件另有协议，见 `THIRD-PARTY-NOTICES.md`）

## 功能

- 超级岛歌词：逐字高亮、滚动对齐、动态宽度、分离歌词模式、暂停保活
- 状态栏歌词：滚动/逐字、彩虹歌词、封面取色、图标样式、动态宽度、锁屏隐藏
- 状态栏歌词「词岛卡片」控制面板：点状态栏歌词弹出，含播放控制（上一首/暂停/下一首/进度）与快速设置（显示内容、字号、彩虹、避让超级岛）——另有经典卡片、下拉媒体卡两种样式可选
- 歌词源：在线源 + 自建源仓库，支持应用过滤
- 歌词打轴：人工打轴器，随同一个 APK 发布
- 多套主题：纯净蓝 / 唱片机 / 晨雾概览 / 深空仪表

## 框架要求

| 项 | 要求 |
| --- | --- |
| 系统 | Android 13+（minSdk 33），小米澎湃 OS / HyperOS |
| 框架 | **LSPosed 2.x 或 Vector 2.1+**（实现 libxposed API 101/102 的框架） |
| 模块元数据 | `minApiVersion=101`、`targetApiVersion=102`、`staticScope=true` |
| 作用域（包内固定） | `com.android.systemui`、`miui.systemui.plugin` |
| 架构 | arm64-v8a |

> 仅提供旧版 XposedBridge API 的框架（例如 LSPosed 1.9.2 及更早）无法加载本模块，会提示 API 版本过低。

## 安装

1. 手机已 root（Magisk / KernelSU / APatch + Zygisk），装好 LSPosed 2.x 或 Vector
2. 安装本仓库 Release 里的 APK
3. 打开 LSPosed / Vector → 模块 → 启用「词岛」
4. 作用域由 `staticScope` 固定在 SystemUI，不需要手动勾选其他应用
5. 重启一次系统界面（SystemUI），或在管理器里点「重启系统界面」

调试时可 `adb install -r` 覆盖安装；升级不需要卸载，测试版与正式版同一签名。

## 已知限制

- Xposed / SystemUI 侧的代码改动，必须重启一次系统界面才会加载新模块
- 状态栏歌词「显示内容」选「不显示」后，状态栏没有歌词可点，控制面板入口随之消失，需从 App 首页「状态栏歌词」入口改回来
- 面板上的快速设置通过定向广播交给 App 进程写设置（SystemUI 进程只有 prefs 只读权限）
- 首次使用需要在 App 内开启对应开关，模块本身不强制任何功能

## 版本号规则

版本号由根 `build.gradle.kts` 在构建时自动进位，不要手改：

- 正式版：`2.2.0` → 下一次正式打包 `2.3.0`
- 测试版：正式版号 + 一位小数，`2.3.0` → `2.3.1`、`2.3.2`……
- `versionCode` 每次打包 +1；号只在构建成功后写回文件，配置阶段和失败构建都不会涨号

## 构建

本地：

```powershell
# 测试版（debug 变体，签正式签名，可覆盖安装）
.\gradlew.bat :lyricon:assembleStandardDebug

# 正式版
.\gradlew.bat :lyricon:assembleStandardRelease
```

产物统一落在 `build/all-apks/<buildType>/词岛-<版本>-<buildType>[-abi].apk`。

CI：`.github/workflows/release.yml`，手动触发，构建 `assembleStandardRelease` 并创建 GitHub Release（附 APK）。

签名密钥**不随仓库发布**（`.gitignore` 已排除 `*.jks`）。本地构建把口令写进 `local.properties`（不入库）：

```
RELEASE_STORE_FILE=release-v3.jks
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=liuxiansheng
RELEASE_KEY_PASSWORD=***
```

CI 构建从仓库 Secrets 读取：`RELEASE_JKS_BASE64`（jks 的 base64）、`RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。

四个参数齐备且密钥文件在位时才用这份签名；缺参数时回退到系统默认 debug 签名——所以别人 clone 下来也能编出可安装的包，但签名不是作者的，覆盖不了官方发布版。

## 目录结构

```
lyricon/      APK 壳（applicationId、打包、签名）
app/          设置界面、打轴器、主题（library）
xposed/       注入 SystemUI 的 hook 层（超级岛 / 状态栏歌词 / 控制面板）
bridge/       App ↔ SystemUI 的广播与设置同步
common/       libxposed API 封装、日志、常量
lyric/        歌词模型、渲染、歌词源桥
axis/         打轴器 library
opencc-lite/  简繁转换
```

## 隐私与数据

模块只读取本机当前播放的媒体信息（歌名、歌手、封面、进度）用于渲染，不采集设备标识、不读取聊天、通讯录、位置等信息。

- 听歌记录、打轴数据、全部设置项存在本机 App 私有目录，不上传
- 在线歌词由你在「歌词源」里主动启用的源发起请求，请求内容只有歌曲标识
- 模块自身没有账号体系，不需要登录

## 免责声明

- 使用本模块需要 root 与 Xposed 框架，属于改动运行时的系统行为，风险自担；模块不写系统分区、不修改系统 APK
- 歌词来自公开音乐平台的媒体元数据与用户自建歌词源，模块不内置任何版权歌词内容，歌词版权归原权利人
- 作者不对因使用本模块造成的系统异常、数据丢失或账号问题负责

## 相关文档

- `docs/状态栏歌词控制面板-词岛卡片.md`：控制面板设计与改动记录
- `docs/LSPosed上架-提交说明.md`：模块仓库上架步骤与文案
- `THIRD-PARTY-NOTICES.md`：上游来源与许可
- `更新日志.md`：逐版变更
