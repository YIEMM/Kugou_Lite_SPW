package cn.spw.kugou.lite.ui

import cn.spw.kugou.lite.sidecar.AlbumBrief
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * N12（BUG_AUDIT）回归：新碟上架专辑卡片点击必须产生导航，而不是只写日志。
 *
 * 修复方式：插件已有专辑详情页路由（[PageNav.openAlbum] → AlbumPage 按
 * PageNav.albumId 加载 /album/detail 与 /album/songs），点击直接接入该路由；
 * 不走「取专辑曲目建队列播放」方案 —— 专辑详情页信息更完整，且与歌手卡
 * （openArtist）、歌单卡（openPlaylist）的点击行为一致。
 *
 * UI 组合本身无法单测，这里钉住两件事：
 * 1. 导航目标解析纯函数 [albumNavigationTarget]：有效条目 → (albumId, 名称)；
 *    下标越界 / id 为空 → null（不导航、不崩溃）
 * 2. [PageNav.openAlbum] 路由：进专辑页，albumId/标题随帧携带、pop 后恢复
 */
class AlbumPickNavigationTest {

    private val albums = listOf(
        AlbumBrief(id = "100", name = "专辑甲", artist = "歌手A", coverUrl = null),
        AlbumBrief(id = "200", name = "专辑乙", artist = "歌手B", coverUrl = "http://x/{size}/y.jpg"),
    )

    @Test
    fun `点击有效专辑解析出专辑id与名称`() {
        assertEquals("100" to "专辑甲", albumNavigationTarget(albums, 0))
        assertEquals("200" to "专辑乙", albumNavigationTarget(albums, 1))
    }

    @Test
    fun `下标越界或id为空不导航`() {
        assertNull(albumNavigationTarget(albums, 2))
        assertNull(albumNavigationTarget(albums, -1))
        val blank = listOf(AlbumBrief(id = "", name = "无id专辑", artist = "", coverUrl = null))
        assertNull(albumNavigationTarget(blank, 0))
    }

    @Test
    fun `openAlbum路由进专辑页且帧携带albumId`() {
        PageNav.reset()
        PageNav.openAlbum("200", "专辑乙")
        assertEquals(KugouLiteTab.Album, PageNav.tab)
        assertEquals("200", PageNav.albumId)
        assertEquals("专辑乙", PageNav.detailTitle)
        PageNav.pop()
        assertEquals("", PageNav.albumId)   // 弹栈恢复上一帧，不残留
    }
}
