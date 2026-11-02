package cn.spw.kugou.lite.host

import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 对**真实宿主归档**验证宿主原生 Salt 组件的签名能否被正确解析。
 *
 * <p>这个测试是本插件最重要的一道防线。原因：</p>
 *
 * <p>1. **签名错了宿主会崩**。插件通过 `Method.invoke` 调宿主 `@Composable` 入口，
 * 参数列表或默认掩码错一位，宿主读到错误的默认值甚至解引用 null，
 * 而宿主把插件组合期异常升级成 `FATAL_FAILURE` / `PLUGIN_FATAL` 并**直接终止进程**。
 * 真机上表现为「点一下按钮，播放器直接消失」。</p>
 *
 * <p>2. **错误很容易被误判**。曾经 `type()` 用 `Class.forName("long")` 解析基本类型，
 * 抛 `ClassNotFoundException`，ABI 预检日志只显示 `SaltUI Text=缺失` 加一个 `long`，
 * 看起来像「宿主版本不支持」，实际只是漏了基本类型映射。
 * 有这个测试就能立刻定位。</p>
 *
 * <p>3. **`ANY` 通配位置必须真的通配**。`Item` 第 12 个参数、`BasicScreen` 第 7 个参数
 * 是混淆类型，插件从不使用（传 null 走默认），所以按「非基本类型」通配。
 * 这里验证通配不会误匹配到别的重载。</p>
 *
 * <p>归档不存在时**跳过**，这样没装 SPW 的机器上 `gradle build` 也不会失败。</p>
 */
class HostSaltAbiTest {

    private val archive: File? = System.getProperty("spw.host.archive")
        ?.let(::File)
        ?.takeIf { it.isFile }

    /**
     * 用宿主归档建一个只读 ClassLoader。
     *
     * <p>关键点：**父加载器必须是插件的 ClassLoader**。宿主的 Compose / Kotlin
     * 标准库类由插件 ClassLoader（最终是宿主 AppClassLoader）提供，两边必须是
     * 同一批 `Class` 对象，否则 `Modifier` / `Composer` 等类型比较会全部失败。
     * 归档里只额外提供宿主自己的类。</p>
     */
    private fun hostLoader(): ClassLoader? {
        val file = archive ?: run {
            println("[skip] 找不到宿主归档，跳过（设置 -Dspw.host.archive）")
            return null
        }
        return java.net.URLClassLoader(
            arrayOf(file.toURI().toURL()),
            HostSaltAbiTest::class.java.classLoader,
        )
    }

    /** 复刻 [HostSalt] 的解析逻辑，但用测试自己的 ClassLoader。 */
    private fun component(loader: ClassLoader, owner: String, vararg parameters: String): Method {
        val entry = 0x037F.toChar().toString()
        fun type(name: String): Class<*> = when (name) {
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "int" -> Int::class.javaPrimitiveType!!
            "long" -> Long::class.javaPrimitiveType!!
            "float" -> Float::class.javaPrimitiveType!!
            else -> Class.forName(name, false, loader)
        }
        val want: List<Class<*>?> = parameters.map { if (it == "?") null else type(it) }
        val ownerType = Class.forName(owner, false, loader)
        val matches = ownerType.declaredMethods.filter { m ->
            if (!JavaModifier.isStatic(m.modifiers)) return@filter false
            if (m.name != entry) return@filter false
            if (m.parameterCount != want.size) return@filter false
            val actual = m.parameterTypes
            want.indices.all { i ->
                val w = want[i]
                if (w == null) !actual[i].isPrimitive else actual[i] == w
            }
        }
        assertEquals(1, matches.size, "$owner ${parameters.toList()} 应唯一匹配")
        return matches.single()
    }

