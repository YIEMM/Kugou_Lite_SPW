package cn.spw.kugou.lite.sidecar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FLAC 元数据块写入（用户反馈：FLAC 无法写入封面与歌词）。
 *
 * 宿主 TagParser（反汇编确认）读 FLAC：
 * - 封面：`readFlacArtworkLazily` —— SaltAudioTagReader 的 pictures，
 *   失败回退 JAudioTagger 的 firstArtwork（= METADATA_BLOCK_PICTURE，type 6）
 * - 歌词：`readFlacLyricsWithoutPictures` —— Vorbis Comment 的
 *   `LYRICS` / `UNSYNCEDLYRICS` 字段（不区分大小写）
 *
 * FLAC 结构：`fLaC` magic + METADATA_BLOCK 链（头 4 字节 =
 * 1bit last-flag + 7bit type + 24bit length）+ 音频数据。
 * STREAMINFO(type 0) 必须是第一个块；写 = 重建块链 + 原样复制音频。
 */
class FlacTagWriterTest {

    /** 构造最小合法 FLAC：fLaC + STREAMINFO(34B) + 音频数据。 */
    private fun flacFile(dir: File, name: String = "a.flac"): File {
        val f = File(dir, name)
        val out = java.io.ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // STREAMINFO：last=1（初始只有这一个块），type=0，len=34
        out.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22.toByte()))
        out.write(ByteArray(34) { it.toByte() })
        out.write("AUDIO_DATA_PLACEHOLDER".toByteArray(Charsets.US_ASCII))
        f.writeBytes(out.toByteArray())
        return f
    }

    @Test
    fun `写封面后能解析出 PICTURE 块`() {
        val f = flacFile(java.nio.file.Files.createTempDirectory("flac").toFile())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)

        val ok = FlacTagWriter.writeTags(f, picture = jpeg, mime = "image/jpeg", lyrics = null)

        assertTrue(ok)
        val blocks = FlacTagReader.readBlocks(f)
        val pic = blocks.filter { it.type == 6 }
        assertEquals(1, pic.size, "应恰好一个 PICTURE 块")
        val parsed = FlacTagReader.parsePicture(pic.single())
        assertEquals("image/jpeg", parsed.mime)
        assertTrue(parsed.data.contentEquals(jpeg))
        // last-flag 落在最后一个块上（PICTURE 之后没有别的块）
        assertTrue(pic.single().isLast)
    }

    @Test
    fun `写歌词后 VORBIS_COMMENT 含 LYRICS 字段`() {
        val f = flacFile(java.nio.file.Files.createTempDirectory("flac").toFile())

        val ok = FlacTagWriter.writeTags(f, picture = null, mime = null, lyrics = "[00:01.00]测试")

        assertTrue(ok)
        val vc = FlacTagReader.readBlocks(f).filter { it.type == 4 }
        assertEquals(1, vc.size)
        assertEquals(mapOf("LYRICS" to "[00:01.00]测试"), FlacTagReader.parseVorbisComment(vc.single()))
    }

    @Test
    fun `已有 VORBIS_COMMENT 保留原字段并覆盖 LYRICS`() {
        val dir = java.nio.file.Files.createTempDirectory("flac").toFile()
        val f = flacFile(dir)
        // 预置一个带 ALBUM 字段的 VORBIS_COMMENT
        val vcBytes = FlacTagWriter.buildVorbisComment(mapOf("ALBUM" to "专辑名"))
        val withVc = File(dir, "b.flac")
        val out = java.io.ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x00.toByte(), 0x00, 0x00, 0x22.toByte())) // STREAMINFO，非 last
        out.write(ByteArray(34)) // STREAMINFO 数据（header 声明 len=34）
        out.write(byteArrayOf((0x80 or 0x04).toByte()) + byteArrayOf(
            ((vcBytes.size ushr 16) and 0xFF).toByte(),
            ((vcBytes.size ushr 8) and 0xFF).toByte(),
            (vcBytes.size and 0xFF).toByte(),
        ))
        out.write(vcBytes)
        out.write("AUDIO".toByteArray(Charsets.US_ASCII))
        withVc.writeBytes(out.toByteArray())

        val ok = FlacTagWriter.writeTags(withVc, picture = null, mime = null, lyrics = "新歌词")

        assertTrue(ok)
        val vc = FlacTagReader.readBlocks(withVc).filter { it.type == 4 }.single()
        val fields = FlacTagReader.parseVorbisComment(vc)
        assertEquals("专辑名", fields["ALBUM"], "原有字段必须保留")
        assertEquals("新歌词", fields["LYRICS"])
    }

    @Test
    fun `重复写入不产生重复块`() {
        val f = flacFile(java.nio.file.Files.createTempDirectory("flac").toFile())
        val jpeg = byteArrayOf(1, 2, 3)

        FlacTagWriter.writeTags(f, picture = jpeg, mime = "image/jpeg", lyrics = null)
        FlacTagWriter.writeTags(f, picture = jpeg, mime = "image/jpeg", lyrics = null)

        assertEquals(1, FlacTagReader.readBlocks(f).count { it.type == 6 }, "幂等：PICTURE 不重复")
    }

    @Test
    fun `音频数据原样保留`() {
        val f = flacFile(java.nio.file.Files.createTempDirectory("flac").toFile())
        val before = f.readBytes()

        FlacTagWriter.writeTags(f, picture = byteArrayOf(9, 8, 7), mime = "image/png", lyrics = "词")

        val after = f.readBytes()
        assertTrue(
            before.decodeToString().endsWith("AUDIO_DATA_PLACEHOLDER") &&
                after.decodeToString().endsWith("AUDIO_DATA_PLACEHOLDER"),
            "音频数据必须原样保留在块链之后",
        )
    }

    @Test
    fun `非 FLAC 文件拒绝写入`() {
        val f = java.nio.file.Files.createTempDirectory("flac").toFile().resolve("x.flac")
        f.writeBytes("not a flac".toByteArray())

        assertFalse(FlacTagWriter.writeTags(f, picture = byteArrayOf(1), mime = "image/png", lyrics = null))
    }
}
