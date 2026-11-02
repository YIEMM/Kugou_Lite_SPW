# 宿主字节码探查工具

本目录的脚本用于**从宿主归档里实测出注入目标**，而不是靠"按版本号硬编码混淆名"。

适用宿主：Salt Player for Windows **1.19.3**（归档 `app/ffmpeg-x64.dll`，实为 JAR，42,147 个 class）。

## 为什么需要这些工具

宿主的 UI 类被混淆成 Unicode 码点名（如 `androidx/compose/ui/Ɏ`），且**每次更新都会重排**。
写死类名必然失效——`spw-listen-stats` 的版本映射表在 1.19.3 上就已经对不上（它记的 1.19.1 值是 `U+0443`，1.19.3 真实值是 `U+024E`）。

但宿主**被调用的 Compose 函数类保留了 ASCII 原始名**（`com.xuncorp.voxzen.ui.navigation.AppNavigationKt`），只有调用方被混淆。
所以插件只需要运行时解析出「谁调用了它」，`className` / `methodName` 可以写原始名。

## 核心脚本：`resolve_target.py`

一次给出 Hook 需要的**全部四项**：`className`、`methodName`、`inClass`、`parameterTypes`。

```powershell
python tools/resolve_target.py <原始类名> <原始方法名>

# 例：
python tools/resolve_target.py 'com.xuncorp.voxzen.ui.navigation.AppNavigation' 'AppNavigation'
```

解析链路（两遍扫描）：

```
原始类名.方法名
  └─ 第 1 遍：找常量池里含 LDC 源码标记的类
       → 声明类（可能被混淆）+ 字节码方法名 + 描述符 + 业务参数
       └─ 第 2 遍：找常量池 Methodref 指向「声明类.方法名」的类
            → 调用方 inClass（保留全部候选，按 LDC 标记消歧）
```

### 四个必须处理的细节

1. **LDC 加载的是 `CONSTANT_String`（tag 8），它再指向 UTF-8 项。**
   解析常量池时必须跟进这层间接引用，否则所有 LDC 字符串都读成 `null`，标记一个都匹配不上。
   （这个坑让第一版解析器完全失效，`total ldc strings across methods: 0`。）

2. **匹配必须用 `(owner, name, descriptor)` 完整三元组。**
   只按 `(owner, name)` 匹配会把不同重载混在一起，得到假的"调用方不唯一"。
   `ModConfigScreen` 就踩过：正确匹配后真实调用方是 2 个，不是 12 个。

3. **不要排除自引用（这条曾经写错，是真实缺陷的根源）。**
   本 README 早先写的"必须排除自引用"是**错的**。按此实现会把唯一可用的调用点也排掉，
   导致 hook 注册成功但**永远不触发**。

   `tools/resolve_target.py` 曾有一段：

   ```python
   # 错：声明类自己引用自己就跳过
   if any(this_name == t[0] for t in targets): continue
   ```

   已删除，并在原位留下长注释说明原因。

   实测（`tools/probe_callers.py`，纯常量池三元组扫描、不做任何自引用排除）：

   ```
   扫描 class 总数：42147
   真实调用方数量：2
     androidx/compose/ui/ஏ.class                                   ← 声明类自身的转发
     com/xuncorp/voxzen/ui/navigation/ComposableSingletons$AppNavigation_desktopKt.class  ← 导航分发（要注册的 inClass）
   ```

   两条都是真实调用。**正确做法**：保留全部候选，按「LDC 标记的源文件/函数名与目标原始名相符」消歧。

4. **`businessParams` 要剔除 `Composer` 及其后的合成 `int`。**
   Hook 的 `parameterTypes` 只收业务参数。

## 实测结论（1.19.3，6/6 复现）

解析器输出与 `ui-examples/MOUNT-POINTS.md` 的实测值**完全一致**：

