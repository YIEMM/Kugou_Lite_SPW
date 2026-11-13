package cn.spw.kugou.lite.host

import androidx.compose.ui.Modifier
import java.io.File
import java.lang.reflect.Modifier as JavaModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 宿主侧栏入口的 ABI 与参数约定。
 *
 * <h2>为什么这里必须锁死参数顺序和默认掩码</h2>
 *
 * <p>侧栏行是用 `Method.invoke` 调宿主 `SidebarItemLayout` 画的。它 11 个参数里有
 * 4 个默认值（`Modifier` / `boolean` / `Color` / 一个混淆类型），宿主靠末尾的
 * `defaultMask` 决定读哪个默认值。**掩码错一位 = 宿主解引用 null 或读到错误默认值**，
 * 而宿主把插件组合期异常升级成 `PLUGIN_FATAL` 并直接终止进程 ——
 * 真机表现是「一点侧栏，播放器消失」。</p>
 *
 * <p>所以参数数组的构造被抽成纯函数 [SidebarHost.rowArgs]，在这里逐位断言。
 * 期望值来自对 1.19.3 `SidebarKt` 的 javap 实证：宿主自己画侧栏行时
 * `invokestatic` 前面的压栈顺序是
 * `label, selected, aconst_null, iconst_0, clickableModifier, aconst_null, aconst_null, content`
 * 且掩码是 `bipush 108`。</p>
 *
 * <h2>为什么路由判定要单独测</h2>
 *
 * <p>钩子挂在「每个侧栏条目」的 lambda 上（`SidebarKt$items$4`），宿主**每画一行都会调一次**。
 * 只有当前这一行的路由是 `ScreenRoute.MusicLibrary` 时才追加我们的行。
 * 判定错了的后果不是崩溃，而是「酷狗入口出现在错误的行后面」或「不出现」——
 * 这两种都很难从日志上看出来。</p>
 */
class SidebarAbiTest {

    private val archive: File? = System.getProperty("spw.host.archive")
        ?.let(::File)
        ?.takeIf { it.isFile }

    /**
     * 用宿主归档建一个只读 ClassLoader。
     *
     * 父加载器必须是插件自己的 ClassLoader —— Compose / Kotlin 标准库类必须两边
     * 是**同一批 `Class` 对象**，否则 `Modifier` / `Composer` 的类型比较会全部失败。
     */
    private fun hostLoader(): ClassLoader? {
        val file = archive ?: return null
        return java.net.URLClassLoader(
            arrayOf(file.toURI().toURL()),
            SidebarAbiTest::class.java.classLoader,
        )
    }

    // ------------------------------------------------------------------
    // 1. 参数数组与默认掩码
    // ------------------------------------------------------------------

    @Test
    fun `业务参数按宿主压栈顺序排列`() {
        val modifier = Modifier
        val content = Any()
        val args = SidebarHost.rowArgs("酷狗音乐", true, modifier, content)

        assertEquals(8, args.size, "业务参数 8 个；Composer 与两个 int 不在其中")
        assertEquals("酷狗音乐", args[0], "第 0 位是标题")
        assertEquals(true, args[1], "第 1 位是选中态")
        assertEquals(null, args[2], "第 2 位是默认 Modifier（掩码置位，占位 null）")
        assertEquals(false, args[3], "第 3 位是默认 boolean（占位 false）")
        assertSame(modifier, args[4], "第 4 位才是可点击的行 Modifier")
        assertEquals(null, args[5], "第 5 位是默认 Color（占位 null）")
        assertEquals(null, args[6], "第 6 位是默认的混淆类型（占位 null）")
        assertSame(content, args[7], "第 7 位是 BoxScope 内容 lambda")
    }

    /**
     * 这一条是**真机事故的回归测试**。
     *
     * 早期把 `changed` / `defaultMask` 也放进了业务参数数组，于是 `Composer` 的
     * 位置收到了一个 `Integer` —— 宿主抛 `IllegalArgumentException: argument
     * type mismatch`，升级成 `PLUGIN_FATAL`，**播放器进程直接消失**。
     *
     * 所以必须锁死：第 8 位是 `Composer`，第 9/10 位才是两个 int。
     */
    @Test
    fun `完整参数把 Composer 放在第 8 位而不是 int`() {
        val composer = fakeComposer()
        val args = SidebarHost.fullRowArgs("酷狗音乐", false, Modifier, Any(), composer)

        assertEquals(11, args.size)
        assertSame(composer, args[8], "第 8 位必须是 Composer")
        assertEquals(0, args[9], "第 9 位是 changed")
        assertEquals(SidebarHost.ROW_DEFAULTS_MASK, args[10], "第 10 位是 defaultMask")
    }

    @Test
    fun `类型预检能指出错位的下标`() {
        val types = arrayOf<Class<*>>(
            String::class.java,
            java.lang.Boolean.TYPE,
            androidx.compose.ui.Modifier::class.java,
            java.lang.Boolean.TYPE,
            androidx.compose.ui.Modifier::class.java,
            androidx.compose.ui.graphics.Color::class.java,
            Any::class.java,
            kotlin.jvm.functions.Function3::class.java,
            androidx.compose.runtime.Composer::class.java,
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
        )
        val composer = fakeComposer()
        // 内容位必须是真正的 Function3，否则预检会正确地把它判为错位。
        // Kotlin 的函数字面量不是 Function3（不做 FunctionN 的 SAM 转换），显式实现它。
        val content = object : kotlin.jvm.functions.Function3<Any?, Any?, Any?, Unit> {
            override fun invoke(p1: Any?, p2: Any?, p3: Any?) = Unit
        }
        val good = SidebarHost.fullRowArgs("x", true, Modifier, content, composer)
        assertEquals(-1, SidebarHost.firstTypeMismatch(types, good), "正确布局应无错位")

        // 把 composer 位置塞成 int —— 正是那次事故的形态
        val broken = good.copyOf()
        broken[8] = 0
        assertEquals(8, SidebarHost.firstTypeMismatch(types, broken))

        // null 给基本类型也不行
        val nullForPrimitive = good.copyOf()
        nullForPrimitive[9] = null
        assertEquals(9, SidebarHost.firstTypeMismatch(types, nullForPrimitive))

        // 个数不对也要能发现
        assertEquals(10, SidebarHost.firstTypeMismatch(types, good.copyOf(10)))
    }

