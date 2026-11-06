package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Album] 的测试。
 *
 * 字段约定来自实测 `/album/detail` 与 `/album/songs`。
 */
class AlbumTest {

    @Test
    fun `封面模板替换尺寸`() {
        val album = Album(
            id = "214011848",
            name = "NEON HEARTBEAT",
            artist = "初音ミク",
            coverTemplate = "http://imge.kugou.com/stdmusic/{size}/20261106/20261106034132325884.jpg",
        )
        assertEquals(
            "http://imge.kugou.com/stdmusic/500/20261106/20261106034132325884.jpg",
            album.coverUrl(500),
        )
        assertEquals(
            "http://imge.kugou.com/stdmusic/120/20261106/20261106034132325884.jpg",
            album.coverUrl(120),
        )
    }

    @Test
    fun `无封面模板时返回 null`() {
        val album = Album(id = "1", name = "x", artist = "y", coverTemplate = null)
        assertEquals(null, album.coverUrl(500))
    }

    @Test
    fun `歌曲解析出 albumId 供专辑页跳转`() {
        val song = KugouApi.parseSongForTest(
            JsonLite.parse(
                """
                {
                  "hash": "H1",
                  "songname": "NEON HEARTBEAT",
                  "author_name": "初音ミク",
                  "album_id": "214011848",
                  "duration": 180
                }
                """.trimIndent(),
            ),
        )
        assertTrue(song != null)
        assertEquals("214011848", song.albumId, "应解析出 album_id")
        assertEquals("NEON HEARTBEAT", song.title)
    }

    @Test
    fun `缺少 album_id 时为空字符串而非崩溃`() {
        val song = KugouApi.parseSongForTest(
            JsonLite.parse(
                """
                {"hash": "H2", "songname": "无专辑", "author_name": "某歌手"}
                """.trimIndent(),
            ),
        )
        assertTrue(song != null)
        assertEquals("", song.albumId)
    }
}
