package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PersonalFm] 的测试：档位、游标、去重、补货阈值、切档清空。
 */
class PersonalFmTest {

    /** 构造一首最小歌曲，只带测试关心的 hash。 */
    private fun song(hash: String) = Song(
        fileName = hash,
        title = hash,
        singer = "测试歌手",
        hash = hash,
        durationSec = 100,
        imageUrl = null,
        fileSize = 0,
        payType = 0,
        albumName = null,
    )

    @Test
    fun `四档位参数正确`() {
        assertEquals(4, PersonalFm.stations.size)
        assertEquals("normal", PersonalFm.stations[0].mode)
        assertEquals(0, PersonalFm.stations[0].songPoolId)
        assertEquals(2, PersonalFm.stations[1].songPoolId)
        assertEquals("small", PersonalFm.stations[2].mode)
        assertEquals("radio", PersonalFm.stations[3].mode)
    }

    @Test
    fun `补货按 hash 去重`() {
        val s = PersonalFm.Session(stationIndex = 0)
        s.markPlayed(listOf(song("A"), song("B")))
        val fresh = s.filterFresh(listOf(song("B"), song("C")))
        assertEquals(listOf("C"), fresh.map { it.hash })
    }

    @Test
    fun `整批重复时返回空列表`() {
        val s = PersonalFm.Session(0)
        s.markPlayed(listOf(song("A")))
        assertTrue(s.filterFresh(listOf(song("A"))).isEmpty())
    }

    @Test
    fun `剩余不超过3首时需要补货`() {
        assertTrue(PersonalFm.needsRefill(remaining = 3))
        assertFalse(PersonalFm.needsRefill(remaining = 4))
    }

    @Test
    fun `切档位重置续流游标与 seenHashes`() {
        val s = PersonalFm.Session(0)
        s.markPlayed(listOf(song("A")))
        // 续流游标是「上一首」，不是 cur_mark（见 PersonalFm.Session.lastSong）
        s.lastSong = song("A")
        val next = s.switchStation(2)
        assertEquals(2, next.stationIndex)
        assertNull(next.lastSong, "切档必须清空游标，否则会把旧档位的歌带到新档位")
        assertTrue(next.seenHashes.isEmpty())
    }
}
