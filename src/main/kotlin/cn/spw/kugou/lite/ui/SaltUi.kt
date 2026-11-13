package cn.spw.kugou.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.host.HostSalt
import cn.spw.kugou.lite.host.SidebarHost

/**
 * 宿主原生 Salt 控件的 `@Composable` 包装层。
 *
 * <p>**为什么单独一层**：`currentComposer` 是 `@Composable` getter，只能在 composable
 * 上下文里读；而 [HostSalt] 里的 `draw*` 都是普通函数（这样才不会出现
 * 「普通函数里读 currentComposer」的编译错误，也便于单元测试）。
 * 这一层就是唯一的桥：每个包装函数读一次 `currentComposer`，原样传给 `HostSalt`。</p>
 *
 * <h2>最关键的一点：lambda 的接收者类型决定 FunctionN 的元数</h2>
 *
 * <p>`@Composable` lambda 在 JVM 上是 `FunctionN` 实例，**N = 1 + 接收者个数 + 2**：
 * `Composer` 与 `changed: Int` 各占一个参数，`BoxScope` / `ColumnScope` 等接收者
 * 本身也是第一个参数。</p>
 *
 * ```
 * @Composable () -> Unit                    → Function2<Composer, Integer, Unit>
 * @Composable BoxScope.() -> Unit           → Function3<BoxScope, Composer, Integer, Unit>
 * @Composable ColumnScope.() -> Unit         → Function3<ColumnScope, Composer, Integer, Unit>
 * @Composable BoxScope.(PaddingValues) -> Unit → Function4<BoxScope, PaddingValues, Composer, Integer, Unit>
 * ```
 *
 * <p>**真机事故记录**：早期版本把 `SaltCaptionButton` 的 `content` 声明成
 * `@Composable () -> Unit`（编译成 `Function2`），而宿主 `BasicCaptionButton` 的参数是
 * `Function3<BoxScope,...>`。宿主 `Method.invoke` 时直接抛
 * `ClassCastException: ...$$Lambda cannot be cast to kotlin.jvm.functions.Function3`，
 * 宿主将其升级成 `FATAL_FAILURE` / `PLUGIN_FATAL` 并**终止进程**
 * （`safe-mode-session.properties` 有完整记录）。
 * 所以这里的接收者类型必须与宿主 JVM 签名**逐个对齐**，不能凭直觉写。</p>
 *
 * <p>**绝不手工 `object : Function3 {...}`**：`@Composable` lambda 的 `FunctionN`
 * 实例带着 Compose 编译器的插桩信息，手工构造的没有，传给宿主后会导致组合状态错乱。
 * 正确做法是声明成真正的 composable 函数类型，让编译器生成，再原样透传。</p>
 */

/** Salt 文字。样式全部取自 `SaltTheme`。 */
@Composable
fun SaltText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    HostSalt.drawText(text, modifier, maxLines, overflow, currentComposer)
}

/**
 * Salt 卡片。底色、圆角、描边取自 `SaltTheme`。
 *
 * 内容接收者必须是 `ColumnScope` —— 宿主 `Card` 的 `content` 是
 * `Function3<ColumnScope, Composer, Integer, Unit>`。
 */
@Composable
fun SaltCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    HostSalt.drawCard(modifier, content, currentComposer)
}

/**
 * Salt 分组标题（`ItemOuterTitle`）。
 *
 * ## 不要用负 padding 抵消
 *
 * 宿主 `ItemOuterTitle`（`ItemOuter.kt:44`）自带水平内边距，而同级 `Card`（U+1080）
 * 不带。曾试图用 `padding(horizontal = (-16).dp)` 抵消，但 Compose 运行时校验
 * `Padding must be non-negative`，会抛 `IllegalArgumentException`，宿主升级为
 * `FATAL_FAILURE` / `PLUGIN_FATAL` 并**终止进程**（真机确认）。
 *
 * ## 正确做法：外层不加水平 padding，只给 `Card` 加
 *
 * 页面外层的 `Column` 只加 `padding(bottom = ...)`；水平留白由各个组件自己负责：
 * - `Card` 加 `padding(horizontal = 16.dp)` → 卡片不贴窗口边缘
 * - `ItemOuterTitle` 自带 16dp → 分组标题自然缩进
 *
 * 两者缩进量相同（都是 16dp），因此分组标题与卡片内文字左对齐，与宿主一致。
 * 实测：宿主分组标题 x=254、行标题 x=253（差 1px 属抗锯齿）。
 */
@Composable
fun SaltGroupTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    HostSalt.drawOuterTitle(title, modifier, currentComposer)
}

/**
 * Salt 列表行（`Item`）：标题 + 副标题 + 右箭头，整行可点。
 *
 * `Item` 的 `trailingContent` 是 `Function2<Composer, Integer, Unit>`，
 * 也就是不带接收者的普通 `@Composable () -> Unit`；本封装不使用该参数（传 null）。
 */
