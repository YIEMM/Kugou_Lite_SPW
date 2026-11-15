package cn.spw.kugou.lite.sidecar

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import cn.spw.kugou.lite.diag.Diag

/** 解析出的一个 METADATA_BLOCK。 */
internal class FlacBlock(
    /** 块类型：0=STREAMINFO、4=VORBIS_COMMENT、6=PICTURE。 */
    val type: Int,
    /** 是否是块链的最后一个（头字节最高位）。 */
    val isLast: Boolean,
    /** 块数据（不含 4 字节头）。 */
    val data: ByteArray,
)

/** FLAC PICTURE 块的解析结果。 */
internal class PictureInfo(val mime: String, val data: ByteArray)

/**
 * FLAC 元数据块**只读**解析（[FlacTagWriter] 与单测共用）。
 *
 * 用户反馈「FLAC 无法写入封面与歌词」的排查结论（宿主 TagParser 反汇编确认）：
 * 宿主读 FLAC 封面 = METADATA_BLOCK_PICTURE（type 6）；
 * 读 FLAC 歌词 = Vorbis Comment 的 `LYRICS` / `UNSYNCEDLYRICS` 字段。
 * 本插件此前只写同名 `.lrc` sidecar、封面明确跳过 FLAC —— 这就是缺口。
 */
internal object FlacTagReader {

