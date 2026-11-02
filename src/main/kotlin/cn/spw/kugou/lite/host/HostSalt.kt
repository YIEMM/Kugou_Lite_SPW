package cn.spw.kugou.lite.host

import androidx.compose.runtime.Composer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import cn.spw.kugou.lite.diag.Diag
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier

/**
 * 宿主 Salt Player 原生控件的运行时 ABI。
 *
 * ## 为什么反射而不是 import
 *
 * `com.moriafly.salt.ui.*` 编译在宿主 JAR 里，并被 R8 混淆成 `androidx.compose.ui.<假名>`
 * 形式的假名。插件编译期没有这些类，只能运行时用宿主 ClassLoader 取 `Class`，
 * 再按「入口方法名 + 完整 JVM 参数列表」定位唯一的静态 `@Composable` 入口，
 * 用 `Method.invoke` 把当前 `Composer` 传进去执行。
 *
 * ## 为什么必须传宿主自己的 Composer
 *
 * Compose 的 `@Composable` 函数隐藏参数是 `Composer`。宿主调起我们的
 * `ComposableHook.Content` 时，`currentComposer` 就是宿主当前组合帧的 Composer；
 * 原样透传给宿主的 Salt 组件，组件便注册进宿主同一条组合树，于是 `SaltTheme` 的
 * 全部 CompositionLocal、字体解析器、密度、点击涟漪自动生效。
 * 这是「看起来完全是宿主原生控件」的唯一实现方式。
 *
 * ## 方法名 U+037F 的依据
 *
 * `resolve_markers.py` 扫过宿主全部 2,618 个声明类，所有已知顶层 `@Composable` 入口
 * 都被 R8 重命名成 U+037F；重载之间靠完整参数列表区分。
 *
 * ## 为什么参数匹配用通配
 *
 * 宿主签名里少数参数本身也是混淆类型（例如 `Item` 第 12 个参数、
 * `BasicScreen` 第 7 个参数）。把这些码位写死等于把插件钉死在某一个宿主版本上，
 * 一旦宿主重新混淆就会静默匹配失败。因此 [component] 支持「只校验非混淆参数，
 * 混淆参数只校验位置与个数」的通配匹配 —— 这些位置上的类型插件从不使用
 * （一律传 null 走宿主默认值），不需要知道它们是什么。
 *
 * ## 签名里的 `long`
 *
 * `Color` / `TextUnit` / `TextDecoration` 等 value class 在 JVM 上是未装箱的 `long`
 * 位模式。必须传 `Color.value.toLong()`，不能传 `0L` 猜颜色 —— 否则颜色全透明。
 *
 * ## 默认参数掩码
 *
 * 宿主组件有大量默认参数，Kotlin 编译器把它们编成末尾的 `int changed, int defaultMask`。
 * `defaultMask` 第 n 位为 1 表示第 n 个参数取默认值，此时该参数必须传占位值
 * （对象 null、基本类型 0）。掩码算错会让宿主读到错误默认值甚至解引用 null 崩溃，
 * 因此每个组件的掩码都由 [defaults] 显式声明，而不是手写魔数。
 *
 * ## 分层：本文件不提供 @Composable 函数
 *
 * `currentComposer` 是 `@Composable` getter，只能在 composable 上下文里读。
 * 因此这里所有 `draw*` 都是**普通函数**，`composer` 由调用方显式传入；
 * 真正的 `@Composable` 包装放在 `ui/SaltUi.kt`，那里才读 `currentComposer`。
 *
 * ## 失败策略
 *
 * 全部符号在 [validate] 里提前解析，任一缺失就整体失败并记日志，
 * 绝不在组合过程中抛异常 —— 宿主对插件组合异常的处理是 `FATAL_FAILURE` / `PLUGIN_FATAL`，
 * 直接终止进程；且 Compose 编译器禁止在 `@Composable` 调用外面套 try-catch，
 * 不存在「捕获后降级」的余地。
 */
internal object HostSalt {

    private const val TAG = "salt"

