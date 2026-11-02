package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [KrcLyrics] 的测试。
 *
 * 样本取自**真实酷狗 KRC 响应**（`fmt=krc`，7744 字节，322 个逐字标签）。
 */
class KrcLyricsTest {

    /** 真实的 KRC 片段（《黑縁》前两行）。 */
    private val sample = """
        [id:${'$'}00000000]
        [ti:黑縁]
        [ar:]
        [al:ROSE]
        [by:krc转qrc工具]
        [language:eyJjb250ZW50IjogW3sibHlyaWNDb250ZW50IjogW1siXHU0ZWU1XHU0ZTBiXHU2YjRjXV19XX0=]
        [191,3515]<0,836,0>黑<836,493,0>縁
        [3758,3220]<0,255,0>愛<255,587,0>舐
    """.trimIndent()

    @Test
    fun `识别 KRC 格式`() {
        assertTrue(KrcLyrics.isKrc(sample))
        // 普通 LRC 不算 KRC
        assertFalse(KrcLyrics.isKrc("[00:03.75]愛舐め上手芳しき"))
        assertFalse(KrcLyrics.isKrc(""))
    }

    @Test
    fun `剥离 language 翻译段`() {
        val cleaned = KrcLyrics.sanitize(sample)
        assertFalse(cleaned.contains("[language:"))
        // 其余内容保留
        assertTrue(cleaned.contains("[ti:黑縁]"))
        assertTrue(cleaned.contains("<0,836,0>黑"))
    }

    @Test
    fun `转成增强 LRC`() {
        val lrc = KrcLyrics.toEnhancedLrc(sample)
        // 元信息保留
        assertTrue(lrc.contains("[ti:黑縁]"))
        // 行时间 + 逐字绝对时间
        // 行起始 191ms，第一字偏移 0 → 00:00.19；第二字偏移 836 → 191+836=1027ms → 00:01.02
        assertTrue(lrc.contains("[00:00.19]"), "应含行时间标签，实际：\n$lrc")
        assertTrue(lrc.contains("<00:00.19>黑"), "应含首字绝对时间，实际：\n$lrc")
        assertTrue(lrc.contains("<00:01.02>縁"), "应含次字绝对时间，实际：\n$lrc")
        // 不应再有 KRC 私有标签
        assertFalse(lrc.contains("<0,836,0>"))
    }

    @Test
    fun `第二行的相对偏移基于行首`() {
        val lrc = KrcLyrics.toEnhancedLrc(sample)
        // 第二行行首 3758ms → 00:03.75
        assertTrue(lrc.contains("[00:03.75]"), "应含第二行时间，实际：\n$lrc")
        // 「愛」偏移 0 → 3758ms
        assertTrue(lrc.contains("<00:03.75>愛"), "实际：\n$lrc")
        // 「舐」偏移 255 → 4013ms → 00:04.01
        assertTrue(lrc.contains("<00:04.01>舐"), "实际：\n$lrc")
    }

    @Test
    fun `普通 LRC 原样保留`() {
        val plain = "[00:03.75]愛舐め上手芳しき"
        assertEquals(plain, KrcLyrics.sanitize(plain))
    }
}
