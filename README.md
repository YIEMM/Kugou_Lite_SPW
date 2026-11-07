# Kugou Lite

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Sidecar: AGPL-3.0](https://img.shields.io/badge/Sidecar-AGPL--3.0-orange.svg)](sidecar/LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Windows-lightgrey.svg)]()
[![SPW](https://img.shields.io/badge/Salt%20Player-1.19.3-green.svg)]()

把**酷狗音乐**的曲库接入 [Salt Player for Windows](https://github.com/Moriafly/SaltPlayerSource)：
在宿主**导航内**用**原生 Salt 控件**渲染 MD3Music 式发现页（私人 FM / 每日推荐 / 刷歌推荐 / 新歌速递 / 新碟上架），以及搜索、歌单、榜单、歌手、专辑，点歌即在 SPW 里播放。

> 非官方第三方插件，与酷狗音乐、Salt Player 官方均无关联。仅供学习研究，请支持正版。

---

## 特性

| 能力 | 状态 |
|---|---|
| _(图例：✅ 已真机验证 ／ 🧪 代码完成待真机验证)_ | |
| 标题栏入口（原生 caption 按钮） | ✅ |
| 导航内页面（原生 Salt 组件） | ✅ |
| **发现页（1:1 复刻 MD3Music）** | ✅ 已真机验证 · 5 区块 + 快捷磁贴行 + 自动列数瀑布流 + 编辑面板 |
| 私人 FM（4 档位 / 游标 / 去重 / 补货） | ✅ |
| 搜索（支持回车） | ✅ |
| **搜索升级（热搜/建议/综合Tab）** | 🧪 待真机 |
| **歌单广场（分类Tab/标签/加载更多）** | 🧪 待真机 |
| **播放历史（按日期分组）** | 🧪 待真机 |
| **歌手页（热门/专辑/信息 + 关注）** | 🧪 待真机 |
| **每日推荐（今日/风格/历史）** | 🧪 待真机 |
| **歌单管理（新建/删除）** | 🧪 待真机（重命名上游 30203 不支持；加删歌 UI 未接） |
| 歌单 / 榜单 / 每日推荐 / 歌手 / 专辑 | ✅ |
| 收藏到「我喜欢」（♡ 一键切换） | ✅ |
| 扫码登录（token 持久化） | ✅ |
| 取流（音质降级链 + 收藏页解锁） | ✅ |
| 逐字歌词 + 中文译文 + 封面内嵌 | ✅ |
| 播放队列 / 切歌自动补流 | ✅ |
| 设置（音质 / 缓存 / 登出） | ✅ |
| 主题（浅色/深色跟随宿主） | ✅ |

---

## 架构

```
┌─────────────────────────────────────────────┐
│ Salt Player for Windows (GraalVM 25 jlink)  │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │ Kugou Lite 插件 (Kotlin, MIT)         │  │
│  │  · 运行时字节码解析 → 页面注入         │  │
│  │  · 原生 Salt 控件渲染                  │  │
│  │  · KugouApi (HTTP 客户端)              │  │
│  └──────────────┬────────────────────────┘  │
│                 │ JNI (C ABI)               │
│  ┌──────────────▼────────────────────────┐  │
│  │ kugou_bridge.dll (C, MIT)             │  │
│  │  · 转发 JNI 调用到上游 C 函数          │  │
│  └──────────────┬────────────────────────┘  │
│                 │ LoadLibrary + GetProcAddress│
│  ┌──────────────▼────────────────────────┐  │
│  │ kugou_server.dll (Rust, AGPL-3.0)     │  │
│  │  · tiny_http 本地服务                 │  │
│  │  · rustls + webpki-roots 自带根证书   │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

**为什么用 Rust 而不是 Node**：早期版本用 pkg 打包的 Node 服务（`app_win.exe`，32.78 MB）。
改成 Rust 动态库后体积降到 **3.98 MB**（1/8），且**自带根证书**——宿主 jlink 运行时的
`cacerts` 不全会导致 JVM 侧 HTTPS 报 `PKIX path building failed`，Rust 侧完全不受影响。

### 许可证分层

| 范围 | 许可证 |
|---|---|
| 仓库根目录（Kotlin 插件、构建脚本、文档） | **MIT** |
| `sidecar/`（Rust 服务 + JNI 桥接） | **AGPL-3.0** |

两部分通过**动态库 + 稳定 C ABI** 交互。详见 [`NOTICE.md`](NOTICE.md) 与
[`sidecar/NOTICE.md`](sidecar/NOTICE.md)。

> `sidecar/rust/` 派生自 [zzyoxml/md3Music](https://github.com/zzyoxml/md3Music)（AGPL-3.0），
> 源码零修改，保留上游许可。

---

## 构建

### 前置要求

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | 25 | 编译 Kotlin 插件 |
| Gradle | 9.8（用 `gradlew` 即可） | 构建 |
| Rust | ≥ 1.85（stable-msvc） | 编译边车 |
| Visual Studio | 2022+，含「使用 C++ 的桌面开发」 | 编译 JNI 桥接层 |

### 1. 构建原生组件

```powershell
cd sidecar
.\build_native.ps1
```

产出到 `src/main/resources/native/`：

```
kugou_server.dll    3.98 MB   Rust 边车（AGPL-3.0）
kugou_bridge.dll     132 KB   JNI 桥接层（MIT）
```

> **首次构建 Rust 可能遇到两个环境问题**（脚本里已处理）：
> - `cargo` 装到 `C:\Users\<u>\.cargo` 被安全策略拒绝 → 脚本改用工作区目录
> - MSVC 链接器报 `LNK1104`（`%TEMP%` 不可写）→ 脚本重定向 `TEMP`

### 2. 构建插件

```powershell
$env:JAVA_HOME='<你的 JDK 25>'
$env:GRADLE_USER_HOME="$PWD\.gradle-home"
.\gradlew.bat test plugin
```

产出 `build/libs/plugin-cn.spw.kugou.lite-<version>.spmod`（约 2.2 MB）。

> Gradle 的 `checkNativeLibs` 任务会校验两个 dll 是否存在，
> 缺失时给出明确提示而不是打出残包。

### 3. 安装

```powershell
$dst = "$env:APPDATA\Salt Player for Windows\workshop\plugins"
Copy-Item build\libs\*.spmod $dst -Force
```

重启 Salt Player，标题栏右侧会出现**频谱图标**，点击进入。

---

## 使用

1. 点击标题栏频谱图标进入 Kugou Lite 页面
2. 首次使用需在「账户」分组扫码登录（token 会持久化，之后免扫码）
3. 「搜索歌曲」输入歌名/歌手回车
4. 点击结果即可试听（P4 接入播放队列）

---

## 技术要点

### 运行时解析，不硬编码混淆名

宿主 UI 类被混淆成 Unicode 码点名的假 `androidx/compose/ui/` 包。插件用**两遍字节码解析**：

```
原始 Kotlin 名 →(LDC 源码标记)→ 声明类+方法+描述符 →(Methodref)→ 调用方 inClass
```

标记形如 `com.xuncorp.voxzen.ui.main.titlebar.MainTitleBar (MainTitleBar.kt:78)`。
不依赖任何版本号表，宿主升级后自动适配。工具在 [`tools/`](tools/)。

### 原生控件

所有可见控件都是宿主自己的 Salt 组件（`Text` / `Card` / `Item` / `ItemOuterTitle` /
`BasicCaptionButton` / `BasicScreenColumn` / `BasicScreenBackButton`），
因此明暗主题、间距、字号与宿主二级页面完全一致。

### 已踩过的坑

| 坑 | 现象 | 结论 |
|---|---|---|
| `fillMaxSize()` | 页面不能滚动 | 外框自带滚动容器，内容高度要由内容决定 |
| 外层加水平 padding | 分组标题比卡片文字多缩进 16px | `ItemOuterTitle` 自带 16dp、`Card` 不带，留白要下放到组件 |
| 负 padding 抵消 | `Padding must be non-negative`，宿主 `PLUGIN_FATAL` 终止进程 | 只能调结构，不能抵消 |
| caption 图标不居中 | 图标偏上 7px、小一圈 | 必须 `align(Alignment.Center)` 并填满 `size(18.dp)` |
| kapt 与 `\uXXXX` | 构建报「非法的 Unicode 逃逸」 | kapt 在**词法阶段**处理转义，注释里也不能出现 |
| 取流 `errcode 20028` | 所有曲目取不到 URL | 服务端行为指纹反爬，**必须先登录** |

完整记录见 [`docs/verify-P2.md`](docs/verify-P2.md) 与
[设计规格](docs/superpowers/specs/2026-11-01-kugou-lite-spw-design.md)。

---

## 目录结构

```
Kugou_Lite_SPW/
├── LICENSE                    MIT（主仓）
├── NOTICE.md                  第三方组件与许可合规
├── build.gradle.kts
├── docs/                      设计规格、实现计划、验收记录
├── sidecar/                   AGPL-3.0 子目录
│   ├── LICENSE                AGPL-3.0 全文
│   ├── NOTICE.md              上游来源与修改记录
│   ├── build_native.ps1       构建两个 dll
│   ├── bridge/                JNI 桥接层（MIT）
│   └── rust/                  上游 Rust 服务（AGPL-3.0，零修改）
├── src/
│   ├── main/kotlin/cn/spw/kugou/lite/
│   │   ├── host/              宿主 ABI、页面注入、导航桥
│   │   ├── sidecar/           原生库加载、API 客户端、登录态
│   │   ├── ui/                原生控件封装与页面
│   │   └── diag/              诊断日志
│   └── test/kotlin/           单元测试（40 个）
└── tools/                     宿主字节码探测脚本
```

---

## 诊断

日志位置：

```
%APPDATA%\Salt Player for Windows\workshop\data\cn.spw.kugou.lite\logs\kugou-lite.log
```

反馈问题时请附上日志。关键前缀：`[host]` `[inject]` `[nav]` `[sidecar]` `[native]` `[api]`。

---

## 致谢

- [Salt Player](https://github.com/Moriafly/SaltPlayerSource) — 宿主播放器
- [zzyoxml/md3Music](https://github.com/zzyoxml/md3Music) — Rust 酷狗 API 服务（AGPL-3.0）
- [MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi) — 早期 Node 版参考

## 许可证

- 主仓：[MIT](LICENSE)
- `sidecar/`：[AGPL-3.0](sidecar/LICENSE)
