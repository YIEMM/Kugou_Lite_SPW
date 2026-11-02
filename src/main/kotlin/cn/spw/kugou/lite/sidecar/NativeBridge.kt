package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.net.HttpURLConnection
import java.net.URI

/**
 * JNI 桥接层（`kugou_bridge.dll`）的 Kotlin 声明。
 *
 * ## 为什么需要桥接层
 *
 * 上游 `kugou_server.dll`（AGPL-3.0）只导出**纯 C 函数**：
 *
 * ```c
 * int  start_server(int port, const char* data_dir);
 * void stop_server(void);
 * int  is_server_running(void);
 * int  get_server_port(void);
 * ```
 *
 * 它**没有 JNI 符号**（`Java_*_*`），所以我们不能直接对上游 dll 声明
 * `external fun`。上游也确实没有为 Windows 桌面导出 JNI 符号 ——
 * 它的 JNI 导出只编译进 Android 目标（见 `lib.rs` 的 `#[cfg(target_os = "android")]`）。
 *
 * 因此我们额外编译一个**桥接 dll**（`sidecar/bridge/bridge.c`，MIT 许可），
 * 它：
 * 1. `LoadLibrary` 上游 `kugou_server.dll`，`GetProcAddress` 拿到四个函数指针；
 * 2. 导出与之对应的 JNI 符号，把调用转发过去。
 *
 * 这样**上游源码零修改**（AGPL 合规、便于同步上游），桥接层是我们自己的代码。
 *
 * ## Windows 依赖查找说明
 *
 * 桥接 dll 用 `LoadLibrary` 显式加载上游 dll（不能靠隐式链接），
 * 因为两个 dll 都在临时解压目录里，而宿主 exe 所在目录我们无权写入。
 * 显式加载可以传绝对路径，避免依赖 `PATH` 搜索顺序。
 *
 * ## 稳定性
 *
 * 所有 native 方法都是**同步阻塞**的，必须在后台线程调用
 * （见 [ApiExec]）。`start_server` 内部会初始化设备指纹并绑定端口，
 * 通常 < 1 秒。
 */
internal object NativeBridge {

    private const val TAG = "native"

    @Volatile
    private var prepared = false

    /**
     * 加载桥接 dll，并把上游 dll 的绝对路径传给它。
     *
     * 分两步：
     * 1. `System.load(bridgeDllPath)` —— JVM 加载桥接库本身；
     * 2. `nInit(coreDllPath)` —— 桥接层内部 `LoadLibrary` 上游库并
     *    `GetProcAddress` 拿函数指针，之后所有调用走函数指针。
     *
     * 这样上游 dll 的位置是**显式传入**的，不依赖 `PATH` 搜索顺序。
     */
    fun prepare(workDir: java.io.File) {
        if (prepared) return

        val bridgeDll = java.io.File(workDir, BRIDGE_DLL)
        val coreDll = java.io.File(workDir, CORE_DLL)

        check(bridgeDll.isFile) { "桥接 dll 缺失：${bridgeDll.absolutePath}" }
        check(coreDll.isFile) { "上游 dll 缺失：${coreDll.absolutePath}" }

        System.load(bridgeDll.absolutePath)
        Diag.info(TAG, "已加载桥接库 ${bridgeDll.absolutePath}")

        nInit(coreDll.absolutePath)
        prepared = true
        Diag.info(TAG, "桥接初始化完成，上游 dll=${coreDll.absolutePath}")
    }

    private const val BRIDGE_DLL = "kugou_bridge.dll"
    private const val CORE_DLL = "kugou_server.dll"

    fun startServer(port: Int, dataDir: String): Int = nStartServer(port, dataDir)

    fun stopServer() {
        if (prepared) nStopServer()
    }

    fun isServerRunning(): Boolean = prepared && nIsServerRunning() != 0

    fun getServerPort(): Int = if (prepared) nGetServerPort() else 0

    /** 轻量 HTTP 探活，确认 tiny_http 已在监听。 */
    fun probe(): Boolean = try {
        val port = getServerPort()
        if (port <= 0) {
            false
        } else {
            val conn = (URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 400
                readTimeout = 600
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            // 根路径返回 404（`Cannot GET /`）说明服务活着，这是正常结果。
            code in 200..499
        }
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------ native

    /** 告知桥接层上游 dll 的绝对路径并完成符号解析。 */
    private external fun nInit(coreDllPath: String)

    private external fun nStartServer(port: Int, dataDir: String): Int

    private external fun nStopServer()

    private external fun nIsServerRunning(): Int

    private external fun nGetServerPort(): Int
}
