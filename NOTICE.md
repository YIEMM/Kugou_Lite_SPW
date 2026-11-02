# NOTICE — 第三方组件与许可证声明

本项目 **Kugou Lite**（Salt Player for Windows 插件）包含两部分，分别适用不同许可证。

---

## 1. 主仓：Kotlin 插件代码

| 项目 | 内容 |
|---|---|
| 范围 | 仓库根目录除 `sidecar/` 外的所有文件 |
| 许可证 | **MIT**，见 [`LICENSE`](LICENSE) |
| 版权 | 2026 spw-kugou-lite contributors |

包含：页面注入、宿主原生组件 ABI、UI 层、诊断日志、构建脚本、文档。

---

## 2. `sidecar/`：Rust 酷狗 API 服务

| 项目 | 内容 |
|---|---|
| 范围 | `sidecar/` 整目录 |
| 许可证 | **AGPL-3.0**，见 [`sidecar/LICENSE`](sidecar/LICENSE) |
| 上游项目 | [zzyoxml/md3Music](https://github.com/zzyoxml/md3Music) |
| 上游分支 | `rust-local-force` |
| 上游路径 | `kugou_api_server/rust/` |
| 上游许可证 | AGPL-3.0（见上游仓库 `LICENSE`） |

### 2.1 为什么是 AGPL-3.0

md3Music 采用 **GNU Affero General Public License v3.0**。这是强 copyleft 协议：
任何基于其代码的衍生作品，在**分发**（以及 AGPL §13 的**网络交互**情形）时，
必须以 AGPL-3.0 提供完整对应源码。

本项目对 `sidecar/rust/` 的 Rust 源码**按原许可保留**，不做任何许可降级。

### 2.2 我们做了哪些修改

相对上游 `rust-local-force` 分支的 `kugou_api_server/rust/`：

- **无逻辑修改**。源码内容保持上游原样，便于比对与上游同步。
- 仅新增本 `NOTICE.md` 与 `LICENSE`（AGPL-3.0 全文）。
- `target/`、`.git/` 等构建产物与版本控制目录不入库。

> 若后续修改了上游代码，**必须在本节逐条列出修改内容与日期**，
> 这是 AGPL-3.0 第 5 条（§5 "Modified Source Versions"）的强制要求。

### 2.3 上游同步方式

```powershell
# 拉取上游最新 rust/ 子目录（sparse checkout，避免下载整个 287MB 仓库）
cd sidecar
git remote add upstream https://github.com/zzyoxml/md3Music.git   # 仅首次
git fetch --depth 1 upstream rust-local-force
git checkout FETCH_HEAD -- kugou_api_server/rust/
```

同步后需重新核对上游 `LICENSE` 是否变化，并更新本文件的「上游分支」记录。

---

## 3. 第三方 Rust 依赖

`sidecar/rust/Cargo.lock` 锁定的第三方 crate 各自适用其自身许可证
（多为 MIT / Apache-2.0 双许可）。完整清单可用以下命令生成：

```powershell
cd sidecar/rust
cargo install cargo-license        # 仅首次
cargo license --json > ../THIRD_PARTY_LICENSES.json
```

主要依赖：

| crate | 用途 | 典型许可证 |
|---|---|---|
| `tiny_http` | HTTP 服务（已 patch，见 `vendor/`） | Apache-2.0 / MIT |
| `ureq` + `rustls` + `webpki-roots` | HTTPS 客户端与**内置根证书** | Apache-2.0 / MIT / MPL-2.0 |
| `aes` `rsa` `sha1` `sha2` `md-5` | 酷狗接口签名与加密 | Apache-2.0 / MIT |
| `serde` + `serde_json` | JSON 序列化 | Apache-2.0 / MIT |
| `qrcode` + `image` | 登录二维码生成 | Apache-2.0 / MIT |

> **为什么内置根证书很重要**：宿主 Salt Player 使用 jlink 精简运行时，
> 其 `cacerts` 可能缺少部分根证书，导致 JVM 侧 HTTPS 请求报
> `PKIX path building failed`。`rustls` + `webpki-roots` 自带 Mozilla
> 根证书集，不依赖宿主运行时，因此不受该问题影响。

---

## 4. 免责声明

本项目是**非官方**的第三方插件，与酷狗音乐、Salt Player 官方均无关联。

- 不存储、不传播任何音频文件，仅在用户本地展示公开接口返回的数据。
- 所有音乐、歌词、封面等内容版权归原权利人所有。
- 仅供个人学习与技术研究，请勿用于商业用途。
- 请支持正版音乐。

接口实现随时可能因上游服务调整而失效，本项目不提供可用性保证。

---

## 5. 许可证兼容性说明

按你的选择，采用**分层**方案：

```
Kugou_Lite_SPW/
├── LICENSE              MIT      ← 主仓
├── NOTICE.md            本文件
├── src/                 MIT      ← Kotlin 插件
└── sidecar/
    ├── LICENSE          AGPL-3.0 ← 子目录
    ├── NOTICE.md        上游来源与修改记录
    └── rust/            AGPL-3.0 ← 派生自 md3Music
```

两部分的边界是**动态库 + C FFI**：主仓 Kotlin 代码通过
`System.loadLibrary` 加载 `sidecar/rust` 编译出的 `kugou_server.dll`，
再经 `start_server` / `stop_server` 等 C ABI 函数通信。

这样做的目的是让两部分的许可证边界清晰可辨：
主仓保持 MIT 的宽松性，`sidecar/` 完整遵守 AGPL-3.0。

> **合规提示**：AGPL-3.0 对「衍生作品」的边界以**版权法上的衍生**为准，
> 而非单纯的技术边界。本项目认为通过动态库 + 稳定 C ABI 调用的独立程序
> 属于「聚合」（aggregate）而非单一衍生作品，但这属于**解释性判断**。
> 若你要商业分发或对此有疑虑，请咨询律师，或改用
> 「只读思路、自行重写实现」的路线。

---

## 6. 我的代码签名

本仓库中由我（AI 助手）生成的 Kotlin / 文档 / 构建脚本，
版权归属按 MIT 条款交给项目贡献者。`sidecar/rust/` 的 Rust 代码**不是我写的**，
是 md3Music 上游项目作者的成果，版权归其原作者，按 AGPL-3.0 使用。