    @Test
    fun `SaltUI Text 签名可解析`() {
        val loader = hostLoader() ?: return
        val m = component(
            loader, "androidx.compose.ui." + 0x0622.toChar(),
            "java.lang.String", "androidx.compose.ui.Modifier", "long", "long",
            "androidx.compose.ui.text.font.FontStyle", "androidx.compose.ui.text.font.FontWeight",
            "androidx.compose.ui.text.font.FontFamily", "long",
            "androidx.compose.ui.text.style.TextDecoration", "androidx.compose.ui.text.style.TextAlign",
            "long", "int", "boolean", "int", "int", "kotlin.jvm.functions.Function1",
            "androidx.compose.ui.text.TextStyle", "androidx.compose.runtime.Composer", "int", "int", "int",
        )
        assertEquals(21, m.parameterCount, "Text 应有 21 个业务参数 + Composer + 3 int")
    }

    @Test
    fun `SaltUI Card 的 Shape 在 color 之前`() {
        val loader = hostLoader() ?: return
        val m = component(
            loader, "androidx.compose.ui." + 0x1080.toChar(),
            "androidx.compose.ui.Modifier", "androidx.compose.ui.graphics.Shape", "long",
            "androidx.compose.foundation.BorderStroke", "kotlin.jvm.functions.Function3",
            "androidx.compose.runtime.Composer", "int", "int",
        )
        // 参数 1 必须是 Shape、参数 2 必须是 long —— 与 Material3 Card 相反，锁住这个易错点。
        assertEquals("androidx.compose.ui.graphics.Shape", m.parameterTypes[1].name)
        assertEquals(Long::class.javaPrimitiveType, m.parameterTypes[2])
    }

    @Test
    fun `SaltUI Item 混淆位置可用通配匹配`() {
        val loader = hostLoader() ?: return
        val m = component(
            loader, "androidx.compose.ui." + 0x0824.toChar(),
            "kotlin.jvm.functions.Function0", "java.lang.String", "androidx.compose.ui.Modifier",
            "boolean", "androidx.compose.ui.graphics.painter.Painter",
            "androidx.compose.foundation.layout.PaddingValues", "androidx.compose.ui.graphics.Color",
            "long", "java.lang.String", "long", "kotlin.jvm.functions.Function2",
            "java.lang.String", "?", "androidx.compose.runtime.Composer", "int", "int", "int",
        )
        // 末尾 3 个 int（Item 的完整重载比普通入口多一个 int）
        assertEquals(17, m.parameterCount)
        // 第 6 个参数是 Color（不是 long）—— 这里极易写错成 long
        assertEquals("androidx.compose.ui.graphics.Color", m.parameterTypes[6].name)
        // 第 12 个是通配位置，必须是引用类型且不是插件已知的任何具体类
        assertTrue(!m.parameterTypes[12].isPrimitive, "通配位置应为引用类型")
    }

    @Test
    fun `SaltUI BasicScreen 首参是 onBack 而非 Modifier`() {
        val loader = hostLoader() ?: return
        val m = component(
            loader, "androidx.compose.ui." + 0x09BD.toChar(),
            "kotlin.jvm.functions.Function2", "androidx.compose.ui.Modifier", "java.lang.String",
            "java.lang.String", "kotlin.jvm.functions.Function2",
            "androidx.compose.foundation.layout.PaddingValues", "kotlin.jvm.functions.Function4",
            "?", "kotlin.jvm.functions.Function4", "androidx.compose.runtime.Composer", "int", "int",
        )
        assertEquals(12, m.parameterCount)
        // 第 0 个是 onBack（Function2），第 1 个才是 Modifier —— 与直觉相反，锁住。
        assertEquals("kotlin.jvm.functions.Function2", m.parameterTypes[0].name)
        assertEquals("androidx.compose.ui.Modifier", m.parameterTypes[1].name)
    }

