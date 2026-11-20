package cn.spw.kugou.lite.host

/**
 * 队列重建时的下标换算与 path 选择（N13）。
 *
 * ## 缺陷一：过滤后下标错位
 *
 * `playQueue` / `appendToQueue` 都按 `songs` 的原下标算 `idx`，但随后
 * 用 `mapIndexed { ... }.filterNotNull()` 建 `items`。一旦中间有条目被过滤
 * 掉，`setQueueList(ctrl, items, idx)` 的 `idx` 就指向**另一首歌** ——
 * 用户看到的是「切歌后播的不是点的那首」，或直接无声。
 *
 * ## 缺陷二：追加时当前曲目 path 恒为空
 *
 * `appendToQueue` 里写的是：
 *
 *     val path = if (i == idx) cachedLocalPath(s) ?: "" else cachedLocalPath(s) ?: ""
 *
 * 两个分支**完全相同**，等于没区分当前曲目。而流式播放的曲目并没有本地
 * 缓存（`cachedLocalPath` 返回 null），于是重建队列后当前项 path 为空串 ——
 * 宿主拿不到音频地址，正在播的歌会静音/重来。
 *
 * 正确做法：当前曲目优先用 [lastQueuedPathOf]（上次入队时宿主真正在用的
 * 地址），缓存路径只作兜底。
 */
object QueueIndexPolicy {

    /**
     * 把「原列表下标」换算成「过滤后列表下标」。
     *
     * @param kept 与 [originalIndex] 同一列表等长，true 表示该条进入了 items
     * @param originalIndex 过滤前的当前曲目下标
     * @return 过滤后的下标；`kept` 全为 false 时返回 0
     */
    fun remap(kept: List<Boolean>, originalIndex: Int): Int {
        if (kept.isEmpty()) return 0
        val from = originalIndex.coerceIn(0, kept.lastIndex)
        val total = kept.count { it }
        if (total == 0) return 0
        // 两种情形是同一个公式：
        // - 当前项被保留 → 它就是「它之前保留了几条」这个下标
        // - 当前项被过滤 → 落到它后面第一个保留项，下标同样是「它之前保留了几条」
        val before = kept.take(from).count { it }
        return before.coerceAtMost(total - 1)
    }

    /**
     * 重建队列时，第 [index] 项该用什么 path。
     *
     * @param isCurrent 是否是当前正在播放的曲目
     * @param lastQueuedPath 上次入队时宿主在用的地址（可能为空）
     * @param cachedPath 本地缓存路径（可能为 null）
     */
    fun pathFor(
        isCurrent: Boolean,
        lastQueuedPath: String?,
        cachedPath: String?,
    ): String {
        val cached = cachedPath.orEmpty()
        if (!isCurrent) return cached
        // 当前曲目：宿主正在用的地址优先。流式播放时它非空而缓存为空，
        // 用它才能保住正在播的音频源（这是 N13 的静默点）。
        val live = lastQueuedPath.orEmpty()
        return live.ifBlank { cached }
    }
}
