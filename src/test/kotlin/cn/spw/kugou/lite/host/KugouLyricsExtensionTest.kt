package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 歌词直接投喂宿主的扩展点（用户提示「有参数可以直接传歌词」）。
 *
 * 反汇编宿主 PlaybackService$loadLyrics$2：遍历 PlaybackExtensionPoint 调
 * updateLyrics / onBeforeLoadLyrics，返回非 null 就直接用，跳过
 * TagParser.readLyrics。而 TagParser 只在 onLoaded 时读一次，我们的歌词
 * 在切歌之后才下载 —— 这是「有几率无歌词、重读缓存就好」的根因。
 */
class KugouLyricsExtensionTest {

    @Test
    fun `KRC 登记后保留逐字标记`() {
        val krc = listOf(
            "[ti:Test]",
            "[offset:0]",
            "[0,1000]<0,500,0>Hello<500,500,0>World",
        ).joinToString("\n")
        LyricsRegistry.put("kugou-TEST", krc)
        val text = LyricsRegistry.get("kugou-TEST").orEmpty()
        // 宿主靠 <mm:ss.xx> 逐字单元渲染逐字高亮，**必须保留**。
        // 早期误用 toPlainLrc 剥掉标记 → 退化成整行高亮（用户反馈「逐字歌词呢」）。
        assertTrue(text.contains("<00:00.00>Hello"), "必须保留逐字标记：\n" + text)
        assertTrue(text.contains("<00:00.50>World"))
        assertTrue(text.contains("[00:00.00]"), "行时间戳要保留")
    }

    @Test
    fun `空歌词与空 id 不登记`() {
        LyricsRegistry.put("", "[00:01.00]x")
        LyricsRegistry.put("kugou-BLANK", "   ")
        assertNull(LyricsRegistry.get("kugou-BLANK"))
    }

    @Test
    fun `当前曲目兜底查询`() {
        LyricsRegistry.put("kugou-CUR", "[00:03.00]cur")
        LyricsRegistry.markCurrent("kugou-CUR")
        assertEquals("[00:03.00]cur", LyricsRegistry.current())
    }

    @Test
    fun `扩展点类带有两个歌词回调方法`() {
        // workshop-api / pf4j 是 compileOnly，测试运行时不在 classpath，
        // 用类文件字节验证方法名（不触发类加载）。
        val resource = "cn/spw/kugou/lite/host/KugouLyricsExtension.class"
        val bytes = javaClass.classLoader.getResourceAsStream(resource)
            ?.use { it.readBytes() } ?: ByteArray(0)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("updateLyrics"), "必须实现 updateLyrics")
        assertTrue(text.contains("onBeforeLoadLyrics"), "必须实现 onBeforeLoadLyrics")
        assertTrue(text.contains("LyricsRegistry"), "必须读注册表")
    }
}
