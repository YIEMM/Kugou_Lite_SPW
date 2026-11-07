package cn.spw.kugou.lite.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 封面网格断点（整分支审查 Important #7：七处硬编码 `chunked(3)`）。
 */
class CoverGridTest {

    @Test
    fun `窄窗口降为单列`() {
        assertEquals(1, coverGridColumns(200f))
        assertEquals(1, coverGridColumns(239f))
    }

    @Test
    fun `按 240dp 一档增加列数`() {
        assertEquals(2, coverGridColumns(480f))
        assertEquals(3, coverGridColumns(720f))
    }

    @Test
    fun `封顶四列`() {
        assertEquals(4, coverGridColumns(2000f))
    }

    @Test
    fun `非法宽度保底单列`() {
        assertEquals(1, coverGridColumns(0f))
        assertEquals(1, coverGridColumns(-50f))
    }

    @Test
    fun `切行与列数一致且不丢项`() {
        val items = (1..7).toList()
        val rows = gridRows(items, 3)
        assertEquals(3, rows.size)
        assertEquals(listOf(1, 2, 3), rows[0])
        assertEquals(listOf(7), rows[2])
        assertEquals(items, rows.flatten())
    }

    @Test
    fun `空列表返回空行`() {
        assertTrue(gridRows(emptyList<Int>(), 3).isEmpty())
    }
}
