package cn.spw.kugou.lite.host

import androidx.compose.runtime.Composer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cn.spw.kugou.lite.diag.Diag
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier

/**
 * 宿主左侧栏的原生行 ABI。
 *
 * ## 目标：让「酷狗音乐」像「音乐库」一样常驻在左侧栏
 *
 * 宿主侧栏（`com.xuncorp.voxzen.ui.main.sidebar.Sidebar`）是一个 `LazyColumn`，
 * 每个条目由 `SidebarKt` 里的一个逐行 `@Composable` 绘制，它内部再调
 * `SidebarItemLayout` 画真正的行。条目内容 lambda 编译成 `SidebarKt$items$4`
 * 这样的合成类，**每画一行就被调一次** —— 所以把 `afterComposable` 挂在这个
 * 逐行 composable 的调用点上，就能精确地在「某一行的后面」追加内容。
 *
 * ## 为什么这些混淆名可以写死
 *
 * 宿主把 `com.xuncorp.voxzen.ui.*` 混淆成 `androidx.compose.ui.<单字符>`，
 * 而且**每次发版都可能重排**。本插件对其它宿主组件的做法是「运行时扫 LDC 源码标记」，
 * 但侧栏这几处**没有源码标记**（`Sidebar.kt` 只有 7 个方法带标记，恰好不是我们要的三个），
 * 因此只能像 [HostSalt] 那样：**把实测到的码位写成常量，并在安装期做严格签名校验**。
 *
 * 校验失败的后果是「侧栏入口不出现」，页面仍可从标题栏按钮进入 —— 不会崩。
 * 这是本项目一贯的降级策略。
 *
 * ## 唯一的硬约束：默认参数掩码
 *
 * `SidebarItemLayout` 有 11 个参数，其中 4 个带默认值。Kotlin 把它们编成末尾的
 * `int defaultMask`，**掩码错一位宿主就会读到错误的默认值甚至解引用 null**，
 * 而宿主把插件组合期异常升级成 `PLUGIN_FATAL` 并**直接终止进程**。
 *
 * 掩码不是猜的：javap 实证宿主自己画侧栏行时 `invokestatic` 前压栈
 * `label, selected, aconst_null, iconst_0, clickableModifier, aconst_null, aconst_null, content`
 * 且末尾是 `bipush 108`（= 二进制 `1101100`，第 2/3/5/6 位为 1）。
 * 也就是说宿主只显式传 4 个参数：标题、选中态、行 Modifier、内容 lambda。
 *
 * ## 选中态
 *
 * 宿主自己算选中态的方式（同一段 javap 实证）是：
 * `Navigator.getNavBackStack().lastOrNull() == routeItem.getScreenRoute()`。
 * 我们照抄同一份真相 —— 只读宿主导航栈，不自己维护 tab。
 */
internal object SidebarHost {

    private const val TAG = "sidebar"

    /** 把 Unicode 码位拼成单字符串。避开 kapt stub 的 Unicode 转义限制，见 [HostSalt.ENTRY]。 */
    private fun cp(code: Int): String = code.toChar().toString()

    /** 宿主所有顶层 `@Composable` 被 R8 后的统一入口名（U+037F）。 */
    private val ENTRY = cp(0x037F)

    /** `com.xuncorp.voxzen.ui.main.sidebar.SidebarKt`（`Sidebar.kt`）。 */
    private val SIDEBAR_CLASS = "androidx.compose.ui." + cp(0x0B1A)

    /**
     * `SidebarKt$items$4` —— `LazyListScope.items()` 的内容 lambda
     * （`Function4<LazyItemScope, Integer, Composer, Integer, Unit>`）。
     *
     * 它持有 `List` + `SidebarViewModel`，循环里对每个条目调用 [ENTRY]。
     * `afterComposable` 的 `inClass` 必须是这个**调用方**，而不是 `SidebarKt`。
     */
    private val ITEM_LAMBDA_CLASS = SIDEBAR_CLASS + "$" + cp(0x052E)

