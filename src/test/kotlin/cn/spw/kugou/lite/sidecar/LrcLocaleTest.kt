package cn.spw.kugou.lite.sidecar

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LRC 时间戳必须是 ASCII 数字，不能受默认 Locale 影响。
 *
 * ## 缺陷
 *
 * `KrcLyrics.formatTimeInner` 用 `"%02d:%02d.%02d".format(...)`，
 * Kotlin 的 `String.format` 取 `Locale.getDefault()`。在 ar / fa / bn 这类
 * 「本地数字形态」的 Locale 下，`%d` 输出的是 U+0660..U+0669 等本地数字，
 * 写出的 `.lrc` 时间戳不再匹配 `[mm:ss.xx]` —— 宿主解析失败，整首无歌词。
 *
 * 这与「宿主读不到歌词」是两类不同问题：这里文件内容本身就是坏的，
 * 换成任何读取时机都没用。
 */
class LrcLocaleTest {

    private var saved: Locale = Locale.getDefault()

    @BeforeTest
    fun saveLocale() { saved = Locale.getDefault() }

    @AfterTest
    fun restoreLocale() { Locale.setDefault(saved) }

    private fun krc(): String = "[1000,2000]<0,500,0>la<500,500,0>la"

    @Test
    fun `阿拉伯语环境下时间戳仍是 ASCII`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val lrc = KrcLyrics.toEnhancedLrc(krc())
        val line = lrc.lineSequence().last { it.isNotBlank() }
        assertTrue(
            Regex("""^\[\d{2}:\d{2}\.\d{2}]""").containsMatchIn(line),
            "时间戳必须是 ASCII [mm:ss.xx]，实际：$line",
        )
        val hasLocalized = line.any { it in Char(0x0660)..Char(0x0669) }
        assertTrue(!hasLocalized, "不应出现本地数字：$line")
    }

    @Test
    fun `波斯语环境下逐字标记也是 ASCII`() {
        Locale.setDefault(Locale.forLanguageTag("fa-IR"))
        val lrc = KrcLyrics.toEnhancedLrc(krc())
        val line = lrc.lineSequence().last { it.isNotBlank() }
        assertTrue(line.contains("<00:01.00>"), "逐字标记必须是 ASCII，实际：$line")
    }
}