    /** 把 Unicode 码位拼成单字符串。避开 kapt stub 的 Unicode 转义限制，见 [ENTRY]。 */
    private fun cp(code: Int): String = code.toChar().toString()

    /**
     * 宿主所有顶层 `@Composable` 函数被 R8 后的统一入口名（U+037F）。
     *
     * **为什么写成 `cp(0x037F)` 而不是字面量或 Unicode 转义**：kapt 会为 Kotlin 源码
     * 生成 Java stub，而 Java 源码里的 Unicode 转义在**词法阶段**就被处理，
     * 注释里出现反斜杠 + u + 四位十六进制都会让 stub 编译失败
     * （实测 `kaptKotlin FAILED: 非法的 Unicode 逃逸`）。直接写字面量字符又难以辨认、
     * 易被误改。用码位常量构造最稳。
     */
    private val ENTRY = cp(0x037F)

    private const val C = "androidx.compose.runtime.Composer"
    private const val M = "androidx.compose.ui.Modifier"
    private const val F0 = "kotlin.jvm.functions.Function0"
    private const val F1 = "kotlin.jvm.functions.Function1"
    private const val F2 = "kotlin.jvm.functions.Function2"
    private const val F3 = "kotlin.jvm.functions.Function3"
    private const val F4 = "kotlin.jvm.functions.Function4"

    /** 通配标记：该位置是混淆类型，只校验位置与个数，不校验具体类。 */
    private const val ANY = "?"

    // ------------------------------------------------------------------
    // 混淆类名：来自 resolve_markers.py 对宿主 JAR 的 LDC 源码标记扫描，
    // 原始名写在各条注释里。任一取不到即视为宿主版本不受支持。
    // ------------------------------------------------------------------

    /** SaltUI `Text`：`com.moriafly.salt.ui.Text.Text`，`Text.kt:101`（U+0622）。 */
    private val TEXT_CLASS = "androidx.compose.ui." + cp(0x0622)

    /** SaltUI `Card`：`com.moriafly.salt.ui.Card.Card`，`Card.kt:57`（U+1080）。 */
    private val CARD_CLASS = "androidx.compose.ui." + cp(0x1080)

    /** SaltUI `Item`：`com.moriafly.salt.ui.Item.Item`，`Item.kt:209`（U+0824）。 */
    private val ITEM_CLASS = "androidx.compose.ui." + cp(0x0824)

    /** SaltUI `ItemOuterTitle`：`com.moriafly.salt.ui.ItemOuterTitle`，`ItemOuter.kt:44`（U+02BD）。 */
    private val OUTER_CLASS = "androidx.compose.ui." + cp(0x02BD)

    /**
     * 宿主二级页面外框 `com.xuncorp.voxzen.ui.screen.BasicScreenColumn`（`BasicScreen.kt:83`），
     * 混淆名 U+0E16。宿主自己的 34 个页面（含 `ModConfigScreen`）都用它。
     *
     * 该类里有 `Function3` / `Function4` 两个公有重载，**只有 `Function3` 那条路径带
     * 滚动容器**，见下方 [screen] 的说明。
     */
    private val SCREEN_CLASS = "androidx.compose.ui." + cp(0x0E16)

    /** 宿主 `BasicCaptionButton`：`com.xuncorp.voxzen.ui.component.BasicCaptionButton`（U+0529）。 */
    private val CAPTION_CLASS = "com.xuncorp.voxzen.ui.component." + cp(0x0529)

    /**
     * 宿主二级页左上角的返回按钮 `com.xuncorp.voxzen.ui.screen.BasicScreenBackButton`
     * （`BasicScreenBackButton.desktop.kt:5`），混淆名 U+015F。
     *
     * javap 实测签名 `(Function0 onClick, Composer, int changed)` —— 无 Modifier、
     * 无 enabled，是一个纯粹的图标按钮。宿主 `BasicScreen` 的 `onBack` 槽位要的就是
     * 它的 composable 实例（`Function2<Composer, Integer, Unit>`）。
     */
    private val BACK_CLASS = "androidx.compose.ui." + cp(0x015F)

