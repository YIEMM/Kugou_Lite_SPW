package cn.spw.kugou.lite.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StorageMoverTest {

    /** 迁移：全部文件到新目录，旧目录清空。 */
    @Test
    fun movesAllFiles(@TempDir oldDir: File, @TempDir newDir: File) {
        File(oldDir, "a.img").writeBytes(ByteArray(10))
        File(oldDir, "b.mp3").writeBytes(ByteArray(20))
        val r = StorageMover.move(oldDir, newDir)
        assertEquals(2, r.moved)
        assertEquals(0, r.failed)
        assertTrue(File(newDir, "a.img").isFile)
        assertTrue(File(newDir, "b.mp3").isFile)
        assertTrue(oldDir.listFiles()!!.isEmpty())
    }

    /** 删除：清空旧目录。 */
    @Test
    fun deletesAllFiles(@TempDir oldDir: File) {
        File(oldDir, "a.img").writeBytes(ByteArray(10))
        File(oldDir, "b.mp3").writeBytes(ByteArray(20))
        assertEquals(2, StorageMover.deleteAll(oldDir))
        assertTrue(oldDir.listFiles()!!.isEmpty())
    }

    /** 空目录迁移安全。 */
    @Test
    fun emptyDirMoveSafe(@TempDir oldDir: File, @TempDir newDir: File) {
        val r = StorageMover.move(oldDir, newDir)
        assertEquals(0, r.moved)
        assertEquals(0, r.failed)
    }
}
