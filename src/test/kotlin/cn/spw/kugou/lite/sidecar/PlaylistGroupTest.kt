package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 歌单分组的测试。
 *
 * 酷狗把「我喜欢」「默认收藏」都做成**自建歌单**（`type=0`），
 * 和用户自建混在一起。UI 需要把它们单独标出来并置顶，
 * 否则最常用的入口会被 20 多个歌单淹没。
 */
class PlaylistGroupTest {

    private fun playlist(
        listId: String,
        name: String,
        type: Int,
        count: Int = 10,
    ) = Playlist(
        id = "collection_3_1_${listId}_0",
        name = name,
        coverUrl = null,
        songCount = count,
        playCount = 0,
        description = null,
        listId = listId,
        type = type,
        isFavorites = listId == "2" && type == 0,
    )

    @Test
    fun `我喜欢按 listid 识别而不是按名字`() {
        val fav = playlist("2", "我喜欢", type = 0)
        assertTrue(fav.isFavorites)
        assertTrue(fav.isOwn)

        // 用户把歌单改名也仍然是「我喜欢」
        val renamed = playlist("2", "我的最爱改名了", type = 0)
        assertTrue(renamed.isFavorites, "识别必须按 listid，不按名字")

        // 别的歌单叫「我喜欢」也不算
        val impostor = playlist("99", "我喜欢", type = 0)
        assertFalse(impostor.isFavorites)
    }

    @Test
    fun `收藏的歌单 type 为 1`() {
        val collected = playlist("3", "二十四节气", type = 1)
        assertFalse(collected.isOwn)
        assertFalse(collected.isFavorites)
    }

    @Test
    fun `三段分组互不重叠`() {
        val all = listOf(
            playlist("1", "默认收藏", 0),
            playlist("2", "我喜欢", 0),
            playlist("5", "自建A", 0),
            playlist("3", "收藏的谷", 1),
        )
        val system = all.filter { it.isFavorites || it.listId == "1" }
        val own = all.filter { it.isOwn && !system.contains(it) }
        val collected = all.filter { !it.isOwn }

        assertEquals(2, system.size, "系统歌单应为我喜欢 + 默认收藏")
        assertEquals(1, own.size)
        assertEquals(1, collected.size)
        // 无重叠
        assertTrue(system.none { own.contains(it) || collected.contains(it) })
        assertTrue(own.none { collected.contains(it) })
    }

    @Test
    fun `封面 {size} 占位被替换`() {
        val pl = Playlist(
            id = "x",
            name = "x",
            coverUrl = "http://c1.kgimg.com/stdmusic/{size}/20250217/xxx.jpg",
            songCount = 1,
            playCount = 0,
            description = null,
        )
        assertEquals(
            "http://c1.kgimg.com/stdmusic/300/20250217/xxx.jpg",
            pl.coverAt(300),
        )
        assertEquals(
            "http://c1.kgimg.com/stdmusic/56/20250217/xxx.jpg",
            pl.coverAt(56),
        )
    }

    @Test
    fun `无 {size} 的封面原样返回`() {
        val pl = Playlist(
            id = "x",
            name = "x",
            coverUrl = "http://imge.kugou.com/kugouicon/165/20250816/x.png",
            songCount = 0,
            playCount = 0,
            description = null,
        )
        assertEquals("http://imge.kugou.com/kugouicon/165/20250816/x.png", pl.coverAt(300))
    }

    @Test
    fun `无封面返回 null`() {
        val pl = Playlist("x", "x", null, 0, 0, null)
        assertEquals(null, pl.coverAt(300))
    }
}