    /** `Composer` 是接口，用动态代理造一个占位实例。 */
    private fun fakeComposer(): androidx.compose.runtime.Composer =
        java.lang.reflect.Proxy.newProxyInstance(
            androidx.compose.runtime.Composer::class.java.classLoader,
            arrayOf(androidx.compose.runtime.Composer::class.java),
        ) { _, _, _ -> null } as androidx.compose.runtime.Composer

    @Test
    fun `默认掩码恰好标记 2 3 5 6 四位`() {
        val mask = SidebarHost.ROW_DEFAULTS_MASK
        // 与宿主 javap 实证的 bipush 108 一致；写死这个数是为了让「掩码被改」
        // 变成一条读得懂的失败信息，而不是真机上的进程消失。
        assertEquals(108, mask, "宿主 SidebarKt 自己调用时用的就是 108")
        val bits = (0 until 11).filter { (mask shr it) and 1 == 1 }
        assertEquals(listOf(2, 3, 5, 6), bits)
    }

    // ------------------------------------------------------------------
    // 2. 路由判定
    // ------------------------------------------------------------------

    @Test
    fun `只有音乐库这一行才追加酷狗入口`() {
        assertTrue(
            SidebarHost.isMusicLibraryRoute("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$MusicLibrary"),
            "宿主的「音乐库」行是唯一锚点：酷狗入口紧跟在它后面"
        )
        assertFalse(
            SidebarHost.isMusicLibraryRoute("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$Settings")
        )
        assertFalse(
            SidebarHost.isMusicLibraryRoute("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$Album")
        )
        assertFalse(SidebarHost.isMusicLibraryRoute(null))
        assertFalse(SidebarHost.isMusicLibraryRoute(""))
    }

    @Test
    fun `路由判定不能被子串骗过`() {
        // 简单 contains 会把「MusicLibraryXxx」也判成真；必须整名相等。
        assertFalse(
            SidebarHost.isMusicLibraryRoute(
                "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$MusicLibraryExtra"
            )
        )
        assertFalse(
            SidebarHost.isMusicLibraryRoute(
                "evil.com.xuncorp.voxzen.ui.navigation.ScreenRoute\$MusicLibrary"
            )
        )
    }

    // ------------------------------------------------------------------
    // 3. 对真实宿主归档解析
    // ------------------------------------------------------------------

    @Test
    fun `真实宿主里能解析出侧栏三个目标`() {
        val loader = hostLoader()
        if (loader == null) {
            println("[skip] 找不到宿主归档，跳过（设置 -Dspw.host.archive 或环境变量 SPW_HOST_ARCHIVE）")
            return
        }
        val abi = SidebarHost.resolve(loader)

        assertNotNull(abi, "侧栏 ABI 解析失败：" + SidebarHost.lastError)
        assertEquals("androidx.compose.ui.\u0B1A", abi.sidebarClass, "SidebarKt 的混淆名")
        assertEquals("androidx.compose.ui.\u0B1A\$\u052E", abi.itemLambdaClass, "逐行 lambda 类")
        assertEquals("androidx.compose.ui.\u063A\$\u052A", abi.routeItemClass, "侧栏条目包装类")
        assertEquals("\u037F", abi.entryName, "宿主顶层 @Composable 被 R8 后的统一入口名")

        // 行渲染器的签名必须与 javap 一致
        val row = abi.rowMethod
        assertEquals(11, row.parameterCount)
        assertEquals(String::class.java, row.parameterTypes[0])
        assertEquals(java.lang.Boolean.TYPE, row.parameterTypes[1])
        assertEquals(androidx.compose.ui.Modifier::class.java, row.parameterTypes[2])
        assertEquals(java.lang.Boolean.TYPE, row.parameterTypes[3])
        assertEquals(androidx.compose.ui.Modifier::class.java, row.parameterTypes[4])
        assertEquals(androidx.compose.ui.graphics.Color::class.java, row.parameterTypes[5])
        assertFalse(row.parameterTypes[6].isPrimitive, "第 6 位是宿主混淆类型，按引用类型校验")
        assertEquals(kotlin.jvm.functions.Function3::class.java, row.parameterTypes[7])
        assertEquals(androidx.compose.runtime.Composer::class.java, row.parameterTypes[8])
        assertEquals(java.lang.Integer.TYPE, row.parameterTypes[9])
        assertEquals(java.lang.Integer.TYPE, row.parameterTypes[10])
        assertTrue(JavaModifier.isStatic(row.modifiers))
    }

    @Test
    fun `真实宿主里音乐库路由类是稳定的未混淆名`() {
        val loader = hostLoader()
        if (loader == null) {
            println("[skip] 找不到宿主归档，跳过")
            return
        }
        val cls = Class.forName(
            "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$MusicLibrary", false, loader
        )
        assertTrue(
            SidebarHost.isMusicLibraryRoute(cls.name),
            "ScreenRoute 是宿主自己的密封类，名字不参与混淆 —— 与 ModConfigContent 同理"
        )
    }
}
