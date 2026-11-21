package cn.spw.kugou.lite.audit

import cn.spw.kugou.lite.host.dropRemovedMessage
import cn.spw.kugou.lite.sidecar.AudioCache
import cn.spw.kugou.lite.sidecar.WelfareApi
import cn.spw.kugou.lite.sidecar.pruneDayKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * N06 / N16 / N17 修复的回归测试（2026-11-15 审计收尾）。
 *
 * - N06：短歌词（<100 字节但带当前版本戳）不再被判为「不存在」反复重写、
 *   缓存命中路径不再重复请求歌词接口。
 * - N16：队列剔除日志打印「剔除了多少首（前后数量）」，不再是两个相同的数。
 * - N17：WelfareApi 的读-改-写全程持锁（并发不丢更新）；signed_ 键按日期清理。
 */
class N06N16N17FixTest {

    // ------------------------------------------------------------- N06

    /** 短于 100 字节但带当前生成器戳的歌词：应被视为新鲜，不重写。 */
    @Test
    fun n06ShortLyricsWithCurrentStampIsFresh(@TempDir dir: File) {
        val audio = File(dir, "H-128.mp3").apply { writeBytes(ByteArray(16)) }
        val short = "[by:KugouLite/v7]\n[00:01.00]la\n[00:02.00]la"

        assertTrue(AudioCache.writeLrcSidecar(audio, short))
        val lrc = File(dir, "H-128.lrc")
        assertTrue(lrc.length() < 100, "前提：这条歌词确实短于 100 字节")
        assertTrue(AudioCache.hasFreshLyrics(audio), "带版本戳的短歌词应当算新鲜")
    }

    /** 内容没变（同版本戳）时第二次写入不应发生——lastModified 不变。 */
    @Test
    fun n06SameStampShortLyricsIsNotRewritten(@TempDir dir: File) {
        val audio = File(dir, "H-128.mp3").apply { writeBytes(ByteArray(16)) }
        val short = "[by:KugouLite/v7]\n[00:01.00]la"

        assertTrue(AudioCache.writeLrcSidecar(audio, short))
        val lrc = File(dir, "H-128.lrc")
        val first = lrc.lastModified()
        Thread.sleep(60)
        assertTrue(AudioCache.writeLrcSidecar(audio, short))
        assertEquals(first, lrc.lastModified(), "同版本短歌词不应被重复写入")
    }

    /** 没有版本戳的旧短歌词仍然要重写（版本失效语义不能因长度而丢）。 */
    @Test
    fun n06LegacyShortLyricsStillNeedsRewrite(@TempDir dir: File) {
        val audio = File(dir, "H-128.mp3").apply { writeBytes(ByteArray(16)) }
        val legacy = "[00:01.00]la\n[00:02.00]la"
        File(dir, "H-128.lrc").writeText(legacy)

        assertFalse(AudioCache.hasFreshLyrics(audio), "旧格式（无戳）必须重写")
    }

    // ------------------------------------------------------------- N16

    @Test
    fun n16DropLogShowsBeforeAfterAndRemoved() {
        assertEquals("队列登记剔除 3 首（10 → 7）", dropRemovedMessage(10, 7))
        assertEquals("队列登记剔除 1 首（5 → 4）", dropRemovedMessage(5, 4))
    }

    // ------------------------------------------------------------- N17

    /** pruneDayKeys：按键尾部日期从新到旧保留 keep 个。 */
    @Test
    fun n17PruneKeepsNewestAndDropsOlder() {
        val p = Properties()
        for (d in 1..50) p.setProperty("signed_u1_2026-01-" + d.toString().padStart(2, '0'), "1")
        pruneDayKeys(p, "signed_", 40)
        val left = p.stringPropertyNames().filter { it.startsWith("signed_") }
        assertEquals(40, left.size)
        assertFalse(left.contains("signed_u1_2026-01-01"))
        assertTrue(left.contains("signed_u1_2026-01-50"))
    }

    /** 键数量在限额内时不动（幂等、无副作用）。 */
    @Test
    fun n17PruneKeepsWhenUnderLimit() {
        val p = Properties()
        p.setProperty("signed_u1_2026-01-01", "1")
        p.setProperty("signed_u1_2026-01-02", "1")
        pruneDayKeys(p, "signed_", 40)
        assertEquals(2, p.stringPropertyNames().size)
    }

    /** 多账号混合时按各自日期清理，不会删掉别的账号的新记录。 */
    @Test
    fun n17PruneAcrossAccountsByDate() {
        val p = Properties()
        for (d in 1..5) p.setProperty("tried_u1_2026-01-" + d.toString().padStart(2, '0'), "1")
        for (d in 1..3) p.setProperty("tried_u2_2026-03-" + d.toString().padStart(2, '0'), "1")
        pruneDayKeys(p, "tried_", 6)
        val left = p.stringPropertyNames().filter { it.startsWith("tried_") }
        assertEquals(6, left.size)
        assertTrue(left.any { it.startsWith("tried_u2_") }, "新账号记录不应被误删")
    }

    /**
     * 并发读-改-写不丢更新：N 个线程同时首次触发 props() 并各写各的键，
     * 最后每个键都必须在文件里（修复前首次并发会创建多个 Properties
     * 实例互相覆盖，部分更新丢失）。
     */
    @Test
    fun n17ConcurrentWritesLoseNothing(@TempDir dir: File) {
        WelfareApi.configure(dir)
        val threads = 12
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) { i ->
            pool.submit {
                start.await()
                WelfareApi.markSigned("user" + i, "2026-01-01")
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发写入应在限时内完成")
        pool.shutdown()

        // 重新 configure 强制从文件重读（而不是信内存缓存）
        WelfareApi.configure(dir)
        val file = File(dir, "welfare.properties")
        assertTrue(file.isFile, "welfare.properties 应已落盘")
        val text = file.readText()
        repeat(threads) { i ->
            assertTrue(text.contains("signed_user" + i + "_2026-01-01"), "user" + i + " 的签到记录丢失")
        }
        WelfareApi.configure(File(dir, "unused-after-test"))
    }
}
