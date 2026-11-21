package cn.spw.kugou.lite.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * 封面下载限流器（用户需求 2026-11-21）。
 *
 * ## 需求原文
 *
 * - 歌曲在视图范围内：1 秒内最多 5~8 次（实现取 6）；
 * - 歌曲不在视图范围内：1 秒内最多 2 次。
 *
 * ## 实现：滑动窗口计数
 *
 * 每个档位维护一个最近 1 秒内的成功时间戳队列（毫秒）。取号时先淘汰
 * 窗口外的旧戳，再按队列长度决定放行/拒绝。相比固定窗口，滑动窗口
 * 不会在窗口边界出现 2 倍突发；相比令牌桶，实现直观、无需后台线程补充。
 *
 * 两个档位**独立计数**：视野内配额用满不影响视野外的慢速通道，
 * 保证滚动列表时屏外的封面仍能以 2/s 逐步补齐，而不是全部饿死。
 *
 * 并发：取号在 `synchronized` 内完成（亚毫秒级，无 IO），调用方是
 * Compose 的 LaunchedEffect 与后台线程，竞争极低，无需无锁结构。
 *
 * 时钟可注入（测试）；生产用 [System.currentTimeMillis]。
 */
object CoverRateLimiter {

    /** 视图内档位：每秒放行数（用户给的范围 5~8，取 6）。 */
    const val VISIBLE_PER_SECOND: Int = 6

    /** 视野外档位：每秒放行数（用户指定 2）。 */
    const val INVISIBLE_PER_SECOND: Int = 2

    /** 滑动窗口长度（毫秒）。 */
    private const val WINDOW_MS = 1_000L

    /** 时钟。测试用 [resetForTest] 注入假时钟；生产保持默认。 */
    @Volatile
    internal var now: () -> Long = { System.currentTimeMillis() }
        private set

    /** 可见档位的窗口时间戳（毫秒）。 */
    private val visibleStamps = ArrayDeque<Long>()

    /** 不可见档位的窗口时间戳（毫秒）。 */
    private val invisibleStamps = ArrayDeque<Long>()

    private val lock = Any()

    /** 测试注入时钟并清空窗口；生产代码不要调用。 */
    internal fun resetForTest(nowFn: () -> Long) {
        now = nowFn
        synchronized(lock) {
            visibleStamps.clear()
            invisibleStamps.clear()
        }
    }

    /**
     * 尝试为一次封面下载取号。
     *
     * @param visible 该封面是否在用户当前视图范围内（行可见）
     * @return true = 放行；false = 本档位配额已满，调用方应稍后重试
     */
    fun tryAcquire(visible: Boolean): Boolean {
        synchronized(lock) {
            val t = now()
            val q = if (visible) visibleStamps else invisibleStamps
            val quota = if (visible) VISIBLE_PER_SECOND else INVISIBLE_PER_SECOND
            prune(q, t)
            if (q.size >= quota) return false
            q.addLast(t)
            return true
        }
    }

    /**
     * 配额耗尽时，距下次可重试还有多少毫秒（供调用方 delay 后重试，而不是自旋）。
     * 配额未满返回 0。
     */
    fun retryDelayMs(visible: Boolean): Long {
        synchronized(lock) {
            val t = now()
            val q = if (visible) visibleStamps else invisibleStamps
            prune(q, t)
            val quota = if (visible) VISIBLE_PER_SECOND else INVISIBLE_PER_SECOND
            if (q.size < quota) return 0L
            val oldest = q.first()
            return (oldest + WINDOW_MS - t).coerceIn(1L, WINDOW_MS)
        }
    }

    /** 淘汰滑出 1 秒窗口的旧时间戳。必须持锁调用。 */
    private fun prune(q: ArrayDeque<Long>, t: Long) {
        while (q.isNotEmpty() && t - q.first() >= WINDOW_MS) q.removeFirst()
    }
}
