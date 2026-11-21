package cn.spw.kugou.lite.ui

import java.util.ArrayDeque

/**
 * 封面下载限流器（用户需求 2026-11-21）。
 *
 * 需求：视图范围内 1 秒最多 5~8 次（实现取 6）；范围外最多 2 次。
 *
 * 滑动窗口计数：每个档位维护最近 1 秒的取号时间戳，取号前淘汰窗口外的旧戳。
 * 两档独立计数——视野内配额用满不影响视野外的慢速通道。
 *
 * 实例化使用（可注入时钟便于测试）；全局单例见 [Companion.DEFAULT]。
 */
class CoverRateLimiter(
    private val visiblePerSecond: Int = 6,
    private val invisiblePerSecond: Int = 2,
    private val windowMs: Long = 1_000L,
    @Volatile private var now: () -> Long = { System.currentTimeMillis() },
) {

    private val visibleStamps = ArrayDeque<Long>()
    private val invisibleStamps = ArrayDeque<Long>()
    private val lock = Any()

    /** 尝试取号；false = 本档配额已满，稍后重试。 */
    fun tryAcquire(visible: Boolean): Boolean {
        synchronized(lock) {
            val t = now()
            val q = if (visible) visibleStamps else invisibleStamps
            val quota = if (visible) visiblePerSecond else invisiblePerSecond
            prune(q, t)
            if (q.size >= quota) return false
            q.addLast(t)
            return true
        }
    }

    /** 配额耗尽时距下次可重试的毫秒数；未满返回 0。 */
    fun retryDelayMs(visible: Boolean): Long {
        synchronized(lock) {
            val t = now()
            val q = if (visible) visibleStamps else invisibleStamps
            prune(q, t)
            val quota = if (visible) visiblePerSecond else invisiblePerSecond
            if (q.size < quota) return 0L
            return (q.first() + windowMs - t).coerceIn(1L, windowMs)
        }
    }

    /** 测试注入时钟并清空窗口。 */
    fun resetForTest(nowFn: () -> Long) {
        now = nowFn
        synchronized(lock) {
            visibleStamps.clear()
            invisibleStamps.clear()
        }
    }

    /** 必须持锁调用：淘汰滑出窗口的旧戳。 */
    private fun prune(q: ArrayDeque<Long>, t: Long) {
        while (q.isNotEmpty() && t - q.first() >= windowMs) q.removeFirst()
    }

    companion object {
        /** 生产用全局单例（CoverLoader/rememberCover 走这里）。 */
        val DEFAULT = CoverRateLimiter()
    }
}
