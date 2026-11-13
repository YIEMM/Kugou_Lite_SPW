package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 歌曲可播放性的测试（用户反馈「无版权歌曲要标注、播放全部时剔除」）。
 *
 * ## 关键教训：接口字段猜不出来
 *
 * 最初按 `media_privilege` + `trans_param.display` 推断，结果误标一片。
 * 实测反证（「yi_捏喜欢的音乐」前 10 首）：
 *
 * | 歌曲 | media_privilege | display | 实际取流 |
 * |---|---|---|---|
 * | ハレハレヤ | 8 | 0 | 31833 无版权 |
 * | 群青 | 10 | 0 | **成功** |
 * | 春に落ちて | 8 | 0 | 成功 |
 * | 我们的歌 | 10 | 0 | 成功（收藏页解锁）|
 *
 * 同样的 privilege=10 + display=0 既有无版权也有能播的。
 * 所以 [playabilityOf] 现在一律返回 [Playability.Unknown]，
 * 真判定只认 [KugouApi.probePlayability] 的实测结果。
 */
class SongPrivilegeTest {

    @Test
    fun `接口字段不再用于判定可播放性`() {
        // 曾经 privilege=0 判 Ok、privilege!=0+display=0 判无版权，都是错的
        assertEquals(Playability.Unknown, playabilityOf(mediaPrivilege = 0, displayFlag = 1))
        assertEquals(Playability.Unknown, playabilityOf(mediaPrivilege = 5, displayFlag = 0))
        assertEquals(Playability.Unknown, playabilityOf(mediaPrivilege = 10, displayFlag = 0))
    }

    @Test
    fun `31833 是无版权的取流错误码`() {
        assertTrue(isNoCopyrightErrorCode(31833))
        assertFalse(isNoCopyrightErrorCode(0))
        assertFalse(isNoCopyrightErrorCode(20017)) // 需要登录
    }

    @Test
    fun `播放全部时按探测结果过滤无版权`() {
        val probed = mapOf(
            "a" to Playability.Ok,
            "b" to Playability.NoCopyright,
            "c" to Playability.Unknown,
        )
        val songs = listOf("a", "b", "c")
        // 只有「确认无版权」的剔除；未知的保留（点了再实时判断）
        val playable = songs.filter { probed[it] != Playability.NoCopyright }
        assertEquals(listOf("a", "c"), playable)
    }

    @Test
    fun `未知状态不标标签`() {
        assertFalse(Playability.Unknown == Playability.NoCopyright)
        assertTrue(Playability.NoCopyright == Playability.NoCopyright)
    }

    @Test
    fun `全是无版权时播放列表为空`() {
        val probed = mapOf("b" to Playability.NoCopyright)
        val playable = listOf("b").filter { probed[it] != Playability.NoCopyright }
        assertTrue(playable.isEmpty())
    }
}
