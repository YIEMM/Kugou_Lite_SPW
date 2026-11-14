package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 切歌后「启动播放」的判定。
 *
 * ## 第一次修复（60c2174）留下的回归
 *
 * 用户反馈：加载有缓存的歌曲时会加载 2 次。
 *
 * 原因：缓存命中时走了 PlayOnly，里面调了 prepare() + play()。
 * 但宿主自己切到该曲目时已经加载过（path 一直有效），再 prepare()
 * 一次就等于第二次加载 —— 表现为重复加载 / 进度重置。
 *
 * 参考实现 Salt-Extend-Online 的 SaltPlaybackStartBindings 只在
 * onLoaded 回调里补 play()，从不调 prepare()，印证了这一点。
 */
class TrackStartPolicyTest {

    @Test
    fun `path 从空到有效时重建音频源并播放`() {
        assertEquals(TrackStartPolicy.Action.RebindAndPlay, TrackStartPolicy.decide(hadValidPath = false))
    }

    @Test
    fun `path 本来就有效时只补 play 不重载`() {
        assertEquals(TrackStartPolicy.Action.PlayOnly, TrackStartPolicy.decide(hadValidPath = true))
    }

    @Test
    fun `没有可播放地址时不启动`() {
        assertEquals(TrackStartPolicy.Action.None, TrackStartPolicy.decide(hadValidPath = false, hasPlayablePath = false))
        assertEquals(TrackStartPolicy.Action.None, TrackStartPolicy.decide(hadValidPath = true, hasPlayablePath = false))
    }

    @Test
    fun `PlayOnly 不得重新 prepare 音频源`() {
        val plan = TrackStartPolicy.plan(TrackStartPolicy.Action.PlayOnly)
        assertFalse(plan.rebuildSource, "不应重建音频源")
        assertFalse(plan.prepare, "不应再 prepare，那会造成第二次加载")
        assertTrue(plan.play, "只需补一次 play")
    }

    @Test
    fun `RebindAndPlay 才需要 prepare`() {
        val plan = TrackStartPolicy.plan(TrackStartPolicy.Action.RebindAndPlay)
        assertTrue(plan.rebuildSource)
        assertTrue(plan.prepare)
        assertTrue(plan.play)
    }

    @Test
    fun `None 什么都不做`() {
        val plan = TrackStartPolicy.plan(TrackStartPolicy.Action.None)
        assertFalse(plan.rebuildSource)
        assertFalse(plan.prepare)
        assertFalse(plan.play)
    }

    @Test
    fun `同一首在冷却期内不重复启动`() {
        val c = TrackStartPolicy.Cooldown(cooldownMs = 3_000)
        assertTrue(c.shouldStart("kugou-A", nowMs = 0), "首次切到该曲应启动")
        assertFalse(c.shouldStart("kugou-A", nowMs = 1_000), "冷却期内不重复启动")
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