    // ------------------------------------------------------------------
    // 组件句柄。lazy：只有真正用到才解析，解析失败抛出可读异常。
    // ------------------------------------------------------------------

    /**
     * SaltUI `Text`。javap 实测 21 个业务参数：
     * `(String, Modifier, long color, long fontSize, FontStyle, FontWeight, FontFamily,
     * long letterSpacing, TextDecoration, TextAlign, long lineHeight, int overflow,
     * boolean softWrap, int maxLines, int minLines, Function1 onTextLayout,
     * TextStyle style, Composer, int changed, int defaultMask)`。
     */
    private val text: Method by lazy {
        component(
            TEXT_CLASS,
            "java.lang.String", M, "long", "long",
            "androidx.compose.ui.text.font.FontStyle", "androidx.compose.ui.text.font.FontWeight",
            "androidx.compose.ui.text.font.FontFamily", "long",
            "androidx.compose.ui.text.style.TextDecoration", "androidx.compose.ui.text.style.TextAlign",
            "long", "int", "boolean", "int", "int", F1,
            "androidx.compose.ui.text.TextStyle", C, "int", "int", "int",
        )
    }

    /** `TextOverflow` 是 value class，JVM 签名是 `int`，需 `unbox-impl` 取位模式。 */
    private val overflowUnbox: Method by lazy {
        TextOverflow::class.java.getDeclaredMethod("unbox-impl").apply { isAccessible = true }
    }

    /**
     * SaltUI `Card`。javap 实测：
     * `(Modifier, Shape, long color, BorderStroke, Function3<ColumnScope>, Composer, int, int)`。
     * 注意 **Shape 在 color 之前**（与 Material3 Card 相反，极易写错）。
     */
    private val card: Method by lazy {
        component(
            CARD_CLASS,
            M, "androidx.compose.ui.graphics.Shape", "long",
            "androidx.compose.foundation.BorderStroke", F3, C, "int", "int",
        )
    }

    /**
     * SaltUI `Item` 的完整重载。javap 实测 13 个业务参数：
     * `(Function0 onClick, String title, Modifier, boolean showArrow, Painter leadingPainter,
     * PaddingValues contentPadding, Color textColor, long titleColor, String subtitle,
     * long subtitleColor, Function2 trailingContent, String leadingContent,
     * <混淆类型>, Composer, int changed, int defaultMask, int)`。
     * 末尾比普通入口**多一个 int**，共 3 个尾部 int。
     */
    private val item: Method by lazy {
        component(
            ITEM_CLASS,
            F0, "java.lang.String", M, "boolean",
            "androidx.compose.ui.graphics.painter.Painter",
            "androidx.compose.foundation.layout.PaddingValues",
            "androidx.compose.ui.graphics.Color", "long", "java.lang.String", "long",
            F2, "java.lang.String", ANY, C, "int", "int", "int",
        )
    }

    /**
     * SaltUI `ItemOuterTitle(String, Modifier, Composer, int, int)`。
     *
     * `ItemOuter.kt` 里 `ItemOuterTitle`（:44）与 `ItemOuterTip`（:70）签名完全相同，
     * 被重命名成两个不同的方法名。因此这里**不能要求唯一**，改为按入口名精确取 ——
     * `ItemOuterTitle` 源码位置在前，重命名后仍是 U+037F。
     * 这是本文件唯一一处「按名而非按签名」的解析。
     */
    private val outerTitle: Method by lazy { resolveOuterTitle(loader) }

    private fun resolveOuterTitle(loader: ClassLoader): Method {
        val type = type(loader, OUTER_CLASS)
        val candidates = type.declaredMethods.filter {
            JavaModifier.isStatic(it.modifiers) &&
                it.parameterCount == 5 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Modifier::class.java
        }
        check(candidates.isNotEmpty()) { "宿主 ItemOuterTitle 签名缺失" }
        return candidates.first { it.name == ENTRY }.apply { isAccessible = true }
    }

