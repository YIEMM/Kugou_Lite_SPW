package cn.spw.kugou.lite.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 歌词必须**按 id 精确匹配**，绝不能拿上一首的歌词兜底。
 *
 * ## 现场
 *
 * 用户反馈：歌词能显示了，但「给的歌词都是上一首歌的」。
 *
 * 根因是扩展点里的兜底：
 *
 *     LyricsRegistry.get(id) ?: LyricsRegistry.current()
 *
 * 宿主切到新歌的瞬间就来问歌词，而那一刻新歌的歌词还在下载，
 * 注册表里没有；`current()` 此时仍是**上一首**的 id，于是把上一首的
 * 歌词当成新歌的返回了。错歌词比没歌词更糟。
 *
 * ## 判定
 *
 * - id 有歌词 → 返回它
 * - id 没有 → 返回 null（让宿主自己回退读文件），**不许**用别的曲目顶替
 */
class LyricsRegistryMatchTest {

    @Test
    fun `按 id 精确命中`() {
        LyricsRegistry.put("kugou-AAA", "[00:01.00]aaa")
        assertEquals("[00:01.00]aaa", LyricsRegistry.get("kugou-AAA"))
    }

    @Test
    fun `未登记的 id 必须返回 null`() {
        LyricsRegistry.put("kugou-PREV", "[00:01.00]prev")
        LyricsRegistry.markCurrent("kugou-PREV")
        assertNull(LyricsRegistry.get("kugou-NEXT"), "不许把当前曲目的歌词当成新曲目的")
    }

    @Test
    fun `当前曲目标记不影响按 id 查询`() {
        LyricsRegistry.put("kugou-X", "[00:01.00]x")
        LyricsRegistry.put("kugou-Y", "[00:02.00]y")
        LyricsRegistry.markCurrent("kugou-Y")
        assertEquals("[00:01.00]x", LyricsRegistry.get("kugou-X"))
        assertEquals("[00:02.00]y", LyricsRegistry.get("kugou-Y"))
    }
}