| 区域 | 原始名 | `inClass` | 业务参数 | 文档实测 | 一致 |
|---|---|---|---|---|---|
| A 整窗外层 | `navigation.AppNavigation.AppNavigation` | `androidx/compose/ui/Ɏ` (U+024E) | 空 | `androidx.compose.ui.Ɏ` | ✅ |
| B 侧栏 | `sidebar.Sidebar.Sidebar` | `androidx/compose/ui/ब` (U+092C) | `Modifier, float, א` | `androidx.compose.ui.ब` | ✅ |
| C 标题栏 | `titlebar.MainTitleBar.MainTitleBar` | `androidx/compose/ui/Է` (U+0537) | `ӗ, ઠ, च, Modifier` | `androidx.compose.ui.Է` | ✅ |
| D 内容区 | `main.MainWindowContent.MainWindowContent` | `androidx/compose/ui/Ԑ` (U+0510) | `boolean` | `androidx.compose.ui.Ԑ` | ✅ |
| E 播放页 | `player.PlayerScreen.PlayerScreen` | `androidx/compose/ui/ԥ` (U+0525) | `Function0, boolean, च, Modifier` | `androidx.compose.ui.ԥ` | ✅ |
| F 配置页 | `workshop.ModConfigScreen.ModConfigScreen` | `androidx/compose/ui/ஏ` (U+0B8F) | `ScreenRoute$ModConfigContent, ಠ` | `androidx.compose.ui.ஏ` | ✅ |

### 多调用方消歧

A / C / E 天然唯一；B / D / F 有 2–12 个候选。

**消歧规则**：取「带 LDC 标记、且标记的源文件与目标原始类相符」的那个。
Lambda 内部类（`X$ͩ`）与 `ComposableSingletons$Xxx` **不带标记**，据此排除。

## 辅助脚本

| 脚本 | 用途 |
|---|---|
| `scan_refs.py <owner> [method]` | 全量扫描 42,147 个 class 的常量池，找 Methodref 指向 `<owner>` 的类。用于**只查调用方**的场景，比 `resolve_target.py` 快 |
| `probe_callers.py <owner> <name> <descriptor>` | **独立复核器**：纯常量池三元组扫描，不做任何自引用排除，直接打印真实调用方数量与类名。用来交叉验证 `resolve_target.py` 的结论，避免再被"排除自引用"之类的逻辑错误误导 |
| `dump_class.py <internal-name> <out-file>` | 按类自己的 `this_class` 导出 `.class` 到 ASCII 文件名，便于 `javap` 反汇编。zip 条目名在非 UTF-8 标志下会被不同工具按不同代码页解码，直接按名字匹配会 miss。**必须传两个参数**，少传输出路径会 `IndexError`；混淆类名要写 `U+XXXX` 形式（如 `'androidx/compose/ui/U+0E16'`），`$` 内部类同样适用（`'com/xuncorp/voxzen/workshop/hooks/U+0529$U+052D'`） |
| `resolve_markers.py --grep <子串>` | 只列 LDC 标记，不做第 2 遍扫描。用于"某个原始名到底叫什么"的探索 |
| `scan_appnav.py` | 早期粗筛：按字符串搜。**只作线索**，字符串命中不等于真实调用 |

## 找「宿主原生组件」的正确姿势

宿主所有 Salt 组件顶层函数都被 R8 改名成 **U+037F（`Ϳ`）**，所以**不能按名字搜**。可行的定位路径：

1. **有 Voxzen 源码标记的**（`com.xuncorp.voxzen.*` 自有组件）→ 直接用 `resolve_target.py`。
   标记格式是 `包名.函数名 (文件名.kt:行号)`，例如
   `com.xuncorp.voxzen.ui.screen.BasicScreenBackButton (BasicScreenBackButton.desktop.kt:5)`。

2. **Salt 库组件（`com.moriafly.salt.*`）没有标记** → `resolve_target.py` 会 `NOT FOUND`。
   改用**签名反查**：先按候选签名筛出所有同签名的类，再读调用链里的 LDC 标记确认身份。

   ```powershell
   # 例：找 BasicScreen 的返回按钮（签名 (Function0, Composer, int)）
   # 1) 全量扫出 13 个同签名候选类
   # 2) javap 逐个反汇编，读 startRestartGroup 附近的 LDC 标记
   # 3) 命中 com.xuncorp.voxzen.ui.screen.BasicScreenBackButton (BasicScreenBackButton.desktop.kt:5)
   #    → 类 androidx/compose/ui/ş（U+015F）
   ```

   注意同名候选可能很多（本例 13 个），必须靠 LDC 标记定身份，不能靠猜。