    /** 侧栏条目接口 `androidx.compose.ui.063A`（`SidebarRoute` 密封接口）。 */
    private val ROUTE_INTERFACE = "androidx.compose.ui." + cp(0x063A)

    /**
     * `SidebarRoute.ScreenRouteItem` —— 把宿主 `ScreenRoute` 包成侧栏条目。
     *
     * 它有一个接受 `ScreenRoute` 的构造器和一个 `ScreenRoute` getter，
     * 后者正是宿主算选中态用的那个方法（混淆名 U+0528，因此按**返回类型**定位）。
     */
    private val ROUTE_ITEM_CLASS = ROUTE_INTERFACE + "$" + cp(0x052A)

    /** `com.xuncorp.voxzen.ui.main.sidebar.SidebarItemLayoutKt`（`SidebarItemLayout.kt`）。 */
    private val ROW_CLASS = "androidx.compose.ui." + cp(0x04CC)

    /**
     * 宿主「音乐库」行对应的路由类。
     *
     * `ScreenRoute` 是宿主自己的密封类，**不参与混淆**（与 `ModConfigContent` 同理），
     * 所以这个名字跨版本稳定。酷狗入口紧跟在它后面渲染。
     */
    const val MUSIC_LIBRARY_ROUTE =
        "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$MusicLibrary"

    /** 宿主 `ScreenRoute` 的基类名，用于按返回类型定位条目包装类上的 getter。 */
    private const val SCREEN_ROUTE_CLASS = "com.xuncorp.voxzen.ui.navigation.ScreenRoute"

    /**
     * `SidebarItemLayout` 的默认参数掩码（第 2/3/5/6 位为 1）。
     *
     * **不要改成别的值**：宿主按这个掩码决定读哪个默认值，错一位就是进程消失。
     */
    const val ROW_DEFAULTS_MASK = 108

    private const val COMPOSER = "androidx.compose.runtime.Composer"

    /** 一个已解析的侧栏 ABI。 */
    class Abi(
        val sidebarClass: String,
        val itemLambdaClass: String,
        val routeItemClass: String,
        val entryName: String,
        /** 行渲染器 `SidebarItemLayout`（11 参静态 `@Composable`）。 */
        val rowMethod: Method,
        /** 条目包装类上的 `getScreenRoute()`。 */
        val routeGetter: Method,
    )

    @Volatile
    private var cached: Abi? = null

