package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag
import java.io.File
import java.io.RandomAccessFile

/**
 * ID3v2.3 标签写入器 —— 给缓存的 mp3 补上**内嵌封面**。
 *
 * ## 为什么必须写进音频文件
 *
 * 宿主播放条/播放页的封面**不走**图片加载器，而是：
 *
 * ```
 * PlaybackService.refreshCurrentCover
 *   → TagParser.readImage(path, w, h)
 * ```
 *
 * 它要求 `Files.exists(path)`，读的是**本地音频文件的内嵌图**。
 * 这是 SPW_connect_Moe 网易云插件踩了很久才定案的结论（他们的
 * `PlaybarCover` 注释原文）：
 *
 * > 该函数要求 `Files.exists(path)` 且读的是**本地音频文件的内嵌图** ——
 * > 而本插件的 `Track.path` 恒为 `http://127.0.0.1:17788/...`，所以必然抛
 * > `IllegalArgumentException(Failed requirement.)` ⇒ 播放条永远是 ♪ 占位符。
 *
 * 我们比他们有利：**音频本来就是本地文件**（见 [AudioCache]），
 * 所以只要把封面写进 ID3 的 APIC 帧，播放条就会自动显示 —— 不需要反射投递位图。
 *
 * ## 为什么写 ID3v2.3 而不是 2.4
 *
 * 2.3 兼容性最好（Windows 资源管理器、各类播放器、JAudioTagger 都稳定支持）。
 * 宿主用的是 JAudioTagger（`TagParser` 内部有 `JAudioTagFileKey`），
 * 它对 2.3 的 APIC 支持最成熟。
 *
 * ## 结构
 *
 * ```
 * ID3v2.3 header (10 字节) + 原有帧（保留）+ APIC 帧（新增）
 * ```
 *
 * 只**追加** APIC，不动已有帧（TIT2/TPE1 等由酷狗自带，要保留）。
 */
object Id3CoverWriter {

    private const val TAG = "id3"

    /** 封面图过大时跳过：播放条只需要缩略图，几十 KB 足够。 */
    private const val MAX_COVER_BYTES = 2 * 1024 * 1024

