package cn.spw.kugou.lite.sidecar

import java.util.concurrent.atomic.AtomicReference

/**
 * 「我喜欢」状态的进程内缓存（N11）。
 *
 * ## 为什么需要它
 *
 * [KugouApi.isFavorite] 的实现是 `playlistSongs(cid, pageSize = 500)` ——
 * 为了回答「这一首收藏了吗」，把整张「我喜欢」拉了下来。
 *
 * 收藏按钮接进列表后，每行一个 `LaunchedEffect`：20 行就是 20 次 500 条的
 * 请求，既慢又必然触发上游频控。缓存后整张只在 TTL 过期时拉一次。
 *
 * ## 并发
 *
 * 状态整体放在一个不可变快照里用 [AtomicReference] 替换，读取不加锁；
 * 写操作用 `synchronized` 串行化，避免「读-改-写」丢更新。
 */
class FavoriteCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private data class Snapshot(val hashes: Set<String>, val loadedAt: Long)

    private val state = AtomicReference<Snapshot?>(null)

    /** 是否该重新拉一次整张「我喜欢」：从没拉过，或已超过 TTL。 */
    fun shouldRefresh(): Boolean {
        val s = state.get() ?: return true
        return now() - s.loadedAt >= ttlMs
    }

    /** 用刚拉到的整张列表覆盖缓存。 */
    fun put(hashes: Set<String>) {
        state.set(Snapshot(hashes, now()))
    }

    /** 查一首是否已收藏。缓存未加载或 hash 为空时返回 false（不谎报）。 */
    fun contains(hash: String): Boolean {
        if (hash.isBlank()) return false
        // 大小写不敏感：原实现用 equals(ignoreCase = true)，hash 与 albumAudioId
        // 的大小写来源不完全一致，精确匹配会误判成「未收藏」。
        return state.get()?.hashes?.any { it.equals(hash, ignoreCase = true) } ?: false
    }

    /**
     * 用户点了收藏/取消：就地更新缓存，不等 TTL。
     *
     * 不做这一步的话，用户点完 ♡ 后必须在 30 秒后才能看到 ♥，
     * 期间再点一次会发一个方向相反的请求。
     */
    @Synchronized
    fun setFavorite(hash: String, favorite: Boolean) {
        if (hash.isBlank()) return
        val cur = state.get() ?: Snapshot(emptySet(), now())
        val next = if (favorite) cur.hashes + hash else cur.hashes - hash
        state.set(Snapshot(next, cur.loadedAt))
    }

    /** 作废缓存（退出登录、切换账号时调用）。 */
    fun invalidate() {
        state.set(null)
    }

    companion object {
        /** 30 秒：足够挡住列表的成批查询，又不至于让收藏状态明显过期。 */
        const val DEFAULT_TTL_MS = 30_000L
    }
}
