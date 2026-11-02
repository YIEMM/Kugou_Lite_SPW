package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * 音频下载缓存。
 *
 * ## 为什么必须下载成本地文件
 *
 * 宿主对「曲库外的 HTTP 曲目」经常**根本不去打开** —— 这来自 SPW_connect_Moe
 * 网易云插件的实证注释：
 *
 * > Detects a selected HTTP item that the host never opens.
 * > **Position/Ready are not audio evidence.**
 *
 * 表现是：播放栏显示歌名、播控按钮齐全、SMTC 也有曲目信息，但
 * `PlaybackStatus = Stopped`，宿主从未发起取流。
 *
 * 本地文件路径是宿主最成熟的播放通路，不依赖外部音源机制。所以取流成功后
 * 先把音频下载到插件数据目录，再用本地绝对路径入队。
 *
 * ## 缓存策略
 *
 * 文件名用取流键（hash / album_audio_id）+ 扩展名，因此：
 * - 同一首歌重复播放**不重复下载**
 * - 不同音质档位是不同的键，互不覆盖
 *
 * 下载用 `.part` 临时文件 + 原子改名：下载中断不会留下半截文件被误当成完整音频。
 */
object AudioCache {

    private const val TAG = "cache"

    /** 单文件大小上限，防御异常响应把磁盘塞满。 */
    private const val MAX_BYTES = 200L * 1024 * 1024

    @Volatile
    private var dir: File? = null

    fun configure(baseDir: File) {
        dir = File(baseDir, "audio-cache").apply { mkdirs() }
    }

    /**
     * 确保音频已在本地。
     *
     * @return 可播放的本地文件；失败返回 null（调用方回退到 HTTP 直链）。
     */
    fun ensureLocal(song: Song, stream: StreamInfo): File? {
        val cacheDir = dir ?: run {
            Diag.warn(TAG, "未配置缓存目录，跳过下载")
            return null
        }

        val key = song.hash.ifBlank { song.albumAudioId }.ifBlank { return null }
        val ext = stream.extName.ifBlank { "mp3" }.lowercase()
        val target = File(cacheDir, "$key.$ext")

        // 已缓存且大小吻合 → 直接用
        if (target.isFile && target.length() > 0) {
            if (stream.fileSize <= 0 || target.length() == stream.fileSize) {
                return target
            }
            // 大小不符说明上次写坏了，删掉重下
            target.delete()
        }

        val part = File(cacheDir, "$key.$ext.part")
        part.delete()

        // 主地址失败时依次试备用 CDN
        for (url in stream.allUrls) {
            if (download(url, part)) {
                return try {
                    if (!part.renameTo(target)) {
                        // Windows 上 rename 失败通常是目标已存在
                        target.delete()
                        part.renameTo(target)
                    }
                    if (target.isFile && target.length() > 0) {
                        Diag.info(TAG, "已缓存 ${target.name}（${target.length() / 1024} KB）")
                        target
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Diag.warn(TAG, "改名失败：${e.message}")
                    null
                }
            }
        }

        part.delete()
        Diag.warn(TAG, "全部地址下载失败")
        return null
    }

    private fun download(url: String, target: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "KugouLite-SPW")
            }
            if (conn.responseCode !in 200..299) {
                Diag.warn(TAG, "下载 HTTP ${conn.responseCode}：${url.take(60)}…")
                return false
            }
            var total = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            Diag.warn(TAG, "超过大小上限，中止")
                            return false
                        }
                        output.write(buf, 0, n)
                    }
                }
            }
            total > 0
        } catch (e: Exception) {
            Diag.warn(TAG, "下载异常：${e.javaClass.simpleName}: ${e.message}")
            runCatching { target.delete() }
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 清空缓存（配置页「清除缓存」用）。 */
    fun clear() {
        runCatching { dir?.listFiles()?.forEach { it.delete() } }
        Diag.info(TAG, "音频缓存已清空")
    }

    /** 当前缓存占用（字节）。 */
    fun usage(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L
}
