package cn.spw.kugou.lite.ui

import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.spw.kugou.lite.diag.Diag
import cn.spw.kugou.lite.sidecar.AlbumBrief
import cn.spw.kugou.lite.sidecar.Async
import cn.spw.kugou.lite.sidecar.DiscoverApi
import cn.spw.kugou.lite.sidecar.KugouApi
import cn.spw.kugou.lite.sidecar.Song
import cn.spw.kugou.lite.sidecar.rememberAsyncState

/**
 * 统一详情页 —— 合并原先 5 条详情路径。
 *
 * | detailType | 来源页面 | 数据 | 操作组 |
 * |---|---|---|---|
 * | `playlist` | 我的音乐 / 搜索歌单 / 广场 | `/playlist/track/all` | 播放全部 |
 * | `rank` | 排行榜列表 | `/rank/audio` | 播放全部 |
 * | `square` | 歌单广场 | 同 playlist（gid） | 播放全部 |
 * | `daily` | 每日推荐 | `/everyday/recommend` | 播放全部 |
 * | `discover` | 发现页区块「›」 | `DiscoverApi.*`（按 sectionId） | 播放全部 |
 * | `album` | 歌曲行专辑名 / 发现新碟 | `/album/songs` | 播放全部 |
 *
 * ## 为什么合并
 *
 * 旧实现里 `SongListPage`（歌单/榜单）与 `DiscoverDetailPage`（发现区块）
 * 是两套几乎一样的代码，改动要同步两处；专辑项点击还只记日志不跳转。
 * 合并后一个文件管全部详情，`PageNav.detailType` 决定标题与数据源。
 *
 * ## 表格列
 *
 * 统一用 [DataTableRow]（# / 封面 / 标题+歌手 / 专辑 / 时长）；
 * 内容宽度 < 440dp 隐藏专辑列（LS 断点，规格 §5.8）。
 * 榜单类型额外在歌曲行下方显示歌手（榜单数据无专辑名时自然为空）。
 */
