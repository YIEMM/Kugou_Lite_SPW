package cn.spw.kugou.lite.ui

import androidx.compose.runtime.mutableStateOf
import cn.spw.kugou.lite.host.NavigatorBridge

/**
 * 页面与入口的安装状态，以及「打开酷狗页面」的入口。
 *
 * 与早期 `OverlayState` 的区别：这里描述的是「注入是否成功」以及「当前是否处于
 * 酷狗页面」，不涉及任何浮层开关 —— 浮层方案已废弃。
 *
 * 全部用 `mutableStateOf` 而不是普通字段：配置页要显示安装状态，需要在组合期读到变化。
 * 写入发生在安装线程（daemon），读取发生在 UI 线程；Compose 的快照机制保证跨线程可见性。
 */
object PageState {

    /** 导航内页面 hook 是否注册成功。 */
    val pageInstalled = mutableStateOf(false)

    /** 标题栏按钮 hook 是否注册成功。 */
    val titleInstalled = mutableStateOf(false)

    /** 左侧栏原生条目 hook 是否注册成功。 */
    val sidebarInstalled = mutableStateOf(false)

    /** 用户是否已请求进入酷狗页面（仅用于配置页展示，不参与渲染决策）。 */
    val open = mutableStateOf(false)

    /**
     * 当前是否已经停留在酷狗页面。
     *
     * 真相只有一份：宿主导航栈的栈顶路由是不是本插件的 `ModConfigContent`。
     * 侧栏的选中态、以及「重复点击不再压栈」都读它。
     */
    fun isCurrentPage(): Boolean = NavigatorBridge.isOurPageOpen()

    /**
     * 打开酷狗页面。
     *
     * 实际动作是**通过宿主 Navigator 导航到 `ModConfigContent(本插件 id)`**，
     * 宿主导航分发会把该路由交给插件用 `replaceComposable` 接管的调用点，
     * 于是渲染出酷狗页面。详见 [NavigatorBridge]。
     *
     * 已经在酷狗页面时**不再重复导航** —— 否则每点一次标题栏按钮 / 侧栏行
     * 都会往宿主返回栈里多压一层，用户要按好几次返回键才能退出。
     *
     * 这里不抛异常：调用方是用户点击，抛出去会变成宿主未捕获异常。
     * 失败时 `open` 保持 false，配置页的自检信息会显示「未就绪」。
     */
    fun requestOpen() {
        if (NavigatorBridge.isOurPageOpen()) {
            open.value = true
            return
        }
        val ok = NavigatorBridge.openPage()
        open.value = ok
    }

    /**
     * 返回上一页。
     *
     * 由页面左上角返回按钮调用。转发到 [NavigatorBridge.back]，不抛异常。
     */
    fun requestBack() {
        NavigatorBridge.back()
        open.value = false
    }

    fun close() {
        open.value = false
    }
}
