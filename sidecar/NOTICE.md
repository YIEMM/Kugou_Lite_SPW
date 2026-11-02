# sidecar — 上游来源与许可证声明

本目录整体按 **AGPL-3.0** 授权，见 [`LICENSE`](LICENSE)。

---

## 上游来源

| 项目 | 内容 |
|---|---|
| 仓库 | [zzyoxml/md3Music](https://github.com/zzyoxml/md3Music) |
| 描述 | 酷狗概念版 第三方音乐播放器 Flutter + Rust |
| 分支 | `rust-local-force` |
| 摄取路径 | `kugou_api_server/rust/` |
| 摄取日期 | 2026-11-02 |
| 上游许可证 | AGPL-3.0 |

原文声明（引自上游 `LICENSE`）：

> GNU AFFERO GENERAL PUBLIC LICENSE
> Version 3, 19 November 2007
> Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>

---

## 修改记录（AGPL-3.0 §5 要求）

AGPL-3.0 第 5 条要求分发修改版时必须「载明修改」。

| 日期 | 修改内容 | 修改人 |
|---|---|---|
| 2026-11-02 | 首次摄取上游 `kugou_api_server/rust/`，**源码内容零修改** | spw-kugou-lite |
| 2026-11-02 | 新增本文件与 `LICENSE`（AGPL-3.0 全文） | spw-kugou-lite |

**当前状态：源码逻辑与上游一致，未做任何功能修改。**

> 修改本目录代码时，**必须在上面表格追加一行**，写明日期、改了什么、为什么改。

---

## 目录结构

```
sidecar/
├── LICENSE                 AGPL-3.0 全文（本目录适用）
├── NOTICE.md               本文件
└── rust/                   上游 kugou_api_server/rust/ 原样摄取
    ├── Cargo.toml
    ├── Cargo.lock
    ├── build_desktop.ps1   上游自带：Windows 桌面构建脚本
    ├── build_android.sh    上游自带：Android 交叉编译脚本
    ├── src/                61 个 Rust 源文件
    ├── tests/              上游测试
    └── vendor/tiny_http    上游 patch 的 HTTP 库
```

---

## 对外接口（C FFI）

编译产物 `kugou_server.dll` 导出四个 C ABI 函数（`#[no_mangle] extern "C"`）：

```c
int  start_server(int port, const char* data_dir);  // 返回实际端口，0=失败
void stop_server(void);
int  is_server_running(void);
int  get_server_port(void);
```

- `port == 0` 表示随机选端口（被占用则换下一个，最多 10 次）
- `data_dir` 用于持久化 `device_info.json`（设备指纹，需跨重启稳定）

Kotlin 侧通过 `System.load` + 反射/JNI 绑定调用这四个函数，
**不依赖任何外部进程**。

---

## 为什么用 Rust 版替代原 Node 版

项目早期用的是同作者思路的 Node 版（[MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)，
pkg 打包的 `app_win.exe`，32.78 MB）。切换到 Rust 版的原因：

| 维度 | Node 版 | Rust 版 |
|---|---|---|
| 体积 | 32.78 MB | **3.98 MB**（1:8.2） |
| 形态 | 外部进程 | **进程内动态库** |
| HTTPS 证书 | 依赖宿主 jlink `cacerts`，**实测 PKIX 失败** | **自带 webpki-roots** |
| 杀软误报 | pkg 打包 Node，常被报毒 | 原生 dll |
| 额外运行时 | 无（已打包） | `VCRUNTIME140.dll`（Win10+ 自带） |

切换到 Rust 后，插件不再需要「下载 32MB 边车」这一步，
也因此彻底绕开了宿主运行时缺根证书导致的下载失败问题。

---

## 许可证合规要点

1. **不可改为更宽松的许可**。本目录代码衍生于 AGPL-3.0 作品，
   分发时必须继续以 AGPL-3.0 授权。
2. **必须提供完整对应源码**。若你分发了包含 `kugou_server.dll` 的插件包，
   必须同时提供（或书面承诺提供）本目录的完整源码。
3. **AGPL §13：网络交互**。若修改后的版本通过网络提供服务，
   必须向用户提供获取源码的途径。本项目当前是**本地进程内**调用，
   但仍按最严格解释准备源码分发。
4. **保留版权声明**。不得移除上游的版权声明与许可文本。

---

## 数据来源与免责

上游项目 `DISCLAIMER.md` 的要点同样适用：

- 非酷狗官方软件，无任何官方授权或关联。
- 不存储、不传播音频文件；仅调用公开接口在本地展示数据。
- 所有音乐、歌词、封面等内容版权归原平台与权利人所有。
- 仅供个人学习与技术研究，禁止商业用途与批量抓取。
- 接口可能随时失效，不提供可用性保证。