    /**
     * 宿主 `BasicCaptionButton` 的 10 参重载 —— 标题栏「账户 / 刷新应用库」那一条用的就是它。
     * javap 实测 `(Function0, String, Modifier, boolean, String tooltip, float iconSize,
     * Function3<BoxScope>, Composer, int, int)`。
     */
    private val captionButton: Method by lazy {
        component(
            CAPTION_CLASS,
            F0, "java.lang.String", M, "boolean", "java.lang.String",
            "float", F3, C, "int", "int",
        )
    }

    /**
     * 宿主二级页面左上角的返回按钮。
     *
     * javap 实测签名 `(Function0 onClick, Composer, int changed)`，无 Modifier、
     * 无 enabled —— 是一个纯粹的图标按钮。宿主 `BasicScreen` 的 `onBack` 槽位就是
     * 用来放它的（参考实现同样调它来绘制返回键）。
     */
    private val back: Method by lazy {
        component(BACK_CLASS, F0, C, "int")
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 在宿主 ClassLoader 里定位唯一的静态 `@Composable` 入口。
     *
     * 参数列表里写 `?` 的位置是混淆类型：只要求该位置是**引用类型**（非基本类型），
     * 不校验具体类。其余位置必须完全一致。匹配结果必须唯一 ——
     * 多一个说明参数列表写错，少一个说明宿主版本不支持。
     */
    private fun component(owner: String, vararg parameters: String): Method {
        val type = type(owner)
        // ANY 位置没有具体类型可比，先解析成 null，比较时单独处理。
        val want: List<Class<*>?> = parameters.map { if (it == ANY) null else type(it) }
        val matches = type.declaredMethods.filter { m ->
            if (!JavaModifier.isStatic(m.modifiers)) return@filter false
            if (m.name != ENTRY) return@filter false
            if (m.parameterCount != want.size) return@filter false
            val actual = m.parameterTypes
            want.indices.all { i ->
                val w = want[i]
                // ANY：只要求该位置是引用类型（非基本类型），不校验具体类。
                if (w == null) !actual[i].isPrimitive else actual[i] == w
            }
        }
        check(matches.size == 1) {
            "宿主 Salt 组件签名缺失或不唯一：$owner ${parameters.toList()}（匹配 ${matches.size} 个）"
        }
        return matches.single().apply { isAccessible = true }
    }

    /**
     * 解析 JVM 类型名。
     *
     * **必须自己处理基本类型**：`Class.forName("long")` 会抛 `ClassNotFoundException`
     * （`forName` 只认二进制类名，基本类型不是类）。实测症状是 ABI 预检里
     * `SaltUI Text=缺失`、异常消息只有一个 `long` —— 非常容易误判成
     * 「宿主版本不支持」，实际只是这里漏了映射。
     *
     * 其余情况用 `Class.forName(name, false, loader)`：`initialize = false`
     * 避免在解析 ABI 阶段触发宿主类的静态初始化。
     */
    private fun type(loader: ClassLoader, name: String): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> Class.forName(name, false, loader)
    }

    private fun type(name: String): Class<*> = type(loader, name)

    private val loader: ClassLoader by lazy { HostTargets::class.java.classLoader }

    /**
     * 计算默认参数掩码：`indices` 里的位为 1 表示该参数取宿主默认值。
     * 用位集合显式声明，避免手写魔数出错。
     */
    private fun defaults(vararg indices: Int): Int {
        var mask = 0
        for (i in indices) mask = mask or (1 shl i)
        return mask
    }

    /** 预检全部符号；任一缺失以异常暴露给安装层，而不是等宿主组合时才崩。 */
    fun validate() {
        val steps = listOf<Pair<String, () -> Any>>(
            "SaltUI Text" to { text },
            "SaltUI Card" to { card },
            "SaltUI Item" to { item },
            "SaltUI ItemOuterTitle" to { outerTitle },
            "SaltUI BasicScreen" to { screen },
            "SaltUI BackButton" to { back },
            "宿主 BasicCaptionButton" to { captionButton },
        )
        val results = ArrayList<String>(steps.size)
        for ((name, step) in steps) {
            try {
                step()
                results += "$name=ok"
            } catch (t: Throwable) {
                results += "$name=缺失"
                Diag.warn("[$TAG] 原生组件解析失败 $name：" + (t.message ?: t.javaClass.simpleName))
            }
        }
        Diag.info("[$TAG] ABI 预检：" + results.joinToString("，"))
    }

