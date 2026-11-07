package cn.spw.kugou.lite.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PageNav] 返回栈的行为测试。
 *
 * 栈语义：push 压入新帧，pop 恢复上一帧（含 detail 状态），栈空再 pop 停在 Home。
 */
class PageNavTest {

    @Test
    fun `push压栈pop弹栈栈空回Home`() {
        PageNav.reset()
        PageNav.go(KugouLiteTab.Search)
        PageNav.push(KugouLiteTab.SongList)
        PageNav.push(KugouLiteTab.Artist)
        PageNav.push(KugouLiteTab.Album)
        assertEquals(KugouLiteTab.Album, PageNav.tab)
        PageNav.pop(); assertEquals(KugouLiteTab.Artist, PageNav.tab)
        PageNav.pop(); assertEquals(KugouLiteTab.SongList, PageNav.tab)
        PageNav.pop(); assertEquals(KugouLiteTab.Search, PageNav.tab)
        PageNav.pop(); assertEquals(KugouLiteTab.Home, PageNav.tab)
        PageNav.pop(); assertEquals(KugouLiteTab.Home, PageNav.tab) // 栈空再 pop 不崩
    }

    @Test
    fun `push时detail状态随帧切换且pop后不残留`() {
        PageNav.reset()
        PageNav.push(KugouLiteTab.SongList)
        PageNav.detailPlaylistId = "123"
        PageNav.push(KugouLiteTab.Artist)
        PageNav.artistId = "9"
        assertEquals("9", PageNav.artistId)
        PageNav.pop()
        assertEquals("", PageNav.artistId)          // 弹栈恢复上一层帧
        assertEquals("123", PageNav.detailPlaylistId)
        assertEquals(KugouLiteTab.SongList, PageNav.tab)
    }

    @Test
    fun `discoverRefresh仅在离开编辑面板时递增`() {
        PageNav.reset()
        PageNav.openDiscoverEditor()
        val n = PageNav.discoverRefresh
        PageNav.pop()
        assertEquals(n + 1, PageNav.discoverRefresh)
    }

    @Test
    fun `open系列仍可用且走push`() {
        PageNav.reset()
        PageNav.openPlaylist("c_1", "通勤路上")
        assertEquals(KugouLiteTab.SongList, PageNav.tab)
        assertEquals("c_1", PageNav.detailPlaylistId)
        assertEquals("通勤路上", PageNav.detailTitle)
        PageNav.openArtist("83837", "初音ミク")
        assertEquals(KugouLiteTab.Artist, PageNav.tab)
        PageNav.pop()
        assertEquals(KugouLiteTab.SongList, PageNav.tab)
        assertEquals("通勤路上", PageNav.detailTitle) // 回到歌单详情帧
        PageNav.reset()
        assertEquals(KugouLiteTab.Home, PageNav.tab)
    }

    @Test
    fun `go直达顶层页并清栈`() {
        PageNav.reset()
        PageNav.openPlaylist("c_1", "通勤路上")
        PageNav.go(KugouLiteTab.Ranks)
        assertEquals(KugouLiteTab.Ranks, PageNav.tab)
        assertTrue(PageNav.stackDepth() == 0)
        PageNav.pop()
        assertEquals(KugouLiteTab.Home, PageNav.tab) // go 后栈空，pop 停在 Home
    }
}
