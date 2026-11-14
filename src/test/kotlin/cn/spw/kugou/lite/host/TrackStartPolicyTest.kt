package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 切歌后「启动播放」的判定（用户反馈：点下一首无法正常播放）。
 *
 * ## 缺陷现场（2026-11-14 21:09 真机日志）
 *
 * 队列建好时 `队列补齐 2/5` 已经给下一首写好了本地 path；用户点「下一首」后
 * `TrackWatcher` 只做了 `updateMediaItems`（日志 `已更新队列项 path=本地`），
 * 播放栏仍停在 ♪ 占位、▶ 未播放、进度 0 秒 —— 宿主换了当前索引，但**没有启动音频**。
 *
 * ## 与参考实现的一致性
 *
 * Salt-Extend-Online 的 `SaltPlaybackStartBindings` 在播放器加载完成回调里
 * **再 `play()` 一次**，并用 `PlaybackStartGate` 保证这次启动意图不被后续
 * 用户操作覆盖。说明「宿主不会因为我们更新了队列项就自动开播」是共性问题。
 *
 * ## 为什么区分两种启动方式
 *
 * 早期实现踩过「抖动」：在 `setPlaybackQueue` 之后又
 * `setPlaybackAudioSource + prepare + play`，同一首被加载两次，
 * 播放 0.3 秒后跳回 0 秒。
 *
 * 所以只在**宿主没有机会加载**时才重建音频源：
 * - path 之前是空占位 → 宿主必然加载失败 → 需要 `setPlaybackAudioSource + prepare + play`
 * - path 本来就是有效本地文件 → 宿主已经加载过，只是没开播 → 只补 `play()`，不重载
 */
class TrackStartPolicyTest {

    @Test
    fun `path 从空到有效时重建音频源并播放`() {
        val d = TrackStartPolicy.decide(hadValidPath = false)
        assertEquals(TrackStartPolicy.Action.RebindAndPlay, d)
    }

    @Test
    fun `path 本来就有效时只补 play 不重载`() {
        val d = TrackStartPolicy.decide(hadValidPath = true)
        assertEquals(TrackStartPolicy.Action.PlayOnly, d)
    }

    @Test
    fun `没有可播放地址时不启动`() {
        assertEquals(TrackStartPolicy.Action.None, TrackStartPolicy.decide(hadValidPath = false, hasPlayablePath = false))
        assertEquals(TrackStartPolicy.Action.None, TrackStartPolicy.decide(hadValidPath = true, hasPlayablePath = false))
    }

    @Test
    fun `同一首在冷却期内不重复启动`() {
        val c = TrackStartPolicy.Cooldown(cooldownMs = 3_000)
        assertTrue(c.shouldStart("kugou-A", nowMs = 0), "首次切到该曲应启动")
        assertFalse(c.shouldStart("kugou-A", nowMs = 1_000), "冷却期内不重复启动，避免抖动")
        assertTrue(c.shouldStart("kugou-B", nowMs = 1_200), "换曲目立刻放行")
        assertTrue(c.shouldStart("kugou-A", nowMs = 4_000), "冷却过后允许再次启动")
    }

    @Test
    fun `冷却重置后同一首可立即启动`() {
        val c = TrackStartPolicy.Cooldown(cooldownMs = 3_000)
        assertTrue(c.shouldStart("kugou-A", nowMs = 0))
        assertFalse(c.shouldStart("kugou-A", nowMs = 100))
        c.reset()
        assertTrue(c.shouldStart("kugou-A", nowMs = 200))
    }
}