    /** 全部符号是否齐备；安装层据此决定要不要注册 UI hook。 */
    fun isAvailable(): Boolean = try {
        text; card; item; outerTitle; screen; back; captionButton
        true
    } catch (t: Throwable) {
        Diag.warn("[$TAG] 原生组件不可用：" + (t.message ?: t.javaClass.simpleName))
        false
    }

    // ------------------------------------------------------------------
    // 绘制入口：普通函数，composer 由调用方（@Composable 包装层）传入。
    //
    // lambda 参数一律声明成 Any —— @Composable lambda 在 JVM 上是
    // Function2/3/4 实例，Kotlin 会在调用方按声明的 composable 函数类型
    // 把它编译成对应 FunctionN，原样透传即可，绝不能手工包一层。
    // ------------------------------------------------------------------

    /**
     * 画一行 Salt 文字。
     *
     * 只显式传 value / modifier / overflow / softWrap / maxLines / minLines，
     * 其余走宿主默认 —— 颜色、字号、字体、字重、字距、对齐、行高、onTextLayout、style
     * 都取自 `SaltTheme`，与设置页里的说明文字完全一致。
     */
    fun drawText(
        value: String,
        modifier: Modifier,
        maxLines: Int,
        overflow: TextOverflow,
        composer: Composer,
    ) {
        // 显式：0=value, 1=modifier, 11=overflow, 12=softWrap, 13=maxLines, 14=minLines
        // 默认：其余全部
        val mask = defaults(2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 16)
        text.invoke(
            null, value, modifier, 0L, 0L, null, null, null, 0L, null, null, 0L,
            overflowUnbox.invoke(overflow), true, maxLines, 1, null,
            TextStyle.Default, composer, 0, 0, mask,
        )
    }

    /**
     * 画 SaltUI 卡片。shape / color / border 全部走宿主默认 ——
     * 卡片底色、圆角、描边直接取自 `SaltTheme`，明暗主题自动跟随。
     */
    fun drawCard(modifier: Modifier, content: Any, composer: Composer) {
        card.invoke(null, modifier, null, 0L, null, content, composer, 0, defaults(1, 2, 3))
    }

    /**
     * 画 SaltUI `Item` 行（标题 + 副标题 + 右箭头，整行可点）。
     *
     * 显式：onClick / title / modifier / subtitle（第 8 位）。
     * 其余（showArrow 取默认 true、leadingPainter、contentPadding、textColor、
     * titleColor、subtitleColor、trailingContent、leadingContent、末尾混淆参数）
     * 全部走宿主默认。
     */
    fun drawItem(
        title: String,
        subtitle: String,
        modifier: Modifier,
        onClick: () -> Unit,
        composer: Composer,
    ) {
        val explicit = defaults(0, 1, 2, 8)
        val all = (1 shl 13) - 1
        item.invoke(
            null, onClick, title, modifier, true, null, null, null, 0L, subtitle,
            0L, null, null, null, composer, 0, 0, all xor explicit,
        )
    }

    /** 画 SaltUI `ItemOuterTitle` 分组标题。title 与 modifier 都显式，掩码 0。 */
    fun drawOuterTitle(title: String, modifier: Modifier, composer: Composer) {
        outerTitle.invoke(null, title, modifier, composer, 0, 0)
    }

    /**
     * 用宿主 `BasicCaptionButton` 画标题栏按钮。
     *
     * 显式：onClick / contentDescription / modifier / enabled / content。
     * 默认：tooltip（第 4 位）、iconSize（第 5 位）。
     * 按钮宽度、图标大小、悬停底色、按下态与宿主 caption 按钮完全一致，
     * 且作为 `CaptionButtons` 那一行 Row 的子项参与布局，不会与 □/✕ 重叠。
     */
    fun drawCaptionButton(
        onClick: () -> Unit,
        contentDescription: String,
        modifier: Modifier,
        enabled: Boolean,
        content: Any,
        composer: Composer,
    ) {
        captionButton.invoke(
            null, onClick, contentDescription, modifier, enabled, null, 0f,
            content, composer, 0, defaults(4, 5),
        )
    }

