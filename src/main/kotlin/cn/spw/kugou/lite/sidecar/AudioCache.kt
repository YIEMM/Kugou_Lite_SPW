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
/**
 * 歌词文件生成器版本。
 *
 * 每改一次歌词生成逻辑（尤其是译文提取）就 +1 —— 旧版本的 `.lrc`
 * 会因此被重写，不会永远留着无译文的旧文件（用户反馈「有些歌词没有翻译」）。
 *
 * - v2：KRC `[language:]` 译文提取（type=1）
 * - v3：加版本戳 + 陈旧缓存失效。v2 之前写出的文件没有戳，
 *   而「已有非空 .lrc 就跳过」让它们永远不会被新代码覆盖。
 * - v4：头部始终补 `[offset:]`、丢弃酷狗内部 `[id:]`。
 *   缺 `[offset:]` 的歌词宿主会判为「无歌词」（用户反馈「人是猫」）。
 *   旧缓存带着 v3 的戳，必须升级版本号才能让它们被重写。
 * - v5：丢弃 `[total:]`。它的值是 0 时宿主把歌词时长算成 0，
 *   直接判为「无歌词」（用户反馈「第一次播放仍然没有歌词」，
 *   带 `[total:0]` 的文件全部中招）。
 * - v7：翻译行**必须与原文行同时间戳**。v6 曾把译文改成 +10ms，
 *   导致宿主把译文当成独立歌词行（主/译文交替、逐字失效）。
 *   宿主靠相同时间戳把下一行识别为上一行的 `pureSubText`。
 */
const val LRC_GENERATOR_VERSION = "v7"

/** 生成器标记，写进 `[by:]` 标签。 */
private const val GENERATOR_TAG = "KugouLite/$LRC_GENERATOR_VERSION"

/**
 * 已有 `.lrc` 是否需要重写：头部 `[by:]` 是否带当前版本的生成器戳。
 * 早期文件（`[by:]` 为空或没有戳）一律重写。
 */
fun needsLrcRewrite(existing: String): Boolean = !existing.contains(GENERATOR_TAG)

/** 给生成的歌词文本加上生成器标记（放在头部元信息里，并去掉旧的 `[by:]` 行）。 */
fun withGeneratorTag(text: String): String {
    val cleaned = text.lineSequence()
        .filterNot { it.trimStart().startsWith("[by:") }
        .joinToString("\n")
    return "[by:$GENERATOR_TAG]" + "\n" + cleaned
}

/**
 * 已知的音频容器扩展名。
 *
 * 查找缓存时逐个探测：`ensureLocal` 按上游返回的容器写文件（mp3 / flac / m4a…），
 * 而查找侧**不知道**当时拿到的是哪种，只能枚举。
 *
 * 曾经只探测 `mp3` —— 无损缓存写成了 `.flac` 却永远查不到，
 * 于是每次播放都重新下载（BUG-07）。真机上表现为「无损总是慢、磁盘里堆着同一首歌」。
 */
internal val CACHE_AUDIO_EXTS: List<String> = listOf("mp3", "flac", "m4a", "ogg", "aac", "wav")

/**
 * 缓存键净化：只保留字母数字与 `-` `_`，其余一律替换成 `_`。
 *
 * **这是安全边界，不是排版**：`key` 来自上游 hash / `albumAudioId`，
 * 直接拼进 `File(cacheDir, "$key.$ext")` 时，万一上游返回 `../../x`，
 * 就能把文件写出缓存目录（BUG-36）。
 */
internal fun sanitizeCacheKey(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        sb.append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_')
    }
    return sb.toString().trim('_').ifBlank { "unknown" }
}

/**
 * 缓存键（文件名主干）：`hash` 优先，其次 `albumAudioId`；两者都空返回 null。
 *
 * 净化后的结果不含路径字符，可直接作为文件名。
 */
internal fun cacheStemOf(hash: String, albumAudioId: String): String? {
    val raw = hash.ifBlank { albumAudioId }.trim()
    if (raw.isBlank()) return null
    return sanitizeCacheKey(raw)
}

