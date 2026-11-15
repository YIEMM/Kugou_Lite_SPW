package cn.spw.kugou.lite.sidecar

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-11-15 审计的回归断言（N02 / N04 / N05 / N20）。
 *
 * 这些缺陷由临时探针 P02/P04/P04b/P06/P12 复现，修复后改写为回归断言。
 */
class CacheAndDurationAuditTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("cache-audit").toFile()
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    // ---------------------------------------------------------- N04 扩展名净化

    @Test
    fun `N04 扩展名里的路径分隔符不能逃出缓存目录`() {
        val name = cacheName("ABC", "128", "../../../evil")
        assertFalse(name.contains("/"), "文件名不能含 /：$name")
        assertFalse(name.contains("\\"), "文件名不能含 \\：$name")
        assertFalse(name.contains(".."), "文件名不能含 ..：$name")
    }

    @Test
    fun `N04 未知扩展名回退 mp3`() {
        assertEquals("ABC-128.mp3", cacheName("ABC", "128", "exe"))
        assertEquals("ABC-128.flac", cacheName("ABC", "128", "flac"))
    }

    // ---------------------------------------------------------- N05 垃圾缓存

    @Test
    fun `N05 三字节垃圾文件不算有效音频`() {
        val f = File(dir, "junk-128.mp3")
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(AudioCache.isPlausibleAudio(f), "3 字节文件不能当成有效缓存")
    }

    @Test
    fun `N05 体积够但文件头不对也不算有效音频`() {
        val f = File(dir, "fake-128.mp3")
        f.writeBytes(ByteArray(64 * 1024) { 0x41 }) // 全 'A'，不是音频
        assertFalse(AudioCache.isPlausibleAudio(f), "文件头不匹配不能当成有效缓存")
    }

    @Test
    fun `N05 合法 ID3 头的 mp3 算有效音频`() {
        val f = File(dir, "ok-128.mp3")
        val head = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0)
        f.writeBytes(head + ByteArray(16 * 1024))
        assertTrue(AudioCache.isPlausibleAudio(f))
    }

    @Test
    fun `N05 合法 flac 魔数算有效音频`() {
        val f = File(dir, "ok-128.flac")
        val head = "fLaC".toByteArray(Charsets.ISO_8859_1)
        f.writeBytes(head + ByteArray(16 * 1024))
        assertTrue(AudioCache.isPlausibleAudio(f))
    }
}
