package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 歌单曲目接口选择策略的测试（用户报告的「122 首只剩 118 首」）。
 *
 * ## 两个接口的差异（2026-11 实测）
 *
 * | 接口 | 参数 | 版权屏蔽曲目 | 完整率 |
 * |---|---|---|---|
 * | `/playlist/track/all` | `global_collection_id`（gid） | 只返回 `{"fileid":95,"shield":1}` 占位 | 122 → **118** |
 * | `/playlist/track/all/new` | `listid`（数字） | **返回完整曲目信息** | 122 → **122** |
 *
 * 官方客户端与 MoeKoe 都能显示 122 首，就是因为走的是后者。
 *
 * 所以：**自己的歌单（有 listid）优先用 `/new`，拿不到再回退 gid 接口**。
 * 别人的歌单/广场只能走 gid 接口（没有 listid 权限）。
 */
class PlaylistSourceTest {

    @Test
    fun `有自己的 listid 时优先走 track_all_new`() {
        assertEquals(
            PlaylistSource.OwnList,
            pickPlaylistSource(listId = "39", gid = "collection_3_1072630322_39_0"),
        )
    }

    @Test
    fun `没有 listid 时走 gid 接口`() {
        assertEquals(
            PlaylistSource.ByGid,
            pickPlaylistSource(listId = "", gid = "collection_3_573354191_11_0"),
        )
    }

    @Test
    fun `listid 非数字时视为不可用`() {
        // 广场/搜索来的歌单 id 是 collection_xxx，不是数字 listid
        assertEquals(
            PlaylistSource.ByGid,
            pickPlaylistSource(listId = "collection_3_x_1_0", gid = "collection_3_x_1_0"),
        )
    }

    @Test
    fun `两个都空时走 gid（内部会返回空）`() {
        assertEquals(PlaylistSource.ByGid, pickPlaylistSource(listId = "", gid = ""))
    }

    // ---------------------------------------------------- 复合歌名拆解

    @Test
    fun `track_all_new 的复合歌名能被拆成歌手与歌名`() {
        val (singer, title) = splitCompositeName("猫瑾 - ハレハレヤ (朗朗晴天).mp3")
        assertEquals("猫瑾", singer)
        assertEquals("ハレハレヤ (朗朗晴天)", title)
    }

    @Test
    fun `没有歌手前缀时整串当歌名`() {
        val (singer, title) = splitCompositeName("只有歌名.mp3")
        assertEquals("", singer)
        assertEquals("只有歌名", title)
    }

    @Test
    fun `多个连字符时只按第一个拆`() {
        val (singer, title) = splitCompositeName("Aimer - 星屑ビーナス - Live.mp3")
        assertEquals("Aimer", singer)
        assertEquals("星屑ビーナス - Live", title)
    }

    @Test
    fun `空字符串不崩`() {
        val (singer, title) = splitCompositeName("")
        assertEquals("", singer)
        assertEquals("", title)
        assertTrue(true)
    }
}
