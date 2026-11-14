package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 歌词头部不能带 [total:0]（用户反馈「第一次播放仍然没有歌词」）。
 *
 * ## 现场
 *
 * 播放「ダーリン (亲爱的)」时界面显示「无歌词」，但磁盘上的 .lrc
 * 内容完整（185 行、12909 字节）：
 *
 *     [by:KugouLite/v4]
 *     [ar:MARETU (極悪P)/初音未来 (初音ミク)]
 *     [ti:ダーリン (亲爱的)]
 *     [al:しう (四有)]
 *     [total:0]        <-- 关键
 *     [offset:0]
 *     [00:00.00]<00:00.00>MARETU、<00:00.20>初...
 *
 * 把缓存里所有 .lrc 查一遍：**带 [total:] 的 3 个文件全部显示无歌词**，
 * 且值都是 0。宿主的歌词解析把 [total:] 当作歌词总时长，
 * 声明为 0 时判为无效歌词。
 *
 * [total:] 来自上游 KRC 头部（有的歌带 [total:N]，多数不带），
 * 我们的转换原样保留 —— 对宿主来说是害处大于好处。
 *
 * ## 判定
 *
 * 输出里不得出现 [total:]；其余头部标签照旧保留。
 */
class NoZeroTotalTagTest {

    private fun krcWithZeroTotal(): String = listOf(
        "[ti:ダーリン]",
        "[total:0]",
        "[offset:0]",
        "[0,295]<0,31,0>M<31,33,0>A",
    ).joinToString("\n")

    @Test
    fun `输出不得包含 total 标签`() {
        val lrc = KrcLyrics.toEnhancedLrc(krcWithZeroTotal())
        assertFalse(lrc.contains("[total:"), "total 会被宿主当作歌词时长，0 判无效；实际输出：\n" + lrc)
    }

    @Test
    fun `其余头部标签仍然保留`() {
        val lrc = KrcLyrics.toEnhancedLrc(krcWithZeroTotal())
        assertTrue(lrc.contains("[ti:ダーリン]"), "标题要保留")
        assertTrue(lrc.contains("[offset:0]"), "offset 要保留")
        assertTrue(lrc.contains("[00:00.00]"), "歌词行要保留")
    }
}
