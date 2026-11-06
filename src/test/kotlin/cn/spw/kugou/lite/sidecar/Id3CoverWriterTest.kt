package cn.spw.kugou.lite.sidecar

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Id3CoverWriter] 的帧写入测试。
 *
 * ## 重点回归：尾部填充
 *
 * 真机踩坑：酷狗返回的 mp3 自带 ID3 标签，**末尾有若干 `0x00` 填充**。
 * 早期追加逻辑是「旧标签 + 新帧」，新帧被顶到填充**后面**，
 * jaudiotagger（宿主用的解析器）扫到填充就认为帧区结束，
 * 于是：
 * - 日志显示「已追加封面帧」
 * - 但宿主读不到封面，用工具看标签里只有 TXXX/TSSE
 *
 * 这里用「带尾部填充的标签」构造测试文件，验证写入后 APIC 位于
 * 帧区内且填充已被清理。
 */
class Id3CoverWriterTest {

    /** 构造一个带尾部填充的 mp3（ID3v2.3 + TSSE 帧 + 7 字节填充 + 假音频）。 */
    private fun makeTaggedMp3(withPadding: Int): File {
        val f = File.createTempFile("id3test", ".mp3")
        f.deleteOnExit()

        // 一个 TSSE 帧：'TSSE' + size(4) + flags(2) + body
        val body = "Lavf58".toByteArray(Charsets.ISO_8859_1)
        val frame = ByteArray(10 + body.size)
        "TSSE".toByteArray(Charsets.ISO_8859_1).copyInto(frame)
        frame[4] = ((body.size shr 24) and 0xFF).toByte()
        frame[5] = ((body.size shr 16) and 0xFF).toByte()
        frame[6] = ((body.size shr 8) and 0xFF).toByte()
        frame[7] = (body.size and 0xFF).toByte()
        body.copyInto(frame, 10)

        val tagSize = frame.size + withPadding
        val header = ByteArray(10)
        "ID3".toByteArray(Charsets.ISO_8859_1).copyInto(header)
        header[3] = 3; header[4] = 0; header[5] = 0
        header[6] = ((tagSize shr 21) and 0x7F).toByte()
        header[7] = ((tagSize shr 14) and 0x7F).toByte()
        header[8] = ((tagSize shr 7) and 0x7F).toByte()
        header[9] = (tagSize and 0x7F).toByte()

        RandomAccessFile(f, "rw").use { raf ->
            raf.write(header)
            raf.write(frame)
            // 尾部填充
            if (withPadding > 0) raf.write(ByteArray(withPadding))
            // 假音频数据（MP3 帧同步字）
            raf.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x00, 0x00))
        }
        return f
    }

    /** 读出标签里的帧名列表（遇到填充即停止，与宿主解析器一致）。 */
    private fun frameNames(f: File): List<String> {
        RandomAccessFile(f, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) < 10) return emptyList()
            val size = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)
            // 读实际可用的部分（文件可能比声明的标签短）
            val available = (raf.length() - 10).coerceAtMost(size.toLong()).toInt()
            if (available <= 0) return emptyList()
            val tag = ByteArray(available)
            val read = raf.read(tag)
            if (read < 10) return emptyList()

            val out = ArrayList<String>()
            var pos = 0
            while (pos + 10 <= read) {
                if (tag[pos] == 0.toByte()) break
                val fsize = ((tag[pos + 4].toInt() and 0xFF) shl 24) or
                    ((tag[pos + 5].toInt() and 0xFF) shl 16) or
                    ((tag[pos + 6].toInt() and 0xFF) shl 8) or
                    (tag[pos + 7].toInt() and 0xFF)
                if (fsize < 0 || pos + 10 + fsize > read) break
                out.add(String(tag, pos, 4, Charsets.ISO_8859_1))
                pos += 10 + fsize
            }
            return out
        }
    }

    @Test
    fun `带尾部填充的标签也能正确追加封面`() {
        val mp3 = makeTaggedMp3(withPadding = 7)
        // 写入一张假 JPEG
        val cover = ByteArray(200) { 0x41 }
        cover[0] = 0xFF.toByte(); cover[1] = 0xD8.toByte(); cover[2] = 0xFF.toByte()

        assertTrue(Id3CoverWriter.writeCover(mp3, cover, "image/jpeg"))

        val names = frameNames(mp3)
        assertTrue(names.contains("TSSE"), "原有帧应保留，实际：$names")
        assertTrue(
            names.contains("APIC"),
            "APIC 必须位于填充之前（否则宿主读不到），实际帧序列：$names",
        )
    }

    @Test
    fun `已存在可读的 APIC 时不重复写`() {
        val mp3 = makeTaggedMp3(withPadding = 0)
        val cover = ByteArray(100) { 0x42 }
        cover[0] = 0xFF.toByte(); cover[1] = 0xD8.toByte(); cover[2] = 0xFF.toByte()

        assertTrue(Id3CoverWriter.writeCover(mp3, cover, "image/jpeg"))
        assertTrue(Id3CoverWriter.hasApicFrame(mp3), "写入后应能检测到 APIC")

        val sizeAfterFirst = mp3.length()
        assertTrue(Id3CoverWriter.writeCover(mp3, cover, "image/jpeg"))
        assertEquals(sizeAfterFirst, mp3.length(), "第二次写入应被跳过，文件大小不变")
    }

    @Test
    fun `填充之后的 APIC 不算已有封面`() {
        // 手工构造：TSSE + 7 字节填充 + APIC（模拟旧版 bug 写出的文件）
        val mp3 = makeTaggedMp3(withPadding = 7)
        val apicBody = ByteArray(10) { 0 }
        val apic = ByteArray(10 + apicBody.size)
        "APIC".toByteArray(Charsets.ISO_8859_1).copyInto(apic)
        apic[7] = apicBody.size.toByte()

        RandomAccessFile(mp3, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(apic)
        }

        // 宿主的解析器读不到（填充之后的），所以我们也不能认为「已有封面」
        assertFalse(
            Id3CoverWriter.hasApicFrame(mp3),
            "填充之后的 APIC 不可读，不应被判定为已有封面",
        )
    }

    @Test
    fun `无标签文件也能写入封面`() {
        val f = File.createTempFile("notag", ".mp3")
        f.deleteOnExit()
        RandomAccessFile(f, "rw").use { it.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())) }

        val cover = ByteArray(50) { 0x43 }
        cover[0] = 0xFF.toByte(); cover[1] = 0xD8.toByte(); cover[2] = 0xFF.toByte()
        assertTrue(Id3CoverWriter.writeCover(f, cover, "image/jpeg"))
        assertTrue(frameNames(f).contains("APIC"))
    }

    /**
     * 回归：酷狗原始标签的 size 少 1 字节。
     *
     * 真机实测某首歌的 TALB 帧声明 size=59、实际占 60 字节，
     * 导致后续帧解析整体偏移，写进去的 APIC 被解析成 `PIC` + 天文数字 size。
     *
     * 修复方式是「解析旧帧 → 规范重建」，本测试验证重建后帧序列合法。
     */
    @Test
    fun `原始标签 size 错位时也能重建出合法标签`() {
        val f = File.createTempFile("badid3", ".mp3")
        f.deleteOnExit()

        // 构造：TALB 真实 body 60 字节，但声明的 size 写成 59（少 1）
        val text = "Morning Coffee".toByteArray(Charsets.UTF_16) // 带 BOM
        val realBody = ByteArray(1 + text.size)
        realBody[0] = 1
        text.copyInto(realBody, 1)

        val declaredSize = realBody.size - 1 // 故意少 1，复现酷狗的坏标签

        val talb = ByteArray(10 + realBody.size)
        "TALB".toByteArray(Charsets.ISO_8859_1).copyInto(talb)
        talb[4] = ((declaredSize shr 24) and 0xFF).toByte()
        talb[5] = ((declaredSize shr 16) and 0xFF).toByte()
        talb[6] = ((declaredSize shr 8) and 0xFF).toByte()
        talb[7] = (declaredSize and 0xFF).toByte()
        realBody.copyInto(talb, 10)

        val header = ByteArray(10)
        "ID3".toByteArray(Charsets.ISO_8859_1).copyInto(header)
        header[3] = 3
        val tagSize = talb.size
        header[6] = ((tagSize shr 21) and 0x7F).toByte()
        header[7] = ((tagSize shr 14) and 0x7F).toByte()
        header[8] = ((tagSize shr 7) and 0x7F).toByte()
        header[9] = (tagSize and 0x7F).toByte()

        RandomAccessFile(f, "rw").use { raf ->
            raf.write(header)
            raf.write(talb)
            raf.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte()))
        }

        val cover = ByteArray(120) { 0x44 }
        cover[0] = 0xFF.toByte(); cover[1] = 0xD8.toByte(); cover[2] = 0xFF.toByte()
        assertTrue(Id3CoverWriter.writeCover(f, cover, "image/jpeg"))

        // 重建后必须是合法的 APIC（而不是被读成 PIC + 乱码 size）
        val names = frameNames(f)
        assertTrue(names.contains("APIC"), "重建后应有合法 APIC，实际：$names")
        assertFalse(names.contains("PIC "), "不应出现错位的 PIC 帧，实际：$names")
        assertTrue(Id3CoverWriter.hasApicFrame(f), "重建后应能检测到 APIC")
    }
}
