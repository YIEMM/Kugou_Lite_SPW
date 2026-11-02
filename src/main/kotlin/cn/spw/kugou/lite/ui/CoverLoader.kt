package cn.spw.kugou.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.ApiExec
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 列表行封面加载。
 *
 * ## 为什么自己实现而不用图片库
 *
 * 插件不能引入第三方图片库（会与宿主类路径冲突）。宿主是桌面 JVM，
 * `java.desktop` 模块一定存在（宿主自己就是 AWT 应用），
 * 所以用 `ImageIO` + `toComposeImageBitmap()` 就够了。
 *
 * ## 缓存策略（两级）
 *
 * 1. **内存**：[memoryCache] 按 URL 缓存已解码的 `ImageBitmap`，
 *    列表滚动时不会重复解码。
 * 2. **磁盘**：复用 [cn.spw.kugou.lite.sidecar.AudioCache] 目录下的
 *    `cover-*.img`，与播放条用的是同一份封面文件。
 *
 * ## 线程
 *
 * 下载与解码都在 [ApiExec] 的后台线程，结果写回 `MutableState` 触发重组。
 * **绝不能**在组合期同步下载 —— 会卡住渲染线程。
 */
object CoverLoader {

    private const val TAG = "cover"

    /** 已解码的位图缓存（URL → Bitmap）。 */
    private val memoryCache = ConcurrentHashMap<String, ImageBitmap>()

    /** 正在下载的 URL，避免同一封面被重复请求。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 磁盘缓存目录（由插件启动时注入）。 */
    @Volatile
    private var cacheDir: File? = null

    fun configure(dir: File) {
        cacheDir = File(dir, "cover-cache").apply { mkdirs() }
    }

    fun cached(url: String): ImageBitmap? = memoryCache[url]

    /**
     * 异步加载封面。
     *
     * @param url 封面地址（含 `{size}` 的模板已由调用方替换）
     * @param onLoaded 解码完成后回调（在主线程之外，写入 MutableState 是安全的）
     */
    fun load(url: String, onLoaded: (ImageBitmap?) -> Unit) {
        memoryCache[url]?.let { onLoaded(it); return }
        if (!inFlight.add(url)) return

        ApiExec.run({ result ->
            inFlight.remove(url)
            result.onSuccess { bmp ->
                if (bmp != null) memoryCache[url] = bmp
                onLoaded(bmp)
            }
            result.onFailure {
                inFlight.remove(url)
                onLoaded(null)
            }
        }) {
            loadBlocking(url)
        }
    }

    private fun loadBlocking(url: String): ImageBitmap? {
        try {
            // 先查磁盘缓存
            val dir = cacheDir
            if (dir != null) {
                val key = url.hashCode().toUInt().toString(16)
                val f = File(dir, "$key.img")
                if (f.isFile && f.length() > 0) {
                    decode(f.readBytes())?.let { return it }
                }
                val bytes = download(url) ?: return null
                runCatching { f.writeBytes(bytes) }
                return decode(bytes)
            }
            val bytes = download(url) ?: return null
            return decode(bytes)
        } catch (e: Throwable) {
            Diag.warn(TAG, "加载失败：${e.javaClass.simpleName}")
            return null
        }
    }

    private fun download(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "KugouLite-SPW")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? = try {
        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        img?.toComposeImageBitmap()
    } catch (e: Throwable) {
        null
    }

    fun clearMemory() {
        memoryCache.clear()
    }
}

/**
 * 记住一张封面并在加载完成后自动重组。
 *
 * @param url 封面地址；为空时直接返回 null
 */
@Composable
fun rememberCover(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    var bitmap by remember(url) { mutableStateOf(CoverLoader.cached(url)) }
    LaunchedEffect(url) {
        if (bitmap == null) {
            CoverLoader.load(url) { bmp -> bitmap = bmp }
        }
    }
    return bitmap
}