/**
 * 缓存文件名：`<stem>-<quality>.<ext>`。
 *
 * ## 为什么音质要进文件名
 *
 * 音质档位曾经不是缓存键的一部分：设置从「标准」切到「无损」后，仍然命中
 * 之前那份 128k 文件 —— 用户切了音质却听不出任何变化，还以为设置没生效（BUG-08）。
 *
 * 音质参数用的是**用户请求的档位**（`Settings.requestQuality()`），不是服务端
 * 实际给的：`resolveQualityChain()` 会回退（请求 flac 拿到 320 是常态），
 * 若按实际档位命名，下次请求 flac 就查不到 `-320.mp3`，又退化成重复下载。
 *
 * 音质为空时按标准档（128）兜底，避免出现 `ABC-.mp3` 这种无法复现的名字。
 */
internal fun cacheName(stem: String, quality: String, ext: String): String {
    val q = sanitizeCacheKey(quality.ifBlank { "128" })
    // **扩展名也必须净化**（N04）：它是安全边界，不是排版。
    //
    // BUG-36 当初只覆盖了 key 与音质，漏了扩展名；而 extName 来自上游
    // 响应，若返回 "../../../evil" 就能把文件写出缓存目录。
    // 这里只保留字母数字，并且**必须以已知音频容器为准**，
    // 否则回退 mp3 —— 避免 "mp3/../../x" 这类绕过。
    val rawExt = ext.ifBlank { "mp3" }.lowercase().trim()
    val e = rawExt.takeIf { it in CACHE_AUDIO_EXTS } ?: "mp3"
    return "$stem-$q.$e"
}

/**
 * 曲目 id，必须与 PlaybackBridge.buildTrackId 完全一致，
 * 否则宿主传给 PlaybackExtensionPoint 的 id 与注册表对不上。
 */
