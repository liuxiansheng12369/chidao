## 词岛 {{VERSION}}

- **versionCode**：{{VERSION_CODE}}
- **体积**：约 23 MB（arm64-v8a）
- **要求**：Android 13+（HyperOS），**LSPosed 2.x 或 Vector 2.1+**（libxposed API 101/102）

### 增加

-

### 修改

-

### 修复

-

### 安装

1. 下载下方 APK，点击安装或 `adb install -r`
2. 打开 LSPosed / Vector → 模块 → 启用「词岛」（作用域已由模块固定为 SystemUI，无需手动勾选）
3. 重启一次系统界面

### 说明

- Xposed / SystemUI 侧代码有变更的版本，必须重启系统界面才加载
- 与上一版同一签名，可直接覆盖安装，设置不丢
- 状态栏歌词「显示内容」选「不显示」后控制面板入口会消失，从 App 首页「状态栏歌词」改回