    /** 解析块链；非 FLAC 文件返回空列表。 */
    fun readBlocks(file: File): List<FlacBlock> {
        val bytes = file.readBytes()
        if (bytes.size < 8 || String(bytes, 0, 4, Charsets.US_ASCII) != "fLaC") return emptyList()
        val blocks = ArrayList<FlacBlock>()
        var pos = 4
        while (pos + 4 <= bytes.size) {
            val h = bytes[pos].toInt() and 0xFF
            val type = h and 0x7F
            val isLast = (h and 0x80) != 0
            val len = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + len > bytes.size) break
            blocks.add(FlacBlock(type, isLast, bytes.copyOfRange(pos, pos + len)))
            pos += len
            if (isLast) break
        }
        return blocks
    }

    /** 音频数据在文件中的起点（块链结束处）。 */
    fun audioDataStart(file: File): Long {
        val bytes = file.readBytes()
        var pos = 4
        while (pos + 4 <= bytes.size) {
            val h = bytes[pos].toInt() and 0xFF
            val len = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            pos += 4 + len
            if ((h and 0x80) != 0) break
        }
        return pos.toLong().coerceAtMost(bytes.size.toLong())
    }

    /** PICTURE 块 → (mime, 图片数据)。 */
    fun parsePicture(block: FlacBlock): PictureInfo {
        val d = block.data
        var p = 0
        fun i32(): Int {
            val v = ((d[p].toInt() and 0xFF) shl 24) or ((d[p + 1].toInt() and 0xFF) shl 16) or
                ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
            p += 4
            return v
        }
        i32() // picture type（3 = front cover）
        val mimeLen = i32()
        val mime = String(d, p, mimeLen, Charsets.US_ASCII)
        p += mimeLen
        val descLen = i32()
        p += descLen // 描述符内容（i32 已跳过长度字段本身）
        p += 16 // width / height / depth / colors
        val dataLen = i32()
        val data = d.copyOfRange(p, p + dataLen)
        return PictureInfo(mime, data)
    }

    /** VORBIS_COMMENT 块 → 字段表（键统一大写）。 */
    fun parseVorbisComment(block: FlacBlock): Map<String, String> {
        val d = block.data
        var p = 0
        // Vorbis Comment 的整数是**小端**（Xiph 规范：little-endian）。
        //
        // 早期这里按大端读，把 vendor 长度 32（字节 20 00 00 00）解析成
        // 536870912，指针直接飞出块尾 —— 真机报错：
        //
        //     FLAC 元数据写入失败：Index 536870916 out of bounds for length 116
        //
        // 于是所有「已有 VORBIS_COMMENT 的 FLAC」都写不进去（封面/歌词都失败）。
        fun i32(): Int {
            if (p + 4 > d.size) throw IllegalArgumentException("Vorbis Comment 截断 @$p/${d.size}")
            val v = (d[p].toInt() and 0xFF) or
                ((d[p + 1].toInt() and 0xFF) shl 8) or
                ((d[p + 2].toInt() and 0xFF) shl 16) or
                ((d[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendorLen = i32()
        p += vendorLen
        val count = i32()
        val out = LinkedHashMap<String, String>()
        repeat(count) {
            val len = i32()
            if (p + len > d.size) return out
            val entry = String(d, p, len, Charsets.UTF_8)
            p += len
            val idx = entry.indexOf('=')
            if (idx > 0) out[entry.substring(0, idx).uppercase()] = entry.substring(idx + 1)
        }
        return out
    }
}

/**
 * FLAC 元数据写入：封面（PICTURE 块）与歌词（Vorbis Comment `LYRICS` 字段）。
 *
 * ## 为什么手写而不是用宿主的 JAudioTagger
 *
 * 宿主进程里虽有 `org.jaudiotagger`，但插件是 compileOnly 宿主 API 的
 * 独立构建，没有它的编译期依赖；反射调用整条 JAudioTagger 链路
 * （AudioFileIO → FlacTag → TagField）脆弱且难单测。FLAC 块结构本身
 * 规整（magic + 块链 + 音频），手写解析/重建各 60 行，行为完全可控。
 *
 * ## 写入方式
 *
 * **流式重建**：解析现有块链 → 替换/插入目标块 → 新块链 + 原样复制音频
 * 到临时文件 → rename。幂等：目标块已存在（封面）或字段值相同（歌词）时
 * 不重写文件。
 */
object FlacTagWriter {

    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6
    private const val VENDOR = "kugou-lite"

    /**
     * 写入封面与/或歌词。
     *
     * @param picture 封面字节（null = 不动封面）
     * @param mime 封面 MIME（picture 非 null 时必填）
     * @param lyrics 歌词文本（null = 不动歌词）
     * @return 是否就绪（无需改动也算成功）；非 FLAC 文件返回 false
     */
    fun writeTags(file: File, picture: ByteArray?, mime: String?, lyrics: String?): Boolean {
        return try {
            val blocks = FlacTagReader.readBlocks(file)
            if (blocks.isEmpty()) return false

            // ---- 幂等判定
            val hasPicture = blocks.any { it.type == TYPE_PICTURE }
            val existingVc = blocks.firstOrNull { it.type == TYPE_VORBIS_COMMENT }
                ?.let { FlacTagReader.parseVorbisComment(it) }
                ?: emptyMap()
            val needPicture = picture != null && !hasPicture
            val needLyrics = lyrics != null && existingVc["LYRICS"] != lyrics
            if (!needPicture && !needLyrics) return true

            // ---- 重建块链：替换已有的 VC/PICTURE，缺失的补到末尾
            data class NewBlock(val type: Int, val data: ByteArray)
            val newBlocks = ArrayList<NewBlock>()
            var vcDone = false
            for (b in blocks) {
                when (b.type) {
                    TYPE_VORBIS_COMMENT -> {
                        val merged = existingVc.toMutableMap()
                        if (lyrics != null) merged["LYRICS"] = lyrics
                        newBlocks.add(NewBlock(TYPE_VORBIS_COMMENT, buildVorbisComment(merged)))
                        vcDone = true
                    }
                    // 已有封面且本次不更新 → 原样保留（幂等）
                    TYPE_PICTURE -> newBlocks.add(NewBlock(TYPE_PICTURE, b.data))
                    else -> newBlocks.add(NewBlock(b.type, b.data))
                }
            }
            if (needPicture && picture != null) {
                newBlocks.add(NewBlock(TYPE_PICTURE, buildPicture(picture, mime ?: "image/jpeg")))
            }
            if (needLyrics && !vcDone && lyrics != null) {
                newBlocks.add(NewBlock(TYPE_VORBIS_COMMENT, buildVorbisComment(mapOf("LYRICS" to lyrics))))
            }

            // ---- 流式写临时文件：magic + 块链 + 原样音频
            val audioStart = FlacTagReader.audioDataStart(file)
            val tmp = File(file.parentFile, file.name + ".flactmp")
            BufferedOutputStream(FileOutputStream(tmp)).use { out ->
                out.write("fLaC".toByteArray(Charsets.US_ASCII))
                newBlocks.forEachIndexed { i, nb ->
                    val last = i == newBlocks.lastIndex
                    out.write(blockHeader(nb.type, nb.data.size, last))
                    out.write(nb.data)
                }
                // 音频数据原样复制（按 64KB 分段，避免 36MB 整块进内存）
                file.inputStream().use { input ->
                    input.skip(audioStart)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    Diag.warn("flac", "元数据写回失败（rename）：${file.name}")
                    return false
                }
            }
            val what = listOfNotNull(
                if (needPicture) "封面" else null,
                if (needLyrics) "歌词" else null,
            ).joinToString(" + ")
            Diag.info("flac", "已写入 FLAC $what ${file.name}")
            true
        } catch (e: Exception) {
            Diag.warn("flac", "FLAC 元数据写入失败（不影响播放）：${e.message}")
            false
        }
    }

    /** 块头 4 字节：1bit last-flag + 7bit type + 24bit length。 */
    private fun blockHeader(type: Int, len: Int, last: Boolean): ByteArray {
        val h = (if (last) 0x80 else 0x00) or (type and 0x7F)
        return byteArrayOf(
            h.toByte(),
            ((len ushr 16) and 0xFF).toByte(),
            ((len ushr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte(),
        )
    }

    /** 构造 PICTURE 块数据（front cover，宽高信息不填——宿主不读它定位）。 */
    fun buildPicture(data: ByteArray, mime: String): ByteArray {
        val desc = ByteArray(0)
        val size = 4 + 4 + mime.length + 4 + desc.size + 16 + 4 + data.size
        val out = ByteBuffer.allocate(size)
        out.putInt(3) // picture type: front cover
        out.putInt(mime.length)
        out.put(mime.toByteArray(Charsets.US_ASCII))
        out.putInt(desc.size)
        out.put(desc)
        out.putInt(0) // width
        out.putInt(0) // height
        out.putInt(0) // depth
        out.putInt(0) // colors
        out.putInt(data.size)
        out.put(data)
        return out.array()
    }

    /** 构造 VORBIS_COMMENT 块数据（vendor 固定，字段键保持传入时的大小写）。 */
    fun buildVorbisComment(fields: Map<String, String>): ByteArray {
        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        val entries = fields.map { (k, v) -> "$k=$v".toByteArray(Charsets.UTF_8) }
        val size = 4 + vendor.size + 4 + entries.sumOf { 4 + it.size }
        // **必须小端**（Xiph 规范）：ByteBuffer 默认是大端，
        // 早期漏了 order() 导致写出的块长度字段全错，其它软件/宿主读不了。
        val out = ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putInt(vendor.size)
        out.put(vendor)
        out.putInt(entries.size)
        for (e in entries) {
            out.putInt(e.size)
            out.put(e)
        }
        return out.array()
    }
}
