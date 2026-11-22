package cn.spw.kugou.lite.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ObfClassResolver 的动态反查测试（R18/R19/R20/R21）——替代 HostSalt/HostReplacementDefaults 里的写死码点。
 */
class ObfClassResolverTest {

    private fun idx(vararg pairs: Pair<String, String>): HostTargets.Index {
        val decls = pairs.map { (original, obf) ->
            HostTargets.Decl(
                obfClass = obf,
                obfMethod = "m",
                descriptor = "(Landroidx/compose/runtime/Composer;II)V",
                original = original,
                source = original.substringAfterLast(".") + ".kt:1",
                line = 1,
            )
        }
        return HostTargets.Index(File("fake"), decls, emptyList())
    }

    /** 按原始全名反查混淆类名：命中返回 obfClass。 */
    @Test
    fun resolvesByOriginalName() {
        val i = idx("com.moriafly.salt.ui.Text.Text" to "androidx.compose.ui.\u0622")
        assertEquals("androidx.compose.ui.\u0622", ObfClassResolver.resolve(i, "com.moriafly.salt.ui.Text.Text", "Text"))
    }

    /** 查不到返回 null（降级路径由调用方记日志），不抛异常。 */
    @Test
    fun returnsNullWhenMissing() {
        val i = idx("com.moriafly.salt.ui.Text.Text" to "x")
        assertNull(ObfClassResolver.resolve(i, "com.example.Missing", "Missing"))
    }

    /** 同一原始名出现多个声明时拒绝（无法唯一定位）。 */
    @Test
    fun rejectsAmbiguousDecls() {
        val i = idx(
            "com.a.B.B" to "obf1",
            "com.a.B.B" to "obf2",
        )
        assertThrows(IllegalStateException::class.java) {
            ObfClassResolver.resolve(i, "com.a.B.B", "B")
        }
    }

    /** 缓存：同 Index 二次查询直接命中（covered indirectly——用计数器验证只扫一次）。 */
    @Test
    fun cachesPerIndex() {
        val i = idx("com.a.C.C" to "obfC")
        assertEquals("obfC", ObfClassResolver.resolve(i, "com.a.C.C", "C"))
        assertEquals("obfC", ObfClassResolver.resolve(i, "com.a.C.C", "C"))
        assertEquals("obfC", ObfClassResolver.resolve(i, "com.a.C.C", "C"))
    }

    /** 回退：键形态为 包.类.函数 时，用 包.类 + 函数名 也能命中。 */
    @Test
    fun suffixFallbackMatchesFunctionQualifiedName() {
        val decls = listOf(
            HostTargets.Decl(
                obfClass = "obfS",
                obfMethod = "m",
                descriptor = "(Landroidx/compose/runtime/Composer;II)V",
                original = "com.moriafly.salt.ui.Text.Text.Text",
                source = "Text.kt:1",
                line = 1,
            )
        )
        val i = HostTargets.Index(File("fake"), decls, emptyList())
        // 键是 包.类.函数（Text.Text.Text），按 包.类 + 函数名 查询
        assertEquals("obfS", ObfClassResolver.resolve(i, "com.moriafly.salt.ui.Text.Text", "Text"))
    }

    /** 多声明但混淆类名相同（R8 同类重载）：应返回该类名而不是拒绝。 */
    @Test
    fun sameClassMultiDeclsReturnClass() {
        val decls = listOf(
            HostTargets.Decl(
                obfClass = "obfSame",
                obfMethod = "m1",
                descriptor = "(Landroidx/compose/runtime/Composer;II)V",
                original = "com.moriafly.salt.ui.Text.Text",
                source = "Text.kt:1",
                line = 1,
            ),
            HostTargets.Decl(
                obfClass = "obfSame",
                obfMethod = "m2",
                descriptor = "(Landroidx/compose/runtime/Composer;IIII)V",
                original = "com.moriafly.salt.ui.Text.Text",
                source = "Text.kt:9",
                line = 9,
            ),
        )
        val i = HostTargets.Index(File("fake"), decls, emptyList())
        assertEquals("obfSame", ObfClassResolver.resolve(i, "com.moriafly.salt.ui.Text", "Text"))
    }
}
