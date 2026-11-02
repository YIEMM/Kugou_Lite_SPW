package cn.spw.kugou.lite.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 对**真实宿主归档**跑解析，断言新架构的两个挂载点都能解析出来。
 *
 * 期望值来自 `tools/resolve_target.py` 对 1.19.3 的全量实测。
 *
 * 归档不存在时**跳过**，这样没装 SPW 的机器上 `gradle build` 也不会失败。
 *
 * ## 本测试锁定的架构决策
 *
 * 1. **页面挂载点是 `ModConfigScreen`**，唯一调用方是宿主导航分发
 *    `ComposableSingletons$AppNavigation_desktopKt` —— 只有这个位置能把插件页面
 *    画进宿主导航内容区（标题栏/侧栏/底部播放条全部保留）。
 * 2. **标题栏入口锚点是 `DebugCaptionButton`**，唯一调用方是 `CaptionButtons`
 *    —— 那一行 Row 正是「账户 / 刷新应用库」所在位置，追加的按钮是行内子项，
 *    不会与 □/✕ 重叠（这是旧浮层方案在真机上点没窗口的根因）。
 * 3. **`MainWindow` 绝不可用**：它位于 provider 树之外，注入必崩
 *    （`LocalFontFamilyResolver not present` → `PLUGIN_FATAL` → 进程退出）。
 *    用例保留「能解析」这个事实，但注释写死「不能挂载」。
 */
class HostTargetsTest {

    private val archive: File? = System.getProperty("spw.host.archive")
        ?.let(::File)
        ?.takeIf { it.isFile }

    private fun indexOrSkip(): HostTargets.Index? {
        if (archive == null) {
            println("[skip] 找不到宿主归档，跳过（设置 -Dspw.host.archive 或环境变量 SPW_HOST_ARCHIVE）")
            return null
        }
        return HostTargets.indexOf(archive!!)
    }

    @Test
    fun `ModConfigScreen 解析出唯一调用方（导航内页面挂载点）`() {
        val idx = indexOrSkip() ?: return
        val t = HostTargets.resolveByUniqueCaller(
            idx,
            "com.xuncorp.voxzen.ui.screen.workshop.ModConfigScreen",
            "ModConfigScreen"
        )
        assertNotNull(t, "ModConfigScreen 应可解析")
        // 1.19.3 实测：唯一调用方是宿主导航分发
        assertEquals(
            "com.xuncorp.voxzen.ui.navigation.ComposableSingletons\$AppNavigation_desktopKt",
            t.inClass
        )
        // 业务参数：路由 + ViewModel（ViewModel 的混淆名随版本变化，只断言个数与首项）
        assertEquals(2, t.parameterTypes.size, "业务参数应为 2 个（路由 + ViewModel）")
        assertEquals("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent", t.parameterTypes[0])
    }

    @Test
    fun `DebugCaptionButton 解析出唯一调用方（标题栏入口锚点）`() {
        val idx = indexOrSkip() ?: return
        val t = HostTargets.resolveByUniqueCaller(
            idx,
            "com.xuncorp.voxzen.ui.main.titlebar.DebugCaptionButton",
            "DebugCaptionButton"
        )
        assertNotNull(t, "DebugCaptionButton 应可解析")
        // 1.19.3 实测：唯一调用方是 CaptionButtons（U+0A88）
        // 这就是「账户 / 刷新应用库」那一条按钮 Row —— 追加按钮是行内子项。
        assertEquals("androidx.compose.ui.\u0A88", t.inClass)
        assertTrue(t.parameterTypes.isEmpty(), "DebugCaptionButton 无业务参数")
    }

    @Test
    fun `CaptionButtons 解析出唯一调用方`() {
        val idx = indexOrSkip() ?: return
        val t = HostTargets.resolveByUniqueCaller(
            idx,
            "com.xuncorp.voxzen.ui.main.titlebar.CaptionButtons",
            "CaptionButtons"
        )
        assertNotNull(t, "CaptionButtons 应可解析")
        // 1.19.3 实测：inClass = androidx.compose.ui.ୡ (U+0B61)
        assertEquals("androidx.compose.ui.\u0B61", t.inClass)
    }

