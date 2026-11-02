package cn.spw.kugou.lite

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.host.PageInjection

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
    }
}
