package cn.spw.kugou.lite.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 发现页流式布局的纯逻辑测试：自动列数与瀑布流分列。
 *
 * UI 本身无法单测，这里钉住三条规则：
 * 1. 列数 = 可用宽度 ÷ 每列最小宽度，向下取整，封顶 3、保底 1
 * 2. 分列按「轮转」分配（第 i 个区块进第 i%列数 列）——视觉阅读顺序与配置顺序一致，
 *    且每列独立往下排，矮卡片下方不会被撑出空洞
 * 3. 空列丢弃（列数多于区块数时不留占位列）
 */
class DiscoverFlowTest {

    // ------------------------------------------------------------ discoverColumnCount

    @Test
    fun `宽度非法或不足一列时保底单列`() {
        assertEquals(1, discoverColumnCount(0f))
        assertEquals(1, discoverColumnCount(-100f))
        assertEquals(1, discoverColumnCount(299f))
    }

    @Test
    fun `每满最小列宽增加一列`() {
        assertEquals(1, discoverColumnCount(300f))
        assertEquals(2, discoverColumnCount(600f))
        assertEquals(2, discoverColumnCount(650f))
    }

    @Test
    fun `列数封顶不超过上限`() {
        assertEquals(3, discoverColumnCount(900f))
        assertEquals(3, discoverColumnCount(5000f))
    }

    // ------------------------------------------------------------ discoverFlowColumns

    @Test
    fun `四个区块按两列轮转分两列`() {
        val cols = discoverFlowColumns(listOf("a", "b", "c", "d"), 2)
        assertEquals(listOf(listOf("a", "c"), listOf("b", "d")), cols)
    }

    @Test
    fun `四个区块按三列轮转末项回到第一列`() {
        val cols = discoverFlowColumns(listOf("a", "b", "c", "d"), 3)
        assertEquals(listOf(listOf("a", "d"), listOf("b"), listOf("c")), cols)
    }

    @Test
    fun `列数多于区块数时空列被丢弃`() {
        val cols = discoverFlowColumns(listOf("a", "b"), 3)
        assertEquals(listOf(listOf("a"), listOf("b")), cols)
    }

    @Test
    fun `空区块列表返回空`() {
        assertTrue(discoverFlowColumns(emptyList(), 2).isEmpty())
    }

    @Test
    fun `列数非法时按单列堆叠`() {
        val cols = discoverFlowColumns(listOf("a", "b"), 0)
        assertEquals(listOf(listOf("a", "b")), cols)
    }

    @Test
    fun `轮转分列保持配置的阅读顺序`() {
        // 第 i 项永远排在第 i-列数 项之后（同列内），阅读顺序 = 配置顺序
        val ids = listOf("fm", "daily", "discover", "newSongs", "newAlbums")
        val cols = discoverFlowColumns(ids, 3)
        val reading = buildList {
            var index = 0
            while (cols.any { it.size > index }) {
                cols.forEach { col -> if (index < col.size) add(col[index]) }
                index++
            }
        }
        assertEquals(ids, reading)
    }
}
