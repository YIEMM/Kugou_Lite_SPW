package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 补流失败重试策略（BUG-05）。
 *
 * ## 背景
 *
 * 切歌补流失败后，失败曲目仍是宿主的「当前曲目」：
 * `pollOnce` 的 `currentId == previous` 早退让它**永远不会被重试**，
 * 用户听到的是持续静音（比审计原描述「30 秒内不重试」更严重）。
 *
 * [StreamRetryPolicy] 把重试判定抽成纯状态机：
 * 延迟触发（默认 5 秒）+ 次数上限（默认 3 次）+ 曲目切换时作废。
 */
class StreamRetryPolicyTest {

    @Test
    fun `失败后冷却期内不重试`() {
        val p = StreamRetryPolicy(delayMs = 5_000, maxRetries = 3)
        p.onFailure("A", nowMs = 1_000)

        assertFalse(p.dueRetry("A", nowMs = 3_000), "冷却未到不应重试")
        assertFalse(p.dueRetry("B", nowMs = 9_000), "只对失败的那首重试")
        assertTrue(p.dueRetry("A", nowMs = 6_000), "冷却过后应允许重试")
    }

    @Test
    fun `每次失败后冷却重新计时`() {
        val p = StreamRetryPolicy(delayMs = 5_000, maxRetries = 3)
        p.onFailure("A", nowMs = 0)
        p.markAttempted()
        p.onFailure("A", nowMs = 2_000) // 第一次重试又失败

        assertFalse(p.dueRetry("A", nowMs = 5_000), "应从最近一次失败起重新冷却")
        assertTrue(p.dueRetry("A", nowMs = 7_000))
    }

    @Test
    fun `次数用尽后不再重试`() {
        val p = StreamRetryPolicy(delayMs = 1_000, maxRetries = 3)
        p.onFailure("A", nowMs = 0)
        repeat(3) {
            val at = 10_000L + it * 1_000L // 每次失败后冷却 1s，重试时刻依次顺延
            assertTrue(p.dueRetry("A", nowMs = at))
            p.markAttempted()
            p.onFailure("A", nowMs = at)
        }

        assertFalse(p.dueRetry("A", nowMs = 99_000), "重试 3 次后必须放弃，不能无限静音循环")
        assertTrue(p.exhausted("A", nowMs = 99_000), "用尽后应报告放弃，供日志输出与状态清理")
        p.onResolved()
        assertFalse(p.exhausted("A", nowMs = 99_000), "清理后不再报告")
    }

    @Test
    fun `成功后清除重试状态`() {
        val p = StreamRetryPolicy(delayMs = 1_000, maxRetries = 3)
        p.onFailure("A", nowMs = 0)
        p.onResolved()

        assertFalse(p.dueRetry("A", nowMs = 9_000), "成功后不应再重试")
        assertFalse(p.exhausted("A", nowMs = 9_000))
    }

    @Test
    fun `换曲目时旧的重试作废`() {
        val p = StreamRetryPolicy(delayMs = 1_000, maxRetries = 3)
        p.onFailure("A", nowMs = 0)
        p.onFailure("B", nowMs = 1_000) // 用户切到另一首，它也失败

        assertFalse(p.dueRetry("A", nowMs = 9_000), "只重试当前失败曲目")
        assertTrue(p.dueRetry("B", nowMs = 9_000))
        assertEquals(0, p.attemptCount("B"), "换曲目后次数从零计")
    }
}
