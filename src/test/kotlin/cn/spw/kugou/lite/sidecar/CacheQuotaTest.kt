package cn.spw.kugou.lite.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 缓存容量上限（LRU 淘汰）测试（用户需求：默认 1 GB 上限）。
 */
class CacheQuotaTest {

    private fun file(dir: File, name: String, size: Int, mtime: Long) =
        File(dir, name).apply { writeBytes(ByteArray(size)); setLastModified(mtime) }

    /** 超限时从最旧开始淘汰，直到回到限额内。 */
    @Test
    fun evictsOldestFirst(@TempDir dir: File) {
        file(dir, "a.img", 100, 1_000L)
        file(dir, "b.img", 100, 2_000L)
        file(dir, "c.img", 100, 3_000L)
        val removed = CacheQuota.enforce(dir, limitBytes = 250L)
        assertEquals(listOf(File(dir, "a.img")), removed)
        assertTrue(dir.listFiles()!!.none { it.name == "a.img" })
        assertTrue(dir.listFiles()!!.any { it.name == "b.img" })
    }

    /** 限额内不动。 */
    @Test
    fun noEvictionUnderLimit(@TempDir dir: File) {
        file(dir, "a.img", 100, 1_000L)
        val removed = CacheQuota.enforce(dir, limitBytes = 1_000L)
        assertTrue(removed.isEmpty())
    }

    /** 单文件超限也淘汰（哪怕淘汰后为空）。 */
    @Test
    fun evictsWhenSingleFileOverLimit(@TempDir dir: File) {
        file(dir, "big.mp3", 5_000, 1_000L)
        val removed = CacheQuota.enforce(dir, limitBytes = 1_000L)
        assertEquals(1, removed.size)
    }

    /** 空目录安全。 */
    @Test
    fun emptyDirSafe(@TempDir dir: File) {
        val removed = CacheQuota.enforce(dir, limitBytes = 100L)
        assertTrue(removed.isEmpty())
    }
}
