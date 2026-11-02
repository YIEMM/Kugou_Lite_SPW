package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.io.File

/**
 * `kugou_server.dll`（Rust 边车）的加载与生命周期管理。
 *
 * ## 架构
 *
 * 与早期的 Node 边车（外部 `app_win.exe` 进程，32.78 MB）不同，
 * Rust 版是**进程内动态库**（3.98 MB），通过 C FFI 直接调用：
 *
 * ```c
 * int  start_server(int port, const char* data_dir);  // 返回实际端口，0=失败
 * void stop_server(void);
 * int  is_server_running(void);
 * int  get_server_port(void);
 * ```
 *
 * 好处：
 * - **无外部进程**：不用 `ProcessBuilder`，不用担心杀软拦 exe。
 * - **自带根证书**：`rustls` + `webpki-roots`，不依赖宿主 jlink 的 `cacerts`。
 *   宿主运行时缺根证书时 JVM 侧 HTTPS 会报 `PKIX path building failed`，
 *   Rust 侧完全不受影响。
 * - **体积**：从 32.78 MB 降到 3.98 MB。
 *
 * ## 为什么用 JNI 而不是 JNA / 手写 FFI
 *
 * 插件不能引入额外依赖（会与宿主类路径冲突）。Java 21+ 的
 * **Foreign Function & Memory API** 需要 `--enable-native-access`，
 * 宿主不会为我们开。JNI 是 JDK 唯一**零依赖**的本地调用方式。
 *
 * 但 JNI 需要原生侧导出 `Java_<mangled>_<method>` 符号 —— 上游
 * `kugou_server.dll` 只导出纯 C 函数（`start_server` 等），没有 JNI 符号。
 *
 * 所以采用 **JNI 桥接 dll**：见 `sidecar/bridge/`，它链接上游 dll，
 * 导出 JNI 符号并转发调用。这样上游源码保持零修改（AGPL 合规、
 * 便于同步），桥接层是我们自己的代码。
 *
 * ## 加载顺序
 *
 * ```
 * 1. 把 kugou_server.dll 与 kugou_bridge.dll 解压到临时目录（插件 jar 内资源无法直接 load）
 * 2. System.load(bridge)      —— 桥接层依赖 kugou_server.dll，需保证可被搜索到
 * 3. start(port=0, dataDir)   —— 随机端口，返回实际值
 * ```
 *
 * ## 数据目录
 *
 * `data_dir` 用于持久化 `device_info.json`。设备指纹必须**跨重启稳定**，
 * 否则每次启动都向上游呈现一个全新设备，账号会累积设备条目并触发风控
 * （上游 `server.rs` 注释明确写了这一点）。
 */
object SidecarManager {

    private const val TAG = "sidecar"

    /** 上游 Rust 库（AGPL-3.0，见 sidecar/NOTICE.md）。 */
    private const val LIB_CORE = "kugou_server"

    /** 我们的 JNI 桥接层（MIT）。 */
    private const val LIB_BRIDGE = "kugou_bridge"

    private const val READY_TIMEOUT_MS = 15_000L

    private val lock = Any()

    @Volatile
    private var loaded = false

    @Volatile
    private var dataDir: File? = null

    /** 边车就绪后的基址，如 `http://127.0.0.1:52341`。未启动时为 null。 */
    @Volatile
    var baseUrl: String? = null
        private set

    @Volatile
    var lastHint: String? = null
        private set

    fun configure(dir: File) {
        dataDir = File(dir, "sidecar").apply { mkdirs() }
    }

    fun isRunning(): Boolean = runCatching { loaded && NativeBridge.isServerRunning() }.getOrDefault(false)

    fun statusText(): String = when {
        isRunning() -> "在线服务已就绪"
        lastHint != null -> "在线服务未启动：$lastHint"
        else -> "在线服务未启动"
    }

    /**
     * 确保边车可用：解压 dll → 加载 → 启动 → 等就绪。
     *
     * @return 成功返回基址；失败返回 null（调用方回退到「在线功能不可用」）。
     */
    fun ensureStarted(): String? {
        baseUrl?.let { if (isRunning()) return it }

        synchronized(lock) {
            baseUrl?.let { if (isRunning()) return it }

            val workDir = dataDir ?: run {
                Diag.warn(TAG, "未配置数据目录，跳过边车")
                return null
            }

            if (!ensureLoaded(workDir)) return null

            val port = try {
                NativeBridge.startServer(0, workDir.absolutePath)
            } catch (e: Throwable) {
                // 原生库崩溃会抛 Error 而非 Exception，必须一起接住：
                // 否则会作为插件组合期异常被宿主判为 FATAL_FAILURE 直接杀进程。
                Diag.warn(TAG, "startServer 抛异常：${e.javaClass.simpleName}: ${e.message}")
                lastHint = "原生库启动失败"
                return null
            }

            if (port <= 0) {
                Diag.warn(TAG, "startServer 返回 $port（0 表示失败）")
                lastHint = "原生库未能启动"
                return null
            }

            if (!awaitReady()) {
                Diag.warn(TAG, "边车未在 ${READY_TIMEOUT_MS}ms 内就绪")
                runCatching { NativeBridge.stopServer() }
                lastHint = "服务启动超时"
                return null
            }

            baseUrl = "http://127.0.0.1:$port"
            lastHint = null
            Diag.info(TAG, "边车就绪 $baseUrl")
            return baseUrl
        }
    }

    fun stop() {
        synchronized(lock) {
            runCatching {
                if (loaded) NativeBridge.stopServer()
            }
            baseUrl = null
        }
    }

    // ---------------------------------------------------------------- 加载

    private fun ensureLoaded(workDir: File): Boolean {
        if (loaded) return true

        return try {
            // 插件 jar 里的 dll 无法直接 System.load，必须先解压到磁盘。
            NativeLibExtractor.extract(workDir, "$LIB_CORE.dll")
            NativeLibExtractor.extract(workDir, "$LIB_BRIDGE.dll")

            // 上游 dll 的依赖（VCRUNTIME140.dll 等）需要 PATH 兜底。
            NativeLibExtractor.prependToPath(File(workDir, "native"))

            NativeBridge.prepare(File(workDir, "native"))
            loaded = true
            true
        } catch (e: Throwable) {
            Diag.warn(TAG, "原生库加载失败：${e.javaClass.simpleName}: ${e.message}")
            lastHint = "原生库加载失败（${e.javaClass.simpleName}）"
            false
        }
    }

    /**
     * 轮询等待 HTTP 可用。
     *
     * 不用固定 sleep：`start_server` 返回端口时 tiny_http 已开始监听，
     * 但还要初始化 device_info，可能稍慢。
     */
    private fun awaitReady(): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (NativeBridge.probe()) return true
            try {
                Thread.sleep(120)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}
