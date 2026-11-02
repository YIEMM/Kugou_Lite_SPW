package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Song] 取流键逻辑的测试。
 *
 * 背景：不同接口返回的歌曲字段完全不同（实测）——
 * `/search` 有 `FileHash`，`/everyday/recommend` 有 `hash`，
 * 而 `/rank/audio` **完全没有 hash**，只有 `album_audio_id`。
 *
 * 早期实现把 hash 当必需字段，导致榜单歌曲全部被丢弃，
 * 表现为「排行榜内列表为空 / 无法点击」。
 */
class SongStreamKeyTest {

    private fun song(hash: String = "", albumAudioId: String = "") = Song(
        fileName = "x",
        title = "x",
        singer = "x",
        hash = hash,
        durationSec = 0,
        imageUrl = null,
        fileSize = 0,
        payType = 0,
        albumName = null,
        albumAudioId = albumAudioId,
    )

    @Test
    fun `只有 hash 时用 hash 取流`() {
        val s = song(hash = "ABC123")
        assertTrue(s.canStream)
        assertEquals("hash=ABC123", s.streamQuery)
    }

    @Test
    fun `只有 album_audio_id 时用后者取流`() {
        // 榜单歌曲就是这种情况：没有 hash
        val s = song(albumAudioId = "967249675")
        assertTrue(s.canStream)
        assertEquals("album_audio_id=967249675", s.streamQuery)
    }

    @Test
    fun `两者都有时优先 hash`() {
        val s = song(hash = "ABC", albumAudioId = "123")
        assertEquals("hash=ABC", s.streamQuery)
    }

    @Test
    fun `两者都没有时不可播放`() {
        val s = song()
        assertFalse(s.canStream)
        assertEquals("", s.streamQuery)
    }
}