    /**
     * 把封面写入 mp3 的 ID3v2.3 APIC 帧。
     *
     * 幂等：文件里已有 APIC 帧时直接返回 true，不重复写。
     *
     * @param audioFile 本地 mp3（或其他支持 ID3 的格式）
     * @param coverBytes 封面图片字节（JPEG / PNG）
     * @param mimeType 图片 MIME，如 `image/jpeg`
     * @return 成功写入或已存在返回 true
     */
    fun writeCover(audioFile: File, coverBytes: ByteArray, mimeType: String): Boolean {
        if (!audioFile.isFile || audioFile.length() == 0L) return false
        if (coverBytes.isEmpty() || coverBytes.size > MAX_COVER_BYTES) {
            Diag.warn(TAG, "封面尺寸不合适（${coverBytes.size} 字节），跳过")
            return false
        }

        return try {
            RandomAccessFile(audioFile, "rw").use { raf ->
                val header = readHeader(raf) ?: return writeFresh(raf, coverBytes, mimeType)

                // 已有 APIC 就不重复写（避免每次播放都重写文件）
                if (hasApic(raf, header)) return true

                appendApic(raf, header, coverBytes, mimeType)
                true
            }
        } catch (e: Exception) {
            Diag.warn(TAG, "写入封面失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 已解析的 ID3v2 头信息。 */
    private data class Header(
        val majorVersion: Int,
        /** 标签区总大小（不含 10 字节头）。 */
        val tagSize: Int,
        /** 原标签的 flags。 */
        val flags: Int,
    )

    private fun readHeader(raf: RandomAccessFile): Header? {
        if (raf.length() < 10) return null
        raf.seek(0)
        val head = ByteArray(10)
        raf.readFully(head)
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) {
            return null
        }
        val major = head[3].toInt() and 0xFF
        // syncsafe integer：每字节只用低 7 位
        val size = ((head[6].toInt() and 0x7F) shl 21) or
            ((head[7].toInt() and 0x7F) shl 14) or
            ((head[8].toInt() and 0x7F) shl 7) or
            (head[9].toInt() and 0x7F)
        val flags = head[5].toInt() and 0xFF
        return Header(major, size, flags)
    }

    private fun hasApic(raf: RandomAccessFile, header: Header): Boolean {
        raf.seek(10)
        val buf = ByteArray(header.tagSize.coerceAtMost(Int.MAX_VALUE))
        val read = raf.read(buf)
        if (read <= 0) return false
        // 粗暴但可靠：在标签区里找 APIC 帧标识
        for (i in 0..(read - 4)) {
            if (buf[i] == 'A'.code.toByte() &&
                buf[i + 1] == 'P'.code.toByte() &&
                buf[i + 2] == 'I'.code.toByte() &&
                buf[i + 3] == 'C'.code.toByte()
            ) {
                return true
            }
        }
        return false
    }

    /** 文件没有 ID3 标签时，新建一个只含 APIC 的标签。 */
    private fun writeFresh(raf: RandomAccessFile, cover: ByteArray, mime: String): Boolean {
        val apic = buildApicFrame(cover, mime)
        val total = apic.size
        val out = ByteArray(10 + total)
        // "ID3" + version 2.3.0 + flags 0 + syncsafe size
        out[0] = 'I'.code.toByte(); out[1] = 'D'.code.toByte(); out[2] = '3'.code.toByte()
        out[3] = 3; out[4] = 0; out[5] = 0
        out[6] = ((total shr 21) and 0x7F).toByte()
        out[7] = ((total shr 14) and 0x7F).toByte()
        out[8] = ((total shr 7) and 0x7F).toByte()
        out[9] = (total and 0x7F).toByte()
        System.arraycopy(apic, 0, out, 10, apic.size)

        // 读原文件全部内容（没有标签时整个就是音频），拼到新标签后面
        val audio = ByteArray(raf.length().toInt())
        raf.seek(0)
        raf.readFully(audio)

        raf.seek(0)
        raf.setLength(0)
        raf.write(out)
        raf.write(audio)
        Diag.info(TAG, "已新建 ID3 标签并写入封面（${cover.size} 字节）")
        return true
    }

    /** 在已有标签末尾追加 APIC 帧，并更新标签大小。 */
    private fun appendApic(raf: RandomAccessFile, header: Header, cover: ByteArray, mime: String) {
        val apic = buildApicFrame(cover, mime)
        val newSize = header.tagSize + apic.size

        // 读原标签内容 + 之后的音频数据
        raf.seek(10)
        val oldTag = ByteArray(header.tagSize)
        raf.readFully(oldTag)
        val audioStart = 10L + header.tagSize
        val audio = ByteArray((raf.length() - audioStart).toInt().coerceAtLeast(0))
        if (audio.isNotEmpty()) {
            raf.seek(audioStart)
            raf.readFully(audio)
        }

        // 重写：新头 + 旧标签 + APIC + 音频
        val newHeader = ByteArray(10)
        newHeader[0] = 'I'.code.toByte(); newHeader[1] = 'D'.code.toByte(); newHeader[2] = '3'.code.toByte()
        newHeader[3] = 3; newHeader[4] = 0
        newHeader[5] = header.flags.toByte()
        newHeader[6] = ((newSize shr 21) and 0x7F).toByte()
        newHeader[7] = ((newSize shr 14) and 0x7F).toByte()
        newHeader[8] = ((newSize shr 7) and 0x7F).toByte()
        newHeader[9] = (newSize and 0x7F).toByte()

        raf.seek(0)
        raf.setLength(0)
        raf.write(newHeader)
        raf.write(oldTag)
        raf.write(apic)
        if (audio.isNotEmpty()) raf.write(audio)
        Diag.info(TAG, "已追加封面帧（${cover.size} 字节，标签 $newSize 字节）")
    }

    /**
     * 构造 ID3v2.3 APIC 帧。
     *
     * 帧结构：
     * ```
     * 'APIC' + size(4, 大端) + flags(2) + encoding(1)
     *   + MIME(ASCII, 0 结尾) + pictureType(1) + description(0 结尾)
     *   + pictureData
     * ```
     */
    private fun buildApicFrame(cover: ByteArray, mime: String): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)
        // encoding(1) + mime + 0 + type(1) + desc(0)
        val bodySize = 1 + mimeBytes.size + 1 + 1 + 1 + cover.size
        val frame = ByteArray(10 + bodySize)

        frame[0] = 'A'.code.toByte()
        frame[1] = 'P'.code.toByte()
        frame[2] = 'I'.code.toByte()
        frame[3] = 'C'.code.toByte()
        // 帧大小：ID3v2.3 用普通大端 32 位（不是 syncsafe）
        frame[4] = ((bodySize shr 24) and 0xFF).toByte()
        frame[5] = ((bodySize shr 16) and 0xFF).toByte()
        frame[6] = ((bodySize shr 8) and 0xFF).toByte()
        frame[7] = (bodySize and 0xFF).toByte()
        // flags
        frame[8] = 0; frame[9] = 0

        var pos = 10
        frame[pos++] = 0                       // encoding: ISO-8859-1
        System.arraycopy(mimeBytes, 0, frame, pos, mimeBytes.size)
        pos += mimeBytes.size
        frame[pos++] = 0                       // MIME 结束
        frame[pos++] = 3                       // picture type 3 = front cover
        frame[pos++] = 0                       // description 空串结束
        System.arraycopy(cover, 0, frame, pos, cover.size)
        return frame
    }

    /** 按图片头判断 MIME。 */
    fun detectMime(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        else -> "image/jpeg"
    }

    // ================================================================ 歌词

