package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SquareApi] 与 [PlaylistManage] 的纯解析/参数测试。
 * 样例 JSON 逐字来自 2026-11-07 实测（规格附录字段矩阵）。
 * 写操作的网络层无法单测，用注入的 [PlaylistManage.verifyGone] 探针测轮询语义。
 */
class SquareApiTest {

    @Test
    fun `标签解析_父分组与子标签`() {
        val root = JsonLite.parse(
            """{"data":[
                {"parent_id":"0","sort":"1","tag_id":"5","tag_name":"场景",
                 "son":[{"parent_id":"5","tag_id":"587","tag_name":"学习","sort":"1"},
                        {"parent_id":"5","tag_id":"660","tag_name":"工作","sort":"2"}]},
                {"parent_id":"0","tag_id":"23","tag_name":"语种","son":[]}]}""",
        )
        val groups = SquareApi.parseTags(root)
        assertEquals(2, groups.size)
        assertEquals("5", groups[0].tagId)
        assertEquals("场景", groups[0].name)
        assertEquals(2, groups[0].children.size)
        assertEquals("学习", groups[0].children[0].name)
        assertEquals(0, groups[1].children.size)
    }

    @Test
    fun `标签解析_null回空`() {
        assertTrue(SquareApi.parseTags(null).isEmpty())
    }

    @Test
    fun `广场歌单解析_speciallist字段与hasnext`() {
        val root = JsonLite.parse(
            """{"data":{"has_next":1,"special_list":[
                {"specialname":"<em>英语</em> | 咖啡厅的静谧","flexible_cover":"http://c1.kgimg.com/custom/{size}/x.jpg",
                 "imgurl":"http://c1.kgimg.com/custom/{size}/x.jpg","play_count":4680479,"collectcount":2256,
                 "global_collection_id":"collection_3_1069106038_168_0","nickname":" brewed","intro":"安静",
                 "specialid":3651264},
                {"specialname":"无封面的歌单","play_count":10,"global_collection_id":"collection_3_x_1_0"}]}}""",
        )
        val page = SquareApi.parsePlaylists(root)
        assertTrue(page.hasNext)
        assertEquals(2, page.items.size)
        val p0 = page.items[0]
        assertEquals("英语 | 咖啡厅的静谧", SquareApi.stripEm(p0.name))
        assertEquals("collection_3_1069106038_168_0", p0.collectionId)
        assertEquals(4680479L, p0.playCount)
        assertTrue(p0.coverUrl!!.contains("{size}"))
        // 无 flexible_cover 的条目回落 imgurl（本例没有 → null 允许）
        assertEquals(null, page.items[1].coverUrl)
    }

    @Test
    fun `广场歌单解析_null回空`() {
        assertTrue(SquareApi.parsePlaylists(null).items.isEmpty())
        assertFalse(SquareApi.parsePlaylists(null).hasNext)
    }

    @Test
    fun `播放量文本_万级格式化`() {
        assertEquals("468万", SquareApi.playCountText(4680479L))
        assertEquals("1.2万", SquareApi.playCountText(12000L))
        assertEquals("999", SquareApi.playCountText(999L))
    }
}

class PlaylistManageTest {

    @Test
    fun `回读轮询_消失即成功`() {
        var calls = 0
        val gone = PlaylistManage.verifyGone("42", attempts = 5, delayMs = 0) {
            calls++
            calls < 3   // 前两次还在（缓存延迟），第三次消失
        }
        assertTrue(gone)
        assertEquals(3, calls)
    }

    @Test
    fun `回读轮询_超过次数仍存在则失败`() {
        val gone = PlaylistManage.verifyGone("42", attempts = 3, delayMs = 0) { true }
        assertFalse(gone)
    }

    @Test
    fun `回读轮询_立即消失零等待`() {
        val gone = PlaylistManage.verifyGone("42", attempts = 5, delayMs = 0) { false }
        assertTrue(gone)
    }
}

/**
 * N15（BUG_AUDIT）回归：删除歌单的 `type` 参数必须由调用方按歌单来源传入。
 *
 * 根因：此前 `/playlist/del` 硬编码 `type=1`（收藏歌单），自建歌单（`type=0`）
 * 删除请求被上游拒绝 —— 自建歌单永远删不掉。
 * 上游契约（playlist.rs /playlist/del 分支）：`type=0` 删自建、`type=1` 删收藏。
 */
class PlaylistManageDeleteParamsTest {

    @Test
    fun `删除自建歌单传type0`() {
        val params = PlaylistManage.deleteParams("12345", type = 0)
        assertEquals("12345", params["listid"])
        assertEquals("0", params["type"])
    }

    @Test
    fun `删除收藏歌单传type1`() {
        val params = PlaylistManage.deleteParams("67890", type = 1)
        assertEquals("67890", params["listid"])
        assertEquals("1", params["type"])
    }

    @Test
    fun `缺省type保持收藏行为兼容`() {
        // 保留默认行为：老调用点（未传 type）仍按收藏歌单 type=1 删除。
        assertEquals("1", PlaylistManage.deleteParams("1", type = 1)["type"])
        assertEquals("1", PlaylistManage.deleteParams("1")["type"])
    }
}
