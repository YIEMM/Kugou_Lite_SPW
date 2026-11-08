package cn.spw.kugou.lite.ui

/**
 * 队列续拉的纯逻辑（用户反馈 #2 / #4）。
 *
 * ## 为什么需要
 *
 * - 歌单详情只拉第一页（`pagesize = 50`），播完 50 首就停
 * - 私人 FM 一次只取 5 首，播完即止
 *
 * 两者都需要「队列快到末尾时自动续拉下一批并追加」。
 * 判定条件放在这里做成纯函数，UI 只消费结果 —— 便于单测。
 */

/** 歌单分页默认页大小（与 `KugouApi.playlistSongs` 一致）。 */
internal const val PLAYLIST_PAGE_SIZE = 50

/** 私人 FM 队列剩余这么多首时开始补货。 */
internal const val FM_REFILL_THRESHOLD = 2

/**
 * 歌单详情是否还应该续拉下一页。
 *
 * @param loadedCount 当前已加载条数
 * @param pageSize 每页大小
 * @param serverTotal 服务端声明的总数（`/user/playlist` 的 count；0 = 未提供）
 *
 * 规则：
 * 1. 本页没拉满（`loadedCount % pageSize != 0`）→ 到底了
 * 2. 服务端给了总数且已够 → 到底了
 * 3. 否则还能拉
 *
 * 注意服务器**实际返回可能少于声明的 count**（下架/失效曲目不返回），
 * 所以不能只信 `serverTotal`，必须以「本页是否拉满」为主判据。
 */
internal fun shouldExtendPlaylist(loadedCount: Int, pageSize: Int, serverTotal: Int): Boolean {
    if (loadedCount <= 0) return false
    if (pageSize <= 0) return false
    // 本页没拉满 → 服务端已经没有更多了
    if (loadedCount % pageSize != 0) return false
    // 服务端给了总数且已加载够 → 到底
    if (serverTotal > 0 && loadedCount >= serverTotal) return false
    return true
}

/**
 * 私人 FM 是否该补货：当前索引接近队列末尾时补。
 *
 * @param currentIndex 正在播放的下标
 * @param queueSize 当前队列长度
 */
internal fun shouldRefillFm(currentIndex: Int, queueSize: Int, threshold: Int = FM_REFILL_THRESHOLD): Boolean {
    if (queueSize <= 0) return false
    if (currentIndex < 0) return false
    return currentIndex >= queueSize - threshold
}

/**
 * 追加去重：从待追加列表里滤掉已在队列中的歌（按 hash / albumAudioId 判重）。
 *
 * @return 可以安全追加的新歌（保序）
 */
internal fun dedupeAppend(existingKeys: List<String>, incoming: () -> List<String>): List<String> {
    val seen = existingKeys.toHashSet()
    return incoming().filter { it.isNotBlank() && seen.add(it) }
}
