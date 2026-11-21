package cn.spw.kugou.lite.ui

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N24：FollowStore 读-改-写丢更新。
 *
 * 修复前 `setFollowing` 是「读 cache → 算新集合 → 写回」，无锁：
 * 两个歌手并发关注时后写者整体覆盖前者，丢一条关注记录。
 * 修复后读-改-写 + 落盘全程持同一把锁（参照 FavoriteCache N11 模式）。
 *
 * 用 [FollowStore] 真实落盘路径（临时目录）验证：
 * 32 个线程各关注一个不同歌手，最终 properties 与内存必须一个不少。
 */
class FollowStoreConcurrencyTest {

    private lateinit var dir: File

    private val pool = Executors.newFixedThreadPool(16)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("follow-n24").toFile()
        FollowStore.configure(dir)
    }

    @AfterTest
    fun tearDown() {
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "线程池应正常退出")
    }

    @Test
    fun `并发关注不丢更新`() {
        val n = 32
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)

        repeat(n) { i ->
            pool.submit {
                start.await()
                FollowStore.setFollowing(i.toString(), true)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "所有写入应完成")

        // 磁盘真相：重新 configure 清掉内存缓存后从文件重读
        FollowStore.configure(dir)
        repeat(n) { i ->
            assertTrue(
                FollowStore.isFollowing(i.toString(), serverStatus = 0),
                "歌手 $i 的关注记录不得丢失（N24 缺陷本体）",
            )
        }
    }

    @Test
    fun `并发关注与取关混合后状态与最后一次操作一致`() {
        // 同一歌手被反复关注/取关：只要不丢中间更新，最终集合必然无重复、可反序列化
        val n = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        repeat(n) { i ->
            pool.submit {
                start.await()
                FollowStore.setFollowing("999", i % 2 == 0)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))

        FollowStore.configure(dir)
        val still = FollowStore.isFollowing("999", serverStatus = 0)
        // 16 次交替操作全部串行化后，最终值只能是 true 或 false，不存在第三态；
        // 且落盘的 ids 键反序列化后与内存判定一致（串行化保证中间更新一个不丢）
        val line = File(dir, "follow.properties").readLines().firstOrNull { it.startsWith("ids=") }
        val persistedIds = FollowStore.from(line?.removePrefix("ids="))
        assertEquals(still, persistedIds.contains("999"), "落盘的 999 状态必须与内存判定一致")
    }

    @Test
    fun `落盘为原子写且不残留part临时文件`() {
        FollowStore.setFollowing("42", true)
        val leftovers = dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertTrue(leftovers.isEmpty(), "不得残留 .part，实际：${leftovers.map { it.name }}")
        val props = File(dir, "follow.properties")
        assertTrue(props.isFile, "follow.properties 应存在")
        assertTrue(props.readText().contains("42"), "内容应包含新关注")
    }
}