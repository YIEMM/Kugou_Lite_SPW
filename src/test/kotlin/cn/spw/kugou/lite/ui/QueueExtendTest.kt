package cn.spw.kugou.lite.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 队列续拉判定的纯逻辑测试（用户反馈 #2 / #4）。
 *
 * ## 背景
 *
 * - **#2「播放整个歌单只自动加入 50 首」**：详情页只拉了第一页（pagesize=50），
 *   队列建好后播完就停，不会续拉。
 * - **#4「私人 FM 五首放完不自动续」**：FM 卡片一次只播 5 首，播完即止。
 *
 * 两者的共同点是「当前队列已放到末尾，需要拉下一批并追加」。
 * 判定条件抽在这里，避免再次出现「代码写了但没接上」的回归。
 */
class QueueExtendTest {

    // ------------------------------------------------------------ 歌单分页

    @Test
    fun `还有下一页时应续拉`() {
        assertTrue(shouldExtendPlaylist(loadedCount = 50, pageSize = 50, serverTotal = 320))
    }

    @Test
    fun `已加载完所有曲目不应再拉`() {
        assertFalse(shouldExtendPlaylist(loadedCount = 16, pageSize = 50, serverTotal = 16))
    }

    @Test
    fun `服务端总数为0时不续拉`() {
        // 元数据缺失（count=0）时无法判断，靠「本页是否拉满」兜底
        assertTrue(shouldExtendPlaylist(loadedCount = 50, pageSize = 50, serverTotal = 0))
        assertFalse(shouldExtendPlaylist(loadedCount = 10, pageSize = 50, serverTotal = 0))
    }

    @Test
    fun `本页未拉满即视为到底`() {
        // 返回 20 条 < pagesize 50 → 没有更多了
        assertFalse(shouldExtendPlaylist(loadedCount = 20, pageSize = 50, serverTotal = 999))
    }

    // ------------------------------------------------------------ 私人 FM

    @Test
    fun `FM 队列剩余不足阈值时应补货`() {
        assertTrue(shouldRefillFm(currentIndex = 3, queueSize = 5, threshold = 2))
    }

    @Test
    fun `FM 队列还充足时不补货`() {
        assertFalse(shouldRefillFm(currentIndex = 0, queueSize = 5, threshold = 2))
    }

    @Test
    fun `FM 播到最后一首必须补货`() {
        assertTrue(shouldRefillFm(currentIndex = 4, queueSize = 5, threshold = 2))
    }

    @Test
    fun `FM 空队列不补货`() {
        assertFalse(shouldRefillFm(currentIndex = 0, queueSize = 0, threshold = 2))
    }

    // ------------------------------------------------------------ 结果合并

    @Test
    fun `追加去重_按hash过滤已在队列的歌`() {
        val existing = listOf("h1", "h2", "h3")
        val incoming = listOf("h2", "h3", "h4", "h5")
        assertEquals(listOf("h4", "h5"), dedupeAppend(existing) { incoming }.let { incoming.filter { h -> h in it } })
    }

    @Test
    fun `追加去重_全部重复时返回空`() {
        val existing = listOf("h1", "h2")
        val incoming = listOf("h1", "h2")
        val fresh = dedupeAppend(existing) { incoming }
        assertTrue(fresh.isEmpty())
    }
}
