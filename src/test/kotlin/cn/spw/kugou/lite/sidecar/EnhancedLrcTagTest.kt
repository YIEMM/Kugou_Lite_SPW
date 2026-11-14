package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 增强 LRC 的头部标签（用户反馈「人是猫」没有歌词）。
 *
 * ## 现场
 *
 * 该曲的 .lrc 已正常写入磁盘，内容也是标准增强 LRC：
 *
 *     [by:KugouLite/v3]
 *     [id:$00000000]
 *     [ti:人是猫]
 *     [00:00.00]<00:00.00>人<00:00.03>是...
 *
 * 但宿主显示「无歌词」。把缓存里 35 个 .lrc 全查一遍后发现：
 * **缺 [offset:] 的 4 个，恰好就是带 [id:] 的那 4 个**，
 * 其余 31 个都有 [offset:0] 且显示正常。
 *
 * 上游 KRC 头部随来源不同（有的带 [id:]/[total:]，多数带 [offset:]），
 * 我们只原样保留已有标签，没有补 [offset:]。
 *
 * ## 判定
 *
 * - 输出必须**始终**带 [offset:]（缺省补 0）
 * - [id:] 是酷狗内部字段，对宿主无意义且值形如 $00000000，应丢弃
 */
class EnhancedLrcTagTest {

    private fun krcWithoutOffset(): String = listOf(
        "[id:\$00000000]",
        "[ti:人是猫]",
        "[0,295]<0,31,0>人<31,33,0>是<64,16,0>猫",
    ).joinToString("\n")

    private fun krcWithOffset(): String = listOf(
        "[ti:Sample]",
        "[offset:0]",
        "[0,295]<0,31,0>人<31,33,0>是",
    ).joinToString("\n")

    @Test
    fun `缺少 offset 的歌词要补上 offset`() {
        val lrc = KrcLyrics.toEnhancedLrc(krcWithoutOffset())
        assertTrue(lrc.contains("[offset:"), "必须补 [offset:]，否则宿主判为无歌词；实际输出：\n" + lrc)
    }

    @Test
    fun `已有 offset 时保持原值`() {
        val lrc = KrcLyrics.toEnhancedLrc(krcWithOffset())
        assertTrue(lrc.contains("[offset:0]"), "已有值不应被改写")
        assertFalse(lrc.contains("[offset:0]\n[offset:0]"), "不能重复添加")
    }

    @Test
    fun `丢弃酷狗内部的 id 标签`() {
        val lrc = KrcLyrics.toEnhancedLrc(krcWithoutOffset())
        assertFalse(lrc.contains("[id:"), "id 是酷狗内部字段（值形如 $00000000），对宿主无意义")
    }

    @Test
    fun `正常歌词头信息完整保留`() {
        val lrc = KrcLyrics.toEnhancedLrc(krcWithoutOffset())
        assertTrue(lrc.contains("[ti:人是猫]"), "标题要保留")
        assertTrue(lrc.contains("[00:00.00]<00:00.00>人"), "歌词行要保留")
    }
}