    @Test
    fun `MainWindow 虽然可解析但绝不可用作挂载点`() {
        val idx = indexOrSkip() ?: return
        // 解析层面它能出结果（inClass 唯一），但那个调用点在窗口之外，
        // 不在 provider 树里 → 注入后必崩（LocalFontFamilyResolver not present）。
        // 这个用例锁住「能解析」这个事实，同时用注释说明它不能拿来挂载，
        // 防止后来的人看到 inClass 唯一就误用。
        val t = HostTargets.resolveByUniqueCaller(
            idx,
            "com.xuncorp.voxzen.ui.window.MainWindow",
            "MainWindow"
        )
        assertNotNull(t, "MainWindow 应可解析")
        // 1.19.3 实测：inClass = com.xuncorp.voxzen.desktop.Ԩ (U+0528)
        assertEquals("com.xuncorp.voxzen.desktop.\u0528", t.inClass)
        assertEquals(
            listOf("kotlin.jvm.functions.Function0", "boolean", "kotlin.jvm.functions.Function0"),
            t.parameterTypes
        )
    }

    @Test
    fun `AppNavigation 可作为兜底挂载点`() {
        val idx = indexOrSkip() ?: return
        val t = HostTargets.resolveByUniqueCaller(
            idx,
            "com.xuncorp.voxzen.ui.navigation.AppNavigation",
            "AppNavigation"
        )
        assertNotNull(t, "AppNavigation 应可解析")
        // 1.19.3 实测：inClass = androidx.compose.ui.Ɏ (U+024E)
        assertEquals("androidx.compose.ui.\u024E", t.inClass)
        assertTrue(t.parameterTypes.isEmpty(), "AppNavigation 无业务参数")
    }

    @Test
    fun `声明类本身未被混淆时直接可用`() {
        val idx = indexOrSkip() ?: return
        val t = HostTargets.resolveByUniqueCaller(
            idx,
            "com.xuncorp.voxzen.ui.navigation.AppNavigation",
            "AppNavigation"
        )
        assertNotNull(t)
        // AppNavigationKt 保留了 ASCII 原名
        assertEquals("com.xuncorp.voxzen.ui.navigation.AppNavigationKt", t.className)
        assertEquals("AppNavigation", t.methodName)
    }

    @Test
    fun `多调用方的目标返回 null 而不是猜`() {
        val idx = indexOrSkip() ?: return
        // TitleBarPillButton 有 14 个调用方类，必须拒绝解析。
        // 注意：这里必须按**完整描述符**过滤才算数 —— 只按 (owner, name) 匹配会把
        // 同名重载全部算进来，得出虚假的「多调用方」结论（Python 工具就踩过这个坑）。
        val t = HostTargets.resolveByUniqueCaller(
            idx,
            "com.xuncorp.voxzen.ui.component.TitleBarPillButton",
            "TitleBarPillButton"
        )
        assertEquals(null, t, "多调用方必须返回 null，绝不能挑一个看着像的")
    }

    @Test
    fun `BarButton 同样因多调用方被拒绝`() {
        val idx = indexOrSkip() ?: return
        val t = HostTargets.resolveByUniqueCaller(
            idx,
            "com.xuncorp.voxzen.ui.component.BarButton",
            "BarButton"
        )
        assertEquals(null, t)
    }

    @Test
    fun `找不到的目标返回 null 而不是猜`() {
        val idx = indexOrSkip() ?: return
        val t = HostTargets.resolveByUniqueCaller(
            idx,
            "com.xuncorp.voxzen.ui.navigation.NotARealClass",
            "NotARealMethod"
        )
        assertEquals(null, t)
    }
}
