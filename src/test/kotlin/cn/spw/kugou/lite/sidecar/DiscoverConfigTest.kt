package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DiscoverConfig] 的测试：编辑面板配置的默认值、可见性、排序、容错与往返。
 */
class DiscoverConfigTest {

    @Test
    fun `默认配置是单列全部显示档位0`() {
        val c = DiscoverConfig.default()
        assertEquals(DiscoverConfig.Layout.SINGLE, c.layout)
        assertEquals(listOf("fm", "daily", "discover", "newSongs", "newAlbums"), c.order)
        assertTrue(c.hidden.isEmpty())
        assertEquals(0, c.fmStationIndex)
    }

    @Test
    fun `隐藏区块后 visible 保持顺序`() {
        val c = DiscoverConfig.default().copy(hidden = setOf("daily", "newAlbums"))
        assertEquals(listOf("fm", "discover", "newSongs"), c.visible)
    }

    @Test
    fun `上移下移改变顺序`() {
        val c = DiscoverConfig.default().move("newSongs", -1)
        assertEquals("newSongs", c.order[2])
    }

    @Test
    fun `反序列化容错未知ID与缺失项`() {
        val c = DiscoverConfig.fromProperties(
            mapOf(
                "layout" to "pair",
                "order" to "daily,UNKNOWN,fm",
                "hidden" to "daily,BOGUS",
                "fmStationIndex" to "99",
            ),
        )
        assertEquals(DiscoverConfig.Layout.PAIR, c.layout)
        assertEquals("daily", c.order[0])
        assertTrue(c.order.containsAll(listOf("fm", "daily", "discover", "newSongs", "newAlbums")))
        assertEquals(5, c.order.size)
        assertEquals(0, c.fmStationIndex)
    }

    @Test
    fun `写入再读出一致`() {
        val c = DiscoverConfig.default().copy(
            layout = DiscoverConfig.Layout.PAIR,
            fmStationIndex = 2,
        )
        assertEquals(c, DiscoverConfig.fromProperties(c.toProperties()))
    }
}
