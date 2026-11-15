package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 私人 FM 续流的游标协议。
 *
 * ## 真机故障（2026-11-15）
 *
 * 日志显示每次续拉都带同一个 `cur_mark=99999998`，上游也就每次返回**同一批 5 首**：
 *
 *     21:21:22 私人 FM → 5 首，cur_mark=99999998  → 追加 5 首
 *     21:21:42 私人 FM → 5 首，cur_mark=99999998  → 本批全听过，停止续拉
 *
 * 于是「无限续播」实际只能成功一次，之后被去重全挡。
 *
 * ## 正确协议（对照 MD3Music `personal_fm_core.dart` / `kugou_api_client.dart`）
 *
 * 续流靠**上一首的 hash + songid**，不是 `cur_mark` 分页：
 *
 *     params["mode"]        = mode
 *     params["song_pool_id"] = songPoolId
 *     params["hash"]        = 上一首的 hash
 *     params["songid"]      = 上一首的 songId
 *     params["action"]      = "play"
 *
 * 且带游标要不到新歌时必须**退回不带游标再要一次**（参考实现 `_append`）。
 */
class FmCursorTest {

    @Test
    fun `续流游标用 hash 而不是 cur_mark`() {
        val params = DiscoverApi.buildFmParams(
            mode = "normal",
            songPoolId = 0,
            cursorHash = "ABC123",
            cursorSongId = "987654",
        )
        assertEquals("ABC123", params["hash"], "必须带上一首的 hash")
        assertEquals("987654", params["songid"], "必须带上一首的 songid")
        assertTrue("cur_mark" !in params, "cur_mark 不是分页游标，不应作为续流参数")
        assertEquals("play", params["action"])
    }

    @Test
    fun `无游标时只带模式与档位`() {
        val params = DiscoverApi.buildFmParams(
            mode = "normal",
            songPoolId = 0,
            cursorHash = null,
            cursorSongId = null,
        )
        assertTrue("hash" !in params, "无游标不应带 hash")
        assertTrue("songid" !in params)
        assertEquals("normal", params["mode"])
        assertEquals("0", params["song_pool_id"])
    }
}
