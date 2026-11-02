package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据模型的格式化与容错测试。
 *
 * 重点覆盖**真实响应里可能缺失的字段**：接口一变，字段就少，
 * UI 不能因此崩掉或显示 null。
 */
class ModelsTest {

    private fun song(
        durationSec: Int = 0,
        imageUrl: String? = null,
        bitRate: Int = 128_000,
        extName: String = "mp3",
    ) = Song(
        fileName = "AC/DC - Back In Black",
        title = "Back In Black",
        singer = "AC/DC",
        hash = "HASH",
        durationSec = durationSec,
        imageUrl = imageUrl,
        fileSize = 0,
        payType = 0,
        albumName = null,
    )

    @Test
    fun `时长格式化为 m colon ss`() {
        assertEquals("3:39", song(durationSec = 219).durationText)
        assertEquals("0:05", song(durationSec = 5).durationText)
        assertEquals("10:00", song(durationSec = 600).durationText)
    }

    @Test
    fun `时长缺失时显示占位符`() {
        assertEquals("--:--", song(durationSec = 0).durationText)
        assertEquals("--:--", song(durationSec = -1).durationText)
    }

    @Test
    fun `异常大的时长不渲染荒谬值`() {
        // 真机踩坑：某接口 timelen 单位是毫秒（270027），按秒算会得到 4500:27
        assertEquals("--:--", song(durationSec = 270_027).durationText)
        assertEquals("--:--", song(durationSec = 86_401).durationText)
        // 24 小时整仍可渲染
        assertEquals("1440:00", song(durationSec = 86_400).durationText)
    }

    @Test
    fun `封面模板替换 size 占位符`() {
        val s = song(imageUrl = "http://imge.kugou.com/stdmusic/{size}/cover.jpg")
        assertEquals("http://imge.kugou.com/stdmusic/240/cover.jpg", s.coverUrl(240))
        assertEquals("http://imge.kugou.com/stdmusic/480/cover.jpg", s.coverUrl(480))
    }

    @Test
    fun `无封面或非 http 时返回 null`() {
        assertNull(song(imageUrl = null).coverUrl())
        assertNull(song(imageUrl = "file:///c:/a.png").coverUrl())
        // 没有 {size} 占位符时原样返回（仍是合法 http 地址）
        assertEquals("http://x/y.jpg", song(imageUrl = "http://x/y.jpg").coverUrl())
    }

    @Test
    fun `音质描述映射`() {
        assertEquals("128k", StreamInfo("u", emptyList(), 128_000, 0, "mp3", 0).qualityText)
        assertEquals("320k", StreamInfo("u", emptyList(), 320_000, 0, "mp3", 0).qualityText)
        assertEquals("无损", StreamInfo("u", emptyList(), 900_000, 0, "flac", 0).qualityText)
        // 扩展名是 flac 但码率数据缺失时，也应识别为无损
        assertEquals("无损", StreamInfo("u", emptyList(), 0, 0, "flac", 0).qualityText)
        // 未知码率不应崩
        assertEquals("0k", StreamInfo("u", emptyList(), 0, 0, "mp3", 0).qualityText)
    }

    @Test
    fun `取流地址列表含主地址与备用`() {
        val info = StreamInfo(
            url = "http://main/a.mp3",
            backupUrls = listOf("http://b1/a.mp3", "http://b2/a.mp3"),
            bitRate = 128_000,
            fileSize = 100,
            extName = "mp3",
            durationSec = 10,
        )
        assertEquals(3, info.allUrls.size)
        assertEquals("http://main/a.mp3", info.allUrls.first())
    }

    @Test
    fun `排行榜与歌单模型容错`() {
        val rank = Rank(id = "8888", name = "飙升榜", coverUrl = null, songCount = 100)
        assertEquals("8888", rank.id)
        val pl = Playlist(id = "1", name = "我喜欢的音乐", coverUrl = null, songCount = 0, playCount = 0, description = null)
        assertTrue(pl.songCount == 0)
    }
}
