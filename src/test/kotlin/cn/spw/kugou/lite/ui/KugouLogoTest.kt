package cn.spw.kugou.lite.ui

import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 品牌图标的基本不变量。
 *
 * <p>几何本身由离线脚本生成（见 [KugouLogo] 的文档），这里只锁「结构没被改坏」：
 * 视口必须是 24×24、只有一条路径、并且确实是矢量路径而不是空壳。
 * 坐标范围在生成时已断言落在 `[0,24]`，用脚本复算比在 Kotlin 里解析
 * 内部 `PathNode` 更可靠（`PathNode` 是 compose 的 internal 类型）。</p>
 */
class KugouLogoTest {

    @Test
    fun `图标是 24x24 单路径矢量`() {
        val v = KugouLogo.Vector
        assertEquals("KugouLogo", v.name)
        assertEquals(24f, v.viewportWidth)
        assertEquals(24f, v.viewportHeight)
        assertEquals(24.dp, v.defaultWidth)
        assertEquals(24.dp, v.defaultHeight)

        val nodes = v.root.toList()
        assertEquals(1, nodes.size, "品牌图标是一条复合路径（5 段子路径），不该拆成多个 path")
        assertTrue(nodes.single() is VectorPath, "节点类型应为矢量路径")
    }
}
