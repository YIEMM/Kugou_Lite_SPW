package cn.spw.kugou.lite.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import cn.spw.kugou.lite.diag.Diag

/**
 * 宿主导航桥：从宿主的 `CompositionLocal` 里读 `Navigator`，用它把酷狗页面
 * **导航进宿主导航栈**。
 *
 * ## 为什么必须用宿主自己的 Navigator
 *
 * 宿主 UI 全部是 Compose 绘制的，没有可用的原生窗口/视图层级能「塞进去」。
 * 唯一正确的进入方式是走宿主自己的导航栈 —— 这样页面才会作为**真正的二级页面**
 * 出现在导航里：标题栏、侧栏、底部播放条全部保留，返回键走宿主 `back()`，
 * 与宿主「设置 → 关于」这类页面完全一致。
 *
 * ## 为什么复用 ModConfigContent 路由
 *
 * 宿主的「模块管理 → 配置」本身就是
 * `navigate(ScreenRoute.ModConfigContent(pluginId))`，然后在导航分发里调
 * `ModConfigScreen(route, viewModel)`。本插件已经用 `replaceComposable` 接管了那个
 * 调用点，并且**只在 route.getPluginId() 等于本插件 id 时渲染自己的页面**，
 * 其它插件的配置页照常执行宿主原组件。
 *
 * 所以点击标题栏按钮时，只要导航到 `ModConfigContent("cn.spw.kugou.lite")`，
 * 宿主就会自动把该路由分发到我们接管的调用点，渲染酷狗页面。
 * **不需要新增任何路由类型，也不碰宿主导航的内部结构。**
 *
 * ## 怎么拿到 Navigator（关键）
 *
 * **`rememberNavigator` 是 `@Composable`，`hookMethod` 会直接拒绝**：
 * 实测抛出
 * `IllegalArgumentException: Use a Compose hook for
 * com.xuncorp.voxzen.ui.navigation.NavigatorKt.rememberNavigator`。
 * 所以不能走 method hook 这条路。
 *
 * 正确做法是宿主自己暴露的 `CompositionLocal`：
 * `AppNavigationKt.getLocalNavigator()` 返回 `CompositionLocal<Navigator>`。
 * 在**任意处于宿主导航组合树内**的 composable 里 `LocalNavigator.current` 就能读到
 * 当前 Navigator。本插件挂在 `ModConfigScreen` 调用点和标题栏 `CaptionButtons` 里，
 * 两者都在宿主组合树内，所以都能读。
 *
 * `getLocalNavigator` 是 `AppNavigationKt` 上的静态方法，而 `AppNavigationKt`
 * 在 1.19.3 保留了 ASCII 原名（不像其它 `*Kt` 类被混淆），所以可以按类名反射取。
 *
 * ## 为什么不 hook `Navigator.navigate` 去抓实例
 *
 * 也可以（参考实现里有这么做的），但那样拿到的是**调用时**的实例，而我们需要的是
 * **点击时**可用的实例。用 CompositionLocal 在组合期读一次并缓存，
 * 点击时直接用缓存值，时序更简单、也不会因为 hook 没装好而失效。
 */
object NavigatorBridge {

    private const val TAG = "nav"

    private const val APP_NAV_KT = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"
    private const val GET_LOCAL_NAVIGATOR = "getLocalNavigator"

    /** 宿主 `ScreenRoute$ModConfigContent` 的二进制类名（`$` 需转义）。 */
    private const val ROUTE_CLASS =
        "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent"

    /**
     * 宿主的 `CompositionLocal<Navigator>`。
     *
     * `null` 表示还没解析成功（或宿主版本不支持）。解析失败只记日志，
     * 不影响标题栏按钮与页面渲染 —— 用户仍可从「模块管理 → 配置」进入。
     */
    private val localNavigator: CompositionLocal<Any?>? by lazy { resolveLocal() }

    private fun resolveLocal(): CompositionLocal<Any?>? = try {
        val getter = Class.forName(APP_NAV_KT, false, HostTargets::class.java.classLoader)
            .getMethod(GET_LOCAL_NAVIGATOR)
        @Suppress("UNCHECKED_CAST")
        (getter.invoke(null) as CompositionLocal<Any?>).also {
            Diag.info("[$TAG] 已解析 LocalNavigator")
        }
    } catch (t: Throwable) {
        Diag.warn("[$TAG] 解析 LocalNavigator 失败：" + (t.message ?: t.javaClass.simpleName))
        null
    }