3. **确定后必须用 `resolve_target.py` 复核**：给它**完全限定的原始类名**才能命中标记：

   ```powershell
   python tools/resolve_target.py 'com.xuncorp.voxzen.ui.screen.BasicScreenBackButton' 'BasicScreenBackButton'
   # → declaringClass = androidx/compose/ui/\u015f，descriptor = (Lkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;I)V
   ```

## 关键教训：`replaceComposable` 会静默回退

**注册成功 ≠ 生效。** 宿主替换守卫拒绝**非零 Compose 默认参数掩码**时会**静默画原组件**，不抛异常、不打日志。

判断方法：在**同一个** `inClass` 上注册一个 `afterComposable` 探针。

- 探针触发、`replaceComposable` 组件不触发 → **守卫问题**，不是解析问题（`inClass` 是对的）。
- 两者都不触发 → 才是 `inClass` / `parameterTypes` 解析错了。

解法见 `src/main/kotlin/cn/spw/kugou/lite/host/HostReplacementDefaults.kt`：对 hook site 做手术，把允许掩码区间收窄成 `IntRange.EMPTY` 并换 transformer。两个码位必须分清 —— getter/setter 的**基类型**是 `hooks.Ϳ`（U+037F），要**实例化**的具体实现是 `hooks.ԭ`（U+052D）。

## 播放相关（javap 实测，1.19.3）

```powershell
python tools/dump_class.py 'com/xuncorp/voxzen/service/PlaybackController' "$env:TEMP\pc.class"
& javap -p -c "$env:TEMP\pc.class"
```

| 类 | 状态 | 关键成员 |
|---|---|---|
| `com.xuncorp.voxzen.service.PlaybackController` | 未混淆，Kotlin object | `INSTANCE`；`setPlaybackQueue(List<PiscesMediaItem>, int)`；`setPlaybackQueue(PiscesMediaItem)`；`prepare()`；`play()`；`playMusicAt(int, boolean)`；`addToPlay(PiscesMediaItem)`；`updateMediaItems(PiscesMediaItem...)` |
| `com.xuncorp.pisces.PiscesMediaItem` | 未混淆，`implements Serializable` | 构造器 `(String, String, String, String, String, String)`；6 个 `private final String` 字段（混淆字段名 `Ԩ`–`ԭ`，**不要按字段名反射，按构造器顺序传值**） |
| `com.xuncorp.voxzen.cast.MusicVideoAudioSourceRegistry` | 未混淆，Kotlin object | `INSTANCE`；`activate(String, String)`；`clear()`；`matches(PiscesMediaItem)` |

## 注意

- 脚本里的宿主路径硬编码为 Steam 安装路径，换机器要改 `HOST` 常量
- Windows 控制台默认 GBK，输出混淆类名会 `UnicodeEncodeError`；脚本已用 `backslashreplace` 转义，另需 `$env:PYTHONIOENCODING='utf-8'`
- `javap` 需要 **JDK 25**（宿主类是 class file major 69，JDK 21 的 javap 会拒绝）。反汇编混淆类时还要设 `JAVA_TOOL_OPTIONS='-Dstdout.encoding=UTF-8'`，否则中文/Unicode 输出乱码
- 全量扫描一次约 10–40 秒（取决于磁盘），生产代码里必须放后台线程并缓存结果
- **Python 里不要用 `subprocess` 调 `javap`**（管道解码会 `UnicodeDecodeError`），改用 PowerShell 直接调

## Kotlin 源码里的 kapt 陷阱（必读）

**任何 `\uXXXX` 序列出现在 Kotlin 源文件的任何位置（包括 KDoc 注释里）都会让 `kaptKotlin` 失败**：

```
e: .../HostSalt.java:7: 错误: 非法的 Unicode 逃逸
```

原因是 kapt 先把 Kotlin 编译成 Java 存根，而 **Java 在词法阶段就处理 `\uXXXX` 转义 —— 连注释里的也算**。

所以：

- **不要**在注释里写 `\u0529`、`\u037f` 这类码位说明；
- 用**实际字符**（`ԩ$ԭ`）或纯文本描述（`码位 U+0529 与 U+052D`）；
- 构造混淆名一律用 `cp(code: Int): String = code.toChar().toString()` 辅助函数，不写转义。

自查命令（应为空）：

```powershell
Select-String -Path src\main\kotlin\**\*.kt -Pattern '\\u'
```
