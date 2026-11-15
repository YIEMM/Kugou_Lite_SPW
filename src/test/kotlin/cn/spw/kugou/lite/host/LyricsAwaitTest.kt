package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 扩展点查不到歌词时要**短暂等待**，而不是立刻返回 null。
 *
 * 真机日志（2026-11-15）：
 *
 *     12:34:23 宿主查询 id=kugou-4EA48315 命中=false 长度=0
 *     12:35:07 宿主查询 id=kugou-4EA48315 命中=true  长度=2583
 *
 * 宿主在切歌瞬间就问歌词，歌词接口还在路上；宿主只问一次，问空就永远无歌词。
 */
class LyricsAwaitTest {

    @Test
    fun `已就绪时立即返回不等待`() {
        LyricsRegistry.put("kugou-READY", "[00:01.00]ready")
        val t0 = System.currentTimeMillis()
        val got = LyricsRegistry.awaitLyrics("kugou-READY", 1_500)
        val cost = System.currentTimeMillis() - t0
        assertEquals("[00:01.00]ready", got)
        assertTrue(cost < 100, "已就绪应立即返回，实际耗时 ${cost}ms")
    }

    @Test
    fun `等待期间登记会被唤醒`() {
        val id = "kugou-LATE"
        Thread {
            Thread.sleep(150)
            LyricsRegistry.put(id, "[00:02.00]late")
        }.apply { isDaemon = true }.start()

        val t0 = System.currentTimeMillis()
        val got = LyricsRegistry.awaitLyrics(id, 1_500)
        val cost = System.currentTimeMillis() - t0
        assertEquals("[00:02.00]late", got)
        assertTrue(cost < 1_200, "应在登记后立即唤醒，实际 ${cost}ms")
    }

    @Test
    fun `明确无歌词时立即放弃不空等`() {
        val id = "kugou-NONE"
        Thread {
            Thread.sleep(100)
            LyricsRegistry.markNoLyrics(id)
        }.apply { isDaemon = true }.start()

        val t0 = System.currentTimeMillis()
        val got = LyricsRegistry.awaitLyrics(id, 1_500)
        val cost = System.currentTimeMillis() - t0
        assertNull(got)
        assertTrue(cost < 1_200, "判定无歌词后应立即返回，实际 ${cost}ms")
    }
}
