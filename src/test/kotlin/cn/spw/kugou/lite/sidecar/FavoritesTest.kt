package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 收藏相关的数据约定测试。
 *
 * 「我喜欢」的实现方式：酷狗把它做成**一个自建歌单**（`type=0`）。
 * 实测 `/user/playlist` 返回：
 *
 *   listid=1  type=0  name=默认收藏
 *   listid=2  type=0  name=我喜欢      ← 固定 id
 *   listid=3  type=1  name=二十四节气   ← type=1 是收藏别人的歌单
 *
 * 写接口在 `/playlist/tracks/add` 与 `/playlist/tracks/del`，
 * 它们不在 `/user/` 前缀下 —— 这是最初漏掉的位置。
 */
class FavoritesTest {

    @Test
    fun `收藏请求的 data 格式是 歌名-hash-albumId-mixSongId`() {
        val song = Song(
            fileName = "x",
            title = "夜に駆ける",
            singer = "YOASOBI",
            hash = "ABC123",
            durationSec = 200,
            imageUrl = null,
            fileSize = 0,
            payType = 0,
            albumName = "THE BOOK",
            albumId = "45471175",
            mixSongId = "318808767",
        )
        // 这个格式来自上游 playlist_tracks_add.js：
        //   data 参数 `歌名|hash|album_id|mixsongid`
        val expected = "夜に駆ける|ABC123|45471175|318808767"
        val actual = "${song.title}|${song.hash}|${song.albumId}|${song.mixSongId}"
        assertEquals(expected, actual)
    }

    @Test
    fun `缺 albumId 与 mixSongId 时补 0 而不是空串`() {
        val song = Song(
            fileName = "x",
            title = "某歌",
            singer = "某歌手",
            hash = "H1",
            durationSec = 100,
            imageUrl = null,
            fileSize = 0,
            payType = 0,
            albumName = null,
        )
        val albumId = song.albumId.ifBlank { "0" }
        val mixId = song.mixSongId.ifBlank { "0" }
        assertEquals("某歌|H1|0|0", "${song.title}|${song.hash}|$albumId|$mixId")
    }

    @Test
    fun `歌名里的竖线与逗号会被清掉以免破坏协议`() {
        val raw = "A|B,C"
        val safe = raw.replace('|', ' ').replace(',', ' ')
        assertEquals("A B C", safe)
        // 否则 data 会被拆成多余字段
        assertTrue(safe.split('|').size == 1)
    }

    @Test
    fun `解析出 mixSongId 供收藏使用`() {
        val song = KugouApi.parseSongForTest(
            JsonLite.parse(
                """
                {
                  "hash": "ABC",
                  "songname": "夜に駆ける",
                  "author_name": "YOASOBI",
                  "album_id": 45471175,
                  "mixsongid": 318808767
                }
                """.trimIndent(),
            ),
        )
        assertTrue(song != null)
        assertEquals("45471175", song.albumId)
        assertEquals("318808767", song.mixSongId)
    }
}
