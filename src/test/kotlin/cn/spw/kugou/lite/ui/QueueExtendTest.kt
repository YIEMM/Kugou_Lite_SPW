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
    fun `服务端总数为0时保守续拉直到空页`() {
        // 总数未知：本页有数据就继续尝试（详见 PlaylistPagingTest 的完整规则）
        assertTrue(shouldExtendPlaylist(loadedCount = 50, pageSize = 50, serverTotal = 0))
        assertTrue(shouldExtendPlaylist(loadedCount = 10, pageSize = 50, serverTotal = 0))
    }

    @Test
    fun `服务端少回条数时仍按总数判断`() {
        // ⚠️ 这条曾固化过 bug：早期用「本页是否拉满」判定，
        // 而服务端请求 50 常只回 48，导致第 49 首之后永远拉不到。
        // 正确行为：以总数 999 为准 → 48 < 999 → 继续拉。
        assertTrue(shouldExtendPlaylist(loadedCount = 48, pageSize = 50, serverTotal = 999))
        // 只有总数已知且已达上限才停
        assertFalse(shouldExtendPlaylist(loadedCount = 48, pageSize = 50, serverTotal = 48))
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

    /**
     * BUG-56：「播放全部」漏传 `needMore`，长歌单播到第一页末尾就停。
     *
     * 根因是判据在两个调用点各写一遍，其中一处漏了。现在共用
     * [supportsAutoExtend]，这条测试锁住「哪些类型该续拉」。
     */
    @Test
    fun `分页类型的详情页应支持自动续拉`() {
        assertTrue(supportsAutoExtend("playlist"), "歌单有分页，必须续拉")
        assertTrue(supportsAutoExtend("square"), "广场歌单同样分页")
        assertTrue(supportsAutoExtend("album"), "专辑也走 requestNextPage")

        assertFalse(supportsAutoExtend("rank"), "榜单一次性返回，没有下一页")
        assertFalse(supportsAutoExtend("daily"), "每日推荐没有分页")
        assertFalse(supportsAutoExtend("discover"), "发现区块没有分页")
        assertFalse(supportsAutoExtend(""))
    }
}
