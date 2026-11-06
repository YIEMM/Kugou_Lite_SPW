package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 多歌手解析的测试。
 *
 * 用户反馈「不同的歌手会变成一个整体」：一首歌有 `A、B、C` 三位歌手时，
 * 早期实现把它们拼成**一个字符串**，界面上点击只能跳到第一位歌手。
 *
 * 修复后保留 `Song.artists` 结构，每位单独可点。
 */
class MultiArtistTest {

    /** 用真实响应片段构造歌曲对象并解析。 */
    private fun parse(json: String): Song? = KugouApi.parseSongForTest(JsonLite.parse(json))

    @Test
    fun `搜索接口的多位歌手逐个保留 id`() {
        val song = parse(
            """
            {
              "FileHash": "ABC",
              "OriSongName": "妄想感傷代償連盟",
              "Singers": [
                {"name": "DECO*27", "id": 84673},
                {"name": "初音ミク", "id": 83837}
              ],
              "timelen": 270000
            }
            """.trimIndent(),
        )
        assertTrue(song != null)
        assertEquals("妄想感傷代償連盟", song.title)

        assertEquals(2, song.artists.size, "应保留两位歌手，实际：${song.artists}")
        assertEquals("DECO*27", song.artists[0].name)
        assertEquals("84673", song.artists[0].id)
        assertTrue(song.artists[0].clickable)

        assertEquals("初音ミク", song.artists[1].name)
        assertEquals("83837", song.artists[1].id)
        assertTrue(song.artists[1].clickable)

        // 展示用字符串仍是拼接后的整体
        assertEquals("DECO*27、初音ミク", song.singer)
    }

    @Test
    fun `歌单接口没有 id 时名字可显示但不可点`() {
        val song = parse(
            """
            {
              "hash": "XYZ",
              "name": "DECO*27、初音ミク - 妄想感傷代償連盟",
              "timelen": 270000
            }
            """.trimIndent(),
        )
        assertTrue(song != null)
        assertEquals("妄想感傷代償連盟", song.title)
        assertEquals("DECO*27、初音ミク", song.singer)

        // 从拼接字符串拆出两位，但都没有 id
        assertEquals(2, song.artists.size, "实际：${song.artists}")
        assertEquals("DECO*27", song.artists[0].name)
        assertEquals("", song.artists[0].id)
        assertFalse(song.artists[0].clickable, "没有 id 时不应可点")

        assertEquals("初音ミク", song.artists[1].name)
        assertFalse(song.artists[1].clickable)
    }

    @Test
    fun `歌手接口的 authors 结构`() {
        val song = parse(
            """
            {
              "hash": "H1",
              "audio_name": "NEON HEARTBEAT",
              "author_name": "初音ミク",
              "authors": [{"author_name": "初音ミク", "author_id": 83837}],
              "timelen": 180000
            }
            """.trimIndent(),
        )
        assertTrue(song != null)
        assertEquals("NEON HEARTBEAT", song.title)
        assertEquals(1, song.artists.size)
        assertEquals("初音ミク", song.artists[0].name)
        assertEquals("83837", song.artists[0].id)
        assertTrue(song.artists[0].clickable)
    }

    @Test
    fun `单歌手也走同一套结构`() {
        val song = parse(
            """
            {
              "FileHash": "S1",
              "OriSongName": "独自的歌",
              "Singers": [{"name": "某歌手", "id": 123}],
              "timelen": 100000
            }
            """.trimIndent(),
        )
        assertTrue(song != null)
        assertEquals(1, song.artists.size)
        assertEquals("某歌手", song.artists[0].name)
        assertTrue(song.artists[0].clickable)
    }
}
