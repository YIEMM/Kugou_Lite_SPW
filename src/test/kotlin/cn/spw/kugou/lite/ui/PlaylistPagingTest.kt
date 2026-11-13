package cn.spw.kugou.lite.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 分页续拉判定的回归测试 —— 用户反馈的「超过 49/50 首只能拿到 48 首」。
 *
 * ## 这个 bug 是什么
 *
 * 早期用「本页条数是否 >= pagesize」判断还有没有下一页：
 *
 * ```kotlin
 * hasMore = page.size >= 50   // 服务端只回 48 条 → false → 永远停在第 1 页
 * ```
 *
 * 但**酷狗服务端对 `pagesize` 有内部上限**，请求 50 可能只返回 48 条。
 * 于是 `48 >= 50` 为假，`hasMore` 变 false，**第 49 首之后的歌永远拉不到**。
 * 表现就是「50 首的歌单只能看到 48 首」。
 *
 * ## 正确判据
 *
 * 以**服务端声明的总数**（`list_info.count` / 歌单列表的 `count`）为准：
 * 已加载数 < 总数 → 还有下一页。只有在总数未知（0）时才退回
 * 「本页是否有数据」判断 —— 而**绝不能用「本页是否拉满」**。
 */
class PlaylistPagingTest {

    /** 服务端只回 48 条（请求 50），但歌单实际有 320 首 → 必须继续拉。 */
    @Test
    fun `服务端少回 2 条时仍要续拉`() {
        assertTrue(hasMorePages(loadedCount = 48, serverTotal = 320))
    }

    /** 50 首的歌单：拿到 48 首，还有 2 首没拿 → 继续拉。 */
    @Test
    fun `50首歌单拿到48首时仍要续拉`() {
        assertTrue(hasMorePages(loadedCount = 48, serverTotal = 50))
    }

    /** 49 首的歌单拿到 48 首 → 还有 1 首。 */
    @Test
    fun `49首歌单拿到48首时仍要续拉`() {
        assertTrue(hasMorePages(loadedCount = 48, serverTotal = 49))
    }

    /** 拿到全部 18 首、声明也是 18 → 到底了。 */
    @Test
    fun `已拿到全部时不续拉`() {
        assertFalse(hasMorePages(loadedCount = 18, serverTotal = 18))
    }

    /** 拿到比声明更多（服务端补充曲目）→ 到底了。 */
    @Test
    fun `超过声明数时视为到底`() {
        assertFalse(hasMorePages(loadedCount = 20, serverTotal = 18))
    }

    /** 总数未知（0）但本页有数据 → 保守地继续尝试，直到拿到空页。 */
    @Test
    fun `总数未知时有数据就继续尝试`() {
        assertTrue(hasMorePages(loadedCount = 48, serverTotal = 0))
    }

    /** 总数未知且本页为空 → 到底了（避免死循环）。 */
    @Test
    fun `总数未知且空页时停止`() {
        assertFalse(hasMorePages(loadedCount = 0, serverTotal = 0))
        assertFalse(hasMorePages(loadedCount = 0, serverTotal = 50))
    }

    /** 防御：负数/非法值不应导致死循环。 */
    @Test
    fun `非法入参不续拉`() {
        assertFalse(hasMorePages(loadedCount = -1, serverTotal = 100))
        assertFalse(hasMorePages(loadedCount = 100, serverTotal = -5))
    }

    // ------------------------------------------------------------ 自动续播

    /** 队列快播完时，只要还可能存在更多曲目就应续拉（与上面同一判据）。 */
    @Test
    fun `续播判定复用同一套分页规则`() {
        assertTrue(shouldExtendPlaylist(loadedCount = 48, pageSize = 50, serverTotal = 320))
        assertFalse(shouldExtendPlaylist(loadedCount = 48, pageSize = 50, serverTotal = 48))
    }

    // ---------------------------------------------------- 屏蔽占位导致的 48 首

    /**
     * **核心回归**：122 首歌单，第 1 页请求 50 条 → 服务端回 50 条，
     * 其中 2 条是被版权屏蔽的占位（`{"fileid":95,"shield":1}`），
     * 解析后只有 48 首。旧判据「48 >= 50」为 false → 误判到底 →
     * 用户只能看到 48 首。正确行为：50×1 < 122 → 继续翻页。
     */
    @Test
    fun `屏蔽占位导致解析48首时仍要续拉`() {
        assertTrue(
            hasMoreAfterPage(pageNo = 1, pageSize = 50, rawCount = 50, serverTotal = 122),
        )
    }

    /** 翻到第 3 页：3×50=150 >= 122 → 到底了。 */
    @Test
    fun `翻够页数后停止`() {
        assertFalse(
            hasMoreAfterPage(pageNo = 3, pageSize = 50, rawCount = 22, serverTotal = 122),
        )
    }

    /** 恰好整除：4×50=200，总数 200 → 到底。 */
    @Test
    fun `正好整除时停止`() {
        assertFalse(hasMoreAfterPage(pageNo = 4, pageSize = 50, rawCount = 50, serverTotal = 200))
        assertTrue(hasMoreAfterPage(pageNo = 3, pageSize = 50, rawCount = 50, serverTotal = 200))
    }

    /** 服务端某页返回空 → 无论总数多少都停（防死循环）。 */
    @Test
    fun `空页立即停止`() {
        assertFalse(hasMoreAfterPage(pageNo = 2, pageSize = 50, rawCount = 0, serverTotal = 320))
    }

    /** 总数未知：只要还有数据就继续。 */
    @Test
    fun `总数未知时有数据就继续`() {
        assertTrue(hasMoreAfterPage(pageNo = 1, pageSize = 50, rawCount = 50, serverTotal = 0))
    }

    /** 非法入参不续拉。 */
    @Test
    fun `非法页码不续拉`() {
        assertFalse(hasMoreAfterPage(pageNo = 0, pageSize = 50, rawCount = 50, serverTotal = 100))
        assertFalse(hasMoreAfterPage(pageNo = 1, pageSize = 0, rawCount = 50, serverTotal = 100))
    }

    /**
     * **核心回归 2**：服务端只在第 1 页返回准确总数。
     *
     * 实测 122 首歌单：
     * - 第 1 页 `list_info.count = 122`
     * - 第 2 页 `list_info.count = 1`（服务端 bug）
     *
     * 若每页都用本页 total 覆盖，第 2 页会把 122 覆盖成 1，
     * 分页当场判 `100 < 1 = false` → 只拉到 97 首就停。
     *
     * 所以 DetailPage 只在首次拿到 total 时采纳（见该处注释），
     * 这里的测试钉住「沿用首页总数」后的正确判定。
     */
    @Test
    fun `沿用首页总数时能翻到最后一页`() {
        // 第 2 页：翻过 100 个位置，总数仍是首页的 122 → 还有 22 首
        assertTrue(hasMoreAfterPage(pageNo = 2, pageSize = 50, rawCount = 50, serverTotal = 122))
        // 第 3 页：150 >= 122 → 到底
        assertFalse(hasMoreAfterPage(pageNo = 3, pageSize = 50, rawCount = 22, serverTotal = 122))
        // 若误用第 2 页的异常 total=1，会错误停止 —— 这个断言记录该陷阱
        assertFalse(hasMoreAfterPage(pageNo = 2, pageSize = 50, rawCount = 50, serverTotal = 1))
    }
}
