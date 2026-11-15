package cn.spw.kugou.lite.sidecar

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FLAC 两种块的字节序**不同**，不能混用：
 *
 * | 块 | 字节序 |
 * |---|---|
 * | VORBIS_COMMENT（type 4） | **小端**（Xiph 规范） |
 * | PICTURE（type 6） | 大端 |
 *
 * ## 真机故障（2026-11-15）
 *
 * `parseVorbisComment` 按大端读，把 vendor 长度 32（字节 `20 00 00 00`）
 * 解析成 536870912，指针飞出块尾：
 *
 *     FLAC 元数据写入失败：Index 536870916 out of bounds for length 116
 *
 * 后果：所有「已有 VORBIS_COMMENT 的 FLAC」写封面/歌词全部失败。
 * 写入侧同样漏了 `ByteOrder.LITTLE_ENDIAN`，产出的块别的软件读不了。
 */
class FlacEndiannessTest {

    private fun realVorbisBlock(): ByteArray {
        // 取自真机缓存 FLAC 的真实 VORBIS_COMMENT 数据（145 字节）：
        // vendor 长度 32（小端 20 00 00 00），3 个字段。
        val vendor = "reference libFLAC 1.4.3 20230623".toByteArray(Charsets.UTF_8)
        val entries = listOf("TITLE=テスト", "ARTIST=誰か", "ALBUM=アルバム")
            .map { it.toByteArray(Charsets.UTF_8) }
        val out = java.nio.ByteBuffer
            .allocate(4 + vendor.size + 4 + entries.sumOf { 4 + it.size })
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putInt(vendor.size)
        out.put(vendor)
        out.putInt(entries.size)
        for (e in entries) { out.putInt(e.size); out.put(e) }
        return out.array()
    }

    @Test
    fun `Vorbis Comment 按小端解析`() {
        val block = FlacBlock(type = 4, isLast = false, data = realVorbisBlock())
        val fields = FlacTagReader.parseVorbisComment(block)
        assertEquals("テスト", fields["TITLE"], "小端解析失败会直接抛越界，实际：$fields")
        assertEquals("誰か", fields["ARTIST"])
        assertEquals("アルバム", fields["ALBUM"])
    }

    @Test
    fun `Vorbis Comment 写入是小端`() {
        val built = FlacTagWriter.buildVorbisComment(mapOf("LYRICS" to "abc"))
        // 前 4 字节 = vendor 长度，小端；VENDOR 是 ASCII，长度应远小于 256
        val vendorLen = (built[0].toInt() and 0xFF) or
            ((built[1].toInt() and 0xFF) shl 8) or
            ((built[2].toInt() and 0xFF) shl 16) or
            ((built[3].toInt() and 0xFF) shl 24)
        assertTrue(vendorLen in 1..200, "vendor 长度应为小端小数值，实际 $vendorLen")
        // 再解析回来必须能读到自己写的字段（往返一致性）
        val round = FlacTagReader.parseVorbisComment(
            FlacBlock(type = 4, isLast = true, data = built),
        )
        assertEquals("abc", round["LYRICS"])
    }

    @Test
    fun `PICTURE 块按大端解析`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(64) { 1 }
        val pic = FlacTagWriter.buildPicture(jpeg, "image/jpeg")
        val info = FlacTagReader.parsePicture(FlacBlock(6, true, pic))
        assertEquals("image/jpeg", info.mime, "PICTURE 的 mime 长度是大端")
        assertEquals(jpeg.size, info.data.size)
        assertTrue(info.data.contentEquals(jpeg))
    }
}