internal fun trackIdOf(song: Song): String =
    "kugou-${song.hash.ifBlank { song.albumAudioId }}"

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
     * 取已缓存的本地音频文件（不触发下载）。
     *
     * 用于「队列预填」：建队列时如果某首歌之前播过，直接拿它的路径，
     * 宿主立刻就能读到封面与歌词，不必再走一次网络。
     *
     * 按当前请求音质逐个容器扩展名探测；旧命名（`<key>.mp3`，无音质后缀）
     * **有意不再命中** —— 无法判断它是什么音质，继续命中就等于让 BUG-08 复活。
     * 旧文件由用户的「清除缓存」回收。
     */
    fun localFileOrNull(song: Song): File? {
        val cacheDir = dir ?: return null
        val stem = cacheStemOf(song.hash, song.albumAudioId) ?: return null
        val quality = Settings.requestQuality()
        for (ext in CACHE_AUDIO_EXTS) {
            val f = File(cacheDir, cacheName(stem, quality, ext))
            // 不能只判 length() > 0（N05）：异常退出/下载中断会留下 3 字节垃圾，
            // 一旦被当成有效缓存，宿主播放无声，且 ensureLocal 认为命中 → 永不重下。
            if (isPlausibleAudio(f)) return f
        }
        return null
    }

    /**
     * 文件是否「看起来是完整音频」（N05）。
     *
     * ## 为什么不能只看体积
     *
     * 下载中断 / 进程被杀会留下几字节的垃圾文件。早期只判 `length() > 0`，
     * 这类文件被当成有效缓存：宿主播放无声，且 `ensureLocal` 认为命中 →
     * **永久无声且永不重下**（用户只能手动清缓存）。
     *
     * ## 判据
     *
     * - 体积下限：一首歌不可能小于 [MIN_AUDIO_BYTES]
     * - 文件头魔数：mp3（ID3 / 帧同步）、flac、ogg、m4a、wav
     *
     * 任一不满足即视为损坏，交给调用方重新下载。
     */
    internal fun isPlausibleAudio(f: File): Boolean {
        if (!f.isFile || f.length() < MIN_AUDIO_BYTES) return false
        return runCatching {
            val head = ByteArray(12)
            val read = f.inputStream().use { it.read(head) }
            if (read < 4) return@runCatching false
            hasAudioMagic(head, read, f.extension.lowercase())
        }.getOrDefault(false)
    }

    /** 一首歌的最小合理体积（字节）。8KB 以下不可能是有效音频。 */
    private const val MIN_AUDIO_BYTES = 8L * 1024L

    /** 按容器类型校验文件头。 */
    private fun hasAudioMagic(head: ByteArray, read: Int, ext: String): Boolean {
        fun u(i: Int) = head[i].toInt() and 0xFF
        return when (ext) {
            "mp3" ->
                // "ID3" 标签头
                (read >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) ||
                    // MPEG 帧同步：11 个 1
                    (read >= 2 && u(0) == 0xFF && (u(1) and 0xE0) == 0xE0)
            "flac" -> read >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
            "ogg" -> read >= 4 && head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() &&
                head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte()
            "m4a" -> read >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
            "wav" -> read >= 4 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
                head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte()
            "aac" -> read >= 2 && u(0) == 0xFF && (u(1) and 0xF0) == 0xF0
            // 未知容器：只做体积校验，不误杀
            else -> true
        }
    }

    /**
     * 确保音频已在本地，并**保证封面与歌词也已写入**。
     *
     * ## 为什么要一起做
     *
     * 宿主播放栏的封面读 `TagParser.readImage(path)`，歌词读同名 `.lrc`。
     * 两者都依赖本地文件已就绪。如果只在「首次下载」后写一次，
     * 那么命中缓存时（`.meta` 已存在）就会跳过 —— 但那时封面/歌词
     * 可能因为各种原因缺失（例如上一版插件没写、文件被清），
     * 表现为「能播放但没有封面没有歌词」。
     *
     * 所以这里把「写封面 + 写歌词」统一成**幂等的补齐操作**：
     * - 封面：检查 ID3 里是否已有 APIC，没有才写
     * - 歌词：检查同名 `.lrc` 是否存在且非空，没有才写
     *
     * @param lyrics 歌词文本（可选；为空时只补封面）
     * @return 可播放的本地文件；失败返回 null（调用方回退到 HTTP 直链）。
     */
    fun ensureLocal(song: Song, stream: StreamInfo, lyrics: String? = null): File? {
        val cacheDir = dir ?: run {
            Diag.warn(TAG, "未配置缓存目录，跳过下载")
            return null
        }

        val stem = cacheStemOf(song.hash, song.albumAudioId) ?: return null
        val ext = stream.extName.ifBlank { "mp3" }.lowercase()
        val target = File(cacheDir, cacheName(stem, Settings.requestQuality(), ext))

        // 把歌词投喂给宿主扩展点（见 LyricsRegistry）—— 无论是否命中缓存都要登记。
        // 宿主只在 onLoaded 时读一次歌词，而我们的取歌词在切歌之后才发生；
        // 走 PlaybackExtensionPoint.updateLyrics 直接返回文本，绕开文件与时序。
        if (!lyrics.isNullOrBlank()) {
            cn.spw.kugou.lite.host.LyricsRegistry.put(trackIdOf(song), lyrics)
        }

        // 已有缓存 → 补齐元数据后直接返回。
        // 必须校验「看起来是完整音频」（N05）：否则下载中断留下的垃圾文件
        // 会被当成命中，导致永久无声且永不重下。
        if (isPlausibleAudio(target)) {
            ensureMetadata(song, target, lyrics)
            return target
        }
        // 损坏的缓存直接删掉，走重新下载
        if (target.isFile) {
            Diag.warn(TAG, "缓存损坏，重新下载：${target.name}（${target.length()} 字节）")
            runCatching { target.delete() }
        }

        // 下载
        val part = File(cacheDir, "${target.name}.part")
        part.delete()

        for (url in stream.allUrls) {
            if (download(url, part)) {
                return try {
                    if (!part.renameTo(target)) {
                        target.delete()
                        part.renameTo(target)
                    }
                    if (isPlausibleAudio(target)) {
                        Diag.info(TAG, "已缓存 ${target.name}（${target.length() / 1024} KB）")
                        ensureMetadata(song, target, lyrics)
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
     * 幂等补齐封面与歌词。
     *
     * 每一步都先检查是否已存在，已存在就跳过 —— 避免每次播放都重写
     * 整个音频文件（那是「播放到 1 秒卡一下」的根因）。
     */
    /**
     * 音频文件旁挂的 `.lrc` 是否已存在且版本新鲜。
     *
     * 供缓存命中路径判断「还需不需要去接口补歌词」——已有新鲜歌词就跳过，
     * 避免每次切歌都为同一首歌发一次歌词请求。
     */
    fun hasFreshLyrics(audioFile: File): Boolean {
        val lrc = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc")
        if (!lrc.isFile || lrc.length() <= 100) return false
        return runCatching {
            !needsLrcRewrite(lrc.readText(Charsets.UTF_8))
        }.getOrDefault(false)
    }

    fun ensureMetadata(song: Song, audioFile: File, lyrics: String?) {
        val ext = audioFile.extension.lowercase()
        val (coverWritten, lyricsWritten) = if (ext == "flac") {
            // FLAC：封面走 PICTURE 块、歌词内嵌 Vorbis Comment ——
            // 宿主 `readFlacArtworkLazily` / `readFlacLyricsWithoutPictures`
            // 就是按这两个块读的，光靠 sidecar `.lrc` 拿不到封面。
            // 一次调用同时写两样，避免几十 MB 的文件重写两遍。
            val wroteTags = ensureFlacMetadata(song, audioFile, lyrics)
            // sidecar 仍然要写：宿主对 FLAC 也会尝试旁挂 .lrc，两条路都通更稳。
            val wroteLrc = if (!lyrics.isNullOrBlank()) writeLrcSidecar(audioFile, lyrics) else false
            wroteTags to wroteLrc
        } else {
            val cover = embedCover(song, audioFile)
            val lrc = if (!lyrics.isNullOrBlank()) writeLrcSidecar(audioFile, lyrics) else false
            cover to lrc
        }
        if (coverWritten || lyricsWritten) {
            // 记录「本次真的写了文件」，供切歌后触达宿主重读封面/歌词。
            // 宿主的封面链路只在切歌时读一次文件；若读取早于写入完成，
            // 就会停在 ♪ 占位（用户反馈「第一次播放没封面没歌词、第二次正常」）。
            lastMetadataWriteAt = System.currentTimeMillis()
            Diag.info(TAG, "元数据已更新（封面=$coverWritten 歌词=$lyricsWritten），需通知宿主重读")
        }
    }

    /**
     * FLAC 元数据补齐：封面写 PICTURE 块、歌词写 Vorbis Comment。
     *
     * 用户反馈「FLAC 没有封面」：宿主读 FLAC 封面走的是 PICTURE 块
     * （`readFlacArtworkLazily`），而旧实现只给 mp3 写 ID3 APIC、
     * FLAC 直接跳过 —— 于是无损曲目永远只有 ♪ 占位。
     *
     * [FlacTagWriter.writeTags] 幂等（已写入就跳过重写），失败不影响播放。
     *
     * @return 是否真的改写了文件（供调用方决定是否通知宿主重读）
     */
    private fun ensureFlacMetadata(song: Song, audioFile: File, lyrics: String?): Boolean {
        return try {
            val cover = coverBytesOf(song)
            val mime = cover?.let { Id3CoverWriter.detectMime(it) }
            // **必须与 sidecar .lrc 同源**：raw 可能是 KRC，直接内嵌会把
            // `[kana:...]` / `[language:...]` 等私有标签写进 Vorbis Comment，
            // 宿主和其它播放器会把它当普通歌词行显示出来。
            val embedded = lyrics?.takeIf { it.isNotBlank() }?.let {
                if (KrcLyrics.isKrc(it)) KrcLyrics.toEnhancedLrc(it) else it
            }
            FlacTagWriter.writeTags(audioFile, cover, mime, embedded)
        } catch (e: Exception) {
            Diag.warn(TAG, "FLAC 元数据补齐失败（不影响播放）：${e.message}")
            false
        }
    }

    /**
     * 上次写入封面/歌词的时间戳。
     *
     * 供 [cn.spw.kugou.lite.host.PlaybackBridge] 判断「是否需要让宿主
     * 重新读取封面」：刚写完就必须刷新，否则播放条会一直是 ♪ 占位。
     */
    @Volatile
    var lastMetadataWriteAt: Long = 0L
        private set

    /**
     * 把封面写进音频文件的 ID3 APIC 帧。
     *
     * 失败不影响播放（只是没有封面），所以整体静默降级。
     * 已有 APIC 帧时跳过（[Id3CoverWriter.writeCover] 内部判断），
     * 避免每次播放都重写整个音频文件。
     */
    private fun embedCover(song: Song, audioFile: File): Boolean {
        return try {
            // 只处理支持 ID3 的容器；FLAC 走 ensureFlacMetadata（PICTURE 块）
            val ext = audioFile.extension.lowercase()
            if (ext != "mp3") return false

            // 已有封面就跳过（读写整个文件很贵）
            if (Id3CoverWriter.hasApicFrame(audioFile)) return false

            val bytes = coverBytesOf(song) ?: return false

            Id3CoverWriter.writeCover(audioFile, bytes, Id3CoverWriter.detectMime(bytes))
        } catch (e: Exception) {
            Diag.warn(TAG, "内嵌封面失败（不影响播放）：${e.message}")
            false
        }
    }

    /**
     * 封面字节：`cover-<hash>.img` 缓存优先，未命中现下载并缓存。
     *
     * MP3（ID3 APIC）与 FLAC（PICTURE 块）共用同一份字节，避免各自实现
     * 一遍「先查缓存再下载」的逻辑。
     */
    private fun coverBytesOf(song: Song): ByteArray? {
        val coverUrl = song.coverUrl(500) ?: return null
        val cacheDir = dir ?: return null
        val coverFile = File(cacheDir, "cover-${song.hash.ifBlank { song.albumAudioId }}.img")
        return if (coverFile.isFile && coverFile.length() > 0) {
            coverFile.readBytes()
        } else {
            downloadBytes(coverUrl)?.also { b ->
                runCatching { coverFile.writeBytes(b) }
            }
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
        // 队列预取线程与切歌线程可能同时为同一首歌写歌词：串行化，
        // 避免「A 刚写完、B 读到旧版本又重写」的互相覆盖。
        val lock = lrcWriteLocks.computeIfAbsent(audioFile.absolutePath) { Any() }
        return synchronized(lock) {
            writeLrcSidecarLocked(audioFile, lyrics)
        }
    }

    /** 每条路径一把锁；条目很少（只在播放过的曲目上创建）。 */
    private val lrcWriteLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun writeLrcSidecarLocked(audioFile: File, lyrics: String): Boolean {
        return try {
            val isKrc = KrcLyrics.isKrc(lyrics)
            val text = withGeneratorTag(if (isKrc) KrcLyrics.toEnhancedLrc(lyrics) else lyrics)
            val lrc = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc")

            // 已有歌词但版本过旧（或没有生成器戳）就重写；当前版本才跳过。
            // 早期代码不会提取 [language:] 译文，写出的 .lrc 只有原文，
            // 而「非空就跳过」让它们永远不会被新代码覆盖 —— 这就是
            // 用户反馈「有些歌词没有翻译」的根因。
            if (lrc.isFile && lrc.length() > 100 && !needsLrcRewrite(lrc.readText(Charsets.UTF_8))) {
                return true
            }

            // 原子写：先写 .part 再 rename。
            //
            // 用户反馈「偶发无歌词，重新读取缓存就好」：宿主的歌词加载在另一条
            // 协程上，若撞上 writeText 的写入过程，会读到半截内容（甚至空文件），
            // 于是判为「无歌词」。音频下载早就用 .part + rename 的原子写法，
            // 歌词却漏了 —— 这就是「有概率触发」的来源。
            atomicWriteText(lrc, text)

            if (isKrc) {
                val krcFile = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".krc")
                // .krc 也随版本失效，保证排查用的原文与 .lrc 同源
                if (!krcFile.isFile || needsLrcRewrite(krcFile.readText(Charsets.UTF_8))) {
                    runCatching { atomicWriteText(krcFile, withGeneratorTag(KrcLyrics.sanitize(lyrics))) }
                }
                Diag.info(TAG, "已写入逐字歌词 ${lrc.name}（${text.length} 字符，KRC 源 ${lyrics.length} 字符，译文=${KrcLyrics.extractTranslation(lyrics).size} 行）")
            } else {
                Diag.info(TAG, "已写入逐行歌词 ${lrc.name}（${lyrics.length} 字符）")
            }
            true
        } catch (e: Exception) {
            Diag.warn(TAG, "写入 .lrc 失败：${e.message}")
            false
        }
    }

    /**
     * 原子写文本文件：先写 `<name>.part`，成功后再改名覆盖目标。
     *
     * ## 为什么不用 writeText
     *
     * `File.writeText` 会先把文件截断为 0 再逐块写入。宿主的歌词加载在
     * 另一条协程上，短窗口内读到的是空文件或半截内容 → 判为「无歌词」。
     * 用户观察到的「有概率没歌词、重新读取缓存就好」正是这个竞态。
     *
     * 改名在同目录内是原子的（Windows 用 MoveFileEx 的 replace 语义），
     * 读者要么看到完整旧内容、要么看到完整新内容。
     *
     * 失败时清理临时文件，不留下垃圾。
     */
    private fun atomicWriteText(target: File, text: String) {
        val part = File(target.parentFile, target.name + ".part")
        try {
            part.writeText(text, Charsets.UTF_8)
            if (!part.renameTo(target)) {
                // 某些环境 rename 不能覆盖已存在文件：先删再改名
                target.delete()
                if (!part.renameTo(target)) {
                    throw IllegalStateException("rename 失败：${part.name} -> ${target.name}")
                }
            }
        } catch (t: Throwable) {
            runCatching { part.delete() }
            throw t
        }
    }

    /** 仅供测试：走真实写入路径，验证原子性。 */
    internal fun writeLrcSidecarForTest(audioFile: File, lyrics: String): Boolean =
        writeLrcSidecar(audioFile, lyrics)

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

    /**
     * 列出音频缓存条目（缓存管理页用）。
     *
     * @return 按「越新越靠前」排序的条目；目录未配置返回空。
     */
    fun entries(): List<CacheEntry> = dir?.let { cacheEntries(it) } ?: emptyList()

    /**
     * 删除一条缓存（音频 + 随附歌词）。
     *
     * @return 音频文件是否删除成功（不存在视为失败）
     */
    fun deleteEntry(audio: File): Boolean {
        val cacheDir = dir ?: return false
        if (!cacheDir.equals(audio.parentFile)) return false
        return deleteCacheEntry(cacheDir, audio)
    }
}

/** 一条音频缓存条目：音频文件 + 随附歌词 sidecar。 */
data class CacheEntry(
    /** 音频文件（`<hash>-<音质>.<ext>`）。 */
    val audio: File,
    /** 同名歌词 sidecar（`.lrc` / `.krc`），可能为空。 */
    val sidecars: List<File>,
    /** 从文件名解析的音质（如 `128` / `320` / `flac`）。 */
    val quality: String,
    /** 音频扩展名（小写）。 */
    val ext: String,
    /** 音频 + 全部 sidecar 的总字节数。 */
    val totalBytes: Long,
)

/**
 * 扫描缓存目录，列出可播放的音频条目（[CacheEntry]）。
 *
 * 判定规则：
 * - 扩展名 ∈ [CACHE_AUDIO_EXTS] 的才是音频（排除 `cover-*`、`*.part`、
 *   孤立的 `.lrc`/`.krc`、杂项文件）；
 * - 同名 `.lrc`/`.krc` 归入同一条目，大小一起计入 totalBytes。
 *
 * 纯函数（目录由调用方给定），单测直接对临时目录断言。
 */
internal fun cacheEntries(dir: File): List<CacheEntry> {
    val files = dir.listFiles() ?: return emptyList()
    val sidecarExts = setOf("lrc", "krc")
    val audioFiles = files.filter { f ->
        val ext = f.extension.lowercase()
        ext in CACHE_AUDIO_EXTS && !f.name.startsWith("cover-") && !f.name.endsWith(".part")
    }
    return audioFiles
        .map { audio ->
            val sidecars = files.filter { s ->
                s.isFile &&
                    s.extension.lowercase() in sidecarExts &&
                    s.nameWithoutExtension == audio.nameWithoutExtension
            }
            // 文件名形如 `<stem>-<音质>.<ext>`；stem 是净化后的 hash（不含 `-`），
            // 取最后一个 `-` 之后为音质；解析不出时如实留空。
            val base = audio.nameWithoutExtension
            val quality = base.substringAfterLast('-', "")
            val bytes = audio.length() + sidecars.sumOf { it.length() }
            CacheEntry(
                audio = audio,
                sidecars = sidecars,
                quality = quality,
                ext = audio.extension.lowercase(),
                totalBytes = bytes,
            )
        }
        .sortedByDescending { it.audio.lastModified() }
}

/**
 * 删除一条缓存条目：音频 + 同名歌词 sidecar。
 *
 * @return 音频文件是否删除成功
 */
internal fun deleteCacheEntry(dir: File, audio: File): Boolean {
    if (audio.parentFile != dir) return false
    val base = audio.nameWithoutExtension
    val sidecars = dir.listFiles()?.filter {
        it.extension.lowercase() in setOf("lrc", "krc") && it.nameWithoutExtension == base
    } ?: emptyList()
    val audioOk = audio.delete()
    sidecars.forEach { runCatching { it.delete() } }
    if (audioOk) {
        Diag.info("cache", "已删除缓存 ${audio.name}（含歌词 ${sidecars.size} 个）")
    }
    return audioOk
}
