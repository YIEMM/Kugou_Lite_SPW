package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [KrcLyrics] 的测试。
 *
 * 样本取自**真实酷狗 KRC 响应**。
 */
class KrcLyricsTest {

    /** 真实的 KRC 片段（含元信息、音译、翻译、两行歌词）。 */
    private val sample = run {
        val json = """{"content":[{"lyricContent":[[""],[""]],"type":1,"language":0}]}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        """
        [id:${'$'}00000000]
        [ti:臙脂]
        [ar:bk]
        [al:]
        [by:]
        [offset:0]
        [kana:1えん1じ1か(1213,221)]
        [language:$b64]
        [494,2215]<0,247,0>臙<247,116,0>脂
        [23129,5561]<0,169,0>え<169,978,0>？
        """.trimIndent()
    }

    /** 只有罗马音（type=0）、没有中文译文（type=1）的样本。 */
    private val romajiOnly = run {
        val json = """{"content":[{"lyricContent":[["mi "],["mu "],["ka u "]],"type":0,"language":0}]}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        """
        [id:${'$'}00000000]
        [ti:みむかゥわナイストライ]
        [ar:ぬぬぬぬぬぬぬぬ]
        [by:]
        [offset:0]
        [language:$b64]
        [494,2215]<0,247,0>み<247,116,0>む
        """.trimIndent()
    }

    @Test
    fun `识别 KRC 格式`() {
        assertTrue(KrcLyrics.isKrc(sample))
        assertFalse(KrcLyrics.isKrc("[00:03.75]愛舐め上手芳しき"))
        assertFalse(KrcLyrics.isKrc(""))
    }

    /**
     * 用户反馈「みむかゥわナイストライ 为什么没有翻译」。
     *
     * 实测这首歌的 [language:] 段只有 `type=0`（罗马音），没有 `type=1`
     * （中文译文）。早期实现只取译文 → 完全没有翻译行。
     * 现在应退化为显示罗马音，至少给用户读音。
     */
    @Test
    fun `没有中文译文时退化为显示罗马音`() {
        val lrc = KrcLyrics.toEnhancedLrc(romajiOnly, withTranslation = true)
        // 译文/罗马音行必须与原文行**同时间戳**：宿主靠它把下一行识别为
        // 上一行的 pureSubText，渲染在下方。
        assertTrue(lrc.contains("[00:00.49]mi"), "应含罗马音行且与原文同时间戳，实际：\n$lrc")
        assertFalse(lrc.contains("[language:"), "不应残留 language 段")
    }

    @Test
    fun `显式关闭翻译时不输出罗马音`() {
        val lrc = KrcLyrics.toEnhancedLrc(romajiOnly, withTranslation = false)
        assertFalse(lrc.contains("]mi"), "关闭翻译后不应有罗马音行，实际：\n$lrc")
    }

    @Test
    fun `剥离 language 与 kana 段`() {
        val cleaned = KrcLyrics.sanitize(sample)
        assertFalse(cleaned.contains("[language:"))
        assertFalse(cleaned.contains("[kana:"))
        assertTrue(cleaned.contains("[ti:臙脂]"))
        assertTrue(cleaned.contains("<0,247,0>臙"))
    }

    @Test
    fun `逐字时间加上行首偏移`() {
        val lrc = KrcLyrics.toEnhancedLrc(sample, withTranslation = false)
        // 第一行行首 494ms，首字偏移 0 → 00:00.49
        assertTrue(lrc.contains("[00:00.49]"), "应含首行时间，实际：\n$lrc")
        assertTrue(lrc.contains("<00:00.49>臙"), "实际：\n$lrc")
        // 第二字偏移 247 → 494+247=741ms → 00:00.74
        assertTrue(lrc.contains("<00:00.74>脂"), "实际：\n$lrc")

        // 第二行行首 23129ms → 00:23.12（厘秒截断：129ms/10=12）
        assertTrue(lrc.contains("[00:23.12]"), "应含第二行时间，实际：\n$lrc")
        assertTrue(lrc.contains("<00:23.12>え"), "实际：\n$lrc")
        // 第二字偏移 169 → 23129+169=23298ms → 00:23.29
        assertTrue(lrc.contains("<00:23.29>？"), "实际：\n$lrc")
    }

    @Test
    fun `不再残留 KRC 私有标签`() {
        val lrc = KrcLyrics.toEnhancedLrc(sample, withTranslation = false)
        assertFalse(lrc.contains("<0,247,0>"))
        assertFalse(lrc.contains("[23129,5561]"))
    }

    @Test
    fun `普通 LRC 原样保留`() {
        val plain = "[00:03.75]愛舐め上手芳しき"
        assertEquals(plain, KrcLyrics.sanitize(plain))
    }

    @Test
    fun `解析翻译数组`() {
        // 真实的 language JSON：type=1 才是译文
        val json = """{"content":[{"type":1,"language":0,"lyricContent":[[""],["测试翻译"]]}]}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val krc = """
            [ti:x]
            [language:$b64]
            [100,1000]<0,500,0>A
            [2000,1000]<0,500,0>B
        """.trimIndent()

        val tr = KrcLyrics.extractTranslation(krc)
        assertEquals(2, tr.size, "应解析出 2 行翻译")
        assertEquals("", tr[0])
        assertEquals("测试翻译", tr[1])
    }

    @Test
    fun `按 type 区分罗马音与译文`() {
        // 真实结构：content[0] 是罗马音(type=0)，content[1] 是译文(type=1)
        val json = """
            {"content":[
              {"type":0,"language":0,"lyricContent":[["ha tsu "],["shi "]]},
              {"type":1,"language":0,"lyricContent":[["不小心说出"],["想留在你身边"]]}
            ]}
        """.trimIndent()
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val krc = """
            [ti:x]
            [language:$b64]
            [100,1000]<0,500,0>A
            [2000,1000]<0,500,0>B
        """.trimIndent()

        val tr = KrcLyrics.extractTranslation(krc)
        assertEquals(2, tr.size)
        assertEquals("不小心说出", tr[0], "译文必须是 type=1 的内容，不是罗马音")
        assertEquals("想留在你身边", tr[1])

        val ro = KrcLyrics.extractRomanization(krc)
        assertEquals(2, ro.size)
        assertEquals("ha tsu", ro[0], "罗马音是 type=0，需拼接逐字数组")
        assertEquals("shi", ro[1])
    }

    @Test
    fun `译文行紧随原文且共用行时间`() {
        val json = """{"content":[{"type":1,"language":0,"lyricContent":[["中文意思"]]}]}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val krc = """
            [ti:x]
            [language:$b64]
            [1000,2000]<0,500,0>原<500,500,0>文
        """.trimIndent()

        val lrc = KrcLyrics.toEnhancedLrc(krc, withTranslation = true)
        val lines = lrc.lines().filter { it.startsWith("[") && !it.startsWith("[ti:") }
        // 应有两行：原文行 + 翻译行
        assertTrue(lines.size >= 2, "实际：\n$lrc")
        // 必须与原文行同时间戳：宿主靠它把译文并入 pureSubText
        assertTrue(lrc.contains("[00:01.00]中文意思"), "翻译行应复用原文行时间，实际：\n$lrc")
    }

    @Test
    fun `无翻译时不影响原文`() {
        val krc = """
            [ti:x]
            [1000,2000]<0,500,0>A
        """.trimIndent()
        val lrc = KrcLyrics.toEnhancedLrc(krc, withTranslation = true)
        assertTrue(lrc.contains("[00:01.00]<00:01.00>A"))
    }
}
