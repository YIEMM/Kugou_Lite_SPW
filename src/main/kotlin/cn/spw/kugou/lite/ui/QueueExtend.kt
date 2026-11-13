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

/**
 * 歌单详情「一次拉全」时每页请求的条数。
 *
 * 实测 `/playlist/track/all` 与 `/playlist/track/all/new` 都接受 100–300，
 * 用 100 是折中：既能一趟拿完常见歌单（<100 首），又不至于单次响应过大。
 */
internal const val ALL_AT_ONCE_PAGE_SIZE = 100

/** 一次拉全时最多翻多少页（防御性上限，100×20 = 2000 首）。 */
internal const val ALL_AT_ONCE_MAX_PAGES = 20

/**
 * 歌单详情最多探测多少首「能否播放」。
 *
 * 每首一次取流请求（约 100–300ms），全探 122 首要 15–40 秒，
 * 所以限量。超出的曲目保持未知（不标「无版权」），点击时再实时判断。
 */
internal const val PLAYLIST_PROBE_LIMIT = 20

/** 私人 FM 队列剩余这么多首时开始补货。 */
internal const val FM_REFILL_THRESHOLD = 2

/**
 * 这类详情页是否支持「队列快播完自动续拉」。
 *
 * <p>**为什么抽成函数**（BUG-56）：条件原来在两个调用点各写一遍 ——
 * 行点击传了 `needMore`，而「播放全部」漏了。于是同一个歌单，
 * 单点一首能一直播下去，点「播放全部」播到第一页末尾就停。
 * 两处共用同一个判据，才不会再漂移。</p>
 *
 * <p>榜单 / 每日推荐 / 发现区块本来就是一次性列表，没有分页，返回 false。</p>
 */
internal fun supportsAutoExtend(type: String): Boolean =
    type == "playlist" || type == "square" || type == "album"

/**
 * 翻完第 [pageNo] 页后，是否还有下一页。
 *
 * ## 为什么需要「位置」而不是「条数」（用户反馈的严重 bug）
 *
 * 酷狗对**版权屏蔽**曲目会在列表里留占位对象（`{"fileid":95,"shield":1}`），
 * 解析时被过滤。于是「请求 50 条 → 解析出 48 首」是常态。
 *
 * 若用解析后条数判断（48 < 50 → 以为到底），**第 49 首之后的歌永远拉不到**。
 *
 * 正确做法：`begin_idx` 是按**原始位置**分页的，所以只要
 * 「已翻过的位置」（`pageNo × pageSize`）还小于服务端声明的总数，
 * 就说明后面还有没翻到的位置 —— 被屏蔽的那几首仍占位置，不影响判断。
 *
 * @param pageNo 刚翻完的是第几页（从 1 开始）
 * @param pageSize 每页请求大小
 * @param rawCount 该页服务端返回的**原始**条数（含屏蔽占位）。0 = 服务端真没数据了
 * @param serverTotal 服务端声明的总数；0 = 未知
 */
internal fun hasMoreAfterPage(
    pageNo: Int,
    pageSize: Int,
    rawCount: Int,
    serverTotal: Int,
): Boolean {
    if (pageNo <= 0 || pageSize <= 0) return false
    // 服务端这页一条都没返回 → 真到底了
    if (rawCount <= 0) return false
    // 有明确总数：已翻过的位置覆盖到总数就算到底
    if (serverTotal > 0) return pageNo * pageSize < serverTotal
    // 总数未知：只要这页还有数据就继续（下一页为空时自然停）
    return true
}

/**
 * 是否还有下一页 —— **以服务端声明的总数为准**。
 *
 * 这个函数按「已解析条数」判断，适用于**没有屏蔽占位**的场景
 * （如专辑/搜索结果）。歌单请用 [hasMoreAfterPage]。
 */
internal fun hasMorePages(loadedCount: Int, serverTotal: Int): Boolean {
    if (loadedCount < 0) return false
    if (serverTotal < 0) return false
    // 本页什么都没拿到 → 到底了（防死循环）
    if (loadedCount == 0) return false
    // 有明确总数：以它为准
    if (serverTotal > 0) return loadedCount < serverTotal
    // 总数未知：本页有数据就保守地再试一页，直到拿到空页
    return true
}

/**
 * 歌单详情是否还应该续拉下一页。
 *
 * @param loadedCount 当前已加载条数
 * @param pageSize 每页大小（仅用于文档与日志，不参与判定 —— 服务端会少回）
 * @param serverTotal 服务端声明的总数（`/user/playlist` 的 count；0 = 未提供）
 */
internal fun shouldExtendPlaylist(loadedCount: Int, pageSize: Int, serverTotal: Int): Boolean {
    if (pageSize <= 0) return false
    // 与详情页共用同一套判据，避免两处漂移
    return hasMorePages(loadedCount, serverTotal)
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
