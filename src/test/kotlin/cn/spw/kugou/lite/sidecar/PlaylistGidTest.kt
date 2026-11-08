package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 歌单取数 id 选择的回归测试（用户反馈 #1）。
 *
 * ## 这个测试防的是什么
 *
 * 「好听的沉浸式日文歌」在歌单列表显示 18 首，进详情只有 16 首。
 * 根因：`/user/playlist` 对**收藏的歌单**返回两个 gid：
 *
 * - `global_collection_id` = collection_3_<自己>_<listid>_0 → 收藏副本（16 首）
 * - `list_create_gid` = collection_3_<原作者>_<原listid>_0 → 原歌单（18 首）
 *
 * 早期实现无条件取 `global_collection_id`，收藏歌单因此少曲目。
 * MoeKoe / 官方酷狗 Lite 能拿全，是因为它们对收藏歌单优先用
 * `list_create_gid`。
 *
 * 规则：**收藏歌单（type=1，且有 list_create_gid）用原作者 gid；
 * 自建歌单用 global_collection_id**（原作者就是自己，两者一致）。
 */
class PlaylistGidTest {

    @Test
    fun `收藏歌单优先用 list_create_gid`() {
        val chosen = pickPlaylistGid(
            type = 1,
            globalCollectionId = "collection_3_1072630322_15_0",
            listCreateGid = "collection_3_573354191_11_0",
        )
        assertEquals("collection_3_573354191_11_0", chosen)
    }

    @Test
    fun `自建歌单用 global_collection_id`() {
        val chosen = pickPlaylistGid(
            type = 0,
            globalCollectionId = "collection_3_1072630322_2_0",
            listCreateGid = "collection_3_1072630322_2_0",
        )
        assertEquals("collection_3_1072630322_2_0", chosen)
    }

    @Test
    fun `收藏歌单没有 list_create_gid 时降级 global_collection_id`() {
        val chosen = pickPlaylistGid(
            type = 1,
            globalCollectionId = "collection_3_1072630322_15_0",
            listCreateGid = "",
        )
        assertEquals("collection_3_1072630322_15_0", chosen)
    }

    @Test
    fun `两个 id 相同就是同一份`() {
        val chosen = pickPlaylistGid(
            type = 1,
            globalCollectionId = "collection_3_x_1_0",
            listCreateGid = "collection_3_x_1_0",
        )
        assertEquals("collection_3_x_1_0", chosen)
    }

    @Test
    fun `收藏歌单判定`() {
        assertTrue(isCollectedPlaylist(type = 1))
        assertFalse(isCollectedPlaylist(type = 0))
    }
}
