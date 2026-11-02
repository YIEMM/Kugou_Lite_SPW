package cn.spw.kugou.lite.diag

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 诊断日志。
 *
 * 设计原则：
 * - **永不抛异常**。它要在插件最坏情况下还能工作，任何 IO 失败都吞掉。
 * - 同时写 stdout 与文件：stdout 进宿主控制台，文件用于用户反馈问题时附上。
 * - 写到 `%APPDATA%/Salt Player for Windows/workshop/data/cn.spw.kugou.lite/logs/`。
 */
object Diag {

    private const val PLUGIN_ID = "cn.spw.kugou.lite"
    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /**
     * 插件私有数据目录：`%APPDATA%/Salt Player for Windows/workshop/data/<pluginId>`。
     *
     * 这个目录由宿主 workshop 创建并管理，卸载插件时会被一起清掉，
     * 所以适合放运行时产物（[SidecarManager] 下载的 app_win.exe、
     * [cn.spw.kugou.lite.sidecar.AuthStore] 存的登录态）。
     *
     * **为什么不从 `PluginContext` 取**：workshop API 的这个版本没有稳定暴露
     * 数据目录访问器（`spw-workshop-api` 的 jar 不在常规 Gradle 模块缓存里，
     * 无法用 javap 确认成员）。而 `APPDATA` 路径是宿主自己的约定，
     * 已知且稳定，直接拼更可靠。
     */
    val dataDir: File by lazy {
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
        File(base, "Salt Player for Windows/workshop/data/$PLUGIN_ID").apply { mkdirs() }
    }

    private val logFile: File by lazy {
        runCatching {
            File(dataDir, "logs").apply { mkdirs() }
            File(File(dataDir, "logs"), "kugou-lite.log")
        }.getOrElse { File("kugou-lite.log") }
    }

    fun info(msg: String) = write("INFO", msg)
    fun warn(msg: String) = write("WARN", msg)
    fun error(msg: String, t: Throwable? = null) {
        write("ERROR", msg)
        if (t != null) write("ERROR", t.stackTraceToString())
    }

    /**
     * 带模块标签的日志重载。
     *
     * 插件进入 P3 后模块变多（sidecar / api / auth / exec / page），
     * 单看消息分不清来源，所以加了 tag 版本。tag 会渲染成 `[sidecar]` 前缀，
     * 便于在日志里按模块 grep。
     */
    fun info(tag: String, msg: String) = write("INFO", "[$tag] $msg")
    fun warn(tag: String, msg: String) = write("WARN", "[$tag] $msg")
    fun error(tag: String, msg: String, t: Throwable? = null) {
        write("ERROR", "[$tag] $msg")
        if (t != null) write("ERROR", "[$tag] ${t.stackTraceToString()}")
    }

    private fun write(level: String, msg: String) {
        val line = "${LocalDateTime.now().format(FMT)} [$level] $msg"
        runCatching { println("[KUGOU-LITE] $msg") }
        runCatching { logFile.appendText(line + "\n") }
    }
}
