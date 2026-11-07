package cn.spw.kugou.lite.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 发现页流式布局的纯逻辑测试：自动列数与流式分行。
 *
 * UI 本身无法单测，这里只钉住两条规则：
 * 1. 列数 = 可用宽度 ÷ 每列最小宽度，向下取整，封顶 3、保底 1
 * 2. 分行按列数切块，末行只剩 1 项时独占整行（不留半行空洞）
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

    // ------------------------------------------------------------ discoverFlowRows

    @Test
    fun `四个区块按两列流式分成两行`() {
        val rows = discoverFlowRows(listOf("a", "b", "c", "d"), 2)
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows[0].map { it.id })
        assertEquals(listOf("c", "d"), rows[1].map { it.id })
        assertTrue(rows.all { row -> row.all { it.span == 1 } })
    }

    @Test
    fun `末行落单的区块独占整行`() {
        val rows = discoverFlowRows(listOf("a", "b", "c"), 2)
        assertEquals(2, rows.size)
        assertEquals(1, rows[1].size)
        assertEquals("c", rows[1][0].id)
        assertEquals(2, rows[1][0].span)
    }

    @Test
    fun `单个区块也独占整行`() {
        val rows = discoverFlowRows(listOf("a"), 3)
        assertEquals(1, rows.size)
        assertEquals(3, rows[0][0].span)
    }

    @Test
    fun `空区块列表返回空行`() {
        assertTrue(discoverFlowRows(emptyList(), 2).isEmpty())
    }

    @Test
    fun `列数非法时按单列纵向堆叠`() {
        val rows = discoverFlowRows(listOf("a", "b"), 0)
        assertEquals(2, rows.size)
        assertEquals(1, rows[0][0].span)
    }
}
