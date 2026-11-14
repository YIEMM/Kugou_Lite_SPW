package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 首次播放「没有封面、没有歌词」的判定（用户反馈）。
 *
 * ## 现场
 *
 * 用户反馈：播放 Overdose 时没有封面、没有歌词；**先放别的歌再回来放它就正常**。
 * 日志显示首次切歌时封面帧与 .lrc 都是刚写进去的：
 *
 * ```
 * 21:57:25.287 [cache] 已缓存 D7E890…-128.mp3（3080 KB）
 * 21:57:25.331 [id3]   已写入封面帧（80220 字节）
 * 21:57:25.339 [cache] 已写入逐字歌词（7782 字符）
 * 21:57:25.341 [play]  已更新队列项「Overdose」path=本地
 * 21:57:25.343 [play]  已重建音频源并播放「Overdose」
 * ```
 *
 * 也就是说：**写入磁盘没问题，但宿主是在写入完成「之前/同时」开始读的**。
 * 宿主的封面链路是 `PlaybackService.refreshCurrentCover`
 * → `TagParser.readImage(path)`，读不到就停在 ♪ 占位；等下一次切歌
 * 才重新读文件 —— 所以才出现「第二次播放就正常」。
 *
 * 参考实现 Salt-Extend-Online 的 `PlaybackCoverRefresh` 印证了这一点：
 * 写完 artwork 后必须在 **UI 线程**显式调 `refreshCurrentCover()`。
 *
 * ## 判定
 *
 * 只在「元数据本次被写入」时才需要刷新：
 * - 首次下载并写入封面/歌词 → 需要刷新
 * - 命中完整缓存、无需写入 → 不需要（避免每次切歌都重解码封面）
 */
class CoverRefreshPolicyTest {

    @Test
    fun `首次写入封面后需要刷新`() {
        assertTrue(CoverRefreshPolicy.shouldRefresh(coverWritten = true, lyricsWritten = false))
    }

    @Test
    fun `首次写入歌词后需要刷新`() {
        assertTrue(CoverRefreshPolicy.shouldRefresh(coverWritten = false, lyricsWritten = true))
    }

    @Test
    fun `两者都写入时需要刷新`() {
        assertTrue(CoverRefreshPolicy.shouldRefresh(coverWritten = true, lyricsWritten = true))
    }

    @Test
    fun `命中完整缓存不刷新`() {
        assertFalse(CoverRefreshPolicy.shouldRefresh(coverWritten = false, lyricsWritten = false))
    }

    @Test
    fun `同一首在冷却期内不重复刷新`() {
        val p = CoverRefreshPolicy.Cooldown(cooldownMs = 5_000)
        assertTrue(p.shouldRefresh("kugou-A", nowMs = 0), "首次写入应刷新")
        assertFalse(p.shouldRefresh("kugou-A", nowMs = 2_000), "冷却期内不重复刷新")
        assertTrue(p.shouldRefresh("kugou-B", nowMs = 2_500), "换曲目立刻放行")
        assertTrue(p.shouldRefresh("kugou-A", nowMs = 6_000), "冷却过后允许再次刷新")
    }
}
