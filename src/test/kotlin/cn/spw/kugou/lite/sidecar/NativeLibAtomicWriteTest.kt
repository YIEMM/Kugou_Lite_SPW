package cn.spw.kugou.lite.sidecar

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N24：解压 writeBytes + writeText 非原子。
 *
 * 修复前：直接 target.writeBytes(bytes) + stamp.writeText(digest)，
 * 进程在中间被杀留下「旧 stamp + 半截新 dll」—— 下次启动指纹匹配
 * 跳过重写，System.load 加载截断 dll 崩溃。修复后统一走 .part + rename
 * （与 AudioCache.atomicWriteText 同模式）。
 */
class NativeLibAtomicWriteTest {

    private lateinit var dir: File

    private val pool = Executors.newFixedThreadPool(8)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("native-n24").toFile()
    }

    @AfterTest
    fun tearDown() {
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun `原子写字节：目标完整且无 part 残留`() {
        val target = File(dir, "lib.dll")
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        NativeLibExtractor.atomicWriteBytes(target, bytes)

        assertTrue(target.isFile, "目标应存在")
        assertTrue(target.readBytes().contentEquals(bytes), "目标内容必须完整（半截 dll 即 N24 缺陷本体）")
        val leftovers = dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertTrue(leftovers.isEmpty(), "不得残留 .part，实际：${leftovers.map { it.name }}")
    }

    @Test
    fun `原子写文本：旧内容被完整替换`() {
        val target = File(dir, "lib.dll.sha256")
        target.writeText("stale-digest", Charsets.UTF_8)
        NativeLibExtractor.atomicWriteText(target, "fresh-digest")
        assertEquals("fresh-digest", target.readText(Charsets.UTF_8))
    }

    @Test
    fun `覆盖已存在目标不残留旧版本`() {
        val target = File(dir, "lib.dll")
        NativeLibExtractor.atomicWriteBytes(target, ByteArray(1024))
        val fresh = ByteArray(2048) { (it * 7).toByte() }
        NativeLibExtractor.atomicWriteBytes(target, fresh)
        assertTrue(target.readBytes().contentEquals(fresh), "重写后必须是新内容")
        val leftovers = dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertTrue(leftovers.isEmpty(), "不得残留 .part")
    }

    @Test
    fun `并发原子写互不截断`() {
        val target = File(dir, "lib.dll")
        val n = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        val okCount = AtomicInteger(0)
        repeat(n) { k ->
            pool.submit {
                start.await()
                try {
                    // 每个线程写不同填充值的缓冲；修复前并发 writeBytes 会互相截断
                    NativeLibExtractor.atomicWriteBytes(target, ByteArray(8192) { k.toByte() })
                    okCount.incrementAndGet()
                } catch (_: Throwable) {
                    // rename 竞态下的兜底重试是允许的：只要最终文件完整
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        val size = target.length()
        assertEquals(8192L, size, "并发写后文件长度必须是完整的一块（截断即 N24 缺陷本体）")
        val body = target.readBytes()
        // 内容必须是某一个线程的完整缓冲，而不是混合体
        val fillers = setOf(body[0], body[size.toInt() - 1])
        assertEquals(1, fillers.size, "首尾填充值必须一致（混合即被截断拼接）")
        assertTrue(body.all { it == body[0] }, "全文必须是单一填充值的完整缓冲")
        assertTrue(okCount.get() == n || okCount.get() > 0)
    }
}
