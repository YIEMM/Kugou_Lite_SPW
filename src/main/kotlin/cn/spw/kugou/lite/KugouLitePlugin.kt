package cn.spw.kugou.lite

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.host.PageInjection
import cn.spw.kugou.lite.sidecar.ApiExec
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.AuthStore
import cn.spw.kugou.lite.sidecar.SidecarManager
import cn.spw.kugou.lite.ui.PageNav

/**
 * Kugou Lite 插件入口。
 *
 * `start()` 里只做装配，所有失败都降级为诊断日志 ——
 * 插件启动期抛异常会让 SPW 把插件标为损坏并禁用。
 */
class KugouLitePlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    override fun start() {
        Diag.info("=== start ===")
        runCatching {
            Diag.info("pluginId=${pluginContext.pluginId}")
            Diag.info("pluginVersion=${pluginContext.pluginVersion}")
            Diag.info("spwVersion=${pluginContext.spwVersion}")
            Diag.info("channel=${pluginContext.spwChannel}")
        }.onFailure { Diag.error("读取插件上下文失败", it) }

        // P3：初始化在线能力（原生边车目录 + 登录态）。
        // 全部包在 runCatching 里：在线功能是可选增强，失败不能影响插件本体。
        runCatching {
            val dir = Diag.dataDir
            Diag.info("dataDir=${dir.absolutePath}")
            SidecarManager.configure(dir)
            AuthStore.configure(dir)
            // 音频下载缓存：宿主要用本地文件路径才能可靠播放（见 AudioCache 类文档）
            AudioCache.configure(dir)
            // 登录态不需要注入给边车：Rust 版是进程内服务，KugouApi 每次请求
            // 都从 AuthStore 读 token 并通过 Cookie 头传给本地服务（见 KugouApi.get）。
            Diag.info("在线能力已就绪：登录=${AuthStore.isLoggedIn}")
        }.onFailure { Diag.error("初始化在线能力失败", it) }

        // CLASS_TRANSFORM 是注入前提；未授权就降级为「只读模式」，不崩溃
        val granted = runCatching {
            WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)
        }.getOrDefault(false)
        Diag.info("CLASS_TRANSFORM granted=$granted")

        if (granted) {
            // 全量扫描宿主归档要 8 秒左右，必须异步，否则会卡住启动
            PageInjection.installAsync()
        } else {
            Diag.warn("未授权 class-transform，页面注入跳过。请到插件设置勾选后重启。")
        }
        Diag.info("=== start 完成 ===")
    }

    override fun stop() {
        Diag.info("=== stop ===")
        PageInjection.uninstall()
        ApiExec.shutdown()
        SidecarManager.stop()
        PageNav.reset()
    }
}
