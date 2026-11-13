package cn.spw.kugou.lite.host

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.ui.KugouSidebarRow
import cn.spw.kugou.lite.ui.PageState

/**
 * 左侧栏入口注入：在宿主「音乐库」那一行后面追加一个原生「酷狗音乐」行。
 *
 * ## 钩子的粒度为什么是「每一行」
 *
 * `afterComposable` 的目标不是整个 `Sidebar`，而是 `SidebarKt` 里那个
 * **逐行 `@Composable`**，它的调用方是 `LazyListScope.items()` 的内容 lambda
 * （`SidebarKt$items$4`）。宿主每画一行就调这个 lambda 一次，于是
 * `Content(call)` 里 `call.arguments[0]` 就是**当前这一行**的条目对象。
 *
 * 这是「精确插到某一行的后面」的唯一办法：如果钩整个 `Sidebar`，一次组合只能
 * 拿到一次调用，就得自己重排全部行。
 *
 * ## 为什么只对「音乐库」追加
 *
 * 用户要的是「跟音乐库并列的固定位置」。宿主自己的行顺序由数据决定
 * （音乐库 / 歌单 / 专辑 / …），其中「音乐库」是唯一稳定存在的锚点，
 * 而且它在视觉上就是「本地内容」分组的开头，品牌入口紧跟其后最自然。
 *
 * ## 为什么要用 `Column` 包一层
 *
 * 一个 `LazyColumn` item 里追加多个元素时必须自己包容器 ——
 * item 的多个 placeable 是垂直堆叠的，但不包的话我们这一行会与宿主的行
 * 平级铺开、失去「行内紧跟其后」的关系。
 *
 * ## 失败策略
 *
 * 全部符号在 [SidebarHost.resolve] 里预检；任一缺失就整体放弃并只记日志。
 * 侧栏入口是**可选增强**：没有它，用户仍可从标题栏按钮进入页面。
 */
object SidebarInjection {

    private const val TAG = "sidebar"

    private var handle: HookHandle? = null

    /**
     * 注册侧栏条目。**幂等**，失败返回 false 且不抛异常。
     *
     * 必须在 `PageInjection.install()` 里、页面 hook 之后调用：
     * 点击条目要导航到 `ModConfigContent`，而那个路由要能渲染出我们的页面。
     */
    fun install(): Boolean {
        if (handle != null) {
            Diag.info("[$TAG] 已安装，跳过")
            return true
        }
        return runCatching {
            val abi = SidebarHost.resolve()
            if (abi == null) {
                Diag.error("[$TAG] 侧栏 ABI 不可用，跳过入口注入：" + SidebarHost.lastError)
                return false
            }
            handle = WorkshopApi.hookRegistrar.afterComposable(
                className = abi.sidebarClass,
                methodName = abi.entryName,
                inClass = abi.itemLambdaClass,
                parameterTypes = listOf(abi.routeItemClass),
                priority = 100,
                content = RowHook,
            )
            PageState.sidebarInstalled.value = true
            Diag.info("[$TAG] afterComposable 注册成功（侧栏逐行入口）")
            true
        }.onFailure {
            Diag.error("[$TAG] 注册失败", it)
        }.getOrDefault(false)
    }

    /** 卸载。**幂等**。 */
    fun uninstall() {
        runCatching { handle?.unhook() }.onFailure { Diag.error("[$TAG] 卸载异常", it) }
        handle = null
        PageState.sidebarInstalled.value = false
        Diag.info("[$TAG] 已卸载")
    }

    /**
     * 逐行钩子。
     *
     * <p>**组合期绝不能抛异常**：宿主把插件组合期异常升级成 `FATAL_FAILURE` /
     * `PLUGIN_FATAL` 并终止进程，而 Compose 编译器禁止在 `@Composable` 调用外层
     * 包 try-catch。所以这里只做「读参数 → 判路由 → 画或不画」，所有可能失败的
     * 反射都在安装期解析好。</p>
     *
     * <p>`NavigatorBridge.rememberHostNavigator()` 必须调：它顺便把宿主 `Navigator`
     * 缓存下来，用户点击时 `PageState.requestOpen` 才有东西可用。侧栏就在宿主导航
     * 组合树内，所以这里读得到 `LocalNavigator`。</p>
     */
    private object RowHook : ComposableHook() {
        @Composable
        override fun Content(call: UiHookCall) {
            NavigatorBridge.rememberHostNavigator()
            val item = runCatching { call.arguments.firstOrNull() }.getOrNull()
            if (!SidebarHost.isMusicLibrary(item)) return
            // 一个 LazyColumn item 里追加内容必须自己包容器，否则与宿主行平级铺开。
            Column { KugouSidebarRow() }
        }
    }
}
