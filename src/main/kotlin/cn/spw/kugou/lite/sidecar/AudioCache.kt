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
        val metaStamp = File(cacheDir, "$key.meta")

        // 已缓存 → 直接用。
        //
        // **不要拿 target.length() 和 stream.fileSize 比**：写 ID3（封面 + 歌词）
        // 会让文件变大（实测 4.2 MB → 4.4 MB），大小必然对不上。
        // 早期实现因此每次都判定「文件坏了」→ 删掉重下 → 再写 ID3，
        // 形成「每次都重新下载+重写整个文件」的死循环，
        // 表现为「播放到 1 秒卡一下」。
        //
        // 现在的判据：只要文件存在且非空就算有效；元数据用 .meta 戳记控制。
        if (target.isFile && target.length() > 0) {
            val stamp = "${target.length()}:${target.lastModified()}"
            if (!metaStamp.isFile || metaStamp.readText().trim() != stamp) {
                // 只在没写过元数据时补一次
                embedCover(song, target)
                runCatching { metaStamp.writeText("${target.length()}:${target.lastModified()}") }
            }
            return target
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
                        // 顺手补内嵌封面：宿主播放条/播放页的封面读的就是
                        // 本地音频文件的内嵌图（TagParser.readImage）。
                        embedCover(song, target)
                        // 记戳：下次命中缓存时不再重复写 ID3（写 ID3 要重写整个文件，
                        // 是「播放到 1 秒卡一下」的根因）
                        runCatching {
                            metaStamp.writeText("${target.length()}:${target.lastModified()}")
                        }
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

    /**
     * 把封面写进音频文件的 ID3 APIC 帧，同时**旁挂一份同名 `.lrc` 文件**。
     *
     * 失败不影响播放（只是没有封面/歌词），所以整体静默降级。
     */
    private fun embedCover(song: Song, audioFile: File) {
        try {
            // 只处理支持 ID3 的容器；flac 用 vorbis comment，暂不支持
            val ext = audioFile.extension.lowercase()
            if (ext != "mp3") return

            val coverUrl = song.coverUrl(500) ?: return
            val cacheDir = dir ?: return
            val coverFile = File(cacheDir, "cover-${song.hash.ifBlank { song.albumAudioId }}.img")

            val bytes = if (coverFile.isFile && coverFile.length() > 0) {
                coverFile.readBytes()
            } else {
                downloadBytes(coverUrl)?.also { b ->
                    runCatching { coverFile.writeBytes(b) }
                }
            } ?: return

            Id3CoverWriter.writeCover(audioFile, bytes, Id3CoverWriter.detectMime(bytes))
        } catch (e: Exception) {
            Diag.warn(TAG, "内嵌封面失败（不影响播放）：${e.message}")
        }
    }

    /**
     * 把歌词写到音频文件**同目录同名的 `.lrc`** 文件。
     *
     * ## 为什么要旁挂一份
     *
     * 宿主 `TagParser.readLyrics` 支持的来源（反汇编确认）：
     *
     * | 来源 | 方法 |
     * |---|---|
     * | 内嵌 tag 的 LYRICS 字段 | `readEmbeddedLyrics` |
     * | FLAC 的 LYRICS/UNSYNCEDLYRICS | `readFlacLyricsWithoutPictures` |
     * | **同目录同名 `.lrc`** | `readLyricsByLrc` |
     * | 同目录同名 `.spl` | `readLyricsBySpl` |
     *
     * ## KRC（逐字）优先
     *
     * 酷狗 `fmt=krc` 返回**逐字**歌词，`fmt=lrc` 只有逐行。
     * 宿主内部的歌词模型是 `LyricsLine` + `LyricsCell`（逐字单元），
     * 所以喂逐字数据才能逐字高亮。
     *
     * 写入策略：
     * 1. 若是 KRC，先转成**增强 LRC**（`[00:03.75]<00:03.75>愛<00:04.33>舐`）——
     *    这是业界通用写法，兼容性最好；
     * 2. 同时把原始 KRC（剥掉 `[language:]` 翻译段）也保留一份 `.krc`，
     *    便于排查与将来切换。
     *
     * @param audioFile 本地音频文件
     * @param lyrics 歌词文本（KRC 或 LRC 均可）
     */
    fun writeLrcSidecar(audioFile: File, lyrics: String): Boolean {
        if (lyrics.isBlank()) return false
        return try {
            val isKrc = KrcLyrics.isKrc(lyrics)
            val text = if (isKrc) KrcLyrics.toEnhancedLrc(lyrics) else lyrics
            val lrc = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc")

            // 已写过且内容一致 → 跳过（避免每次播放都重写磁盘）
            if (lrc.isFile && lrc.length().toLong() == text.toByteArray(Charsets.UTF_8).size.toLong()) {
                return true
            }

            lrc.writeText(text, Charsets.UTF_8)

            if (isKrc) {
                val krcFile = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".krc")
                if (!krcFile.isFile) {
                    runCatching { krcFile.writeText(KrcLyrics.sanitize(lyrics), Charsets.UTF_8) }
                }
                Diag.info(TAG, "已写入逐字歌词 ${lrc.name}（${text.length} 字符，KRC 源 ${lyrics.length} 字符）")
            } else {
                Diag.info(TAG, "已写入逐行歌词 ${lrc.name}（${lyrics.length} 字符）")
            }
            true
        } catch (e: Exception) {
            Diag.warn(TAG, "写入 .lrc 失败：${e.message}")
            false
        }
    }

    /** 下载到内存（封面通常几十 KB）。 */
    private fun downloadBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 15_000
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