    /**
     * 画宿主二级页左上角的返回按钮。
     *
     * 签名 `(Function0 onClick, Composer, int changed)` —— 无 Modifier、无 enabled，
     * 所以掩码传 0（所有参数都是显式的）。
     *
     * `onClick` 是普通 `Function0`，直接传 `() -> Unit` 即可，**不需要** composable
     * 包装：宿主签名里它就是 `Function0`，不是 `@Composable () -> Unit`。
     */
    fun drawBack(onClick: () -> Unit, composer: Composer) {
        back.invoke(null, onClick, composer, 0)
    }

    /**
     * 宿主二级页面外框 `com.xuncorp.voxzen.ui.screen.BasicScreenColumn`
     * （`BasicScreen.kt:83`），混淆名 U+0E16 的 **`Function3` 重载**。
     *
     * <h3>为什么用 `Function3` 重载而不是 `Function4`</h3>
     *
     * 宿主 `U+0E16` 一个类里有两个公有重载，**只有 `Function3` 那条路径带滚动容器**：
     *
     * | 重载 | content 类型 | 滚动容器 |
     * |---|---|---|
     * | `BasicScreenBox`（`Function4`） | `Function4<BoxScope, PaddingValues>` | **无** |
     * | `BasicScreenColumn`（`Function3`） | `Function3<ColumnScope>` | **有**（`ScrollableState`） |
     *
     * javap 实证：滚动相关的 `ScrollableState` + `voxzen.ui.component` 滚动组件调用位于
     * `BasicScreenColumn.<anonymous> (BasicScreen.kt:96)` 方法体内，也就是 `Function3`
     * 这条路径。`Function4` 路径里没有任何滚动修饰符。
     *
     * **真机教训**：曾经为了「修」一个不存在的裁切问题把这里换成 `Function4`，
     * 结果页面彻底不能滚动了 —— 因为切到了没有滚动容器的那条路径。
     * 内容超出视口时靠 `Function3` 路径的滚动容器滚动，这才是宿主页面的真实行为
     * （宿主每个页面自己能滚，也是因为走了这条路径）。
     */
    private val screen: Method by lazy {
        component(
            SCREEN_CLASS,
            M, F2, "java.lang.String", "java.lang.String", F2,
            F4, ANY, F3, C, "int", "int",
        )
    }

    /**
     * 画宿主二级页面外框（`BasicScreenColumn`，`Function3` 重载，自带滚动容器）。
     *
     * 参数顺序：`(Modifier, onBack, title, subtitle, actions, tools, 混淆参数, content)`。
     *
     * 显式：Modifier(0) / onBack(1) / title(2) / content(7)。
     * 默认：subtitle(3) / actions(4) / tools(5) / 混淆参数(6)。
     *
     * **`onBack` 是「在返回键位置绘制什么」的槽位**，不是点击回调。传 null 就没返回入口。
     * 正确做法是传入一个绘制 [drawBack] 的 composable，见 `ui.SaltScreen`。
     *
     * **`content` 是 `Function3<ColumnScope, Composer, Integer>`** —— 只给 `ColumnScope`，
     * 不给 `PaddingValues`。垂直内边距（标题栏 + 迷你播放条）由外框内部处理，
     * 内容不需要也不应该自己 `fillMaxSize()`：那样会把内容高度钉死在视口高度，
     * 滚动范围变成 0，页面就滚不动了。
     */
    fun drawScreen(
        title: String,
        onBack: Any?,
        content: Any,
        composer: Composer,
    ) {
        screen.invoke(
            null, Modifier, onBack, title, null, null, null, null, content,
            composer, 0, defaults(3, 4, 5, 6),
        )
    }
}
