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

    private val logFile: File by lazy {
        runCatching {
            val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
            val dir = File(base, "Salt Player for Windows/workshop/data/$PLUGIN_ID/logs")
            dir.mkdirs()
            File(dir, "kugou-lite.log")
        }.getOrElse { File("kugou-lite.log") }
    }

    fun info(msg: String) = write("INFO", msg)
    fun warn(msg: String) = write("WARN", msg)
    fun error(msg: String, t: Throwable? = null) {
        write("ERROR", msg)
        if (t != null) write("ERROR", t.stackTraceToString())
    }

    private fun write(level: String, msg: String) {
        val line = "${LocalDateTime.now().format(FMT)} [$level] $msg"
        runCatching { println("[KUGOU-LITE] $msg") }
        runCatching { logFile.appendText(line + "\n") }
    }
}
