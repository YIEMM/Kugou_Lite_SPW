package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 译文行必须与原文行**共用同一时间戳**（用户纠正：「时间戳必须一样的啊」）。
 *
 * ## 宿主的歌词模型
 *
 *     LyricsLine(startTime, endTime, lyricsCells, pureMainText, pureSubText)
 *
 * 宿主按 LRC 文本建行：遇到与上一行**时间戳相同**的一行，就把它当作上一行的
 * 译文填进 `pureSubText`（渲染在主文本下方，受宿主设置项
 * `show_player_lyrics_sub_text` 控制）。主文本行里的 `<mm:ss.xx>` 单元则成为
 * 逐字高亮的依据。
 *
 * ## 曾经的错误修复
 *
 * 看到「同一时间戳出现两行」就误判为解析冲突，把译文改成 +10ms。
 * 结果译文变成**独立歌词行**：主文本与译文交替显示、逐字也失效
 * （用户反馈「我的逐字歌词呢？」）。时间戳必须一致。
 */
class TranslationPairingTest {

    private fun languageSegment(): String {
        val json = """{"content":[{"type":1,"lyricContent":["T-one","T-two"]}]}""".trimIndent()
        val b64 = java.util.Base64.getEncoder()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        return "[language:$b64]"
    }

    private fun krc(): String = listOf(
        "[ti:Test]",
        "[offset:0]",
        "[0,1000]<0,500,0>Line<500,500,0>One",
        "[2000,1000]<0,500,0>Line<500,500,0>Two",
        languageSegment(),
    ).joinToString("\n")

    @Test
    fun `译文行与原文行时间戳相同`() {
        val lrc = KrcLyrics.toEnhancedLrc(krc())
        val lines = lrc.lines()
        val mainIdx = lines.indexOfFirst { it.startsWith("[00:00.00]<") }
        assertTrue(mainIdx >= 0, "应存在带逐字单元的原文行：\n$lrc")
        // 紧随其后的译文行必须同时间戳
        val next = lines.getOrNull(mainIdx + 1).orEmpty()
        assertTrue(
            next.startsWith("[00:00.00]"),
            "译文行必须复用原文行时间戳 00:00.00，实际：$next\n$lrc",
        )
        assertTrue(next.contains("T-one"), "紧随行应是译文，实际：$next")
    }

    @Test
    fun `原文行保留逐字标记`() {
        val lrc = KrcLyrics.toEnhancedLrc(krc())
        assertTrue(
            lrc.contains("[00:00.00]<00:00.00>Line"),
            "原文行必须带逐字单元：\n$lrc",
        )
    }

    @Test
    fun `译文内容不丢失`() {
        val lrc = KrcLyrics.toEnhancedLrc(krc())
        assertTrue(lrc.contains("T-one"), "译文不能丢：\n$lrc")
        assertTrue(lrc.contains("T-two"))
    }

    @Test
    fun `每对主副文本时间戳一致`() {
        val lrc = KrcLyrics.toEnhancedLrc(krc())
        val stamp = Regex("^\\[([0-9]{2}:[0-9]{2}\\.[0-9]{2})\\]")
        val seq = lrc.lines().mapNotNull { stamp.find(it)?.groupValues?.get(1) }
        // 主/副成对出现：序列里相邻重复即为配对，不允许出现「孤立的偏移时间戳」
        assertEquals(4, seq.size, "应为主副两对共 4 行：$seq\n$lrc")
        assertEquals(seq[0], seq[1], "第一对时间戳必须一致：$seq")
        assertEquals(seq[2], seq[3], "第二对时间戳必须一致：$seq")
    }
}
