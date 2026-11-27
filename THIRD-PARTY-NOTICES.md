# 第三方代码声明（THIRD-PARTY NOTICES）

**词岛 Lyricon V3**（`com.liuxiansheng.lyricon.v3`）
本工程以 **GNU General Public License v3.0** 发布，全文见仓库根目录 `LICENSE`。

> 说明：上游 HyperLyric 的 README 明确写「GNU General Public License v3.0」，其 `LICENSE` 为 v3 全文且未声明「or any later version」。本工程不代其扩展为「or later」，统一按 **GPL-3.0** 发布。

本工程包含下列第三方开源代码或其衍生实现。原始版权归各原作者所有，本工程仅按各自许可证条款使用。

---

## 1. HyperLyric

| 项 | 内容 |
|---|---|
| 作者 | limczhh |
| 仓库 | https://github.com/limczhh/HyperLyric |
| 许可证 | **GNU GPL v3.0**（仓库内 `LICENSE` 原文核实） |
| 本工程用途 | 超级岛歌词注入、呈现协调、锁屏重建回填、多岛切换、取色与宽度适配、行视图与逐字渲染、动画 |
| 说明 | 该仓库源码文件头标注为 Apache-2.0，系其分叉自 Lyricon 时未同步修改；**以仓库 `LICENSE` 的 GPL-3.0 为准**，本工程按 GPL-3.0 履行义务。 |

GPL-3.0 要求：分发本工程（含衍生作品）时须提供完整对应源码，并保留本声明与原作者版权声明。本仓库即完整源码。

---

## 2. HyperIsland

| 项 | 内容 |
|---|---|
| 作者 | 1812z |
| 仓库 | https://github.com/1812z/HyperIsland |
| 许可证 | **MIT License** |
| 本工程用途 | 岛背景绘制、实时模糊、液态 / 柔光玻璃材质、描边、HDR 高光钩子 |

MIT 要求：保留原作者版权声明与许可声明。原文：

```
MIT License

Copyright (c) 2026 1812z

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 3. Lyricon（tomakino）

| 项 | 内容 |
|---|---|
| 作者 | Proify / Tomakino |
| 仓库 | https://github.com/tomakino/lyricon |
| 许可证 | **Apache License 2.0** |
| 本工程用途 | 歌词数据模型、LRC / ELRC 解析思路、行视图结构、Bridge / Provider 对接、媒体信息采集 |

Apache-2.0 要求：保留版权声明与许可声明，若修改过文件须注明。本工程相关文件头已逐条注明来源。

---

## 4. 运行期依赖（按各自仓库协议）

| 组件 | 用途 | 仓库 |
|---|---|---|
| Miuix KMP | 设置页 UI 框架 | https://github.com/kyantuto/miuix |
| libxposed / LSPosed | Xposed 模块 API 与框架 | https://github.com/LSPosed/LSPosed |
| ColorOS Live Lyrics Providers | 上游歌词源推送协议参考 | 见其各自仓库 |

三者均为编译期 / 运行期依赖，其许可证以各自仓库声明为准，本工程不主张对其拥有任何权利。

---

## 5. 自研部分

除上述第三方代码及其衍生实现外，本工程其余代码（画布绘制、25 种歌词动画、皮肤系统、设置页、字体库、倒计时、歌词源管理器、逐字进度控制器等）由 **liuxiansheng（刘先生）** 独立编写，版权归作者所有。

---

## 6. 分发义务清单

发布 APK 时须同时满足：

- [ ] 保留本文件，或以「开源许可」页面对外展示同等信息（本工程设置页 → 关于词岛 → 致谢 已实现）
- [ ] 提供完整对应源码（GPL-3.0 要求），或按 GPL-3.0 第 6 条提供书面要约
- [ ] 不删除任何源文件中的原作者版权声明
- [ ] 不把 Apache-2.0 / MIT 的代码改标成「GPL 原作者授权」

---

最后更新：2026-11-11