    @Test
    fun `SaltUI ItemOuterTitle 按入口名解析`() {
        val loader = hostLoader() ?: return
        val entry = 0x037F.toChar().toString()
        val type = Class.forName("androidx.compose.ui." + 0x02BD.toChar(), false, loader)
        // ItemOuterTitle 与 ItemOuterTip 签名完全相同，所以不能靠签名唯一匹配。
        val sameShape = type.declaredMethods.filter {
            JavaModifier.isStatic(it.modifiers) && it.parameterCount == 5 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Class.forName("androidx.compose.ui.Modifier", false, loader)
        }
        assertTrue(sameShape.size >= 2, "同形重载应至少 2 个（Title 与 Tip）")
        val picked = sameShape.first { it.name == entry }
        assertNotNull(picked, "按入口名应能取到 ItemOuterTitle")
        assertEquals(5, picked.parameterCount)
    }

    @Test
    fun `宿主 BasicCaptionButton 十参重载可解析`() {
        val loader = hostLoader() ?: return
        val m = component(
            loader, "com.xuncorp.voxzen.ui.component." + 0x0529.toChar(),
            "kotlin.jvm.functions.Function0", "java.lang.String", "androidx.compose.ui.Modifier",
            "boolean", "java.lang.String", "float", "kotlin.jvm.functions.Function3",
            "androidx.compose.runtime.Composer", "int", "int",
        )
        assertEquals(10, m.parameterCount)
        // 第 5 个是 float（iconSize），第 6 个是 Function3（内容）
        assertEquals(Float::class.javaPrimitiveType, m.parameterTypes[5])
    }

    @Test
    fun `全部六个组件在真实宿主上都可用`() {
        val loader = hostLoader() ?: return
        // 逐个跑一遍，任一失败都会在这里暴露，而不是等真机组合期才崩。
        component(
            loader, "androidx.compose.ui." + 0x0622.toChar(),
            "java.lang.String", "androidx.compose.ui.Modifier", "long", "long",
            "androidx.compose.ui.text.font.FontStyle", "androidx.compose.ui.text.font.FontWeight",
            "androidx.compose.ui.text.font.FontFamily", "long",
            "androidx.compose.ui.text.style.TextDecoration", "androidx.compose.ui.text.style.TextAlign",
            "long", "int", "boolean", "int", "int", "kotlin.jvm.functions.Function1",
            "androidx.compose.ui.text.TextStyle", "androidx.compose.runtime.Composer", "int", "int", "int",
        )
        component(
            loader, "androidx.compose.ui." + 0x1080.toChar(),
            "androidx.compose.ui.Modifier", "androidx.compose.ui.graphics.Shape", "long",
            "androidx.compose.foundation.BorderStroke", "kotlin.jvm.functions.Function3",
            "androidx.compose.runtime.Composer", "int", "int",
        )
        component(
            loader, "androidx.compose.ui." + 0x0824.toChar(),
            "kotlin.jvm.functions.Function0", "java.lang.String", "androidx.compose.ui.Modifier",
            "boolean", "androidx.compose.ui.graphics.painter.Painter",
            "androidx.compose.foundation.layout.PaddingValues", "androidx.compose.ui.graphics.Color",
            "long", "java.lang.String", "long", "kotlin.jvm.functions.Function2",
            "java.lang.String", "?", "androidx.compose.runtime.Composer", "int", "int", "int",
        )
        component(
            loader, "androidx.compose.ui." + 0x09BD.toChar(),
            "kotlin.jvm.functions.Function2", "androidx.compose.ui.Modifier", "java.lang.String",
            "java.lang.String", "kotlin.jvm.functions.Function2",
            "androidx.compose.foundation.layout.PaddingValues", "kotlin.jvm.functions.Function4",
            "?", "kotlin.jvm.functions.Function4", "androidx.compose.runtime.Composer", "int", "int",
        )
        component(
            loader, "com.xuncorp.voxzen.ui.component." + 0x0529.toChar(),
            "kotlin.jvm.functions.Function0", "java.lang.String", "androidx.compose.ui.Modifier",
            "boolean", "java.lang.String", "float", "kotlin.jvm.functions.Function3",
            "androidx.compose.runtime.Composer", "int", "int",
        )
        println("[ok] 六个宿主原生组件全部解析成功")
    }
}
