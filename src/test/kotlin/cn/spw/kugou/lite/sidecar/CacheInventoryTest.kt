package cn.spw.kugou.lite.sidecar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 缓存清单扫描与删除（B1 下载/缓存管理页的数据层）。
 *
 * 缓存目录里的文件形如：
 * - 音频：`<hash>-<音质>.<ext>`（如 `ABC123-128.mp3`）
 * - 歌词：与音频同名 `.lrc`（或 `.krc` 原文）
 * - 封面：`cover-<hash>.<ext>`
 * - 下载中：`*.part`（半成品，不算可用条目）
 *
 * 管理页要能列出音频条目（含随附歌词的大小）并支持单曲删除
 * （音频 + 歌词一起删），不能把封面/半成品当成条目。
 */
class CacheInventoryTest {

    private fun dirWith(vararg files: Pair<String, Long>): File {
        val dir = java.nio.file.Files.createTempDirectory("cache-inv").toFile()
        files.forEach { (name, size) ->
            val f = File(dir, name)
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(size.toInt().coerceAtLeast(1)))
        }
        return dir
    }

    @Test
    fun `扫描列出音频条目并解析音质与扩展名`() {
        val dir = dirWith(
            "ABC123-128.mp3" to 1000L,
            "DEF456-flac.flac" to 2000L,
        )

        val entries = cacheEntries(dir)

        assertEquals(2, entries.size)
        val mp3 = entries.first { it.audio.name == "ABC123-128.mp3" }
        assertEquals("128", mp3.quality)
        assertEquals("mp3", mp3.ext)
        assertEquals(1000L, mp3.totalBytes)
        val flac = entries.first { it.audio.name == "DEF456-flac.flac" }
        assertEquals("flac", flac.quality)
        assertEquals("flac", flac.ext)
    }

    @Test
    fun `歌词 sidecar 计入条目大小并可一起删除`() {
        val dir = dirWith(
            "ABC123-128.mp3" to 1000L,
            "ABC123-128.lrc" to 300L,
            "ABC123-128.krc" to 500L,
        )

        val entries = cacheEntries(dir)
        assertEquals(1, entries.size, "同名的音频 + lrc + krc 是一条")
        val entry = entries.single()
        assertEquals(1800L, entry.totalBytes, "totalBytes = 音频 + lrc + krc")
        assertEquals(setOf("ABC123-128.lrc", "ABC123-128.krc"), entry.sidecars.map { it.name }.toSet())

        val ok = deleteCacheEntry(dir, entry.audio)
        assertTrue(ok)
        assertFalse(File(dir, "ABC123-128.mp3").exists())
        assertFalse(File(dir, "ABC123-128.lrc").exists())
        assertFalse(File(dir, "ABC123-128.krc").exists())
    }

    @Test
    fun `封面与半成品与孤立歌词不算音频条目`() {
        val dir = dirWith(
            "cover-ABC123.png" to 100L,
            "XYZ-320.mp3.part" to 100L,
            "orphan-128.lrc" to 100L,
            "readme.txt" to 100L,
        )

        val entries = cacheEntries(dir)
        assertEquals(0, entries.size, "这些都不是可播放的音频缓存")
    }

    @Test
    fun `单曲删除不动其他条目`() {
        val dir = dirWith(
            "AAA-128.mp3" to 100L,
            "BBB-320.mp3" to 200L,
            "BBB-320.lrc" to 50L,
        )

        val entries = cacheEntries(dir)
        val aaa = entries.first { it.audio.name == "AAA-128.mp3" }
        deleteCacheEntry(dir, aaa.audio)

        assertFalse(File(dir, "AAA-128.mp3").exists())
        assertTrue(File(dir, "BBB-320.mp3").exists(), "其他条目必须保留")
        assertTrue(File(dir, "BBB-320.lrc").exists(), "其他条目的歌词必须保留")
    }

    @Test
    fun `越新的条目排越前`() {
        val dir = dirWith("AAA-128.mp3" to 100L, "BBB-320.mp3" to 100L)
        val a = File(dir, "AAA-128.mp3")
        val b = File(dir, "BBB-320.mp3")
        a.setLastModified(1_000L)
        b.setLastModified(2_000L)

        val entries = cacheEntries(dir)
        assertEquals(listOf("BBB-320.mp3", "AAA-128.mp3"), entries.map { it.audio.name })
    }
}