@Composable
fun DetailPage() {
    val type = PageNav.detailType.ifBlank {
        // 兼容旧调用点（openRank/openPlaylist 已带 type，这里兜底）
        when {
            PageNav.detailRankId.isNotBlank() -> "rank"
            PageNav.detailPlaylistId.isNotBlank() -> "playlist"
            PageNav.detailSectionId.isNotBlank() -> "discover"
            PageNav.albumId.isNotBlank() -> "album"
            else -> "playlist"
        }
    }
    val rankId = PageNav.detailRankId
    val playlistId = PageNav.detailPlaylistId
    val sectionId = PageNav.detailSectionId
    val albumId = PageNav.albumId
    val title = PageNav.detailTitle.ifBlank { defaultTitle(type) }
    val declaredCount = PageNav.declaredCount

    val songs = rememberAsyncState<List<Song>>()
    val albums = rememberAsyncState<List<AlbumBrief>>()
    // 分页：以服务端声明的总数为准判断是否还有下一页。
    // 分页状态。
    //
    // ## 一次拉全（用户要求）
    //
    // 歌单详情不再「首屏只拉 50 首 + 手动加载更多」，而是**首屏就循环拉完**
    // （每页 100 条，最多 20 页 = 2000 首，足够覆盖常见歌单）。
    // 理由：用户明确要求「一下子全部加载出来」；分页交互对歌单浏览没价值，
    // 而且旧实现的「加载更多」按钮容易被忽略，看起来就像「歌单少了歌」。
    var page by remember { mutableStateOf(1) }
    var allSongs by remember { mutableStateOf(ArrayList<Song>()) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    /** 服务端声明的曲目总数（0 = 未知）。优先用它，其次用列表页带过来的 declaredCount。 */
    var serverTotal by remember { mutableStateOf(0) }
    /** 首页服务端返回的**原始**条数（含被版权屏蔽的占位项，用于分页判定）。 */
    var firstPageRawCount by remember { mutableStateOf(0) }
    /** 累计因版权屏蔽被丢弃的条目数（用于头部提示）。 */
    var droppedTotal by remember { mutableStateOf(0) }
    /** 点无版权歌曲时的提示文案（显示成一条横幅）。 */
    var playNotice by remember { mutableStateOf("") }
    /**
     * 已探测到的无版权曲目：`hash → true`。
     *
     * 判定只认**实际取流结果**（`media_privilege` 与可播性无关，实测反证见
     * [KugouApi.probePlayability] 的注释）。
     */
    var noCopyright by remember { mutableStateOf(emptyMap<String, Boolean>()) }

    LaunchedEffect(type, rankId, playlistId, sectionId, albumId) {
        page = 1
        allSongs = ArrayList()
        hasMore = true
        serverTotal = 0
        firstPageRawCount = 0
        droppedTotal = 0
        when (type) {
            "rank" -> songs.load { KugouApi.rankSongs(rankId) }
            "album" -> songs.load {
                val p = KugouApi.albumSongPage(albumId, page = 1, pageSize = ALL_AT_ONCE_PAGE_SIZE)
                // 接口失败必须显式报错：否则空列表会被渲染成「暂无歌曲」，
                // 用户以为专辑真的没歌（BUG-03 同类问题）。
                if (p.failed) error(LOAD_FAILED_MESSAGE)
                serverTotal = p.total
                firstPageRawCount = p.rawCount
                droppedTotal = p.droppedCount
                p.songs
            }
            "daily" -> songs.load { runCatching { DiscoverApi.dailyRecommend() }.getOrDefault(emptyList()) }
            "discover" -> when (sectionId) {
                "daily" -> songs.load { runCatching { DiscoverApi.dailyRecommend() }.getOrDefault(emptyList()) }
                "discover" -> songs.load { runCatching { DiscoverApi.homeDiscover() }.getOrDefault(emptyList()) }
                "newSongs" -> songs.load { runCatching { DiscoverApi.newSongs() }.getOrDefault(emptyList()) }
                "newAlbums" -> albums.load { runCatching { DiscoverApi.newAlbums() }.getOrDefault(emptyList()) }
                else -> songs.load { emptyList() }
            }
            // playlist / square：用大页一次拿尽可能多（listId 有值时走 /new 接口拿到完整曲目）
            else -> songs.load {
                val p = KugouApi.playlistSongPage(
                    globalCollectionId = playlistId,
                    page = 1,
                    pageSize = ALL_AT_ONCE_PAGE_SIZE,
                    listId = PageNav.numericListId,
                )
                // 同上：网络失败 ≠ 空歌单。详见 LOAD_FAILED_MESSAGE 的注释。
                if (p.failed) error(LOAD_FAILED_MESSAGE)
                serverTotal = p.total
                firstPageRawCount = p.rawCount
                droppedTotal = p.droppedCount
                p.songs
            }
        }
    }
    // 首页结果回填 + 判定是否还有下一页（首屏用大页，所以按大页口径算位置）
    LaunchedEffect(songs.value) {
        val s = songs.value
        if (s is Async.Success) {
            allSongs = ArrayList(s.value)
            val total = if (serverTotal > 0) serverTotal else declaredCount
            hasMore = when (type) {
                // 歌单有屏蔽占位：按「翻过的位置」判断
                "playlist", "square" -> hasMoreAfterPage(
                    pageNo = 1,
                    pageSize = ALL_AT_ONCE_PAGE_SIZE,
                    rawCount = firstPageRawCount,
                    serverTotal = total,
                )
                // 专辑没有屏蔽占位问题，按已解析条数判断
                "album" -> hasMorePages(s.value.size, total)
                else -> false
            }
        }
    }

    /**
     * 拉下一页并追加（手动按钮与自动续拉共用）。
     *
     * 自动续拉由 [TrackWatcher] 在「队列快播完」时回调，跑在监听线程上；
     * 所以这里自己负责防重入（[loadingMore]）。
     */
    fun requestNextPage() {
        if (loadingMore || !hasMore) return
        if (type != "playlist" && type != "square" && type != "album") return
        loadingMore = true
        val next = page + 1
        cn.spw.kugou.lite.sidecar.ApiExec.run(onResult = { res ->
            val pageData = res.getOrNull()
            val more = pageData?.songs.orEmpty()
            page = next
            // ⚠️ 总数只在**第 1 页**可信：实测第 2 页的 list_info.count 会变成 1
            // （服务端只对首页返回准确总数）。所以这里只在「还没有可信总数」时采纳，
            // 绝不每页覆盖 —— 否则第 2 页会把 122 覆盖成 1，分页当场停住。
            if (serverTotal <= 0 && pageData != null && pageData.total > 0) {
                serverTotal = pageData.total
            }
            if (pageData != null) droppedTotal += pageData.droppedCount
            // 去重：服务端在某些边界会重复返回上一页末尾的曲目，按 hash/albumAudioId 过滤
            val existingKeys = allSongs.map { s -> s.hash.ifBlank { s.albumAudioId } }.toHashSet()
            val fresh = more.filter { s ->
                val key = s.hash.ifBlank { s.albumAudioId }
                key.isNotBlank() && existingKeys.add(key)
            }
            val merged = ArrayList<Song>(allSongs.size + fresh.size).apply {
                addAll(allSongs); addAll(fresh)
            }
            if (fresh.isNotEmpty()) {
                allSongs = merged
                // ⚠️ 只在「本歌单确实正在播放」时才同步宿主队列。
                //
                // 用户反馈：打开歌单时播放列表会莫名刷新、播放被中断。
                // 根因是一次性拉全要走 requestNextPage，早期版本**每翻一页**
                // 都无条件调 appendToQueue 重建宿主队列 —— 用户只是在浏览
                // 歌单，播放队列却被替换了好几次。
                //
                // 判据：当前播放曲目 id 必须能在本歌单列表里找到。
                // 找不到就说明用户没在播这个歌单，纯加载数据即可。
                val curId = currentPlayingTrackId()
                val isPlayingThisPlaylist = curId.isNotBlank() &&
                    cn.spw.kugou.lite.host.PlaybackBridge.isPlayingFrom(merged)
                if (isPlayingThisPlaylist) {
                    runCatching {
                        cn.spw.kugou.lite.host.TrackWatcher.appendToQueue(fresh)
                        cn.spw.kugou.lite.host.PlaybackBridge.appendToQueue(
                            allSongs = merged,
                            currentTrackId = curId,
                            resolveStream = { s -> KugouApi.streamUrl(s) },
                        )
                    }.onFailure { Diag.warn("[detail] 追加队列失败：${it.message}") }
                } else {
                    Diag.info("[detail] 第 $next 页已加载，非当前播放歌单，不重建播放队列")
                }
            } else {
                allSongs = merged
            }
            // 判据（三件事分开，别混）：
            // - 「这页有没有数据」用**原始条数**（含被屏蔽占位，否则会被误判为到底）
            // - 「还有没有下一页」用**服务端声明总数** + 已翻页覆盖的位置
            val rawCount = pageData?.rawCount ?: more.size
            val total = if (serverTotal > 0) serverTotal else declaredCount
            hasMore = hasMoreAfterPage(
                pageNo = next,
                pageSize = ALL_AT_ONCE_PAGE_SIZE,
                rawCount = rawCount,
                serverTotal = total,
            )
            loadingMore = false
            Diag.info("[detail] 加载第 $next 页：${more.size} 首（累计 ${allSongs.size}，总数 $total，还有更多=$hasMore）")
            // 一次拉全：还有下一页就自动接着拉（用户明确要求「一下子全部加载出来」）
            if (hasMore && next < ALL_AT_ONCE_MAX_PAGES) {
                requestNextPage()
            }
        }) {
            if (type == "album") {
                KugouApi.albumSongPage(albumId, page = next, pageSize = ALL_AT_ONCE_PAGE_SIZE)
            } else {
                KugouApi.playlistSongPage(
                    globalCollectionId = playlistId,
                    page = next,
                    pageSize = ALL_AT_ONCE_PAGE_SIZE,
                    listId = PageNav.numericListId,
                )
            }
        }
    }

    // ⚠️ 这里**曾经**有「后台主动探测无版权」的逻辑，现已移除（实测教训）。
    //
    // 原因：探测要逐首发取流请求，连发 20+ 次会触发酷狗频控，
    // 全部返回 status=3；插件把这些「假失败」当成无版权标注，
    // 反而把本来能播的歌标成「无版权」。日志里 134 次「取流无地址」
    // 集中在几秒内，就是主动探测自己制造的。
    //
    // 现在的策略：**只按需判定** —— 用户点击播放时真的取流一次，
    // 失败且原因是版权类才标「无版权」并提示（见下面 onRowClick 与
    // BrowsePages.playSongsFromQueue 的 onUnplayable 回调）。
    // 这样零额外请求、不会频控、也不会误标。

    // 首屏拉完后，如果还有剩余页就自动接着拉（用户要求一次全量加载）
    LaunchedEffect(hasMore, allSongs.size) {
        if (hasMore && allSongs.isNotEmpty() && !loadingMore &&
            (type == "playlist" || type == "square" || type == "album")
        ) {
            requestNextPage()
        }
    }

    SaltScreen(title = title, onBackClick = { PageNav.backHome() }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showAlbum = maxWidth.value >= TABLE_ALBUM_MIN_WIDTH_DP
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                // ---------------------------------------------- 头部横幅
                // ⚠️ 展示必须用 allSongs，不能用 songs.value（用户反馈「还是显示 100 首」）。
            //
            // songs.value 只是**首屏第一页**的快照（100 首）；后续分页追加进 allSongs。
            // 早期这里用 songs.value 渲染表格和 meta 文案，结果：
            //   - 日志已经加载了 122 首
            //   - 界面却一直显示「歌单 · 100 首（声明 122 首，22 首暂不可用）」
            // allSongs 在首屏回填后与 songs.value 相同，所以这样就统一了。
            val list = allSongs.ifEmpty { (songs.value as? Async.Success)?.value.orEmpty() }
                val albumList = (albums.value as? Async.Success)?.value.orEmpty()
                val heroCover = list.firstOrNull()?.coverUrl(240)
                    ?: albumList.firstOrNull()?.coverAt(240)
                PageHero(
                    coverUrl = heroCover,
                    title = title,
                    meta = heroMeta(type, list.size, albumList.size, declaredCount, droppedTotal),
                ) {
                    if (list.isNotEmpty()) {
                        // 播放全部：自动剔除无版权曲目（用户需求）。
                        // 全被剔除时给提示，而不是建一个空队列。
                        ActionChip("▶ 播放全部") {
                            val playable = list.filter { noCopyright[it.hash] != true }
                            // BUG-56：这里过去**漏传** needMore —— 同一个歌单，
                            // 单点一首能一直播下去，点「播放全部」播到第一页末尾就停。
                            // 与行点击共用 supportsAutoExtend，两处判据不会再漂移。
                            val extend: (() -> Unit)? = if (supportsAutoExtend(type)) {
                                { requestNextPage() }
                            } else {
                                null
                            }
                            when {
                                playable.isEmpty() -> playNotice = "该列表所有歌曲都暂无版权"
                                playable.size < list.size -> {
                                    playNotice = "已跳过 ${list.size - playable.size} 首无版权歌曲"
                                    playSongsFromQueue(playable, 0, extend)
                                }
                                else -> playSongsFromQueue(playable, 0, extend)
                            }
                        }
                    }
                    if (type == "playlist" || type == "square") {
                        ActionChip("♥ 收藏") { Diag.info("[detail] 收藏歌单（入口占位）") }
                    }
                }

                // ---------------------------------------------- 内容
                // 无版权提示横幅（点无版权歌曲 / 播放全部跳过后显示）
                if (playNotice.isNotBlank()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .background(KugouTheme.colors.subtle, RoundedCornerShape(8.dp))
                            .clickable { playNotice = "" }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text3(
                            text = playNotice,
                            color = KugouTheme.colors.text,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                        Text3("✕", color = KugouTheme.colors.faint, maxLines = 1)
                    }
                }
                when {
                    type == "discover" && sectionId == "newAlbums" -> AlbumGridBlock(albums.value)
                    else -> {
                        SongTableBlock(
                            state = songs.value,
                            allSongs = allSongs,
                            showAlbum = showAlbum,
                            // 播放时把「自动续拉」能力交给监听器（用户反馈 #2）：
                            // 队列快播完时它会回调这里拉下一页并追加，无需用户手动点。
                            needMore = if (supportsAutoExtend(type)) {
                                { requestNextPage() }
                            } else {
                                null
                            },
                            onNotice = { playNotice = it },
                            noCopyrightHashes = noCopyright,
                            noCopyrightHashesCallback = { h ->
                                noCopyright = noCopyright + (h to true)
                            },
                        )
                        // 手动「加载更多」（与自动续拉共用同一个 requestNextPage）
                        if (hasMore && (type == "playlist" || type == "square" || type == "album")) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text3(
                                    text = if (loadingMore) "加载中…" else "加载更多",
                                    color = if (loadingMore) KugouTheme.colors.faint else KugouTheme.colors.text,
                                    modifier = Modifier.clickable(enabled = !loadingMore) { requestNextPage() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 当前正在播放的曲目 id（供追加队列时定位下标）。
 *
 * 读不到时返回空串 —— [cn.spw.kugou.lite.host.PlaybackBridge.appendToQueue]
 * 会把空 id 当成「找不到」从而退化为从第 1 首重建，不会崩。
 */
private fun currentPlayingTrackId(): String = runCatching {
    cn.spw.kugou.lite.host.TrackWatcher.currentTrackId()
}.getOrDefault("")

/** 标题兜底（PageNav 没给 title 时按类型给默认名）。 */
private fun defaultTitle(type: String): String = when (type) {
    "rank" -> "榜单"
    "square" -> "歌单"
    "daily" -> "每日推荐"
    "album" -> "专辑"
    "discover" -> "发现"
    else -> "歌单"
}

/**
 * 头部元信息文案。
 *
 * @param declared 列表页声明的曲目数（0 = 未知）
 * @param dropped 已被丢弃的条目数（版权屏蔽占位 `{"fileid":x,"shield":1}`，
 *        只有 fileid 没有任何歌曲信息，无法显示成有效行）
 */
private fun heroMeta(
    type: String,
    songCount: Int,
    albumCount: Int,
    declared: Int = 0,
    dropped: Int = 0,
): String {
    val base = when (type) {
        "album" -> if (songCount > 0) "专辑 · $songCount 首" else "专辑"
        "rank" -> if (songCount > 0) "榜单 · $songCount 首" else "榜单"
        "daily" -> if (songCount > 0) "每日推荐 · $songCount 首" else "每日推荐"
        "discover" -> if (albumCount > 0) "新碟 · $albumCount 张" else if (songCount > 0) "$songCount 首" else "发现"
        else -> if (songCount > 0) "歌单 · $songCount 首" else "歌单"
    }
    // 有版权受限曲目就如实说明，避免用户以为「歌单少了歌」
    return if (dropped > 0) {
        "$base（另有 $dropped 首因版权限制不可播放）"
    } else if (declared > songCount && songCount > 0) {
        "$base（声明 $declared 首，${declared - songCount} 首暂不可用）"
    } else {
        base
    }
}

/** 操作组小胶囊（中性底色，不用 accent）。 */
@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text3(text = label, color = KugouTheme.colors.text, maxLines = 1)
    }
}

/** 歌曲表格块：状态 + 行。
 *
 * @param allSongs 累计已加载（含分页追加），用于建播放队列
 * @param needMore 播放时的续拉回调，透传给 [playSongsFromQueue]
 */
@Composable
private fun SongTableBlock(
    state: Async<List<Song>>,
    allSongs: List<Song>,
    showAlbum: Boolean,
    needMore: (() -> Unit)? = null,
    onNotice: (String) -> Unit = {},
    noCopyrightHashes: Map<String, Boolean> = emptyMap(),
    /** 取流失败确认无版权时回调（把 hash 记进 map）。 */
    noCopyrightHashesCallback: (String) -> Unit = {},
) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> DetailStatus("正在加载…")
        is Async.Failure -> DetailStatus("加载失败：${s.message}")
        is Async.Success -> {
            val display = allSongs.ifEmpty { s.value }
            when {
                display.isEmpty() -> DetailStatus(
                    if (KugouApi.lastNeedsLogin) "需要登录酷狗账号" else "该列表暂无歌曲",
                )
                else -> SaltCardBlock {
                    display.forEachIndexed { index, song ->
                        val noCopyright = noCopyrightHashes[song.hash] == true
                        DataTableRow(
                            index = String.format(Locale.ROOT, "%02d", index + 1),
                            coverUrl = song.coverUrl(120),
                            title = song.title,
                            subtitle = song.singer,
                            album = song.albumName,
                            duration = song.durationText,
                            showAlbum = showAlbum,
                            // 无版权歌曲：点击时提示而不能播放（用户需求）
                            // 无版权歌曲：点击时提示而不能播放（用户需求）。
                            // 已探测到的直接提示；未探测的先实时试取流一次，
                            // 失败且原因是版权类就提示（比盲目相信接口字段准确）。
                            onRowClick = {
                                if (noCopyright) {
                                    onNotice("「${song.title}」暂无版权，无法播放")
                                } else {
                                    playSongsFromQueue(
                                        queue = display,
                                        index = index,
                                        needMore = needMore,
                                        onUnplayable = { bad, reason ->
                                        // 取流失败且确属版权问题 → 当场标为无版权并提示
                                        noCopyrightHashesCallback(bad.hash)
                                        onNotice("「${bad.title}」暂无版权，无法播放（$reason）")
                                        },
                                    )
                                }
                            },
                            badge = if (noCopyright) "无版权" else null,
                            cells = DataTableCells(
                                onArtist = if (song.artists.firstOrNull()?.id?.isNotBlank() == true) {
                                    { PageNav.openArtist(song.artists.first().id, song.artists.first().name) }
                                } else null,
                                onAlbum = if (!song.albumId.isBlank()) {
                                    { PageNav.openAlbum(song.albumId, song.albumName ?: "专辑") }
                                } else null,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 专辑网格块（发现页「新碟上架」详情）。 */
@Composable
private fun AlbumGridBlock(state: Async<List<AlbumBrief>>) {
    when (val s = state) {
        is Async.Idle, is Async.Loading -> DetailStatus("正在加载…")
        is Async.Failure -> DetailStatus("加载失败：${s.message}")
        is Async.Success -> {
            val list = s.value
            if (list.isEmpty()) {
                DetailStatus("暂无专辑")
            } else {
                SaltCardBlock {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        gridRows(list, DETAIL_GRID_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { album ->
                                    CoverGridCard(
                                        coverUrl = album.coverAt(300),
                                        title = album.name,
                                        subtitle = album.artist,
                                        modifier = Modifier.weight(1f).padding(4.dp),
                                    ) { PageNav.openAlbum(album.id, album.name) }
                                }
                                repeat(DETAIL_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 详情页专辑网格列数（内容区宽度稳定，固定三列）。 */
/**
 * 接口失败时的用户可见文案。
 *
 * **为什么不能沿用「该列表暂无歌曲」**：`KugouApi.get()` 网络失败返回 null，
 * 早期被折成空页，于是网络一抖用户就看到「该列表暂无歌曲」——
 * 会以为歌单被删了，而且没有任何重试提示（BUG-03）。
 *
 * 现在数据层用 `SongPage.failed` 把它区分开，UI 显示「加载失败」。
 */
private const val LOAD_FAILED_MESSAGE = "接口无响应，请检查网络后重试"

private const val DETAIL_GRID_COLUMNS = 3

/** 统一的外层卡片（保持 16dp 水平留白）。 */
@Composable
private fun SaltCardBlock(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
    }
}

/** 状态提示。 */
@Composable
private fun DetailStatus(text: String) {
    SaltCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text3(text, color = KugouTheme.colors.muted)
        }
    }
}
