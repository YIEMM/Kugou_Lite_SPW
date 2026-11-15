package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 投喂宿主的歌词必须保留逐字标记（用户反馈「我的逐字歌词呢？」）。
 *
 * ## 回归背景
 *
 * 宿主的歌词模型是 `LyricsLine` + `LyricsCell`（逐字单元），
 * `PlaybackExtensionPoint.updateLyrics` 的返回值直接喂给它。
 * 喂**增强 LRC**（`[00:02.33]<00:02.33>鬼<00:02.70>ノ...`）才有逐字高亮；
 * 喂纯文本 LRC 只有整行高亮。
 *
 * 早期 `LyricsRegistry.put` 误用了 `toPlainLrc`（那是给 ID3 USLT
 * 纯文本用的），把 `<...>` 全剥掉，于是逐字歌词退化成整行 ——
 * 磁盘上的 .lrc 明明是对的，界面却没有逐字效果。
 */
class LyricsWordByWordTest {

    private fun krc(): String = listOf(
        "[ti:Test]",
        "[offset:0]",
        "[0,1000]<0,500,0>Hello<500,500,0>World",
    ).joinToString("\n")

    @Test
    fun `注册表保留逐字标记`() {
        LyricsRegistry.put("kugou-WBW", krc())
        val text = LyricsRegistry.get("kugou-WBW").orEmpty()
        assertTrue(
            text.contains("<00:00.00>Hello"),
            "必须保留逐字标记，否则只有整行高亮。实际：\n" + text,
        )
        assertTrue(text.contains("<00:00.50>World"), "第二个逐字单元也要在")
        assertTrue(text.contains("[00:00.00]"), "行时间戳要保留")
    }

    @Test
    fun `纯文本 LRC 原样保留`() {
        val plain = "[00:01.00]hello\n[00:02.00]world"
        LyricsRegistry.put("kugou-PLAIN", plain)
        assertTrue(LyricsRegistry.get("kugou-PLAIN").orEmpty().contains("[00:01.00]hello"))
    }
}
