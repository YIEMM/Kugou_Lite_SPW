package cn.spw.kugou.lite

import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.host.HostSalt
import cn.spw.kugou.lite.ui.PageState

/**
 * 配置页按钮的入口。
 *
 * `preference_config.json` 的 `on_click` 只认**全限定静态方法**，所以这里全部是 `@JvmStatic`。
 *
 * 每个入口都自行兜住 `Throwable`：配置页点按钮崩掉会连累整个宿主设置页。
 */
object PluginActions {

    /**
     * 打开酷狗页面。
     *
     * <p>注意这里**不能**直接让页面出现 —— 页面由宿主导航的 `ModConfigScreen` 路由驱动，
     * 只有当用户从「创意工坊 → 模块管理 → Kugou Lite → 配置」进入时，
     * 宿主才会构造出带本插件 `pluginId` 的路由。本方法只置位内部状态并写日志，
     * 供页面在组合时读取（例如高亮某个分区）。</p>
     */
    @JvmStatic
    fun openPage() {
        runCatching {
            PageState.requestOpen()
            Diag.info("[action] 请求打开酷狗页面：pageInstalled=${PageState.pageInstalled.value}")
        }.onFailure { Diag.error("[action] 打开页面失败", it) }
    }

    @JvmStatic
    fun closePage() {
        runCatching {
            PageState.close()
            Diag.info("[action] 关闭酷狗页面")
        }.onFailure { Diag.error("[action] 关闭页面失败", it) }
    }

    /**
     * 写入自检信息：把注入状态与原生组件 ABI 解析结果写进插件日志。
     *
     * <p>排查真机问题时先点这个按钮，日志里就能看到「页面 hook 是否装上、标题栏按钮是否装上、
     * 六个宿主 Salt 组件是否都解析成功」。</p>
     */
    @JvmStatic
    fun writeSelfCheck() {
        runCatching {
            Diag.info("[selfcheck] pageInstalled=${PageState.pageInstalled.value}")
            Diag.info("[selfcheck] titleInstalled=${PageState.titleInstalled.value}")
            HostSalt.validate()
        }.onFailure { Diag.error("[selfcheck] 失败", it) }
    }
}
