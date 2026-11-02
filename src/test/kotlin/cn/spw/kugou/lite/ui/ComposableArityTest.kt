package cn.spw.kugou.lite.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 校验 [SaltUi] 包装层的 JVM 参数类型与宿主原生组件签名**逐个对齐**。
 *
 * <h2>为什么必须锁住这个</h2>
 *
 * <p>`@Composable` lambda 在 JVM 上是 `FunctionN` 实例，**N = 1 + 接收者个数 + 2**
 * （`Composer` 与 `changed: Int` 各占一个）：</p>
 *
 * ```
 * @Composable () -> Unit                         → Function2
 * @Composable BoxScope.() -> Unit                → Function3
 * @Composable ColumnScope.() -> Unit             → Function3
 * @Composable BoxScope.(PaddingValues) -> Unit   → Function4
 * ```
 *
 * <p>宿主原生组件的参数类型在字节码里写死了 N，插件必须完全一致：</p>
 *
 * | 宿主组件 | 源码接收者 | JVM 参数类型 |
 * |---|---|---|
 * | `Card` | `ColumnScope.() -> Unit` | `Function3` |
 * | `BasicCaptionButton` | `BoxScope.() -> Unit` | `Function3` |
 * | `BasicScreen` | `BoxScope.(PaddingValues) -> Unit` | `Function4` |
 *
 * <p>**真机事故**：早期把 `SaltCaptionButton` 的 content 声明成无接收者的
 * `@Composable () -> Unit`（编译成 `Function2`），而宿主要 `Function3`。
 * 宿主 `Method.invoke` 抛
 * `ClassCastException: ...KugouLiteTitleButtonKt$$Lambda cannot be cast to
 * kotlin.jvm.functions.Function3`，宿主升级成 `FATAL_FAILURE` / `PLUGIN_FATAL`
 * **直接终止进程**（`safe-mode-session.properties` 有完整记录）。</p>
 *
 * <p>这类错误**编译期不报、单测不跑就发现不了**，真机上只表现为「播放器窗口消失」。
 * 所以在这里用反射读 `SaltUiKt` 的**方法签名**来锁住 —— 反射读自己写的顶层函数
 * 是可靠的；反过来反射读 lambda 实例的接口并不可靠（lambda 走 `invokedynamic`
 * 生成合成类，接口信息拿不到，这条路已试过并放弃）。</p>
 */
class ComposableArityTest {

    /** `SaltUi.kt` 的顶层函数编译到这个类。 */
    private val uiClass: Class<*> = Class.forName("cn.spw.kugou.lite.ui.SaltUiKt")

    /**
     * 取指定名字的静态方法（顶层 `@Composable` 函数会多出 `Composer, int, int`
     * 三个尾部参数，且可能因默认参数产生重载，所以按参数个数挑选）。
     *
     * <p>参数含 value class（如 `TextOverflow`）时，Kotlin 会给函数名加
     * `-<hash>` 后缀做 JVM 名改写，并把它降级成底层类型。所以这里按
     * `startsWith` 匹配名字。</p>
     */
    private fun method(name: String, parameterCount: Int): java.lang.reflect.Method {
        val m = uiClass.declaredMethods.singleOrNull {
            it.name.startsWith(name) && it.parameterCount == parameterCount
        }
        assertNotNull(m, "$name/$parameterCount 应存在且唯一")
        return m
    }

    /** 断言第 [index] 个参数的类型名。 */
    private fun assertParam(m: java.lang.reflect.Method, index: Int, expected: String) {
        assertEquals(
            expected,
            m.parameterTypes[index].name,
            "${m.name} 第 $index 个参数应为 $expected（实际 ${m.parameterTypes[index].name}）；" +
                "错配会在真机上以 ClassCastException 崩掉宿主进程",
        )
    }

    @Test
    fun `SaltCard 的 content 是 Function3（对应宿主 ColumnScope 接收者）`() {
        // SaltCard(Modifier, Function3, Composer, int, int)
        val m = method("SaltCard", 5)
        assertParam(m, 1, "kotlin.jvm.functions.Function3")
    }

    @Test
    fun `SaltCaptionButton 的 content 是 Function3（对应宿主 BoxScope 接收者）`() {
        // SaltCaptionButton(Function0, String, Modifier, boolean, Function3, Composer, int, int)
        val m = method("SaltCaptionButton", 8)
        assertParam(m, 0, "kotlin.jvm.functions.Function0")
        assertParam(m, 4, "kotlin.jvm.functions.Function3")
    }

    @Test
    fun `SaltScreen 的 content 是 Function3（对应宿主 ColumnScope 接收者）`() {
        // SaltScreen(String, Modifier, Function0, Function3, Composer, int, int)
        val m = method("SaltScreen", 7)
        // 第 2 个参数是 onBackClick —— **普通**点击回调（不是 @Composable），
        // 所以 JVM 上是 Function0。宿主外框的 onBack 是「在返回键位置画什么」的槽位。
        assertParam(m, 2, "kotlin.jvm.functions.Function0")
        // 第 3 个参数是 content → **Function3**（ColumnScope 接收者）
        //
        // 必须对应宿主 U+0E16 的 **Function3 重载**（BasicScreenColumn）。
        // 那个重载的内部实现里才有滚动容器（ScrollableState）；Function4 重载
        // （BasicScreenBox）没有滚动容器，换成它会直接让页面失去滚动能力。
        // 真机踩过一次：为「修」一个不存在的裁切问题换成 Function4，结果不能滚了。
        assertParam(m, 3, "kotlin.jvm.functions.Function3")
    }

    @Test
    fun `SaltBackButton 的 onClick 是普通 Function0`() {
        // SaltBackButton(Function0, Composer, int)
        // 宿主 BasicScreenBackButton 的签名就是 (Function0, Composer, int)，
        // onClick 是普通回调而不是 @Composable 槽位 —— 传 composable lambda 会
        // 因 Function2 vs Function0 不匹配崩宿主。
        val m = method("SaltBackButton", 3)
        assertParam(m, 0, "kotlin.jvm.functions.Function0")
    }

    @Test
    fun `SaltItem 的 onClick 是 Function0`() {        // SaltItem(String, String, Modifier, Function0, Composer, int, int)
        val m = method("SaltItem", 7)
        assertParam(m, 3, "kotlin.jvm.functions.Function0")
    }

    @Test
    fun `SaltText 不带 lambda 参数`() {
        // SaltText-L6sJoHM(String, Modifier, int, int, Composer, int, int)
        // 注意：TextOverflow 是 value class，JVM 上降级成 int，函数名也被改写。
        val m = method("SaltText", 7)
        assertParam(m, 0, "java.lang.String")
        assertParam(m, 1, "androidx.compose.ui.Modifier")
        // 第 2 个是 maxLines（int），第 3 个是 TextOverflow 降级后的 int
        assertParam(m, 2, "int")
        assertParam(m, 3, "int")
    }
}