    /** 最近一次解析失败的原因（诊断用）。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 进程内缓存的 ABI；解析失败返回 null，绝不抛异常。 */
    fun resolve(loader: ClassLoader = SidebarHost::class.java.classLoader): Abi? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return runCatching { resolveUncached(loader) }
                .onFailure {
                    lastError = describe(it)
                    Diag.warn("[$TAG] 侧栏 ABI 解析失败：$lastError")
                }
                .getOrNull()
                ?.also {
                    cached = it
                    Diag.info(
                        "[$TAG] 侧栏 ABI：row=${it.rowMethod.declaringClass.name}.${it.rowMethod.name} " +
                            "item=${it.routeItemClass} lambda=${it.itemLambdaClass} mask=$ROW_DEFAULTS_MASK"
                    )
                }
        }
    }

    fun isAvailable(): Boolean = resolve() != null

    /** 把异常链压成一行可读原因（`InvocationTargetException` 只带外壳没用）。 */
    private fun describe(t: Throwable): String {
        var cur: Throwable? = t
        var text = t.message ?: t.javaClass.simpleName
        while (cur?.cause != null && cur.cause !== cur) {
            cur = cur.cause
            text = cur?.message ?: text
        }
        return text
    }

    private fun resolveUncached(loader: ClassLoader): Abi {
        val sidebar = Class.forName(SIDEBAR_CLASS, false, loader)
        val lambda = Class.forName(ITEM_LAMBDA_CLASS, false, loader)
        val routeItem = Class.forName(ROUTE_ITEM_CLASS, false, loader)
        val row = Class.forName(ROW_CLASS, false, loader)

        // 1) 行渲染器：签名在全宿主唯一 —— String, Z, Modifier, Z, Modifier, Color, ?, Function3, Composer, I, I
        val rowMethod = row.declaredMethods.singleOrNull { m -> isRowRenderer(m) }
            ?: error("宿主 SidebarItemLayout 的 11 参入口不唯一或签名不符")

        // 2) 逐行 composable：唯一接受「条目包装类」的 1 参静态 @Composable
        val rowEntry = sidebar.declaredMethods.singleOrNull { m ->
            if (!JavaModifier.isStatic(m.modifiers)) return@singleOrNull false
            if (m.name != ENTRY || m.parameterCount != 3) return@singleOrNull false
            val p = m.parameterTypes
            p[0] == routeItem &&
                p[1].name == COMPOSER &&
                p[2] == java.lang.Integer.TYPE
        } ?: error("宿主 SidebarKt 的逐行入口不唯一或签名不符")

        // 3) 条目包装类上的 ScreenRoute getter：按返回类型找，避免依赖混淆方法名
        val routeGetter = routeItem.declaredMethods.singleOrNull { m ->
            m.parameterCount == 0 && m.returnType.name == SCREEN_ROUTE_CLASS
        } ?: error("宿主侧栏条目包装类上找不到 ScreenRoute getter")

        // 4) 调用方 lambda 必须真的调用那个逐行入口，否则 afterComposable 注册不上
        Diag.info("[$TAG] 逐行入口=${rowEntry.declaringClass.name}.${rowEntry.name}；调用方 lambda=${lambda.name}")

        return Abi(
            sidebarClass = SIDEBAR_CLASS,
            itemLambdaClass = ITEM_LAMBDA_CLASS,
            routeItemClass = ROUTE_ITEM_CLASS,
            entryName = ENTRY,
            rowMethod = rowMethod,
            routeGetter = routeGetter,
        )
    }

    /**
     * 行渲染器的签名判定。
     *
     * 第 6 位是宿主混淆类型（插件从不使用，走默认值），所以只校验「是引用类型」。
     */
    private fun isRowRenderer(m: Method): Boolean {
        if (!JavaModifier.isStatic(m.modifiers)) return false
        if (m.name != ENTRY || m.parameterCount != 11) return false
        val p = m.parameterTypes
        return p[0] == String::class.java &&
            p[1] == java.lang.Boolean.TYPE &&
            p[2] == Modifier::class.java &&
            p[3] == java.lang.Boolean.TYPE &&
            p[4] == Modifier::class.java &&
            p[5] == Color::class.java &&
            !p[6].isPrimitive &&
            p[7] == kotlin.jvm.functions.Function3::class.java &&
            p[8].name == COMPOSER &&
            p[9] == java.lang.Integer.TYPE &&
            p[10] == java.lang.Integer.TYPE
    }

    // ------------------------------------------------------------------
    // 参数构造（纯函数，便于单元测试逐位断言）
    // ------------------------------------------------------------------

    /**
     * 构造 `SidebarItemLayout` 的**业务参数**（8 个，不含 `Composer` 与两个尾部 int）。
     *
     * 顺序与宿主自己压栈的顺序一致；掩码置位的 4 个位置传占位值：
     * - 2 `Modifier` → null
     * - 3 `boolean` → false
     * - 5 `Color` → null
     * - 6 混淆类型 → null
     *
     * **不要在这里追加 `changed` / `defaultMask`**：它们在 `Composer` **之后**，
     * 由 [fullRowArgs] 负责。曾经把它们一起塞进这个数组，导致 `Composer` 位置收到
     * 一个 `Integer`，宿主抛 `IllegalArgumentException: argument type mismatch`
     * 并升级成 `PLUGIN_FATAL`（进程直接退出）——真机踩过。
     */
    fun rowArgs(label: String, selected: Boolean, modifier: Modifier, content: Any): Array<Any?> =
        arrayOf(label, selected, null, false, modifier, null, null, content)

    /**
     * 完整 11 参调用数组：8 个业务参数 + `Composer` + `changed` + `defaultMask`。
     *
     * 两个尾部 int 必须在 `Composer` 之后 —— 这是 Compose 编译器对所有
     * `@Composable` 的固定布局。
     *
     * `changed` 传 0 表示「按不稳定处理」：多几次重组无所谓，漏重组会让选中态卡住。
     */
    fun fullRowArgs(
        label: String,
        selected: Boolean,
        modifier: Modifier,
        content: Any,
        composer: Composer,
    ): Array<Any?> {
        val biz = rowArgs(label, selected, modifier, content)
        val args = arrayOfNulls<Any?>(biz.size + 3)
        System.arraycopy(biz, 0, args, 0, biz.size)
        args[biz.size] = composer
        args[biz.size + 1] = 0
        args[biz.size + 2] = ROW_DEFAULTS_MASK
        return args
    }

    /**
     * 逐个参数做类型预检，返回第一个不匹配的下标（-1 表示全部匹配）。
     *
     * 纯函数、不抛异常，便于单元测试。它的价值在于把「参数顺序写错」变成
     * **一条日志 + 不画这一行**，而不是宿主进程消失。
     *
     * 规则与 `Method.invoke` 一致：`null` 只能给引用类型；基本类型接受其包装类。
     */
    fun firstTypeMismatch(parameterTypes: Array<Class<*>>, args: Array<Any?>): Int {
        if (parameterTypes.size != args.size) return minOf(parameterTypes.size, args.size)
        for (i in parameterTypes.indices) {
            val want = parameterTypes[i]
            val value = args[i]
            if (value == null) {
                if (want.isPrimitive) return i
            } else if (!boxed(want).isInstance(value)) {
                return i
            }
        }
        return -1
    }

    /** 基本类型 → 包装类，其余原样返回。 */
    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }

    /**
     * 画一行原生侧栏行。
     *
     * `content` 必须是 `@Composable BoxScope.() -> Unit`（编译成 `Function3`）——
     * 接收者类型写错会在 `Method.invoke` 时 `ClassCastException`，宿主升级成
     * `PLUGIN_FATAL`。所以这个参数从 `SaltUi.SaltSidebarRow` 透传，绝不手工构造。
     */
    fun drawRow(
        label: String,
        selected: Boolean,
        modifier: Modifier,
        content: Any,
        composer: Composer,
    ) {
        val abi = cached ?: return
        val args = fullRowArgs(label, selected, modifier, content, composer)
        // 预检：类型不匹配就放弃这一行，绝不把异常抛进组合期。
        // （宿主把插件组合期异常升级成 PLUGIN_FATAL 并终止进程，没有第二次机会。）
        val bad = firstTypeMismatch(abi.rowMethod.parameterTypes, args)
        if (bad >= 0) {
            Diag.error(
                "[$TAG] 行参数类型不匹配，跳过渲染：index=$bad " +
                    "want=${abi.rowMethod.parameterTypes.getOrNull(bad)?.name} " +
                    "got=${args.getOrNull(bad)?.javaClass?.name ?: "null"}"
            )
            return
        }
        abi.rowMethod.invoke(null, *args)
    }

    // ------------------------------------------------------------------
    // 路由判定
    // ------------------------------------------------------------------

    /** 条目包装类 → 宿主 `ScreenRoute`。 */
    fun routeOf(item: Any?): Any? {
        if (item == null) return null
        val getter = cached?.routeGetter ?: return null
        return runCatching { getter.invoke(item) }.getOrNull()
    }

    /** 这一行的路由是不是「音乐库」。 */
    fun isMusicLibrary(item: Any?): Boolean = isMusicLibraryRoute(routeOf(item)?.javaClass?.name)

    /**
     * 路由类名判定。
     *
     * 必须是**整名相等**：用 `contains` 会把 `MusicLibraryExtra` 也判成真，
     * 结果是入口出现在错误的行后面（不崩，但很难排查）。
     */
    fun isMusicLibraryRoute(routeClassName: String?): Boolean =
        routeClassName != null && routeClassName == MUSIC_LIBRARY_ROUTE
}