@Composable
fun SaltItem(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    HostSalt.drawItem(title, subtitle, modifier, onClick, currentComposer)
}

/**
 * 宿主标题栏 caption 按钮。
 *
 * 内容接收者必须是 `BoxScope` —— 宿主 `BasicCaptionButton` 的 `content` 是
 * `Function3<BoxScope, Composer, Integer, Unit>`。写成无接收者的 `() -> Unit`
 * 会在真机上以 `ClassCastException: cannot be cast to Function3` 崩掉宿主进程。
 */
@Composable
fun SaltCaptionButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    HostSalt.drawCaptionButton(onClick, contentDescription, modifier, enabled, content, currentComposer)
}

/**
 * 宿主二级页面外框（`BasicScreenColumn`，U+0E16 的 `Function3` 重载）。
 *
 * ## 为什么是 `Function3` 重载
 *
 * 宿主 `U+0E16` 有两个公有重载，**只有 `Function3` 这条路径带滚动容器**：
 *
 * | 重载 | content 类型 | 滚动 |
 * |---|---|---|
 * | `BasicScreenBox`（`Function4`） | `Function4<BoxScope, PaddingValues>` | 无 |
 * | `BasicScreenColumn`（`Function3`） | `Function3<ColumnScope>` | **有** |
 *
 * 换成 `Function4` 会让页面彻底失去滚动能力（真机踩过）。
 *
 * ## 内容不要 `fillMaxSize()`
 *
 * 外框只给 `ColumnScope`，垂直内边距（标题栏 + 迷你播放条）由它内部处理。
 * 内容若 `fillMaxSize()`，高度会被钉死在视口高度，滚动范围变成 0 —— 也就滚不动了。
 * 直接排布内容即可，超出的部分由外框的滚动容器负责。
 *
 * ## onBack 是「槽位」不是「回调」
 *
 * 第 1 个参数 `onBack` 语义是**「在左上角返回键位置绘制什么」**，不是点击回调。
 * 传 null 就是什么都不画，页面没有返回入口。所以这里默认渲染宿主原生的
 * `BasicScreenBackButton`，点击行为接到 [onBackClick]（由页面层接到 `Navigator.back()`）。
 *
 * @param onBackClick 点击返回按钮时执行的动作；null 表示用宿主导航栈返回。
 * @param content 内容区，接收者是 `ColumnScope`。
 */
@Composable
fun SaltScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 返回键槽位的内容：宿主原生 BackButton（图标、hover、无障碍描述全由宿主提供）。
    val backSlot: @Composable () -> Unit = {
        SaltBackButton(onClick = { (onBackClick ?: PageState::requestBack)() })
    }
    HostSalt.drawScreen(title, backSlot, content, currentComposer)
}

/**
 * 宿主原生返回按钮。
 *
 * 注意 `onClick` 是**普通** `Function0`（不是 `@Composable`），所以可以直接传 lambda；
 * 用 `remember` 不是必需的，但保持引用稳定能避免无谓重组。
 */
@Composable
fun SaltBackButton(onClick: () -> Unit) {
    HostSalt.drawBack(onClick, currentComposer)
}

/**
 * 宿主左侧栏的一行原生条目（`SidebarItemLayout`）。
 *
 * <h3>为什么用宿主的行而不是自绘</h3>
 *
 * 行高、左内边距、图标槽位、选中底色、hover 态、字体与主题色全部由宿主的
 * `SidebarItemLayout` 提供，所以我们的条目与「音乐库 / 音效 / 设置」在视觉上
 * 完全一致，不需要逐个 dp 去猜（猜出来的结果在不同 DPI 与主题下都会偏）。
 *
 * <h3>内容接收者必须是 `BoxScope`</h3>
 *
 * 宿主签名里 `content` 是 `Function3<BoxScope, Composer, Integer, Unit>`。
 * 写成没有接收者的 `() -> Unit` 会编译成 `Function2`，`Method.invoke` 时直接
 * `ClassCastException`，宿主升级成 `PLUGIN_FATAL` 并终止进程（真机踩过同类问题）。
 * 因此这里沿用 `BoxScope.() -> Unit`，让编译器生成正确的 `Function3`。
 *
 * <h3>点击为什么挂在 Modifier 上</h3>
 *
 * 宿主自己画侧栏行时也是把 `Modifier.clickable(...)` 作为第 4 个参数传进去的
 * （javap 实证），行组件本身不接管点击。保持一致才能得到同样的涟漪与命中区域。
 *
 * @param selected 是否高亮为当前页。真相只有一份：宿主导航栈（见 [PageState]）。
 * @param icon 图标槽位内容，接收者是 `BoxScope`；宿主行会给它固定尺寸与居中。
 */
@Composable
fun SaltSidebarRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable BoxScope.() -> Unit,
) {
    SidebarHost.drawRow(
        label = label,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        content = icon,
        composer = currentComposer,
    )
}