    /**
     * 把歌词写入 ID3v2.3 的 **USLT**（Unsynchronised lyrics）帧。
     *
     * ## 为什么要写进音频文件
     *
     * 宿主加载歌词走官方扩展点 `PlaybackExtensionPoint.onBeforeLoadLyrics`，
     * 但那需要插件注册 PF4J 扩展。更省事且对「本地文件」天然有效的做法是：
     * 把歌词作为**内嵌元数据**写进音频文件，宿主自己的 tag 解析会读到。
     *
     * 这也符合用户的要求「歌词内嵌元数据」。
     *
     * ## USLT 帧结构（ID3v2.3）
     *
     * ```
     * 'USLT' + size(4, 大端) + flags(2)
     *   + encoding(1) + language(3, 如 "chi"/"eng") + descriptor(0 结尾) + lyrics
     * ```
     *
     * 用 UTF-16（encoding=1）存歌词：中文歌词用 ISO-8859-1 会乱码。
     */
    fun writeLyrics(audioFile: File, lyrics: String, language: String = "chi"): Boolean {
        if (!audioFile.isFile || lyrics.isBlank()) return false
        return try {
            RandomAccessFile(audioFile, "rw").use { raf ->
                val header = readHeader(raf) ?: return false
                if (hasFrame(raf, header, "USLT")) return true
                appendFrame(raf, header, buildUsltFrame(lyrics, language))
                true
            }
        } catch (e: Exception) {
            Diag.warn(TAG, "写入歌词失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 标签区里是否已有指定帧。 */
    private fun hasFrame(raf: RandomAccessFile, header: Header, id: String): Boolean {
        raf.seek(10)
        val buf = ByteArray(header.tagSize.coerceAtMost(Int.MAX_VALUE))
        val read = raf.read(buf)
        if (read < 4) return false
        val target = id.toByteArray(Charsets.ISO_8859_1)
        for (i in 0..(read - 4)) {
            var match = true
            for (j in 0 until 4) {
                if (buf[i + j] != target[j]) { match = false; break }
            }
            if (match) return true
        }
        return false
    }

    private fun buildUsltFrame(lyrics: String, language: String): ByteArray {
        // UTF-16 带 BOM（encoding=1）
        val text = lyrics.toByteArray(Charsets.UTF_16)
        val lang = language.padEnd(3).take(3).toByteArray(Charsets.ISO_8859_1)
        // encoding(1) + lang(3) + descriptor 终止符(2, UTF-16 空串) + lyrics
        val bodySize = 1 + 3 + 2 + text.size
        val frame = ByteArray(10 + bodySize)
        frame[0] = 'U'.code.toByte()
        frame[1] = 'S'.code.toByte()
        frame[2] = 'L'.code.toByte()
        frame[3] = 'T'.code.toByte()
        frame[4] = ((bodySize shr 24) and 0xFF).toByte()
        frame[5] = ((bodySize shr 16) and 0xFF).toByte()
        frame[6] = ((bodySize shr 8) and 0xFF).toByte()
        frame[7] = (bodySize and 0xFF).toByte()
        frame[8] = 0; frame[9] = 0

        var pos = 10
        frame[pos++] = 1                    // encoding: UTF-16 with BOM
        System.arraycopy(lang, 0, frame, pos, 3); pos += 3
        frame[pos++] = 0; frame[pos++] = 0  // descriptor 空串（UTF-16 的 \0\0）
        System.arraycopy(text, 0, frame, pos, text.size)
        return frame
    }

    /**
     * 往已有标签追加一个帧并更新标签大小。
     *
     * 与 [appendApic] 逻辑相同，抽出来供 USLT 复用。
     */
    private fun appendFrame(raf: RandomAccessFile, header: Header, frame: ByteArray) {
        val newSize = header.tagSize + frame.size

        raf.seek(10)
        val oldTag = ByteArray(header.tagSize)
        raf.readFully(oldTag)
        val audioStart = 10L + header.tagSize
        val audio = ByteArray((raf.length() - audioStart).toInt().coerceAtLeast(0))
        if (audio.isNotEmpty()) {
            raf.seek(audioStart)
            raf.readFully(audio)
        }

        val newHeader = ByteArray(10)
        newHeader[0] = 'I'.code.toByte(); newHeader[1] = 'D'.code.toByte(); newHeader[2] = '3'.code.toByte()
        newHeader[3] = 3; newHeader[4] = 0
        newHeader[5] = header.flags.toByte()
        newHeader[6] = ((newSize shr 21) and 0x7F).toByte()
        newHeader[7] = ((newSize shr 14) and 0x7F).toByte()
        newHeader[8] = ((newSize shr 7) and 0x7F).toByte()
        newHeader[9] = (newSize and 0x7F).toByte()

        raf.seek(0)
        raf.setLength(0)
        raf.write(newHeader)
        raf.write(oldTag)
        raf.write(frame)
        if (audio.isNotEmpty()) raf.write(audio)
        Diag.info(TAG, "已追加帧（${frame.size} 字节，标签 $newSize 字节）")
    }
}
