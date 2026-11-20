package cn.spw.kugou.lite.sidecar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N11：收藏状态的缓存。
 *
 * ## 缺陷
 *
 * `KugouApi.isFavorite` 每次调用都 `playlistSongs(cid, pageSize = 500)`
 * —— 拉整张「我喜欢」。收藏按钮一旦接进列表，每行的 `LaunchedEffect`
 * 都会触发一次全量拉取：20 行 = 20 次 500 条的请求，必然触发上游频控。
 *
 * 缓存后：「我喜欢」整张只在过期后拉一次，行内查询全部命中内存。
 */
class FavoriteCacheTest {

    private fun cache(ttl: Long = 30_000L): Pair<FavoriteCache, () -> Unit> {
        var now = 1_000L
        val c = FavoriteCache(ttlMs = ttl, now = { now })
        return c to { now += ttl + 1 }
    }

    @Test
    fun `从未加载时需要刷新`() {
        val (c, _) = cache()
        assertTrue(c.shouldRefresh(), "首次必须刷新")
    }

    @Test
    fun `刚加载过不需要刷新`() {
        val (c, _) = cache(ttl = 30_000L)
        c.put(setOf("A"))
        assertFalse(c.shouldRefresh(), "TTL 内不应重复拉整张歌单")
    }

    @Test
    fun `超过 TTL 后再次需要刷新`() {
        val (c, advance) = cache(ttl = 30_000L)
        c.put(setOf("A"))
        advance()
        assertTrue(c.shouldRefresh())
    }

    @Test
    fun `命中缓存返回已收藏`() {
        val (c, _) = cache()
        c.put(setOf("HASH1"))
        assertTrue(c.contains("HASH1"))
        assertFalse(c.contains("HASH2"))
    }

    @Test
    fun `未加载时一律返回未收藏`() {
        val (c, _) = cache()
        assertFalse(c.contains("ANY"), "没有数据时不能谎报已收藏")
    }

    @Test
    fun `取消收藏后立刻不再命中（不等 TTL）`() {
        val (c, _) = cache()
        c.put(setOf("A", "B"))
        c.setFavorite("A", false)
        assertFalse(c.contains("A"), "用户点了取消，界面必须立刻反映")
        assertTrue(c.contains("B"))
    }

    @Test
    fun `收藏后立刻命中（不等 TTL）`() {
        val (c, _) = cache()
        c.put(emptySet())
        c.setFavorite("NEW", true)
        assertTrue(c.contains("NEW"))
    }

    @Test
    fun `invalidate 后需要重新刷新`() {
        val (c, _) = cache()
        c.put(setOf("A"))
        c.invalidate()
        assertTrue(c.shouldRefresh())
    }

    @Test
    fun `大小写不一致时仍算已收藏`() {
        val (c, _) = cache()
        c.put(setOf("abc123"))
        assertTrue(c.contains("ABC123"), "hash 大小写来源不一致，应忽略大小写比对")
    }

    @Test
    fun `空 hash 一律不算已收藏`() {
        val (c, _) = cache()
        c.put(setOf("", "A"))
        assertFalse(c.contains(""), "空 hash 不能匹配到任何条目")
    }
}