    /** 最新读到的 Navigator 实例。写入/读取都在组合线程，但仍用 volatile 保守。 */
    @Volatile
    private var navigator: Any? = null

    /** 路由构造器缓存：每次点击都反射查找很慢，且失败点更分散。 */
    @Volatile
    private var routeConstructor: java.lang.reflect.Constructor<*>? = null

    /** 是否已经拿到 Navigator。配置页/自检信息要读它。 */
    fun isReady(): Boolean = navigator != null

    /**
     * 在组合期读一次 Navigator 并缓存。
     *
     * **必须在 composable 上下文调用**（因为要读 `CompositionLocal.current`）。
     * 本插件的两个挂载点（页面 hook、标题栏 hook）都在宿主组合树内，所以都能调。
     *
     * 用 `remember` 只是为了减少重复读取；即使不 remember，每次重组读一次
     * `current` 也是安全的 —— CompositionLocal 读取本身是廉价的快照读取。
     */
    @Composable
    fun rememberHostNavigator(): Any? {
        val local = localNavigator ?: return null
        val value = local.current
        if (value != null && navigator !== value) {
            navigator = value
            Diag.info("[$TAG] 已缓存宿主 Navigator：" + value.javaClass.name)
        }
        return value
    }

    fun uninstall() {
        navigator = null
        routeConstructor = null
    }

    /**
     * 打开酷狗页面：导航到 `ModConfigContent(插件 id)`。
     *
     * 失败只记日志、不抛异常 —— 这是用户点击触发的路径，抛出去会变成宿主的
     * 未捕获异常。返回是否成功，方便自检信息里体现。
     */
    fun openPage(): Boolean {
        val nav = navigator
        if (nav == null) {
            Diag.warn("[$TAG] 尚未拿到 Navigator，无法打开页面（宿主可能还未完成首次组合）")
            return false
        }
        return try {
            val route = newRoute() ?: return false
            val navigate = nav.javaClass.methods.firstOrNull {
                it.name == "navigate" && it.parameterCount == 1
            }
            if (navigate == null) {
                Diag.warn("[$TAG] Navigator 上找不到 navigate(ScreenRoute)")
                return false
            }
            navigate.isAccessible = true
            navigate.invoke(nav, route)
            Diag.info("[$TAG] 已导航到 ModConfigContent(" + PageInjection.PLUGIN_ID + ")")
            true
        } catch (t: Throwable) {
            // 反射调用宿主方法必须拆包，否则日志只剩 InvocationTargetException。
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Diag.warn("[$TAG] 导航失败：" + cause)
            false
        }
    }

    /**
     * 返回上一页。
     *
     * 宿主 `Navigator.back()` 是无参方法，直接反射调用即可。
     * 用于页面左上角返回按钮 —— 宿主所有二级页面都有这个按钮，
     * 我们的页面必须一致，否则用户进了页面出不去（只能点侧栏）。
     */
    fun back(): Boolean {
        val nav = navigator
        if (nav == null) {
            Diag.warn("[$TAG] 尚未拿到 Navigator，无法返回")
            return false
        }
        return try {
            val back = nav.javaClass.methods.firstOrNull {
                it.name == "back" && it.parameterCount == 0
            }
            if (back == null) {
                Diag.warn("[$TAG] Navigator 上找不到 back()")
                return false
            }
            back.isAccessible = true
            back.invoke(nav)
            Diag.info("[$TAG] 已返回上一页")
            true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Diag.warn("[$TAG] 返回失败：" + cause)
            false
        }
    }

    /** 构造 `ModConfigContent("cn.spw.kugou.lite")`。 */
    private fun newRoute(): Any? {
        val ctor = routeConstructor ?: run {
            val found = try {
                Class.forName(ROUTE_CLASS, false, HostTargets::class.java.classLoader)
                    .declaredConstructors
                    .firstOrNull {
                        it.parameterCount == 1 &&
                            it.parameterTypes[0] == String::class.java
                    }
            } catch (t: Throwable) {
                Diag.warn("[$TAG] 找不到 ScreenRoute\$ModConfigContent：" + t)
                null
            }
            if (found == null) return null
            found.isAccessible = true
            routeConstructor = found
            found
        }
        return try {
            ctor.newInstance(PageInjection.PLUGIN_ID)
        } catch (t: Throwable) {
            Diag.warn("[$TAG] 构造 ModConfigContent 失败：" + t)
            null
        }
    }
}
