package cn.spw.kugou.lite.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CoverRateLimiter 的限流判据（纯逻辑，时钟可注入）。
 *
 * 用户需求：视图范围内 1 秒最多 5~8 次（实现取 6），视图范围外最多 2 次。
 */
class CoverRateLimiterTest {

    /** 视图内：1 秒内第 1~6 次放行，第 7 次拒绝。 */
    @Test
    fun visibleAllowsSixPerSecond() {
        val limiter = CoverRateLimiter().apply { resetForTest { 1_000L } }
        for (i in 1..6) assertTrue(limiter.tryAcquire(visible = true), "第 $i 次应放行")
        assertFalse(limiter.tryAcquire(visible = true), "第 7 次应拒绝")
    }

    /** 视野外：1 秒内第 1~2 次放行，第 3 次拒绝。 */
    @Test
    fun invisibleAllowsTwoPerSecond() {
        val limiter = CoverRateLimiter().apply { resetForTest { 2_000L } }
        assertTrue(limiter.tryAcquire(visible = false))
        assertTrue(limiter.tryAcquire(visible = false))
        assertFalse(limiter.tryAcquire(visible = false), "第 3 次应拒绝")
    }

    /** 滑动窗口：时间推进后配额恢复。 */
    @Test
    fun windowSlidesWithTime() {
        var t = 0L
        val limiter = CoverRateLimiter().apply { resetForTest { t } }
        repeat(6) { assertTrue(limiter.tryAcquire(visible = true)) }
        assertFalse(limiter.tryAcquire(visible = true))
        t = 1_100L // 全部旧戳移出 1 秒窗口
        assertTrue(limiter.tryAcquire(visible = true), "窗口滑过后应放行")
    }

    /** 混合：视野内请求用满配额后，视野外仍按自己的独立窗口判定。 */
    @Test
    fun tiersAreIndependent() {
        val limiter = CoverRateLimiter().apply { resetForTest { 5_000L } }
        repeat(6) { limiter.tryAcquire(visible = true) }
        assertFalse(limiter.tryAcquire(visible = true))
        // 视野外窗口独立，2 次仍可放行
        assertTrue(limiter.tryAcquire(visible = false))
        assertTrue(limiter.tryAcquire(visible = false))
        assertFalse(limiter.tryAcquire(visible = false))
    }

    /** 不可见请求在配额耗尽时给出排队延迟（毫秒）。 */
    @Test
    fun retryDelayComputed() {
        val limiter = CoverRateLimiter().apply { resetForTest { 3_000L } }
        assertEquals(0L, limiter.retryDelayMs(visible = false))
        repeat(2) { limiter.tryAcquire(visible = false) }
        val d = limiter.retryDelayMs(visible = false)
        assertTrue(d in 1..1000, "配额耗尽后应有 1~1000ms 延迟，实际 $d")
    }

    /** 并发安全：多线程同时取号不超过上限。 */
    @Test
    fun concurrentAcquireRespectsLimit() {
        val limiter = CoverRateLimiter().apply { resetForTest { 7_000L } }
        val ok = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        repeat(20) {
            pool.submit {
                if (limiter.tryAcquire(visible = true)) ok.incrementAndGet()
            }
        }
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(6, ok.get(), "并发下放行数不得超过 6")
    }
}
